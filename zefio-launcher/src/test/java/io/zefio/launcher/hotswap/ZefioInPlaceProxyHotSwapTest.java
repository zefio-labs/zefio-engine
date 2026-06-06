package io.zefio.launcher.hotswap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ZefioInPlaceProxyHotSwapTest {

    private static final Logger log = LoggerFactory.getLogger(ZefioInPlaceProxyHotSwapTest.class);

    private static final String REDIS_HOST = "e000.bond";
    private static final int REDIS_PORT = 6379;
    private static final int MOCK_UPSTREAM_PORT = 51109;
    private static final int FIXED_INGRESS_PORT = 51009;

    private static JedisPool jedisPool;
    private static HttpServer mockUpstreamServer;
    private static ObjectMapper objectMapper;
    private static ExecutorService loadGeneratorExecutor;

    private static final AtomicInteger totalSentRequests = new AtomicInteger(0);
    private static final AtomicInteger totalSuccessResponses = new AtomicInteger(0);
    private static final AtomicInteger totalFailedResponses = new AtomicInteger(0);
    private static final AtomicInteger mockUpstreamRequests = new AtomicInteger(0);
    private static final AtomicBoolean isLoadRunning = new AtomicBoolean(false);

    private static class HttpResponse8 {
        private final int statusCode;
        private final String body;

        public HttpResponse8(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public int statusCode() { return statusCode; }
        public String body() { return body; }
    }

    @BeforeAll
    static void setupAll() throws IOException {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(15);
        jedisPool = new JedisPool(poolConfig, REDIS_HOST, REDIS_PORT);

        mockUpstreamServer = HttpServer.create(new InetSocketAddress(MOCK_UPSTREAM_PORT), 0);
        mockUpstreamServer.createContext("/service/", exchange -> {
            mockUpstreamRequests.incrementAndGet();
            try { TimeUnit.MILLISECONDS.sleep(50); } catch (InterruptedException ignored) {}
            byte[] response = "MOCK_UPSTREAM_RESPONSE_OK".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        mockUpstreamServer.setExecutor(Executors.newCachedThreadPool());
        mockUpstreamServer.start();

        objectMapper = new ObjectMapper();
        loadGeneratorExecutor = Executors.newFixedThreadPool(10);

        log.info("🏁 In-Place Proxy Test Environment Ready. fixed Ingress Port: {}", FIXED_INGRESS_PORT);
    }

    @AfterAll
    static void teardownAll() {
        isLoadRunning.set(false);
        if (mockUpstreamServer != null) mockUpstreamServer.stop(0);
        if (jedisPool != null) jedisPool.close();
        if (loadGeneratorExecutor != null) loadGeneratorExecutor.shutdownNow();
    }

    @Test
    @Order(1)
    @DisplayName("🔥 SEDA 큐 고부하 적재 상황 중 In-Place Proxy (포트 고정형 라우터 교체) 테스트")
    void verifyInPlaceProxyHotSwapUnderLoad() throws Exception {
        isLoadRunning.set(true);

        // 1. Initiate non-stop transaction load towards the FIXED_INGRESS_PORT without port modification
        for (int i = 0; i < 8; i++) {
            loadGeneratorExecutor.submit(() -> {
                while (isLoadRunning.get()) {
                    try {
                        totalSentRequests.incrementAndGet();
                        String trxId = UUID.randomUUID().toString().replace("-", "");
                        byte[] rawPayload = buildFixedStandardPayload(trxId, "IN_PLACE_STRESS_DATA");

                        HttpResponse8 response = sendTestRawTransaction(FIXED_INGRESS_PORT, rawPayload);
                        if (response.statusCode() == 200) {
                            totalSuccessResponses.incrementAndGet();
                        } else {
                            totalFailedResponses.incrementAndGet();
                        }
                    } catch (IOException e) {
                        // In the In-Place architecture model, any exception triggered here violates zero-downtime compliance
                        totalFailedResponses.incrementAndGet();
                    }
                }
            });
        }

        TimeUnit.SECONDS.sleep(2); // Wait to induce active queue backlog saturation

        // 2. Spin up a background listener thread to capture the asynchronous deployment callback status
        String deployId = UUID.randomUUID().toString();
        CountDownLatch statusLatch = new CountDownLatch(1);
        final String[] swapResultStatus = new String[1];

        Thread statusSubscriber = new Thread(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        try {
                            Map<String, Object> responseMap = objectMapper.readValue(message, Map.class);
                            if (deployId.equals(responseMap.get("deployId"))) {
                                swapResultStatus[0] = (String) responseMap.get("status");
                                statusLatch.countDown();
                                this.unsubscribe();
                            }
                        } catch (Exception ignored) {}
                    }
                }, "zefio:deploy:status");
            }
        });
        statusSubscriber.setDaemon(true);
        statusSubscriber.start();

        // 3. Assemble the fully-flattened target blueprint keeping port 51009 completely fixed
        String updatedYaml = buildTestingYaml(FIXED_INGRESS_PORT);
        Map<String, Object> commandMessage = new HashMap<>();
        commandMessage.put("targetGroup", "main");
        commandMessage.put("action", "hot-reload");
        commandMessage.put("deployId", deployId);

        // Aligned Packaging: Map distinct segregated attributes matching the DP agent listener contract
        Map<String, Object> payload = new HashMap<>();
        payload.put("flowsYaml", updatedYaml); // Changed from 'yaml' to 'flowsYaml' to match DP decoupling
        payload.put("telegrams", new HashMap<>()); // Transmit empty context map to simulate standalone test boundary
        commandMessage.put("payload", payload);

        try (Jedis jedis = jedisPool.getResource()) {
            String jsonPayload = objectMapper.writeValueAsString(commandMessage);
            log.info("🔥 [In-Place Proxy] Injecting Valve Swap Signal to Redis. Port remains 51009.");
            jedis.publish("zefio:command", jsonPayload);
        }

        boolean received = statusLatch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "In-Place Hot-Swap Context Timeout.");
        assertEquals("SUCCESS", swapResultStatus[0]);

        // 4. Retain observation window to verify that absolutely no socket drop leaks occur post-swap
        TimeUnit.SECONDS.sleep(3);

        // 💡 Signal the load threads to stop generating traffic
        isLoadRunning.set(false);

        // 💡 [CRITICAL FIX] Active Drain Phase: Block until all inflight transactions are completely settled
        long drainStartTime = System.currentTimeMillis();
        while ((totalSuccessResponses.get() + totalFailedResponses.get() < totalSentRequests.get())
                && (System.currentTimeMillis() - drainStartTime < 5000)) {
            TimeUnit.MILLISECONDS.sleep(10);
        }

        log.info("==========================================================================");
        log.info("📊 IN-PLACE PROXY STRESS REPORT");
        log.info("==========================================================================");
        log.info("Fired Transactions    : {}", totalSentRequests.get());
        log.info("Success Settled (200) : {}", totalSuccessResponses.get());
        log.info("Refused/Dropped Loss  : {}", totalFailedResponses.get());
        log.info("==========================================================================");

        // Precise Verification: Total failed responses must be zero to confirm absolute zero-downtime status
        assertEquals(0, totalFailedResponses.get(), "🚨 CRITICAL: In-Place swap dropped connection during flight!");

        // 💡 Exact Symmetrical Validation: Compare total transmitted requests against total settled successful responses
        assertEquals(totalSentRequests.get(), totalSuccessResponses.get(), "Data Plane transmission mismatch.");
    }

    private static byte[] buildFixedStandardPayload(String trxId, String varData) {
        byte[] dummyHeader = new byte[64];
        Arrays.fill(dummyHeader, (byte) ' ');
        byte[] trxIdBytes = trxId.getBytes(StandardCharsets.UTF_8);
        byte[] varDataBytes = varData.getBytes(StandardCharsets.UTF_8);
        int bodyLength = dummyHeader.length + trxIdBytes.length + varDataBytes.length;
        String lengthStr = String.format("%08d", bodyLength);
        byte[] framingHeader = lengthStr.getBytes(StandardCharsets.UTF_8);
        byte[] fullPacket = new byte[framingHeader.length + bodyLength];
        System.arraycopy(framingHeader, 0, fullPacket, 0, framingHeader.length);
        System.arraycopy(dummyHeader, 0, fullPacket, framingHeader.length, dummyHeader.length);
        System.arraycopy(trxIdBytes, 0, fullPacket, framingHeader.length + dummyHeader.length, trxIdBytes.length);
        System.arraycopy(varDataBytes, 0, fullPacket, framingHeader.length + dummyHeader.length + trxIdBytes.length, varDataBytes.length);
        return fullPacket;
    }

    private HttpResponse8 sendTestRawTransaction(int targetPort, byte[] rawPayload) throws IOException {
        URL url = new URL("http://127.0.0.1:" + targetPort + "/");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "text/plain");
        conn.setRequestProperty("Connection", "close");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(5000);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(rawPayload);
            os.flush();
        }
        int statusCode = conn.getResponseCode();
        InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder content = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) content.append(line);
            }
        }
        return new HttpResponse8(statusCode, content.toString());
    }

    /**
     * Refactored Blueprint Builder: Completely purges 'profile' references and 'refErrorHandler' tags.
     * Fully mirrors the inline expanded execution specifications sent by the production Control Plane.
     */
    private static String buildTestingYaml(int port) {
        return "flows:\n" +
                "  - name: inbound\n" +
                "    label: \"9_HTTP_TO_HTTP_Massive\"\n" +
                "    options:\n" +
                "      threadPool:\n" +
                "        corePoolSize: 2\n" +
                "        maxPoolSize: 5\n" +
                "        queueCapacity: 0\n" +
                "      cpuQueue:\n" +
                "        capacity: 10\n" +
                "      ioQueue:\n" +
                "        capacity: 10\n" +
                "    ingress:\n" +
                "      name: from\n" +
                "      label: \"HTTP Inbound (" + port + ")\"\n" +
                "      type: HttpIngress\n" +
                "      telegram: fixed-standard\n" +
                "      exchangePattern: RequestReply\n" +
                "      config:\n" +
                "        port: " + port + "\n" +
                "        requestEncoding: utf-8\n" +
                "        responseEncoding: utf-8\n" +
                "        responseContentType: text/plain\n" +
                "    steps:\n" +
                "      - name: to\n" +
                "        label: \"Target Request (HTTP_Massive)\"\n" +
                "        type: HttpUpstream\n" +
                "        telegram: fixed-standard\n" +
                "        exchangePattern: RequestReply\n" +
                "        retry:\n" +
                "          enabled: false\n" +
                "        config:\n" + // Inlined value injection directly reflecting original flattened config parameters
                "          workThreadCount: 8\n" +
                "          connectTimeout: 3000\n" +
                "          soKeepAlive: false\n" +
                "          tcpNoDelay: true\n" +
                "          soReUseAddr: true\n" +
                "          readTimeout: 5000\n" +
                "          transactionTimeoutMillis: 10000\n" +
                "          responseMatchingType: session\n" +
                "          keepAlive: false\n" +
                "          poolMaxSize: 0\n" +
                "          onceMaxRetries: 3\n" +
                "          onceTryTimeoutMillis: 2000\n" +
                "          onceBackoffDelayMillis: 500\n" +
                "          host: localhost\n" +
                "          port: 51109\n" +
                "          requestContentType: text/plain\n" +
                "          requestHttpMethod: POST\n" +
                "          requestPath: service/\n" +
                "          requestEncoding: utf-8\n" +
                "          responseEncoding: utf-8\n" +
                "    on-error:\n" + // Fixed Compliance: Pure inline steps structure avoiding deprecated reference calls
                "      - error-type: ANY\n" +
                "        steps:\n" +
                "          - name: central-fixed-error\n" +
                "            type: FixedFaultHandler\n" +
                "            label: \"Common Error Handling\"\n" +
                "            config:\n" +
                "              errorCodeRules: [ ]\n" +
                "              valueOverrides: [ ]\n" +
                "              messageRule: null\n";
    }
}

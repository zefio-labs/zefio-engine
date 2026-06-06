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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ZefioHotSwapIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ZefioHotSwapIntegrationTest.class);

    private static final String REDIS_HOST = "e000.bond";
    private static final int REDIS_PORT = 6379;
    private static final int MOCK_UPSTREAM_PORT = 51109;

    private static final int ORIGINAL_INGRESS_PORT = 51009;
    private static final int NEW_INGRESS_PORT = 51010;

    private static JedisPool jedisPool;
    private static HttpServer mockUpstreamServer;
    private static ObjectMapper objectMapper;
    private static ExecutorService trafficExecutor;

    private static final AtomicInteger mockRequestCounter = new AtomicInteger(0);

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
        poolConfig.setMaxTotal(10);
        jedisPool = new JedisPool(poolConfig, REDIS_HOST, REDIS_PORT);

        // 1. Mock Upstream Server Setup
        mockUpstreamServer = HttpServer.create(new InetSocketAddress(MOCK_UPSTREAM_PORT), 0);
        mockUpstreamServer.createContext("/service/", exchange -> {
            mockRequestCounter.incrementAndGet();
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
        trafficExecutor = Executors.newFixedThreadPool(10);

        log.info("🏁 Mock Upstream Server started on port {}", MOCK_UPSTREAM_PORT);
    }

    @AfterAll
    static void teardownAll() {
        if (mockUpstreamServer != null) mockUpstreamServer.stop(0);
        if (jedisPool != null) jedisPool.close();
        if (trafficExecutor != null) trafficExecutor.shutdownNow();
        log.info("🛑 Integration test environment cleaned up.");
    }

    @Test
    @Order(1)
    @DisplayName("Step 1: Send fixed-length byte telegram to original port (51009)")
    void verifyOriginalIngressTraffic() {
        assertDoesNotThrow(() -> {
            String trxId = UUID.randomUUID().toString().replace("-", "");
            byte[] rawPayload = buildFixedStandardPayload(trxId, "PAYLOAD_DATA_TO_51009");

            HttpResponse8 response = sendTestRawTransaction(ORIGINAL_INGRESS_PORT, rawPayload);
            assertEquals(200, response.statusCode());
            log.info("✅ Original Port Transaction Successful with valid fixed-layout packet.");
        }, "Zefio instance must be alive and listening on port 51009.");
    }

    @Test
    @Order(2)
    @DisplayName("Step 2: Propagate modified deployment specification via Redis and verify CP feedback loop")
    void executePipelineHotSwapViaRedis() throws InterruptedException {
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
                        } catch (Exception e) {
                            fail("Failed to parse deployment feedback message: " + e.getMessage());
                        }
                    }
                }, "zefio:deploy:status");
            }
        });
        statusSubscriber.setDaemon(true);
        statusSubscriber.start();

        TimeUnit.MILLISECONDS.sleep(500);

        // 💡 Refactored Blueprint: Stripped connection profiles and refErrorHandler tags to mirror the core compiler output format
        String updatedYaml = "flows:\n" +
                "  - name: inbound\n" +
                "    label: \"9_HTTP_TO_HTTP_Massive\"\n" +
                "    options:\n" +
                "      threadPool:\n" +
                "        corePoolSize: 200\n" +
                "        maxPoolSize: 400\n" +
                "        queueCapacity: 0\n" +
                "        autoScaling:\n" +
                "          enabled: true\n" +
                "          threshold: 0.7\n" +
                "          checkInterval: 5\n" +
                "          scaleUpStep: 30\n" +
                "          scaleDownStep: 10\n" +
                "      cpuQueue:\n" +
                "        capacity: 50000\n" +
                "      ioQueue:\n" +
                "        capacity: 20000\n" +
                "    ingress:\n" +
                "      name: from\n" +
                "      label: \"HTTP Inbound (51010)\"\n" +
                "      type: HttpIngress\n" +
                "      telegram: fixed-standard\n" +
                "      exchangePattern: RequestReply\n" +
                "      config:\n" +
                "        port: 51010\n" +
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
                "        config:\n" + // 💡 Fully flattened profile values inlined straight under the config block scope
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
                "    on-error:\n" + // 💡 Fixed Compliance: Embedded standard nested step handler component instead of reference pointer
                "      - error-type: ANY\n" +
                "        steps:\n" +
                "          - name: central-fixed-error\n" +
                "            type: FixedFaultHandler\n" +
                "            label: \"Common Error Handling\"\n" +
                "            config:\n" +
                "              errorCodeRules: [ ]\n" +
                "              valueOverrides: [ ]\n" +
                "              messageRule: null\n";

        Map<String, Object> commandMessage = new HashMap<>();
        commandMessage.put("targetGroup", "main");
        commandMessage.put("action", "hot-reload");
        commandMessage.put("deployId", deployId);

        // 💡 Segregated Packaging Protocol: Decouple distinct processing pipelines from structural configurations
        Map<String, Object> payload = new HashMap<>();
        payload.put("flowsYaml", updatedYaml); // Unified key target mapping to the Data Plane listener contract
        payload.put("telegrams", new HashMap<>()); // Send mock map entity to maintain isolation boundaries
        commandMessage.put("payload", payload);

        try (Jedis jedis = jedisPool.getResource()) {
            String jsonPayload = objectMapper.writeValueAsString(commandMessage);
            log.info("🔥 Sending Hot-Swap Command to channel 'zefio:command'...");
            jedis.publish("zefio:command", jsonPayload);
        } catch (Exception e) {
            fail("Failed to transmit orchestration command over Redis: " + e.getMessage());
        }

        boolean received = statusLatch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Hot-swap timeout exceeded. Cluster agent failed to respond within 10s.");
        assertEquals("SUCCESS", swapResultStatus[0], "Data plane agent reported failure during pipeline reconstruction loop.");
        log.info("✅ Zefio Core Hot-Swap Acknowledged by Data Plane Successfully.");
    }

    @Test
    @Order(3)
    @DisplayName("Step 3: Validate that the new port (51010) successfully claimed traffic with valid fixed telegram")
    void verifySwappedPipelineTraffic() {
        assertThrows(IOException.class, () -> {
            String trxId = UUID.randomUUID().toString().replace("-", "");
            byte[] rawPayload = buildFixedStandardPayload(trxId, "Stale Port Data");
            sendTestRawTransaction(ORIGINAL_INGRESS_PORT, rawPayload);
        }, "Stale port 51009 must be cleanly unbound and refused by the OS kernel.");

        assertDoesNotThrow(() -> {
            int beforeCount = mockRequestCounter.get();
            String trxId = UUID.randomUUID().toString().replace("-", "");
            byte[] rawPayload = buildFixedStandardPayload(trxId, "NEW_HOT_SWAPPED_TRAFFIC_DATA");

            HttpResponse8 response = sendTestRawTransaction(NEW_INGRESS_PORT, rawPayload);

            assertEquals(200, response.statusCode());
            assertTrue(mockRequestCounter.get() > beforeCount, "Traffic sent via new port 51010 failed to trace back to Mock Upstream.");
            log.info("🎉 Hot-Swap Perfect Success! Traffic processed safely via port {}", NEW_INGRESS_PORT);
        });
    }

    private static byte[] buildFixedStandardPayload(String trxId, String varData) {
        byte[] dummyHeader = new byte[64];
        Arrays.fill(dummyHeader, (byte) ' ');

        byte[] trxIdBytes = trxId.getBytes(StandardCharsets.UTF_8);
        if (trxIdBytes.length != 32) {
            throw new IllegalArgumentException("GLOBAL_TRX_ID length must be exactly 32 bytes.");
        }

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

        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(rawPayload);
            os.flush();
        }

        int statusCode = conn.getResponseCode();
        InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();

        StringBuilder responseContent = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    responseContent.append(line);
                }
            }
        }
        return new HttpResponse8(statusCode, responseContent.toString());
    }
}

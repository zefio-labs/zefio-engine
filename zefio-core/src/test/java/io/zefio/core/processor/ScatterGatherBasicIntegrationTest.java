package io.zefio.core.processor;

import io.zefio.core.payload.builder.config.CorrelationField;
import io.zefio.core.payload.builder.config.CorrelationIdType;
import io.zefio.core.payload.builder.config.FixedValues;
import io.zefio.core.payload.builder.config.Telegram;
import io.zefio.core.payload.util.TelegramFactory;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Atomic Test: SCATTER_GATHER Basic (JDK 1.8 Compatible)
 * - Target: flow-scatter-basic.yaml (Port 51002)
 * - Tool: OkHttp3 (Test Scope)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ScatterGatherBasicIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(ScatterGatherBasicIntegrationTest.class);

    private final String TARGET_URL = "http://127.0.0.1:51002/";
    private OkHttpClient okHttpClient;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize JDK 1.8 compatible OkHttpClient
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS) // Generous timeout considering SCATTER_GATHER parallel processing time
                .build();

        // 96-byte precision layout setup
        FixedValues values = new FixedValues();
        values.setEncodingIgnore(true);
        CorrelationField correlation = new CorrelationField(CorrelationIdType.SpEL);
        correlation.setExpression("#{body['GLOBAL_TRX_ID']}");
        values.setCorrelation(correlation);

        values.setLayout(Arrays.asList(
                new FixedValues.FixedField("PREFIX", 3, null, FixedValues.FieldType.STRING, FixedValues.Align.L, ' ', true),
                new FixedValues.FixedField("TARGET_VAL", 3, null, FixedValues.FieldType.STRING, FixedValues.Align.L, ' ', true),
                new FixedValues.FixedField("DUMMY_HEADER", 58, null, FixedValues.FieldType.STRING, FixedValues.Align.L, ' ', true),
                new FixedValues.FixedField("GLOBAL_TRX_ID", 32, null, FixedValues.FieldType.STRING, FixedValues.Align.L, ' ', true)
        ));

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> valuesMap = mapper.convertValue(values, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        TelegramFactory.register("fixed-http-req", Telegram.Type.Fixed, valuesMap);
    }

    @Test
    @DisplayName("[Scatter-Basic] Best effort error swallowing on parallel branch failure and 96-byte restoration verification")
    void testScatterGatherBestEffort() throws Exception {
        runInSandbox(() -> {
            String trxId = "SG-BASIC-TEST-0000000000000001";
            String payload = buildPayload("REQ", "ABC", trxId);

            long startTime = System.currentTimeMillis();
            TestResponse response = sendHttpRequest(payload);
            long elapsed = System.currentTimeMillis() - startTime;

            // [Then] Verify 200 OK
            assertEquals(200, response.statusCode(), "SCATTER_GATHER should ignore the error and return 200 OK");

            String res = response.body();
            log.info("[Final Response ({}ms)]: \n[{}]", elapsed, res);

            assertTrue(res.contains("RES"), "Response PREFIX restoration failed");
            assertTrue(res.contains("S_G"), "Top-level fallback marking (S_G) restoration failed");
            assertTrue(res.contains(trxId), "Identifier restoration failed");
        });
    }

    // -------------------------------------------------------------
    // Helper Methods (JDK 1.8 Compatible)
    // -------------------------------------------------------------

    private String buildPayload(String prefix, String targetVal, String trxId) {
        return String.format("%-3s%-3s%-58s%-32s",
                prefix.substring(0, Math.min(3, prefix.length())),
                targetVal.substring(0, Math.min(3, targetVal.length())),
                " ",
                trxId.substring(0, Math.min(32, trxId.length())));
    }

    private void runInSandbox(TestTask task) throws Exception {
        assumeTrue(isServerUp(), "Engine (51002) is not up. Ignoring test.");
        task.run();
    }

    private boolean isServerUp() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 51002), 300);
            return true;
        } catch (Exception e) { return false; }
    }

    private TestResponse sendHttpRequest(String payload) throws Exception {
        RequestBody body = RequestBody.create(payload, MediaType.parse("text/plain; charset=utf-8"));
        Request request = new Request.Builder().url(TARGET_URL).post(body).build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            String bodyString = (response.body() != null) ? response.body().string() : "";
            return new TestResponse(response.code(), bodyString);
        } catch (IOException e) {
            log.error("Error occurred while sending HTTP request", e);
            return new TestResponse(500, e.getMessage());
        }
    }

    // Wrapper providing usability similar to Java 11 HttpResponse interface
    private static class TestResponse {
        private final int code;
        private final String body;
        public TestResponse(int code, String body) { this.code = code; this.body = body; }
        public int statusCode() { return code; }
        public String body() { return body; }
    }

    @FunctionalInterface interface TestTask { void run() throws Exception; }
}

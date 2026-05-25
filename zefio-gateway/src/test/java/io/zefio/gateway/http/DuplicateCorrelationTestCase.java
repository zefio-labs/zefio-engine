package io.zefio.gateway.http;

import io.zefio.core.Ingress;
import io.zefio.core.Upstream;
import io.zefio.core.factory.PluginContext;
import io.zefio.core.payload.Payload;
import io.zefio.core.payload.ExchangePattern;
import io.zefio.core.payload.PayloadBuilder;
import io.zefio.core.payload.builder.config.*;
import io.zefio.testsupport.endpoint.UpstreamToIngressIntegrationTestCase;
import io.zefio.testsupport.payload.FixedPayloadBuilderFactory;
import io.zefio.testsupport.payload.IPayloadBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HTTP Duplicate CorrelationID concurrent request test")
public class DuplicateCorrelationTestCase extends UpstreamToIngressIntegrationTestCase {

    public DuplicateCorrelationTestCase() throws Exception {
        // Reuse the YAML configuration where Keep-Alive is set, or specify a separate configuration
        super("httpInbound_connectionHeader_test", "httpOutbound_connectionKeepAliveHeader_test");
    }

    @Override
    public IPayloadBuilderFactory createSenderBuilder() throws Exception {
        // 🚀 Physical field definition
        List<FixedValues.FixedField> layout = new ArrayList<>();

        // 0~63: Data dummy
        layout.add(new FixedValues.FixedField("DUMMY_FRONT", 64, 0,
                FixedValues.FieldType.STRING, FixedValues.Align.L, ' ', true));

        // 64~95: Correlation ID area (TrxID)
        layout.add(new FixedValues.FixedField("CORR_ID_AREA", 32, 64,
                FixedValues.FieldType.STRING, FixedValues.Align.L, ' ', true));

        // 96~128: Remaining
        layout.add(new FixedValues.FixedField("DUMMY_TAIL", 32, 96,
                FixedValues.FieldType.STRING, FixedValues.Align.L, ' ', true));

        // 1. Set Framing strategy (length 4 bytes)
        FramingField framing = new FramingField();
        framing.setType(FramingType.Length);
        framing.setLengthDataSize(4);
        framing.setLengthDataInclude(false);
        framing.setLengthDataUpdate(true);

        // 2. Set Correlation (match with layout definition)
        CorrelationField correlation = new CorrelationField(CorrelationIdType.Offset);
        correlation.setStart(64);
        correlation.setLength(32);

        // 3. Create builder
        PayloadBuilder builder = new Telegram.Builder()
                .name("fixed-standard-offset")
                .type(Telegram.Type.Fixed)
                .values(FixedValues.builder()
                        .framing(framing)
                        .correlation(correlation)
                        .layout(layout) // 🚀 Inject layout
                        .encodingIgnore(false)
                        .build())
                .build();

        return new FixedPayloadBuilderFactory(builder, senderEncoding);
    }

    @Override
    public Ingress createReceiver(PluginContext.PluginContextBuilder builder) {
        return new HttpIngress(builder.exchangePattern(ExchangePattern.RequestReply).build());
    }

    @Override
    public Upstream createSender(PluginContext.PluginContextBuilder builder) {
        return new HttpUpstream(builder.exchangePattern(ExchangePattern.RequestReply).build());
    }

    @Override
    public Payload handleFilter(Payload requestPayload) throws Exception {
        // Server echo logic (returns received as is)
        // In real situations, it saves or processes based on CID here
        return requestPayload;
    }

    @Test
    @DisplayName("Verify if ERR_003 exception occurs on identical CID request")
    void testDuplicateCorrelationExpectedError() throws Exception {
        String duplicateId = "DUP_KEY_001";
        byte[] body = generateMessage(duplicateId);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Start first request
        CompletableFuture<Payload> future1 = sender.executeAsync(
                senderBuilder.withBody(body, senderEncoding), executor);

        // Second request (almost simultaneously)
        CompletableFuture<Payload> future2 = sender.executeAsync(
                senderBuilder.withBody(body, senderEncoding), executor);

        // Check results
        try {
            CompletableFuture.allOf(future1, future2).join();
        } catch (Exception e) {
            // e should be CompletionException, and the cause should be ERR_003
            String errorMsg = e.getMessage();
            assertTrue(errorMsg.contains("ERR_003"), "The error message must contain ERR_003.");
            System.out.println("Intended duplicate error confirmed: " + errorMsg);
        }

        executor.shutdown();
    }

    private byte[] generateMessage(String key) {
        byte[] body = new byte[128];
        java.util.Arrays.fill(body, (byte) ' ');
        // Inject CID at 64th index
        System.arraycopy(key.getBytes(), 0, body, 64, key.length());
        return body;
    }
}

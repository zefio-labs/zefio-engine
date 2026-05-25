package io.zefio.gateway.http;

import io.zefio.core.common.base.MDCKey;
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
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Http Keep-Alive reuse integration test")
public class HttpKeepAliveReuseTestCase extends UpstreamToIngressIntegrationTestCase {

    public HttpKeepAliveReuseTestCase() throws Exception {
        // Load the httpInbound_keepalive and httpOutbound_keepalive settings defined in the configuration file
        super("httpInbound_keepalive", "httpOutbound_keepalive");
    }

    @Override
    public IPayloadBuilderFactory createSenderBuilder() throws Exception {
        // 🚀 Define the physical structure of the message
        List<FixedValues.FixedField> layout = new ArrayList<>();

        // 1. Header area (0~63)
        layout.add(new FixedValues.FixedField("HEADER_DUMMY", 64, 0,
                FixedValues.FieldType.STRING, FixedValues.Align.L, ' ', true));

        // 2. TID area (64~95) - Position where data is embedded in the test method
        layout.add(new FixedValues.FixedField("TID_AREA", 32, 64,
                FixedValues.FieldType.STRING, FixedValues.Align.L, ' ', true));

        // 3. Remainder area (96~500)
        layout.add(new FixedValues.FixedField("REMAINDER", 404, 96,
                FixedValues.FieldType.STRING, FixedValues.Align.L, ' ', true));

        // Framing and Correlation settings (maintain existing standard)
        FramingField framing = new FramingField();
        framing.setType(FramingType.EOF);

        CorrelationField correlation = new CorrelationField(CorrelationIdType.SpEL);
        correlation.setExpression("#{body['TID_AREA']}");

        PayloadBuilder builder = new Telegram.Builder()
                .name("http-keepalive-fixed")
                .type(Telegram.Type.Fixed)
                .values(FixedValues.builder()
                        .framing(framing)
                        .correlation(correlation)
                        .layout(layout) // 🚀 Inject defined layout
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
        // keepAlive: keep-alive is already set in the Outbound configuration
        return new HttpUpstream(builder.exchangePattern(ExchangePattern.RequestReply).build());
    }

    @Override
    public Payload handleFilter(Payload requestPayload) throws Exception {
        // Server-side logic: Echo the received content as a response
        return requestPayload;
    }

    @Test
    @DisplayName("Consecutive transmission/reception test using the same Upstream connection")
    void testHttpConnectionReuse() throws Exception {
        // --- [STEP 1] Send first request ---
        String tid1 = "REUSE_TEST_01_ABCDEFG_12345678";
        Payload reqPayload1 = senderBuilder.withBody(generateTestMessage(tid1), senderEncoding);

        System.out.println(">>> Sending First Request: " + tid1);
        sender.executeAsync(reqPayload1, Executors.newSingleThreadExecutor()).join();

        Payload resPayload1 = getReceiverCapturedEvent();
        assertNotNull(resPayload1, "The first response is null.");
        System.out.println("<<< Received First Response. CID: " + resPayload1.getMdcContext().get(MDCKey.CID.name()));

        // Wait briefly (to check if the server maintains the connection)
        Thread.sleep(2000);

        // --- [STEP 2] Send second request (using the same sender instance) ---
        String tid2 = "REUSE_TEST_02_HIJKLMN_87654321";
        Payload reqPayload2 = senderBuilder.withBody(generateTestMessage(tid2), senderEncoding);

        System.out.println(">>> Sending Second Request: " + tid2);
        sender.executeAsync(reqPayload2, Executors.newSingleThreadExecutor()).join();

        Payload resPayload2 = getReceiverCapturedEvent();
        assertNotNull(resPayload2, "The second response is null.");
        System.out.println("<<< Received Second Response. CID: " + resPayload2.getMdcContext().get(MDCKey.CID.name()));

        // --- [STEP 3] Verification ---
        // 1. Check if the response body contains the TID of each request
        String resBody1 = new String(resPayload1.getBody(), resPayload1.getCurrentEncoding());
        String resBody2 = new String(resPayload2.getBody(), resPayload2.getCurrentEncoding());

        assertEquals(true, resBody1.contains("REUSE_TEST_01"));
        assertEquals(true, resBody2.contains("REUSE_TEST_02"));

        // 2. (Crucial) Compare if the CIDs of the two events are the same (check for reuse)
        // Note: Depending on the framework's internal attribute naming, you might need to extract "CID" or "ChannelID".
        Object cid1 = resPayload1.getMdcContext().get(MDCKey.CID.name());
        Object cid2 = resPayload2.getMdcContext().get(MDCKey.CID.name());

        System.out.println("First CID: " + cid1 + " / Second CID: " + cid2);
        assertEquals(cid1, cid2, "Connection was not reused and was newly created!");
    }

    // ✅ TO-BE (Generate 500 bytes according to the layout specification)
    private byte[] generateTestMessage(String tid) {
        byte[] body = new byte[500]; // 🚀 Modified from 128 -> 500
        java.util.Arrays.fill(body, (byte) ' ');
        byte[] tidBytes = tid.getBytes();
        // Copy TID from the 64th offset within the 32-byte limit (maintain normally)
        System.arraycopy(tidBytes, 0, body, 64, Math.min(tidBytes.length, 32));
        return body;
    }
}

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

@DisplayName("HTTP Connection Header (Keep-Alive) verification test")
public class ConnectionKeepAliveHeaderTestCase extends UpstreamToIngressIntegrationTestCase {

    public ConnectionKeepAliveHeaderTestCase() throws Exception {
        // Must match the name in YAML configuration
        super("httpInbound_connectionHeader_test", "httpOutbound_connectionKeepAliveHeader_test");
    }

    @Override
    public IPayloadBuilderFactory createSenderBuilder() throws Exception {
        // 🚀 Physical field definition
        List<FixedValues.FixedField> layout = new ArrayList<>();

        // 0~63: Data dummy area
        layout.add(new FixedValues.FixedField("DUMMY_HEAD", 64, 0,
                FixedValues.FieldType.STRING, FixedValues.Align.L, ' ', true));

        // 64~95: Correlation ID (TID) area
        layout.add(new FixedValues.FixedField("CORR_ID_AREA", 32, 64,
                FixedValues.FieldType.STRING, FixedValues.Align.L, ' ', true));

        // 96~128: Tail area
        layout.add(new FixedValues.FixedField("DUMMY_TAIL", 32, 96,
                FixedValues.FieldType.STRING, FixedValues.Align.L, ' ', true));

        // 1. Set Framing strategy (length header 4 bytes)
        FramingField framing = new FramingField();
        framing.setType(FramingType.Length);
        framing.setLengthDataSize(4);
        framing.setLengthDataInclude(false);
        framing.setLengthDataUpdate(true);

        // 2. Set Correlation (synchronize with layout definition)
        CorrelationField correlation = new CorrelationField(CorrelationIdType.Offset);
        correlation.setStart(64);
        correlation.setLength(32);

        // 3. Create Telegram and builder
        PayloadBuilder builder = new Telegram.Builder()
                .name("fixed-standard-offset")
                .type(Telegram.Type.Fixed)
                .values(FixedValues.builder()
                        .framing(framing)
                        .correlation(correlation)
                        .layout(layout)            // 🚀 Inject layout
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
        // Server echo logic
        return requestPayload;
    }

    @Test
    @DisplayName("Case 1: Connection Keep-Alive test - Verify if CID is identical on consecutive requests")
    void testConnectionKeepAlive() throws Exception {
        // First request
        Payload req1 = senderBuilder.withBody(generateMessage("KEEP_TEST_01"), senderEncoding);

        sender.executeAsync(req1, Executors.newSingleThreadExecutor()).join();
        String cid1 = (String) getReceiverCapturedEvent().getMdcContext().get(MDCKey.CID.name());


        // Second request (If connection is maintained, use the same CID)
        Payload req2 = senderBuilder.withBody(generateMessage("KEEP_TEST_02"), senderEncoding);

        sender.executeAsync(req2, Executors.newSingleThreadExecutor()).join();
        String cid2 = (String) getReceiverCapturedEvent().getMdcContext().get(MDCKey.CID.name());

        System.out.println("Keep-Alive test - CID1: " + cid1 + ", CID2: " + cid2);

        // CID should be the same on Keep-alive in the same outbound instance (when connection pooling is operating)
        assertEquals(cid1, cid2, "Connection was not reused despite Keep-Alive request.");
    }

    private byte[] generateMessage(String key) {
        byte[] body = new byte[128];
        java.util.Arrays.fill(body, (byte) ' ');
        System.arraycopy(key.getBytes(), 0, body, 64, key.length());
        return body;
    }
}

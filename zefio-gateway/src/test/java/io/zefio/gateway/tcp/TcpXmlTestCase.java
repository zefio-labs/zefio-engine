package io.zefio.gateway.tcp;

import io.zefio.core.Ingress;
import io.zefio.core.Upstream;
import io.zefio.core.factory.PluginContext;
import io.zefio.core.payload.Payload;
import io.zefio.core.payload.ExchangePattern;
import io.zefio.testsupport.endpoint.UpstreamToIngressIntegrationTestCase;
import io.zefio.testsupport.payload.IPayloadBuilderFactory;
import io.zefio.testsupport.payload.XmlPayloadBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

@DisplayName("Tcp 2-way test")
public class TcpXmlTestCase extends UpstreamToIngressIntegrationTestCase {
    public TcpXmlTestCase() throws Exception {
        super("tcpInbound_xml", "tcpOutbound_xml");
    }

    private final String DELIMITER = "DDD";

    // Telegram assembly, Framing, Correlation configuration, and Register are all processed in a single line.
    @Override
    public IPayloadBuilderFactory createSenderBuilder() throws Exception {
        return XmlPayloadBuilderFactory.createStandardFactory("xml-delimiter-test", senderEncoding, 500, DELIMITER);
    }

    @Override
    public Ingress createReceiver(PluginContext.PluginContextBuilder builder) {
        PluginContext context = builder
                .exchangePattern(ExchangePattern.RequestReply)
                .build();
        return new TcpIngress(context);
    }
    @Override
    public Upstream createSender(PluginContext.PluginContextBuilder builder) {
        PluginContext context = builder
                .exchangePattern(ExchangePattern.RequestReply)
                .build();
        return new TcpUpstream(context);
    }

    @Override
    public Payload handleFilter(Payload requestPayload) throws Exception {
        // 🚀 [Modified] Please handle the Map directly instead of string replace.
        // The moment getBodyMap() is called, Lazy Parsing operates and outputs a clean Map.
        Map<String, Object> bodyMap = requestPayload.getBodyMap();

        // Business logic: Perform necessary processing (e.g., change response flag, etc.)
        bodyMap.put("processed", "true");
        requestPayload.setBodyMap(bodyMap);

        // Here, even if you simply return keeping the original Map, the engine will generate the same tags during Baking.
        requestPayload.setBodyMap(bodyMap);

        // 🚨 Never manually append the DELIMITER("DDD").
        // The engine's Framing function attaches it automatically.
        return requestPayload;
    }

    @Test
    @DisplayName("Tcp transmission/reception test (XML message processing)")
    void testTcpSyncRequestResponse() throws Exception {
        send();
    }
}

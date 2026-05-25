package io.zefio.gateway.websocket;

import io.zefio.core.Ingress;
import io.zefio.core.Upstream;
import io.zefio.core.factory.PluginContext;
import io.zefio.core.payload.Payload;
import io.zefio.core.payload.ExchangePattern;
import io.zefio.testsupport.endpoint.UpstreamToIngressIntegrationTestCase;
import io.zefio.testsupport.payload.FixedPayloadBuilderFactory;
import io.zefio.testsupport.payload.IPayloadBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WebSocket 2-way test")
public class WebSocketTwowayToTwowayTestCase extends UpstreamToIngressIntegrationTestCase {

    public WebSocketTwowayToTwowayTestCase() throws Exception {
        super("WebSocketIngress_twoway_twoway", "websocketOutbound_twoway_twoway");
    }

    @Override
    public IPayloadBuilderFactory createSenderBuilder() throws Exception {
        return FixedPayloadBuilderFactory.createStandardFactory("fixed-standard-tcp", senderEncoding, 500);
    }

    @Override
    public Ingress createReceiver(PluginContext.PluginContextBuilder builder) {
        PluginContext context = builder
                .exchangePattern(ExchangePattern.RequestReply)
                .build();
        return new WebSocketIngress(context);
    }

    @Override
    public Upstream createSender(PluginContext.PluginContextBuilder builder) {
        PluginContext context = builder
                .exchangePattern(ExchangePattern.RequestReply)
                .build();
        return new WebSocketUpstream(context);
    }

    @Override
    public Payload handleFilter(Payload requestPayload) throws Exception {
        String req = new String(requestPayload.getBody(), senderEncoding);
        String res = req.replace("req", "res");
        requestPayload.setBody(res.getBytes(receiverEncoding));

        return requestPayload;
    }

    @Test
    @DisplayName("WebSocket transmission/reception test")
    void testWebSocketSendReceive() throws Exception {
        send(); // Request → Verify response
    }
}

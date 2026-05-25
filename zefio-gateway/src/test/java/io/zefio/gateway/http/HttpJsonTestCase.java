package io.zefio.gateway.http;

import io.zefio.core.Ingress;
import io.zefio.core.Upstream;
import io.zefio.core.factory.PluginContext;
import io.zefio.core.payload.Payload;
import io.zefio.core.payload.ExchangePattern;
import io.zefio.testsupport.endpoint.UpstreamToIngressIntegrationTestCase;
import io.zefio.testsupport.payload.IPayloadBuilderFactory;
import io.zefio.testsupport.payload.JsonPayloadBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Http 2-way test")
public class HttpJsonTestCase extends UpstreamToIngressIntegrationTestCase {
    public HttpJsonTestCase() throws Exception {
        super("httpInbound_json", "httpOutbound_json");
    }

    @Override
    public IPayloadBuilderFactory createSenderBuilder() throws Exception {
        return JsonPayloadBuilderFactory.createStandardFactory("json-spel-test", senderEncoding, 500, "DELIMITER");
    }

    @Override
    public Ingress createReceiver(PluginContext.PluginContextBuilder builder) {
        PluginContext context = builder
                .exchangePattern(ExchangePattern.RequestReply)
                .build();
        return new HttpIngress(context);
    }
    @Override
    public Upstream createSender(PluginContext.PluginContextBuilder builder) {
        PluginContext context = builder
                .exchangePattern(ExchangePattern.RequestReply)
                .build();
        return new HttpUpstream(context);
    }

    @Override
    public Payload handleFilter(Payload requestPayload) throws Exception {
        String request = new String(requestPayload.getBody(), requestPayload.getCurrentEncoding());
        byte[] send = request.replace("req", "res").getBytes(requestPayload.getCurrentEncoding());
        requestPayload.setBody(send);

        return requestPayload;
    }

    @Test
    @DisplayName("Http synchronous transmission/reception test (Json message processing)")
    void testHttpUpstreamRequestResponse() throws Exception {
        send();
    }
}

package io.zefio.gateway.tcp;

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

import java.nio.charset.StandardCharsets;

@DisplayName("Tcp 2-way test")
public class TcpEncodingTestCase extends UpstreamToIngressIntegrationTestCase {
    public TcpEncodingTestCase() throws Exception {
        super("tcpInbound_encoding", "tcpOutbound_encoding");
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
        String request = new String(requestPayload.getBody(), StandardCharsets.UTF_8);
        byte[] send = request.replace("req", "res").getBytes(StandardCharsets.UTF_8);
        requestPayload.setBody(send);

        return requestPayload;
    }

    @Test
    @DisplayName("Tcp transmission/reception test (Encoding message processing)")
    void testTcpSyncRequestResponse() throws Exception {
        send();
    }
}

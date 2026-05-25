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

@DisplayName("Tcp 2-way test")
public class TcpFixedTestCase extends UpstreamToIngressIntegrationTestCase {
    public TcpFixedTestCase() throws Exception {
        super("tcpInbound_fixed", "tcpOutbound_fixed");
    }

    // 🚀 Successfully slimmed down completely!
    // Telegram assembly, Framing, Correlation configuration, and Register are all processed in a single line.
    @Override
    public IPayloadBuilderFactory createSenderBuilder() throws Exception {
        return FixedPayloadBuilderFactory.createStandardFactory("fixed-standard-tcp", senderEncoding, 500);
    }

    @Override
    public Ingress createReceiver(PluginContext.PluginContextBuilder builder) {
        return new TcpIngress(builder.exchangePattern(ExchangePattern.RequestReply).build());
    }

    @Override
    public Upstream createSender(PluginContext.PluginContextBuilder builder) {
        return new TcpUpstream(builder.exchangePattern(ExchangePattern.RequestReply).build());
    }

    @Override
    public Payload handleFilter(Payload requestPayload) throws Exception {
        String request = new String(requestPayload.getBody(), senderEncoding);
        byte[] send = request.replace("req", "res").getBytes(receiverEncoding);
        requestPayload.setBody(send);
        return requestPayload;
    }

    @Test
    @DisplayName("Tcp transmission/reception test (Fixed message processing)")
    void testTcpSyncRequestResponse() throws Exception {
        send();
    }
}

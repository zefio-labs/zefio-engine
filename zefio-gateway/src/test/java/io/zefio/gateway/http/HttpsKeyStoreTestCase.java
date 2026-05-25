package io.zefio.gateway.http;

import io.zefio.core.Ingress;
import io.zefio.core.Upstream;
import io.zefio.core.factory.PluginContext;
import io.zefio.core.payload.ExchangePattern;
import io.zefio.testsupport.endpoint.UpstreamToIngressIntegrationTestCase;
import io.zefio.testsupport.payload.FixedPayloadBuilderFactory;
import io.zefio.testsupport.payload.IPayloadBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Https 2-way test")
public class HttpsKeyStoreTestCase extends UpstreamToIngressIntegrationTestCase {
    public HttpsKeyStoreTestCase() throws Exception {
        super("httpsInbound_keystore", "httpsOutbound_keystore");
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
        if (System.getProperty("java.version").startsWith("1.8")) {
            System.out.println("Skipping test on JDK 1.8 due to Illegal key size issue");
            return new HttpIngress(context);
        }
        return new HttpsIngress(context);
    }
    @Override
    public Upstream createSender(PluginContext.PluginContextBuilder builder) {
        PluginContext context = builder
                .exchangePattern(ExchangePattern.RequestReply)
                .build();
        if (System.getProperty("java.version").startsWith("1.8")) {
            System.out.println("Skipping test on JDK 1.8 due to Illegal key size issue");
            return new HttpUpstream(context);
        }
        return new HttpsUpstream(context);
    }

    @Test
    @DisplayName("Https synchronous transmission/reception test (Key Store)")
    void testHttpUpstreamRequestResponse() throws Exception {
        if (System.getProperty("java.version").startsWith("1.8")) {
            System.out.println("Skipping test on JDK 1.8 due to Illegal key size issue");
            return;  // Skip test
        }

        send();
    }
}

package io.zefio.gateway.error;

import io.zefio.core.common.exception.FlowException;
import io.zefio.core.common.exception.FlowResultStatus;
import io.zefio.core.GatewayInterceptor;
import io.zefio.core.factory.PluginContext;
import io.zefio.core.payload.Payload;
import io.zefio.core.payload.ZefioMessage;
import io.zefio.testsupport.payload.IPayloadBuilderFactory;
import io.zefio.testsupport.payload.XmlPayloadBuilderFactory;
import io.zefio.testsupport.filter.AbstractNormalFilterTestCase;
import org.apache.commons.lang3.ObjectUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@DisplayName("XmlError filter test")
public class XmlErrorTestCase extends AbstractNormalFilterTestCase {

    public XmlErrorTestCase() throws Exception {
        super("error-test.yaml", "xml1"); // Uses resources/error/xml1.yml configuration
    }

    @Override
    public IPayloadBuilderFactory createSenderBuilder() throws Exception {
        return XmlPayloadBuilderFactory.createStandardFactory("xml-default", filterEncoding, 500, "");
    }

    @Override
    public GatewayInterceptor createFilter(Map<String, Object> context) {
        return buildFilterWithContext(context);
    }

    // =========================================================================
    // 🛠️ [Helper 1] Remove filter creation boilerplate
    // =========================================================================
    private GatewayInterceptor buildFilterWithContext(Map<String, Object> context) {
        PluginContext ctx = PluginContext.builder()
                .flowName("default")
                .pluginName(getClass().getName())
                .telegramName("xml-default") // 🚀 Common injection for constructor validation
                .sharedScheduledPool(sharedPool.getValue0())
                .sharedIoPool(sharedPool.getValue1())
                .context(context)
                .build();
        return new StructuredFaultHandler(ctx);
    }

    // =========================================================================
    // 🛠️ [Helper 2] Filter re-initialization helper
    // =========================================================================
    private void reInitializeFilter(String yamlKey) throws Exception {
        Map<String, Object> context = getContext(yamlKey);
        this.filterEncoding = ObjectUtils.isEmpty(context.get("requestEncoding")) ?
                StandardCharsets.UTF_8 : Charset.forName(context.get("requestEncoding").toString());
        this.filter = buildFilterWithContext(context);
        this.filter.initialise();
    }

    // =========================================================================
    // 🛠️ [Helper 3] Remove event creation boilerplate
    // =========================================================================
    private Payload createXmlEvent(String xmlBody) {
        byte[] bodyBytes = xmlBody != null ? xmlBody.getBytes(filterEncoding) : null;
        Payload payload = new ZefioMessage(bodyBytes, filterEncoding);
        payload.setTelegramName("xml-default"); // 🚀 Common injection for factory lookup
        return payload;
    }

    // =========================================================================
    // 🧪 Start of test cases (perfectly removed repetitive code)
    // =========================================================================

    @Test
    @DisplayName("XML error filter - Error code replacement test")
    void testXmlErrorWithErrorCodeReplacement() throws Exception {
        // 🚀 Event creation logic reduced by applying the helper!
        String xml = "<root><code>E123</code><message>fail</message><errorMessage>fail</errorMessage></root>";
        Payload requestPayload = createXmlEvent(xml);
        requestPayload.setThrowable(new FlowException(new Exception(), FlowResultStatus.CUSTOM_FILTER_ERROR, "E123", ""));

        Payload responsePayload = executeAssert(requestPayload);
        String result = new String(responsePayload.getBody(), filterEncoding);
        System.out.println("Modified XML: " + result);

        // key "code" is replaced with "ABC"
        assert result.contains("<code>ABC</code>");
        // Check for overwrite "desc" = "xml override" by valueOverrides
        assert result.contains("<desc>xml override</desc>");
        // messageRule.key ("errorMessage") is retained for error message merging
        assert result.contains("E123");
    }

}

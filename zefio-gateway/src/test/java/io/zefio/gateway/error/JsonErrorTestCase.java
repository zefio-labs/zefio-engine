package io.zefio.gateway.error;

import io.zefio.core.common.exception.FlowException;
import io.zefio.core.common.exception.FlowResultStatus;
import io.zefio.core.GatewayInterceptor;
import io.zefio.core.factory.PluginContext;
import io.zefio.core.payload.Payload;
import io.zefio.core.payload.ZefioMessage;
import io.zefio.testsupport.payload.IPayloadBuilderFactory;
import io.zefio.testsupport.payload.JsonPayloadBuilderFactory;
import io.zefio.testsupport.filter.AbstractNormalFilterTestCase;
import org.apache.commons.lang3.ObjectUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@DisplayName("JsonError filter test")
public class JsonErrorTestCase extends AbstractNormalFilterTestCase {

    public JsonErrorTestCase() throws Exception {
        super("error-test.yaml", "json1"); // Uses resources/error/json1.yml configuration
    }

    @Override
    public IPayloadBuilderFactory createSenderBuilder() throws Exception {
        return JsonPayloadBuilderFactory.createStandardFactory("json-default", filterEncoding, 500, "");
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
                .telegramName("json-default") // 🚀 Common injection for constructor validation
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
    private Payload createJsonEvent(String jsonBody) {
        byte[] bodyBytes = jsonBody != null ? jsonBody.getBytes(filterEncoding) : null;
        Payload payload = new ZefioMessage(bodyBytes, filterEncoding);
        payload.setTelegramName("json-default"); // 🚀 Common injection for factory lookup
        return payload;
    }

    // =========================================================================
    // 🧪 Start of test cases (perfectly removed repetitive code)
    // =========================================================================

    @Test
    @DisplayName("JSON error filter - FlowException replacement test")
    void testJsonErrorWithErrorCodeReplacement() throws Exception {
        // 🚀 Event creation logic is reduced to a single line by applying the helper!
        Payload requestPayload = createJsonEvent("{\"code\":\"E123\",\"message\":\"original\"}");
        requestPayload.setThrowable(new FlowException(new IOException("IO Fail"), FlowResultStatus.CUSTOM_FILTER_ERROR, "E123", ""));

        Payload responsePayload = executeAssert(requestPayload);
        String result = new String(responsePayload.getBody(), filterEncoding);
        System.out.println("Modified JSON: " + result);

        // Then (Modify result verification)
        // 1. Verify if "E123" is changed to "ABC" by errorCodeRules
        assert(result.contains("\"code\":\"ABC\""));

        // 2. Verify if "desc" key is added by valueOverrides
        assert(result.contains("\"desc\":\"json override\""));

        // 3. Verify if existing fields are maintained
        assert(result.contains("\"message\":\"original\""));
    }
}

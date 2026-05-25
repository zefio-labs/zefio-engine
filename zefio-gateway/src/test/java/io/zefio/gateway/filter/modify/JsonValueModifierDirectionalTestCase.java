package io.zefio.gateway.filter.modify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JsonValueModifierDirectional filter test")
public class JsonValueModifierDirectionalTestCase extends AbstractNormalFilterTestCase {

    public JsonValueModifierDirectionalTestCase() throws Exception {
        super("modifyJsonBody");
    }

    @Override
    public IPayloadBuilderFactory createSenderBuilder() throws Exception {
        return JsonPayloadBuilderFactory.createStandardFactory("json-key-test", filterEncoding, 500, "");
    }

    @Override
    public GatewayInterceptor createFilter(Map<String, Object> context) {
        // 🚀 Register JSON builder to global factory
        return buildFilterWithContext(context);
    }

    // =========================================================================
    // 🛠️ [Helper 1] Remove filter creation boilerplate
    // =========================================================================
    private GatewayInterceptor buildFilterWithContext(Map<String, Object> context) {
        PluginContext ctx = PluginContext.builder()
                .flowName("default")
                .pluginName(getClass().getName())
                .telegramName("json-key-test") // 🚀 Common injection for constructor validation
                .sharedScheduledPool(sharedPool.getValue0())
                .sharedIoPool(sharedPool.getValue1())
                .context(context)
                .build();
        return new ValueModifierDirectional(ctx);
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
    private Payload createJsonEvent(String jsonBody, String trxId) {
        byte[] bodyBytes = jsonBody != null ? jsonBody.getBytes(filterEncoding) : null;
        Payload payload = new ZefioMessage(bodyBytes, filterEncoding);
        payload.setTelegramName("json-key-test"); // 🚀 Common injection for factory lookup
        payload.setTrxID(trxId);
        return payload;
    }


    // =========================================================================
    // 🧪 Start of test cases (perfectly removed repetitive code)
    // =========================================================================

    @Test
    @DisplayName("MODIFY_BODY: Test replacing internal values of event body")
    public void testModifyBody() throws Exception {
        Payload requestPayload = createJsonEvent("{ \"person\": { \"name\": \"Bob\", \"age\": 20 } }", "abc");

        filter.executeAsync(requestPayload, Executors.newSingleThreadExecutor()).join();
        String result = new String(requestPayload.getBody(), filterEncoding);

        // Simple string comparison
        assertTrue(result.contains("\"name\":\"Alice\""));
        assertTrue(result.contains("\"age\":\"30\""));
    }

    @Test
    @DisplayName("PROPERTY_TO_BODY: Test inserting property → body")
    public void testPropertyToBody() throws Exception {
        reInitializeFilter("propertyToBody");

        // user object exists in existing JSON and both name and age exist
        Payload payload = createJsonEvent("{\"user\":{\"name\":\"XXX\",\"age\":30}}", "trx002");

        // Set event property
        payload.setHeader("userName", "Alice");
        payload.setHeader("userAge", 25);  // Add age

        filter.executeAsync(payload, Executors.newSingleThreadExecutor()).join();

        // Compare body JSON
        ObjectMapper mapper = new ObjectMapper();
        JsonNode expectedJson = mapper.readTree("{\"user\":{\"name\":\"Alice\",\"age\":25}}");
        JsonNode actualJson = mapper.readTree(new String(payload.getBody(), filterEncoding));
        assertEquals(expectedJson, actualJson);

        // Check property value
        assertEquals("Alice", payload.getHeader("userName"));
        assertEquals(25, payload.getHeader("userAge"));

        filter.close();
    }

    @Test
    @DisplayName("BODY_TO_PROPERTY: Test extracting body → property")
    public void testBodyToProperty() throws Exception {
        reInitializeFilter("bodyToProperty");

        Payload requestPayload = createJsonEvent("{ \"user\": { \"name\": \"Bob\", \"age\": \"40\" } }", "trx003");

        filter.executeAsync(requestPayload, Executors.newSingleThreadExecutor()).join();

        assertEquals("Bob", requestPayload.getHeader("userName"));
        assertEquals("40", requestPayload.getHeader("userAge"));

        filter.close();
    }
}

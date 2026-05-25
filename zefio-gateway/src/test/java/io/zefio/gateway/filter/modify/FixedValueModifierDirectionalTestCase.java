package io.zefio.gateway.filter.modify;

import io.zefio.core.GatewayInterceptor;
import io.zefio.core.factory.PluginContext;
import io.zefio.core.payload.Payload;
import io.zefio.core.payload.ZefioMessage;
import io.zefio.testsupport.payload.FixedPayloadBuilderFactory;
import io.zefio.testsupport.payload.IPayloadBuilderFactory;
import io.zefio.testsupport.filter.AbstractNormalFilterTestCase;
import org.apache.commons.lang3.ObjectUtils;
import org.javatuples.Pair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("FixedBinaryModifierDirectional filter test")
public class FixedValueModifierDirectionalTestCase extends AbstractNormalFilterTestCase {

    public FixedValueModifierDirectionalTestCase() throws Exception {
        super("fixed-body-insert-1");
    }

    @Override
    public IPayloadBuilderFactory createSenderBuilder() throws Exception {
        return FixedPayloadBuilderFactory.createStandardFactory("fixed", filterEncoding, 500);
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
                .telegramName("fixed") // 🚀 Common injection
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
    private Payload createFixedEvent(String bodyStr, String trxId) {
        byte[] bodyBytes = bodyStr != null ? bodyStr.getBytes(filterEncoding) : null;
        Payload payload = new ZefioMessage(bodyBytes, filterEncoding);
        payload.setTelegramName("fixed"); // 🚀 Common injection
        payload.setTrxID(trxId);
        return payload;
    }


    // =========================================================================
    // 🧪 Start of test cases (amazingly concise code!)
    // =========================================================================

    @Test
    @DisplayName("Insert header key value into the specified position in the existing body")
    public void testSetKeyIntoBody() throws Exception {
        Payload requestPayload = createFixedEvent("XXXXXX", "trx001");
        requestPayload.setHeader("header", "ABC".getBytes(filterEncoding));

        executeAssertBodyEquals(requestPayload, "ABCXXX");
    }

    @Test
    @DisplayName("Throw exception when key data is missing")
    public void testMissingKeyData() {
        Payload requestPayload = createFixedEvent("123456", "trx003");
        executeAssertThrows(Exception.class, requestPayload);
    }

    @Test
    @DisplayName("Cumulative overwrite merging")
    public void testMultipleChildren() throws Exception {
        reInitializeFilter("fixed-body-insert-2");

        Payload requestPayload = createFixedEvent("ABCDEZ", "trx004");
        requestPayload.setHeader("key1", "XXX".getBytes(filterEncoding));
        requestPayload.setHeader("key2", "YY".getBytes(filterEncoding));

        executeAssertBodyPropertyEquals(requestPayload, "XXXYYZ", Arrays.asList(Pair.with("key1", "XXX"), Pair.with("key2", "YY")));
    }

    @Test
    @DisplayName("Verify body expands when inserting key data larger than the body")
    public void testBodyExpansion() throws Exception {
        reInitializeFilter("fixed-body-insert-4");

        Payload requestPayload = createFixedEvent("1234", "trx006");
        requestPayload.setHeader("largeKey", "XYZ987".getBytes(filterEncoding));

        executeAssertBodyEquals(requestPayload, "1234XYZ987");
    }

    @Test
    @DisplayName("Save the prefix of the string as a key and remove it from the body")
    public void testExtractAndRemoveFromBody() throws Exception {
        reInitializeFilter("fixedkeyExtractor1");
        Payload requestPayload = createFixedEvent("ABCDEF", "trx001");
        executeAssertBodyPropertyEquals(requestPayload, "DEF", Pair.with("header", "ABC"));
    }

    @Test
    @DisplayName("Throw exception when data is insufficient")
    public void testDataTooShort() throws Exception {
        reInitializeFilter("fixedkeyExtractor1");
        Payload requestPayload = createFixedEvent("AB", "trx002");
        executeAssertThrows(Exception.class, requestPayload);
    }

    @Test
    @DisplayName("Verify extracted value by key is stored correctly")
    public void testKeyPropertyStored() throws Exception {
        reInitializeFilter("fixedkeyExtractor1");
        Payload requestPayload = createFixedEvent("12345678", "trx003");
        executeAssertBodyPropertyEquals(requestPayload, "45678", Pair.with("header", "123"));
    }

    @Test
    @DisplayName("Throw exception when null body is input")
    public void testNullBodyThrowsException() throws Exception {
        reInitializeFilter("fixedkeyExtractor1");
        Payload requestPayload = createFixedEvent(null, "trx004");
        executeAssertThrows(Exception.class, requestPayload);
    }

    @Test
    @DisplayName("Verify extracted part is removed from body with removeExtracted=true option")
    public void testExtractAndRemoveWithOptionTrue() throws Exception {
        reInitializeFilter("fixedkeyExtractor2");
        Payload requestPayload = createFixedEvent("ABCDEF", "trx005");
        executeAssertBodyPropertyEquals(requestPayload, "DEF", Pair.with("header", "ABC"));
    }

    @Test
    @DisplayName("Verify body is not changed when removeExtracted=false option is used")
    public void testExtractWithoutRemovingBody() throws Exception {
        reInitializeFilter("fixedkeyExtractor3");
        Payload requestPayload = createFixedEvent("ABCDEF", "trx006");

        filter.executeAsync(requestPayload, Executors.newSingleThreadExecutor()).join();

        byte[] extracted = (byte[]) requestPayload.getHeader("header");
        assertArrayEquals("ABC".getBytes(filterEncoding), extracted);
        assertEquals("ABCDEF", new String(requestPayload.getBody(), filterEncoding));

        filter.close();
    }

    @Test
    @DisplayName("Conditionally replace intermediate string values")
    public void testOffsetReplace_conditionally() throws Exception {
        reInitializeFilter("offsetModifier1");
        Payload requestPayload = createFixedEvent("000ABC123", "abc");
        executeAssertBodyEquals(requestPayload, "000XYZ123");
    }

    @Test
    @DisplayName("Apply replace even if find does not match (else logic)")
    public void testOffsetReplace_noFind() throws Exception {
        reInitializeFilter("offsetModifier1");
        Payload requestPayload = createFixedEvent("000DEF123", "abc");
        executeAssertBodyEquals(requestPayload, "000XYZ123");
    }

    @Test
    @DisplayName("Throw exception if input is null")
    public void testNullInput_throwsException() throws Exception {
        reInitializeFilter("offsetModifier1");
        Payload requestPayload = createFixedEvent(null, "abc");
        executeAssertThrows(Exception.class, requestPayload);
    }

    @Test
    @DisplayName("Throw exception if offset exceeds string length")
    public void testOffsetOutOfBounds_throwsException() throws Exception {
        reInitializeFilter("offsetModifier1");
        Payload requestPayload = createFixedEvent("00", "abc");
        executeAssertThrows(Exception.class, requestPayload);
    }
}

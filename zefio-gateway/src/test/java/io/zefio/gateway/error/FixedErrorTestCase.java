package io.zefio.gateway.error;

import io.zefio.core.common.exception.FlowException;
import io.zefio.core.common.exception.FlowResultStatus;
import io.zefio.core.GatewayInterceptor;
import io.zefio.core.factory.PluginContext;
import io.zefio.core.payload.builder.config.*;
import io.zefio.core.payload.util.TelegramFactory;
import io.zefio.core.payload.Payload;
import io.zefio.core.payload.ZefioMessage;
import io.zefio.testsupport.payload.FixedPayloadBuilderFactory;
import io.zefio.testsupport.payload.IPayloadBuilderFactory;
import io.zefio.testsupport.filter.AbstractNormalFilterTestCase;
import org.apache.commons.lang3.ObjectUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FixedError filter test")
public class FixedErrorTestCase extends AbstractNormalFilterTestCase {

    public FixedErrorTestCase() throws Exception {
        super("error-test.yaml", "error1");
    }

    @Override
    public IPayloadBuilderFactory createSenderBuilder() throws Exception {
        FramingField framing = new FramingField();
        framing.setType(FramingType.Length);
        framing.setLengthDataSize(4);
        framing.setLengthDataInclude(false);
        framing.setLengthDataUpdate(true);

        CorrelationField correlation = new CorrelationField(CorrelationIdType.Offset);
        correlation.setStart(43);
        correlation.setLength(20);

        return new FixedPayloadBuilderFactory(
                new Telegram.Builder()
                        .name("fixed")
                        .type(Telegram.Type.Fixed)
                        .values(FixedValues.builder()
                                .framing(framing)
                                .correlation(correlation)
                                .encodingIgnore(false)
                                .build())
                        .build(),
                filterEncoding);
    }

    @Override
    public GatewayInterceptor createFilter(Map<String, Object> context) {
        TelegramFactory.register("fixed", senderBuilder);
        return buildFilterWithContext(context);
    }

    private GatewayInterceptor buildFilterWithContext(Map<String, Object> context) {
        PluginContext ctx = PluginContext.builder()
                .flowName("default")
                .pluginName(getClass().getName())
                .telegramName("fixed")
                .sharedScheduledPool(sharedPool.getValue0())
                .sharedIoPool(sharedPool.getValue1())
                .context(context)
                .build();
        return new StructuredFaultHandler(ctx);
    }

    private void reInitializeFilter(String yamlKey) throws Exception {
        Map<String, Object> context = getContext(yamlKey);
        this.filterEncoding = ObjectUtils.isEmpty(context.get("requestEncoding")) ?
                StandardCharsets.UTF_8 : Charset.forName(context.get("requestEncoding").toString());
        this.filter = buildFilterWithContext(context);
        this.filter.initialise();
    }

    private Payload createFixedEvent(String bodyStr) {
        byte[] bodyBytes = bodyStr != null ? bodyStr.getBytes(filterEncoding) : null;
        Payload payload = new ZefioMessage(bodyBytes, filterEncoding);
        payload.setTelegramName("fixed");
        return payload;
    }

    @Test
    @DisplayName("General error, errorCodeRules, valueOverrides, messageRule test (error1)")
    void testProcessSync_withValidErrorValuesAndFlowException() throws Exception {
        Payload requestPayload = createFixedEvent("ORIGINAL_MESSAGE");
        // The message of FlowException is internally processed into the form "[E123] "
        requestPayload.setThrowable(new FlowException(new IOException(), FlowResultStatus.CUSTOM_FILTER_ERROR, "E123", ""));

        Payload responsePayload = executeAssert(requestPayload);

        String modifiedBodyStr = new String(responsePayload.getBody(), filterEncoding);
        System.out.println("Modified body: " + modifiedBodyStr);

        // 🚀 [Modified] Check the result of merging 13-byte Payload + E123 error code
        assertTrue(modifiedBodyStr.startsWith("0013IABC_M[E123]"));
    }

    @Test
    @DisplayName("NullPointerException error, errorCodeRules, valueOverrides, messageRule test (error2)")
    void testProcessSync_withNullPointerExceptionCause() throws Exception {
        reInitializeFilter("error2");

        Payload requestPayload = createFixedEvent("SIMPLESIMPLESIMPLESIMPLE");
        requestPayload.setThrowable(new FlowException(new NullPointerException("NPE"), FlowResultStatus.CUSTOM_FILTER_ERROR));

        Payload responsePayload = executeAssert(requestPayload);

        String modifiedBodyStr = new String(responsePayload.getBody(), filterEncoding);
        System.out.println("Modified body: " + modifiedBodyStr);

        // 🚀 Passed successfully (10-byte Payload + null merged)
        assertTrue(modifiedBodyStr.startsWith("0010LESZZZnull"));
    }

    @Test
    @DisplayName("General error, errorCodeRules, valueOverrides, messageRule test (error3)")
    void testProcessSync_withNoErrorMessageFormat() throws Exception {
        reInitializeFilter("error3");

        Payload requestPayload = createFixedEvent("SIMPLE");
        // Inject "Error Happened" string (14 bytes)
        requestPayload.setThrowable(new FlowException(new Exception("Error Happened"), FlowResultStatus.CUSTOM_FILTER_ERROR));

        Payload responsePayload = executeAssert(requestPayload);

        String modifiedBodyStr = new String(responsePayload.getBody(), filterEncoding);
        System.out.println("Modified body: " + modifiedBodyStr);

        // 🚀 [Modified] Check the total Payload 16-byte corrected value as a result of merging "Error Happened"
        assertTrue(modifiedBodyStr.startsWith("0016LEError Happened"));
    }
}

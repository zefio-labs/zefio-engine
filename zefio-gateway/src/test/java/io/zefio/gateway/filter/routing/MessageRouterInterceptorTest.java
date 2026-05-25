package io.zefio.gateway.filter.routing;

import io.zefio.core.common.exception.FlowException;
import io.zefio.core.common.exception.FlowResultStatus;
import io.zefio.core.GatewayInterceptor;
import io.zefio.core.factory.PluginContext;
import io.zefio.core.payload.Payload;
import io.zefio.core.payload.PayloadBuilder;
import io.zefio.core.payload.ZefioMessage;
import io.zefio.core.payload.builder.config.*;
import io.zefio.core.payload.util.TelegramFactory;
import io.zefio.gateway.filter.routing.dto.MessageRoutingRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.MDC;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageRouterInterceptorTest {

    @Mock private PluginContext context;
    @Mock private Payload mockPayload;
    @Mock private PayloadBuilder mockFixedBuilder;
    @Mock private PayloadBuilder mockJsonBuilder;
    @Mock private PayloadBuilder mockXmlBuilder;

    @Mock private GatewayInterceptor targetFilter1;
    @Mock private GatewayInterceptor targetFilter2;

    private MessageRouterInterceptor routerFilter;
    private ExecutorService flowExecutor;


    @BeforeEach
    void setUp() throws Exception {
        MDC.clear();
        flowExecutor = Executors.newSingleThreadExecutor();
        TelegramFactory.clear();

        // Set up Mock builders by format and register to factory
        setupMockBuilder(mockFixedBuilder, "fixed-tg", Telegram.Type.Fixed, new FixedValues());
        setupMockBuilder(mockJsonBuilder, "json-tg", Telegram.Type.JSON, new JsonValues());
        setupMockBuilder(mockXmlBuilder, "xml-tg", Telegram.Type.XML, new XmlValues());

        // Set default behavior for target filters
        when(targetFilter1.getPluginName()).thenReturn("target1");
        when(targetFilter2.getPluginName()).thenReturn("target2");
        when(targetFilter1.executeAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(mockPayload));
        when(targetFilter2.executeAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(mockPayload));

        // 🚀 [Deleted] Removed the cumbersome logic mocking BytesUtils! (Leaving it to use the actual utility methods)
    }

    @AfterEach
    void tearDown() {
        flowExecutor.shutdownNow();
    }

    // =========================================================================
    // 🛠️ Helpers
    // =========================================================================
    private void setupMockBuilder(PayloadBuilder builder, String name, Telegram.Type type, TelegramValues values) {
        Telegram tg = mock(Telegram.class);
        when(tg.getName()).thenReturn(name);
        when(tg.getType()).thenReturn(type);
        when(tg.getValues()).thenReturn(values);
        when(builder.getTelegram()).thenReturn(tg);
        TelegramFactory.register(name, builder);
    }

    private void initRouter(String telegramName, MessageRoutingRule... rules) throws Exception {
        Map<String, Object> ctxMap = new HashMap<>();
        ctxMap.put("routingRules", Arrays.asList(rules));

        when(context.getFlowName()).thenReturn("router-flow");
        when(context.getTelegramName()).thenReturn(telegramName);
        when(context.getContext()).thenReturn(ctxMap);

        routerFilter = new MessageRouterInterceptor(context);

        Map<String, GatewayInterceptor> toolMap = new HashMap<>();
        toolMap.put("target1", targetFilter1);
        toolMap.put("target2", targetFilter2);

        Field mapField = MessageRouterInterceptor.class.getDeclaredField("toolModuleMap");
        mapField.setAccessible(true);
        mapField.set(routerFilter, toolMap);
    }

    private Payload createEvent(String telegramName, String bodyStr) {
        byte[] body = (bodyStr == null) ? null : bodyStr.getBytes(StandardCharsets.UTF_8);
        Payload payload = new ZefioMessage(body, StandardCharsets.UTF_8);
        payload.setTelegramName(telegramName);
        return payload;
    }

    private FlowException catchEx(CompletableFuture<Payload> future) {
        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        return (FlowException) thrown.getCause();
    }

    // =========================================================================
    // [1] All scenarios for Fixed format
    // =========================================================================

    @Test @DisplayName("Fixed Success: Extraction based on Offset/Length")
    void testFixed_Success() throws Exception {
        MessageRoutingRule r = new MessageRoutingRule();
        r.setOffset(4); r.setLength(2); r.setMatchValue("OK"); r.setRefModuleName("target1");
        initRouter("fixed-tg", r);
        Payload e = createEvent("fixed-tg", "1234OK789");
        assertNotNull(routerFilter.executeAsync(e, flowExecutor).join());
        verify(targetFilter1).executeAsync(e, flowExecutor);
    }

    @Test @DisplayName("Fixed Defense: BAD_REQUEST when message length is insufficient")
    void testFixed_ShortBody() throws Exception {
        MessageRoutingRule r = new MessageRoutingRule();
        r.setOffset(10); r.setLength(5); r.setMatchValue("OK"); r.setRefModuleName("target1");
        initRouter("fixed-tg", r);
        assertEquals(FlowResultStatus.BAD_REQUEST, catchEx(routerFilter.executeAsync(createEvent("fixed-tg", "SHORT"), flowExecutor)).getStatus());
    }

    // =========================================================================
    // [2] All scenarios for JSON format
    // =========================================================================

    @Test @DisplayName("JSON Success: Based on 1Depth Key")
    void testJson_Key_Success() throws Exception {
        MessageRoutingRule r = new MessageRoutingRule();
        r.setKey("type"); r.setMatchValue("REQ"); r.setRefModuleName("target1");
        initRouter("json-tg", r);
        assertNotNull(routerFilter.executeAsync(createEvent("json-tg", "{\"type\":\"REQ\"}"), flowExecutor).join());
    }

    @Test @DisplayName("JSON Success: JsonPath nested structure")
    void testJson_Path_Success() throws Exception {
        MessageRoutingRule r = new MessageRoutingRule();
        r.setPath("$.header.code"); r.setMatchValue("200"); r.setRefModuleName("target2");
        initRouter("json-tg", r);
        assertNotNull(routerFilter.executeAsync(createEvent("json-tg", "{\"header\":{\"code\":\"200\"}}"), flowExecutor).join());
    }

    @Test @DisplayName("JSON Multi-Depth: Precise extraction with Jayway JsonPath")
    void testJson_DeepNested() throws Exception {
        MessageRoutingRule r = new MessageRoutingRule();
        r.setPath("$.header.routing.info.target"); r.setMatchValue("OQ"); r.setRefModuleName("target1");
        initRouter("json-tg", r);
        assertNotNull(routerFilter.executeAsync(createEvent("json-tg", "{\"header\":{\"routing\":{\"info\":{\"target\":\"OQ\"}}}}"), flowExecutor).join());
    }

    @Test @DisplayName("JSON Array Depth: Array index extraction")
    void testJson_Array() throws Exception {
        MessageRoutingRule r = new MessageRoutingRule();
        r.setPath("$.body.items[1].status"); r.setMatchValue("ACTIVE"); r.setRefModuleName("target2");
        initRouter("json-tg", r);
        assertNotNull(routerFilter.executeAsync(createEvent("json-tg", "{\"body\":{\"items\":[{\"status\":\"IDLE\"},{\"status\":\"ACTIVE\"}]}}"), flowExecutor).join());
    }

    @Test @DisplayName("JSON Defense: BAD_REQUEST on broken format")
    void testJson_InvalidFormat() throws Exception {
        initRouter("json-tg", new MessageRoutingRule());
        assertEquals(FlowResultStatus.BAD_REQUEST, catchEx(routerFilter.executeAsync(createEvent("json-tg", "{ invalid"), flowExecutor)).getStatus());
    }

    // =========================================================================
    // [3] All scenarios for XML format
    // =========================================================================

    @Test @DisplayName("XML Success: 1Depth node")
    void testXml_Key_Success() throws Exception {
        MessageRoutingRule r = new MessageRoutingRule();
        r.setKey("command"); r.setMatchValue("START"); r.setRefModuleName("target1");
        initRouter("xml-tg", r);
        assertNotNull(routerFilter.executeAsync(createEvent("xml-tg", "<root><command>START</command></root>"), flowExecutor).join());
    }

    @Test @DisplayName("XML Success: XPath nested structure")
    void testXml_XPath_Success() throws Exception {
        MessageRoutingRule r = new MessageRoutingRule();
        r.setPath("//body/item/status"); r.setMatchValue("ACTIVE"); r.setRefModuleName("target2");
        initRouter("xml-tg", r);
        assertNotNull(routerFilter.executeAsync(createEvent("xml-tg", "<msg><body><item><status>ACTIVE</status></item></body></msg>"), flowExecutor).join());
    }

    @Test @DisplayName("XML Attribute: XPath attribute value extraction")
    void testXml_Attribute() throws Exception {
        MessageRoutingRule r = new MessageRoutingRule();
        r.setPath("//item[@id='002']/@status"); r.setMatchValue("SUCCESS"); r.setRefModuleName("target2");
        initRouter("xml-tg", r);
        assertNotNull(routerFilter.executeAsync(createEvent("xml-tg", "<data><item id='002' status='SUCCESS'/></data>"), flowExecutor).join());
    }

    @Test @DisplayName("XML Defense: BAD_REQUEST on broken format")
    void testXml_InvalidFormat() throws Exception {
        initRouter("xml-tg", new MessageRoutingRule());
        assertEquals(FlowResultStatus.BAD_REQUEST, catchEx(routerFilter.executeAsync(createEvent("xml-tg", "<msg><status>ACTIVE</msg>"), flowExecutor)).getStatus());
    }

    // =========================================================================
    // [4] Routing logic and exception propagation
    // =========================================================================

    @Test @DisplayName("Multiple rules (Priority): Fallback success")
    void testRouting_PriorityFallback() throws Exception {
        MessageRoutingRule r1 = new MessageRoutingRule(); r1.setKey("type"); r1.setMatchValue("A"); r1.setRefModuleName("target1");
        MessageRoutingRule r2 = new MessageRoutingRule(); r2.setKey("type"); r2.setMatchValue("B"); r2.setRefModuleName("target2");
        initRouter("json-tg", r1, r2);
        assertNotNull(routerFilter.executeAsync(createEvent("json-tg", "{\"type\":\"B\"}"), flowExecutor).join());
        verify(targetFilter2).executeAsync(any(), any());
    }

    @Test @DisplayName("Match failure: When no rule matches")
    void testRouting_NoMatch() throws Exception {
        initRouter("json-tg", new MessageRoutingRule());
        assertEquals(FlowResultStatus.BAD_REQUEST, catchEx(routerFilter.executeAsync(createEvent("json-tg", "{\"x\":\"y\"}"), flowExecutor)).getStatus());
    }

    @Test @DisplayName("Target filter error upstream: Pass lower errors directly to upper level")
    void testRouting_TargetFilterThrowsException() throws Exception {
        MessageRoutingRule r = new MessageRoutingRule(); r.setKey("type"); r.setMatchValue("A"); r.setRefModuleName("target1");
        initRouter("json-tg", r);
        CompletableFuture<Payload> failed = new CompletableFuture<>();
        failed.completeExceptionally(new FlowException(FlowResultStatus.DATABASE_TIMEOUT, "Target Dead"));
        when(targetFilter1.executeAsync(any(), any())).thenReturn(failed);
        assertEquals(FlowResultStatus.DATABASE_TIMEOUT, catchEx(routerFilter.executeAsync(createEvent("json-tg", "{\"type\":\"A\"}"), flowExecutor)).getStatus());
    }

    // =========================================================================
    // [5] Extreme Defense and Edge Cases
    // =========================================================================

    @Test @DisplayName("Extreme Defense: Telegram Values itself is Null")
    void testEdge_NullTelegramValues() throws Exception {
        setupMockBuilder(mockJsonBuilder, "null-values-tg", Telegram.Type.JSON, null);
        initRouter("null-values-tg", new MessageRoutingRule());
        assertEquals(FlowResultStatus.BAD_REQUEST, catchEx(routerFilter.executeAsync(createEvent("null-values-tg", "{}"), flowExecutor)).getStatus());
    }

    @Test @DisplayName("Extreme Defense: When Body is Null")
    void testEdge_NullBody() throws Exception {
        initRouter("fixed-tg", new MessageRoutingRule());
        assertEquals(FlowResultStatus.BAD_REQUEST, catchEx(routerFilter.executeAsync(createEvent("fixed-tg", null), flowExecutor)).getStatus());
    }

    @Test @DisplayName("Configuration mismatch defense: Applying Fixed rule to JSON data")
    void testEdge_MismatchedConfig() throws Exception {
        MessageRoutingRule r = new MessageRoutingRule(); r.setOffset(0); r.setLength(2); r.setMatchValue("A"); r.setRefModuleName("target1");
        initRouter("json-tg", r);
        assertEquals(FlowResultStatus.BAD_REQUEST, catchEx(routerFilter.executeAsync(createEvent("json-tg", "{\"k\":\"v\"}"), flowExecutor)).getStatus());
    }
}

package io.zefio.gateway.filter.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import io.zefio.core.common.base.PluginType;
import io.zefio.core.common.exception.FlowException;
import io.zefio.core.common.exception.FlowResultStatus;
import io.zefio.core.payload.Payload;
import io.zefio.core.BaseGatewayPlugin;
import io.zefio.core.GatewayInterceptor;
import io.zefio.core.beans.ApplicationContextProvider;
import io.zefio.core.beans.DynamicSchemaLoader;
import io.zefio.core.factory.PluginContext;
import io.zefio.core.util.MDCUtils;
import io.zefio.core.payload.PayloadBuilder;
import io.zefio.core.payload.builder.config.FixedValues;
import io.zefio.core.payload.builder.config.JsonValues;
import io.zefio.core.payload.builder.config.Telegram;
import io.zefio.core.payload.builder.config.XmlValues;
import io.zefio.core.payload.util.BytesUtils;
import io.zefio.core.payload.util.TelegramFactory;
import io.zefio.gateway.filter.routing.dto.MessageRouterInterceptorValues;
import io.zefio.gateway.filter.routing.dto.MessageRoutingRule;
import io.zefio.core.telemetry.module.ModuleMetricsAggregator;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Unified Content-Based Router (CBR) capable of multi-depth data extraction.
 * Supports Fixed-length, JSON (JsonPath), and XML (XPath) formats to determine
 * the downstream execution path. Optimized with an index-aligned array structure.
 */
public class MessageRouterInterceptor extends BaseGatewayPlugin implements GatewayInterceptor {
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper xmlMapper = new XmlMapper();
    private final MessageRouterInterceptorValues values;

    /** 💡 Array aligned 1:1 with routingRules index positions for direct O(1) array access. */
    private GatewayInterceptor[] targetFilters;

    public MessageRouterInterceptor(PluginContext context) {
        super(context, new ModuleMetricsAggregator(PluginType.interceptor, context.getFlowName() + "-" + context.getPluginName()));
        this.values = yamlMapper.convertValue(context.getContext(), MessageRouterInterceptorValues.class);
    }

    @Override
    public String getDescription() {
        return "Unified router that selects filters based on configured multi-depth routing rules.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initialise() throws Exception {
        super.initialise();

        List<MessageRoutingRule> rules = values.getRoutingRules();
        if (rules == null || rules.isEmpty()) return;

        DynamicSchemaLoader loader = ApplicationContextProvider.getApplicationContext().getBean(DynamicSchemaLoader.class);

        // Allocate array tracking slots matching the exact rule set boundaries
        this.targetFilters = new GatewayInterceptor[rules.size()];

        for (int i = 0; i < rules.size(); i++) {
            MessageRoutingRule rule = rules.get(i);

            if (StringUtils.isBlank(rule.getType())) {
                log.warn("[{}] Indexed CBR routing rule slot [{}] lacks a valid inner component type.", pluginName, i);
                continue;
            }

            // Create a trace handle context localized by its position index
            String internalPluginName = flowName + "-" + pluginName + "-cbr-target-" + i;

            PluginContext.PluginContextBuilder contextBuilder = PluginContext.builder()
                    .flowName(flowName)
                    .flowLabel(flowLabel)
                    .pluginName(internalPluginName)
                    .pluginLabel(rule.getName())
                    .telegramName(rule.getTelegram())
                    .context(rule.getConfig()) // Directly wire the embedded config map payload
                    .sharedScheduledPool(context.getSharedScheduledPool())
                    .sharedIoPool(context.getSharedIoPool())
                    .flowSyncBridge(syncBridge)
                    .meterRegistry(meterRegistry);

            PluginContext ctx = rule.getExchangePattern() == null ?
                    contextBuilder.build() :
                    contextBuilder.exchangePattern(rule.getExchangePattern()).build();

            Class<GatewayInterceptor> filterClazz = (Class<GatewayInterceptor>) Class.forName(loader.get(rule.getType()).getClassName());
            GatewayInterceptor filter = filterClazz.getConstructor(PluginContext.class).newInstance(ctx);
            filter.initialise();

            // Bind the active execution engine directly into the matching index slot position
            this.targetFilters[i] = filter;
        }
    }

    @Override
    public CompletableFuture<Payload> executeAsync(Payload payload, Executor flowExecutor) {
        this.metricsAggregator.incrementPayloadReceivedCount();
        long start = System.currentTimeMillis();

        try {
            MDCUtils.restoreMdc(payload);

            Charset encoding = payload.getCurrentEncoding();
            byte[] body = payload.getBody();

            // Retrieve Telegram metadata from the factory based on the payload label
            String telegramName = payload.getTelegramName();
            if (telegramName == null) {
                throw new FlowException(FlowResultStatus.MESSAGE_FORMAT_ERROR, "Routing failed: Telegram name is missing.");
            }

            PayloadBuilder currentBuilder = TelegramFactory.getBuilder(telegramName);
            if (currentBuilder == null || currentBuilder.getTelegram() == null) {
                throw new FlowException(FlowResultStatus.MESSAGE_FORMAT_ERROR, "Routing failed: Telegram metadata not found for " + telegramName);
            }

            Telegram.Type telegramType = currentBuilder.getTelegram().getType();
            Object telegramValues = currentBuilder.getTelegram().getValues();

            GatewayInterceptor selectedFilter = null;
            List<MessageRoutingRule> rules = values.getRoutingRules();

            // Evaluate rules sequentially (First-Hit-Win)
            for (int i = 0; i < rules.size(); i++) {
                MessageRoutingRule rule = rules.get(i);
                String extractedValue = null;

                // 1. Fixed Format: Offset-based extraction
                if (telegramType == Telegram.Type.Fixed && telegramValues instanceof FixedValues && rule.getOffset() != null) {
                    int requiredLength = rule.getOffset() + rule.getLength();
                    if (body.length >= requiredLength) {
                        extractedValue = new String(BytesUtils.bytesOffsetCopy(body, rule.getOffset(), rule.getLength()), encoding).trim();
                    }
                }
                // 2. JSON Format: JsonPath support for complex navigation
                else if (telegramType == Telegram.Type.JSON && telegramValues instanceof JsonValues) {
                    if (StringUtils.isNotBlank(rule.getPath())) {
                        try {
                            String jsonStr = new String(body, encoding);
                            Object result = JsonPath.read(jsonStr, rule.getPath());
                            if (result != null) extractedValue = String.valueOf(result);
                        } catch (PathNotFoundException e) {
                            // Suppress error and proceed to the next rule if path is missing
                        }
                    } else if (StringUtils.isNotBlank(rule.getKey())) {
                        JsonNode root = jsonMapper.readTree(body);
                        JsonNode valNode = root.path(rule.getKey());
                        if (!valNode.isMissingNode()) extractedValue = valNode.asText();
                    }
                }
                // 3. XML Format: XPath support for attribute and deep-node selection
                else if (telegramType == Telegram.Type.XML && telegramValues instanceof XmlValues) {
                    if (StringUtils.isNotBlank(rule.getPath())) {
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        factory.setNamespaceAware(true);
                        DocumentBuilder docBuilder = factory.newDocumentBuilder();
                        Document doc = docBuilder.parse(new ByteArrayInputStream(body));
                        XPath xPath = XPathFactory.newInstance().newXPath();
                        extractedValue = (String) xPath.compile(rule.getPath()).evaluate(doc, XPathConstants.STRING);
                        if (extractedValue != null) extractedValue = extractedValue.trim();
                        if (StringUtils.isEmpty(extractedValue)) extractedValue = null;
                    } else if (StringUtils.isNotBlank(rule.getKey())) {
                        JsonNode root = xmlMapper.readTree(body);
                        JsonNode valNode = root.path(rule.getKey());
                        if (!valNode.isMissingNode()) extractedValue = valNode.asText();
                    }
                }

                if (extractedValue != null && extractedValue.equals(rule.getMatchValue())) {
                    // 💡 Direct array slot retrieval by index pointer position - Pure O(1) performance
                    selectedFilter = this.targetFilters[i];
                    if (selectedFilter != null) {
                        log.info("Routing matched slot [{}]: [{}] (Value: {}, Target Filter: {})", i, rule.getName(), extractedValue, selectedFilter.getPluginName());
                        break;
                    }
                }
            }

            if (selectedFilter == null) {
                FlowException ex = new FlowException(FlowResultStatus.BAD_REQUEST, "No routing rule matched for the incoming packet.");
                handleMetrics(payload, ex, start);

                CompletableFuture<Payload> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(ex);
                return failedFuture;
            }

            // Delegation of Control: Pass the execution and the current flowExecutor to the target filter.
            return selectedFilter.executeAsync(payload, flowExecutor)
                    .whenComplete((result, ex) -> {
                        handleMetrics(result, ex, start);
                    });

        } catch (Exception e) {
            log.error("Routing process failed: {}", e.getMessage());
            FlowException ex = new FlowException(e, FlowResultStatus.BAD_REQUEST);
            handleMetrics(payload, ex, start);

            CompletableFuture<Payload> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(ex);
            return failedFuture;
        }
    }
}

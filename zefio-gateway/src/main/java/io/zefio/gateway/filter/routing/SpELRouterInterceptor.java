package io.zefio.gateway.filter.routing;

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
import io.zefio.core.payload.ExchangePattern;
import io.zefio.core.payload.spel.PayloadExpressionEvaluator;
import io.zefio.gateway.filter.routing.dto.SpELRouterInterceptorValues;
import io.zefio.gateway.filter.routing.dto.SpELRoutingRule;
import io.zefio.core.telemetry.module.ModuleMetricsAggregator;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Intelligent router that evaluates SpEL expressions to dynamically
 * select target modules for payload delegation using flattened CP contexts.
 * Optimized using an index-aligned execution array instead of a string-keyed map.
 */
public class SpELRouterInterceptor extends BaseGatewayPlugin implements GatewayInterceptor {
    private final SpELRouterInterceptorValues values;

    /** 💡 Array aligned 1:1 with routingRules index positions for direct O(1) array access. */
    private GatewayInterceptor[] targetFilters;

    public SpELRouterInterceptor(PluginContext context) {
        super(context, new ModuleMetricsAggregator(PluginType.interceptor, context.getFlowName() + "-" + context.getPluginName()));
        this.values = yamlMapper.convertValue(context.getContext(), SpELRouterInterceptorValues.class);
    }

    @Override
    public String getDescription() {
        return "Evaluates SpEL expressions to dynamically select target modules using flattened definitions.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initialise() throws Exception {
        super.initialise();

        List<SpELRoutingRule> rules = values.getRoutingRules();
        if (rules == null || rules.isEmpty()) return;

        DynamicSchemaLoader loader = ApplicationContextProvider.getApplicationContext().getBean(DynamicSchemaLoader.class);

        // Allocate array tracking slots matching the exact rule set boundaries
        this.targetFilters = new GatewayInterceptor[rules.size()];

        // Pre-initialize and cache target module instances directly from flattened rule values
        for (int i = 0; i < rules.size(); i++) {
            SpELRoutingRule rule = rules.get(i);

            if (StringUtils.isBlank(rule.getType())) {
                log.warn("[{}] Flattened SpEL routing rule at slot [{}] lacks a valid component type.", pluginName, i);
                continue;
            }

            // Create a deterministic internal tracking name using position index
            String internalPluginName = flowName + "-" + pluginName + "-spel-target-" + i;

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

            // Map-bound priority resolution for target pattern contracts
            ExchangePattern targetPattern = rule.getExchangePattern();

            PluginContext ctx = (targetPattern == null) ?
                    contextBuilder.build() :
                    contextBuilder.exchangePattern(targetPattern).build();

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
            GatewayInterceptor selectedFilter = null;
            List<SpELRoutingRule> rules = values.getRoutingRules();

            // Evaluate conditions sequentially (First-Hit-Win)
            for (int i = 0; i < rules.size(); i++) {
                SpELRoutingRule rule = rules.get(i);
                Boolean isMatched = PayloadExpressionEvaluator.evaluate(rule.getCondition(), payload, Boolean.class);

                if (Boolean.TRUE.equals(isMatched)) {
                    // 💡 Direct array slot retrieval by index pointer position - Pure O(1) performance
                    selectedFilter = this.targetFilters[i];
                    if (selectedFilter != null) {
                        log.info("Routing rule matched slot [{}]: [{}] -> Target Filter: [{}]", i, rule.getName(), selectedFilter.getPluginName());
                        break;
                    }
                }
            }

            // Fail-Fast rule policy compliance block
            if (selectedFilter == null) {
                FlowException ex = new FlowException(FlowResultStatus.DYNAMIC_ROUTE_NOT_FOUND, "No routing rule matched for the incoming transaction.");
                handleMetrics(payload, ex, start);
                CompletableFuture<Payload> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(ex);
                return failedFuture;
            }

            // Continuous asynchronous execution context handover delegation pass
            return selectedFilter.executeAsync(payload, flowExecutor)
                    .whenComplete((result, ex) -> {
                        handleMetrics(result, ex, start);
                    });

        } catch (Exception e) {
            log.error("Routing evaluation failed: {}", e.getMessage(), e);
            FlowException ex = new FlowException(e, FlowResultStatus.SPEL_EVALUATION_ERROR);
            handleMetrics(payload, ex, start);

            CompletableFuture<Payload> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(ex);
            return failedFuture;
        }
    }
}

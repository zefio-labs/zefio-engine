package io.zefio.core.beans;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zefio.core.config.flow.TelegramsConfiguration;
import io.zefio.core.schema.DslConfigurationLoader;
import io.zefio.core.config.flow.FlowSettings;
import io.zefio.core.config.flow.FlowConfiguration;
import io.zefio.core.config.flow.StepConfiguration;
import io.zefio.core.config.global.GlobalOptionsProperties;
import io.zefio.core.PipelineService;
import io.zefio.core.factory.FlowServiceFactory;
import io.zefio.core.engine.pool.SharedPoolManager;
import io.zefio.core.engine.pool.SharedPools;
import io.zefio.core.payload.util.TelegramFactory;
import io.zefio.core.util.FlowConfigUtils;
import io.zefio.jdk.registry.ComponentRegistry;
import io.zefio.core.telemetry.GlobalMonitorManager;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@RefreshScope
public class FlowSettingsBean implements InitializingBean, DisposableBean {
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Getter
    @Value("${server.port}")
    private int serverPort;

    @Autowired
    @Getter
    private GlobalOptionsProperties globalOptionsProperties;

    @Getter
    private FlowSettings settings;

    @Autowired
    private SharedPoolManager sharedPoolManager;

    @Autowired
    private GlobalMonitorManager monitorManager;

    @Autowired
    private FlowServiceFactory flowFactory;

    @Autowired
    private FlowSyncBridge flowSyncBridge;

    @Getter
    private final List<PipelineService> flowServiceList = new CopyOnWriteArrayList<>();

    @Override
    public void afterPropertiesSet() throws Exception {
        log.info("[DSL Loader] Initializing system...");
        this.settings = loadConfiguration();
        applyConfiguration(this.settings);
    }

    private FlowSettings loadConfiguration() throws Exception {
        String mainConfigPath = FlowConfigUtils.getMainConfigPath();
        log.info("[DSL Loader] Initializing Flow configuration from: {}", mainConfigPath);

        DslConfigurationLoader loader = new DslConfigurationLoader();
        Map<String, Object> mergedYamlMap = loader.loadAndMerge(mainConfigPath);

        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.convertValue(mergedYamlMap, FlowSettings.class);
    }

    private void applyConfiguration(FlowSettings newSettings) {
        TelegramFactory.clear();
        if (newSettings.getTelegrams() != null) {
            newSettings.getTelegrams().forEach((name, config) -> {
                try { TelegramFactory.register(name, config.getType(), config.getConfig()); }
                catch (Exception e) { log.error("[Telegram Init] Error: " + name, e); }
            });
        }

        SharedPools pools = sharedPoolManager.setupPools();
        monitorManager.startGlobalMonitoring(pools);
        monitorManager.printConfigurationLog();

        if (newSettings.getFlows() != null) {
            newSettings.getFlows().forEach(config -> {
                PipelineService service = flowFactory.build(config, pools);
                if (service != null) flowServiceList.add(service);
            });
        }
    }

    /**
     * Optimized Hot-Swap Sequence aligned with the Control Plane's atomic state transfer.
     */
    public synchronized void hotSwap(FlowSettings newSettings) throws Exception {
        log.info("🚀 [Hot-Swap] Initiating non-blocking Blue-Green deployment sequence...");
        try {
            SharedPools pools = sharedPoolManager.setupPools();

            // ========================================================================
            // 🛡️ Context Management Layer (CP-Driven Optimization)
            // ========================================================================

            // 1. Synchronize Telegram layouts directly from the CP Snapshot
            if (newSettings.getTelegrams() == null || newSettings.getTelegrams().isEmpty()) {
                log.info("[Hot-Swap] Retaining previous running telegram layouts context.");
                if (this.settings != null && this.settings.getTelegrams() != null) {
                    newSettings.setTelegrams(this.settings.getTelegrams());
                }
            } else if (this.settings != null && this.settings.getTelegrams() != null) {
                Map<String, TelegramsConfiguration> mergedTelegrams = new HashMap<>(this.settings.getTelegrams());
                mergedTelegrams.putAll(newSettings.getTelegrams());
                newSettings.setTelegrams(mergedTelegrams);
            }

            // Refresh the active standalone Telegram Factory with the updated CP specifications
            if (newSettings.getTelegrams() != null) {
                newSettings.getTelegrams().forEach((name, config) -> {
                    try { TelegramFactory.register(name, config.getType(), config.getConfig()); }
                    catch (Exception e) { log.error("[Telegram Hot-Swap Init] Error: " + name, e); }
                });
            }
            // ========================================================================

            if (newSettings.getFlows() == null || newSettings.getFlows().isEmpty()) {
                log.warn("⚠️ No active flow definitions identified in the deployment payload.");
                return;
            }

            for (FlowConfiguration newFlowConfig : newSettings.getFlows()) {
                PipelineService oldFlow = null;
                for (PipelineService activeService : this.flowServiceList) {
                    if (activeService.getName().equals(newFlowConfig.getName())) {
                        oldFlow = activeService;
                    }
                }

                if (oldFlow != null) {
                    log.info("🔄 [Hot-Swap] Existing active flow pipeline [{}] detected. Commencing swap.", oldFlow.getName());

                    if (newFlowConfig.getIngress() != null) {
                        ComponentRegistry.unregisterIngress(newFlowConfig.getIngress().getName());
                    }
                    if (newFlowConfig.getSteps() != null) {
                        for (StepConfiguration step : newFlowConfig.getSteps()) {
                            recursiveUnregisterComponents(step);
                        }
                    }

                    oldFlow.stopListening();
                    this.flowServiceList.remove(oldFlow);

                    PipelineService newFlowService = flowFactory.build(newFlowConfig, pools);

                    if (newFlowService != null) {
                        newFlowService.start();
                        this.flowServiceList.add(newFlowService);
                        log.info("✨ [Hot-Swap] New flow instance [{}] successfully claimed port and went active.", newFlowService.getName());
                    }

                    final PipelineService finalOldFlow = oldFlow;
                    pools.getSharedScheduledPool().submit(() -> {
                        try {
                            int checkCount = 0;
                            boolean isDrained = false;
                            log.info("[WatchDog] Monitoring transaction drain for deprecated flow pipeline: {}", finalOldFlow.getName());

                            while (checkCount < 120) {
                                if (finalOldFlow.isAllQueueEmpty()) {
                                    isDrained = true;
                                    break;
                                }
                                Thread.sleep(500);
                                checkCount++;
                            }

                            if (!isDrained) {
                                log.warn("[WatchDog] Drain timeout exceeded for old flow [{}]. Forcing cleanup sequence.", finalOldFlow.getName());
                            } else {
                                log.info("[WatchDog] All in-flight payloads drained successfully for old flow [{}].", finalOldFlow.getName());
                            }

                            finalOldFlow.shutdown();
                            log.info("✅ [Hot-Swap] Deprecated flow pipeline [{}] resources fully reclaimed.", finalOldFlow.getName());
                        } catch (Exception e) {
                            log.error("[WatchDog] Critical failure during old flow resource reclamation", e);
                        }
                    });

                } else {
                    PipelineService newService = flowFactory.build(newFlowConfig, pools);
                    if (newService != null) {
                        newService.start();
                        this.flowServiceList.add(newService);
                        log.info("🆕 [Hot-Swap] Provisioned entirely new flow pipeline [{}].", newService.getName());
                    }
                }
            }

            this.settings = newSettings;
            log.info("Base context memory refresh complete. Hot-swap transaction loop fully active.");

        } catch (Exception e) {
            log.error("❌ [Hot-Swap] Operational failure during non-blocking pipeline reload", e);
            throw e;
        }
    }

    private void recursiveUnregisterComponents(StepConfiguration step) {
        if (step == null) return;
        ComponentRegistry.unregisterUpstream(step.getName());
        ComponentRegistry.unregisterInterceptor(step.getName());
        ComponentRegistry.unregisterError(step.getName());

        if (step.getSteps() != null) {
            for (StepConfiguration subStep : step.getSteps()) recursiveUnregisterComponents(subStep);
        }
        if (step.getFallbackSteps() != null) {
            for (StepConfiguration fallbackStep : step.getFallbackSteps()) recursiveUnregisterComponents(fallbackStep);
        }
        if (step.getCases() != null) {
            for (io.zefio.core.config.flow.SwitchCaseConfig caseConfig : step.getCases()) {
                if (caseConfig.getSteps() != null) {
                    for (StepConfiguration caseStep : caseConfig.getSteps()) recursiveUnregisterComponents(caseStep);
                }
            }
        }
        if (step.getDefaultSteps() != null) {
            for (StepConfiguration defStep : step.getDefaultSteps()) recursiveUnregisterComponents(defStep);
        }
    }

    @Override
    public void destroy() throws Exception {
        log.info("Starting graceful shutdown for FlowSettingsBean...");
        if (!this.flowServiceList.isEmpty()) {
            for (PipelineService flow : this.flowServiceList) { flow.shutdown(); }
            this.flowServiceList.clear();
        }
        if (flowSyncBridge != null) flowSyncBridge.destroy();
        if (sharedPoolManager != null) sharedPoolManager.destroy();
        ComponentRegistry.clear();
    }
}

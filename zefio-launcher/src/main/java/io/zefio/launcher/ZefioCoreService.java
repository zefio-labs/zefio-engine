package io.zefio.launcher;

import io.zefio.core.common.exception.FlowResultStatus;
import io.zefio.core.common.util.CommonUtils;
import io.zefio.core.config.ZefioEngineProperties;
import io.zefio.core.PipelineService;
import io.zefio.core.beans.FlowSettingsBean;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Core service orchestrating the lifecycle of all pipelines.
 * Manages flow startup, graceful shutdown, and the Hot-Deploy watchdog.
 */
@Service
public class ZefioCoreService implements io.zefio.core.ZefioCoreService, DisposableBean {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Autowired
    private FlowSettingsBean flowSettings;
    @Autowired
    private ZefioEngineProperties zefioEngineProperties;
    @Autowired
    private Environment environment;
    @Autowired
    private ContextRefresher contextRefresher;

    private ExecutorService watchDogExecutor;
    private List<PipelineService> flowServices;

    @Override
    public void execute() {
        if (this.zefioEngineProperties.getGlobalOptions().isHotDeploy()) {
            this.watchDogExecutor = Executors.newSingleThreadExecutor(CommonUtils.getThreadFactory("file-watch-"));
            try {
                this.watchDogExecutor.submit(new ConfigurationDirectoryWatcher(environment, this, contextRefresher));
            } catch (Exception e) {
                log.error("[WatchDog] Failed to initialize directory watcher: {}", e.getMessage());
            }
        }

        try {
            startAllFlows();
        } catch (Exception e) {
            log.error("[Lifecycle] Critical error during startup: {}", e.getMessage());
            shutdown();
        }
    }

    @Override
    public void startAllFlows() throws Exception {
        this.flowServices = this.flowSettings.getFlowServiceList();

        for (PipelineService flowService : this.flowServices) {
            try {
                flowService.start();
            } catch (Exception e) {
                log.error("Flow Start Failed [{}]: {}", FlowResultStatus.SYSTEM_SHUTDOWN.name(), e.getMessage());
                throw e;
            }
        }
        log.info("{}", StringUtils.center(" FLOWS STARTED SUCCESSFULLY ", 70, "■"));
    }

    @Override
    public void shutdownFlowsOnly() {
        if (this.flowServices != null && !this.flowServices.isEmpty()) {
            log.info("{}", StringUtils.center(" STOPPING FLOW INGRESS (GRACEFUL) ", 70, "■"));

            // Parallel shutdown to overlap grace periods
            this.flowServices.parallelStream().forEach(PipelineService::shutdown);
            this.flowServices.clear();
        }
        log.info("{}", StringUtils.center(" FLOW SHUTDOWN COMPLETE ", 70, "■"));
    }

    @Override
    public void shutdown() {
        if (this.watchDogExecutor != null && !this.watchDogExecutor.isShutdown()) {
            this.watchDogExecutor.shutdownNow();
        }
        shutdownFlowsOnly();
    }

    @Override
    public void destroy() {
        log.info("[Lifecycle] Stopping Zefio Service and cleaning up resources...");
        if (this.watchDogExecutor != null && !this.watchDogExecutor.isShutdown()) {
            this.watchDogExecutor.shutdownNow();
        }
    }
}

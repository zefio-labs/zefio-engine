package io.zefio.core.telemetry;

import io.zefio.core.config.ZefioEngineProperties;
import io.zefio.core.config.monitor.MonitorProperties;
import io.zefio.core.telemetry.history.InMemoryHistoryManager;
import io.zefio.core.telemetry.jvm.JvmMonitorLogger;
import io.zefio.core.telemetry.logging.LoggingMonitorLogger;
import io.zefio.core.telemetry.provider.IMonitorPoolProvider;
import io.zefio.core.telemetry.stat.StatLogger;
import io.zefio.core.telemetry.thread.ThreadPoolMonitorLogger;
import io.zefio.core.telemetry.thread.ThreadPoolStateTracker;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Orchestrates global monitoring components and manages their lifecycles.
 * Responsible for initializing, starting, and stopping various telemetry loggers
 * such as ThreadPool, JVM, and Logging monitors.
 */
@Component
@RequiredArgsConstructor
public class GlobalMonitorManager implements InitializingBean, DisposableBean {
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    // Immutable dependencies managed via constructor injection
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final ZefioEngineProperties zefioEngineProperties;
    private final InMemoryHistoryManager historyManager;

    // Runtime logger state contexts (Non-final due to dynamic reallocation)
    private ThreadPoolMonitorLogger sharedIoPoolLogger;
    private JvmMonitorLogger jvmLogger;
    private LoggingMonitorLogger loggingLogger;

    /**
     * Initializes static dependencies for telemetry utilities after property injection.
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        StatLogger.setHistoryManager(this.historyManager);
        log.info("[GlobalMonitorManager] Injected InMemoryHistoryManager into StatLogger.");
    }

    /**
     * Ensures a graceful shutdown of all active monitoring loggers when the context is destroyed.
     */
    @Override
    public void destroy() throws Exception {
        stopAll();
        log.info("[GlobalMonitorManager] Disposed GlobalMonitorManager resources.");
    }

    /**
     * Starts global monitoring components based on the provided pool provider.
     */
    public void startGlobalMonitoring(IMonitorPoolProvider poolProvider) {
        stopAll();

        // Source target monitor settings block directly from the unified properties gateway
        MonitorProperties monitorProps = zefioEngineProperties.getMonitor();

        MonitorInitContext.MonitorInitContextBuilder monitorInitContextBuilder = MonitorInitContext.builder()
                .sharedScheduler(poolProvider.getSharedScheduledPool())
                .meterRegistry(meterRegistry);

        // 1. Shared IO Pool Monitoring
        if (monitorProps.getDefaultOptions().isEnableThreadPoolLogger()) {
            ThreadPoolStateTracker tracker = new ThreadPoolStateTracker(poolProvider.getSharedIoPool());
            this.sharedIoPoolLogger = new ThreadPoolMonitorLogger(
                    monitorInitContextBuilder.flowName("Shared-IO-Pool").flowLabel("Shared-IO-Pool").build(),
                    tracker, monitorProps.getThreadPoolThreshold());
            this.sharedIoPoolLogger.start();
        }

        // 2. Global JVM & Logging Monitoring
        if (monitorProps.getDefaultOptions().isEnableGlobalMonitorLogger()) {
            this.jvmLogger = new JvmMonitorLogger(
                    monitorInitContextBuilder.flowName("JVM-Global").flowLabel("JVM-Global").build(),
                    monitorProps.getJvmMonitorThreshold());
            this.jvmLogger.start();

            this.loggingLogger = new LoggingMonitorLogger(
                    monitorInitContextBuilder.flowName("Logging-Global").flowLabel("Logging-Global").build(),
                    monitorProps.getLoggingMonitorThreshold(),
                    this.historyManager);
            if (this.loggingLogger.initializeAndStart()) {
                this.loggingLogger.start();
            }
        }
    }

    /**
     * Logs the current monitoring configuration and thresholds for diagnostic purposes.
     */
    public void printConfigurationLog() {
        log.info("{}", StringUtils.center(" MONITORING SETTINGS APPLIED ", 70, "■"));

        // Source target monitor settings block directly from the unified properties gateway
        MonitorProperties monitorProps = zefioEngineProperties.getMonitor();

        // 1. General Logging Options
        log.info("  [Logging Options]");
        log.info("    > ThreadPool Logger Enabled: {}", monitorProps.getDefaultOptions().isEnableThreadPoolLogger());
        log.info("    > Queue Logger Enabled: {}", monitorProps.getDefaultOptions().isEnableQueueLogger());
        log.info("    > Module Metrics Logger Enabled: {}", monitorProps.getDefaultOptions().isEnableModuleMetricsLogger());

        // 2. ThreadPool Thresholds
        log.info("  [ThreadPool Threshold]");
        log.info("    > Monitor Interval (Sec): {}", monitorProps.getThreadPoolThreshold().getIntervalSeconds());
        log.info("    > Queue Capacity Ratio (Warn): {}", monitorProps.getThreadPoolThreshold().getQueueCapacityRatio());
        log.info("    > Pool Max Ratio (Warn): {}", monitorProps.getThreadPoolThreshold().getPoolMaxRatio());
        log.info("    > Core Exhaust Ratio (Warn): {}", monitorProps.getThreadPoolThreshold().getCoreExhaustRatio());

        // 3. Module Metrics Thresholds (Formerly Filter Metrics)
        log.info("  [Module Metrics Threshold]");
        log.info("    > Monitor Interval (Sec): {}", monitorProps.getModuleMetricsThreshold().getIntervalSeconds());
        log.info("    > Failure Rate (Warn): {}", monitorProps.getModuleMetricsThreshold().getFailureRate());
        log.info("    > Avg Execution Time (Warn, ms): {}", monitorProps.getModuleMetricsThreshold().getAvgExecTimeMs());

        // 4. Netty EventLoop Thresholds
        log.info("  [Netty EventLoop Threshold]");
        log.info("    > Monitor Interval (Sec): {}", monitorProps.getNettyEventLoopThreshold().getIntervalSeconds());
        log.info("    > Pending Task Ratio (Warn): {}", monitorProps.getNettyEventLoopThreshold().getPendingTaskRatio());
        log.info("    > Active Thread Ratio (Warn): {}", monitorProps.getNettyEventLoopThreshold().getActiveThreadRatio());

        // 5. JVM Monitor Thresholds
        log.info("  [JVM Monitor Threshold]");
        log.info("    > Monitor Interval (Sec): {}", monitorProps.getJvmMonitorThreshold().getIntervalSeconds());
        log.info("    > Heap Usage Ratio (Warn): {}", monitorProps.getJvmMonitorThreshold().getHeapUsageRatio());
        log.info("    > Old Gen GC Time (Warn, ms): {}", monitorProps.getJvmMonitorThreshold().getOldGenGcTimeMs());
        log.info("    > GC Frequency (Sec/Count): {}", monitorProps.getJvmMonitorThreshold().getGcFrequencySec());

        // 6. Logging Monitor Thresholds
        log.info("  [Logging Monitor Threshold]");
        log.info("    > Monitor Interval (Sec): {}", monitorProps.getLoggingMonitorThreshold().getIntervalSeconds());
        log.info("    > Max Queue Capacity Ratio (Warn): {}", monitorProps.getLoggingMonitorThreshold().getMaxQueueCapacityRatio());
        log.info("    > Error Burst Rate (Warn, count/sec): {}", monitorProps.getLoggingMonitorThreshold().getErrorBurstRate());

        log.info("{}", StringUtils.center(" END OF SETTINGS ", 70, "■"));
    }

    public void resetAll() {
        if (sharedIoPoolLogger != null) sharedIoPoolLogger.reset();
        if (jvmLogger != null) jvmLogger.reset();
        if (loggingLogger != null) loggingLogger.reset();
    }

    public void stopAll() {
        if (sharedIoPoolLogger != null) sharedIoPoolLogger.shutdown();
        if (jvmLogger != null) jvmLogger.shutdown();
        if (loggingLogger != null) loggingLogger.shutdown();
        log.info("[GlobalMonitorManager] All monitoring loggers stopped.");
    }
}

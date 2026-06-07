package io.zefio.core.config.monitor;

import lombok.Getter;
import lombok.Setter;

/**
 * Comprehensive monitoring configuration for the engine's health and performance.
 * Defines thresholds for alerting and automatic resource scaling across different subsystems.
 */
@Getter
@Setter
public class MonitorProperties {
    private MetricsReset metricsReset = new MetricsReset();
    private DefaultOptions defaultOptions = new DefaultOptions();

    // Monitoring thresholds for various resource pools
    private ThreadPoolThreshold threadPoolThreshold = new ThreadPoolThreshold();
    private ModuleMetricsThreshold moduleMetricsThreshold = new ModuleMetricsThreshold();
    private NettyEventLoopThreshold nettyEventLoopThreshold = new NettyEventLoopThreshold();
    private ConnectionPoolThreshold connectionPoolThreshold = new ConnectionPoolThreshold();
    private QueueThreshold queueMonitorThreshold = new QueueThreshold();
    private JvmMonitorThreshold jvmMonitorThreshold = new JvmMonitorThreshold();
    private LoggingMonitorThreshold loggingMonitorThreshold = new LoggingMonitorThreshold();

    @Getter @Setter
    public static class MetricsReset {
        private String cron = "0 0 0 * * *"; // Default: Midnight every day
        private String zone = "Asia/Seoul";
    }

    @Getter @Setter
    public static class DefaultOptions {
        private boolean enableThreadPoolLogger = false;
        private boolean enableQueueLogger = false;
        private boolean enableModuleMetricsLogger = false;
        private boolean enableGlobalMonitorLogger = false;
    }

    @Getter @Setter
    public static class ThreadPoolThreshold {
        private int intervalSeconds = 5;
        private double queueCapacityRatio = 0.8;
        private double poolMaxRatio = 0.9;
        private double coreExhaustRatio = 0.9;
    }

    @Getter @Setter
    public static class ModuleMetricsThreshold {
        private int intervalSeconds = 5;
        private double failureRate = 0.10;
        private int avgExecTimeMs = 500;
    }

    @Getter @Setter
    public static class NettyEventLoopThreshold {
        private int intervalSeconds = 5;
        private double pendingTaskRatio = 2.0;
        private double activeThreadRatio = 0.8;
    }

    @Getter @Setter
    public static class ConnectionPoolThreshold {
        private int intervalSeconds = 5;
        private double usageRatio = 0.8;
    }

    @Getter @Setter
    public static class QueueThreshold {
        private int intervalSeconds = 3; // Queues are monitored more frequently
        private double usageRatio = 0.8;
    }

    @Getter @Setter
    public static class JvmMonitorThreshold {
        private int intervalSeconds = 60; // JVM monitoring is less frequent to reduce overhead
        private double heapUsageRatio = 0.85;
        private long oldGenGcTimeMs = 500;
        private long gcFrequencySec = 60;
    }

    @Getter @Setter
    public static class LoggingMonitorThreshold {
        private int intervalSeconds = 5;
        private double maxQueueCapacityRatio = 0.75;
        private int errorBurstRate = 5;
    }
}

package io.zefio.core.config.threadpool;

import lombok.Data;

/**
 * Defines the specifications for shared thread pools.
 * Includes defensive defaults to ensure system stability even if external
 * configuration files are missing or incomplete.
 */
@Data
public class SharedThreadPoolProperties {
    private ThreadPoolSetting scheduler = new ThreadPoolSetting();
    private ThreadPoolSetting failsafe = new ThreadPoolSetting();
    private ThreadPoolSetting io = new ThreadPoolSetting();

    public SharedThreadPoolProperties() {

        // 1. Shared IO Pool: Heavy-duty pool for primary non-blocking I/O operations.
        this.io.setFixedPoolSize(200);
        this.io.setQueueCapacity(5000);
        this.io.setThreadNamePrefix("Shared-IO");

        // 2. Shared Scheduler Pool: Lightweight pool for monitoring and heartbeat tasks.
        this.scheduler.setFixedPoolSize(5);
        this.scheduler.setThreadNamePrefix("Shared-Scheduler");

        // 3. Shared Failsafe Pool: Dedicated pool for error recovery and circuit breaker tasks.
        this.failsafe.setFixedPoolSize(50);
        this.failsafe.setThreadNamePrefix("Shared-Failsafe");
    }

    @Data
    public static class ThreadPoolSetting {
        private int fixedPoolSize = 200;
        private int queueCapacity = 5000;
        private String threadNamePrefix = "Shared-Pool";
        private AutoScalingConfig autoScaling = new AutoScalingConfig();
    }

    @Data
    public static class AutoScalingConfig {
        private boolean enabled = false;
        private int maxSize = 300;
        private double threshold = 0.8;
        private int checkInterval = 5;
        private int scaleUpStep = 20;
        private int scaleDownStep = 10;
    }
}

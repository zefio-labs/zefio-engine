package io.zefio.core.config.global;

import lombok.Data;

/**
 * Global configuration properties for the engine's operational policies.
 * Includes default retry strategies, synchronization bridge capacities for Request-Reply,
 * and global exception handling behaviors.
 */
@Data
public class GlobalOptionsProperties {
    private boolean hotDeploy = false;

    private DefaultRetry defaultRetry = new DefaultRetry();
    private SyncBridge syncBridge = new SyncBridge();
    private ExceptionPolicyProperties exceptionPolicy = new ExceptionPolicyProperties();

    @Data
    public static class DefaultRetry {
        private Boolean enabled = false;
        private Integer maxRetries = 3;
        private Integer delay = 100; // Delay between retries in milliseconds
    }

    @Data
    public static class SyncBridge {
        /** Maximum number of concurrent Request-Reply correlations. */
        private int maxCapacity = 50000;
        /** Time-to-live for a correlation entry in the bridge. */
        private int expireSeconds = 60;
    }
}

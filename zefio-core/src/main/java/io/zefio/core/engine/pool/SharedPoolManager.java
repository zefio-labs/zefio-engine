package io.zefio.core.engine.pool;

import io.zefio.core.common.exception.FlowException;
import io.zefio.core.common.exception.FlowResultStatus;
import io.zefio.core.common.util.DisposableExecutor;
import io.zefio.core.config.ZefioEngineProperties;
import io.zefio.core.config.threadpool.SharedThreadPoolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Manages the lifecycle of shared thread pools used across the core engine.
 * It initializes and configures the Scheduler pool for monitoring, the Failsafe pool
 * for error isolation/retries, and the high-performance IO pool for main processing.
 * Includes dynamic auto-scaling logic where $Usage = \frac{ActiveCount}{CoreSize}$
 * and provides robust rejection handlers to prevent system hangs during high load or shutdown.
 */
@Component
public class SharedPoolManager {
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ZefioEngineProperties zefioEngineProperties;

    private DisposableExecutor sharedScheduledPoolWrapper;
    private DisposableExecutor sharedIoPoolWrapper;
    private DisposableExecutor sharedFailsafePoolWrapper;

    public SharedPools setupPools() {
        SharedThreadPoolProperties poolProps = zefioEngineProperties.getThreadPools();

        // ---------------------------------------------------------
        // 1. Shared Scheduler Pool (For monitoring and system control)
        // ---------------------------------------------------------
        SharedThreadPoolProperties.ThreadPoolSetting schedulerSetting = poolProps.getScheduler();
        int schedulerPoolSize = schedulerSetting.getFixedPoolSize();
        String schedulerPrefix = schedulerSetting.getThreadNamePrefix();

        ThreadPoolTaskScheduler newSchedulerTask = new ThreadPoolTaskScheduler();
        newSchedulerTask.setPoolSize(schedulerPoolSize);
        newSchedulerTask.setThreadNamePrefix(schedulerPrefix + "-");
        newSchedulerTask.initialize();

        ScheduledExecutorService newScheduler = newSchedulerTask.getScheduledExecutor();
        this.sharedScheduledPoolWrapper = new DisposableExecutor(newScheduler, schedulerPrefix + "Pool");
        log.info("[{}] New Shared Scheduler Pool created. Size: {}", schedulerPrefix, schedulerPoolSize);

        // ---------------------------------------------------------
        // 2. Shared Failsafe Pool (For error isolation and dedicated retries)
        // ---------------------------------------------------------
        SharedThreadPoolProperties.ThreadPoolSetting failsafeSetting = poolProps.getFailsafe();
        int failsafePoolSize = failsafeSetting.getFixedPoolSize();
        String failsafePrefix = failsafeSetting.getThreadNamePrefix();

        ThreadPoolTaskScheduler newFailsafeTask = new ThreadPoolTaskScheduler();
        newFailsafeTask.setPoolSize(failsafePoolSize);
        newFailsafeTask.setThreadNamePrefix(failsafePrefix + "-");

        // Defense 1: Prevent thread death from uncaught exceptions within Failsafe logic
        newFailsafeTask.setErrorHandler(t ->
                log.error("[{}] CRITICAL: Uncaught exception inside retry task. Thread might be compromised: {}", failsafePrefix, t.getMessage(), t)
        );

        // Defense 2: Handle task rejections (e.g., retry scheduled during system shutdown)
        newFailsafeTask.setRejectedExecutionHandler((r, executor) -> {
            String status = String.format("PoolSize=%d, Active=%d", executor.getPoolSize(), executor.getActiveCount());
            String errMsg = "Retry task rejected from [" + failsafePrefix + "]. (Likely during shutdown). " + status;

            FlowException rejectEx = new FlowException(FlowResultStatus.SYSTEM_BUSY, errMsg);
            log.warn("[{}] REJECTION EVENT: {}", failsafePrefix, errMsg);

            throw new TaskRejectedException(errMsg, rejectEx);
        });
        newFailsafeTask.initialize();

        ScheduledExecutorService newFailsafe = newFailsafeTask.getScheduledExecutor();
        this.sharedFailsafePoolWrapper = new DisposableExecutor(newFailsafe, failsafePrefix + "Pool");
        log.info("[{}] New Shared Failsafe Pool created. Size: {}", failsafePrefix, failsafePoolSize);

        // ---------------------------------------------------------
        // 3. Shared I/O Pool (Main engine execution)
        // ---------------------------------------------------------
        SharedThreadPoolProperties.ThreadPoolSetting ioSetting = poolProps.getIo();
        int finalIoPoolSize = ioSetting.getFixedPoolSize();
        int ioQueueCapacity = ioSetting.getQueueCapacity();
        String ioPrefix = ioSetting.getThreadNamePrefix();

        log.info("[{}] Shared I/O Pool Size calculated. Configuration: {}", ioPrefix, finalIoPoolSize);

        ThreadPoolTaskExecutor newIoTask = new ThreadPoolTaskExecutor();
        newIoTask.setCorePoolSize(finalIoPoolSize);
        newIoTask.setMaxPoolSize(finalIoPoolSize); // Behave like a fixed pool
        newIoTask.setQueueCapacity(ioQueueCapacity);
        newIoTask.setThreadNamePrefix(ioPrefix + "-");
        newIoTask.setRejectedExecutionHandler((r, executor) -> {
            String status = String.format("PoolSize=%d, Active=%d, Queue=%d", executor.getPoolSize(), executor.getActiveCount(), executor.getQueue().size());
            String errMsg = "Task rejected from [" + ioPrefix + "]. " + status;

            FlowException rejectEx = new FlowException(FlowResultStatus.SYSTEM_BUSY, errMsg);

            // Notify ErrorCountingAppender by passing the exception object
            log.error("[{}] CRITICAL: REJECTION EVENT OCCURRED! Pool reached capacity. Current state: {}",
                    ioPrefix, status, rejectEx);

            throw new TaskRejectedException(errMsg, rejectEx);
        });
        newIoTask.initialize();

        SharedThreadPoolProperties.AutoScalingConfig autoScaling = ioSetting.getAutoScaling();
        if (autoScaling.isEnabled()) {
            log.info("[Shared-IO] Auto-scaling is enabled. Range: {} ~ {}", finalIoPoolSize, ioSetting.getAutoScaling().getMaxSize());
            startSharedIoAutoScaling(newIoTask, ioSetting.getAutoScaling(), finalIoPoolSize);
        } else {
            log.info("[Shared-IO] Auto-scaling is DISABLED. Running with fixed size: {}", finalIoPoolSize);
        }

        ExecutorService newIoPool = newIoTask.getThreadPoolExecutor();
        this.sharedIoPoolWrapper = new DisposableExecutor(newIoPool, ioPrefix + "Pool");
        log.info("[{}] New Shared I/O Pool created. Size: {}", ioPrefix, finalIoPoolSize);

        return new SharedPools(newScheduler, newIoTask, newFailsafe);
    }

    public void destroy() throws Exception {
        // Ensure graceful shutdown of all pools during refresh or context close
        if (this.sharedScheduledPoolWrapper != null) {
            this.sharedScheduledPoolWrapper.destroy();
        }
        if (this.sharedFailsafePoolWrapper != null) {
            this.sharedFailsafePoolWrapper.destroy();
        }
        if (this.sharedIoPoolWrapper != null) {
            this.sharedIoPoolWrapper.destroy();
        }
    }

    private void startSharedIoAutoScaling(ThreadPoolTaskExecutor executor,
                                          SharedThreadPoolProperties.AutoScalingConfig config,
                                          int minSize) {
        ScheduledExecutorService scheduler = (ScheduledExecutorService) sharedScheduledPoolWrapper.getExecutor();

        DynamicPoolScaler.start(
                scheduler,
                executor,
                "Shared-IO-Scale",
                config.getCheckInterval(),
                minSize,
                config.getMaxSize(),
                config.getThreshold(),
                0.3, // Down Threshold
                () -> {
                    int currentCore = executor.getCorePoolSize();
                    return currentCore > 0 ? (double) executor.getActiveCount() / currentCore : 0.0;
                },
                currentCore -> currentCore + config.getScaleUpStep(),
                currentCore -> currentCore - config.getScaleDownStep()
        );
    }
}

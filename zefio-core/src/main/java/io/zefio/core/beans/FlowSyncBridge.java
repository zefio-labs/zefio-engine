package io.zefio.core.beans;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.zefio.core.common.exception.FlowException;
import io.zefio.core.common.exception.FlowResultStatus;
import io.zefio.core.payload.Payload;
import io.zefio.core.config.ZefioEngineProperties;
import io.zefio.core.config.global.GlobalOptionsProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Acts as a synchronization bridge between asynchronous processing steps, utilizing a high-performance
 * Caffeine cache to manage pending requests via correlation IDs. It handles registration,
 * completion, and timeout scenarios for CompletableFutures, ensuring system stability
 * through back-pressure management and graceful shutdown procedures.
 */
@Component
@RefreshScope
@RequiredArgsConstructor
public class FlowSyncBridge implements InitializingBean, DisposableBean {

    private final Logger log = LoggerFactory.getLogger(FlowSyncBridge.class);
    private final ZefioEngineProperties zefioEngineProperties;

    private int maxCapacity;
    private Cache<String, CompletableFuture<Payload>> pendingRequests;

    @Override
    public void afterPropertiesSet() throws Exception {
        // Enforce explicit type declaration to guarantee backward compatibility across runtime baseline environments
        GlobalOptionsProperties.SyncBridge syncBridgeConfig = zefioEngineProperties.getGlobalOptions().getSyncBridge();
        this.maxCapacity = syncBridgeConfig.getMaxCapacity();
        int expireSeconds = syncBridgeConfig.getExpireSeconds();

        pendingRequests = Caffeine.newBuilder()
                .maximumSize(maxCapacity)
                .expireAfterWrite(Duration.ofSeconds(expireSeconds))
                .removalListener((String key, CompletableFuture<Payload> future, RemovalCause cause) -> {
                    if (cause.wasEvicted() && future != null && !future.isDone()) {
                        log.warn("[FlowSyncBridge] Key [{}] evicted (Cause: {}). Cancelling future.", key, cause);

                        FlowResultStatus status = (cause == RemovalCause.SIZE)
                                ? FlowResultStatus.QUEUE_CAPACITY_EXCEEDED
                                : FlowResultStatus.SYNC_BRIDGE_EXPIRED;

                        future.completeExceptionally(new FlowException(status, "Bridge storage evicted: " + cause.name()));
                    }
                })
                .build();
        log.info("[FlowSyncBridge] Initialized with Caffeine Cache (Max: {}, Expire: {}s)", maxCapacity, expireSeconds);
    }

    @Override
    public void destroy() throws Exception {
        long pendingCount = size();
        if (pendingCount > 0) {
            log.info("[FlowSyncBridge] Shutting down. Clearing {} pending requests.", pendingCount);
            pendingRequests.asMap().forEach((key, future) -> {
                if (!future.isDone()) {
                    future.completeExceptionally(new FlowException(FlowResultStatus.SYSTEM_SHUTDOWN, "System is shutting down"));
                }
            });
        }
        pendingRequests.invalidateAll();
        pendingRequests.cleanUp();
    }

    public CompletableFuture<Payload> register(String key) {
        if (pendingRequests.estimatedSize() >= maxCapacity) {
            log.error("[FlowSyncBridge] Bridge is full! Rejecting key [{}].", key);
            CompletableFuture<Payload> fail = new CompletableFuture<>();
            fail.completeExceptionally(new FlowException(FlowResultStatus.QUEUE_CAPACITY_EXCEEDED, "System Busy: Bridge Capacity Exceeded"));
            return fail;
        }

        CompletableFuture<Payload> future = new CompletableFuture<>();

        CompletableFuture<Payload> existing = pendingRequests.asMap().putIfAbsent(key, future);
        if (existing != null) {
            log.warn("[FlowSyncBridge] Duplicate Key detected [{}]. Replacing existing one.", key);
            existing.completeExceptionally(new FlowException(FlowResultStatus.DUPLICATE_REQUEST, "Replaced by duplicate TID request"));
            pendingRequests.put(key, future);
        }
        return future;
    }

    public boolean complete(String key, Payload responsePayload) {
        CompletableFuture<Payload> future = pendingRequests.asMap().remove(key);
        if (future != null) {
            return future.complete(responsePayload);
        }
        log.warn("[FlowSyncBridge] Key [{}] not found for completion (Already timed out?).", key);
        return false;
    }

    public boolean completeExceptionally(String key, Throwable throwable) {
        CompletableFuture<Payload> future = pendingRequests.asMap().remove(key);
        if (future != null) {
            log.info("[FlowSyncBridge] Notifying error for key [{}]: {}", key, throwable.getMessage());

            if (throwable instanceof FlowException) {
                return future.completeExceptionally(throwable);
            } else {
                return future.completeExceptionally(new FlowException(throwable, FlowResultStatus.INTERNAL_SERVER_ERROR));
            }
        }
        return false;
    }

    public void remove(String key) {
        pendingRequests.invalidate(key);
    }

    public long size() {
        return pendingRequests.estimatedSize();
    }
}

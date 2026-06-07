package io.zefio.core.telemetry.cp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.zefio.core.config.ZefioEngineProperties;
import io.zefio.core.telemetry.MonitorConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Scheduled service responsible for extracting telemetry data from
 * the Micrometer Registry and pushing it to the Redis Hub (Control Plane).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricPushService {

    private final MeterRegistry meterRegistry;
    private final ZefioEngineProperties zefioEngineProperties;
    private final ZefioCpRedisPublisher cpRedisPublisher;

    @Scheduled(fixedRateString = "${zefio.cp.metrics.push-interval-ms:3000}")
    public void pushMetricsToCP() {
        if (!zefioEngineProperties.getCp().isEnabled()) {
            return;
        }

        try {
            Map<String, Object> payload = new HashMap<>();

            // 1. Extract JVM Stats
            Map<String, Object> jvmStats = new HashMap<>();
            double jvmMemoryUsed = getGaugeValue(MonitorConstants.JVM_HEAP_USAGE_BYTES);
            double jvmMemoryMax = getGaugeValue(MonitorConstants.JVM_HEAP_MAX_BYTES);
            double cpuUsage = getGaugeValue("system.cpu.usage");

            jvmStats.put("usedHeapMb", Math.round(jvmMemoryUsed / (1024 * 1024)));
            jvmStats.put("maxHeapMb", Math.round(jvmMemoryMax / (1024 * 1024)));
            jvmStats.put("cpuUsagePercent", Math.round(cpuUsage * 100));
            payload.put("jvm", jvmStats);

            // 2. Extract Global Metrics
            payload.put("tps", getGaugeValue(MonitorConstants.MODULE_TPS));
            payload.put("totalFailures", getCounterValue(MonitorConstants.MODULE_FAILED));

            // 3. Extract Component Pools
            payload.put("sedaPools", extractThreadPoolMetrics());
            payload.put("connectionPools", extractConnectionPoolMetrics());

            // 4. Assemble and dispatch the Redis Message
            Map<String, Object> redisMessage = new HashMap<>();
            redisMessage.put("type", "metrics");
            redisMessage.put("nodeId", zefioEngineProperties.getNode().getId());
            redisMessage.put("payload", payload);

            cpRedisPublisher.sendMessage(redisMessage);

        } catch (Exception e) {
            log.warn("[CP-Agent] Failed to push metrics to Redis: {}", e.getMessage());
        }
    }

    private double getGaugeValue(String metricName) {
        Gauge gauge = meterRegistry.find(metricName).gauge();
        return gauge != null ? gauge.value() : 0.0;
    }

    private double getCounterValue(String metricName) {
        Counter counter = meterRegistry.find(metricName).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private List<Map<String, Object>> extractThreadPoolMetrics() {
        List<Map<String, Object>> pools = new ArrayList<>();
        Collection<Gauge> activeGauges = meterRegistry.find(MonitorConstants.THREAD_POOL_ACTIVE_THREADS).gauges();

        for (Gauge activeGauge : activeGauges) {
            String poolName = activeGauge.getId().getTag("name");
            if (poolName != null) {
                Map<String, Object> poolData = new HashMap<>();
                poolData.put("plugin", poolName);
                poolData.put("active", Math.round(activeGauge.value()));

                Gauge maxGauge = meterRegistry.find(MonitorConstants.THREAD_POOL_SIZE).tag("name", poolName).gauge();
                poolData.put("max", maxGauge != null ? Math.round(maxGauge.value()) : 0);
                pools.add(poolData);
            }
        }
        return pools;
    }

    private List<Map<String, Object>> extractConnectionPoolMetrics() {
        List<Map<String, Object>> pools = new ArrayList<>();
        Collection<Gauge> activeGauges = meterRegistry.find(MonitorConstants.CONNECTION_POOL_ACTIVE).gauges();

        for (Gauge activeGauge : activeGauges) {
            String poolName = activeGauge.getId().getTag("name");
            String flowName = activeGauge.getId().getTag("flow");
            if (poolName != null) {
                Map<String, Object> poolData = new HashMap<>();
                poolData.put("name", poolName);
                poolData.put("flow", flowName != null ? flowName : "Global");
                poolData.put("active", Math.round(activeGauge.value()));

                Gauge maxGauge = meterRegistry.find(MonitorConstants.CONNECTION_POOL_MAX).tag("name", poolName).gauge();
                poolData.put("max", maxGauge != null ? Math.round(maxGauge.value()) : 0);
                pools.add(poolData);
            }
        }
        return pools;
    }
}

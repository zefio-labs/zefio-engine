package io.zefio.core.telemetry.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.zefio.core.common.base.PluginType;
import io.zefio.core.config.ZefioEngineProperties;
import io.zefio.core.telemetry.module.ModuleMetricsAggregator;
import io.zefio.core.telemetry.netty.NettyEventLoopStateTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/**
 * REST controller for exposing JMX-based metrics and diagnostic data.
 * Provides real-time insights into Ingress/Upstream module performance,
 * Netty event loop health, and thread execution states.
 */
@RefreshScope
@RestController
@RequestMapping("/base/metrics")
public class MetricsApi {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public MetricsApi(ZefioEngineProperties zefioEngineProperties) {
        mapper.findAndRegisterModules();
        this.mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
        this.mapper.configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true);
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.mapper.configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, true);
        this.mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
    }

    /**
     * Utility for querying MBeans from the platform MBeanServer and mapping them to a response list.
     */
    private <T> void loadMBeanByQuery(MBeanServer mbs, String query, Class<T> mbeanInterface, List<Map<String, Object>> rootList, MBeanExtractor<T> extractor) throws MalformedObjectNameException {
        Set<ObjectInstance> instances = mbs.queryMBeans(new ObjectName(query), null);

        for (ObjectInstance instance : instances) {
            T model = JMX.newMBeanProxy(mbs, instance.getObjectName(), mbeanInterface, true);
            Map<String, Object> map = extractor.extract(model);
            map.put("name", instance.getObjectName().getCanonicalName());
            rootList.add(map);
        }
    }

    @FunctionalInterface
    private interface MBeanExtractor<T> {
        Map<String, Object> extract(T model);
    }

    /**
     * Aggregates metrics for all plugin types, including Ingress, Interceptors, and Upstream modules.
     */
    @GetMapping(value = "/aggregator")
    public List<Map<String, Object>> filterMetricsAggregator() {
        List<Map<String, Object>> rootList = new ArrayList<>();
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            for (PluginType type : PluginType.values()) {
                String groupName = type.name().toLowerCase(Locale.ENGLISH);
                String query = String.format("io.zefio.*:type=%s", groupName);

                loadMBeanByQuery(mbs, query, ModuleMetricsAggregator.class, rootList, model -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("eventAcceptedCount", model.getPayloadAcceptedCount());
                    map.put("eventFailedCount", model.getPayloadFailedCount());
                    map.put("eventReceivedCount", model.getPayloadReceivedCount());
                    map.put("startTime", model.getStartTime());
                    map.put("stopTime", model.getStopTime());
                    map.put("channelActiveCount", model.getChannelActiveCount());
                    map.put("channelInActiveCount", model.getChannelInActiveCount());
                    map.put("executionAvg", model.getExecutionAvg());
                    map.put("executionMax", model.getExecutionMax());
                    map.put("filterType", model.getFilterType());
                    return map;
                });
            }
        } catch (MalformedObjectNameException e) {
            log.error("Error loading module aggregator MBeans", e);
        }
        return rootList;
    }

    /**
     * Retrieves internal metrics for Netty event loops and pending task counts.
     */
    @GetMapping(value = "/netty")
    public List<Map<String, Object>> nettyMetrics() {
        List<Map<String, Object>> rootList = new ArrayList<>();
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            String query = "io.zefio.*:type=netty";

            loadMBeanByQuery(mbs, query, NettyEventLoopStateTracker.class, rootList, model -> {
                model.refresh();
                Map<String, Object> map = new HashMap<>();
                map.put("totalThreads", model.getTotalThreads());
                map.put("activeThreads", model.getActiveThreads());
                map.put("totalPendingTasks", model.getTotalPendingTasks());
                return map;
            });
        } catch (MalformedObjectNameException e) {
            log.error("Error loading Netty MBeans", e);
        }
        return rootList;
    }

    /**
     * Captures a thread dump specifically targeting the Shared I/O Worker Pool.
     * Useful for diagnosing CPU-bound bottlenecks or blocking operations in active threads.
     */
    @GetMapping(value = "/threaddump")
    public List<Map<String, Object>> getThreadDump() {
        java.lang.management.ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        long[] allThreadIds = threadMXBean.getAllThreadIds();
        java.lang.management.ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(allThreadIds, true, true);

        List<Map<String, Object>> threadDetailsList = new ArrayList<>();
        String ioPoolPrefix = "Shared-IO-Pool";

        for (java.lang.management.ThreadInfo info : threadInfos) {
            if (info == null) continue;

            // Filter for runnable threads within the Shared-IO-Pool to identify active workloads
            if (info.getThreadName().contains(ioPoolPrefix) && info.getThreadState() == Thread.State.RUNNABLE) {
                Map<String, Object> details = new HashMap<>();

                details.put("threadId", info.getThreadId());
                details.put("threadName", info.getThreadName());
                details.put("threadState", info.getThreadState().toString());
                details.put("cpuTime", threadMXBean.getThreadCpuTime(info.getThreadId()));
                details.put("lockName", info.getLockName());
                details.put("lockOwnerName", info.getLockOwnerName());

                List<String> stackTrace = new ArrayList<>();
                for (StackTraceElement element : info.getStackTrace()) {
                    stackTrace.add(element.toString());
                }
                details.put("stackTrace", stackTrace);

                threadDetailsList.add(details);
            }
        }
        return threadDetailsList;
    }
}

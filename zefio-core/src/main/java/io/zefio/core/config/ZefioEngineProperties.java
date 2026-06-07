package io.zefio.core.config;

import io.zefio.core.config.flow.FlowConfiguration;
import io.zefio.core.config.flow.TelegramsConfiguration;
import io.zefio.core.config.global.GlobalOptionsProperties;
import io.zefio.core.config.monitor.MonitorProperties;
import io.zefio.core.config.threadpool.SharedThreadPoolProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Root configuration gateway reflecting the flattened architecture.
 * Directly maps core engine components underneath the 'zefio' namespace.
 */
@Configuration
@ConfigurationProperties(prefix = "zefio")
@Data
public class ZefioEngineProperties {

    private Node node = new Node();
    private Cp cp = new Cp();

    // Flattened infrastructure components wired as pure target objects
    private List<String> imports = new ArrayList<>();
    private GlobalOptionsProperties globalOptions = new GlobalOptionsProperties();
    private SharedThreadPoolProperties threadPools = new SharedThreadPoolProperties();
    private MonitorProperties monitor = new MonitorProperties();

    // Domain components populated via multi-yaml merging
    private Map<String, TelegramsConfiguration> telegrams = new HashMap<>();
    private List<FlowConfiguration> flows = new ArrayList<>();

    @Data
    public static class Node {
        private String id = "DP-01";
        private String group = "main";
    }

    @Data
    public static class Cp {
        private boolean enabled = true;
        private Redis redis = new Redis();
        private Metrics metrics = new Metrics();
    }

    @Data
    public static class Redis {
        private String url = "redis://e000.bond:6379/0";
    }

    @Data
    public static class Metrics {
        private long pushIntervalMs = 3000;
    }
}

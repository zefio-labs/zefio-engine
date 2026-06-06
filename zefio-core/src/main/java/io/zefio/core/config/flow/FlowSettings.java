package io.zefio.core.config.flow;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Root configuration class that enforces a pure pre-flattened architecture.
 * Eliminates profiles, endpoints, and global-errors completely from the Data Plane,
 * treating both local boot and hot-swaps under a unified contract.
 */
@Configuration
@ConfigurationProperties(prefix = "")
@Data
public class FlowSettings {

    /** Specification for split configuration files to be imported locally. */
    private List<String> imports = new ArrayList<>();

    /** Global telegram definitions for data format parsing. */
    private Map<String, TelegramsConfiguration> telegrams = new HashMap<>();

    /** The collection of executable, pre-flattened flow definitions. */
    private List<FlowConfiguration> flows = new ArrayList<>();
}

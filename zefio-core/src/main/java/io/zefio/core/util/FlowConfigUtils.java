package io.zefio.core.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.zefio.core.beans.ApplicationContextProvider;
import io.zefio.core.beans.FlowSettingsBean;
import io.zefio.core.schema.DslConfigurationLoader;
import io.zefio.core.config.flow.ErrorHandlerConfiguration;
import io.zefio.core.config.flow.FlowConfiguration;
import io.zefio.core.config.flow.FlowSettings;
import io.zefio.core.config.flow.StepConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for handling pre-flattened flow and step configurations.
 * Purely optimized to locate inline module settings directly from running flows.
 */
public class FlowConfigUtils {
    private static final Logger log = LoggerFactory.getLogger(FlowConfigUtils.class);
    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    static {
        mapper.findAndRegisterModules();
        mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Dynamically reads the main configuration path from JVM options (-Dspring.config.location).
     */
    public static String getMainConfigPath() {
        String path = System.getProperty("spring.config.location");
        if (StringUtils.isNotBlank(path)) {
            return path.replace("classpath:/", "classpath:");
        }
        return "classpath:zefio.yml";
    }

    /**
     * Returns the StepConfiguration object based on the given name directly from running flow scopes.
     * Profile template interpolation is completely deprecated as configurations are flattened.
     */
    public static StepConfiguration getStepConfigByName(String name) throws Exception {
        if (StringUtils.isBlank(name)) return null;

        try {
            FlowSettingsBean bean = ApplicationContextProvider.getApplicationContext().getBean(FlowSettingsBean.class);
            FlowSettings settings = bean.getSettings();
            return searchStepInSettings(settings, name);
        } catch (BeansException | NullPointerException e) {
            log.debug("Spring Context not ready. Attempting DSL fallback load for name: {}", name);
            return findStepFromDslFallback(name);
        }
    }

    /**
     * Internal logic to search for a step definition within explicit pre-flattened FlowSettings.
     * Searches only through running ingress gateways and active pipeline step paths.
     */
    private static StepConfiguration searchStepInSettings(FlowSettings settings, String name) {
        if (settings == null || settings.getFlows() == null) return null;

        // Search exclusively inside Ingress and Pipeline Step Hierarchies
        for (FlowConfiguration flow : settings.getFlows()) {
            if (flow.getIngress() != null && name.equals(flow.getIngress().getName())) {
                return convertToStepConfig(flow.getIngress().getName(), flow.getIngress().getType(), flow.getIngress().getConfig());
            }

            // Search main workflow pipeline
            StepConfiguration stepConfig = searchInStepsRecursive(flow.getSteps(), name);
            if (stepConfig != null) return stepConfig;

            // Search local flattened inline error handling pipelines
            if (flow.getOnError() != null) {
                for (ErrorHandlerConfiguration err : flow.getOnError()) {
                    StepConfiguration foundInErr = searchInStepsRecursive(err.getSteps(), name);
                    if (foundInErr != null) return foundInErr;
                }
            }
        }
        return null;
    }

    /**
     * Recursively searches for a step within nested step hierarchies.
     */
    private static StepConfiguration searchInStepsRecursive(List<StepConfiguration> steps, String name) {
        if (steps == null || steps.isEmpty()) return null;
        for (StepConfiguration step : steps) {
            if (name.equals(step.getName())) return step;

            // Search in primary execution steps
            StepConfiguration child = searchInStepsRecursive(step.getSteps(), name);
            if (child != null) return child;

            // Search in fallback recovery steps
            StepConfiguration fallbackChild = searchInStepsRecursive(step.getFallbackSteps(), name);
            if (fallbackChild != null) return fallbackChild;
        }
        return null;
    }

    /**
     * Fallback logic to find steps via DSL Configuration Loader if Spring Context is unavailable.
     */
    private static StepConfiguration findStepFromDslFallback(String name) {
        try {
            DslConfigurationLoader loader = new DslConfigurationLoader();
            Map<String, Object> mergedYamlMap = loader.loadAndMerge(getMainConfigPath());
            FlowSettings tempSettings = mapper.convertValue(mergedYamlMap, FlowSettings.class);
            return searchStepInSettings(tempSettings, name);
        } catch (Exception ex) {
            log.error("Failed to load YAML via DslConfigurationLoader fallback.", ex);
            return null;
        }
    }

    private static StepConfiguration convertToStepConfig(String name, String type, Map<String, Object> config) {
        StepConfiguration step = new StepConfiguration();
        step.setName(name);
        step.setType(type);
        step.setConfig(config != null ? config : new HashMap<>());
        return step;
    }
}

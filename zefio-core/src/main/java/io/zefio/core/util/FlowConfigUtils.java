package io.zefio.core.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.zefio.core.beans.FlowSettingsBean;
import io.zefio.core.config.flow.ErrorHandlerConfiguration;
import io.zefio.core.config.flow.FlowConfiguration;
import io.zefio.core.config.flow.StepConfiguration;
import io.zefio.core.schema.DslConfigurationLoader;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * Internal logic to search for a step definition within explicit pre-flattened flow lists.
     * Searches only through running ingress gateways and active pipeline step paths.
     */
    private static StepConfiguration searchStepInFlows(List<FlowConfiguration> flows, String name) {
        if (flows == null) return null;

        // Search exclusively inside Ingress and Pipeline Step Hierarchies
        for (FlowConfiguration flow : flows) {
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
    @SuppressWarnings("unchecked")
    private static StepConfiguration findStepFromDslFallback(String name) {
        try {
            DslConfigurationLoader loader = new DslConfigurationLoader();
            Map<String, Object> mergedYamlMap = loader.loadAndMerge(getMainConfigPath());

            // Extract nested 'zefio' block to maintain consistency with the structural setup
            Map<String, Object> zefioBlock = (Map<String, Object>) mergedYamlMap.get("zefio");
            if (zefioBlock == null) {
                zefioBlock = mergedYamlMap;
            }

            FlowSettingsBean.DeploymentPayload tempPayload = mapper.convertValue(zefioBlock, FlowSettingsBean.DeploymentPayload.class);
            return searchStepInFlows(tempPayload.getFlows(), name);
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

    /**
     * Parsing utility designed to evaluate property keys against running IntelliJ/OS environments blocks directly.
     */
    public static String resolveEnvPlaceholders(String text, String hardcodedFallback) {
        if (text == null || text.isEmpty()) {
            return hardcodedFallback;
        }

        // Regex capturing pattern rules matching standard template tokens: ${VARIABLE_NAME} or ${VARIABLE_NAME:DEFAULT}
        Pattern pattern = Pattern.compile("\\$\\{([^:}]+)(?::([^}]+))?\\}");
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            String envKey = matcher.group(1);
            String inlineFallback = matcher.group(2);

            // Fetch absolute runtime mapping straight from the IntelliJ environment variables stack
            String resolvedValue = System.getenv(envKey);
            if (resolvedValue == null || resolvedValue.isEmpty()) {
                resolvedValue = System.getProperty(envKey); // Secondary fallback fallback tracking standard JVM properties (-D)
            }

            if (resolvedValue != null && !resolvedValue.isEmpty()) {
                log.info("[Zefio Placeholder Core] Successfully evaluated target key [{}] to OS path registry.", envKey);
                return resolvedValue.trim();
            }

            if (inlineFallback != null && !inlineFallback.isEmpty()) {
                log.warn("[Zefio Placeholder Core] Environment token [{}] absent, falling back to inline definition bounds.", envKey);
                return inlineFallback.trim();
            }

            log.warn("[Zefio Placeholder Core] Terminal configuration missing for key [{}]. Deploying hardcoded absolute path parameters.", envKey);
            return hardcodedFallback;
        }

        return text;
    }
}

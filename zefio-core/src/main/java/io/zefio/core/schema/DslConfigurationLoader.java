package io.zefio.core.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/**
 * Responsible for loading and merging distributed YAML configuration files.
 * It supports recursive imports, wildcard pattern matching, and deep merging of
 * hierarchical data structures while preventing circular references.
 */
public class DslConfigurationLoader {
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    /**
     * Entry point: Loads the main configuration and recursively merges all imported files.
     */
    public Map<String, Object> loadAndMerge(String mainConfigPath) throws Exception {
        Map<String, Object> globalContext = new HashMap<>();
        Set<String> loadedFiles = new HashSet<>(); // Guard against circular references (infinite loops)

        loadRecursive(mainConfigPath, globalContext, loadedFiles);

        return globalContext;
    }

    /**
     * Recursively traverses and loads YAML segments based on path patterns.
     */
    @SuppressWarnings("unchecked")
    private void loadRecursive(String pathPattern, Map<String, Object> globalContext, Set<String> loadedFiles) {
        try {
            Resource[] resources = resolver.getResources(pathPattern);
            Yaml yaml = new Yaml();

            for (Resource res : resources) {
                String fileId = res.getURL().toString();
                if (!loadedFiles.add(fileId)) {
                    continue;
                }

                log.info("[DSL Loader] Loading YAML segment: {}", res.getFilename());

                try (InputStream is = res.getInputStream()) {
                    Map<String, Object> yamlMap = yaml.load(is);
                    if (yamlMap == null) continue;

                    // Extract imports from either root level or nested under the 'zefio' block
                    List<String> imports = null;
                    if (yamlMap.containsKey("imports")) {
                        Object importsObj = yamlMap.get("imports");
                        if (importsObj instanceof List) {
                            imports = (List<String>) importsObj;
                        }
                        yamlMap.remove("imports");
                    } else if (yamlMap.get("zefio") instanceof Map) {
                        Map<String, Object> zefioSubMap = (Map<String, Object>) yamlMap.get("zefio");
                        if (zefioSubMap.containsKey("imports")) {
                            Object importsObj = zefioSubMap.get("imports");
                            if (importsObj instanceof List) {
                                imports = (List<String>) importsObj;
                            }
                            zefioSubMap.remove("imports");
                        }
                    }

                    // 1. Process discovered imports recursively (Post-order traversal)
                    if (imports != null) {
                        for (String importPath : imports) {
                            if (importPath == null || importPath.trim().isEmpty()) {
                                log.warn("[DSL Loader] Skipped empty or null import path.");
                                continue;
                            }
                            loadRecursive(importPath, globalContext, loadedFiles);
                        }
                    }

                    // 2. Merge current file content into the Global Context
                    deepMerge(globalContext, yamlMap);
                }
            }
        } catch (Exception e) {
            log.error("[DSL Loader] Failed to load path pattern: {}", pathPattern, e);
        }
    }

    /**
     * Performs a deep merge of two Maps, maintaining hierarchical structures
     * and appending list elements instead of overwriting them.
     */
    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object sourceValue = entry.getValue();

            // Case 1: Both values are Maps - Recurse deeper
            if (target.containsKey(key) && target.get(key) instanceof Map && sourceValue instanceof Map) {
                Map<String, Object> targetChild = (Map<String, Object>) target.get(key);
                Map<String, Object> sourceChild = (Map<String, Object>) sourceValue;
                deepMerge(targetChild, sourceChild);
            }
            // Case 2: Both values are Lists - Append elements (e.g., merging 'flows' or 'telegrams')
            else if (target.containsKey(key) && target.get(key) instanceof List && sourceValue instanceof List) {
                List<Object> targetList = (List<Object>) target.get(key);
                List<Object> sourceList = (List<Object>) sourceValue;
                targetList.addAll(sourceList);
            }
            // Case 3: Primitive types or mismatched structures - Overwrite/Add
            else {
                target.put(key, sourceValue);
            }
        }
    }
}

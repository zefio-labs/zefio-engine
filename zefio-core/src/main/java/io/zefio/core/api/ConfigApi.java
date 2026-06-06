package io.zefio.core.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.zefio.core.beans.DynamicSchemaLoader;
import io.zefio.core.common.base.PluginMeta;
import io.zefio.core.config.flow.FlowSettings;
import io.zefio.core.schema.PluginSchemaExtractor;
import io.zefio.core.telemetry.cp.ZefioCpRedisPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST Controller responsible for providing endpoints to retrieve configuration metadata.
 */
@Slf4j
@RestController
@RequestMapping("/base/config")
public class ConfigApi {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private DynamicSchemaLoader yamlFilterLoader;

    @Autowired
    private PluginSchemaExtractor schemaExtractor;

    @Autowired
    private ZefioCpRedisPublisher redisPublisher;

    @PostMapping(value = "/reload", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> reloadConfig(@RequestBody Map<String, String> payload) {
        try {
            String yamlContent = payload.get("yaml");
            String targetGroup = payload.getOrDefault("targetGroup", "main");

            // 1. Fail-Fast Validation: Ensure the YAML conforms to FlowSettings DTO
            yamlMapper.readValue(yamlContent, FlowSettings.class);

            // 2. Broadcast to all DP nodes via Redis
            log.info("[ConfigApi] Structural validation successful. Broadcasting cluster update payload to: {}", targetGroup);

            Map<String, Object> command = new HashMap<>();
            command.put("type", "command");
            command.put("action", "hot-reload");
            command.put("targetGroup", targetGroup);
            command.put("payload", Collections.singletonMap("yaml", yamlContent));

            redisPublisher.sendCommand(command);

            Map<String, String> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("message", "Hot-reload update successfully broadcasted to cluster.");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[ConfigApi] Cluster configuration hot-deployment aborted due to structural violation", e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("status", "FAIL");
            errorResponse.put("reason", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @GetMapping(value = "/registry", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> getRegistry() {
        List<Map<String, Object>> plugins = new ArrayList<>();

        for (PluginMeta meta : yamlFilterLoader.getAllFilters().values()) {
            Map<String, Object> plugin = new HashMap<>();
            plugin.put("name", meta.getName());
            plugin.put("type", meta.getType());
            plugin.put("className", meta.getClassName());

            if (meta.getDtoClassName() != null) {
                try {
                    Class<?> dtoClass = Class.forName(meta.getDtoClassName());
                    plugin.put("schema", schemaExtractor.extractSchemaDescriptions(dtoClass, new HashSet<>()));
                } catch (Exception e) {
                    log.warn("[ConfigApi] Schema extraction skipped for: {}", meta.getName());
                }
            }
            plugins.add(plugin);
        }
        return plugins;
    }
}

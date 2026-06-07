package io.zefio.core.telemetry.cp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.zefio.core.beans.FlowSettingsBean;
import io.zefio.core.beans.FlowSettingsBean.DeploymentPayload;
import io.zefio.core.config.ZefioEngineProperties;
import io.zefio.core.config.flow.TelegramsConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.HashMap;
import java.util.Map;

/**
 * Listener that subscribes to the Redis 'zefio:command' channel.
 * Receives hot-reload commands from the Control Plane (CP) and orchestrates the pipeline swap.
 * Crucially, it establishes a bidirectional feedback loop by reporting the deployment status back to the CP.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZefioCpRedisCommandListener implements InitializingBean, DisposableBean {

    private final ZefioEngineProperties zefioEngineProperties;
    private final JedisPool jedisPool;
    private final FlowSettingsBean flowSettingsBean;

    private final ObjectMapper jsonMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Thread subscriberThread;
    private JedisPubSub pubSub;

    @Override
    public void afterPropertiesSet() {
        subscriberThread = new Thread(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleCommand(message);
                    }
                };
                log.info("[CP-Agent] Subscribed to Redis channel: zefio:command");
                jedis.subscribe(pubSub, "zefio:command");
            } catch (Exception e) {
                log.error("Redis Command Listener Failed", e);
            }
        }, "Zefio-Redis-Subscriber");

        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    @SuppressWarnings("unchecked")
    private void handleCommand(String jsonMessage) {
        try {
            Map<String, Object> command = jsonMapper.readValue(jsonMessage, new TypeReference<Map<String, Object>>() {});

            String targetGroup = (String) command.getOrDefault("targetGroup", "main");
            if (!targetGroup.equals(zefioEngineProperties.getNode().getGroup())) {
                log.debug("[CP-Agent] Command ignored. Node group mismatch. (Expected: {}, Actual: {})",
                        targetGroup, zefioEngineProperties.getNode().getGroup());
                return;
            }

            if ("hot-reload".equals(command.get("action"))) {
                Map<String, Object> payload = (Map<String, Object>) command.get("payload");

                String flowsYaml = (String) payload.get("flowsYaml");
                Map<String, Object> rawTelegrams = (Map<String, Object>) payload.get("telegrams");
                String deployId = (String) command.getOrDefault("deployId", "unknown");

                log.info("🔥 [CP-Agent] Hot-Reloading Pipeline on Node: {}", zefioEngineProperties.getNode().getId());

                try {
                    // Map target class type to the newly introduced inner structural DeploymentPayload
                    DeploymentPayload newSettings = yamlMapper.readValue(flowsYaml, DeploymentPayload.class);

                    if (rawTelegrams != null && !rawTelegrams.isEmpty()) {
                        Map<String, TelegramsConfiguration> typedTelegrams = jsonMapper.convertValue(
                                rawTelegrams,
                                new TypeReference<Map<String, TelegramsConfiguration>>() {}
                        );
                        newSettings.setTelegrams(typedTelegrams);
                    }

                    flowSettingsBean.hotSwap(newSettings);

                    log.info("✅ [CP-Agent] Pipeline hot-swap completed.");
                    reportStatusToCp(deployId, "SUCCESS", "Deployment successful on node: " + zefioEngineProperties.getNode().getId());

                } catch (Exception e) {
                    log.error("❌ [CP-Agent] Pipeline hot-swap FAILED: {}", e.getMessage());
                    reportStatusToCp(deployId, "FAILED", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[CP-Agent] Critical error during command execution", e);
        }
    }

    private void reportStatusToCp(String deployId, String status, String errorMessage) {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, Object> response = new HashMap<>();
            response.put("nodeId", zefioEngineProperties.getNode().getId());
            response.put("group", zefioEngineProperties.getNode().getGroup());
            response.put("deployId", deployId);
            response.put("status", status);
            response.put("message", errorMessage);
            response.put("timestamp", System.currentTimeMillis());

            String jsonResponse = jsonMapper.writeValueAsString(response);
            jedis.publish("zefio:deploy:status", jsonResponse);

            log.debug("[CP-Agent] Published deployment status to CP: {}", status);
        } catch (Exception e) {
            log.error("[CP-Agent] Failed to report status to CP", e);
        }
    }

    @Override
    public void destroy() {
        if (pubSub != null && pubSub.isSubscribed()) {
            pubSub.unsubscribe();
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
        log.info("[CP-Agent] Redis Command Listener shut down.");
    }
}

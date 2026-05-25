package io.zefio.core.telemetry.cp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.zefio.core.beans.FlowSettingsBean;
import io.zefio.core.config.ZefioProperties;
import io.zefio.core.config.flow.FlowSettings;
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

    private final ZefioProperties zefioProperties;
    private final JedisPool jedisPool;
    private final FlowSettingsBean flowSettingsBean; // The engine orchestrator for hot-swapping

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private Thread subscriberThread;
    private JedisPubSub pubSub;

    @Override
    public void afterPropertiesSet() {
        subscriberThread = new Thread(() -> {
            // Use the injected jedisPool for subscription
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

    private void handleCommand(String jsonMessage) {
        try {
            // 1. Parse the incoming JSON command
            Map<String, Object> command = jsonMapper.readValue(jsonMessage, new TypeReference<Map<String, Object>>() {});

            // 2. Target Group filtering to ensure only relevant nodes process this command
            String targetGroup = (String) command.getOrDefault("targetGroup", "main");
            if (!targetGroup.equals(zefioProperties.getNode().getGroup())) {
                log.debug("[CP-Agent] Command ignored. Node group mismatch. (Expected: {}, Actual: {})",
                        targetGroup, zefioProperties.getNode().getGroup());
                return;
            }

            // 3. Process hot-reload action
            if ("hot-reload".equals(command.get("action"))) {
                Map<String, Object> payload = (Map<String, Object>) command.get("payload");
                String yamlStr = (String) payload.get("yaml");

                // Extract deployId to correlate the response. Default to "unknown" if not provided by CP.
                String deployId = (String) command.getOrDefault("deployId", "unknown");

                log.info("🔥 [CP-Agent] Hot-Reloading Pipeline on Node: {}", zefioProperties.getNode().getId());

                try {
                    // 4. Convert YAML string to FlowSettings object and trigger hotSwap
                    FlowSettings newSettings = yamlMapper.readValue(yamlStr, FlowSettings.class);

                    // If this fails, it will immediately throw an exception and jump to the catch block
                    flowSettingsBean.hotSwap(newSettings);

                    log.info("✅ [CP-Agent] Pipeline hot-swap completed.");

                    // 5. Report success back to Control Plane
                    reportStatusToCp(deployId, "SUCCESS", "Deployment successful on node: " + zefioProperties.getNode().getId());

                } catch (Exception e) {
                    log.error("❌ [CP-Agent] Pipeline hot-swap FAILED: {}", e.getMessage());

                    // 5. Report failure back to Control Plane so the UI does not hang or show a false positive
                    reportStatusToCp(deployId, "FAILED", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[CP-Agent] Critical error during command execution", e);
        }
    }

    /**
     * Asynchronously reports the deployment status back to the Control Plane via Redis.
     * This prevents false positives by ensuring the CP knows exactly what happened on the DP node.
     *
     * @param deployId The unique identifier of the deployment request.
     * @param status The result status ("SUCCESS" or "FAILED").
     * @param errorMessage Detailed message or error description.
     */
    private void reportStatusToCp(String deployId, String status, String errorMessage) {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, Object> response = new HashMap<>();
            response.put("nodeId", zefioProperties.getNode().getId());
            response.put("group", zefioProperties.getNode().getGroup());
            response.put("deployId", deployId);
            response.put("status", status);
            response.put("message", errorMessage);
            response.put("timestamp", System.currentTimeMillis());

            String jsonResponse = jsonMapper.writeValueAsString(response);

            // Publish the result to the status channel monitored by the CP WebSocket server
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

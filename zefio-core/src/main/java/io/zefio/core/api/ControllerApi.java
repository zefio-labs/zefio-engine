package io.zefio.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.zefio.core.ZefioCoreService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller responsible for managing application lifecycle and configuration settings.
 * It provides endpoints to dynamically update and merge YAML configuration files,
 * as well as commands to start or stop the core application flow and trigger application refreshes.
 */
@RefreshScope
@RestController
@RequestMapping("/base/ctrl")
public class ControllerApi {

	private final Logger log = LoggerFactory.getLogger(getClass());
	private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

	// Core services declared as final to ensure immutability and thread-safety
	private final ZefioCoreService service;

	// Directly inject the port into the controller layer to eliminate cross-bean dependency
	@Value("${server.port:52001}")
	private int serverPort;

	/**
	 * Single constructor injection framework aligned with Spring Boot idioms.
	 */
	public ControllerApi(ZefioCoreService flowService) {
		this.service = flowService;

		// Establish deterministic JSON/YAML parsing guidelines
		this.mapper.findAndRegisterModules();
		this.mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
		this.mapper.configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true);
		this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		this.mapper.configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, true);
		this.mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
		this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
		this.mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
	}

	public static Yaml createYaml() {
		DumperOptions dumperOptions = new DumperOptions();
		dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		dumperOptions.setPrettyFlow(true);
		return new Yaml(dumperOptions);
	}

	@PostMapping(value = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> updateSettings(@RequestBody Map<String, Object> newSettings) {
		try {
			log.info("[Ctrl-API] Received configuration update request: {}", newSettings);

			String propertiesFile = System.getProperty("spring.config.location");
			log.info("[Ctrl-API] Target configuration location: {}", propertiesFile);
			if (propertiesFile == null) {
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
						.body("System property 'spring.config.location' is not defined.");
			}

			propertiesFile = propertiesFile.replace("classpath:/", "");
			File yamlFile = new File(getClass().getClassLoader().getResource(propertiesFile).getFile());

			Yaml yaml = createYaml();
			List<Map<String, Object>> documents = new ArrayList<>();
			try (InputStream inputStream = new FileInputStream(yamlFile)) {
				yaml.loadAll(inputStream).forEach(obj -> {
					if (obj instanceof Map) {
						documents.add((Map<String, Object>) obj);
					}
				});
			}

			if (documents.isEmpty()) {
				documents.add(new HashMap<>());
			}
			mergeRecursive(documents.get(0), newSettings);

			try (PrintWriter writer = new PrintWriter(yamlFile)) {
				for (int i = 0; i < documents.size(); i++) {
					yaml.dump(documents.get(i), writer);
					if (i < documents.size() - 1) {
						writer.write("---\n");
					}
				}
			}

			log.info("[Ctrl-API] Configuration file updated successfully: {}", yamlFile.getAbsolutePath());
			return ResponseEntity.ok("Configuration successfully merged and persisted.");
		} catch (Exception e) {
			log.error("[Ctrl-API] Configuration update sequence failed", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body("Error occurred during configuration update: " + e.getMessage());
		}
	}

	@SuppressWarnings("unchecked")
	private void mergeRecursive(Map<String, Object> original, Map<String, Object> updates) {
		for (Map.Entry<String, Object> entry : updates.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();

			if (value instanceof Map && original.get(key) instanceof Map) {
				mergeRecursive((Map<String, Object>) original.get(key), (Map<String, Object>) value);
			} else {
				original.put(key, value);
			}
		}
	}

	private void callRefresh(int port) {
		try {
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			RestTemplate restTemplate = new RestTemplate();
			String url = String.format("http://%s:%d/actuator/refresh", InetAddress.getLocalHost().getHostAddress(), port);
			HttpEntity<String> request = new HttpEntity<>(headers);
			ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
			log.info("[Ctrl-API] Context refresh response status: {}", response.getStatusCode());
		} catch (Exception e) {
			log.error("[Ctrl-API] Context refresh broadcast failed: {}", e.getMessage());
		}
	}

	@GetMapping(value = "/on")
	public ResponseEntity<?> getON() throws UnknownHostException {
		if (log.isInfoEnabled()) {
			log.info("{}", StringUtils.center(" ✔️ App Start Call. ", 70, "■"));
		}
		this.service.execute();
		return new ResponseEntity<String>(HttpStatus.OK);
	}

	@GetMapping(value = "/off")
	public ResponseEntity<?> getOFF() throws UnknownHostException {
		if (log.isInfoEnabled()) {
			log.info("{}", StringUtils.center(" ✔️ App Stop Call. ", 70, "■"));
		}
		new Thread(() -> {
			try {
				Thread.sleep(500);
				// Resolved compile error by referencing the locally managed serverPort property
				callRefresh(this.serverPort);
			} catch (InterruptedException e) {
				log.error("Thread sleep sequence interrupted", e);
			}
		}).start();
		this.service.shutdown();

		return new ResponseEntity<String>(HttpStatus.OK);
	}
}

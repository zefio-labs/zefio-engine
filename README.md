# Zefio Engine (Core Data Plane)

![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)
![Java](https://img.shields.io/badge/Java-8%20%7C%2021+-orange.svg)
![Docker](https://img.shields.io/badge/Docker-Ready-2496ED.svg)
![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)

**Zefio Engine** is a high-performance, asynchronous integration gateway and event mesh built upon the **Staged Event-Driven Architecture (SEDA)** pattern.

It is designed to handle massive data streams, AI prompt routing, and real-time IoT edge telemetry with extreme efficiency. Operating as the **Data Plane (DP)**, Zefio Engine seamlessly synchronizes with the [Zefio Control Plane (FlowShift Console)](#) via Redis to provide real-time cluster metrics and AI-driven pipeline configurations.

---

## 🚀 Key Features

* **SEDA Architecture:** Isolates heavy workloads into independent thread pools and queues, preventing system-wide thread exhaustion during massive traffic spikes.
* **Declarative YAML Pipelines:** Build complex logic (Scatter-Gather, Try-Catch, Fail-Fast, Dynamic Routing) entirely through simple YAML flow definitions. No compilation required.
* **Plug & Play Adapters:** Extensible integration with TCP, HTTP(REST), WebSockets, Kafka, Redis Pub/Sub, and JDBC.
* **Edge-Ready & Multi-Arch:** Fully containerized and optimized for heterogeneous environments—from high-end cloud servers to ARM-based Edge devices like Raspberry Pi.
* **Smart Telemetry:** Auto-detects JVM versions (Java 8 to 21+) and automatically aggregates real-time stream metrics to the Redis Hub for the Control Plane.

---

## 🏗 Architecture

Zefio operates in a decoupled ecosystem:
1. **Data Plane (Zefio Engine):** The core routing runtime. Executes YAML flows, processes streams, and pushes telemetry (This repository).
2. **Control Plane (Zefio Console):** A centralized Web UI featuring real-time dashboards and an AI-powered Pipeline Architect.

---

## 🐳 Quick Start (Docker)

Zefio Engine is distributed with a production-ready `Dockerfile` and dynamic `entrypoint.sh` that automatically tunes the JVM based on the Java version.

### 1. Build the Image
```bash
# Clone the repository
git clone [https://github.com/zefio-labs/zefio-engine.git](https://github.com/zefio-labs/zefio-engine.git)
cd zefio-engine

# Build the Docker image
docker build -t zefio-engine:1.0.0 .
```

### 2. Run the Container
You can pass custom JVM arguments and environment variables via `JAVA_OPTS` and `APP_ENV`.

```bash
docker run -d \
  --name zefio-edge-node \
  -p 52001-52020:52001-52020 \
  -e APP_ENV=prd \
  -e ZEFIO_NODE_ID=DP-01 \
  -e ZEFIO_NODE_GROUP=edge-cluster \
  -e JAVA_OPTS="-Xms512m -Xmx512m" \
  zefio-engine:1.0.0
```

### 3. Verify Startup
Check the logs to verify the dynamic JVM parameter application:
```bash
docker logs -f zefio-edge-node
```
*Expected Output:*
```text
==================================================
🚀 Starting Zefio (K8s Mode: prd)
☕ Detected Java Version: 21.0.2 (JDK 21+)
📂 Log Directory: /Zefio/logs
⚙️ K8s Custom OPTS (Memory & ETC): -Xms512m -Xmx512m
==================================================
```

---

## ⚙️ Configuration

Zefio loads its routing logic via YAML files. The core configurations are typically injected via `classpath:/extreme-composite.yaml` (or customized via `-Dspring.config.location` in `entrypoint.sh`).

To connect to the Control Plane, ensure your Redis properties are set correctly:
```yaml
zefio:
  node:
    id: ${ZEFIO_NODE_ID:DP-01}
    group: ${ZEFIO_NODE_GROUP:main}
  cp:
    enabled: true
    redis:
      url: ${ZEFIO_REDIS_URL:redis://localhost:6379/0}
    metrics:
      push-interval-ms: 3000
```

---

## 🗺️ Roadmap to v1.0.0

Zefio is currently in **Public Beta (v0.9.0)**. We are actively polishing the core features before the official 1.0.0 release.

- [x] **v0.8.0**: SEDA Engine Core & Multi-Architecture Edge Clustering (Raspberry Pi support).
- [x] **v0.9.0 (Current)**: Redis-based Control Plane, Real-time Telemetry Dashboard, and AI Architect Prototype.
- [ ] **v0.9.5**: AI Prompt Engineering Refinement (Improving Flow YAML generation accuracy and context sync).
- [ ] **v1.0.0-RC**: Zero-Downtime Hot Deployment (Seamless pipeline reloading via Redis Pub/Sub).
- [ ] **v1.0.0 (Stable)**: Official Open Source Release.

---

## 🤝 Contributing

We welcome contributions from the community! If you'd like to improve the SEDA core, add a new protocol adapter, or help stabilize the Zero-Downtime Hot Deploy feature, please submit a Pull Request or open an Issue.

## 📄 License

This project is licensed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file for details.

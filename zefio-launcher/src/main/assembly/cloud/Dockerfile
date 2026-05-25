FROM hypriot/rpi-java

# ✅ Set working directory
WORKDIR /Zefio

# ✅ Create a non-root user (UID 1000)
RUN useradd -u 1000 -m appuser

# 💡 Modification 1: Create log directory in advance and grant permissions to appuser (fixes log errors)
RUN mkdir -p /Zefio/logs && chown -R appuser:appuser /Zefio

# 💡 Modification 2: Copy the contents of the local Zefio folder (lib, resources) to /Zefio/ in the container
COPY --chown=appuser:appuser ./Zefio /Zefio/

# 💡 Modification 3: Copy entrypoint.sh from the local root path to /Zefio/ in the container
COPY --chown=appuser:appuser ./entrypoint.sh /Zefio/

# ✅ Grant execution permissions for the entrypoint
RUN chmod +x /Zefio/entrypoint.sh

# ✅ Switch user
USER appuser

# 💡 Modification 4: Expose only the specifically required ports.
# Exposing a massive port range like 0-62000 depletes the Raspberry Pi's memory and crashes the Docker daemon.
EXPOSE 52001-52020

# ✅ Execute entrypoint
ENTRYPOINT ["/Zefio/entrypoint.sh"]

package org.mtr.mod.bridge;

/**
 * MMTR engine connection settings (M0b prototype).
 *
 * @param host         engine host (default 127.0.0.1)
 * @param port         engine Jetty port (engine default 8888)
 * @param basePath     API base path (default /mmtr/api/bridge)
 * @param timeoutMs    HTTP timeouts
 * @param dimension    dimension id to address (query dimension), fallback 0
 */
public record EngineConfig(String host, int port, String basePath, int timeoutMs, String dimension) {

	public static EngineConfig defaults() {
		return new EngineConfig("127.0.0.1", 8888, "/mmtr/api/bridge", 5000, "0");
	}

	public String baseUrl() {
		return "http://" + host + ":" + port + basePath;
	}
}

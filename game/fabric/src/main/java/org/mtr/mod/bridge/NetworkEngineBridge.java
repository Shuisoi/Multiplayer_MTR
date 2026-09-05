package org.mtr.mod.bridge;

import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Network implementation of {@link EngineBridge}: talks to a standalone MMTR Engine process
 * over plain HTTP (Jetty servlets). M0b prototype - uses JDK HttpClient so the mod gains no
 * extra dependencies for the prototype.
 */
public final class NetworkEngineBridge implements EngineBridge {

	private EngineConfig config = EngineConfig.defaults();
	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	@Override
	public boolean start(EngineConfig config) {
		this.config = config == null ? EngineConfig.defaults() : config;
		return ping();
	}

	@Override
	public void stop() {
		// stateless HTTP prototype: nothing to release
	}

	@Override
	public boolean isConnected() {
		return ping();
	}

	@Override
	public boolean echo(String client, long nonce, Consumer<JsonObject> onResponse) {
		return post("echo", "{\"client\":\"" + client + "\",\"nonce\":" + nonce + "}", onResponse);
	}

	@Override
	public JsonObject fetchVehicles(String dimension) {
		final JsonObject data = get("vehicles", dimension);
		return data;
	}

	private boolean ping() {
		return get("ping", config.dimension()) != null;
	}

	private JsonObject get(String endpoint, String dimension) {
		try {
			final URI uri = URI.create(config.baseUrl() + "/" + endpoint + "?dimension=" + URLEncoder.encode(dimension == null ? config.dimension() : dimension, StandardCharsets.UTF_8));
			final HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(config.timeoutMs())).GET().build();
			final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 200) {
				return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("data");
			}
		} catch (Exception ignored) {
			// engine not reachable
		}
		return null;
	}

	private boolean post(String endpoint, String jsonBody, Consumer<JsonObject> onResponse) {
		try {
			final URI uri = URI.create(config.baseUrl() + "/" + endpoint + "?dimension=" + URLEncoder.encode(config.dimension(), StandardCharsets.UTF_8));
			final HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofMillis(config.timeoutMs()))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
				.build();
			final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 200) {
				final JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("data");
				if (onResponse != null) {
					onResponse.accept(data);
				}
				return true;
			}
		} catch (Exception ignored) {
			// engine not reachable
		}
		return false;
	}
}

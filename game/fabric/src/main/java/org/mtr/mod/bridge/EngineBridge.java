package org.mtr.mod.bridge;

import org.mtr.libraries.com.google.gson.JsonObject;

import java.util.function.Consumer;

/**
 * Boundary between the Minecraft mod (game layer) and the MMTR Engine (standalone TSC fork).
 * Gameplay logic only talks to this interface; the engine may be embedded (MTR-style) or a
 * separate process (MMTR target architecture, "option 2").
 *
 * <p>M0b prototype surface (kept deliberately small):</p>
 * <ul>
 *   <li>{@link #start}/{@link #stop}/{@link #isConnected} - lifecycle + liveness</li>
 *   <li>{@link #echo} - correlated request/response round-trip probe (validates the channel)</li>
 *   <li>{@link #fetchVehicles} - lightweight snapshot poll (render/benchmark spike)</li>
 * </ul>
 */
public interface EngineBridge {

	boolean start(EngineConfig config);

	void stop();

	boolean isConnected();

	/** @return true if the request was sent (response delivered via callback) */
	boolean echo(String client, long nonce, Consumer<JsonObject> onResponse);

	/** @return parsed {@code data} object of the vehicles response, or null on failure */
	JsonObject fetchVehicles(String dimension);
}

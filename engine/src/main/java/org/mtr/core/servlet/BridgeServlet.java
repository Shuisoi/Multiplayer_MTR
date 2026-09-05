package org.mtr.core.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Vehicle;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * MMTR spike bridge: minimal HTTP surface that an external Minecraft mod (or any client)
 * can poll to reach the standalone MMTR Engine process.
 *
 * <p>Endpoints (mounted at {@code /mmtr/api/bridge/*}):</p>
 * <ul>
 *   <li>{@code ping} - liveness + vehicle count for the routed simulator</li>
 *   <li>{@code vehicles} - lightweight per-vehicle snapshot list (render/probe prototype)</li>
 * </ul>
 * <p>This is intentionally tiny: it proves external-process connectivity and gives us a
 * vehicle-snapshot channel to benchmark. The full EngineBridge protocol (C2S operations,
 * event stream, task/freight/coupling ops) builds on the same servlet base later.</p>
 */
public class BridgeServlet extends ServletBase {

	public BridgeServlet(ObjectImmutableList<Simulator> simulators) {
		super(simulators);
	}

	@Override
	public void getContent(String endpoint, String data, Object2ObjectAVLTreeMap<String, String> parameters, JsonReader jsonReader, Simulator simulator, Consumer<@Nullable JsonObject> sendResponse) {
		switch (endpoint) {
			case "echo" -> {
				final JsonObject echo = new JsonObject();
				echo.addProperty("client", jsonReader.getString("client", ""));
				echo.addProperty("nonce", jsonReader.getLong("nonce", 0));
				final JsonObject response = new JsonObject();
				response.addProperty("dimension", simulator.dimension);
				response.addProperty("serverTimeMs", System.currentTimeMillis());
				response.add("echo", echo);
				sendResponse.accept(response);
			}
			case "ping" -> {
				final JsonObject response = new JsonObject();
				response.addProperty("ok", true);
				response.addProperty("dimension", simulator.dimension);
				response.addProperty("serverTimeMs", System.currentTimeMillis());
				final AtomicInteger vehicleCount = new AtomicInteger();
				simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> vehicleCount.incrementAndGet()));
				response.addProperty("vehicles", vehicleCount.get());
				sendResponse.accept(response);
			}
			case "vehicles" -> {
				final JsonArray vehicleArray = new JsonArray();
				simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> vehicleArray.add(getVehicleJson(vehicle))));
				final JsonObject response = new JsonObject();
				response.addProperty("dimension", simulator.dimension);
				response.add("vehicles", vehicleArray);
				sendResponse.accept(response);
			}
			case "bench" -> {
				// DEV/BENCH ONLY: synthetic vehicle snapshot payload, same shape as the real
				// "vehicles" list, to measure per-train payload cost without needing world data.
				int count;
				try { count = Math.max(0, Math.min(1000, Integer.parseInt(parameters.getOrDefault("count", "8")))); }
				catch (NumberFormatException e) { count = 8; }
				final JsonArray benchArray = new JsonArray();
				for (int i = 0; i < count; i++) {
					final JsonObject v = new JsonObject();
					v.addProperty("moving", true);
					v.addProperty("reversed", false);
					v.addProperty("routeId", i);
					v.addProperty("thisStation", i % 20);
					v.addProperty("nextStation", (i + 1) % 20);
					v.addProperty("powerLevel", 0);
					v.addProperty("totalLength", 20.0);
					benchArray.add(v);
				}
				final JsonObject response = new JsonObject();
				response.addProperty("dimension", simulator.dimension);
				response.addProperty("synthetic", true);
				response.add("vehicles", benchArray);
				sendResponse.accept(response);
			}
			default -> sendResponse.accept(null);
		}
	}

	private static JsonObject getVehicleJson(Vehicle vehicle) {
		final JsonObject vehicleJson = new JsonObject();
		vehicleJson.addProperty("moving", vehicle.getIsOnRoute());
		vehicleJson.addProperty("reversed", vehicle.getReversed());
		vehicleJson.addProperty("routeId", vehicle.vehicleExtraData.getThisRouteId());
		vehicleJson.addProperty("thisStation", vehicle.vehicleExtraData.getThisStationId());
		vehicleJson.addProperty("nextStation", vehicle.vehicleExtraData.getNextStationId());
		vehicleJson.addProperty("powerLevel", vehicle.vehicleExtraData.getPowerLevel());
		vehicleJson.addProperty("totalLength", vehicle.vehicleExtraData.getTotalVehicleLength());
		return vehicleJson;
	}
}
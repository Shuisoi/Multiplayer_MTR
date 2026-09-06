package org.mtr.core.servlet;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.Main;
import org.mtr.core.data.NameColorDataBase;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.map.*;
import org.mtr.core.operation.ArrivalsRequest;
import org.mtr.core.operation.MmtrMissionControl;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;

import java.util.function.Consumer;

public final class SystemMapServlet extends ServletBase {

	private final Object2ObjectAVLTreeMap<String, CachedResponse> stationsAndRoutesResponses = new Object2ObjectAVLTreeMap<>();
	private final Object2ObjectAVLTreeMap<String, CachedResponse> departuresResponses = new Object2ObjectAVLTreeMap<>();
	private final Object2ObjectAVLTreeMap<String, CachedResponse> clientsResponses = new Object2ObjectAVLTreeMap<>();
	private final Object2ObjectAVLTreeMap<String, CachedResponse> mmtrTrainsResponses = new Object2ObjectAVLTreeMap<>();

	/**
	 * Cache lifespan for the relatively-static stations / routes payload.
	 */
	private static final long STATIONS_AND_ROUTES_CACHE_MILLIS = 30_000L;
	/**
	 * Cache lifespan for live departures and client positions — short enough for the map UI to feel live, long enough that a busy server isn't recomputing per request.
	 */
	private static final long LIVE_DATA_CACHE_MILLIS = 3_000L;

	public SystemMapServlet(ObjectImmutableList<Simulator> simulators) {
		super(simulators);
	}

	@Override
	public void getContent(String endpoint, String data, Object2ObjectAVLTreeMap<String, String> parameters, JsonReader jsonReader, Simulator simulator, Consumer<@Nullable JsonObject> sendResponse) {
		if (endpoint.equals("directions")) {
			simulator.directionsFinder.addRequest(new DirectionsRequest(jsonReader, directionsResponse -> sendResponse.accept(Utilities.getJsonObjectFromData(directionsResponse)), null));
		} else {
			sendResponse.accept(switch (endpoint) {
				case "stations-and-routes" -> stationsAndRoutesResponses.computeIfAbsent(simulator.dimension, key -> new CachedResponse(SystemMapServlet::getStationsAndRoutes, STATIONS_AND_ROUTES_CACHE_MILLIS)).get(simulator);
				case "departures" -> departuresResponses.computeIfAbsent(simulator.dimension, key -> new CachedResponse(SystemMapServlet::getDepartures, LIVE_DATA_CACHE_MILLIS)).get(simulator);
				case "arrivals" -> Utilities.getJsonObjectFromData(new ArrivalsRequest(jsonReader).getArrivals(simulator));
				case "clients" -> clientsResponses.computeIfAbsent(simulator.dimension, key -> new CachedResponse(SystemMapServlet::getClients, LIVE_DATA_CACHE_MILLIS)).get(simulator);
				case "mmtr-trains" -> mmtrTrainsResponses.computeIfAbsent(simulator.dimension, key -> new CachedResponse(SystemMapServlet::getMmtrTrains, LIVE_DATA_CACHE_MILLIS)).get(simulator);
				case "mmtr-dispatch" -> {
					final boolean ok = new MmtrMissionControl(jsonReader).dispatch(simulator);
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", ok);
					yield result;
				}
				case "mmtr-jobs" -> Utilities.getJsonObjectFromData(simulator.getMmtrJobRegistry());
				case "mmtr-job-references" -> getMmtrJobReferences(simulator);
				case "mmtr-jobs-upsert" -> {
					final org.mtr.core.mmtr.job.MmtrConsistJob job = new org.mtr.core.mmtr.job.MmtrConsistJob(jsonReader);
					simulator.upsertMmtrJob(job);
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", true);
					yield result;
				}
				case "mmtr-jobs-delete" -> {
					final String jobId = jsonReader.getString("jobId", "");
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", simulator.deleteMmtrJob(jobId));
					yield result;
				}
				case "mmtr-job-states" -> {
					final com.google.gson.JsonArray states = new com.google.gson.JsonArray();
					final org.mtr.core.mmtr.job.MmtrJobScheduler scheduler = simulator.mmtrJobScheduler;
					for (final org.mtr.core.mmtr.job.MmtrConsistJob job : simulator.getMmtrJobRegistry().jobs) {
						final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
						out.addProperty("jobId", job.jobId);
						out.addProperty("startTimeOfDayMs", job.startTimeOfDayMs);
						final org.mtr.core.mmtr.job.MmtrJobScheduler.JobState state = scheduler == null ? null : scheduler.stateOf(job.jobId);
						out.addProperty("state", state == null ? "PENDING" : state.name());
						out.addProperty("step", scheduler == null ? -1 : scheduler.stepIndexOf(job.jobId));
						out.addProperty("totalSteps", job.steps.size());
						out.addProperty("cars", scheduler == null ? job.cars.size() : scheduler.carsOf(job.jobId));
						final String failure = scheduler == null ? null : scheduler.failureOf(job.jobId);
						if (failure != null) {
							out.addProperty("failure", failure);
						}
						states.add(out);
					}
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.add("states", states);
					yield result;
				}
				default -> null;
			});
		}
	}

	private static JsonObject getStationsAndRoutes(Simulator simulator) {
		final StationAndRoutes stationAndRoutes = new StationAndRoutes(simulator.dimensions);
		simulator.stations.forEach(stationAndRoutes::addStation);
		simulator.routes.forEach(stationAndRoutes::addRoute);
		return Utilities.getJsonObjectFromData(stationAndRoutes);
	}

	private static JsonObject getDepartures(Simulator simulator) {
		final long currentMillis = System.currentTimeMillis();
		final Object2ObjectAVLTreeMap<String, Long2ObjectAVLTreeMap<LongArrayList>> departures = new Object2ObjectAVLTreeMap<>();
		simulator.sidings.forEach(siding -> siding.getDeparturesForMap(currentMillis, departures));
		return Utilities.getJsonObjectFromData(new Departures(currentMillis, departures));
	}

	private static JsonObject getClients(Simulator simulator) {
		final long currentMillis = System.currentTimeMillis();
		final Object2ObjectAVLTreeMap<String, Client> clients = new Object2ObjectAVLTreeMap<>();

		simulator.clients.forEach(client -> {
			final String clientId = client.uuid.toString();
			clients.put(clientId, new Client(
				clientId, Main.CLIENT_NAME_RESOLVER == null ? "" : Main.CLIENT_NAME_RESOLVER.apply(client.uuid),
				client.getPosition().getX(), client.getPosition().getZ(),
				simulator.stations.stream().filter(station -> station.inArea(client.getPosition())).map(NameColorDataBase::getHexId).findFirst().orElse("")
			));
		});

		simulator.sidings.forEach(siding -> siding.iterateVehiclesAndRidingEntities((vehicleExtraData, vehicleRidingEntity) -> {
			final String clientId = vehicleRidingEntity.uuid.toString();
			final Client client = clients.get(clientId);
			if (client != null) {
				clients.put(clientId, new Client(
					client,
					Utilities.numberToPaddedHexString(vehicleExtraData.getThisRouteId()),
					Utilities.numberToPaddedHexString(vehicleExtraData.getThisStationId()),
					Utilities.numberToPaddedHexString(vehicleExtraData.getNextStationId())
				));
			}
		}));

		return Utilities.getJsonObjectFromData(new Clients(currentMillis, new ObjectArrayList<>(clients.values())));
	}

	private static JsonObject getMmtrTrains(Simulator simulator) {
		final long currentMillis = System.currentTimeMillis();
		final com.google.gson.JsonArray trains = new com.google.gson.JsonArray();
		final com.google.gson.JsonArray sidings = new com.google.gson.JsonArray();
		simulator.sidings.forEach(siding -> {
			final int[] vehicleCount = {0};
			final int[] parkedCount = {0};
			siding.iterateVehicles(vehicle -> {
				vehicleCount[0]++;
				if (!vehicle.getIsOnRoute()) {
					parkedCount[0]++;
				}
				final com.google.gson.JsonObject train = new com.google.gson.JsonObject();
				train.addProperty("vehicleId", String.valueOf(vehicle.getId()));
				train.addProperty("sidingId", String.valueOf(siding.getId()));
				train.addProperty("sidingName", siding.getName());
				train.addProperty("depotName", siding.getDepotName());
				train.addProperty("routeName", vehicle.vehicleExtraData.getThisRouteName());
				train.addProperty("routeNumber", vehicle.vehicleExtraData.getThisRouteNumber());
				train.addProperty("destination", vehicle.vehicleExtraData.getThisRouteDestination());
				train.addProperty("isManualAllowed", vehicle.vehicleExtraData.getIsManualAllowed());
				train.addProperty("isCurrentlyManual", vehicle.isCurrentlyManual());
				train.addProperty("onRoute", vehicle.getIsOnRoute());
				train.addProperty("moving", vehicle.isMoving());
				train.addProperty("speedKmh", Math.round(vehicle.getSpeed() * 3600000.0) / 1000.0);
				train.addProperty("railProgressM", Math.round(vehicle.getRailProgress() * 100.0) / 100.0);
				train.addProperty("doorsOpen", vehicle.vehicleExtraData.getDoorMultiplier() > 0);
				final Vehicle.PositionAndTiltAngle head = vehicle.getHeadPositionAndTiltAngle();
				if (head != null) {
					train.addProperty("headX", Math.round(head.position().x() * 100.0) / 100.0);
					train.addProperty("headZ", Math.round(head.position().z() * 100.0) / 100.0);
				}
				final MmtrMission mission = vehicle.getMmtrMission();
				if (mission != null) {
					final com.google.gson.JsonObject missionJson = new com.google.gson.JsonObject();
					missionJson.addProperty("kind", mission.getKind().name());
					missionJson.addProperty("state", mission.getState().name());
					missionJson.addProperty("executor", mission.getExecutor().name());
					missionJson.addProperty("startSidingId", String.valueOf(mission.getStartSidingId()));
					missionJson.addProperty("targetSidingId", String.valueOf(mission.getTargetSidingId()));
					missionJson.addProperty("assignedMillis", mission.getAssignedMillis());
					if (mission.getFailureReason() != null) {
						missionJson.addProperty("failureReason", mission.getFailureReason());
					}
					train.add("mission", missionJson);
				}
				trains.add(train);
			});
			final com.google.gson.JsonObject sidingJson = new com.google.gson.JsonObject();
			sidingJson.addProperty("sidingId", String.valueOf(siding.getId()));
			sidingJson.addProperty("sidingName", siding.getName());
			sidingJson.addProperty("depotName", siding.getDepotName());
			sidingJson.addProperty("manual", siding.getIsManual());
			sidingJson.addProperty("vehiclesTotal", vehicleCount[0]);
			sidingJson.addProperty("vehiclesParked", parkedCount[0]);
			sidings.add(sidingJson);
		});
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.addProperty("currentTime", currentMillis);
		root.add("trains", trains);
		root.add("sidings", sidings);
		// Reserved for the automatic signal / point layer (future infrastructure reaction layer).
		root.add("signals", new com.google.gson.JsonArray());
		root.add("points", new com.google.gson.JsonArray());
		return root;
	}

	/** Job-editor pickers: in-game depots / sidings / platforms (decimal id strings + display names). */
	private static JsonObject getMmtrJobReferences(Simulator simulator) {
		final com.google.gson.JsonArray depots = new com.google.gson.JsonArray();
		simulator.depots.forEach(depot -> {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("id", String.valueOf(depot.getId()));
			out.addProperty("name", depot.getName());
			depots.add(out);
		});
		final com.google.gson.JsonArray sidings = new com.google.gson.JsonArray();
		simulator.sidings.forEach(siding -> {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("id", String.valueOf(siding.getId()));
			out.addProperty("name", siding.getName());
			out.addProperty("depotId", String.valueOf(siding.area == null ? 0 : siding.area.getId()));
			out.addProperty("depotName", siding.area == null ? "" : siding.area.getName());
			out.addProperty("manual", siding.getIsManual());
			sidings.add(out);
		});
		final com.google.gson.JsonArray platforms = new com.google.gson.JsonArray();
		simulator.platforms.forEach(platform -> {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("id", String.valueOf(platform.getId()));
			out.addProperty("name", platform.getName());
			out.addProperty("stationName", platform.area == null ? "" : platform.area.getName());
			platforms.add(out);
		});
		final com.google.gson.JsonArray templates = new com.google.gson.JsonArray();
		for (final org.mtr.core.mmtr.job.MmtrConsistTemplate template : simulator.mmtrConsistTemplates.templates) {
			templates.add(Utilities.getJsonObjectFromData(template));
		}
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("depots", depots);
		root.add("sidings", sidings);
		root.add("platforms", platforms);
		root.add("templates", templates);
		return root;
	}
}
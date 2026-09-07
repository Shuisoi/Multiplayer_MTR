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
				case "mmtr-job-op" -> {
					final String jobId = jsonReader.getString("jobId", "");
					final String op = jsonReader.getString("op", "");
					final org.mtr.core.mmtr.job.MmtrJobScheduler scheduler = simulator.mmtrJobScheduler;
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					final boolean ok;
					if (scheduler == null) {
						ok = false;
					} else {
						ok = switch (op) {
							case "pause" -> scheduler.pause(jobId);
							case "resume" -> scheduler.resume(jobId);
							case "human" -> scheduler.humanTakeover(jobId);
							case "release" -> scheduler.releaseToAutopilot(jobId);
							default -> false;
						};
					}
					result.addProperty("ok", ok);
					yield result;
				}
				case "mmtr-motion" -> getMmtrMotion(simulator);
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
				case "mmtr-rolling-stock" -> Utilities.getJsonObjectFromData(simulator.getMmtrRollingStock());
				case "mmtr-manifest-reset" -> {
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", true);
					result.addProperty("placedSidings", simulator.mmtrResetAndApplyRollingStock());
					yield result;
				}
				case "mmtr-vehicle-op" -> {
					final String op = jsonReader.getString("op", "");
					final String rawVehicleId = jsonReader.getString("vehicleId", "").trim();
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("op", op);
					if (op.equals("clear-all")) {
						simulator.mmtrClearAllVehicles();
						result.addProperty("ok", true);
						yield result;
					}
					boolean ok = false;
					if (rawVehicleId.isEmpty()) {
						result.addProperty("ok", false);
						result.addProperty("error", "vehicleId is required");
					} else {
						try {
							final long vehicleId = Long.parseLong(rawVehicleId);
							// Reserved vehicle-level task sheet (per-train 作业表) - future layer.
							if (op.equals("delete")) {
								ok = simulator.deleteMmtrVehicle(vehicleId);
								if (!ok) {
									result.addProperty("error", "no vehicle with id " + rawVehicleId);
								}
							} else {
								result.addProperty("error", "unsupported op '" + op + "'");
							}
						} catch (NumberFormatException e) {
							result.addProperty("error", "vehicleId must be numeric");
						}
					}
					result.addProperty("ok", ok);
					yield result;
				}
				case "mmtr-vehicle-task" -> {
					// Reserved: assign a task sheet (作业表) to one generated train by vehicle id.
					final String rawVehicleId = jsonReader.getString("vehicleId", "").trim();
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", true);
					result.addProperty("reserved", true);
					result.addProperty("message", "per-vehicle task sheets are reserved (vehicleId=" + rawVehicleId + ")");
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
						out.addProperty("paused", scheduler != null && scheduler.isPaused(job.jobId));
						out.addProperty("human", scheduler != null && scheduler.isHumanHeld(job.jobId));
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
				case "mmtr-topology" -> getMmtrTopology(simulator);
				case "mmtr-points" -> getMmtrPoints(simulator);
				case "mmtr-point-op" -> {
					final long x = jsonReader.getLong("x", 0);
					final long y = jsonReader.getLong("y", 0);
					final long z = jsonReader.getLong("z", 0);
					final String via = jsonReader.getString("via", "");
					// "branch" is optional: a lock/unlock-only op must not clobber the operator branch.
					final boolean hasBranch = jsonReader.has("branch");
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", !via.isEmpty());
					if (!via.isEmpty()) {
						if (hasBranch) {
							simulator.mmtrSetPoint(x, y, z, via, jsonReader.getInt("branch", 0));
						}
						if (jsonReader.getBoolean("lock", false)) {
							simulator.mmtrPointLock(x, y, z, via);
							result.addProperty("locked", true);
						}
						if (jsonReader.getBoolean("unlock", false)) {
							simulator.mmtrPointUnlock(x, y, z, via);
							result.addProperty("locked", false);
						}
					}
					yield result;
				}
				case "mmtr-point-req" -> {
					final long x = jsonReader.getLong("x", 0);
					final long y = jsonReader.getLong("y", 0);
					final long z = jsonReader.getLong("z", 0);
					final String via = jsonReader.getString("via", "");
					final String owner = jsonReader.getString("owner", "");
					final int leg = jsonReader.getInt("leg", 0);
					final long untilMillis = jsonReader.getLong("untilMillis", System.currentTimeMillis() + 10L * 60L * 1000L);
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					if (via.isEmpty() || owner.isEmpty()) {
						result.addProperty("ok", false);
					} else {
						result.addProperty("result", simulator.mmtrPointRequest(x, y, z, via, owner, leg, untilMillis).name());
					}
					yield result;
				}
				case "mmtr-point-rel" -> {
					final long x = jsonReader.getLong("x", 0);
					final long y = jsonReader.getLong("y", 0);
					final long z = jsonReader.getLong("z", 0);
					final String via = jsonReader.getString("via", "");
					final String owner = jsonReader.getString("owner", "");
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", !via.isEmpty() && !owner.isEmpty());
					if (!via.isEmpty() && !owner.isEmpty()) {
						simulator.mmtrPointRelease(x, y, z, via, owner);
					}
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

	/**
	 * Turnout console feed (道岔, P2 UI): every direction-aware (node, approach rail) fork with two
	 * or more ordered continuations, enriched with the operator manual branch, the authority state
	 * (locked / holder / queue) and the ordered legs each with its direction kind. Coordinates and
	 * via rail hex together key one point; leg indexes are the SAME indexes the walker/planner elect
	 * against (straight > left > right > other ordering).
	 */
	private static JsonObject getMmtrPoints(org.mtr.core.simulation.Simulator simulator) {
		final com.google.gson.JsonArray points = new com.google.gson.JsonArray();
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.mmtr.point.MmtrPoint> discovered = org.mtr.core.mmtr.point.MmtrPoint.discoverDirectionAware(simulator);
		for (final org.mtr.core.mmtr.point.MmtrPoint p : discovered) {
			if (p.legs.size() < 2) {
				continue; // pass-throughs / dead ends are not operator forks
			}
			final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("x", p.nodeX);
			o.addProperty("y", p.nodeY);
			o.addProperty("z", p.nodeZ);
			o.addProperty("via", p.viaRailHex);
			o.addProperty("form", p.form.name());
			final com.google.gson.JsonArray legs = new com.google.gson.JsonArray();
			for (final org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg leg : p.legs) {
				final com.google.gson.JsonObject legJson = new com.google.gson.JsonObject();
				legJson.addProperty("hex", leg.railHex);
				legJson.addProperty("kind", leg.kind.name());
				legs.add(legJson);
			}
			o.add("legs", legs);
			final org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore store = simulator.mmtrPointBranches;
			final int manual = store.contains(p.nodeX, p.nodeY, p.nodeZ, p.viaRailHex) ? store.get(p.nodeX, p.nodeY, p.nodeZ, p.viaRailHex) : -1;
			o.addProperty("manual", manual);
			o.addProperty("locked", simulator.mmtrPointAuthority.isLocked(p.nodeX, p.nodeY, p.nodeZ, p.viaRailHex));
			final String holder = simulator.mmtrPointAuthority.holder(p.nodeX, p.nodeY, p.nodeZ, p.viaRailHex);
			o.addProperty("holder", holder == null ? "" : holder);
			o.addProperty("holderLeg", simulator.mmtrPointAuthority.grantedLeg(p.nodeX, p.nodeY, p.nodeZ, p.viaRailHex));
			final com.google.gson.JsonArray queue = new com.google.gson.JsonArray();
			for (final String q : simulator.mmtrPointAuthority.queuedSnapshot(p.nodeX, p.nodeY, p.nodeZ, p.viaRailHex)) {
				queue.add(q);
			}
			o.add("queue", queue);
			points.add(o);
		}
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("points", points);
		return root;
	}

	/**
	 * Rail-topology feed (web track display): every node (degree >= 1, buffers included) with its
	 * neighbour rails, PLUS the full rail segment list with both real endpoints - the map draws the
	 * actual track network underneath the fork markers (topological display), not just the abstract
	 * schematic connections.
	 */
	private static JsonObject getMmtrTopology(org.mtr.core.simulation.Simulator simulator) {
		final com.google.gson.JsonArray nodes = new com.google.gson.JsonArray();
		simulator.positionsToRail.forEach((node, neighbourMap) -> {
			if (neighbourMap.isEmpty()) {
				return;
			}
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("x", node.getX());
			out.addProperty("y", node.getY());
			out.addProperty("z", node.getZ());
			out.addProperty("degree", neighbourMap.size());
			final com.google.gson.JsonArray neighbours = new com.google.gson.JsonArray();
			neighbourMap.forEach((pos, rail) -> {
				final com.google.gson.JsonObject n = new com.google.gson.JsonObject();
				n.addProperty("x", pos.getX());
				n.addProperty("y", pos.getY());
				n.addProperty("z", pos.getZ());
				n.addProperty("rail", rail.getHexId());
				neighbours.add(n);
			});
			out.add("neighbors", neighbours);
			nodes.add(out);
		});
		// Deduplicated rail segments: collect each rail's two endpoint nodes from the position map.
		final com.google.gson.JsonArray rails = new com.google.gson.JsonArray();
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, org.mtr.core.data.Rail> byHex = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, org.mtr.core.data.Position[]> railEnds = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();
		simulator.positionsToRail.forEach((node, neighbourMap) -> neighbourMap.forEach((pos, rail) -> {
			byHex.putIfAbsent(rail.getHexId(), rail);
			final org.mtr.core.data.Position[] ends = railEnds.computeIfAbsent(rail.getHexId(), k -> new org.mtr.core.data.Position[2]);
			if (ends[0] == null) {
				ends[0] = node;
			} else if (ends[1] == null && !ends[0].equals(node)) {
				ends[1] = node;
			}
		}));
		byHex.forEach((hex, rail) -> {
			final org.mtr.core.data.Position[] ends = railEnds.get(hex);
			if (ends == null || ends[1] == null) {
				return;
			}
			final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("hex", hex);
			// Sample the REAL rail curve (circle/segment geometry from RailMath) so the web track
			// display follows the in-game shape instead of a straight chord between the endpoints.
			final double railLength = rail.railMath.getLength();
			final int samples = Math.max(2, Math.min(28, (int) Math.ceil(railLength / 5.0)));
			final com.google.gson.JsonArray pts = new com.google.gson.JsonArray();
			for (int i = 0; i <= samples; i++) {
				final org.mtr.core.tool.Vector v = rail.railMath.getPosition((double) i / samples, false);
				final com.google.gson.JsonObject pt = new com.google.gson.JsonObject();
				pt.addProperty("x", Math.round(v.x() * 10.0) / 10.0);
				pt.addProperty("z", Math.round(v.z() * 10.0) / 10.0);
				pts.add(pt);
			}
			o.add("pts", pts);
			rails.add(o);
		});
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("nodes", nodes);
		root.add("rails", rails);
		return root;
	}

	/** Decoupled vehicle motion feed: (segment id + offset) positions for clients/map (no baked routes). */
	private static JsonObject getMmtrMotion(Simulator simulator) {
		final com.google.gson.JsonArray snapshots = new com.google.gson.JsonArray();
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> snapshots.add(Utilities.getJsonObjectFromData(org.mtr.core.mmtr.MmtrMotionSnapshot.from(siding, vehicle)))));
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("snapshots", snapshots);
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
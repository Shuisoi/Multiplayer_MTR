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
				case "mmtr-schedule" -> getMmtrSchedule(simulator);
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
							} else if (op.equals("cab-enter") || op.equals("cab-leave") || op.equals("change-ends")) {
								// B7.6: crew cab ops (key in / key out / 换端) on the consist model. The
								// game side has already checked the driver's key and position; the engine
								// enforces the physical gates (consist model, train at a stand).
								final org.mtr.core.data.Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
								if (vehicle == null) {
									result.addProperty("error", "no vehicle with id " + rawVehicleId);
								} else if (vehicle.getMmtrConsistWalker() == null) {
									result.addProperty("error", "vehicle " + rawVehicleId + " is not a consist-body train");
								} else if (op.equals("cab-enter")) {
									final String cabName = jsonReader.getString("cab", "CAB_A");
									org.mtr.core.mmtr.consist.MmtrCabState.Cab cab = org.mtr.core.mmtr.consist.MmtrCabState.Cab.NONE;
									try {
										cab = org.mtr.core.mmtr.consist.MmtrCabState.Cab.valueOf(cabName);
									} catch (IllegalArgumentException e) {
										result.addProperty("error", "cab must be CAB_A or CAB_B");
									}
									if (cab != org.mtr.core.mmtr.consist.MmtrCabState.Cab.NONE) {
										ok = vehicle.enterMmtrCab(cab);
										if (!ok) {
											result.addProperty("error", "cannot take " + cabName + " (train moving or cab occupied)");
										}
									}
								} else if (op.equals("cab-leave")) {
									ok = vehicle.leaveMmtrCab();
								} else {
									ok = vehicle.changeEndsMmtrMotion();
									if (!ok) {
										result.addProperty("error", "cannot change ends (train moving, no cab manned, or not a consist)");
									}
								}
								if (ok) {
									result.addProperty("activeCab", vehicle.getMmtrActiveCab().name());
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
				case "mmtr-lines" -> getMmtrLines(simulator);
				case "mmtr-points" -> getMmtrPoints(simulator);
				case "mmtr-junction-legs" -> getMmtrJunctionLegs(simulator);
				case "mmtr-junction-legs-upsert" -> {
					final long x = jsonReader.getLong("x", 0);
					final long y = jsonReader.getLong("y", 0);
					final long z = jsonReader.getLong("z", 0);
					final String via = jsonReader.getString("via", "");
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					if (via.isEmpty()) {
						result.addProperty("ok", false);
						yield result;
					}
					final it.unimi.dsi.fastutil.objects.ObjectArrayList<String> legs = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
					jsonReader.iterateStringArray("legs", legs::clear, legs::add);
					result.addProperty("ok", simulator.mmtrJunctionLegsUpsert(x, y, z, via, legs));
					yield result;
				}
				case "mmtr-signals" -> getMmtrSignals(simulator);
				case "mmtr-command" -> {
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					final String command = jsonReader.getString("command", "");
					if (!command.isEmpty()) {
						simulator.mmtrPushCommand(command);
						result.addProperty("ok", true);
					} else {
						result.addProperty("ok", false);
					}
					final com.google.gson.JsonArray log = new com.google.gson.JsonArray();
					simulator.mmtrCommandLog.forEach(log::add);
					result.add("log", log);
					yield result;
				}
				case "mmtr-signal-op" -> {
					final long x = jsonReader.getLong("x", 0);
					final long y = jsonReader.getLong("y", 0);
					final long z = jsonReader.getLong("z", 0);
					final float angle = (float) jsonReader.getDouble("angle", 0);
					final int aspects = jsonReader.getInt("aspects", 2);
					final String op = jsonReader.getString("op", "set");
					final String target = jsonReader.getString("target", "");
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					if (op.equals("set") && target.isEmpty() && jsonReader.has("nodeX") && jsonReader.has("nodeY") && jsonReader.has("nodeZ")) {
						// Game-side bind tool upload: infer the read rail from the clicked node +
						// the light facing (covered bind), register BOUND.
						result.addProperty("ok", simulator.mmtrSignalBindAtNode((int) x, (int) y, (int) z, angle, aspects,
							jsonReader.getLong("nodeX", 0), jsonReader.getLong("nodeY", 0), jsonReader.getLong("nodeZ", 0)));
					} else {
						result.addProperty("ok", simulator.mmtrSignalOp((int) x, (int) y, (int) z, angle, aspects, op, target));
					}
					yield result;
				}
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
				// B7.7: which cab is manned (B-series consist body) - the ops console shows it.
				final org.mtr.core.mmtr.consist.MmtrConsistWalker consistWalker = vehicle.getMmtrConsistWalker();
				if (consistWalker != null) {
					train.addProperty("activeCab", consistWalker.cabs().activeCab().name());
					train.addProperty("cabManned", consistWalker.cabs().isManned());
					// 钥匙归属: "SYSTEM" is the engine's placeholder key on a staged consist (nobody
					// may drive from it), "CREW" is a player's key - with the holder's uuid.
					train.addProperty("cabKeyHolder", consistWalker.cabs().keyHolder().name());
					train.addProperty("cabCrew", consistWalker.cabs().crewUuid() == null ? "" : consistWalker.cabs().crewUuid().toString());
					// B7.6: a consist body has no legacy head position - report the leading face, which
					// is what the map draws the train marker at (and what the driver is looking along).
					final org.mtr.core.mmtr.MmtrMotionSnapshot consistSnapshot = org.mtr.core.mmtr.MmtrMotionSnapshot.ofConsistWalker(consistWalker);
					train.addProperty("headX", Math.round(consistSnapshot.frontX * 100.0) / 100.0);
					train.addProperty("headZ", Math.round(consistSnapshot.frontZ * 100.0) / 100.0);
				} else {
					final Vehicle.PositionAndTiltAngle head = vehicle.getHeadPositionAndTiltAngle();
					if (head != null) {
						train.addProperty("headX", Math.round(head.position().x() * 100.0) / 100.0);
						train.addProperty("headZ", Math.round(head.position().z() * 100.0) / 100.0);
					}
				}
				// C3a: the subsidiary-aspect authority a train holds (main head stays red) - the ops
				// console shows which movement is authorised to enter an occupied section.
				final org.mtr.core.mmtr.signal.MmtrShuntAuthority shuntAuthority = vehicle.getMmtrShuntAuthority();
				if (shuntAuthority != null) {
					train.addProperty("shuntAuthority", shuntAuthority.getKind().name());
					train.addProperty("shuntTargetRail", shuntAuthority.getTargetRailHex());
					train.addProperty("shuntSpeedLimitKmh", shuntAuthority.getSpeedLimitKmh());
					train.addProperty("shuntRemainingS", Math.round(shuntAuthority.remainingMillis(simulator.getCurrentMillis()) / 1000.0));
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
		// Signal display layer: every rail's block aspect (RED when a train occupies the rail, the
		// yellow chain behind it from the occupancy chain ahead) - the web console colours the
		// track exactly like the in-game signal lights protecting each rail.
		root.add("signals", getMmtrRailAspects(simulator));
		root.add("points", new com.google.gson.JsonArray());
		return root;
	}

	/**
	 * MMTR rail signal aspects for the web console: one entry per rail with its display aspect.
	 * RED = a train currently occupies the rail; SINGLE_YELLOW = the rail beyond (in the travel
	 * direction) is occupied; DOUBLE_YELLOW = two rails beyond; GREEN = clear ahead. The chain
	 * mirrors the fabric signal-light renderer: continuations keep the travel direction (no
	 * turn-backs); at a fork every branch counts (conservative worst case - the S5 route-locked
	 * aspect would narrow it to the set route). "Pre-approach" reservations are not occupancy.
	 */
	private static com.google.gson.JsonArray getMmtrRailAspects(Simulator simulator) {
		final com.google.gson.JsonArray signals = new com.google.gson.JsonArray();
		computeRailAspectMap(simulator).forEach((hex, aspect) -> {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("hex", hex);
			out.addProperty("aspect", aspect);
			signals.add(out);
		});
		return signals;
	}

	/** hex -> display aspect for every rail (shared by the rail feed and the signal registry feed). */
	private static java.util.HashMap<String, String> computeRailAspectMap(Simulator simulator) {
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
		final java.util.HashMap<String, String> aspects = new java.util.HashMap<>();
		byHex.forEach((hex, rail) -> {
			final org.mtr.core.data.Position[] ends = railEnds.get(hex);
			if (ends == null || ends[1] == null) {
				return;
			}
			final int depth = signalDepth(simulator, byHex, railEnds, hex, ends[0], ends[1]);
			aspects.put(hex, switch (depth) {
				case 1 -> "RED";
				case 2 -> "SINGLE_YELLOW";
				case 3 -> "DOUBLE_YELLOW";
				default -> "GREEN";
			});
		});
		return aspects;
	}

	/**
	 * Display aspect of one rail, walked from both travel directions (a signal approaching from
	 * either end would protect it). Depth semantics match the fabric renderer: 1 = the rail
	 * itself is occupied (RED); 2 = one rail beyond in the travel direction is occupied (single
	 * yellow); 3 = two rails beyond (double yellow); 0 = clear. The most restrictive direction
	 * wins.
	 */
	private static int signalDepth(Simulator simulator, it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, org.mtr.core.data.Rail> byHex, it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, org.mtr.core.data.Position[]> railEnds, String hex, org.mtr.core.data.Position end1, org.mtr.core.data.Position end2) {
		int best = 0;
		for (final org.mtr.core.data.Position entry : new org.mtr.core.data.Position[]{end1, end2}) {
			final int depth = chainDepthFrom(simulator, byHex, railEnds, hex, entry);
			if (depth > 0 && (best == 0 || depth < best)) {
				best = depth;
			}
		}
		return best;
	}

	/**
	 * How far ahead (in rails, the protected one included) the nearest occupied rail sits when
	 * the signal protecting {@code hex} is approached from {@code entryPos}: 1 = protected rail
	 * occupied, 2 = one rail beyond, 3 = two rails beyond, 0 = clear. Same walk as the fabric
	 * renderer ({@code mmtrChainDepth}): from the far end of every rail only continuations that
	 * keep the travel direction (dot product with the incoming heading) are followed; at a fork
	 * every branch counts (conservative worst case until route-locked aspects exist).
	 */
	private static int chainDepthFrom(Simulator simulator, it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, org.mtr.core.data.Rail> byHex, it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, org.mtr.core.data.Position[]> railEnds, String hex, org.mtr.core.data.Position entryPos) {
		final java.util.ArrayList<Object[]> level = new java.util.ArrayList<>(); // {node, hex}
		level.add(new Object[]{entryPos, hex});
		for (int depth = 1; depth <= 3; depth++) {
			for (final Object[] entry : level) {
				final org.mtr.core.data.Rail rail = byHex.get((String) entry[1]);
				if (rail != null && rail.mmtrIsCurrentlyBlocked()) {
					return depth;
				}
			}
			if (depth == 3) {
				break;
			}
			final java.util.ArrayList<Object[]> nextLevel = new java.util.ArrayList<>();
			for (final Object[] entry : level) {
				final org.mtr.core.data.Position node = (org.mtr.core.data.Position) entry[0];
				final String curHex = (String) entry[1];
				final org.mtr.core.data.Position far = farEndOf(railEnds, curHex, node);
				if (far == null) {
					continue;
				}
				final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<org.mtr.core.data.Position, org.mtr.core.data.Rail> neighbours = simulator.positionsToRail.get(far);
				if (neighbours == null) {
					continue;
				}
				neighbours.forEach((otherEnd, rail) -> {
					if (!rail.getHexId().equals(curHex)) {
						// Continue only in the travel direction (dot product with the incoming heading).
						final double dot = (otherEnd.getX() - far.getX()) * (far.getX() - node.getX()) + (otherEnd.getZ() - far.getZ()) * (far.getZ() - node.getZ());
						if (dot > 0) {
							nextLevel.add(new Object[]{far, rail.getHexId()});
						}
					}
				});
			}
			if (nextLevel.isEmpty()) {
				break;
			}
			level.clear();
			level.addAll(nextLevel);
		}
		return 0;
	}

	/** The far endpoint of {@code hex} when its rail is entered from {@code node}. */
	private static org.mtr.core.data.Position farEndOf(it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, org.mtr.core.data.Position[]> railEnds, String hex, org.mtr.core.data.Position node) {
		final org.mtr.core.data.Position[] ends = railEnds.get(hex);
		if (ends == null || ends[1] == null) {
			return null;
		}
		return ends[0].equals(node) ? ends[1] : ends[1].equals(node) ? ends[0] : null;
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
	 * Wayside signal feed (信号机登记表): every registered signal light with its MTR facing
	 * angle, aspect count, mode (AUTO = infer / BOUND = covered bind) and - for rail-bound
	 * lights - the live aspect of the rail it reads.
	 */
	private static JsonObject getMmtrSignals(Simulator simulator) {
		final java.util.HashMap<String, String> railAspects = computeRailAspectMap(simulator);
		final com.google.gson.JsonArray signals = new com.google.gson.JsonArray();
		simulator.mmtrSignals.signals.forEach((key, entry) -> {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("key", key);
			out.addProperty("x", entry.x);
			out.addProperty("y", entry.y);
			out.addProperty("z", entry.z);
			out.addProperty("angle", entry.angle);
			out.addProperty("aspects", entry.aspects);
			out.addProperty("mode", entry.mode);
			out.addProperty("target", entry.target);
			if ("BOUND".equals(entry.mode) && !entry.target.isEmpty() && !entry.target.contains("|")) {
				out.addProperty("aspect", railAspects.getOrDefault(entry.target, "GREEN"));
			} else {
				out.addProperty("aspect", "");
			}
			signals.add(out);
		});
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("signals", signals);
		return root;
	}

	/**
	 * Junction leg tables feed (进向表): every authored (node, via) entry with its ordered
	 * continuation rails. Entries override geometric auto-detection wherever they exist.
	 */
	private static JsonObject getMmtrJunctionLegs(Simulator simulator) {
		final com.google.gson.JsonArray entries = new com.google.gson.JsonArray();
		simulator.mmtrJunctionLegs.legs.forEach((key, legHexes) -> {
			final String[] p = key.split("\\|");
			if (p.length != 2) {
				return;
			}
			final String[] c = p[0].split(",");
			if (c.length != 3) {
				return;
			}
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("x", Long.parseLong(c[0]));
			out.addProperty("y", Long.parseLong(c[1]));
			out.addProperty("z", Long.parseLong(c[2]));
			out.addProperty("via", p[1]);
			final com.google.gson.JsonArray legs = new com.google.gson.JsonArray();
			for (final String hex : legHexes) {
				legs.add(hex);
			}
			out.add("legs", legs);
			entries.add(out);
		});
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("entries", entries);
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
			// Pure topology edge: the web track display connects the rail's two real nodes with one
			// straight edge (no in-game curve sampling) - the map is a track graph, not geometry.
			final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("hex", hex);
			o.addProperty("x1", ends[0].getX());
			o.addProperty("y1", ends[0].getY());
			o.addProperty("z1", ends[0].getZ());
			o.addProperty("x2", ends[1].getX());
			o.addProperty("y2", ends[1].getY());
			o.addProperty("z2", ends[1].getZ());
			// Signal S2: per-direction speed limits (km/h from the MTR rail data) along each travel
			// direction of this edge - the web console colours / labels tracks by speed band + regime.
			o.addProperty("speedLimitKmh1", rail.getSpeedLimitKilometersPerHour(ends[0].compareTo(ends[1]) > 0));
			o.addProperty("speedLimitKmh2", rail.getSpeedLimitKilometersPerHour(ends[1].compareTo(ends[0]) > 0));
			rails.add(o);
		});
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("nodes", nodes);
		root.add("rails", rails);
		return root;
	}

	/** Automatic lines feed (线路自动识别): every detected line with its rails in stroke order. */
	private static JsonObject getMmtrLines(org.mtr.core.simulation.Simulator simulator) {
		final com.google.gson.JsonArray lines = new com.google.gson.JsonArray();
		for (final org.mtr.core.mmtr.line.MmtrLineDetector.MmtrLine line : simulator.mmtrDetectLines()) {
			final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("id", line.id());
			o.addProperty("name", line.name());
			o.addProperty("lengthM", Math.round(line.lengthM * 10.0) / 10.0);
			final com.google.gson.JsonArray rails = new com.google.gson.JsonArray();
			for (final String hex : line.rails) {
				rails.add(hex);
			}
			o.add("rails", rails);
			lines.add(o);
		}
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("lines", lines);
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

	/**
	 * Task-sheet timetable feed (任务单时间表): every consist job rendered as one row per task
	 * (step) with its kind, target, planned time and a per-step state derived from the scheduler
	 * (DONE for finished steps, RUNNING for the current one, FAILED on a dead job, PENDING after).
	 */
	private static JsonObject getMmtrSchedule(Simulator simulator) {
		final org.mtr.core.mmtr.job.MmtrJobScheduler scheduler = simulator.mmtrJobScheduler;
		final com.google.gson.JsonArray jobs = new com.google.gson.JsonArray();
		for (final org.mtr.core.mmtr.job.MmtrConsistJob job : simulator.getMmtrJobRegistry().jobs) {
			final com.google.gson.JsonObject jobJson = new com.google.gson.JsonObject();
			jobJson.addProperty("jobId", job.jobId);
			jobJson.addProperty("depotId", String.valueOf(job.depotId));
			jobJson.addProperty("sidingId", String.valueOf(job.sidingId));
			jobJson.addProperty("startTimeOfDayMs", job.startTimeOfDayMs);
			jobJson.addProperty("loop", job.loop);
			final String jobState = scheduler == null ? null : scheduler.stateOf(job.jobId) == null ? null : scheduler.stateOf(job.jobId).name();
			jobJson.addProperty("state", jobState == null ? "PENDING" : jobState);
			final int currentStep = scheduler == null ? -1 : scheduler.stepIndexOf(job.jobId);
			jobJson.addProperty("currentStep", currentStep);
			final String failure = scheduler == null ? null : scheduler.failureOf(job.jobId);
			if (failure != null) {
				jobJson.addProperty("failure", failure);
			}
			final com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
			for (int i = 0; i < job.steps.size(); i++) {
				final org.mtr.core.mmtr.job.MmtrJobStep step = job.steps.get(i);
				final com.google.gson.JsonObject row = new com.google.gson.JsonObject();
				row.addProperty("stepIndex", i);
				row.addProperty("stepId", step.stepId);
				row.addProperty("type", step.type.name());
				final boolean isPlatform = step.type != org.mtr.core.mmtr.job.MmtrJobStep.StepType.COUPLE && step.type != org.mtr.core.mmtr.job.MmtrJobStep.StepType.UNCOUPLE && isPlatform(simulator, step.targetId);
				row.addProperty("taskKind", taskKindOf(step, isPlatform));
				row.addProperty("targetKind", step.type == org.mtr.core.mmtr.job.MmtrJobStep.StepType.COUPLE || step.type == org.mtr.core.mmtr.job.MmtrJobStep.StepType.UNCOUPLE || step.type == org.mtr.core.mmtr.job.MmtrJobStep.StepType.CHANGE_ENDS ? "" : isPlatform ? "PLATFORM" : "SIDING");
				row.addProperty("targetId", String.valueOf(step.targetId));
				row.addProperty("plannedMs", step.dueTimeOfDayMs);
				if (step.note != null && !step.note.isEmpty()) {
					row.addProperty("note", step.note);
				}
				row.addProperty("state", stepState(jobState, currentStep, i));
				rows.add(row);
			}
			jobJson.add("rows", rows);
			jobs.add(jobJson);
		}
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("jobs", jobs);
		root.addProperty("currentTime", System.currentTimeMillis());
		return root;
	}

	/** Task-kind label of a step for the timetable (job step → task mapping, same as MmtrTaskFactory). */
	private static String taskKindOf(org.mtr.core.mmtr.job.MmtrJobStep step, boolean isPlatform) {
		return switch (step.type) {
			case MOVE_TO -> isPlatform ? "DRIVE_TO_PLATFORM" : "DRIVE_TO_SIDING";
			case SERVE -> "STATION_SERVICE";
			case CHANGE_ENDS -> "CHANGE_ENDS";
			case COUPLE -> "COUPLE";
			case UNCOUPLE -> "UNCOUPLE";
		};
	}

	private static String stepState(String jobState, int currentStep, int stepIndex) {
		if (jobState == null || jobState.equals("PENDING") || currentStep < 0) {
			return "PENDING";
		}
		return switch (jobState) {
			case "RUNNING" -> stepIndex < currentStep ? "DONE" : stepIndex == currentStep ? "RUNNING" : "PENDING";
			case "DONE" -> "DONE";
			case "FAILED" -> stepIndex < currentStep ? "DONE" : stepIndex == currentStep ? "FAILED" : "PENDING";
			default -> "PENDING";
		};
	}

	/** Whether the given world-object id is a platform (the timetable target-kind split). */
	private static boolean isPlatform(Simulator simulator, long targetId) {
		final boolean[] found = {false};
		simulator.platforms.forEach(platform -> {
			if (platform.getId() == targetId) {
				found[0] = true;
			}
		});
		return found[0];
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
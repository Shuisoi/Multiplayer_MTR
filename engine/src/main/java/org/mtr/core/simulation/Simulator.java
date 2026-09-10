package org.mtr.core.simulation;

import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.*;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.*;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.directions.DirectionsFinder;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.SerializedDataBaseWithId;
import org.mtr.core.servlet.MessageQueue;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.servlet.QueueObject;
import org.mtr.core.tool.Utilities;
import org.mtr.legacy.data.LegacyRailLoader;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Per-dimension simulation engine — one {@link Simulator} per Minecraft world / dimension.
 *
 * <p>The simulator owns the in-memory graph of stations, platforms, sidings, routes, depots,
 * lifts, rails, homes, landmarks and clients for its dimension, and ticks them forward in
 * one-second slices via {@link #tickUntilCaughtUp()}. State mutation is single-threaded: any
 * cross-thread work (HTTP servlets, embedding mod callbacks) must be marshalled through
 * {@link #run(Runnable)} so it executes on the simulator's own thread.</p>
 *
 * <p>Persistence is delegated to {@link FileLoader} — one per top-level entity type. Saves are
 * incremental (only changed buckets are rewritten) unless {@code useReducedHash} is {@code false}
 * during the final shutdown save.</p>
 */
@Log4j2
public class Simulator extends Data implements Utilities {

	private long lastMillis;
	private boolean autoSave = false;
	private long gameMillis;
	/**
	 * Real-time milliseconds per in-game day or {@code 0} if unknown / paused; default = 20 in-game minutes ≈ Minecraft's vanilla rate.
	 */
	@Getter
	private long gameMillisPerDay = DEFAULT_GAME_MILLIS_PER_DAY;
	/**
	 * Whether the daylight cycle (and therefore the in-game clock) is currently advancing.
	 */
	@Getter
	private boolean isTimeMoving;
	private long lastSetGameMillisMidnight;
	private int currentPassengerDirectionsRequests;

	/**
	 * Connected dashboard / mod clients for this dimension.
	 */
	public final ObjectArraySet<Client> clients = new ObjectArraySet<>();
	/**
	 * MMTR: optional server-side ConsistType definitions and the default consist id used for
	 * vehicles without an explicit type. Null/absent keeps the legacy driving behaviour.
	 */
	public ConsistTypeRegistry mmtrConsistTypes;
	public String mmtrDefaultConsistTypeId;
	/**
	 * MMTR: periodic task sources (timetable-style adapters). Each fires on its own cadence and
	 * attaches a mission to an idle parked train — the task belongs to the consist itself, no
	 * player/AI needed.
	 */
	public final ObjectArrayList<org.mtr.core.mmtr.MmtrPeriodicTaskSource> mmtrPeriodicTaskSources = new ObjectArrayList<>();
	/**
	 * MMTR: consist-job scheduler (web-driven diagrams). Null until a scheduler is attached; it
	 * ticks each simulation tick after vehicle simulation.
	 */
	public org.mtr.core.mmtr.job.MmtrJobScheduler mmtrJobScheduler;
	/** Named consist templates (编组代码 -> 车列), loaded from <save>/mmtr-consist-templates.json. */
	public org.mtr.core.mmtr.job.MmtrConsistTemplateRegistry mmtrConsistTemplates = new org.mtr.core.mmtr.job.MmtrConsistTemplateRegistry();
	private boolean mmtrDepotPathsGenerated;
	/**
	 * MMTR job mode: when true the legacy depot frequency/departure auto-dispatch is disabled -
	 * vehicles only run what MmtrJobScheduler starts (the web diagrams). Default false keeps the
	 * original MTR timetable behaviour until migration is complete.
	 */
	public boolean mmtrJobsMode;
	public org.mtr.core.mmtr.job.MmtrJobRegistry mmtrJobRegistry = new org.mtr.core.mmtr.job.MmtrJobRegistry();
	private java.nio.file.Path mmtrJobsPath;
	/**
	 * Rolling-stock manifest (车辆生成表): declares which consist each depot siding must carry after
	 * the explicit vehicle reset on every server restart. AI diagram steps are disabled by default;
	 * the manifest + per-vehicle operations own the traffic.
	 */
	public org.mtr.core.mmtr.manifest.MmtrRollingStockManifest mmtrRollingStock = new org.mtr.core.mmtr.manifest.MmtrRollingStockManifest();
	private java.nio.file.Path mmtrManifestPath;
	/** AI diagram step execution (web consist jobs). Off by default; reserved for the future task layer. */
	public boolean mmtrAiJobStepsEnabled;
	/** Operator-set turnout (道岔) branch states, persisted to mmtr-points.json. */
	public org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore mmtrPointBranches = new org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore();
	private java.nio.file.Path mmtrPointsPath;
	/** Authoritative junction leg tables (进向表): (node, via) -> ordered continuation rails. */
	public final org.mtr.core.mmtr.point.MmtrJunctionLegsRegistry.LegsStore mmtrJunctionLegs = new org.mtr.core.mmtr.point.MmtrJunctionLegsRegistry.LegsStore();
	private java.nio.file.Path mmtrJunctionLegsPath;
	/** Wayside signal registry (信号机登记表): placed MTR signal lights participating in the
	 * block/section model, with optional covered binds (which rail/approach a light reads). */
	public final org.mtr.core.mmtr.signal.MmtrSignalRegistry mmtrSignals = new org.mtr.core.mmtr.signal.MmtrSignalRegistry();
	private java.nio.file.Path mmtrSignalsPath;
	/** OP command queue (指令栏): web-pushed commands wait here for the game-side executor
	 * (fabric server) to poll and run (e.g. /signals scan); results come back as log lines. */
	public final java.util.ArrayDeque<String> mmtrCommandQueue = new java.util.ArrayDeque<>();
	public final java.util.ArrayDeque<String> mmtrCommandLog = new java.util.ArrayDeque<>();

	/** Web OP pushes a command; the game-side executor polls {@link #mmtrPollCommand()}. */
	public void mmtrPushCommand(String command) {
		final String cmd = command == null ? "" : command.trim();
		if (!cmd.isEmpty()) {
			mmtrCommandQueue.addLast(cmd);
			mmtrCommandLog.addLast("> " + cmd);
			while (mmtrCommandLog.size() > 200) {
				mmtrCommandLog.removeFirst();
			}
		}
	}

	/** Game-side executor takes the next pending command, or null when idle. */
	public @org.jspecify.annotations.Nullable String mmtrPollCommand() {
		final String cmd = mmtrCommandQueue.pollFirst();
		if (cmd != null) {
			mmtrCommandLog.addLast("… 执行: " + cmd);
			while (mmtrCommandLog.size() > 200) {
				mmtrCommandLog.removeFirst();
			}
		}
		return cmd;
	}

	/** Game-side executor reports a command outcome back into the OP log. */
	public void mmtrCommandResult(String result) {
		if (result != null && !result.isEmpty()) {
			mmtrCommandLog.addLast(result);
			while (mmtrCommandLog.size() > 200) {
				mmtrCommandLog.removeFirst();
			}
		}
	}
	/** P3 turnout authority (multi-level control): auto requests/grants per (node, via) point; the
	 * walker reads manual operator settings (mmtrPointBranches) first and this authority second. */
	public final org.mtr.core.mmtr.point.MmtrPointAuthority mmtrPointAuthority = new org.mtr.core.mmtr.point.MmtrPointAuthority(this::getCurrentMillis);
	/** C3a 调车授权 (subsidiary-aspect authority): one train at a time may pass a signal at danger into
	 * an occupied section to couple; the registry is the data plane the vehicle/yard read. */
	public final org.mtr.core.mmtr.signal.MmtrShuntAuthorityRegistry mmtrShuntAuthorities = new org.mtr.core.mmtr.signal.MmtrShuntAuthorityRegistry(this::getCurrentMillis);
	/** S5 进路登记表 (route registry): the live route object per train (rails + turnouts + SET/PENDING
	 * state), derived from {@link #mmtrPointAuthority}. The signal layer (A2) reads it to decide whether
	 * a proceed aspect may be shown; the ops feed shows it per train. */
	public final org.mtr.core.mmtr.route.MmtrRouteRegistry mmtrRoutes = new org.mtr.core.mmtr.route.MmtrRouteRegistry();
	/** 闭塞区间服务 (B1/B2): sections cut by the wayside signals reading each rail. S1 stops at a
	 * SECTION boundary instead of the rail end, so a train may run up to the signal protecting an
	 * occupied section. Lazy + rails/signals-signature gated. */
	public final org.mtr.core.mmtr.signal.MmtrBlockService mmtrBlocks = new org.mtr.core.mmtr.signal.MmtrBlockService(this);
	/** 闭塞区间 v2 (S1-S3): <strong>directional, lamp-to-lamp</strong> sections - what one lamp protects,
	 * walked the way it faces until the next lamp, so a section spans rail boundaries. S1 stops at this
	 * model's boundary ({@code Vehicle.directionalSectionStopM}) with the v1 service above as the
	 * fallback on rails no lamp reaches. Lazy + rails/signals-signature gated. */
	public final org.mtr.core.mmtr.signal.MmtrDirectionalBlockService mmtrDirectionalBlocks = new org.mtr.core.mmtr.signal.MmtrDirectionalBlockService(this);
	/** 硬默认 0 (option 3): real servers preset every turnout to operator branch 0. Engines tests keep
	 * this false so authority/mission semantics stay synthetic; {@link org.mtr.core.Main} enables it. */
	public boolean mmtrDefaultPointsZero;
	private String mmtrPointDefaultsSignature = "";

	/**
	 * MMTR health watchdog: produces a periodic health summary (SimRail-style server health):
	 * live vehicle/riding/driver counts, active mmtr overrides, protection states and jammed
	 * routes, so an operator (or an external process) can detect stuck trains early.
	 */
	private static final int MMTR_WATCHDOG_INTERVAL_TICKS = 100;
	/** 日志降噪: the summary is RECOUNTED every 5 s but only PRINTED this often while everything is idle. */
	private static final long MMTR_WATCHDOG_LOG_INTERVAL_MILLIS = 60_000L;
	private int watchdogTickCounter;
	private long watchdogLastCheckAt;
	private long watchdogLastLogAtMillis;
	private int watchdogVehicles;
	private int watchdogRiders;
	private int watchdogDrivers;
	private int watchdogMmtrOverrides;
	private int watchdogProtections;
	private int watchdogJammedRoutes;

	/**
	 * Stable dimension identifier (e.g. {@code "minecraft/overworld"}).
	 */
	public final String dimension;
	/**
	 * Identifiers of every dimension hosted in the same process — used for cross-dimension routing.
	 */
	public final String[] dimensions;
	/**
	 * Background path-finder for passenger directions queries.
	 */
	public final DirectionsFinder directionsFinder = new DirectionsFinder(this);

	private final FileLoader<Station> fileLoaderStations;
	private final FileLoader<Platform> fileLoaderPlatforms;
	private final FileLoader<Siding> fileLoaderSidings;
	private final FileLoader<Route> fileLoaderRoutes;
	private final FileLoader<Depot> fileLoaderDepots;
	private final FileLoader<Lift> fileLoaderLifts;
	private final FileLoader<Rail> fileLoaderRails;
	private final FileLoader<Home> fileLoaderHomes;
	private final FileLoader<Landmark> fileLoaderLandmarks;
	private final FileLoader<Settings> fileLoaderSettings;
	private final Consumer<Settings> writeSettings;
	private final MessageQueue<Runnable> queuedRuns = new MessageQueue<>();
	private final ObjectImmutableList<ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>>> vehiclePositions;
	private final Object2LongOpenHashMap<UUID> ridingVehicleIds = new Object2LongOpenHashMap<>();
	private final MessageQueue<QueueObject> messageQueueC2S = new MessageQueue<>();
	private final MessageQueue<QueueObject> messageQueueS2C = new MessageQueue<>();
	private final LongOpenHashSet jammedRouteIds = new LongOpenHashSet();

	/**
	 * If the simulation falls more than this many milliseconds behind wall clock, log a notice and
	 * fast-forward in one-second slices until caught up. Picked at two minutes as a balance between
	 * "noisy log on a sluggish host" and "silent multi-hour drift".
	 */
	private static final int SIMULATION_DIFFERENCE_LOGGING_THRESHOLD = 120000;
	private static final int MAX_PASSENGER_DIRECTIONS_REQUESTS = 512;
	/**
	 * Default in-game day length in real-time milliseconds (20 in-game minutes).
	 */
	private static final long DEFAULT_GAME_MILLIS_PER_DAY = 20L * 60 * MILLIS_PER_SECOND;

	/**
	 * Load a dimension from disk and bring its in-memory graph up to a tickable state.
	 *
	 * @param dimension           identifier of the dimension being loaded
	 * @param dimensions          identifiers of every dimension hosted in the same process
	 * @param rootPath            root data directory; per-dimension state lives under {@code rootPath/<dimension>}
	 * @param threadedFileLoading if {@code true}, fan file reads out across a thread pool
	 */
	public Simulator(String dimension, String[] dimensions, Path rootPath, boolean threadedFileLoading) {
		this.dimension = dimension;
		this.dimensions = dimensions;

		// Load data
		final Path savePath = rootPath.resolve(dimension);
		final ObjectLongImmutablePair<FileLoaderHolder> fileLoaderHolderAndDuration = Utilities.measureDuration(() -> {
			LegacyRailLoader.load(savePath, rails, threadedFileLoading);
			return new FileLoaderHolder(
				new FileLoader<>(stations, messagePackHelper -> new Station(messagePackHelper, this), savePath, "stations", threadedFileLoading),
				new FileLoader<>(platforms, messagePackHelper -> new Platform(messagePackHelper, this), savePath, "platforms", threadedFileLoading),
				new FileLoader<>(sidings, messagePackHelper -> new Siding(messagePackHelper, this), savePath, "sidings", threadedFileLoading),
				new FileLoader<>(routes, messagePackHelper -> new Route(messagePackHelper, this), savePath, "routes", threadedFileLoading),
				new FileLoader<>(depots, messagePackHelper -> new Depot(messagePackHelper, this), savePath, "depots", threadedFileLoading),
				new FileLoader<>(lifts, messagePackHelper -> new Lift(messagePackHelper, this), savePath, "lifts", threadedFileLoading),
				new FileLoader<>(rails, Rail::new, savePath, "rails", threadedFileLoading),
				new FileLoader<>(homes, messagePackHelper -> new Home(messagePackHelper, this), savePath, "homes", threadedFileLoading),
				new FileLoader<>(landmarks, messagePackHelper -> new Landmark(messagePackHelper, this), savePath, "landmarks", threadedFileLoading)
			);
		});
		fileLoaderStations = fileLoaderHolderAndDuration.left().fileLoaderStations;
		fileLoaderPlatforms = fileLoaderHolderAndDuration.left().fileLoaderPlatforms;
		fileLoaderSidings = fileLoaderHolderAndDuration.left().fileLoaderSidings;
		fileLoaderRoutes = fileLoaderHolderAndDuration.left().fileLoaderRoutes;
		fileLoaderDepots = fileLoaderHolderAndDuration.left().fileLoaderDepots;
		fileLoaderLifts = fileLoaderHolderAndDuration.left().fileLoaderLifts;
		fileLoaderRails = fileLoaderHolderAndDuration.left().fileLoaderRails;
		fileLoaderHomes = fileLoaderHolderAndDuration.left().fileLoaderHomes;
		fileLoaderLandmarks = fileLoaderHolderAndDuration.left().fileLoaderLandmarks;
		log.info("Data loading complete for {} in {} second(s)", dimension, (float) fileLoaderHolderAndDuration.rightLong() / MILLIS_PER_SECOND);
		System.out.println("[MMTR-DBG] loaded stations=" + stations.size() + " platforms=" + platforms.size() + " rails=" + rails.size()
			+ " sidings=" + sidings.size() + " depots=" + depots.size() + " routes=" + routes.size() + " lifts=" + lifts.size() + " for " + dimension);

		// MMTR: optional server-side consist-type policy at <root>/<dimension>/mmtr-consist-types.json
		final Path mmtrConfigPath = savePath.resolve("mmtr-consist-types.json");
		log.info("MMTR: dimension={}, savePath={}, consist config path={}", dimension, savePath, mmtrConfigPath);
		System.out.println("[MMTR-DBG] dimension=" + dimension + " savePath=" + savePath + " config=" + mmtrConfigPath);
		try {
			if (java.nio.file.Files.exists(mmtrConfigPath)) {
				mmtrConsistTypes = ConsistTypeRegistry.fromFile(mmtrConfigPath);
				mmtrDefaultConsistTypeId = mmtrConsistTypes.getDefaultId();
				if (mmtrDefaultConsistTypeId == null && !mmtrConsistTypes.all().isEmpty()) {
					mmtrDefaultConsistTypeId = mmtrConsistTypes.all().keySet().iterator().next();
				}
				log.info("MMTR consist-type policy loaded for {} (default={})", dimension, mmtrDefaultConsistTypeId);
			} else {
				log.info("MMTR: no consist-type policy at {} -> legacy driving behaviour", mmtrConfigPath);
			}
		} catch (Exception e) {
			log.warn("Failed to load MMTR consist-type policy for {}: {}", dimension, e.getMessage());
		}

		// MMTR: rolling-stock manifest (车辆生成表): read at restart, applied after the vehicle reset.
		mmtrManifestPath = savePath.resolve("mmtr-rolling-stock.json");
		try {
			if (java.nio.file.Files.exists(mmtrManifestPath)) {
				mmtrRollingStock = org.mtr.core.mmtr.manifest.MmtrRollingStockManifest.fromFile(mmtrManifestPath);
				log.info("MMTR: loaded rolling-stock manifest for {} ({} depot(s), {} siding(s))", dimension, mmtrRollingStock.depots.size(), mmtrRollingStock.sidingEntryCount());
			}
		} catch (Exception e) {
			log.warn("Failed to load MMTR rolling-stock manifest for {}: {}", dimension, e.getMessage());
		}

		// MMTR: operator turnout (道岔) branch states.
		mmtrPointsPath = savePath.resolve("mmtr-points.json");
		mmtrPointBranches = org.mtr.core.mmtr.point.MmtrPointRegistry.loadBranches(mmtrPointsPath);

		// MMTR: authoritative junction leg tables (进向表) - human/tool authored continuations per
		// (node, via rail). They override geometric auto-detection wherever they exist.
		mmtrJunctionLegsPath = savePath.resolve("mmtr-junction-legs.json");
		final org.mtr.core.mmtr.point.MmtrJunctionLegsRegistry.LegsStore loadedLegs = org.mtr.core.mmtr.point.MmtrJunctionLegsRegistry.load(mmtrJunctionLegsPath);
		loadedLegs.legs.forEach(mmtrJunctionLegs.legs::put);

		// MMTR: wayside signal registry (信号机登记表) - placed signal lights + covered binds.
		mmtrSignalsPath = savePath.resolve("mmtr-signals.json");
		final org.mtr.core.mmtr.signal.MmtrSignalRegistry loadedSignals = org.mtr.core.mmtr.signal.MmtrSignalRegistry.load(mmtrSignalsPath);
		loadedSignals.signals.forEach(mmtrSignals.signals::put);

		// MMTR: web-authored consist jobs (replaces the depot timetable for mmtr-managed stock).
		mmtrJobsPath = savePath.resolve("mmtr-jobs.json");
		try {
			if (java.nio.file.Files.exists(mmtrJobsPath)) {
				mmtrJobRegistry = org.mtr.core.mmtr.job.MmtrJobRegistry.fromFile(mmtrJobsPath);
			}
		} catch (Exception e) {
			log.warn("Failed to load MMTR consist jobs for {}: {}", dimension, e.getMessage());
		}

		// MMTR: named consist templates (编组代码) for job authoring.
		final java.nio.file.Path mmtrTemplatePath = savePath.resolve("mmtr-consist-templates.json");
		try {
			if (java.nio.file.Files.exists(mmtrTemplatePath)) {
				mmtrConsistTemplates = org.mtr.core.mmtr.job.MmtrConsistTemplateRegistry.fromFile(mmtrTemplatePath);
				log.info("MMTR: loaded {} consist template(s) for {}", mmtrConsistTemplates.templates.size(), dimension);
			}
		} catch (Exception e) {
			log.warn("Failed to load MMTR consist templates for {}: {}", dimension, e.getMessage());
		}

		if (!mmtrJobRegistry.jobs.isEmpty()) {
			expandMmtrJobTemplates();
			mmtrJobsMode = true; // jobs present => web orchestration owns the traffic
			mmtrAiJobStepsEnabled = true; // scheduler ticks when consist jobs are loaded
			mmtrJobScheduler = org.mtr.core.mmtr.job.MmtrJobScheduler.create(mmtrJobRegistry.jobs);
			log.info("MMTR: loaded {} consist job(s) for {}", mmtrJobRegistry.jobs.size(), dimension);
		}

		// Initialize cache
		sync();
		depots.forEach(Depot::init);
		rails.forEach(Rail::checkMigrationStatus);

		final ObjectArrayList<ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>>> tempVehiclePositions = new ObjectArrayList<>();
		for (int i = 0; i < TransportMode.values().length; i++) {
			final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositionsForTransportMode = new ObjectArrayList<>();
			vehiclePositionsForTransportMode.add(new Object2ObjectAVLTreeMap<>());
			vehiclePositionsForTransportMode.add(new Object2ObjectAVLTreeMap<>());
			tempVehiclePositions.add(vehiclePositionsForTransportMode);
		}
		vehiclePositions = new ObjectImmutableList<>(tempVehiclePositions);
		sidings.forEach(siding -> siding.initVehiclePositions(vehiclePositions.get(siding.getTransportModeOrdinal()).get(1)));
		homes.forEach(home -> home.iteratePassengers(passenger -> passenger.writeVehicleCache(this)));

		// Load settings
		final ObjectArraySet<Settings> settings = new ObjectArraySet<>();
		fileLoaderSettings = new FileLoader<>(settings, Settings::new, savePath, "settings", threadedFileLoading);
		writeSettings = newSettings -> {
			settings.clear();
			settings.add(newSettings);
		};

		// Set the last simulated millis
		setCurrentMillis(Utilities.getElement(new ObjectArrayList<>(settings), 0, new Settings(0)).getLastSimulationMillis());

		// MMTR: explicit rolling-stock reset on restart - clear all trains, then generate per manifest.
		if (!mmtrRollingStock.isEmpty()) {
			mmtrResetAndApplyRollingStock();
		}
	}

	/**
	 * Catch the simulation up to wall clock and log a notice if it had drifted by more than
	 * {@link #SIMULATION_DIFFERENCE_LOGGING_THRESHOLD} milliseconds. If the drift exceeds an hour
	 * the simulator jumps to "one hour ago" instead of replaying the full gap, since replaying
	 * many hours of vehicle motion is rarely useful and is expensive.
	 */
	public void tick() {
		final long totalDifference = System.currentTimeMillis() - getCurrentMillis();
		if (totalDifference >= SIMULATION_DIFFERENCE_LOGGING_THRESHOLD) {
			if (totalDifference > MILLIS_PER_HOUR) {
				// If the simulation is over an hour behind, jump to one hour ago and simulate the last hour
				setCurrentMillis(System.currentTimeMillis() - MILLIS_PER_HOUR);
				sidings.forEach(Siding::clearVehicles);
			}
			final ObjectLongImmutablePair<Integer> ticksAndDuration = Utilities.measureDuration(this::tickUntilCaughtUp);
			log.info(
				"Simulation difference of {}h{}m for {} caught up with {} ticks in {} second(s)",
				totalDifference / MILLIS_PER_SECOND / (MILLIS_PER_HOUR / MILLIS_PER_SECOND), (totalDifference / MILLIS_PER_SECOND / (MILLIS_PER_MINUTE / MILLIS_PER_SECOND)) % (MILLIS_PER_MINUTE / MILLIS_PER_SECOND),
				dimension,
				ticksAndDuration.left(),
				(float) ticksAndDuration.rightLong() / MILLIS_PER_SECOND
			);
		} else {
			tickUntilCaughtUp();
		}
	}

	/**
	 * Schedule a full save on the next tick. Returns immediately.
	 */
	/**
	 * MMTR: upsert a consist job from the web editor; persists it and rebuilds the scheduler.
	 */
	public void upsertMmtrJob(org.mtr.core.mmtr.job.MmtrConsistJob job) {
		mmtrJobRegistry.put(job);
		persistMmtrJobs();
	}

	/**
	 * MMTR: delete a consist job by id; persists and rebuilds the scheduler.
	 * @return whether a job was removed
	 */
	public boolean deleteMmtrJob(String jobId) {
		final boolean removed = mmtrJobRegistry.remove(jobId);
		if (removed) {
			persistMmtrJobs();
		}
		return removed;
	}

	public org.mtr.core.mmtr.job.MmtrJobRegistry getMmtrJobRegistry() {
		return mmtrJobRegistry;
	}

	/**
	 * MMTR rolling-stock reset (车辆生成表 apply): drop every generated train - parked or en route -
	 * on all sidings, clear stale templates, then install the manifest's consist on each declared
	 * depot siding so the engine spawns exactly the configured rolling stock. Runs explicitly once
	 * at startup and is callable on demand (operator reset). Job-mode dispatch stays disabled so the
	 * generated trains simply wait for a human or an AI driver.
	 *
	 * @return number of sidings the manifest installed stock on
	 */
	public int mmtrResetAndApplyRollingStock() {
		sidings.forEach(Siding::clearVehicles);
		sidings.forEach(siding -> {
			siding.setVehicleCars(new ObjectArrayList<>());
			siding.mmtrManualSpawn = false;
			siding.mmtrSessionSpawned = false;
		});
		int placed = 0;
		for (final org.mtr.core.mmtr.manifest.MmtrManifestDepot depotEntry : mmtrRollingStock.depots) {
			Depot depot = null;
			for (final Depot candidate : depots) {
				if (candidate.getId() == depotEntry.depotId) {
					depot = candidate;
					break;
				}
			}
			if (depot == null) {
				System.out.println("[MMTR-MFST] manifest depot " + depotEntry.depotId + " not found - skipped");
				continue;
			}
			for (final org.mtr.core.mmtr.manifest.MmtrManifestSiding sidingEntry : depotEntry.sidings) {
				if (sidingEntry.cars.isEmpty()) {
					continue;
				}
				Siding siding = null;
				for (final Siding candidate : depot.savedRails) {
					if (candidate.getId() == sidingEntry.sidingId) {
						siding = candidate;
						break;
					}
				}
				if (siding == null) {
					System.out.println("[MMTR-MFST] manifest siding " + sidingEntry.sidingId + " not found in depot " + depotEntry.depotId + " - skipped");
					continue;
				}
				final ObjectArrayList<org.mtr.core.data.VehicleCar> cars = new ObjectArrayList<>();
				for (final org.mtr.core.mmtr.job.MmtrCarSpec spec : sidingEntry.cars) {
					cars.add(spec.toVehicleCar());
				}
				if (Siding.getTotalVehicleLength(cars) > siding.getRailLength()) {
					System.out.println("[MMTR-MFST] manifest consist on siding " + sidingEntry.sidingId + " does not fit (rail " + siding.getRailLength() + " m) - skipped");
					continue;
				}
				siding.setVehicleCars(cars);
				siding.mmtrManualSpawn = true;
				siding.mmtrSessionSpawned = false;
				placed++;
			}
		}
		if (placed > 0) {
			// Keep legacy depot auto-dispatch off so generated stock stays parked until operated.
			mmtrJobsMode = true;
		}
		System.out.println("[MMTR-MFST] rolling-stock reset applied: " + placed + " siding(s) staged for spawn on " + dimension);
		return placed;
	}

	public org.mtr.core.mmtr.manifest.MmtrRollingStockManifest getMmtrRollingStock() {
		return mmtrRollingStock;
	}

	/**
	 * MMTR session-level cleanup: remove every generated train (parked or en route) and clear every
	 * siding template so nothing re-seeds. The rolling-stock manifest is untouched - it is applied
	 * again on the next restart (explicit reset).
	 */
	public void mmtrClearAllVehicles() {
		sidings.forEach(Siding::clearVehicles);
		sidings.forEach(siding -> {
			siding.setVehicleCars(new ObjectArrayList<>());
			siding.mmtrManualSpawn = false;
			siding.mmtrSessionSpawned = false;
		});
		System.out.println("[MMTR-MFST] cleared all vehicles + templates on " + dimension);
	}

	private Object[] mmtrLinesCache; // {signature, lines}

	/**
	 * Automatic line detection (线路自动识别): deterministic partition of every real rail into
	 * "lines" (straightest-continuation strokes, longest first). Cached against a rail-set
	 * signature so frequent map polling never re-runs the detector; invalidates when rails change.
	 */
	public ObjectArrayList<org.mtr.core.mmtr.line.MmtrLineDetector.MmtrLine> mmtrDetectLines() {
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<String> hexes = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
		for (final org.mtr.core.data.Rail rail : rails) {
			hexes.add(rail.getHexId());
		}
		hexes.sort(null);
		final StringBuilder sig = new StringBuilder().append(hexes.size()).append('|');
		for (final String hex : hexes) {
			sig.append(hex).append(',');
		}
		final String signature = sig.toString();
		if (mmtrLinesCache != null && mmtrLinesCache[0].equals(signature)) {
			return (ObjectArrayList<org.mtr.core.mmtr.line.MmtrLineDetector.MmtrLine>) mmtrLinesCache[1];
		}
		final ObjectArrayList<org.mtr.core.mmtr.line.MmtrLineDetector.MmtrLine> lines = org.mtr.core.mmtr.line.MmtrLineDetector.detect(this);
		mmtrLinesCache = new Object[]{signature, lines};
		return lines;
	}

	/**
	 * Hard default 0: ensure every turnout (fork with 2+ legs) carries an explicit operator branch
	 * 0. Runs on boot / whenever the rail set changes (rails-signature gated, so an operator clearing
	 * a fork with ✕设 keeps it unset until the track changes or the server restarts).
	 */
	public void mmtrEnsurePointDefaults() {
		if (!mmtrDefaultPointsZero) {
			return;
		}
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<String> hexes = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
		for (final org.mtr.core.data.Rail rail : rails) {
			hexes.add(rail.getHexId());
		}
		hexes.sort(null);
		final StringBuilder sig = new StringBuilder().append(hexes.size()).append('|');
		for (final String hex : hexes) {
			sig.append(hex).append(',');
		}
		final String signature = sig.toString();
		if (signature.equals(mmtrPointDefaultsSignature)) {
			return;
		}
		mmtrPointDefaultsSignature = signature;
		final java.util.Set<String> currentForks = new java.util.HashSet<>();
		boolean changed = false;
		for (final org.mtr.core.mmtr.point.MmtrPoint point : org.mtr.core.mmtr.point.MmtrPoint.discoverDirectionAware(this)) {
			if (point.legs.size() < 2) {
				continue;
			}
			currentForks.add(point.nodeX + "," + point.nodeY + "," + point.nodeZ + "|" + point.viaRailHex);
			if (!mmtrPointBranches.contains(point.nodeX, point.nodeY, point.nodeZ, point.viaRailHex)) {
				mmtrPointBranches.set(point.nodeX, point.nodeY, point.nodeZ, point.viaRailHex, 0);
				changed = true;
			}
		}
		// Prune stale operator rows (forks that disappeared with a rail change), then persist once.
		changed |= mmtrPointBranches.branches.keySet().removeIf(key -> !currentForks.contains(key));
		if (changed && mmtrPointsPath != null) {
			org.mtr.core.mmtr.point.MmtrPointRegistry.saveBranches(mmtrPointsPath, mmtrPointBranches.branches);
		}
	}

	private String mmtrSignalColorsSignature = "";

	/**
	 * MMTR signal display (server-authoritative): give every rail its own reserved MMTR signal
	 * color (rails-signature gated) so the standard rail signal-block channel can carry MMTR
	 * occupancy - {@link Vehicle#markMmtrSignalBlocks()} registers CURRENTLY_RESERVE holds under
	 * these colors, Rail#tick1 diffs them and pushes SignalBlockUpdates, and the in-game signal
	 * lights turn red for EVERY client regardless of locally simulated vehicles.
	 *
	 * <p>B3b: a rail that a wayside signal splits carries one colour per SECTION (section 0 = the rail
	 * colour, so unsplit rails are unchanged); {@link Vehicle#markMmtrSignalBlocks()} reserves only the
	 * sections the consist actually occupies, which is what lets the display count sections.</p>
	 */
	public void mmtrEnsureSignalColors() {
		// B3b: a rail split by a wayside signal carries one colour per SECTION, so the standard
		// signal-block channel can carry per-section occupancy to every client.
		mmtrBlocks.refresh();
		final StringBuilder sig = new StringBuilder().append(rails.size()).append('|').append(mmtrBlocks.signature()).append('|');
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<String> hexes = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
		for (final org.mtr.core.data.Rail rail : rails) {
			hexes.add(rail.getHexId());
		}
		hexes.sort(null);
		hexes.forEach(hex -> sig.append(hex).append(','));
		final String signature = sig.toString();
		if (signature.equals(mmtrSignalColorsSignature)) {
			return;
		}
		mmtrSignalColorsSignature = signature;
		rails.forEach(rail -> {
			rail.mmtrEnsureSignalColor();
			mmtrBlocks.blocksOf(rail.getHexId()).forEach(block -> rail.mmtrEnsureSignalColor(block.signalColor));
		});
	}

	/**
	 * ④ 显示层: the live occupancy trees of the train transport mode (the pair S1 reads: current tick
	 * and the previous one), or null before {@link #sync()}. The aspect view uses them to tell whether a
	 * junction's clearance zone is fouled; nothing else should mutate them.
	 */
	public @org.jspecify.annotations.Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> mmtrOccupancyTrees() {
		final int ordinal = org.mtr.core.data.TransportMode.TRAIN.ordinal();
		return vehiclePositions.isEmpty() || ordinal >= vehiclePositions.size() ? null : vehiclePositions.get(ordinal);
	}

	private org.mtr.core.mmtr.signal.@org.jspecify.annotations.Nullable MmtrSignalAspect mmtrSignalAspectView;
	private String mmtrSignalAspectSignature = "";

	/**
	 * A2/A3: the cached signal-aspect view (进路 × 闭塞). Rebuilt when the rail set changes - the
	 * same rails-signature gate the signal colours use - while the route state is read live from
	 * {@link #mmtrRoutes} on every call. Vehicles ask it what the signal they are about to pass
	 * shows; the web feed builds its own (one per request).
	 */
	public org.mtr.core.mmtr.signal.MmtrSignalAspect mmtrSignalAspectView() {
		if (mmtrSignalAspectView == null) {
			mmtrSignalAspectSignature = mmtrRailSetSignature();
			mmtrSignalAspectView = new org.mtr.core.mmtr.signal.MmtrSignalAspect(this, mmtrRoutes);
		}
		return mmtrSignalAspectView;
	}

	/** Rebuild the cached aspect view when the rail set changed (once per tick, after the rail ticks). */
	public void mmtrRefreshSignalAspectView() {
		final String signature = mmtrRailSetSignature();
		if (!signature.equals(mmtrSignalAspectSignature)) {
			mmtrSignalAspectSignature = signature;
			mmtrSignalAspectView = new org.mtr.core.mmtr.signal.MmtrSignalAspect(this, mmtrRoutes);
		}
	}

	private String mmtrRailSetSignature() {
		final StringBuilder sig = new StringBuilder().append(rails.size()).append('|');
		final ObjectArrayList<String> hexes = new ObjectArrayList<>();
		for (final org.mtr.core.data.Rail rail : rails) {
			hexes.add(rail.getHexId());
		}
		hexes.sort(null);
		hexes.forEach(hex -> sig.append(hex).append(','));
		return sig.toString();
	}

	/** Discover all turnouts (道岔) on the rail graph with the operator branch states applied. */
	public ObjectArrayList<org.mtr.core.mmtr.point.MmtrSwitch> mmtrDiscoverPoints() {
		final ObjectArrayList<org.mtr.core.mmtr.point.MmtrSwitch> points = org.mtr.core.mmtr.point.MmtrPointRegistry.discover(this);
		for (final org.mtr.core.mmtr.point.MmtrSwitch s : points) {
			s.branch = mmtrPointBranches.get(s.nodeX, s.nodeY, s.nodeZ, s.viaRailHex);
		}
		return points;
	}

	/** Set an operator turnout branch index (0..legs-1 in the ordered-leg model, legacy 0/1 on
	 * two-leg forks) and persist it. A negative branch removes the operator setting (halt at that
	 * fork, never auto). */
	public boolean mmtrSetPoint(long x, long y, long z, String viaRailHex, int branch) {
		mmtrPointBranches.set(x, y, z, viaRailHex, branch);
		if (mmtrPointsPath != null) {
			org.mtr.core.mmtr.point.MmtrPointRegistry.saveBranches(mmtrPointsPath, mmtrPointBranches.branches);
		}
		System.out.println("[MMTR-PT] set switch " + x + "," + y + "," + z + " via " + viaRailHex + " -> " + (branch < 0 ? "unset" : String.valueOf(branch)));
		return true;
	}

	/** Persist the operator branch store to mmtr-points.json (batch clear before a mission arm). */
	public void persistMmtrPointBranches() {
		if (mmtrPointsPath != null) {
			org.mtr.core.mmtr.point.MmtrPointRegistry.saveBranches(mmtrPointsPath, mmtrPointBranches.branches);
		}
	}

	// --- P3 turnout authority machine interface (multi-level control) ---

	/** Auto logic requests a leg of an en-route turnout (approach locking). Returns GRANTED/QUEUED. */
	/**
	 * MMTR: upsert one authoritative junction leg table entry (进向表) for (node, via rail).
	 * Empty {@code legHexes} removes the entry (geometry auto-detection takes over again).
	 * @return whether the table changed
	 */
	public boolean mmtrJunctionLegsUpsert(long x, long y, long z, String viaRailHex, it.unimi.dsi.fastutil.objects.ObjectArrayList<String> legHexes) {
		final boolean changed;
		if (legHexes == null || legHexes.isEmpty()) {
			changed = mmtrJunctionLegs.legs.remove(x + "," + y + "," + z + "|" + viaRailHex) != null;
		} else {
			final String key = x + "," + y + "," + z + "|" + viaRailHex;
			final it.unimi.dsi.fastutil.objects.ObjectArrayList<String> prev = mmtrJunctionLegs.legs.get(key);
			changed = prev == null || !prev.equals(legHexes);
			if (changed) {
				mmtrJunctionLegs.legs.put(key, new it.unimi.dsi.fastutil.objects.ObjectArrayList<>(legHexes));
			}
		}
		if (changed && mmtrJunctionLegsPath != null) {
			org.mtr.core.mmtr.point.MmtrJunctionLegsRegistry.save(mmtrJunctionLegsPath, mmtrJunctionLegs.legs);
		}
		return changed;
	}

	/**
	 * MMTR: upsert/remove one wayside signal entry (信号机登记表). Ops:
	 * "set" = register the light (AUTO unless target given -> BOUND), "remove" = delete.
	 * @return whether the registry changed
	 */
	public boolean mmtrSignalOp(int x, int y, int z, float angle, int aspects, String op, String target) {
		final boolean changed;
		if ("remove".equalsIgnoreCase(op)) {
			changed = mmtrSignals.signals.remove(org.mtr.core.mmtr.signal.MmtrSignalRegistry.key(x, y, z)) != null;
		} else {
			final String mode = target == null || target.isEmpty() ? "AUTO" : "BOUND";
			final boolean had = mmtrSignals.get(x, y, z) != null;
			mmtrSignals.put(x, y, z, angle, aspects, mode, target);
			changed = !had;
		}
		if (changed && mmtrSignalsPath != null) {
			org.mtr.core.mmtr.signal.MmtrSignalRegistry.save(mmtrSignalsPath, mmtrSignals.signals);
		}
		return changed;
	}

	/**
	 * Covered bind helper (游戏侧工具/扫描上行): given the light position/angle and a clicked rail node,
	 * choose the rail leaving that node whose heading best matches the light's facing, then register the
	 * light BOUND to that rail.
	 *
	 * <p><strong>Single source of truth (闭塞区间 v2).</strong> The rail is chosen by the SAME resolution
	 * the section model uses ({@code MmtrDirectionalBlockService.resolveProtectedRail}), which resolves by
	 * the lamp's facing angle. An earlier version re-implemented the facing maths here with a
	 * "the renderer applies a 90 degree offset" assumption; a bind tool that disagrees with the model by a
	 * quarter turn is exactly how a light ends up bound to a rail running ACROSS its facing - the dead
	 * binding found at {@code -163,-60,-189} (notes/105 §3.1), which then neither cuts a section nor shows
	 * a trustworthy aspect.</p>
	 *
	 * @return whether a matching rail was found and the light registered
	 */
	public boolean mmtrSignalBindAtNode(int x, int y, int z, float angle, int aspects, long nodeX, long nodeY, long nodeZ) {
		final org.mtr.core.data.Position node = new org.mtr.core.data.Position(nodeX, nodeY, nodeZ);
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<org.mtr.core.data.Position, org.mtr.core.data.Rail> neighbours = positionsToRail.get(node);
		if (neighbours == null || neighbours.isEmpty()) {
			return false;
		}
		// Ask the v2 model which rail this lamp protects (by its facing). Fall back to the nearest-heading
		// search only when the model cannot answer (no rail within tolerance of the light).
		final org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry probe =
			new org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry(x, y, z, angle, aspects);
		final org.mtr.core.mmtr.signal.MmtrDirectionalBlockService.ProtectedRail resolved =
			mmtrDirectionalBlocks.resolveProtectedRail(probe);
		if (resolved != null) {
			return mmtrSignalOp(x, y, z, angle, aspects, "set", resolved.rail.getHexId());
		}
		final double want = Math.toRadians(angle + 90.0);
		final double[] best = {Double.MAX_VALUE};
		final String[] bestHex = {null};
		neighbours.forEach((far, rail) -> {
			final double bearing = Math.atan2(far.getZ() - node.getZ(), far.getX() - node.getX());
			double diff = bearing - want;
			while (diff > Math.PI) {
				diff -= 2 * Math.PI;
			}
			while (diff < -Math.PI) {
				diff += 2 * Math.PI;
			}
			if (Math.abs(diff) < best[0]) {
				best[0] = Math.abs(diff);
				bestHex[0] = rail.getHexId();
			}
		});
		return bestHex[0] != null && mmtrSignalOp(x, y, z, angle, aspects, "set", bestHex[0]);
	}

	public org.mtr.core.mmtr.point.MmtrPointAuthority.Result mmtrPointRequest(long x, long y, long z, String viaRailHex, String owner, int leg, long untilMillis) {
		final org.mtr.core.mmtr.point.MmtrPointAuthority.Result result = mmtrPointAuthority.request(x, y, z, viaRailHex, owner, leg, untilMillis);
		System.out.println("[MMTR-PT] req " + owner + "@" + x + "," + y + "," + z + " via " + viaRailHex + " leg " + leg + " -> " + result);
		return result;
	}

	/** The train crossed (or gave up on) a point: its hold is consumed and the queue advances. */
	public void mmtrPointRelease(long x, long y, long z, String viaRailHex, String owner) {
		mmtrPointAuthority.passed(x, y, z, viaRailHex, owner);
		System.out.println("[MMTR-PT] rel " + owner + "@" + x + "," + y + "," + z + " via " + viaRailHex);
	}

	/** Operator parks a point for manual use: auto requests queue until mmtrPointUnlock. */
	public void mmtrPointLock(long x, long y, long z, String viaRailHex) {
		mmtrPointAuthority.lock(x, y, z, viaRailHex);
		System.out.println("[MMTR-PT] lock " + x + "," + y + "," + z + " via " + viaRailHex);
	}

	public void mmtrPointUnlock(long x, long y, long z, String viaRailHex) {
		mmtrPointAuthority.unlock(x, y, z, viaRailHex);
		System.out.println("[MMTR-PT] unlock " + x + "," + y + "," + z + " via " + viaRailHex);
	}

	/** Release every turnout request held/queued by this owner (terminal missions, resets). */
	public void mmtrPointReleaseAll(String owner) {
		mmtrPointAuthority.releaseAll(owner);
	}


	/**
	 * MMTR vehicle-level operation: delete the train with the given world-unique vehicle id
	 * wherever it stands (parked or en route). Generation (rolling-stock manifest) and deletion are
	 * independent operations keyed by the spawned train's own id.
	 *
	 * @return whether a vehicle with that id existed and was removed
	 */
	public boolean deleteMmtrVehicle(long vehicleId) {
		for (final Siding siding : sidings) {
			if (siding.removeVehicleById(vehicleId)) {
				// A deleted train must not leave a stale route / turnout hold behind: the signal layer
				// would keep showing its route as set over rails nothing runs on any more.
				mmtrRoutes.release(vehicleId);
				mmtrPointAuthority.releaseAll("v" + vehicleId);
				mmtrShuntAuthorities.revoke(vehicleId);
				return true;
			}
		}
		return false;
	}

	/**
	 * B7.6: find a live vehicle by id — OP commands and cab ops address vehicles by their engine id
	 * (the same id the web feeds and the drive command use).
	 */
	@Nullable
	public Vehicle mmtrFindVehicle(long vehicleId) {
		final Vehicle[] found = {null};
		sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			if (vehicle.getId() == vehicleId) {
				found[0] = vehicle;
			}
		}));
		return found[0];
	}

	private void persistMmtrJobs() {
		expandMmtrJobTemplates();
		if (!mmtrJobRegistry.jobs.isEmpty()) {
			mmtrJobsMode = true;
			mmtrAiJobStepsEnabled = true; // scheduler ticks as soon as consist jobs exist
		}
		if (mmtrJobsPath != null) {
			mmtrJobRegistry.save(mmtrJobsPath);
		}
		mmtrJobScheduler = org.mtr.core.mmtr.job.MmtrJobScheduler.create(mmtrJobRegistry.jobs);
	}

	/**
	 * Expand jobs that reference a named consist template (车辆代码) but carry no explicit cars.
	 * Authoring then is just 车场/股道 + 编组代码; spawning still uses the concrete car list.
	 */
	private void expandMmtrJobTemplates() {
		for (final org.mtr.core.mmtr.job.MmtrConsistJob job : mmtrJobRegistry.jobs) {
			if (!job.consistId.isEmpty() && job.cars.isEmpty()) {
				final org.mtr.core.mmtr.job.MmtrConsistTemplate template = mmtrConsistTemplates.get(job.consistId);
				if (template != null) {
					for (final org.mtr.core.mmtr.job.MmtrCarSpec spec : template.cars) {
						job.cars.add(spec);
					}
				}
			}
		}
	}


	public void save() {
		autoSave = true;
	}

	/**
	 * Stop ticking and perform a final, non-incremental save.
	 */
	public void stop() {
		save(false);
	}

	/**
	 * Compare {@code millis} to the last-tick / current-tick window, accounting for day-wrap.
	 *
	 * @param millis Milliseconds to check
	 * @return 1 if upcoming, 0 if current, -1 if passed
	 */
	public int matchMillis(long millis) {
		if (Utilities.circularDifference(getCurrentMillis(), millis, MILLIS_PER_DAY) < 0) {
			return 1;
		} else {
			return Utilities.circularDifference(millis, lastMillis, MILLIS_PER_DAY) > 0 ? 0 : -1;
		}
	}

	/**
	 * Fast-forward the listed depots through one full in-game day in one-second slices, leaving
	 * the simulator's clock unchanged. Used by the dashboard "instant deploy" button so depot
	 * vehicles are immediately spawned on every siding.
	 */
	public void instantDeployDepots(ObjectArrayList<Depot> depotsToInstantDeploy) {
		final long oldLastMillis = lastMillis;
		final long oldCurrentMillis = getCurrentMillis();
		for (int i = 0; i < MILLIS_PER_DAY; i += MILLIS_PER_SECOND) {
			lastMillis = getCurrentMillis();
			setCurrentMillis(lastMillis + MILLIS_PER_SECOND);
			depotsToInstantDeploy.forEach(depot -> depot.savedRails.forEach(siding -> siding.simulateVehicles(MILLIS_PER_SECOND, null)));
		}
		lastMillis = oldLastMillis;
		setCurrentMillis(oldCurrentMillis);
	}

	/**
	 * Convenience overload of {@link #instantDeployDepots(ObjectArrayList)} that selects depots by
	 * a name {@code filter} via {@link NameColorDataBase#getDataByName}.
	 *
	 * @param simulator simulator whose depots are scanned (typically {@code this} — kept as a
	 *                  parameter so the method matches the embedding mod's existing signature)
	 * @param filter    case-insensitive name filter
	 */
	public void instantDeployDepotsByName(Simulator simulator, String filter) {
		instantDeployDepots(NameColorDataBase.getDataByName(simulator.depots, filter));
	}

	/**
	 * MMTR deterministic stepping: advance the simulation by exactly millisElapsed simulation
	 * milliseconds, independent of the host wall clock. tick() chases the wall clock, so in fast
	 * headless loops most ticks advance 0 ms and physics freezes; this seam is the deterministic
	 * entry point used by tests and future headless task servers. Internally it slices into
	 * one-second steps, matching the engine's own catch-up cadence.
	 */
	public void step(long millisElapsed) {
		while (millisElapsed > 0) {
			final long slice = Math.min(millisElapsed, MILLIS_PER_SECOND);
			tick(slice);
			millisElapsed -= slice;
		}
	}
	/**
	 * @param gameMillis       the number of real-time milliseconds since midnight of the in-game time
	 * @param gameMillisPerDay the total number of real-time milliseconds of one in-game day
	 * @param isTimeMoving     whether the daylight cycle is on
	 */
	public void setGameTime(long gameMillis, long gameMillisPerDay, boolean isTimeMoving) {
		this.gameMillis = gameMillisPerDay > 0 ? gameMillis % gameMillisPerDay : gameMillis;
		this.gameMillisPerDay = gameMillisPerDay;
		this.isTimeMoving = isTimeMoving;
		lastSetGameMillisMidnight = getCurrentMillis() - gameMillis;
	}

	/**
	 * @return milliseconds after epoch of the first midnight in-game
	 */
	public long getMillisOfGameMidnight() {
		return gameMillisPerDay > 0 && isTimeMoving ? Math.max(0, lastSetGameMillisMidnight - lastSetGameMillisMidnight / gameMillisPerDay * gameMillisPerDay) : 0;
	}

	/**
	 * @return the game hour (0-23)
	 */
	public int getGameHour() {
		return getGameHourAt(getCurrentMillis());
	}

	/**
	 * @param simulationMillis target simulation timestamp
	 * @return normalized in-game milliseconds in [0, gameMillisPerDay), projected from current
	 * simulator time if game time is moving
	 */
	public long getGameMillisAt(long simulationMillis) {
		if (gameMillisPerDay <= 0) {
			return 0;
		}

		final long projectedGameMillis = isTimeMoving ? gameMillis + (simulationMillis - getCurrentMillis()) : gameMillis;
		return Math.floorMod(projectedGameMillis, gameMillisPerDay);
	}

	/**
	 * @param simulationMillis target simulation timestamp
	 * @return projected in-game hour (0-23) at {@code simulationMillis}
	 */
	public int getGameHourAt(long simulationMillis) {
		return gameMillisPerDay > 0 ? (int) (getGameMillisAt(simulationMillis) * HOURS_PER_DAY / gameMillisPerDay) : 0;
	}

	/**
	 * Map an in-game day offset (0..{@link Utilities#MILLIS_PER_DAY}) to an absolute simulation
	 * timestamp using the current game-day scale.
	 */
	public long getSimulationMillisAtGameDayOffset(long gameDayOffsetMillis) {
		if (gameMillisPerDay <= 0) {
			return getCurrentMillis();
		}

		return getMillisOfGameMidnight() + Math.floorMod(gameDayOffsetMillis, (long) MILLIS_PER_DAY) * gameMillisPerDay / MILLIS_PER_DAY;
	}

	/**
	 * For in-game schedules, when game time is paused we pin frequency lookup to the current game
	 * hour; when moving, use the iterated schedule hour.
	 */
	public int getScheduleFrequencyHour(int iteratedHour) {
		return isTimeMoving ? iteratedHour : getGameHour();
	}

	/**
	 * Limit how many new passenger direction requests enter CSA per simulation tick to keep latency
	 * stable on very large maps.
	 */
	public boolean tryConsumePassengerDirectionsRequestBudget() {
		if (currentPassengerDirectionsRequests >= MAX_PASSENGER_DIRECTIONS_REQUESTS) {
			return false;
		} else {
			currentPassengerDirectionsRequests++;
			return true;
		}
	}

	/**
	 * Queue a {@link Runnable} to execute on the simulator thread at the start of the next tick.
	 * Used by HTTP servlets and the embedding mod to safely mutate simulator state without
	 * crossing threads.
	 */
	public void run(Runnable runnable) {
		queuedRuns.put(runnable);
	}

	/**
	 * Enqueue a client-to-server message; processed during the next tick.
	 */
	public void sendMessageC2S(QueueObject queueObject) {
		messageQueueC2S.put(queueObject);
	}

	/**
	 * Push a server-to-client message into the outgoing queue. The optional {@code consumer} is
	 * invoked on the simulator thread when the matching response payload of type
	 * {@code responseDataClass} arrives back from the client.
	 */
	public <T extends SerializedDataBase> void sendMessageS2C(String key, SerializedDataBase data, @Nullable Consumer<T> consumer, @Nullable Class<T> responseDataClass) {
		messageQueueS2C.put(new QueueObject(key, data, consumer == null ? null : responseData -> run(() -> consumer.accept(responseData)), responseDataClass));
	}

	/**
	 * Drain all pending S2C messages and feed each into {@code callback}.
	 */
	public void processMessagesS2C(Consumer<QueueObject> callback) {
		messageQueueS2C.process(callback);
	}

	/**
	 * Drop the {@link Client} record for {@code uuid} - the player has left this dimension or the
	 * server. The record's "already sent" bookkeeping describes the departed session, and a rejoining
	 * player starts from an empty client dataset, so keeping the record left every stationary
	 * vehicle/rail/passenger unsent (trains and rails stayed invisible until something moved).
	 *
	 * @param uuid the player to forget
	 * @return whether a record was actually removed
	 */
	public boolean removeClient(UUID uuid) {
		return clients.removeIf(client -> client.uuid.equals(uuid));
	}

	/**
	 * @return whether the entity {@code uuid} is currently riding {@code vehicleId}
	 */
	public boolean isRiding(UUID uuid, long vehicleId) {
		return ridingVehicleIds.getLong(uuid) == vehicleId;
	}

	/**
	 * Record that the entity {@code uuid} has boarded {@code vehicleId}.
	 */
	public void ride(UUID uuid, long vehicleId) {
		ridingVehicleIds.put(uuid, vehicleId);
	}

	/**
	 * Record that the entity {@code uuid} has dismounted whatever vehicle it was riding.
	 */
	public void stopRiding(UUID uuid) {
		ridingVehicleIds.removeLong(uuid);
	}

	/**
	 * @return whether the route is currently considered jammed for pathfinding purposes.
	 */
	public boolean isRouteJammed(long routeId) {
		return routeId != 0 && jammedRouteIds.contains(routeId);
	}

	/**
	 * Mark a route as jammed for the current tick so CSA/path searches avoid it.
	 */
	public void markRouteJammed(long routeId) {
		if (routeId != 0) {
			jammedRouteIds.add(routeId);
		}
	}

	/**
	 * @param uuid riding entity to look up
	 * @return the next platform of the vehicle being ridden by {@code uuid}, or {@code null} if
	 * the entity is not riding anything or its vehicle has no upcoming platform.
	 */
	@Nullable
	public Platform getNextPlatformOfRidingVehicle(UUID uuid) {
		final @Nullable Platform[] platform = {null};
		sidings.forEach(siding -> siding.iterateVehiclesAndRidingEntities((vehicleExtraData, vehicleRidingEntity) -> {
			if (vehicleRidingEntity.uuid.equals(uuid)) {
				final Platform checkPlatform = platformIdMap.get(vehicleExtraData.getNextPlatformId());
				if (checkPlatform != null) {
					platform[0] = checkPlatform;
				}
			}
		}));
		return platform[0];
	}

	/**
	 * Simulates the system in one-second intervals until the simulation is all caught up
	 *
	 * @return the number of ticks it took
	 */
	private int tickUntilCaughtUp() {
		int ticks = 0;
		while (true) {
			ticks++;
			final long totalDifference = System.currentTimeMillis() - getCurrentMillis();
			if (totalDifference > MILLIS_PER_SECOND) {
				tick(MILLIS_PER_SECOND);
			} else {
				tick(totalDifference);
				return ticks;
			}
		}
	}

	/**
	 * The main simulation tick loop
	 *
	 * @param millisElapsed the number of milliseconds since the last tick
	 */
	private void tick(long millisElapsed) {
		lastMillis = getCurrentMillis();
		setCurrentMillis(lastMillis + millisElapsed);
		currentPassengerDirectionsRequests = 0;

		try {
			vehiclePositions.forEach(vehiclePositionsForTransportMode -> {
				if (!vehiclePositionsForTransportMode.isEmpty()) {
					vehiclePositionsForTransportMode.removeFirst();
				}
				vehiclePositionsForTransportMode.add(new Object2ObjectAVLTreeMap<>());
			});

			rails.forEach(rail -> rail.tick1(this));
			rails.forEach(rail -> rail.tick2(millisElapsed));
			// MTR depot auto path-generation pipeline removed (auto rebuilt on Motion/tasks): nothing auto-dispatches.

			// Try setting a siding's default path data
			// If a siding doesn't have a rail associated with it, it should be removed from the data set
			if (sidings.removeIf(Siding::tick)) {
				sync();
			}

			jammedRouteIds.clear();
			// MTR depot path auto-generation removed (auto rebuilt on Motion/tasks): stock runs on
			// Motion legs, not depot-generated route legs.
			sidings.forEach(siding -> siding.simulateVehicles(millisElapsed, vehiclePositions.get(siding.getTransportModeOrdinal())));
			// C8 自动车钩: after the vehicle simulation (the surgery unregisters the trailing train, so
			// it must not run while a siding iterates its vehicles), let trains with automatic couplers
			// latch onto the rake they have drawn up to under a 调车授权.
			org.mtr.core.mmtr.MmtrAutoCoupler.tick(this);
			mmtrPeriodicTaskSources.forEach(source -> source.tick(getCurrentMillis(), this));
			mmtrEnsurePointDefaults();
			mmtrEnsureSignalColors();
			mmtrRefreshSignalAspectView();
			if (mmtrJobScheduler != null && mmtrAiJobStepsEnabled) {
				mmtrJobScheduler.tick(getCurrentMillis(), this);
			}
			clients.forEach(client -> client.sendUpdates(this));

			if (autoSave) {
				save(true);
				autoSave = false;
			}

			lifts.forEach(lift -> lift.tick(millisElapsed));
			landmarks.forEach(Landmark::tick);
			homes.forEach(Home::tick);

			// Process queued runs
			queuedRuns.process(Runnable::run);

			// Directions
			directionsFinder.tick();

			// Process messages
			messageQueueC2S.process(queueObject -> queueObject.runCallback(OperationProcessor.process(queueObject.key, queueObject.data, this)));

			// MMTR health watchdog (every ~5 seconds at 20 TPS)
			if (++watchdogTickCounter >= MMTR_WATCHDOG_INTERVAL_TICKS) {
				watchdogTickCounter = 0;
				watchdogHealthCheck();
			}
		} catch (Throwable e) {
			log.fatal("", e);
		}
	}

	/**
	 * MMTR health watchdog: recounts the live simulation state and logs a one-line summary.
	 * Called automatically every {@value #MMTR_WATCHDOG_INTERVAL_TICKS} ticks and callable on
	 * demand (e.g. from an external watchdog process or tests).
	 */
	public void watchdogHealthCheck() {
		final int[] vehicles = {0};
		final int[] riders = {0};
		final int[] drivers = {0};
		final int[] overrides = {0};
		final int[] protections = {0};
		sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			vehicles[0]++;
			if (vehicle.isMmtrOverrideActive()) {
				overrides[0]++;
			}
			if (vehicle.isMmtrProtectionFromSync()) {
				protections[0]++;
			}
			vehicle.vehicleExtraData.iterateRidingEntities(vehicleRidingEntity -> {
				if (vehicleRidingEntity.isOnVehicle()) {
					riders[0]++;
					if (vehicleRidingEntity.isDriver()) {
						drivers[0]++;
					}
				}
			});
		}));
		watchdogLastCheckAt = getCurrentMillis();
		watchdogVehicles = vehicles[0];
		watchdogRiders = riders[0];
		watchdogDrivers = drivers[0];
		watchdogMmtrOverrides = overrides[0];
		watchdogProtections = protections[0];
		watchdogJammedRoutes = jammedRouteIds.size();
		// 日志降噪 (notes/77): the counts are refreshed every 5 s for the ops UI and tests, but printing
		// them every 5 s made the heartbeat the last per-tick-ish noise in the real-machine log (three
		// simulators = 36 lines a minute). Print immediately whenever a counter is non-zero - that is
		// the state an operator must see - and otherwise at most once a minute.
		final boolean watchdogInteresting = watchdogRiders > 0 || watchdogDrivers > 0 || watchdogMmtrOverrides > 0 || watchdogProtections > 0 || watchdogJammedRoutes > 0;
		if (watchdogInteresting || getCurrentMillis() - watchdogLastLogAtMillis >= MMTR_WATCHDOG_LOG_INTERVAL_MILLIS) {
			watchdogLastLogAtMillis = getCurrentMillis();
			System.out.println("[MMTR-HLTH] t=" + getCurrentMillis()
				+ " vehicles=" + watchdogVehicles + " riders=" + watchdogRiders + " drivers=" + watchdogDrivers
				+ " mmtrOverrides=" + watchdogMmtrOverrides + " protections=" + watchdogProtections + " jammedRoutes=" + watchdogJammedRoutes);
		}
	}

	public long getWatchdogLastCheckAt() { return watchdogLastCheckAt; }
	public int getWatchdogVehicles() { return watchdogVehicles; }
	public int getWatchdogRiders() { return watchdogRiders; }
	public int getWatchdogDrivers() { return watchdogDrivers; }
	public int getWatchdogMmtrOverrides() { return watchdogMmtrOverrides; }
	public int getWatchdogProtections() { return watchdogProtections; }
	public int getWatchdogJammedRoutes() { return watchdogJammedRoutes; }

	private void save(boolean useReducedHash) {
		// Save all data
		final ObjectLongImmutablePair<Boolean> changedAndDuration = Utilities.measureDuration(() -> {
			final boolean changed1 = save(fileLoaderStations, useReducedHash);
			final boolean changed2 = save(fileLoaderPlatforms, useReducedHash);
			final boolean changed3 = save(fileLoaderSidings, useReducedHash);
			final boolean changed4 = save(fileLoaderRoutes, useReducedHash);
			final boolean changed5 = save(fileLoaderDepots, useReducedHash);
			final boolean changed6 = save(fileLoaderLifts, useReducedHash);
			final boolean changed7 = save(fileLoaderRails, useReducedHash);
			final boolean changed8 = save(fileLoaderHomes, useReducedHash);
			final boolean changed9 = save(fileLoaderLandmarks, useReducedHash);
			return changed1 || changed2 || changed3 || changed4 || changed5 || changed6 || changed7 || changed8 || changed9;
		});
		if (changedAndDuration.left() || !useReducedHash) {
			log.info("Save complete for {} in {} second(s)", dimension, (float) changedAndDuration.rightLong() / MILLIS_PER_SECOND);
		}

		// Save settings
		writeSettings.accept(new Settings(getCurrentMillis()));
		if (useReducedHash) {
			fileLoaderSettings.save(false);
		} else {
			save(fileLoaderSettings, false);
		}
	}

	private <T extends SerializedDataBaseWithId> boolean save(FileLoader<T> fileLoader, boolean useReducedHash) {
		final IntIntImmutablePair saveCounts = fileLoader.save(useReducedHash);
		final int changedCount = saveCounts.leftInt();
		if (changedCount > 0) {
			log.info("- Changed {}: {}", fileLoader.key, changedCount);
		}
		final int deletedCount = saveCounts.rightInt();
		if (deletedCount > 0) {
			log.info("- Deleted {}: {}", fileLoader.key, deletedCount);
		}
		return changedCount > 0 || deletedCount > 0;
	}

	private record FileLoaderHolder(
		FileLoader<Station> fileLoaderStations,
		FileLoader<Platform> fileLoaderPlatforms,
		FileLoader<Siding> fileLoaderSidings,
		FileLoader<Route> fileLoaderRoutes,
		FileLoader<Depot> fileLoaderDepots,
		FileLoader<Lift> fileLoaderLifts,
		FileLoader<Rail> fileLoaderRails,
		FileLoader<Home> fileLoaderHomes,
		FileLoader<Landmark> fileLoaderLandmarks
	) {
	}
}
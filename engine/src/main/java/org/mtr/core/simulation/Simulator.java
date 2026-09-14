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
	public final org.mtr.core.mmtr.point.MmtrPointAuthority mmtrPointAuthority = new org.mtr.core.mmtr.point.MmtrPointAuthority(this::getCurrentMillis).withTurnoutLookup(this::mmtrTurnout);
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
		/*
		 * 开机把两个运维标记清掉：它们是**上一轮**的意图，这一轮刚刚开始，没有任何人要求重启或停机。
		 *
		 * 必须清掉而不是"留着也没事"：留着的重启标记会让启动器在这一轮结束后以为"又要重启"，
		 * 于是一轮接一轮地转下去；留着的停机标记会让它在这一轮结束后直接退出。
		 * 两处都清理是刻意的冗余（启动器也清一次）——因为"标记残留"的代价是服务端莫名重启或莫名不启动，
		 * 而这两种症状都极难从现象反推原因。
		 */
		mmtrClearRestartMarker();
		try {
			java.nio.file.Files.deleteIfExists(mmtrStopMarker());
		} catch (Exception e) {
			log.warn("Failed to clear MMTR stop marker for {}: {}", dimension, e.getMessage());
		}
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
			// **单开道岔不进这里**：它由 refreshMmtrTurnouts 按"节点级位置"统一写三行派生视图
			// （一处道岔一个位置，绝不能一行一行各补 0 —— 那正是三行互相矛盾的来源）。
			if (mmtrTurnout(point.nodeX, point.nodeY, point.nodeZ) != null) {
				continue;
			}
			if (!mmtrPointBranches.contains(point.nodeX, point.nodeY, point.nodeZ, point.viaRailHex)) {
				mmtrPointBranches.set(point.nodeX, point.nodeY, point.nodeZ, point.viaRailHex, 0);
				changed = true;
			}
		}
		// 道岔节点的行由位置派生，也要算作"活的"，否则会被下面的修剪误删
		changed |= refreshMmtrTurnoutRowsForPrune(currentForks);
		// Prune stale operator rows (forks that disappeared with a rail change), then persist once.
		changed |= mmtrPointBranches.branches.keySet().removeIf(key -> !currentForks.contains(key));
		if (changed) {
			persistMmtrPointBranches();
		}
	}

	/**
	 * 把道岔节点派生的三行登记进"活的行"集合，并返回是否有行发生变化。
	 *
	 * <p>没有这一步，道岔的行会被上面的修剪当成"消失的岔口"删掉，下一次走行就会以为这一侧没有续行。</p>
	 */
	private boolean refreshMmtrTurnoutRowsForPrune(java.util.Set<String> currentForks) {
		boolean changed = false;
		for (final org.mtr.core.mmtr.point.MmtrTurnout turnout : mmtrAllTurnouts()) {
			changed |= normalizeTurnoutRows(turnout);
			final int position = mmtrPointBranches.nodePosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
			for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
				if (turnout.continuationFrom(via, position) != null) {
					currentForks.add(turnout.nodeX + "," + turnout.nodeY + "," + turnout.nodeZ + "|" + via);
				}
			}
		}
		return changed;
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

	/**
	 * 轨图签名（只有轨）。{@code refreshMmtrTurnouts} 用它 —— 道岔是从**轨图**认出来的，
	 * 认完才可能有位置，所以这条签名里绝不能出现道岔（否则就是自己调自己）。
	 */
	private String mmtrRailGraphSignature() {
		final StringBuilder sig = new StringBuilder().append(rails.size()).append('|');
		final ObjectArrayList<String> hexes = new ObjectArrayList<>();
		for (final org.mtr.core.data.Rail rail : rails) {
			hexes.add(rail.getHexId());
		}
		hexes.sort(null);
		hexes.forEach(hex -> sig.append(hex).append(','));
		return sig.toString();
	}

	private String mmtrRailSetSignature() {
		final StringBuilder sig = new StringBuilder(mmtrRailGraphSignature());
		// 道岔位置也进签名：区间"走到哪里为止、哪一段撞在禁行侧"取决于它（信号显示视图因此要失效重建）。
		// 注意这不等于"灯守哪几条轨随位置变" —— 那是被用户否掉的规则，见 notes/115 §7。
		refreshMmtrTurnouts();
		for (final org.mtr.core.mmtr.point.MmtrTurnout turnout : mmtrTurnouts.values()) {
			sig.append('|').append(turnout.key()).append(':').append(mmtrPointBranches.nodePosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ));
		}
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

	// ---------------------------------------------------------------- 物理道岔（一处一个位置）

	private final java.util.HashMap<String, org.mtr.core.mmtr.point.MmtrTurnout> mmtrTurnouts = new java.util.HashMap<>();
	private String mmtrTurnoutSignature = "";

	/**
	 * 某节点上的**物理道岔**（一处道岔一个位置，两个互斥进路）；不是单开道岔时返回 null。
	 *
	 * <p>缓存按"轨集合签名"失效：世界改画了会自动重认。</p>
	 */
	public org.mtr.core.mmtr.point.MmtrTurnout mmtrTurnout(long x, long y, long z) {
		refreshMmtrTurnouts();
		return mmtrTurnouts.get(x + "," + y + "," + z);
	}

	public ObjectArrayList<org.mtr.core.mmtr.point.MmtrTurnout> mmtrAllTurnouts() {
		refreshMmtrTurnouts();
		final ObjectArrayList<org.mtr.core.mmtr.point.MmtrTurnout> out = new ObjectArrayList<>(mmtrTurnouts.values());
		out.sort((a, b) -> a.key().compareTo(b.key()));
		return out;
	}

	/** 道岔位置（0 = 正线贯通 / 1 = 岔股开放）。 */
	public int mmtrTurnoutPosition(long x, long y, long z) {
		refreshMmtrTurnouts();
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = mmtrTurnouts.get(x + "," + y + "," + z);
		if (turnout == null) {
			return mmtrPointBranches.nodePosition(x, y, z);
		}
		// T1: 有物理持有者时，位置由它决定 —— "行视图折进位置"这条老路（最后写入者为准）不得把
		// 正在持有这道岔的列车脚下的位置改掉。
		final int physicalHolderPosition = mmtrPointAuthority.physicalPosition(x, y, z);
		if (physicalHolderPosition != org.mtr.core.mmtr.point.MmtrPointAuthority.NO_PHYSICAL_HOLDER) {
			return physicalHolderPosition;
		}
		/*
		 * **行视图折进位置**（最后写入者为准）：老调用方（网页某一行的腿号、旧测试、手工改 mmtr-points.json）
		 * 直接写"某进向的第几条腿"时，这里把能翻译成进路的那个腿采纳为节点位置。
		 *
		 * <p>这样"一个位置 + 三行派生"不会因为写入路径不同而分裂：无论从哪一头写，最终都落到同一个位置。</p>
		 */
		final int current = mmtrPointBranches.nodePosition(x, y, z);
		for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
			if (!mmtrPointBranches.contains(x, y, z, via)) {
				continue;
			}
			final int position = mmtrTurnoutPositionForLeg(x, y, z, via, mmtrPointBranches.get(x, y, z, via));
			if (position != Integer.MIN_VALUE && position != current) {
				mmtrPointBranches.setNode(x, y, z, position);
				normalizeTurnoutRows(turnout);
				persistMmtrPointBranches();
				return position;
			}
		}
		return current;
	}

	/**
	 * 联锁扳动道岔（单处、立即）：这个道岔上若有授权持有的腿，就把位置扳到它。
	 *
	 * <p>走行在岔前直接调用，所以进路一旦批下来，道岔在**同一步**就位（不必等一个 tick）。</p>
	 */
	public void mmtrSyncTurnoutPositionToGrant(org.mtr.core.mmtr.point.MmtrTurnout turnout) {
		final int current = mmtrPointBranches.nodePosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
		/*
		 * T1: **位置由持有者决定**。从前这里按 {stem, far, branch} 的数组顺序取第一个"有授权能翻译成位置"
		 * 的进向，于是"哪一列车赢"取决于数组下标 —— 两列车从不同进向要求互斥位置时，先出现在数组里的
		 * 那个说了算。现在物理层只有**一个**持有者，位置由它定；没有持有者才退回逐进向的老路
		 * （人工位/默认位，人工随时可以再扳）。
		 */
		final int physical = mmtrPointAuthority.physicalPosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
		if (physical != org.mtr.core.mmtr.point.MmtrPointAuthority.NO_PHYSICAL_HOLDER) {
			if (physical != current) {
				mmtrPointBranches.setNode(turnout.nodeX, turnout.nodeY, turnout.nodeZ, physical);
				normalizeTurnoutRows(turnout);
				persistMmtrPointBranches();
			}
			return;
		}
		for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
			final int granted = mmtrPointAuthority.grantedLeg(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via);
			if (granted < 0) {
				continue;
			}
			final int position = mmtrTurnoutPositionForLeg(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via, granted);
			if (position != Integer.MIN_VALUE && position != current) {
				mmtrPointBranches.setNode(turnout.nodeX, turnout.nodeY, turnout.nodeZ, position);
				normalizeTurnoutRows(turnout);
				persistMmtrPointBranches();
				return;
			}
		}
	}

	private void refreshMmtrTurnouts() {
		// 只用**轨图**签名：道岔本身是从轨图认出来的，把位置算进来就成了自己调自己（无限递归）。
		final String signature = mmtrRailGraphSignature();
		if (signature.equals(mmtrTurnoutSignature)) {
			return;
		}
		mmtrTurnoutSignature = signature;
		mmtrTurnouts.clear();
		positionsToRail.forEach((node, neighbours) -> {
			final org.mtr.core.mmtr.point.MmtrTurnout turnout = org.mtr.core.mmtr.point.MmtrTurnout.resolve(node, neighbours);
			if (turnout != null) {
				mmtrTurnouts.put(turnout.key(), turnout);
			}
		});
		boolean changed = false;
		for (final org.mtr.core.mmtr.point.MmtrTurnout turnout : mmtrTurnouts.values()) {
			if (!mmtrPointBranches.containsNode(turnout.nodeX, turnout.nodeY, turnout.nodeZ)) {
				// 老存档没有节点级位置：从行视图反推（有人把任一进向扳到岔股 → 位置 1），
				// 这样升级不会把既有的人工设置抹掉；没有任何行则默认 0（正线贯通 = 安全侧）。
				mmtrPointBranches.setNode(turnout.nodeX, turnout.nodeY, turnout.nodeZ, inferPositionFromRows(turnout));
				changed = true;
			}
			changed |= normalizeTurnoutRows(turnout);
		}
		if (changed) {
			persistMmtrPointBranches();
		}
	}

	/** 从行视图反推节点位置：任何进向上"选的是岔股"即位置 1。 */
	private int inferPositionFromRows(org.mtr.core.mmtr.point.MmtrTurnout turnout) {
		for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
			final Integer branchLeg = turnout.branchLeg.get(via);
			if (branchLeg != null && mmtrPointBranches.contains(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via)
				&& mmtrPointBranches.get(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via) == branchLeg) {
				return org.mtr.core.mmtr.point.MmtrTurnout.REVERSE;
			}
		}
		return org.mtr.core.mmtr.point.MmtrTurnout.NORMAL;
	}

	/**
	 * 把节点位置翻译回"每个进向一行"的腿号，并顺手把**禁止通行**那一行写成 -1。
	 *
	 * <p>这是"一个位置、三行派生"的唯一写入口：位置是权威，行视图只为了让既有调用方
	 * （{@code MmtrForkElection}、诊断、网页）看到一致的事实。返回值 = 是否有变化。</p>
	 */
	private boolean normalizeTurnoutRows(org.mtr.core.mmtr.point.MmtrTurnout turnout) {
		final int position = mmtrPointBranches.nodePosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
		boolean changed = false;
		for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
			final String allowed = turnout.continuationFrom(via, position);
			final int leg = allowed == null ? -1 : legIndexForRail(turnout, via, allowed);
			if (leg >= 0) {
				if (!mmtrPointBranches.contains(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via)
					|| mmtrPointBranches.get(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via) != leg) {
					mmtrPointBranches.set(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via, leg);
					changed = true;
				}
			} else if (mmtrPointBranches.contains(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via)) {
				// 禁止通行：行也要消失，否则"这一侧有续行"会骗到走行与显示层
				mmtrPointBranches.set(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via, -1);
				changed = true;
			}
		}
		return changed;
	}

	private static int legIndexForRail(org.mtr.core.mmtr.point.MmtrTurnout turnout, String viaRailHex, String railHex) {
		if (railHex.equals(turnout.stemRailHex)) {
			return turnout.stemLeg.getOrDefault(viaRailHex, -1);
		}
		if (railHex.equals(turnout.farRailHex)) {
			return turnout.farLeg.getOrDefault(viaRailHex, -1);
		}
		if (railHex.equals(turnout.branchRailHex)) {
			return turnout.branchLeg.getOrDefault(viaRailHex, -1);
		}
		return -1;
	}

	/**
	 * 设定道岔：入参是"某个进向上的第几条腿"（既有调用方：网页、指令、任务），
	 * 内部**翻译成节点位置**（一处道岔只有两个位置），并把三行派生视图一起刷新。
	 *
	 * @return 是否受理；{@code false} = 物理上不存在这个组合（例如"岔股 → 正线远端"这种交叉）
	 */
	public boolean mmtrSetTurnoutPosition(long x, long y, long z, int position) {
		refreshMmtrTurnouts();
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = mmtrTurnouts.get(x + "," + y + "," + z);
		if (turnout == null) {
			return false;
		}
		mmtrPointBranches.setNode(x, y, z, position == org.mtr.core.mmtr.point.MmtrTurnout.REVERSE
			? org.mtr.core.mmtr.point.MmtrTurnout.REVERSE : org.mtr.core.mmtr.point.MmtrTurnout.NORMAL);
		normalizeTurnoutRows(turnout);
		persistMmtrPointBranches();
		System.out.println("[MMTR-PT] turnout " + turnout.key() + " -> 位置 " + mmtrPointBranches.nodePosition(x, y, z)
			+ "（正线贯通 vs 岔股开放；禁行 = " + turnout.prohibitedRailHex(mmtrPointBranches.nodePosition(x, y, z)).substring(0, 8) + "…）");
		return true;
	}

	/**
	 * **联锁扳动道岔**：某条进路/调车授权持有这个道岔时，道岔位置跟着授权的腿走。
	 *
	 * <p>这是"道岔 × 信号"真正接起来的那一环：进路要岔股 → 道岔扳到 1（正线那一侧随之禁止通行）；
	 * 授权释放后位置留在原地（人工位/默认位，人工随时可以再扳）。每 tick 一次，只在真的变了才落盘。</p>
	 */
	public void mmtrSyncTurnoutPositionsToGrants() {
		refreshMmtrTurnouts();
		if (mmtrTurnouts.isEmpty()) {
			return;
		}
		boolean changed = false;
		for (final org.mtr.core.mmtr.point.MmtrTurnout turnout : mmtrTurnouts.values()) {
			final int current = mmtrPointBranches.nodePosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
			// T1: 物理持有者优先（理由同 mmtrSyncTurnoutPositionToGrant）。
			final int physical = mmtrPointAuthority.physicalPosition(turnout.nodeX, turnout.nodeY, turnout.nodeZ);
			if (physical != org.mtr.core.mmtr.point.MmtrPointAuthority.NO_PHYSICAL_HOLDER) {
				if (physical != current) {
					mmtrPointBranches.setNode(turnout.nodeX, turnout.nodeY, turnout.nodeZ, physical);
					normalizeTurnoutRows(turnout);
					changed = true;
				}
				continue;
			}
			for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
				final int granted = mmtrPointAuthority.grantedLeg(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via);
				if (granted < 0) {
					continue;
				}
				final int position = mmtrTurnoutPositionForLeg(turnout.nodeX, turnout.nodeY, turnout.nodeZ, via, granted);
				if (position != Integer.MIN_VALUE && position != current) {
					mmtrPointBranches.setNode(turnout.nodeX, turnout.nodeY, turnout.nodeZ, position);
					normalizeTurnoutRows(turnout);
					changed = true;
				}
			}
		}
		if (changed) {
			persistMmtrPointBranches();
		}
	}

	/** 把 (进向, 腿号) 翻译成节点位置；物理上不存在的组合返回 {@link Integer#MIN_VALUE}。 */
	public int mmtrTurnoutPositionForLeg(long x, long y, long z, String viaRailHex, int leg) {
		refreshMmtrTurnouts();
		final org.mtr.core.mmtr.point.MmtrTurnout turnout = mmtrTurnouts.get(x + "," + y + "," + z);
		if (turnout == null) {
			return Integer.MIN_VALUE;
		}
		// 只认物理事实（{@link MmtrTurnout#positionForLeg}）：**"从岔股回根部"就是"把岔股扳通"**。
		// 从前这里对它返回"当前位置"，于是车尾在岔股上的车请求开出时扳不动道岔、被禁行闸门挡在岔前，
		// 永远等不到（S5 队列测试实测）。
		return turnout.positionForLeg(viaRailHex, leg);
	}

	/** Set an operator turnout branch index (0..legs-1 in the ordered-leg model, legacy 0/1 on
	 * two-leg forks) and persist it. A negative branch removes the operator setting (halt at that
	 * fork, never auto). */
	public boolean mmtrSetPoint(long x, long y, long z, String viaRailHex, int branch) {
		refreshMmtrTurnouts();
		if (mmtrTurnouts.containsKey(x + "," + y + "," + z)) {
			// 单开道岔：入参是"某进向上的第几条腿"，翻译成**节点位置**（一处道岔只有两个位置）。
			if (branch < 0) {
				return mmtrSetTurnoutPosition(x, y, z, org.mtr.core.mmtr.point.MmtrTurnout.NORMAL);
			}
			final int position = mmtrTurnoutPositionForLeg(x, y, z, viaRailHex, branch);
			if (position == Integer.MIN_VALUE) {
				System.out.println("[MMTR-PT] 拒绝 " + x + "," + y + "," + z + " 从 " + shortHex(viaRailHex)
					+ " 的第 " + branch + " 条腿：这两条进路互斥，物理上不存在（会把列车带上尖轨）");
				return false;
			}
			return mmtrSetTurnoutPosition(x, y, z, position);
		}
		mmtrPointBranches.set(x, y, z, viaRailHex, branch);
		persistMmtrPointBranches();
		System.out.println("[MMTR-PT] set switch " + x + "," + y + "," + z + " via " + viaRailHex + " -> " + (branch < 0 ? "unset" : String.valueOf(branch)));
		return true;
	}

	private static String shortHex(String hex) {
		return hex.length() <= 8 ? hex : hex.substring(0, 8) + "…";
	}

	/** Persist the operator branch store to mmtr-points.json (batch clear before a mission arm). */
	public void persistMmtrPointBranches() {
		if (mmtrPointsPath != null) {
			org.mtr.core.mmtr.point.MmtrPointRegistry.saveBranches(mmtrPointsPath, mmtrPointBranches.branches, mmtrPointBranches.nodePositions);
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
	/**
	 * 显式改一盏灯的守轨列表（点选绑定），并落盘。
	 *
	 * <p>与 {@link #mmtrSignalOp} 分开：那个接口是"登记/改朝向/绑单根轨"，这里是"它守哪几根轨"，
	 * 两者写入的字段不同，混在一个 op 字符串里只会让语义越来越绕。</p>
	 *
	 * @param rails 完整的目标列表（整体替换）；空列表 = 清掉人工绑定，回到按几何推断
	 * @return 是否找到并改了这盏灯
	 */
	public boolean mmtrSignalBindRails(int x, int y, int z, java.util.List<String> rails) {
		final boolean changed = mmtrSignals.setBoundRails(x, y, z, rails);
		if (changed && mmtrSignalsPath != null) {
			org.mtr.core.mmtr.signal.MmtrSignalRegistry.save(mmtrSignalsPath, mmtrSignals.signals);
		}
		return changed;
	}

	/**
	 * 删掉一条信号灯登记并落盘（世界扫描发现"那一格已经没有灯了"时用）。
	 *
	 * <p>与 {@link #mmtrSignalOp} 的 remove 区别：那个是走指令通道的通用删除，这个专门给**扫描**
	 * 用，语义是"世界扫描确认它不在了"。分出来是因为调用方只有游戏端扫描一处，
	 * 而且它要的是"删了没有"这个布尔值来写扫描报告。</p>
	 */
	public boolean mmtrSignalRemove(int x, int y, int z) {
		final boolean changed = mmtrSignals.remove(x, y, z);
		if (changed && mmtrSignalsPath != null) {
			org.mtr.core.mmtr.signal.MmtrSignalRegistry.save(mmtrSignalsPath, mmtrSignals.signals);
		}
		return changed;
	}

	/**
	 * 节点的**朝向角**（游戏端扫描上报）：{@code BlockNode.getAngle(state)}，也就是 MTR 在放置节点时
	 * 由玩家朝向决定的那个值（{@code FACING} / {@code IS_22_5} / {@code IS_45} 三个方块属性）。
	 *
	 * <h3>为什么引擎需要它</h3>
	 * <p>引擎的拓扑里节点只有**坐标**（{@code positionsToRail} 的键），没有朝向。而"一盏灯守哪条腿"
	 * 在实测世界里**不能只用灯自己的朝向推出来**：同一个节点上、朝向相对的两盏灯，一盏守北、一盏守南，
	 * 六盏实测灯里四盏"与朝向同向"、两盏"与朝向反向" —— 缺的那个变量就是节点朝向
	 * （原版 MTR 的 {@code RenderSignalBase.getAspectState} 用的正是它）。</p>
	 *
	 * <p>键是节点坐标（{@code x,y,z}）；值为角度（度）。</p>
	 */
	public final java.util.HashMap<String, Float> mmtrNodeAngles = new java.util.HashMap<>();

	/**
	 * 记下一个节点的朝向角（游戏端扫描上行）。
	 *
	 * @return 是否是新值或值变了（调用方据此决定要不要落盘/重算）
	 */
	public boolean mmtrNodeAngleUpsert(long x, long y, long z, float angle) {
		final String key = x + "," + y + "," + z;
		final Float previous = mmtrNodeAngles.get(key);
		if (previous != null && Math.abs(previous - angle) < 1e-3) {
			return false;
		}
		mmtrNodeAngles.put(key, angle);
		return true;
	}

	/** 某个节点的朝向角；没上报过则返回 null。 */
	public @org.jspecify.annotations.Nullable Float mmtrNodeAngle(long x, long y, long z) {
		return mmtrNodeAngles.get(x + "," + y + "," + z);
	}

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
	 * 中控指令用：在**一条股道**上立刻生成一列车（{@code vehicle spawn} 的落点）。
	 *
	 * <p>走的是引擎自己已有的即时路径：{@link #instantDeployDepots} 就是把 depot 快进一整天来立刻生成车辆，
	 * 这里对单条股道做同一件事（按 1 秒切片推进 {@code Siding.simulateVehicles}）。所以命令返回时车已经
	 * 在世界上，调用方可以直接拿车辆 id 去核对 —— 不必等下一个 tick，也不需要重启。
	 *
	 * <p>顺序上先写编组模板与标记（{@code mmtrManualSpawn} 且 {@code mmtrSessionSpawned=false} 才会触发一次生成），
	 * 再推进；生成成功后 {@code Siding} 自己会把 session 标记置上，所以重复调用不会叠出第二列车。</p>
	 *
	 * @param siding 目标股道
	 * @param cars   编组（每个元素一辆车卡）
	 * @return 生成出来的车辆；股道上有在途车辆、放不下、或走不出站场时返回 null
	 */
	public org.mtr.core.data.Vehicle mmtrSpawnOnSiding(org.mtr.core.data.Siding siding, it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.data.VehicleCar> cars) {
		if (siding == null || cars == null || cars.isEmpty()) {
			return null;
		}
		if (org.mtr.core.data.Siding.getTotalVehicleLength(cars) > siding.getRailLength() + 1e-6) {
			return null;
		}
		siding.setVehicleCars(cars);
		siding.mmtrManualSpawn = true;
		siding.mmtrSessionSpawned = false;
		// 与 instantDeployDepots 同一手法：按 1 秒切片推进，直到股道走完它自己的生成周期
		for (int i = 0; i < MILLIS_PER_DAY; i += MILLIS_PER_SECOND) {
			siding.simulateVehicles(MILLIS_PER_SECOND, null);
		}
		// 取这条股道上"在场"的那辆车作为结果。
		// 取这条股道上"在场"的那辆车作为结果。
		// 引擎里没有全局车辆集合：车辆挂在**股道**上，所以枚举方式是 `sidings.forEach(s -> s.iterateVehicles(…))`
		// （`mmtrFindVehicle` 也是这么找的）。归属用 `vehicleExtraData.getSidingId()` 判断，
		// 因为 `Vehicle.siding` 是 private，而 sidingId 是公开且稳定的关联。
		final org.mtr.core.data.Vehicle[] found = {null};
		siding.iterateVehicles(vehicle -> {
			if (vehicle.vehicleExtraData.getSidingId() == siding.getId()) {
				found[0] = vehicle;
			}
		});
		return found[0];
	}

	/**
	 * 中控指令用：把一列车**直接放在指定的轨上**（{@code vehicle spawn --rail=<轨hex>} 的落点）。
	 *
	 * <h3>为什么需要"临时股道"</h3>
	 * <p>引擎里车辆挂在**股道**上：{@code Siding.simulateVehicles} 第一句就是"没有车辆段 ⇒ 清空返回"，
	 * 而车辆段归属是 {@code Data.mapAreasAndSavedRails} 按几何算出来的。所以"在任意一根轨上落车"只能
	 * 给那根轨**临时建一条股道**（一个临时车辆段 + 与轨等长的股道），从而复用引擎自己那条即刻生成路径。</p>
	 *
	 * <p>它是**工具产物**，不是世界里的东西：游戏端不知道这个车辆段，所以这列车只存在于引擎
	 * （网页地图、闭塞计算、占用树都看得到；游戏里看不到）。要一辆游戏里也存在的车，只能在游戏里放。</p>
	 *
	 * @param rail  目标轨（必须在轨图里）
	 * @param cars  编组
	 * @return 生成出来的车辆；轨太短放不下、或走不出站场时返回 null
	 */
	public org.mtr.core.data.@Nullable Vehicle mmtrSpawnOnRail(org.mtr.core.data.Rail rail, it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.data.VehicleCar> cars) {
		if (rail == null || cars == null || cars.isEmpty()) {
			return null;
		}
		final double railLength = rail.railMath.getLength();
		if (org.mtr.core.data.Siding.getTotalVehicleLength(cars) > railLength + 1e-6) {
			return null;
		}
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.data.Position> ends = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
		final org.mtr.core.data.Position[] ordered = rail.mmtrOrderedPositions();
		ends.add(ordered[0]);
		ends.add(ordered[1]);

		final org.mtr.core.data.Depot depot = new org.mtr.core.data.Depot(org.mtr.core.data.TransportMode.TRAIN, this);
		depot.setName("临时落车");
		// 范围要比包围盒大一点：将来真的 sync 一次时，这条股道的中点仍落在车辆段内，归属不会掉
		final long minX = Math.min(ordered[0].getX(), ordered[1].getX()) - 4;
		final long maxX = Math.max(ordered[0].getX(), ordered[1].getX()) + 4;
		final long minZ = Math.min(ordered[0].getZ(), ordered[1].getZ()) - 4;
		final long maxZ = Math.max(ordered[0].getZ(), ordered[1].getZ()) + 4;
		depot.setCorners(new org.mtr.core.data.Position(minX, ordered[0].getY() - 4, minZ), new org.mtr.core.data.Position(maxX, ordered[0].getY() + 4, maxZ));

		final org.mtr.core.data.Siding siding = new org.mtr.core.data.Siding(ends.get(0), ends.get(1), railLength, org.mtr.core.data.TransportMode.TRAIN, this);
		depot.adoptSiding(siding);
		depots.add(depot);
		sidings.add(siding);
		siding.tick(); // 解析它自己的站场轨（defaultPathData），没有它 simulateVehicles 不会生成
		final org.mtr.core.data.Vehicle vehicle = mmtrSpawnOnSiding(siding, cars);
		if (vehicle == null) {
			// 放不下 / 走不出站场：把临时设施撤掉，别留一个空壳在世界里
			sidings.remove(siding);
			depots.remove(depot);
		}
		return vehicle;
	}

	/** 中控指令用：某条股道上当前有几辆车（{@code query depots} 显示用）。 */
	public int countVehiclesOnSiding(long sidingId) {
		final int[] count = {0};
		sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			if (vehicle.vehicleExtraData.getSidingId() == sidingId) {
				count[0]++;
			}
		}));
		return count[0];
	}

	/** 中控指令用：某条股道上那辆车的编组（车上实际挂了几节什么车），没有车时返回空表。 */
	public java.util.List<String> mmtrCarsOnSiding(long sidingId) {
		final java.util.ArrayList<String> out = new java.util.ArrayList<>();
		sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			if (vehicle.vehicleExtraData.getSidingId() == sidingId) {
				for (final org.mtr.core.data.VehicleCar car : vehicle.vehicleExtraData.immutableVehicleCars) {
					out.add(car.getVehicleId());
				}
			}
		}));
		return out;
	}

	/** 中控指令入口：执行一条名词打头的指令，返回可核对的结果。 */
	public org.mtr.core.mmtr.command.MmtrCommandDispatcher.Result mmtrExecuteCommand(String command) {
		return org.mtr.core.mmtr.command.MmtrCommandDispatcher.execute(this, command);
	}

	/**
	 * 中控指令用：从磁盘重读列车表（{@code manifest reload}）。
	 *
	 * <p>存在的理由很实际：列车表原先只在启动时读一次，所以"改了文件"必须重启才生效。
	 * 有了这个方法，改文件之后一条指令就能生效。</p>
	 */
	public boolean mmtrReloadRollingStockManifest() {
		try {
			if (mmtrManifestPath == null || !java.nio.file.Files.exists(mmtrManifestPath)) {
				return false;
			}
			final org.mtr.core.mmtr.manifest.MmtrRollingStockManifest reloaded = org.mtr.core.mmtr.manifest.MmtrRollingStockManifest.fromFile(mmtrManifestPath);
			mmtrRollingStock = reloaded;
			System.out.println("[MMTR-MFST] manifest reloaded on demand (" + reloaded.depots.size() + " depot(s), " + reloaded.sidingEntryCount() + " siding(s))");
			return true;
		} catch (Exception e) {
			System.out.println("[MMTR-MFST] manifest reload failed: " + e.getMessage());
			return false;
		}
	}

	/**
	 * 中控指令用：把一条股道写进列车表（热改 + 立刻生成 + 落盘）。
	 *
	 * <p>三条动作缺一不可，否则会出现"配了但车没出来"或"重启后又没了"这类问题：
	 * 先写内存里的表（当前进程立刻可用），再按它生成（拿到车辆 id 可核对），最后落盘（下次启动还在）。</p>
	 *
	 * @param carIds 编组里的车型；为空时沿用该股道已有的模板
	 * @return 生成是否成功
	 */
	public boolean mmtrManifestAddSiding(long depotId, long sidingId, java.util.List<String> carIds) {
		final org.mtr.core.data.Siding siding = org.mtr.core.mmtr.command.MmtrCommandLookup.findSiding(this, sidingId);
		if (siding == null) {
			return false;
		}
		final java.util.List<String> cars = new java.util.ArrayList<>();
		if (carIds == null || carIds.isEmpty()) {
			for (final org.mtr.core.data.VehicleCar car : siding.getVehicleCars()) {
				cars.add(car.getVehicleId());
			}
		} else {
			cars.addAll(carIds);
		}
		if (cars.isEmpty()) {
			return false;
		}
		final String depotName = siding.area == null ? "" : siding.area.getName();
		mmtrRollingStock.putSiding(depotId, depotName, sidingId, siding.getName(), cars, 16);
		persistMmtrRollingStockManifest();
		// 立刻生成：把同一条指令交给统一执行器，于是"热改"与"启动播种"行为必然一致
		final org.mtr.core.mmtr.command.MmtrCommandDispatcher.Result spawnResult = mmtrExecuteCommand(
			"vehicle spawn " + String.join(" ", cars) + " --depot=" + depotId + " --siding=" + sidingId);
		return spawnResult.ok;
	}

	/** 中控指令用：从列车表删条目并落盘。 */
	public boolean mmtrManifestRemove(long depotId, long sidingId) {
		final boolean removed = mmtrRollingStock.remove(depotId, sidingId);
		if (removed) {
			persistMmtrRollingStockManifest();
		}
		return removed;
	}

	/** 把当前列车表写回磁盘（热改之后调用，保证下次启动仍是这份配置）。 */
	public void persistMmtrRollingStockManifest() {
		if (mmtrManifestPath == null) {
			return;
		}
		try {
			mmtrRollingStock.save(mmtrManifestPath);
		} catch (Exception e) {
			System.out.println("[MMTR-MFST] manifest save failed: " + e.getMessage());
		}
	}

	// ---------------------------------------------------------------- 服务端运维（一键重启）

	/**
	 * 重启标记文件的位置（与 {@code scripts/dev-server.ps1} 约定的一致）。
	 *
	 * <p>放在 {@code game/fabric/run/}（启动器的工作目录）：启动器只认这个位置，所以这里必须算准。
	 * 不能靠"往上数几级"——存档路径是 {@code <run>/world/mtr/minecraft/<dimension>/mmtr-rolling-stock.json}，
	 * 层级一旦变（维度名、存档布局）就会算错。这里改成**按目录名找**：从存档路径往上走，
	 * 第一个名为 {@code run} 的目录就是它。</p>
	 */
	private java.nio.file.Path mmtrRestartMarker() {
		java.nio.file.Path current = mmtrManifestPath == null ? null : mmtrManifestPath.toAbsolutePath().getParent();
		while (current != null) {
			final java.nio.file.Path name = current.getFileName();
			if (name != null && name.toString().equals("run")) {
				return current.resolve("mmtr-restart.request");
			}
			current = current.getParent();
		}
		// 找不到 run 目录（例如测试环境用临时路径）：退回到进程工作目录
		return java.nio.file.Paths.get("mmtr-restart.request").toAbsolutePath();
	}

	/** 重启标记的路径（给指令回复显示用）。 */
	public String mmtrRestartMarkerPath() {
		return mmtrRestartMarker().toAbsolutePath().toString();
	}

	/** 请求重启：写标记文件 + 请求优雅停机；启动器看到标记会再拉起来。 */
	public void mmtrRequestRestart(int delaySeconds) {
		try {
			final java.nio.file.Path marker = mmtrRestartMarker();
			if (marker.getParent() != null) {
				java.nio.file.Files.createDirectories(marker.getParent());
			}
			java.nio.file.Files.writeString(marker, "restart requested at " + java.time.Instant.now() + System.lineSeparator()
				+ "服务端会在退出后由启动器（scripts/dev-server.ps1）重新拉起。" + System.lineSeparator());
			System.out.println("[MMTR-SRV] restart marker written: " + marker.toAbsolutePath());
		} catch (Exception e) {
			System.out.println("[MMTR-SRV] failed to write restart marker: " + e.getMessage());
		}
		mmtrRequestShutdown(Math.max(1, delaySeconds));
	}

	/** 请求优雅停机（不写重启标记）。 */
	public void mmtrRequestShutdown(int delaySeconds) {
		mmtrShutdownAtMillis = getCurrentMillis() + Math.max(1, delaySeconds) * 1000L;
		System.out.println("[MMTR-SRV] shutdown requested, will stop in " + delaySeconds + "s");
	}

	/** 清掉可能残留的重启标记（"只停机"时必须做，否则启动器会误判成重启）。 */
	public void mmtrClearRestartMarker() {
		try {
			java.nio.file.Files.deleteIfExists(mmtrRestartMarker());
		} catch (Exception e) {
			System.out.println("[MMTR-SRV] failed to clear restart marker: " + e.getMessage());
		}
	}

	/**
	 * 停机标记文件（与 {@code scripts/dev-server.ps1} 约定）——"是你要我停的"。
	 *
	 * <h3>为什么停机也需要一个标记</h3>
	 * <p>启动器在一轮结束后要判断"该不该再拉一次"。原来只看两件事：有没有重启标记、
	 * 8888 有没有应答。可是 {@code server stop} 之后这两件事都指向"没起来"——8888 当然不应答，
	 * 也没有重启标记——于是启动器把**用户主动停机**当成了"构建失败"去重试，白起了一轮。
	 * 实测就是这么被触发的（日志里"第 1 轮结束：8888 应答=False → 第 1 次重试"）。</p>
	 *
	 * <p>所以意图要在两边都说清楚：重启写重启标记，停机写停机标记。文件是两边都能读、
	 * 也看得见的东西，比"猜日志"可靠。</p>
	 */
	private java.nio.file.Path mmtrStopMarker() {
		return mmtrRestartMarker().resolveSibling("mmtr-stop.request");
	}

	/** 请求停机：写停机标记 + 请求优雅停机；启动器看到它就知道不要再拉起来，也不会当成失败去重试。 */
	public void mmtrRequestStopWithMarker(int delaySeconds) {
		try {
			final java.nio.file.Path marker = mmtrStopMarker();
			if (marker.getParent() != null) {
				java.nio.file.Files.createDirectories(marker.getParent());
			}
			java.nio.file.Files.writeString(marker, "stop requested at " + java.time.Instant.now() + System.lineSeparator()
				+ "这是**主动停机**：启动器不要再拉起来，也不要当成启动失败去重试。" + System.lineSeparator());
			System.out.println("[MMTR-SRV] stop marker written: " + marker.toAbsolutePath());
		} catch (Exception e) {
			System.out.println("[MMTR-SRV] failed to write stop marker: " + e.getMessage());
		}
		mmtrRequestShutdown(Math.max(1, delaySeconds));
	}

	/**
	 * 是否已经到"该停机"的时刻（游戏端每 tick 调用）。
	 *
	 * <p>放在这里而不是直接 {@code System.exit}：停机必须是**优雅**的 —— 存档、断开连接、
	 * 通知客户端都走服务端自己的流程，所以由游戏端拿到这个信号后调用 {@code MinecraftServer.stop(false)}。
	 */
	public boolean mmtrShutdownDue() {
		return mmtrShutdownAtMillis > 0 && getCurrentMillis() >= mmtrShutdownAtMillis;
	}

	/** 停机时刻（0 = 没有停机请求）。 */
	private long mmtrShutdownAtMillis;


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
			mmtrSyncTurnoutPositionsToGrants();
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


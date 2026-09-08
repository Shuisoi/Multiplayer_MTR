package org.mtr.core.data;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.*;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.Nullable;
import org.mtr.core.directions.DirectionsFinder;
import org.mtr.core.generated.data.SidingSchema;
import org.mtr.core.oba.*;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.mmtr.consist.MmtrCabState;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.ConditionalList;
import org.mtr.core.tool.Utilities;
import org.mtr.legacy.data.DataFixer;

import java.util.Collections;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * A single platform / parking spot inside a {@link Depot} that owns its own physical track
 * (between the depot's main loop and the siding tail) and the {@link Vehicle} fleet currently
 * dispatched along it.
 *
 * <p>Sidings are the unit at which the simulator generates and stores paths, dispatches vehicles
 * for each timetabled departure, and reports OBA arrivals / trip details.</p>
 */
@Log4j2
public final class Siding extends SidingSchema implements Utilities {

	@Nullable
	private PathData defaultPathData;
	private double timeOffsetForRepeating;

	/**
	 * Vehicles on this siding; also doubles as an ID map
	 */
	private final Long2ObjectOpenHashMap<Vehicle> vehicleIdMap = new Long2ObjectOpenHashMap<>();
	private final ObjectImmutableList<ReaderBase> vehicleReaders;
	/**
	 * Trips this siding will serve
	 */
	private final ObjectArrayList<Trip> trips = new ObjectArrayList<>();
	/**
	 * Mapping of platform ID to stop times
	 */
	private final Long2ObjectAVLTreeMap<ObjectArraySet<Trip.StopTime>> platformTripStopTimes = new Long2ObjectAVLTreeMap<>();
	/**
	 * Absolute departures (millis after 12am UTC) for this siding only
	 */
	private final LongArrayList departures = new LongArrayList();
	private final LongArrayList tempReturnTimes = new LongArrayList();
	/**
	 * Current path speed changes, used for calculating duration along path
	 */
	private final ObjectArrayList<TimeSegment> timeSegments = new ObjectArrayList<>();
	/**
	 * Mapping of departure indices to real time vehicle times
	 */
	private final Long2ObjectOpenHashMap<LongObjectImmutablePair<Vehicle>> vehicleTimesAlongRoute = new Long2ObjectOpenHashMap<>();

	public static final double ACCELERATION_DEFAULT = 1D / 250000;
	public static final double MAX_ACCELERATION = 1D / 50000;
	public static final double MIN_ACCELERATION = 1D / 2500000;
	private static final Random RANDOM = new Random();
	private static final String KEY_VEHICLES = "vehicles";
	/** Rate limit for the per-vehicle simulation failure report. */
	private static long mmtrLastSimulateErrorMillis;
	public Siding(Position position1, Position position2, double railLength, TransportMode transportMode, Data data) {
		super(getRailLength(railLength), position1, position2, transportMode, data);
		vehicleReaders = ObjectImmutableList.of();
	}

	public Siding(ReaderBase readerBase, Data data) {
		super(DataFixer.convertSiding(readerBase), data);
		vehicleReaders = savePathDataReaderBase(readerBase, KEY_VEHICLES);
		updateData(readerBase);
		DataFixer.unpackSidingVehicleCars(readerBase, transportMode, railLength, vehicleCars);
		DataFixer.unpackSidingMaxVehicles(readerBase, value -> maxVehicles = value);
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		super.updateData(readerBase);
		final ObjectIterator<Long2ObjectMap.Entry<Vehicle>> iterator = vehicleIdMap.long2ObjectEntrySet().fastIterator();
		while (iterator.hasNext()) {
			final Long2ObjectMap.Entry<Vehicle> entry = iterator.next();
			if (!entry.getValue().getIsOnRoute()) {
				iterator.remove();
			}
		}
	}

	@Override
	public void serializeFullData(WriterBase writerBase) {
		super.serializeFullData(writerBase);
		writerBase.writeDataset(vehicleIdMap.values(), KEY_VEHICLES);
	}

	/**
	 * Should only be called during initialisation and when a siding is created (by building a siding rail)
	 */
	public void init() {
		tick();
		generatePathDistancesAndTimeSegments();
		if (area != null && defaultPathData != null) {
			vehicleReaders.forEach(readerBase -> {
				final Vehicle vehicle = new Vehicle(VehicleExtraData.createWithLegs(area.getId(), id, railLength, vehicleCars, ObjectArrayList.wrap(new PathData[]{defaultPathData}), acceleration, deceleration, (getIsManual() || mmtrManualSpawn), maxManualSpeed, manualToAutomaticTime), this, readerBase, data);
				vehicleIdMap.put(vehicle.getId(), vehicle);
			});
		}
		// Automatically clamp acceleration and deceleration values
		setAcceleration(acceleration);
		setDeceleration(deceleration);
	}

	public double getRailLength() {
		return railLength;
	}

	public ObjectArrayList<VehicleCar> getVehicleCars() {
		return vehicleCars;
	}

	public boolean getIsManual() {
		return maxVehicles < 0;
	}

	public boolean getIsUnlimited() {
		return maxVehicles == 0;
	}

	public long getMaxVehicles() {
		return getIsManual() ? 1 : maxVehicles;
	}

	public int getDelayedVehicleSpeedIncreasePercentage() {
		return (int) delayedVehicleSpeedIncreasePercentage;
	}

	public int getDelayedVehicleReduceDwellTimePercentage() {
		return (int) delayedVehicleReduceDwellTimePercentage;
	}

	public int getTransportModeOrdinal() {
		return transportMode.ordinal();
	}

	public boolean getEarlyVehicleIncreaseDwellTime() {
		return earlyVehicleIncreaseDwellTime;
	}

	public double getMaxManualSpeed() {
		return maxManualSpeed;
	}

	public int getManualToAutomaticTime() {
		return (int) manualToAutomaticTime;
	}

	public double getAcceleration() {
		return acceleration;
	}

	public double getDeceleration() {
		return deceleration;
	}

	public void setVehicleCars(ObjectArrayList<VehicleCar> newVehicleCars) {
		vehicleCars.clear();
		double tempVehicleLength = 0;
		for (int i = 0; i < newVehicleCars.size(); i++) {
			final VehicleCar vehicleCar = newVehicleCars.get(i);
			if (tempVehicleLength + vehicleCar.getTotalLength(i == 0, true) > railLength) {
				break;
			}
			vehicleCars.add(vehicleCar);
			tempVehicleLength += vehicleCar.getTotalLength(i == 0, false);
			if (vehicleCars.size() >= transportMode.maxLength) {
				break;
			}
		}
	}

	public void setIsManual(boolean isManual) {
		maxVehicles = transportMode.continuousMovement ? 0 : (isManual ? -1 : 1);
	}

	public void setUnlimitedVehicles(boolean unlimitedVehicles) {
		maxVehicles = transportMode.continuousMovement ? 0 : (unlimitedVehicles ? 0 : 1);
	}

	public void setMaxVehicles(int newMaxVehicles) {
		maxVehicles = transportMode.continuousMovement ? 0 : Math.max(1, newMaxVehicles);
	}

	public void setDelayedVehicleSpeedIncreasePercentage(int delayedVehicleSpeedIncreasePercentage) {
		this.delayedVehicleSpeedIncreasePercentage = Utilities.clampSafe(delayedVehicleSpeedIncreasePercentage, 0, 100);
	}

	public void setDelayedVehicleReduceDwellTimePercentage(int delayedVehicleReduceDwellTimePercentage) {
		this.delayedVehicleReduceDwellTimePercentage = Utilities.clampSafe(delayedVehicleReduceDwellTimePercentage, 0, 100);
	}

	public void setEarlyVehicleIncreaseDwellTime(boolean earlyVehicleIncreaseDwellTime) {
		this.earlyVehicleIncreaseDwellTime = earlyVehicleIncreaseDwellTime;
	}

	public void setMaxManualSpeed(double maxManualSpeed) {
		this.maxManualSpeed = maxManualSpeed;
	}

	public void setManualToAutomaticTime(int manualToAutomaticTime) {
		this.manualToAutomaticTime = manualToAutomaticTime;
	}

	public void setAcceleration(double newAcceleration) {
		acceleration = transportMode.continuousMovement ? MAX_ACCELERATION : roundAcceleration(newAcceleration);
	}

	public void setDeceleration(double newDeceleration) {
		deceleration = transportMode.continuousMovement ? MAX_ACCELERATION : roundAcceleration(newDeceleration);
	}

	public void clearVehicles() {
		vehicleIdMap.clear();
	}


	public boolean tick() {
		// MTR depot-route auto-generation removed (auto rebuilt on Motion/tasks). The siding only
		// resolves its own yard rail (defaultPathData) for parked stock.
		if (defaultPathData == null) {
			final Rail rail = Data.tryGet(data.positionsToRail, position1, position2);
			if (rail == null) {
				// No corresponding rail: this siding is invalid and should be removed.
				return true;
			}
			defaultPathData = new PathData(rail, id, 1, -1, 0, rail.railMath.getLength(), position1, rail.getStartAngle(position1), position2, rail.getStartAngle(position2));
		}
		return false;
	}


	public void initVehiclePositions(Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> vehiclePositions) {
		vehicleIdMap.values().forEach(vehicle -> vehicle.initVehiclePositions(vehiclePositions));
	}

	/**
	 * Simulate this siding's vehicles for one tick and refresh runtime vehicle ID caches used by passenger boarding and arrivals payload enrichment.
	 */
	public void simulateVehicles(long millisElapsed, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions) {
		vehicleTimesAlongRoute.clear();

		if (area == null) {
			vehicleIdMap.clear();
			return;
		}

		int trainsAtDepot = 0;
		boolean spawnTrain = true;

		final ObjectArraySet<Vehicle> trainsToRemove = new ObjectArraySet<>();
		for (final Vehicle vehicle : vehicleIdMap.values()) {
			try {
				vehicle.simulate(millisElapsed, vehiclePositions, vehicleTimesAlongRoute);
			} catch (Exception e) {
				// One broken vehicle must not take the whole simulation (and with it every client's
				// vehicle and rail data) down with it: log at most once a second and keep going.
				final long now = System.currentTimeMillis();
				if (now - mmtrLastSimulateErrorMillis > 1000) {
					mmtrLastSimulateErrorMillis = now;
					System.out.println("[MMTR-SIM] vehicle " + vehicle.getId() + " failed to simulate: " + e);
					e.printStackTrace(System.out);
				}
				continue;
			}

			if (vehicle.closeToDepot()) {
				spawnTrain = false;
			}

			if (vehicle.getIsOnRoute()) {
				// MTR timetable auto-dispatch removed (auto rebuilt on Motion/tasks): a train on the
				// network was put there by a driver / task / manual spawn; nothing auto-prunes or
				// auto-re-cycles it by a timetable departure index.
			} else {
				trainsAtDepot++;
				final boolean allowDoublePark = mmtrFormationWindow && trainsAtDepot <= 2;
				if (trainsAtDepot > 1 && !allowDoublePark) {
					trainsToRemove.add(vehicle);
				}
			}
		}

		// Keep at most one parked train from the template (manual / MMTR stock). MTR no longer
		// auto-dispatches a service train onto a timetable route. MMTR-manual sidings (rolling-stock
		// manifest) spawn their stock in LIVE Motion-Core mode (segment+offset walker from the yard,
		// free-run/forks live) instead of a legacy baked single-yard-rail path - so a driver can
		// actually leave the yard.
		if (defaultPathData != null && !vehicleCars.isEmpty() && spawnTrain && (getIsUnlimited() || vehicleIdMap.size() < getMaxVehicles())
			&& (!mmtrManualSpawn || !mmtrSessionSpawned)) {
			Vehicle vehicle = null;
			if (mmtrManualSpawn) {
				final BranchStore store = data instanceof final Simulator simulator ? simulator.mmtrPointBranches : null;
				// B7.2d: prefer the consist-body model (double-ended body + manned cab), fall back to
				// the legacy single-point walker when the body cannot be placed on this yard.
				final MmtrConsistWalker consistWalker = mmtrConsistWalkerFromYard(null, store, null);
				if (consistWalker != null) {
					vehicle = spawnMmtrConsistVehicle(consistWalker, MmtrCabState.Cab.CAB_A);
				}
				if (vehicle == null) {
					final MmtrMotionWalker walker = mmtrMotionWalkerFromYard(null, store, null);
					if (walker != null) {
						vehicle = spawnMmtrMotionVehicle(walker);
					}
				}
				if (vehicle == null) {
					System.out.println("[MMTR-MFST] manual siding " + id + " could not stage a Motion-Core car (yard busy/unwalkable) - legacy fallback");
				}
			}
			if (vehicle == null) {
				vehicle = new Vehicle(VehicleExtraData.createWithLegs(area.getId(), id, railLength, vehicleCars, ObjectArrayList.wrap(new PathData[]{defaultPathData}), acceleration, deceleration, (getIsManual() || mmtrManualSpawn), maxManualSpeed, manualToAutomaticTime), this, transportMode, data);
				vehicleIdMap.put(vehicle.getId(), vehicle);
				if (mmtrManualSpawn) {
					mmtrSessionSpawned = true;
				}
			}
		}

		if (!trainsToRemove.isEmpty()) {
			trainsToRemove.forEach(vehicle -> vehicleIdMap.remove(vehicle.getId()));
		}
	}

	/**
	 * MMTR yard surgery: replace the single parked (not-on-route) vehicle standing on this siding
	 * with one rebuilt from {@code cars} - the foundation for a parked consist union (COUPLE) or a
	 * post-cut head (UNCOUPLE). Yard-only: returns {@code null} and changes nothing when the siding
	 * is busy (any vehicle on route), holds more than one parked vehicle, or the requested formation
	 * does not fit the siding rail / transport-mode car cap. The siding's car template is updated to
	 * the new formation so later spawning/booking sees the coupled consist.
	 */
	@Nullable
	public Vehicle rebuildParkedConsist(ObjectArrayList<VehicleCar> cars) {
		if (cars.isEmpty() || defaultPathData == null || area == null) {
			return null;
		}
		if (cars.size() > transportMode.maxLength || Siding.getTotalVehicleLength(cars) > railLength + 1e-6) {
			return null;
		}
		Vehicle parked = null;
		for (final Vehicle vehicle : vehicleIdMap.values()) {
			if (vehicle.getIsOnRoute()) {
				return null; // yard must be idle for formation surgery
			}
			if (parked != null) {
				return null; // more than one parked vehicle is not a rebuildable yard state
			}
			parked = vehicle;
		}
		if (parked == null) {
			return null;
		}
		vehicleIdMap.remove(parked.getId());
		setVehicleCars(cars); // keep the template consistent with the rebuilt formation
		final Vehicle rebuilt = new Vehicle(VehicleExtraData.createWithLegs(area.getId(), id, railLength, vehicleCars, ObjectArrayList.wrap(new PathData[]{defaultPathData}), acceleration, deceleration, (getIsManual() || mmtrManualSpawn), maxManualSpeed, manualToAutomaticTime), this, transportMode, data);
		vehicleIdMap.put(rebuilt.getId(), rebuilt);
		return rebuilt;
	}

	/**
	 * MMTR Motion-Core dispatch seam (T3): spawn a parked manual-allowed vehicle on this siding whose
	 * running path is supplied directly as Motion Core legs ({@code MmtrMotionWalker.buildLegs()} - a
	 * route Motion Core chose segment by segment by turnout authority) instead of the pre-baked
	 * per-siding path caches. The yard must be idle (no vehicle on route, at most one parked) so the
	 * Motion legs do not fight a legacy generated route; the parked vehicle is replaced by the new one.
	 * Returns {@code null} when the yard is busy, the formation does not fit the rail, or {@code legs}
	 * is unusable. This is the additive production seam the task/operator dispatch layer calls to put a
	 * real vehicle on Motion-Core-chosen rails; legacy auto trains are untouched.
	 */
	@Nullable
	public Vehicle spawnMmtrManualWithLegs(ObjectArrayList<PathData> legs) {
		if (legs == null || legs.isEmpty() || vehicleCars.isEmpty()) {
			return null;
		}
		if (Siding.getTotalVehicleLength(vehicleCars) > railLength + 1e-6) {
			return null;
		}
		Vehicle parked = null;
		for (final Vehicle vehicle : vehicleIdMap.values()) {
			if (vehicle.getIsOnRoute()) {
				return null; // yard must be idle for Motion-Core dispatch
			}
			if (parked != null) {
				return null; // more than one parked vehicle is not dispatchable
			}
			parked = vehicle;
		}
		if (parked != null) {
			vehicleIdMap.remove(parked.getId());
		}
		final Vehicle vehicle = new Vehicle(VehicleExtraData.createWithLegs(area == null ? 0 : area.getId(), id, railLength, vehicleCars, legs,
			acceleration, deceleration, true, maxManualSpeed, manualToAutomaticTime), this, transportMode, data);
		vehicleIdMap.put(vehicle.getId(), vehicle);
		return vehicle;
	}

	/**
	 * MMTR (L3, slice 2): build the live Motion-Core walker for a yard-parked consist of this siding:
	 * the yard rail (defaultPathData) is walked from its rear end (the rail end whose node connects to
	 * the fewest other rails — a depot tail/buffer; override with {@code rearEnd} when the topology is
	 * ambiguous) and the head starts at the parked position inside the rail so the body fits the yard.
	 * A driver can then depart the parked vehicle with the existing cab control and it runs the whole
	 * way by Motion Core (every fork elected live) — no pre-baked route at all. Returns {@code null}
	 * when the yard rail is unresolved or the formation does not fit the yard.
	 */
	@Nullable
	public MmtrMotionWalker mmtrMotionWalkerFromYard(@Nullable Position rearEnd, @Nullable BranchStore branches, @Nullable String targetRailHex) {
		if (defaultPathData == null || vehicleCars.isEmpty()) {
			return null;
		}
		final Rail rail = defaultPathData.getRail();
		if (rail == null) {
			return null;
		}
		final double trainLength = Siding.getTotalVehicleLength(vehicleCars);
		final double railLengthM = rail.railMath.getLength();
		if (trainLength > railLengthM + 1e-6) {
			return null;
		}
		final Position end1 = position1;
		final Position end2 = position2;
		final Position rear = rearEnd != null ? rearEnd : (countOtherRailsAt(end1, rail) <= countOtherRailsAt(end2, rail) ? end1 : end2);
		// Parked head offset: body fully inside the rail, rear clear of the buffer node, roughly centred.
		final double headOffset = Math.max(trainLength, Math.min((railLengthM + trainLength) / 2, railLengthM));
		return MmtrMotionWalker.startAtOffset(data, rail, rear, headOffset, branches == null && data instanceof Simulator ? ((Simulator) data).mmtrPointBranches : branches, targetRailHex);
	}

	private int countOtherRailsAt(Position node, Rail yardRail) {
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = data.positionsToRail.get(node);
		if (neighbors == null) {
			return 0;
		}
		int count = 0;
		for (final Object2ObjectOpenHashMap.Entry<Position, Rail> e : neighbors.object2ObjectEntrySet()) {
			if (e.getValue() != yardRail) {
				count++;
			}
		}
		return count;
	}

	/**
	 * B7.2d: the consist-body equivalent of {@link #mmtrMotionWalkerFromYard}. The parked consist is
	 * placed with its <strong>A end</strong> (car 0's outer end — the head, since MTR car 0 is the
	 * front car) at the parked head offset measured from the yard's rear node, and its body extends
	 * toward that rear node. The A-end cab's driver faces outward, so CAB_A departs the yard head-first
	 * with no reverse running; {@link #spawnMmtrConsistVehicle} hands the consist that system key.
	 *
	 * @return the walker, or {@code null} when the yard rail is unresolved or the formation does not fit
	 */
	@Nullable
	public MmtrConsistWalker mmtrConsistWalkerFromYard(@Nullable Position rearEnd, @Nullable BranchStore branches, @Nullable String targetRailHex) {
		if (defaultPathData == null || vehicleCars.isEmpty()) {
			return null;
		}
		final Rail rail = defaultPathData.getRail();
		if (rail == null) {
			return null;
		}
		final double trainLength = Siding.getTotalVehicleLength(vehicleCars);
		final double railLengthM = rail.railMath.getLength();
		if (trainLength > railLengthM + 1e-6) {
			return null;
		}
		final Position end1 = position1;
		final Position end2 = position2;
		final Position rear = rearEnd != null ? rearEnd : (countOtherRailsAt(end1, rail) <= countOtherRailsAt(end2, rail) ? end1 : end2);
		final Position front = rear.equals(end1) ? end2 : end1;
		final double headOffset = Math.max(trainLength, Math.min((railLengthM + trainLength) / 2, railLengthM));
		final double[] carLengthsM = new double[vehicleCars.size()];
		for (int i = 0; i < carLengthsM.length; i++) {
			carLengthsM[i] = vehicleCars.get(i).getTotalLength(i == 0, i == carLengthsM.length - 1);
		}
		final BranchStore store = branches == null && data instanceof final Simulator simulator ? simulator.mmtrPointBranches : branches;
		// The spine runs from the A end toward the rear node: measure the A end from the front node.
		return MmtrConsistWalker.place(data, store, rail, front, railLengthM - headOffset, carLengthsM, targetRailHex);
	}

	/**
	 * B7.2d: spawn the parked stock as a consist-body vehicle (the B-series replacement for
	 * {@link #spawnMmtrMotionVehicle}). Same idle-yard rules; the engine inserts the system key in
	 * {@code cab} so the consist can move (a real driver path replaces this in B7.6).
	 */
	@Nullable
	public Vehicle spawnMmtrConsistVehicle(MmtrConsistWalker walker, MmtrCabState.Cab cab) {
		if (walker == null || vehicleCars.isEmpty()) {
			return null;
		}
		if (Siding.getTotalVehicleLength(vehicleCars) > railLength + 1e-6) {
			return null;
		}
		Vehicle parked = null;
		for (final Vehicle vehicle : vehicleIdMap.values()) {
			if (vehicle.getIsOnRoute()) {
				return null; // yard must be idle for Motion-Core dispatch
			}
			if (parked != null) {
				return null; // more than one parked vehicle is not dispatchable
			}
			parked = vehicle;
		}
		if (parked != null) {
			vehicleIdMap.remove(parked.getId());
		}
		final Vehicle vehicle = new Vehicle(VehicleExtraData.createWithLegs(area == null ? 0 : area.getId(), id, railLength, vehicleCars, new ObjectArrayList<>(),
			acceleration, deceleration, true, maxManualSpeed, manualToAutomaticTime), this, transportMode, data);
		vehicle.engageMmtrConsistMotion(walker, cab);
		vehicleIdMap.put(vehicle.getId(), vehicle);
		mmtrManualSpawn = true;
		mmtrSessionSpawned = true;
		return vehicle;
	}

	/**
	 * MMTR (L3, slice 2): Motion-Core dispatch seam — spawns (replacing the single parked stock, yard
	 * must be idle) a manual-allowed vehicle that is ALREADY in live motion mode: parked at the yard
	 * position, walker seeded from {@link #mmtrMotionWalkerFromYard}, no route baked anywhere. The
	 * driver departs it with the existing cab control (ControlState); every fork after that is elected
	 * live by Motion Core from the current turnout state. The siding's session-spawn slot is taken so
	 * the template respawner does not seed a second parked train once this one has left.
	 */
	@Nullable
	public Vehicle spawnMmtrMotionVehicle(MmtrMotionWalker walker) {
		if (walker == null || vehicleCars.isEmpty()) {
			return null;
		}
		if (Siding.getTotalVehicleLength(vehicleCars) > railLength + 1e-6) {
			return null;
		}
		Vehicle parked = null;
		for (final Vehicle vehicle : vehicleIdMap.values()) {
			if (vehicle.getIsOnRoute()) {
				return null; // yard must be idle for Motion-Core dispatch
			}
			if (parked != null) {
				return null; // more than one parked vehicle is not dispatchable
			}
			parked = vehicle;
		}
		if (parked != null) {
			vehicleIdMap.remove(parked.getId());
		}
		final Vehicle vehicle = new Vehicle(VehicleExtraData.createWithLegs(area == null ? 0 : area.getId(), id, railLength, vehicleCars, new ObjectArrayList<>(),
			acceleration, deceleration, true, maxManualSpeed, manualToAutomaticTime), this, transportMode, data);
		vehicle.engageMmtrMotion(walker);
		vehicleIdMap.put(vehicle.getId(), vehicle);
		// Take the siding's spawn slot: the template respawner must not seed a second parked train once
		// this one departs (manual policy + session flag together gate that re-seed).
		mmtrManualSpawn = true;
		mmtrSessionSpawned = true;
		return vehicle;
	}

	/**
	 * MMTR yard reset: remove every parked (not-on-route) vehicle from this siding. Used before a
	 * daily respawn / when re-authoring web jobs so leftover stock never blocks a fresh spawn or a
	 * make-up (the engine keeps at most one parked vehicle per siding).
	 */
	/** MMTR arrival make-up window: while true the siding tolerates up to two parked vehicles for one merge tick. */
	public boolean mmtrFormationWindow;
	/**
	 * MMTR rolling-stock manifest: when true, vehicles generated on this siding are spawned
	 * manual-allowed so a human (or, later, an AI peer) can drive them directly regardless of the
	 * legacy depot manual/auto flag. Set during the manifest apply and cleared on a full reset.
	 */
	public boolean mmtrManualSpawn;
	/** Manifest-managed: set once this siding has generated its one session train so the engine's
	 * "keep one parked from template" respawn does not keep re-seeding a new train every departure. */
	public boolean mmtrSessionSpawned;

	public void clearParkedVehicles() {
		final ObjectArraySet<Vehicle> toRemove = new ObjectArraySet<>();
		vehicleIdMap.values().forEach(vehicle -> {
			if (!vehicle.getIsOnRoute()) {
				toRemove.add(vehicle);
			}
		});
		toRemove.forEach(vehicle -> vehicleIdMap.remove(vehicle.getId()));
	}

	/**
	 * MMTR vehicle-level operation: remove the train with the given world-unique vehicle id from
	 * this siding, whether parked or on route. Returns whether it was found here.
	 */
	public boolean removeVehicleById(long vehicleId) {
		final Vehicle vehicle = vehicleIdMap.get(vehicleId);
		if (vehicle == null) {
			return false;
		}
		// A parked vehicle is normally re-seeded from the siding template by the engine. Deleting a
		// generated train is an explicit vehicle-level operation, so release the siding's generation
		// slot too: the train stays gone for the session (the manifest is re-applied on next restart).
		final boolean parked = !vehicle.getIsOnRoute();
		vehicleIdMap.remove(vehicleId);
		if (parked) {
			vehicleCars.clear();
		}
		System.out.println("[MMTR-VEH] deleted vehicle " + vehicleId + " from siding " + id + " (" + name + ")" + (parked ? " (slot released)" : ""));
		return true;
	}

	public void startGeneratingDepartures() {
		departures.clear();
		tempReturnTimes.clear();
		for (int i = 0; i < maxVehicles; i++) {
			tempReturnTimes.add(0);
		}
	}

	public boolean addDeparture(long departure) {
		if (getIsManual()) {
			return false;
		} else if (getIsUnlimited()) {
			departures.add(departure);
			return true;
		}

		if (!timeSegments.isEmpty() && area != null) {
			final long journeyTime = getJourneyTime();
			for (int i = 0; i < tempReturnTimes.size(); i++) {
				if (departure >= tempReturnTimes.getLong(i)) {
					departures.add(departure);
					tempReturnTimes.set(i, area.getRepeatInfinitely() ? Long.MAX_VALUE : departure + journeyTime);
					return true;
				}
			}
		}

		return false;
	}

	public double getTimeAlongRoute(double railProgress) {
		final int index = Utilities.getIndexFromConditionalList(timeSegments, railProgress);
		return index < 0 ? -1 : timeSegments.get(index).getTimeAlongRoute(railProgress);
	}

	public void updateVehicleRidingEntities(long vehicleId, ObjectArrayList<VehicleRidingEntity> vehicleRidingEntities) {
		for (final Vehicle vehicle : vehicleIdMap.values()) {
			if (vehicle.getId() == vehicleId) {
				vehicle.updateRidingEntities(vehicleRidingEntities);
				break;
			}
		}
	}

	public String getDepotName() {
		return area == null ? "" : area.getName();
	}

	public void iterateVehiclesAndRidingEntities(BiConsumer<VehicleExtraData, VehicleRidingEntity> consumer) {
		vehicleIdMap.values().forEach(vehicle -> vehicle.vehicleExtraData.iterateRidingEntities(vehicleRidingEntity -> consumer.accept(vehicle.vehicleExtraData, vehicleRidingEntity)));
	}

	/**
	 * Iterate currently active vehicles on this siding.
	 */
	public void iterateVehicles(Consumer<Vehicle> consumer) {
		vehicleIdMap.values().forEach(consumer);
	}

	/**
	 * Look up a vehicle by ID on this siding.
	 *
	 * @param vehicleId the vehicle ID to find
	 * @return the vehicle, or {@code null} if not on this siding
	 */
	@Nullable
	public Vehicle getVehicleById(long vehicleId) {
		return vehicleIdMap.get(vehicleId);
	}

	/**
	 * Build sorted arrivals for one platform, including realtime passenger counts when a concrete
	 * serving vehicle is available.
	 */
	public void getArrivals(long currentMillis, Platform platform, long count, ObjectArrayList<ArrivalResponse> arrivalResponseList) {
		final long[] maxArrivalAndCount = {0, 0};
		final ObjectArrayList<ArrivalResponse> tempArrivalResponseList = new ObjectArrayList<>();

		iterateArrivals(currentMillis, platform.getId(), 0, MILLIS_PER_DAY, (vehicle, trip, tripStopIndex, stopTime, scheduledArrivalTime, scheduledDepartureTime, predicted, deviation, departureIndex, departureOffset) -> {
			if (scheduledArrivalTime + deviation < maxArrivalAndCount[0] || maxArrivalAndCount[1] < count) {
				final ArrivalResponse arrivalResponse = new ArrivalResponse(stopTime.customDestination, scheduledArrivalTime + deviation, scheduledDepartureTime + deviation, deviation, predicted, departureIndex, stopTime.tripStopIndex, trip.route, platform);
				arrivalResponse.setCarDetails(getVehicleCars(), vehicle == null ? null : vehicle.vehicleExtraData.passengers);
				tempArrivalResponseList.add(arrivalResponse);
				maxArrivalAndCount[0] = Math.max(maxArrivalAndCount[0], scheduledArrivalTime + deviation);
				maxArrivalAndCount[1]++;
			}
		});

		Collections.sort(tempArrivalResponseList);
		for (int i = 0; i < Math.min(tempArrivalResponseList.size(), count); i++) {
			arrivalResponseList.add(tempArrivalResponseList.get(i));
		}
	}

	/**
	 * Gets the departures for the {@link DirectionsFinder}.
	 *
	 * @param currentMillis the current time
	 * @param departures    the map to be written to, using the route ID mapped to the {@link Route} and departures at the last platform
	 */
	public void getDeparturesForDirections(long currentMillis, Long2ObjectOpenHashMap<ObjectObjectImmutablePair<Route, LongArrayList>> departures) {
		getDeparturesAtEndOfRoute(
			currentMillis,
			(route, scheduledDepartureTime, deviation) -> departures.computeIfAbsent(route.getId(), key -> new ObjectObjectImmutablePair<>(route, new LongArrayList())).right().add(scheduledDepartureTime + deviation)
		);
	}

	/**
	 * Gets the departures for the online system map for showing realtime vehicle positions.
	 *
	 * @param currentMillis the current time
	 * @param departures    the map to be written to, using the {@link Route} hex ID mapped to departures at the last platform for deviation
	 */
	public void getDeparturesForMap(long currentMillis, Object2ObjectAVLTreeMap<String, Long2ObjectAVLTreeMap<LongArrayList>> departures) {
		getDeparturesAtEndOfRoute(
			currentMillis,
			(route, scheduledDepartureTime, deviation) -> departures.computeIfAbsent(route.getHexId(), key -> new Long2ObjectAVLTreeMap<>()).computeIfAbsent(deviation, key -> new LongArrayList()).add(scheduledDepartureTime - currentMillis)
		);
	}

	public void getOBAArrivalsAndDeparturesElementsWithTripsUsed(SingleElement<StopWithArrivalsAndDepartures> singleElement, StopWithArrivalsAndDepartures stopWithArrivalsAndDepartures, long currentMillis, Platform platform, int millsBefore, int millisAfter) {
		final ObjectAVLTreeSet<String> addedTripIds = new ObjectAVLTreeSet<>();
		iterateArrivals(currentMillis, platform.getId(), millsBefore, millisAfter, (vehicle, trip, tripStopIndex, stopTime, scheduledArrivalTime, scheduledDepartureTime, predicted, deviation, departureIndex, departureOffset) -> {
			final String tripId = trip.getTripId(departureIndex, departureOffset);
			stopWithArrivalsAndDepartures.add(ArrivalAndDeparture.create(
				trip,
				tripId,
				platform,
				stopTime,
				transportMode.continuousMovement ? currentMillis + Depot.CONTINUOUS_MOVEMENT_FREQUENCY : scheduledArrivalTime,
				transportMode.continuousMovement ? currentMillis + Depot.CONTINUOUS_MOVEMENT_FREQUENCY : scheduledDepartureTime,
				predicted,
				deviation,
				getOBAOccupancyStatus(predicted),
				getOBAVehicleId(departureIndex),
				getOBAFrequencyElement(currentMillis),
				new TripStatus(
					tripId,
					stopTime,
					"",
					"",
					getOBAOccupancyStatus(predicted),
					predicted,
					currentMillis,
					deviation,
					getOBAVehicleId(departureIndex),
					getOBAFrequencyElement(currentMillis)
				)
			));
			if (!addedTripIds.contains(tripId)) {
				singleElement.addTrip(trip.getOBATripElement(tripId, departureIndex));
				addedTripIds.add(tripId);
			}
		});
		stopWithArrivalsAndDepartures.sort();
	}

	public void getOBATripDetailsWithDataUsed(SingleElement<TripDetails> singleElement, long currentMillis, int tripIndex, int departureIndex, long departureOffset) {
		final Trip trip = Utilities.getElement(trips, tripIndex);
		if (trip != null) {
			trip.getOBATripDetailsWithDataUsed(
				singleElement,
				currentMillis,
				Utilities.getElement(departures, departureIndex, 0L) + departureOffset * getRepeatInterval(MILLIS_PER_DAY),
				departureIndex,
				departureOffset,
				Utilities.getElement(trips, tripIndex + 1),
				Utilities.getElement(trips, tripIndex - 1)
			);
		}
	}

	public TripStatus getOBATripStatus(long currentMillis, Trip.StopTime stopTime, int departureIndex, long departureOffset, String closestStop, String nextStop) {
		final VehicleDeviationInfo vehicleDeviationInfo = getVehicleDeviationInfo(currentMillis, departureIndex, departureOffset);
		return new TripStatus(
			stopTime.trip.getTripId(departureIndex, departureOffset),
			stopTime,
			closestStop,
			nextStop,
			getOBAOccupancyStatus(vehicleDeviationInfo.predicted),
			vehicleDeviationInfo.predicted,
			currentMillis,
			vehicleDeviationInfo.deviation,
			getOBAVehicleId(departureIndex),
			getOBAFrequencyElement(currentMillis)
		);
	}

	@Nullable
	public Frequency getOBAFrequencyElement(long currentMillis) {
		return transportMode.continuousMovement ? new Frequency(currentMillis) : null;
	}

	long getJourneyTime() {
		final TimeSegment lastTimeSegment = Utilities.getElement(timeSegments, -1);
		return lastTimeSegment == null ? 0 : (long) Math.ceil(lastTimeSegment.startTime + lastTimeSegment.startSpeed / lastTimeSegment.acceleration);
	}

	void writePathCache() {
	}

	long getRepeatInterval(long defaultAmount) {
		if (area == null) {
			return defaultAmount;
		} else if (transportMode.continuousMovement) {
			return (long) Depot.CONTINUOUS_MOVEMENT_FREQUENCY * area.savedRails.size();
		} else if (area.getRepeatInfinitely()) {
			return Math.round(timeOffsetForRepeating);
		} else if (data instanceof final Simulator simulator && !area.getUseRealTime()) {
			return simulator.getGameMillisPerDay() * area.getRepeatDepartures();
		} else {
			return defaultAmount;
		}
	}

	/**
	 * Used by passengers to find a vehicle currently at the platform running on a specified route.
	 *
	 * @return a pair containing the vehicle (null if continuous movement) and whether an arrival was found
	 */
	ObjectBooleanImmutablePair<@Nullable Vehicle> getVehicleDetailsAtPlatform(long routeId, long platformId) {
		final @Nullable Vehicle[] tempVehicles = {null};
		final boolean[] hasArrival = {false};
		iterateArrivals(data.getCurrentMillis(), platformId, 0, 0, (vehicle, trip, tripStopIndex, stopTime, scheduledArrivalTime, scheduledDepartureTime, predicted, deviation, departureIndex, departureOffset) -> {
			if (trip.route.getId() == routeId) {
				tempVehicles[0] = vehicle;
				hasArrival[0] = true;
			}
		});
		return new ObjectBooleanImmutablePair<>(tempVehicles[0], hasArrival[0]);
	}

	/**
	 * Gets the departures at the last platform of each route. Note that departures are different from arrivals; the dwell time at the last platform is included as well.
	 */
	private void getDeparturesAtEndOfRoute(long currentMillis, DepartureConsumer departureConsumer) {
		if (area != null) {
			for (int i = 0; i < area.routes.size(); i++) {
				final Route route = area.routes.get(i);
				final RoutePlatformData routePlatformData = Utilities.getElement(route.getRoutePlatforms(), -1);
				if (routePlatformData == null) {
					continue;
				}

				final long targetRouteId;
				final int targetTripStopIndex;
				final Route nextRoute = Utilities.getElement(area.routes, area.getRepeatInfinitely() && i == area.routes.size() - 1 ? 0 : i + 1);
				final RoutePlatformData nextRoutePlatformData = nextRoute == null ? null : Utilities.getElement(nextRoute.getRoutePlatforms(), 0);
				if (nextRoutePlatformData != null && routePlatformData.platform != null && nextRoutePlatformData.platform != null && routePlatformData.platform.getId() == nextRoutePlatformData.platform.getId()) {
					targetRouteId = nextRoute.getId();
					targetTripStopIndex = 0;
				} else {
					targetRouteId = route.getId();
					targetTripStopIndex = route.getRoutePlatforms().size() - 1;
				}

				if (!transportMode.continuousMovement) {
					iterateArrivals(currentMillis, routePlatformData.platform.getId(), 0, MILLIS_PER_DAY, (vehicle, trip, tripStopIndex, stopTime, scheduledArrivalTime, scheduledDepartureTime, predicted, deviation, departureIndex, departureOffset) -> {
						if (trip.route.getId() == targetRouteId && tripStopIndex == targetTripStopIndex) {
							departureConsumer.accept(route, scheduledDepartureTime, deviation);
						}
					});
				}
			}
		}
	}


	private VehicleDeviationInfo getVehicleDeviationInfo(long currentMillis, int departureIndex, long departureOffset) {
		final Vehicle vehicle;
		final boolean predicted;
		final long deviation;

		if (transportMode.continuousMovement) {
			vehicle = null; // TODO don't return null for continuous movement
			predicted = true;
			deviation = 0;
		} else {
			final LongObjectImmutablePair<Vehicle> timeAlongRoute = vehicleTimesAlongRoute.getOrDefault(departureIndex, new LongObjectImmutablePair<>(-1, null));
			vehicle = timeAlongRoute.right();
			predicted = timeAlongRoute.leftLong() >= 0;
			final long repeatInterval = getRepeatInterval(MILLIS_PER_DAY);
			deviation = predicted ? Utilities.circularDifference(currentMillis - repeatInterval * departureOffset - departures.getLong(getIsManual() ? 0 : departureIndex), timeAlongRoute.leftLong(), repeatInterval) : 0;
		}

		return new VehicleDeviationInfo(vehicle, predicted, deviation);
	}

	private void iterateArrivals(long currentMillis, long platformId, long millsBefore, long millisAfter, ArrivalConsumer arrivalConsumer) {
		if (area == null || departures.isEmpty()) {
			return;
		}

		final ObjectArraySet<Trip.StopTime> tripStopTimes = platformTripStopTimes.get(platformId);
		if (tripStopTimes == null) {
			return;
		}

		final long repeatInterval = getRepeatInterval(MILLIS_PER_DAY);

		tripStopTimes.forEach(stopTime -> {
			final Trip trip = stopTime.trip;

			if (!area.getRepeatInfinitely() || trip.tripIndexInBlock < trips.size() - 1 || stopTime.tripStopIndex < trip.route.getRoutePlatforms().size() - 1) {
				for (int departureIndex = 0; departureIndex < departures.size(); departureIndex++) {
					final long departure = departures.getLong(departureIndex);
					long departureOffset = (currentMillis - (transportMode.continuousMovement ? 0 : millsBefore) - repeatInterval / 2 - stopTime.endTime - departure) / repeatInterval + 1;
					final VehicleDeviationInfo vehicleDeviationInfo = getVehicleDeviationInfo(currentMillis, getIsManual() ? -1 : departureIndex, departureOffset);
					final boolean predicted = vehicleDeviationInfo.predicted;
					final long deviation = vehicleDeviationInfo.deviation;

					while (true) {
						final long scheduledArrivalTime;
						final long scheduledDepartureTime;

						if (transportMode.continuousMovement) {
							scheduledArrivalTime = 0;
							scheduledDepartureTime = 0;
						} else {
							final long offsetMillis = repeatInterval * departureOffset;
							scheduledArrivalTime = stopTime.startTime + offsetMillis + departure;
							scheduledDepartureTime = stopTime.endTime + offsetMillis + departure;
						}

						departureOffset++;

						if (scheduledArrivalTime > currentMillis + millisAfter + repeatInterval / 2) {
							break;
						} else if (!transportMode.continuousMovement) {
							final boolean outOfRange = scheduledDepartureTime + deviation < currentMillis - millsBefore || scheduledArrivalTime + deviation > currentMillis + millisAfter;
							final boolean missedDeparture = !predicted && scheduledArrivalTime - stopTime.startTime + MILLIS_PER_SECOND < currentMillis;
							if (outOfRange || missedDeparture) {
								if (getIsManual()) {
									break;
								} else {
									continue;
								}
							}
						}

						arrivalConsumer.accept(vehicleDeviationInfo.vehicle, trip, stopTime.tripStopIndex, stopTime, scheduledArrivalTime, scheduledDepartureTime, predicted, deviation, getIsManual() ? -1 : departureIndex, departureOffset - 1);

						if (transportMode.continuousMovement || getIsManual()) {
							break;
						}
					}
				}
			}
		});
	}

	private OccupancyStatus getOBAOccupancyStatus(boolean predicted) {
		// TODO implement actual occupancy calculation based on vehicle capacity
		return predicted ? OccupancyStatus.values()[RANDOM.nextInt(OccupancyStatus.values().length - 2)] : OccupancyStatus.NO_DATA_AVAILABLE;
	}

	private String getOBAVehicleId(int departureIndex) {
		final ObjectArrayList<String> vehicleIds = new ObjectArrayList<>();
		vehicleCars.forEach(vehicleCar -> {
			final String vehicleId = vehicleCar.getVehicleId();
			final int index = vehicleId.lastIndexOf("_");
			final String trimmedVehicleId = index < 0 ? vehicleId : vehicleId.substring(0, index);
			if (!vehicleIds.contains(trimmedVehicleId)) {
				vehicleIds.add(trimmedVehicleId);
			}
		});
		return vehicleIds.isEmpty() ? "" : String.format("%s_%s", String.join("_", vehicleIds), departureIndex);
	}
	private void generatePathDistancesAndTimeSegments() {
		// MTR depot-route distance/time generation removed (auto rebuilt on Motion/tasks): the
		// three per-siding route caches are gone; sidings resolve only their yard rail.
		vehicleIdMap.clear();
		trips.clear();
		platformTripStopTimes.clear();
		timeSegments.clear();
	}


	public static double getRailLength(double rawRailLength) {
		return Utilities.round(rawRailLength, 3);
	}

	public static ObjectImmutableList<ReaderBase> savePathDataReaderBase(ReaderBase readerBase, String key) {
		final ObjectArrayList<ReaderBase> tempReaders = new ObjectArrayList<>();
		readerBase.iterateReaderArray(key, tempReaders::clear, tempReaders::add);
		return new ObjectImmutableList<>(tempReaders);
	}

	public static double getTotalVehicleLength(ObjectArrayList<VehicleCar> vehicleCars) {
		double totalVehicleLength = 0;
		for (int i = 0; i < vehicleCars.size(); i++) {
			totalVehicleLength += vehicleCars.get(i).getTotalLength(i == 0, i == vehicleCars.size() - 1);
		}
		return totalVehicleLength;
	}

	public static double roundAcceleration(double acceleration) {
		final double tempAcceleration = Utilities.round(acceleration, 8);
		return tempAcceleration <= 0 ? ACCELERATION_DEFAULT : Utilities.clampSafe(tempAcceleration, MIN_ACCELERATION, MAX_ACCELERATION);
	}

	/**
	 * Finds an upcoming slower rail speed given the current position and speed. A new speed is only returned if the vehicle needs to slow down immediately.
	 *
	 * @return the new slower speed or -1 if no change
	 */
	public static double getUpcomingSlowerSpeed(ObjectList<PathData> path, int currentIndex, double railProgress, double currentSpeed, double deceleration) {
		final double stoppingDistance = 0.5 * currentSpeed * currentSpeed / deceleration;
		int index = currentIndex + 1;
		double railSpeed = -1;
		double bestDistance = 0;

		while (true) {
			final PathData pathData = Utilities.getElement(path, index);
			if (pathData == null) {
				return -1;
			}

			final double newRailSpeed = pathData.getSpeedLimitMetersPerMillisecond();
			final double distance = pathData.getStartDistance() - railProgress;
			if (newRailSpeed < currentSpeed && distance >= bestDistance && distance <= 0.5 * (currentSpeed * currentSpeed - newRailSpeed * newRailSpeed) / deceleration) {
				railSpeed = newRailSpeed;
				bestDistance = distance;
			}

			if (pathData.getEndDistance() >= railProgress + stoppingDistance) {
				return railSpeed;
			}

			index++;
		}
	}

	private record RoutePlatformInfo(Route route, int routeIndex, long platformId, String customDestination) {

		private RoutePlatformInfo(Route route, int routeIndex, long platformId, @Nullable String customDestination) {
			this.route = route;
			this.routeIndex = routeIndex;
			this.platformId = platformId;
			this.customDestination = customDestination == null ? "" : customDestination;
		}
	}

	private record TimeSegment(double startRailProgress, double startSpeed, double startTime, int speedChange, double acceleration, double deceleration) implements ConditionalList {

		private TimeSegment(double startRailProgress, double startSpeed, double startTime, int speedChange, double acceleration, double deceleration) {
			this.startRailProgress = startRailProgress;
			this.startSpeed = startSpeed;
			this.startTime = startTime;
			this.speedChange = speedChange;
			this.acceleration = roundAcceleration(acceleration);
			this.deceleration = roundAcceleration(deceleration);
		}

		@Override
		public boolean matchesCondition(double value) {
			return value >= startRailProgress;
		}

		private double getTimeAlongRoute(double railProgress) {
			final double distance = railProgress - startRailProgress;
			if (speedChange == 0) {
				return startTime + distance / startSpeed;
			} else {
				final double totalAcceleration = speedChange * (speedChange > 0 ? acceleration : deceleration);
				final double endSpeedSquared = 2 * totalAcceleration * distance + startSpeed * startSpeed;
				return endSpeedSquared < 0 ? -1 : startTime + (distance == 0 ? 0 : (Math.sqrt(endSpeedSquared) - startSpeed) / totalAcceleration);
			}
		}
	}

	private record VehicleDeviationInfo(@Nullable Vehicle vehicle, boolean predicted, long deviation) {
	}

	@FunctionalInterface
	private interface ArrivalConsumer {

		void accept(@Nullable Vehicle vehicle, Trip trip, int tripStopIndex, Trip.StopTime stopTime, long scheduledArrivalTime, long scheduledDepartureTime, boolean predicted, long deviation, int departureIndex, long departureOffset);
	}

	@FunctionalInterface
	private interface DepartureConsumer {

		void accept(Route route, long scheduledDepartureTime, long deviation);
	}
}
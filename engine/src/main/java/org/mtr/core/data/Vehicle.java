package org.mtr.core.data;

import it.unimi.dsi.fastutil.booleans.BooleanBooleanImmutablePair;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleDoubleImmutablePair;
import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongObjectImmutablePair;
import it.unimi.dsi.fastutil.objects.*;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.Nullable;
import org.mtr.core.generated.data.VehicleSchema;
import org.mtr.core.mmtr.ConsistDynamics;
import org.mtr.core.mmtr.ConsistType;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.DriveController;
import org.mtr.core.mmtr.DriveOutput;
import org.mtr.core.mmtr.MmtrComposition;
import org.mtr.core.mmtr.MmtrDriveAccess;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.mmtr.MmtrProtection;
import org.mtr.core.mmtr.MmtrSupport;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;

import java.util.UUID;

/**
 * A single train / boat / cable car / airplane consist running along a {@link Siding}.
 *
 * <p>Lives both server-side (where it owns its motion physics, signal blocks and door state)
 * and client-side (where it is replayed from sync snapshots) — see {@link #isClientside}. The
 * physics step is split into {@link #simulateMoving}, {@link #simulateStopped} and
 * {@link #simulateInDepot} depending on the vehicle's state at the start of the tick.</p>
 */
@Log4j2
public class Vehicle extends VehicleSchema implements Utilities {

	/**
	 * The amount of time to check for a blocked status again after detecting a blocked status.
	 */
	private long stoppingCooldown;
	private long deviation;
	private double deviationSpeedAdjustment;
	/**
	 * The time until the vehicle switches from manual to automatic
	 */
	private long manualCooldown;
	private long doorCooldown;
	private boolean atoOverride;
	/**
	 * Last simulator timestamp at which this vehicle moved; used by jam detection.
	 */
	private long lastMovementMillis;

	public final VehicleExtraData vehicleExtraData;

	/**
	 * MMTR: lazily resolved consist type + controller for vehicles driven with the MMTR control
	 * model (server policy set on the {@link Simulator}). Null keeps the legacy power-handle path.
	 */
	private @Nullable ConsistType mmtrConsistType;
	private @Nullable DriveController mmtrDriveController;
	/**
	 * MMTR: explicit control override. Only engaged by the future input layer that sends a real
	 * ControlState (keyboard/HID). Until then manual driving follows the legacy single handle so
	 * the game stays fully playable (equivalent to having no consist-type policy).
	 */
	private boolean mmtrManualOverride;
	private @Nullable ControlState mmtrActiveControl;
	/**
	 * MMTR: uuid of the driver currently holding the explicit override (occupation lock).
	 * Set/cleared together with {@link #mmtrManualOverride}; kept {@code null} on the legacy
	 * no-identity path so that path keeps its old behaviour.
	 */
	private @Nullable UUID mmtrDriverUuid;
	/**
	 * MMTR: per-car composition used when the consist runs the AIR_BRAKE model (one pipe/cylinder
	 * state per car, equalised along the train). Server persists it across ticks; mirrored clients
	 * rebuild it from the latest snapshot (seeded via {@code mmtrAirState}).
	 */
	private @Nullable MmtrComposition mmtrComposition;
	private String mmtrLastAirSeed = "";
	/**
	 * MMTR (server): remaining hold time (ms) after an overrun/SPAD protection emergency stop,
	 * before control is released again (SCR-style lock). Mirrored clients just follow the
	 * synced {@code mmtrProtection} flag and do not count down locally.
	 */
	private long mmtrProtectionLockRemaining;
	/**
	 * MMTR: the task/mission currently assigned to this train (consist). Owned by the train —
	 * players/AI only execute or read it. Null when the consist is idle/unscheduled.
	 */
	private @Nullable MmtrMission mmtrMission;
	/**
	 * MMTR (server): when the current mission entered AT_TARGET; used to complete after a dwell.
	 */
	private long mmtrMissionTargetArrivedMillis;
	/**
	 * MMTR (L3, server): live Motion-Core run mode. When non-null this vehicle's RUNNING motion is
	 * decided per tick by the walker — (segment, offset), fork branches elected live from the current
	 * BranchStore/task at each node — instead of a pre-baked whole-journey path. {@link #railProgress}
	 * is the walker's cumulative distance and {@link #mmtrMotionLegs} is the growing ordered leg
	 * shadow (cumulative PathData) that the legacy render/occupancy helpers walk. Clientside mirrors
	 * never engage this mode (they keep replaying the synced legacy path).
	 */
	private @Nullable MmtrMotionWalker mmtrMotionWalker;
	/** Motion-mode leg shadow: cumulative PathData list, refreshed when the walker boards a new rail. */
	private final ObjectArrayList<PathData> mmtrMotionLegs = new ObjectArrayList<>();
	/** Walker leg count at the last shadow refresh (detects newly boarded rails). */
	private int mmtrMotionLegCount;
	/**
	 * MMTR (L3): cumulative stop target for the current motion run (m in walker distance space);
	 * -1 = no stop target (free run). When set, the vehicle auto service-brakes and comes to rest
	 * exactly at the target, opens the doors if requested, and holds until a NEW control is applied.
	 */
	private double mmtrMotionStopTargetM = -1;
	private boolean mmtrMotionStoppedAtTarget;
	private boolean mmtrMotionStopOpenDoors;
	/** applyMmtrControl() sequence; a changed sequence while stopped at a target = the driver's continue. */
	private int mmtrControlApplySeq;
	private int mmtrMotionArrivalControlSeq = -1;
	/**
	 * MMTR (L3): unmanned auto run (task/ATO foundation). While armed and a stop target is active,
	 * the vehicle drives itself (cruise at the auto notch, service-brake envelope to the exact stop
	 * target, doors per the stop request) without any driver override; arming the NEXT stop target
	 * while stopped departs automatically (step-run). An active manual override always wins; when it
	 * is released the auto run resumes.
	 */
	private boolean mmtrMotionAuto;
	@Nullable
	private final Siding siding;
	/**
	 * If a vehicle is clientside, don't open the doors or start up automatically. Always wait for a socket update instead.
	 */
	private final boolean isClientside;

	public static final int MAX_POWER_LEVEL = 7;
	public static final int POWER_LEVEL_RATIO = 5;
	public static final int DOOR_MOVE_TIME = 3200;
	private static final int DOOR_DELAY = 1000;
	/**
	 * Vehicles that do not move for this long while on-route are treated as jammed.
	 */
	private static final long JAM_THRESHOLD = 5 * MILLIS_PER_MINUTE;
	/**
	 * MMTR: hold time (ms) after an overrun/SPAD protection emergency stop before the driver can
	 * take control again (SCR/TPWS-style lock).
	 */
	private static final long MMTR_PROTECTION_LOCK_MS = 10_000;
	/**
	 * MMTR: fixed integration sub-step for the longitudinal model. Server ticks and client frames
	 * split their elapsed time into these fine steps so stiff dynamics (air brake, coupler slack
	 * later) stay stable and the client mirror integrates identically.
	 */
	private static final long MMTR_INTEGRATION_SUB_STEP_MS = 10;
	/**
	 * MMTR: how long a mission stays AT_TARGET (dwell for passengers) before completing.
	 */
	private static final long MMTR_MISSION_DWELL_MILLIS = 5000;

	public Vehicle(VehicleExtraData vehicleExtraData, @Nullable Siding siding, TransportMode transportMode, Data data) {
		super(transportMode, data);
		this.siding = siding;
		this.vehicleExtraData = vehicleExtraData;
		this.isClientside = !(data instanceof Simulator);
	}

	public Vehicle(VehicleExtraData vehicleExtraData, @Nullable Siding siding, ReaderBase readerBase, Data data) {
		super(readerBase, data);
		this.siding = siding;
		this.vehicleExtraData = vehicleExtraData;
		this.isClientside = !(data instanceof Simulator);
		updateData(readerBase);
	}

	/**
	 * Internal-only: the single-argument constructor used by
	 * {@link org.mtr.core.operation.VehicleUpdate} when reconstructing a vehicle from a network
	 * update. Wraps the read in a fresh {@link ClientData} so the resulting vehicle has somewhere
	 * to look up cached references. <strong>Do not call from user code.</strong>
	 */
	@Deprecated
	@SuppressWarnings("DeprecatedIsStillUsed")
	public Vehicle(ReaderBase readerBase) {
		this(new VehicleExtraData(readerBase), null, readerBase, new ClientData());
	}

	@Override
	public boolean isValid() {
		return true;
	}

	public boolean isMoving() {
		return speed != 0;
	}

	/** Current speed in engine internal units (m/ms). */
	public double getSpeed() {
		return speed;
	}

	/** Current distance along the path (m) measured at the vehicle's head. */
	public double getRailProgress() {
		return railProgress;
	}

	/** Debug/test read access: remaining time until the vehicle falls back out of manual mode. */
	public long getManualCooldownMillis() {
		return manualCooldown;
	}

	/** Debug/test read access: door animation/cooldown time left. */
	public long getDoorCooldownMillis() {
		return doorCooldown;
	}

	/** Whether the vehicle is currently driven manually (server-side semantics). */
	public boolean isCurrentlyManual() {
		if (isClientside) {
			log.warn("Vehicle#isCurrentlyManual should only be called on the server side!");
		}
		// MMTR: while an operator / AI controller holds the explicit override the train stays manual
		// - MTR's "manual-to-automatic" hand-back must not hijack an in-progress human (or future AI)
		// drive. Release returns it to whatever the (empty) stock state implies.
		return mmtrManualOverride || (!atoOverride && manualCooldown > 0);
	}

	/**
	 * Server-side autopilot / headless seam: engages the manual control path without a riding
	 * player, so a mission's AUTOPILOT executor can drive the consist directly (manual sidings).
	 * No-op on clientside or when the vehicle does not allow manual driving.
	 */
	public void engageManualAutopilot(long manualToAutomaticMillis) {
		if (isClientside || !vehicleExtraData.getIsManualAllowed()) {
			return;
		}
		atoOverride = false;
		manualCooldown = Math.max(0, manualToAutomaticMillis);
	}

	/**
	 * MMTR: assign a task/mission to this train. Only one mission is active at a time;
	 * assigning over an existing active mission fails (callers should cancel first).
	 */
	public boolean setMmtrMission(@Nullable MmtrMission mission) {
		if (mission == null) {
			mmtrMission = null;
			return true;
		}
		if (mmtrMission != null && !mmtrMission.isTerminal()) {
			return false;
		}
		mmtrMission = mission;
		return true;
	}

	public @Nullable MmtrMission getMmtrMission() {
		return mmtrMission;
	}

	/**
	 * MMTR (server): drive this train headlessly for an AUTOPILOT mission on a manual-allowed
	 * consist, mirroring exactly what a real driver does (doors closed, manual engaged, full
	 * throttle). Refreshing manual cooldown each tick keeps the autopilot engaged.
	 */
	public void engageMissionAutopilot() {
		if (isClientside || !vehicleExtraData.getIsManualAllowed()) {
			return;
		}
		vehicleExtraData.closeDoors();
		engageManualAutopilot(vehicleExtraData.getManualToAutomaticTime());
		vehicleExtraData.setPowerLevel(MAX_POWER_LEVEL);
	}

	/**
	 * MMTR (server): advance the active mission state machine from observed train state.
	 * Missions are attached to the train; the executor (AUTOPILOT / PLAYER / AI) only reads or
	 * drives, so the lifecycle advances whether a driver is present or not.
	 */
	public void mmtrMissionTick() {
		if (isClientside || mmtrMission == null) {
			return;
		}
		final MmtrMission mission = mmtrMission;
		final boolean motionMission = mmtrMotionWalker != null;
		switch (mission.getState()) {
			case ASSIGNED:
				// The consist started moving (left the depot / began its run) => dispatched.
				if (isMoving()) {
					mission.dispatch();
				}
				break;
			case DISPATCHED:
				// Motion-mode missions arrive when the armed stop target is reached exactly;
				// legacy-path missions use their path/platform stop semantics.
				if (motionMission ? isMmtrMotionStoppedAtTarget() : isStoppedAtMissionTarget()) {
					mission.atTarget();
					mmtrMissionTargetArrivedMillis = data.getCurrentMillis();
					if (!motionMission && mission.getExecutor() == MmtrMission.Executor.AUTOPILOT && vehicleExtraData.getIsManualAllowed()) {
						// An AUTOPILOT mission drives headlessly: release the throttle on arrival so the
						// consist settles at the target (platform dwell or depot terminal) instead of
						// re-departing on the repeating depot path.
						vehicleExtraData.setPowerLevel(0);
					}
				}
				break;
			case AT_TARGET:
				// Complete after a dwell at the target while stationary (passengers board/alight).
				if (!isMoving() && data.getCurrentMillis() - mmtrMissionTargetArrivedMillis >= MMTR_MISSION_DWELL_MILLIS) {
					mission.complete();
				}
				break;
			default:
				break;
		}
		// A terminal motion mission (complete / failed / canceled) hands the vehicle back to idle:
		// auto run off, stop target cleared, doors closed - the consist rests where it is.
		if (motionMission && mission.isTerminal()) {
			mmtrMotionAuto = false;
			mmtrMotionStopTargetM = -1;
			mmtrMotionStoppedAtTarget = false;
			mmtrMotionStopOpenDoors = false;
			mmtrMotionArrivalControlSeq = -1;
			vehicleExtraData.closeDoors();
		}
	}

	/**
	 * Whether the train is stationary at its mission target: either stopped on the platform whose
	 * id equals the target (PASSENGER service) or stopped at the end of the route (target 0 =
	 * terminal / freight destination).
	 */
	private boolean isStoppedAtMissionTarget() {
		final long targetSidingId = mmtrMission == null ? 0 : mmtrMission.getTargetSidingId();
		if (isMoving() || !getIsOnRoute()) {
			return false;
		}
		if (targetSidingId == 0) {
			// Terminal / freight run: the consist either stopped at the far end of the path or
			// has been wrapped back into its depot slot after finishing the run.
			return railProgress >= vehicleExtraData.getTotalDistance() - 1 || closeToDepot();
		}
		return vehicleExtraData.getThisPlatformId() == targetSidingId;
	}

	public boolean getIsOnRoute() {
		return railProgress > vehicleExtraData.getDefaultPosition();
	}

	public boolean getReversed() {
		return reversed;
	}

	public boolean closeToDepot() {
		return !getIsOnRoute() || railProgress < vehicleExtraData.getTotalVehicleLength() + vehicleExtraData.getRailLength();
	}

	public void initVehiclePositions(Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> vehiclePositions) {
		// Motion-mode vehicles seed their occupancy from the live walker shadow each tick instead.
		if (mmtrMotionWalker == null) {
			writeVehiclePositions(Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, railProgress), vehiclePositions);
		}
	}

	public void simulate(long millisElapsed, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, @Nullable Long2ObjectOpenHashMap<LongObjectImmutablePair<Vehicle>> vehicleTimesAlongRoute) {
		// MMTR: release the explicit override as soon as its driver no longer rides as a cab
		// driver (occupation lock), so a stale ControlState never keeps a consist moving and a
		// new driver can take over.
		if (!isClientside && MmtrDriveAccess.shouldAutoRelease(mmtrManualOverride, mmtrDriverUuid, mmtrDriverUuid != null && hasMmtrDriverRiding(mmtrDriverUuid))) {
			releaseMmtrManualOverride();
		}

		// MMTR (server): protection lock countdown after an overrun/SPAD emergency stop.
		if (!isClientside && mmtrProtection && speed <= 0) {
			mmtrProtectionLockRemaining -= millisElapsed;
			if (mmtrProtectionLockRemaining <= 0) {
				mmtrProtection = false;
				mmtrProtectionLockRemaining = 0;
				System.out.println("[MMTR-DRV] protection lock cleared");
			}
		}

		final int currentIndex;
		final BooleanBooleanImmutablePair containsDriverAndDoorOverride = vehicleExtraData.containsDriverAndDoorOverride();
		manualCooldown = vehicleExtraData.getIsManualAllowed() && containsDriverAndDoorOverride.leftBoolean() ? vehicleExtraData.getManualToAutomaticTime() : Math.max(0, manualCooldown - millisElapsed);
		doorCooldown = vehicleExtraData.getDoorMultiplier() > 0 || containsDriverAndDoorOverride.rightBoolean() ? DOOR_MOVE_TIME + DOOR_DELAY : Math.max(0, doorCooldown - millisElapsed);

		// MMTR: an active operator / AI controller (explicit override) keeps the train in manual
		// control - never let the stock ATO hand-back or auto platform-stopping take over mid-drive.
		if (mmtrManualOverride) {
			manualCooldown = Math.max(1, manualCooldown);
			atoOverride = false;
		}

		// MMTR (L3): live Motion-Core run vehicles (see {@link #engageMmtrMotion}) tick their own
		// segment+offset state machine; the legacy baked-path on-route/stopped/depot dispatch does not
		// apply while engaged.
		final boolean mmtrMotionMode = !isClientside && mmtrMotionWalker != null;

		if (mmtrMotionMode) {
			simulateMmtrMotion(millisElapsed, vehiclePositions);
			currentIndex = 0;
		} else if (getIsOnRoute()) {
			if (vehicleExtraData.getRepeatIndex2() == 0 && railProgress >= vehicleExtraData.getTotalDistance() - (vehicleExtraData.getRailLength() - vehicleExtraData.getTotalVehicleLength()) / 2) {
				// If the route does not repeat infinitely and the vehicle is reaching the end
				currentIndex = 0;
				if (!isClientside) {
					vehicleExtraData.setPowerLevel(Math.min(vehicleExtraData.getPowerLevel(), -1));
				}
				vehicleExtraData.passengers.forEach(ObjectArraySet::clear);
				simulateInDepot();
			} else {
				// If the vehicle is on route normally
				currentIndex = Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, railProgress);
				if (speed <= 0) {
					// If the vehicle is stopped (at a platform or waiting for a signal)
					speed = 0;
					simulateStopped(millisElapsed, vehiclePositions, currentIndex);
				} else {
					// If the vehicle is moving normally
					simulateMoving(millisElapsed, vehiclePositions, currentIndex);
				}
			}
		} else {
			currentIndex = 0;
			simulateInDepot();
		}

		stoppingCooldown = Math.max(0, stoppingCooldown - millisElapsed);

		if (vehiclePositions != null && vehiclePositions.size() > 1) {
			if (mmtrMotionMode) {
				writeMmtrMotionVehiclePositions(vehiclePositions.get(1));
			} else {
				writeVehiclePositions(currentIndex, vehiclePositions.get(1));
			}
		}

		if (vehicleTimesAlongRoute != null && !mmtrMotionMode) {
			final long timeAlongRoute = getTimeAlongRoute(railProgress);
			if (timeAlongRoute > 0) {
				vehicleTimesAlongRoute.put(departureIndex, new LongObjectImmutablePair<>(timeAlongRoute, this));
			}
		}

		if (!isClientside) {
			if (data instanceof final Simulator simulator) {
				// Remove entities that have dismounted
				vehicleExtraData.removeRidingEntitiesIf(vehicleRidingEntity -> !simulator.isRiding(vehicleRidingEntity.uuid, id));

				// Check jam status
				if (simulator.getCurrentMillis() - lastMovementMillis >= JAM_THRESHOLD) {
					simulator.markRouteJammed(vehicleExtraData.getPreviousRouteId());
					simulator.markRouteJammed(vehicleExtraData.getThisRouteId());
					simulator.markRouteJammed(vehicleExtraData.getNextRouteId());
				}
			}

			// Update the manual state for the client
			vehicleExtraData.setIsCurrentlyManual(isCurrentlyManual());

			// MMTR: keep the mission alive. An AUTOPILOT mission on a manual-allowed consist is
			// driven headlessly (refresh the manual seam so it never times out), then the mission
			// state machine advances from observed train state (moved / at target / dwell done).
			// Motion-mode missions run through the auto step-run instead - no legacy manual seam.
			if (mmtrMission != null && !mmtrMission.isTerminal() && mmtrMission.getState() != MmtrMission.State.AT_TARGET && mmtrMission.getExecutor() == MmtrMission.Executor.AUTOPILOT && vehicleExtraData.getIsManualAllowed() && mmtrMotionWalker == null) {
				engageMissionAutopilot();
			}
			mmtrMissionTick();
		}
	}

	public void startUp(long newDepartureIndex, long newSidingDepartureTime) {
		if (isClientside) {
			log.warn("Vehicle#startUp should only be called on the server side!");
		}

		vehicleExtraData.closeDoors();
		lastMovementMillis = data.getCurrentMillis();

		// Ensure doors are closed before starting up
		if (doorCooldown == 0) {
			departureIndex = newDepartureIndex;
			sidingDepartureTime = newSidingDepartureTime;
			railProgress += Siding.ACCELERATION_DEFAULT;
			elapsedDwellTime = 0;
			speed = Siding.ACCELERATION_DEFAULT;
			atoOverride = false;
			vehicleExtraData.setSpeedTarget(speed);
			setNextStoppingIndex();

			// Calculate deviation speed adjustment
			updateDeviation();
			if (deviation > 0 && nextStoppingIndexAto < vehicleExtraData.immutablePath.size() - 1 && siding != null && siding.getDelayedVehicleSpeedIncreasePercentage() > 0) {
				final double endRailProgress = vehicleExtraData.immutablePath.get((int) nextStoppingIndexAto).getEndDistance();
				final double distance = endRailProgress - railProgress;
				final double scheduledDuration = getTimeAlongRoute(endRailProgress) - getTimeAlongRoute(railProgress);
				final double expectedDuration = Math.max(1, scheduledDuration - deviation);
				final double averageSpeed = distance / scheduledDuration;
				final double expectedSpeed = distance / expectedDuration;
				deviationSpeedAdjustment = Math.min(expectedSpeed / averageSpeed, siding.getDelayedVehicleSpeedIncreasePercentage() / 100F + 1);
			} else {
				deviationSpeedAdjustment = 1;
			}
		}
	}

	public long getDepartureIndex() {
		return departureIndex;
	}

	public ObjectArrayList<ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<BogiePosition>>> getVehicleCarsAndPositions() {
		final ObjectArrayList<ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<BogiePosition>>> vehicleCarsAndPositions = new ObjectArrayList<>();
		double checkRailProgress = railProgress - (reversed ? vehicleExtraData.getTotalVehicleLength() : 0);

		for (int i = 0; i < vehicleExtraData.immutableVehicleCars.size(); i++) {
			final VehicleCar vehicleCar = vehicleExtraData.immutableVehicleCars.get(i);
			checkRailProgress += (reversed ? 1 : -1) * vehicleCar.getCouplingPadding1(i == 0);
			final double halfLength = vehicleCar.getLength() / 2;
			final ObjectArrayList<BogiePosition> bogiePositionsList = new ObjectArrayList<>();
			final DoubleArrayList overrideY = new DoubleArrayList(); // For airplanes, don't nosedive when descending
			bogiePositionsList.add(getBogiePositions(checkRailProgress + (reversed ? 1 : -1) * (halfLength + vehicleCar.getBogie1Position()), overrideY));

			if (!vehicleCar.hasOneBogie) {
				bogiePositionsList.add(getBogiePositions(checkRailProgress + (reversed ? 1 : -1) * (halfLength + vehicleCar.getBogie2Position()), overrideY));
			}

			vehicleCarsAndPositions.add(new ObjectObjectImmutablePair<>(vehicleCar, bogiePositionsList));
			checkRailProgress += (reversed ? 1 : -1) * vehicleCar.getTotalLength(true, false);
		}

		return vehicleCarsAndPositions;
	}

	@Nullable
	public PositionAndTiltAngle getHeadPositionAndTiltAngle() {
		return getPositionAndTiltAngle(railProgress, new DoubleArrayList());
	}

	void updateRidingEntities(ObjectArrayList<VehicleRidingEntity> vehicleRidingEntities) {
		if (!isClientside && data instanceof final Simulator simulator) {
			final ObjectOpenHashSet<UUID> uuidToRemove = new ObjectOpenHashSet<>();
			final ObjectOpenHashSet<VehicleRidingEntity> vehicleRidingEntitiesToAdd = new ObjectOpenHashSet<>();

			vehicleRidingEntities.forEach(vehicleRidingEntity -> {
				uuidToRemove.add(vehicleRidingEntity.uuid);

				if (vehicleRidingEntity.isOnVehicle()) {
					vehicleRidingEntitiesToAdd.add(vehicleRidingEntity);
					simulator.ride(vehicleRidingEntity.uuid, id);
				} else {
					simulator.stopRiding(vehicleRidingEntity.uuid);
				}

				if (vehicleExtraData.getIsManualAllowed() && vehicleRidingEntity.isDriver()) {
					final int powerLevel = vehicleExtraData.getPowerLevel();
					if (vehicleRidingEntity.manualToggleDoors()) {
						if (speed > 0) {
							vehicleExtraData.closeDoors();
						} else {
							vehicleExtraData.toggleDoors();
						}
					}

					if (vehicleRidingEntity.manualToggleAto()) {
						atoOverride = speed > 0 && !atoOverride;
					}

					if (vehicleRidingEntity.manualAccelerate()) {
						vehicleExtraData.setPowerLevel(Math.min(powerLevel + 1, MAX_POWER_LEVEL));
						atoOverride = false;
					} else if (vehicleRidingEntity.manualBrake()) {
						vehicleExtraData.setPowerLevel(Math.max(powerLevel - 1, -MAX_POWER_LEVEL - 1));
						atoOverride = false;
					}
				}
			});

			vehicleExtraData.removeRidingEntitiesIf(vehicleRidingEntity -> uuidToRemove.contains(vehicleRidingEntity.uuid));
			vehicleExtraData.addRidingEntities(vehicleRidingEntitiesToAdd);
		}
	}

	long getSidingDepartureTime() {
		return sidingDepartureTime;
	}

	private void simulateInDepot() {
		railProgress = vehicleExtraData.getDefaultPosition();
		reversed = false;
		speed = 0;
		nextStoppingIndexAto = 0;
		nextStoppingIndexManual = 0;
		departureIndex = -1;
		sidingDepartureTime = -1;
		vehicleExtraData.closeDoors();

		if (!isClientside && isCurrentlyManual() && !mmtrProtection && (vehicleExtraData.getPowerLevel() > 0 || isMmtrRequestingPower())) {
			startUp(-1, data.getCurrentMillis());
		}
	}

	private void simulateStopped(long millisElapsed, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, int currentIndex) {
		if (isClientside) {
			return;
		}

		final PathData pathData = Utilities.getElement(vehicleExtraData.immutablePath, currentIndex);
		if (pathData == null) {
			return;
		}

		vehicleExtraData.setStoppingPoint(railProgress);
		stoppingCooldown = 0;

		if (isCurrentlyManual()) {
			lastMovementMillis = data.getCurrentMillis();
			if (railProgress == pathData.getStartDistance()) {
				// Stopped behind a node
				final PathData currentPathData = Utilities.getElement(vehicleExtraData.immutablePath, currentIndex - 1);
				final PathData nextPathData = Utilities.getElement(vehicleExtraData.immutablePath, vehicleExtraData.getRepeatIndex2() > 0 && currentIndex >= vehicleExtraData.getRepeatIndex2() ? vehicleExtraData.getRepeatIndex1() : currentIndex);
				final boolean isOpposite = currentPathData != null && nextPathData != null && currentPathData.isOppositeRail(nextPathData);
				final double nextStartDistance = nextPathData == null ? 0 : nextPathData.getStartDistance() + (isOpposite ? vehicleExtraData.getTotalVehicleLength() : 0);

				if (!mmtrProtection && (vehicleExtraData.getPowerLevel() > 0 || isMmtrRequestingPower()) && railBlockedDistance(currentIndex, nextStartDistance, 0, vehiclePositions, true, false) < 0) {
					if (doorCooldown == 0) {
						railProgress = nextStartDistance;
						if (isOpposite) {
							reversed = !reversed;
						}
					}
					startUp(departureIndex, sidingDepartureTime);
				}
			} else {
				// Stopped anywhere else
				if (!mmtrProtection && (vehicleExtraData.getPowerLevel() > 0 || isMmtrRequestingPower()) && railBlockedDistance(currentIndex, railProgress, 0, vehiclePositions, true, false) < 0) {
					startUp(departureIndex, sidingDepartureTime);
				}
			}
		} else {
			// MTR timetable/ATO auto driving removed (auto rebuilt on Motion/tasks): an unmanned
			// consist stopped at a stop does not auto-dwell / open doors / auto-restart. It only resumes
			// when a driver (ControlState / mmtrManualOverride) or a task/mission drives it.
		}
	}

	/**
	 * MMTR (L3): one tick of the live Motion-Core run state machine (server). The existing cab control
	 * (a ControlState via {@link #applyMmtrControl}) drives the MMTR physics model exactly like the
	 * legacy path branch; the integrated distance advances the embedded {@link MmtrMotionWalker},
	 * which moves the consist by (segment, offset) and elects each next rail at the node from the
	 * CURRENT turnout state / task (an unset fork halts and waits — the next tick re-asks, so flipping
	 * the branch makes the same vehicle continue). Speed is zeroed the moment the walker can no longer
	 * consume distance (authority halt / end of line). Platform dwell / doors / signals are later
	 * slices; doors stay closed while running.
	 */
	private void simulateMmtrMotion(long millisElapsed, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions) {
		final ControlState control = mmtrActiveControl;
		final boolean overridden = mmtrManualOverride && control != null;
		final boolean wantPower = overridden && control.getReverser() > 0 && control.getThrottleNotch() > 0 && !(control.getBrakeNotch() > 0 || control.isEmergency());
		final boolean braking = overridden && (control.getBrakeNotch() > 0 || control.isEmergency());
		final boolean stopTargetActive = mmtrMotionStopTargetM >= 0;

		// Stopped exactly at the armed stop target: hold there. Doors stay open when the stop asked
		// for it; a FRESH control application (driver pushes again / task re-commands) closes the
		// doors and starts the next run from the same spot.
		if (mmtrMotionStoppedAtTarget) {
			speed = 0;
			if (mmtrMotionStopOpenDoors) {
				vehicleExtraData.openDoors();
			}
			if (overridden && wantPower && mmtrControlApplySeq != mmtrMotionArrivalControlSeq) {
				vehicleExtraData.closeDoors();
				mmtrMotionStopTargetM = -1;
				mmtrMotionStoppedAtTarget = false;
				mmtrMotionArrivalControlSeq = -1;
				System.out.println("[MMTR-DRV] motion departed stop target");
			}
			if (!isClientside) {
				vehicleExtraData.setPowerLevel(0);
				vehicleExtraData.setSpeedTarget(0);
				updateMmtrSyncFields();
			}
			return;
		}

		vehicleExtraData.closeDoors();
		final double previousSpeed = speed;
		// Unmanned auto run: drives itself toward the armed stop target while no cab override is held.
		final boolean autoActive = mmtrMotionAuto && !mmtrManualOverride && stopTargetActive && !mmtrMotionStoppedAtTarget && mmtrMotionStopTargetM - mmtrMotionWalker.distanceM() > 1e-6;
		final int autoNotch = mmtrConsistType != null ? Math.max(1, Math.min(4, mmtrConsistType.getPowerNotches())) : 4;
		final double remainingToStop = stopTargetActive ? mmtrMotionStopTargetM - mmtrMotionWalker.distanceM() : Double.MAX_VALUE;
		final boolean autoBraking = stopTargetActive && speed > 0 && remainingToStop > 0 && remainingToStop < 0.5 * speed * speed / Math.max(mmtrMotionServiceDecelPerMs(), 1e-12);

		double integratedDistance = 0;
		if (autoBraking) {
			// Service-brake to an exact rest at the armed stop target (constant-decel law; the trailing
			// clamp below trims the last sub-tick remainder). Driver traction is overridden inside the
			// braking envelope, like an ATO stop; the driver's own emergency brake stays stronger.
			final double brakeDelta = Math.min(0.5 * speed * speed / Math.max(remainingToStop, 1e-3), mmtrMotionServiceDecelPerMs()) * millisElapsed;
			speed = Math.max(0, speed - brakeDelta);
			integratedDistance = speed > 0 ? speed * millisElapsed : 0;
		} else if ((overridden || autoActive) && tryInitMmtrController() && mmtrConsistType != null && mmtrDriveController != null) {
			// Same fixed sub-step ConsistDynamics integration as the legacy MMTR branch in
			// simulateMoving (server side; motion mode has no clientside mirror yet). Auto runs feed a
			// synthesized cruise ControlState; an active cab override feeds the driver's own state.
			final ConsistType mmtrType = mmtrConsistType;
			final ControlState mmtrState = overridden ? control : new ControlState().setThrottleNotch(autoNotch).setReverser(1);
			final boolean useCompositionAir = mmtrType.getControlMode() == ConsistType.ControlMode.AIR_BRAKE;
			final MmtrComposition mmtrCompositionNow = useCompositionAir ? getMmtrComposition() : null;
			final double mmtrStartSpeedSi = MmtrSupport.internalSpeedToSi(speed);
			final ConsistDynamics.SpeedDistance mmtrResult = ConsistDynamics.advance(mmtrStartSpeedSi, mmtrType, millisElapsed, MMTR_INTEGRATION_SUB_STEP_MS, (siSpeed, stepMillis) -> {
				if (mmtrCompositionNow != null) {
					return mmtrCompositionNow.stepAir(mmtrState, siSpeed, stepMillis);
				}
				return mmtrDriveController.compute(mmtrState, mmtrType, siSpeed, stepMillis);
			});
			speed = MmtrSupport.siSpeedToInternal(mmtrResult.speedMetersPerSecond);
			integratedDistance = mmtrResult.distanceMeters;
			if (mmtrCompositionNow != null) {
				mmtrAirState = MmtrComposition.encodeAirStates(mmtrCompositionNow);
				mmtrPipePressure = mmtrCompositionNow.averagePipePressure();
				mmtrBrakeCylinderPressure = mmtrCompositionNow.averageCylinderPressure();
			}
		} else if (overridden || autoActive) {
			// No consist-type policy: linear legacy-style integration from the ControlState notches.
			// Stored VED values are SI (m/s^2) scaled by 1e-3; the internal per-ms rate is SI * 1e-6,
			// so the per-tick speed change is value * 1e-3 * millisElapsed (m/ms).
			final double accelPerMs = vehicleExtraData.getAcceleration() * 1e-3;
			final double decelPerMs = vehicleExtraData.getDeceleration() * 1e-3;
			final boolean effectivePower = overridden ? wantPower : autoActive;
			if (overridden && braking) {
				speed = Math.max(0, speed - decelPerMs * millisElapsed);
			} else if (effectivePower) {
				speed = Math.min(vehicleExtraData.getMaxManualSpeed(), speed + accelPerMs * millisElapsed);
			} else {
				speed = Math.max(0, speed - decelPerMs * 0.1 * millisElapsed); // coast-down
			}
			integratedDistance = speed * millisElapsed;
		} else if (speed > 0) {
			// Override released mid-run: service-brake to rest (occupation safety).
			speed = Math.max(0, speed - vehicleExtraData.getDeceleration() * 1e-3 * millisElapsed);
			integratedDistance = speed * millisElapsed;
		}

		if (stopTargetActive && !mmtrMotionStoppedAtTarget) {
			final double remaining = mmtrMotionStopTargetM - mmtrMotionWalker.distanceM();
			if (remaining <= 1e-6) {
				integratedDistance = 0;
				speed = 0;
			} else if (integratedDistance > remaining) {
				integratedDistance = remaining; // land exactly on the armed stop target
			}
		}

		if (integratedDistance > 0) {
			final double before = mmtrMotionWalker.distanceM();
			mmtrMotionWalker.advance(integratedDistance);
			if (mmtrMotionWalker.legCount() > mmtrMotionLegCount) {
				refreshMmtrMotionLegs();
				mmtrMotionLegCount = mmtrMotionWalker.legCount();
			}
			railProgress = mmtrMotionWalker.distanceM();
			final double consumed = railProgress - before;
			if (stopTargetActive && railProgress >= mmtrMotionStopTargetM - 1e-6) {
				speed = 0;
				mmtrMotionArriveAtStopTarget();
			} else if (consumed < integratedDistance - 1e-9) {
				// Authority halt at an unset fork / end of line / target: cannot consume the whole
				// integrated distance — come to rest and wait (a fresh advance re-asks the node).
				speed = 0;
				if (previousSpeed > 1e-9) {
					System.out.println("[MMTR-DRV] motion authority halt on " + mmtrMotionWalker.railHex() + " at " + Math.round(mmtrMotionWalker.offsetM() * 100.0) / 100.0 + "m (awaiting operator/task)");
				}
			} else {
				lastMovementMillis = data.getCurrentMillis();
				System.out.println("[MMTR-DRV] motion seg=" + mmtrMotionWalker.railHex() + " offset=" + Math.round(mmtrMotionWalker.offsetM() * 100.0) / 100.0 + " dist=" + Math.round(consumed * 1000.0) / 1000.0 + " speed=" + speed);
			}
		} else if (stopTargetActive && speed == 0 && mmtrMotionStopTargetM - mmtrMotionWalker.distanceM() <= 1e-6) {
			mmtrMotionArriveAtStopTarget();
		}

		if (!isClientside) {
			final int displayPower = overridden ? (wantPower ? control.getThrottleNotch() : braking ? -Math.max(1, control.getBrakeNotch()) : 0) : (autoActive ? autoNotch : 0);
			vehicleExtraData.setPowerLevel(displayPower);
			vehicleExtraData.setSpeedTarget(speed);
			updateMmtrSyncFields();
		}
	}

	/** Marks the exact arrival at the armed stop target: rest, doors per the stop request, hold. */
	private void mmtrMotionArriveAtStopTarget() {
		speed = 0;
		mmtrMotionStoppedAtTarget = true;
		mmtrMotionArrivalControlSeq = mmtrControlApplySeq;
		vehicleExtraData.closeDoors();
		if (mmtrMotionStopOpenDoors) {
			vehicleExtraData.openDoors();
		}
		System.out.println("[MMTR-DRV] motion arrived at stop target " + Math.round(mmtrMotionStopTargetM * 100.0) / 100.0 + "m (doors " + (mmtrMotionStopOpenDoors ? "open" : "closed") + ")");
	}

	/** Service deceleration in internal units (m/ms per ms); falls back to the VED value scaled to SI. */
	private double mmtrMotionServiceDecelPerMs() {
		final double siMps2 = mmtrConsistType != null ? mmtrConsistType.getServiceBrakeDecelerationMps2() : vehicleExtraData.getDeceleration() * 1000.0;
		return siMps2 * 1e-6;
	}

	/** Enables/disables the MMTR explicit control path (used by the future input layer). */
	public void setMmtrManualOverride(boolean enabled) { mmtrManualOverride = enabled; }

	/**
	 * Applies an explicit separated ControlState from the MMTR input layer (throttle/brake/
	 * reverser/axes), without a driver identity (legacy no-identity path for tests/tools).
	 */
	public void applyMmtrControl(ControlState controlState) {
		applyMmtrControl(controlState, null);
	}

	/**
	 * Applies an explicit separated ControlState from the MMTR input layer on behalf of
	 * {@code driverUuid}. Enables the explicit control path and records the driver occupation
	 * lock. Passing {@code null} control releases the override.
	 */
	public void applyMmtrControl(@Nullable ControlState controlState, @Nullable UUID driverUuid) {
		if (controlState == null) {
			releaseMmtrManualOverride();
			return;
		}
		final boolean wasOverride = mmtrManualOverride;
		mmtrActiveControl = controlState.copy();
		// Server-authoritative input guard: clamp whatever the client sent before storing/mirroring.
		MmtrDriveAccess.sanitize(mmtrActiveControl);
		mmtrDriverUuid = driverUuid;
		mmtrManualOverride = true;
		if (!wasOverride && driverUuid != null) {
			System.out.println("[MMTR-DRV] driver=" + driverUuid + " engaged override T" + controlState.getThrottleNotch() + " B" + controlState.getBrakeNotch() + " R" + controlState.getReverser());
		}
		mmtrControlApplySeq++;
	}

	/** Releases the MMTR explicit override (occupation lock) and neutralises the legacy HUD power. */
	public void releaseMmtrManualOverride() {
		if (!mmtrManualOverride && !mmtrActive) {
			return;
		}
		final UUID releasedDriver = mmtrDriverUuid;
		mmtrActiveControl = null;
		mmtrManualOverride = false;
		mmtrDriverUuid = null;
		mmtrActive = false;
		mmtrMode = "";
		mmtrDriver = "";
		vehicleExtraData.setPowerLevel(0);
		if (releasedDriver != null) {
			System.out.println("[MMTR-DRV] driver=" + releasedDriver + " released override (auto)");
		}
	}

	/** @return true when this vehicle runs in live Motion-Core mode (L3). */
	public boolean isMmtrMotion() {
		return mmtrMotionWalker != null;
	}

	/** @return the live Motion-Core walker when this vehicle runs in motion mode, else {@code null}. */
	@Nullable
	public MmtrMotionWalker getMmtrMotionWalker() {
		return mmtrMotionWalker;
	}

	/**
	 * MMTR (L3, server): arms a precise stop for the current motion run: the vehicle auto
	 * service-brakes and comes to rest with its head exactly at {@code cumulativeDistanceM} (metres
	 * from the run start, i.e. walker distance space), opens the doors when {@code openDoors}, and
	 * holds there until a fresh control is applied (see {@link #applyMmtrControl}). Pass -1 to clear
	 * (free run). Only meaningful while {@link #isMmtrMotion()}.
	 */
	public void setMmtrMotionStopTarget(double cumulativeDistanceM, boolean openDoors) {
		if (isClientside || mmtrMotionWalker == null) {
			return;
		}
		final boolean wasStoppedAtTarget = mmtrMotionStoppedAtTarget;
		mmtrMotionStopTargetM = cumulativeDistanceM;
		mmtrMotionStopOpenDoors = openDoors;
		mmtrMotionStoppedAtTarget = false;
		mmtrMotionArrivalControlSeq = -1;
		// Auto step-run: arming the next stop target while stopped = depart automatically (the task
		// owns the dwell time and re-arms when it is done). Manual holds still need a fresh control.
		if (mmtrMotionAuto && wasStoppedAtTarget && cumulativeDistanceM >= 0) {
			vehicleExtraData.closeDoors();
			System.out.println("[MMTR-DRV] motion auto-departing to next stop target " + Math.round(cumulativeDistanceM * 100.0) / 100.0 + "m");
		}
	}

	/** @return true when the vehicle is stopped exactly at its armed motion stop target. */
	public boolean isMmtrMotionStoppedAtTarget() {
		return mmtrMotionStoppedAtTarget;
	}

	/** @return true when the vehicle runs unmanned (auto step-run) in motion mode. */
	public boolean isMmtrMotionAuto() {
		return mmtrMotionAuto;
	}

	/**
	 * MMTR (L3, server): enables/disables the unmanned auto step-run for this motion vehicle. While
	 * enabled, arming a stop target drives the vehicle to it automatically; arming the next target
	 * while stopped departs automatically. A manual override (cab driver) always wins while active.
	 */
	public void setMmtrMotionAuto(boolean auto) {
		if (isClientside) {
			return;
		}
		mmtrMotionAuto = auto;
		System.out.println("[MMTR-DRV] motion auto run " + (auto ? "enabled" : "disabled"));
	}

	/**
	 * MMTR (L3, server-only): switches this vehicle to live Motion-Core run mode. The walker becomes
	 * the motion authority: every tick the vehicle advances it by the physically integrated distance;
	 * the walker crosses nodes by the CURRENT turnout/task state (an unset fork halts and waits, never
	 * auto), and railProgress/render/occupancy follow the walker plus its growing leg shadow. The
	 * legacy baked-path state machine is bypassed while engaged. Call with the walker seeded on the
	 * rail the consist stands on. Clientside mirrors must never engage this mode.
	 */
	public void engageMmtrMotion(@Nullable MmtrMotionWalker walker) {
		if (isClientside) {
			log.warn("Vehicle#engageMmtrMotion is server-side only; ignoring on clientside mirror");
			return;
		}
		releaseMmtrManualOverride();
		mmtrMotionWalker = walker;
		mmtrMotionLegCount = 0;
		mmtrMotionLegs.clear();
		mmtrProtection = false;
		mmtrProtectionLockRemaining = 0;
		atoOverride = false;
		vehicleExtraData.closeDoors();
		departureIndex = -1;
		sidingDepartureTime = -1;
		reversed = false;
		speed = 0;
		mmtrMotionStopTargetM = -1;
		mmtrMotionStoppedAtTarget = false;
		mmtrMotionStopOpenDoors = false;
		mmtrMotionArrivalControlSeq = -1;
		if (walker == null) {
			railProgress = vehicleExtraData.getDefaultPosition();
		} else {
			refreshMmtrMotionLegs();
			mmtrMotionLegCount = walker.legCount();
			railProgress = walker.distanceM();
		}
	}

	/** Rebuild the motion leg shadow from the walker's recorded legs (cumulative PathData). */
	private void refreshMmtrMotionLegs() {
		mmtrMotionLegs.clear();
		if (mmtrMotionWalker != null) {
			mmtrMotionLegs.addAll(mmtrMotionWalker.buildLegs());
		}
	}

	/** @return the uuid currently holding the MMTR explicit override, or {@code null} */
	@Nullable
	public UUID getMmtrDriverUuid() {
		return mmtrDriverUuid;
	}

	public boolean isMmtrManualOverride() {
		return mmtrManualOverride;
	}

	/** @return whether this vehicle currently holds an explicit MMTR manual override (server). */
	public boolean isMmtrOverrideActive() {
		return mmtrManualOverride;
	}

	/**
	 * Server-authoritative driver check: may {@code uuid} take/keep MMTR control right now?
	 * A {@code null} uuid keeps the legacy semantic of "some cab driver is present" so older
	 * no-identity callers (tests/tools) keep working.
	 */
	public boolean canTakeMmtrControl(@Nullable UUID uuid) {
		if (uuid == null) {
			final boolean[] anyDriverRiding = {false};
			vehicleExtraData.iterateRidingEntities(vehicleRidingEntity -> {
				if (vehicleRidingEntity.isDriver()) {
					anyDriverRiding[0] = true;
				}
			});
			return anyDriverRiding[0];
		}
		final boolean senderIsRidingDriver = hasMmtrDriverRiding(uuid);
		final boolean holderStillRiding = mmtrDriverUuid == null || hasMmtrDriverRiding(mmtrDriverUuid);
		return MmtrDriveAccess.canControl(senderIsRidingDriver, mmtrManualOverride, mmtrDriverUuid, uuid, holderStillRiding);
	}

	private boolean hasMmtrDriverRiding(UUID uuid) {
		final boolean[] found = {false};
		vehicleExtraData.iterateRidingEntities(vehicleRidingEntity -> {
			if (vehicleRidingEntity.isDriver() && vehicleRidingEntity.uuid.equals(uuid)) {
				found[0] = true;
			}
		});
		return found[0];
	}

	/** True when explicit MMTR control requests traction (used to allow departing from a stop). */
	public boolean isMmtrRequestingPower() {
		return mmtrManualOverride && mmtrActiveControl != null && mmtrActiveControl.getThrottleNotch() > 0;
	}

	/**
	 * Lazily resolves the MMTR consist type + controller.
	 * <ul>
	 *   <li>Server: from the simulator's server-side policy (ConsistTypeRegistry).</li>
	 *   <li>Client: rebuilt from the mmtr parameters mirrored in the latest vehicle snapshot, so
	 *       every client simulates exactly the same longitudinal physics as the server
	 *       (identical model, authoritative ControlState, seeded air-brake state).</li>
	 * </ul>
	 * Returns true when MMTR control is active (never for DEFAULT mode).
	 */
	private boolean tryInitMmtrController() {
		if (mmtrDriveController != null) {
			return true;
		}
		if (!isClientside) {
			if (data instanceof Simulator simulator && simulator.mmtrConsistTypes != null && simulator.mmtrDefaultConsistTypeId != null) {
				mmtrConsistType = simulator.mmtrConsistTypes.get(simulator.mmtrDefaultConsistTypeId);
			}
		} else {
			mmtrConsistType = createMirrorConsistTypeFromSync();
		}
		if (mmtrConsistType != null) {
			mmtrDriveController = switch (mmtrConsistType.getControlMode()) {
				case NOTCHED -> new org.mtr.core.mmtr.NotchedDriveController();
				case STEPLESS -> new org.mtr.core.mmtr.SteplessDriveController();
				case AIR_BRAKE -> new org.mtr.core.mmtr.AirBrakeController();
				default -> null;
			};
			if (isClientside && mmtrDriveController instanceof final org.mtr.core.mmtr.AirBrakeController airBrakeController) {
				// Seed the fresh mirror controller with the authoritative air state from the snapshot.
				airBrakeController.setState(mmtrPipePressure, mmtrBrakeCylinderPressure);
			}
		}
		return mmtrDriveController != null;
	}

	/** Client-side: rebuild the ConsistType mirrored in the latest vehicle snapshot. */
	private @Nullable ConsistType createMirrorConsistTypeFromSync() {
		if (mmtrMode == null || mmtrMode.isEmpty()) {
			return null;
		}
		final ConsistType.ControlMode mode;
		try {
			mode = ConsistType.ControlMode.valueOf(mmtrMode);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (mode == ConsistType.ControlMode.DEFAULT) {
			return null;
		}
		return new ConsistType(
			"mirror", "", mode,
			(int) mmtrPowerNotches, (int) mmtrBrakeNotches,
			mmtrMaxSpeedKmh, mmtrTractionAccelerationMps2, mmtrServiceBrakeDecelerationMps2,
			mmtrEmergencyDecelerationMps2, mmtrTractionBreakpointKmh, mmtrResistanceA, mmtrResistanceB, mmtrResistanceC,
			mmtrAirPipeChargeRatePerSecond, mmtrAirPipeDischargeRatePerSecond,
			mmtrAirBrakeApplyRatePerSecond, mmtrAirBrakeReleaseRatePerSecond, mmtrManualMaxSpeedKmh,
			mmtrMassRatio
		);
	}

	/** Client-side: rebuild the authoritative ControlState from the mirrored snapshot fields. */
	private ControlState createMirrorControlStateFromSync() {
		return new ControlState()
			.setThrottleNotch((int) mmtrThrottleNotch).setBrakeNotch((int) mmtrBrakeNotch).setReverser((int) mmtrReverser)
			.setThrottleAxis(mmtrThrottleAxis).setBrakeAxis(mmtrBrakeAxis).setEmergency(mmtrEmergency);
	}

	/**
	 * Returns (and lazily builds) the per-car composition used by the AIR_BRAKE model: one unit
	 * per vehicle car, all sharing the consist's ConsistType. Later (mixed formations) individual
	 * cars may get their own ConsistType / powered flag.
	 */
	@Nullable
	private MmtrComposition getMmtrComposition() {
		if (mmtrComposition == null && mmtrConsistType != null) {
			final MmtrComposition composition = new MmtrComposition();
			final int carCount = Math.max(1, vehicleExtraData.immutableVehicleCars.size());
			for (int i = 0; i < carCount; i++) {
				composition.couple(new MmtrComposition.Unit("car" + i, mmtrConsistType, true));
			}
			mmtrComposition = composition;
		}
		return mmtrComposition;
	}

	/**
	 * Server-side: writes the current MMTR drive state + consist parameters into the synced
	 * vehicle fields so clients can mirror the physics and show the authoritative state.
	 */
	private void updateMmtrSyncFields() {
		mmtrActive = mmtrManualOverride && mmtrConsistType != null;
		mmtrMode = mmtrConsistType == null ? "" : mmtrConsistType.getControlMode().name();
		mmtrDriver = mmtrDriverUuid == null ? "" : mmtrDriverUuid.toString();
		if (mmtrActiveControl != null) {
			mmtrThrottleNotch = mmtrActiveControl.getThrottleNotch();
			mmtrBrakeNotch = mmtrActiveControl.getBrakeNotch();
			mmtrReverser = mmtrActiveControl.getReverser();
			mmtrThrottleAxis = mmtrActiveControl.getThrottleAxis();
			mmtrBrakeAxis = mmtrActiveControl.getBrakeAxis();
			mmtrEmergency = mmtrActiveControl.isEmergency();
		}
		if (mmtrConsistType != null) {
			mmtrPowerNotches = mmtrConsistType.getPowerNotches();
			mmtrBrakeNotches = mmtrConsistType.getBrakeNotches();
			mmtrMaxSpeedKmh = mmtrConsistType.getMaxSpeedKmh();
			mmtrManualMaxSpeedKmh = mmtrConsistType.getManualMaxSpeedMetersPerSecond() * 3.6;
			mmtrTractionAccelerationMps2 = mmtrConsistType.getTractionAccelerationMps2();
			mmtrServiceBrakeDecelerationMps2 = mmtrConsistType.getServiceBrakeDecelerationMps2();
			mmtrEmergencyDecelerationMps2 = mmtrConsistType.getEmergencyDecelerationMps2();
			mmtrTractionBreakpointKmh = mmtrConsistType.getTractionBreakpointKmh();
			mmtrResistanceA = mmtrConsistType.getResistanceA();
			mmtrResistanceB = mmtrConsistType.getResistanceB();
			mmtrResistanceC = mmtrConsistType.getResistanceC();
			mmtrAirPipeChargeRatePerSecond = mmtrConsistType.getAirPipeChargeRatePerSecond();
			mmtrAirPipeDischargeRatePerSecond = mmtrConsistType.getAirPipeDischargeRatePerSecond();
			mmtrAirBrakeApplyRatePerSecond = mmtrConsistType.getAirBrakeApplyRatePerSecond();
			mmtrAirBrakeReleaseRatePerSecond = mmtrConsistType.getAirBrakeReleaseRatePerSecond();
			mmtrMassRatio = mmtrConsistType.getMassRatio();
		}
		if (mmtrDriveController instanceof final org.mtr.core.mmtr.AirBrakeController airBrakeController) {
			mmtrPipePressure = airBrakeController.getPipePressure();
			mmtrBrakeCylinderPressure = airBrakeController.getBrakeCylinderPressure();
		}
	}

	/** Server-side: (re)evaluate overrun/SPAD protection ahead of the {@code stoppingPoint}. */
	private boolean evaluateMmtrProtection(double stoppingPoint) {
		if (mmtrProtection) {
			return true;
		}
		// Same emergency envelope as the legacy path (Siding.MAX_ACCELERATION * 2, m/ms^2).
		if (MmtrProtection.requiresProtection(speed, stoppingPoint - railProgress, Siding.MAX_ACCELERATION * 2)) {
			mmtrProtection = true;
			mmtrProtectionLockRemaining = MmtrProtection.LOCK_MILLIS;
			System.out.println("[MMTR-DRV] overrun protection engaged (past stopping point or cannot stop in time)");
			return true;
		}
		return false;
	}

	/** Public getters for the client HUD / mirror overlay (values from the last snapshot). */
	public boolean isMmtrActiveFromSync() { return mmtrActive; }
	public String getMmtrModeFromSync() { return mmtrMode == null ? "" : mmtrMode; }
	public String getMmtrDriverFromSync() { return mmtrDriver == null ? "" : mmtrDriver; }
	public int getMmtrThrottleFromSync() { return (int) mmtrThrottleNotch; }
	public int getMmtrBrakeFromSync() { return (int) mmtrBrakeNotch; }
	public int getMmtrReverserFromSync() { return (int) mmtrReverser; }
	public boolean isMmtrProtectionFromSync() { return mmtrProtection; }
	public boolean isMmtrEmergencyFromSync() { return mmtrEmergency; }

	private void simulateMoving(long millisElapsed, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, int currentIndex) {
		// Tracks the distance
		final double stoppingPoint;
		// Tracks the speed
		final double speedTarget;
		// Tracks the acceleration
		final int powerLevel;

		if (isClientside) {
			stoppingPoint = vehicleExtraData.getStoppingPoint();
			speedTarget = vehicleExtraData.getSpeedTarget();
			powerLevel = vehicleExtraData.getPowerLevel();
		} else {
			lastMovementMillis = data.getCurrentMillis();
			final double safeStoppingDistance = 0.5 * speed * speed / vehicleExtraData.getDeceleration() * (isCurrentlyManual() ? POWER_LEVEL_RATIO : 1); // when on manual mode, check for blocked rails on the lowest deceleration (B1)
			final double hardStoppingDistance = 0.5 * speed * speed / (Siding.MAX_ACCELERATION * 2);

			// Set the stopping point
			if (transportMode.continuousMovement) {
				stoppingPoint = Double.MAX_VALUE;
				if (vehicleExtraData.immutablePath.get(currentIndex).getDwellTime() > 0) {
					vehicleExtraData.openDoors();
				} else {
					vehicleExtraData.closeDoors();
				}
			} else {
				final double pathStoppingPoint = getPathStoppingPoint();
				if (stoppingCooldown > 0) {
					stoppingPoint = Math.min(vehicleExtraData.getStoppingPoint(), pathStoppingPoint);
				} else {
					final double railBlockedDistance = railBlockedDistance(currentIndex, railProgress, safeStoppingDistance, vehiclePositions, true, false);
					if (railBlockedDistance < 0) {
						stoppingPoint = pathStoppingPoint;
					} else {
						// Set the stopping point to the blocked position
						stoppingPoint = Math.min(railBlockedDistance + railProgress, pathStoppingPoint);
						stoppingCooldown = 1000;
					}
				}
			}

			// Set the power level and speed target
			if (stoppingPoint - railProgress < (isCurrentlyManual() ? hardStoppingDistance : safeStoppingDistance)) {
				// If blocked ahead, slow down (using normal deceleration for automatic and emergency brake for manual)
				speedTarget = -1;
				powerLevel = Math.min(vehicleExtraData.getPowerLevel(), isCurrentlyManual() && hardStoppingDistance > 2 ? -MAX_POWER_LEVEL - 1 : -POWER_LEVEL_RATIO);
				atoOverride = true;
			} else {
				if (isCurrentlyManual()) {
					if (speed > vehicleExtraData.getMaxManualSpeed()) {
						// Slow down if above the max manual speed
						speedTarget = vehicleExtraData.getMaxManualSpeed();
						powerLevel = -POWER_LEVEL_RATIO;
					} else {
						powerLevel = vehicleExtraData.getPowerLevel();
						speedTarget = powerLevel > 0 ? vehicleExtraData.getMaxManualSpeed() : (powerLevel < 0 ? 0 : speed);
					}
				} else {
					final double upcomingSlowerSpeed = Siding.getUpcomingSlowerSpeed(vehicleExtraData.immutablePath, currentIndex, railProgress, speed, vehicleExtraData.getDeceleration());
					if (upcomingSlowerSpeed >= 0 && upcomingSlowerSpeed < speed) {
						speedTarget = upcomingSlowerSpeed * deviationSpeedAdjustment;
						powerLevel = -POWER_LEVEL_RATIO;
					} else {
						speedTarget = vehicleExtraData.immutablePath.get(currentIndex).getSpeedLimitMetersPerMillisecond() * deviationSpeedAdjustment;
						powerLevel = Double.compare(speedTarget, speed) * POWER_LEVEL_RATIO;
					}
				}
			}

			// Sync to the client
			vehicleExtraData.setStoppingPoint(stoppingPoint);
			vehicleExtraData.setSpeedTarget(speedTarget);
			vehicleExtraData.setPowerLevel(powerLevel);
		}

		// Distance covered inside the MMTR sub-stepped integration (set when the mmtr branch runs).
		double mmtrDistanceTravelled = -1;

		// Set speed
		if (speedTarget < 0) {
			final double stoppingDistance = stoppingPoint - railProgress;
			speed = stoppingDistance <= 0 ? Siding.ACCELERATION_DEFAULT : Math.max(speed - (0.5 * speed * speed / stoppingDistance) * millisElapsed, Siding.ACCELERATION_DEFAULT);
		} else if (tryInitMmtrController() && mmtrConsistType != null && mmtrDriveController != null && (!isClientside ? mmtrManualOverride && isCurrentlyManual() : mmtrActive)) {
			// MMTR explicit control model: drive from the separated ControlState sent by the input
			// layer (throttle notch 0..N, brake notch, axes). No legacy single-handle mapping.
			// Mirrored client-side too (same controller, same authoritative ControlState + seeded
			// air-brake state from the snapshot) so every client simulates identical physics.
			final boolean mmtrProtectionNow;
			final ControlState mmtrControl;
			if (!isClientside) {
				mmtrProtectionNow = evaluateMmtrProtection(stoppingPoint);
				mmtrControl = mmtrActiveControl == null ? new ControlState() : mmtrActiveControl;
			} else {
				mmtrProtectionNow = mmtrProtection;
				mmtrControl = createMirrorControlStateFromSync();
			}
			// Fixed sub-step integration shared verbatim by the server and mirrored clients, so
			// the physics stay identical (and stiff dynamics stable) regardless of dt. During
			// overrun/SPAD protection the provider always requests emergency braking.
			final boolean mmtrProtectionActive = mmtrProtectionNow;
			final ConsistType mmtrType = mmtrConsistType;
			final ControlState mmtrState = mmtrControl;
			// AIR_BRAKE driving uses the per-car composition (one pipe/cylinder per car, train-pipe
			// equalisation along the consist). Mirrored clients seed their composition from the
			// synced mmtrAirState whenever a fresh snapshot arrives.
			final boolean useCompositionAir = mmtrType.getControlMode() == ConsistType.ControlMode.AIR_BRAKE;
			final MmtrComposition mmtrCompositionNow = useCompositionAir ? getMmtrComposition() : null;
			if (isClientside && mmtrCompositionNow != null && !mmtrAirState.isEmpty() && !mmtrLastAirSeed.equals(mmtrAirState)) {
				mmtrCompositionNow.applyAirStateString(mmtrAirState);
				mmtrLastAirSeed = mmtrAirState;
			}
			final double mmtrStartSpeedSi = MmtrSupport.internalSpeedToSi(speed);
			final MmtrComposition compForIntegration = mmtrCompositionNow;
			final ConsistDynamics.SpeedDistance mmtrResult = ConsistDynamics.advance(mmtrStartSpeedSi, mmtrType, millisElapsed, MMTR_INTEGRATION_SUB_STEP_MS, (siSpeed, stepMillis) -> {
				if (mmtrProtectionActive && siSpeed > 0) {
					return new DriveOutput(-mmtrType.getEmergencyDecelerationMps2(), true, true, 0, 1);
				}
				if (compForIntegration != null) {
					return compForIntegration.stepAir(mmtrState, siSpeed, stepMillis);
				}
				return mmtrDriveController.compute(mmtrState, mmtrType, siSpeed, stepMillis);
			});
			final double mmtrSpeed = MmtrSupport.siSpeedToInternal(mmtrResult.speedMetersPerSecond);
			mmtrDistanceTravelled = mmtrResult.distanceMeters;
			if (compForIntegration != null) {
				// Publish per-car air state (mirror seed) + averages for the legacy HUD fields.
				mmtrAirState = MmtrComposition.encodeAirStates(compForIntegration);
				mmtrPipePressure = compForIntegration.averagePipePressure();
				mmtrBrakeCylinderPressure = compForIntegration.averageCylinderPressure();
			}
			if (!isClientside) {
				// Keep the legacy HUD in sync: show throttle positive, brake negative, coast at zero
				// (emergency during protection). Also refresh the mirrored snapshot fields.
				if (mmtrProtectionNow) {
					vehicleExtraData.setPowerLevel(-MAX_POWER_LEVEL - 1);
				} else {
					vehicleExtraData.setPowerLevel(mmtrControl.getThrottleNotch() > 0 ? mmtrControl.getThrottleNotch()
						: mmtrControl.getBrakeNotch() > 0 ? -mmtrControl.getBrakeNotch() : 0);
				}
				updateMmtrSyncFields();
				if (speed != mmtrSpeed || mmtrDistanceTravelled > 0) {
					System.out.println("[MMTR-DRV] mode=" + mmtrConsistType.getControlMode() + " throttle=" + mmtrControl.getThrottleNotch() + " brake=" + mmtrControl.getBrakeNotch() + " speed=" + speed + "->" + mmtrSpeed + " dist=" + mmtrResult.distanceMeters + " prot=" + mmtrProtectionNow);
				}
			}
			speed = mmtrSpeed;
		} else {
			if (powerLevel > 0) {
				speed = Math.min(speed + vehicleExtraData.getAcceleration() * powerLevel / POWER_LEVEL_RATIO * millisElapsed, speedTarget);
			} else if (powerLevel < 0) {
				speed = Math.max(speed + (powerLevel < -MAX_POWER_LEVEL ? -Siding.MAX_ACCELERATION * 2 : vehicleExtraData.getDeceleration() * powerLevel / POWER_LEVEL_RATIO) * millisElapsed, speedTarget);
			} else {
				speed = speedTarget;
			}
		}

		// Set rail progress (mmtr branch carries its own sub-stepped trapezoidal distance)
		railProgress += mmtrDistanceTravelled >= 0 ? mmtrDistanceTravelled : speed * millisElapsed;
		if (railProgress >= stoppingPoint) {
			railProgress = stoppingPoint;
			speed = 0;
			vehicleExtraData.setSpeedTarget(0);
			updateDeviation();
			if (!isClientside) {
				atoOverride = false;
				vehicleExtraData.setPowerLevel(Math.min(vehicleExtraData.getPowerLevel(), -1));
			}
		} else if (vehicleExtraData.getRepeatIndex2() > 0 && railProgress >= vehicleExtraData.getTotalDistance()) {
			railProgress = vehicleExtraData.immutablePath.get(vehicleExtraData.getRepeatIndex1()).getStartDistance() + railProgress - vehicleExtraData.getTotalDistance();
		}
	}

	/**
	 * Gets the stopping point of the path (a platform, a turnback, or the end of the route).
	 *
	 * @return the position (not the index) of the stop
	 */
	private double getPathStoppingPoint() {
		final double stoppingPointByStoppingIndex;
		final PathData pathDataAto = Utilities.getElement(vehicleExtraData.immutablePath, (int) nextStoppingIndexAto);
		final boolean pastAtoStoppingPoint = pathDataAto != null && railProgress > pathDataAto.getEndDistance();
		final int nextStoppingIndex = (int) (isCurrentlyManual() || pastAtoStoppingPoint ? nextStoppingIndexManual : nextStoppingIndexAto);

		if (nextStoppingIndex >= vehicleExtraData.immutablePath.size() - 1) {
			// Set the stopping point to the end of the whole journey
			stoppingPointByStoppingIndex = vehicleExtraData.getTotalDistance() - (vehicleExtraData.getRepeatIndex2() > 0 ? 0 : (vehicleExtraData.getRailLength() - vehicleExtraData.getTotalVehicleLength()) / 2);
		} else {
			// Set the stopping point to the next expected platform or turnback
			stoppingPointByStoppingIndex = vehicleExtraData.immutablePath.get(nextStoppingIndex).getEndDistance();
		}

		if (pastAtoStoppingPoint) {
			setNextStoppingIndex();
		}

		return stoppingPointByStoppingIndex;
	}

	// public isCurrentlyManual() defined above; kept private once no longer used? remove entirely


	private void setNextStoppingIndex() {
		nextStoppingIndexAto = vehicleExtraData.immutablePath.size() - 1;
		nextStoppingIndexManual = nextStoppingIndexAto;
		vehicleExtraData.setStoppingPoint(vehicleExtraData.getTotalDistance());
		for (int i = Utilities.getIndexFromConditionalList(vehicleExtraData.immutablePath, railProgress); i < vehicleExtraData.immutablePath.size(); i++) {
			final PathData pathData = vehicleExtraData.immutablePath.get(i);
			if (pathData.getDwellTime() > 0) {
				if (vehicleExtraData.getIsManualAllowed()) {
					nextStoppingIndexAto = Math.min(nextStoppingIndexAto, i);
					// Find the next turnback
					if (i < vehicleExtraData.immutablePath.size() - 1 && vehicleExtraData.immutablePath.get(i + 1).isOppositeRail(pathData)) {
						nextStoppingIndexManual = i;
						vehicleExtraData.setStoppingPoint(pathData.getEndDistance());
						break;
					}
				} else {
					nextStoppingIndexAto = i;
					nextStoppingIndexManual = i;
					vehicleExtraData.setStoppingPoint(pathData.getEndDistance());
					break;
				}
			}
		}
	}

	/**
	 * Motion-mode occupancy footprint over the walker's growing leg shadow (same blocked-bounds
	 * bookkeeping as {@link #writeVehiclePositions}, minus signal reservations and client pushes,
	 * which motion mode does not handle yet). Other vehicles block on this footprint through the
	 * shared vehiclePositions maps.
	 */
	private void writeMmtrMotionVehiclePositions(Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> vehiclePositions) {
		if (vehiclePositions == null || mmtrMotionLegs.isEmpty() || !getIsOnRoute()) {
			return;
		}
		int index = indexInMmtrMotionLegs(railProgress);
		while (index >= 0) {
			final PathData pathData = mmtrMotionLegs.get(index);
			if (railProgress - vehicleExtraData.getTotalVehicleLength() > pathData.getEndDistance()) {
				break;
			}
			if (index > 0) {
				final DoubleDoubleImmutablePair blockedBounds = getBlockedBounds(pathData, railProgress - vehicleExtraData.getTotalVehicleLength(), railProgress - 0.01);
				if (blockedBounds.rightDouble() - blockedBounds.leftDouble() > 0.01) {
					final Position position1 = pathData.getOrderedPosition1();
					final Position position2 = pathData.getOrderedPosition2();
					Data.put(vehiclePositions, position1, position2, vehiclePosition -> {
						final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
						newVehiclePosition.addSegment(blockedBounds.leftDouble(), blockedBounds.rightDouble(), id);
						return newVehiclePosition;
					}, Object2ObjectAVLTreeMap::new);
				}
			}
			index--;
		}
	}

	/** Index of the leg whose cumulative range contains {@code progress} (last leg when beyond). */
	private int indexInMmtrMotionLegs(double progress) {
		for (int i = 0; i < mmtrMotionLegs.size(); i++) {
			if (mmtrMotionLegs.get(i).getEndDistance() > progress) {
				return i;
			}
		}
		return Math.max(0, mmtrMotionLegs.size() - 1);
	}

	/** Motion-mode leg shadow, or the legacy baked path when not in motion mode (single lookup chokepoint). */
	private java.util.List<PathData> motionOrLegacyPath() {
		return mmtrMotionWalker == null ? vehicleExtraData.immutablePath : mmtrMotionLegs;
	}

	/**
	 * Indicate which portions of each path segment are occupied by this vehicle. Also check if the vehicle needs to send a socket update:
	 * <ul>
	 * <li>Entered a client's view radius</li>
	 * <li>Left a client's view radius</li>
	 * <li>Started moving</li>
	 * <li>New stopping index or blocked rail</li>
	 * </ul>
	 */
	private void writeVehiclePositions(int currentIndex, Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> vehiclePositions) {
		final @Nullable Position[] minMaxPositions = {null, null};
		int index = currentIndex;

		while (index >= 0) {
			final PathData pathData = vehicleExtraData.immutablePath.get(index);
			final Position position1 = pathData.getOrderedPosition1();
			final Position position2 = pathData.getOrderedPosition2();
			minMaxPositions[0] = Position.getMin(minMaxPositions[0], Position.getMin(position1, position2));
			minMaxPositions[1] = Position.getMax(minMaxPositions[1], Position.getMax(position1, position2));

			if (railProgress - vehicleExtraData.getTotalVehicleLength() > pathData.getEndDistance()) {
				break;
			}

			if (!transportMode.continuousMovement) {
				final DoubleDoubleImmutablePair blockedBounds = getBlockedBounds(pathData, railProgress - vehicleExtraData.getTotalVehicleLength(), railProgress - 0.01);
				if (blockedBounds.rightDouble() - blockedBounds.leftDouble() > 0.01) {
					if (getIsOnRoute() && index > 0) {
						Data.put(vehiclePositions, position1, position2, vehiclePosition -> {
							final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
							newVehiclePosition.addSegment(blockedBounds.leftDouble(), blockedBounds.rightDouble(), id);
							return newVehiclePosition;
						}, Object2ObjectAVLTreeMap::new);
						pathData.isSignalBlocked(id, Rail.BlockReservation.CURRENTLY_RESERVE);
					}
				}
			}

			index--;
		}

		if (siding != null) {
			if (siding.area != null && data instanceof final Simulator simulator) {
				final boolean needsUpdate = vehicleExtraData.checkForUpdate();
				// MMTR: clients now mirror the same mmtr physics from the snapshot fields, so the
				// stock dirty-driven sync cadence (needsUpdate on state/power changes) is accurate
				// enough — no per-tick authoritative push hack required anymore.
				// TODO for continuous movement, maybe only send the path once rather than sending the entire path for each vehicle
				final int pathUpdateIndex = transportMode.continuousMovement ? 0 : Math.max(0, index + 1);
				simulator.clients.forEach(client -> {
					final Position position = client.getPosition();
					final double updateRadius = client.getUpdateRadius();
					if ((minMaxPositions[0] == null || minMaxPositions[1] == null) ? siding.area.inArea(position, updateRadius) : Utilities.isBetween(position, minMaxPositions[0], minMaxPositions[1], updateRadius) || !closeToDepot() && vehicleExtraData.hasRidingEntity(client.uuid)) {
						client.update(this, needsUpdate, pathUpdateIndex);
					}
				});
			}

			vehicleExtraData.setRoutePlatformInfo(siding.area, currentIndex);
		}
	}

	/**
	 * Checks if the rails ahead are clear up to a certain point (in terms of other vehicles or signals).
	 *
	 * @return the distance until the rail is blocked or -1 if there is nothing in front
	 */
	private double railBlockedDistance(int currentIndex, double checkRailProgress, double checkDistance, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, boolean reserveRail, boolean secondPass) {
		int index = currentIndex;

		while (vehiclePositions != null && index < vehicleExtraData.immutablePath.size()) {
			final PathData pathData = vehicleExtraData.immutablePath.get(index);
			final double checkRailProgressEnd = checkRailProgress + checkDistance + transportMode.stoppingSpace;

			if (pathData.getStartDistance() >= checkRailProgressEnd) {
				return -1;
			}

			final double blockedStartOffset = Math.max(0, pathData.getStartDistance() - checkRailProgress);

			if (checkAndBlockSignal(index, vehiclePositions, reserveRail, secondPass)) {
				return blockedStartOffset;
			} else if (Utilities.isIntersecting(pathData.getStartDistance(), pathData.getEndDistance(), checkRailProgress, checkRailProgressEnd)) {
				final DoubleDoubleImmutablePair blockedBounds = getBlockedBounds(pathData, checkRailProgress, checkRailProgressEnd);
				for (int i = 0; i < 2; i++) {
					final VehiclePosition vehiclePosition = Data.tryGet(vehiclePositions.get(i), pathData.getOrderedPosition1(), pathData.getOrderedPosition2());
					if (vehiclePosition != null) {
						final double closestOverlap = vehiclePosition.getClosestOverlap(blockedBounds.leftDouble(), blockedBounds.rightDouble(), pathData.reversePositions, id);
						if (closestOverlap >= 0) {
							return Math.max(0, blockedStartOffset + closestOverlap - transportMode.stoppingSpace);
						}
					}
				}
			}

			index++;
		}

		return -1;
	}

	/**
	 * If a signal block is encountered, first check if the path after the entire block is clear. If so, reserve the signal block.
	 *
	 * @return if the vehicle should stop
	 */
	private boolean checkAndBlockSignal(int currentIndex, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions, boolean reserveRail, boolean secondPass) {
		final PathData firstPathData = vehicleExtraData.immutablePath.get(currentIndex);

		if (secondPass) {
			return firstPathData.isSignalBlocked(id, Rail.BlockReservation.DO_NOT_RESERVE);
		} else {
			final IntAVLTreeSet signalColors = firstPathData.getSignalColors();
			int index = currentIndex + 1;

			while (!signalColors.isEmpty() && index < vehicleExtraData.immutablePath.size()) {
				final PathData pathData = vehicleExtraData.immutablePath.get(index);

				if (pathData.getSignalColors().intStream().noneMatch(signalColors::contains)) {
					// Only reserve the signal block after checking if the path after the signal block is clear, not before!
					final double railBlockedDistance = railBlockedDistance(index, pathData.getStartDistance(), vehicleExtraData.getTotalVehicleLength(), vehiclePositions, false, true);
					return railBlockedDistance >= 0 && railBlockedDistance < vehicleExtraData.getTotalVehicleLength() || firstPathData.isSignalBlocked(id, reserveRail ? Rail.BlockReservation.PRE_RESERVE : Rail.BlockReservation.DO_NOT_RESERVE);
				}

				index++;
			}

			return false;
		}
	}

	private long getTimeAlongRoute(double checkRailProgress) {
		// Subtract 1 from railProgress for rounding errors
		return siding == null ? 0 : (long) Math.floor(siding.getTimeAlongRoute(checkRailProgress - (speed == 0 ? 1 : 0)) + elapsedDwellTime);
	}

	private void updateDeviation() {
		deviation = transportMode.continuousMovement || siding == null ? 0 : Utilities.circularDifference(data.getCurrentMillis() - sidingDepartureTime, getTimeAlongRoute(railProgress), siding.getRepeatInterval(MILLIS_PER_DAY));
	}

	@Nullable
	private PositionAndTiltAngle getPositionAndTiltAngle(double value, DoubleArrayList overrideY) {
		final java.util.List<PathData> path = motionOrLegacyPath();
		final PathData pathData = Utilities.getElement(path, Utilities.getIndexFromConditionalList(path, value));
		if (pathData == null) {
			return null;
		} else {
			final PositionAndTiltAngle positionAndTiltAngle = pathData.getPositionAndTiltAngle(data, value - pathData.getStartDistance());
			if (transportMode == TransportMode.AIRPLANE && pathData.getSpeedLimitKilometersPerHour() == SidingPathFinder.AIRPLANE_SPEED && pathData.isDescending()) {
				if (overrideY.isEmpty()) {
					overrideY.add(positionAndTiltAngle.position.y());
					return positionAndTiltAngle;
				} else {
					return new PositionAndTiltAngle(new Vector(positionAndTiltAngle.position.x(), overrideY.getDouble(0), positionAndTiltAngle.position.z()), positionAndTiltAngle.tiltAngle);
				}
			} else {
				return positionAndTiltAngle;
			}
		}
	}

	private BogiePosition getBogiePositions(double value, DoubleArrayList overrideY) {
		final double lowerBound = railProgress - vehicleExtraData.getTotalVehicleLength();
		final double clampedValue = Utilities.clampSafe(value, lowerBound, railProgress);
		final double value1;
		final double value2;
		final double clamp = Utilities.clampSafe(Math.min(Math.abs(clampedValue - lowerBound), Math.abs(clampedValue - railProgress)), 0.1, 1);
		value1 = Utilities.clampSafe(clampedValue + (reversed ? -clamp : clamp), lowerBound, railProgress - 0.001);
		value2 = Utilities.clampSafe(clampedValue - (reversed ? -clamp : clamp), lowerBound, railProgress - 0.001);
		final PositionAndTiltAngle positionAndTiltAngle1 = getPositionAndTiltAngle(value1, overrideY);
		final PositionAndTiltAngle positionAndTiltAngle2 = getPositionAndTiltAngle(value2, overrideY);
		return positionAndTiltAngle1 == null || positionAndTiltAngle2 == null ? new BogiePosition(new PositionAndTiltAngle(new Vector(value1, 0, 0), 0), new PositionAndTiltAngle(new Vector(value2, 0, 0), 0)) : new BogiePosition(positionAndTiltAngle1, positionAndTiltAngle2);
	}

	private static DoubleDoubleImmutablePair getBlockedBounds(PathData pathData, double lowerRailProgress, double upperRailProgress) {
		final double distanceFromStart = Utilities.clampSafe(lowerRailProgress, pathData.getStartDistance(), pathData.getEndDistance()) - pathData.getStartDistance();
		final double distanceToEnd = pathData.getEndDistance() - Utilities.clampSafe(upperRailProgress, pathData.getStartDistance(), pathData.getEndDistance());
		return new DoubleDoubleImmutablePair(pathData.reversePositions ? distanceToEnd : distanceFromStart, pathData.getEndDistance() - pathData.getStartDistance() - (pathData.reversePositions ? distanceFromStart : distanceToEnd));
	}

	public record BogiePosition(PositionAndTiltAngle positionAndTiltAngle1, PositionAndTiltAngle positionAndTiltAngle2) {
	}

	public record PositionAndTiltAngle(Vector position, double tiltAngle) {
	}
}
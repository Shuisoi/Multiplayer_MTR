package org.mtr.mod.client;

import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntObjectImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectBooleanImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.Box;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.EntityHelper;
import org.mtr.mod.InitClient;
import org.mtr.mod.item.ItemDepotDriverKey;
import org.mtr.mod.item.ItemDriverKey;
import org.mtr.mod.packet.PacketUpdateVehicleRidingEntities;
import org.mtr.mod.render.RenderVehicleHelper;
import org.mtr.mod.screen.LiftSelectionScreen;

import javax.annotation.Nullable;

/**
 * Client-side "the player is inside a moving thing" state.
 *
 * <h2>⚠️ This file is mid-refactor. Read this before touching it.</h2>
 *
 * <p>The MMTR train CAB layer that used to live here is still DELETED (notes/185): the cab lock, the seat
 * pin, the driver key, the permissions seam and the G-key cab interaction have no replacement yet. What
 * is NOT deleted any more is train RIDING - {@code RenderVehicles} calls {@link #startRiding} and
 * {@link #movePlayer} again (restored 2026-09-18), so a player can board a train car through an open
 * doorway and be carried inside it. That restore was necessary rather than optional: while nobody could be
 * inside a train, every in-cab feature - the wiper above all - was untestable in game no matter how
 * correct its offline model was.</p>
 *
 * <p>So the split is now:</p>
 *
 * <ul>
 *   <li><b>Lifts (worked all along):</b> {@code RenderLifts} calls {@link #startRiding} when the player
 *       stands in an open lift doorway and {@link #movePlayer} every frame, and reads
 *       {@link #getRidingVehicleCarNumberAndOffset} to render the world relative to the player.</li>
 *   <li><b>Trains (riding restored; the cab layer is still absent):</b> {@code RenderVehicles} calls both
 *       again, so the player rides inside the car they boarded. What is still missing is the CAB itself -
 *       no driver key, no seat pin, no cab lock. {@code MmtrWindshield.driverOnBoard} answers "which cab
 *       am I in" from the RIDE state (which car of which vehicle), which is what the wiper needs and is
 *       deliberately not a claim that the cab layer exists.</li>
 * </ul>
 *
 * <h2>Why the shared state is kept rather than split yet</h2>
 *
 * <p>Lifts and trains share this state, which is how a train-side change kept breaking the lift side and
 * vice versa. The rebuild (notes/185 §6) still intends to give the train half its own session object
 * instead of reaching back in here. Until then, every train change made here has to be re-checked against
 * the lift path.</p>
 *
 * <h2>Ownership rule to preserve</h2>
 *
 * <p>This class is the ONLY writer of the riding coordinate. Anything that wants the player somewhere
 * else states an intent; it does not assign {@code ridingVehicleX/Y/Z} from outside.</p>
 */
public class VehicleRidingMovement {

	// ---- the riding state (lift-only writer at this checkpoint) ------------------------------------

	private static long ridingSidingId;
	private static long ridingVehicleId;
	private static int ridingVehicleCarNumber;
	private static double ridingVehicleX;
	private static double ridingVehicleY;
	private static double ridingVehicleZ;
	private static boolean isOnGangway;
	/** Consecutive frames in which some vehicle reported updating this player's position. */
	private static int ridingVehicleCooldown;
	/** How long sneak has been held, in frames' worth of milliseconds. */
	private static float shiftHoldingTicks;

	/** The car number the camera offset was last taken for, and the offset itself. */
	private static int ridingVehicleCarNumberCacheOld;
	private static Vector3d ridingPositionCacheOld;
	private static Vector3d ridingPositionCache;
	private static Double ridingYawDifference;
	private static double previousVehicleYaw;

	/** When the next position report is due. */
	private static long sendPositionUpdateTime;
	/** Whether this client last told the server it is THE driver (ride packet's {@code isDriver}). */
	private static boolean isDriverReported;
	private static int doorOverrideTicks;

	// ---- the cab (B2, restored 2026-09-19) ---------------------------------------------------------
	//
	// Taking a cab via MmtrCabInteraction does exactly two things to this class: it MOVES the player to
	// the cab's seat (car-local, the space this class already maintains) and it LOCKS walking. Both are
	// owned here; the interaction only states the intent, because this class stays the only writer of the
	// riding coordinate (see the ownership rule in the class doc, and notes/185 §7 rule 1).
	//
	// There is deliberately NO separate "seat foot Y" pin any more. The old layer carried one and it had
	// four different clearing points, which is how the camera kept dropping back to the floor
	// (notes/184 §6 #4). It is not needed: MmtrVehicleAnchors.cabView already returns the FLOOR TOP
	// (sill + FLOOR_TOP_OFFSET_M), which is exactly what movePlayer computes from the floor box.

	/** The vehicle whose cab this client holds, or 0 when the player is only a passenger. */
	private static long mmtrCabVehicleId;
	private static int mmtrCabCarNumber = -1;
	private static int mmtrCabNumber = -1;

	/**
	 * True once the ENGINE has confirmed this client holds the cab (mirror: one of our cab's ends is
	 * active and the key holder is a CREW key).
	 *
	 * <p>This is deliberately separate from {@link #mmtrCabVehicleId}, which is set the instant the key is
	 * pressed. Boarding and DRIVING are different things: a refused claim must leave the player aboard as a
	 * passenger who can still walk, not lock them in place. The first version locked on the local flag, so
	 * a refused claim froze the player where they stood with no way to move until the ride timed out.</p>
	 */
	private static boolean mmtrCabConfirmed;

	private static final int RIDING_COOLDOWN = 5;
	private static final float VEHICLE_WALKING_SPEED_MULTIPLIER = 0.005F;
	private static final int SHIFT_ACTIVATE_TICKS = 30;
	private static final int DISMOUNT_PROGRESS_BAR_LENGTH = 30;

	/** How often the client reports its riding position, in milliseconds. */
	public static final int SEND_UPDATE_FREQUENCY = 1000;

	/**
	 * Called once per client tick.
	 *
	 * <p>Does two things and nothing else: dismounts the player when nothing is updating their position
	 * any more (the lift stopped, the chunk unloaded), and tracks how long sneak has been held so the
	 * progress bar and the dismount decision agree.</p>
	 */
	public static void tick() {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();

		if (ridingVehicleCooldown < RIDING_COOLDOWN && shiftHoldingTicks < SHIFT_ACTIVATE_TICKS) {
			ridingVehicleCooldown++;
		} else {
			// Nothing is moving the player: end the ride. This is the timer that catches "the ride was
			// established but movePlayer never ran for it" - the likeliest way a cab entry dies silently.
			leaveRide("计时器：movePlayer 没再推动玩家（没上车 / 车节号不匹配 / 车没了）");
		}

		if (ridingPositionCache != null) {
			ridingVehicleCarNumberCacheOld = ridingVehicleCarNumber;
			ridingPositionCacheOld = ridingPositionCache;
		}

		if (ridingVehicleId == 0) {
			shiftHoldingTicks = 0;
		} else {
			if (org.mtr.mod.KeyBindings.LIFT_MENU.isPressed()) {
				final org.mtr.mapping.holder.Screen currentScreen = minecraftClient.getCurrentScreenMapped();
				if (MinecraftClientData.getLift(ridingVehicleId) != null && (currentScreen == null || !(currentScreen.data instanceof LiftSelectionScreen))) {
					minecraftClient.openScreen(new org.mtr.mapping.holder.Screen(new LiftSelectionScreen(ridingVehicleId)));
				}
			}

			// Only lifts reach here at this checkpoint, and a lift has no cab to be locked into, so the
			// player is always free to walk.
			final ClientPlayerEntity clientPlayerEntity = minecraftClient.getPlayerMapped();
			if (clientPlayerEntity != null && clientPlayerEntity.isSneaking()) {
				shiftHoldingTicks += minecraftClient.getLastFrameDuration();
			} else {
				shiftHoldingTicks = 0;
			}
		}
	}

	/**
	 * Boards whatever {@code openFloorsAndDoorways} the player is standing in.
	 *
	 * <p>Called by {@code RenderLifts} for lifts and by {@code RenderVehicles} for trains - one generic
	 * mechanism for both. Each caller passes only the boxes it considers boardable: for trains that is the
	 * OPEN DOORWAYS and not the floors, so walking around inside a car does not re-mount a player who
	 * deliberately dismounted.</p>
	 */
	public static void startRiding(ObjectArrayList<Box> openFloorsAndDoorways, long depotId, long sidingId, long vehicleId, int carNumber, double x, double y, double z, double yaw) {
		if (ridingVehicleId != 0 && !isRiding(vehicleId)) {
			return;
		}
		for (final Box floorOrDoorway : openFloorsAndDoorways) {
			if (RenderVehicleHelper.boxContains(floorOrDoorway, x, y, z)) {
				ridingSidingId = sidingId;
				ridingVehicleId = vehicleId;
				ridingVehicleCarNumber = carNumber;
				ridingVehicleX = x;
				ridingVehicleY = y;
				ridingVehicleZ = z;
				isOnGangway = false;
				ridingPositionCacheOld = null;
				ridingPositionCache = null;
				ridingYawDifference = null;
				previousVehicleYaw = yaw;
				sendUpdate(false);
				return;
			}
		}
	}

	/**
	 * Walks the player around inside the vehicle they are riding, and pins them to it.
	 *
	 * <p>Called every frame for the car being ridden, by {@code RenderLifts} and - restored 2026-09-18 -
	 * by {@code RenderVehicles}. This is the ONLY thing that keeps a rider glued to a train, and it also
	 * renews the ride session: {@link #tick} ends the ride as soon as nothing has moved the player. The
	 * gangway branches are the train path (a lift has no gangways).</p>
	 */
	public static void movePlayer(
			long millisElapsed, long vehicleId, int carNumber,
			ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways,
			@Nullable GangwayMovementPositions previousCarGangwayMovementPositions,
			@Nullable GangwayMovementPositions thisCarGangwayMovementPositions1,
			@Nullable GangwayMovementPositions thisCarGangwayMovementPositions2,
			org.mtr.mod.render.PositionAndRotation positionAndRotation
	) {
		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity == null) {
			return;
		}
		if (!isRiding(vehicleId) || ridingVehicleCarNumber != carNumber) {
			return;
		}

		ridingVehicleCooldown = 0;
		final double entityYawOld = EntityHelper.getYaw(new org.mtr.mapping.holder.Entity(clientPlayerEntity.data));
		// In a cab the driver does not walk: the same key that took the cab gives it back. This is the
		// cab lock, and it is a MULTIPLIER rather than an early return on purpose - the rest of this
		// method is what keeps the player glued to the train and renews the ride session, so skipping it
		// would drop the driver out of their own cab.
		//
		// The lock follows the ENGINE's verdict (mmtrIsDriverLocked), not the local request: while a claim
		// is still unconfirmed the player is a passenger who may walk, so a refused claim cannot freeze
		// them in place.
		final float speedMultiplier = mmtrIsDriverLocked() ? 0 : millisElapsed * VEHICLE_WALKING_SPEED_MULTIPLIER * (clientPlayerEntity.isSprinting() ? 2 : 1);
		final Vector3d movement = positionAndRotation.transformBackwards(new Vector3d(
				Math.abs(clientPlayerEntity.getSidewaysSpeedMapped()) > 0.5 ? Math.copySign(speedMultiplier, clientPlayerEntity.getSidewaysSpeedMapped()) : 0,
				0,
				Math.abs(clientPlayerEntity.getForwardSpeedMapped()) > 0.5 ? Math.copySign(speedMultiplier, clientPlayerEntity.getForwardSpeedMapped()) : 0
		), (vector, pitch) -> vector, (vector, yaw) -> vector.rotateY((float) (yaw - Math.toRadians(entityYawOld))), (vector, x, y, z) -> vector);
		final double movementX = movement.getXMapped();
		final double movementZ = movement.getZMapped();

		if (sendPositionUpdateTime == 0 && (movementX != 0 || movementZ != 0)) {
			sendPositionUpdateTime = System.currentTimeMillis() + SEND_UPDATE_FREQUENCY;
		}

		if (isOnGangway) {
			moveOnGangway(previousCarGangwayMovementPositions, thisCarGangwayMovementPositions1, thisCarGangwayMovementPositions2, movementX, movementZ);
		} else if (thisCarGangwayMovementPositions1 != null && thisCarGangwayMovementPositions1.getPercentageZ(ridingVehicleZ + movementZ) < 1) {
			isOnGangway = true;
			ridingVehicleX = thisCarGangwayMovementPositions1.getPercentageX(ridingVehicleX + movementX);
			ridingVehicleZ = thisCarGangwayMovementPositions1.getPercentageZ(ridingVehicleZ + movementZ);
			ridingPositionCache = null;
		} else if (thisCarGangwayMovementPositions2 != null && thisCarGangwayMovementPositions2.getPercentageZ(ridingVehicleZ + movementZ) > 0) {
			isOnGangway = true;
			ridingVehicleCarNumber++;
			ridingVehicleX = thisCarGangwayMovementPositions2.getPercentageX(ridingVehicleX + movementX);
			ridingVehicleZ = thisCarGangwayMovementPositions2.getPercentageZ(ridingVehicleZ + movementZ);
			ridingPositionCache = null;
		} else {
			final ObjectArrayList<Vector3d> offsets = new ObjectArrayList<>();
			clampPosition(floorsAndDoorways, ridingVehicleX + movementX - RenderVehicleHelper.HALF_PLAYER_WIDTH, ridingVehicleZ + movementZ - RenderVehicleHelper.HALF_PLAYER_WIDTH, offsets);
			clampPosition(floorsAndDoorways, ridingVehicleX + movementX + RenderVehicleHelper.HALF_PLAYER_WIDTH, ridingVehicleZ + movementZ - RenderVehicleHelper.HALF_PLAYER_WIDTH, offsets);
			clampPosition(floorsAndDoorways, ridingVehicleX + movementX + RenderVehicleHelper.HALF_PLAYER_WIDTH, ridingVehicleZ + movementZ + RenderVehicleHelper.HALF_PLAYER_WIDTH, offsets);
			clampPosition(floorsAndDoorways, ridingVehicleX + movementX - RenderVehicleHelper.HALF_PLAYER_WIDTH, ridingVehicleZ + movementZ + RenderVehicleHelper.HALF_PLAYER_WIDTH, offsets);

			if (offsets.isEmpty()) {
				// Not standing on any floor any more: out of the vehicle. If this fires on the FIRST frame
				// after a cab entry, the seat point is not over a floor box and the entry is being undone
				// here - so report the clamp's own inputs, which is the only way to tell "no boxes at all"
				// apart from "boxes exist but the point/Y misses them".
				leaveRide("四个角都没有地板（点 x=" + Math.round(ridingVehicleX * 100) / 100.0
						+ " z=" + Math.round(ridingVehicleZ * 100) / 100.0
						+ " y=" + Math.round(ridingVehicleY * 1000) / 1000.0
						+ "；" + describeBoxes(floorsAndDoorways) + "）");
				return;
			}

			double clampX = 0;
			double maxY = -Double.MAX_VALUE;
			double clampZ = 0;
			for (final Vector3d offset : offsets) {
				if (Math.abs(offset.getXMapped()) > Math.abs(clampX)) {
					clampX = offset.getXMapped();
				}
				maxY = Math.max(maxY, offset.getYMapped());
				if (Math.abs(offset.getZMapped()) > Math.abs(clampZ)) {
					clampZ = offset.getZMapped();
				}
			}
			ridingVehicleX += movementX + clampX;
			ridingVehicleY = maxY;
			ridingVehicleZ += movementZ + clampZ;
		}

		ridingPositionCache = new Vector3d(ridingVehicleX, ridingVehicleY, ridingVehicleZ);
		final Vector3d newPlayerPosition = positionAndRotation.transformForwards(ridingPositionCache, Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
		movePlayerTo(newPlayerPosition.getXMapped(), newPlayerPosition.getYMapped(), newPlayerPosition.getZMapped());
		EntityHelper.setYaw(new org.mtr.mapping.holder.Entity(clientPlayerEntity.data), (float) (Math.toDegrees(previousVehicleYaw - positionAndRotation.yaw) + entityYawOld));

		ridingYawDifference = Math.abs(positionAndRotation.yaw - previousVehicleYaw) > 0.001 ? previousVehicleYaw + Math.toRadians(entityYawOld) : null;
		previousVehicleYaw = positionAndRotation.yaw;

		if (sendPositionUpdateTime > 0 && sendPositionUpdateTime <= System.currentTimeMillis()) {
			sendUpdate(false);
		}
		// 位置变了 ⇒ "我在不在司机位"可能变了（走进/走出驾驶室都不需要按键）。变化时才发包。
		refreshDriverFlag();
	}

	// ---- the cab entry (B2) ------------------------------------------------------------------------

	/**
	 * Puts the player in a cab: the camera goes onto that cab's seat and walking is disabled until
	 * {@link #mmtrLeaveCab()}.
	 *
	 * <p>Coordinates are <b>car-local</b>, which is the space {@link #movePlayer} maintains. The old cab
	 * entry wrote a WORLD position into the same fields and relied on the next frame to overwrite it
	 * (notes/184 §6 #1) - that is the inconsistency this avoids: the value written here is already the
	 * value the next {@code movePlayer} would compute.</p>
	 *
	 * @param cab            1 = the A end, 2 = the B end (the anchor's {@code cab})
	 * @param carLocalX/Y/Z  the seat point from {@code MmtrVehicleAnchors.cabView}
	 * @param vehicleYaw     the car's own yaw right now (so the view does not snap on the first frame)
	 * @param playerYawDeg   the entity yaw to face (the direction the cab faces), in MC degrees
	 */
	public static void mmtrEnterCab(long sidingId, long vehicleId, int carNumber, int cab, double carLocalX, double carLocalY, double carLocalZ, double vehicleYaw, float playerYawDeg) {
		ridingSidingId = sidingId;
		ridingVehicleId = vehicleId;
		ridingVehicleCarNumber = carNumber;
		ridingVehicleX = carLocalX;
		ridingVehicleY = carLocalY;
		ridingVehicleZ = carLocalZ;
		isOnGangway = false;
		ridingPositionCacheOld = null;
		ridingPositionCache = null;
		ridingYawDifference = null;
		previousVehicleYaw = vehicleYaw;
		ridingVehicleCooldown = 0;
		shiftHoldingTicks = 0;
		mmtrCabVehicleId = vehicleId;
		mmtrCabCarNumber = carNumber;
		mmtrCabNumber = cab;
		// 坐进座椅就是司机（引擎侧"谁能操纵"的判据只认这个），是否被引擎接受由它自己的闸门决定
		// （停稳、一个驾驶室只有一把钥匙）—— 不再等钥匙确认才敢上报（用户口径 2026-09-19）。
		mmtrCabConfirmed = false;
		refreshDriverFlag();
		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity != null) {
			EntityHelper.setYaw(new org.mtr.mapping.holder.Entity(clientPlayerEntity.data), playerYawDeg);
		}
		sendUpdate(false);
	}

	/**
	 * Gives the cab back. The player stays on the train (they are still riding) but may walk again; the
	 * server is told the driver key is gone.
	 */
	public static void mmtrLeaveCab() {
		if (mmtrCabVehicleId == 0) {
			return;
		}
		mmtrCabVehicleId = 0;
		mmtrCabCarNumber = -1;
		mmtrCabNumber = -1;
		refreshDriverFlag();
		if (ridingVehicleId != 0) {
			sendUpdate(false);
		}
	}

	/** @return whether the local player currently holds a cab (and is still on that train) */
	public static boolean mmtrIsInCab() {
		return mmtrCabVehicleId != 0 && ridingVehicleId == mmtrCabVehicleId;
	}

	/**
	 * @return whether the driver LOCK applies: we hold a cab locally AND the engine has confirmed the key.
	 *         Only this should ever stop the player walking.
	 */
	public static boolean mmtrIsDriverLocked() {
		return mmtrIsInCab() && mmtrCabConfirmed;
	}

	/**
	 * Called by the interaction layer once per tick with the engine's mirrored verdict.
	 *
	 * <p>The verdict still matters: it is what {@link #mmtrIsDriverLocked()} uses to stop the player
	 * walking. But it is no longer what makes this client a DRIVER - that follows the seat (see
	 * {@link #refreshDriverFlag()}).</p>
	 */
	public static void mmtrSetCabConfirmed(boolean confirmed) {
		if (confirmed == mmtrCabConfirmed) {
			return;
		}
		mmtrCabConfirmed = confirmed;
		refreshDriverFlag();
		if (ridingVehicleId != 0) {
			sendUpdate(false);
		}
	}

	/**
	 * 重算并（仅当变化时）上报 ride 包里的 {@code isDriver}：**坐在司机位上就够了，不再要求钥匙**
	 * （用户口径 2026-09-19：操作手柄不需要手里握着钥匙）。
	 *
	 * <p>为什么这一位必须如实跟着位置走：引擎侧的"谁能操纵"判据就是它（{@code Vehicle.canTakeMmtrControl}
	 * 只认"骑在司机位上"）。按 G 申领驾驶室依然会让它为真（那条路还会把人钉在座椅上），但它不再是前提。</p>
	 *
	 * <p>只在**变化**时发包：位置每帧都在动，而这一位回答的是"我是不是司机"，不是坐标。</p>
	 */
	private static void refreshDriverFlag() {
		final boolean isDriver = mmtrCabConfirmed || MmtrDriverSeat.isAtControls();
		if (isDriver == isDriverReported) {
			return;
		}
		isDriverReported = isDriver;
		if (ridingVehicleId != 0) {
			sendUpdate(false);
		}
	}

	/** @return whether the engine has confirmed the cab claim (for messages and diagnostics) */
	public static boolean mmtrIsCabConfirmed() {
		return mmtrCabConfirmed;
	}

	/** @return the vehicle whose cab is held, or 0 */
	public static long mmtrCabVehicleId() {
		return mmtrIsInCab() ? mmtrCabVehicleId : 0;
	}

	/** @return the CONSIST car index whose cab is held, or -1 */
	public static int mmtrCabCarNumber() {
		return mmtrIsInCab() ? mmtrCabCarNumber : -1;
	}

	/** @return the cab number held (1 = A end, 2 = B end), or -1 */
	public static int mmtrCabNumber() {
		return mmtrIsInCab() ? mmtrCabNumber : -1;
	}

	/** The gangway branch of {@link #movePlayer}: X is a percentage across, Z is a percentage along. */
	private static void moveOnGangway(@Nullable GangwayMovementPositions previousCarGangwayMovementPositions, @Nullable GangwayMovementPositions thisCarGangwayMovementPositions1, @Nullable GangwayMovementPositions thisCarGangwayMovementPositions2, double movementX, double movementZ) {
		if (thisCarGangwayMovementPositions1 == null || previousCarGangwayMovementPositions == null) {
			// The gangway data is gone: nothing left to stand on.
			leaveRide("贯通道数据没了（isOnGangway 但两侧贯通道为空）");
			return;
		}
		if (ridingVehicleZ + movementZ > 1) {
			isOnGangway = false;
			ridingVehicleX = thisCarGangwayMovementPositions1.getX(ridingVehicleX);
			ridingVehicleZ = thisCarGangwayMovementPositions1.getZ() + ridingVehicleZ + movementZ - 1;
			ridingPositionCache = null;
		} else if (ridingVehicleZ + movementZ < 0) {
			isOnGangway = false;
			ridingVehicleX = thisCarGangwayMovementPositions2 == null ? thisCarGangwayMovementPositions1.getX(ridingVehicleX) : thisCarGangwayMovementPositions2.getX(ridingVehicleX);
			ridingVehicleZ = thisCarGangwayMovementPositions2 == null ? ridingVehicleZ + movementZ : thisCarGangwayMovementPositions2.getZ() + ridingVehicleZ + movementZ;
			ridingPositionCache = null;
		} else {
			ridingVehicleX = Math.max(0, Math.min(1, ridingVehicleX + movementX));
			ridingVehicleZ += movementZ;
			ridingPositionCache = null;
		}
	}

	/**
	 * Ends the ride: tells the server, and forgets everything about being on board.
	 *
	 * <p>The reason is logged, but ONLY when there was actually something to leave: {@code tick} calls this
	 * on every frame the player is not riding, so an unconditional log line would flood the file. Knowing
	 * WHY a ride ended is the difference between "the player stepped off" and "the floor clamp found
	 * nothing under the cab and dropped them", and from the outside those look identical.</p>
	 */
	private static void leaveRide(String reason) {
		final boolean wasRiding = ridingVehicleId != 0;
		final boolean hadCab = mmtrCabVehicleId != 0;
		if (wasRiding || hadCab) {
			org.mtr.mod.Init.LOGGER.info("[MMTR-RIDE] 结束骑乘（{}）车={} 车节={} 驾驶室={} 已确认={}",
					reason, ridingVehicleId, ridingVehicleCarNumber, mmtrCabNumber, mmtrCabConfirmed);
		}
		sendUpdate(true);
		ridingSidingId = 0;
		ridingVehicleId = 0;
		ridingVehicleCarNumber = 0;
		isOnGangway = false;
		ridingPositionCache = null;
		ridingPositionCacheOld = null;
		ridingYawDifference = null;
		ridingVehicleCooldown = 0;
		shiftHoldingTicks = 0;
		// The cab goes with the ride: this is the ONE place that clears it, so getting off the train can
		// never leave a stale "I hold a cab" behind (the old layer had four clearing points - notes/184 §6 #4).
		mmtrCabVehicleId = 0;
		mmtrCabCarNumber = -1;
		mmtrCabNumber = -1;
		mmtrCabConfirmed = false;
		isDriverReported = false;
	}

	private static void sendUpdate(boolean dismount) {
		if (ridingVehicleId != 0) {
			InitClient.REGISTRY_CLIENT.sendPacketToServer(PacketUpdateVehicleRidingEntities.create(ridingSidingId, ridingVehicleId, dismount ? -1 : ridingVehicleCarNumber, ridingVehicleX, ridingVehicleY, ridingVehicleZ, isOnGangway, isDriverReported, false, false, false, false, doorOverrideTicks > 1));
			sendPositionUpdateTime = 0;
		}
		if (dismount) {
			ridingVehicleId = 0;
		}
	}

	/**
	 * Moves the client player to an absolute world position right now, and again at the end of the client
	 * tick (without the second one there is a rubber-band animation while moving inside a vehicle).
	 */
	private static void movePlayerTo(double x, double y, double z) {
		if (InitClient.getGameTick() > 40) {
			mmtrMovePlayerCalls++;
			final Runnable runnable = () -> {
				final MinecraftClient minecraftClient = MinecraftClient.getInstance();
				if (minecraftClient.getWorldMapped() != null && minecraftClient.getPlayerMapped() != null) {
					minecraftClient.getPlayerMapped().setFallDistanceMapped(0);
					minecraftClient.getPlayerMapped().setVelocity(0, 0, 0);
					minecraftClient.getPlayerMapped().setMovementSpeed(0);
					minecraftClient.getPlayerMapped().updatePosition(x, y, z);
				}
			};
			runnable.run();
			InitClient.scheduleMovePlayer(runnable);
		}
	}

	/** Finds a floor or doorway box containing {@code (x, z)} at the rider's current Y. */
	@Nullable
	private static ObjectBooleanImmutablePair<Box> bestPosition(ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsOrDoorways, double x, double z) {
		return floorsOrDoorways.stream()
				.filter(floorOrDoorway -> RenderVehicleHelper.boxContains(floorOrDoorway.left(), x, ridingVehicleY, z))
				.max(java.util.Comparator.comparingDouble(floorOrDoorway -> floorOrDoorway.left().getMaxYMapped()))
				.orElse(floorsOrDoorways.stream().filter(floorOrDoorway -> Math.abs(floorOrDoorway.left().getMaxYMapped() - ridingVehicleY) <= 1).min(java.util.Comparator.comparingDouble(floorOrDoorway -> {
					final Box box = floorOrDoorway.left();
					final double minX = box.getMinXMapped();
					final double maxX = box.getMaxXMapped();
					final double minZ = box.getMinZMapped();
					final double maxZ = box.getMaxZMapped();
					return (org.mtr.core.tool.Utilities.isBetween(x, minX, maxX) ? 0 : Math.min(Math.abs(minX - x), Math.abs(maxX - x))) + (org.mtr.core.tool.Utilities.isBetween(z, minZ, maxZ) ? 0 : Math.min(Math.abs(minZ - z), Math.abs(maxZ - z)));
				})).orElse(null));
	}

	/**
	 * One line describing the boxes the floor clamp was given: how many, how many of them are floors, and
	 * the union of their Y extents.
	 *
	 * <p>The clamp needs a box that contains the rider's Y, and the model's floor slab is only centimetres
	 * thick, so "the seat point is not over a floor" has two completely different causes - no boxes at all
	 * (the model publishes no FLOOR part / the wrong car) versus boxes that exist but whose Y the rider
	 * misses by a hair. Reporting the inputs is what tells them apart without another round trip.</p>
	 */
	private static String describeBoxes(ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways) {
		int floors = 0;
		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (final ObjectBooleanImmutablePair<Box> box : floorsAndDoorways) {
			if (box.rightBoolean()) {
				floors++;
			}
			minY = Math.min(minY, box.left().getMinYMapped());
			maxY = Math.max(maxY, box.left().getMaxYMapped());
		}
		if (floorsAndDoorways.isEmpty()) {
			return "这次一个盒都没有（车型没有 FLOOR 部件？或车节对不上）";
		}
		return "这次有 " + floorsAndDoorways.size() + " 个盒（其中地板 " + floors + " 个），所有盒的 y 范围 ["
				+ Math.round(minY * 1000) / 1000.0 + ", " + Math.round(maxY * 1000) / 1000.0 + "]";
	}

	private static void clampPosition(ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways, double x, double z, ObjectArrayList<Vector3d> offsets) {
		final ObjectBooleanImmutablePair<Box> floorOrDoorway = bestPosition(floorsAndDoorways, x, z);
		if (floorOrDoorway == null) {
			return;
		}
		if (floorOrDoorway.rightBoolean()) {
			offsets.add(new Vector3d(
					Math.max(floorOrDoorway.left().getMinXMapped(), Math.min(floorOrDoorway.left().getMaxXMapped(), x)) - x,
					floorOrDoorway.left().getMaxYMapped(),
					Math.max(floorOrDoorway.left().getMinZMapped(), Math.min(floorOrDoorway.left().getMaxZMapped(), z)) - z
			));
		} else if (RenderVehicleHelper.boxContains(floorOrDoorway.left(), x, ridingVehicleY, z)) {
			offsets.add(new Vector3d(0, floorOrDoorway.left().getMaxYMapped(), 0));
		}
	}

	// ---- read interface ---------------------------------------------------------------------------

	public static boolean isRiding(long vehicleId) {
		return vehicleId != 0 && vehicleId == ridingVehicleId;
	}

	/** @return the id of the vehicle (or lift) the local player is riding, or 0 when not riding anything */
	public static long getRidingVehicleId() {
		return ridingVehicleId;
	}

	/**
	 * @return {@code null} if the player is not riding anything, otherwise the car number being ridden and
	 * the car-local position/yaw offset the camera should be built from
	 */
	@Nullable
	public static IntObjectImmutablePair<ObjectObjectImmutablePair<Vector3d, Double>> getRidingVehicleCarNumberAndOffset(long vehicleId) {
		return isRiding(vehicleId) ? new IntObjectImmutablePair<>(ridingVehicleCarNumberCacheOld, new ObjectObjectImmutablePair<>(ridingPositionCacheOld, ridingYawDifference)) : null;
	}

	/** @return the driver key the player is holding that matches {@code depotId}, or null */
	@Nullable
	public static ItemDriverKey getValidHoldingKey(long depotId) {
		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity != null) {
			final ItemDriverKey mainHand = keyOf(clientPlayerEntity.getMainHandStack(), depotId);
			if (mainHand != null) {
				return mainHand;
			}
			return keyOf(clientPlayerEntity.getOffHandStack(), depotId);
		}
		return null;
	}

	@Nullable
	private static ItemDriverKey keyOf(org.mtr.mapping.holder.ItemStack itemStack, long depotId) {
		final org.mtr.mapping.holder.Item item = itemStack.getItem();
		if (item.data instanceof ItemDriverKey) {
			return ItemDepotDriverKey.isCreativeDriverKeyOrMatchesDepot(itemStack, depotId) ? (ItemDriverKey) item.data : null;
		}
		return null;
	}

	/** Tells the server the player's own body is blocking a door, so it may open. */
	public static void overrideDoors() {
		final double previous = doorOverrideTicks;
		doorOverrideTicks = 2;
		if (previous == 0) {
			sendUpdate(false);
		}
	}

	/**
	 * @return the throttle/brake/reverser notches for the driving HUD. Always zero at this checkpoint:
	 * the notches belonged to the deleted train control layer, and the HUD keeps drawing so the gap is
	 * visible rather than mysterious. Rebuild step B6 restores them from the new ride session.
	 */
	public static int getMmtrThrottleNotch() {
		return 0;
	}

	public static int getMmtrBrakeNotch() {
		return 0;
	}

	public static int getMmtrReverser() {
		return 0;
	}

	/**
	 * @return how many times the client has teleported the player onto a vehicle. Always zero at this
	 * checkpoint because the only teleport site (the train's movePlayer call) was deleted; the lift path
	 * has its own. Kept so the motion trace keeps compiling and so the counter means the same thing after
	 * the rebuild.
	 */
	public static int mmtrGetMovePlayerCalls() {
		return mmtrMovePlayerCalls;
	}

	public static int mmtrGetAndResetMovePlayerCalls() {
		final int value = mmtrMovePlayerCalls;
		mmtrMovePlayerCalls = 0;
		return value;
	}

	/**
	 * Counts how many times the client has teleported the player to a car-local point. Reset by
	 * {@link #mmtrGetAndResetMovePlayerCalls()}; reported by {@code MmtrPlayerMotionTrace}.
	 */
	private static int mmtrMovePlayerCalls;

	public static boolean showShiftProgressBar() {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final ClientPlayerEntity clientPlayerEntity = minecraftClient.getPlayerMapped();
		if (shiftHoldingTicks > 0 && clientPlayerEntity != null) {
			final int progressFilled = Math.max(0, Math.min(DISMOUNT_PROGRESS_BAR_LENGTH, (int) (shiftHoldingTicks * DISMOUNT_PROGRESS_BAR_LENGTH / SHIFT_ACTIVATE_TICKS)));
			final StringBuilder bar = new StringBuilder();
			for (int i = 0; i < progressFilled; i++) {
				bar.append('|');
			}
			for (int i = progressFilled; i < DISMOUNT_PROGRESS_BAR_LENGTH; i++) {
				bar.append(' ');
			}
			clientPlayerEntity.sendMessage(new org.mtr.mapping.holder.Text(org.mtr.mapping.mapper.TextHelper.literal(bar.toString()).data), true);
			return true;
		}
		return false;
	}
}

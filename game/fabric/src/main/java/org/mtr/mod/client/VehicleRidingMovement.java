package org.mtr.mod.client;
import org.mtr.mod.MathUtils;

import org.apache.commons.lang3.StringUtils;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntObjectImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectBooleanImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.EntityHelper;
import org.mtr.mod.InitClient;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.generated.lang.TranslationProvider;
import org.mtr.mod.item.ItemDepotDriverKey;
import org.mtr.mod.item.ItemDriverKey;
import org.mtr.mod.packet.PacketDriveControl;
import org.mtr.mod.packet.PacketMmtrCabOp;
import org.mtr.mod.packet.PacketUpdateVehicleRidingEntities;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.render.RenderVehicleHelper;
import org.mtr.mod.screen.LiftSelectionScreen;

import javax.annotation.Nullable;
import java.util.Comparator;

public class VehicleRidingMovement {

	private static long ridingDepotId;
	private static long ridingSidingId;
	private static long ridingVehicleId;
	private static int ridingVehicleCarNumber;
	private static double ridingVehicleX;
	private static double ridingVehicleY;
	private static double ridingVehicleZ;
	private static boolean isOnGangway;
	private static int ridingVehicleCooldown;
	private static float shiftHoldingTicks;

	private static int ridingVehicleCarNumberCacheOld;
	private static Vector3d ridingPositionCacheOld;
	private static Vector3d ridingPositionCache;
	private static Double ridingYawDifference;
	private static double previousVehicleYaw;

	// Cooldown for sending player position to simulator
	private static long sendPositionUpdateTime;

	private static boolean isHoldingDriverKey = false;
	private static int pressingAccelerateTicks = 0;
	private static int pressingBrakeTicks = 0;
	private static int pressingDoorsTicks = 0;
	private static int pressingAtoTicks = 0;
	private static int doorOverrideTicks;

	// MMTR separated control state (client authority on the notch positions)
	private static final int MAX_MMTR_THROTTLE_NOTCH = 7;
	private static final int MAX_MMTR_BRAKE_NOTCH = 8;
	private static int mmtrThrottleNotch;
	private static int mmtrBrakeNotch;
	private static int mmtrReverser;
	private static boolean prevThrottleUp, prevThrottleDown, prevBrakeApply, prevBrakeRelease, prevReverserUp, prevReverserDown;
	/** Rising-edge state of the cab crew's per-side door keys (Y = left, U = right). */
	private static boolean prevDoorLeftPressed, prevDoorRightPressed;
	/** Rising-edge state of the AWS acknowledge key (H) - a one-shot press. */
	private static boolean prevAwsAckPressed;
	/** True once the engine has been told this client occupies a cab driver seat this ride. */
	private static boolean mmtrDriverSynced;
	/**
	 * True while the crew is seated in a cab: the driver is fixed at the seat and cannot walk.
	 * Set when a cab is taken and cleared when it is left (or when the ride ends).
	 *
	 * <p>Purely about WALKING. It no longer owns the eye-height latch - see
	 * {@link #mmtrPinSeatFootY(double)} for why conflating the two broke boarding.</p>
	 */
	private static boolean mmtrCabLocked;
	/**
	 * The car-local FOOT Y a rider's view is pinned to, or {@link Double#NaN} when the resource pack's
	 * floor boxes decide it.
	 *
	 * <p>It exists because {@code movePlayer} OVERWRITES the Y from the floor-clamp box every tick, and
	 * that clamp is fed by the resource pack's floor boxes. When those boxes do not match the model -
	 * which is exactly what happens when a consist is raised to platform height - the rider's eye height
	 * is dragged to the wrong place, reported as "视角过于低". A seat's Y comes from the model's own
	 * {@code mmtr_seat_<cab>} anchor, which moves with the model, so once someone is seated it wins.</p>
	 *
	 * <p>Deliberately NOT applied everywhere: a standing passenger's Y SHOULD come from the floors,
	 * because that is what keeps them on the car and what dismounts them when they walk off it.</p>
	 */
	private static double mmtrLockedSeatFootY = Double.NaN;

	/**
	 * The Y to PROBE the resource pack's floor/doorway boxes at, i.e. where the boxes actually are.
	 *
	 * <p>Usually maintained automatically: {@code bestPosition} adopts a box's own top whenever the direct
	 * probe misses. The entry paths seed it with their best guess so the FIRST tick does not have to fall
	 * back, and {@link Double#NaN} means "no idea, ask the boxes".</p>
	 *
	 * <p>It exists because the rider's Y and the boxes can disagree by more than a block: saf101's model
	 * floor sits at 2.47 while its floor box is at 1.0, because the model was raised and the resource pack
	 * was not. Anything that asks "is the rider on a floor?" using the rider's Y answers no, and the
	 * caller dismounts them.</p>
	 */
	private static double mmtrFloorProbeY = Double.NaN;
	/**
	 * MMTR 取证计数器（notes/177）：客户端把玩家"按"到车内绝对坐标的次数。
	 *
	 * <p>"人卡在两帧之间来回抽搐"最可能的机制就是这里跟服务端抢人 —— 客户端每帧把玩家按到车上，
	 * 服务端每帧按回它认为的位置。所以这个计数只要不是 0，就说明本地确实在持续瞬移玩家；
	 * 它跟 {@link MmtrPlayerMotionTrace} 的"拍间挪动"计数放在一起，就能分清是"本地按人"还是
	 * "服务端按人"。</p>
	 */
	private static int mmtrMovePlayerCalls;

	public static final int SEND_UPDATE_FREQUENCY = 1000;
	private static final float VEHICLE_WALKING_SPEED_MULTIPLIER = 0.005F;
	private static final int RIDING_COOLDOWN = 5;
	private static final int SHIFT_ACTIVATE_TICKS = 30;
	private static final int DISMOUNT_PROGRESS_BAR_LENGTH = 30;

	public static void tick() {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final ItemDriverKey driverKey = getValidHoldingKey(ridingDepotId);

		if (ridingVehicleCooldown < RIDING_COOLDOWN && shiftHoldingTicks < SHIFT_ACTIVATE_TICKS) {
			ridingVehicleCooldown++;
		} else {
			// If no vehicles are updating the player's position, dismount the player.
			//
			// The PIN is not cleared here on purpose. This branch fires on a single quiet tick - a lag
			// spike, a chunk not loaded, the vehicle stream stuttering - and `ridingVehicleCooldown` is the
			// only thing that has to recover. Clearing the pin made the camera drop to the floor for those
			// ticks and then snap back, which is the "it works then it falls" flicker. The pin belongs to
			// the RIDE, so it is cleared at the two real exits instead (see markLeftTrain).
			sendUpdate(true);
			ridingDepotId = 0;
			ridingSidingId = 0;
			ridingVehicleId = 0;
			markLeftTrain();
		}

		if (ridingPositionCache != null) {
			ridingVehicleCarNumberCacheOld = ridingVehicleCarNumber;
			ridingPositionCacheOld = ridingPositionCache;
		}

		final ClientPlayerEntity permissionPlayer = minecraftClient.getPlayerMapped();
		// MMTR: a crew member seated in a cab is the driver even without holding a key item. The engine
		// occupation lock (MmtrDriveAccess.canControl) only honours control from a rider it sees as
		// isDriver, and that flag is exactly what this client reports to the server - so taking a cab
		// with the interact key must set it, otherwise the throttle only worked while holding the key.
		final boolean mmtrCabDriver = mmtrCabLocked && ridingVehicleId != 0 && MmtrCabPermissions.canDrive(permissionPlayer, ridingVehicleId) && MmtrCabInteraction.holdsCab(ridingVehicleId);
		final boolean isHoldingDriverKeyNew = driverKey != null || mmtrCabDriver;
		// MMTR: the driver key still grants control, but the permission seam can grant it as well, so
		// boarding a cab no longer requires holding the creative/depot key.
		final boolean canDrive = driverKey != null && driverKey.canDrive || ridingVehicleId != 0 && MmtrCabPermissions.canDrive(permissionPlayer, ridingVehicleId);
		// A null key item must not be dereferenced here: a crew member sitting in a cab (no key item in
		// hand) is the normal case now, and this used to throw every tick for exactly that player.
		final boolean canOpenDoors = driverKey != null && isHoldingDriverKeyNew && driverKey.canOpenDoors || ridingVehicleId != 0 && MmtrCabPermissions.canOpenDoors(permissionPlayer, ridingVehicleId);
		pressingAccelerateTicks = canDrive && KeyBindings.TRAIN_ACCELERATE.isPressed() ? pressingAccelerateTicks + 1 : 0;
		pressingBrakeTicks = canDrive && KeyBindings.TRAIN_BRAKE.isPressed() ? pressingBrakeTicks + 1 : 0;
		pressingDoorsTicks = canOpenDoors && KeyBindings.TRAIN_TOGGLE_DOORS.isPressed() ? pressingDoorsTicks + 1 : 0;
		pressingAtoTicks = canDrive && KeyBindings.TRAIN_TOGGLE_DOORS.isPressed() ? pressingAtoTicks + 1 : 0;

		// MMTR separated throttle/brake controls (rising-edge per press)
		if (canDrive && ridingVehicleId != 0) {
			boolean changed = false;
			final boolean throttleUp = KeyBindings.TRAIN_ACCELERATE.isPressed();
			if (throttleUp && !prevThrottleUp && mmtrThrottleNotch < MAX_MMTR_THROTTLE_NOTCH) { mmtrThrottleNotch++; changed = true; }
			final boolean throttleDown = KeyBindings.TRAIN_BRAKE.isPressed();
			if (throttleDown && !prevThrottleDown && mmtrThrottleNotch > 0) { mmtrThrottleNotch--; changed = true; }
			final boolean brakeApply = KeyBindings.MMTR_BRAKE_APPLY.isPressed();
			if (brakeApply && !prevBrakeApply && mmtrBrakeNotch < MAX_MMTR_BRAKE_NOTCH) { mmtrBrakeNotch++; changed = true; }
			final boolean brakeRelease = KeyBindings.MMTR_BRAKE_RELEASE.isPressed();
			if (brakeRelease && !prevBrakeRelease && mmtrBrakeNotch > 0) { mmtrBrakeNotch--; changed = true; }
			final boolean reverserUp = KeyBindings.MMTR_REVERSER_UP.isPressed();
			if (reverserUp && !prevReverserUp && mmtrReverser < 1) { mmtrReverser++; changed = true; }
			final boolean reverserDown = KeyBindings.MMTR_REVERSER_DOWN.isPressed();
			if (reverserDown && !prevReverserDown && mmtrReverser > -1) { mmtrReverser--; changed = true; }
			prevThrottleUp = throttleUp; prevThrottleDown = throttleDown; prevBrakeApply = brakeApply;
			prevBrakeRelease = brakeRelease; prevReverserUp = reverserUp; prevReverserDown = reverserDown;
			if (changed) {
				// Make sure the engine has registered this client as a cab driver before sending
				// control, otherwise the occupation-lock check would reject the first command.
				if (!mmtrDriverSynced) {
					isHoldingDriverKey = isHoldingDriverKeyNew;
					sendUpdate(false);
					mmtrDriverSynced = true;
				}
				InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketDriveControl(ridingVehicleId, mmtrThrottleNotch, mmtrBrakeNotch, mmtrReverser, false));
			}
		} else {
			prevThrottleUp = prevThrottleDown = prevBrakeApply = prevBrakeRelease = prevReverserUp = prevReverserDown = false;
		}

		// A3: AWS acknowledge (H) - a one-shot press of the yellow/black cancel button. It must reach
		// the engine even when no notch changed, so it sends its own drive command; an unacknowledged
		// warning becomes a SPAD emergency stop after ~2.5 s.
		if (canDrive && ridingVehicleId != 0) {
			final boolean awsAckPressed = KeyBindings.MMTR_AWS_ACK.isPressed();
			if (awsAckPressed && !prevAwsAckPressed) {
				if (!mmtrDriverSynced) {
					isHoldingDriverKey = isHoldingDriverKeyNew;
					sendUpdate(false);
					mmtrDriverSynced = true;
				}
				InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketDriveControl(ridingVehicleId, mmtrThrottleNotch, mmtrBrakeNotch, mmtrReverser, false, true));
			}
			prevAwsAckPressed = awsAckPressed;
		} else {
			prevAwsAckPressed = false;
		}

		if (sendPositionUpdateTime > 0 && sendPositionUpdateTime <= System.currentTimeMillis() || isHoldingDriverKeyNew != isHoldingDriverKey || pressingAccelerateTicks == 1 || pressingBrakeTicks == 1 || pressingDoorsTicks == 1 || pressingAtoTicks == 1 || doorOverrideTicks == 1) {
			isHoldingDriverKey = isHoldingDriverKeyNew;
			sendUpdate(false);
		}

		if (doorOverrideTicks > 0) {
			doorOverrideTicks--;
		}

		// B7.6h: the crew sitting in the cab works the doors per side — Y = left, U = right, as on a
		// real desk. Only the cab crew (the key holder) may do this; passengers keep the plain door key.
		if (mmtrCabDriver) {
			final boolean doorLeftPressed = KeyBindings.MMTR_DOOR_LEFT.isPressed();
			if (doorLeftPressed && !prevDoorLeftPressed) {
				InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(ridingVehicleId, PacketMmtrCabOp.Op.DOORS, "left"));
			}
			prevDoorLeftPressed = doorLeftPressed;
			final boolean doorRightPressed = KeyBindings.MMTR_DOOR_RIGHT.isPressed();
			if (doorRightPressed && !prevDoorRightPressed) {
				InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(ridingVehicleId, PacketMmtrCabOp.Op.DOORS, "right"));
			}
			prevDoorRightPressed = doorRightPressed;
		} else {
			prevDoorLeftPressed = false;
			prevDoorRightPressed = false;
		}

		if (ridingVehicleId == 0) {
			shiftHoldingTicks = 0;
		} else {
			if (KeyBindings.LIFT_MENU.isPressed()) {
				final Screen currentScreen = minecraftClient.getCurrentScreenMapped();
				if (MinecraftClientData.getLift(ridingVehicleId) != null && (currentScreen == null || !(currentScreen.data instanceof LiftSelectionScreen))) {
					minecraftClient.openScreen(new Screen(new LiftSelectionScreen(ridingVehicleId)));
				}
			}

			final ClientPlayerEntity clientPlayerEntity = minecraftClient.getPlayerMapped();
			if (clientPlayerEntity != null && clientPlayerEntity.isSneaking()) {
				shiftHoldingTicks += minecraftClient.getLastFrameDuration();
			} else {
				shiftHoldingTicks = 0;
			}
		}
	}

	/**
	 * Iterate through all open floors and doorways and see if the player is intersecting any of them. If so, start riding the vehicle.
	 */
	public static void startRiding(ObjectArrayList<Box> openFloorsAndDoorways, long depotId, long sidingId, long vehicleId, int carNumber, double x, double y, double z, double yaw) {
		if (ridingVehicleId == 0 || isRiding(vehicleId)) {
			for (final Box floorOrDoorway : openFloorsAndDoorways) {
				if (RenderVehicleHelper.boxContains(floorOrDoorway, x, y, z)) {
					ridingDepotId = depotId;
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
					mmtrDriverSynced = false;
					if (ridingVehicleId == 0) {
						sendUpdate(false);
					}
				}
			}
		}
	}

	public static void movePlayer(
			long millisElapsed, long vehicleId, int carNumber,
			ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways,
			@Nullable GangwayMovementPositions previousCarGangwayMovementPositions,
			@Nullable GangwayMovementPositions thisCarGangwayMovementPositions1,
			@Nullable GangwayMovementPositions thisCarGangwayMovementPositions2,
			PositionAndRotation positionAndRotation
	) {
		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity == null) {
			return;
		}

		if (isRiding(vehicleId) && ridingVehicleCarNumber == carNumber) {
			ridingVehicleCooldown = 0;
			final double entityYawOld = EntityHelper.getYaw(new Entity(clientPlayerEntity.data));
			final float speedMultiplier = mmtrCabLocked ? 0 : millisElapsed * VEHICLE_WALKING_SPEED_MULTIPLIER * (clientPlayerEntity.isSprinting() ? 2 : 1);
			// Calculate the relative motion inside vehicle (+Z towards back of vehicle, +/-X towards the left and right of the vehicle)
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
				// If the player is currently standing on a gangway, ridingVehicleX and Z will indicate percentages along the gangway (rather than a relative coordinate inside the vehicle car)
				if (thisCarGangwayMovementPositions1 == null || previousCarGangwayMovementPositions == null) {
					// Dismount player
					sendUpdate(true);
					ridingDepotId = 0;
					ridingSidingId = 0;
					ridingVehicleId = 0;
					markLeftTrain();
				} else {
					if (ridingVehicleZ + movementZ > 1) {
						// If player has left the gangway (in the +Z direction), convert back to non-gangway positioning for ridingVehicleX and Z
						isOnGangway = false;
						ridingVehicleX = thisCarGangwayMovementPositions1.getX(ridingVehicleX);
						ridingVehicleZ = thisCarGangwayMovementPositions1.getZ() + ridingVehicleZ + movementZ - 1;
						ridingPositionCache = null;
					} else if (ridingVehicleZ + movementZ < 0) {
						// If player has left the gangway (in the -Z direction) and consequently moved to the previous car, convert back to non-gangway positioning for ridingVehicleX and Z
						isOnGangway = false;
						ridingVehicleCarNumber--;
						ridingVehicleX = previousCarGangwayMovementPositions.getX(ridingVehicleX);
						ridingVehicleZ = previousCarGangwayMovementPositions.getZ() + ridingVehicleZ + movementZ;
						ridingPositionCache = null;
					} else {
						// Gangway positioning logic
						ridingVehicleX = MathUtils.clamp(ridingVehicleX + movementX, 0, 1);
						ridingVehicleZ += movementZ;
						final Vector3d position1Min = previousCarGangwayMovementPositions.getMinWorldPosition();
						final Vector3d position1Max = previousCarGangwayMovementPositions.getMaxWorldPosition();
						final Vector3d position2Min = thisCarGangwayMovementPositions1.getMinWorldPosition();
						final Vector3d position2Max = thisCarGangwayMovementPositions1.getMaxWorldPosition();
						final double positionX = getFromScale(
								getFromScale(position1Min.getXMapped(), position1Max.getXMapped(), ridingVehicleX),
								getFromScale(position2Min.getXMapped(), position2Max.getXMapped(), ridingVehicleX),
								ridingVehicleZ
						);
						final double positionY = getFromScale(
								getFromScale(position1Min.getYMapped(), position1Max.getYMapped(), ridingVehicleX),
								getFromScale(position2Min.getYMapped(), position2Max.getYMapped(), ridingVehicleX),
								ridingVehicleZ
						);
						final double positionZ = getFromScale(
								getFromScale(position1Min.getZMapped(), position1Max.getZMapped(), ridingVehicleX),
								getFromScale(position2Min.getZMapped(), position2Max.getZMapped(), ridingVehicleX),
								ridingVehicleZ
						);

						// ridingPositionCache should always store the relative position of the player with respect to the riding car, even when the player is on a gangway
						ridingPositionCache = positionAndRotation.transformBackwards(new Vector3d(positionX, positionY, positionZ), Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
						movePlayer(positionX, positionY, positionZ);
					}
				}
			} else {
				if (thisCarGangwayMovementPositions1 != null && thisCarGangwayMovementPositions1.getPercentageZ(ridingVehicleZ + movementZ) < 1) {
					// If player has entered the gangway (in the -Z direction), convert to gangway positioning for ridingVehicleX and Z
					isOnGangway = true;
					ridingVehicleX = thisCarGangwayMovementPositions1.getPercentageX(ridingVehicleX + movementX);
					ridingVehicleZ = thisCarGangwayMovementPositions1.getPercentageZ(ridingVehicleZ + movementZ);
					ridingPositionCache = null;
				} else if (thisCarGangwayMovementPositions2 != null && thisCarGangwayMovementPositions2.getPercentageZ(ridingVehicleZ + movementZ) > 0) {
					// If player has entered the gangway (in the +Z direction) and consequently moved to the next car, convert to gangway positioning for ridingVehicleX and Z
					isOnGangway = true;
					ridingVehicleCarNumber++;
					ridingVehicleX = thisCarGangwayMovementPositions2.getPercentageX(ridingVehicleX + movementX);
					ridingVehicleZ = thisCarGangwayMovementPositions2.getPercentageZ(ridingVehicleZ + movementZ);
					ridingPositionCache = null;
				} else {
					// Calculate and store all the offsets that should be applied to the player to keep them in bounds of the floors.
					//
					// MMTR: the Y used to FIND the floor is the floor's own Y, never the pinned seat Y. This
					// is load-bearing. `bestPosition` rejects a floor whose top is more than a block from the
					// Y it is handed, and `boxContains` needs the Y to be inside the box at all - so asking
					// "is there a floor here?" at the pinned seat height (which is deliberately ABOVE the
					// floor, because the anchor is at eye height minus... nothing) answered "no floor" for a
					// player sitting perfectly well in a cab, and the dismount below fired one tick after
					// boarding. That was the 9.6-block server correction in the log.
					//
					// The two questions are genuinely different and must be asked separately:
					//   "is the player on a floor?"     -> the resource pack's boxes, at the floor's Y
					//   "how high is the camera?"       -> the model's seat anchor (the pin)
					final ObjectArrayList<Vector3d> offsets = new ObjectArrayList<>();
					clampPosition(floorsAndDoorways, ridingVehicleX + movementX - RenderVehicleHelper.HALF_PLAYER_WIDTH, ridingVehicleZ + movementZ - RenderVehicleHelper.HALF_PLAYER_WIDTH, offsets);
					clampPosition(floorsAndDoorways, ridingVehicleX + movementX + RenderVehicleHelper.HALF_PLAYER_WIDTH, ridingVehicleZ + movementZ - RenderVehicleHelper.HALF_PLAYER_WIDTH, offsets);
					clampPosition(floorsAndDoorways, ridingVehicleX + movementX + RenderVehicleHelper.HALF_PLAYER_WIDTH, ridingVehicleZ + movementZ + RenderVehicleHelper.HALF_PLAYER_WIDTH, offsets);
					clampPosition(floorsAndDoorways, ridingVehicleX + movementX - RenderVehicleHelper.HALF_PLAYER_WIDTH, ridingVehicleZ + movementZ + RenderVehicleHelper.HALF_PLAYER_WIDTH, offsets);

					if (offsets.isEmpty()) {
						// Player is not standing on any floor, dismount player.
						//
						// MMTR: this is also where a PINNED rider gets thrown off, so it reports the search
						// that failed before it does. The pinned Y is deliberately above the resource pack's
						// floor boxes, so a search that probes at the wrong Y finds nothing and dismounts a
						// player who is sitting perfectly well - which is exactly what happened once.
						logFloorMissOnce();
						sendUpdate(true);
						ridingDepotId = 0;
						ridingSidingId = 0;
						ridingVehicleId = 0;
						// A new cab lock will re-latch; a stale one must not pin the next ride's Y.
						markLeftTrain();
					} else {
						// Find the highest amounts to clamp the player movement in both the X and Z direction and apply the clamps
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
						// MMTR: a rider whose view is pinned to a modelled seat keeps the Y that seat
						// anchor implies. The clamp above still decides whether they are on the train at all
						// (an empty `offsets` dismounts) and the X/Z clamp still applies - only the Y,
						// which comes from the resource pack's floor box, is overridden.
						//
						// Note this is keyed on the LATCH alone, NOT on mmtrCabLocked: a boarded player is
						// deliberately free to walk (not locked), and gating on the lock made the latch
						// unreachable. See mmtrPinSeatFootY.
						//
						// mmtrFloorProbeY is remembered separately so the NEXT tick's floor search still
						// probes where the boxes are, instead of at the raised seat. See mmtrFloorProbeY.
						mmtrFloorProbeY = maxY;						ridingVehicleY = Double.isNaN(mmtrLockedSeatFootY) ? maxY : mmtrLockedSeatFootY;
						ridingVehicleZ += movementZ + clampZ;
						logFloorClamp(maxY, ridingVehicleX, ridingVehicleY, ridingVehicleZ);
					}

					ridingPositionCache = new Vector3d(ridingVehicleX, ridingVehicleY, ridingVehicleZ);
					final Vector3d newPlayerPosition = positionAndRotation.transformForwards(ridingPositionCache, Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
					movePlayer(newPlayerPosition.getXMapped(), newPlayerPosition.getYMapped(), newPlayerPosition.getZMapped());
					EntityHelper.setYaw(new Entity(clientPlayerEntity.data), (float) (Math.toDegrees(previousVehicleYaw - positionAndRotation.yaw) + entityYawOld));
				}
			}

			ridingYawDifference = Math.abs(positionAndRotation.yaw - previousVehicleYaw) > 0.001 ? previousVehicleYaw + Math.toRadians(entityYawOld) : null;
			previousVehicleYaw = positionAndRotation.yaw;
		}
	}

	/**
	 * @return {@code null} if the player is not riding a vehicle or an {@link IntObjectImmutablePair} of the car number the player is currently riding in and the relative position and yaw of the player with respect to the center of the car they are currently riding in.
	 */
	@Nullable
	public static IntObjectImmutablePair<ObjectObjectImmutablePair<Vector3d, Double>> getRidingVehicleCarNumberAndOffset(long vehicleId) {
		return isRiding(vehicleId) ? new IntObjectImmutablePair<>(ridingVehicleCarNumberCacheOld, new ObjectObjectImmutablePair<>(ridingPositionCacheOld, ridingYawDifference)) : null;
	}

	public static boolean isRiding(long vehicleId) {
		return vehicleId == ridingVehicleId;
	}

	/** @return the id of the vehicle (or lift) the local player is riding, or 0 when not riding anything */
	public static long getRidingVehicleId() {
		return ridingVehicleId;
	}

	/** @return 从进程启动起，客户端把玩家按到车内坐标的累计次数（notes/177 取证用） */
	public static int mmtrGetMovePlayerCalls() {
		return mmtrMovePlayerCalls;
	}

	/** @return 同上，但读后清零 —— 用来做"这一窗口按了几次"的统计 */
	public static int mmtrGetAndResetMovePlayerCalls() {
		final int value = mmtrMovePlayerCalls;
		mmtrMovePlayerCalls = 0;
		return value;
	}

	/**
	 * MMTR: while the crew holds a cab the driver is fixed at the seat and cannot walk around; the
	 * passenger compartment stays freely walkable. Set when a cab is taken and cleared when it is
	 * left (or when the ride ends).
	 *
	 * @param locked also latches/clears the seat's eye height; see {@link #mmtrLockedSeatFootY}
	 */
	/**
	 * True while the crew is seated in a cab: the driver is fixed at the seat and cannot walk.
	 * Set when a cab is taken and cleared when it is left (or when the ride ends).
	 *
	 * <p>Purely about WALKING. It deliberately does NOT touch the eye-height pin any more: releasing the
	 * lock must not drop the camera, and coupling the two is what made the view flip between the seat and
	 * the floor every tick (the cab-key reconciliation releases the lock repeatedly while the player is
	 * sitting in the seat). See {@link #mmtrPinSeatFootY(double, double)} for the pin's own lifecycle.</p>
	 */
	public static void mmtrSetCabLock(boolean locked) {
		mmtrCabLocked = locked;
	}

	/**
	 * Whether a cab has been taken by key (as opposed to merely boarded). The two entry paths into the same
	 * seat have to be able to tell each other apart: an explicit cab claim is the intentional one and wins.
	 */
	public static boolean mmtrHasCabLock() {
		return mmtrCabLocked;
	}

	/**
	 * Pin the Y to a modelled seat's foot height AND record where the car's floor actually is, then clear
	 * the cab lock so the rider stays free to walk.
	 *
	 * <p><b>Pin</b> = "this rider's eye height comes from the model's seat anchor".<br>
	 * <b>Lock</b> = "this rider may not walk around".</p>
	 *
	 * <p>They are separate ideas and must not be conflated. Conflating them has now cost two rounds: first
	 * the pin was gated ON the lock (so it could never apply to a boarder, who is deliberately unlocked),
	 * then releasing the lock also cleared the pin (so the cab-key reconciliation dropped the camera to the
	 * floor several times a second). The pin's lifecycle is now its own: set here or by
	 * {@link #mmtrLockSeatAndPin(double, double)}, and cleared only by {@link #markLeftTrain()}.</p>
	 *
	 * <p>A boarded player gets the pin but not the lock; a driver who took the cab by key gets both.</p>
	 */
	/**
	 * Pin the Y to a modelled seat's foot height, and record where the car's floor actually is.
	 *
	 * <p><b>Only the pin.</b> {@code mmtrCabLocked} is deliberately NOT touched, and neither is anything
	 * else about the ride. An earlier version cleared the cab lock here so a boarded player could walk, and
	 * that was a per-frame bug once the pin became self-healing: {@code MmtrBoarding.tick()} re-asserts the
	 * pin every tick, so every tick cleared the lock, so the driver-key check downstream saw "not seated"
	 * and stopped reporting the key to the server - which then revoked the cab. The visible result was
	 * "上车一会就被判定为下车了" (the HUD drops the cab), while the player was still sitting in it.</p>
	 *
	 * <p>Whether the rider may walk is {@link #mmtrLockSeatAndPin(double, double)} /
	 * {@link #mmtrSetCabLock(boolean)}'s business, and it is set once at the door rather than every tick.</p>
	 */
	public static void mmtrPinSeatFootY(double footY, double floorY) {
		setSeatFootY(footY, "硬绑上车 mmtrPinSeatFootY");
		mmtrFloorProbeY = floorY;
	}

	/**
	 * Pin the Y to a modelled seat's foot height AND lock the rider in place.
	 *
	 * <p>The paired form exists so a caller cannot get the order wrong: the two are set together rather
	 * than by a caller that has to remember the sequence.</p>
	 *
	 * @param footY  the car-local foot Y the camera is pinned to (seat anchor minus eye height)
	 * @param floorY the car-local floor Y the floor boxes sit at
	 */
	public static void mmtrLockSeatAndPin(double footY, double floorY) {
		setSeatFootY(footY, "进驾驶室 mmtrLockSeatAndPin");
		mmtrFloorProbeY = floorY;
		mmtrCabLocked = true;
	}

	/**
	 * The single place that ends a ride: the player is no longer on the train, so the seat pin and the cab
	 * lock go with it.
	 *
	 * <p>Every path that removes the rider must call this rather than clearing the pin by hand, because the
	 * pin has to outlive everything ELSE that touches the riding state (a floor probe that misses for a
	 * tick, a cab-key reconciliation releasing the lock) and only die with the ride itself.</p>
	 */
	private static void markLeftTrain() {
		// The cab lock is what keeps the driver's key alive in MmtrCabInteraction, so whoever clears it had
		// better say so: "the cab key vanished while I was sitting in the seat" is otherwise unattributable.
		final StackTraceElement caller = new Throwable().getStackTrace()[1];
		org.mtr.mod.Init.LOGGER.info("[MMTR-RIDE] 离开列车（{}#{}）车辆={} 车内={} 座位锁存={}",
				caller.getClassName().substring(caller.getClassName().lastIndexOf('.') + 1), caller.getMethodName(),
				ridingVehicleId, ridingVehicleCarNumber,
				Double.isNaN(mmtrLockedSeatFootY) ? "无" : round3(mmtrLockedSeatFootY));
		mmtrDriverSynced = false;
		mmtrCabLocked = false;
		setSeatFootY(Double.NaN, "离开列车");
		mmtrFloorProbeY = Double.NaN;
	}

	/** Throttle for the floor-clamp diagnostic. */
	private static long mmtrLastFloorClampLogMillis;

	/** Why the last floor search came up empty, for the diagnostic. Null once one succeeds. */
	private static String mmtrFloorMiss;

	private static void recordFloorMiss(ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways, double x, double z) {
		if (mmtrFloorMiss != null) {
			return;
		}
		final StringBuilder builder = new StringBuilder();
		builder.append("probe=(").append(round3(x)).append(", ").append(round3(mmtrFloorProbeY)).append(", ").append(round3(z)).append(")");
		builder.append(" 瞄准锁存=").append(Double.isNaN(mmtrLockedSeatFootY) ? "无" : round3(mmtrLockedSeatFootY));
		builder.append(" 盒子 ").append(floorsAndDoorways.size()).append(" 个:");
		int shown = 0;
		for (final ObjectBooleanImmutablePair<Box> entry : floorsAndDoorways) {
			if (shown++ >= 6) {
				builder.append(" …");
				break;
			}
			final Box box = entry.left();
			builder.append(String.format(" [%s x %.2f..%.2f y %.2f..%.2f z %.2f..%.2f]",
					entry.rightBoolean() ? "地板" : "门洞",
					box.getMinXMapped(), box.getMaxXMapped(),
					box.getMinYMapped(), box.getMaxYMapped(),
					box.getMinZMapped(), box.getMaxZMapped()));
		}
		mmtrFloorMiss = builder.toString();
	}

	/**
	 * Every write to {@link #mmtrLockedSeatFootY} goes through here.
	 *
	 * <p>"It works for a moment and then the view drops" has exactly one shape of cause: something
	 * CLEARS the pin a tick or two after it is set, and the floor clamp takes back over. Guessing which
	 * of the four clear sites does it costs a game launch each time; naming the caller in the log costs
	 * nothing.</p>
	 */
	private static void setSeatFootY(double footY, String source) {
		final double previous = mmtrLockedSeatFootY;
		mmtrLockedSeatFootY = footY;
		if (Double.isNaN(previous) != Double.isNaN(footY) || !Double.isNaN(previous) && Math.abs(previous - footY) > 1.0E-6) {
			org.mtr.mod.Init.LOGGER.info("[MMTR-RIDE] 座位锁存 {} → {} （来源 {}）",
					Double.isNaN(previous) ? "无" : round3(previous),
					Double.isNaN(footY) ? "无" : round3(footY), source);
		}
	}

	/**
	 * Reports the floor clamp's verdict against the pinned seat height, a couple of times a second.
	 *
	 * <p>"The floor is still limiting it" is otherwise indistinguishable from "the pin was never set",
	 * "the pin was set then cleared", and "the clamp is fine and something else moves the camera". This
	 * prints the numbers that separate those cases: where the boxes are ({@link #mmtrFloorProbeY}), what
	 * the clamp wanted, what was used, and whether a pin is in force at all.</p>
	 */
	private static void logFloorClamp(double floorWantedY, double x, double y, double z) {
		mmtrFloorMiss = null;
		final long now = System.currentTimeMillis();		if (now - mmtrLastFloorClampLogMillis < 500) {
			return;
		}
		mmtrLastFloorClampLogMillis = now;
		org.mtr.mod.Init.LOGGER.info("[MMTR-RIDE] 地板盒在 y={} 钳制想要 y={} 实际用 y={} 座位锁存={} 驾驶位锁={} local=({}, {}, {})",
				round3(mmtrFloorProbeY), round3(floorWantedY), round3(y),
				Double.isNaN(mmtrLockedSeatFootY) ? "无" : round3(mmtrLockedSeatFootY),
				mmtrCabLocked,
				round3(x), round3(y), round3(z));
	}

	private static double round3(double value) {
		return Math.round(value * 1000) / 1000.0;
	}

	/**
	 * Reports why the floor search failed, once per half second.
	 *
	 * <p>"No floor under the player" has several causes that need opposite fixes: the probe Y being outside
	 * every box (a pin interaction), the probe X/Z being outside every box (position), or the car having no
	 * boxes at all (a resource pack problem). Printing the probe and the boxes separates them immediately
	 * instead of costing a round trip per hypothesis.</p>
	 */
	private static void logFloorMissOnce() {
		final long now = System.currentTimeMillis();
		if (mmtrFloorMiss == null || now - mmtrLastFloorMissLogMillis < 500) {
			return;
		}
		mmtrLastFloorMissLogMillis = now;
		org.mtr.mod.Init.LOGGER.info("[MMTR-RIDE] 找不到地板 → 下车：{}", mmtrFloorMiss);
	}

	private static long mmtrLastFloorMissLogMillis;

	/**
	 * MMTR B7.6d: puts the player inside a specific car at a fixed car-local point without requiring
	 * that point to intersect an open floor or doorway. Used when a driver takes a cab — the cab view
	 * point comes from the model anchors and the crew has to land exactly there — and when a player
	 * walks into a cab from outside the train.
	 *
	 * @param depotId    the depot the vehicle belongs to (needed by the driver key check)
	 * @param sidingId   the siding the vehicle belongs to
	 * @param vehicleId  the vehicle ID
	 * @param carNumber  the car index inside the consist
	 * @param x          car-local X (positive to the right of the car)
	 * @param y          car-local Y (floor level, in blocks)
	 * @param z          car-local Z (positive towards the rear of the car)
	 * @param vehicleYaw the car's current yaw, so the player's view does not jump on the next tick
	 * @param yawDegrees the world yaw the player should face, or {@link Double#NaN} to keep it
	 */
	public static void mmtrPlaceRiding(long depotId, long sidingId, long vehicleId, int carNumber, double x, double y, double z, double vehicleYaw, double yawDegrees) {
		if (ridingVehicleId != 0 && ridingVehicleId != vehicleId) {
			sendUpdate(true);
		}

		ridingDepotId = depotId;
		ridingSidingId = sidingId;
		ridingVehicleId = vehicleId;
		ridingVehicleCarNumber = carNumber;
		ridingVehicleCarNumberCacheOld = carNumber;
		ridingVehicleX = x;
		ridingVehicleY = y;
		ridingVehicleZ = z;
		isOnGangway = false;
		ridingVehicleCooldown = 0;
		ridingPositionCache = new Vector3d(x, y, z);
		ridingPositionCacheOld = ridingPositionCache;
		ridingYawDifference = null;
		previousVehicleYaw = vehicleYaw;
		mmtrDriverSynced = false;

		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity != null && !Double.isNaN(yawDegrees)) {
			EntityHelper.setYaw(new Entity(clientPlayerEntity.data), (float) yawDegrees);
		}

		sendUpdate(false);
	}

	public static int getMmtrThrottleNotch() {
		return mmtrThrottleNotch;
	}

	public static int getMmtrBrakeNotch() {
		return mmtrBrakeNotch;
	}

	public static int getMmtrReverser() {
		return mmtrReverser;
	}

	public static void overrideDoors() {
		final double oldDoorOverrideTicks = doorOverrideTicks;
		doorOverrideTicks = 2;
		if (oldDoorOverrideTicks == 0) {
			sendUpdate(false);
		}
	}

	/**
	 * @param depotId the {@link org.mtr.core.data.Depot} ID
	 * @return the driver key item that is valid for the depot ID (either a matching key or the {@link org.mtr.mod.item.ItemCreativeDriverKey})
	 */
	@Nullable
	public static ItemDriverKey getValidHoldingKey(long depotId) {
		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity != null) {
			final ItemStack itemStack1 = clientPlayerEntity.getMainHandStack();
			final Item item1 = itemStack1.getItem();
			if (item1.data instanceof ItemDriverKey) {
				return ItemDepotDriverKey.isCreativeDriverKeyOrMatchesDepot(itemStack1, depotId) ? (ItemDriverKey) item1.data : null;
			}

			final ItemStack itemStack2 = clientPlayerEntity.getOffHandStack();
			final Item item2 = itemStack2.getItem();
			if (item2.data instanceof ItemDriverKey) {
				return ItemDepotDriverKey.isCreativeDriverKeyOrMatchesDepot(itemStack2, depotId) ? (ItemDriverKey) item2.data : null;
			}
		}

		return null;
	}

	public static boolean showShiftProgressBar() {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final ClientPlayerEntity clientPlayerEntity = minecraftClient.getPlayerMapped();

		if (shiftHoldingTicks > 0 && clientPlayerEntity != null) {
			final int progressFilled = MathHelper.clamp((int) (shiftHoldingTicks * DISMOUNT_PROGRESS_BAR_LENGTH / SHIFT_ACTIVATE_TICKS), 0, DISMOUNT_PROGRESS_BAR_LENGTH);
			final String progressBar = String.format("§6%s§7%s", StringUtils.repeat('|', progressFilled), StringUtils.repeat('|', DISMOUNT_PROGRESS_BAR_LENGTH - progressFilled));
			clientPlayerEntity.sendMessage(TranslationProvider.GUI_MTR_DISMOUNT_HOLD.getText(InitClient.getShiftText(), progressBar), true);
			return false;
		} else {
			return true;
		}
	}

	/**
	 * Find an intersecting floor or doorway for a rider standing at {@code (x, z)}.
	 *
	 * <p>Probed at {@link #mmtrFloorProbeY}, which is where the boxes actually are. Then, if that finds
	 * nothing, a <b>second pass probes each box at its own top</b> and accepts the first one whose X/Z
	 * contains the rider.</p>
	 *
	 * <p>That second pass is the load-bearing part. The riding Y and the resource pack's floor boxes can
	 * disagree by a lot - a model raised to platform height moves its anchors up while the floor boxes stay
	 * put (measured on saf101: model floor 2.47, floor box 1.0, a 1.47 m gap) - and the first pass then
	 * misses, the caller sees an empty offset list, and it DISMOUNTS a player who is sitting perfectly
	 * well. Probing each box at its own top removes the assumption that anyone can name the right Y in
	 * advance: the boxes answer for themselves.</p>
	 */
	@Nullable
	private static ObjectBooleanImmutablePair<Box> bestPosition(ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsOrDoorways, double x, double z) {
		final ObjectBooleanImmutablePair<Box> direct = floorsOrDoorways.stream()
				.filter(floorOrDoorway -> RenderVehicleHelper.boxContains(floorOrDoorway.left(), x, mmtrFloorProbeY, z))
				.max(Comparator.comparingDouble(floorOrDoorway -> floorOrDoorway.left().getMaxYMapped()))
				.orElse(floorsOrDoorways.stream().filter(floorOrDoorway -> Math.abs(floorOrDoorway.left().getMaxYMapped() - mmtrFloorProbeY) <= 1).min(Comparator.comparingDouble(floorOrDoorway -> {
					final Box box = floorOrDoorway.left();
					final double minX = box.getMinXMapped();
					final double maxX = box.getMaxXMapped();
					final double minZ = box.getMinZMapped();
					final double maxZ = box.getMaxZMapped();
					return (Utilities.isBetween(x, minX, maxX) ? 0 : Math.min(Math.abs(minX - x), Math.abs(maxX - x))) + (Utilities.isBetween(z, minZ, maxZ) ? 0 : Math.min(Math.abs(minZ - z), Math.abs(maxZ - z)));
				})).orElse(null));
		if (direct != null) {
			return direct;
		}
		// Second pass: ask each box whether it contains the rider AT ITS OWN TOP.
		ObjectBooleanImmutablePair<Box> best = null;
		for (final ObjectBooleanImmutablePair<Box> entry : floorsOrDoorways) {
			final Box box = entry.left();
			if (!RenderVehicleHelper.boxContains(box, x, box.getMaxYMapped(), z)) {
				continue;
			}
			if (best == null || box.getMaxYMapped() > best.left().getMaxYMapped()) {
				best = entry;
			}
		}
		if (best != null) {
			// Adopt this box's height as the probe, so the following ticks take the direct path again.
			mmtrFloorProbeY = best.left().getMaxYMapped();
		}
		return best;
	}

	private static void clampPosition(ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways, double x, double z, ObjectArrayList<Vector3d> offsets) {
		final ObjectBooleanImmutablePair<Box> floorOrDoorway = bestPosition(floorsAndDoorways, x, z);

		if (floorOrDoorway != null) {
			if (floorOrDoorway.rightBoolean()) {
				// If the intersecting or closest floor or doorway is a floor, then force the player to be in bounds
				offsets.add(new Vector3d(
						MathUtils.clamp(x, floorOrDoorway.left().getMinXMapped(), floorOrDoorway.left().getMaxXMapped()) - x,
						floorOrDoorway.left().getMaxYMapped(),
						MathUtils.clamp(z, floorOrDoorway.left().getMinZMapped(), floorOrDoorway.left().getMaxZMapped()) - z
				));
			} else if (RenderVehicleHelper.boxContains(floorOrDoorway.left(), x, mmtrFloorProbeY, z)) {
				// If the intersecting or closest floor or doorway is a doorway, then don't force the player to be in bounds
				// Dismount if the player is not intersecting the doorway
				//
				// Probed at mmtrFloorProbeY, NOT at ridingVehicleY: the question here is "is the rider inside
				// the doorway box", and a pinned rider's Y is deliberately above where the boxes are. Testing
				// at the pinned Y answers "no" for a rider standing in a doorway on a raised model, and the
				// empty offset list then dismounts them.
				offsets.add(new Vector3d(0, floorOrDoorway.left().getMaxYMapped(), 0));
			}
		} else {
			// Nothing found for this probe: remember it, so logFloorClamp can report WHY the dismount below
			// is about to happen. "No floor" has several distinct causes (probe Y outside every box, probe
			// X/Z outside every box, or no boxes at all) and they need different fixes.
			recordFloorMiss(floorsAndDoorways, x, z);
		}
	}

	/**
	 * Moves the client player to absolute world coordinates right now and also at the end of the client tick.
	 * (If the player is not moved at the end of the client tick, there will be a rubber banding animation which will look weird when moving inside a vehicle.)
	 */
	private static void movePlayer(double x, double y, double z) {
		if (InitClient.getGameTick() > 40) {
			mmtrMovePlayerCalls++;
			final Runnable runnable = () -> {
				final MinecraftClient minecraftClient = MinecraftClient.getInstance();
				final ClientWorld clientWorld = minecraftClient.getWorldMapped();
				final ClientPlayerEntity clientPlayerEntity = minecraftClient.getPlayerMapped();
				if (clientPlayerEntity != null && clientWorld != null) {
					clientPlayerEntity.setFallDistanceMapped(0);
					clientPlayerEntity.setVelocity(0, 0, 0);
					clientPlayerEntity.setMovementSpeed(0);
					clientPlayerEntity.updatePosition(x, y, z);
				}
			};

			runnable.run();
			InitClient.scheduleMovePlayer(runnable);
		}
	}

	private static void sendUpdate(boolean dismount) {
		if (dismount) {
			final StackTraceElement caller = new Throwable().getStackTrace()[1];
			org.mtr.mod.Init.LOGGER.info("[MMTR-RIDE] 发出下车包（{}#{}）车辆={} 车内={}",
					caller.getClassName().substring(caller.getClassName().lastIndexOf('.') + 1),
					caller.getMethodName(), ridingVehicleId, ridingVehicleCarNumber);
		}
		if (ridingVehicleId != 0) {
			InitClient.REGISTRY_CLIENT.sendPacketToServer(PacketUpdateVehicleRidingEntities.create(ridingSidingId, ridingVehicleId, dismount ? -1 : ridingVehicleCarNumber, ridingVehicleX, ridingVehicleY, ridingVehicleZ, isOnGangway, isHoldingDriverKey, pressingAccelerateTicks == 1, pressingBrakeTicks == 1, pressingDoorsTicks == 1, pressingAtoTicks == 1, doorOverrideTicks > 1));
			sendPositionUpdateTime = 0;
		}
	}

	private static double getFromScale(double min, double max, double percentage) {
		return (max - min) * percentage + min;
	}
}
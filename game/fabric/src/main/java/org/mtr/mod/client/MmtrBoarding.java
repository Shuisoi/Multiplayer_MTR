package org.mtr.mod.client;

import org.mtr.core.data.VehicleCar;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.Init;
import org.mtr.mod.client.MmtrVehicleAnchors.Anchor;
import org.mtr.mod.client.MmtrVehicleAnchors.CabView;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.PositionAndRotation;

import javax.annotation.Nullable;

/**
 * Deletes the whole class of "the resource pack's boxes do not line up with the train" boarding bugs.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>MTR decides whether a player may board by asking whether the player's world position is INSIDE one
 * of the boxes the resource pack declares ({@code floors} and {@code doorways}), via
 * {@code RenderVehicleHelper.boxContains}. When the boxes line up with the model that is exactly right.
 * It stops being right the moment the two disagree, and then the failure is total and silent: the player
 * can stand in the doorway of a train, see it, aim at it, and be unable to get on - because the box the
 * test uses is somewhere else entirely. Moving, rotating or re-scaling a model is enough to cause it, and
 * so is raising a whole consist to platform height.</p>
 *
 * <p>That class of bug has no good symptom. "The doors are open but I cannot board", "I get flung off when
 * I walk in", "I can board at one platform but not another" are all the same root cause, and each one
 * costs a debugging round trip into the game.</p>
 *
 * <h2>The way out: anchors instead of boxes</h2>
 *
 * <p>The model already states where its doors are, in the {@code mmtr_cabdoor_<cab>_<n>} anchors, and
 * {@link MmtrVehicleAnchors} reads them out of the resource pack. An anchor is a POINT in the car's own
 * frame, so it moves with the model for free - there is nothing to keep in sync and nothing to line up.
 * So instead of testing a world-space box, this asks:</p>
 *
 * <ol>
 *   <li>which car is the player standing next to (nearest car centre, horizontally)?</li>
 *   <li>is any of that car's door anchors within {@link #REACH_M} of the player?</li>
 *   <li>if so, bind the player to the car AT THAT ANCHOR's car-local position.</li>
 * </ol>
 *
 * <p>Which is what the user asked for: <b>hard-bind the view and the player position to the train</b>.
 * There is no box to be misaligned, so raising the model, changing its floor height, or editing the
 * doorway geometry cannot break boarding any more.</p>
 *
 * <h2>What it does NOT replace</h2>
 *
 * <p>The resource-pack boxes still drive MTR's own things - the door position, the gangway, where the
 * player may WALK while already on board ({@code movePlayer} tests them every tick). This only replaces
 * the ENTRY TEST, which is the part that was fragile. Walking around inside is untouched.</p>
 *
 * <h2>Discipline</h2>
 *
 * <p>Three rules keep a hard bind from becoming a trap: it only fires for a train that is effectively
 * stopped and has a driver close by is NOT required, it never fights an existing ride, and it says so in
 * the action bar rather than teleporting the player in silence.</p>
 */
public final class MmtrBoarding {

	private MmtrBoarding() {
	}

	/**
	 * How close (blocks) the player must be to a car's door anchor to be put on board, measured
	 * HORIZONTALLY. Deliberately tight: this is "standing in the doorway", not "somewhere on the
	 * platform". Holding sneak overrides it entirely, so there is always a way to stand next to a train
	 * without being put on it.
	 */
	private static final double REACH_M = 2.0;
	/**
	 * Vertical slack (blocks) around the door anchor. Larger than the horizontal reach because a raised
	 * or lowered model is THE case this exists for - a train lifted to platform height can easily be a
	 * block and a half above where the player stands.
	 */
	private static final double REACH_Y_M = 2.5;
	/** The train must be slower than this (m/ms) to be boarded; walking onto a moving train is not a thing. */
	private static final double MAX_BOARDING_SPEED_M_PER_MS = 1.0E-4;
	/**
	 * How far inside the door the player is put when the car has no usable seat point, so they do not
	 * stand in the door frame itself.
	 */
	private static final double STEP_IN_M = 0.55;
	/**
	 * Standard Minecraft eye height: the camera sits this far above the entity's feet.
	 */
	private static final double EYE_HEIGHT_M = 1.62;
	/**
	 * How far below the resolved point the FOOT position is placed, so that the CAMERA lands on it.
	 *
	 * <p>{@code mmtr_seat_<cab>} is authored where the driver's eyes go, not where their feet go. The
	 * riding state's Y is a FOOT position - the camera is then {@link #EYE_HEIGHT_M} above it - so the
	 * foot Y has to be one eye height below the anchor or the view floats a head's height too high.</p>
	 */
	private static final double SEAT_EYE_DROP_M = EYE_HEIGHT_M;
	/** How long a "boarding failed" message stays suppressed, so a stuck player is not spammed per frame. */
	private static final long MESSAGE_COOLDOWN_MILLIS = 3000;

	private static long lastMessageMillis;
	/** Placement already reported, so the one-shot diagnostic fires once per car+model+door. */
	private static final java.util.Set<String> PLACEMENT_LOGGED = new java.util.HashSet<>();

	/**
	 * Where the local player is pinned, so the seat can be RE-ASSERTED every tick.
	 *
	 * <p>The pin cannot be a one-shot applied at the door. Two things clear it - the floor clamp's own
	 * "no floor under the rider" dismount, and any code path that resets the cab lock - and once it is
	 * clear, nothing re-applies it, because {@code boardNearestTrain} deliberately skips the vehicle you
	 * are already riding (otherwise it would fight the walking code every tick). The observable result was
	 * "it works for a moment and then the view drops to the floor", with {@code 座位锁存=无} in the log
	 * while still riding. Re-asserting from the stored descriptor makes the pin self-healing, which is what
	 * "hard-bind the view to the seat" actually needs to mean.</p>
	 */
	private static long pinnedVehicleId;
	private static int pinnedCarNumber;
	private static double pinnedX;
	private static double pinnedFootY;
	private static double pinnedZ;
	private static double pinnedFloorY = Double.NaN;

	/**
	 * Called once per client tick from {@link MmtrCabInteraction#tick()}, right next to the cab-key
	 * handling, so boarding and taking a cab agree on the same tick.
	 */
	public static void tick() {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null || OptimizedRenderer.renderingShadows()) {
			return;
		}
		// Self-healing re-assertion first: if this client is riding the car it pinned, the pin is restored
		// before anything else can look at a stale one. Costs nothing when the pin is already in place.
		reassertPin();
		// Sneak = "I am standing here on purpose". Without an explicit opt-out a proximity bind becomes a
		// tractor beam, and a player who wants to walk along the platform would be unable to.
		if (((net.minecraft.entity.player.PlayerEntity) player.data).isSneaking()) {
			return;
		}
		boardNearestTrain(player);
	}

	/**
	 * Re-applies the stored seat pin if this client is still riding the car it was pinned to, and forgets
	 * it entirely once the ride ends.
	 *
	 * <p>Deliberately does NOT call {@code mmtrPlaceRiding}: the position is already right, and re-placing
	 * would send a riding-entity packet every tick. Only the Y pin, which something else may have cleared,
	 * is restored.</p>
	 *
	 * <p><b>Yields to a cab taken by key.</b> There are two ways to end up in the same seat - this class
	 * putting you there by proximity to the door, and the driver pressing the cab key - and they resolve
	 * the seat independently. When both were live they fought: the key put the rider at the seat for the
	 * cab it named, this class re-pinned them at the seat for the door it had chosen, and the rider was
	 * teleported between the two ends of the car (measured: the two anchors are 2 x 5.096 = 10.2 blocks
	 * apart, exactly the correction in the log) several times a second. An explicit cab claim wins, because
	 * it is the intentional one.</p>
	 */
	private static void reassertPin() {
		if (pinnedVehicleId == 0) {
			return;
		}
		if (!VehicleRidingMovement.isRiding(pinnedVehicleId)) {
			// Ride over (or moved to another vehicle): the pin belongs to the old car.
			pinnedVehicleId = 0;
			pinnedFloorY = Double.NaN;
			return;
		}
		if (VehicleRidingMovement.mmtrHasCabLock()) {
			// The rider took this cab with the key: its seat point, not ours, is authoritative.
			pinnedVehicleId = 0;
			pinnedFloorY = Double.NaN;
			return;
		}
		VehicleRidingMovement.mmtrPinSeatFootY(pinnedFootY, pinnedFloorY);
	}

	/**
	 * Binds the player to the nearest boardable train, if there is one within reach.
	 *
	 * <p>Returns immediately when the player is already on board, so this never fights
	 * {@code VehicleRidingMovement.movePlayer} - which is what actually walks the player around inside
	 * the car once this has put them in it.</p>
	 */
	private static void boardNearestTrain(ClientPlayerEntity player) {
		final double playerX = player.getX();
		final double playerY = player.getY();
		final double playerZ = player.getZ();

		VehicleExtension bestVehicle = null;
		int bestCarNumber = -1;
		Anchor bestDoor = null;
		double bestDistanceSquared = REACH_M * REACH_M;

		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			if (VehicleRidingMovement.isRiding(vehicle.getId()) || Math.abs(vehicle.getSpeed()) > MAX_BOARDING_SPEED_M_PER_MS) {
				continue;
			}
			final ObjectArrayList<CarAndRotation> cars = carsOf(vehicle);
			for (int carNumber = 0; carNumber < cars.size(); carNumber++) {
				final CarAndRotation car = cars.get(carNumber);
				final ObjectArrayList<Anchor> anchors = MmtrVehicleAnchors.get(car.vehicleId);
				if (anchors.isEmpty()) {
					continue;
				}
				for (final Anchor anchor : anchors) {
					if (anchor.kind != MmtrVehicleAnchors.Kind.CABDOOR) {
						continue;
					}
					// Car-local -> car centre-relative, then to world, the same way the lift and cab code do.
					final Vector world = car.positionAndRotation.transformForwards(anchor.position, Vector::rotateX, Vector::rotateY, Vector::add);
					final double deltaX = world.x() - playerX;
					final double deltaY = world.y() - playerY;
					final double deltaZ = world.z() - playerZ;
					if (Math.abs(deltaY) > REACH_Y_M) {
						continue;
					}
					// Horizontal distance alone decides "which door am I standing at": the vertical is
					// already gated above and must NOT be able to veto a raised model.
					final double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
					if (distanceSquared < bestDistanceSquared) {
						bestDistanceSquared = distanceSquared;
						bestVehicle = vehicle;
						bestCarNumber = carNumber;
						bestDoor = anchor;
					}
				}
			}
		}

		if (bestVehicle == null || bestDoor == null) {
			return;
		}
		placeOnCar(player, bestVehicle, bestCarNumber, bestDoor);
	}

	/**
	 * Binds the player to the car at the resolved SEAT point, stepped out of the door frame.
	 *
	 * <p>The destination is the same point taking a cab uses ({@link MmtrVehicleAnchors#cabView}), which
	 * resolves in this order: an explicit {@code mmtr_seat_<cab>} anchor wins, otherwise the seat is
	 * derived from the dashboard. That anchor is authored to be where the DRIVER'S EYES GO, so binding to
	 * it is what makes "board the train" and "sit down in the cab" land on the same spot.</p>
	 *
	 * <p>Binding to the DOOR anchor instead (the first version of this class) gets the player onto the
	 * train but leaves them standing in the doorway: the door anchor is at the car's side, while the seat
	 * is in front of the desk. The camera ends up a couple of blocks from where the model says the driver
	 * sits, which is exactly what "上去了但视角不在 mmtr_seat 上" looks like.</p>
	 */
	private static void placeOnCar(ClientPlayerEntity player, VehicleExtension vehicle, int carNumber, Anchor door) {
		final ObjectArrayList<CarAndRotation> cars = carsOf(vehicle);
		if (carNumber < 0 || carNumber >= cars.size()) {
			return;
		}
		final CarAndRotation car = cars.get(carNumber);
		final int modelCar = modelCarIndex(cars, carNumber);

		// The seat point, resolved the same way the cab key resolves it. Null only when the model has no
		// cab door at all, which cannot happen here - the caller found one.
		final CabView view = MmtrVehicleAnchors.cabView(MmtrVehicleAnchors.get(car.vehicleId), door.cab <= 0 ? 1 : door.cab);
		final Anchor seatAnchor = MmtrVehicleAnchors.findSeat(MmtrVehicleAnchors.get(car.vehicleId), view != null && view.mirrored ? 1 : (door.cab <= 0 ? 1 : door.cab));

		final double localX;
		final double localY;
		final double localZ;
		final double worldYawDegrees;
		/** The car's FLOOR Y, which is where the resource pack's floor boxes live. */
		final double floorY;
		if (view != null) {
			localX = view.x;
			localZ = view.z;
			// The camera's X/Z in the car is the rider's X/Z (RenderVehicles reads the riding position as
			// the camera offset), so these two numbers ARE "is the view on mmtr_seat".
			//
			// Y is the subtle one, and the version that shipped before this line was WRONG:
			//
			//   * the riding state's Y is a FOOT position (the camera sits EYE_HEIGHT above it), and
			//   * `mmtr_seat_<cab>` is authored at the driver's EYE height, and
			//   * `movePlayer` OVERWRITES the Y from the floor-clamp box EVERY TICK.
			//
			// So the Y given here is only the first frame; after that the resource pack's floor box owns
			// it. Setting it to `seat.y - 1.62` therefore does not put the camera on the seat - it puts it
			// wherever the floor box says, which is the "视角过于低" report. The fix is to PIN the seat Y
			// (mmtrPinSeatFootY), which makes movePlayer keep this value instead of the floor clamp's.
			final double eyeY = seatAnchor == null ? view.y + SEAT_EYE_DROP_M : seatAnchor.position.y();
			localY = eyeY - SEAT_EYE_DROP_M;
			floorY = view.y;
			worldYawDegrees = yawFacingDirection(car.positionAndRotation, view.forwardX, view.forwardZ);
		} else {
			// No cab view (a car with a door anchor but nothing to sit at): put the player just inside the
			// door and face them down the aisle, so boarding still works on a model that is only half
			// furnished.
			final double towardCentre = door.position.z() >= 0 ? -1 : 1;
			localX = door.position.x();
			floorY = door.position.y() - door.heightM / 2;
			localY = floorY;
			localZ = door.position.z() + towardCentre * STEP_IN_M;
			worldYawDegrees = yawFacingDirection(car.positionAndRotation, 0, towardCentre);
		}

		VehicleRidingMovement.mmtrPlaceRiding(
				vehicle.vehicleExtraData.getDepotId(),
				vehicle.vehicleExtraData.getSidingId(),
				vehicle.getId(),
				carNumber,
				localX,
				localY,
				localZ,
				car.positionAndRotation.yaw,
				worldYawDegrees
		);
		// A boarded player is free to walk (no cab lock) but their eye height comes from the model's seat
		// anchor rather than from the resource pack's floor box - hence mmtrPinSeatFootY and not
		// mmtrSetCabLock. That distinction is the whole reason this class needed two attempts at the Y.
		//
		// floorY goes with it: the "is the player on a floor?" search has to probe where the floor boxes
		// are, which is NOT the pinned seat height.
		VehicleRidingMovement.mmtrPinSeatFootY(localY, floorY);
		// Remembered so tick() can re-assert it: the pin is cleared by the floor clamp's own dismount path,
		// and nothing else would ever put it back. See pinnedVehicleId.
		pinnedVehicleId = vehicle.getId();
		pinnedCarNumber = carNumber;
		pinnedX = localX;
		pinnedFootY = localY;
		pinnedZ = localZ;
		pinnedFloorY = floorY;
		logPlacementOnce(vehicle, carNumber, modelCar, door, view, seatAnchor, localX, localY, localZ, worldYawDegrees);
		sendMessage(player, "已上车（按锚点硬绑）/ on board");
	}

	/**
	 * One-shot placement dump, per model. This is the diagnostic that turns "the camera is in the wrong
	 * place" into two numbers to compare - the seat anchor the model authored versus where the rider was
	 * actually put - and it names which of the two resolution paths was taken.
	 */
	private static void logPlacementOnce(VehicleExtension vehicle, int carNumber, int modelCar, Anchor door, @Nullable CabView view, @Nullable Anchor seatAnchor,
			double localX, double localY, double localZ, double yaw) {
		final String key = carNumber + ":" + modelCar + ":" + door.name;
		if (!PLACEMENT_LOGGED.add(key)) {
			return;
		}
		if (seatAnchor == null) {
			Init.LOGGER.info("[MMTR-BOARD] door={} → 无 mmtr_seat，用门的推导点 local=({}, {}, {}) yaw={}",
					door.name, round(localX), round(localY), round(localZ), round(yaw));
		} else {
			Init.LOGGER.info("[MMTR-BOARD] door={} → mmtr_seat ride-space=({}, {}, {})  [cabView x/z=({}, {}) 户内地板 y={}]",
					door.name, round(seatAnchor.position.x()), round(seatAnchor.position.y()), round(seatAnchor.position.z()),
					round(view == null ? 0 : view.x), round(view == null ? 0 : view.z), round(view == null ? 0 : view.y));
			Init.LOGGER.info("[MMTR-BOARD] 绑定 local=({}, {}, {})（X/Z 取座位锚点；Y 交给 movePlayer 的地板钳制：相机 = 地板 + 1.62）yaw={}",
					round(localX), round(localY), round(localZ), round(yaw));
		}
	}

	private static double round(double value) {
		return Math.round(value * 100) / 100.0;
	}

	/**
	 * The world yaw for a car-local horizontal direction. Derived from the car's transform rather than
	 * from an anchor's normal, because a door faces sideways along the car while the person coming in
	 * should end up looking down the aisle.
	 */
	private static double yawFacingDirection(PositionAndRotation carRotation, double localX, double localZ) {
		final Vector direction = new Vector(localX, 0, localZ).rotateX(carRotation.pitch).rotateY(carRotation.yaw);
		if (Math.abs(direction.x()) < 1.0E-6 && Math.abs(direction.z()) < 1.0E-6) {
			return Double.NaN;
		}
		// Minecraft yaw: 0 = +Z, 90 = -X.
		return Math.toDegrees(Math.atan2(-direction.x(), direction.z()));
	}

	private static void sendMessage(ClientPlayerEntity player, String message) {
		final long now = System.currentTimeMillis();
		if (now - lastMessageMillis < MESSAGE_COOLDOWN_MILLIS) {
			return;
		}
		lastMessageMillis = now;
		player.sendMessage(new Text(TextHelper.literal(message).data), true);
	}

	/** The consist's cars with their model ID and world transform; same shape the cab code uses. */
	private static ObjectArrayList<CarAndRotation> carsOf(VehicleExtension vehicle) {
		final ObjectArrayList<CarAndRotation> result = new ObjectArrayList<>();
		final boolean hasPitch = vehicle.getTransportMode().hasPitchAscending || vehicle.getTransportMode().hasPitchDescending;
		for (final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<org.mtr.core.data.Vehicle.BogiePosition>> carAndPositions : vehicle.getVehicleCarsAndPositions()) {
			final VehicleCar vehicleCar = carAndPositions.left();
			final ObjectArrayList<PositionAndRotation> bogiePositions = new ObjectArrayList<>();
			for (final org.mtr.core.data.Vehicle.BogiePosition bogiePosition : carAndPositions.right()) {
				bogiePositions.add(new PositionAndRotation(bogiePosition.positionAndTiltAngle1().position(), bogiePosition.positionAndTiltAngle2().position(), true));
			}
			result.add(new CarAndRotation(vehicleCar.getVehicleId(), new PositionAndRotation(bogiePositions, vehicleCar, hasPitch)));
		}
		return result;
	}

	/** The car index inside the model for a consist car (a model can be used several times). */
	private static int modelCarIndex(ObjectArrayList<CarAndRotation> cars, int carNumber) {
		int index = 0;
		for (int i = 0; i < carNumber; i++) {
			if (cars.get(i).vehicleId.equals(cars.get(carNumber).vehicleId)) {
				index++;
			}
		}
		return index;
	}

	private static final class CarAndRotation {

		private final String vehicleId;
		private final PositionAndRotation positionAndRotation;

		private CarAndRotation(String vehicleId, PositionAndRotation positionAndRotation) {
			this.vehicleId = vehicleId;
			this.positionAndRotation = positionAndRotation;
		}
	}
}

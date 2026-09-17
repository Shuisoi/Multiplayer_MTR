package org.mtr.mod.client;

import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.Camera;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.client.MmtrVehicleAnchors.Anchor;
import org.mtr.mod.client.MmtrVehicleAnchors.CabView;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.packet.PacketMmtrCabOp;
import org.mtr.mod.render.PositionAndRotation;

import javax.annotation.Nullable;
import java.util.stream.Collectors;

/**
 * B7.6d: the "look at a cab door and press G" interaction (design §3.6.4).
 *
 * <p>The crew walks into the train like any passenger, walks to the cab door, looks at it and presses
 * the cab key. The door is found through the {@code mmtr_cabdoor_<cab>_<n>} anchors of the model
 * ({@link MmtrVehicleAnchors}), so the interaction targets the door the player actually aims at
 * rather than the nearest end of the train. Taking a cab also snaps the player to that cab's view
 * point, which is what "the driver is fixed at a point in the train" means in practice.</p>
 *
 * <p>Pressing the key while holding a cab (and not aiming at a door) pulls the key instead. The
 * engine still owns the gates: this class only picks the target, sends the request and moves the
 * player's own view.</p>
 */
public final class MmtrCabInteraction {

	private MmtrCabInteraction() {
	}

	/** How close (blocks) the player must stand to a cab door to reach it. */
	private static final double REACH_M = 5.0;
	/** How far off the crosshair (degrees) a cab door may be and still be "aimed at". */
	private static final double MAX_AIM_ANGLE_DEGREES = 40;
	/**
	 * Standard Minecraft eye height: the riding Y is a FOOT position and the camera sits this far above
	 * it, so a seat anchor (authored at eye height) has to come down by this much to become a foot Y.
	 */
	private static final double CAB_SEAT_EYE_HEIGHT_M = 1.62;
	/** Fallback drop from {@code cabView.y} when the model has no {@code mmtr_seat} anchor. */
	private static final double CAB_SEAT_FLOOR_DROP_M = 0.05;
	/** How often the action bar prompt is refreshed, in ticks. */
	private static final int PROMPT_INTERVAL_TICKS = 10;

	/** Key edge detection: the mapping key only exposes isPressed(). */
	private static boolean lastPressed = false;
	private static int promptCooldown = 0;

	/** The cab this client believes it holds; the engine is still the authority. */
	private static long heldVehicleId = 0;
	private static int heldCab = 0;
	/** C6: the consist car the held cab sits in (a double-ended loco has two cabs in one car). */
	private static int heldCarNumber = -1;
	/** When the current claim was made; reconciliation waits for the server's mirrored answer. */
	private static long claimMillis = 0;

	/** Throttle for the temporary "what does this client actually see" diagnostic. */
	private static long lastVehicleDebugMillis = 0;

	/**
	 * Grace period before the mirrored cab state may contradict a fresh claim: the request is a packet
	 * plus one engine tick plus the vehicle update back, so an immediate check would drop a cab that
	 * was in fact granted.
	 */
	private static final long CLAIM_GRACE_MILLIS = 1500;

	/** Whether this client is (still) the crew member holding {@code vehicleId}'s cab key. */
	public static boolean holdsCab(long vehicleId) {
		return heldVehicleId != 0 && heldVehicleId == vehicleId;
	}

	/**
	 * Whether this client holds ANY cab. Used by driver-workstation inputs that do not care which train
	 * they are in (the wiper stalk), so a passenger cannot reach them.
	 */
	public static boolean holdsAnyCab() {
		return heldVehicleId != 0;
	}

	public static void tick() {
		final boolean pressed = KeyBindings.MMTR_CAB_INTERACT.isPressed();
		final boolean justPressed = pressed && !lastPressed;
		lastPressed = pressed;

		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return;
		}

		logVehicleDebug(player);

		// Hard-bind boarding. Runs BEFORE the cab key handling so that pressing G while standing at the
		// door works on a player who has just been put on board, and so that a misaligned resource-pack
		// box can never again be the difference between "can board" and "cannot board" (see MmtrBoarding).
		MmtrBoarding.tick();

		// Forget a stale hold as soon as the player is no longer riding that consist.
		//
		// "No longer riding" is judged by the CAB LOCK, not by `isRiding` alone. The riding state is
		// cleared by things that have nothing to do with leaving the seat - a floor probe missing for a
		// tick, the vehicle stream stuttering - and releasing the key on those made the driver loss look
		// like "上车一会就被判定为下车": the ride recovered by itself and the key did not. The cab lock is
		// only cleared when the rider is genuinely out (see VehicleRidingMovement.markLeftTrain).
		if (heldVehicleId != 0 && !VehicleRidingMovement.isRiding(heldVehicleId) && !VehicleRidingMovement.mmtrHasCabLock()) {
			forget(player, null);
		} else {
			// 钥匙归属: the client only claims a cab the engine actually gave it. The key holder is
			// mirrored in every vehicle update (mmtrActiveCab / mmtrCabKeyHolder / mmtrCabCrew), so a
			// refused or stolen cab is corrected on the next snapshot instead of leaving this client
			// convinced it may drive.
			reconcile(player);
		}

		final AimTarget target = findAimTarget();
		if (justPressed) {
			handle(player, target);
		}

		if (promptCooldown > 0) {
			promptCooldown--;
		} else if (target != null) {
			final boolean holdsThisCab = heldVehicleId == target.vehicle.getId() && heldCab == target.end && heldCarNumber == target.carNumber;
			player.sendMessage(new Text(TextHelper.literal(holdsThisCab ? KEY_OUT_PROMPT : enterPrompt(target.carNumber, target.end)).data), true);
			promptCooldown = PROMPT_INTERVAL_TICKS;
		}
	}

	/**
	 * Temporary diagnostic (B7.6e acceptance): every two seconds report how many vehicles this client
	 * knows about, where the nearest one is relative to the player and which models they use, so a
	 * "I cannot see the train" report can be split into "the client has no vehicle" versus
	 * "the vehicle is far away" versus "the model is not drawn".
	 */
	private static void logVehicleDebug(ClientPlayerEntity player) {
		final long now = System.currentTimeMillis();
		if (now - lastVehicleDebugMillis < 2000) {
			return;
		}
		lastVehicleDebugMillis = now;

		final var vehicles = MinecraftClientData.getInstance().vehicles;
		double nearest = -1;
		String nearestId = "-";
		String nearestModels = "-";
		String nearestCarPosition = "-";
		boolean nearestRayTracing = false;
		int rayTracingCars = 0;
		for (final VehicleExtension vehicle : vehicles) {
			final boolean[] rayTracing = vehicle.persistentVehicleData.rayTracing;
			for (int i = 0; i < rayTracing.length; i++) {
				if (rayTracing[i]) {
					rayTracingCars++;
				}
			}
			final double distance = Math.sqrt(Math.max(0, nearestCarDistanceSquared(vehicle, player.getX(), player.getY(), player.getZ())));
			if (nearest < 0 || distance < nearest) {
				nearest = distance;
				nearestId = String.valueOf(vehicle.getId());
				nearestRayTracing = rayTracing.length > 0 && rayTracing[0];
				final StringBuilder models = new StringBuilder();
				for (final var car : vehicle.getVehicleCarsAndPositions()) {
					if (models.length() > 0) {
						models.append(',');
					}
					models.append(car.left().getVehicleId());
					if (car.right().isEmpty()) {
						continue;
					}
					final var bogie = car.right().get(0).positionAndTiltAngle1().position();
					nearestCarPosition = String.format("%.1f,%.1f,%.1f", bogie.x(), bogie.y(), bogie.z());
				}
				nearestModels = models.toString();
			}
		}
		Init.LOGGER.info("[MMTR-DBG] client vehicles={} rayTracingCars={} optimizedRendering={} nearest={} distance={} models=[{}] carPos=({}) rayTracing={} player=({}, {}, {})", vehicles.size(), rayTracingCars, OptimizedRenderer.hasOptimizedRendering(), nearestId, nearest < 0 ? "-" : String.format("%.1f", nearest), nearestModels, nearestCarPosition, nearestRayTracing, String.format("%.1f", player.getX()), String.format("%.1f", player.getY()), String.format("%.1f", player.getZ()));
	}

	private static void handle(ClientPlayerEntity player, @Nullable AimTarget target) {
		if (target != null) {
			if (heldVehicleId == target.vehicle.getId() && heldCab == target.end && heldCarNumber == target.carNumber) {
				leaveCab(player);
			} else {
				enterCab(player, target);
			}
			return;
		}

		// Not aiming at a cab door: the key works the doors of the train within reach instead, so a
		// train standing with its doors closed can be opened from the platform. No cab teleport here -
		// the crew has to aim at the cab door to take or leave a cab.
		final VehicleExtension nearest = nearestVehicle(player);
		if (nearest == null) {
			player.sendMessage(new Text(TextHelper.literal("附近没有列车 / no train within reach").data), true);
			return;
		}
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(nearest.getId(), PacketMmtrCabOp.Op.DOORS, ""));
		player.sendMessage(new Text(TextHelper.literal("开门/关门 / doors toggled").data), true);
	}

	private static void leaveCab(ClientPlayerEntity player) {
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(heldVehicleId, PacketMmtrCabOp.Op.LEAVE, ""));
		forget(player, "拔出钥匙，离开驾驶室 / key out");
	}

	/** Drops the local cab claim (and the seat lock); {@code message} is shown when not {@code null}. */
	private static void forget(@Nullable ClientPlayerEntity player, @Nullable String message) {
		heldVehicleId = 0;
		heldCab = 0;
		heldCarNumber = -1;
		claimMillis = 0;
		// Back to being a passenger: free to walk again.
		VehicleRidingMovement.mmtrSetCabLock(false);
		if (player != null && message != null) {
			player.sendMessage(new Text(TextHelper.literal(message).data), true);
		}
	}

	/**
	 * Checks the local cab claim against the authoritative mirrored cab state of the vehicle.
	 *
	 * <p>The server mirrors {@code mmtrActiveCab} / {@code mmtrCabKeyHolder} / {@code mmtrCabCrew} with
	 * every update, so a REFUSED claim is corrected without an extra packet.</p>
	 *
	 * <p>What it must NOT do is treat an imperfect mirror as a refusal. The previous version dropped the
	 * key the moment the mirror did not match exactly, and the mirror is not exact while a claim is still
	 * settling, while the consist changes ends, or while a vehicle update is in flight. The player was then
	 * sitting in the driver's seat with no key - reported as "在驾驶位上按 J 提示需要坐在驾驶座上", because
	 * the wiper stalk (and everything else keyed on the cab) reads the local hold.</p>
	 *
	 * <p>So: another crew member holding the key is decisive and the claim is dropped. Anything else that
	 * disagrees is treated as "not yet settled" and the claim is simply SENT AGAIN, at most once per
	 * {@link #CLAIM_RETRY_MILLIS}.</p>
	 */
	private static void reconcile(ClientPlayerEntity player) {
		if (heldVehicleId == 0 || System.currentTimeMillis() - claimMillis < CLAIM_GRACE_MILLIS) {
			return;
		}
		final VehicleExtension vehicle = findVehicle(heldVehicleId);
		if (vehicle == null) {
			return;
		}
		final String activeCab = vehicle.getMmtrActiveCabFromSync();
		final String expectedCab = heldCab == 2 ? "CAB_B" : "CAB_A";
		final String holder = vehicle.getMmtrCabKeyHolderFromSync();
		final String crew = vehicle.getMmtrCabCrewFromSync();
		final String localUuid = player.getUuid() == null ? "" : player.getUuid().toString();
		final boolean sameCar = vehicle.getMmtrCabCarIndexFromSync() == heldCarNumber + 1;
		if (activeCab.equals(expectedCab) && sameCar && "CREW".equals(holder) && (crew.isEmpty() || crew.equals(localUuid))) {
			// Everything agrees: the claim stands.
			return;
		}
		if (activeCab.isEmpty() && holder.isEmpty()) {
			// No cab state mirrored at all (legacy path vehicle / older server): keep the old behaviour.
			return;
		}
		if ("CREW".equals(holder) && !crew.isEmpty() && !crew.equals(localUuid)) {
			// Another crew member really does hold this cab. That is decisive.
			forget(player, "驾驶室已不属于你（钥匙在 " + crew + "）");
			return;
		}
		// Nothing contradicts us except the mirror being behind. Re-assert rather than give up.
		reclaim(player);
	}

	/**
	 * Sends the cab claim again, throttled. Used when the mirror has not caught up yet: the alternative is
	 * to give up a cab the player is physically sitting in, which is strictly worse than a repeated
	 * request.
	 */
	private static void reclaim(ClientPlayerEntity player) {
		final long now = System.currentTimeMillis();
		if (now - lastReclaimMillis < CLAIM_RETRY_MILLIS) {
			return;
		}
		lastReclaimMillis = now;
		claimMillis = now;
		final String cabName = (heldCarNumber + 1) + (heldCab == 2 ? "B" : "A");
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(heldVehicleId, PacketMmtrCabOp.Op.ENTER, cabName));
		if (player != null) {
			player.sendMessage(new Text(TextHelper.literal("重新认领驾驶室 " + cabName + " / re-claiming cab").data), true);
		}
	}

	/** When the last re-claim was sent, so a persistent mismatch cannot become a packet storm. */
	private static long lastReclaimMillis;
	/** How often a disagreeing mirror may be answered with another claim. */
	private static final long CLAIM_RETRY_MILLIS = 2000;

	@Nullable
	private static VehicleExtension findVehicle(long vehicleId) {
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			if (vehicle.getId() == vehicleId) {
				return vehicle;
			}
		}
		return null;
	}

	private static void enterCab(ClientPlayerEntity player, AimTarget target) {
		if (!MmtrCabPermissions.canBoard(player, target.vehicle.getId())) {
			player.sendMessage(new Text(TextHelper.literal("没有进入该驾驶室的权限 / not authorised").data), true);
			return;
		}
		final String cabName = (target.carNumber + 1) + (target.end == 2 ? "B" : "A");
		final boolean changing = heldCab != 0 && (heldCab != target.end || heldCarNumber != target.carNumber);
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(target.vehicle.getId(), PacketMmtrCabOp.Op.ENTER, cabName));
		heldVehicleId = target.vehicle.getId();
		heldCab = target.end;
		heldCarNumber = target.carNumber;
		claimMillis = System.currentTimeMillis();

		final boolean snapped = placeAtCabView(target.vehicle, target.carNumber, target.end);
		player.sendMessage(new Text(TextHelper.literal((changing ? "换到 " : "") + enterPrompt(target.carNumber, target.end) + (snapped ? "" : "（该模型缺少 mmtr_ 锚点，未移动视角）")).data), true);
	}

	/**
	 * Moves the player's riding state to the cab's view point. The cab is identified by its consist
	 * car and end, so a double-ended locomotive's two cabs (same car, opposite ends) and a coupled
	 * formation's interior cab all resolve to their own model anchors.
	 *
	 * @return true when the view point could be resolved and applied
	 */
	private static boolean placeAtCabView(VehicleExtension vehicle, int carNumber, int end) {
		final ObjectArrayList<CarTransform> cars = carTransforms(vehicle);
		if (cars.isEmpty() || carNumber < 0 || carNumber >= cars.size()) {
			return false;
		}

		final String modelId = cars.get(carNumber).vehicleId;
		final ObjectArrayList<Anchor> anchors = MmtrVehicleAnchors.get(modelId);
		final CabView view = MmtrVehicleAnchors.cabView(anchors, end);
		if (view == null) {
			Init.LOGGER.info("[MMTR] cab {} of car {} (vehicle {}, model {}): no mmtr_cabdoor_{}_* anchor found ({} anchors loaded)", end, carNumber + 1, vehicle.getId(), modelId, end, anchors.size());
			return false;
		}
		Init.LOGGER.info("[MMTR] cab {} of car {} (vehicle {}, model {}): seat car-local ({}, {}, {}) mirrored={}", end, carNumber + 1, vehicle.getId(), modelId, view.x, view.y, view.z, view.mirrored);

		final PositionAndRotation carRotation = cars.get(carNumber).rotation;

		// The driver faces the direction the dashboard faces away from (car-local, taken from the
		// anchor normal), so the view stays correct whichever end of the model the cab sits at.
		final Vector forward = new Vector(view.forwardX, 0, view.forwardZ).rotateX(carRotation.pitch).rotateY(carRotation.yaw);
		final double yawDegrees = Math.toDegrees(Math.atan2(-forward.x(), forward.z()));

		VehicleRidingMovement.mmtrPlaceRiding(
				vehicle.vehicleExtraData.getDepotId(),
				vehicle.vehicleExtraData.getSidingId(),
				vehicle.getId(),
				carNumber,
				view.x,
				view.y,
				view.z,
				carRotation.yaw,
				yawDegrees
		);
		// The driver is fixed at the seat: no walking around inside the cab, AND their eye height comes
		// from the seat anchor rather than from the resource pack's floor box.
		//
		// Two Y values, because two different questions are being answered:
		//   view.y        = where the car's FLOOR is, which is what the floor-box search must probe at
		//   seat anchor   = where the driver's EYES go, which is what the camera must sit at
		// Passing only the second made the driver dismount on the next tick, because the floor search
		// looked for a floor at eye height.
		VehicleRidingMovement.mmtrLockSeatAndPin(mmtrSeatFootY(anchors, end, view), view.y);
		return true;
	}

	/**
	 * The car-local FOOT Y of the cab's seat: the {@code mmtr_seat_<cab>} anchor (authored at EYE height)
	 * one eye height down, or the floor-derived point when the model has no seat anchor.
	 *
	 * <p>Kept next to the cab code rather than inside {@link MmtrVehicleAnchors} because it mixes the two
	 * halves deliberately: the anchor gives the eye height, the fallback gives the floor.</p>
	 */
	private static double mmtrSeatFootY(ObjectArrayList<MmtrVehicleAnchors.Anchor> anchors, int end, CabView view) {
		final MmtrVehicleAnchors.Anchor seat = MmtrVehicleAnchors.findSeat(anchors, view.mirrored ? 1 : end);
		return seat == null ? view.y - CAB_SEAT_FLOOR_DROP_M : seat.position.y() - CAB_SEAT_EYE_HEIGHT_M;
	}

	/** C6 cab naming: car index (1-based) + end, e.g. {@code 第3节 A 端驾驶室} = "3A". */
	private static String enterPrompt(int carNumber, int end) {
		return "按 G 进入 第" + (carNumber + 1) + "节 " + (end == 2 ? "B" : "A") + " 端驾驶室 / press G — cab " + (carNumber + 1) + (end == 2 ? "B" : "A");
	}

	/** Shown instead of the entry prompt when the crew already holds that cab. */
	private static final String KEY_OUT_PROMPT = "按 G 拔出钥匙 / press G — key out";

	@Nullable
	private static AimTarget findAimTarget() {
		final Camera camera = MinecraftClient.getInstance().getGameRendererMapped().getCamera();
		if (camera == null) {
			return null;
		}
		final double cameraX = camera.getPos().getXMapped();
		final double cameraY = camera.getPos().getYMapped();
		final double cameraZ = camera.getPos().getZMapped();
		final double yaw = Math.toRadians(camera.getYaw());
		final double pitch = Math.toRadians(camera.getPitch());
		final double cosPitch = Math.cos(pitch);
		final double lookX = -Math.sin(yaw) * cosPitch;
		final double lookY = -Math.sin(pitch);
		final double lookZ = Math.cos(yaw) * cosPitch;

		AimTarget best = null;
		double bestAngle = MAX_AIM_ANGLE_DEGREES;

		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			final ObjectArrayList<CarTransform> cars = carTransforms(vehicle);
			for (int carNumber = 0; carNumber < cars.size(); carNumber++) {
				final String modelId = cars.get(carNumber).vehicleId;
				final ObjectArrayList<Anchor> anchors = MmtrVehicleAnchors.get(modelId);
				if (anchors.isEmpty()) {
					continue;
				}
				final int modelCar = modelCarIndex(cars, carNumber);

				for (final Anchor anchor : anchors) {
					if (anchor.kind != MmtrVehicleAnchors.Kind.CABDOOR || anchor.car != modelCar) {
						continue;
					}
					final int cab = validCab(cars, anchors, carNumber, anchor.cab);
					if (cab == 0) {
						continue;
					}
					final Vector world = cars.get(carNumber).rotation.transformForwards(anchor.position, Vector::rotateX, Vector::rotateY, Vector::add);
					final double dx = world.x() - cameraX;
					final double dy = world.y() - cameraY;
					final double dz = world.z() - cameraZ;
					final double distanceSquared = dx * dx + dy * dy + dz * dz;
					if (distanceSquared > REACH_M * REACH_M || distanceSquared < 1.0E-4) {
						continue;
					}
					final double distance = Math.sqrt(distanceSquared);
					final double dot = (dx * lookX + dy * lookY + dz * lookZ) / distance;
					final double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
					if (angle < bestAngle || angle == bestAngle && best != null && distanceSquared < best.distanceSquared) {
						bestAngle = angle;
						best = new AimTarget(vehicle, cab, carNumber, distanceSquared);
					}
				}
			}
		}

		return best;
	}

	/**
	 * Which cab an anchor actually gives access to ({@code 1} = the car's A-end cab, {@code 2} = its
	 * B-end cab), or {@code 0} when the anchor is unusable.
	 *
	 * <p>C6: a model may carry a cab at EITHER or BOTH ends of the same car (双端机车 names them
	 * {@code mmtr_cabdoor_1_*} and {@code mmtr_cabdoor_2_*}), and a coupled formation can have cabs
	 * inside it, so any car whose model carries the anchor is a valid cab — the old rule only accepted
	 * the consist's two end cars, which made a double-ended locomotive's second cab and every
	 * post-coupling interior cab unreachable. The one exception stays: a single-cab model placed at
	 * the B end serves cab 2 through its cab-1 anchors (mirroring).</p>
	 */
	private static int validCab(ObjectArrayList<CarTransform> cars, ObjectArrayList<Anchor> anchors, int carNumber, int anchorCab) {
		final int end = anchorCab == 2 ? 2 : 1;
		if (end == 1 && cars.size() > 1 && carNumber == cars.size() - 1 && MmtrVehicleAnchors.findCabDoor(anchors, 2) == null) {
			return 2;
		}
		return end;
	}

	/** The car index inside the model for a consist car (a model can be used several times). */
	private static int modelCarIndex(ObjectArrayList<CarTransform> cars, int carNumber) {
		int index = 0;
		for (int i = 0; i < carNumber; i++) {
			if (cars.get(i).vehicleId.equals(cars.get(carNumber).vehicleId)) {
				index++;
			}
		}
		return index;
	}

	/** The consist's cars with their vehicle ID and world transform. */
	private static ObjectArrayList<CarTransform> carTransforms(Vehicle vehicle) {
		final ObjectArrayList<CarTransform> result = new ObjectArrayList<>();
		final boolean hasPitch = vehicle.getTransportMode().hasPitchAscending || vehicle.getTransportMode().hasPitchDescending;
		for (final var carAndPositions : vehicle.getVehicleCarsAndPositions()) {
			final VehicleCar vehicleCar = carAndPositions.left();
			final ObjectArrayList<PositionAndRotation> bogiePositions = carAndPositions.right()
					.stream()
					.map(bogiePosition -> new PositionAndRotation(bogiePosition.positionAndTiltAngle1().position(), bogiePosition.positionAndTiltAngle2().position(), true))
					.collect(Collectors.toCollection(ObjectArrayList::new));
			result.add(new CarTransform(vehicleCar.getVehicleId(), new PositionAndRotation(bogiePositions, vehicleCar, hasPitch)));
		}
		return result;
	}

	@Nullable
	private static VehicleExtension nearestVehicle(ClientPlayerEntity player) {
		final double x = player.getX();
		final double y = player.getY();
		final double z = player.getZ();
		VehicleExtension nearest = null;
		double nearestDistanceSquared = REACH_M * REACH_M;
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			final double distanceSquared = nearestCarDistanceSquared(vehicle, x, y, z);
			if (distanceSquared >= 0 && distanceSquared < nearestDistanceSquared) {
				nearestDistanceSquared = distanceSquared;
				nearest = vehicle;
			}
		}
		return nearest;
	}

	/** Squared distance to the nearest car centre of the consist, or -1 when it has no cars. */
	private static double nearestCarDistanceSquared(Vehicle vehicle, double x, double y, double z) {
		double nearest = -1;
		for (final var car : vehicle.getVehicleCarsAndPositions()) {
			for (final Vehicle.BogiePosition bogie : car.right()) {
				final double distanceSquared = squaredDistance(bogie.positionAndTiltAngle1().position(), x, y, z);
				if (nearest < 0 || distanceSquared < nearest) {
					nearest = distanceSquared;
				}
			}
		}
		return nearest;
	}

	/** The car index of the car the player stands closest to. */
	private static double squaredDistance(Vector position, double x, double y, double z) {
		final double dx = position.x() - x;
		final double dy = position.y() - y;
		final double dz = position.z() - z;
		return dx * dx + dy * dy + dz * dz;
	}

	private static final class AimTarget {

		private final VehicleExtension vehicle;
		private final int end;
		private final int carNumber;
		private final double distanceSquared;

		private AimTarget(VehicleExtension vehicle, int end, int carNumber, double distanceSquared) {
			this.vehicle = vehicle;
			this.end = end;
			this.carNumber = carNumber;
			this.distanceSquared = distanceSquared;
		}
	}

	private static final class CarTransform {

		private final String vehicleId;
		private final PositionAndRotation rotation;

		private CarTransform(String vehicleId, PositionAndRotation rotation) {
			this.vehicleId = vehicleId;
			this.rotation = rotation;
		}
	}
}

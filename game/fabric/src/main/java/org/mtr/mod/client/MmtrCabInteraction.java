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
	/** How often the action bar prompt is refreshed, in ticks. */
	private static final int PROMPT_INTERVAL_TICKS = 10;

	/** Key edge detection: the mapping key only exposes isPressed(). */
	private static boolean lastPressed = false;
	private static int promptCooldown = 0;

	/** The cab this client believes it holds; the engine is still the authority. */
	private static long heldVehicleId = 0;
	private static int heldCab = 0;
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

	public static void tick() {
		final boolean pressed = KeyBindings.MMTR_CAB_INTERACT.isPressed();
		final boolean justPressed = pressed && !lastPressed;
		lastPressed = pressed;

		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return;
		}

		logVehicleDebug(player);

		// Forget a stale hold as soon as the player is no longer riding that consist.
		if (heldVehicleId != 0 && !VehicleRidingMovement.isRiding(heldVehicleId)) {
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
			final boolean holdsThisCab = heldVehicleId == target.vehicle.getId() && heldCab == target.cab;
			player.sendMessage(new Text(TextHelper.literal(holdsThisCab ? KEY_OUT_PROMPT : enterPrompt(target.cab)).data), true);
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
			if (heldVehicleId == target.vehicle.getId() && heldCab == target.cab) {
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
		claimMillis = 0;
		// Back to being a passenger: free to walk again.
		VehicleRidingMovement.mmtrSetCabLock(false);
		if (player != null && message != null) {
			player.sendMessage(new Text(TextHelper.literal(message).data), true);
		}
	}

	/**
	 * Checks the local cab claim against the authoritative mirrored cab state of the vehicle. The
	 * server mirrors {@code mmtrActiveCab} / {@code mmtrCabKeyHolder} / {@code mmtrCabCrew} with every
	 * update, so this needs no extra packet: if the cab is no longer ours (the engine refused the key,
	 * another crew member took it, or the consist changed ends under us), the claim is dropped and the
	 * player is told why instead of silently driving nothing.
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
		if (activeCab.equals(expectedCab) && "CREW".equals(holder) && (crew.isEmpty() || crew.equals(localUuid))) {
			return;
		}
		if (activeCab.isEmpty() && holder.isEmpty()) {
			// No cab state mirrored at all (legacy path vehicle / older server): keep the old behaviour.
			return;
		}
		final String reason = "CREW".equals(holder) ? "钥匙在 " + crew : "SYSTEM".equals(holder) ? "自动运行持有钥匙" : activeCab.isEmpty() || "NONE".equals(activeCab) ? "无人持钥匙" : "已换到 " + activeCab;
		forget(player, "驾驶室已不属于你（" + reason + "）");
	}

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
		final int cab = target.cab;
		final String cabName = cab == 2 ? "CAB_B" : "CAB_A";
		final boolean changing = heldCab != 0 && heldCab != cab;
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(target.vehicle.getId(), PacketMmtrCabOp.Op.ENTER, cabName));
		heldVehicleId = target.vehicle.getId();
		heldCab = cab;
		claimMillis = System.currentTimeMillis();

		final boolean snapped = placeAtCabView(target.vehicle, cab);
		player.sendMessage(new Text(TextHelper.literal((changing ? "换到 " : "") + enterPrompt(cab) + (snapped ? "" : "（该模型缺少 mmtr_ 锚点，未移动视角）")).data), true);
	}

	/**
	 * Moves the player's riding state to the cab's view point. Cab 1 lives in the consist's first car
	 * and cab 2 in the last car, so the anchor is resolved from that end's model.
	 *
	 * @return true when the view point could be resolved and applied
	 */
	private static boolean placeAtCabView(VehicleExtension vehicle, int cab) {
		final ObjectArrayList<CarTransform> cars = carTransforms(vehicle);
		if (cars.isEmpty()) {
			return false;
		}

		final int edgeCar = cab == 2 ? cars.size() - 1 : 0;
		final String modelId = cars.get(edgeCar).vehicleId;
		final ObjectArrayList<Anchor> anchors = MmtrVehicleAnchors.get(modelId);
		final CabView view = MmtrVehicleAnchors.cabView(anchors, cab);
		if (view == null) {
			Init.LOGGER.info("[MMTR] cab {} of vehicle {} (model {}): no mmtr_cabdoor_{}_* anchor found ({} anchors loaded)", cab, vehicle.getId(), modelId, cab, anchors.size());
			return false;
		}
		Init.LOGGER.info("[MMTR] cab {} of vehicle {} (model {}): seat car-local ({}, {}, {}) mirrored={}", cab, vehicle.getId(), modelId, view.x, view.y, view.z, view.mirrored);

		// The model block containing the edge car; view.modelCar is an index inside that block.
		int base = edgeCar;
		while (base > 0 && cars.get(base - 1).vehicleId.equals(modelId)) {
			base--;
		}
		final int carNumber = Math.max(0, Math.min(cars.size() - 1, base + view.modelCar));
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
		// The driver is fixed at the seat: no walking around inside the cab.
		VehicleRidingMovement.mmtrSetCabLock(true);
		return true;
	}

	private static String enterPrompt(int cab) {
		return cab == 2 ? "按 G 进入 2 号驾驶室 / press G — cab 2" : "按 G 进入 1 号驾驶室 / press G — cab 1";
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
	 * Which cab an anchor actually gives access to, or {@code 0} when this anchor does not belong to
	 * a cab of the consist. Cab 1 lives at the A end (first car) and cab 2 at the B end (last car); a
	 * model with a single cab is used at both ends by mirroring it into the B end.
	 */
	private static int validCab(ObjectArrayList<CarTransform> cars, ObjectArrayList<Anchor> anchors, int carNumber, int anchorCab) {
		final int lastCarNumber = cars.size() - 1;
		if (anchorCab == 1) {
			if (carNumber == 0) {
				return 1;
			}
			if (carNumber == lastCarNumber && MmtrVehicleAnchors.findCabDoor(anchors, 2) == null) {
				return 2;
			}
			return 0;
		}
		if (anchorCab == 2 && carNumber == lastCarNumber) {
			return 2;
		}
		return 0;
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
		private final int cab;
		private final int carNumber;
		private final double distanceSquared;

		private AimTarget(VehicleExtension vehicle, int cab, int carNumber, double distanceSquared) {
			this.vehicle = vehicle;
			this.cab = cab;
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

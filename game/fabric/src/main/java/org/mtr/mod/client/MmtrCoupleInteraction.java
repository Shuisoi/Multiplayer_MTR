package org.mtr.mod.client;

import org.mtr.core.data.Vehicle;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.Camera;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.InitClient;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.packet.PacketMmtrCoupleOp;

import javax.annotation.Nullable;

/**
 * C7: the "aim at a train and press the coupler key" interaction.
 *
 * <p>Coupling is a movement: the crew drives the locomotive up to the standing rake (under a 调车授权)
 * and then attaches. Uncoupling is a cut: the crew aims at the car that should become the front of the
 * tail and cuts the coupler in front of it. Both are decided by the ENGINE
 * ({@code MmtrCoupleSurgery}) — this class only picks the target, sends the request and reports back;
 * every gate (authority, both trains at a stand, orientation, a real coupler at the cut) is enforced
 * server-side and its refusal lands in the engine command log.</p>
 *
 * <p>Feedback: the engine answer is asynchronous (a command queue), so the client watches the
 * consist's car count for a moment and tells the crew whether the formation actually changed.</p>
 */
public final class MmtrCoupleInteraction {

	private MmtrCoupleInteraction() {
	}

	/** How close (blocks) the aimed train must be to be worked. */
	private static final double REACH_M = 10.0;
	/** How far off the crosshair (degrees) a train may be and still be "aimed at". */
	private static final double MAX_AIM_ANGLE_DEGREES = 35;
	/** How far (blocks) a train may be before the "aim at it" hint is shown. */
	private static final double HINT_RANGE_M = 25.0;
	/**
	 * 粗筛余量（米）：见 {@link #anyTrainNearby} 的解释 —— 车头到最长那一节车尾的距离用
	 * {@code getTotalVehicleLength()} 估，再加一点余量吸收朝向/瞄准高度带来的差。
	 * 只影响"要不要认真算"（算多了只是慢，算少了才会漏），所以取宽一点。
	 */
	private static final double NEARBY_SLACK_M = 8.0;
	/**
	 * MMTR（notes/405）：{@link #anyTrainNearby} 的粗筛（车头距离 > 提示半径 + 车长 + 余量 ⇒ 整列车都不可能命中）。
	 * {@code -Dmmtr.couplehintfilter=false} 回到"每列车都算整列位置"。
	 */
	private static final boolean MMTR_COUPLE_HINT_FILTER = !"false".equalsIgnoreCase(System.getProperty("mmtr.couplehintfilter", "true"));
	/** Aim points are also tested at car-body height so the crosshair catches the train at eye level. */
	private static final double AIM_HEIGHT_OFFSET_M = 1.5;
	/** How often the action bar prompt is refreshed, in ticks. */
	private static final int PROMPT_INTERVAL_TICKS = 10;
	/** How long the client waits for the consist to actually change before reporting "no effect". */
	private static final long CONFIRM_MILLIS = 2500;

	private static boolean lastPressed;
	private static int promptCooldown;

	/** A request that was sent and is waiting for the engine's (mirrored) result. */
	private static long pendingVehicleId;
	private static int pendingCarCount = -1;
	private static long pendingMillis;
	private static String pendingLabel = "";

	public static void tick() {
		final boolean pressed = KeyBindings.MMTR_COUPLE.isPressed();
		final boolean justPressed = pressed && !lastPressed;
		lastPressed = pressed;

		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return;
		}

		confirm(player);

		final AimTarget target = findAimTarget(player);
		if (justPressed) {
			handle(player, target);
		}

		if (promptCooldown > 0) {
			promptCooldown--;
		} else if (target != null && pendingVehicleId == 0) {
			player.sendMessage(new Text(TextHelper.literal(prompt(target)).data), true);
			promptCooldown = PROMPT_INTERVAL_TICKS;
		} else if (target == null && pendingVehicleId == 0 && anyTrainNearby(player)) {
			player.sendMessage(new Text(TextHelper.literal("把准星对准车厢再按 K / aim the crosshair at a car, then press K").data), true);
			promptCooldown = PROMPT_INTERVAL_TICKS;
		}
	}

	/**
	 * The train the player aims at, together with the consist car under the crosshair.
	 *
	 * <p>When the player rides a train, the interaction works on that train (its own car list decides
	 * the cut) and needs a DIFFERENT train to couple onto; when the player stands outside, the aimed
	 * train itself is the subject (uncoupling a standing rake).</p>
	 */
	private static final class AimTarget {

		private final VehicleExtension vehicle;
		private final int carNumber;
		private final double distanceSquared;

		private AimTarget(VehicleExtension vehicle, int carNumber, double distanceSquared) {
			this.vehicle = vehicle;
			this.carNumber = carNumber;
			this.distanceSquared = distanceSquared;
		}
	}

	private static void handle(ClientPlayerEntity player, @Nullable AimTarget target) {
		if (pendingVehicleId != 0) {
			player.sendMessage(new Text(TextHelper.literal("上一个连挂/解挂请求还在处理中 / previous request still pending").data), true);
			return;
		}
		if (target == null) {
			player.sendMessage(new Text(TextHelper.literal("没有瞄准到列车 / no train aimed at").data), true);
			return;
		}

		final long ridingVehicleId = VehicleRidingMovement.getRidingVehicleId();
		if (ridingVehicleId != 0 && ridingVehicleId != target.vehicle.getId()) {
			// The crew drives a train and aims at another one: couple the aimed train onto ours.
			send(player, ridingVehicleId, target.vehicle.getId(), -1, "连挂 / couple");
		} else if (ridingVehicleId == target.vehicle.getId() || ridingVehicleId == 0) {
			// Same train (or working a standing rake from outside): cut in front of the aimed car.
			if (target.carNumber <= 0) {
				player.sendMessage(new Text(TextHelper.literal("瞄准第 2 节以后的车厢才能解挂 / aim at a car after the first to uncouple").data), true);
				return;
			}
			send(player, target.vehicle.getId(), 0, target.carNumber - 1, "解挂（第 " + target.carNumber + " 节前）/ uncouple");
		}
	}

	private static void send(ClientPlayerEntity player, long vehicleId, long targetVehicleId, int cutAfterCarIndex, String label) {
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCoupleOp(vehicleId, targetVehicleId, cutAfterCarIndex));
		pendingVehicleId = vehicleId;
		pendingCarCount = carCount(vehicleId);
		pendingMillis = System.currentTimeMillis();
		pendingLabel = label;
		player.sendMessage(new Text(TextHelper.literal(label + " 已发送 / sent").data), true);
	}

	/** Reports whether the formation actually changed once the engine has had time to run. */
	private static void confirm(ClientPlayerEntity player) {
		if (pendingVehicleId == 0 || System.currentTimeMillis() - pendingMillis < CONFIRM_MILLIS) {
			return;
		}
		final int now = carCount(pendingVehicleId);
		if (pendingCarCount >= 0 && now >= 0 && now != pendingCarCount) {
			player.sendMessage(new Text(TextHelper.literal(pendingLabel + " 完成（" + pendingCarCount + " → " + now + " 节）/ done").data), true);
		} else {
			player.sendMessage(new Text(TextHelper.literal(pendingLabel + " 未生效：检查调车授权 / 停稳 / 该处是否有车钩（引擎日志）/ no effect").data), true);
		}
		pendingVehicleId = 0;
		pendingCarCount = -1;
		pendingLabel = "";
	}

	private static int carCount(long vehicleId) {
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			if (vehicle.getId() == vehicleId) {
				return vehicle.getVehicleCarsAndPositions().size();
			}
		}
		return -1;
	}

	private static String prompt(AimTarget target) {
		final long ridingVehicleId = VehicleRidingMovement.getRidingVehicleId();
		if (ridingVehicleId != 0 && ridingVehicleId != target.vehicle.getId()) {
			return "按 K 连挂这列车 / press K — couple";
		}
		if (target.carNumber > 0) {
			return "按 K 在第 " + target.carNumber + " 节前解挂 / press K — uncouple";
		}
		return "按 K 连挂（先开车贴近）/ press K — couple";
	}

	/**
	 * The nearest car of the nearest train inside the crosshair cone. Uses each car's own bogie
	 * positions (the same data the renderer draws), so the aimed CAR is what the cut refers to.
	 */
	@Nullable
	private static AimTarget findAimTarget(ClientPlayerEntity player) {
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
			final ObjectArrayList<ObjectArrayList<Vector>> carCentres = carCentres(vehicle);
			for (int carNumber = 0; carNumber < carCentres.size(); carNumber++) {
				for (final Vector centre : carCentres.get(carNumber)) {
					final double dx = centre.x() - cameraX;
					final double dy = centre.y() - cameraY;
					final double dz = centre.z() - cameraZ;
					final double distanceSquared = dx * dx + dy * dy + dz * dz;
					if (distanceSquared > REACH_M * REACH_M || distanceSquared < 1.0E-4) {
						continue;
					}
					final double distance = Math.sqrt(distanceSquared);
					final double dot = (dx * lookX + dy * lookY + dz * lookZ) / distance;
					final double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
					if (angle < bestAngle || angle == bestAngle && best != null && distanceSquared < best.distanceSquared) {
						bestAngle = angle;
						best = new AimTarget(vehicle, carNumber, distanceSquared);
					}
				}
			}
		}

		return best;
	}

	/**
	 * Each car's aim points in world space: its centre plus (when it has two bogies) the midpoint of
	 * the two bogies, each tested at bogie height and at car-body height so a crew aiming from the cab
	 * or from the ground both catch the train.
	 */
	private static ObjectArrayList<ObjectArrayList<Vector>> carCentres(Vehicle vehicle) {
		final ObjectArrayList<ObjectArrayList<Vector>> result = new ObjectArrayList<>();
		for (final var carAndPositions : vehicle.getVehicleCarsAndPositions()) {
			final ObjectArrayList<Vector> centres = new ObjectArrayList<>();
			final ObjectArrayList<Vehicle.BogiePosition> bogies = carAndPositions.right();
			if (bogies.isEmpty()) {
				result.add(centres);
				continue;
			}
			final Vector first = bogies.get(0).positionAndTiltAngle1().position();
			final ObjectArrayList<Vector> anchors = new ObjectArrayList<>();
			anchors.add(first);
			if (bogies.size() > 1) {
				final Vector second = bogies.get(1).positionAndTiltAngle1().position();
				anchors.add(new Vector((first.x() + second.x()) / 2, (first.y() + second.y()) / 2, (first.z() + second.z()) / 2));
			}
			for (final Vector anchor : anchors) {
				centres.add(anchor);
				centres.add(new Vector(anchor.x(), anchor.y() + AIM_HEIGHT_OFFSET_M, anchor.z()));
			}
			result.add(centres);
		}
		return result;
	}

	/**
	 * True when some train is within the hint range of the player's eye, aimed at or not.
	 *
	 * <p>粗筛（notes/405）：一列车的每一节都在**车头往后 totalLength 之内**，所以
	 * "车头到眼睛的距离 > 提示半径 + 车长 + 余量"时，不可能有任一节车进入提示半径 ⇒ 直接跳过这列车。
	 * 这一步只是把"离得远、本来就不会命中"的列车从"算整列车的位置"里摘出去：被判为"可能近"的列车
	 * 仍然走下面原来的逐节判定，**结论不变**。</p>
	 */
	private static boolean anyTrainNearby(ClientPlayerEntity player) {
		final double eyeX = player.getX();
		final double eyeY = player.getY() + 1.6;
		final double eyeZ = player.getZ();
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			final Vehicle.PositionAndTiltAngle head = MMTR_COUPLE_HINT_FILTER ? vehicle.getHeadPositionAndTiltAngle() : null;
			if (head != null) {
				final double dx = head.position().x() - eyeX;
				final double dy = head.position().y() - eyeY;
				final double dz = head.position().z() - eyeZ;
				final double reach = HINT_RANGE_M + vehicle.vehicleExtraData.getTotalVehicleLength() + NEARBY_SLACK_M;
				if (dx * dx + dy * dy + dz * dz > reach * reach) {
					continue;
				}
			}
			for (final ObjectArrayList<Vector> centres : carCentres(vehicle)) {
				for (final Vector centre : centres) {
					final double dx = centre.x() - eyeX;
					final double dy = centre.y() - eyeY;
					final double dz = centre.z() - eyeZ;
					if (dx * dx + dy * dy + dz * dz <= HINT_RANGE_M * HINT_RANGE_M) {
						return true;
					}
				}
			}
		}
		return false;
	}
}

package org.mtr.mod.client;

import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.packet.PacketMmtrCabOp;
import org.mtr.mod.render.MmtrInteractPrompt;
import org.mtr.mod.render.PositionAndRotation;

import javax.annotation.Nullable;

/**
 * B2 (rebuilt 2026-09-19): aim at a driver's door and press the cab key to take that cab.
 *
 * <h2>What this class owns, and what it deliberately does not</h2>
 *
 * <p>It owns exactly three things: WHICH cab the player is aiming at, the request to the engine, and the
 * feedback. It does not own the player's position — {@link VehicleRidingMovement#mmtrEnterCab} does, in
 * keeping with the ownership rule that class documents ("the only writer of the riding coordinate").
 * The old version of this interaction wrote the ride fields itself, which is half of why the deleted
 * layer could not be reasoned about (notes/184 §6 #2 and #3).</p>
 *
 * <h2>The target comes from the prompt layer</h2>
 *
 * <p>{@link MmtrInteractPrompt#findCabTarget} is the same search that draws {@code [G] 进入驾驶室1} at the
 * door. Deriving "which cab" twice is how a player ends up taking the cab next to the one under the
 * label, so there is one search and both users call it.</p>
 *
 * <h2>The engine decides</h2>
 *
 * <p>The request goes through {@code PacketMmtrCabOp} → an engine {@code cab <id> <car><A|B> <crew>}
 * command, and the physical gates are the engine's: the vehicle must be an MMTR consist-body train, it
 * must be at a stand, and the cab must be free (an engine SYSTEM key is displaced, another crew member's
 * key is not). Those gates are NOT duplicated here — a refusal is reported by watching the mirrored cab
 * state, because a client-side copy of the rules would drift from the engine's and then disagree with it
 * in front of the player.</p>
 */
public final class MmtrCabInteraction {

	private MmtrCabInteraction() {
	}

	/** How long to wait for the engine's mirrored answer before reporting "no effect". */
	private static final long CONFIRM_MILLIS = 1500;
	/** 自动接管司机位时，两次请求之间至少隔这么久（引擎可能因为"车没停稳"而暂时拒绝）。 */
	private static final long AUTO_CLAIM_RETRY_MILLIS = 3000;

	private static boolean lastPressed;

	/** 自动接管的节流状态：上一次请求的时刻 + 请求的驾驶室，避免每拍重发。 */
	private static long autoClaimMillis;
	private static String autoClaimCab = "";

	/**
	 * **坐在司机位上动了手柄 ⇒ 把这个驾驶室接过来**（把"按 G 申领"那件事自动做掉）。
	 *
	 * <p>用户口径（2026-09-19）：操纵不需要手里握着钥匙。引擎侧已经不要求钥匙了，但"哪一端在前"仍然由
	 * **被占用的驾驶室**决定（{@code MmtrCabState.leadingEnd}）—— 一个没有人接管的驾驶室会让方向退回
	 * "朝 B 端"，坐在 A 端驾驶室的司机就成了倒着开。所以这里在第一次动操纵时替玩家把驾驶室接管过来：
	 * 方向随他的座位，仪表/门控/镜像也都跟原来一样。</p>
	 *
	 * <p>刻意**不在"坐下"时就接管**：那会让"坐到 AI 车的驾驶室里看看"把自动运行停掉
	 * （接管会顶掉 system 钥匙并解除自动运行）。只有真的动了手柄才算"我要开这列车"。</p>
	 *
	 * <p>引擎的闸门仍然有效（车必须停稳、一个驾驶室一把钥匙）：被拒时不报错、隔几秒再试
	 * —— 车停下来之后那一次就会成功。</p>
	 */
	public static void claimSeatWhenDriving(@Nullable MmtrDriverSeat.Seat seat) {
		if (seat == null) {
			autoClaimCab = "";
			return;
		}
		final long now = System.currentTimeMillis();
		final String cabSpec = seat.cabSpec();
		if (!cabSpec.equals(autoClaimCab)) {
			autoClaimCab = cabSpec;
			autoClaimMillis = 0;
		}
		if (now - autoClaimMillis < AUTO_CLAIM_RETRY_MILLIS) {
			return;
		}
		final VehicleExtension vehicle = vehicleById(seat.vehicleId());
		if (vehicle == null || cabIsHeldByCrew(vehicle)) {
			// 已经是我（或别的乘务员）拿着钥匙：不重复请求，也不去抢别人手里的钥匙。
			return;
		}
		autoClaimMillis = now;
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(seat.vehicleId(), PacketMmtrCabOp.Op.ENTER, cabSpec));
		Init.LOGGER.info("[MMTR-CAB] 自动接管司机位：车={} 驾驶室={}（动操纵即接管；引擎的停稳/独占闸门照旧）", seat.vehicleId(), cabSpec);
	}

	/** The cab request that is waiting for the mirrored result. */
	private static long pendingVehicleId;
	private static long pendingMillis;
	private static String pendingLabel = "";

	/** Called once per client tick, next to the coupling interaction. */
	public static void tick() {
		final boolean pressed = KeyBindings.MMTR_CAB_INTERACT.isPressed();
		final boolean justPressed = pressed && !lastPressed;
		lastPressed = pressed;

		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return;
		}

		confirm(player);
		refreshConfirmation();
		if (justPressed) {
			handle(player);
		}
	}

	/**
	 * Keeps {@link VehicleRidingMovement}'s driver lock in step with the engine's mirrored verdict.
	 *
	 * <p>The lock is what stops the driver walking, so it must follow the ENGINE and not the local
	 * request: a claim the engine refuses has to leave the player aboard and still able to move.</p>
	 */
	private static void refreshConfirmation() {
		final long heldCabVehicleId = VehicleRidingMovement.mmtrCabVehicleId();
		if (heldCabVehicleId == 0) {
			VehicleRidingMovement.mmtrSetCabConfirmed(false);
			return;
		}
		final VehicleExtension vehicle = vehicleById(heldCabVehicleId);
		VehicleRidingMovement.mmtrSetCabConfirmed(vehicle != null && cabIsHeldByCrew(vehicle));
	}

	/** @return whether the engine's mirror says a CREW key holds one of this vehicle's cabs */
	private static boolean cabIsHeldByCrew(VehicleExtension vehicle) {
		return !vehicle.getMmtrActiveCabFromSync().isEmpty()
				&& vehicle.getMmtrCabKeyHolderFromSync().toLowerCase(java.util.Locale.ROOT).contains("crew");
	}

	private static void handle(ClientPlayerEntity player) {
		// Holding a cab: the same key gives it back (the crew's "pull the key").
		final long heldCabVehicleId = VehicleRidingMovement.mmtrCabVehicleId();
		if (heldCabVehicleId != 0) {
			InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(heldCabVehicleId, PacketMmtrCabOp.Op.LEAVE, ""));
			VehicleRidingMovement.mmtrLeaveCab();
			message(player, "已拔钥匙，可以走动了 / key out");
			return;
		}

		final MmtrInteractPrompt.CabTarget target = MmtrInteractPrompt.findCabTarget(player);
		if (target == null) {
			message(player, "把准星对准司机门再按 G / aim at a driver's door, then press G");
			return;
		}
		enterCab(player, target);
	}

	private static void enterCab(ClientPlayerEntity player, MmtrInteractPrompt.CabTarget target) {
		final VehicleExtension vehicle = vehicleById(target.vehicleId());
		if (vehicle == null) {
			message(player, "找不到这辆车 / vehicle not found");
			return;
		}

		final String modelId = modelIdFor(vehicle, target.carNumber());
		final ObjectArrayList<MmtrVehicleAnchors.Anchor> anchors = MmtrVehicleAnchors.get(modelId);
		final MmtrVehicleAnchors.CabView view = MmtrVehicleAnchors.cabView(anchors, target.cab());
		if (view == null) {
			// No mmtr_cabdoor for this end: the prompt should not have offered it, so say which model is
			// short of anchors rather than teleporting the player somewhere arbitrary.
			message(player, "车型 " + modelId + " 没有这段驾驶室的锚点 / no cab door anchor");
			return;
		}

		// The engine's cab naming is "<car><A|B>" (MmtrCommandExecutor.executeCabCommand), 1-based car.
		final String cabSpec = (target.carNumber() + 1) + (target.cab() == 2 ? "B" : "A");
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(target.vehicleId(), PacketMmtrCabOp.Op.ENTER, cabSpec));

		// Face the way the cab faces: the seat anchor's normal is the direction of travel, so the world
		// look direction is that car-local vector put through the CAR's own transform. The entity yaw
		// convention is Minecraft's: look = (-sin(yaw), 0, cos(yaw)).
		final PositionAndRotation carTransform = target.carTransform();
		final Vector worldForward = carTransform.transformForwards(
				new Vector(view.forwardX, 0, view.forwardZ), Vector::rotateX, Vector::rotateY, Vector::add);
		final float playerYawDeg = (float) Math.toDegrees(Math.atan2(-worldForward.x(), worldForward.z()));

		// Enter at the height the model says a rider's feet belong at, NOT at the door sill.
		//
		// VehicleRidingMovement clamps the rider onto MTR's synthetic floor slab and only tolerates a
		// 1 m gap when it has to search for it, so the entry height used to cap how high the modelled eye
		// point could be raised: the sill is fixed by the model's doors, and the slab moves with the seat
		// anchor. The packager now declares the slab height, which makes the entry land on it exactly.
		final double declaredFeetY = MmtrVehicleAnchors.riderFeetY(modelId);
		final double entryY = Double.isNaN(declaredFeetY) ? view.y : declaredFeetY;

		VehicleRidingMovement.mmtrEnterCab(
				vehicle.vehicleExtraData.getSidingId(),
				target.vehicleId(),
				target.carNumber(),
				target.cab(),
				view.x, entryY, view.z,
				carTransform.yaw,
				playerYawDeg
		);

		pendingVehicleId = target.vehicleId();
		pendingMillis = System.currentTimeMillis();
		pendingLabel = "进入驾驶室 " + cabSpec;
		// The chat line is for the player; this one is for whoever reads the log afterwards. A failed
		// entry used to leave NO trace at all, which made "the view froze" and "I never boarded"
		// indistinguishable from a bug in the riding layer.
		Init.LOGGER.info("[MMTR-CAB] 请求上车：车={} 车节={} 驾驶室={}（{}）模型={} 座位点=({}, {}, {}) 车体朝向={} 玩家朝向={} 掉宝={}",
				target.vehicleId(), target.carNumber(), target.cab(), cabSpec, modelId, view.x, view.y, view.z,
				carTransform.yaw, playerYawDeg, view.mirrored);
		message(player, pendingLabel + "（" + modelId + "）… / taking cab");
	}

	/**
	 * Reports whether the cab actually became ours, by reading the engine's mirrored state.
	 *
	 * <p>The check is on the KEY HOLDER being a crew key rather than on the command having been accepted -
	 * that is the engine's own verdict, and it is the one the drive gate will use later.</p>
	 *
	 * <p>On refusal the message carries the state it judged from, because the three gates are invisible
	 * from the cab and two of them look identical to the player. In particular the OLD text promised
	 * "the engine log has the reason", which is FALSE for the likeliest cause: when a train's consist body
	 * cannot be placed on its rail ({@code Siding.mmtrConsistWalkerFromYard} returns null for a train
	 * longer than the siding) the spawn SILENTLY falls back to a legacy vehicle with no cab, and
	 * {@code Vehicle.getMmtrConsistWalker()} is null forever after with no log line at all. A driver who
	 * was told to go read the log would search it in vain.</p>
	 */
	private static void confirm(ClientPlayerEntity player) {
		if (pendingVehicleId == 0 || System.currentTimeMillis() - pendingMillis < CONFIRM_MILLIS) {
			return;
		}
		final VehicleExtension vehicle = vehicleById(pendingVehicleId);
		final String activeCab = vehicle == null ? "" : vehicle.getMmtrActiveCabFromSync();
		final String keyHolder = vehicle == null ? "" : vehicle.getMmtrCabKeyHolderFromSync();
		// Speed in km/h from the same source the dashboard draws, so the two can never disagree.
		final long speedKmh = vehicle == null ? 0 : Math.round(Math.abs(vehicle.getSpeed()) * 3600);
		final String state = "驾驶室=" + (activeCab.isEmpty() ? "无" : activeCab)
				+ "·钥匙=" + (keyHolder.isEmpty() ? "无" : keyHolder)
				+ "·速度=" + speedKmh + "km/h";
		Init.LOGGER.info("[MMTR-CAB] 上车结果：{} | {} | 仍在车上={} 驾驶室已确认={}",
				pendingLabel, state, VehicleRidingMovement.mmtrIsInCab(), VehicleRidingMovement.mmtrIsCabConfirmed());
		if (!activeCab.isEmpty() && keyHolder.toLowerCase(java.util.Locale.ROOT).contains("crew")) {
			message(player, pendingLabel + " 完成（" + state + "）/ done");
		} else if (speedKmh > 1) {
			// Name the gate that is actually shut rather than listing all three.
			message(player, pendingLabel + " 未生效（" + state + "）：车没停稳 —— 停稳后再按 G / no effect: vehicle still moving");
		} else {
			message(player, pendingLabel + " 未生效（" + state + "）：这里只可能是"
				+ "①别的乘务员拿着钥匙 ②该驾驶室不空闲，或 ③这列不是 MMTR 编组体车。"
				+ "③ 是不会写日志的（车长超过股道时 spawn 会静默退成没有驾驶室的遗留整车）——"
				+ "用 OP 命令 `cab " + pendingVehicleId + " A` 复查，答“不是编组体车”就是它 / no effect");
		}
		pendingVehicleId = 0;
		pendingLabel = "";
	}

	@Nullable
	private static VehicleExtension vehicleById(long vehicleId) {
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			if (vehicle.getId() == vehicleId) {
				return vehicle;
			}
		}
		return null;
	}

	/** The model ID of one car of a consist - the same lookup {@code ModelPropertiesPart} uses. */
	@Nullable
	private static String modelIdFor(VehicleExtension vehicle, int carNumber) {
		final ObjectArrayList<org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<org.mtr.core.data.VehicleCar, ObjectArrayList<org.mtr.core.data.Vehicle.BogiePosition>>> cars = vehicle.getVehicleCarsAndPositions();
		return carNumber < 0 || carNumber >= cars.size() ? null : cars.get(carNumber).left().getVehicleId();
	}

	private static void message(ClientPlayerEntity player, String text) {
		player.sendMessage(new Text(TextHelper.literal(text).data), true);
	}
}

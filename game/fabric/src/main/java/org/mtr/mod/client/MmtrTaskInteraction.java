package org.mtr.mod.client;

import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.InitClient;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.packet.PacketMmtrCabOp;

import javax.annotation.Nullable;

/**
 * **计划内接管**的客户端那一半：坐在司机位上按一下 B，把本车当前挂着的作业单接过来（再按一下还回去）。
 *
 * <h2>为什么是"计划内"、为什么要求停稳</h2>
 *
 * <p>用户口径（2026-09-21）："比如在车辆还在等待发车，到站停站等静止状态时接管"。
 * 判据在引擎里（{@code Simulator.mmtrJobTakeover}：速度必须为 0），这里**不复刻**那条规则 ——
 * 客户端自己判一遍就会与引擎分叉，然后当着司机的面互相矛盾（notes/215 的教训）。
 * 这里只负责"把意图发出去 + 说一句我在等结果"；被拒的原因由引擎写进指令日志，也会在这里显示。</p>
 *
 * <h2>接过来之后是什么样</h2>
 *
 * <p>引擎仍然替他：规划进路、申请/扳道岔、给停车点与信号；**油门与制动完全在司机手里**。
 * 把车停到停车点（或站台作业停够时间）这一步就算完成，作业表接着往下走 —— 那两件事分别由
 * {@code Vehicle.mmtrMotionSelfArmMission} 的 PLAYER 分支（给停车点但不接管油门）与
 * {@code MmtrJobScheduler}（认司机、逐步标 PLAYER）实现。</p>
 */
public final class MmtrTaskInteraction {

	private MmtrTaskInteraction() {
	}

	private static boolean lastPressed;
	private static boolean lastConfirmPressed;
	/** 上一次回给引擎的确认（{@code 序号:版本}）—— 同一版只回一次，不靠重发掩盖被拒。 */
	private static String lastConfirmed = "";

	/** 每客户端 tick 一次（{@code MainRenderer} 的 GUI 钩子里，与别的交互同一处）。 */
	public static void tick() {
		tickSubTaskConfirmation(MmtrDriverSeat.ridingVehicle());
		tickJobTakeover();
		tickManualConfirm();
	}

	/**
	 * **双向确认的上行那一半**：客户端把"我显示的这一步，引擎判为完成"回给引擎。
	 *
	 * <p>它是**自动**的（不是要司机按两遍键）：司机要做的仍然是开车、开门、关门这些真实动作，
	 * 确认只是"两端看到的是同一版状态"的凭据。引擎收到后会把"客户端确认 rev N"记进日志，
	 * 版本对不上时明确说"不一致"——于是"双向确认"是一条能查的数据，而不是一句设计口号。</p>
	 *
	 * <p>人工再确认一次用 {@link KeyBindings#MMTR_TASK_CONFIRM}（N）：那一下是司机自己按的，
	 * 与自动确认走同一个包、同一条引擎入口。</p>
	 */
	private static void tickSubTaskConfirmation(@Nullable VehicleExtension vehicle) {
		if (vehicle == null) {
			return;
		}
		/*
		 * 只在"这一步确实是我在执行"时自动回确认。判据全部读**引擎的镜像字段**（执行者 + 驾驶室钥匙
		 * 持有人），不自己判业务：乘客坐在自动车上时不该替司机回确认 —— 那会被引擎如实拒绝
		 * （"本步不是由你执行"），然后每一条拒绝都写进指令日志，把日志刷满。
		 */
		if (!"PLAYER".equals(vehicle.getMmtrMissionExecutorFromSync()) || !isMyCab(vehicle)) {
			return;
		}
		final java.util.List<MmtrSubTaskView.Entry> entries = MmtrSubTaskView.of(vehicle);
		final int index = MmtrSubTaskView.firstUnconfirmedIndex(entries);
		if (index < 0) {
			return;
		}
		final long revision = vehicle.getMmtrSubTaskRevisionFromSync();
		final String key = index + ":" + revision;
		if (key.equals(lastConfirmed)) {
			return;
		}
		lastConfirmed = key;
		sendConfirm(vehicle.getId(), index, revision);
	}

	/** 驾驶室钥匙是不是在我手里（读镜像字段 —— 谁在开由引擎说了算）。 */
	private static boolean isMyCab(VehicleExtension vehicle) {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		return player != null && player.getUuid() != null
			&& player.getUuid().toString().equals(vehicle.getMmtrCabCrewFromSync());
	}

	/** 接管 / 归还（B）：坐在司机位上按一下，把本车当前挂着的作业单接过来（再按一下还回去）。 */
	private static void tickJobTakeover() {
		final boolean pressed = KeyBindings.MMTR_TASK.isPressed();
		final boolean justPressed = pressed && !lastPressed;
		lastPressed = pressed;
		if (!justPressed) {
			return;
		}
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return;
		}
		final MmtrDriverSeat.Seat seat = MmtrDriverSeat.current();
		if (seat == null) {
			message(player, "先坐到司机位上再按 B / sit in the driver's seat first");
			return;
		}
		final VehicleExtension vehicle = vehicleById(seat.vehicleId());
		// 已经是"我在开"⇒ 这一下是**还回去**；否则是**接过来**。判据读引擎的镜像字段，
		// 不是本地记忆 —— 引擎把作业收回去时（比如车被别的调度拿走）按一下就又变成接管。
		final boolean mineAlready = vehicle != null && "PLAYER".equals(vehicle.getMmtrMissionExecutorFromSync());
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(seat.vehicleId(),
			mineAlready ? PacketMmtrCabOp.Op.TASK_RELEASE : PacketMmtrCabOp.Op.TASK_TAKE, ""));
		message(player, mineAlready
			? "已请求还回自动执行… / releasing to autopilot"
			: "已请求接管本车的作业…（车必须停稳；被拒的原因会写进指令日志）/ taking over");
	}

	/** 人工确认（N）：对**当前正在做的那一条**子任务说一句"我这边确认"。 */
	public static void tickManualConfirm() {
		final boolean pressed = KeyBindings.MMTR_TASK_CONFIRM.isPressed();
		final boolean justPressed = pressed && !lastConfirmPressed;
		lastConfirmPressed = pressed;
		if (!justPressed) {
			return;
		}
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return;
		}
		final VehicleExtension vehicle = MmtrDriverSeat.ridingVehicle();
		if (vehicle == null) {
			message(player, "先上车 / ride a train first");
			return;
		}
		final java.util.List<MmtrSubTaskView.Entry> entries = MmtrSubTaskView.of(vehicle);
		if (entries.isEmpty()) {
			message(player, "本车这一步没有子任务 / no sub-tasks on this step");
			return;
		}
		final int current = MmtrSubTaskView.currentIndex(entries);
		final int index = current < 0 ? entries.size() - 1 : current;
		final long revision = vehicle.getMmtrSubTaskRevisionFromSync();
		lastConfirmed = index + ":" + revision;
		sendConfirm(vehicle.getId(), index, revision);
		message(player, "已确认子任务 " + (index + 1) + "/" + entries.size() + "：" + entries.get(index).text()
			+ " / confirmed");
	}

	private static void sendConfirm(long vehicleId, int index, long revision) {
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrCabOp(vehicleId, PacketMmtrCabOp.Op.TASK_CONFIRM,
			index + ":" + revision));
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

	private static void message(ClientPlayerEntity player, String text) {
		player.sendMessage(new Text(TextHelper.literal(text).data), true);
	}
}

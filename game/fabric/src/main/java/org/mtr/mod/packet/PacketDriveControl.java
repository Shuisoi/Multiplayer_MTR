package org.mtr.mod.packet;

import org.mtr.core.Main;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.MmtrLightSwitch;
import org.mtr.core.mmtr.duty.MmtrDutyRegistry;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.simulation.Simulator;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;

import java.util.UUID;

/**
 * MMTR explicit separated drive command (client -&gt; server -&gt; engine).
 * Throttle and brake are independent notches; reverser is carried for future shunting use.
 *
 * <p>三手柄机车（BR101）多带两个字段：{@code driveHandle}（油门手柄 ±97：正牵引 / 负电阻制动 /
 * 0 关闭）与 {@code cruiseSpeedKmh}（定速巡航设定值，0 = 关闭，步长 5）。{@code brakeNotch} 对三手柄车底
 * 是**制动手柄的位置下标**（运行/1A/1B/2…8/EB），对老车底仍是档位数 —— 同一个字段按车型解释，
 * 引擎侧 {@code ConsistType.controlMode} 决定用哪套口径（见 docs/01-设计/驾驶输入与控制模型.md §6.1）。</p>
 *
 * <p>{@code acknowledge} (A3) is the AWS cancel button: a one-shot press that must reach the engine
 * even when no notch changed, so it travels on its own drive command.</p>
 */
public final class PacketDriveControl extends PacketHandler {

	private final long vehicleId;
	private final int throttleNotch;
	private final int brakeNotch;
	private final int driveHandle;
	private final int cruiseSpeedKmh;
	private final int reverser;
	/**
	 * 灯光开关档位（{@code MmtrLightSwitch}：0 关 / 1 尾 / 2 日 / 3 夜），描述**司机所在驾驶室那一端**。
	 *
	 * <p>车灯要别人也看得见，所以开关状态由引擎权威保存、再镜像回所有客户端（notes/352）；
	 * 这一包只上报"我这端的开关在哪一档"，**端由引擎按占用状态决定** —— 客户端改不了别人那一端。</p>
	 */
	private final int lightSwitch;
	private final boolean emergency;
	private final boolean acknowledge;

	public PacketDriveControl(PacketBufferReceiver packetBufferReceiver) {
		vehicleId = packetBufferReceiver.readLong();
		throttleNotch = packetBufferReceiver.readInt();
		brakeNotch = packetBufferReceiver.readInt();
		driveHandle = packetBufferReceiver.readInt();
		cruiseSpeedKmh = packetBufferReceiver.readInt();
		reverser = packetBufferReceiver.readInt();
		lightSwitch = packetBufferReceiver.readInt();
		emergency = packetBufferReceiver.readBoolean();
		acknowledge = packetBufferReceiver.readBoolean();
	}

	public PacketDriveControl(long vehicleId, int throttleNotch, int brakeNotch, int reverser, boolean emergency) {
		this(vehicleId, throttleNotch, brakeNotch, reverser, emergency, false);
	}

	public PacketDriveControl(long vehicleId, int throttleNotch, int brakeNotch, int reverser, boolean emergency, boolean acknowledge) {
		this(vehicleId, throttleNotch, brakeNotch, reverser, emergency, acknowledge, 0, 0);
	}

	public PacketDriveControl(long vehicleId, int throttleNotch, int brakeNotch, int reverser, boolean emergency, boolean acknowledge,
		int driveHandle, int cruiseSpeedKmh) {
		this(vehicleId, throttleNotch, brakeNotch, reverser, emergency, acknowledge, driveHandle, cruiseSpeedKmh, MmtrLightSwitch.DEFAULT);
	}

	public PacketDriveControl(long vehicleId, int throttleNotch, int brakeNotch, int reverser, boolean emergency, boolean acknowledge,
		int driveHandle, int cruiseSpeedKmh, int lightSwitch) {
		this.vehicleId = vehicleId;
		this.throttleNotch = throttleNotch;
		this.brakeNotch = brakeNotch;
		this.driveHandle = driveHandle;
		this.cruiseSpeedKmh = cruiseSpeedKmh;
		this.reverser = reverser;
		this.lightSwitch = lightSwitch;
		this.emergency = emergency;
		this.acknowledge = acknowledge;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(vehicleId);
		packetBufferSender.writeInt(throttleNotch);
		packetBufferSender.writeInt(brakeNotch);
		packetBufferSender.writeInt(driveHandle);
		packetBufferSender.writeInt(cruiseSpeedKmh);
		packetBufferSender.writeInt(reverser);
		packetBufferSender.writeInt(lightSwitch);
		packetBufferSender.writeBoolean(emergency);
		packetBufferSender.writeBoolean(acknowledge);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		final ControlState state = new ControlState()
			.setThrottleNotch(throttleNotch).setBrakeNotch(brakeNotch).setDriveHandle(driveHandle)
			.setCruiseSpeedKmh(cruiseSpeedKmh).setReverser(reverser).setLightSwitch(lightSwitch)
			.setEmergency(emergency).setAcknowledge(acknowledge);
		// The engine only honours control from the player currently occupying a cab driver seat
		// of this consist (occupation lock), so attach the sender's identity.
		final UUID sender = serverPlayerEntity == null ? null : serverPlayerEntity.getUuid();
		/*
		 * notes/409 §4.6 第 14 条：**没有驾驶权的人不许把引擎的 AI 挤下车**。
		 *
		 * <p>引擎那边 {@code Vehicle.applyMmtrControl} 一收到操纵包就把 {@code mmtrManualOverride}
		 * 置位（单测原话 "drive command must hold the override"），而手动接管一开引擎就**不许自己开**
		 * （{@code SystemMapServlet} 的注释："手动接管（mmtrManualOverride）：车交给人在开 ⇒ 引擎
		 * 不允许自己开"）。于是"**人坐进驾驶室**"加上客户端那条**油门=0 的中性心跳**（2 s 一次）
		 * 就足以让一列正在自动运行的车把 AI 让出去 —— 而这个人如果还没拿到驾驶权
		 * （值守=已上车·未获驾驶权），他也开不动（引擎只认驾驶权持有人）⇒ **谁都不能开车，车停在半路**。
		 * 实机 2026-10-09 19:46–19:52：车 3210607068659547126 派给玩家后 6 分钟 0 km/h、
		 * 六个手柄全 0（{@code [MMTR-DRV] 收到操纵：… 油门=0 制动=0}）、{@code isCurrentlyManual=true}，
		 * 而作业执行者仍是 AUTOPILOT、当前步仍是"出发前往 鸥湾站1台"（用户原话："车不加速不正常运营"）。</p>
		 *
		 * <p>判据与上行走廊**同一条**（{@code PacketMmtrUploadMotion} 的 holder）：值守状态机上这辆车
		 * 归他、且他已经在 {@code DRIVING}/{@code DRIVING_EXIT_ARMED}。**没有值守记录**（自由车 /
		 * 人工调车）照旧放行 —— 那条老路不许被这道闸门掐掉。{@code DRIVING_EXIT_ARMED} 必须算
		 * （"运转中·下一站退出"的司机还在开，notes/409 §4.6 第 5 条踩过同一个洞）。</p>
		 *
		 * <p>放在**服务端**而不是客户端：值守状态的真源就在服务端、判据即时成立 —— 交接那一刻
		 * （{@code mmtrJobTakeover}）本包立刻放行，不必等值守状态同步回客户端镜像（那有 1–3 s 滞后，
		 * 放到客户端会让"交接完推手柄没反应"多出几秒）。</p>
		 */
		if (sender != null && mmtrDriveNotAuthorized(minecraftServer, sender, vehicleId)) {
			return;
		}
		Init.sendMessageC2S(OperationProcessor.MMTR_DRIVE, minecraftServer, null, new MmtrDriveControl(vehicleId, state, sender), null, null);
	}

	/**
	 * 这个发件人在**这辆车**上有没有驾驶权（理由见 {@link #runServer} 里那段注释）。
	 *
	 * @return {@code true} = 没有 ⇒ 丢掉这条操纵，并记一行说明（1 秒最多一行）
	 */
	private static boolean mmtrDriveNotAuthorized(MinecraftServer minecraftServer, UUID sender, long vehicleId) {
		final Main main = Init.getMain();
		if (main == null) {
			return false;
		}
		for (final net.minecraft.server.world.ServerWorld serverWorld : minecraftServer.data.getWorlds()) {
			final Simulator simulator = main.getSimulator(Init.getWorldId(new World(serverWorld)));
			if (simulator == null || simulator.mmtrFindVehicle(vehicleId) == null) {
				continue;
			}
			final MmtrDutyRegistry.Duty duty = simulator.mmtrDuties.of(sender);
			if (duty == null || duty.vehicleId() != vehicleId) {
				// 这辆车没有他的值守记录：自由车 / 人工调车，照旧放行。
				return false;
			}
			if (duty.state() == MmtrDutyRegistry.State.DRIVING || duty.state() == MmtrDutyRegistry.State.DRIVING_EXIT_ARMED) {
				return false;
			}
			mmtrLogDriveRefusal(simulator, sender, vehicleId, duty);
			return true;
		}
		return false;
	}

	/** 丢掉操纵的说明**只在理由变化时记一行**：这条心跳 2 s 一次，不设闸门会把日志刷满。 */
	private static void mmtrLogDriveRefusal(Simulator simulator, UUID sender, long vehicleId, MmtrDutyRegistry.Duty duty) {
		final long now = simulator.getCurrentMillis();
		final String key = vehicleId + "/" + duty.state().name();
		if (key.equals(mmtrLastDriveRefusalKey) && now - mmtrLastDriveRefusalMillis < 1000) {
			return;
		}
		mmtrLastDriveRefusalKey = key;
		mmtrLastDriveRefusalMillis = now;
		Init.LOGGER.info("[MMTR-DRV] 忽略操纵：{} 还没拿到车 {} 的驾驶权（值守状态={}）"
			+ " —— 引擎继续按作业自己开，到站停稳交接之后这里立刻放行", sender, vehicleId, duty.state().name());
	}

	private static long mmtrLastDriveRefusalMillis;
	private static String mmtrLastDriveRefusalKey = "";
}

package org.mtr.mod.client;

import org.mtr.core.mmtr.MmtrTrace;
import org.mtr.core.mmtr.ThreeHandleSpec;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.KeyBinding;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.InitClient;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.packet.PacketDriveControl;

import javax.annotation.Nullable;

/**
 * 三手柄机车的**客户端输入逻辑**（BR101）：把键盘读成三根手柄的状态，变化时上报引擎。
 *
 * <h2>为什么单独一个类</h2>
 *
 * <p>输入是"状态机 + 上报策略"，不是渲染也不是权限：{@link MmtrCabInteraction} 管"我有没有这根驾驶室的钥匙"，
 * 这里管"钥匙在我手里时三根手柄在哪"。两者都只在客户端 tick 里跑，但所有权分开 ——
 * 一个类既认领驾驶室又发手柄状态，是上一代驾驶层没法推理的原因之一（notes/184 §6）。</p>
 *
 * <h2>上报策略（用户口径 + 省钱）</h2>
 *
 * <ul>
 *   <li><b>坐在司机位上就能操作</b>：判据是 {@link MmtrDriverSeat}（沿车长的位置离哪个驾驶室的玻璃最近），
 *       <b>不需要手里握着钥匙、也不需要按 G 申领</b>（用户口径 2026-09-19）；</li>
 *   <li><b>只在变化时发</b>：手柄是保持型状态，不按不耗流量；</li>
 *   <li><b>握着司机位期间每秒补发一次</b>：引擎侧 override 被自动释放（人走了/断线）之后能自愈；</li>
 *   <li><b>离开司机位时把三手柄归到 关闭 / 运行 / 定速 0 并发最后一次</b>：否则"人还在车上但走开了"
 *       会让车带着旧手柄继续跑。换向器**不动**（真车手柄停在哪就是哪，且方向由引擎在停稳时才认）。</li>
 * </ul>
 *
 * <h2>键位与长按</h2>
 *
 * <p>1% 一档 × 96 档，单击要按 96 次 —— 所以单击一步、长按超过 holdDelay 后按固定间隔扫。
 * 扫描用**墙钟时间**而不是 tick 计数：客户端 tick 在一帧里可能被调用多次（阴影通道），
 * 按 tick 计会双倍速。</p>
 *
 * <p><b>HID（手柄/摇杆）</b>与键盘并存：DirectInput 的 X = 定速巡航、Y = 油门手柄、Z = 气制动手柄
 * （{@link MmtrHidInput}，换算在引擎的 {@code MmtrHidMapping}）。轴是**绝对量**，所以它压在键盘之后：
 * 有真杆时以杆位为准；没插手柄就纯键盘。</p>
 *
 * <p>量程来自**车辆快照镜像的手柄规格**（{@code mmtrHandleSpec}）：客户端没有 consist-types.json，
 * 用它才知道这列车有几档油门、制动几位。镜像还没到（没人开过的车）时退回出厂规格。</p>
 */
public final class MmtrDriveInput {

	private MmtrDriveInput() {
	}

	/** 长按多久之后开始扫（单击 = 正好一步）。 */
	private static final long DRIVE_HOLD_DELAY_MILLIS = 250;
	/** 油门手柄扫描间隔：50 ms 一步 = 20%/s（推满约 5 s）。 */
	private static final long DRIVE_SWEEP_INTERVAL_MILLIS = 50;
	private static final long BRAKE_HOLD_DELAY_MILLIS = 400;
	/** 制动手柄扫描间隔：250 ms 一位 = 4 位/s（11 个位置约 2.5 s 走完）。 */
	private static final long BRAKE_SWEEP_INTERVAL_MILLIS = 250;
	private static final long AFB_HOLD_DELAY_MILLIS = 300;
	/** 定速扫描间隔：200 ms 一档 = 25 km/h per second（0→160 约 6.4 s）。 */
	private static final long AFB_SWEEP_INTERVAL_MILLIS = 200;
	/** 逆时针/换向器不扫：方向是离散意图，扫过去容易越过中立位。 */
	private static final long REVERSER_HOLD_DELAY_MILLIS = Long.MAX_VALUE / 4;
	/** 握钥匙期间即使手柄没变也补发一次（自愈）。 */
	private static final long REFRESH_INTERVAL_MILLIS = 1000;

	private static final KeyPair DRIVE_KEYS = new KeyPair(KeyBindings.MMTR_DRIVE_TRACTION, KeyBindings.MMTR_DRIVE_ELECTRIC_BRAKE, 1, 1, DRIVE_HOLD_DELAY_MILLIS, DRIVE_SWEEP_INTERVAL_MILLIS);
	private static final KeyPair BRAKE_KEYS = new KeyPair(KeyBindings.MMTR_BRAKE_APPLY, KeyBindings.MMTR_BRAKE_RELEASE, 1, 1, BRAKE_HOLD_DELAY_MILLIS, BRAKE_SWEEP_INTERVAL_MILLIS);
	private static final KeyPair AFB_KEYS = new KeyPair(KeyBindings.MMTR_AFB_UP, KeyBindings.MMTR_AFB_DOWN, 5, 5, AFB_HOLD_DELAY_MILLIS, AFB_SWEEP_INTERVAL_MILLIS);
	private static final KeyPair REVERSER_KEYS = new KeyPair(KeyBindings.MMTR_REVERSER_FORWARD, KeyBindings.MMTR_REVERSER_BACK, 1, 0, REVERSER_HOLD_DELAY_MILLIS, Long.MAX_VALUE / 4);

	private static ThreeHandleSpec activeSpec = ThreeHandleSpec.defaults();

	// 三根手柄的当前位置（客户端本地；引擎侧是权威副本）
	private static int driveHandle;
	private static int brakePosition;
	private static int cruiseKmh;
	private static int reverser = 1;

	/** 我们正在控制的车（握钥匙的那辆）；0 = 手里没有驾驶室。 */
	private static long controlledVehicleId;

	// 上次发出去的值（变化检测用哨兵值强制首帧必发）
	private static int sentDriveHandle = Integer.MIN_VALUE;
	private static int sentBrakePosition = Integer.MIN_VALUE;
	private static int sentCruiseKmh = Integer.MIN_VALUE;
	private static int sentReverser = Integer.MIN_VALUE;
	private static long lastSendMillis;

	/** 每客户端 tick 调用一次（挨着其它交互键，见 {@code MainRenderer}）。 */
	public static void tick() {
		final long nowMillis = System.currentTimeMillis();
		// 判据：坐在司机位上（位置判定），**不看钥匙**。按 G 申领驾驶室仍然是可用的另一条路
		// （它把人钉到座椅上并锁定走动），但它不再是想开车的前提。
		final MmtrDriverSeat.Seat seat = MmtrDriverSeat.current();
		final long vehicleId = seat == null ? 0 : seat.vehicleId();

		if (vehicleId == 0) {
			if (controlledVehicleId != 0) {
				neutraliseHandles();
				send(controlledVehicleId);
				MmtrTrace.log("[MMTR-DRV] client handles released (vehicle " + controlledVehicleId + ")");
				controlledVehicleId = 0;
			}
			return;
		}

		final VehicleExtension vehicle = vehicleById(vehicleId);
		if (controlledVehicleId != vehicleId) {
			// 刚坐上司机位：从镜像取回换向器的位置（别把别人停成尾向前的车又掰回正向），
			// 三根手柄归零，并强制首帧上报。
			controlledVehicleId = vehicleId;
			activeSpec = specOf(vehicle);
			reverser = vehicle != null && vehicle.getMmtrReverserFromSync() < 0 ? -1 : 1;
			neutraliseHandles();
			sentDriveHandle = Integer.MIN_VALUE;
			warnIfNotThreeHandle(vehicle);
			MmtrTrace.log("[MMTR-DRV] client seated at " + seat.cabSpec() + " (vehicle " + vehicleId + " spec=" + activeSpec.encode() + ")");
		}

		applyKeyDeltas(nowMillis);
		applyHidAxes();
		if (handleChangedThisTick) {
			// 动了手柄 ⇒ 顺手把这个驾驶室接管过来（引擎要的"哪一端在前"由它决定，见 MmtrCabInteraction）。
			MmtrCabInteraction.claimSeatWhenDriving(seat);
		}
		if (changedSinceLastSend() || nowMillis - lastSendMillis >= REFRESH_INTERVAL_MILLIS) {
			send(vehicleId);
		}
	}

	/**
	 * HID（手柄/摇杆）：轴值就是**杆位**（绝对量），所以它压在键盘之后 —— 有真杆时以杆为准。
	 *
	 * <p>没插手柄时 {@link MmtrHidInput#poll} 返回 {@code null}，这一支什么都不做：纯键盘照常，
	 * 零回归。轴号/方向/死区可用 {@code -Dmmtr.hid.*} 校准（见 {@code MmtrHidInput}）。</p>
	 */
	private static void applyHidAxes() {
		final MmtrHidInput.State hid = MmtrHidInput.poll(activeSpec);
		if (hid == null) {
			return;
		}
		if (hid.cruiseKmh() != cruiseKmh) {
			cruiseKmh = hid.cruiseKmh();
			handleChangedThisTick = true;
		}
		if (hid.driveHandle() != driveHandle) {
			driveHandle = hid.driveHandle();
			handleChangedThisTick = true;
		}
		if (hid.brakePosition() != brakePosition) {
			brakePosition = hid.brakePosition();
			handleChangedThisTick = true;
		}
	}

	/** 本拍有没有哪个手柄动了（驱动"动操纵即接管司机位"）。 */
	private static boolean handleChangedThisTick;

	private static void applyKeyDeltas(long nowMillis) {
		handleChangedThisTick = false;
		final int driveDelta = DRIVE_KEYS.read(nowMillis);
		if (driveDelta != 0) {
			driveHandle = activeSpec.clampDriveHandle(driveHandle + driveDelta);
			handleChangedThisTick = true;
		}
		final int brakeDelta = BRAKE_KEYS.read(nowMillis);
		if (brakeDelta != 0) {
			brakePosition = activeSpec.clampBrakePosition(brakePosition + brakeDelta);
			handleChangedThisTick = true;
		}
		final int afbDelta = AFB_KEYS.read(nowMillis);
		if (afbDelta != 0) {
			cruiseKmh = activeSpec.clampCruiseKmh(cruiseKmh + afbDelta);
			handleChangedThisTick = true;
		}
		final int reverserDelta = REVERSER_KEYS.read(nowMillis);
		if (reverserDelta != 0) {
			reverser = Math.max(-1, Math.min(1, reverser + reverserDelta));
			handleChangedThisTick = true;
		}
	}

	/** 三根手柄归到 关闭 / 运行（缓解） / 定速关闭；**换向器不动**。 */
	private static void neutraliseHandles() {
		driveHandle = 0;
		brakePosition = ThreeHandleSpec.runningPosition();
		cruiseKmh = 0;
	}

	private static boolean changedSinceLastSend() {
		return driveHandle != sentDriveHandle || brakePosition != sentBrakePosition
			|| cruiseKmh != sentCruiseKmh || reverser != sentReverser;
	}

	private static void send(long vehicleId) {
		// throttleNotch 走 0：三手柄车底读 driveHandle；老车底（NOTCHED/STEPLESS/AIR_BRAKE）本轮不由
		// 新键位驱动，理由与后续计划见 docs/01-设计/驾驶输入与控制模型.md §10。
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketDriveControl(vehicleId, 0, brakePosition, reverser, false, false, driveHandle, cruiseKmh));
		sentDriveHandle = driveHandle;
		sentBrakePosition = brakePosition;
		sentCruiseKmh = cruiseKmh;
		sentReverser = reverser;
		lastSendMillis = System.currentTimeMillis();
		MmtrTrace.log("[MMTR-DRV] client handles → " + describeHandles() + " (vehicle " + vehicleId + ")");
	}

	private static ThreeHandleSpec specOf(@Nullable VehicleExtension vehicle) {
		// 客户端没有 consist-types.json：位置表靠快照镜像。镜像还没到（这台车还没人开过）就用出厂规格。
		final ThreeHandleSpec decoded = vehicle == null ? null : ThreeHandleSpec.decode(vehicle.getMmtrHandleSpecFromSync());
		return decoded == null ? ThreeHandleSpec.defaults() : decoded;
	}

	/** 这列车底不是三手柄就直说 —— 否则玩家会以为是键位坏了（新键位确实不驱动老车底）。 */
	private static void warnIfNotThreeHandle(@Nullable VehicleExtension vehicle) {
		if (vehicle == null) {
			return;
		}
		final String mode = vehicle.getMmtrModeFromSync();
		if (!mode.isEmpty() && !"THREE_HANDLE".equals(mode)) {
			message("本车底是 " + mode + "，不是三手柄（THREE_HANDLE）：油门/制动/定速键不驱动它");
		}
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

	private static void message(String text) {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player != null) {
			player.sendMessage(new Text(TextHelper.literal(text).data), true);
		}
	}

	// ---- 读接口（HUD / 诊断） -----------------------------------------------------------------------

	public static int getDriveHandle() { return driveHandle; }

	public static int getBrakePosition() { return brakePosition; }

	public static int getCruiseKmh() { return cruiseKmh; }

	public static int getReverser() { return reverser; }

	/** 正在控制的车；0 = 手里没有司机位。 */
	public static long getControlledVehicleId() { return controlledVehicleId; }

	/** 油门手柄的短语：牵引 45% / 电阻制动 最小 / 关闭（HUD 与日志共用 {@link ThreeHandleSpec} 的说法）。 */
	public static String driveHandleText() { return activeSpec.describeDriveHandle(driveHandle); }

	/** 制动手柄的档名：运行 / 1A / 1B / 2…8 / EB。 */
	public static String brakeText() { return activeSpec.brakePositionLabel(brakePosition); }

	/** 制动手柄是否在紧急位（HUD 用它上红色）。 */
	public static boolean isEmergencyBrake() { return activeSpec.isEmergencyPosition(brakePosition); }

	/** 定速巡航的短语：关闭 / 100 km/h。 */
	public static String cruiseText() { return cruiseKmh <= 0 ? "关闭" : cruiseKmh + " km/h"; }

	public static String reverserText() { return reverser > 0 ? "前进" : reverser < 0 ? "后退" : "中立"; }

	/** 当前三手柄的一句话状态（日志用；HUD 分行显示同样这几个词，避免两处各写一套）。 */
	public static String describeHandles() {
		return "油门=" + driveHandleText() + " 制动=" + brakeText() + " 定速=" + cruiseText() + " 换向=" + reverserText();
	}

	/** 一对"加/减"键：单击正好一步，长按超过 {@code holdDelayMillis} 后每 {@code sweepIntervalMillis} 扫一步。 */
	private static final class KeyPair {

		private final KeyBinding positiveKey;
		private final KeyBinding negativeKey;
		private final int tapStep;
		private final int sweepStep;
		private final long holdDelayMillis;
		private final long sweepIntervalMillis;
		private boolean positivePressed;
		private boolean negativePressed;
		private long positiveNextSweepMillis;
		private long negativeNextSweepMillis;

		KeyPair(KeyBinding positiveKey, KeyBinding negativeKey, int tapStep, int sweepStep, long holdDelayMillis, long sweepIntervalMillis) {
			this.positiveKey = positiveKey;
			this.negativeKey = negativeKey;
			this.tapStep = tapStep;
			this.sweepStep = sweepStep;
			this.holdDelayMillis = holdDelayMillis;
			this.sweepIntervalMillis = sweepIntervalMillis;
		}

		int read(long nowMillis) {
			return readOne(true, nowMillis) + readOne(false, nowMillis);
		}

		private int readOne(boolean positive, long nowMillis) {
			final boolean pressed = (positive ? positiveKey : negativeKey).isPressed();
			final boolean wasPressed = positive ? positivePressed : negativePressed;
			long nextSweep = positive ? positiveNextSweepMillis : negativeNextSweepMillis;
			int delta = 0;
			if (pressed && !wasPressed) {
				delta = positive ? tapStep : -tapStep;
				nextSweep = nowMillis + holdDelayMillis;
			} else if (pressed && nowMillis >= nextSweep) {
				delta = positive ? sweepStep : -sweepStep;
				nextSweep = nowMillis + sweepIntervalMillis;
			} else if (!pressed) {
				nextSweep = 0;
			}
			if (positive) {
				positivePressed = pressed;
				positiveNextSweepMillis = nextSweep;
			} else {
				negativePressed = pressed;
				negativeNextSweepMillis = nextSweep;
			}
			return delta;
		}
	}
}

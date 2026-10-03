package org.mtr.mod.client;

import org.mtr.core.mmtr.MmtrHidMapping;
import org.mtr.core.mmtr.MmtrLightSwitch;
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

	/**
	 * 已经认下的规格串（{@code VehicleExtension#getMmtrHandleSpecFromSync()} 的原文）。
	 *
	 * <p>2026-09-23 现场（用户：「怎么 AFB 一会是 160，一会是 220」）：规格原来**只在坐上司机位的那一拍读一次**，
	 * 而那一拍镜像往往还是空的（车对象刚建、快照还没到）⇒ 退回 {@link ThreeHandleSpec#defaults()}，
	 * 定速上限是**出厂 160**；等镜像到了，同一根杆压到底却是**配置的 220**。同一个人、同一根杆，
	 * 读数在两次上车之间摇摆。现在改成"镜像串一变就重新认"，并且**镜像还没到就沿用上一份**（不再掉回出厂值）。
	 */
	private static String activeSpecRaw = "";

	/** 我坐的那个驾驶室（{@code <车节><A|B>}）：换端也要重新认手柄与灯光，见 {@link #tick()}。 */
	private static String activeCabSpec = "";

	// 三根手柄的当前位置（客户端本地；引擎侧是权威副本）
	private static int driveHandle;
	private static int brakePosition;
	private static int cruiseKmh;
	private static int reverser = 1;

	/**
	 * <b>单手柄</b>车底的那一根杆（有级 {@code NOTCHED} / 降保升 {@code AIR_BRAKE} / 无级 …）。
	 *
	 * <p>位置是**一个有符号的档位**：{@code +1…+P} = 牵引第 1…P 档，{@code 0} = 惰行/关闭，
	 * {@code −1…−B} = 制动第 1…B 档，{@code −(B+1)} = **紧急位 EB**（真车的 P5+B8+EB 手柄就是
	 * "往制动侧推到底再多一格"）。</p>
	 *
	 * <p>为什么客户端要自己算这一根杆：引擎的三种操纵方式里，三手柄读 {@code driveHandle}，
	 * 而有级/无级读的是 {@code throttleNotch} / {@code brakeNotch} —— 这两个字段此前被
	 * {@link #send} **硬编码成 0**（见那里的注释），于是有级车底在游戏里只有制动、永远没有牵引。
	 * 现在按镜像里的 {@code mmtrMode} 分流：三手柄照旧，其它车底把这根杆折成两个档位字段。</p>
	 */
	private static int singleNotch;

	/** 当前车底的控制方式（镜像 {@code mmtrMode}），空串 = 还没同步到。 */
	private static String activeMode = "";
	/** 当前车底的档数，来自镜像的 {@code mmtrPowerNotches} / {@code mmtrBrakeNotches}。 */
	private static int activePowerNotches = 7;
	private static int activeBrakeNotches = 8;

	/** 我们正在控制的车（握钥匙的那辆）；0 = 手里没有驾驶室。 */
	private static long controlledVehicleId;

	/**
	 * 灯光开关（notes/352）：**我这一端**的档位（{@code MmtrLightSwitch} 的 关/尾/日/夜）。
	 *
	 * <p>上车时从镜像认下来（别把别人停在"远光"的车又掰回尾灯），按 {@code L} 循环一档并**立刻单独发一包**
	 * （点按语义，与响应键同一条路）。谁是"我这一端"由引擎的占用状态决定，这里只报档位。</p>
	 */
	private static int lightSwitch = MmtrLightSwitch.DEFAULT;
	/** 本车底有没有"关闭"档（镜像 {@code mmtrLightLoco}；客户端没有 consist-types.json）。 */
	private static boolean lightHasOff;
	/** **我在哪一端**（1 = A，2 = B）：座位点的几何事实，见 {@code MmtrVehicleAnchors.engineEndOfSeat}。 */
	private static int lightEnd = MmtrLightSwitch.END_A;
	/** 灯光键的上升沿检测（{@code KeyBinding} 只有 isPressed）。 */
	private static boolean lightsKeyPressed;

	// 上次发出去的值（变化检测用哨兵值强制首帧必发）
	private static int sentDriveHandle = Integer.MIN_VALUE;
	private static int sentBrakePosition = Integer.MIN_VALUE;
	private static int sentCruiseKmh = Integer.MIN_VALUE;
	private static int sentReverser = Integer.MIN_VALUE;
	private static int sentSingleNotch = Integer.MIN_VALUE;
	private static int sentLightSwitch = Integer.MIN_VALUE;
	private static long lastSendMillis;

	/** 每客户端 tick 调用一次（挨着其它交互键，见 {@code MainRenderer}）。 */
	public static void tick() {
		/*
		 * 界面开着（聊天/背包）时**不读驾驶键**，但**继续**维持手柄状态与每秒补发。
		 *
		 * <p>为什么必须挡：这套键位（用户 2026-09-29 指定）里 A/D/;/'/R/F/Q/J/L 全是字母与符号，
		 * 而打字就是在按它们 —— 不挡的话"在聊天框里打一个 R"会顺手响应一次 AWS、打一个 A 会把油门推上去。
		 * 为什么不能整个 tick 都停：手柄是保持型状态、每秒还要补发一次（引擎侧占用锁靠"人还在司机位上"维持，
		 * 而司机位判据来自身上的骑行状态），聊天时停发会让"我坐在驾驶室里聊天"和"我不在"分不出来。</p>
		 */
		final boolean keyboardBlocked = MinecraftClient.getInstance().getCurrentScreenMapped() != null;
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
		/*
		 * "刚坐上司机位"= 换车**或换端**（notes/352）。换端必须也算：同一个人从 A 端走到 B 端时车没变，
		 * 但那边的换向器位置与**灯光开关**都是另一套值，不重新认就会把 A 端那套推给 B 端。
		 */
		if (controlledVehicleId != vehicleId || !seat.cabSpec().equals(activeCabSpec)) {
			controlledVehicleId = vehicleId;
			activeCabSpec = seat.cabSpec();
			// 换车了：规格重新认。镜像还没到就**沿用上一份**（见 activeSpecRaw），不要退回出厂值。
			activeSpecRaw = "";
			/*
			 * 控制方式**不能跨车沿用**：改用这辆车自己的记忆（见 modeByVehicle）。
			 * 沿用上一辆车的方式时，BR101 → SAF420 的头一两拍会按三手柄走，A/D 去改 driveHandle，
			 * 而单手柄车底根本不读它 ⇒ "按 A/D 没反应"（2026-09-29 实机）。
			 */
			activeMode = modeByVehicle.getOrDefault(vehicleId, "");
			reportUnknownModeOnce(vehicleId, vehicle);
			adoptSpec(vehicle);
			reverser = vehicle != null && vehicle.getMmtrReverserFromSync() < 0 ? -1 : 1;
			adoptLightSwitchOnSeating(vehicle, seat);
			neutraliseHandles();
			sentDriveHandle = Integer.MIN_VALUE;
			warnIfNotThreeHandle(vehicle);
			MmtrTrace.log("[MMTR-DRV] client seated at " + seat.cabSpec() + " (vehicle " + vehicleId
				+ " spec=" + activeSpec.encode() + " 镜像规格=" + (vehicle == null || vehicle.getMmtrHandleSpecFromSync().isEmpty() ? "还没到（先用上面这份）" : "已到")
				+ " 灯光=" + MmtrLightSwitch.label(lightSwitch) + (lightHasOff ? "(机车四档)" : "(动车组三档)") + ")");
		}

		// 每一拍都对一次规格串：镜像（或配置）到了就换过来 —— 只比字符串，零开销。
		adoptSpec(vehicle);
		adoptVehicleMode(vehicle);
		adoptLightCapability(vehicle, seat);
		if (!keyboardBlocked) {
			applyKeyDeltas(nowMillis);
			handleLightsKey(vehicleId, vehicle, seat);
		}
		applyHidAxes();
		if (handleChangedThisTick) {
			// 动了手柄 ⇒ 顺手把这个驾驶室接管过来（引擎要的"哪一端在前"由它决定，见 MmtrCabInteraction）。
			MmtrCabInteraction.claimSeatWhenDriving(seat);
		}
		if (!keyboardBlocked && readAcknowledgePressed()) {
			// 点按语义：**立刻单独发一包**（不等变化检测 / 每秒补发），引擎消费后即清。
			send(vehicleId, true);
			MmtrTrace.log("[MMTR-DRV] client pressed the response key (acknowledge) for vehicle " + vehicleId);
			message("响应键：已确认（AWS 报警 / 解除紧急制动）");
		}
		if (changedSinceLastSend() || nowMillis - lastSendMillis >= REFRESH_INTERVAL_MILLIS) {
			send(vehicleId);
			traceSentState(nowMillis, vehicleId);
		}
	}

	// ---- 灯光开关（notes/352） ----------------------------------------------------------------------

	/**
	 * 上车（含换端）时把**我这一端**的灯光开关从镜像认下来。
	 *
	 * <p>与换向器同一个道理：别把别人停在"远光"的车又掰回尾灯。端用座位点的几何（{@code seat.engineEnd}）
	 * 而不是镜像里的 {@code mmtrCabEnd} —— 刚坐下那一拍镜像还可能是上一任的值，而"我在哪一端"是本地的几何事实。</p>
	 */
	private static void adoptLightSwitchOnSeating(@Nullable VehicleExtension vehicle, MmtrDriverSeat.Seat seat) {
		lightEnd = seat.engineEnd();
		if (vehicle == null) {
			lightSwitch = MmtrLightSwitch.DEFAULT;
			lightHasOff = false;
			return;
		}
		lightHasOff = vehicle.isMmtrLightLocoFromSync();
		lightSwitch = MmtrLightSwitch.sanitize(
			MmtrLightSwitch.switchOfEnd(vehicle.getMmtrLightAFromSync(), vehicle.getMmtrLightBFromSync(), lightEnd), lightHasOff);
		sentLightSwitch = Integer.MIN_VALUE;
	}

	/**
	 * 每拍对一次"本车底有没有关闭档"，并且**别人改了灯**时跟着走。
	 *
	 * <h2>⚠️ 光有"镜像 != 我上次发的值"这条判据不够（2026-10-01 实机：灯光档位自己来回跳）</h2>
	 *
	 * <p>镜像要**一个来回**才回来。我按下 L 发出"近光"之后，在自己这个值回来之前，先看到的是**旧值**
	 * （尾灯）：{@code mirror(尾灯) != sentLightSwitch(日间)} 成立、{@code mirror != lightSwitch} 也成立，
	 * 于是它"跟着镜像走"退回尾灯并再发一次；下一拍我先前发出去的那个"近光"才到，又把它拉回近光……
	 * 这条规则本身变成了**自己跟自己打架的振荡器**，频率就是镜像延迟（实机 2–20 次/秒，
	 * 服务端 `[MMTR-LIGHT]` 每秒成对翻转）。用户口径"按 L 会连续切换档位 / 无法正常切换"就是这个。</p>
	 *
	 * <p>所以：**自己刚扳过开关的 {@link #LIGHT_MIRROR_GRACE_MILLIS} 毫秒内不跟镜像走**。
	 * 镜像迟到的那几拍里，我自己的值就是权威；窗口过后若镜像真的不一样（别人改了、司机离开回落、
	 * 连挂灭尾灯），照样跟上 —— 但那时它已经不是"我刚发出去的那个值的回声"了。</p>
	 */
	private static void adoptLightCapability(@Nullable VehicleExtension vehicle, MmtrDriverSeat.Seat seat) {
		if (vehicle == null) {
			return;
		}
		lightEnd = seat.engineEnd();
		lightHasOff = vehicle.isMmtrLightLocoFromSync();
		final int mirror = MmtrLightSwitch.switchOfEnd(vehicle.getMmtrLightAFromSync(), vehicle.getMmtrLightBFromSync(), lightEnd);
		if (mirror == sentLightSwitch || !MmtrLightSwitch.isKnown(mirror) || mirror == lightSwitch) {
			return;
		}
		if (System.currentTimeMillis() - lastLightSwitchChangeMillis < LIGHT_MIRROR_GRACE_MILLIS) {
			return;
		}
		lightSwitch = MmtrLightSwitch.sanitize(mirror, lightHasOff);
		MmtrTrace.log("[MMTR-DRV] 灯光开关跟着镜像走（别人改的）：" + MmtrLightSwitch.label(lightSwitch));
	}

	/** 自己扳动灯光开关之后，多久之内**不跟镜像走**（见 {@link #adoptLightCapability}）。 */
	private static final long LIGHT_MIRROR_GRACE_MILLIS = 2000L;
	/** 上一次"我这边档位真的变了"的时刻（{@code send()} 里维护）。 */
	private static long lastLightSwitchChangeMillis;

	/**
	 * 灯光键（{@code L}）的**上升沿**：本端开关循环一档（动车组三档 / 机车四档），
	 * 立刻单独发一包（点按语义，与响应键同一条路），并在动作栏说清现在是哪一档。
	 */
	private static void handleLightsKey(long vehicleId, @Nullable VehicleExtension vehicle, MmtrDriverSeat.Seat seat) {
		final boolean pressed = KeyBindings.MMTR_LIGHTS.isPressed();
		final boolean rising = pressed && !lightsKeyPressed;
		lightsKeyPressed = pressed;
		if (!rising) {
			return;
		}
		lightEnd = seat.engineEnd();
		lightHasOff = vehicle != null && vehicle.isMmtrLightLocoFromSync();
		lightSwitch = MmtrLightSwitch.cycle(lightSwitch, lightHasOff);
		send(vehicleId);
		MmtrTrace.log("[MMTR-DRV] 灯光开关 → " + MmtrLightSwitch.label(lightSwitch) + "（" + seat.cabSpec() + "，端="
			+ (lightEnd == MmtrLightSwitch.END_B ? "B" : "A") + (lightHasOff ? "，机车四档" : "，动车组三档") + "）");
		message("灯光 " + MmtrLightSwitch.label(lightSwitch) + (lightHasOff ? "（关→尾→日→夜）" : "（尾→日→夜）"));
	}

	/** 限频 2 s 把"客户端到底发了什么"打进日志（{@code -Dmmtr.trace=true} 时可见）。 */
	private static void traceSentState(long nowMillis, long vehicleId) {
		if (nowMillis - lastSentTraceMillis < 2000) {
			return;
		}
		lastSentTraceMillis = nowMillis;
		MmtrTrace.log("[MMTR-DRV] client sent vehicle=" + vehicleId + " " + describeHandles());
	}

	private static long lastSentTraceMillis;

	/**
	 * 响应键（{@code R}）的**上升沿**：AWS 报警确认 / 解除闯信号触发的紧急制动，都是点按语义。
	 *
	 * <p>为什么必须有它：引擎侧 {@code Vehicle.tickMmtrAwsWarning} 在报警后 2.5 s 未确认就 SPAD 紧急制动，
	 * 而客户端此前**既没有这根键、acknowledge 也恒为 false** —— 司机被刹停后无从解除（notes/217 §4 之后
	 * 实测就是"过了信号就再也开不动"）。</p>
	 */
	private static boolean readAcknowledgePressed() {
		final boolean pressed = KeyBindings.MMTR_AWS_ACK.isPressed();
		final boolean rising = pressed && !lastAcknowledgePressed;
		lastAcknowledgePressed = pressed;
		return rising;
	}

	private static boolean lastAcknowledgePressed;

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
		if (isSingleHandle()) {
			/*
			 * **单手柄 = 一根杆，所以 HID 的 Y 轴直接就是这根杆**（用户口径 2026-09-25
			 * 「控制器肯定是 Y 轴直接分配到单手柄上就行了呗」）。
			 *
			 * 这里刻意"**有杆就把键盘让开**"（而不是像三手柄那样键盘叠在轴值上）：单手柄车的 X/Z 轴
			 * 在这类车底上不存在（没有定速杆、没有独立制动杆），所以一旦手柄在位，它就该是**唯一**的
			 * 杆位来源 —— 否则键盘的一次 ±1 会被下一拍的绝对轴值抹掉，"按了没反应"。
			 * 没插手柄时（清障路径）退回键盘增量，纯键盘照常，零回归。
			 */
			final MmtrHidInput.SingleHandleAxis hid = MmtrHidInput.pollSingleHandleAxis();
			if (hid == null) {
				// 一根杆：牵引键往牵引侧推、制动键往制动侧推，另一侧各是回退。
				// 真车的单手柄就是这个手感（一根杆、中央关闭），所以两对键都作用在同一根杆上，
				// 玩家用哪一对都不会"按了没反应"。
				final int delta = DRIVE_KEYS.read(nowMillis) - BRAKE_KEYS.read(nowMillis);
				if (delta != 0) {
					final int before = singleNotch;
					singleNotch = clampSingle(singleNotch + delta);
					if (singleNotch != before) {
						handleChangedThisTick = true;
					}
				}
			} else {
				final int before = singleNotch;
				singleNotch = MmtrHidMapping.singleHandleFromAxis(hid.axis(), activePowerNotches, activeBrakeNotches, hid.deadzone());
				if (singleNotch != before) {
					handleChangedThisTick = true;
				}
			}
		} else {
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

	/**
	 * 这列车底是不是**单手柄**（= 除三手柄之外的一切：有级 / 无级 / 降保升 / 缺省兜底）。
	 *
	 * <p><b>2026-09-29 实机修</b>（用户口径："对于单手柄车而言，A/D 则为控制那个手柄的键位"）：
	 * 这里原来是"镜像还没到 ⇒ 按三手柄走"，而 {@link #send} 只在**单手柄**那一支里填
	 * {@code throttleNotch}（三手柄走 {@code driveHandle}）。于是刚坐上单手柄车、镜像还没到的那一两拍里，
	 * A/D 改的是 {@code driveHandle}，而 NOTCHED 车底根本不读它 —— 现场就是"按 A/D 没反应"。</p>
	 *
	 * <p>现在反过来判：**只有明确的三手柄车底才走三手柄那条路**，其余一切（含"还不知道"）
	 * 都按单手柄填档位。两个方向的猜错都不对称，这才是选它的理由：
	 * 猜成单手柄而车其实是三手柄 ⇒ 三手柄控制器只读 {@code driveHandle}（它不看 {@code throttleNotch}）
	 * ⇒ 车不动、按 D 还在制动侧（无害）；猜成三手柄而车其实是单手柄 ⇒ 按键**完全没反应**（就是这次的现场）。</p>
	 */
	private static boolean isSingleHandle() {
		return !"THREE_HANDLE".equals(activeMode);
	}

	/** 单手柄的行程：牵引侧到 {@code +P}，制动侧到 {@code −B}，再往外一格是紧急位。 */
	private static int clampSingle(int value) {
		final int lowest = -(Math.max(1, activeBrakeNotches) + 1);
		final int highest = Math.max(1, activePowerNotches);
		return Math.max(lowest, Math.min(highest, value));
	}

	/** 单手柄是否停在紧急位。 */
	private static boolean singleHandleEmergency() {
		return isSingleHandle() && singleNotch <= -(Math.max(1, activeBrakeNotches) + 1);
	}

	/**
	 * 认下这列车底的**控制方式与档数**（镜像 {@code mmtrMode} / {@code mmtrPowerNotches} /
	 * {@code mmtrBrakeNotches}）。
	 *
	 * <p>和 {@link #adoptSpec} 一样"只在真的变了时才动"：模式变化意味着杆的档数可能变窄
	 * （换成另一列车底），所以认下之后要把手里的杆位重新钳一次，否则会把越界的档位发出去
	 * （引擎会钳，但客户端 HUD 与实际档位就会不一致）。</p>
	 */
	private static void adoptVehicleMode(@Nullable VehicleExtension vehicle) {
		if (vehicle == null) {
			return;
		}
		final String mode = vehicle.getMmtrModeFromSync();
		final int powerNotches = vehicle.getMmtrPowerNotchesFromSync();
		final int brakeNotches = vehicle.getMmtrBrakeNotchesFromSync();
		if (mode.equals(activeMode) && powerNotches == activePowerNotches && brakeNotches == activeBrakeNotches) {
			return;
		}
		activeMode = mode;
		activePowerNotches = powerNotches;
		activeBrakeNotches = brakeNotches;
		if (!mode.isEmpty()) {
			// 记住这辆车的方式：下次再坐进来（镜像要过一两拍才到）就不用重新猜。
			modeByVehicle.put(vehicle.getId(), mode);
		}
		final int before = singleNotch;
		singleNotch = clampSingle(singleNotch);
		if (singleNotch != before) {
			handleChangedThisTick = true;
		}
		MmtrTrace.log("[MMTR-DRV] vehicle mode adopted: " + activeMode + " P" + activePowerNotches
			+ " B" + activeBrakeNotches + " -> single handle " + (isSingleHandle() ? "ACTIVE" : "off"));
	}

	/**
	 * 这辆车的控制方式记忆（vehicleId → {@code mmtrMode}）与"镜像还没到"时的说法。
	 *
	 * <p>为什么必须记住：{@code activeMode} 原来是**跨车**留着的 —— 从 BR101（THREE_HANDLE）换到
	 * SAF420（NOTCHED）的头一两拍里，客户端仍以为自己在开三手柄车，于是 A/D 去改 {@code driveHandle}，
	 * 而单手柄车底不读它 ⇒ <b>按 A/D 没反应</b>（2026-09-29 实机）。记住之后，"镜像还没到"这个窗口
	 * 里用的是**上次数这辆车时认下的方式**；真正第一次上车（从没认过）才落到 §{@link #isSingleHandle()} 的兜底。</p>
	 */
	private static final java.util.HashMap<Long, String> modeByVehicle = new java.util.HashMap<>();
	/** 已经报过"这辆车的方式还不知道"的车（每辆一行就够）。 */
	private static final java.util.HashSet<Long> modeUnknownReported = new java.util.HashSet<>();

	/**
	 * 第一次坐上"从没认过控制方式、镜像也还没到"的车时说一句（每辆一次）。
	 *
	 * <p>为什么值得说：这一拍起客户端的按键映射是**兜底口径**（单手柄），镜像一到就切回真口径。
	 * 不说的话，这一两拍里的手感差异（例如三手柄车按 A 暂时没牵引）会看起来像"按键坏了"。</p>
	 */
	private static void reportUnknownModeOnce(long vehicleId, @Nullable VehicleExtension vehicle) {
		if (!activeMode.isEmpty() || vehicle == null || !modeUnknownReported.add(vehicleId)) {
			return;
		}
		message("本车底的控制方式还没同步到 —— 先按单手柄走（A/D 推同一根杆），镜像一到自动切回");
	}

	/** 三根手柄归到 关闭 / 运行（缓解） / 定速关闭；单手柄归到 0（惰行）；**换向器不动**。 */
	private static void neutraliseHandles() {
		driveHandle = 0;
		brakePosition = ThreeHandleSpec.runningPosition();
		singleNotch = 0;
		cruiseKmh = 0;
	}

	private static boolean changedSinceLastSend() {
		return driveHandle != sentDriveHandle || brakePosition != sentBrakePosition
			|| cruiseKmh != sentCruiseKmh || reverser != sentReverser || singleNotch != sentSingleNotch
			|| lightSwitch != sentLightSwitch;
	}

	private static void send(long vehicleId) {
		send(vehicleId, false);
	}

	private static void send(long vehicleId, boolean acknowledge) {
		// WHICH HANDLE MODEL THIS CONSIST USES decides which fields carry the driver's input:
		//
		//   THREE_HANDLE cars read `driveHandle` (the continuous traction/rheostatic handle) and
		//   `brakeNotch` (the 11-position air-brake handle), and a notched car would read
		//   `throttleNotch` - which this packet used to hard-code to 0 for EVERYONE. That is why a
		//   NOTCHED consist could be braked but never pulled: its throttle notch arrived as 0 on every
		//   tick, whatever the driver did with the keys (notes/279 follow-up, and the warning this file
		//   prints when it sees a non-three-handle car).
		//
		//   So a single-handle car sends its one handle as the two notch fields instead, with the
		//   engine's own counts as the limits. Everything downstream is unchanged: the engine clamps
		//   per consist type, `ControlState` carries all of these at once, and a car that reads the
		//   three-handle fields simply never sees a non-zero throttle notch from this path.
		final boolean single = isSingleHandle();
		final int throttleNotch = single ? Math.max(0, singleNotch) : 0;
		final int brakeNotch = single ? Math.max(0, -singleNotch) : brakePosition;
		final boolean emergency = single && singleHandleEmergency();
		// acknowledge 只在按下响应键的那一拍为真（点按语义），其余时刻必须为假 ——
		// 引擎把"报警时按下的那一次"当成确认，若常亮就等于每拍都在确认（notes/94 的坑）。
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketDriveControl(vehicleId, throttleNotch, brakeNotch, reverser, emergency, acknowledge, driveHandle, cruiseKmh, lightSwitch));
		sentDriveHandle = driveHandle;
		sentBrakePosition = brakePosition;
		sentSingleNotch = singleNotch;
		sentCruiseKmh = cruiseKmh;
		sentReverser = reverser;
		if (lightSwitch != sentLightSwitch) {
			// 只有"我这边档位真的变了"才算扳动（见 adoptLightCapability 的静默窗口）。
			lastLightSwitchChangeMillis = System.currentTimeMillis();
		}
		sentLightSwitch = lightSwitch;
		lastSendMillis = System.currentTimeMillis();
		MmtrTrace.log("[MMTR-DRV] client handles → " + describeHandles() + " 灯光=" + MmtrLightSwitch.label(lightSwitch)
			+ (lightHasOff ? "(四档)" : "(三档)") + " (vehicle " + vehicleId + (acknowledge ? ", acknowledge" : "") + ")");
	}

	/**
	 * 把镜像里的手柄规格认下来（位置表 / 定速量程 / 制动档比例）。
	 *
	 * <p>客户端没有 consist-types.json，只能靠这条字符串。**只在字符串真的变了时才动**，所以每拍调用也不花钱。
	 * 镜像还没到（{@code decode} 返回 null）时**什么都不做** —— 沿用上一份规格，绝不退回出厂值：
	 * 出厂规格的定速上限是 160、电阻制动力 54 kN，与 BR101 配置（220 / 150 kN）不同，
	 * 退回会让同一根杆的读数在两次上车之间摇摆（2026-09-23 现场：「AFB 一会 160 一会 220」）。
	 * 新规格可能比旧规格窄（11 位换 8 位这类），所以认下之后手里的杆位要重新钳一次。
	 */
	private static void adoptSpec(@Nullable VehicleExtension vehicle) {
		final String raw = vehicle == null ? "" : vehicle.getMmtrHandleSpecFromSync();
		if (raw.isEmpty() || raw.equals(activeSpecRaw)) {
			return;
		}
		final ThreeHandleSpec decoded = ThreeHandleSpec.decode(raw);
		if (decoded == null) {
			return;
		}
		activeSpecRaw = raw;
		activeSpec = decoded;
		driveHandle = activeSpec.clampDriveHandle(driveHandle);
		brakePosition = activeSpec.clampBrakePosition(brakePosition);
		cruiseKmh = activeSpec.clampCruiseKmh(cruiseKmh);
		MmtrTrace.log("[MMTR-DRV] handle spec adopted: " + activeSpec.encode());
	}

	/**
	 * 这列车底不是三手柄就告诉司机**它现在由哪根杆驱动** —— 旧版本的这句话是"键位不驱动老车底"，
	 * 因为那时确实不驱动；现在单手柄车底走同一批键位（见 {@link #applyKeyDeltas}），
	 * 所以这句话改成报到几档、以及哪一对键在推它，否则会把人吓回去不用。
	 */
	private static void warnIfNotThreeHandle(@Nullable VehicleExtension vehicle) {
		if (vehicle == null) {
			return;
		}
		final String mode = vehicle.getMmtrModeFromSync();
		if (!mode.isEmpty() && !"THREE_HANDLE".equals(mode) && !"DEFAULT".equals(mode)) {
			message("本车底是 " + mode + "（单手柄 P" + Math.max(1, activePowerNotches) + "+B"
				+ Math.max(1, activeBrakeNotches) + "）：牵引/制动键推同一根杆，推到底再一格是 EB");
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

	/** 油门手柄的短语：牵引 45% / 电阻制动 最小 / 关闭（HUD 与日志共用 {@link ThreeHandleSpec} 的说法）。单手柄车底给的是那根杆的读数。 */
	public static String driveHandleText() { return isSingleHandle() ? singleNotchText() : activeSpec.describeDriveHandle(driveHandle); }

	/** 制动手柄的档名：运行 / 1A / 1B / 2…8 / EB；单手柄车底只有一根杆，两个读数因此是同一个。 */
	public static String brakeText() { return isSingleHandle() ? singleNotchText() : activeSpec.brakePositionLabel(brakePosition); }

	/** 制动手柄是否在紧急位（HUD 用它上红色）。 */
	public static boolean isEmergencyBrake() { return isSingleHandle() ? singleHandleEmergency() : activeSpec.isEmergencyPosition(brakePosition); }

	/** 当前车底是不是单手柄（HUD 据此只显示一根杆，而不是并排两根）。 */
	public static boolean isSingleHandleMode() { return isSingleHandle(); }

	/** 单手柄的原始档位（正 = 牵引，负 = 制动，见 {@link #singleNotch}）。 */
	public static int getSingleNotch() { return singleNotch; }

	/** 单手柄的读数：牵引 3/5 · 惰行 · 制动 5/8 · 紧急 EB。 */
	public static String singleNotchText() {
		if (singleHandleEmergency()) {
			return "紧急 EB";
		}
		if (singleNotch > 0) {
			return "牵引 " + singleNotch + "/" + Math.max(1, activePowerNotches);
		}
		if (singleNotch < 0) {
			return "制动 " + (-singleNotch) + "/" + Math.max(1, activeBrakeNotches);
		}
		return "惰行";
	}

	/** 定速巡航的短语：关闭 / 100 km/h。 */
	public static String cruiseText() { return cruiseKmh <= 0 ? "关闭" : cruiseKmh + " km/h"; }

	public static String reverserText() { return reverser > 0 ? "前进" : reverser < 0 ? "后退" : "中立"; }

	// ---- 灯光的读接口（HUD） -----------------------------------------------------------------------

	/** 我这一端的灯光开关档位（本地真值，与镜像同步；判据在引擎的 {@code MmtrLightSwitch}）。 */
	public static int getLightSwitch() { return lightSwitch; }

	/** 本车底有没有"关闭"档（机车四档 / 动车组三档）—— HUD 据此决定要不要多画那一档。 */
	public static boolean lightHasOffPosition() { return lightHasOff; }

	/**
	 * 灯光那一行（HUD）：**本端**档位 + 对端的档位（从镜像读，因为它不是我能设的那一份），
	 * 换向器 N 时端点明"固定红"—— 否则司机会看到"开关写着远光、车却是红的"，只能靠猜。
	 */
	public static String lightText(@Nullable VehicleExtension vehicle) {
		if (vehicle == null) {
			return MmtrLightSwitch.label(lightSwitch);
		}
		final int farEnd = lightEnd == MmtrLightSwitch.END_B ? MmtrLightSwitch.END_A : MmtrLightSwitch.END_B;
		final int far = MmtrLightSwitch.switchOfEnd(vehicle.getMmtrLightAFromSync(), vehicle.getMmtrLightBFromSync(), farEnd);
		final boolean neutral = vehicle.getMmtrReverserFromSync() == 0;
		final int nearState = MmtrLightSwitch.lampState(lightSwitch, vehicle.getMmtrReverserFromSync());
		final int farState = MmtrLightSwitch.lampState(far, vehicle.getMmtrReverserFromSync());
		return "本端 " + MmtrLightSwitch.label(nearState) + " · 对端 " + MmtrLightSwitch.label(farState) + (neutral ? "（换向N）" : "");
	}

	/** 当前手柄状态的一句话（日志用；HUD 分行显示同样这几个词，避免两处各写一套）。 */
	public static String describeHandles() {
		if (isSingleHandle()) {
			return "单手柄=" + singleNotchText() + " 定速=" + cruiseText() + " 换向=" + reverserText();
		}
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

package org.mtr.mod.render;

import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.holder.Window;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MmtrDriveInput;
import org.mtr.mod.client.MmtrDriverSeat;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.VehicleExtension;

import javax.annotation.Nullable;

/**
 * 三手柄机车的**屏幕右上角简略 HUD**：速度 + 三根手柄的位置，给正在开车的人看。
 *
 * <h2>为什么是"简略"</h2>
 *
 * <p>用户口径（2026-09-19）：「屏幕右上角 HUD 上简略显示操作信息」。所以这里只有两列若干行
 * （状态 / 速度 / 加速度 / 电机 / 油门 / 制动 / **管压 / 缸压 / 制动力** / 定速 / 换向），
 * 没有面板、没有量程刻度、没有第二个表——那套（BR101 的仪表画面）走资源包的 {@code mmtr_hud_*} 面板，
 * 是另一条路（重建步 B6）。</p>
 *
 * <h2>任务信息不在这里（用户口径 2026-09-25）</h2>
 *
 * <p>作业 / 任务 / 子任务 / 提示那几行本来在这块面板的最上面，现在搬去了**左上角的独立面板**
 * {@link MmtrTaskHud}（连同任务三种时刻的提示音与动作栏播报）。</p>
 *
 * <p>★ 这块面板本身**一行都不因此改动**：不设标题行、行序不动、宽度仍由最长的值决定 ——
 * 只是不再画任务那几行。理由是任务说明可以很长，而面板宽度由最长那行决定；把它搬走之后
 * 这块自然变窄变短，回到纯操作读数的样子。两块面板分工：**左上角说"我该干什么"，
 * 右上角说"车现在什么状态"**。任务信息只有一个出口（左上角），这边不留副本 ——
 * 两块面板说同一件事时，"哪块是准的"就成了新的困惑源。</p>
 *
 * <p>「电机」那一行是 notes/250（用户 2026-09-23「在屏幕右上角 HUD 上提示电机做功」）：出力 kN + 做功 MW，
 * 两个数都由**这一拍真正施加的力**折算而来，因此"HUD 说在出力、车却不动"这种组合不可能出现。</p>
 *
 * <p>「管压 / 缸压 / 制动力」三行是 notes/266（用户 2026-09-25「在屏幕 UI 右上角添加制动压力以及制动力显示」）：
 * bar 口径上线后，"司机要多少气压（管压）、分配阀给了多少（缸压）、轮周上到底有多少力（制动力）"
 * 是三个不同的数；制动力那行还拆开"气 / 电"，因为电制动在 153.6 km/h 之上会按恒功率掉。</p>
 *
 * <h2>什么时候显示</h2>
 *
 * <p>只有**坐在司机位上**时画（{@link MmtrDriverSeat#isAtControls()}，与"能不能操作手柄"同一条判据）：
 * 乘客不该看到司机的手柄读数，而站在站台上的人更不该看到。判据同源也意味着"能看到这个 HUD"
 * 与"按了手柄有反应"永远一致 —— 不会出现"HUD 在、按键没反应"这种最费解的组合。</p>
 *
 * <p>左上角那块任务卡（{@link MmtrTaskHud}）的显示面**更宽**：坐在这列车上就有，不限司机位 ——
 * "这趟车在做什么作业"是车上所有人都能看的信息。两块面板的判据不同是刻意的，不要合到一起。</p>
 *
 * <h2>数值从哪来</h2>
 *
 * <p>手柄位置取自客户端输入层自己持有的状态（{@link MmtrDriveInput}，它才是这三根手柄的本地真值）；
 * 速度取自车辆镜像（与服务端权威值同源，见 {@code VehicleExtension.getSpeed()}）。</p>
 */
public final class MmtrDriverHud {

	private MmtrDriverHud() {
	}

	private static final int EDGE_PADDING = 6;
	private static final int PADDING = 5;
	private static final int COLUMN_GAP = 8;
	private static final int LINE_SPACING = 2;
	private static final int BACKGROUND_COLOR = 0xA0000000;
	private static final int LABEL_COLOR = 0xFF9FB3C6;
	private static final int VALUE_COLOR = 0xFFF2F4F6;
	private static final int TRACTION_COLOR = 0xFF7FC4FF;
	private static final int BRAKE_COLOR = 0xFFFF9900;
	private static final int EMERGENCY_COLOR = 0xFFFF5555;
	private static final int CRUISE_COLOR = 0xFF55FFFF;
	private static final int HOLD_COLOR = 0xFFFFAA33;

	/** 上一拍显示过的"为什么不动"，用来只在**变化**时给玩家一句提示（动作栏），而不是每帧刷屏。 */
	private static String lastHoldReason = "";

	/** 一行：标签 + 值 + 值的颜色（标签一律用同一个弱色，值才承载信息）。 */
	private record Row(String label, String value, int valueColor) {
	}

	public static void render(GraphicsHolder graphicsHolder) {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		if (minecraftClient.getCurrentScreenMapped() != null) {
			return;
		}
		final VehicleExtension ridingVehicle = MmtrDriverSeat.ridingVehicle();
		notifyHoldReason(ridingVehicle);
		notifyAwsWarning(ridingVehicle);
		if (!MmtrDriverSeat.isAtControls()) {
			return;
		}
		final Row[] rows = rows(ridingVehicle);
		if (rows.length == 0) {
			return;
		}

		// 字体：MMTR 屏幕 UI 字体（DIN 1451 西文 + HarmonyOS Sans SC 中文，notes/221）。
		// ★ 测量与绘制必须用**同一份带样式的文本**：换字体后字宽会变，用默认字体量、用 UI 字体画，
		//   面板宽度与右对齐就会错位（这类"差几个像素"的问题在屏幕上很难归因）。
		final MutableText[] labels = new MutableText[rows.length];
		final MutableText[] values = new MutableText[rows.length];
		int labelWidth = 0;
		int valueWidth = 0;
		for (int i = 0; i < rows.length; i++) {
			labels[i] = IDrawing.withUIFont(TextHelper.literal(rows[i].label()));
			values[i] = IDrawing.withUIFont(TextHelper.literal(rows[i].value()));
			labelWidth = Math.max(labelWidth, GraphicsHolder.getTextWidth(labels[i]));
			valueWidth = Math.max(valueWidth, GraphicsHolder.getTextWidth(values[i]));
		}

		final Window window = minecraftClient.getWindow();
		final int panelWidth = PADDING * 2 + labelWidth + COLUMN_GAP + valueWidth;
		final int panelHeight = PADDING * 2 + rows.length * IGui.TEXT_HEIGHT + (rows.length - 1) * LINE_SPACING;
		final int panelRight = window.getScaledWidth() - EDGE_PADDING;
		final int panelLeft = panelRight - panelWidth;
		final int panelTop = EDGE_PADDING;

		final GuiDrawing guiDrawing = new GuiDrawing(graphicsHolder);
		guiDrawing.beginDrawingRectangle();
		guiDrawing.drawRectangle(panelLeft, panelTop, panelRight, panelTop + panelHeight, BACKGROUND_COLOR);
		guiDrawing.finishDrawingRectangle();

		int y = panelTop + PADDING;
		for (int i = 0; i < rows.length; i++) {
			graphicsHolder.drawText(labels[i], panelLeft + PADDING, y, LABEL_COLOR, true, GraphicsHolder.getDefaultLight());
			// 值右对齐：三根手柄的数字长短不一（"牵引 100%" vs "2"），右对齐才看得出变化。
			graphicsHolder.drawText(values[i], panelRight - PADDING - GraphicsHolder.getTextWidth(values[i]), y, rows[i].valueColor(), true, GraphicsHolder.getDefaultLight());
			y += IGui.TEXT_HEIGHT + LINE_SPACING;
		}
	}

	private static Row[] rows(@Nullable VehicleExtension vehicle) {
		final int speedKmh = vehicle == null ? 0 : (int) Math.round(Math.abs(vehicle.getSpeed()) * 3600);
		final int driveHandle = MmtrDriveInput.getDriveHandle();
		final String holdReason = holdReasonOf(vehicle);
		final java.util.ArrayList<Row> rows = new java.util.ArrayList<>();
		/*
		 * 这一块**只说车**（操作读数），版式与加任务卡之前**逐行一致**：不设标题行、行序不动。
		 * 任务那四行（作业 / 任务 / 子任务 / 提示）搬去了左上角的独立面板 {@link MmtrTaskHud} ——
		 * 只搬走，不在这边留副本（两块面板说同一件事时，"哪块是准的"就成了新的困惑源）。
		 */
		if (!holdReason.isEmpty()) {
			// 被信号/进路/任务按住时，把理由顶到操作读数之前 —— 这正是"手柄有反应但车不动"的答案。
			rows.add(new Row("状态", holdReason, HOLD_COLOR));
		}
		rows.add(new Row("速度", speedKmh + " km/h", VALUE_COLOR));
		/*
		 * notes/261（用户 2026-09-23「AFB 定速 160、当前 20 km/h，手柄 20% 和 100% 的加速度是错误的相同的」）：
		 * **把实测加速度直接显示出来** —— 20% 与 100% 到底差多少，一眼可读，不必再靠感觉/靠日志反推。
		 * 算法：镜像速度的差分（约 1 s 窗口），客户端速度本来就每拍随快照更新，够用。
		 */
		rows.add(new Row("加速度", accelerationText(vehicle), VALUE_COLOR));
		// notes/250（用户 2026-09-23「在屏幕右上角 HUD 上提示电机做功」）：紧挨速度给出**电机出力与做功**。
		// 两个数都由引擎按"这一拍真正施加的力"给出（三手柄走控制器的实际比例 ⇒ 含黏着截断与建力延迟），
		// 所以手柄推到底而这一行还是 0 kN 时，那就是真的没在出力（联锁/保护），不是显示问题。
		rows.add(new Row("电机", motorText(vehicle), motorColor(vehicle)));
		// 手柄读数：三手柄车底并排两行（油门/制动），**单手柄车底只有一根杆**，所以只给一行 ——
		// 并排两行显示同一个读数会让人以为车有两根杆（notes/279 的控制模式）。
		if (MmtrDriveInput.isSingleHandleMode()) {
			final int singleNotch = MmtrDriveInput.getSingleNotch();
			rows.add(new Row("手柄", MmtrDriveInput.singleNotchText(),
				MmtrDriveInput.isEmergencyBrake() ? EMERGENCY_COLOR : singleNotch > 0 ? TRACTION_COLOR : singleNotch < 0 ? BRAKE_COLOR : LABEL_COLOR));
		} else {
			rows.add(new Row("油门", MmtrDriveInput.driveHandleText(), driveHandle > 0 ? TRACTION_COLOR : driveHandle < 0 ? BRAKE_COLOR : LABEL_COLOR));
			rows.add(new Row("制动", MmtrDriveInput.brakeText(), MmtrDriverHud.isEmergencyBrakeInput() ? EMERGENCY_COLOR : MmtrDriveInput.getBrakePosition() > 0 ? BRAKE_COLOR : LABEL_COLOR));
		}
		/*
		 * notes/266（用户 2026-09-25「拉之前在屏幕 UI 右上角添加制动压力以及制动力显示」）：
		 * 紧挨制动手柄给三行 —— **管压 / 缸压（bar）+ 制动力（kN，气/电拆开）**。
		 *
		 * 为什么这三行必须在一起：bar 口径上线后，"拉了闸有没有气、气有多少"与"到底出没出力"是
		 * 三个不同的数（管压是司机要的、缸压是分配阀给的、制动力才是作用在轮周上的）。
		 * 现场最费解的组合（"闸拉了、缸压上来了、车却没减速"）现在一眼可拆。
		 */
		rows.add(new Row("管压", barText(vehicle, true), pressureColor(vehicle, true)));
		rows.add(new Row("缸压", barText(vehicle, false), pressureColor(vehicle, false)));
		rows.add(new Row("制动力", brakeForceText(vehicle), brakeForceColor(vehicle)));
		rows.add(new Row("定速", MmtrDriveInput.cruiseText(), MmtrDriveInput.getCruiseKmh() > 0 ? CRUISE_COLOR : LABEL_COLOR));
		rows.add(new Row("换向", MmtrDriveInput.reverserText(), VALUE_COLOR));
		/*
		 * 灯光（notes/352）：**本端 / 对端**各一档 —— 用户口径是"每个驾驶室各一个开关"，
		 * 所以司机要能同时看见自己这一端与对面那一端（换向 N 时两端都会被强制成红，这里报生效后的结果）。
		 */
		rows.add(new Row("灯光", MmtrDriveInput.lightText(vehicle), lightColor(vehicle)));
		return rows.toArray(new Row[0]);
	}

	/** 灯光那一行的颜色：白前照灯 = 亮色、尾灯 = 红、关闭 = 灰（档位语义与渲染侧同一份判据）。 */
	private static int lightColor(@Nullable VehicleExtension vehicle) {
		if (vehicle == null) {
			return LABEL_COLOR;
		}
		final int reverser = vehicle.getMmtrReverserFromSync();
		final int near = org.mtr.core.mmtr.MmtrLightSwitch.lampState(MmtrDriveInput.getLightSwitch(), reverser);
		if (near == org.mtr.core.mmtr.MmtrLightSwitch.OFF) {
			return LABEL_COLOR;
		}
		return near == org.mtr.core.mmtr.MmtrLightSwitch.TAIL || reverser == 0 ? EMERGENCY_COLOR : VALUE_COLOR;
	}

	/** 空调用的紧急判据转发（只为一处调用点读起来短一点）。 */
	private static boolean isEmergencyBrakeInput() {
		return MmtrDriveInput.isEmergencyBrake();
	}

	/**
	 * **管压 / 缸压**（bar，notes/266）：两位小数 —— 5.20 与 3.80 是判据（全常用缸压 3.8 bar）。
	 * 车底没有气压规格（legacy 模式）时按出厂满量程折算，读数照样有，只是那一口径下没有 bar 语义。
	 */
	private static String barText(@Nullable VehicleExtension vehicle, boolean pipe) {
		final double bar = vehicle == null ? 0 : pipe ? vehicle.getMmtrPipeBar() : vehicle.getMmtrCylinderBar();
		return String.format(java.util.Locale.ROOT, "%.2f bar", bar);
	}

	/** 压力那一行的颜色：缸压建起来 = 制动色（在施闸），管压掉下去也是制动色（减压中）。 */
	private static int pressureColor(@Nullable VehicleExtension vehicle, boolean pipe) {
		if (vehicle == null) {
			return LABEL_COLOR;
		}
		return pipe
			? vehicle.getMmtrPipeBar() < 5.1 ? BRAKE_COLOR : LABEL_COLOR
			: vehicle.getMmtrCylinderBar() > 0.05 ? BRAKE_COLOR : LABEL_COLOR;
	}

	/**
	 * **制动力**那一行的值（kN，notes/266/269）：合计 + 括号里拆开"气 / 电"。
	 *
	 * <p>两个数**都读镜像**（服务端算好发下来）：电制动取电机出力的负侧；气制动是**整列**的气制动力
	 * （逐车求和）—— 不能用车头缸压反算：逐车管压 + 电空混合之后，机车自己那份缸压会被 EP 阀削到 0
	 * （电制动替掉了它），而拖车仍在气制动，反算就会显示成"只有电制动"（notes/269 修的就是这个）。
	 * **两边都显示**（哪怕一边是 0）：0 本身是信息 —— "电全吃下了"与"气没建起来"是两件事。</p>
	 */
	private static String brakeForceText(@Nullable VehicleExtension vehicle) {
		if (vehicle == null) {
			return "0 kN（气 0 · 电 0）";
		}
		final double pneumaticKn = vehicle.getMmtrPneumaticBrakeForceN() / 1000;
		final double electricKn = vehicle.getMmtrElectricBrakeForceN() / 1000;
		final double totalKn = vehicle.getMmtrBrakeForceN() / 1000;
		// notes/267：被黏着截断时**必须说出来** —— 否则"拉到底却只有这么点力"会被当成 bug 查
		final String clipped = vehicle.isMmtrBrakingAdhesionLimited() ? " · 黏着截断" : "";
		return Math.round(totalKn) + " kN（气 " + Math.round(pneumaticKn) + " · 电 " + Math.round(electricKn) + "）" + clipped;
	}

	/** 制动力那行的颜色：在刹车 = 制动色，没刹车 = 弱色（"没制动"本身是信息）。 */
	private static int brakeForceColor(@Nullable VehicleExtension vehicle) {
		if (vehicle == null || vehicle.getMmtrBrakeForceN() <= 500) {
			return LABEL_COLOR;
		}
		// 黏着截断时给警示色：这一行说的是"传不下去"，与"司机没拉"是两回事（notes/267）。
		return vehicle.isMmtrBrakingAdhesionLimited() ? HOLD_COLOR : BRAKE_COLOR;
	}

	/**
	 * **电机做功**那一行的值（notes/250）：{@code 出力 kN · 做功 MW}。
	 *
	 * <p>出力与做功都带符号：牵引为正、电阻制动为负 —— 一眼能分出"在拉"还是"在发电/耗能"。
	 * 力用整数 kN（0–300 kN 的量程，小数没有意义），功率保留两位（0–6.4 MW）。</p>
	 */
	private static String motorText(@Nullable VehicleExtension vehicle) {
		if (vehicle == null) {
			return "0 kN · 0.00 MW";
		}
		final double forceKn = vehicle.getMmtrMotorForceN() / 1000;
		final double powerMw = vehicle.getMmtrMotorPowerW() / 1_000_000;
		final String actual = Math.round(forceKn) + " kN · " + (powerMw < 0 ? "-" : "") + String.format(java.util.Locale.ROOT, "%.2f", Math.abs(powerMw)) + " MW";
		/*
		 * notes/255：**把"手柄诉求"也标出来**。用户口径 2026-09-23「杆在 20、显示 20，牵引力不是 300×0.2=60 kN」——
		 * 那一刻车已在设定速度上，AFB 照口径把力削到了 ~0；两个数并排，"被 AFB 削掉了"一眼可见，
		 * 不会再把"手柄要多少"当成"车出了多少"。
		 */
		final double demandKn = vehicle.getMmtrHandleDemandForceN(MmtrDriveInput.getDriveHandle()) / 1000;
		logMotorIfTracing(vehicle, forceKn, demandKn, actual);
		return Math.abs(demandKn - forceKn) > 5 ? actual + "（手柄 " + Math.round(demandKn) + " kN）" : actual;
	}

	/** 上一次采样（算实测加速度用）：速度 m/s 与时间戳。 */
	private static double lastAccelSampleSpeedMps;
	private static long lastAccelSampleMillis;

	/**
	 * **实测加速度**（m/s²，notes/261）：镜像速度对时间做差分，窗口约 1 s。
	 *
	 * <p>为什么要有它：用户口径 2026-09-23「AFB 定速 160、当前 20，手柄 20% 与 100% 的加速度是错误的相同的」——
	 * 到底一不一样，屏幕上一读就知道（力那两行只说力，加速度才是司机感觉到的东西）。</p>
	 */
	private static String accelerationText(@Nullable VehicleExtension vehicle) {
		if (vehicle == null) {
			return "0.00 m/s²";
		}
		final double speedMps = Math.abs(vehicle.getSpeed()) * 1000;
		final long now = System.currentTimeMillis();
		if (lastAccelSampleMillis == 0) {
			lastAccelSampleSpeedMps = speedMps;
			lastAccelSampleMillis = now;
			return "0.00 m/s²";
		}
		final double elapsedS = (now - lastAccelSampleMillis) / 1000.0;
		if (elapsedS < 0.5) {
			return lastAccelerationText;
		}
		final double acceleration = (speedMps - lastAccelSampleSpeedMps) / elapsedS;
		lastAccelSampleSpeedMps = speedMps;
		lastAccelSampleMillis = now;
		lastAccelerationText = (acceleration >= 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%.2f", acceleration) + " m/s²";
		return lastAccelerationText;
	}

	/** 上一次算出的加速度文本（两次采样之间沿用，免得每帧都跳）。 */
	private static String lastAccelerationText = "0.00 m/s²";

	/** 上一次打"电机行读数"的时间（1 s 节流）。 */
	private static long lastMotorLogMillis;

	/**
	 * **右上角 HUD 到底收到了什么**（notes/259，1 s 一次，`-Dmmtr.trace=true`）。
	 *
	 * <p>用户口径 2026-09-23「不实时 / 恒 300 kN / 恒 0 / 卡住」四连，而服务端日志一直是对的 ——
	 * 所以必须能直接看到**客户端这一侧**的值：快照值、手柄诉求、以及它俩的差。
	 * 客户端不跑 `simulateMoving`（本机控制器不推进），所以这一行也是"别再改回本机算"的证据。</p>
	 */
	private static void logMotorIfTracing(@Nullable VehicleExtension vehicle, double forceKn, double demandKn, String actual) {
		if (vehicle == null || !org.mtr.core.mmtr.MmtrTrace.isEnabled()) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (now - lastMotorLogMillis < 1000) {
			return;
		}
		lastMotorLogMillis = now;
		org.mtr.core.mmtr.MmtrTrace.log("[MMTR-HUD] 电机行=" + actual
			+ " 快照=" + Math.round(vehicle.getMmtrMotorForceN() / 1000) + "kN"
			+ " 手柄诉求=" + Math.round(demandKn) + "kN"
			+ " 加速度=" + lastAccelerationText
			+ " 速度=" + Math.round(Math.abs(vehicle.getSpeed()) * 3600) + "km/h"
			+ " 车=" + vehicle.getId());
	}

	/** 电机那行的颜色：在牵引 = 牵引色，在电阻制动 = 制动色，都不出力 = 弱色（"没做功"本身是信息）。 */
	private static int motorColor(@Nullable VehicleExtension vehicle) {
		if (vehicle == null) {
			return LABEL_COLOR;
		}
		final double forceN = vehicle.getMmtrMotorForceN();
		return forceN > 500 ? TRACTION_COLOR : forceN < -500 ? BRAKE_COLOR : LABEL_COLOR;
	}

	@Nullable
	private static String holdReasonOf(@Nullable VehicleExtension vehicle) {
		return vehicle == null ? "" : vehicle.getMmtrHoldReasonFromSync();
	}

	/**
	 * 理由**变化**时给玩家一句动作栏提示。
	 *
	 * <p>为什么不能只靠右上角那行小字：司机盯着前方与信号，不会一直看角落；而"按了油门车不动"
	 * 恰恰是必须先知道原因的场景（notes/217）。只在变化时提示，理由持续存在期间不再刷屏。</p>
	 */
	/**
	 * **AWS 报警时说一句"该按哪个键"**。
	 *
	 * <p>引擎侧报警只有 2.5 s 的确认窗口，超时就是 SPAD 紧急制动（{@code Vehicle.tickMmtrAwsWarning}）。
	 * 而客户端此前**连确认键都没有**（notes/185 删旧驾驶键位时一起删了，acknowledge 恒为 false），
	 * 于是司机在游戏里既不知道为什么被刹停、也无从解除。现在 R 是响应键，
	 * 但"有键"不等于"人知道要按" —— 报警**出现的那一刻**必须说出来（与"车被扣住"同一个规矩）。</p>
	 */
	private static void notifyAwsWarning(@Nullable VehicleExtension vehicle) {
		final boolean warning = vehicle != null && vehicle.isMmtrAwsWarningPendingFromSync();
		if (warning == lastAwsWarning) {
			return;
		}
		lastAwsWarning = warning;
		if (!warning || !MmtrDriverSeat.isAtControls()) {
			return;
		}
		announce("AWS 报警：按响应键 R 确认（约 2.5 秒内，否则紧急制动）");
	}

	private static boolean lastAwsWarning;

	private static void notifyHoldReason(@Nullable VehicleExtension vehicle) {
		final String reason = holdReasonOf(vehicle);
		if (reason.equals(lastHoldReason)) {
			return;
		}
		lastHoldReason = reason;
		if (reason.isEmpty() || !MmtrDriverSeat.isAtControls()) {
			return;
		}
		announce("车被扣住：" + reason);
	}

	/**
	 * **任务提示的播报与提示音已搬到左上角的任务卡**（{@link MmtrTaskHud}）。
	 *
	 * <p>搬走而不是复制一份，是刻意的：那两件事（"下一步做什么"、"现在该做什么"）本来就在播同一份
	 * 引擎文案，两处各写一个"上次播了什么"的闩就一定会分叉 —— 到时候同一句话在一处被吞、在另一处
	 * 被播，或者两个地方各播一遍。所以任务信息现在只有一个出口，本类只剩"车"的那几句
	 * （车被扣住、AWS 报警）。</p>
	 */
	private static void announce(String text) {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player != null) {
			player.sendMessage(new Text(TextHelper.literal(text).data), true);
		}
	}
}

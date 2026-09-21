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
 * <p>用户口径（2026-09-19）：「屏幕右上角 HUD 上简略显示操作信息」。所以这里只有五行两列
 * （速度 / 油门 / 制动 / 定速 / 换向），没有面板、没有量程刻度、没有第二个表——那套（BR101 的
 * 仪表画面）走资源包的 {@code mmtr_hud_*} 面板，是另一条路（重建步 B6）。</p>
 *
 * <h2>什么时候显示</h2>
 *
 * <p>只有**坐在司机位上**时画（{@link MmtrDriverSeat#isAtControls()}，与"能不能操作手柄"同一条判据）：
 * 乘客不该看到司机的手柄读数，而站在站台上的人更不该看到。判据同源也意味着"能看到这个 HUD"
 * 与"按了手柄有反应"永远一致 —— 不会出现"HUD 在、按键没反应"这种最费解的组合。</p>
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
	/** 作业/任务那两行：司机最先要看的东西，所以给它一个与手柄读数不同的颜色。 */
	private static final int JOB_COLOR = 0xFF9FE08F;
	private static final int TASK_COLOR = 0xFFF2F4F6;
	/** 子任务清单那一行（到站停稳 / 开门 / 停够 / 关门）：正在做的那一条要跳出来。 */
	private static final int SUB_TASK_COLOR = 0xFFCFE8FF;
	private static final int SUB_TASK_HINT_COLOR = 0xFFFFD98A;
	/** 任务说明在面板里最多留这么多个字（作业单的 note 可以很长，整句塞进右上角会撑满屏幕）。 */
	private static final int TASK_NOTE_MAX_CHARS = 22;
	/** 子任务清单整行最多这么多个字（四条：✔✔到站停稳 ✔✔开门 …）。 */
	private static final int SUB_TASK_MAX_CHARS = 46;

	/** 上一拍显示过的"为什么不动"，用来只在**变化**时给玩家一句提示（动作栏），而不是每帧刷屏。 */
	private static String lastHoldReason = "";
	/** 上一拍显示过的任务提示，同样只在变化时播报一次（"下一步该做什么"要说出来）。 */
	private static String lastTaskNote = "";
	/** 上一拍显示过的**子任务提示**（"请按开门键" / "停站还差 12s"）：同样只在变化时播报。 */
	private static String lastSubTaskHint = "";

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
		notifyTaskNote(ridingVehicle);
		notifySubTaskHint(ridingVehicle);
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
		 * 任务提示摆在最上面：司机先要知道"这一步是什么、谁在执行"，其余是操作读数。
		 * 全部由引擎算好发过来（作业号 / 人话说明 / 第几步 / 执行者），客户端一个字段都不反查。
		 */
		rows.addAll(taskRows(vehicle));
		if (!holdReason.isEmpty()) {
			// 被信号/进路/任务按住时，把理由顶到操作读数之前 —— 这正是"手柄有反应但车不动"的答案。
			rows.add(new Row("状态", holdReason, HOLD_COLOR));
		}
		rows.add(new Row("速度", speedKmh + " km/h", VALUE_COLOR));
		rows.add(new Row("油门", MmtrDriveInput.driveHandleText(), driveHandle > 0 ? TRACTION_COLOR : driveHandle < 0 ? BRAKE_COLOR : LABEL_COLOR));
		rows.add(new Row("制动", MmtrDriveInput.brakeText(), MmtrDriveInput.isEmergencyBrake() ? EMERGENCY_COLOR : MmtrDriveInput.getBrakePosition() > 0 ? BRAKE_COLOR : LABEL_COLOR));
		rows.add(new Row("定速", MmtrDriveInput.cruiseText(), MmtrDriveInput.getCruiseKmh() > 0 ? CRUISE_COLOR : LABEL_COLOR));
		rows.add(new Row("换向", MmtrDriveInput.reverserText(), VALUE_COLOR));
		return rows.toArray(new Row[0]);
	}

	/** 作业/任务两行；没有任务时返回空表（乘客、调车自由开都不该看到"作业"这种字）。 */
	private static java.util.List<Row> taskRows(@Nullable VehicleExtension vehicle) {
		final java.util.List<Row> rows = new java.util.ArrayList<>();
		if (vehicle == null) {
			return rows;
		}
		final String jobId = vehicle.getMmtrJobIdFromSync();
		final int step = vehicle.getMmtrTaskStepFromSync();
		final int steps = vehicle.getMmtrTaskStepsFromSync();
		final String executor = vehicle.getMmtrMissionExecutorFromSync();
		if (!jobId.isEmpty() || step >= 0) {
			final String progress = step >= 0 && steps > 0 ? (step + 1) + "/" + steps : "?";
			rows.add(new Row("作业", (jobId.isEmpty() ? "" : jobId + " ") + progress, JOB_COLOR));
		}
		final String note = vehicle.getMmtrTaskNoteFromSync();
		if (!note.isEmpty()) {
			rows.add(new Row("任务", truncate(note, TASK_NOTE_MAX_CHARS), TASK_COLOR));
		}
		if (!executor.isEmpty()) {
			final boolean mine = "PLAYER".equals(executor);
			rows.add(new Row("执行", mine ? "司机" : "自动", mine ? TRACTION_COLOR : LABEL_COLOR));
		}
		/*
		 * **站台作业的子任务清单**（用户口径 2026-09-21）：到站停稳 → 开门 → 停够 → 关门。
		 *
		 * 两行：清单本身（✔✔=引擎判定完成且客户端已确认、✔?=引擎说完成了但我这边还没确认、
		 * ▶=正在做、·=还没轮到），以及"现在该做什么"的一句话提示。
		 * **一个字都不在这里拼**：清单与提示都是引擎按原话发过来的（notes/170 §8.4）。
		 */
		final java.util.List<org.mtr.mod.client.MmtrSubTaskView.Entry> subTasks = org.mtr.mod.client.MmtrSubTaskView.of(vehicle);
		if (!subTasks.isEmpty()) {
			rows.add(new Row("子任务", truncate(org.mtr.mod.client.MmtrSubTaskView.summary(subTasks), SUB_TASK_MAX_CHARS), SUB_TASK_COLOR));
			final String hint = vehicle.getMmtrSubTaskHintFromSync();
			if (!hint.isEmpty()) {
				rows.add(new Row("提示", truncate(hint, TASK_NOTE_MAX_CHARS), SUB_TASK_HINT_COLOR));
			}
		}
		return rows;
	}

	private static String truncate(String text, int maxChars) {
		return text.length() <= maxChars ? text : text.substring(0, maxChars - 1) + "…";
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
	 * **任务提示变化时说一句**（"下一步做什么"）。
	 *
	 * <p>用户口径（2026-09-21）："需要有任务提示传送到客户端"。右上角那两行是常驻读数，
	 * 而**换步**是必须被看见的事件 —— 司机在站台上等，门关了、下一步来了，若只更新角落里的小字，
	 * 人会一直等下去。所以换步时往动作栏播一次，与"车被扣住"同一个规矩：只在变化时播。</p>
	 */
	private static void notifyTaskNote(@Nullable VehicleExtension vehicle) {
		final String note = vehicle == null ? "" : vehicle.getMmtrTaskNoteFromSync();
		final int step = vehicle == null ? -1 : vehicle.getMmtrTaskStepFromSync();
		final int steps = vehicle == null ? 0 : vehicle.getMmtrTaskStepsFromSync();
		final String key = step + "/" + steps + " " + note;
		if (key.equals(lastTaskNote)) {
			return;
		}
		lastTaskNote = key;
		if (note.isEmpty() || !MmtrDriverSeat.isAtControls()) {
			return;
		}
		announce("任务 " + (steps > 0 && step >= 0 ? (step + 1) + "/" + steps + "：" : "") + note);
	}

	/**
	 * **子任务提示变化时说一句**（"现在该做什么"）。
	 *
	 * <p>与换步提示同一个理由、同一处实现：司机在站台上等的这几十秒里，他要做的是"按开门键"
	 * 或"按关门键"这种**具体动作** —— 只更新右上角那行小字等于没说。停留计时那句每秒都在变，
	 * 播报会被节流成"只在文字真的变了时说一次"，所以它不会刷屏（引擎那边的提示文案按秒粒度变化）。</p>
	 */
	private static void notifySubTaskHint(@Nullable VehicleExtension vehicle) {
		final String hint = vehicle == null ? "" : vehicle.getMmtrSubTaskHintFromSync();
		if (hint.equals(lastSubTaskHint)) {
			return;
		}
		lastSubTaskHint = hint;
		if (hint.isEmpty() || !MmtrDriverSeat.isAtControls()) {
			return;
		}
		announce("子任务：" + hint);
	}

	private static void announce(String text) {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player != null) {
			player.sendMessage(new Text(TextHelper.literal(text).data), true);
		}
	}
}

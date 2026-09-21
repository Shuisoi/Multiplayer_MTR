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
		if (holdReason.isEmpty()) {
			return new Row[]{
				new Row("速度", speedKmh + " km/h", VALUE_COLOR),
				new Row("油门", MmtrDriveInput.driveHandleText(), driveHandle > 0 ? TRACTION_COLOR : driveHandle < 0 ? BRAKE_COLOR : LABEL_COLOR),
				new Row("制动", MmtrDriveInput.brakeText(), MmtrDriveInput.isEmergencyBrake() ? EMERGENCY_COLOR : MmtrDriveInput.getBrakePosition() > 0 ? BRAKE_COLOR : LABEL_COLOR),
				new Row("定速", MmtrDriveInput.cruiseText(), MmtrDriveInput.getCruiseKmh() > 0 ? CRUISE_COLOR : LABEL_COLOR),
				new Row("换向", MmtrDriveInput.reverserText(), VALUE_COLOR)
			};
		}
		// 被信号/进路/任务按住时，把理由顶到最上面一行 —— 这正是"手柄有反应但车不动"的答案。
		return new Row[]{
			new Row("状态", holdReason, HOLD_COLOR),
			new Row("速度", speedKmh + " km/h", VALUE_COLOR),
			new Row("油门", MmtrDriveInput.driveHandleText(), driveHandle > 0 ? TRACTION_COLOR : driveHandle < 0 ? BRAKE_COLOR : LABEL_COLOR),
			new Row("制动", MmtrDriveInput.brakeText(), MmtrDriveInput.isEmergencyBrake() ? EMERGENCY_COLOR : MmtrDriveInput.getBrakePosition() > 0 ? BRAKE_COLOR : LABEL_COLOR),
			new Row("定速", MmtrDriveInput.cruiseText(), MmtrDriveInput.getCruiseKmh() > 0 ? CRUISE_COLOR : LABEL_COLOR),
			new Row("换向", MmtrDriveInput.reverserText(), VALUE_COLOR)
		};
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
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player != null) {
			player.sendMessage(new Text(TextHelper.literal("车被扣住：" + reason).data), true);
		}
	}
}

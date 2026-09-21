package org.mtr.mod.render;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.holder.Style;
import org.mtr.mapping.holder.Window;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.Init;
import org.mtr.mod.client.MmtrDriverSeat;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.VehicleExtension;

/**
 * 屏幕**右下角的速度读数**（notes/223–225）：背景楔形 + 大号三位数字 + 单位 km/h + 限速图标 + AWS 指示灯。
 *
 * <pre>
 *                                  ┌──────────┐
 *                       AWS (灯)   │ 限速 160 │      ← 图标行（右对齐，限速图标在 km/h 正上方）
 *                       ┌───┐      └──────────┘
 *                       087  km/h                    ← 数字（DIN、右斜体、逐位固定格宽）+ 单位
 *                       ╲＿＿＿＿＿＿＿＿＿＿╱          ← 背景楔形（直角在屏幕右下角）
 * </pre>
 *
 * <h2>为什么逐位排版，而不是整串画一次</h2>
 *
 * <p>用户口径：「三个数字**单独排版**，**位置中点固定**，防止字宽不同导致整体伸缩」。
 * 比例字体里 {@code 1} 比 {@code 8} 窄，整串画会让读数随数字内容左右抖动（速度从 199 掉到 100 时，
 * 整块往左缩一截）。做法：每位一个**固定格宽**（取 0–9 里最宽的那位量出来），各位在自己的格子里
 * **居中** ⇒ 格子中心与"这一位是谁"无关，整块宽度恒定、数字在原地变化。</p>
 *
 * <h2>图标是画出来的，不是贴图</h2>
 *
 * <p>只用矩形（MC 的 2D 原语）拼：圆 = 逐行的水平条，圆环 = 大红盘 + 小白盘，灯 = 圆 + 状态色。
 * 好处是随分辨率自适应、不引入新资源；代价是没有抗锯齿（用户要的"简易绘制"）。</p>
 */
public final class MmtrSpeedHud {

	private MmtrSpeedHud() {
	}

	/** 距屏幕右边与下边的留白（缩放前像素）。 */
	private static final int EDGE_PADDING = 8;
	/** 大号：整串按 4 倍放大（MC 字体行高 9 px ⇒ 约 36 px 高）。 */
	private static final float SCALE = 4.0F;
	/** 三位数。 */
	private static final int DIGITS = 3;
	/**
	 * 格与格之间的呼吸（缩放前像素）。用户 2026-09-21：「三个数字的间距缩小点」：2 → 1。
	 * 格宽本身取最宽的那位，所以实际观感间距就是这一个数；调到 0 时最宽的数字会彼此贴住（斜体还会右移）。
	 */
	private static final int DIGIT_GAP = 1;

	/** 单位 {@code km/h} 的字号倍率与它到数字块的间隙（缩放前像素）。 */
	private static final float UNIT_SCALE = 1.5F;
	private static final int UNIT_GAP = 4;

	/** 图标行的尺寸与间隙（屏幕像素）。 */
	private static final int ICON_ROW_GAP = 4;
	private static final int LIMIT_SIGN_RADIUS = 13;
	private static final int LIMIT_RING_WIDTH = 3;
	private static final int AWS_LAMP_RADIUS = 8;
	private static final float AWS_LABEL_SCALE = 0.9F;
	/** AWS 未确认告警时的闪烁周期（ms）：亮/灭各半。 */
	private static final long AWS_BLINK_PERIOD_MILLIS = 1000;

	private static final int DIGIT_COLOR = 0xFFFFFFFF;
	private static final int UNIT_COLOR = 0xFF9FB3C6;
	/** 限速牌：红圈 + 白面 + 黑字（与资源包 BR101 仪表盘里的限速控件同色）。 */
	private static final int LIMIT_RING_COLOR = 0xFFC8322B;
	private static final int LIMIT_FACE_COLOR = 0xFFF2F4F6;
	private static final int LIMIT_TEXT_COLOR = 0xFF101418;
	/** AWS 灯：灭 / 已确认 / 未确认告警。 */
	private static final int AWS_OFF_COLOR = 0xFF2A2F36;
	private static final int AWS_ACK_COLOR = 0xFF3FA34D;
	private static final int AWS_WARN_COLOR = 0xFFFFC107;
	private static final int AWS_LABEL_COLOR = 0xFF9FB3C6;

	/**
	 * 背景楔形：**直角顶点在屏幕右下角**，两条直角边沿屏幕底边与右边，
	 * 底边 = 屏宽的 {@value #WEDGE_BOTTOM_FRACTION_PERCENT}%，斜边与**底边**夹角 30°
	 * ⇒ 竖边高 = 底边 × tan30°。
	 */
	private static final double WEDGE_BOTTOM_FRACTION = 0.20;
	private static final int WEDGE_BOTTOM_FRACTION_PERCENT = 20;
	private static final double WEDGE_ANGLE_DEGREES = 30.0;
	private static final int WEDGE_COLOR = 0xA8101418;

	/**
	 * 固定格宽（缩放前像素）—— 取 0–9 里**最宽**的那一位，加上呼吸。
	 * 缓存：字体与样式不变时这是常量；资源重载（换字体）后由 {@link #reset()} 作废。
	 */
	private static int cachedCellWidth = -1;
	/** 单位文本 {@code km/h} 的宽度（缩放前像素），同样缓存。 */
	private static int cachedUnitWidth = -1;

	public static void render(GraphicsHolder graphicsHolder) {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		if (minecraftClient.getCurrentScreenMapped() != null) {
			return;
		}
		// 只在**坐在司机位上**时画：判据与"能不能操作手柄"同源（MmtrDriverSeat），
		// 于是不会出现"有读数却按不动"这种最费解的组合。
		if (!MmtrDriverSeat.isAtControls()) {
			return;
		}
		final VehicleExtension vehicle = MmtrDriverSeat.ridingVehicle();
		if (vehicle == null) {
			return;
		}

		final Window window = minecraftClient.getWindow();
		final int screenWidth = window.getScaledWidth();
		final int screenHeight = window.getScaledHeight();
		final GuiDrawing guiDrawing = new GuiDrawing(graphicsHolder);

		renderWedge(guiDrawing, screenWidth, screenHeight);

		// ---- 数字 + 单位（右对齐） ------------------------------------------------------------------
		final int speedKmh = Math.min(999, Math.max(0, (int) Math.round(Math.abs(vehicle.getSpeed()) * 3600)));
		final String text = String.format("%0" + DIGITS + "d", speedKmh);
		final int cellWidth = cellWidth();
		final int unitWidth = unitWidth();
		final int digitsLeft = digitsLeft(screenWidth, cellWidth, SCALE, EDGE_PADDING, Math.round(unitWidth * UNIT_SCALE) + UNIT_GAP);
		final int digitsTop = screenHeight - EDGE_PADDING - Math.round(IGui.TEXT_HEIGHT * SCALE);

		graphicsHolder.push();
		graphicsHolder.translate(digitsLeft, digitsTop, 0);
		graphicsHolder.scale(SCALE, SCALE, 1);
		// 逐位：每位在自己的格子里居中。★ 格子中心 = index*cellWidth + cellWidth/2，与这一位是谁无关。
		for (int i = 0; i < DIGITS; i++) {
			final MutableText digit = styledDigit(text.charAt(i), false);
			graphicsHolder.drawText(digit, digitOffset(i, GraphicsHolder.getTextWidth(digit), cellWidth), 0, DIGIT_COLOR, true, GraphicsHolder.getDefaultLight());
		}
		graphicsHolder.pop();

		// 单位：与数字**底部对齐**，所以按自己的缩放单独摆一次（不是简单地把 y 相加）
		final MutableText unit = styledUnit();
		final int unitHeight = Math.round(IGui.TEXT_HEIGHT * UNIT_SCALE);
		graphicsHolder.push();
		graphicsHolder.translate(screenWidth - EDGE_PADDING - Math.round(GraphicsHolder.getTextWidth(unit) * UNIT_SCALE), digitsTop + Math.round(IGui.TEXT_HEIGHT * SCALE) - unitHeight, 0);
		graphicsHolder.scale(UNIT_SCALE, UNIT_SCALE, 1);
		graphicsHolder.drawText(unit, 0, 0, UNIT_COLOR, true, GraphicsHolder.getDefaultLight());
		graphicsHolder.pop();

		// ---- 图标行：限速牌在 km/h 正上方，AWS 灯在它左边；整行右对齐 --------------------------------
		renderIcons(graphicsHolder, guiDrawing, vehicle, screenWidth, digitsTop);
	}

	/** 背景楔形：逐列 1 px 竖条，列高线性涨到竖边高（30° 是缓坡，逐列不会有阶梯洞）。 */
	private static void renderWedge(GuiDrawing guiDrawing, int screenWidth, int screenHeight) {
		final int leg = wedgeBottomLeg(screenWidth);
		guiDrawing.beginDrawingRectangle();
		for (int column = 1; column <= leg; column++) {
			final int columnHeight = wedgeColumnHeight(screenWidth, column);
			final int x = screenWidth - leg + column - 1;
			guiDrawing.drawRectangle(x, screenHeight - columnHeight, x + 1, screenHeight, WEDGE_COLOR);
		}
		guiDrawing.finishDrawingRectangle();
	}

	/** 限速牌（红圈白面黑字）+ AWS 指示灯（含标签），整行贴着右边、压在数字块上方。 */
	private static void renderIcons(GraphicsHolder graphicsHolder, GuiDrawing guiDrawing, VehicleExtension vehicle, int screenWidth, int digitsTop) {
		final int rowBottom = digitsTop - ICON_ROW_GAP;
		final int limitKmh = (int) Math.min(999, Math.max(0, vehicle.getMmtrSpeedLimitKmhFromSync()));

		// 限速牌：右边缘与数字块/单位对齐（= 屏幕右边留白）。限速为 0 时不画 —— 没有监督就没有牌子。
		if (limitKmh > 0) {
			final int centreX = screenWidth - EDGE_PADDING - LIMIT_SIGN_RADIUS;
			final int centreY = rowBottom - LIMIT_SIGN_RADIUS;
			fillDisc(guiDrawing, centreX, centreY, LIMIT_SIGN_RADIUS, LIMIT_RING_COLOR);
			fillDisc(guiDrawing, centreX, centreY, LIMIT_SIGN_RADIUS - LIMIT_RING_WIDTH, LIMIT_FACE_COLOR);
			final MutableText limitText = styledLimit(String.valueOf(limitKmh));
			graphicsHolder.push();
			graphicsHolder.translate(centreX - GraphicsHolder.getTextWidth(limitText) / 2F, centreY - IGui.TEXT_HEIGHT / 2F, 0);
			graphicsHolder.drawText(limitText, 0, 0, LIMIT_TEXT_COLOR, false, GraphicsHolder.getDefaultLight());
			graphicsHolder.pop();

			// AWS：灯在限速牌左边，标签在灯左边
			final boolean warning = vehicle.isMmtrAwsWarningPendingFromSync();
			final boolean acknowledged = vehicle.isMmtrAwsWarningAcknowledgedFromSync();
			final int lampColour = warning ? awsWarningColourNow() : acknowledged ? AWS_ACK_COLOR : AWS_OFF_COLOR;
			final int lampCentreX = centreX - LIMIT_SIGN_RADIUS - ICON_ROW_GAP - AWS_LAMP_RADIUS;
			final int lampCentreY = centreY;
			fillDisc(guiDrawing, lampCentreX, lampCentreY, AWS_LAMP_RADIUS, 0xFF101418);
			fillDisc(guiDrawing, lampCentreX, lampCentreY, AWS_LAMP_RADIUS - 2, lampColour);

			final MutableText label = styledAwsLabel();
			graphicsHolder.push();
			graphicsHolder.translate(lampCentreX - AWS_LAMP_RADIUS - ICON_ROW_GAP - Math.round(GraphicsHolder.getTextWidth(label) * AWS_LABEL_SCALE), lampCentreY - Math.round(IGui.TEXT_HEIGHT * AWS_LABEL_SCALE) / 2F, 0);
			graphicsHolder.scale(AWS_LABEL_SCALE, AWS_LABEL_SCALE, 1);
			graphicsHolder.drawText(label, 0, 0, warning ? AWS_WARN_COLOR : AWS_LABEL_COLOR, true, GraphicsHolder.getDefaultLight());
			graphicsHolder.pop();
		}
	}

	/** 未确认的 AWS 告警闪烁（亮/灭各半）—— 告警不该是静止的一盏灯。 */
	static int awsWarningColourNow() {
		return System.currentTimeMillis() / (AWS_BLINK_PERIOD_MILLIS / 2) % 2 == 0 ? AWS_WARN_COLOR : AWS_OFF_COLOR;
	}

	/** 实心圆：逐行的水平条。{@code dy} 是相对圆心的行偏移（可为负）。 */
	private static void fillDisc(GuiDrawing guiDrawing, int centreX, int centreY, int radius, int colour) {
		guiDrawing.beginDrawingRectangle();
		for (int dy = -radius; dy <= radius; dy++) {
			final int halfWidth = circleHalfWidth(radius, dy);
			guiDrawing.drawRectangle(centreX - halfWidth, centreY + dy, centreX + halfWidth, centreY + dy + 1, colour);
		}
		guiDrawing.finishDrawingRectangle();
	}

	/**
	 * 半径 {@code radius} 的圆在行偏移 {@code dy} 处的半宽（像素）：{@code round(sqrt(r² - dy²))}。
	 * 纯函数，供单测钉住"圆的形状"（中心最宽、边缘为 0、关于中心对称）。
	 */
	static int circleHalfWidth(int radius, int dy) {
		final double inside = (double) radius * radius - (double) dy * dy;
		return inside <= 0 ? 0 : (int) Math.round(Math.sqrt(inside));
	}

	/**
	 * 数字块的左边缘（缩放前像素）：**右对齐**——数字右边缘 = {@code 屏宽 - 留白 - 尾部宽度}，
	 * 尾部宽度 = 单位 {@code km/h} 占的地方（缩放后的像素）。
	 */
	static int digitsLeft(int scaledWindowWidth, int cellWidth, float scale, int edgePadding, int trailingWidth) {
		return scaledWindowWidth - edgePadding - trailingWidth - Math.round(DIGITS * cellWidth * scale);
	}

	/**
	 * 第 {@code index} 位在它自己格子里的绘制 x（缩放前像素，相对整块左上角）。
	 *
	 * <p>单独抽出来是为了能被单测钉住：**格子中心与字宽无关**（整数除法带来 1 px 取整误差，
	 * 判据写成"中心偏差 ≤ 1 px"）。</p>
	 */
	static int digitOffset(int index, int digitWidth, int cellWidth) {
		return index * cellWidth + (cellWidth - digitWidth) / 2;
	}

	/** 楔形的底边长（沿屏幕底边，像素）：屏宽的 {@value #WEDGE_BOTTOM_FRACTION_PERCENT}%。 */
	static int wedgeBottomLeg(int scaledWindowWidth) {
		return (int) Math.round(scaledWindowWidth * WEDGE_BOTTOM_FRACTION);
	}

	/** 楔形的竖边高（沿屏幕右边，像素）= 底边 × tan(30°) —— 由"斜边与底边成 30°"推出。 */
	static int wedgeHeight(int scaledWindowWidth) {
		return (int) Math.round(wedgeBottomLeg(scaledWindowWidth) * Math.tan(Math.toRadians(WEDGE_ANGLE_DEGREES)));
	}

	/** 自楔形左下顶点数起第 {@code column}（1 起）列的竖条高度；越界被钳住。 */
	static int wedgeColumnHeight(int scaledWindowWidth, int column) {
		final int leg = wedgeBottomLeg(scaledWindowWidth);
		if (leg <= 0 || column <= 0) {
			return 0;
		}
		return (int) Math.round(wedgeHeight(scaledWindowWidth) * (double) Math.min(column, leg) / leg);
	}

	/** 固定格宽：0–9 里最宽的那位 + 呼吸。 */
	private static int cellWidth() {
		if (cachedCellWidth > 0) {
			return cachedCellWidth;
		}
		int widest = 0;
		for (char digit = '0'; digit <= '9'; digit++) {
			widest = Math.max(widest, GraphicsHolder.getTextWidth(styledDigit(digit, false)));
		}
		cachedCellWidth = widest + DIGIT_GAP;
		return cachedCellWidth;
	}

	private static int unitWidth() {
		if (cachedUnitWidth < 0) {
			cachedUnitWidth = GraphicsHolder.getTextWidth(styledUnit());
		}
		return cachedUnitWidth;
	}

	/** 资源重载（换字体）后作废量出来的宽度缓存 —— 与 {@code MmtrPanelFont.reset()} 同一套路。 */
	public static void reset() {
		cachedCellWidth = -1;
		cachedUnitWidth = -1;
	}

	private static final Identifier UI_FONT = new Identifier(Init.MOD_ID, "ui");

	/** 数字：屏幕 UI 字体（DIN）+ 右斜体。 */
	private static MutableText styledDigit(char digit, boolean bold) {
		final Style style = Style.getEmptyMapped().withFont(UI_FONT).withItalic(Boolean.TRUE);
		return TextHelper.setStyle(TextHelper.literal(String.valueOf(digit)), bold ? style.withBold(Boolean.TRUE) : style);
	}

	/** 单位 {@code km/h}：同一套字体，不斜体（单位不该跟着读数一起歪）。 */
	private static MutableText styledUnit() {
		return TextHelper.setStyle(TextHelper.literal("km/h"), Style.getEmptyMapped().withFont(UI_FONT));
	}

	/** 限速牌里的数字：不斜体、黑色。 */
	private static MutableText styledLimit(String value) {
		return TextHelper.setStyle(TextHelper.literal(value), Style.getEmptyMapped().withFont(UI_FONT));
	}

	/** AWS 标签。 */
	private static MutableText styledAwsLabel() {
		return TextHelper.setStyle(TextHelper.literal("AWS"), Style.getEmptyMapped().withFont(UI_FONT));
	}
}

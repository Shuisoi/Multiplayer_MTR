package org.mtr.mod.render;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Window;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.mtr.mod.Init;
import org.mtr.mod.client.MmtrDriverSeat;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.panel.MmtrPanelCanvas;
import org.mtr.mod.render.panel.MmtrPanelTexture;

import java.util.function.DoubleUnaryOperator;

/**
 * 屏幕**右下角的速度读数**：背景楔形 + 大号三位数字 + 单位 km/h + 限速牌 + AWS 指示灯。
 *
 * <pre>
 *                          ┌──────────┐
 *               AWS (灯)   │ 限速 160 │
 *               ┌───┐      └──────────┘
 *               087  km/h
 *               ╲＿＿＿＿＿＿＿＿＿＿╱        ← 直角在屏幕右下角（底边 20% 屏宽、与底边 30°）
 * </pre>
 *
 * <h2>为什么整幅烘成一张贴图（notes/227）</h2>
 *
 * <p>第一版是拿 MC 的矩形原语一帧帧拼的（圆 = 逐行条、楔形 = 逐列条），**没有抗锯齿**；
 * 数字更是把 MC 字体图集的字形放大 4 倍，糊。用户一句"绘图也太粗糙了吧"就是冲这个来的。</p>
 *
 * <p>现在与仪表面板走**同一条链**：{@link MmtrPanelCanvas} 用 Java2D 把整幅画出来（抗锯齿、任意曲线、
 * 真斜体、任意字号下都锐），上传成一张贴图，屏幕上只画一个 quad。代价是每次内容变化要重绘一次
 * —— 所以按"签名"缓存（数字/限速/AWS 状态/尺寸任一变化才重画），与 {@code MmtrCabDashboard} 同一套路。</p>
 *
 * <h2>逐位固定格宽</h2>
 *
 * <p>三位数字**单独排版**：格宽取 0–9 里最宽的一位（Java2D 量），每位在自己的格子里居中，
 * 斜体也绕各自墨迹中心错切 ⇒ "位置中点固定，不因字宽伸缩"。</p>
 */
public final class MmtrSpeedHud {

	private MmtrSpeedHud() {
	}

	// 版面上的数字**故意留包内可见**（不是 private）：JUnit 与离线探针都要用真实字体量出"到底放不放得下"，
	// 而不是各自抄一份常量（抄一份就会在改版式时悄悄失配）。
	/** 画布尺寸 = 屏幕上的绘制尺寸（GUI 单位）。右上角是画布原点，所以直角顶点落在画布右下角。 */
	static final int HUD_WIDTH = 208;
	static final int HUD_HEIGHT = 104;
	/** 距屏幕右边与下边的留白。 */
	static final int EDGE_PADDING = 8;
	/** 读数底板：内容包围盒之外再放这么多（画布单位）。 */
	static final double PLATE_PADDING = 6;

	/** 数字墨迹高（GUI 单位）—— 大号读数。 */
	static final double DIGIT_INK_HEIGHT = 36;
	/** 格与格之间的呼吸。 */
	static final double DIGIT_GAP = 1;
	/** 数字的右斜体错切（≈12°，正数向右倾）；可调。 */
	static final double DIGIT_SHEAR = 0.22;

	static final double UNIT_INK_HEIGHT = 13;
	static final double UNIT_GAP = 4;

	static final double ICON_GAP = 4;
	static final double LIMIT_SIGN_RADIUS = 13;
	static final double LIMIT_RING_WIDTH = 3;
	/** 限速数字在白面里占的最大宽度比例（留一点边）。 */
	static final double LIMIT_BOX_FILL = 0.92;
	/** 限速数字的**上限**墨迹高；宽度不够时按比例降。 */
	static final double LIMIT_TEXT_MAX_INK_HEIGHT = 14;
	/** 字号再小也不低于这个墨迹高（否则不如不画）。 */
	static final double MIN_TEXT_INK_HEIGHT = 6;
	/** 反推字号最多迭代几轮（宽度对字号只是近似线性，见 {@link #fittedInkHeight}）。 */
	static final int MAX_FIT_ATTEMPTS = 8;
	/** 每轮至少得缩这么多才算"还在收敛"，否则停手。 */
	static final double MIN_FIT_STEP = 0.1;
	static final double AWS_LAMP_RADIUS = 8;
	static final double AWS_LABEL_INK_HEIGHT = 9;

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
	/** 未确认告警的闪烁周期（ms）：亮/灭各半。 */
	private static final long AWS_BLINK_PERIOD_MILLIS = 1000;

	/**
	 * 背景楔形：**直角顶点在屏幕右下角**，两条直角边沿屏幕底边与右边，
	 * 底边 = 屏宽的 {@value #WEDGE_BOTTOM_FRACTION_PERCENT}%，斜边与**底边**夹角 30°
	 * ⇒ 竖边高 = 底边 × tan30°。
	 */
	private static final double WEDGE_BOTTOM_FRACTION = 0.20;
	private static final int WEDGE_BOTTOM_FRACTION_PERCENT = 20;
	private static final double WEDGE_ANGLE_DEGREES = 30.0;
	private static final int WEDGE_COLOR = 0xB8101418;

	/** AWS 三种状态，参与签名（哪一盏灯亮着要能触发重绘）。 */
	enum AwsState { OFF, ACKNOWLEDGED, WARNING }

	public static void render(GraphicsHolder graphicsHolder) {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		if (minecraftClient.getCurrentScreenMapped() != null) {
			return;
		}
		// 只在**坐在司机位上**时画：判据与"能不能操作手柄"同源（MmtrDriverSeat）。
		if (!MmtrDriverSeat.isAtControls()) {
			return;
		}
		final VehicleExtension vehicle = MmtrDriverSeat.ridingVehicle();
		if (vehicle == null) {
			return;
		}
		final Window window = minecraftClient.getWindow();
		final int speedKmh = Math.min(999, Math.max(0, (int) Math.round(Math.abs(vehicle.getSpeed()) * 3600)));
		final int limitKmh = (int) Math.min(999, Math.max(0, vehicle.getMmtrSpeedLimitKmhFromSync()));
		final AwsState awsState = vehicle.isMmtrAwsWarningPendingFromSync() ? AwsState.WARNING
			: vehicle.isMmtrAwsWarningAcknowledgedFromSync() ? AwsState.ACKNOWLEDGED : AwsState.OFF;

		// 按签名缓存：只有内容/尺寸变了才重绘（含 AWS 闪烁相位 —— 闪的是灯，不是整幅）
		final String signature = speedKmh + "|" + limitKmh + "|" + awsState + "|"
			+ (awsState == AwsState.WARNING ? awsWarningColourNow() : 0) + "|" + HUD_WIDTH + "x" + HUD_HEIGHT + "|" + deviceScale(window);
		final MmtrPanelTexture texture = MmtrPanelTexture.get("speed_hud");
		if (texture.needsRedraw(signature)) {
			final int deviceScale = deviceScale(window);
			final MmtrPanelCanvas canvas = MmtrPanelCanvas.createExactPixels(HUD_WIDTH, HUD_HEIGHT, HUD_WIDTH * deviceScale, HUD_HEIGHT * deviceScale);
			paint(canvas, speedKmh, limitKmh, awsState, window.getScaledWidth(), awsWarningColourNow());
			texture.redraw(canvas, signature);
		}

		final GuiDrawing guiDrawing = new GuiDrawing(graphicsHolder);
		guiDrawing.beginDrawingTexture(texture.identifier());
		final double[] quad = screenQuad(window.getScaledWidth(), window.getScaledHeight());
		guiDrawing.drawTexture(quad[0], quad[1], quad[2], quad[3], 0, 0, 1, 1);
		guiDrawing.finishDrawingTexture();
	}

	/**
	 * HUD 在屏幕上的绘制矩形 {@code {x1, y1, x2, y2}}（GUI 单位，y 自屏幕顶往下），右下角顶住屏幕角。
	 *
	 * <p><b>★ 为什么单独抽出来</b>：{@code GuiDrawing.drawTexture} 的 8 参数重载收的是
	 * **两个角**（x1,y1,x2,y2），不是 {@code (x,y,w,h)} —— 参数全是 {@code double}，写错了照样编译。
	 * 栅格化那一版第一次真的去贴图，我就把宽高填进了后两个角的槽位，于是四边形从右下角一路拉到
	 * (208,104)，整块被斜着摊到屏幕中上部（用户报的"UI 不在右下角了"）。这条几何现在由
	 * {@code MmtrSpeedHudTests} 钉住：尺寸 = 画布尺寸、右/下边缘齐屏、且 x1&lt;x2、y1&lt;y2。</p>
	 */
	static double[] screenQuad(int scaledWindowWidth, int scaledWindowHeight) {
		return new double[]{scaledWindowWidth - HUD_WIDTH, scaledWindowHeight - HUD_HEIGHT, scaledWindowWidth, scaledWindowHeight};
	}

	/**
	 * 把整幅画到画布上（**不碰任何 MC 类型**，所以离线探针也能调用它出图，见 notes/227）。
	 *
	 * @param screenWidth     屏幕宽度（GUI 单位）—— 楔形底边按它的 20% 算，所以画布左侧可能被裁掉一部分
	 * @param awsWarningColour 未确认告警时灯的实际颜色（由 {@link #awsWarningColourNow()} 给出闪烁相位）
	 */
	static void paint(MmtrPanelCanvas canvas, int speedKmh, int limitKmh, AwsState awsState, int screenWidth, int awsWarningColour) {
		// ---- 先把版面量出来：底板要罩住**全部**内容，所以背景必须最后才有得画 ------------------------
		final String digits = String.format("%03d", speedKmh);
		final double cellWidth = digitCellWidth(canvas);
		final double digitsRight = digitsRight(canvas);
		final double digitsLeft = digitCentreX(0, cellWidth, digitsRight) - cellWidth / 2;
		final double rowBottom = EDGE_PADDING + DIGIT_INK_HEIGHT + ICON_GAP;
		final double signCentreX = HUD_WIDTH - EDGE_PADDING - LIMIT_SIGN_RADIUS;
		final double lampCentreX = signCentreX - LIMIT_SIGN_RADIUS - ICON_GAP - AWS_LAMP_RADIUS;
		final double iconRowLeft = limitKmh > 0
			? lampCentreX - AWS_LAMP_RADIUS - ICON_GAP - canvas.textWidth("AWS", AWS_LABEL_INK_HEIGHT)
			: digitsLeft;
		final double contentTop = limitKmh > 0 ? rowBottom + 2 * LIMIT_SIGN_RADIUS : EDGE_PADDING + DIGIT_INK_HEIGHT;
		// 读数底板：内容包围盒 + 留白，右/下都顶到画布边（画布右下角就是屏幕右下角）
		final double plateLeft = Math.max(0, Math.min(digitsLeft, iconRowLeft) - PLATE_PADDING);
		final double plateTop = contentTop + PLATE_PADDING;

		// ---- 背景 = 楔形 ∪ 底板，**一次填充** ------------------------------------------------------
		// ★ 为什么不是"先画楔形、再画底板"：两个形状都是半透明的，分两次画会在重叠处叠深、
		//   沿斜边露出一道可见的接缝。所以这里把并集算成一个多边形，一次画完。
		// ★ 为什么需要底板：楔形是 30° 的浅斜边，而读数是 208×104 的横长条 —— 算术上就没有哪个
		//   30° 楔形能装下它。实测 854 宽时速度的 "0" 上半截正好落在斜边外，白字压白底被"切"了一道。
		//   底板不是装饰，是**让读数一定有暗底**。
		final double[][] background = backgroundOutline(screenWidth, plateLeft, plateTop);
		canvas.fillPolygon(background[0], background[1], WEDGE_COLOR);

		// ---- 数字 + 单位（右对齐） ----------------------------------------------------------------
		for (int i = 0; i < 3; i++) {
			// ★ 每位在自己的格子里居中；斜体绕各自墨迹中心错切 ⇒ 中点固定、不因字宽伸缩
			canvas.textItalic(String.valueOf(digits.charAt(i)), digitCentreX(i, cellWidth, digitsRight), EDGE_PADDING, DIGIT_INK_HEIGHT, DIGIT_SHEAR, DIGIT_COLOR,
				IGui.HorizontalAlignment.CENTER, IGui.VerticalAlignment.BOTTOM);
		}
		canvas.text("km/h", HUD_WIDTH - EDGE_PADDING, EDGE_PADDING, UNIT_INK_HEIGHT, UNIT_COLOR,
			IGui.HorizontalAlignment.RIGHT, IGui.VerticalAlignment.BOTTOM);

		// ---- 图标行：限速牌在 km/h 正上方，AWS 灯在它左边；整行右对齐 --------------------------------
		if (limitKmh > 0) {
			final double signCentreY = rowBottom + LIMIT_SIGN_RADIUS;
			canvas.circle(signCentreX, signCentreY, LIMIT_SIGN_RADIUS, LIMIT_RING_COLOR);
			canvas.circle(signCentreX, signCentreY, LIMIT_SIGN_RADIUS - LIMIT_RING_WIDTH, LIMIT_FACE_COLOR);
			final String limitText = String.valueOf(limitKmh);
			// ★ 按**可用宽度**反推字号，而不是给一个固定墨迹高：三位数的 "160" 会撑出白面
			//   （实测离线出图时 "1" 直接压在红圈上）。
			canvas.text(limitText, signCentreX, signCentreY, limitInkHeight(canvas, limitKmh), LIMIT_TEXT_COLOR,
				IGui.HorizontalAlignment.CENTER, IGui.VerticalAlignment.CENTER);

			final int lampColour = switch (awsState) {
				case WARNING -> awsWarningColour;
				case ACKNOWLEDGED -> AWS_ACK_COLOR;
				case OFF -> AWS_OFF_COLOR;
			};
			canvas.circle(lampCentreX, signCentreY, AWS_LAMP_RADIUS, 0xFF101418);
			canvas.circle(lampCentreX, signCentreY, AWS_LAMP_RADIUS - 2, lampColour);
			canvas.text("AWS", lampCentreX - AWS_LAMP_RADIUS - ICON_GAP, signCentreY, AWS_LABEL_INK_HEIGHT,
				awsState == AwsState.WARNING ? AWS_WARN_COLOR : AWS_LABEL_COLOR,
				IGui.HorizontalAlignment.RIGHT, IGui.VerticalAlignment.CENTER);
		}
	}

	/** 未确认的 AWS 告警闪烁（亮/灭各半）—— 告警不该是静止的一盏灯。 */
	static int awsWarningColourNow() {
		return System.currentTimeMillis() / (AWS_BLINK_PERIOD_MILLIS / 2) % 2 == 0 ? AWS_WARN_COLOR : AWS_OFF_COLOR;
	}

	/** 栅格化倍率：按窗口缩放率取整（≥1）—— 这样贴图分辨率与设备像素 1:1，锐且没有缩放滤波问题。 */
	private static int deviceScale(Window window) {
		return Math.max(1, (int) Math.round(window.getScaleFactor()));
	}

	/** 楔形的底边长（沿屏幕底边，GUI 单位）：屏宽的 {@value #WEDGE_BOTTOM_FRACTION_PERCENT}%。 */
	static double wedgeBottomLeg(int scaledWindowWidth) {
		return scaledWindowWidth * WEDGE_BOTTOM_FRACTION;
	}

	/** 楔形的竖边高（沿屏幕右边，GUI 单位）= 底边 × tan(30°)。 */
	static double wedgeHeight(int scaledWindowWidth) {
		return wedgeBottomLeg(scaledWindowWidth) * Math.tan(Math.toRadians(WEDGE_ANGLE_DEGREES));
	}

	/**
	 * 背景轮廓 = **楔形 ∪ 读数底板**（两者都右/下顶到画布边，即屏幕右下角）。返回 {@code {xs, ys}}。
	 *
	 * <p>四个分支就是两个形状互相包含关系的全部情况（{@code plateLeft} = 底板左边缘，{@code plateTop} = 底板顶边，
	 * {@code x0} = 楔形左顶点，{@code W} = 楔形竖边高）：</p>
	 * <ol>
	 *   <li>底板盖住楔形的水平范围（{@code plateLeft ≤ x0}）：楔形要么整块在底板里（画矩形），
	 *       要么在右上角冒出来（底板 + 斜边那个角）。</li>
	 *   <li>底板左边缘落在楔形里（{@code plateLeft > x0}）：若底板整块在楔形里，背景就是纯楔形；
	 *       否则底板左侧还留着一截楔形的尖（多一个顶点）。</li>
	 * </ol>
	 *
	 * <p>画布坐标 y 从**下边**起算（{@link MmtrPanelCanvas#fillPolygon} 与 {@code text} 同一约定），
	 * 所以这里是"屏幕右下角"。{@code fillPolygon} 实际走 {@code Path2D}，非凹多边形也能正确填充。</p>
	 */
	static double[][] backgroundOutline(int scaledWindowWidth, double plateLeft, double plateTop) {
		final double leg = wedgeBottomLeg(scaledWindowWidth);
		final double wedgeTop = wedgeHeight(scaledWindowWidth);
		final double x0 = HUD_WIDTH - leg;
		final double tan = Math.tan(Math.toRadians(WEDGE_ANGLE_DEGREES));

		if (plateLeft <= x0) {
			if (wedgeTop <= plateTop) {
				// 楔形整块落在底板里 ⇒ 背景就是一块矩形
				return new double[][]{{plateLeft, HUD_WIDTH, HUD_WIDTH, plateLeft}, {0, 0, plateTop, plateTop}};
			}
			final double xCross = x0 + plateTop / tan; // 斜边穿过底板顶边处
			return new double[][]{
				{plateLeft, HUD_WIDTH, HUD_WIDTH, xCross, plateLeft},
				{0, 0, wedgeTop, plateTop, plateTop}};
		}

		final double yAtPlateLeft = (plateLeft - x0) * tan; // 楔形在底板左边缘处的高度
		if (yAtPlateLeft >= plateTop) {
			// 底板整块落在楔形里 ⇒ 背景就是纯楔形（用户口径的形状，没被底板改形）
			return new double[][]{{x0, HUD_WIDTH, HUD_WIDTH}, {0, 0, wedgeTop}};
		}
		if (wedgeTop <= plateTop) {
			// 楔形在底板左侧露出一个尖（顶得比底板低）
			return new double[][]{
				{x0, HUD_WIDTH, HUD_WIDTH, plateLeft, plateLeft},
				{0, 0, plateTop, plateTop, yAtPlateLeft}};
		}
		// 常态：底板 + 左上角的楔形尖 + 右上角斜边冒出来的那块
		final double xCross = x0 + plateTop / tan;
		return new double[][]{
			{x0, HUD_WIDTH, HUD_WIDTH, xCross, plateLeft, plateLeft},
			{0, 0, wedgeTop, plateTop, plateTop, yAtPlateLeft}};
	}

	/**
	 * 第 {@code index} 位数字所在**格子的中心 X**（0 = 百位）。格宽与是哪一位无关，
	 * 所以同一位数字在 0↔8 之间变化时**中点不动** —— 这就是"中点固定、不因字宽伸缩"的落点。
	 *
	 * @param digitsRight 三位数的右边界（= 画布宽 − 留白 − km/h 宽 − 间隙）
	 */
	static double digitCentreX(int index, double cellWidth, double digitsRight) {
		return digitsRight - (3 - index) * cellWidth + cellWidth / 2;
	}

	/** 三位数字块的右边界：给最右边的 km/h 让出位置（用户要求"速度右加个 km/h"）。 */
	static double digitsRight(MmtrPanelCanvas canvas) {
		return HUD_WIDTH - EDGE_PADDING - canvas.textWidth("km/h", UNIT_INK_HEIGHT) - UNIT_GAP;
	}

	/** 格子宽 = 0–9 里最宽的一位（真实字体量）+ 呼吸 ⇒ 读数整块宽度与数字内容无关。 */
	static double digitCellWidth(MmtrPanelCanvas canvas) {
		double widest = 1;
		for (char digit = '0'; digit <= '9'; digit++) {
			widest = Math.max(widest, canvas.textWidth(String.valueOf(digit), DIGIT_INK_HEIGHT));
		}
		return widest + DIGIT_GAP;
	}

	/** 限速牌白面里**能被数字用掉**的宽度（白面直径再留 {@value #LIMIT_BOX_FILL} 的边）。 */
	static double limitBoxWidth() {
		return 2 * (LIMIT_SIGN_RADIUS - LIMIT_RING_WIDTH) * LIMIT_BOX_FILL;
	}

	/** 限速牌里数字的墨迹高：用真实字体量宽度，反复收缩到白面里放得下。 */
	static double limitInkHeight(MmtrPanelCanvas canvas, int limitKmh) {
		final String text = String.valueOf(limitKmh);
		return fittedInkHeight(LIMIT_TEXT_MAX_INK_HEIGHT, limitBoxWidth(), inkHeight -> canvas.textWidth(text, inkHeight));
	}

	/**
	 * 按可用宽度反推墨迹高：放得下就用 {@code maxInkHeight}，放不下就**反复**等比缩小，保底 {@value #MIN_TEXT_INK_HEIGHT}。
	 *
	 * <p><b>为什么要迭代</b>：宽度对字号并不严格线性（AWT 会把字体尺寸取整、字距也按像素取整），
	 * 所以"量一次、按比例缩一次"是**不够**的。离线探针实测：限速 100/120/160 用一次估算得到
	 * 8.69 号字，量出来 18.73，仍然比白面可用宽 18.40 宽 —— 也就是用户看到的"数字压在红圈上"
	 * 并没有被真正修掉。改成量一次缩一次、最多 8 轮、收缩量小于 0.1 就停，才是按**真实宽度**收敛。</p>
	 *
	 * @param widthAtInk 给定墨迹高量出文本宽度（由调用方用真实字体量；纯函数，好测）
	 */
	static double fittedInkHeight(double maxInkHeight, double boxWidth, DoubleUnaryOperator widthAtInk) {
		double inkHeight = maxInkHeight;
		for (int attempt = 0; attempt < MAX_FIT_ATTEMPTS; attempt++) {
			final double width = widthAtInk.applyAsDouble(inkHeight);
			if (width <= boxWidth || width <= 0) {
				break;
			}
			final double shrunk = Math.max(MIN_TEXT_INK_HEIGHT, inkHeight * boxWidth / width);
			if (shrunk >= inkHeight - MIN_FIT_STEP) {
				// 已经到保底字号（或缩不动了）：再迭代也不会更窄，停手，避免死循环
				inkHeight = shrunk;
				break;
			}
			inkHeight = shrunk;
		}
		return inkHeight;
	}
}

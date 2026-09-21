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
 * 屏幕**右下角的速度读数**：三角形的暗底（直角在屏幕右下角、底边 = 屏宽 20%、斜边与底边 30°）
 * + 大号三位数字 + 单位 km/h + 限速牌 + AWS 指示灯。
 *
 * <pre>
 *        ╲                        ← 斜边：与底边 30°，全部读数都在它**里面**
 *         ╲   ┌──┐
 *          ╲  │限│
 *           ╲ └──┘
 *            ╲ ● AWS
 *             ╲ 087  km/h
 *              ╲＿＿＿＿＿＿＿＿＿
 *                          ↑ 直角 = 屏幕右下角
 * </pre>
 *
 * <h2>为什么整幅烘成一张贴图（notes/224）</h2>
 *
 * <p>最早是拿 MC 的矩形原语一帧帧拼的（圆 = 逐行条、楔形 = 逐列条），**没有抗锯齿**；
 * 数字更是把 MC 字体图集的字形放大 4 倍，糊。用户一句"绘图也太粗糙了吧"就是冲这个来的。
 * 现在与仪表面板走**同一条链**：{@link MmtrPanelCanvas} 用 Java2D 把整幅画出来（抗锯齿、任意曲线、
 * 真斜体、任意字号下都锐），上传成一张贴图，屏幕上只画一个 quad。按"签名"缓存，只在内容变化时重绘。</p>
 *
 * <h2>为什么没有"底板"了（notes/225）</h2>
 *
 * <p>30° 的楔形装不下原来 208×104 的横长条读数，我当时的补法是给读数垫一块矩形底板、背景画
 * 楔形 ∪ 底板的并集。用户立刻看出来：**「三角型斜边怎么有个方形的凸起」** —— 斜边上多了一段
 * 水平台阶。</p>
 *
 * <p>这一版改回**纯三角形**：画布就是楔形自己的外接矩形（宽 = 底边、高 = 底边 × tan30°），
 * 背景只画那一个三角形；读数改成**从右下角往左上排、并按斜边约束自动定字号**
 * （{@link #digitInkHeight}）。装不下的东西（例如 854 宽窗口下的 "AWS" 字样）就不画 ——
 * 宁可少一个文字标签，也不要为了塞进内容去改用户指定的形状。</p>
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

	/** 距斜边/屏幕右边与下边的留白。 */
	static final int EDGE_PADDING = 6;

	/** 数字墨迹高的上下限（GUI 单位）—— 上限是"再宽屏也不超过"，下限是"再小就不如不画"。 */
	static final double MAX_DIGIT_INK_HEIGHT = 36;
	static final double MIN_DIGIT_INK_HEIGHT = 16;
	/** 格与格之间的呼吸。 */
	static final double DIGIT_GAP = 1;
	/** 数字的右斜体错切（≈12°，正数向右倾）；可调。 */
	static final double DIGIT_SHEAR = 0.22;

	/** km/h 的墨迹高 = 数字墨迹高 × 这个比例（不低于 {@link #MIN_UNIT_INK_HEIGHT}）。 */
	static final double UNIT_INK_RATIO = 0.36;
	static final double MIN_UNIT_INK_HEIGHT = 9;
	static final double UNIT_GAP = 4;
	/** 线性反解字号时的参考字号（越小越准，但太小会被 hinting 带偏）。 */
	static final double REFERENCE_INK_HEIGHT = 100;

	static final double ICON_GAP = 4;
	static final double LIMIT_SIGN_RADIUS = 12;
	static final double LIMIT_RING_WIDTH = 3;
	/** 限速数字在白面里占的最大宽度比例（留一点边）。 */
	static final double LIMIT_BOX_FILL = 0.92;
	/** 限速数字的**上限**墨迹高；宽度不够时按比例降。 */
	static final double LIMIT_TEXT_MAX_INK_HEIGHT = 13;
	/** 字号再小也不低于这个墨迹高（否则不如不画）。 */
	static final double MIN_TEXT_INK_HEIGHT = 6;
	/** 反推字号最多迭代几轮（宽度对字号只是近似线性，见 {@link #fittedInkHeight}）。 */
	static final int MAX_FIT_ATTEMPTS = 8;
	/** 每轮至少得缩这么多才算"还在收敛"，否则停手。 */
	static final double MIN_FIT_STEP = 0.1;
	static final double AWS_LAMP_RADIUS = 7;
	static final double AWS_LABEL_INK_HEIGHT = 8;
	/** 内容与斜边之间至少留这么多 —— 贴着斜边画看起来就像被切掉一块。 */
	static final double SLOPE_MARGIN = 2.5;

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
	 * 底边 = 屏宽的 {@value #WEDGE_BOTTOM_FRACTION_PERCENT}%（窄窗口下不低于 {@link #MIN_WEDGE_LEG}），
	 * 斜边与**底边**夹角 30° ⇒ 竖边高 = 底边 × tan30°。
	 */
	static final double WEDGE_BOTTOM_FRACTION = 0.20;
	static final int WEDGE_BOTTOM_FRACTION_PERCENT = 20;
	/**
	 * 底边下限（GUI 单位）：窗口很窄时屏宽的 20% 只有几十个单位，连三个数字都放不下。
	 * 下限只影响窄窗口；常见窗口（854 起）走的仍是用户口径的 20%。
	 */
	static final double MIN_WEDGE_LEG = 150;
	static final double WEDGE_ANGLE_DEGREES = 30.0;
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
		final int screenWidth = window.getScaledWidth();
		final int speedKmh = Math.min(999, Math.max(0, (int) Math.round(Math.abs(vehicle.getSpeed()) * 3600)));
		final int limitKmh = (int) Math.min(999, Math.max(0, vehicle.getMmtrSpeedLimitKmhFromSync()));
		final AwsState awsState = vehicle.isMmtrAwsWarningPendingFromSync() ? AwsState.WARNING
			: vehicle.isMmtrAwsWarningAcknowledgedFromSync() ? AwsState.ACKNOWLEDGED : AwsState.OFF;

		// 按签名缓存：只有内容/尺寸变了才重绘（含 AWS 闪烁相位 —— 闪的是灯，不是整幅）
		final String signature = speedKmh + "|" + limitKmh + "|" + awsState + "|"
			+ (awsState == AwsState.WARNING ? awsWarningColourNow() : 0) + "|" + canvasWidth(screenWidth) + "x" + canvasHeight(screenWidth)
			+ "|" + deviceScale(window);
		final MmtrPanelTexture texture = MmtrPanelTexture.get("speed_hud");
		if (texture.needsRedraw(signature)) {
			final int deviceScale = deviceScale(window);
			final MmtrPanelCanvas canvas = MmtrPanelCanvas.createExactPixels(canvasWidth(screenWidth), canvasHeight(screenWidth),
				canvasWidth(screenWidth) * deviceScale, canvasHeight(screenWidth) * deviceScale);
			paint(canvas, speedKmh, limitKmh, awsState, screenWidth, awsWarningColourNow());
			texture.redraw(canvas, signature);
		}

		final GuiDrawing guiDrawing = new GuiDrawing(graphicsHolder);
		guiDrawing.beginDrawingTexture(texture.identifier());
		final double[] quad = screenQuad(screenWidth, window.getScaledHeight());
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
		return new double[]{
			scaledWindowWidth - canvasWidth(scaledWindowWidth), scaledWindowHeight - canvasHeight(scaledWindowWidth),
			scaledWindowWidth, scaledWindowHeight};
	}

	/**
	 * 把整幅画到画布上（**不碰任何 MC 类型**，所以离线探针也能调用它出图，见 notes/224）。
	 *
	 * @param screenWidth      屏幕宽度（GUI 单位）—— 楔形与画布都按它算
	 * @param awsWarningColour 未确认告警时灯的实际颜色（由 {@link #awsWarningColourNow()} 给出闪烁相位）
	 */
	static void paint(MmtrPanelCanvas canvas, int speedKmh, int limitKmh, AwsState awsState, int screenWidth, int awsWarningColour) {
		final double width = canvasWidth(screenWidth);
		final double height = canvasHeight(screenWidth);

		// ---- 背景：**纯三角形**（直角在画布右下角 = 屏幕右下角；画布就是它的外接矩形） ----------------
		// ★ 用户口径的形状，不再挂底板：以前为了给读数垫暗底把"楔形 ∪ 矩形"画成一个多边形，
		//   斜边上就多了一段水平台阶，用户一眼看出来「三角型斜边怎么有个方形的凸起」。
		canvas.fillPolygon(new double[]{0, width, width}, new double[]{0, 0, height}, WEDGE_COLOR);

		// ---- 数字 + 单位（整块右对齐；字号由斜边约束反推，见 digitInkHeight） ------------------------
		final double inkHeight = digitInkHeight(canvas, screenWidth);
		final double cellWidth = digitCellWidth(canvas, inkHeight);
		final double unitInk = unitInkHeight(inkHeight);
		final double digitsRight = width - EDGE_PADDING - canvas.textWidth("km/h", unitInk) - UNIT_GAP;
		final String digits = String.format("%03d", speedKmh);
		for (int i = 0; i < 3; i++) {
			// ★ 每位在自己的格子里居中；斜体绕各自墨迹中心错切 ⇒ 中点固定、不因字宽伸缩
			canvas.textItalic(String.valueOf(digits.charAt(i)), digitCentreX(i, cellWidth, digitsRight), EDGE_PADDING, inkHeight, DIGIT_SHEAR, DIGIT_COLOR,
				IGui.HorizontalAlignment.CENTER, IGui.VerticalAlignment.BOTTOM);
		}
		canvas.text("km/h", width - EDGE_PADDING, EDGE_PADDING, unitInk, UNIT_COLOR,
			IGui.HorizontalAlignment.RIGHT, IGui.VerticalAlignment.BOTTOM);

		// ---- 图标行：从右往左依次是 限速牌 → AWS 灯 → AWS 字样；整行右对齐 ---------------------------
		final double rowBottom = EDGE_PADDING + inkHeight + ICON_GAP;
		final double rowCentreY = rowBottom + LIMIT_SIGN_RADIUS;
		double cursorRight = width - EDGE_PADDING;
		if (limitKmh > 0) {
			final double signCentreX = cursorRight - LIMIT_SIGN_RADIUS;
			canvas.circle(signCentreX, rowCentreY, LIMIT_SIGN_RADIUS, LIMIT_RING_COLOR);
			canvas.circle(signCentreX, rowCentreY, LIMIT_SIGN_RADIUS - LIMIT_RING_WIDTH, LIMIT_FACE_COLOR);
			// ★ 按**可用宽度**反推字号：三位数的 "160" 会撑出白面（实测离线出图时 "1" 压在红圈上）
			canvas.text(String.valueOf(limitKmh), signCentreX, rowCentreY, limitInkHeight(canvas, limitKmh), LIMIT_TEXT_COLOR,
				IGui.HorizontalAlignment.CENTER, IGui.VerticalAlignment.CENTER);
			cursorRight -= 2 * LIMIT_SIGN_RADIUS + ICON_GAP;
		}

		final double lampCentreX = cursorRight - AWS_LAMP_RADIUS;
		final int lampColour = switch (awsState) {
			case WARNING -> awsWarningColour;
			case ACKNOWLEDGED -> AWS_ACK_COLOR;
			case OFF -> AWS_OFF_COLOR;
		};
		canvas.circle(lampCentreX, rowCentreY, AWS_LAMP_RADIUS, 0xFF101418);
		canvas.circle(lampCentreX, rowCentreY, AWS_LAMP_RADIUS - 2, lampColour);
		cursorRight -= 2 * AWS_LAMP_RADIUS + ICON_GAP;

		// AWS 字样：**只在斜边里放得下时才画**。20%/30° 的楔形在常见窗口下装不下它
		// （854 宽时行顶 66、行左 96，而斜边在 y=66 处只到 x=114）—— 那就不画，
		// 灯本身已经是用户要的"AWS 图标指示"。
		if (awsLabelFits(canvas, screenWidth, inkHeight)) {
			canvas.text("AWS", cursorRight, rowCentreY, AWS_LABEL_INK_HEIGHT,
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

	/** 楔形的底边长（沿屏幕底边，GUI 单位）：屏宽的 {@value #WEDGE_BOTTOM_FRACTION_PERCENT}%，不低于 {@link #MIN_WEDGE_LEG}。 */
	static double wedgeBottomLeg(int scaledWindowWidth) {
		return Math.max(scaledWindowWidth * WEDGE_BOTTOM_FRACTION, MIN_WEDGE_LEG);
	}

	/** 楔形的竖边高（沿屏幕右边，GUI 单位）= 底边 × tan(30°)。 */
	static double wedgeHeight(int scaledWindowWidth) {
		return wedgeBottomLeg(scaledWindowWidth) * Math.tan(Math.toRadians(WEDGE_ANGLE_DEGREES));
	}

	/** 画布宽 = 楔形底边（画布就是楔形的外接矩形，所以背景三角形正好铺满它的一半）。 */
	static int canvasWidth(int scaledWindowWidth) {
		return (int) Math.ceil(wedgeBottomLeg(scaledWindowWidth));
	}

	/** 画布高 = 楔形竖边高。 */
	static int canvasHeight(int scaledWindowWidth) {
		return (int) Math.ceil(wedgeHeight(scaledWindowWidth));
	}

	/**
	 * 点 {@code (x, y)}（x 自画布左边、y 自画布下边，与 {@link MmtrPanelCanvas} 同一约定）
	 * 是否在楔形内侧（再留 {@code margin}）。画布宽高都取了整（{@code ceil}）⇒ 真实斜边只会更陡，
	 * 所以用 tan30° 判是**保守**的。
	 */
	static boolean insideWedge(int scaledWindowWidth, double x, double y, double margin) {
		return y + margin <= x * Math.tan(Math.toRadians(WEDGE_ANGLE_DEGREES));
	}

	/** 给定参考字号下 0–9 里最宽的一位有多宽（用来线性反解字号）。 */
	static double widestDigitWidth(MmtrPanelCanvas canvas, double inkHeight) {
		double widest = 1;
		for (char digit = '0'; digit <= '9'; digit++) {
			widest = Math.max(widest, canvas.textWidth(String.valueOf(digit), inkHeight));
		}
		return widest;
	}

	/** 格子宽 = 0–9 里最宽的一位（真实字体量）+ 呼吸 ⇒ 读数整块宽度与数字内容无关。 */
	static double digitCellWidth(MmtrPanelCanvas canvas, double inkHeight) {
		return widestDigitWidth(canvas, inkHeight) + DIGIT_GAP;
	}

	/** km/h 的墨迹高：跟着数字一起缩，但不低于 {@link #MIN_UNIT_INK_HEIGHT}。 */
	static double unitInkHeight(double digitInkHeight) {
		return Math.max(MIN_UNIT_INK_HEIGHT, digitInkHeight * UNIT_INK_RATIO);
	}

	/** 三位数字块的左边界（整块右对齐，右边给 km/h 让位）—— 斜边约束看的就是它。 */
	static double digitsLeft(MmtrPanelCanvas canvas, int screenWidth, double inkHeight) {
		return canvasWidth(screenWidth) - EDGE_PADDING - canvas.textWidth("km/h", unitInkHeight(inkHeight)) - UNIT_GAP
			- 3 * digitCellWidth(canvas, inkHeight);
	}

	/**
	 * 求"塞得进斜边"的最大数字墨迹高（夹在 {@value #MIN_DIGIT_INK_HEIGHT}~{@value #MAX_DIGIT_INK_HEIGHT}）。
	 *
	 * <p>约束只有一个：**数字块的左上角必须在斜边内侧**（斜边从左下往右上抬，所以块的最左上角
	 * 是离斜边最近的那一点），即 {@code EDGE_PADDING + d + margin ≤ (块左边界) × tan30°}。</p>
	 *
	 * <p>先按参考字号的宽度**线性反解**一个初值（宽度对字号只是近似线性），再用真实宽度收敛 ——
	 * 与限速牌那套 {@link #fittedInkHeight} 同一个道理：一次估算会缩不够。</p>
	 */
	static double digitInkHeight(MmtrPanelCanvas canvas, int screenWidth) {
		final double tan = Math.tan(Math.toRadians(WEDGE_ANGLE_DEGREES));
		final double digitRatio = widestDigitWidth(canvas, REFERENCE_INK_HEIGHT) / REFERENCE_INK_HEIGHT;
		final double unitRatio = canvas.textWidth("km/h", REFERENCE_INK_HEIGHT) / REFERENCE_INK_HEIGHT;
		// 块左边界 ≈ W − PAD − unitRatio·UNIT_INK_RATIO·d − GAP_U − 3·digitRatio·d − 3·GAP_D
		final double denominator = 1 + tan * (unitRatio * UNIT_INK_RATIO + 3 * digitRatio);
		final double numerator = tan * (canvasWidth(screenWidth) - EDGE_PADDING - UNIT_GAP - 3 * DIGIT_GAP) - EDGE_PADDING - SLOPE_MARGIN;
		double inkHeight = Math.max(MIN_DIGIT_INK_HEIGHT, Math.min(MAX_DIGIT_INK_HEIGHT, numerator / denominator));

		for (int attempt = 0; attempt < MAX_FIT_ATTEMPTS; attempt++) {
			if (insideWedge(screenWidth, digitsLeft(canvas, screenWidth, inkHeight), EDGE_PADDING + inkHeight, SLOPE_MARGIN)) {
				break;
			}
			final double shrunk = Math.max(MIN_DIGIT_INK_HEIGHT, inkHeight - 1);
			if (shrunk >= inkHeight) {
				break;
			}
			inkHeight = shrunk;
		}
		return inkHeight;
	}

	/** 图标行的顶边（限速牌比 AWS 灯高，取它）。 */
	static double iconRowTop(double digitInkHeight) {
		return EDGE_PADDING + digitInkHeight + ICON_GAP + 2 * LIMIT_SIGN_RADIUS;
	}

	/** 图标行的左边界（从右往左：限速牌 → AWS 灯 → 可选的 AWS 字样）。 */
	static double iconRowLeft(MmtrPanelCanvas canvas, int screenWidth, boolean withAwsLabel) {
		double left = canvasWidth(screenWidth) - EDGE_PADDING - 2 * LIMIT_SIGN_RADIUS - ICON_GAP - 2 * AWS_LAMP_RADIUS - ICON_GAP;
		if (withAwsLabel) {
			left -= canvas.textWidth("AWS", AWS_LABEL_INK_HEIGHT);
		}
		return left;
	}

	/** AWS 字样放得下吗（放不下就不画 —— 少一个标签好过改形状）。 */
	static boolean awsLabelFits(MmtrPanelCanvas canvas, int screenWidth, double digitInkHeight) {
		return insideWedge(screenWidth, iconRowLeft(canvas, screenWidth, true), iconRowTop(digitInkHeight), SLOPE_MARGIN);
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

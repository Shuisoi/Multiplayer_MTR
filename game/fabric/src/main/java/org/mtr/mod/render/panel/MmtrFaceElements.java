package org.mtr.mod.render.panel;

import org.mtr.mod.Init;
import org.mtr.mod.data.IGui;
import org.mtr.mod.mmtr.face.MmtrFaceAnim;
import org.mtr.mod.mmtr.face.MmtrFaceDocument;
import org.mtr.mod.mmtr.face.MmtrFaceGeometry;
import org.mtr.mod.mmtr.face.MmtrFaceLogic;
import org.mtr.mod.mmtr.face.MmtrFaceSchema;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车辆动态面的**元素注册表 + 绘制器**（notes/359）：把一份 {@link MmtrFaceDocument} 画到
 * {@link MmtrPanelCanvas} 上。
 *
 * <p>每种元素一个画法，注册在 {@link #PAINTERS} 里 —— 这就是"缺画法"那条升级阶梯的落点
 * （{@link #register} 是代码级的入口，附属模组不用改引擎就能加自己的 {@code type}，
 * 作者侧只是 JSON 里多写一个名字）。类型清单本身住在纯数据层
 * （{@link MmtrFaceSchema#ELEMENT_TYPES}），这里必须**不多不少**地实现它，由
 * {@code MmtrFaceToolingTests} 读源码文本对照。</p>
 *
 * <h2>元素集</h2>
 * <table>
 *   <tr><td>{@code text}</td><td>模板文本；{@code size} 是牌高比例；{@code shrinkToFit}（默认 0.9）
 *       超宽等比缩小；{@code align}；{@code anim.marquee} 时变成走马灯（裁在 {@code w} 的视口里）</td></tr>
 *   <tr><td>{@code rect} / {@code roundrect}</td><td>{@code x,y,w,h}（左下角 + 宽高）+ {@code radius}</td></tr>
 *   <tr><td>{@code line}</td><td>{@code x,y → x2,y2}；{@code width}</td></tr>
 *   <tr><td>{@code circle} / {@code arc}</td><td>圆心 + {@code radius}（± {@code start/end/width}）</td></tr>
 *   <tr><td>{@code gauge}</td><td>表盘 + 指针；指针值来自 {@code needle} 表达式</td></tr>
 *   <tr><td>{@code image}</td><td>{@code src}/{@code fit}/{@code alpha}/{@code tint}；缺图 ⇒ 占位框</td></tr>
 *   <tr><td>{@code foreach}</td><td>**没有画法**：重复在 {@link MmtrFaceDocument#plan} 里就展开了，
 *       能走到这里的只有"嵌套超过 4 层"的那一层，记一次账然后跳过</td></tr>
 * </table>
 *
 * <h2>逐帧的三件事（F3）</h2>
 * <ol>
 *   <li><b>透明度</b> {@code opacity}（乘上 {@code anim.fade} 的 alpha）—— 乘进颜色的 alpha 位，
 *       不用 {@code Graphics2D} 的全局 alpha（形状与文本共用一条路，少一处会忘的地方；图片那条自己带 alpha）；</li>
 *   <li><b>旋转</b> {@code rotate}（叠上 {@code anim.spin}）—— 绕**元素自己的锚点**在面内转，
 *       按 §3 的锚点定义（矩形是左下角、圆是圆心、文本是那个点）；</li>
 *   <li><b>显示与否</b> {@code anim.blink} —— 灭着的时候直接不画。</li>
 * </ol>
 *
 * <h2>错一处不毁一块面</h2>
 * <p>条件求值抛异常（不认识的算子 / 超预算）、模板里写坏、类型不认识 ⇒ **跳过那一个元素**并记一次日志，
 * 其余照画。资源包是不可信内容：一块牌里写坏一行，不该让整列车的水牌全黑。另外，
 * **写了但键表里没有的键**会记一次账（{@code colour}/{@code siz} 这种拼错，症状是"预览好好的、
 * 进游戏不对"，属于最难查的一类）。</p>
 */
public final class MmtrFaceElements {

	/**
	 * 画一次需要的东西：数据（{@code forEach} 绑好的那一份）、算好的透明度与时钟、以及动画本身。
	 *
	 * @param data    这个元素的数据（已并进文档 {@code vars} 与所在 {@code forEach} 的项/下标）
	 * @param opacity 0..1（{@code opacity} × {@code anim.fade}）
	 * @param timeMs  时钟（毫秒，见 {@link MmtrFaceAnim}）
	 * @param anim    逐帧动画；{@code null} = 这个元素不动
	 */
	public record Paint(Object data, double opacity, long timeMs, @Nullable MmtrFaceAnim anim) {
	}

	private static final Map<String, MmtrFaceElementRenderer> PAINTERS = new LinkedHashMap<>();
	/** 出过问题的元素只提示一次（文档 id + 元素下标 + 原因）。 */
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	/** 占位框的颜色（原版"缺贴图"的洋红，一眼能认出是"这张图没找到"）。 */
	private static final int MISSING_IMAGE_COLOR = 0x66FF00FF;

	static {
		PAINTERS.put("text", MmtrFaceElements::paintText);
		PAINTERS.put("rect", MmtrFaceElements::paintRect);
		PAINTERS.put("roundrect", MmtrFaceElements::paintRoundRect);
		PAINTERS.put("line", MmtrFaceElements::paintLine);
		PAINTERS.put("circle", MmtrFaceElements::paintCircle);
		PAINTERS.put("arc", MmtrFaceElements::paintArc);
		PAINTERS.put("gauge", MmtrFaceElements::paintGauge);
		PAINTERS.put("image", MmtrFaceElements::paintImage);
		PAINTERS.put("foreach", MmtrFaceElements::paintForEach);
	}

	private MmtrFaceElements() {
	}

	/** 认得的元素类型（打包校验与工作室的"元素表"都读它）：只有随包发行的这几种。 */
	public static Set<String> types() {
		return new LinkedHashSet<>(PAINTERS.keySet());
	}

	public static boolean knows(String type) {
		return PAINTERS.containsKey(type == null ? "" : type.toLowerCase(Locale.ROOT));
	}

	/**
	 * 画一整块面：先铺底（{@code background} 为 0 = 不铺，让模型的材质透出来），
	 * 再按**计划**（{@link MmtrFaceDocument#plan}：选页 + 展开 {@code forEach}）顺序画每个"该画"的元素。
	 */
	public static void paint(MmtrPanelCanvas canvas, MmtrFaceDocument document, Object data) {
		paint(canvas, document, data, 0);
	}

	/** 同上，带时钟（动画）。 */
	public static void paint(MmtrPanelCanvas canvas, MmtrFaceDocument document, Object data, long timeMs) {
		paintPage(canvas, document, -1, data, timeMs);
	}

	/**
	 * 同上，但**指定页**（{@code -1} = 按文档自己选）。
	 *
	 * <p>翻牌机要这条：一帧里每一面贴一页，所以"当前页"这个概念在那条路上不成立。</p>
	 */
	public static void paintPage(MmtrPanelCanvas canvas, MmtrFaceDocument document, int page, Object data, long timeMs) {
		if (canvas == null || document == null) {
			return;
		}
		if (document.ignoredTopLevelElements()) {
			warnOnce(document, 0, "同时写了 pages 与顶层 elements —— elements 被忽略（pages 说了算）");
		}
		final double width = canvas.widthM();
		final double height = canvas.heightM();
		if (document.background() != 0) {
			canvas.fill(0, 0, width, height, document.background());
		}
		final List<MmtrFaceDocument.Drawable> plan = page < 0 ? document.plan(data, timeMs) : document.planPage(page, data, timeMs);
		for (final MmtrFaceDocument.Drawable drawable : plan) {
			final MmtrFaceDocument.Element element = drawable.element();
			final int index = drawable.index() + 1;
			if (!visible(document, element, drawable.data(), index)) {
				continue;
			}
			final MmtrFaceAnim anim = element.anim();
			if (anim != null && !anim.visible(timeMs)) {
				continue;
			}
			final String type = element.type().toLowerCase(Locale.ROOT);
			final MmtrFaceElementRenderer painter = PAINTERS.get(type);
			if (painter == null) {
				warnOnce(document, index, "不认识的元素类型「" + element.type() + "」（认得的有 " + types() + "）");
				continue;
			}
			warnUnknownKeys(document, element, index);
			warnIneffectiveAnim(document, element, index, type);
			final double opacity = clamp(element.number("opacity", 1) * (anim == null ? 1 : anim.alpha(timeMs)), 0, 1);
			final double rotation = element.number("rotate", 0) + (anim == null ? 0 : anim.degrees(timeMs));
			final boolean rotated = Math.abs(rotation) > 1.0E-6;
			if (rotated) {
				canvas.pushRotate(element.x() * width, element.y() * height, rotation);
			}
			try {
				painter.paint(canvas, document, element, new Paint(drawable.data(), opacity, timeMs, anim));
			} catch (RuntimeException e) {
				warnOnce(document, index, "画失败（已跳过这一个元素）：" + e);
			} finally {
				if (rotated) {
					canvas.popTransform();
				}
			}
		}
	}

	/** 这个元素现在该不该画：没有 {@code when} = 画；有就求值（出错 ⇒ 不画 + 记一次）。 */
	private static boolean visible(MmtrFaceDocument document, MmtrFaceDocument.Element element, Object data, int index) {
		if (element.when() == null) {
			return true;
		}
		try {
			return MmtrFaceLogic.truthy(MmtrFaceLogic.eval(element.when(), data));
		} catch (RuntimeException e) {
			warnOnce(document, index, "when 求值失败：" + e);
			return false;
		}
	}

	/**
	 * 写了但键表里没有的键 ⇒ 记一次账（**照画**）。
	 *
	 * <p>为什么是警告而不是忽略：{@code {"siz": 0.8}} 与 {@code {"size": 0.8}} 在屏幕上的差别是
	 * "字大小不对"，在图上看不出、在日志里只有这一行能指出来。为什么还要照画：一个多余的键
	 * 不该让整块牌消失，作者可能正在从一个旧版本迁移。</p>
	 */
	private static void warnUnknownKeys(MmtrFaceDocument document, MmtrFaceDocument.Element element, int index) {
		if (element.raw() == null) {
			return;
		}
		final Set<String> known = MmtrFaceSchema.knownKeys(element.type());
		for (final String key : element.raw().keySet()) {
			if (!known.contains(key)) {
				warnOnce(document, index, "不认识的键「" + key + "」（认得的：" + known + "）—— 已照画，但很可能是拼错了");
			}
		}
	}

	/**
	 * {@code anim} 段写了却不生效的要记一次账（作者按格式文档"会记一次"来翻日志，所以必须真的记）。
	 *
	 * <p>两种"写了等于没写"：{@code kind} 拼错（或者 {@code anim} 根本不是对象）⇒ 当静态显示；
	 * {@code marquee} 写在不是 {@code text} 的元素上 ⇒ 走马灯只对文本有意义。</p>
	 */
	private static void warnIneffectiveAnim(MmtrFaceDocument document, MmtrFaceDocument.Element element, int index, String type) {
		final var raw = element.raw() == null ? null : element.raw().get("anim");
		if (raw != null && !raw.isJsonNull() && element.anim() == null) {
			warnOnce(document, index, raw.isJsonObject()
				? "anim.kind 不认识 —— 已当没有动画（认得的：" + MmtrFaceAnim.kinds() + "）"
				: "anim 必须是对象（如 {\"kind\":\"blink\"}）—— 已当没有动画");
		} else if (element.anim() != null && element.anim().kind() == MmtrFaceAnim.Kind.MARQUEE && !"text".equals(type)) {
			warnOnce(document, index, "anim.marquee 只对 text 有效 —— 写在 " + type + " 上等于没写");
		}
	}

	private static void warnOnce(MmtrFaceDocument document, int index, String reason) {		if (WARNED.add(document.id() + "#" + index + "#" + reason)) {
			Init.LOGGER.warn("[MMTR] 面文档 {} 的第 {} 个元素被跳过：{}", document.id(), index, reason);
		}
	}

	/** 资源重载时清掉"只提示一次"的记忆（与各版式缓存的清理同一个时机）。 */
	public static void clearWarnings() {
		WARNED.clear();
	}

	// ---- 元素画法 ---------------------------------------------------------------------------------

	private static void paintText(MmtrPanelCanvas canvas, MmtrFaceDocument document, MmtrFaceDocument.Element element, Paint paint) {
		final String text = element.resolveText(paint.data(), paint.timeMs());
		if (text.isEmpty()) {
			// 取不到内容 ⇒ 这一行不画（不是画个空位）—— 与 MmtrPidLayout 的"取不到内容的行不画"同一条
			return;
		}
		final double width = canvas.widthM();
		final double height = canvas.heightM();
		double sizeM = element.size() * height;
		final int color = withOpacity(element.color() == 0 ? document.textColor() : element.color(), paint.opacity());
		final double y = element.y() * height;

		if (paint.anim() != null && paint.anim().kind() == MmtrFaceAnim.Kind.MARQUEE) {
			paintMarquee(canvas, element, paint, text, sizeM, color, y);
			return;
		}

		final double shrink = element.number("shrinkToFit", MmtrFaceDocument.DEFAULT_SHRINK_TO_FIT);
		if (shrink > 0) {
			final double maxWidthM = width * shrink;
			final double widthAtFullSize = canvas.textWidth(text, sizeM);
			if (widthAtFullSize > maxWidthM && widthAtFullSize > 0) {
				sizeM = sizeM * maxWidthM / widthAtFullSize;
			}
		}
		canvas.text(text, element.x() * width, y, sizeM, color, align(element.align()), IGui.VerticalAlignment.CENTER);
	}

	/**
	 * 走马灯：视口 = 元素的 {@code w}（{@code w = 0} 时 = 从 {@code x} 到牌右缘），
	 * 文本从视口右边进来走到左边出去，**裁在视口里**。
	 *
	 * <p>位置算在 {@link MmtrFaceGeometry#marqueeLeft}（纯函数，用例钉得住）。
	 * 走马灯时刻意**不做** {@code shrinkToFit}：它的本意就是"装不下才要滚"，
	 * 缩到装得下就没得滚了。{@code align} 也不参与（起点由视口与接缝决定）。</p>
	 */
	private static void paintMarquee(MmtrPanelCanvas canvas, MmtrFaceDocument.Element element, Paint paint, String text, double sizeM, int color, double y) {
		final double width = canvas.widthM();
		final double height = canvas.heightM();
		final double viewportLeft = element.x() * width;
		final double viewportWidth = element.w() > 0 ? element.w() * width : Math.max(0, width - viewportLeft);
		if (viewportWidth <= 0) {
			return;
		}
		final double textWidth = canvas.textWidth(text, sizeM);
		final double gapM = element.anim().gapRatio() * height;
		final double left = MmtrFaceGeometry.marqueeLeft(viewportLeft, viewportWidth, textWidth, gapM, element.anim().offsetFraction(paint.timeMs()));
		// ★ 必须裁：文本比视口长，不裁就会压在旁边的元素上（不是"看不见"那么客气）
		canvas.pushClip(viewportLeft, element.y() * height - sizeM, viewportWidth, sizeM * 2);
		canvas.text(text, left, y, sizeM, color, IGui.HorizontalAlignment.LEFT, IGui.VerticalAlignment.CENTER);
		canvas.popClip();
	}

	private static void paintRect(MmtrPanelCanvas canvas, MmtrFaceDocument document, MmtrFaceDocument.Element element, Paint paint) {
		canvas.fill(element.x() * canvas.widthM(), element.y() * canvas.heightM(),
			element.w() * canvas.widthM(), element.h() * canvas.heightM(), withOpacity(element.color(), paint.opacity()));
	}

	private static void paintRoundRect(MmtrPanelCanvas canvas, MmtrFaceDocument document, MmtrFaceDocument.Element element, Paint paint) {
		canvas.fillRoundRect(element.x() * canvas.widthM(), element.y() * canvas.heightM(),
			element.w() * canvas.widthM(), element.h() * canvas.heightM(),
			element.number("radius", 0.08) * canvas.heightM(), withOpacity(element.color(), paint.opacity()));
	}

	private static void paintLine(MmtrPanelCanvas canvas, MmtrFaceDocument document, MmtrFaceDocument.Element element, Paint paint) {
		canvas.line(element.x() * canvas.widthM(), element.y() * canvas.heightM(),
			element.number("x2", element.x()) * canvas.widthM(), element.number("y2", element.y()) * canvas.heightM(),
			Math.max(element.number("width", 0.03), 0.001) * canvas.heightM(), withOpacity(element.color(), paint.opacity()));
	}

	private static void paintCircle(MmtrPanelCanvas canvas, MmtrFaceDocument document, MmtrFaceDocument.Element element, Paint paint) {
		canvas.circle(element.x() * canvas.widthM(), element.y() * canvas.heightM(),
			element.number("radius", 0.1) * canvas.heightM(), withOpacity(element.color(), paint.opacity()));
	}

	private static void paintArc(MmtrPanelCanvas canvas, MmtrFaceDocument document, MmtrFaceDocument.Element element, Paint paint) {
		canvas.arc(element.x() * canvas.widthM(), element.y() * canvas.heightM(),
			element.number("radius", 0.4) * canvas.heightM(),
			Math.max(element.number("width", 0.01), 0.001) * canvas.heightM(),
			element.number("start", 0), element.number("end", 360), withOpacity(element.color(), paint.opacity()));
	}

	/**
	 * 表盘 + 指针（与 {@code MmtrHudLayout.paintGauge} 的键与缺省值逐字相同，所以 HUD 搬过来时
	 * 是一对一的翻译）。唯一的新东西是**指针值来自表达式** {@code needle}（默认车速）。
	 *
	 * <p>线宽那条老规矩要留着：作者写 {@code width} 而不是 {@code lineWidth} 时，
	 * {@code width} 就是线宽（缺省表里 {@code lineWidth} 有值，所以不能指望"外层兜底"——
	 * 先看作者到底写了哪个键）。</p>
	 */
	private static void paintGauge(MmtrPanelCanvas canvas, MmtrFaceDocument document, MmtrFaceDocument.Element element, Paint paint) {
		final double width = canvas.widthM();
		final double height = canvas.heightM();
		final double cx = element.x() * width;
		final double cy = element.y() * height;
		final double radius = element.number("radius", 0.44) * height;
		if (radius <= 0) {
			return;
		}
		final double start = element.number("start", 225);
		final double end = element.number("end", -45);
		final double lineWidth = Math.max(element.number(element.raw().has("lineWidth") ? "lineWidth" : "width", 0.004), 0.004) * height;
		final int color = withOpacity(element.color() == 0 ? document.textColor() : element.color(), paint.opacity());
		final double max = element.number("max", 160);

		canvas.arc(cx, cy, radius, lineWidth, start, end, color);

		final double ticks = element.number("ticks", 0);
		if (ticks > 0) {
			final double tickLength = element.number("tickLength", 0.12) * height;
			final double labelEvery = element.number("labelEvery", 0);
			final double labelSize = element.number("labelSize", 0.13);
			final int labelColor = withOpacity(MmtrFaceDocument.parseColor(element.string("labelColor", ""), 0), paint.opacity());
			for (int i = 0; i <= (int) ticks; i++) {
				final double fraction = i / ticks;
				final double angle = Math.toRadians(start + (end - start) * fraction);
				final double cos = Math.cos(angle);
				final double sin = Math.sin(angle);
				final boolean major = ticks <= 12 || i % 5 == 0;
				final double inner = radius - tickLength * (major ? 1 : 0.55);
				canvas.line(cx + cos * inner, cy + sin * inner, cx + cos * radius, cy + sin * radius,
					Math.max(lineWidth * 0.7, 0.003 * height), color);
				if (labelEvery > 0 && i % (int) labelEvery == 0 && labelSize > 0) {
					final double labelRadius = radius - tickLength - labelSize * height * 0.7;
					canvas.text(String.valueOf(Math.round(max * fraction)), cx + cos * labelRadius, cy + sin * labelRadius,
						labelSize * height, labelColor == 0 ? color : labelColor,
						IGui.HorizontalAlignment.CENTER, IGui.VerticalAlignment.CENTER);
				}
			}
		}

		final Double value = MmtrFaceLogic.asNumber(needle(element, paint.data()));
		final double fraction = value == null || max <= 0 ? 0 : Math.max(0, Math.min(1, value / max));
		final int needleColor = withOpacity(MmtrFaceDocument.parseColor(element.string("needleColor", ""), 0xFFFF3B30), paint.opacity());
		final double needleWidth = element.number("needleWidth", 0.05) * height;
		canvas.needle(cx, cy, radius * element.number("needleLength", 0.94), Math.max(needleWidth, 0.01 * height),
			start + (end - start) * fraction, needleColor);
		canvas.circle(cx, cy, Math.max(needleWidth, 0.02 * height), needleColor);
	}

	/**
	 * 图片：{@code src}（资源包里的标识）装进 {@code x,y,w,h} 这个框，{@code fit} 决定怎么装
	 * （{@code MmtrFaceGeometry.fitRect}），{@code tint} 在加载时一次调好，{@code alpha} 与
	 * 元素的 {@code opacity} 相乘。
	 *
	 * <p>图找不到 ⇒ 画一个洋红占位框 + 一行小字（作者一眼能看出"是这张图没找到"，
	 * 而不是"我的元素怎么没画"）。</p>
	 */
	private static void paintImage(MmtrPanelCanvas canvas, MmtrFaceDocument document, MmtrFaceDocument.Element element, Paint paint) {
		final double width = canvas.widthM();
		final double height = canvas.heightM();
		final double boxX = element.x() * width;
		final double boxY = element.y() * height;
		final double boxW = element.w() * width;
		final double boxH = element.h() * height;
		if (boxW <= 0 || boxH <= 0) {
			return;
		}
		final String src = element.string("src", "");
		final int tint = MmtrFaceDocument.parseColor(element.string("tint", ""), 0xFFFFFF);
		final double alpha = clamp(element.number("alpha", 1) * paint.opacity(), 0, 1);
		final BufferedImage image = MmtrFaceImages.get(src, tint);
		if (image == null) {
			canvas.fill(boxX, boxY, boxW, boxH, withOpacity(MISSING_IMAGE_COLOR, paint.opacity()));
			final double textSize = Math.min(boxH * 0.5, 0.12 * height);
			canvas.text(src.isEmpty() ? "image: 没写 src" : src, boxX + boxW / 2, boxY + boxH / 2, textSize,
				withOpacity(0xFFFFFFFF, paint.opacity()), IGui.HorizontalAlignment.CENTER, IGui.VerticalAlignment.CENTER);
			return;
		}
		final String fit = element.string("fit", "stretch");
		final double[] rect = MmtrFaceGeometry.fitRect(fit, boxX, boxY, boxW, boxH, (double) image.getWidth() / image.getHeight());
		if ("cover".equalsIgnoreCase(fit)) {
			// cover 会超出框：裁掉超出的部分，否则图像会盖到框外面（贴图上看是"压了别的元素"）
			canvas.pushClip(boxX, boxY, boxW, boxH);
			canvas.drawImage(image, rect[0], rect[1], rect[2], rect[3], alpha);
			canvas.popClip();
		} else {
			canvas.drawImage(image, rect[0], rect[1], rect[2], rect[3], alpha);
		}
	}

	/** {@code forEach} 能走到画法这里，说明嵌套超过 4 层（正常早就被 plan 展开了）—— 记一次账。 */
	private static void paintForEach(MmtrPanelCanvas canvas, MmtrFaceDocument document, MmtrFaceDocument.Element element, Paint paint) {
		warnOnce(document, 0, "forEach 的嵌套超过 " + MmtrFaceDocument.MAX_FOREACH_DEPTH + " 层 —— 这一层不画");
	}

	/** 指针值：{@code needle} 表达式（缺省 = 车速）。 */
	private static Object needle(MmtrFaceDocument.Element element, Object data) {
		final var needle = element.raw().get("needle");
		if (needle == null || needle.isJsonNull()) {
			return MmtrFaceLogic.lookup("speedKmh", data);
		}
		return MmtrFaceLogic.eval(needle, data);
	}

	/** 把透明度乘进颜色的 alpha 位（{@code opacity = 1} 时原样返回）。 */
	private static int withOpacity(int color, double opacity) {
		if (opacity >= 1) {
			return color;
		}
		final int alpha = (int) Math.round(((color >>> 24) & 0xFF) * clamp(opacity, 0, 1));
		return (alpha << 24) | (color & 0xFFFFFF);
	}

	private static IGui.HorizontalAlignment align(String align) {
		return switch (align == null ? "" : align.trim().toLowerCase(Locale.ROOT)) {
			case "left" -> IGui.HorizontalAlignment.LEFT;
			case "right" -> IGui.HorizontalAlignment.RIGHT;
			default -> IGui.HorizontalAlignment.CENTER;
		};
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}

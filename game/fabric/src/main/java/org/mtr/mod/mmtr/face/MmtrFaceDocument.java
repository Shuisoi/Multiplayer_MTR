package org.mtr.mod.mmtr.face;

import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.mod.mmtr.MmtrPidText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 车辆动态面的**面文档**（notes/359）：一块面上画什么、什么条件下画。
 *
 * <p>面文档跟着模型走，住在锚点 JSON 里（和 {@code hud}/{@code pid} 同一处、同一条"作者写配置、
 * 打包器搬运"的路）：</p>
 *
 * <pre>
 * "faces": {
 *   "pid_1": {
 *     "background": "#FF101418", "textColor": "#FFF2F4F6", "pxPerMetre": 512, "side": "normal",
 *     "require": {"!=": [{"var": "pid.service"}, ""]},
 *     "vars": {"arriving": {"and": [{">": [{"var": "lzb.targetM"}, 0]}, {"<": [{"var": "lzb.targetM"}, 200]}]}},
 *     "elements": [
 *       {"type": "text", "text": "{pid.service}", "x": 0.03, "y": 0.5, "size": 0.46, "align": "left"},
 *       {"type": "text", "text": "开往 {pid.terminus}", "x": 0.97, "y": 0.5, "size": 0.62, "align": "right",
 *        "when": {"!=": [{"var": "pid.terminus"}, ""]}}
 *     ]
 *   }
 * }
 * </pre>
 *
 * <h2>一条面 = 数据 + 逻辑 + 几何</h2>
 * <ul>
 *   <li>{@code elements} —— 画什么（元素类型与画法见 {@code MmtrFaceElements}）；</li>
 *   <li>{@code when}（每个元素）与 {@code require}（整块面）—— 什么时候画，方言是
 *       {@link MmtrFaceLogic} 的 JSONLogic 子集；</li>
 *   <li>{@code vars} —— 给条件起名字（求值一次，元素里就能 {@code {"var": "arriving"}}）；</li>
 *   <li>{@code text} 里的 {@code {字段}} 与 {@code {字段|过滤器}} —— 见 {@link MmtrFaceText}；</li>
 *   <li>几何一律是**比例**（{@code x}/{@code y}/{@code w}/{@code h}/{@code size} 相对牌面宽高），
 *       所以同一份文档能贴到任何尺寸的牌上。</li>
 * </ul>
 *
 * <h2>★ 旧段是"糖"，不是另一套系统</h2>
 * <p>{@code pid}/{@code next} 两个老段（notes/358）由 {@link #builtinPid} **翻译成**面文档：
 * 每一行 → 一个 {@code text} 元素，字号/位置/前缀/对齐逐字照搬，连"取不到内容的行不画"
 * 都翻成等价的 {@code when}。于是"老包一个字节都不用改，行为逐字不变"，而新系统里只有**一条**
 * 渲染路径（旧版式类只留作等价性用例的对照物）。</p>
 *
 * <h2>v2（F3）：多页、动画、重复、整面姿态</h2>
 * <p>F0 只支持 {@code elements} + {@code require} + {@code vars} + 每元素 {@code when}。F3 加上
 * {@code pages}/{@code pageSeconds}/{@code pageExpr}（多页轮转）、每元素 {@code anim}
 * （闪烁/走马灯/淡入淡出/自转，见 {@link MmtrFaceAnim}）、{@code forEach}（按列表重复）、
 * {@code roll}/{@code tilt}/{@code drum}（整面滚转/仰角/翻牌机），以及 {@code fps}（动画重画节拍）。
 * 键与缺省的权威表是 {@link MmtrFaceSchema}。v1 文档读起来逐字不变。</p>
 */
public final class MmtrFaceDocument {

	/** 一块面画在哪一侧（映射到 {@code MmtrPanelQuad.Side}；面不带游戏依赖，所以这里只存名字）。 */
	public enum Side {

		DRIVER, NORMAL, BOTH;

		public static Side parse(String value) {
			return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
				case "driver" -> DRIVER;
				case "both" -> BOTH;
				default -> NORMAL;
			};
		}
	}

	/**
	 * 新面文档的缺省底色：**透明**。牌底通常由模型自己给出（锚点只剥掉面，不剥掉材质），
	 * 什么都不写就铺一层不透明黑会把模型上的图案盖掉。要铺底就写 {@code background}。
	 */
	private static final int FACE_DEFAULT_BACKGROUND = 0;
	/** 旧 {@code pid}/{@code next} 段的缺省底色（= notes/357 那套写死的深色牌底）。 */
	private static final int LEGACY_DEFAULT_BACKGROUND = 0xFF101418;
	private static final int DEFAULT_TEXT = 0xFFF2F4F6;
	/** 文字最多占牌宽的多少（超了等比缩小）；{@code shrinkToFit} 写 0 = 不缩。 */
	public static final double DEFAULT_SHRINK_TO_FIT = 0.90;
	/** {@code forEach} 的类型名。 */
	public static final String FOREACH_TYPE = "foreach";
	/** {@code forEach} 一层最多展开几项（作者写 {@code limit:0} = 一项都不画）。 */
	public static final int MAX_FOREACH_ITEMS = 256;
	/** {@code forEach} 嵌套层数上限（不可信内容：套娃式重复会指数爆炸）。 */
	public static final int MAX_FOREACH_DEPTH = 4;
	/** 一页最多画几个元素（展开之后；超了截断 —— 上限是"别让一份坏文档把一帧拖死"）。 */
	public static final int MAX_DRAWABLES = 512;
	/** 旧 pid 版式的两个字号比例（notes/357 那套写死的排版）。 */
	private static final double LEGACY_MAIN_SIZE = 0.52;
	private static final double LEGACY_SUB_SIZE = 0.24;

	/**
	 * 一个元素。几何与文本是**通用**的（每种类型都用得上），类型专属的键（{@code gauge} 的
	 * {@code start/end/max}、{@code roundRect} 的 {@code radius}…）留在 {@link #raw} 里由画法自己读。
	 *
	 * @param type  类型（{@code text}/{@code rect}/…；见 {@code MmtrFaceElements} 的注册表）
	 * @param when  这个元素的显示条件（{@code null} = 总是画）
	 * @param x     水平位置（0..1 牌宽）
	 * @param y     垂直位置（0..1 牌高，0 = 牌底）
	 * @param w     宽（0..1 牌宽；图形元素用）
	 * @param h     高（0..1 牌高；图形元素用）
	 * @param size  字高（0..1 牌高；文本元素用）
	 * @param color 颜色；0 = 用面文档的 {@code textColor}
	 * @param align 水平对齐（left/center/right）
	 * @param text  文本模板（{@code {字段}} / {@code {字段|过滤器}} / 字面量）
	 * @param raw   原始对象（类型专属的键）
	 * @param anim  逐帧动画（{@code null} = 不动；见 {@link MmtrFaceAnim}）
	 * @param children {@code forEach} 的子元素表（其余类型为空表）
	 */
	public record Element(String type, JsonElement when, double x, double y, double w, double h, double size, int color, String align, String text, JsonObject raw, MmtrFaceAnim anim, List<Element> children) {

		/**
		 * 读一个数字键：元素里没写就用**键表里的缺省**（{@link MmtrFaceSchema}），表里也没有才用 {@code fallback}。
		 *
		 * <p>缺省只有一处事实，是"工作室属性面板里显示的那个 0.44"和"引擎真的用的 0.44"必然是同一个数
		 * 的唯一办法（F3 之前画法里各写一份常量，两边迟早分家）。</p>
		 */
		public double number(String key, double fallback) {
			final JsonElement element = raw.get(key);
			return element == null || element.isJsonNull() ? MmtrFaceSchema.defaultNumberOf(type, key, fallback) : element.getAsDouble();
		}

		/** 读一个文本键（缺省同样走键表）。 */
		public String string(String key, String fallback) {
			final JsonElement element = raw.get(key);
			return element == null || element.isJsonNull() ? MmtrFaceSchema.defaultStringOf(type, key, fallback) : element.getAsString();
		}

		/** 读一个布尔键（缺省 = 键表里的 {@code true}/{@code false}）。 */
		public boolean flag(String key, boolean fallback) {
			final JsonElement element = raw.get(key);
			if (element == null || element.isJsonNull()) {
				final String declared = MmtrFaceSchema.defaultJsonOf(type, key);
				return declared == null ? fallback : Boolean.parseBoolean(declared);
			}
			return element.getAsBoolean();
		}

		/** 这个元素要画的话，会画出来的字（不是文本元素 / 条件不成立 / 解析为空 ⇒ 空串）。 */
		public String resolveText(Object data) {
			return resolveText(data, 0);
		}

		/** 同上，带时钟（{@code blink} 灭着的这一瞬间算"不画"）。 */
		public String resolveText(Object data, long timeMs) {
			if (!"text".equals(type)) {
				return "";
			}
			// 没有 when = 总是画（★ 不能把"没有条件"当成"条件不成立"）
			if (when != null && !MmtrFaceLogic.truthy(MmtrFaceLogic.eval(when, data))) {
				return "";
			}
			if (anim != null && !anim.visible(timeMs)) {
				return "";
			}
			return MmtrFaceText.resolve(text, data);
		}
	}

	private final String id;
	private final int background;
	private final int textColor;
	private final int pxPerMetre;
	private final Side side;
	private final JsonElement require;
	private final JsonObject vars;
	private final List<Element> elements;
	private final List<Page> pages;
	private final double pageSeconds;
	private final JsonElement pageExpr;
	private final int fps;
	private final double roll;
	private final double tilt;
	private final Drum drum;

	private MmtrFaceDocument(String id, int background, int textColor, int pxPerMetre, Side side, JsonElement require, JsonObject vars, List<Element> elements,
	                         List<Page> pages, double pageSeconds, JsonElement pageExpr, int fps, double roll, double tilt, Drum drum) {
		this.id = id;
		this.background = background;
		this.textColor = textColor;
		this.pxPerMetre = pxPerMetre;
		this.side = side;
		this.require = require;
		this.vars = vars;
		this.elements = elements;
		this.pages = pages;
		this.pageSeconds = pageSeconds;
		this.pageExpr = pageExpr;
		this.fps = fps;
		this.roll = roll;
		this.tilt = tilt;
		this.drum = drum;
	}

	/** 一页：一个可选的页名、一个可选的门、一串元素。 */
	public record Page(String name, JsonElement require, List<Element> elements) {
	}

	/**
	 * 翻牌机的姿态参数（{@link #drum()}）：把各页贴到一个 {@code count} 面棱柱上，转过去换页。
	 *
	 * @param count        面数（2..8）
	 * @param turnFraction 一个换页周期里用来翻的比例（其余时间停住）
	 * @param radiusM      棱柱外接半径（米）；0 = 用正棱柱的 {@code w / (2·tan(π/N))}
	 */
	public record Drum(int count, double turnFraction, double radiusM) {
	}

	/**
	 * 计划要画的一个元素 + 它自己那份数据（{@code forEach} 把每一项绑进数据的副本里）。
	 *
	 * @param element 元素
	 * @param data    这个元素求值用的数据（已并进文档 {@code vars}、以及所在 {@code forEach} 的项/下标）
	 * @param index   在计划里的次序（日志与"只提示一次"的账用它）
	 */
	public record Drawable(Element element, Object data, int index) {
	}

	/** 文档身份（进重画签名）：作者改了文档、或换了车型，都会变。 */
	public String id() {
		return id;
	}

	public int background() {
		return background;
	}

	/** 文本元素的缺省颜色（元素自己写了 {@code color} 就用元素的）。 */
	public int textColor() {
		return textColor;
	}

	/** 想要的像素密度（px/m）；0 = 用调用方的默认值。 */
	public int pxPerMetre() {
		return pxPerMetre;
	}

	public Side side() {
		return side;
	}

	public List<Element> elements() {
		return elements;
	}

	/** 各页。没写 {@code pages} 时 = **唯一的一页**，内容就是 {@code elements}（v1 文档的形态）。 */
	public List<Page> allPages() {
		return pages != null ? pages : List.of(new Page("", null, elements));
	}

	public int pageCount() {
		return allPages().size();
	}

	/**
	 * 同时写了 {@code pages} 与顶层 {@code elements} ⇒ **后者被忽略**（{@code pages} 说了算）。
	 *
	 * <p>不报错也不合并：合并会让"这一页到底画了什么"变得要靠心算。画法与工作室各提示一次。</p>
	 */
	public boolean ignoredTopLevelElements() {
		return pages != null && !pages.isEmpty() && !elements.isEmpty();
	}

	/** 自动轮转周期（秒）；0 = 不自动转。 */
	public double pageSeconds() {
		return pageSeconds;
	}

	/** 指定当前页的表达式；{@code null} = 没写。 */
	public JsonElement pageExpr() {
		return pageExpr;
	}

	/** 动画重画节拍（1..30；越界用缺省 {@link MmtrFaceAnim#DEFAULT_FPS}）。 */
	public int fps() {
		return fps < 1 || fps > MmtrFaceAnim.MAX_FPS ? MmtrFaceAnim.DEFAULT_FPS : fps;
	}

	/** 整面绕自身法线滚转（度）。 */
	public double roll() {
		return roll;
	}

	/** 整面绕自身水平轴抬起（度）。 */
	public double tilt() {
		return tilt;
	}

	/** 翻牌机参数；{@code null} = 不是翻牌机。 */
	public Drum drum() {
		return drum;
	}

	/** 会不会自己换页（多页 + 有时长 + 没有 {@code pageExpr} 指定）。 */
	public boolean autoRotating() {
		return allPages().size() > 1 && pageExpr == null && pageSeconds > 0;
	}

	/**
	 * 这份文档有没有**随时间变**的东西（动画 / 自动轮转 / 翻牌机的翻转）。
	 *
	 * <p>重画签名按它决定要不要带时间桶（{@link MmtrFaceAnim#bucket}）：没有就用 F0 那套
	 * "数据不变就不重画"，一个字都不多画。</p>
	 */
	public boolean animated() {
		if (autoRotating()) {
			return true;
		}
		for (final Page page : allPages()) {
			if (hasAnim(page.elements())) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasAnim(List<Element> elements) {
		for (final Element element : elements) {
			if (element.anim() != null && element.anim().animates()) {
				return true;
			}
			if (hasAnim(element.children())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 现在该画哪一页（下标）。
	 *
	 * <p>优先级：{@code pageExpr} 有值 → 用它（数 = 下标，**回绕**；字 = 页名）；否则多页 + {@code pageSeconds > 0}
	 * → 按时钟轮转；否则第 0 页。求值失败 / 找不到页名 ⇒ 第 0 页（资源包里的错不该让整块牌消失）。</p>
	 */
	public int pageIndex(Object data, long timeMs) {
		final List<Page> pages = allPages();
		if (pages.size() <= 1) {
			return 0;
		}
		if (pageExpr != null) {
			final Object value;
			try {
				value = MmtrFaceLogic.eval(pageExpr, data);
			} catch (RuntimeException e) {
				return 0;
			}
			if (value instanceof Number number) {
				return wrap(number.intValue(), pages.size());
			}
			if (value instanceof String name && !name.isEmpty()) {
				for (int i = 0; i < pages.size(); i++) {
					if (name.equals(pages.get(i).name())) {
						return i;
					}
				}
				try {
					return wrap(Integer.parseInt(name.trim()), pages.size());
				} catch (NumberFormatException e) {
					return 0;
				}
			}
			return 0;
		}
		if (pageSeconds > 0) {
			final double seconds = Math.max(0, timeMs) / 1000.0;
			return (int) (Math.floor(seconds / pageSeconds) % pages.size());
		}
		return 0;
	}

	/**
	 * 当前这一页的周期走到哪儿了（0..1）—— 翻牌机用它算翻转角度。
	 *
	 * <p>自动轮转 = 在本页周期里的比例；{@code pageExpr} 指定页（或根本不自动转）⇒ **0**
	 * （= 周期刚开头，还没开始翻 —— 于是翻牌机就停在你指定的那一页上，不翻滚）。</p>
	 */
	public double pageClockFraction(long timeMs) {
		if (!autoRotating()) {
			return 0;
		}
		final double seconds = Math.max(0, timeMs) / 1000.0;
		return (seconds % pageSeconds) / pageSeconds;
	}

	/**
	 * **这一帧到底要画哪些元素**（把 {@code pages} 选页与 {@code forEach} 展开都算完）。
	 *
	 * <p>画法拿到的每一条都带着**自己那份数据** —— {@code forEach} 把每一项链进数据的副本
	 * （{@code as}/{@code index}），于是嵌套元素里的 {@code {"var":"call"}} 直接能用，
	 * 而画法本身完全不知道"重复"这件事。</p>
	 *
	 * <p>整块面的 {@code require} **不在这里**判（那是"挂不挂牌"的运行期决定，由调用方先问
	 * {@link #visible}）；页自己的 {@code require} 在这里判 —— 前者是"这列车有没有这块牌"，
	 * 后者是"这一页现在该不该出现"，两件事。</p>
	 */
	public List<Drawable> plan(Object data, long timeMs) {
		return planPage(-1, data, timeMs);
	}

	/**
	 * 同上，但**指定页**（{@code -1} = 按 {@link #pageIndex} 自动选）。
	 *
	 * <p>翻牌机要这条：它把每一面贴一页，同一帧里需要**每一页**的内容，而不是"当前那一页"。</p>
	 */
	public List<Drawable> planPage(int pageIndex, Object data, long timeMs) {
		final Object prepared = augment(data);
		final List<Page> pages = allPages();
		final int index = pageIndex < 0 ? pageIndex(prepared, timeMs) : wrap(pageIndex, pages.size());
		if (index < 0 || index >= pages.size()) {
			return List.of();
		}
		final Page page = pages.get(index);
		if (page.require() != null && !MmtrFaceLogic.truthy(MmtrFaceLogic.eval(page.require(), prepared))) {
			return List.of();
		}
		final List<Drawable> out = new ArrayList<>();
		expand(page.elements(), prepared, out, 0);
		return out;
	}

	/** {@code forEach} 展开：深度上限 {@link #MAX_FOREACH_DEPTH}、总元素上限 {@link #MAX_DRAWABLES}。 */
	private void expand(List<Element> source, Object data, List<Drawable> out, int depth) {
		for (final Element element : source) {
			if (out.size() >= MAX_DRAWABLES) {
				return;
			}
			if (FOREACH_TYPE.equals(element.type()) && depth < MAX_FOREACH_DEPTH) {
				final List<Object> items = itemsOf(element, data);
				final int limit = (int) Math.max(0, Math.min(MAX_FOREACH_ITEMS, element.number("limit", MAX_FOREACH_ITEMS)));
				for (int i = 0; i < items.size() && i < limit && out.size() < MAX_DRAWABLES; i++) {
					expand(element.children(), bind(data, element, items.get(i), i), out, depth + 1);
				}
				continue;
			}
			out.add(new Drawable(element, data, out.size()));
		}
	}

	/** {@code forEach} 的数据：{@code of} 表达式优先，其次 {@code var} 路径；**不是列表就画 0 个**。 */
	private static List<Object> itemsOf(Element element, Object data) {
		final JsonElement of = element.raw().get("of");
		final Object value = of != null && !of.isJsonNull()
			? MmtrFaceLogic.eval(of, data)
			: MmtrFaceLogic.lookup(element.string("var", ""), data);
		if (value instanceof List<?> list) {
			return new ArrayList<>(list);
		}
		// 不是列表（字段缺失 / 写成了标量）⇒ 一项都不画。这与"取不到内容的行不画"是同一条口径：
		// 缺失画成 0 项，而不是画一项空的。
		return List.of();
	}

	/** 把一项绑进数据的副本（{@code as} / {@code index}）。 */
	private static Object bind(Object data, Element element, Object item, int index) {
		if (!(data instanceof Map<?, ?> source)) {
			return data;
		}
		final Map<String, Object> copy = new LinkedHashMap<>();
		for (final Map.Entry<?, ?> entry : source.entrySet()) {
			copy.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		copy.put(element.string("as", "item"), item);
		copy.put(element.string("index", "index"), (long) index);
		return copy;
	}

	private static int wrap(int index, int count) {
		return ((index % count) + count) % count;
	}

	/**
	 * 这块面现在该不该画（{@code require} 门）。
	 *
	 * <p>与旧版式的门是同一条口径：不在作业单上（班次号空）⇒ 一块牌都不挂；下一站牌没有站名 ⇒
	 * 整块不画（{@code MmtrFaceDocumentTests} 拿 {@code MmtrPidText.lines} 逐格对照）。</p>
	 */
	public boolean visible(Object data) {
		return require == null || MmtrFaceLogic.truthy(MmtrFaceLogic.eval(require, data));
	}

	/**
	 * 这块面现在**会画出来的字**（自上而下，取不到内容的元素已剔除）—— 用例与日志读它。
	 * 与 {@code MmtrPidLayout.texts} 是同一个判据面。
	 */
	public List<String> texts(Object data) {
		return texts(data, 0);
	}

	/** 同上，带时钟（走马灯/闪烁在某一瞬间的字）。 */
	public List<String> texts(Object data, long timeMs) {
		final List<String> out = new ArrayList<>();
		for (final Drawable drawable : plan(data, timeMs)) {
			final String text = drawable.element().resolveText(drawable.data(), timeMs);
			if (!text.isEmpty()) {
				out.add(text);
			}
		}
		return out;
	}

	/**
	 * 把 {@code vars} 求值后并进数据：之后元素里的 {@code {"var": "arriving"}} 就能用。
	 *
	 * <p>数据不是 Map（或没有 vars）时原样返回 —— 不为了一个便利功能把数据拷来拷去。</p>
	 */
	public Object augment(Object data) {
		if (vars == null || vars.size() == 0 || !(data instanceof Map<?, ?> source)) {
			return data;
		}
		@SuppressWarnings("unchecked") final Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) source);
		for (final Map.Entry<String, JsonElement> entry : vars.entrySet()) {
			copy.put(entry.getKey(), MmtrFaceLogic.eval(entry.getValue(), source));
		}
		return copy;
	}

	/**
	 * 从锚点 JSON 的 {@code faces} 段里取一块面的文档；没有这一段（老包）返回 {@code null} ——
	 * 由调用方决定要不要退回内置文档（{@link #builtinPid}）。
	 */
	public static MmtrFaceDocument fromAnchors(String vehicleId, String faceName, String anchorFileText) {
		final JsonObject face = faceSection(anchorFileText, faceName);
		if (face == null) {
			return null;
		}
		try {
			return parseObject(vehicleId + "@" + faceName + "@" + anchorFileText.hashCode(), face);
		} catch (Exception e) {
			// 坏文档不该让车画不出来：退回 null，调用方按"这块面没有文档"处理（并已由 parseObject 记日志）
			return null;
		}
	}

	/** 解析一个面对象（{@code faces[name]} 的内容）。坏的键按缺省值处理，坏的元素丢掉。 */
	private static MmtrFaceDocument parseObject(String id, JsonObject object) {
		final int background = parseColor(string(object, "background", ""), FACE_DEFAULT_BACKGROUND);
		final int textColor = parseColor(string(object, "textColor", ""), DEFAULT_TEXT);
		final int pxPerMetre = Math.max(0, (int) getDouble(object, "pxPerMetre", 0));
		final Side side = Side.parse(string(object, "side", "normal"));
		final JsonElement require = logic(object, "require");
		final JsonObject vars = object.get("vars") != null && object.get("vars").isJsonObject() ? object.getAsJsonObject("vars") : null;
		final List<Element> elements = parseElements(object.getAsJsonArray("elements"));
		final List<Page> pages = new ArrayList<>();
		final JsonArray pageArray = object.getAsJsonArray("pages");
		if (pageArray != null) {
			for (final JsonElement element : pageArray) {
				if (element.isJsonObject()) {
					final JsonObject page = element.getAsJsonObject();
					pages.add(new Page(string(page, "name", ""), logic(page, "require"), parseElements(page.getAsJsonArray("elements"))));
				}
			}
		}
		final double pageSeconds = Math.max(0, getDouble(object, "pageSeconds", MmtrFaceSchema.defaultNumberOf("document", "pageSeconds", 6)));
		final int fps = (int) getDouble(object, "fps", MmtrFaceSchema.defaultNumberOf("document", "fps", MmtrFaceAnim.DEFAULT_FPS));
		return new MmtrFaceDocument(id, background, textColor, pxPerMetre, side, require, vars, elements,
			pages.isEmpty() ? null : pages, pageSeconds, logic(object, "pageExpr"), fps,
			getDouble(object, "roll", 0), getDouble(object, "tilt", 0), parseDrum(object.get("drum")));
	}

	/** {@code drum} 段（{@code null} = 不是翻牌机）。面数钳 2..8 —— 一面两块牌不是翻牌机，九面看不清。 */
	private static Drum parseDrum(JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		final JsonObject raw = element.getAsJsonObject();
		final int count = (int) clamp(getDouble(raw, "count", MmtrFaceSchema.defaultNumberOf("drum", "count", 2)), 2, 8);
		final double turnFraction = clamp(getDouble(raw, "turnFraction", MmtrFaceSchema.defaultNumberOf("drum", "turnFraction", 0.25)), 0, 1);
		final double radiusM = Math.max(0, getDouble(raw, "radiusM", 0));
		return new Drum(count, turnFraction, radiusM);
	}

	/** 一串元素（顶层与 {@code forEach} 的子表共用这一条路）。 */
	private static List<Element> parseElements(JsonArray array) {
		final List<Element> elements = new ArrayList<>();
		if (array == null) {
			return elements;
		}
		for (final JsonElement element : array) {
			if (!element.isJsonObject()) {
				continue;
			}
			final Element parsed = element(element.getAsJsonObject());
			if (parsed != null) {
				elements.add(parsed);
			}
		}
		return elements;
	}

	/**
	 * 一个元素对象 → {@link Element}；没有 {@code type}（或空）⇒ {@code null}（丢掉）。
	 *
	 * <p>几何与颜色的缺省**从键表读**（{@link MmtrFaceSchema}），所以"面板上显示的缺省"与
	 * "引擎真的用的缺省"是同一个数。</p>
	 */
	private static Element element(JsonObject raw) {
		final String type = string(raw, "type", "").trim().toLowerCase(Locale.ROOT);
		if (type.isEmpty()) {
			return null;
		}
		final List<Element> children = FOREACH_TYPE.equals(type) ? parseElements(raw.getAsJsonArray("elements")) : List.of();
		return new Element(
			type,
			logic(raw, "when"),
			clamp01(getDouble(raw, "x", MmtrFaceSchema.defaultNumberOf(type, "x", 0.5))),
			clamp01(getDouble(raw, "y", MmtrFaceSchema.defaultNumberOf(type, "y", 0.5))),
			clamp01(getDouble(raw, "w", MmtrFaceSchema.defaultNumberOf(type, "w", 0))),
			clamp01(getDouble(raw, "h", MmtrFaceSchema.defaultNumberOf(type, "h", 0))),
			clamp(getDouble(raw, "size", MmtrFaceSchema.defaultNumberOf(type, "size", 0.4)), 0.02, 1.5),
			parseColor(string(raw, "color", ""), 0),
			string(raw, "align", MmtrFaceSchema.defaultStringOf(type, "align", "center")),
			string(raw, "text", ""),
			raw,
			MmtrFaceAnim.from(raw.get("anim")),
			children
		);
	}

	/**
	 * 旧 {@code pid}/{@code next} 段 → 内置面文档（**逐字等价**于 {@code MmtrPidLayout}，见用例）。
	 *
	 * <p>行为逐条照搬：段缺失 ⇒ 默认两行版式；既没 {@code field} 也没 {@code text} 的行丢掉；
	 * {@code x}/{@code y} 钳到 0..1、{@code size} 钳到 0.02..1.5；{@code prefix}/{@code suffix}
	 * 只在字段**有值**时才写出来（否则回库趟会挂一个孤零零的"开往"）。</p>
	 */
	public static MmtrFaceDocument builtinPid(MmtrPidText.Board board, String vehicleId, String anchorFileText) {
		final String sectionKey = board == MmtrPidText.Board.DESTINATION ? "pid" : "next";
		final JsonObject section = objectSection(anchorFileText, sectionKey);
		final String id = vehicleId + "@builtin-" + sectionKey + "@" + (anchorFileText == null ? 0 : anchorFileText.hashCode());

		if (section == null) {
			return new MmtrFaceDocument(id, LEGACY_DEFAULT_BACKGROUND, DEFAULT_TEXT, 0, Side.NORMAL, gateFor(board), null, defaultElements(board),
			null, 0, null, MmtrFaceAnim.DEFAULT_FPS, 0, 0, null);
		}

		final int background = parseColor(string(section, "background", ""), LEGACY_DEFAULT_BACKGROUND);
		final int textColor = parseColor(string(section, "textColor", ""), DEFAULT_TEXT);
		final int pxPerMetre = Math.max(0, (int) getDouble(section, "pxPerMetre", 0));
		final List<Element> elements = new ArrayList<>();

		final JsonArray rows = section.getAsJsonArray("rows");
		if (rows != null) {
			for (final JsonElement row : rows) {
				if (!row.isJsonObject()) {
					continue;
				}
				final Element element = legacyRow(row.getAsJsonObject());
				if (element != null) {
					elements.add(element);
				}
			}
		}
		if (elements.isEmpty()) {
			// 空段 = 没配（与 MmtrPidLayout 的处理一致：用默认版式，而不是画一块空牌）
			return new MmtrFaceDocument(id, LEGACY_DEFAULT_BACKGROUND, DEFAULT_TEXT, 0, Side.NORMAL, gateFor(board), null, defaultElements(board),
			null, 0, null, MmtrFaceAnim.DEFAULT_FPS, 0, 0, null);
		}
		return new MmtrFaceDocument(id, background, textColor, pxPerMetre, Side.NORMAL, gateFor(board), null, elements,
			null, 0, null, MmtrFaceAnim.DEFAULT_FPS, 0, 0, null);
	}

	/** 一行的旧版式 → 一个 {@code text} 元素。 */
	private static Element legacyRow(JsonObject row) {
		final String field = string(row, "field", "").trim().toLowerCase(Locale.ROOT);
		final String literal = string(row, "text", "");
		final String prefix = string(row, "prefix", "");
		final String suffix = string(row, "suffix", "");
		final String path = legacyPath(field);

		final String template;
		final JsonElement when;
		if (!path.isEmpty()) {
			template = prefix + "{" + path + "}" + suffix;
			// ★ 只在字段有值时整行才画：否则 prefix（"开往 "）会在没终点时单独留在牌上
			when = template.equals("{" + path + "}") ? null : whenPresent(path);
		} else if (!field.isEmpty()) {
			// 认不出的 field 名：老版式**保留**这一行（只是取不到字，因而不会画）。
			// 这里也保留（空模板），于是两份的"行数"逐行对得上 —— 少一行会让作者以为版式变了。
			template = "";
			when = null;
		} else if (!literal.isEmpty()) {
			template = literal;
			when = null;
		} else {
			// 既没字段也没字面量：这一行什么都写不出来 —— 丢掉（老版式也是丢掉）
			return null;
		}

		return new Element("text", when,
			clamp01(getDouble(row, "x", 0.5)),
			clamp01(getDouble(row, "y", 0.5)),
			0,
			0,
			clamp(getDouble(row, "size", 0.4), 0.02, 1.5),
			parseColor(string(row, "color", ""), 0),
			string(row, "align", "center"),
			template,
			row, null, List.of());
	}

	/** 旧字段名 → 面文档里的路径（与 {@code MmtrPidText.field} 认的别名同一套）。 */
	private static String legacyPath(String field) {
		return switch (field) {
			case "service", "number" -> "pid.service";
			case "terminus", "destination", "dest" -> "pid.terminus";
			case "next", "nextstation" -> "pid.next";
			default -> "";
		};
	}

	/**
	 * {@code {"!!": [{"var": path}]}} —— "这个字段**有值**"。
	 *
	 * <p>★ 为什么不是 {@code {"!=": [{"var": path}, ""]}}：快照里"取不到"是**不放进去**（不是放一个 null），
	 * 于是 {@code var} 得 null，而 {@code null != ""} 是**真** —— 那会让"不在作业单上"的车也挂牌
	 * （F0 第一版就是这么错的，用例 {@code theGateMatchesTheLegacyRuleOnEveryCase} 当场抓住）。
	 * 用真值判：null、空串都是假，有字才是真 —— 与"空串也算没有"是同一条口径。</p>
	 */
	private static JsonElement whenPresent(String path) {
		final JsonObject truthy = new JsonObject();
		final JsonArray operands = new JsonArray();
		final JsonObject variable = new JsonObject();
		variable.addProperty("var", path);
		operands.add(variable);
		truthy.add("!!", operands);
		return truthy;
	}

	/** 整块面的门：不在作业单上 ⇒ 不挂；下一站牌还要有站名。 */
	private static JsonElement gateFor(MmtrPidText.Board board) {
		final JsonElement service = whenPresent("pid.service");
		if (board != MmtrPidText.Board.NEXT_STATION) {
			return service;
		}
		final JsonObject both = new JsonObject();
		final JsonArray operands = new JsonArray();
		operands.add(service);
		operands.add(whenPresent("pid.next"));
		both.add("and", operands);
		return both;
	}

	/** 段没配时的默认两行版式（= notes/357 那套写死的排版）。 */
	private static List<Element> defaultElements(MmtrPidText.Board board) {
		final List<Element> elements = new ArrayList<>();
		if (board == MmtrPidText.Board.DESTINATION) {
			elements.add(defaultElement("{pid.service}", 0.21, LEGACY_SUB_SIZE));
			elements.add(defaultElement("{pid.terminus}", 0.65, LEGACY_MAIN_SIZE));
		} else {
			elements.add(defaultElement(MmtrPidText.NEXT_STATION_LABEL, 0.21, LEGACY_SUB_SIZE));
			elements.add(defaultElement("{pid.next}", 0.65, LEGACY_MAIN_SIZE));
		}
		return elements;
	}

	private static Element defaultElement(String template, double y, double size) {
		final JsonObject raw = new JsonObject();
		raw.addProperty("type", "text");
		raw.addProperty("text", template);
		raw.addProperty("x", 0.5);
		raw.addProperty("y", y);
		raw.addProperty("size", size);
		raw.addProperty("align", "center");
		return new Element("text", null, 0.5, y, 0, 0, size, 0, "center", template, raw, null, List.of());
	}

	private static JsonObject faceSection(String anchorFileText, String faceName) {
		final JsonObject faces = objectSection(anchorFileText, "faces");
		if (faces == null || faceName == null) {
			return null;
		}
		final JsonElement face = faces.get(faceName);
		return face != null && face.isJsonObject() ? face.getAsJsonObject() : null;
	}

	private static JsonObject objectSection(String anchorFileText, String key) {
		if (anchorFileText == null || anchorFileText.isEmpty()) {
			return null;
		}
		try {
			final JsonElement root = JsonParser.parseString(anchorFileText);
			if (!root.isJsonObject()) {
				return null;
			}
			final JsonElement section = root.getAsJsonObject().get(key);
			return section != null && section.isJsonObject() ? section.getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * {@code #RRGGBB}, {@code #AARRGGBB} or {@code 0xRRGGBB}；不可用时用 {@code fallback}。
	 *
	 * <p>★ {@code "0"} 是**哨兵值**，不是"不透明黑"。文档里到处写着"0 = 用 textColor / 不铺底 /
	 * 全透明"，而按十六进制解析 {@code "0"} 会得到 {@code 0xFF000000}（不透明黑）—— 两套语义打架的结果
	 * 是作者写 {@code "background": 0} 得到一块黑板而不是"不铺底"（F3 收尾时工作室的绘制器把这条
	 * 抓出来了：它只能靠"把键删掉"绕过去）。要**不透明黑**请写 {@code #FF000000} 或 {@code 000000}。</p>
	 */
	public static int parseColor(String value, int fallback) {
		if (value == null || value.isEmpty()) {
			return fallback;
		}
		String hex = value.trim();
		if (hex.startsWith("#")) {
			hex = hex.substring(1);
		} else if (hex.startsWith("0x") || hex.startsWith("0X")) {
			hex = hex.substring(2);
		}
		if (hex.equals("0")) {
			return 0;
		}
		try {
			final long parsed = Long.parseLong(hex, 16);
			return hex.length() <= 6 ? (int) (0xFF000000L | parsed) : (int) parsed;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static String string(JsonObject object, String key, String fallback) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	/**
	 * 读一段**表达式**：{@code null} = 没有（键不在，或者显式写了 {@code null}）。
	 *
	 * <p>★ 显式的 JSON {@code null} 一律当"没写这个键"。这条规矩是 F3 的共享向量逼出来的：
	 * 工作室那边原来按"键在不在"判，于是作者写了 {@code "require": null}（模板里带出来的、或者
	 * 编辑中间态）就会**静默不画**，而 Java 这边当时是"求值 null = 假" —— 两边都错得不一样。
	 * 现在统一成一条：**JSON 里没有"有值但是 null"这种状态**，与"取不到的字段不放进去""空串算没有"
	 * 是同一条口径。</p>
	 */
	private static JsonElement logic(JsonObject object, String key) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? null : element;
	}

	private static double getDouble(JsonObject object, String key, double fallback) {
		final JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
	}

	private static double clamp01(double value) {
		return clamp(value, 0, 1);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}

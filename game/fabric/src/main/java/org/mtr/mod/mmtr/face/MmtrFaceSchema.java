package org.mtr.mod.mmtr.face;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 面文档的**机读键表**（notes/359 · F3）：文档 / 页 / 元素 / 动画各有哪些键、什么类型、缺省是什么。
 *
 * <h2>为什么要有这一张表</h2>
 * <p>面文档是给**别人**写的（车体作者、资源包作者），所以"有哪几个键"必须是**一份**事实，
 * 而不是散在三处（引擎里一个字面量、工作室里一个表单、校验脚本里一张清单）。三处各写一份的结果
 * 是可以预料的：作者写了 {@code colour}，引擎当没看见（画成黑的），工作室当没看见（预览正常）——
 * 于是"预览好好的，进游戏不对"，而这正是最难查的一类问题。</p>
 *
 * <p>所以：</p>
 * <ul>
 *   <li>引擎侧，元素的缺省值**从这张表读**（{@code Element.number/string/flag} 走
 *       {@link #defaultNumberOf}），表改了行为就跟着改，没有第二份常量；</li>
 *   <li>{@link #exportJson()} 导出 {@code mmtr/tools/face-studio/schema.json}，
 *       工作室的**属性面板是按它生成的** —— 加一个键，绘制器里就多一个输入框，不用改页面代码；</li>
 *   <li>不认识但写了的键，画的时候会**提示一次**（{@code MmtrFaceElements}）——
 *       拼错的键不再静默；</li>
 *   <li>三方对照由用例 {@code MmtrFaceSchemaTests} 钉住：Java 表 ↔ {@code schema.json} ↔
 *       {@code schema.mjs}（工作室/Node），少一条就红。</li>
 * </ul>
 *
 * <h2>元素类型清单住在这里</h2>
 * <p>{@link #ELEMENT_TYPES} 是纯数据层（{@code mmtr.face} 包）的事实，游戏侧的
 * {@code MmtrFaceElements.PAINTERS} 是它的**实现**：注册表少一个画法、或多了个没登记的画法，
 * {@code MmtrFaceToolingTests} 都会红（它读源码文本对照，与既有的"Java↔JS 两张清单一致"同一招）。</p>
 */
public final class MmtrFaceSchema {

	/** 导出路径（**工作区相对**；用例的工作目录是 {@code mmtr/game/fabric}，所以要往前三级）。 */
	public static final String EXPORT_PATH = "mmtr/tools/face-studio/schema.json";

	/** 一条键：名字 / 类型 / 缺省（JSON 字面量）/ 可取值（enum）/ 一句话说明。 */
	public record Key(String name, String type, String defaultJson, List<String> values, String doc) {

		static Key of(String name, String type, String defaultJson, List<String> values, String doc) {
			return new Key(name, type, defaultJson, values, doc);
		}

		/** JSON 里写出来的缺省值：字符串带引号，其余照抄。 */
		public String jsonDefault() {
			return "text".equals(type) || "template".equals(type) || "color".equals(type) || "enum".equals(type) ? "\"" + defaultJson + "\"" : defaultJson;
		}
	}

	/** 元素类型的清单（{@code MmtrFaceElements.PAINTERS} 是它的实现）。 */
	public static final List<String> ELEMENT_TYPES = List.of("text", "rect", "roundrect", "line", "circle", "arc", "gauge", "image", "foreach");

	public static final List<Key> DOCUMENT_KEYS;
	public static final List<Key> PAGE_KEYS;
	public static final List<Key> COMMON_KEYS;
	public static final List<Key> DRUM_KEYS;
	/** 元素类型 → 类型专属键。 */
	public static final Map<String, List<Key>> ELEMENT_KEYS;
	/** 动画名 → 键。 */
	public static final Map<String, List<Key>> ANIM_KEYS;
	static {
		DOCUMENT_KEYS = List.of(
			Key.of("background", "color", "0", null, "整面底色；0 = 不铺底（让模型材质透出来）"),
			Key.of("textColor", "color", "#FFF2F4F6", null, "文本与 gauge 的缺省颜色"),
			Key.of("pxPerMetre", "int", "0", null, "像素密度；0 = 用引擎缺省 512"),
			Key.of("side", "enum", "normal", values("driver", "normal", "both"), "画在法线那侧 / 司机那侧 / 两侧"),
			Key.of("require", "logic", "null", null, "整块面的门（不成立就一块都不画）"),
			Key.of("vars", "object", "{}", null, "给条件起名字（值是一条表达式）"),
			Key.of("fps", "int", "8", null, "动画重画节拍 1..30（没有动画时用不到）"),
			Key.of("elements", "elements", "[]", null, "元素表；没写 pages 时它就是第 0 页"),
			Key.of("pages", "pages", "null", null, "多页；写了它就忽略 elements"),
			Key.of("pageSeconds", "number", "6", null, "自动轮转周期（秒）；0 = 不自动转"),
			Key.of("pageExpr", "logic", "null", null, "指定当前页（数 = 下标，字 = 页名）"),
			Key.of("roll", "number", "0", null, "整面绕自身法线滚转（度）"),
			Key.of("tilt", "number", "0", null, "整面绕自身水平轴抬起（度）"),
			Key.of("drum", "object", "null", null, "翻牌机：把各页贴到 N 面棱柱上转")
		);

		PAGE_KEYS = List.of(
			Key.of("name", "text", "", null, "页名（给 pageExpr 用字名）"),
			Key.of("require", "logic", "null", null, "这一页的门"),
			Key.of("elements", "elements", "[]", null, "这一页的元素表")
		);

		COMMON_KEYS = List.of(
			Key.of("type", "enum", "text", values(ELEMENT_TYPES), "元素类型"),
			Key.of("when", "logic", "null", null, "这个元素的显示条件"),
			Key.of("x", "number", "0.5", null, "水平位置（0..1 牌宽）"),
			Key.of("y", "number", "0.5", null, "竖直位置（0 = 牌底）"),
			Key.of("w", "number", "0", null, "宽（0..1 牌宽）"),
			Key.of("h", "number", "0", null, "高（0..1 牌高）"),
			Key.of("size", "number", "0.4", null, "字高（0..1 牌高）"),
			Key.of("color", "color", "0", null, "颜色；0 = 用文档 textColor（只对 text/gauge）"),
			Key.of("align", "enum", "center", values("left", "center", "right"), "文本水平对齐"),
			Key.of("text", "template", "", null, "文本模板：{字段} / {字段|过滤器}"),
			Key.of("rotate", "number", "0", null, "绕自身锚点在面内转（度，顺时针为正）"),
			Key.of("opacity", "number", "1", null, "整元素透明度 0..1"),
			Key.of("anim", "object", "null", null, "逐帧动画（见 anim 段）")
		);

		DRUM_KEYS = List.of(
			Key.of("count", "int", "2", null, "棱柱面数 2..8（第 i 面贴第 i 页）"),
			Key.of("turnFraction", "number", "0.25", null, "一个周期里用于翻转的比例"),
			Key.of("radiusM", "number", "0", null, "棱柱外接半径（米）；0 = 正棱柱")
		);

		final Map<String, List<Key>> elements = new LinkedHashMap<>();
		elements.put("text", List.of(Key.of("shrinkToFit", "number", "0.9", null, "超宽等比缩小；0 = 不缩")));
		elements.put("rect", List.of());
		elements.put("roundrect", List.of(Key.of("radius", "number", "0.08", null, "圆角半径（牌高比例）")));
		elements.put("line", List.of(
			Key.of("x2", "number", "null", null, "终点水平位置（缺省 = 与 x 相同）"),
			Key.of("y2", "number", "null", null, "终点竖直位置（缺省 = 与 y 相同）"),
			Key.of("width", "number", "0.03", null, "线宽（牌高比例）")
		));
		elements.put("circle", List.of(Key.of("radius", "number", "0.1", null, "半径（牌高比例）")));
		elements.put("arc", List.of(
			Key.of("radius", "number", "0.4", null, "半径（牌高比例）"),
			Key.of("start", "number", "0", null, "起始角（度，逆时针从 +X 起）"),
			Key.of("end", "number", "360", null, "结束角"),
			Key.of("width", "number", "0.01", null, "线宽（牌高比例）")
		));
		elements.put("gauge", List.of(
			Key.of("radius", "number", "0.44", null, "表盘半径（牌高比例）"),
			Key.of("start", "number", "225", null, "刻度起始角"),
			Key.of("end", "number", "-45", null, "刻度结束角"),
			Key.of("width", "number", "0", null, "表盘线宽（别名 lineWidth）"),
			Key.of("lineWidth", "number", "0.004", null, "表盘线宽"),
			Key.of("max", "number", "160", null, "指针满偏对应的值"),
			Key.of("ticks", "number", "0", null, "刻度格数；0 = 不画刻度"),
			Key.of("tickLength", "number", "0.12", null, "刻度长度（牌高比例）"),
			Key.of("labelEvery", "number", "0", null, "每隔几格写数字；0 = 不写"),
			Key.of("labelSize", "number", "0.13", null, "数字字高（牌高比例）"),
			Key.of("labelColor", "color", "0", null, "数字颜色；0 = 跟表盘同色"),
			Key.of("needle", "logic", "null", null, "指针值表达式（缺省车速 km/h）"),
			Key.of("needleColor", "color", "#FFFF3B30", null, "指针颜色"),
			Key.of("needleWidth", "number", "0.05", null, "指针宽度（牌高比例）"),
			Key.of("needleLength", "number", "0.94", null, "指针长度（表盘半径比例）")
		));
		elements.put("image", List.of(
			Key.of("src", "text", "", null, "图片标识：mmtr:vehicle/face/x.png"),
			Key.of("fit", "enum", "stretch", values("stretch", "contain", "cover"), "装进 w×h 的方式"),
			Key.of("alpha", "number", "1", null, "透明度 0..1"),
			Key.of("tint", "color", "#FFFFFF", null, "色调（乘到像素上）")
		));
		elements.put("foreach", List.of(
			Key.of("var", "text", "", null, "数据路径（列表）"),
			Key.of("of", "logic", "null", null, "数据表达式（与 var 二选一）"),
			Key.of("as", "text", "item", null, "每一项绑到哪个名字"),
			Key.of("index", "text", "index", null, "下标绑到哪个名字"),
			Key.of("limit", "int", "32", null, "最多画几项 0..256"),
			Key.of("elements", "elements", "[]", null, "每一项要画的元素")
		));
		ELEMENT_KEYS = java.util.Collections.unmodifiableMap(elements);

		final Map<String, List<Key>> anims = new LinkedHashMap<>();
		anims.put("blink", List.of(
			Key.of("kind", "enum", "blink", values("blink"), "动画名"),
			Key.of("onMs", "number", "600", null, "亮多久（毫秒）"),
			Key.of("offMs", "number", "600", null, "灭多久（毫秒）")
		));
		anims.put("marquee", List.of(
			Key.of("kind", "enum", "marquee", values("marquee"), "动画名"),
			Key.of("spanMs", "number", "4000", null, "走一趟多久（毫秒）"),
			Key.of("direction", "enum", "left", values("left", "right"), "走向"),
			Key.of("gap", "number", "0.3", null, "接缝（牌高比例）")
		));
		anims.put("fade", List.of(
			Key.of("kind", "enum", "fade", values("fade"), "动画名"),
			Key.of("spanMs", "number", "1500", null, "一个来回多久（毫秒）"),
			Key.of("min", "number", "0.25", null, "最暗到多少（0..1）")
		));
		anims.put("spin", List.of(
			Key.of("kind", "enum", "spin", values("spin"), "动画名"),
			Key.of("spanMs", "number", "2000", null, "转一圈多久（毫秒）")
		));
		ANIM_KEYS = java.util.Collections.unmodifiableMap(anims);
	}

	private MmtrFaceSchema() {
	}

	/** 认得的动画名。 */
	public static Set<String> animKinds() {
		return new LinkedHashSet<>(ANIM_KEYS.keySet());
	}

	/** 这个类型认得的全部键（公共 + 专属）。 */
	public static Set<String> knownKeys(String type) {
		final Set<String> names = new LinkedHashSet<>();
		COMMON_KEYS.forEach(key -> names.add(key.name()));
		final List<Key> extra = ELEMENT_KEYS.get(type == null ? "" : type.toLowerCase(Locale.ROOT));
		if (extra != null) {
			extra.forEach(key -> names.add(key.name()));
		}
		return names;
	}

	public static List<Key> elementKeys(String type) {
		final List<Key> keys = new ArrayList<>(COMMON_KEYS);
		final List<Key> extra = ELEMENT_KEYS.get(type == null ? "" : type.toLowerCase(Locale.ROOT));
		if (extra != null) {
			keys.addAll(extra);
		}
		return keys;
	}

	/** 这一段认得的所有键：{@code document}/{@code page}/{@code common}/{@code drum}/元素类型/动画名。 */
	public static List<Key> keysOf(String scope) {
		final String name = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
		switch (name) {
			case "document":
				return DOCUMENT_KEYS;
			case "page":
				return PAGE_KEYS;
			case "common":
				return COMMON_KEYS;
			case "drum":
				return DRUM_KEYS;
			default:
				break;
		}
		final List<Key> anim = ANIM_KEYS.get(name);
		if (anim != null) {
			return anim;
		}
		return elementKeys(name);
	}

	/** 元素某一个键的缺省（JSON 字面量）；没登记过返回 {@code null}。 */
	public static String defaultJsonOf(String scope, String key) {		for (final Key candidate : keysOf(scope)) {
			if (candidate.name().equals(key)) {
				return candidate.defaultJson();
			}
		}
		return null;
	}

	/**
	 * 元素某一个键的**数值**缺省 —— 引擎侧画法读的就是它（所以表是缺省值的唯一来源）。
	 * 没登记 / 不是数字 ⇒ 用调用方给的 {@code fallback}（类型专属的画法仍然说得清自己兜底是多少）。
	 */
	public static double defaultNumberOf(String scope, String key, double fallback) {
		final String value = defaultJsonOf(scope, key);
		if (value == null) {
			return fallback;
		}
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** 同上，字符串键。 */
	public static String defaultStringOf(String scope, String key, String fallback) {
		final String value = defaultJsonOf(scope, key);
		return value == null ? fallback : value;
	}

	/** 机读键表的 JSON（工作室按它生成属性面板；用例逐字比对）。 */
	public static String exportJson() {
		final StringBuilder builder = new StringBuilder();
		builder.append("{\n");
		builder.append("  \"version\": 2,\n");
		builder.append("  \"note\": \"MMTR 车辆动态面 · 面文档键表（v2）。由 MmtrFaceSchema.exportJson() 生成，用例 MmtrFaceSchemaTests 逐字比对 —— 不要手改；缺键时用例会写出 schema.json.actual，拷过来即可。\",\n");
		builder.append("  \"exportPath\": \"").append(EXPORT_PATH).append("\",\n");
		builder.append("  \"elementTypes\": ").append(stringArray(ELEMENT_TYPES)).append(",\n");
		builder.append("  \"animKinds\": ").append(stringArray(animKinds())).append(",\n");
		builder.append("  \"sections\": [\n");
		final List<String> sections = new ArrayList<>();
		sections.add(section("document", "整块面的键（faces[name] 这一层）", DOCUMENT_KEYS));
		sections.add(section("page", "一页的键（pages[] 里的一项）", PAGE_KEYS));
		sections.add(section("common", "每个元素都认的键", COMMON_KEYS));
		sections.add(section("drum", "翻牌机的键（drum 段）", DRUM_KEYS));
		for (final Map.Entry<String, List<Key>> entry : ELEMENT_KEYS.entrySet()) {
			sections.add(section("element:" + entry.getKey(), "元素 " + entry.getKey() + " 的专属键", entry.getValue()));
		}
		for (final Map.Entry<String, List<Key>> entry : ANIM_KEYS.entrySet()) {
			sections.add(section("anim:" + entry.getKey(), "动画 " + entry.getKey() + " 的键", entry.getValue()));
		}
		builder.append(String.join(",\n", sections)).append('\n');
		builder.append("  ]\n");
		builder.append("}\n");
		return builder.toString();
	}

	private static String section(String name, String doc, List<Key> keys) {
		final StringBuilder builder = new StringBuilder();
		builder.append("    {\"name\": \"").append(name).append("\", \"doc\": \"").append(escape(doc)).append("\", \"keys\": [");
		for (int i = 0; i < keys.size(); i++) {
			final Key key = keys.get(i);
			if (i > 0) {
				builder.append(", ");
			}
			builder.append("{\"name\": \"").append(key.name()).append("\", \"type\": \"").append(key.type()).append("\", \"default\": ").append(key.jsonDefault());
			if (key.values() != null) {
				builder.append(", \"values\": ").append(stringArray(key.values()));
			}
			builder.append(", \"doc\": \"").append(escape(key.doc())).append("\"}");
		}
		return builder.append("]}").toString();
	}

	private static String stringArray(java.util.Collection<String> values) {
		final StringBuilder builder = new StringBuilder("[");
		int index = 0;
		for (final String value : values) {
			if (index++ > 0) {
				builder.append(", ");
			}
			builder.append('"').append(escape(value)).append('"');
		}
		return builder.append(']').toString();
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static List<String> values(String... values) {
		return List.of(values);
	}

	private static List<String> values(List<String> values) {
		return values;
	}
}

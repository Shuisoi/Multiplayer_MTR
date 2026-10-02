package org.mtr.mod.mmtr.face;

import org.mtr.mod.Init;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车辆动态面的**文本层**：模板 + 过滤器（notes/359）。
 *
 * <p>面文档里凡是"要写字"的地方都可以用模板：{@code "开往 {pid.terminus}"}。花括号里是
 * **数据路径**（与 {@code {"var": "…"}} 同一套路径，见 {@link MmtrFaceLogic#lookup}），
 * 竖线后面是**过滤器**：{@code "{speedKmh|num:1}"}。</p>
 *
 * <h2>支持的过滤器（这就是全部，多一个都要在这儿加）</h2>
 * <table>
 *   <tr><td>{@code upper} / {@code lower} / {@code trim}</td><td>大小写与去空白</td></tr>
 *   <tr><td>{@code int}</td><td>四舍五入到整数</td></tr>
 *   <tr><td>{@code num:N}</td><td>保留 N 位小数（N = 0..4）</td></tr>
 *   <tr><td>{@code pad:N}</td><td>前面补 0 到 N 位（班次号 {@code 00101} 这种）</td></tr>
 *   <tr><td>{@code len}</td><td>文本长度（数字变字符后数）</td></tr>
 *   <tr><td>{@code default:文本}</td><td>空值/取不到时用这个（{@code {holdReason|default:正常}}）</td></tr>
 * </table>
 *
 * <h2>两条口径</h2>
 * <ul>
 *   <li><b>取不到 = 空串</b>。模板不报错、不画 {@code null}；"这一行整行不画"由元素上的
 *       {@code when} 或 {@code hideWhenEmpty} 表达（判据在 {@code MmtrFaceDocument} 那层）。</li>
 *   <li><b>数字默认不写小数点后缀</b>：{@code 1.0} 写成 {@code 1}（{@code cat} 与模板同一口径）——
 *       否则牌上会出现"00101 次，时速 0.0"这种只有在作者故意要小数时才该出现的字。</li>
 * </ul>
 */
public final class MmtrFaceText {

	/** 花括号转义：{@code {{}} 写出一个字面量花括号。 */
	public static final String ESCAPE_OPEN = "{{";
	public static final String ESCAPE_CLOSE = "}}";

	/** 不认识的过滤器只提示一次（每个模板+过滤器名一条），免得每帧刷屏。 */
	private static final Set<String> WARNED_FILTERS = ConcurrentHashMap.newKeySet();

	/**
	 * 支持的过滤器名（给工具看的清单：web 工作室的补全、{@code verify_face.js} 的静态检查；
	 * {@code MmtrFaceToolingTests} 负责核对它与 JS 那份、与 {@link #applyFilter} 的 {@code case} 一致）。
	 *
	 * <p>这里就是全部：只有随包发行的这 8 个（没有"装了什么才多出来"的那种）。</p>
	 */
	public static Set<String> filters() {
		final java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
		java.util.Collections.addAll(names, "upper", "lower", "trim", "int", "num", "pad", "len", "default");
		return names;
	}

	private MmtrFaceText() {
	}

	/**
	 * 把模板里的 {@code {路径}} / {@code {路径|过滤器}} 换成数据里的值。
	 *
	 * @param template 模板（{@code null}/空 ⇒ 空串）
	 * @param data     数据（见 {@link MmtrFaceLogic#lookup}）
	 */
	public static String resolve(String template, Object data) {
		if (template == null || template.isEmpty()) {
			return "";
		}
		final StringBuilder out = new StringBuilder();
		int index = 0;
		while (index < template.length()) {
			final char character = template.charAt(index);
			if (character == '{') {
				if (template.startsWith(ESCAPE_OPEN, index)) {
					out.append('{');
					index += 2;
					continue;
				}
				final int close = template.indexOf('}', index + 1);
				if (close < 0) {
					// 没有闭合花括号：当作普通文本（不吞掉后半句）
					out.append(template, index, template.length());
					break;
				}
				out.append(resolvePlaceholder(template.substring(index + 1, close), data));
				index = close + 1;
			} else if (character == '}' && template.startsWith(ESCAPE_CLOSE, index)) {
				out.append('}');
				index += 2;
			} else {
				out.append(character);
				index++;
			}
		}
		return out.toString();
	}

	/** 一个占位符：{@code 路径} 或 {@code 路径|过滤器|过滤器}。 */
	private static String resolvePlaceholder(String placeholder, Object data) {
		final String[] parts = placeholder.split("\\|");
		final String path = parts[0].trim();
		Object value = path.isEmpty() ? data : MmtrFaceLogic.lookup(path, data);
		for (int i = 1; i < parts.length; i++) {
			value = applyFilter(value, parts[i].trim());
		}
		return MmtrFaceLogic.asString(value);
	}

	/**
	 * 单个过滤器。不认识的过滤器**原样返回**（并提示一次）——比静默换成空串好：
	 * 作者看到字还在，只是没按他想的格式化，配合日志就能定位。
	 */
	public static Object applyFilter(Object value, String filter) {
		if (filter == null || filter.isEmpty()) {
			return value;
		}
		final int colon = filter.indexOf(':');
		final String name = (colon < 0 ? filter : filter.substring(0, colon)).toLowerCase(Locale.ROOT);
		final String parameter = colon < 0 ? "" : filter.substring(colon + 1);

		return switch (name) {
			case "upper" -> MmtrFaceLogic.asString(value).toUpperCase(Locale.ROOT);
			case "lower" -> MmtrFaceLogic.asString(value).toLowerCase(Locale.ROOT);
			case "trim" -> MmtrFaceLogic.asString(value).trim();
			case "int" -> numberFilter(value, 0, true);
			case "num" -> numberFilter(value, parseDigits(parameter, 1), false);
			case "pad" -> pad(MmtrFaceLogic.asString(value), parseWidth(parameter));
			case "len" -> String.valueOf(MmtrFaceLogic.asString(value).length());
			case "default" -> MmtrFaceLogic.asString(value).isEmpty() ? parameter : value;
			default -> {
				if (WARNED_FILTERS.add(filter)) {
					Init.LOGGER.warn("[MMTR] 面文档用了不认识的过滤器「{}」（内置的见 MmtrFaceText.filters()；扩展的要看装没装那个模组）", filter);
				}
				yield value;
			}
		};
	}

	private static Object numberFilter(Object value, int digits, boolean round) {
		final Double number = MmtrFaceLogic.asNumber(value);
		if (number == null) {
			return value;
		}
		return round ? String.valueOf(Math.round(number)) : decimalText(number, digits);
	}

	/**
	 * 保留小数的文本。
	 *
	 * <p>{@code digits <= 0}（即 {@code num:0}）是**取整**，不是"退回通用文本" ——
	 * 2026-10-02 修：原来这里直接落到 {@link MmtrFaceLogic#asString}，于是 {@code {x|num:0}}
	 * 把 12.345 原样写出来（"保留 0 位小数"的口径反过来成了"不格式化"）。这条与 {@code pad:N} 一样，
	 * 是补 {@code MmtrFaceTextTests} 时抓出来的。</p>
	 */
	public static String decimalText(double value, int digits) {
		if (digits <= 0) {
			return String.format(Locale.ROOT, "%.0f", value);
		}
		return String.format(Locale.ROOT, "%." + Math.min(digits, 4) + "f", value);
	}

	private static String pad(String text, int width) {
		if (width <= text.length()) {
			return text;
		}
		final StringBuilder builder = new StringBuilder();
		for (int i = text.length(); i < width; i++) {
			builder.append('0');
		}
		return builder.append(text).toString();
	}

	private static int parseDigits(String parameter, int fallback) {
		try {
			return Math.max(0, Math.min(4, Integer.parseInt(parameter.trim())));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * {@code pad:N} 的**位宽**（与 {@link #parseDigits} 分开 —— 那是小数位，钳在 0..4）。
	 *
	 * <p>2026-10-02 修：原来两者共用同一个解析，于是 {@code pad:8} 实际只补到 4 位、
	 * {@code {x|pad:6}} 补不到 6 位。这条是前端移植时逐条对照才发现的 —— 共享向量只覆盖**算子**、
	 * 不覆盖**过滤器**，所以自检抓不住它；同批补了 {@code MmtrFaceTextTests} 把过滤器也钉住。</p>
	 */
	private static int parseWidth(String parameter) {
		try {
			return Math.max(0, Math.min(16, Integer.parseInt(parameter.trim())));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}

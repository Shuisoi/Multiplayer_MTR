package org.mtr.mod.mmtr.face;

import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 车辆动态面的**逻辑层**：一个 JSONLogic 子集（notes/359）。
 *
 * <h2>为什么是 JSONLogic，而不是自己发明一门语法</h2>
 * <p>动态面迟早要处理"什么条件显示什么、转多少度"这类业务，而且必须让**车体作者 / 资源包作者**
 * 写得出来。JSONLogic 是一份公开、有文档、多语言实现的规则格式：作者学的是它，生态里也有成熟实现
 * （将来想换成第三方库，**面文档一个字节都不用改**，只换这一层）。所以这里实现的是**规范的一个子集**，
 * 不是"我们自己的表达式语言"。</p>
 *
 * <p>反过来，为什么模组里不直接引一个表达式引擎/脚本引擎：</p>
 * <ul>
 *   <li><b>资源包是不可信内容</b>。它会从服务器、从别人的整合包里进到玩家客户端。声明式规则的求值器
 *       没有反射、没有 I/O、没有类加载，天然造不出沙箱逃逸；脚本引擎则把"恶意的包"变成"在你的
 *       客户端上执行任意代码"（方案 A 的边界，见 notes/359 §信任边界）。</li>
 *   <li><b>零依赖</b>：引擎与模组不因为一块水牌多背一个 jar。</li>
 *   <li><b>可证伪</b>：语义用共享测试向量 {@code tools/face-studio/conformance/logic.json} 钉住，
 *       Java 端与 web 工作室的 JS 端跑的是**同一份**向量。</li>
 * </ul>
 *
 * <h2>支持的算子（这就是"子集"的边界）</h2>
 * <table>
 *   <tr><td>{@code var}</td><td>{@code {"var": "pid.next"}}，支持点号路径、数组下标、缺省值
 *       {@code {"var": ["x", 0]}}；{@code ""} = 整个数据</td></tr>
 *   <tr><td>{@code if}</td><td>{@code {"if": [c1, v1, c2, v2, 兜底]}}</td></tr>
 *   <tr><td>{@code and}/{@code or}/{@code !}/{@code !!}</td><td>短路；返回**值**而不是 true/false
 *       （与 JSONLogic 一致）</td></tr>
 *   <tr><td>{@code ==}/{@code !=}/{@code ===}/{@code !==}</td><td>宽松/严格相等，见
 *       {@link #looseEquals}</td></tr>
 *   <tr><td>{@code <}/{@code <=}/{@code >}/{@code >=}</td><td>可连写（{@code [a,b,c]} = a&lt;b 且 b&lt;c）</td></tr>
 *   <tr><td>{@code +}/{@code -}/{@code *}/{@code /}/{@code %}</td><td>数值；{@code -} 单参数 = 取负；
 *       除零/非数值 ⇒ {@code null}（**不抛异常**，面只是少画一样东西）</td></tr>
 *   <tr><td>{@code min}/{@code max}</td><td>数值；忽略 null</td></tr>
 *   <tr><td>{@code cat}</td><td>字符串拼接</td></tr>
 *   <tr><td>{@code substr}</td><td>{@code [文本, 起点, 长度]}</td></tr>
 *   <tr><td>{@code in}</td><td>子串 / 数组成员</td></tr>
 *   <tr><td>{@code missing}/{@code missing_some}</td><td>缺哪些字段</td></tr>
 *   <tr><td>{@code ?:}</td><td>{@code [条件, 真值, 假值]}</td></tr>
 *   <tr><td>{@code some}/{@code all}/{@code none}/{@code filter}/{@code map}</td><td>数组遍历；
 *       谓词里的 {@code var} 相对于**当前元素**</td></tr>
 * </table>
 *
 * <p>还没有的（要用就得走 SPI 注册，或提成内置算子）：{@code reduce}、{@code merge}、
 * {@code log}、日期时间算术 —— 排到 F3/F4。</p>
 *
 * <h2>口径（都在测试向量里钉着，写文档时照抄）</h2>
 * <ul>
 *   <li><b>真值</b>：{@code null}/false/0/空串/空数组 = 假；其余为真（所以 {@code "0"}
 *       和 {@code "false"} 是**真** —— 与 JS/JSONLogic 一致，与"看起来像假"的直觉不一致，
 *       故用向量钉住）。</li>
 *   <li><b>比较</b>：两边都能当数字 ⇒ 按数字比；否则按字符串比；有一边是 {@code null} ⇒
 *       {@code <} 这类一律**假**（不把 null 当 0 —— 那会让"缺数据"变成"速度为 0"这种谎话）。</li>
 *   <li><b>类型不匹配的算术</b> ⇒ {@code null}，不是 0、也不抛异常。</li>
 *   <li><b>预算</b>：一次求值最多 {@link #MAX_NODES} 个节点、{@link #MAX_DEPTH} 层嵌套，
 *       超了抛 {@link BudgetExceededException}。资源包不可信，一个刻意写深的表达式不能把客户端拖死。</li>
 * </ul>
 */
public final class MmtrFaceLogic {

	/** 一次求值的节点预算（防"资源包里一个刻意写深的表达式"）。 */
	public static final int MAX_NODES = 512;
	/** 一次求值的嵌套深度预算。 */
	public static final int MAX_DEPTH = 24;

	/** 表达式用了不认识的算子。 */
	public static final class UnknownOperatorException extends RuntimeException {

		public UnknownOperatorException(String operator) {
			super("面文档用了不认识的算子：" + operator);
		}
	}

	/** 表达式超出预算（节点数或深度）。 */
	public static final class BudgetExceededException extends RuntimeException {

		public BudgetExceededException(String message) {
			super(message);
		}
	}

	/**
	 * 这个子集支持的算子名。
	 *
	 * <p>它是**给工具看的清单**：web 工作室的自动补全、{@code tools/anchor-check/verify_face.js}
	 * 的静态检查都读它的一份副本；{@code MmtrFaceToolingTests} 负责核对"这三份（本方法的清单 /
	 * {@link #applyOperator} 里的 {@code case} / JS 里那份）永远一致"—— 少一个的表现是
	 * "作者写了个算子，游戏里那块元素被静默跳过"。</p>
	 */
	public static java.util.Set<String> operators() {
		final java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
		java.util.Collections.addAll(names, "var", "if", "and", "or", "!", "!!",
			"==", "!=", "===", "!==", "<", "<=", ">", ">=",
			"+", "-", "*", "/", "%", "min", "max",
			"cat", "substr", "in", "missing", "missing_some", "?:",
			"some", "all", "none", "filter", "map");
		return names;
	}

	private MmtrFaceLogic() {
	}

	/**
	 * 求值一条已经解析好的表达式。
	 *
	 * @param expression JSONLogic 表达式（可以是字面量）
	 * @param data       数据（{@code Map}/{@code List}/标量）；{@code {"var": "a.b"}} 在其中按路径取值
	 * @return 求值结果：{@code Double}/{@code String}/{@code Boolean}/{@code List}/{@code Map}/{@code null}
	 */
	public static Object eval(JsonElement expression, Object data) {
		return evaluate(expression, data, 0, new int[]{0});
	}

	/** 求值一段 JSONLogic **文本**（用例、工作室的预览、日志里都按文本走）。 */
	public static Object evalJson(String json, Object data) {
		return json == null || json.isEmpty() ? null : eval(JsonParser.parseString(json), data);
	}

	private static Object evaluate(JsonElement node, Object data, int depth, int[] nodes) {
		if (node == null || node.isJsonNull()) {
			return null;
		}
		if (depth > MAX_DEPTH) {
			throw new BudgetExceededException("面文档的表达式嵌套超过 " + MAX_DEPTH + " 层");
		}
		if (++nodes[0] > MAX_NODES) {
			throw new BudgetExceededException("面文档的表达式超过 " + MAX_NODES + " 个节点");
		}

		if (node.isJsonArray()) {
			final List<Object> values = new ArrayList<>();
			for (final JsonElement element : node.getAsJsonArray()) {
				values.add(evaluate(element, data, depth + 1, nodes));
			}
			return values;
		}
		if (node.isJsonObject()) {
			return evaluateObject(node.getAsJsonObject(), data, depth, nodes);
		}
		return literal(node);
	}

	/**
	 * 一个对象 = 一次算子调用。
	 *
	 * <p>多键对象按 JSONLogic 的规矩视作 {@code and}：**依次求值、遇到假值就停**（返回那个假值），
	 * 全真则返回最后一个。单键（绝大多数情况）就是那个算子的结果。</p>
	 */
	private static Object evaluateObject(JsonObject object, Object data, int depth, int[] nodes) {
		if (object.size() == 0) {
			return null;
		}
		Object result = null;
		boolean first = true;
		for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
			if (!first && !truthy(result)) {
				return result;
			}
			result = applyOperator(entry.getKey(), entry.getValue(), data, depth, nodes);
			first = false;
		}
		return result;
	}

	private static Object applyOperator(String operator, JsonElement argument, Object data, int depth, int[] nodes) {
		return switch (operator) {
			case "var" -> {
				final List<Object> values = args(argument, data, depth, nodes);
				final String path = asString(values.isEmpty() ? null : values.get(0));
				final Object value = path.isEmpty() ? data : lookup(path, data);
				yield value != null ? value : (values.size() > 1 ? values.get(1) : null);
			}
			case "if" -> {
				final List<Object> values = args(argument, data, depth, nodes);
				Object result = null;
				for (int i = 0; i + 1 < values.size(); i += 2) {
					if (truthy(values.get(i))) {
						result = values.get(i + 1);
						break;
					}
					// 落到底：奇数个参数 ⇒ 最后一个是兜底
					result = i + 2 == values.size() - 1 ? values.get(i + 2) : null;
				}
				yield result;
			}
			case "and" -> {
				Object result = null;
				for (final Object value : args(argument, data, depth, nodes)) {
					result = value;
					if (!truthy(value)) {
						break;
					}
				}
				yield result;
			}
			case "or" -> {
				Object result = null;
				for (final Object value : args(argument, data, depth, nodes)) {
					result = value;
					if (truthy(value)) {
						break;
					}
				}
				yield result;
			}
			case "!" -> !truthy(first(argument, data, depth, nodes));
			case "!!" -> truthy(first(argument, data, depth, nodes));
			case "==" -> chainEquals(argument, data, depth, nodes, true);
			case "!=" -> !chainEquals(argument, data, depth, nodes, true);
			case "===" -> chainEquals(argument, data, depth, nodes, false);
			case "!==" -> !chainEquals(argument, data, depth, nodes, false);
			case "<" -> chainCompare(argument, data, depth, nodes, -1, false);
			case "<=" -> chainCompare(argument, data, depth, nodes, -1, true);
			case ">" -> chainCompare(argument, data, depth, nodes, 1, false);
			case ">=" -> chainCompare(argument, data, depth, nodes, 1, true);
			case "+" -> arithmetic(argument, data, depth, nodes, 0);
			case "*" -> arithmetic(argument, data, depth, nodes, 1);
			case "-" -> {
				final List<Object> values = args(argument, data, depth, nodes);
				if (values.size() == 1) {
					final Double one = asNumber(values.get(0));
					yield one == null ? null : -one;
				}
				yield binaryArithmetic(values, 2);
			}
			case "/" -> binaryArithmetic(args(argument, data, depth, nodes), 3);
			case "%" -> binaryArithmetic(args(argument, data, depth, nodes), 4);
			case "min", "max" -> {
				Double result = null;
				for (final Object value : args(argument, data, depth, nodes)) {
					final Double number = asNumber(value);
					if (number != null) {
						result = result == null ? number : (operator.equals("min") ? Math.min(result, number) : Math.max(result, number));
					}
				}
				yield result;
			}
			case "cat" -> {
				final StringBuilder builder = new StringBuilder();
				for (final Object value : args(argument, data, depth, nodes)) {
					builder.append(asString(value));
				}
				yield builder.toString();
			}
			case "substr" -> {
				final List<Object> values = args(argument, data, depth, nodes);
				final String text = asString(values.isEmpty() ? null : values.get(0));
				final int start = (int) numberOr(values.size() > 1 ? values.get(1) : null, 0);
				// 负起点 = 从尾部数（与 JS 的 slice 同义，JSONLogic 的 substr 也是这么转发的）
				final int from = start < 0 ? Math.max(0, text.length() + start) : Math.min(start, text.length());
				final int to = values.size() > 2 && asNumber(values.get(2)) != null
					? Math.min(text.length(), from + (int) numberOr(values.get(2), 0))
					: text.length();
				yield text.substring(from, Math.max(from, to));
			}
			case "in" -> {
				final List<Object> values = args(argument, data, depth, nodes);
				yield in(values.size() > 0 ? values.get(0) : null, values.size() > 1 ? values.get(1) : null);
			}
			case "missing" -> missing(args(argument, data, depth, nodes), data, -1);
			case "missing_some" -> {
				final List<Object> values = args(argument, data, depth, nodes);
				final int required = (int) numberOr(values.isEmpty() ? null : values.get(0), 0);
				// 第二个参数是**一个数组**：`args` 会把它求值成一个 List，直接拿来当键表用
				final List<Object> keys = values.size() > 1 && values.get(1) instanceof List<?> list ? new ArrayList<>(list) : List.of();
				yield missing(keys, data, required);
			}
			case "?:" -> {
				final List<Object> values = args(argument, data, depth, nodes);
				yield truthy(values.isEmpty() ? null : values.get(0)) ? (values.size() > 1 ? values.get(1) : null) : (values.size() > 2 ? values.get(2) : null);
			}
			case "some" -> iterateArray(argument, data, depth, nodes, 1);
			case "all" -> iterateArray(argument, data, depth, nodes, 2);
			case "none" -> iterateArray(argument, data, depth, nodes, 3);
			case "filter" -> iterateArray(argument, data, depth, nodes, 4);
			case "map" -> iterateArray(argument, data, depth, nodes, 5);
			default -> throw new UnknownOperatorException(operator);
		};
	}

	/**
	 * 取算子参数：JSON 数组 = 逐个求值（JSONLogic 的规矩：数组里的每一项都是表达式），
	 * 非数组 = 单参数。
	 */
	private static List<Object> args(JsonElement argument, Object data, int depth, int[] nodes) {
		final List<Object> values = new ArrayList<>();
		if (argument != null && argument.isJsonArray()) {
			for (final JsonElement element : argument.getAsJsonArray()) {
				values.add(evaluate(element, data, depth + 1, nodes));
			}
		} else {
			values.add(evaluate(argument, data, depth + 1, nodes));
		}
		return values;
	}

	private static Object first(JsonElement argument, Object data, int depth, int[] nodes) {
		final List<Object> values = args(argument, data, depth, nodes);
		return values.isEmpty() ? null : values.get(0);
	}

	private static boolean chainEquals(JsonElement argument, Object data, int depth, int[] nodes, boolean loose) {
		final List<Object> values = args(argument, data, depth, nodes);
		if (values.size() < 2) {
			return values.size() == 1 && (loose ? looseEquals(values.get(0), null) : values.get(0) == null);
		}
		for (int i = 0; i + 1 < values.size(); i++) {
			if (!(loose ? looseEquals(values.get(i), values.get(i + 1)) : strictEquals(values.get(i), values.get(i + 1)))) {
				return false;
			}
		}
		return true;
	}

	private static boolean chainCompare(JsonElement argument, Object data, int depth, int[] nodes, int sign, boolean orEqual) {
		final List<Object> values = args(argument, data, depth, nodes);
		for (int i = 0; i + 1 < values.size(); i++) {
			final Integer comparison = compare(values.get(i), values.get(i + 1));
			if (comparison == null || comparison != 0 && Integer.signum(comparison) != sign || comparison == 0 && !orEqual) {
				return false;
			}
		}
		return values.size() >= 2;
	}

	private static Object arithmetic(JsonElement argument, Object data, int depth, int[] nodes, double identity) {
		final List<Object> values = args(argument, data, depth, nodes);
		if (values.isEmpty()) {
			return identity;
		}
		double result = identity;
		for (final Object value : values) {
			final Double number = asNumber(value);
			if (number == null) {
				return null;
			}
			result = identity == 0 ? result + number : result * number;
		}
		return result;
	}

	private static Object binaryArithmetic(List<Object> values, int kind) {
		if (values.size() < 2) {
			return null;
		}
		final Double left = asNumber(values.get(0));
		final Double right = asNumber(values.get(1));
		if (left == null || right == null) {
			return null;
		}
		return switch (kind) {
			case 2 -> left - right;
			case 3 -> right == 0 ? null : left / right;
			default -> right == 0 ? null : left % right;
		};
	}

	/**
	 * 数组遍历算子：{@code some}(1)/{@code all}(2)/{@code none}(3) 返回布尔，
	 * {@code filter}(4)/{@code map}(5) 返回数组。谓词里的 {@code var} 相对于**当前元素**（JSONLogic 的口径）。
	 *
	 * <p>谓词**只对元素求值**，不先对外层数据求一遍 —— 否则 {@code {"some":[{"var":"list"},…]}} 里的
	 * 谓词会多算一次、白吃节点预算，还可能因为外层没有那个字段而提前抛错。</p>
	 */
	private static Object iterateArray(JsonElement argument, Object data, int depth, int[] nodes, int kind) {
		final boolean wantsList = kind == 4 || kind == 5;
		if (argument == null || !argument.isJsonArray() || argument.getAsJsonArray().size() < 2) {
			return emptyIteration(kind, wantsList);
		}
		final Object collection = evaluate(argument.getAsJsonArray().get(0), data, depth + 1, nodes);
		final List<?> list;
		if (collection instanceof List<?> javaList) {
			list = javaList;
		} else if (collection instanceof JsonArray jsonArray) {
			final List<Object> converted = new ArrayList<>();
			for (final JsonElement item : jsonArray) {
				converted.add(item);
			}
			list = converted;
		} else {
			// 不是数组：some = 没命中（假）、all/none = 没有反例（真）、filter/map = 空数组
			return emptyIteration(kind, wantsList);
		}
		final JsonElement predicate = argument.getAsJsonArray().get(1);
		final List<Object> out = new ArrayList<>();
		for (final Object item : list) {
			final Object itemResult = evaluate(predicate, item, depth + 1, nodes);
			final boolean matched = truthy(itemResult);
			if (kind == 1 && matched) {
				return true;
			}
			if (kind == 2 && !matched) {
				return false;
			}
			if (kind == 3 && matched) {
				return false;
			}
			if (kind == 5) {
				out.add(itemResult);
			} else if (kind == 4 && matched) {
				out.add(item);
			}
		}
		return switch (kind) {
			case 1 -> false;
			case 2, 3 -> true;
			default -> out;
		};
	}

	/** 数组遍历算子的"没有可遍历的东西"结果：some = 假、all/none = 真、filter/map = 空数组。 */
	private static Object emptyIteration(int kind, boolean wantsList) {
		return wantsList ? new ArrayList<>() : kind == 2 || kind == 3;
	}

	private static List<Object> missing(List<Object> keys, Object data, int required) {
		final List<Object> absent = new ArrayList<>();
		int present = 0;
		for (final Object key : keys) {
			final Object value = lookup(asString(key), data);
			if (value == null || asString(value).isEmpty()) {
				absent.add(asString(key));
			} else {
				present++;
			}
		}
		// missing_some：够 required 个就返回空数组（"缺的够不够用"而不是"缺哪些"）
		return required > 0 && present >= required ? new ArrayList<>() : absent;
	}

	/**
	 * {@code {"var": "a.b.c"}} 的取值：点号路径，数组可按下标；取不到 = {@code null}。
	 *
	 * <p>容器既认**普通 Java 集合**（{@code Map}/{@code List}，{@code MmtrFaceData} 建出来的那种），
	 * 也认 **Gson 的 {@code JsonObject}/{@code JsonArray}}（测试向量、离线预览直接喂一份 JSON 数据）。
	 * 叶子值原样返回，由 {@link #asNumber}/{@link #asString}/{@link #truthy} 统一收口 ——
	 * 所以这里**不做整棵树的拷贝**，一次取值只走路径上的那几层。</p>
	 */
	public static Object lookup(String path, Object data) {
		Object current = data;
		for (final String segment : path.split("\\.")) {
			if (current == null) {
				return null;
			}
			current = child(current, segment);
		}
		return current;
	}

	private static Object child(Object container, String segment) {
		if (container instanceof JsonObject object) {
			return object.get(segment);
		}
		if (container instanceof JsonArray array) {
			try {
				final int index = Integer.parseInt(segment);
				return index >= 0 && index < array.size() ? array.get(index) : null;
			} catch (NumberFormatException e) {
				return null;
			}
		}
		if (container instanceof Map<?, ?> map) {
			return map.get(segment);
		}
		if (container instanceof List<?> list) {
			try {
				final int index = Integer.parseInt(segment);
				return index >= 0 && index < list.size() ? list.get(index) : null;
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	/** 把一个 Gson 叶子拆成普通 Java 值（容器原样返回，交给调用方按容器处理）。 */
	private static Object unwrapLeaf(Object value) {
		if (!(value instanceof JsonElement element)) {
			return value;
		}
		if (element.isJsonNull() || !element.isJsonPrimitive()) {
			return element.isJsonNull() ? null : element;
		}
		final var primitive = element.getAsJsonPrimitive();
		if (primitive.isBoolean()) {
			return primitive.getAsBoolean();
		}
		if (primitive.isNumber()) {
			return primitive.getAsDouble();
		}
		return primitive.getAsString();
	}

	/**
	 * 真值口径（与 JSONLogic 一致，见类文档）：{@code null} / false / 0 / 空串 / 空数组 = 假。
	 *
	 * <p>刻意**不**把 {@code "0"}、{@code "false"} 当假 —— 它们是字符串，在 JS 与传统 JSONLogic 里
	 * 都是真。这一条反直觉，所以有测试向量钉着。</p>
	 */
	public static boolean truthy(Object value) {
		value = unwrapLeaf(value);
		if (value == null) {
			return false;
		}
		if (value instanceof JsonArray array) {
			return array.size() > 0;
		}
		if (value instanceof JsonObject) {
			// 空对象在 JS/JSONLogic 里是真（与空数组不同）—— 向量里没有这条，属于"照抄上游口径"
			return true;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof Number number) {
			return number.doubleValue() != 0 && !Double.isNaN(number.doubleValue());
		}
		if (value instanceof String text) {
			return !text.isEmpty();
		}
		if (value instanceof List<?> list) {
			return !list.isEmpty();
		}
		return true;
	}

	/** 能当数字就是数字，否则 {@code null}（空串、文字、null、数组都不是数字）。 */
	public static Double asNumber(Object value) {
		value = unwrapLeaf(value);
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value instanceof Boolean bool) {
			return bool ? 1D : 0D;
		}
		if (value instanceof String text) {
			final String trimmed = text.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			try {
				return Double.parseDouble(trimmed);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	private static double numberOr(Object value, double fallback) {
		final Double number = asNumber(value);
		return number == null ? fallback : number;
	}

	/** 文本口径：整数不写成 {@code 1.0}（{@code cat} 出来的字要能直接上面）。 */
	public static String asString(Object value) {
		value = unwrapLeaf(value);
		if (value == null) {
			return "";
		}
		if (value instanceof Double number) {
			return numberText(number);
		}
		if (value instanceof Number number) {
			return numberText(number.doubleValue());
		}
		if (value instanceof Boolean bool) {
			return bool ? "true" : "false";
		}
		return String.valueOf(value);
	}

	private static String numberText(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return "";
		}
		if (value == Math.rint(value) && Math.abs(value) < 1.0E15) {
			return String.valueOf((long) value);
		}
		return String.valueOf(value);
	}

	/**
	 * {@code ==}：两边都能当数字 ⇒ 按数字比；否则按字符串比；{@code null} 只与 {@code null} 相等
	 * （不把 {@code null} 当 0，也不当空串 —— 见类文档"比较"那条）。
	 */
	public static boolean looseEquals(Object left, Object right) {
		left = unwrapLeaf(left);
		right = unwrapLeaf(right);
		if (left == null || right == null) {
			return left == null && right == null;
		}
		final Double leftNumber = asNumber(left);
		final Double rightNumber = asNumber(right);
		if (leftNumber != null && rightNumber != null) {
			return leftNumber.doubleValue() == rightNumber.doubleValue();
		}
		return asString(left).equals(asString(right));
	}

	/** {@code ===}：类型也得一样。 */
	public static boolean strictEquals(Object left, Object right) {
		left = unwrapLeaf(left);
		right = unwrapLeaf(right);
		if (left == null || right == null) {
			return left == null && right == null;
		}
		if (left instanceof Number && right instanceof Number) {
			return ((Number) left).doubleValue() == ((Number) right).doubleValue();
		}
		if (left instanceof String && right instanceof String) {
			return left.equals(right);
		}
		if (left instanceof Boolean && right instanceof Boolean) {
			return left.equals(right);
		}
		return false;
	}

	/** {@code -1}/{@code 0}/{@code +1}；有一边 {@code null}（或不同类型且不可数字）时 {@code null}。 */
	public static Integer compare(Object left, Object right) {
		left = unwrapLeaf(left);
		right = unwrapLeaf(right);
		if (left == null || right == null) {
			return null;
		}
		final Double leftNumber = asNumber(left);
		final Double rightNumber = asNumber(right);
		if (leftNumber != null && rightNumber != null) {
			return Double.compare(leftNumber, rightNumber);
		}
		if (left instanceof String || right instanceof String) {
			return asString(left).compareTo(asString(right));
		}
		return null;
	}

	private static boolean in(Object needle, Object haystack) {
		if (haystack instanceof JsonArray array) {
			for (final JsonElement item : array) {
				if (looseEquals(needle, item)) {
					return true;
				}
			}
			return false;
		}
		if (haystack instanceof List<?> list) {
			for (final Object item : list) {
				if (looseEquals(needle, item)) {
					return true;
				}
			}
			return false;
		}
		return haystack != null && asString(haystack).contains(asString(needle));
	}

	private static Object literal(JsonElement node) {
		if (!node.isJsonPrimitive()) {
			return null;
		}
		final var primitive = node.getAsJsonPrimitive();
		if (primitive.isBoolean()) {
			return primitive.getAsBoolean();
		}
		if (primitive.isNumber()) {
			return primitive.getAsDouble();
		}
		return primitive.getAsString();
	}
}

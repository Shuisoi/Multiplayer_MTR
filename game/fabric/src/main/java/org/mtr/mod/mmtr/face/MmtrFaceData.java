package org.mtr.mod.mmtr.face;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车辆动态面的**数据快照**（notes/359 §数据接口）：某一刻这台车所有可上面字段的值，一层不可变的数据。
 *
 * <h2>什么时候建、建几份</h2>
 * <p>每个**游戏 tick** 每台车一份（由绘制运行时按需建、按 tick 缓存）。面文档的求值只读这份快照 ——
 * 于是：</p>
 * <ul>
 *   <li>同一 tick 里十几块面读到的是**同一份**数据（不会出现"这块牌写着海山、那块牌写着鸥湾"）；</li>
 *   <li>快照是纯数据（{@code Map}/{@code List}/标量），面文档、逻辑、排版全都能离线测、离线出图；</li>
 *   <li>重画签名可以直接用 {@link #describe()} —— 数据没变就不重画贴图（性能性质与仪表盘/水牌一致）。</li>
 * </ul>
 *
 * <h2>两条口径</h2>
 * <ul>
 *   <li><b>取不到 = 不放进去</b>（不是放一个 {@code null}）：于是 {@code var} 得到 {@code null}、
 *       {@code missing} 认为它缺、模板渲染成空串 —— 三种用法对"没有这个量"的理解完全一致。</li>
 *   <li><b>空字符串也算没有</b>：引擎里"未知"往往就是 {@code ""}（如回库趟的终点）。把它当"有值"
 *       会让 {@code when: {"!=": [{"var":"pid.terminus"}, ""]}} 这类条件写不下去。</li>
 * </ul>
 */
public final class MmtrFaceData {

	private final Map<String, Object> values;

	private MmtrFaceData(Map<String, Object> values) {
		this.values = values;
	}

	/** 按登记表问一遍取值口，再算派生量。 */
	public static MmtrFaceData of(MmtrFaceSource source) {
		final Map<String, Object> values = new LinkedHashMap<>();
		for (final MmtrFaceFields.Spec spec : MmtrFaceFields.specs()) {
			if (spec.kind() != MmtrFaceFields.Kind.RAW) {
				continue;
			}
			final Object coerced = coerce(spec.type(), source == null ? null : source.raw(spec.name()));
			if (coerced != null) {
				putPath(values, spec.name(), coerced);
			}
		}
		derive(values);
		return new MmtrFaceData(values);
	}

	/** 直接给一份（已经分好层的）数据 —— 用例、离线预览、以及将来的"录一份数据回放"用。 */
	public static MmtrFaceData of(Map<String, Object> values) {
		return new MmtrFaceData(new LinkedHashMap<>(values));
	}

	/** 空数据（每台车、每 tick 的起点；也让"没有数据"与"字段都取不到"是同一条路）。 */
	public static MmtrFaceData empty() {
		return new MmtrFaceData(new LinkedHashMap<>());
	}

	/** 按路径取值（{@code "pid.terminus"}）：与 {@code {"var": …}} / {@code {pid.terminus}} 同一套路径。 */
	public Object get(String path) {
		return MmtrFaceLogic.lookup(path, values);
	}

	/** 分层后的原始 Map（{@code MmtrFaceLogic} 直接吃它）。 */
	public Map<String, Object> asMap() {
		return values;
	}

	/** 有哪些字段（已分层，便于阅读；用例与日志用）。 */
	public List<String> names() {
		return values.entrySet().stream().map(entry -> entry.getValue() instanceof Map ? entry.getKey() + ".*" : entry.getKey()).toList();
	}

	/**
	 * 一句话描述这份快照（{@code 字段=值} 按登记表顺序）—— 进重画签名用：
	 * 数据一样 ⇒ 签名一样 ⇒ 不重画贴图。
	 */
	public String describe() {
		final StringBuilder builder = new StringBuilder();
		flatten(builder, "", values);
		return builder.toString();
	}

	@Override
	public String toString() {
		return "MmtrFaceData{" + describe() + "}";
	}

	private static void flatten(StringBuilder builder, String prefix, Map<String, Object> map) {
		for (final Map.Entry<String, Object> entry : map.entrySet()) {
			if (entry.getValue() instanceof Map<?, ?> child) {
				@SuppressWarnings("unchecked") final Map<String, Object> nested = (Map<String, Object>) child;
				flatten(builder, prefix + entry.getKey() + ".", nested);
			} else {
				if (builder.length() > 0) {
					builder.append(' ');
				}
				builder.append(prefix).append(entry.getKey()).append('=').append(MmtrFaceLogic.asString(entry.getValue()));
			}
		}
	}

	/** 派生量：只由已就绪的原始量算；缺料就不放（与"取不到 = 不放进去"同一条口径）。 */
	private static void derive(Map<String, Object> values) {
		final Double speed = MmtrFaceLogic.asNumber(values.get("speed"));
		if (speed != null) {
			putPath(values, "speedKmh", speed * 3600);
		}
	}

	/** 按类型强制转换（值取不到 ⇒ {@code null}，不编造 0/空串）。 */
	static Object coerce(MmtrFaceFields.Type type, Object raw) {
		if (raw == null) {
			return null;
		}
		return switch (type) {
			case NUM -> MmtrFaceLogic.asNumber(raw);
			case BOOL -> raw instanceof Boolean bool ? bool : (MmtrFaceLogic.asNumber(raw) != null ? MmtrFaceLogic.truthy(raw) : Boolean.TRUE);
			case STR -> {
				final String text = MmtrFaceLogic.asString(raw);
				yield text.isEmpty() ? null : text;
			}
		};
	}

	/** 按点号路径分层放进去（{@code pid.terminus} ⇒ {@code pid:{terminus:…}}）。 */
	static void putPath(Map<String, Object> root, String path, Object value) {
		if (value == null) {
			return;
		}
		final String[] segments = path.split("\\.");
		Map<String, Object> current = root;
		for (int i = 0; i + 1 < segments.length; i++) {
			final Object existing = current.get(segments[i]);
			if (existing instanceof Map<?, ?> map) {
				@SuppressWarnings("unchecked") final Map<String, Object> nested = (Map<String, Object>) map;
				current = nested;
			} else {
				final Map<String, Object> child = new LinkedHashMap<>();
				current.put(segments[i], child);
				current = child;
			}
		}
		current.put(segments[segments.length - 1], value);
	}
}

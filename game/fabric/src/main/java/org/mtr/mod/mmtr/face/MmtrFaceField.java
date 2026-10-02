package org.mtr.mod.mmtr.face;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面文档能显示的一个**扩展字段**（notes/361 · F4 的 SPI 之一）：附属模组给面文档加一个量。
 *
 * <h2>为什么是"从快照算"而不是"从引擎要"</h2>
 * <p>字段值有两条来路：</p>
 * <ol>
 *   <li><b>引擎镜像</b>（{@link MmtrFaceFields} 那 53 行）：值来自权威模拟器，走稀疏补丁同步过来。
 *       这条路要动引擎（加同步键 + 加提供者），不是附属模组该做的事；</li>
 *   <li><b>客户端算</b>（本接口）：拿**已经收集好的那份快照**算一个新量。
 *       例：由 {@code motor.forceN} 与 {@code speedKmh} 算一个"牵引/制动状态"字符串。</li>
 * </ol>
 *
 * <p>这条路刻意只能读快照、不能读游戏世界：于是扩展字段与内置字段在面文档里**行为完全一样**
 * （一样能进重画签名、一样能离线出图、一样进不了"权威数据"那条线 —— 见 notes/259 的老毛病）。</p>
 *
 * <h2>命名规矩</h2>
 * <p>名字必须是**一段**（不能带点，例如 {@code vendor:traction}），这样它不可能与内置的分层字段
 * （{@code pid.terminus} 这种）撞名，也不会把别人的子树盖掉。</p>
 */
public interface MmtrFaceField {

	/** 字段名（{@code 厂家:名字}，一段，不能带点）。 */
	String name();

	/** 类型（决定快照里放进去时怎么强制转换）。 */
	MmtrFaceFields.Type type();

	/** 单位（进字段表用；可空）。 */
	default String unit() {
		return "";
	}

	/** 一句话说明（进字段表与工作室提示用；可空）。 */
	default String doc() {
		return "";
	}

	/**
	 * 算这个量。{@code values} 是**已经收集好的内置字段**（含派生量如 {@code speedKmh}），
	 * 路径要用点分层（{@code values.get("motor")} 拿到的是子 Map）。
	 *
	 * @return 值（{@code Double}/{@code String}/{@code Boolean}）；{@code null} = 这次没有
	 * （与"取不到 = 不放进去"同一条口径：不画空值，而不是画一个 0）
	 */
	Object value(Map<String, Object> values);

	/** 扩展字段的登记表（纯数据层：{@link MmtrFaceData} 收集完内置字段后会问它一遍）。 */
	final class Registry {

		private static final Map<String, MmtrFaceField> FIELDS = new LinkedHashMap<>();

		private Registry() {
		}

		/** 登记一个扩展字段（名字不合法就忽略并返回 false）。 */
		public static boolean register(MmtrFaceField field) {
			if (field == null || !MmtrFaceExtension.isNamespaced(field.name()) || field.name().indexOf('.') >= 0) {
				return false;
			}
			FIELDS.put(field.name(), field);
			return true;
		}

		/** 全部扩展字段（登记顺序）。 */
		public static Iterable<MmtrFaceField> all() {
			return FIELDS.values();
		}

		public static int size() {
			return FIELDS.size();
		}

		/** 算一遍所有扩展字段，按内置字段同一套强制转换塞进快照。 */
		static void contribute(Map<String, Object> values) {
			for (final MmtrFaceField field : FIELDS.values()) {
				try {
					final Object computed = field.value(values);
					final Object coerced = MmtrFaceData.coerce(field.type(), computed);
					if (coerced != null) {
						MmtrFaceData.putPath(values, field.name(), coerced);
					}
				} catch (RuntimeException e) {
					// 一个扩展字段算炸了不该让整块面不画：跳过它（与"错一处不毁一块面"同一条）
					MmtrFaceWarnings.warn("扩展字段 " + field.name() + " 算失败（这次没有这个值）：" + e);
				}
			}
		}

		/** 资源重载/用例之间清空。 */
		public static void clear() {
			FIELDS.clear();
		}
	}
}

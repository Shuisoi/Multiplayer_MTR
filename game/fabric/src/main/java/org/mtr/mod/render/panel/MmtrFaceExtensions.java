package org.mtr.mod.render.panel;

import org.mtr.mod.mmtr.face.MmtrFaceExtension;
import org.mtr.mod.mmtr.face.MmtrFaceField;
import org.mtr.mod.mmtr.face.MmtrFaceFilter;
import org.mtr.mod.mmtr.face.MmtrFaceLogic;
import org.mtr.mod.mmtr.face.MmtrFaceSchema;
import org.mtr.mod.mmtr.face.MmtrFaceText;
import org.mtr.mod.mmtr.face.MmtrFaceWarnings;
import org.mtr.mod.Init;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.ServiceLoader;

/**
 * **面系统的扩展装载器**（notes/361 · F4）：把 {@link MmtrFaceExtension} 的实现扫出来、注册进四处。
 *
 * <h2>四样东西各自的落点</h2>
 * <table>
 *   <tr><td>元素画法</td><td>{@link MmtrFaceElements#register}（画布在 {@code render.panel}，
 *       所以它是这一层唯一"必须有游戏侧"的扩展点）</td></tr>
 *   <tr><td>算子</td><td>{@link MmtrFaceLogic#register}（{@code 厂家:名字}，与内置 32 个并存）</td></tr>
 *   <tr><td>过滤器</td><td>{@link MmtrFaceText#registerFilter}</td></tr>
 *   <tr><td>字段</td><td>{@link MmtrFaceField.Registry#register}（从快照算，见那个接口的说明）</td></tr>
 * </table>
 *
 * <h2>什么时候扫</h2>
 * <p>客户端启动一次、每次资源重载再扫一次（重载会把四张表清空再扫，于是"装了/卸了模组"
 * 在重载后立即生效，不会留一半旧注册）。</p>
 *
 * <h2>错了会怎样</h2>
 * <p>名字不带命名空间、注册时抛异常、ServiceLoader 里有坏类 —— 一律**跳过那一条并记一次账**，
 * 绝不让一个坏扩展把整块面系统带下水（与"错一处不毁一块面"同一条口径）。</p>
 */
public final class MmtrFaceExtensions {

	/** 已经登记过的扩展实现类名（重载时不去重也没关系，但日志里看得清楚）。 */
	private static final Set<String> LOADED = new LinkedHashSet<>();

	private MmtrFaceExtensions() {
	}

	/**
	 * 扫一遍 classpath 上的扩展并注册（幂等：同名类型/算子/过滤器/字段会被覆盖成最后注册的那个）。
	 *
	 * @return 成功装载的扩展个数
	 */
	public static int load() {
		return load(MmtrFaceExtensions.class.getClassLoader());
	}

	/** 同上，可以指定类加载器（用例就是这么把"第三方 jar"装进来的）。 */
	public static int load(ClassLoader classLoader) {
		final List<MmtrFaceExtension> extensions = MmtrFaceExtension.discover(classLoader == null ? MmtrFaceExtensions.class.getClassLoader() : classLoader);
		int loaded = 0;
		for (final MmtrFaceExtension extension : extensions) {
			try {
				register(extension);
				LOADED.add(extension.getClass().getName());
				loaded++;
			} catch (Throwable e) {
				MmtrFaceWarnings.warn("面系统扩展 " + extension.getClass().getName() + " 装载失败（已跳过）：" + e);
			}
		}
		flushWarnings();
		return loaded;
	}

	/** 注册**一个**扩展（用例直接调它，不必真的打一个 jar）。 */
	public static void register(MmtrFaceExtension extension) {
		if (extension == null) {
			return;
		}
		extension.register(new Registrar());
	}

	/** 资源重载：把四张表都清空（下一次 {@link #load()} 重新扫）。 */
	public static void clear() {
		MmtrFaceElements.clearRegistered();
		MmtrFaceLogic.clearRegisteredOps();
		MmtrFaceText.clearRegisteredFilters();
		MmtrFaceField.Registry.clear();
		MmtrFaceSchema.clearExtensionKeys();
		LOADED.clear();
	}

	/** 已装载的扩展实现类名（日志、调试）。 */
	public static List<String> loaded() {
		return new ArrayList<>(LOADED);
	}

	/** 把纯数据层攒下的账写进日志（每帧调用是零代价的：队列空就直接返回）。 */
	public static void flushWarnings() {
		for (final String message : MmtrFaceWarnings.drain()) {
			Init.LOGGER.warn("[MMTR] 面系统扩展：{}", message);
		}
	}

	/** 把一个扩展的四类注册落到各自的登记表上（命名规矩在这里把关）。 */
	private static final class Registrar implements MmtrFaceRegistrar {

		@Override
		public void element(String type, MmtrFaceElementRenderer renderer, String... declaredKeys) {
			if (!MmtrFaceExtension.isNamespaced(type)) {
				MmtrFaceWarnings.warn("扩展元素类型「" + type + "」没有命名空间（要写成 厂家:名字）—— 已拒绝，免得和内置类型撞名");
				return;
			}
			MmtrFaceElements.register(type, renderer);
			MmtrFaceSchema.registerExtensionKeys(type, declaredKeys);
		}

		@Override
		public void function(String name, MmtrFaceLogic.Op op) {
			if (!MmtrFaceExtension.isNamespaced(name)) {
				MmtrFaceWarnings.warn("扩展算子「" + name + "」没有命名空间（要写成 厂家:名字）—— 已拒绝，免得盖掉内置算子");
				return;
			}
			MmtrFaceLogic.register(name, op);
		}

		@Override
		public void filter(String name, MmtrFaceFilter filter) {
			if (!MmtrFaceText.registerFilter(name, filter)) {
				MmtrFaceWarnings.warn("扩展过滤器「" + name + "」名字不合法（要写成 厂家:名字）—— 已拒绝");
			}
		}

		@Override
		public void field(MmtrFaceField field) {
			if (!MmtrFaceField.Registry.register(field)) {
				MmtrFaceWarnings.warn("扩展字段「" + (field == null ? "null" : field.name()) + "」名字不合法（要写成 厂家:名字，且不能带点）—— 已拒绝");
			}
		}
	}
}

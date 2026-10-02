package org.mtr.mod.mmtr.face;

import org.mtr.mod.render.panel.MmtrFaceRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 面系统的**扩展入口**（notes/361 · F4）：附属模组实现这一个接口，就能给面文档加
 * **元素画法 / 算子 / 过滤器 / 字段**四样东西，不用改引擎、不用改模组源码。
 *
 * <h2>怎么被找到</h2>
 * <p>标准的 {@link ServiceLoader}：在你的模组里放一个</p>
 * <pre>
 * src/main/resources/META-INF/services/org.mtr.mod.mmtr.face.MmtrFaceExtension
 * </pre>
 * <p>内容是实现类的全限定名（一行一个）。客户端启动与每次资源重载时都会被扫一遍
 * （{@code MmtrFaceExtensions.load()}）。</p>
 *
 * <h2>命名规矩（硬性）</h2>
 * <p>元素类型 / 算子 / 过滤器**必须带命名空间**：{@code 厂家:名字}（例如 {@code vendor:bar}）。
 * 不带冒号的一律**拒绝注册**并记一条账。理由有三条，都是踩过的：</p>
 * <ul>
 *   <li><b>不撞内置</b>：内置的 {@code text}/{@code rect}/… 与 32 个算子是"随包发行"的最小集，
 *       扩展不该能悄悄把它换掉（那样同一份文档在不同客户端上画出不同的东西）；</li>
 *   <li><b>能解释"为什么我这里没有"</b>：作者看到一篇教程里的 {@code vendor:bar} 在自检里报
 *       P4"不认识的元素类型"时，一眼就知道"这是某个模组提供的，我没装"；</li>
 *   <li><b>能解释"为什么我这里不一样"</b>：带了命名空间，文档里的键就能自证来自哪个扩展。</li>
 * </ul>
 *
 * <h2>信任边界没有变</h2>
 * <p>资源包仍然是**纯数据**（notes/359 §2 的方案 A）：扩展是**代码**，装在模组里，
 * 由服务端/整合包决定装不装。装了扩展的客户端会认得更多 {@code type}，
 * 但资源包本身依旧不能执行任何东西。</p>
 */
public interface MmtrFaceExtension {

	/** 注册你的东西（四个 sink 方法都可以不用）。 */
	void register(MmtrFaceRegistrar registrar);

	/** 名字是否合法（{@code 厂家:名字}，两段都非空）。 */
	static boolean isNamespaced(String name) {
		if (name == null) {
			return false;
		}
		final int colon = name.indexOf(':');
		return colon > 0 && colon + 1 < name.length();
	}

	/** 用 {@link ServiceLoader} 扫出实现类（不实例化以外的任何事；坏的实现跳过并记账）。 */
	static List<MmtrFaceExtension> discover(ClassLoader classLoader) {
		final List<MmtrFaceExtension> found = new ArrayList<>();
		try {
			for (final MmtrFaceExtension extension : ServiceLoader.load(MmtrFaceExtension.class, classLoader)) {
				found.add(extension);
			}
		} catch (Throwable e) {
			MmtrFaceWarnings.warn("扫描面系统扩展失败（已当作没有扩展）：" + e);
		}
		return found;
	}
}

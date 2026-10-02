package org.mtr.mod.render.panel;

import org.mtr.mod.mmtr.face.MmtrFaceField;
import org.mtr.mod.mmtr.face.MmtrFaceFilter;
import org.mtr.mod.mmtr.face.MmtrFaceLogic;

/**
 * 扩展要用的**四个登记口**（notes/361 · F4）：附属模组实现 {@code MmtrFaceExtension} 时会拿到它。
 *
 * <p>四类东西的命名规矩一样：**必须带命名空间**（{@code 厂家:名字}）。不带的一律拒绝并记一条账 ——
 * 理由见 {@link org.mtr.mod.mmtr.face.MmtrFaceExtension} 的类注释（不撞内置、能解释"我为什么没有它"）。</p>
 *
 * <p>它住在 {@code render.panel} 而不是纯数据层：四类里**元素画法**必须拿到画布
 * （{@link MmtrPanelCanvas} 是 AWT 的、游戏侧的），所以这一个接口是唯一"跨层"的扩展点，
 * 另外三个（算子/过滤器/字段）的实现都在 {@code mmtr.face}。</p>
 */
public interface MmtrFaceRegistrar {

	/**
	 * 加一种元素类型（例如 {@code "vendor:bar"}）。
	 *
	 * @param declaredKeys 这个类型自己认的键（例如 {@code "value"}）。**建议一定要报**：
	 *                     不报的话，作者在这个类型上写的每个键都会被"未知键"守卫当成拼错记一条账。
	 *                     报上来的键只影响拼写守卫，不会进随包发行的 {@code schema.json}。
	 */
	void element(String type, MmtrFaceElementRenderer renderer, String... declaredKeys);

	/** 加一个算子（例如 {@code "vendor:percent"}；用法 {@code {"vendor:percent": [50, 200]}}）。 */
	void function(String name, MmtrFaceLogic.Op op);

	/** 加一个模板过滤器（例如 {@code "vendor:kmh"}；用法 {@code {speed|vendor:kmh}}）。 */
	void filter(String name, MmtrFaceFilter filter);

	/** 加一个字段（例如 {@code "vendor:traction"}；由快照算出来，见 {@link MmtrFaceField}）。 */
	void field(MmtrFaceField field);
}

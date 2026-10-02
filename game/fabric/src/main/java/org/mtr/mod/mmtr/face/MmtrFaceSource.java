package org.mtr.mod.mmtr.face;

/**
 * 车辆动态面的**取值口**（notes/359 §数据接口）：面系统不关心"值从哪来"，
 * 只按登记表 {@link MmtrFaceFields} 里的名字问。
 *
 * <p>为什么要有这一层接口，而不是让面文档直接读 {@code Vehicle}：</p>
 * <ul>
 *   <li><b>可离线测</b>：面文档、逻辑、排版都能拿一个假数据源跑用例与离线出图，不用起游戏；</li>
 *   <li><b>可扩展（F4 的 SPI）</b>：附属模组可以包一层（先问自己的量，再落到镜像），
 *       于是"某个模组才有的量"不必进引擎；</li>
 *   <li><b>守规矩</b>：哪些量能上面，只在 {@link MmtrFaceFields} 里说得清 —— 面系统不会
 *       偷偷在客户端算一个权威数据出来（notes/259 的老毛病）。</li>
 * </ul>
 */
@FunctionalInterface
public interface MmtrFaceSource {

	/**
	 * @param field 登记表里的字段名（如 {@code "pid.terminus"}）
	 * @return 原始值（{@code Double}/{@code String}/{@code Boolean}）；取不到返回 {@code null}
	 */
	Object raw(String field);

	/** 什么都没有的数据源：用例与"只想看排版"的离线预览用。 */
	static MmtrFaceSource empty() {
		return field -> null;
	}
}

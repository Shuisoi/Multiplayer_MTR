package org.mtr.mod.mmtr.face;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面文档的**文本过滤器**（notes/361 · F4 的 SPI 之一）：`{字段|名字:参数}` 里那个"名字"。
 *
 * <p>过滤器是纯函数（值 → 值），所以附属模组加一个不需要碰画布，也不需要重新打包任何东西：
 * 注册一个名字，作者就能在模板里用。名字**必须带命名空间**（`厂家:名字`）—— 见
 * {@link MmtrFaceExtension} 的命名规矩。</p>
 */
@FunctionalInterface
public interface MmtrFaceFilter {

	/**
	 * @param value     上游的值（第一个过滤器时是字段的值；取不到就是空串）
	 * @param parameter 冒号后面的参数（没有就是空串；{@code pad:7} 里的 {@code "7"}）
	 * @return 加工后的值（返回 {@code null} 当空）
	 */
	Object apply(Object value, String parameter);
}

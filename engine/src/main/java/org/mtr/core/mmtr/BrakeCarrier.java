package org.mtr.core.mmtr;

import org.mtr.core.mmtr.brake.BrakeModel;

/**
 * 带**制动模型宿主**的控制器（notes/270）。
 *
 * <p>存在的理由和 {@link AirBrakeStateful} 一样：让 {@link org.mtr.core.data.Vehicle} 按**能力**而不是按
 * 具体控制器类型分派。三手柄 / 有级 / 无级 / 将来的 ATO 都只是"把各自的手柄折成 {@link
 * org.mtr.core.mmtr.brake.BrakeCommand} 丢进同一个 {@link BrakeModel}"，于是：
 * 连挂接口（逐车气压状态）、镜像种子、HUD 的气制动力读数在**所有操纵方式**下是同一套代码。</p>
 *
 * <p>没配气压口径（{@code consist-types.json} 里没有 bar 键）的车底：模型不接管，控制器照旧走
 * 原来的归一化气路模型 —— 老车底逐位不变。</p>
 */
public interface BrakeCarrier {

	/** 本控制器的制动模型（永远非 null；没配气压口径时 {@link BrakeModel#isPneumatic()} 为 false）。 */
	BrakeModel getBrakeModel();
}

package org.mtr.core.mmtr.brake;

import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.physics.AdhesionSpec;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;
import org.mtr.core.mmtr.physics.TrainPhysics;

/**
 * **一节车的制动能力**（notes/270）：连挂形式的产物 —— 一列车就是一串 {@code BrakeCar}。
 *
 * <p>把"这列车由哪些车组成、各自多少闸、谁是动力车、各自哪套气路口径"抽成数据，于是：</p>
 * <ul>
 *   <li><b>不同连挂形式</b>（单机 / 机车+客车 / 双机重联 / 长途货车）只是**不同的列表**；</li>
 *   <li><b>不同列车逻辑</b>（盘式/闸瓦、不同分配阀、不同闸片）体现在每节车自己的 {@code spec} 上；</li>
 *   <li><b>连挂/解挂</b>就是列表的拼接与切分（{@link BrakeSystem} 的状态按车序同步拼接/切分）。</li>
 * </ul>
 *
 * @param serviceForceN   这节车的**常用制动力锚**（N）—— 由它自己的制动重量反推（UIC）或显式给出
 * @param emergencyForceN 这节车的**紧急制动力锚**（N）
 * @param powered         是不是动力车（只有动力车吃电空混合：电制动是它自己的）
 * @param spec            这节车自己的**气压/闸片口径**；{@code null} = 沿用编组级口径
 * @param lengthM         这节车的车长（m，notes/273 片 3）：**管容积当量的唯一输入**（用户口径
 *                        「管容积当量用车长」）。{@code <= 0} = 车长未知 ⇒ 不做容积修正（旧行为）
 * @param loadedMassKg    这节车**含载重**的质量（kg，notes/274 片 4）：逐车黏着截断的法向力来源
 * @param adhesion        这节车自己的**黏着档**（notes/275 片 5）：闸瓦/盘型不同 ⇒ μ_brake 不同，
 *                        撒砂也折在它的 {@code usableMuMax} 里。{@code null} = 逐车截断不生效
 */
public record BrakeCar(double serviceForceN, double emergencyForceN, boolean powered, @Nullable PneumaticBrakeSpec spec,
	double lengthM, double loadedMassKg, @Nullable AdhesionSpec adhesion) {

	/** 车长/载重/黏着都不知道的写法（单节等效车底、老夹具）：逐车截断不生效，由控制器的编组级截断兜底。 */
	public BrakeCar(double serviceForceN, double emergencyForceN, boolean powered, @Nullable PneumaticBrakeSpec spec) {
		this(serviceForceN, emergencyForceN, powered, spec, 0, 0, null);
	}

	/** 只知道车长（片 3 的写法）：黏着截断仍走编组级。 */
	public BrakeCar(double serviceForceN, double emergencyForceN, boolean powered, @Nullable PneumaticBrakeSpec spec, double lengthM) {
		this(serviceForceN, emergencyForceN, powered, spec, lengthM, 0, null);
	}

	/** 拖车（无闸）：连挂形式的常见产物 —— 它只贡献质量与阻力，不贡献制动力。 */
	public static BrakeCar unbraked(boolean powered) {
		return new BrakeCar(0, 0, powered, null);
	}

	/** 这节车能出多少常用制动（有闸就算）。 */
	public boolean hasBrakes() {
		return serviceForceN > 0 || emergencyForceN > 0;
	}

	/** 逐车黏着截断所需的两个输入齐不齐（法向力 + 黏着档）。缺一个就交给编组级截断。 */
	public boolean hasPerCarAdhesion() {
		return adhesion != null && loadedMassKg > 0;
	}

	/** 这节车的法向正压力（N）：{@code m·g}，**含载重**。 */
	public double normalForceN() {
		return loadedMassKg * TrainPhysics.GRAVITY;
	}

	/** 这节车自己的制动黏着上限（N）：{@code μ_brake(v)·m·g}；没有逐车数据时返回 {@code +∞}（= 不截）。 */
	public double brakingAdhesionLimitN(double speedMetersPerSecond) {
		return hasPerCarAdhesion() ? adhesion.brakingLimitN(normalForceN(), speedMetersPerSecond) : Double.MAX_VALUE;
	}
}

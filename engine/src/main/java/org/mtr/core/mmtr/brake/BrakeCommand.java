package org.mtr.core.mmtr.brake;

/**
 * **与操纵方式无关的制动诉求**（notes/270）：不管司机手里是几根手柄、有级还是无级、档位怎么排，
 * 折到这一层都只有三个数。
 *
 * <ul>
 *   <li>{@code demandRatio} —— **列车管级位的归一化诉求**（0 = 缓解位，1 = 最后一位）。
 *       它由各操纵方式自己折算：三手柄 = {@code 档位/(档位数−1)}；有级 = {@code 档/档数}；
 *       无级 = 制动轴 0…1。{@link BrakeSystem} 再按车底配的级位表把它插值成具体管压(bar)
 *       —— 于是"手柄档位不同"只是数据不同，模型一行不改。
 *       <p>级位表的**表尾是紧急/快排级**：只按"0…1 的常用诉求"给的有级/无级要把诉求折进常用范围
 *       （乘 {@link org.mtr.core.mmtr.physics.PneumaticBrakeSpec#getServiceDemandLimit()}，最后一位正好落在全常用），
 *       紧急由 {@code emergency} 这一位显式给 —— 有级手柄自己的"紧急位"由它的规格判定。</p></li>
 *   <li>{@code assistRatio} —— 上层（AFB / ATO / 保护层）**额外要的补气比例**（0…1，按各车缸压量程的比例）。
 *       {@code 0} = 不补。</li>
 *   <li>{@code emergency} —— 紧急（EB / 保护层）：快排 + 紧急限压 + 紧急力锚。</li>
 * </ul>
 */
public record BrakeCommand(double demandRatio, double assistRatio, boolean emergency) {

	/** 惰行（不制动、不补气）。 */
	public static BrakeCommand coast() {
		return new BrakeCommand(0, 0, false);
	}

	/**
	 * **按档位**给的诉求（有级 / 三手柄这类"一格一格"的操纵方式）。
	 *
	 * @param notch     档位下标（0 = 缓解位）
	 * @param notchCount 档位数（含缓解位）
	 */
	public static BrakeCommand notched(int notch, int notchCount, double assistRatio, boolean emergency) {
		final int count = Math.max(1, notchCount);
		final double ratio = count <= 1 ? (notch > 0 ? 1 : 0)
			: Math.max(0, Math.min(1, (double) Math.max(0, Math.min(count - 1, notch)) / (count - 1)));
		return new BrakeCommand(ratio, assistRatio, emergency);
	}

	/** **按连续量**给的诉求（无级手柄 / ATO）。 */
	public static BrakeCommand ofRatio(double demandRatio, double assistRatio, boolean emergency) {
		return new BrakeCommand(Math.max(0, Math.min(1, demandRatio)), Math.max(0, Math.min(1, assistRatio)), emergency);
	}
}

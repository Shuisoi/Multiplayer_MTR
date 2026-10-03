package org.mtr.core.mmtr.physics;

/**
 * **一个车钩的纵向动力学**（notes/277 片 7，折中版：机车 ↔ 车列一个钩，两个等效质点）。
 *
 * <h2>为什么是"折中版"</h2>
 *
 * <p>引擎里一列车是**一节 {@code Vehicle} + 一个速度**。全多体（每节车一个质点、每个钩一个状态）要改
 * 世界/位置/镜像三层；规格 §6 的收益（起步逐节碰撞的"冲动波"）**主要来自机车与车列之间那一个钩**，
 * 所以片 7 只把这一个钩建出来：车头那节（司机坐的那节）与它后面整列车列各当一个等效质点，
 * 挂车之间仍视为刚性。全多体留给后面（见设计文档 §6）。</p>
 *
 * <h2>状态与力</h2>
 *
 * <pre>
 *   d     相对位移（m，正 = 钩被**拉伸**：车头跑在车列前面）
 *   vRake 车列的等效速度（m/s）
 *   每拍：ḋ = vLead − vRake
 *         F_c = k(d, 卸载?)·(d ∓ slack) + c·ḋ      ← 间隙内为 0（钩舌没受力）
 *         a_rake = F_c / mRake     （车列被钩拖着走）
 *         a_lead = (F_net − F_c) / mLead            ← 这就是司机**感受到**的加速度
 * </pre>
 *
 * <p>于是起步时：间隙没吃完 ⇒ {@code F_c = 0} ⇒ 机车按**自己的质量**冲出去（加速度比刚体大），
 * 间隙一吃完 ⇒ {@code F_c} 顶上来 ⇒ 加速度掉到刚体之下 —— 这一"冲一顿"就是冲动。稳态时
 * {@code F_c} 恰好等于"拖着车列所需的力"，{@code a_lead} 回到刚体值（只差一点弹性压缩）。</p>
 *
 * <h2>数值</h2>
 *
 * <p>半隐式推进（先更新车列速度、再用新速度算相对位移），与 {@code ConsistDynamics.advance} 的
 * 子步同频（≥20 Hz、子步 50 ms）—— 钩的刚度大，显式欧拉在这个步长上会振（规格 §7 的同一件事）。</p>
 */
public final class CouplerDynamics {

	private final CouplerSpec spec;
	/** 相对位移（m，正 = 拉伸）。 */
	private double deflectionM;
	/** 车列的等效速度（m/s）。 */
	private double rakeSpeedMps;
	/** 最近一拍的钩力（N，正 = 拉着车列往前）。 */
	private double forceN;
	/** 出现过的最大钩力绝对值（N）—— 诊断"会不会断钩"。 */
	private double peakForceN;
	/** 这一拍钩子有没有真的受力（间隙吃完了没有）。 */
	private boolean slackTakenUp;

	public CouplerDynamics(CouplerSpec spec) {
		this.spec = spec;
	}

	public CouplerSpec getSpec() { return spec; }
	public double getDeflectionM() { return deflectionM; }
	public double getRelativeSpeedMps() { return rakeSpeedMps; }
	public double getForceN() { return forceN; }
	public double getPeakForceN() { return peakForceN; }
	public boolean isSlackTakenUp() { return slackTakenUp; }

	/**
	 * 内部子步上限（s）：钩的刚度是 {@code 10⁶–10⁷ N/m} 量级，外层的 50 ms 子步对它是**粗的**
	 * （规格 §7 那条"刚性 ODE"警告）。实测：不加内部子步时，间隙吃完那一下会被积到 −8 m/s²
	 * 的假振荡上；2 ms 之后同一场景稳定在 −0.3 m/s² 的顿挫量级。
	 */
	public static final double MAX_INTERNAL_STEP_SECONDS = 0.002;

	/** 迟滞干摩擦的平滑速度（m/s）：{@code tanh(ḋ/v_ref)} 让"方向"连续过渡，避免零点跳变引起振荡。 */
	public static final double HYSTERESIS_SMOOTHING_MPS = 0.01;

	/** 复位（重建控制器 / 换车列时用）：钩回到中立、车列速度跟上车头。 */
	public void reset(double leadSpeedMps) {
		deflectionM = 0;
		rakeSpeedMps = leadSpeedMps;
		forceN = 0;
		peakForceN = 0;
		slackTakenUp = false;
	}

	/**
	 * 推进一拍，返回**车头那节车真正感受到的加速度**（m/s²）。
	 *
	 * @param leadSpeedMps    车头当前速度（m/s，来自本车唯一的那个速度）
	 * @param dtSeconds       子步长（s）
	 * @param leadEffectiveMassKg 车头那节的惯性质量 {@code λ·m}（kg）
	 * @param rakeEffectiveMassKg 后面车列的惯性质量 {@code λ·m}（kg）；{@code <= 0} ⇒ 没有车列、原样返回
	 * @param rigidAccelerationMps2 "整列当刚体"算出来的加速度（m/s²）—— 由它反推净力，避免再算一套
	 */
	public double step(double leadSpeedMps, double dtSeconds, double leadEffectiveMassKg, double rakeEffectiveMassKg,
		double rigidAccelerationMps2) {
		if (rakeEffectiveMassKg <= 0) {
			forceN = 0;
			slackTakenUp = false;
			return rigidAccelerationMps2;
		}
		// 内部子步：钩是刚性 ODE，外层那 50 ms 的子步对它太粗（见 MAX_INTERNAL_STEP_SECONDS）。
		// 车头速度在这里也一起往前推 —— 外层那个积分器随后会把真实速度推到**同一个值**，
		// 所以这不是"算两遍"，而是把同一段 dt 内的相对运动算细。
		final int steps = Math.max(1, (int) Math.ceil(dtSeconds / MAX_INTERNAL_STEP_SECONDS));
		final double dt = Math.max(1e-6, dtSeconds / steps);
		double speed = leadSpeedMps;
		double sum = 0;
		for (int i = 0; i < steps; i++) {
			final double acceleration = stepOnce(speed, dt, leadEffectiveMassKg, rakeEffectiveMassKg, rigidAccelerationMps2);
			speed = Math.max(0, speed + acceleration * dt);
			sum += acceleration;
		}
		// 返回**这一拍的平均加速度**：外层 ConsistDynamics 用它把速度推进整整一个 dt —— 若只返回最后一个
		// 内部子步的值，外层推进的量与内部模型假设的量就差一截，会往相对运动里泵能量（实测稳态抖 ±0.2 m/s²）。
		return sum / steps;
	}

	private double stepOnce(double leadSpeedMps, double dt, double leadEffectiveMassKg, double rakeEffectiveMassKg,
		double rigidAccelerationMps2) {
		final double leadMass = Math.max(1, leadEffectiveMassKg);
		final double rakeMass = Math.max(1, rakeEffectiveMassKg);
		// 净力：从"刚体"结果反推（总惯性 = 车头 + 车列）
		final double netForceN = rigidAccelerationMps2 * (leadMass + rakeMass);
		// 钩力：相对位移与相对速度
		final double relativeSpeedMps = leadSpeedMps - rakeSpeedMps;
		final double magnitude = Math.abs(deflectionM);
		if (magnitude <= spec.slackM()) {
			forceN = 0;                                  // 自由间隙：钩舌没受力
			slackTakenUp = false;
		} else {
			final double elasticM = magnitude - spec.slackM();
			final double stiffness = spec.stiffnessAt(deflectionM);
			final double elasticForceN = stiffness * elasticM;
			final double dampingForceN = spec.dampingNsPerM() * relativeSpeedMps;
			// 迟滞 = 与**相对运动反向**的干摩擦（连续：tanh 过渡，不是刚度跳变 —— 跳变会弛豫振荡）。
			final double hysteresisForceN = spec.yieldForceN() * Math.tanh(relativeSpeedMps / HYSTERESIS_SMOOTHING_MPS);
			forceN = Math.signum(deflectionM) * elasticForceN + dampingForceN + hysteresisForceN;
			slackTakenUp = true;
		}
		peakForceN = Math.max(peakForceN, Math.abs(forceN));
		// 车列：被钩力推着走（半隐式：先更新速度，再更新相对位移）
		rakeSpeedMps += forceN / rakeMass * dt;
		if (rakeSpeedMps < 0) {
			rakeSpeedMps = 0;                            // 车列不会自己倒退（与 ConsistDynamics 同一口径）
		}
		deflectionM += (leadSpeedMps - rakeSpeedMps) * dt;
		// 车头：净力减去钩力就是它自己那点质量上的加速度
		return (netForceN - forceN) / leadMass;
	}
}

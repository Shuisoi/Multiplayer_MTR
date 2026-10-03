package org.mtr.core.mmtr.physics;

/**
 * **轮轨黏着**（规格 §4，`docs/01-设计/列车纵向动力学-指标与模型.md`）：把"牵引力"从"电机能给多少"
 * 拉回到"轮轨能传多少"。
 *
 * <p>牵引力的物理上限是黏着力：{@code F ≤ μ_eff · N}（{@code N} = 该轴法向正压力）。BR 101 的
 * 300 kN 起动牵引力对应 {@code μ = 300 kN / (84 t · g) ≈ 0.364} —— 干燥干净轨面的峰值就在 0.35–0.38，
 * 也就是说**干轨满牵引几乎占满黏着**；一到湿轨（μ≈0.15–0.25）或落叶（μ≤0.08），
 * 300 kN 根本传不下去，必须先降力/撒砂，否则就是空转。</p>
 *
 * <h2>μ–s 曲线（简化 Burckhardt / Polach）</h2>
 *
 * <pre>
 *   μ(s) = μ_max · (s/s_crit)                    0 ≤ s ≤ s_crit   ← 微滑弹性区：线性上升
 *   μ(s) = μ_max · (1 − k·(s − s_crit))          s &gt; s_crit      ← 宏观打滑区：负斜率衰减
 * </pre>
 *
 * <p>峰值滑差 {@code s_crit ≈ 1%–3%}（真车 ASG 就是把滑差锁在这个区间榨最大黏着，见规格 §4）。
 * 本类只给"能传多少力"；轮对角速度状态 / ASG / WSP 在下一步做（那时才会真的看到轮子空转）。</p>
 */
public final class AdhesionSpec {

	/** 环境轨面（决定峰值黏着 μ_max）。 */
	public enum Surface {
		/** 干燥干净轨面：0.33–0.38（规格 §4）。 */
		DRY(0.37),
		/** 湿轨/雨天：0.15–0.25。 */
		WET(0.20),
		/** 湿落叶 / 油污 / 黑冰：≤ 0.08。 */
		LEAVES(0.07);

		private final double muMax;

		Surface(double muMax) {
			this.muMax = muMax;
		}

		public double getMuMax() {
			return muMax;
		}
	}

	/** 干燥干净轨面（缺省）：机车标定就按它算黏着需求。 */
	public static final AdhesionSpec DRY = new AdhesionSpec(Surface.DRY, 0.02, false);

	/** 撒砂的增粘系数（规格 §4：低黏着轨面基础值提升 25%–40%，取中值 1.30）。 */
	public static final double SANDING_GAIN = 1.30;
	/** 峰值黏着对应的滑差（真车 ASG 锁 1.5%–2.5%，取中值）。 */
	public static final double DEFAULT_CRITICAL_SLIP = 0.02;
	/** 峰值之后的负斜率（每 1% 滑差掉多少 μ，相对 μ_max 的比例）—— 简化 Polach 的下降段。 */
	private static final double DECAY_PER_SLIP = 8.0;

	private final Surface surface;
	/** 直接给 μ_max 时用（配置里写数字）；{@code surface} 仅作诊断标签。 */
	private final double muMaxOverride;
	private final double criticalSlip;
	private final boolean sanding;

	public AdhesionSpec(Surface surface, double criticalSlip, boolean sanding) {
		this.surface = surface == null ? Surface.DRY : surface;
		this.muMaxOverride = this.surface.getMuMax();
		this.criticalSlip = criticalSlip > 0 ? criticalSlip : DEFAULT_CRITICAL_SLIP;
		this.sanding = sanding;
	}

	/**
	 * 直接用 μ_max 标定（配置里的 {@code adhesionMuMax}）：现场按实测/天气给数，
	 * 比"选一个枚举值"更灵活。{@code surface} 只用来打日志。
	 */
	public AdhesionSpec(double muMax, double criticalSlip, boolean sanding) {
		this.muMaxOverride = Math.max(0, muMax);
		this.surface = this.muMaxOverride <= Surface.LEAVES.getMuMax() ? Surface.LEAVES
			: this.muMaxOverride <= Surface.WET.getMuMax() ? Surface.WET : Surface.DRY;
		this.criticalSlip = criticalSlip > 0 ? criticalSlip : DEFAULT_CRITICAL_SLIP;
		this.sanding = sanding;
	}

	public Surface getSurface() { return surface; }
	public double getCriticalSlip() { return criticalSlip; }
	public boolean isSanding() { return sanding; }
	/** 撒砂开关（现场由司机/系统控制；这一版只做参数）。 */
	public AdhesionSpec withSanding(boolean sanding) { return new AdhesionSpec(surface, criticalSlip, sanding); }
	public AdhesionSpec withSurface(Surface surface) { return new AdhesionSpec(surface, criticalSlip, sanding); }

	/** 该轨面的可用峰值黏着（含撒砂增益）。 */
	public double usableMuMax() {
		return muMaxOverride * (sanding ? SANDING_GAIN : 1);
	}

	/**
	 * μ–s 曲线：微滑段线性升到 {@code μ_max}，之后按负斜率衰减（打滑区）。
	 *
	 * @param slip 蠕滑率 {@code s = (ωr − v)/max(|v|, |ωr|, ε)}（无因次，正数）
	 */
	public double muAtSlip(double slip) {
		final double s = Math.max(0, slip);
		final double muMax = usableMuMax();
		if (s <= criticalSlip) {
			return muMax * (s / criticalSlip);
		}
		return Math.max(0, muMax * (1 - DECAY_PER_SLIP * (s - criticalSlip)));
	}

	/** 该轴的最大可用轮周力（N）：{@code F ≤ μ_eff · N}。 */
	public double maxTractiveEffortN(double normalForceN) {
		return usableMuMax() * Math.max(0, normalForceN);
	}

	/**
	 * **制动侧可用黏着系数**（notes/267，规格模块四）：Curtius-Kniffler 经验式
	 * {@code μ(v) = 7.5/(v_kmh + 44) + 0.161}，再与轨面/撒砂给出的 {@link #usableMuMax()} 取小。
	 *
	 * <p>为什么牵引侧不用它：牵引侧关心的是"能传多少"的**峰值**（{@link #usableMuMax()}，干轨 0.37），
	 * 而 C-K 是**制动**用的速度曲线（0 km/h 0.331 → 100 km/h 0.213 → 200 km/h 0.192）。
	 * 天气/落叶仍然通过 {@code usableMuMax} 生效（取小 ⇒ 谁更差听谁的）。</p>
	 */
	public double brakingMu(double speedMetersPerSecond) {
		final double vKmh = Math.max(0, speedMetersPerSecond) * 3.6;
		return Math.min(usableMuMax(), 7.5 / (vKmh + 44) + 0.161);
	}

	/**
	 * **制动力的黏着上限**（N）：{@code μ_brake(v) · N}。超过它的制动力在物理上传不到轨面上。
	 *
	 * @param normalForceN 法向正压力（整列 {@code m·g}；逐车版留给 P5）
	 */
	public double brakingLimitN(double normalForceN, double speedMetersPerSecond) {
		return brakingMu(speedMetersPerSecond) * Math.max(0, normalForceN);
	}

	/**
	 * 这份黏着条件下，**要把 {@code requiredEffortN} 传下去需要多少滑差**（诊断/ASG 用）。
	 *
	 * @return 需要的蠕滑率；超过打滑区（曲线已掉到 0）时返回 {@link Double#MAX_VALUE}
	 */
	public double slipForEffort(double requiredEffortN, double normalForceN) {
		final double normal = Math.max(1, normalForceN);
		final double muNeeded = Math.max(0, requiredEffortN) / normal;
		final double muMax = usableMuMax();
		if (muNeeded > muMax) {
			return Double.MAX_VALUE;
		}
		return muNeeded / muMax * criticalSlip;
	}
}

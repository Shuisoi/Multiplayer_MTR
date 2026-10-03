package org.mtr.core.mmtr.physics;

import com.google.gson.JsonObject;

/**
 * **车钩口径**（notes/277 片 7，规格 §6「车钩与编组纵向冲动」）。
 *
 * <p>规格给的三件事：<b>自由间隙</b>（slack，钩舌间非受力死区，每副钩 15–30 mm）、
 * <b>多段刚度</b>（初段软、尾段极硬防撞底）、<b>迟滞耗能阻尼</b>（压缩/复原行程有摩擦迟滞圈，
 * 吸能 60%–70%）。本类只装这三个数，动力学在 {@link CouplerDynamics}。</p>
 *
 * <p><b>折中版的范围</b>（决定：片 7 先只做"机车 ↔ 车列"这一个钩）：一列车在引擎里仍只有**一个速度**
 * （一节 {@code Vehicle}），所以这个钩作用在两个**等效质点**上 —— 车头那节（司机坐的那节）与它后面
 * 整列车列。挂车之间仍视为刚性。玩家的冲动手感主要来自机车与车列之间，这是收益最大的那一半；
 * 全多体（每节车一个质点、每个钩一个）留给后面。</p>
 *
 * @param slackM            自由间隙（m，正负各一段）—— 规格：每副钩 15–30 mm
 * @param stiffnessSoftNPerM 初段刚度（N/m，间隙吃掉之后的软段）
 * @param stiffnessHardNPerM 尾段刚度（N/m，撞底前那一截极硬）
 * @param travelSoftM        软段行程（m）：|d| − 间隙 超过它才进硬段。**必须大于稳态变形**
 *                           （1 m/s² 拖 100 t 级车列 ≈ 85 kN ÷ 2 MN/m ≈ 42 mm）—— 否则系统会在
 *                           两段刚度之间来回切（实测：40 mm 时出现极限环，峰值冲到 833 kN）
 * @param dampingNsPerM      **黏性**阻尼（N·s/m）。注意：规格的"吸能 60%–70%"由 {@code yieldForceN}
 *                           表达（迟滞圈），这一项是**数值稳定 + 缓冲器耗能**的合并口径：取 {@code ζ≈0.45}
 *                           （对 100 t 级车列 ⇒ {@code c = ζ·2√(k·m)} ≈ 4×10⁵）。取小了钩子会在子步频率上自己振
 * @param yieldForceN        **迟滞屈服力**（N）：与相对速度反向的干摩擦项 {@code F_y·tanh(ḋ/v_ref)}。
 *                           一圈吸能 ≈ {@code 4·F_y·A}，对弹性储能 {@code ½kA²} ⇒
 *                           {@code 吸能比 = 8·F_y/(k·A)}（**振幅的函数，且振幅越小吸能比越大** ——
 *                           真缓冲器就是这样；写成"常数吸能比"会在小振幅上给出荒谬的圈面积，实测还会在
 *                           两个刚度之间极限环）。典型行程 40 mm、k=2 MN/m 下取 7 kN ⇒ 70%
 */
public record CouplerSpec(double slackM, double stiffnessSoftNPerM, double stiffnessHardNPerM, double travelSoftM,
	double dampingNsPerM, double yieldForceN) {

	/** 规格的典型值：间隙 20 mm、软段 2 MN/m、硬段 20 MN/m、软段行程 80 mm、阻尼 4×10⁵ N·s/m、屈服力 7 kN。 */
	public static final CouplerSpec DEFAULT = new CouplerSpec(0.020, 2.0e6, 2.0e7, 0.080, 4.0e5, 7.0e3);

	public CouplerSpec {
		slackM = Math.max(0, slackM);
		stiffnessSoftNPerM = Math.max(1, stiffnessSoftNPerM);
		stiffnessHardNPerM = Math.max(stiffnessSoftNPerM, stiffnessHardNPerM);
		travelSoftM = Math.max(1e-4, travelSoftM);
		dampingNsPerM = Math.max(0, dampingNsPerM);
		yieldForceN = Math.max(0, yieldForceN);
	}

	/**
	 * 从车底配置读（全部缺省 ⇒ {@code null} = **刚性车列**，逐位等于没有车钩这回事）。
	 *
	 * <p>键名：{@code couplerSlackM} / {@code couplerStiffnessSoftNPerM} / {@code couplerStiffnessHardNPerM} /
	 * {@code couplerTravelSoftM} / {@code couplerDampingNsPerM} / {@code couplerYieldForceN}。
	 * 只要写了其中**任意一个**，其余按 {@link #DEFAULT} 补 —— 免得"只写了间隙"变成半套参数。</p>
	 */
	public static CouplerSpec fromJsonOrNull(JsonObject json) {
		final String[] keys = {"couplerSlackM", "couplerStiffnessSoftNPerM", "couplerStiffnessHardNPerM",
			"couplerTravelSoftM", "couplerDampingNsPerM", "couplerYieldForceN"};
		boolean any = false;
		for (final String key : keys) {
			any |= json.has(key);
		}
		if (!any) {
			return null;
		}
		return new CouplerSpec(
			getDouble(json, "couplerSlackM", DEFAULT.slackM),
			getDouble(json, "couplerStiffnessSoftNPerM", DEFAULT.stiffnessSoftNPerM),
			getDouble(json, "couplerStiffnessHardNPerM", DEFAULT.stiffnessHardNPerM),
			getDouble(json, "couplerTravelSoftM", DEFAULT.travelSoftM),
			getDouble(json, "couplerDampingNsPerM", DEFAULT.dampingNsPerM),
			getDouble(json, "couplerYieldForceN", DEFAULT.yieldForceN));
	}

	private static double getDouble(JsonObject json, String key, double fallback) {
		try {
			return json.has(key) ? json.get(key).getAsDouble() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	/**
	 * **当前这个变形下的刚度**（N/m）：间隙内为 0（钩舌没受力），软段用软刚度，超过软段行程用硬刚度（撞底）。
	 *
	 * <p>迟滞**不在这里**：它由 {@link #yieldForceN} 那条与相对速度反向的干摩擦项表达（{@link CouplerDynamics}）。
	 * 曾经把迟滞写成"复原行程刚度打折"，结果是 {@code ḋ} 过零时力跳变 ⇒ 两个刚度之间弛豫振荡（实测极限环）。</p>
	 */
	public double stiffnessAt(double deflectionM) {
		final double magnitude = Math.abs(deflectionM);
		if (magnitude <= slackM) {
			return 0;
		}
		return magnitude - slackM <= travelSoftM ? stiffnessSoftNPerM : stiffnessHardNPerM;
	}

	/**
	 * 振幅 {@code amplitudeM} 的迟滞圈**吸能比**（0..1）：一圈吸能 {@code 4·F_y·A}、弹性储能 {@code ½kA²}。
	 * 这是**振幅的函数** —— 规格的 60%–70% 说的是典型行程（40 mm 量级）上的数。
	 */
	public double energyAbsorptionRatio(double amplitudeM) {
		final double elastic = 0.5 * stiffnessSoftNPerM * amplitudeM * amplitudeM;
		if (elastic <= 0) {
			return 0;
		}
		return Math.min(1, 4 * yieldForceN * amplitudeM / elastic);
	}
}

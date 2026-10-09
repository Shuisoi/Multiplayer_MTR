package org.mtr.core.mmtr.physics;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * **电制动（回生 / 电阻）能力**（notes/266 的三段式；notes/379 起**任何操纵方式**都能配）。
 *
 * <p>为什么要有它（用户口径 2026-10-03：「这个车还有一个 B1,B2 是电再生制动逻辑」）：有级/无级车底原来
 * 根本不给电制动 —— {@code availableElectricN} 只有三手柄那条路在传，于是"B1/B2 是回生档"这件事在模型里
 * 不存在，低档的缸压全是空气闸，HUD 上的"缸压 vs 司机诉求"自然分不明白。有了这份口径，共用的
 * {@link org.mtr.core.mmtr.brake.BrakeSystem} 会照它做**电优先**的混合：电制动能覆盖的那部分
 * **动力车缸压被 EP 阀削掉**（拖车的空气闸不动，因为它们没有电机）。</p>
 *
 * <pre>
 *   v &lt; cutoff             → 0（低速切除：电机建立不了力矩）
 *   cutoff ≤ v &lt; full      → B_max × 线性淡入（fade）
 *   full ≤ v ≤ P/B_max     → B_max（恒力）
 *   v &gt; P/B_max            → P / v（恒功率，网侧回馈容量）
 * </pre>
 *
 * <p>单位：N / W / km/h（与车底配置一致）。{@code cutoffKmh} 不写时取 {@code fullForceKmh / 2}
 * —— 那正是 notes/266 起的三手柄口径（衰减带 [fade/2, fade]），所以老配置逐位不变。</p>
 */
public final class ElectricBrakeSpec {

	/** 出厂"满力速度"（km/h）：低于它的淡入带形状见类注释。 */
	public static final double DEFAULT_FULL_FORCE_KMH = 10;

	private final double forceN;
	private final double maxPowerW;
	/** 到这一速度（km/h）就满力（淡入带的上端）。 */
	private final double fullForceKmh;
	/** 低于这一速度（km/h）整段切除（淡入带的下端；不写 = fullForceKmh / 2）。 */
	private final double cutoffKmh;

	public ElectricBrakeSpec(double forceN, double maxPowerW, double fullForceKmh, double cutoffKmh) {
		this.forceN = Math.max(0, forceN);
		this.maxPowerW = Math.max(0, maxPowerW);
		this.fullForceKmh = Math.max(1, fullForceKmh);
		this.cutoffKmh = Math.max(0, Math.min(this.fullForceKmh, cutoffKmh));
	}

	/** 老写法：衰减带上端 = {@code fullForceKmh}，下端 = 它的一半（notes/266）。 */
	public ElectricBrakeSpec(double forceN, double maxPowerW, double fullForceKmh) {
		this(forceN, maxPowerW, fullForceKmh, Math.max(1, fullForceKmh) * 0.5);
	}

	public boolean isPresent() {
		return forceN > 0;
	}

	/**
	 * 从车底 JSON 读；**没有 {@code rheostaticBrakeForceN} 就返回 null**（= 这份车底没有电制动，
	 * 常见于拖车）。有牵引却忘了写它的车底由 {@code ConsistType.fromJson} 点名。
	 */
	public static @Nullable ElectricBrakeSpec fromJsonOrNull(JsonObject json, double fallbackForceN) {
		if (!json.has("rheostaticBrakeForceN")) {
			return null;
		}
		final double fullForceKmh = json.has("rheostaticFadeKmh") ? getDouble(json, "rheostaticFadeKmh", DEFAULT_FULL_FORCE_KMH) : DEFAULT_FULL_FORCE_KMH;
		return new ElectricBrakeSpec(
			getDouble(json, "rheostaticBrakeForceN", fallbackForceN),
			getDouble(json, "rheostaticMaxPowerW", 0),
			fullForceKmh,
			json.has("regenCutoffKmh") ? getDouble(json, "regenCutoffKmh", fullForceKmh * 0.5) : fullForceKmh * 0.5
		);
	}

	/** 低速切除的淡入系数（0…1）：{@code cutoff} 及以下 0，{@code fullForce} 及以上 1。 */
	public double fadeFactor(double speedMetersPerSecond) {
		final double v = Math.max(0, speedMetersPerSecond);
		final double cutoffMps = cutoffKmh / 3.6;
		final double fullMps = fullForceKmh / 3.6;
		if (fullMps <= cutoffMps) {
			return v >= fullMps ? 1 : 0;
		}
		return Math.max(0, Math.min(1, (v - cutoffMps) / (fullMps - cutoffMps)));
	}

	/**
	 * **这一拍电制动的可用力**（N，满比例时）—— 电空混合拿它去替动力车自己的机械制动。
	 * 三段式：淡入带 → 恒力 → 恒功率（{@code maxPowerW = 0} 时退化成一路上限力）。
	 */
	public double effortN(double speedMetersPerSecond) {
		final double v = Math.max(0, speedMetersPerSecond);
		final double fade = fadeFactor(v);
		if (fade <= 0 || forceN <= 0) {
			return 0;
		}
		final double powerLimited = maxPowerW > 0 && v > 0 ? maxPowerW / v : Double.MAX_VALUE;
		return Math.min(forceN, powerLimited) * fade;
	}

	/** 恒力段与恒功率段的折点速度（m/s）：{@code P / B_max}；不限功率或无力时为 0。 */
	public double breakpointMetersPerSecond() {
		return forceN <= 0 || maxPowerW <= 0 ? 0 : maxPowerW / forceN;
	}

	/**
	 * **同一列车的等效电制动口径**：力与功率**相加**（有几节动力车就有几台电机），
	 * 切除/满力速度取**最保守的那个**（有一节车还没投入，整列的可用电制动就不能算满）。
	 */
	public ElectricBrakeSpec plus(ElectricBrakeSpec other) {
		if (other == null || !other.isPresent()) {
			return this;
		}
		if (!isPresent()) {
			return other;
		}
		return new ElectricBrakeSpec(forceN + other.forceN, maxPowerW + other.maxPowerW,
			Math.max(fullForceKmh, other.fullForceKmh), Math.max(cutoffKmh, other.cutoffKmh));
	}

	public double getForceN() { return forceN; }
	public double getMaxPowerW() { return maxPowerW; }
	public double getFullForceKmh() { return fullForceKmh; }
	public double getCutoffKmh() { return cutoffKmh; }

	/** 镜像用的紧凑串（客户端要跑同一份物理：电制动替掉多少气制动，两端必须同一个口径）。 */
	public String encode() {
		return "EB," + trim(forceN) + "," + trim(maxPowerW) + "," + trim(fullForceKmh) + "," + trim(cutoffKmh);
	}

	/** 解 {@link #encode()}；空串/坏串返回 {@code null}（调用方按"没有电制动"处理）。 */
	public static @Nullable ElectricBrakeSpec decode(String text) {
		if (text == null || text.isEmpty()) {
			return null;
		}
		final String[] parts = text.split(",");
		if (parts.length < 5 || !"EB".equals(parts[0])) {
			return null;
		}
		try {
			return new ElectricBrakeSpec(Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
				Double.parseDouble(parts[3]), Double.parseDouble(parts[4]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String trim(double value) {
		return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
	}

	private static double getDouble(JsonObject json, String key, double fallback) {
		try {
			return json.has(key) ? json.get(key).getAsDouble() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}
}

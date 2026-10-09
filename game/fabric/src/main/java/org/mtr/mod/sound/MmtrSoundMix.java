package org.mtr.mod.sound;

import java.util.Locale;

/**
 * **MMTR 车辆音效的混音台 —— 所有"音量从哪来"的常数只在这一处。**
 *
 * <h2>为什么要有这个类</h2>
 * <p>在这之前，音量被分散在三处各写一半：{@code MmtrVehicleSound} 里的 {@code MASTER_VOLUME} 与
 * {@code GEAR_VOLUME}、以及清单里 {@code running.layers[].masterVolume}。而"这几级乘起来到底是多少 dBFS"
 * <b>没有任何一处写下来</b> —— 现场的表现就是"声音偏低"变成一个**没法回答**的问题：
 * 不知道是哪一级吃掉的，也不知道还有没有余量。本类把这条链摊平成一张可核对的表，
 * 并把"我们说了不算"的那一级（烘焙电平）作为**实测常数**记在代码里。</p>
 *
 * <h2>增益分级（从样本到耳朵，逐级相乘）</h2>
 * <table>
 *   <tr><th>#</th><th>级</th><th>谁决定</th><th>范围</th></tr>
 *   <tr><td>1</td><td>ogg 样本峰值</td><td>烘焙链（{@code artifacts/kei2100-av417534959}）</td>
 *       <td>见下面的实测常数</td></tr>
 *   <tr><td>2</td><td>层内律</td><td>{@link MmtrTractionSoundModel}（负载轴 / 速度律 / 门限）</td>
 *       <td>0..1（负载轴可到 1.5，由第 3 级夹回）</td></tr>
 *   <tr><td>3</td><td><b>层基线 × 总出手量</b></td><td><b>本类</b></td><td>0..1</td></tr>
 *   <tr><td>4</td><td>换档淡化</td><td>{@code MmtrVehicleSound}</td><td>0..1</td></tr>
 *   <tr><td>5</td><td>分类音量 × 主音量</td><td>玩家设置（Blocks / Master 滑杆）</td><td>0..1</td></tr>
 *   <tr><td>6</td><td>距离衰减</td><td>MC → OpenAL（{@code AL_DISTANCE_MODEL}）</td>
 *       <td>0..1；<b>坐在操纵位上时被整级旁路</b>（{@code AttenuationType.NONE}）</td></tr>
 * </table>
 *
 * <h2>★ 播放端**能**放大 —— 上一版那条"只能衰减"是错的（2026-10-08 实测纠正）</h2>
 * <p>上一版这里写着"MC 把音量交给 OpenAL 的 {@code AL_GAIN}，而它的上限是 1.0 ⇒ 每层的输出峰值
 * 永远不会超过那条 ogg 自己的峰值"。</p>
 * <p><b>前半句对，后半句错。</b>实测（{@code sandbox/openal-gain-probe/}，用 LWJGL 打包的
 * OpenAL Soft 1.21.1，就是游戏用的那一个）：往 source 写 {@code AL_GAIN = 2.0 / 3.16 / 8.0}，
 * 读回来**原样不变、没有被夹**。规范把 {@code AL_MAX_GAIN} 的默认值定在 1.0，
 * 所以"到底会不会真的放大"是**实现相关**的 —— 但至少这个实现不夹。</p>
 * <p>于是口径改成：<b>允许层增益超过 1（{@link #TRACTION} 就是干这个的），
 * 并用 {@link #worstCasePeak()} 的余量核算挡住削顶。</b>这样做对两种情况都安全 ——
 * 若某个实现真的把增益夹在 1，结果只是回到"用满烘焙电平"的响度，不会更差。</p>
 *
 * <h2>增益分级（从样本到耳朵，逐级相乘）</h2>
 * <table>
 *   <tr><th>#</th><th>级</th><th>谁决定</th><th>范围</th></tr>
 *   <tr><td>1</td><td>ogg 样本峰值</td><td>烘焙链（{@code artifacts/kei2100-av417534959}）</td>
 *       <td>见下面的实测常数</td></tr>
 *   <tr><td>2</td><td><b>力轴</b>（牵引力）</td><td>{@link MmtrTractionSoundModel#forceAxis}</td>
 *       <td>0..1；<b>速度不进这一支</b></td></tr>
 *   <tr><td>3</td><td><b>层基线 × 总出手量</b></td><td><b>本类</b></td>
 *       <td>0..{@link #MAX_LAYER_GAIN}（**可以大于 1**）</td></tr>
 *   <tr><td>4</td><td>换档淡化</td><td>{@code MmtrVehicleSound}</td><td>0..1</td></tr>
 *   <tr><td>5</td><td>分类音量 × 主音量</td><td>玩家设置（Blocks / Master 滑杆）</td><td>0..1</td></tr>
 *   <tr><td>6</td><td>距离衰减</td><td>MC → OpenAL（{@code AL_DISTANCE_MODEL}）</td>
 *       <td>0..1；<b>坐在操纵位上时被整级旁路</b>（{@code AttenuationType.NONE}）</td></tr>
 * </table>
 */
public final class MmtrSoundMix {

	/**
	 * **总出手量**：所有层共用的最后一级（0..1）。
	 *
	 * <p>0.45 → 1.0（2026-10-08）：现场口径是"声音不够大"，而 0.45 就是白丢的 **−6.9 dB**。
	 * 它不承担平衡作用（三层同乘），所以直接顶到 1.0 = 不再衰减。</p>
	 */
	public static final double MASTER = 1.0;

	/**
	 * **牵引层（磁励音）的层基线 —— 就是"把励磁音抬起来"的那个旋钮。**
	 *
	 * <p>为什么需要它：两种音效的**配比**不一样（用户口径）。交付音频实测——
	 * 牵引层最响那一档峰值只有 **−25.47 dBFS**，而走行层是 −11.19 / −10.00 dBFS，
	 * 差 **14～15 dB**。那一截的由来在烘焙链（走行层被一个 −10 dBFS 的上限顶着），
	 * 但**不必回去重编码**：播放端能放大（见类注释的实测），这里给牵引层一个整层标量即可。</p>
	 *
	 * <p>取值 2.2 ⇒ <b>+6.8 dB</b>，牵引层最响一档的输出从 −25.47 抬到 **−18.6 dBFS**。
	 * 再叠上"音量只由牵引力定、低速不再被门限按速度压掉"（{@code forceAxis} 那一改），
	 * 20 km/h 满级位相对上一版合计约 **+11 dB**。它是**单旋钮**：听下来还压不住走行就往上调，
	 * 但每调一次都要看 {@link #worstCasePeak()} 的余量（{@code MmtrSoundMixTests} 会拦削顶）。</p>
	 */
	public static final double TRACTION = 2.2;

	/**
	 * **齿轮层**相对牵引层的比例。
	 *
	 * <p>它与牵引层同一族（都由牵引力激励），所以乘在牵引层那条支路上，而不是另起一套总音量。
	 * 取值理由：真车的齿轮啸叫在车内是"细而持续"的一层，压过载波就变成电动牙刷。</p>
	 */
	public static final double GEAR = 0.5;

	/**
	 * **走行层**（轮轨 / 风噪）相对总出手量的基线。
	 *
	 * <p>两条走行层各自的绝对刻度是清单里的 {@code running.layers[].masterVolume}（现阶段都是 1.0），
	 * 这里只放"走行层相对牵引层"这一个公共因子。</p>
	 *
	 * <p>0.9（−0.9 dB）而不是 1.0：一个是给牵引层那 +6.8 dB 让出余量（三层同时到顶是最坏情况），
	 * 另一个是这两层本来就顶着链上那个**任意的 −10 dBFS 上限**。这是**很小的**一档，
	 * 真正的配比调整是 {@link #TRACTION}。</p>
	 */
	public static final double RUNNING = 0.9;

	/**
	 * **单层增益的上限**（= +12 dB）—— 纯粹是防手滑的夹子，不是物理限制。
	 *
	 * <p>没有它，一个写错的常数就能把某层顶到削顶；有了它，最坏情况仍然由
	 * {@link #worstCasePeak()} 与用例守着。为什么是 4.0 而不是 1.0：见类注释的实测纠正 ——
	 * 播放端**能**放大。</p>
	 */
	public static final double MAX_LAYER_GAIN = 4.0;

	// -----------------------------------------------------------------------------------------
	// 交付 ogg 的实测峰值（dBFS）
	// -----------------------------------------------------------------------------------------

	/**
	 * **交付音频各层的实测峰值（dBFS）** —— 整条链里唯一"我们说了不算"的一级，所以把它写下来并留出处。
	 *
	 * <p>出处：{@code artifacts/kei2100-av417534959/} 下 23+23+23 条源 WAV 的逐条实测
	 * （{@code game/kei2100-upd-step*.wav} 与 {@code game/run/{roll,aero}-step*.wav}）；
	 * ogg 与源 WAV 同电平（编码不改幅度）。这里取的是**各层最响那一档**的峰值：
	 * 三层的最响档会同时出现（满功率 + 高速），所以它们的和就是最坏情况。</p>
	 *
	 * <p>★ <b>动过烘焙就必须回来改这三个数</b> —— {@code MmtrSoundMixTests} 会拿磁盘上的清单
	 * （{@code kei2100_sounds.json} 的逐条 {@code peak_dbfs}）核对走行层，对不上就红。</p>
	 */
	public static final double MOTOR_PEAK_DBFS = -25.47;
	public static final double ROLL_PEAK_DBFS = -11.19;
	public static final double AERO_PEAK_DBFS = -10.00;

	private MmtrSoundMix() {
	}

	// -----------------------------------------------------------------------------------------
	// 音量：三层各取一条
	// -----------------------------------------------------------------------------------------

	/**
	 * **牵引层**（磁励音）的音量。
	 *
	 * <p>★ 输入**只有牵引力**（notes/403 续）：用户口径把两个轴分开 —— 速度决定"播哪一段"
	 * （那是 {@link MmtrTractionSoundModel#selectFor} 的事），牵引力决定"播多大"（这里）。
	 * 本方法里没有任何速度项，所以"同样的力、不同速度音量不同"这种耦合在结构上就不可能发生。</p>
	 *
	 * @param forceAxis {@link MmtrTractionSoundModel#forceAxis} 的结果（0..1）
	 */
	public static double tractionVolume(double forceAxis) {
		return clampLayer(forceAxis * MASTER * TRACTION);
	}

	/** **齿轮层**的音量：与牵引层同一个力轴，另乘 {@link #GEAR}。 */
	public static double gearVolume(double forceAxis) {
		return clampLayer(forceAxis * MASTER * TRACTION * GEAR);
	}

	/**
	 * **走行层**（轮轨 / 风噪）的音量。
	 *
	 * @param layerVolume 层内速度律的结果（{@code (v/vBake)^(lawB/20)}，恒 ≤ 1）
	 * @param layerMaster 清单里这一层自己的 {@code masterVolume}
	 */
	public static double runningVolume(double layerVolume, double layerMaster) {
		return clampLayer(layerVolume * MASTER * RUNNING * layerMaster);
	}

	// -----------------------------------------------------------------------------------------
	// 余量与诊断
	// -----------------------------------------------------------------------------------------

	/**
	 * 牵引层在"最响的一档 + 满牵引力"时的输出峰值（dBFS）。
	 *
	 * <p>★ 它**不再等于烘焙峰值** —— {@link #TRACTION} 那一级就在它上面（实测纠正，见类注释）。</p>
	 */
	public static double tractionCeilingDbfs() {
		return MOTOR_PEAK_DBFS + gainDb(MASTER * TRACTION);
	}

	/**
	 * **最坏情况**：牵引 + 轮轨 + 风噪三层各自最响的一档同时出现，且都满力 / 满速时，
	 * 在听者处叠加出来的峰值（线性）。<b>必须 &lt; 1.0</b>，否则会削顶。
	 */
	public static double worstCasePeak() {
		return MASTER * (TRACTION * dbToLinear(MOTOR_PEAK_DBFS)
				+ RUNNING * dbToLinear(ROLL_PEAK_DBFS)
				+ RUNNING * dbToLinear(AERO_PEAK_DBFS));
	}

	/** {@link #worstCasePeak()} 的 dBFS 读数（给人看的）。 */
	public static double worstCasePeakDbfs() {
		return gainDb(worstCasePeak());
	}

	/** 还有多少余量（dB，正数 = 安全）。 */
	public static double headroomDb() {
		return -worstCasePeakDbfs();
	}

	/** 一行说清整条混音链 —— 进游戏时打进日志，让"声音多大"变成可核对的读数而不是印象。 */
	public static String summary() {
		return String.format(Locale.ROOT,
				"总出手量 %.2f（%+.1f dB）· 层基线 牵引 ×%.2f（%+.1f dB）/ 走行 ×%.2f（%+.1f dB）"
						+ " · 交付峰值 dBFS 牵引 %.2f / 轮轨 %.2f / 风噪 %.2f（实测）"
						+ " · 三层同时到顶 %.2f dBFS（余量 %.1f dB）"
						+ " · ★ 牵引层满力输出 %.2f dBFS（= 烘焙峰值 %+.1f dB）",
				MASTER, gainDb(MASTER),
				TRACTION, gainDb(TRACTION), RUNNING, gainDb(RUNNING),
				MOTOR_PEAK_DBFS, ROLL_PEAK_DBFS, AERO_PEAK_DBFS,
				worstCasePeakDbfs(), headroomDb(),
				tractionCeilingDbfs(), gainDb(MASTER * TRACTION));
	}

	/** 由电平比（线性）算 dB。 */
	public static double gainDb(double linear) {
		return linear > 0 ? 20 * Math.log10(linear) : Double.NEGATIVE_INFINITY;
	}

	/** dBFS → 线性幅度。 */
	public static double dbToLinear(double dbfs) {
		return Math.pow(10, dbfs / 20);
	}

	/**
	 * 层增益的夹子：下界 0、上界 {@link #MAX_LAYER_GAIN}。
	 *
	 * <p>注意**不是** {@code clamp01} —— 播放端能放大（类注释的实测），所以 1.0 不是天花板。</p>
	 */
	private static double clampLayer(double value) {
		return Math.max(0, Math.min(MAX_LAYER_GAIN, value));
	}
}

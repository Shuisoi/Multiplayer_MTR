package org.mtr.mod.sound;

import javax.annotation.Nullable;
import java.util.List;

/**
 * **MMTR 牵引音：游戏端的纯模型**。
 *
 * <h2>它在整条链里的位置</h2>
 * <p>合成（加法正弦 bank）在离线做掉了 —— 见 {@code mmtr/tools/sound-studio/bake.mjs}，
 * 产出若干条 .ogg 循环 + 一份 {@code mmtr_traction.json} 清单。游戏端**不做合成**，
 * 因为 Minecraft 的音效接口只给「事件 + pitch + volume」，没有自定义合成器。</p>
 *
 * <p>于是游戏端只剩三件事，也就是本类的内容：</p>
 * <ol>
 *   <li>由（速度、带符号牵引需求）算出**基波频率 f₁**（含滑差）；</li>
 *   <li>在清单里找 **f₁ 最接近的那一档** ⇒ 决定播哪个 ogg；</li>
 *   <li>算**变调比例** {@code rate = f₁ / 该档的 f₁}。</li>
 * </ol>
 *
 * <h2>为什么"挑最近的一档 + 变调"在数学上是精确的</h2>
 * <p>同步调制段里载波与基波**严格成正比**（{@code f_c = 分周数 × f₁}），所以把一整段按比例
 * 变调播放，等于把载波与所有边带一起按比例搬移 —— 与真车的规律一致。
 * 档位存在的唯一理由是把变调幅度限制在小范围内（见 {@link #MAX_PITCH} 与
 * {@link Selection#rate()}），而不是"因为变调不准"。</p>
 *
 * <h2>本类刻意零依赖</h2>
 * <p>不 import Gson、不 import Minecraft、不 import 引擎 core —— 只有 {@code java.*}。
 * 这样它能被 {@code javac} 裸编译、被 JUnit 与命令行直接跑，
 * 也让"模型算得对不对"与"资源包读得对不对"两件事互不牵连。</p>
 */
public final class MmtrTractionSoundModel {

	/**
	 * Minecraft 音频层对 pitch 的**硬夹取范围**（{@code SoundSystem.getAdjustedPitch}）。
	 * 超出这个范围的变调会被压平 ⇒ 档位设计必须让 rate 落在里面，且最好留足余量。
	 */
	public static final double MIN_PITCH = 0.5;
	public static final double MAX_PITCH = 2.0;

	/**
	 * 变调预算：单档内允许的变调量（行业惯例 ±5%~15%，见 notes/377）。
	 * 超出就该加档，而不是继续变调 —— {@code bake.mjs} 的 {@code --maxstep} 就是照这个定的。
	 */
	public static final double PITCH_BUDGET = 0.15;

	private MmtrTractionSoundModel() {
	}

	// -----------------------------------------------------------------------------------------
	// 车辆电气规格
	// -----------------------------------------------------------------------------------------

	/**
	 * 车辆电气/牵引规格。字段名与 {@code mmtr_traction.json} 的 {@code spec} 段一一对应，
	 * 也与 JS 侧 {@code model.mjs} 的 spec 同名 —— 两边不一致时应当在测试里红，而不是靠人记。
	 */
	public record Spec(
			int poleCount,
			double gearRatio,
			double wheelDiameterM,
			double slipHz,
			/** 再生制动的切除速度（km/h）：低于它电制动不投入，电机不励磁 ⇒ 没有牵引音。 */
			double regenCutoffKmh,
			double carrierMinHz,
			double carrierMaxHz,
			double rotorHzPerKmh,
			double breakpointKmh,
			double maxTractiveEffortN,
			double maxPowerW,
			/**
			 * 旧"负载轴"的力/功率混合速度（m/s）。
			 *
			 * <p>⚠ notes/403 续：音量改成**只由牵引力定**（{@link #forceAxis}）之后，这个量在
			 * **游戏端已经不用了**。它留在 record 里是因为它仍在清单的 {@code spec} 段里、
			 * 且离线链（{@code bake.py}）还在用它算烘焙电平 —— 删字段会让清单 schema 分叉。</p>
			 */
			double loadBlendSpeedMps
	) {
		/** 极对数 = 极数/2（滑差与齿轮啮合都要用）。 */
		public double polePairs() {
			return Math.max(1, poleCount / 2.0);
		}
	}

	/**
	 * 由几何/电气参数算 **每 km/h 的转频系数 k**（Hz per km/h）。
	 *
	 * <p>{@code k = 极数·齿轮比 / (22.62·动轮径 m)} —— 西门子「電車制御時の発生騒音」
	 * （騒音制御 29(4), 2005）式(1) 整理而来。这是"这台车跑到多快时电机叫多尖"的唯一系数。</p>
	 */
	public static double rotorHzPerKmh(int poleCount, double gearRatio, double wheelDiameterM) {
		if (!(wheelDiameterM > 0)) {
			return 0;
		}
		return (poleCount * gearRatio) / (22.62 * wheelDiameterM);
	}

	/** 转频（Hz）：电机机械转速对应的电气频率，{@code = k·v}。齿轮啮合与它相关。 */
	public static double rotorHz(double speedKmh, double rotorHzPerKmh) {
		return rotorHzPerKmh * Math.max(0, speedKmh);
	}

	/**
	 * **定子基波频率**（Hz）。
	 *
	 * <p>转差是"同样速度下牵引与再生音高不同"的物理来源：感生转子频率之上还要叠一个滑差频率，
	 * <b>力行时加、回生时减</b>（日立評論 61(5) 1979 原文「力行時には加算し、回生制動時には減算する」）。</p>
	 *
	 * <p>静止时 {@code rotorHz = 0}，但只要有励磁，{@code f₁ = 滑差} —— 这就是"起步那一下"有声音的原因；
	 * 而惰行（{@code demandSign == 0}）逆变器不励磁，**根本没有定子基波这回事**，返回 0。</p>
	 *
	 * <p>再生制动还有一个**切除速度**：低于它电制动根本不投入（BVE 的 {@code RegenerationLimit}，
	 * 本仓电制动三段式的"低速淡出"），电机不被励磁 ⇒ 返回 0。
	 * 不建这个量的话低速再生会算出一个接近 0 的 f₁，再被变调夹到 pitch 下限，音高完全走样。</p>
	 *
	 * @param demandSign &gt;0 牵引、&lt;0 再生制动、0 惰行
	 */
	public static double fundamentalHz(double speedKmh, int demandSign, Spec spec) {
		return fundamentalHz(speedKmh, demandSign, spec.rotorHzPerKmh(), spec.slipHz(), spec.regenCutoffKmh());
	}

	/** 见 {@link #fundamentalHz(double, int, Spec)}；拆出标量版本便于不构造 Spec 也能算。 */
	public static double fundamentalHz(double speedKmh, int demandSign, double rotorHzPerKmh, double slipHz) {
		return fundamentalHz(speedKmh, demandSign, rotorHzPerKmh, slipHz, 0);
	}

	/** 见 {@link #fundamentalHz(double, int, Spec)}。 */
	public static double fundamentalHz(double speedKmh, int demandSign, double rotorHzPerKmh, double slipHz, double regenCutoffKmh) {
		final double rotor = rotorHz(speedKmh, rotorHzPerKmh);
		if (demandSign > 0) {
			return rotor + slipHz;
		}
		if (demandSign < 0) {
			if (Math.max(0, speedKmh) < regenCutoffKmh) {
				return 0;
			}
			return Math.max(0, rotor - slipHz);
		}
		return 0;
	}

	/**
	 * **力轴**：驱动牵引音量的量（0..1）—— **只由电机牵引力定，速度不进这一支**。
	 *
	 * <h2>为什么把原来那条"负载轴"换掉（notes/403 续）</h2>
	 * <p>用户口径把两个轴**分开**说清了：<b>当前速度决定"播哪一段"，电机牵引力决定"播多大"</b>。
	 * 而原来那条 {@code loadAxis = max(|P|/P_max, ratio·exp(−v/vBlend))} 把速度**混进了音量**：</p>
	 * <ul>
	 *   <li>功率项 {@code |P| = |F|·v} 里带着 v ⇒ 同一个牵引力、速度不同音量就不同；</li>
	 *   <li>级位项被 {@code exp(−v/vBlend)} 按速度削 ⇒ 速度越高，牵引力对音量的影响越小；</li>
	 *   <li>低速段还被 {@code gate.vSmokeKmh} **按速度把音量压成 0** —— 那件事属于"段存不存在"，
	 *       不属于"音量多大"，已挪回选段那一边（见 {@link #selectFor}）。</li>
	 * </ul>
	 *
	 * <p>换掉之后两个轴正交：{@code f₁ = f(速度)} 选段、{@code |F| / F_ref} 定音量。
	 * 顺带一处实打实的变化 —— **低速段音量回到满值**（满级位：20 km/h 旧律 0.55 ⇒ 新律 1.0 = **+5.2 dB**；
	 * 10 km/h 旧律 0.29 ⇒ 1.0 = **+10.9 dB**；60 km/h 以上两者都到顶、听不出差别）。
	 * 那正是"起步与低速听不到励磁音"最刺眼的地方。</p>
	 *
	 * @param motorForceN 电机出力（N，牵引为正、电阻制动为负；取绝对值）
	 * @param spec        规格。参考力取 {@code maxTractiveEffortN}（沿用已有的那个 [官方] 额定位，
	 *                    **不新增自由参数** —— 它同时是 <code>effortRatio</code> 的分母，两处同源）
	 */
	public static double forceAxis(double motorForceN, Spec spec) {
		final double reference = spec != null && spec.maxTractiveEffortN() > 0 ? spec.maxTractiveEffortN() : 1;
		return clamp01(Math.abs(motorForceN) / reference);
	}

	/**
	 * 是否在励磁（有牵引音）—— 现在的判据只有"牵引力是不是 0"（notes/403 续）。
	 *
	 * <p>原来它看 {@code max(|P|/P_max, ratio)}：那是"负载轴"时代的口径，现在音量与选段都与它无关，
	 * 它只用来回答"这一刻该不该出声"。</p>
	 */
	public static boolean isExcited(double forceAxis) {
		return forceAxis > 1e-4;
	}

	/**
	 * **这节车该不该把牵引音压成静音**（拖车不该响）。
	 *
	 * <p>为什么必须是三态而不是 {@code !powered}：引擎里"这节车有没有动力"是**三态**的
	 * （notes/271 片 1）—— 数据里**写了** {@code mmtrPowered:false} 的才是被牵引的挂车；
	 * **根本没写**这个键的车（例如 BR101 这类没配车底清单的）读出来同样是 {@code false}，
	 * 但它其实是有动力的。把"没声明"当成"无动力"会把整批老车静音。</p>
	 *
	 * <p>所以口径是：**只有"显式声明过、且声明为无动力"才静音**；
	 * 没声明过的一律按有动力处理（宁可多响，也不要静默地少响）。</p>
	 *
	 * @param poweredDeclared 数据里到底写没写 {@code mmtrPowered}（{@code VehicleCar.isMmtrPoweredDeclared()}）
	 * @param powered         {@code VehicleCar.getMmtrPowered()}
	 */
	public static boolean shouldMuteMotorSound(boolean poweredDeclared, boolean powered) {
		return poweredDeclared && !powered;
	}

	/**
	 * **这一刻这一节车要不要把牵引音压成静音** = "它是被牵引的挂车" <b>且</b>
	 * "听者不在它的操纵位上"（notes/403 续）。
	 *
	 * <p>为什么要有第二个条件 —— 现场口径是"**没有电机励磁音**"，真因就在这里：
	 * SAF420 的编组是 {@code Tc–M×6–T×2–Tc}，**司机坐的正好是 Tc 控制拖车**
	 * （世界清单里 {@code saf420cab_a: powered=false / saf420_trailer}）。
	 * 按"挂车一律静音"办，司机所在的那一节就被静音，而真正响的 6 节动车在 20–125 m 外、
	 * 又被距离衰减削掉 ⇒ 司机听到的是一片安静。</p>
	 *
	 * <p>豁免是有道理的，而不是"多算一台电机"：喂给音效的出力
	 * （{@code Vehicle.getMmtrMotorForceN()}）本来就是**整列口径**，每节车喂的是同一个数 ——
	 * 谁在操纵，就该由谁听到这条列车级读数。与"驾驶位忽略距离衰减"是同一条口径的两半。</p>
	 *
	 * @param hauledWagon        {@link #shouldMuteMotorSound(boolean, boolean)} 的结果（是挂车）
	 * @param listenerAtControls 听者是否正坐在这节车的操纵位上
	 */
	public static boolean muteTractionSound(boolean hauledWagon, boolean listenerAtControls) {
		return hauledWagon && !listenerAtControls;
	}

	// -----------------------------------------------------------------------------------------
	// 烘焙清单
	// -----------------------------------------------------------------------------------------

	/** 一档烘焙出来的循环。 */
	public record Entry(String file, double f1Hz, String mode, int pulses, double carrierHz) {
	}

	/** 一整套烘焙音效：{@code spec} + 27 档牵引循环 + 可选的齿轮层 + 可选的走行层。 */
	public record SoundSet(Spec spec, List<Entry> entries, GearLayer gear,
			List<RunningLayer> runningLayers, double vSmokeKmh) {

		public SoundSet {
			entries = List.copyOf(entries);
			runningLayers = List.copyOf(runningLayers);
		}

		/**
		 * 没有齿轮层/走行层的三参构造（旧清单、以及大量只用牵引层的用例都走这条）。
		 * 保留它而不是让调用方写 {@code null}：这两层是**可选**的，不是"忘了填"。
		 */
		public SoundSet(Spec spec, List<Entry> entries, GearLayer gear) {
			this(spec, entries, gear, List.of(), 0);
		}

		public SoundSet(Spec spec, List<Entry> entries) {
			this(spec, entries, null, List.of(), 0);
		}

		/** 有齿轮层吗。 */
		public boolean hasGear() {
			return gear != null;
		}

		/** 有走行层吗。 */
		public boolean hasRunning() {
			return !runningLayers.isEmpty();
		}

		public int size() {
			return entries.size();
		}

		/** 是否可用（清单存在且非空）。 */
		public boolean usable() {
			return !entries.isEmpty() && spec != null && spec.rotorHzPerKmh() > 0;
		}
	}

	/**
	 * **齿轮啮合层**（第二层）。
	 *
	 * <p>为什么是独立一层：它唱的是**机械转频**（不含滑差），而牵引层的基准是定子基波
	 * {@code f₁ = 转频 ± 滑差}。两者差一个滑差 ⇒ 变调比例不同 ⇒ 不能共用一条循环、一个 rate。
	 * 调研也明确指出这一层必须合成（它严格随转频成比例，采样做不出无缝变速）。</p>
	 *
	 * <p>{@code playbackRate = 速度 / refSpeedKmh} —— 因为转频与速度成正比，这个比值
	 * 恰好等于机械转频之比，所以这一层的变调在物理上是**严格线性**的。</p>
	 *
	 * @param minKmh 低于它 playbackRate 会出 Minecraft 的 [0.5, 2.0] ⇒ **静音**，
	 *               宁可没有齿轮音，也不要播一个音高错的（速率错在齿轮音上比没有更难听）。
	 * @param maxKmh 高于它同理静音。
	 */
	public record GearLayer(
			String file,
			double refSpeedKmh,
			double rotorRefHz,
			int teeth,
			double gearMeshHz,
			double minKmh,
			double maxKmh
	) {
	}

	/**
	 * 齿轮层的变调比例：{@code 速度 / 参考速度}。
	 *
	 * <p>注意**不做夹取**：出区间时调用方应当静音（见 {@link #gearAudible}），
	 * 夹取会让齿轮音的音高与实际转频脱节。</p>
	 */
	public static double gearRate(double speedKmh, GearLayer gear) {
		return gear == null || gear.refSpeedKmh() <= 0 ? 1 : Math.max(0, speedKmh) / gear.refSpeedKmh();
	}

	/** 齿轮层此刻是否该出声（速度在变调可用区间内）。 */
	public static boolean gearAudible(double speedKmh, GearLayer gear) {
		return gear != null && speedKmh >= gear.minKmh() && speedKmh <= gear.maxKmh();
	}

	/**
	 * 选中结果：播哪一档 + 变调多少 + **当时用的 f₁**。
	 *
	 * <p>把 f₁ 一并带出来是刻意的：播放器要打换档日志、将来还要做多普勒/滤波，
	 * 若在播放器里另算一遍，就又成了"两处各算一套"（本仓最恨的那类现场）。</p>
	 */
	public record Selection(Entry entry, double rate, double f1Hz) {

		/** 变调量是否落在行业预算内（±15%）。超了说明档位铺得不够密。 */
		public boolean withinBudget() {
			return Math.abs(rate - 1) <= PITCH_BUDGET + 1e-9;
		}

		/** 是否会被 Minecraft 的 pitch 夹取动到（夹到就会失真）。 */
		public boolean withinEngineClamp() {
			return rate >= MIN_PITCH && rate <= MAX_PITCH;
		}
	}

	/**
	 * 在清单里找 **f₁ 最接近的那一档**，并给出变调比例。
	 *
	 * <p>找"最近"而不是"按模式匹配"是刻意的：模式切换点已经烘进档位本身（{@code bake.mjs}
	 * 沿 f₁ 细扫分段），所以游戏端不需要再判定异步/同步/分周 —— 状态机只活在 JS 一侧，
	 * 也就不存在"两份实现各说各话"。代价是换档点被量化到档位边界，
	 * 所以烘焙时把档位铺到变调 ≤ {@link #PITCH_BUDGET}，量化误差就听不出来。</p>
	 *
	 * @return {@code null} 表示清单为空或 f₁ 无效
	 */
	@Nullable
	public static Selection select(SoundSet soundSet, double fundamentalHz) {
		if (soundSet == null || soundSet.entries().isEmpty() || !(fundamentalHz > 0)) {
			return null;
		}
		Entry best = null;
		double bestDistance = Double.MAX_VALUE;
		for (final Entry entry : soundSet.entries()) {
			final double distance = Math.abs(entry.f1Hz() - fundamentalHz);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = entry;
			}
		}
		if (best == null || !(best.f1Hz() > 0)) {
			return null;
		}
		// 夹进 Minecraft 的硬范围：宁可音高不准，也不要被音频层夹出非线性失真
		final double rate = Math.max(MIN_PITCH, Math.min(MAX_PITCH, fundamentalHz / best.f1Hz()));
		return new Selection(best, rate, fundamentalHz);
	}

	/**
	 * 一把算完游戏端每帧要用的**选段**。
	 *
	 * <p>★ **这一支只由速度定**（notes/403 续）：选哪一段 = {@code f₁} = 速度的函数；
	 * "段存不存在" = {@code gate.vSmokeKmh}（音阶门限是**段的合法性**，不是音量的开关 ——
	 * 少了它，低速会挑到最低那一档而变调比远小于 0.5、被 MC 硬夹到 0.5，听起来是一段音高走样的嗡嗡声）。
	 * **音量不在这里**：它归 {@code MmtrSoundMix}，由牵引力定。</p>
	 *
	 * <p>为什么不再用"有没有励磁"来决定返回 {@code null}：那会让**力的变化也去换段** ——
	 * 松手柄的一瞬间旧段被淡出、再给力又重新起播，两件事被绑在一起。
	 * 现在松手柄只是音量落到 0（**段不变**），再给力就从同一段接着响。</p>
	 *
	 * <p>一处诚实的例外：{@code demandSign}（牵引 / 再生）仍取自出力的**符号**，
	 * 因为带滑差的音效集里 {@code f₁ = 转频 ± 滑差}、符号会挪动频线（那是物理）。
	 * 本音效集 {@code slipHz = 0} ⇒ 选段严格只由速度定。</p>
	 *
	 * @param speedKmh    速度
	 * @param motorPowerW 电机做功（W，牵引正 / 电阻制动负）—— 只用来取符号
	 * @param ratio       手柄比例 0..1
	 * @return 选中结果；段不合法（低速）或清单不可用时为 {@code null}
	 */
	@Nullable
	public static Selection selectFor(double speedKmh, double motorPowerW, double ratio, SoundSet soundSet) {
		if (soundSet == null || !soundSet.usable()) {
			return null;
		}
		if (!motorAudible(speedKmh, soundSet.vSmokeKmh())) {
			return null;                       // 这一段不合法，与"音量多大"无关
		}
		final int demandSign = motorPowerW > 0 ? 1 : motorPowerW < 0 ? -1 : (ratio > 0 ? 1 : 0);
		return select(soundSet, fundamentalHz(speedKmh, demandSign, soundSet.spec()));
	}

	/**
	 * **音阶门限**（km/h）：低于它电机没有励磁音。
	 *
	 * <p>为什么必须有：低速时 {@code f₁ = v·rotorHzPerKmh} 会掉到几 Hz，挑到最低那一档后
	 * 变调比会远小于 0.5 并被 Minecraft 硬夹到 0.5 ⇒ 听起来是一段音高完全走样的嗡嗡声。
	 * 本仓 {@code game/select.py} 的 {@code V_SMOKE = 5 km/h} 就是这条；
	 * 引擎原模型没有这个量（{@code isExcited} 只看有没有出力），所以清单里用可选的
	 * {@code gate.vSmokeKmh} 补上。</p>
	 *
	 * @param vSmokeKmh ≤ 0 表示不设门限（旧清单的行为，保持向后兼容）
	 */
	public static boolean motorAudible(double speedKmh, double vSmokeKmh) {
		return vSmokeKmh <= 0 || speedKmh >= vSmokeKmh;
	}

	// -----------------------------------------------------------------------------------------
	// 走行层（由速度强绑定：轮轨 + 风噪）
	// -----------------------------------------------------------------------------------------

	/**
	 * 走行层的一档：烘在**该档的上几何边** {@code vBakeKmh}，播放时靠变调降到当前速度。
	 *
	 * @param vBakeKmh  烘焙速度（= 档中心速度 × √档比）⇒ {@code v/vBake ≤ 1} 恒成立
	 * @param vCentreKmh 档中心对应的速度（**选档**用这个，与 {@code select_running.py} 同一张表）
	 */
	public record RunningEntry(String file, double vBakeKmh, double vCentreKmh) {
	}

	/**
	 * 走行层的一条**层**（轮轨 / 风噪各一条）。
	 *
	 * <p>它与牵引层的根本差别：**没有牵引功率这个入参**。响度与频谱都由速度定 ——
	 * 这是用户对"由当前速度强绑定"的定义，不是漏写。</p>
	 *
	 * @param lawB             速度律指数：音量 = {@code (v/vBake)^(lawB/20)}。轮轨 30、风噪 60
	 * @param pitchFollowsSpeed 形状是否随速度移动。轮轨 true（粗度按**波长**定义 ⇒ f = v/λ）；风噪 false
	 * @param vSmokeKmh        低于它整层静音（与电机层同一个门限）
	 * @param masterVolume     这一层相对总音量的比例（两层各自的绝对刻度在这里）
	 */
	public record RunningLayer(String id, double lawB, boolean pitchFollowsSpeed,
			double vSmokeKmh, double masterVolume, List<RunningEntry> entries) {

		public RunningLayer {
			entries = List.copyOf(entries);
		}

		public boolean usable() {
			return !entries.isEmpty() && lawB > 0;
		}
	}

	/** 走行层的选中结果：播哪一档 + 变调 + **音量**（这一层的音量只由速度定）。 */
	public record RunningSelection(RunningEntry entry, double rate, double volume) {
	}

	/**
	 * 走行层选档：在 {@code v / vCentre} 的**对数**意义上取最近的一档。
	 *
	 * <p>为什么是对数而不是线性：档位是几何阶梯，只有对数距离才让"最差变调"在每一档里
	 * 对称（这与 {@code game/select_running.py} 的 {@code argmin |ln(f₁/centre)|} 逐位一致；
	 * 两者在几何阶梯下只在每档约 0.3% 的窄缝里不同，最差变调 1.083 对 1.086）。</p>
	 *
	 * <p>音量 = {@code (v/vBake)^(lawB/20)}：因为烘点是档的**上几何边**，
	 * 这个比值恒 ≤ 1 ⇒ **游戏侧永不需要 boost**。速度超过最高档的烘点时它会 &gt; 1，
	 * 这里夹到 1（那已在设计最高速度之外）。</p>
	 *
	 * @return {@code null} 表示这一刻不出声（低于门限、层不可用、或清单为空）
	 */
	@Nullable
	public static RunningSelection selectRunning(RunningLayer layer, double speedKmh) {
		if (layer == null || !layer.usable() || !(speedKmh >= layer.vSmokeKmh())) {
			return null;
		}
		RunningEntry best = null;
		double bestDistance = Double.MAX_VALUE;
		for (final RunningEntry entry : layer.entries()) {
			if (!(entry.vCentreKmh() > 0)) {
				continue;
			}
			final double distance = Math.abs(Math.log(Math.max(speedKmh, 1e-9) / entry.vCentreKmh()));
			if (distance < bestDistance) {
				bestDistance = distance;
				best = entry;
			}
		}
		if (best == null || !(best.vBakeKmh() > 0)) {
			return null;
		}
		final double ratio = speedKmh / best.vBakeKmh();
		final double rate = layer.pitchFollowsSpeed()
				? Math.max(MIN_PITCH, Math.min(MAX_PITCH, ratio))
				: 1;
		final double volume = clamp01(Math.pow(ratio, layer.lawB() / 20.0));
		return new RunningSelection(best, rate, volume);
	}

	/*
	 * ★ 这里原来有一个 `volume(motorPowerW, ratio, speedKmh, spec, masterVolume)` =
	 * "负载轴 × 总音量"。已删除（2026-10-08）：**层内律**（负载轴）归本类，**出手量**
	 * （总音量、层基线、余量核算）归 {@link MmtrSoundMix} —— 一个量只在一个地方乘，
	 * 否则"声音到底该多大"又会变成两处各算一套（本仓最恨的那类现场）。
	 * 调用方现在写：`MmtrSoundMix.tractionVolume(forceAxis(...))`。
	 */

	private static double clamp01(double value) {
		return Math.max(0, Math.min(1, value));
	}
}

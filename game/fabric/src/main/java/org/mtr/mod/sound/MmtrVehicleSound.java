package org.mtr.mod.sound;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.SoundEvent;
import org.mtr.mod.Init;
import org.mtr.mod.sound.MmtrTractionSoundModel.Selection;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * **MMTR 牵引音（游戏端播放器）**。
 *
 * <h2>它怎么出声</h2>
 * <p>合成在离线做掉了（27 档无缝 ogg）。本类每帧只做三件事：
 * 由（速度、电机出力）算出基波 f₁ → 在清单里挑 f₁ 最近的一档 → 用 {@code playbackRate} 把
 * 那一档变调到当前 f₁。因为同一档内载波与基波**严格成正比**，这个变调在数学上是精确的。</p>
 *
 * <h2>三层播放器（各自一套基准）</h2>
 * <table>
 *   <tr><th>层</th><th>选档依据</th><th>变调</th><th>音量</th></tr>
 *   <tr><td>牵引（磁励音）</td><td>f₁ = v·rotorHzPerKmh</td><td>f₁/该档 f₁</td>
 *       <td><b>力轴</b> |F|/额定牵引力 × 层基线 × 总出手量</td></tr>
 *   <tr><td>齿轮</td><td>—（单条循环）</td><td>v / 参考速度</td><td>力轴 × 齿轮比例</td></tr>
 *   <tr><td><b>走行</b>（轮轨 / 风噪）</td><td>v（**没有**出力入参）</td>
 *       <td>v/vBake 或恒定 1</td><td>(v/vBake)^(lawB/20)</td></tr>
 * </table>
 *
 * <h2>★ 两个轴是**正交**的（notes/403 续）</h2>
 * <p>用户口径：<b>当前速度决定"播哪一段"，电机牵引力决定"播多大"</b>。本类现在就是这么写的 ——</p>
 * <ul>
 *   <li><b>选段</b>看速度：{@code selectFor} 里只有 {@code f₁ = f(速度)} 与"段合不合法"
 *       （{@code gate.vSmokeKmh}），**没有任何音量判断**；</li>
 *   <li><b>音量</b>看牵引力：{@code MmtrSoundMix.tractionVolume(力轴)} 的入参里**没有速度**。</li>
 * </ul>
 * <p>旧口径把两者糊在一起（{@code loadAxis = max(|P|/P_max, ratio·exp(−v/vBlend))} 里带着 v，
 * 低速还被门限按速度把音量压成 0），现场就是"牵引力变了声音不怎么变、低速干脆没有励磁音"。</p>
 * <p>走行层是本仓第 7 轮加的（用户口径：「由当前速度强绑定的轮轨、风噪音」）。
 * 它之所以必须与牵引层分开，是因为那一层的响度**不看**牵引功率，只由速度定 ——
 * 共用一条循环、一个音量口径都会把这个性质破坏掉。</p>
 *
 * <h2>★ 音量（第 3 级）不在这里</h2>
 * <p>本类只算<b>层内律</b>（负载轴 / 速度律 / 门限，0..1）；把它换算成**出手量**的那一级
 * ——总出手量、层基线、以及"各层 ogg 实测多少 dBFS、还能不能更响"的余量核算——全在
 * {@link MmtrSoundMix} 一处。那张表就是"声音到底多大"的唯一答案（notes/403）。</p>
 *
 * <h2>输入从哪来</h2>
 * <p>速度由 {@code playMotorSound} 的参数直接给（单位 m/ms —— MTR 的口径，
 * 见 {@code BveVehicleSound} 里 {@code speed * 1000 = m/s}）；**电机出力**由
 * {@link #feedDemand(double)} 喂进来，值就是引擎快照里的
 * {@code VehicleExtraData.getMmtrMotorForceN()}（N，牵引为正、电阻制动为负）。</p>
 *
 * <p>用出力而不是加速度是刻意的：出力是**权威**的电机读数（notes/250 就是拿它做 HUD 的
 * "电机做功"），惰行时它自然为 0、起步静止时它不为 0 —— 两个最容易搞错的情形都自动对。
 * 若改用加速度/速度差判"在不在牵引"，惰行（阻力导致的减速）会被误判成再生制动。</p>
 *
 * <h2>换档怎么不爆音</h2>
 * <p>挑中的档变了就换一条 {@link VehicleLoopingSoundInstance}：新档起播，旧档在几十毫秒内淡出。
 * 层内相邻档用等功率淡化（噪声/不同音色，线性淡化会在中点掉 3 dB）。
 * 淡出的**最后一帧仍有权重**，所以必须显式把旧档置 0，否则它会永久停在 26% 音量继续播。</p>
 */
public class MmtrVehicleSound extends VehicleSoundBase {

	/*
	 * ★ 音量常数**不在这里**：全部搬到 {@link MmtrSoundMix}（总出手量、层基线、实测交付电平、
	 * 峰值余量核算）。本类只负责"每帧算出层内律、把层内律交给混音台换算成出手量"。
	 *
	 * 这里原来挂着一条注释说"ogg 烘焙时已按全局峰值归一到 0.89" —— **那是错的**，
	 * 至少对这一套（kei2100）是错的：实测交付峰值是牵引 −25.47 / 轮轨 −11.19 / 风噪 −10.00 dBFS，
	 * 离 0.89（≈ −1 dBFS）差得远。错的常数注释正是"声音偏低查不出原因"的根源之一，已删。
	 */

	/** 换档淡出持续多少帧（60 fps 下 ≈0.1 s）。 */
	private static final int FADE_FRAMES = 6;
	/**
	 * 走行层的换档淡化帧数（60 fps ⇒ 50 ms）。
	 *
	 * <p>比牵引层短，因为走行层的档是**同一族噪声**的相邻台阶：淡化要的是"不咔"，
	 * 拉太长会把两档的谱同时听见（听起来像梳状染色）。牵引层那边是不同音色的纯音簇，所以更长。</p>
	 */
	private static final int RUNNING_FADE_FRAMES = 3;

	private final MmtrTractionSoundSet soundSet;
	private final VehicleLoopingSoundInstance[] loops;
	/** 齿轮层：**独立的第二个播放器**（它的变调基准是机械转频，不是 f₁）。 */
	@Nullable
	private VehicleLoopingSoundInstance gearLoop;
	/** 走行层：**每条层一个播放器**（轮轨、风噪），各用各的速度律与变调基准。 */
	private final List<RunningPlayer> runningPlayers = new ArrayList<>();
	/** 每帧喂进来的电机出力（N）。 */
	private double motorForceN;
	/**
	 * **听者是不是就坐在这节车的操纵位上**（客户端本地事实，由
	 * {@code VehicleExtension.playMotorSound} 每帧喂进来）。
	 *
	 * <p>为 true 时本车所有层都报 {@code AttenuationType.NONE} —— 人已经在驾驶室里，
	 * 不该再被"这节车中心离我 8–9.75 m"削一遍。判据本身只有一份：
	 * {@code MmtrDriverSeat}（它同时决定能不能操作手柄、要不要向引擎报"我是司机"）。</p>
	 */
	private boolean listenerAtControls;

	/** "为什么这一刻没有励磁音"的取证：连续静音的帧数（60 fps ⇒ 30 帧 ≈ 0.5 s 才算"真的没在响"）。 */
	private static final int SILENT_REPORT_FRAMES = 30;
	private int silentFrames;
	private String pendingSilentReason = "";
	/** 已经说过的那一条原因 —— 同一个原因只说一次，换了原因再报。上限是"每套音效集几条"。 */
	private String reportedSilentReason = "";

	private int activeIndex = -1;
	private int fadingIndex = -1;
	private int fadeLeft;
	private float fadeFrom;
	/** 淡出中那一档的变调比（与走行层同理：不能写死 1，否则淡出时会听见一次音高跳）。 */
	private float fadingRate = 1;
	/** 当前生效档的变调比（换档时把它交给淡出的那一条，避免淡出瞬间音高跳到 1）。 */
	private float lastRate = 1;
	/**
	 * 当前生效档**上一帧真正送出去的音量**（牵引层那一支的出手量）。
	 *
	 * <p>换档时淡出的那一条要**从这一档当时的音量**往下走，而不是从写死的 1.0 走：
	 * 负载轴小于 1 时（车厢里、半级位）从 1.0 开始淡出，会在换档那一瞬间听见一个**音量凸起**。
	 * 这是把总出手量顶到 1.0 之后才显出来的（原来 0.45 把它压住了）。</p>
	 */
	private float lastActiveVolume;

	public MmtrVehicleSound(MmtrTractionSoundSet soundSet) {
		this.soundSet = soundSet;
		this.loops = new VehicleLoopingSoundInstance[soundSet.model.entries().size()];
		// 走行层：模型里的层与事件表同序（都在 MmtrTractionSoundSet.load 里一次构出来）
		for (int i = 0; i < soundSet.model.runningLayers().size() && i < soundSet.runningEventLayers.size(); i++) {
			runningPlayers.add(new RunningPlayer(soundSet.model.runningLayers().get(i),
					soundSet.runningEventLayers.get(i).events()));
		}
	}

	@Override
	public void feedDemand(double motorForceN) {
		this.motorForceN = motorForceN;
	}

	/**
	 * **听者是不是就坐在这节车的操纵位上** ⇒ 本车所有层忽略距离衰减。
	 *
	 * <p>口径一变就把新口径推给**已经建出来的每一条**循环（牵引档、齿轮、两条走行层的每一档）；
	 * 之后新建的实例在 {@link #newLoop} 里带上同一个口径。
	 * 真正"重新起播"的动作在 {@link VehicleLoopingSoundInstance#setData} 里做
	 * （MC 只在 play() 那一刻把距离模型写给 OpenAL）。</p>
	 */
	@Override
	public void setListenerAtControls(boolean atControls) {
		if (listenerAtControls == atControls) {
			return;
		}
		listenerAtControls = atControls;
		// 口径切换留一行痕：这是"驾驶位满音量"这条功能到底有没有生效的现场读数
		Init.LOGGER.info("[MMTR-SND] {} 距离衰减：{}", soundSet.baseName,
				atControls ? "听者在操纵位上 ⇒ 整级旁路（满音量）" : "回到常规距离衰减");
		for (final VehicleLoopingSoundInstance loop : loops) {
			if (loop != null) {
				loop.setNoAttenuation(atControls);
			}
		}
		if (gearLoop != null) {
			gearLoop.setNoAttenuation(atControls);
		}
		for (final RunningPlayer player : runningPlayers) {
			player.setNoAttenuation(atControls);
		}
	}

	/** 建一条循环并带上当前的距离衰减口径 —— **所有**实例都必须走这里，否则会漏掉口径。 */
	private VehicleLoopingSoundInstance newLoop(SoundEvent event) {
		final VehicleLoopingSoundInstance loop = new VehicleLoopingSoundInstance(event);
		loop.setNoAttenuation(listenerAtControls);
		return loop;
	}

	/**
	 * **为什么这一刻选不出段** —— 把"没声音"从一个没法回答的问题变成日志里的一句人话。
	 *
	 * <p>选段只由速度定，所以这里只有两种来源：① **在音阶门限以下**（{@code gate.vSmokeKmh} ——
	 * 这是设计：这一段本来就不合法，硬播会是音高走样的嗡嗡声）；② 清单不可用。
	 * （"没励磁"不在这里：它不影响选段，只把音量落到 0，见 {@code playMotorSound} 里那一支。）</p>
	 */
	private String explainNoSelection(double kmh) {
		if (!soundSet.model.usable()) {
			return "清单里一档都没有、或 spec 无效（这个音效集不可用）";
		}
		if (!MmtrTractionSoundModel.motorAudible(kmh, soundSet.model.vSmokeKmh())) {
			return String.format("在音阶门限以下：v=%.1f km/h < gate.vSmokeKmh=%.1f km/h"
							+ "（这是设计：这一段不合法，播放会音高走样；本清单 slipHz=%.1f ⇒ 静止段没有定子基波）",
					kmh, soundSet.model.vSmokeKmh(), soundSet.model.spec().slipHz());
		}
		return String.format("基波算出来是 0：v=%.1f km/h、清单 slipHz=%.1f",
				kmh, soundSet.model.spec().slipHz());
	}

	/**
	 * 静音原因**变了**并且**持续了**才说一次；同一个原因一辈子只说一次。
	 *
	 * <p>上限因此是"每套音效集几条"，绝不刷屏 —— 但"进游戏没声音"从此有据可查
	 * （与 notes/217 的"扣车给司机可见理由"同一个口径：静默失败要有一条人话）。</p>
	 */
	private void reportTractionState(@Nullable String reason) {
		if (reason == null) {
			silentFrames = 0;
			pendingSilentReason = "";
			return;
		}
		if (reason.equals(reportedSilentReason)) {
			return;
		}
		if (reason.equals(pendingSilentReason)) {
			silentFrames++;
		} else {
			pendingSilentReason = reason;
			silentFrames = 1;
		}
		if (silentFrames >= SILENT_REPORT_FRAMES) {
			reportedSilentReason = reason;
			Init.LOGGER.info("[MMTR-SND] {} 牵引层没在出声：{}", soundSet.baseName, reason);
		}
	}

	@Override
	public void playMotorSound(BlockPos blockPos, float speed, float speedChange, float acceleration, boolean isOnRoute) {
		// MTR 的速度口径是 m/ms：×1000 得 m/s，×3600 得 km/h（与 BveVehicleSound 一致）
		final double mps = Math.max(0, speed) * 1000;
		final double kmh = Math.max(0, speed) * 3600;
		final double powerW = motorForceN * mps;
		/*
		 * ★ 两个轴**分开**（notes/403 续）：速度进选段、牵引力进音量，互不串门。
		 *
		 *   选段 = MmtrTractionSoundModel.selectFor(kmh, …)   ← 只有速度（+ 出力的符号，见那边的注释）
		 *   音量 = MmtrSoundMix.tractionVolume(力轴)          ← 只有牵引力
		 *
		 * 力轴就是 |F| / maxTractiveEffortN（已有的 [官方] 额定位，不新增参数）。
		 */
		final double forceAxis = MmtrTractionSoundModel.forceAxis(motorForceN, soundSet.model.spec());

		final Selection selection = MmtrTractionSoundModel.selectFor(kmh, powerW, forceAxis, soundSet.model);
		final int wanted = selection == null ? -1 : indexOf(selection);

		if (wanted != activeIndex) {
			// 换档：旧档进入淡出，新档接手
			if (activeIndex >= 0 && activeIndex < loops.length && loops[activeIndex] != null) {
				fadingIndex = activeIndex;
				fadeLeft = FADE_FRAMES;
				// 从**这一档上一帧真正的音量**往下淡，不从写死的 1.0 —— 见 lastActiveVolume 的注释
				fadeFrom = lastActiveVolume;
				fadingRate = lastRate;
			}
			activeIndex = wanted;
			/*
			 * 换档时打一条日志。**这是有上限的**（最多 = 档数 27 条，实际一趟车只经过 5~8 档），
			 * 所以不会刷屏；但它让"进游戏听"变成可对账的：
			 * 日志里的档序应当与 notes/378 §10 那张表一致（0 km/h 起 async，90 km/h 起 7-pulse…）。
			 * 新档的变调 ratio 也一并打出来 —— 它是"档位铺得够不够密"的现场读数。
			 */
			if (selection != null) {
				Init.LOGGER.info("[MMTR-SND] {} 换档 → {}（f₁={} Hz，变调 {}）",
						soundSet.baseName, selection.entry().file(),
						Math.round(selection.f1Hz() * 10) / 10.0,
						Math.round(selection.rate() * 1000) / 1000.0);
			}
		}

		if (selection != null && activeIndex >= 0) {
			/*
			 * 音量**只由牵引力定**（力轴 = |F|/额定牵引力）。这里没有速度项，也没有音阶门限 ——
			 * 门限管的是"这段合不合法"，已经在 selectFor 那一支里判过了。
			 *
			 * 没励磁（力轴 ≈ 0）时压成 0：**段不变**，所以再给力就从同一段接着响，不会重新起播。
			 */
			final boolean excited = MmtrTractionSoundModel.isExcited(forceAxis);
			final double level = excited ? MmtrSoundMix.tractionVolume(forceAxis) : 0;
			lastRate = (float) selection.rate();
			lastActiveVolume = (float) level;
			instance(activeIndex).setData((float) level, lastRate, blockPos);
			reportTractionState(excited
					? null
					: String.format("未被励磁 ⇒ 这一拍没有牵引力：电机出力 %.0f N（力轴 %.4f）"
									+ "。★ 若本节被判成无动力挂车，出力是**被主动喂成 0** 的"
									+ "（看上面那条「[MMTR-SND] 第 N 节被判成无动力挂车」，两回事）",
							motorForceN, forceAxis));
		} else {
			reportTractionState(explainNoSelection(kmh));
		}

		// 淡出那一条：等功率曲线（sin/cos）在不同音色层之间才不会在中点掉 3 dB
		if (fadingIndex >= 0 && fadingIndex < loops.length && loops[fadingIndex] != null) {
			final float progress = 1f - (float) fadeLeft / FADE_FRAMES;
			final float gain = (float) Math.cos(progress * Math.PI / 2) * fadeFrom;
			loops[fadingIndex].setData(gain, fadingRate, blockPos);
			fadeLeft--;
			if (fadeLeft <= 0) {
				/*
				 * ★ 收干净：淡出的**最后一帧仍有权重**（cos(5/6·π/2) = 0.26），
				 * 不显式置 0 的话，旧档会永久停在 26% 音量继续播 —— 每换一次档就多留一层，
				 * 跑一趟下来会把整条阶梯叠成一片糊。这行是必须的。
				 */
				loops[fadingIndex].setData(0, fadingRate, blockPos);
				fadingIndex = -1;
			}
		}

		// ★ 走行层：它**不看** powerW/effortRatio（那一层没有牵引功率入参，只由速度定）
		for (final RunningPlayer player : runningPlayers) {
			player.update(blockPos, kmh);
		}

		playGearLayer(blockPos, kmh);
	}

	/**
	 * **走行层播放器**（轮轨 / 风噪各一个实例）。
	 *
	 * <p>与牵引层播放器的三处根本差别：</p>
	 * <ol>
	 *   <li>选档与音量**只**由速度定（没有出力入参）—— 这是用户对"由当前速度强绑定"的定义；</li>
	 *   <li>变调基准是 {@code v/vBake}（轮轨）或恒定 1（风噪，形状不随速度移动）；</li>
	 *   <li>换档用**等功率**淡化（两条噪声互不相关；等幅会在中点掉 3 dB）。</li>
	 * </ol>
	 */
	private final class RunningPlayer {

		private final MmtrTractionSoundModel.RunningLayer layer;
		private final SoundEvent[] events;
		private final VehicleLoopingSoundInstance[] loops;
		/** 这一层自己的绝对刻度（清单里的 {@code masterVolume}）；总出手量由混音台给。 */
		private final double layerMaster;
		private int activeIndex = -1;
		private int fadingIndex = -1;
		private int fadeLeft;
		/** 淡出中那一档的变调比：**不能**像牵引层那样写死 1，否则淡出时会听见一次音高跳。 */
		private float fadingRate = 1;
		private float lastRate = 1;
		/** 淡出从"这一档上一帧真正的音量"开始（理由同牵引层的 lastActiveVolume）。 */
		private float fadeFrom;
		private float lastVolume;

		RunningPlayer(MmtrTractionSoundModel.RunningLayer layer, SoundEvent[] events) {
			this.layer = layer;
			this.events = events;
			this.loops = new VehicleLoopingSoundInstance[events.length];
			this.layerMaster = layer.masterVolume();
		}

		void setNoAttenuation(boolean noAttenuation) {
			for (final VehicleLoopingSoundInstance loop : loops) {
				if (loop != null) {
					loop.setNoAttenuation(noAttenuation);
				}
			}
		}

		void update(BlockPos blockPos, double kmh) {
			final MmtrTractionSoundModel.RunningSelection selection =
					MmtrTractionSoundModel.selectRunning(layer, kmh);
			final int wanted = selection == null ? -1 : indexOf(selection.entry());

			if (wanted != activeIndex) {
				if (activeIndex >= 0 && activeIndex < loops.length && loops[activeIndex] != null) {
					fadingIndex = activeIndex;
					fadeLeft = RUNNING_FADE_FRAMES;
					fadingRate = lastRate;
					fadeFrom = lastVolume;   // 从上一档真正的音量往下淡，不从写死的层刻度
				}
				activeIndex = wanted;
				if (selection != null) {
					// 与牵引层同一个理由：日志有上限（= 档数），却让"进游戏听"变成可对账的
					Init.LOGGER.info("[MMTR-SND] {} 走行层 {} 换档 → {}（v={} km/h，变调 {}，音量 {}）",
							soundSet.baseName, layer.id(), selection.entry().file(),
							Math.round(kmh * 10) / 10.0,
							Math.round(selection.rate() * 1000) / 1000.0,
							Math.round(MmtrSoundMix.runningVolume(selection.volume(), layerMaster) * 1000) / 1000.0);
				}
			}

			if (selection != null && activeIndex >= 0) {
				lastRate = (float) selection.rate();
				final double level = MmtrSoundMix.runningVolume(selection.volume(), layerMaster);
				lastVolume = (float) level;
				instance(activeIndex).setData((float) level, lastRate, blockPos);
			}

			if (fadingIndex >= 0 && fadingIndex < loops.length && loops[fadingIndex] != null) {
				final float progress = 1f - (float) fadeLeft / RUNNING_FADE_FRAMES;
				final float gain = (float) Math.cos(progress * Math.PI / 2) * fadeFrom;
				loops[fadingIndex].setData(gain, fadingRate, blockPos);
				fadeLeft--;
				if (fadeLeft <= 0) {
					loops[fadingIndex].setData(0, fadingRate, blockPos);   // 同上：收干净
					fadingIndex = -1;
				}
			}
		}

		void dispose() {
			for (final VehicleLoopingSoundInstance loop : loops) {
				if (loop != null) {
					loop.dispose();
				}
			}
		}

		private VehicleLoopingSoundInstance instance(int index) {
			if (loops[index] == null) {
				loops[index] = newLoop(events[index]);
			}
			return loops[index];
		}

		private int indexOf(MmtrTractionSoundModel.RunningEntry entry) {
			for (int i = 0; i < layer.entries().size(); i++) {
				if (layer.entries().get(i) == entry) {
					return i;
				}
			}
			return -1;
		}
	}

	/**
	 * 齿轮层：**独立的一层**，用自己的变调比例。
	 *
	 * <p>它的 rate 是 {@code 速度 / 参考速度}（严格线性、等于机械转频之比），
	 * 与牵引层的 {@code f₁ / 该档 f₁} 是两回事 —— 这正是它必须单独一条循环的原因。
	 * 共用一条会在"档内 f₁ 变化"时把齿轮音也带着跑偏。</p>
	 *
	 * <p>音量跟**负载轴**走（与牵引层同一个口径），因为齿轮啸叫也是出力激出来的；
	 * 惰行时出力为 0 ⇒ 两层一起静音。</p>
	 *
	 * <p>速度出了变调可用区间就**静音**，不夹取：音高错的齿轮音比没有更难听，
	 * 而且夹取会让音高与实际转频脱节（听起来像另一个转速）。</p>
	 */
	private void playGearLayer(BlockPos blockPos, double kmh) {
		final MmtrTractionSoundModel.GearLayer gear = soundSet.model.gear();
		if (gear == null || soundSet.gearEvent == null) {
			return;
		}
		if (!MmtrTractionSoundModel.gearAudible(kmh, gear)) {
			if (gearLoop != null) {
				gearLoop.setData(0, 1, blockPos);   // 出了区间：把音量收到 0，但保留实例（回区间时不再重建）
			}
			return;
		}
		if (gearLoop == null) {
			gearLoop = newLoop(soundSet.gearEvent);
		}
		final double forceAxis = MmtrTractionSoundModel.forceAxis(motorForceN, soundSet.model.spec());
		gearLoop.setData((float) MmtrSoundMix.gearVolume(forceAxis), (float) MmtrTractionSoundModel.gearRate(kmh, gear), blockPos);
	}

	@Override
	protected void playDoorSound(BlockPos blockPos, boolean isOpen) {
		// MMTR 牵引音集里没有门音：门音属于事件层，与牵引模型解耦（走 BVE 集或另做一套）。
	}

	@Override
	protected double getDoorCloseSoundTime() {
		return 1;
	}

	@Override
	public void dispose() {
		for (final VehicleLoopingSoundInstance loop : loops) {
			if (loop != null) {
				loop.dispose();
			}
		}
		if (gearLoop != null) {
			gearLoop.dispose();
			gearLoop = null;
		}
		for (final RunningPlayer player : runningPlayers) {
			player.dispose();
		}
	}

	private VehicleLoopingSoundInstance instance(int index) {
		if (loops[index] == null) {
			loops[index] = newLoop(soundSet.events[index]);
		}
		return loops[index];
	}

	private int indexOf(Selection selection) {
		for (int i = 0; i < soundSet.model.entries().size(); i++) {
			if (soundSet.model.entries().get(i) == selection.entry()) {
				return i;
			}
		}
		return -1;
	}
}

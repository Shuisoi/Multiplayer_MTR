package org.mtr.mod.sound;

import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.SoundEvent;
import org.mtr.mapping.mapper.SoundHelper;
import org.mtr.mod.Init;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * **MMTR 牵引音音效集**：把一份烘焙好的 {@code mmtr_traction.json} 读成"模型 + 事件"。
 *
 * <h2>它在资源包里的位置</h2>
 * <pre>
 *   assets/mtr/sounds/&lt;base&gt;/mmtr_traction.json           ← 清单（本类读它）
 *   assets/mtr/sounds/&lt;base&gt;/mmtr_traction.sounds.json    ← 打包器合并进 assets/mtr/sounds.json
 *   assets/mtr/sounds/&lt;base&gt;/traction_async_00049.ogg     ← 音频本体
 * </pre>
 *
 * <h2>为什么"清单在、就用 MMTR 引擎"是显式的，而不是藏起来的开关</h2>
 * <p>BVE 音效集靠 {@code sound.cfg} 声明自己，MMTR 音效集靠 {@code mmtr_traction.json} 声明自己 ——
 * 两者是**同一层级的东西**（音效集自带的配置），所以"文件在不在"就是声明本身，
 * 不是一个额外的隐藏标志位。清单读不动时会写一条明确的日志，而不是静默退回 BVE/legacy。</p>
 *
 * <h2>为什么事件 id 从清单里读、而不是按命名规则拼</h2>
 * <p>命名规则一旦改动，忘记同步的那一侧会**静默无声**（notes/178 记过这个坑：
 * MC 的 {@code SoundSystem} 在 id 未注册时只打一行日志然后直接 return）。
 * 所以 id 由烘焙器逐条写进清单，本类只负责照读。</p>
 */
public final class MmtrTractionSoundSet {

	/** 清单文件名（与 bake.mjs 的产物名一致）。 */
	public static final String MANIFEST_FILE = "mmtr_traction.json";

	public final String baseName;
	public final MmtrTractionSoundModel.SoundSet model;
	/** 与 {@link #model} 的 entries **同序**的事件表；下标一一对应。 */
	public final SoundEvent[] events;
	/** 齿轮层的事件（清单里没有齿轮层时为 {@code null}）。它**不在** {@link #events} 里 —— 变调基准不同。 */
	@Nullable
	public final SoundEvent gearEvent;
	/**
	 * **走行层**的事件：与 {@code model.runningLayers()} **同序**，每层与自己的 entries 同序。
	 * 它们也不在 {@link #events} 里 —— 那一层没有牵引功率入参，选档与音量都是另一套。
	 */
	public final List<RunningEvents> runningEventLayers;

	/** 一条走行层的事件表。 */
	public record RunningEvents(String id, SoundEvent[] events) {
	}

	private MmtrTractionSoundSet(String baseName, MmtrTractionSoundModel.SoundSet model, SoundEvent[] events,
			@Nullable SoundEvent gearEvent, List<RunningEvents> runningEventLayers) {
		this.baseName = baseName;
		this.model = model;
		this.events = events;
		this.gearEvent = gearEvent;
		this.runningEventLayers = runningEventLayers;
	}

	/**
	 * 试着把一个音效集读成 MMTR 牵引音集。
	 *
	 * @param soundBaseResource 车辆资源里的音效基名（不带冒号时按 {@code mtr} 命名空间）
	 * @return {@code null} = 这个音效集没有 {@code mmtr_traction.json}（那它应当是 BVE 或 legacy）
	 */
	@Nullable
	public static MmtrTractionSoundSet load(@Nullable String soundBaseResource) {
		if (soundBaseResource == null || soundBaseResource.isEmpty()) {
			return null;
		}
		final Identifier base = soundBaseResource.contains(":") ? new Identifier(soundBaseResource) : new Identifier(Init.MOD_ID, soundBaseResource);
		final String path = "sounds/" + base.getPath() + "/" + MANIFEST_FILE;
		final String text = BveVehicleSoundConfig.readResource(new Identifier(base.getNamespace(), path));
		if (text.isEmpty()) {
			return null;
		}
		try {
			final ParsedManifest parsed = parseManifest(base.getPath(), text);
			final SoundEvent[] events = new SoundEvent[parsed.eventIds().size()];
			for (int i = 0; i < events.length; i++) {
				events[i] = SoundHelper.createSoundEvent(new Identifier(parsed.namespace(), parsed.eventIds().get(i)));
			}
			// 齿轮层的事件也要建 —— 清单里有 gear 段、但对应事件没登记在 sounds.json 里时，
			// 这里会抛（被下面的 catch 抓住并写 error 日志），于是整个音效集退回 BVE/legacy。
			// 那比"齿轮层静默不响"更好查：日志里有一行明确的 error。
			@Nullable final SoundEvent gearEvent = parsed.gearEventId() == null
					? null
					: SoundHelper.createSoundEvent(new Identifier(parsed.namespace(), parsed.gearEventId()));
			/*
			 * 走行层的事件（可选段）。同样：清单里声明了、但 sounds.json 里没登记 ⇒
			 * 这里抛异常被 catch 抓住 ⇒ 整个音效集退回 BVE/legacy。
			 * 那正是"清单看着完全正常、进游戏却没声音"的最难查的现场，
			 * 所以测试里有一条专门把真实清单的走行事件与片段逐一对照。
			 */
			final List<RunningEvents> runningEventLayers = new ArrayList<>();
			for (final ParsedRunningLayer parsedLayer : parsed.runningLayers()) {
				final SoundEvent[] layerEvents = new SoundEvent[parsedLayer.eventIds().size()];
				for (int i = 0; i < layerEvents.length; i++) {
					layerEvents[i] = SoundHelper.createSoundEvent(
							new Identifier(parsed.namespace(), parsedLayer.eventIds().get(i)));
				}
				runningEventLayers.add(new RunningEvents(parsedLayer.layer().id(), layerEvents));
			}
			/*
			 * 成功时**也要**打一条日志，而且要说清"谁接管了"。
			 * 为什么值得：进游戏后"没声音"有三种完全不同的原因 ——
			 *   ① 清单没读到（音效集根本没被认成 MMTR 集，静默退回 BVE/legacy）；
			 *   ② 清单读到了但没有档（events 为空 ⇒ 永远挑不中）；
			 *   ③ 引擎接管了、但选档/音量算出来是 0（惰行、未励磁、拖车被静音）。
			 * 没有这条日志，这三种在日志里长得一模一样。有了它，抬头一行就能分掉 ① 和 ②。
			 */
			if (events.length == 0) {
				Init.LOGGER.warn("[MMTR-SND] 音效集 {} 的清单里一档都没有 —— 这个音效集不会发声", base.getPath());
			} else {
				/*
				 * ★ 标签修正（notes/403）：原来把 `namespace:baseDir` 写成"事件前缀"，而 baseDir 是
				 * **资源目录**（清单里的 "sounds/kei2100"），真正的事件前缀是 `namespace:base/`
				 * （`mtr:kei2100/`）—— 两者差一个 `sounds/`，而那正好是 SAF420_v41 整套静音那个坑的形状。
				 * 排障的人照着一个错的标签去 grep，只会被带进沟里，所以两个都打、各自标清。
				 */
				Init.LOGGER.info("[MMTR-SND] 音效集 {} 已被 MMTR 牵引音接管：{} 档{}{}；事件前缀 {}{}/（资源目录 {}:{}）",
						base.getPath(), events.length,
						parsed.gearEventId() == null ? "" : " + 齿轮层",
						parsed.runningLayers().isEmpty() ? ""
								: " + 走行层 " + parsed.runningLayers().size() + " 条"
								+ (parsed.vSmokeKmh() > 0 ? "（门限 " + parsed.vSmokeKmh() + " km/h）" : ""),
						parsed.namespace(), base.getPath(), parsed.namespace(), parsed.baseDir());
				// 混音链一行说清 —— 让"声音多大"变成日志里可核对的读数，而不是印象（notes/403）
				Init.LOGGER.info("[MMTR-SND] 混音：{}", MmtrSoundMix.summary());
			}
			return new MmtrTractionSoundSet(base.getPath(), parsed.model(), events, gearEvent, runningEventLayers);
		} catch (Exception e) {
			// 清单在但读不动 = 包坏了。要说清楚，不能静默退回 —— 否则现场只会觉得"没声音"。
			Init.LOGGER.error("[MMTR-SND] 音效集 {} 的 {} 解析失败，这个音效集不会被使用：{}", soundBaseResource, MANIFEST_FILE, e.toString());
			return null;
		}
	}

	/** 纯解析（不碰资源管理器、不建 SoundEvent），便于用例直接喂字符串。 */
	static ParsedManifest parseManifest(String baseName, String text) {
		final JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		final String namespace = root.has("namespace") ? root.get("namespace").getAsString() : Init.MOD_ID;
		final String baseDir = root.has("baseDir") ? root.get("baseDir").getAsString() : "sounds/" + baseName;

		final JsonObject specJson = root.getAsJsonObject("spec");
		final MmtrTractionSoundModel.Spec spec = new MmtrTractionSoundModel.Spec(
				intOf(specJson, "poleCount", 0),
				doubleOf(specJson, "gearRatio", 0),
				doubleOf(specJson, "wheelDiameterM", 0),
				doubleOf(specJson, "slipHz", 0),
				doubleOf(specJson, "regenCutoffKmh", 0),
				doubleOf(specJson, "carrierMinHz", 0),
				doubleOf(specJson, "carrierMaxHz", 0),
				doubleOf(specJson, "rotorHzPerKmh", 0),
				doubleOf(specJson, "breakpointKmh", 0),
				doubleOf(specJson, "maxTractiveEffortN", 0),
				doubleOf(specJson, "maxPowerW", 0),
				8
		);

		final JsonArray entriesJson = root.getAsJsonArray("entries");
		final List<MmtrTractionSoundModel.Entry> entries = new ArrayList<>();
		final List<String> eventIds = new ArrayList<>();
		for (final JsonElement element : entriesJson) {
			final JsonObject entryJson = element.getAsJsonObject();
			final String file = stringOf(entryJson, "file", "");
			if (file.isEmpty()) {
				continue;
			}
			entries.add(new MmtrTractionSoundModel.Entry(
					file,
					doubleOf(entryJson, "f1Hz", 0),
					stringOf(entryJson, "mode", ""),
					intOf(entryJson, "pulses", 0),
					doubleOf(entryJson, "carrierHz", 0)
			));
			// 事件 id 照清单读；清单里没写才退回"按文件名去扩展名"
			eventIds.add(stringOf(entryJson, "eventId", file.replace(".ogg", "")));
		}

		// 齿轮层（可选）：它不在 entries 里，因为变调基准是机械转频而不是 f₁
		@Nullable MmtrTractionSoundModel.GearLayer gear = null;
		@Nullable String gearEventId = null;
		if (root.has("gear") && root.get("gear").isJsonObject()) {
			final JsonObject gearJson = root.getAsJsonObject("gear");
			final String gearFile = stringOf(gearJson, "file", "");
			if (!gearFile.isEmpty()) {
				gear = new MmtrTractionSoundModel.GearLayer(
						gearFile,
						doubleOf(gearJson, "refSpeedKmh", 100),
						doubleOf(gearJson, "rotorRefHz", 0),
						intOf(gearJson, "teeth", 0),
						doubleOf(gearJson, "gearMeshHz", 0),
						doubleOf(gearJson, "minKmh", 50),
						doubleOf(gearJson, "maxKmh", 200)
				);
				gearEventId = stringOf(gearJson, "eventId", gearFile.replace(".ogg", ""));
			}
		}

		// 门限（可选）：低于它不给励磁音。引擎原模型没有这个量，是本仓为"低速变调被夹"补的。
		final double vSmokeKmh = root.has("gate") && root.get("gate").isJsonObject()
				? doubleOf(root.getAsJsonObject("gate"), "vSmokeKmh", 0)
				: 0;

		// 走行层（可选）：由速度强绑定的轮轨/风噪。它**不在** entries 里 —— 那一层没有功率入参，
		// 选档按速度、音量也是速度的幂律，与牵引层是两套基准（各自一条循环）。
		final List<MmtrTractionSoundModel.RunningLayer> runningLayers = new ArrayList<>();
		final List<ParsedRunningLayer> parsedRunning = new ArrayList<>();
		if (root.has("running") && root.get("running").isJsonObject()) {
			final JsonArray layersJson = root.getAsJsonObject("running").getAsJsonArray("layers");
			if (layersJson != null) {
				for (final JsonElement layerElement : layersJson) {
					final JsonObject layerJson = layerElement.getAsJsonObject();
					final String id = stringOf(layerJson, "id", "");
					final double lawB = doubleOf(layerJson, "lawB", 0);
					final boolean pitchFollowsSpeed = "speed_ratio".equals(stringOf(layerJson, "pitchMode", "speed_ratio"));
					final double layerSmoke = doubleOf(layerJson, "vSmokeKmh", vSmokeKmh);
					final double masterVolume = doubleOf(layerJson, "masterVolume", 1);
					final List<MmtrTractionSoundModel.RunningEntry> runningEntries = new ArrayList<>();
					final List<String> runningEventIds = new ArrayList<>();
					final JsonArray runningEntriesJson = layerJson.getAsJsonArray("entries");
					if (runningEntriesJson != null) {
						for (final JsonElement runningElement : runningEntriesJson) {
							final JsonObject runningJson = runningElement.getAsJsonObject();
							final String file = stringOf(runningJson, "file", "");
							if (file.isEmpty()) {
								continue;
							}
							runningEntries.add(new MmtrTractionSoundModel.RunningEntry(
									file,
									doubleOf(runningJson, "vBakeKmh", 0),
									doubleOf(runningJson, "vCentreKmh", 0)
							));
							runningEventIds.add(stringOf(runningJson, "eventId", file.replace(".ogg", "")));
						}
					}
					if (!runningEntries.isEmpty()) {
						final MmtrTractionSoundModel.RunningLayer layer = new MmtrTractionSoundModel.RunningLayer(
								id, lawB, pitchFollowsSpeed, layerSmoke, masterVolume, runningEntries);
						runningLayers.add(layer);
						parsedRunning.add(new ParsedRunningLayer(layer, runningEventIds));
					}
				}
			}
		}

		return new ParsedManifest(namespace, baseDir,
				new MmtrTractionSoundModel.SoundSet(spec, entries, gear, runningLayers, vSmokeKmh),
				eventIds, gearEventId, parsedRunning);
	}

	/** 纯解析结果：清单里的东西，**不含任何 MC 类型**（所以能在没有 Minecraft 的用例里跑）。 */
	record ParsedManifest(
			String namespace,
			String baseDir,
			MmtrTractionSoundModel.SoundSet model,
			List<String> eventIds,
			/** 齿轮层的事件 id；清单里没有齿轮层时为 {@code null}。 */
			@Nullable String gearEventId,
			/** 走行层（可选段）：与 {@code model.runningLayers()} 同序。 */
			List<ParsedRunningLayer> runningLayers
	) {
		/** 音阶门限（km/h）；清单里没写时为 0 = 不设门限。 */
		double vSmokeKmh() {
			return model.vSmokeKmh();
		}
	}

	/** 走行层的一条层：模型 + 它自己的事件 id 表（与 entries 同序）。 */
	record ParsedRunningLayer(MmtrTractionSoundModel.RunningLayer layer, List<String> eventIds) {
	}

	private static int intOf(JsonObject json, String key, int fallback) {
		return json != null && json.has(key) ? json.get(key).getAsInt() : fallback;
	}

	private static double doubleOf(JsonObject json, String key, double fallback) {
		return json != null && json.has(key) ? json.get(key).getAsDouble() : fallback;
	}

	private static String stringOf(JsonObject json, String key, String fallback) {
		return json != null && json.has(key) ? json.get(key).getAsString() : fallback;
	}
}

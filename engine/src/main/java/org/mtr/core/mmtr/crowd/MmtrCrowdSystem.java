package org.mtr.core.mmtr.crowd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.AreaBase;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.simulation.Simulator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * **车站人流系统**：把"站台上该有多少人"从**手填一个数**变成**系统自动、动态地算**。
 *
 * <h3>它解决的问题</h3>
 * <p>在它之前，客量只能靠 {@code platform set/station/all <0-100>} 一个个填 —— 那是布景参数，
 * 不是运营。真实的车站是"早高峰挤、半夜空、有车进站时人聚、车走了人散"，
 * 而手填的数一旦填下去就永远不动（用户 2026-10-13："车站人流系统，这个需要一个系统进行自动动态设置"）。</p>
 *
 * <h3>一套式子（唯一的数值口径）</h3>
 * <pre>
 *   目标% = clamp( round( 曲线[当前小时] × (基准%/100) × 班次因子 × 事件乘法 + 事件加法 + 停站冲量 ) )
 *   输出% = 把"当前%"按 τ 平滑逼近目标%，再量化到 step% 的整数倍
 * </pre>
 * <table border="1">
 *   <caption>四个输入各管什么</caption>
 *   <tr><th>输入</th><th>来源</th><th>口径</th></tr>
 *   <tr><td>曲线[0..23]</td><td>预设或手改</td><td>"一天里的客流节律"，100% = 最挤的一档；默认 {通勤}</td></tr>
 *   <tr><td>基准%</td><td>车站/站台覆盖，默认 100</td><td>"这个站最忙时有几个人"：乘在曲线上，改小 = 整体更空</td></tr>
 *   <tr><td>班次因子</td><td>**实测**（到站次数，10 分钟半衰）</td><td>相对最忙的那个站：最忙 1.00，没车的站 1−serviceBoost（默认 0.65）</td></tr>
 *   <tr><td>停站冲量</td><td>**实测**（车正停在这个站台上）</td><td>停站作业期间 +dwellSurge%（默认 45），车走了靠 τ 自然散去</td></tr>
 * </table>
 * <p>外加**事件**（{@code crowd event add +40 10} = 该站 10 分钟内 +40%），用于演练/布景/失控场景。</p>
 *
 * <h3>为什么写的是 {@code Platform.crowdLevel} 本身</h3>
 * <p>客量到画面那条路（notes/416）只有一个权威数值：{@code Platform.crowdLevel}，服务端每 tick
 * 比对它、变了就推给客户端镜像（{@code MmtrCrowdModule.syncCrowdLevelsToClients}），客户端按镜像
 * 决定画几个人。**系统直接写这个字段**，于是整条链路（落盘 / 跨端推送 / 渲染 / 只读读数）
 * 一行都不用改 —— 这也是为什么 {@link MmtrCrowd#setModulator} 那个"只改有效值"的钩子在这里
 * 用不上（有效值不跨端，改它画面不动）。</p>
 *
 * <p>代价写在明处：系统在管的时候，{@code platform set/station/all} 手填的数会被下一拍覆盖。
 * 所以那三条指令在系统开时**改的是系统的基准**（见 {@code MmtrPlatformCommands}），
 * 而不是跟系统抢同一个字段。</p>
 *
 * <h3>三个"不写"（防抖与防呆）</h3>
 * <ol>
 *   <li><b>量化后才比</b>：目标差 1% 不写字段 —— 写字段会 bump 版本号、推客户端、重铺方块，
 *       客流是装饰层，不该按 tick 抖动刷屏；</li>
 *   <li><b>平滑</b>：一阶低通（τ 默认 90 秒），"人数"是渐变的；想让它立刻到位用 {@code crowd snap}；</li>
 *   <li><b>关掉就一行不写</b>：{@code enabled=false} 时 {@link #tick} 只做观察（读数用），
 *       绝不碰任何站台 —— 手填的客量原样留着。</li>
 * </ol>
 *
 * <h3>落盘</h3>
 * <p>整个配置（开关、时钟、周期、τ、step、曲线、基准、事件）存成存档目录里的
 * {@code mmtr-crowd-system.json}，重启后照旧。运行态（平滑中的值、班次计数、冲量）**不落盘**：
 * 重启后第一个 tick 会按当前时钟直接落到目标值（见 {@code currentLevel} 的初始化），
 * 这比"回到重启前的半路"更符合直觉。</p>
 */
public final class MmtrCrowdSystem {

	// ------------------------------------------------------------------ 预设曲线

	/** 曲线档位数（一天 24 小时，逐小时一档）。 */
	public static final int HOURS = 24;

	/** **通勤**（默认）：早晚两个高峰，后半夜空 —— 用户口径里的"符合现实的情况"。 */
	public static final long[] CURVE_COMMUTE = {2, 1, 1, 1, 2, 8, 25, 65, 95, 70, 40, 35, 38, 35, 32, 40, 55, 85, 95, 75, 50, 35, 20, 8};
	/** **全天**：白天恒定的中等客流（看不出高峰，用于"就是要一直有人"的布景）。 */
	public static final long[] CURVE_ALL_DAY = {20, 18, 16, 15, 18, 25, 40, 55, 60, 60, 58, 55, 55, 55, 58, 60, 62, 65, 60, 55, 48, 40, 32, 25};
	/** **夜间**：只有后半夜的小客流（想看清"空站台"长什么样）。 */
	public static final long[] CURVE_NIGHT = {30, 25, 20, 15, 10, 10, 8, 8, 5, 5, 5, 5, 5, 5, 5, 5, 8, 10, 15, 20, 25, 30, 35, 35};
	/** **空**：全 0（等价于"把客流关掉"，但系统还在跑、还能被事件临时拉起来）。 */
	public static final long[] CURVE_EMPTY = new long[HOURS];

	// ------------------------------------------------------------------ 参数

	/** 计算周期的默认值（仿真毫秒）。 */
	public static final long DEFAULT_PERIOD_MILLIS = 5_000L;
	/** 计算周期的上下限。 */
	public static final long MIN_PERIOD_MILLIS = 1_000L;
	public static final long MAX_PERIOD_MILLIS = 300_000L;
	/** 平滑时间常数的默认值（秒）。 */
	public static final double DEFAULT_TAU_SECONDS = 90.0;
	/**
	 * **停站冲量的跟进时间常数**（秒）：车一进站，人聚得比早晚高峰快得多。
	 *
	 * <p>为什么必须单独一条：站停只有二十几秒（`MmtrStationSubTaskTests` 量的就是 20–30 s），
	 * 而 τ=90 s 在 20 秒里只走 1/5 —— 实机验证（notes/425）就是"冲量已经 45、目标已经 100，
	 * 站台上却只从 22 爬到 51"。所以冲量**上冲**时改用这条快的（默认 20 s），
	 * **回落**仍然走 τ：人聚集快、散得慢，这也是现实里的样子。</p>
	 */
	public static final double DEFAULT_SURGE_TAU_SECONDS = 20.0;
	/** 量化档的默认值（%）。 */
	public static final long DEFAULT_STEP = 2;
	/** 量化档的硬上限：再粗就是"一格一格跳"，看不出连续变化了。 */
	public static final long MAX_STEP = 10;
	/** 班次因子强度的默认值（0 = 不看班次，全部 1.00）。 */
	public static final double DEFAULT_SERVICE_BOOST = 0.35;
	/** 停站冲量的默认值（%）。 */
	public static final long DEFAULT_DWELL_SURGE = 45;
	/** 停站冲量的硬上限。 */
	public static final long MAX_DWELL_SURGE = 100;
	/** 班次计数的半衰期（仿真毫秒）：10 分钟前的车只剩一半权重。 */
	public static final long RATE_HALF_LIFE_MILLIS = 600_000L;
	/** "停稳"的速度判据（格/毫秒）：约 0.36 km/h，低于它算停住。 */
	private static final double STOPPED_SPEED = 1.0E-4;

	// ------------------------------------------------------------------ 配置（落盘）

	private boolean enabled;
	private boolean worldClock = true;
	private long periodMillis = DEFAULT_PERIOD_MILLIS;
	private double tauSeconds = DEFAULT_TAU_SECONDS;
	private double surgeTauSeconds = DEFAULT_SURGE_TAU_SECONDS;
	private long step = DEFAULT_STEP;
	private double serviceBoost = DEFAULT_SERVICE_BOOST;
	private long dwellSurge = DEFAULT_DWELL_SURGE;
	private long[] curve = CURVE_COMMUTE.clone();
	private String profileName = "通勤";
	/** 没被任何车站/站台覆盖时的基准（{@code crowd base all <n>} 改它）。 */
	private long defaultBase = 100;
	private final Map<Long, Long> stationBase = new TreeMap<>();
	private final Map<Long, Long> platformBase = new TreeMap<>();
	private final List<Event> events = new ArrayList<>();
	private long nextEventId = 1;

	// ------------------------------------------------------------------ 运行态（不落盘）

	/** 平滑中的"当前客量"（站台 id → 0–100 的实数）。 */
	private final Map<Long, Double> currentLevel = new HashMap<>();
	/** 衰减后的到站计数（分组键 → 次数），分组键见 {@link #groupKeyOf}。 */
	private final Map<Long, Double> arrivalRate = new HashMap<>();
	/** 上一拍哪些车**正停在哪里**（车 id → 站台 id）：用来认得站上升沿，也用来算冲量。 */
	private final Map<Long, Long> dwellingVehicles = new HashMap<>();
	/** 本拍的停站冲量（站台 id → %）。 */
	private final Map<Long, Long> surgeByPlatform = new HashMap<>();
	private long lastRunMillis = Long.MIN_VALUE;
	private long tickCount;
	private long lastWrittenCount;
	private long totalArrivals;

	/** 配置落盘的路径（{@code mmtr-crowd-system.json}）；{@code null} = 只读运行，不落盘。 */
	private @Nullable Path path;

	// ------------------------------------------------------------------ 构造 / 落盘

	public MmtrCrowdSystem() {
	}

	/** 从 JSON 读一份配置；文件不存在/读坏时返回**默认配置**（系统默认是关的）。 */
	public static MmtrCrowdSystem load(Path path) {
		final MmtrCrowdSystem system = new MmtrCrowdSystem();
		system.path = path;
		try {
			if (!Files.exists(path)) {
				return system;
			}
			final JsonElement root = JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
			if (root.isJsonObject()) {
				system.readJson(root.getAsJsonObject());
			}
		} catch (Exception e) {
			System.out.println("[MMTR-CROWD-SYS] 读配置失败（按默认配置继续）：" + e.getMessage());
		}
		return system;
	}

	private void readJson(JsonObject o) {
		enabled = readBoolean(o, "enabled", enabled);
		worldClock = readBoolean(o, "worldClock", worldClock);
		periodMillis = clampPeriod(readLong(o, "periodMillis", periodMillis));
		tauSeconds = clampTau(readDouble(o, "tauSeconds", tauSeconds));
		surgeTauSeconds = clampTau(readDouble(o, "surgeTauSeconds", surgeTauSeconds));
		step = clampStep(readLong(o, "step", step));
		serviceBoost = clampServiceBoost(readDouble(o, "serviceBoost", serviceBoost));
		dwellSurge = clampSurge(readLong(o, "dwellSurge", dwellSurge));
		defaultBase = clampPercent(readLong(o, "defaultBase", defaultBase));

		if (o.has("curve") && o.get("curve").isJsonArray()) {
			final JsonArray arr = o.getAsJsonArray("curve");
			for (int i = 0; i < HOURS && i < arr.size(); i++) {
				curve[i] = clampPercent(arr.get(i).getAsLong());
			}
			profileName = o.has("profile") ? o.get("profile").getAsString() : "自定义";
		} else if (o.has("profile")) {
			// 老文件只写了档名：按名字现查预设（改预设时"名字"这一份不会僵在半路）
			applyProfile(o.get("profile").getAsString(), false);
		}

		if (o.has("stationBase") && o.get("stationBase").isJsonObject()) {
			for (final Map.Entry<String, JsonElement> e : o.getAsJsonObject("stationBase").entrySet()) {
				putLongEntry(e, stationBase);
			}
		}
		if (o.has("platformBase") && o.get("platformBase").isJsonObject()) {
			for (final Map.Entry<String, JsonElement> e : o.getAsJsonObject("platformBase").entrySet()) {
				putLongEntry(e, platformBase);
			}
		}

		if (o.has("events") && o.get("events").isJsonArray()) {
			for (final JsonElement el : o.getAsJsonArray("events")) {
				if (!el.isJsonObject()) {
					continue;
				}
				final JsonObject e = el.getAsJsonObject();
				final Event event = new Event();
				event.id = readLong(e, "id", nextEventId++);
				event.stationId = readLong(e, "stationId", 0);
				event.stationLabel = e.has("stationLabel") ? e.get("stationLabel").getAsString() : "";
				event.add = readLong(e, "add", 0);
				event.mul = readDouble(e, "mul", 1.0);
				event.untilMillis = readLong(e, "untilMillis", Long.MAX_VALUE);
				events.add(event);
				nextEventId = Math.max(nextEventId, event.id + 1);
			}
		}
	}

	private static void putLongEntry(Map.Entry<String, JsonElement> entry, Map<Long, Long> target) {
		try {
			target.put(Long.parseLong(entry.getKey()), clampPercent(entry.getValue().getAsLong()));
		} catch (Exception ignored) {
			// 键不是数字（手改坏了）：跳过这一条，别的照读
		}
	}

	/**
	 * 落盘。**只有内容真的需要**时才写（事件过期不算）：调用方在各条指令末尾调一次，是常态但低频。
	 *
	 * @return true = 真的写了文件
	 */
	public boolean save() {
		if (path == null) {
			return false;
		}
		try {
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			final JsonObject root = new JsonObject();
			root.addProperty("enabled", enabled);
			root.addProperty("worldClock", worldClock);
			root.addProperty("periodMillis", periodMillis);
			root.addProperty("tauSeconds", tauSeconds);
			root.addProperty("surgeTauSeconds", surgeTauSeconds);
			root.addProperty("step", step);
			root.addProperty("serviceBoost", serviceBoost);
			root.addProperty("dwellSurge", dwellSurge);
			root.addProperty("defaultBase", defaultBase);
			root.addProperty("profile", profileName);
			final JsonArray curveArray = new JsonArray();
			for (final long value : curve) {
				curveArray.add(value);
			}
			root.add("curve", curveArray);

			final JsonObject stationBaseJson = new JsonObject();
			stationBase.forEach((id, value) -> stationBaseJson.addProperty(String.valueOf(id), value));
			root.add("stationBase", stationBaseJson);
			final JsonObject platformBaseJson = new JsonObject();
			platformBase.forEach((id, value) -> platformBaseJson.addProperty(String.valueOf(id), value));
			root.add("platformBase", platformBaseJson);

			final JsonArray eventArray = new JsonArray();
			for (final Event event : events) {
				final JsonObject e = new JsonObject();
				e.addProperty("id", event.id);
				e.addProperty("stationId", event.stationId);
				e.addProperty("stationLabel", event.stationLabel);
				e.addProperty("add", event.add);
				e.addProperty("mul", event.mul);
				e.addProperty("untilMillis", event.untilMillis);
				eventArray.add(e);
			}
			root.add("events", eventArray);

			Files.write(path, root.toString().getBytes(StandardCharsets.UTF_8));
			return true;
		} catch (Exception e) {
			System.out.println("[MMTR-CROWD-SYS] 写配置失败：" + e.getMessage());
			return false;
		}
	}

	public void attachPath(Path path) {
		this.path = path;
	}

	/**
	 * 把另一份（刚读出来的）配置**接进这一个实例**。
	 *
	 * <p>为什么不是"读出来直接替换引用"：{@code Simulator} 上的字段是 {@code final}（各处的读取者
	 * 已经抓着它了），换引用会让"抓旧引用的那一半"继续用默认配置。这里只搬**配置**，
	 * 不搬运行态（平滑中的值、班次计数、冲量）—— 那些是这一轮的现场，跟存档无关。</p>
	 */
	public void replaceWith(MmtrCrowdSystem other) {
		if (other == null || other == this) {
			return;
		}
		enabled = other.enabled;
		worldClock = other.worldClock;
		periodMillis = other.periodMillis;
		tauSeconds = other.tauSeconds;
		surgeTauSeconds = other.surgeTauSeconds;
		step = other.step;
		serviceBoost = other.serviceBoost;
		dwellSurge = other.dwellSurge;
		curve = other.curve.clone();
		profileName = other.profileName;
		defaultBase = other.defaultBase;
		stationBase.clear();
		stationBase.putAll(other.stationBase);
		platformBase.clear();
		platformBase.putAll(other.platformBase);
		events.clear();
		events.addAll(other.events);
		nextEventId = other.nextEventId;
		path = other.path;
		if (!enabled) {
			currentLevel.clear();
		}
	}

	// ------------------------------------------------------------------ 每拍

	/**
	 * 每个仿真节拍调一次（挂在 {@code Simulator.tick} 上）。周期没到就**一行都不做**。
	 *
	 * <p>顺序是刻意的：先让事件过期、再观察世界（车在哪停着）、再衰减班次计数、最后才算客量 ——
	 * 这样 {@code crowd status} 打出来的"为什么是这个数"里的每一项都是**这一拍的一致快照**。</p>
	 */
	public void tick(Simulator simulator) {
		if (simulator == null) {
			return;
		}
		final long now = simulator.getCurrentMillis();
		if (lastRunMillis != Long.MIN_VALUE && now - lastRunMillis < periodMillis) {
			return;
		}
		final long dt = lastRunMillis == Long.MIN_VALUE ? periodMillis : Math.max(1L, now - lastRunMillis);
		lastRunMillis = now;
		tickCount++;
		expireEvents(now);
		observe(simulator);
		decayRate(dt);
		if (!enabled) {
			lastWrittenCount = 0;
			return;
		}
		apply(simulator, dt);
	}

	/**
	 * 立刻重算一次（{@code crowd now}）—— 不等周期，但**仍然按 τ 平滑**。
	 *
	 * @param snap true = 跳过平滑，直接把当前值拉到目标（{@code crowd snap}，调参/验收用）
	 */
	public void runNow(Simulator simulator, boolean snap) {
		if (simulator == null) {
			return;
		}
		expireEvents(simulator.getCurrentMillis());
		observe(simulator);
		decayRate(periodMillis);
		if (!enabled) {
			return;
		}
		if (snap) {
			currentLevel.clear();
		}
		apply(simulator, periodMillis);
		tickCount++;
	}

	/** 观察世界：这一拍哪些车正停在哪个站台上 ⇒ 冲量 + 到站计数。 */
	private void observe(Simulator simulator) {
		surgeByPlatform.clear();
		final Map<Long, Long> stillDwelling = new HashMap<>();
		for (final Siding siding : simulator.sidings) {
			siding.iterateVehicles(vehicle -> {
				final long platformId = dwellingPlatformOf(simulator, vehicle);
				if (platformId == 0) {
					return;
				}
				surgeByPlatform.merge(platformId, dwellSurge, Math::max);
				final boolean risingEdge = !Long.valueOf(platformId).equals(dwellingVehicles.get(vehicle.getId()));
				if (risingEdge) {
					final Platform platform = simulator.platformIdMap.get(platformId);
					arrivalRate.merge(groupKeyOf(platform), 1.0, Double::sum);
					totalArrivals++;
				}
				stillDwelling.put(vehicle.getId(), platformId);
			});
		}
		dwellingVehicles.clear();
		dwellingVehicles.putAll(stillDwelling);
	}

	/**
	 * 这列车**现在正停在哪个站台**上（0 = 没停在任何站台）。
	 *
	 * <p>两条判据，先精确后观测：</p>
	 * <ol>
	 *   <li><b>作业链的到站/出站确认</b>（{@link MmtrMission#hasStationArrival()} +
	 *       {@link MmtrMission#hasStationDeparture()}）：一到站就标上、门关好离站才标出站 ——
	 *       这正是"人聚在站台上"的那段时间窗。作业单每一步都是一条**新**任务（{@code MmtrJobScheduler}
	 *       逐步新建），所以这两个标记天然是"这一步"的，不需要自己按步号清；</li>
	 *   <li><b>停稳在目标上</b>（{@link Vehicle#isMmtrMotionStoppedAtTarget()} + 速度）：没有子任务
	 *       标记的"纯开往站台"那类任务（{@code DriveToPlatform}）走这条，否则它们进站时站台上一个人都不聚。</li>
	 * </ol>
	 */
	private long dwellingPlatformOf(Simulator simulator, Vehicle vehicle) {
		final MmtrMission mission = vehicle.getMmtrMission();
		if (mission == null) {
			return 0;
		}
		final long targetId = mission.getTargetSidingId();
		if (targetId == 0 || !simulator.platformIdMap.containsKey(targetId)) {
			// 目标不是站台（车辆段股道、纯轨目标）：站台上没有人上下车
			return 0;
		}
		if (mission.hasStationArrival() && mission.getStationDepartureMillis() < 0) {
			return targetId;
		}
		if (!mission.hasStationArrival() && vehicle.isMmtrMotionStoppedAtTarget() && vehicle.getSpeed() <= STOPPED_SPEED) {
			return targetId;
		}
		return 0;
	}

	/** 班次计数按半衰期衰减（时间常数的口径写在 {@link #RATE_HALF_LIFE_MILLIS}）。 */
	private void decayRate(long dt) {
		if (dt <= 0) {
			return;
		}
		final double factor = Math.pow(0.5, dt / (double) RATE_HALF_LIFE_MILLIS);
		arrivalRate.replaceAll((key, value) -> value * factor);
	}

	/** 把算出来的目标写进站台字段 —— 只写**量化后真的变了**的那些。 */
	private void apply(Simulator simulator, long dt) {
		final double maxRate = maxRateRaw();
		long written = 0;
		long counted = 0;
		for (final Platform platform : simulator.platforms) {
			final Decision decision = decide(simulator, platform, maxRate);
			final Long key = platform.getId();
			final double previous = currentLevel.containsKey(key) ? currentLevel.get(key) : decision.target();
			/*
			 * 第一次见到这条站台（刚开系统 / 刚重启）：直接落到目标，而不是从 0 慢慢涨上来。
			 * 之后按一阶低通走：**冲量上冲走快的那条 τ**（车进站人聚得快），
			 * 其余（早晚高峰换档、车走了人散）走慢的 τ。
			 */
			final double tau = decision.surge() > 0 && decision.target() > previous ? Math.min(tauSeconds, surgeTauSeconds) : tauSeconds;
			final double alpha = tau <= 0 ? 1.0 : Math.min(1.0, dt / (tau * 1000.0));
			final double next = currentLevel.containsKey(key) ? previous + (decision.target() - previous) * alpha : decision.target();
			currentLevel.put(key, next);
			final long quantized = quantize(next);
			if (quantized != platform.getCrowdLevel()) {
				platform.setCrowdLevel(quantized);
				written++;
			}
			counted++;
		}
		lastWrittenCount = written;
		if (counted == 0) {
			lastWrittenCount = 0;
		}
	}

	// ------------------------------------------------------------------ 算式（纯函数）

	/** 一个站台此刻的**决策依据**（不写任何状态）—— {@code crowd status/why} 与网页 feed 都打它。 */
	public record Decision(long hour, long curve, long basePercent, double serviceFactor, long surge, long eventAdd, double eventMul, long target) {
	}

	/**
	 * 算出这个站台此刻的**目标客量**（0–100）。
	 *
	 * <p>纯函数：同样输入必然同样输出，不碰任何状态 —— 所以 {@code crowd status} 在系统关着的时候
	 * 也能报"如果打开会是多少"。</p>
	 *
	 * @param maxRate {@link #maxRateRaw()}；调用方在整表遍历里只算一次
	 */
	public Decision decide(Simulator simulator, Platform platform, double maxRate) {
		final long hour = hourOf(simulator);
		final long curveValue = curve[(int) Math.max(0, Math.min(HOURS - 1, hour))];
		final long base = baseOf(platform);
		final double service = serviceFactor(platform, maxRate);
		final long surge = surgeByPlatform.getOrDefault(platform.getId(), 0L);
		long eventAdd = 0;
		double eventMul = 1.0;
		final long stationId = stationIdOf(platform);
		for (final Event event : events) {
			if (event.stationId != 0 && event.stationId != stationId) {
				continue;
			}
			eventAdd += event.add;
			eventMul *= event.mul;
		}
		final double raw = curveValue * (base / 100.0) * service * eventMul + eventAdd + surge;
		return new Decision(hour, curveValue, base, service, surge, eventAdd, eventMul, clampPercent(Math.round(raw)));
	}

	/** 便捷重载：自己算一遍最忙计数（单条站台查询用，别放进遍历里）。 */
	public Decision decide(Simulator simulator, Platform platform) {
		return decide(simulator, platform, maxRateRaw());
	}

	/**
	 * 班次因子：**相对最忙的那个站**。
	 *
	 * <p>{@code 1 − boost + boost × (本站计数 / 最忙计数)}。没有任何到站记录时全体 1.00 ——
	 * "还没开过车"不该被当成"这个站没人"。{@code boost=0} 时恒为 1（完全不看班次）。</p>
	 *
	 * <p>比的是**未取整的**计数（{@link #maxRateRaw()}）：最忙的那个站要恰好拿到 1.00，
	 * 而不是"四舍五入后 1 除 0.994 ⇒ 0.998"这种读数上的锯齿。</p>
	 */
	private double serviceFactor(Platform platform, double maxRateRaw) {
		if (serviceBoost <= 0 || maxRateRaw <= 0) {
			return 1.0;
		}
		final double rate = arrivalRate.getOrDefault(groupKeyOf(platform), 0.0);
		return 1.0 - serviceBoost + serviceBoost * Math.min(1.0, rate / maxRateRaw);
	}

	/** 最忙那一组的到站计数（未取整，算式用）。 */
	private double maxRateRaw() {
		double max = 0;
		for (final double value : arrivalRate.values()) {
			max = Math.max(max, value);
		}
		return max;
	}

	/** 最忙那一组的到站计数（四舍五入到"次"，读数用）。 */
	public long maxRate() {
		return Math.round(maxRateRaw());
	}

	/** 归一化后的曲线值（{@code crowd status} 的一栏）。 */
	public long curveAt(long hour) {
		return curve[(int) Math.max(0, Math.min(HOURS - 1, hour))];
	}

	/**
	 * 当前小时（0–23）。
	 *
	 * <p>{@code worldClock=true} 用**游戏内时刻**（{@code Simulator.getGameHour()}）：太阳就是时钟，
	 * 玩家 {@code /time set} 也能当场把客流拨到那个点；{@code false} 用现实钟点（服务器所在时区的
	 * 墙上时间）—— 日夜循环被关掉（{@code doDaylightCycle=false}）时，游戏内时刻会停住，
	 * 这时用现实钟点才能继续早晚起伏。</p>
	 */
	public long hourOf(Simulator simulator) {
		return worldClock ? Math.max(0, Math.min(23, simulator.getGameHour())) : java.time.LocalTime.now().getHour();
	}

	/** 基准客量：站台覆盖 > 车站覆盖 > 全局默认。 */
	public long baseOf(Platform platform) {
		final Long platformValue = platformBase.get(platform.getId());
		if (platformValue != null) {
			return platformValue;
		}
		final long stationId = stationIdOf(platform);
		if (stationId != 0) {
			final Long stationValue = stationBase.get(stationId);
			if (stationValue != null) {
				return stationValue;
			}
		}
		return defaultBase;
	}

	/** 站台所属车站 id（未归站 = 0）。 */
	public static long stationIdOf(Platform platform) {
		final AreaBase<?, ?> area = platform.area;
		return area == null ? 0 : area.getId();
	}

	/** 到站计数的分组键：有车站按车站，没归站的就单独算一个组（用负数域避开车站 id）。 */
	public static long groupKeyOf(@Nullable Platform platform) {
		if (platform == null) {
			return 0;
		}
		final long stationId = stationIdOf(platform);
		return stationId != 0 ? stationId : -platform.getId();
	}

	/** 量化到 step 的整数倍（step ≤ 1 时就是原值）。 */
	public long quantize(double level) {
		if (step <= 1) {
			return clampPercent(Math.round(level));
		}
		// 直接对**档位网格**取整（先把 0.9 取整成 1 再分成 2 档会把它抬到 2：低客量档会被系统性抬高）
		return clampPercent(Math.round(level / (double) step) * step);
	}

	// ------------------------------------------------------------------ 事件

	/** 一条临时事件（加法 / 乘法，作用于某个车站或全图）。 */
	public static final class Event {

		public long id;
		/** 0 = 全图所有车站。 */
		public long stationId;
		public String stationLabel = "";
		/** 加法项（%），可负。 */
		public long add;
		/** 乘法项；1.0 = 不影响。 */
		public double mul = 1.0;
		/** 到期的**仿真时刻**；{@link Long#MAX_VALUE} = 不过期。 */
		public long untilMillis = Long.MAX_VALUE;
	}

	/**
	 * 加一条事件。
	 *
	 * @param stationId  0 = 全图
	 * @param durationMillis 持续时长；{@link Long#MAX_VALUE} = 不过期
	 * @return 新事件的 id
	 */
	public long addEvent(long stationId, String stationLabel, long add, double mul, long nowMillis, long durationMillis) {
		final Event event = new Event();
		event.id = nextEventId++;
		event.stationId = stationId;
		event.stationLabel = stationLabel == null ? "" : stationLabel;
		event.add = add;
		event.mul = mul <= 0 ? 1.0 : mul;
		event.untilMillis = durationMillis == Long.MAX_VALUE ? Long.MAX_VALUE : nowMillis + Math.max(1L, durationMillis);
		events.add(event);
		return event.id;
	}

	/** 清掉事件；{@code id=0} = 全部清掉。 */
	public int clearEvents(long id) {
		final int before = events.size();
		events.removeIf(event -> id == 0 || event.id == id);
		return before - events.size();
	}

	private void expireEvents(long now) {
		events.removeIf(event -> now >= event.untilMillis);
	}

	// ------------------------------------------------------------------ 曲线预设

	/** 曲线预设名（{@code crowd profile} 认这些）。 */
	public static List<String> profileNames() {
		return List.of("通勤", "全天", "夜间", "空");
	}

	public static long[] profileCurve(String name) {
		switch (name == null ? "" : name.trim().toLowerCase(Locale.ENGLISH)) {
			case "全天":
			case "all":
			case "allday":
				return CURVE_ALL_DAY.clone();
			case "夜间":
			case "night":
				return CURVE_NIGHT.clone();
			case "空":
			case "empty":
			case "off":
				return CURVE_EMPTY.clone();
			default:
				return CURVE_COMMUTE.clone();
		}
	}

	/**
	 * 换一条曲线预设。
	 *
	 * @param save 是否立刻落盘（{@link #load} 里读档时传 false，避免"读一次写一次"）
	 */
	public void applyProfile(String name, boolean save) {
		curve = profileCurve(name);
		profileName = normalizeProfileName(name);
		if (save) {
			save();
		}
	}

	private static String normalizeProfileName(String name) {
		final String token = name == null ? "" : name.trim();
		for (final String candidate : profileNames()) {
			if (candidate.equalsIgnoreCase(token)) {
				return candidate;
			}
		}
		switch (token.toLowerCase(Locale.ENGLISH)) {
			case "all":
			case "allday":
				return "全天";
			case "night":
				return "夜间";
			case "empty":
			case "off":
				return "空";
			default:
				return "通勤";
		}
	}

	// ------------------------------------------------------------------ 读数

	/**
	 * 一屏读数（{@code crowd status} 的内容）。
	 *
	 * @param detail 逐站台再列一遍（{@code crowd status --detail}）
	 */
	public List<String> report(Simulator simulator, boolean detail) {
		final List<String> lines = new ArrayList<>();
		final long hour = hourOf(simulator);
		lines.add("[车站人流系统] 状态=" + (enabled ? "开（系统在写各站台客量）" : "关（只算不写，手填的客量原样保留）")
			+ " ｜ 时钟=" + (worldClock ? "世界" : "现实") + " " + formatHour(hour, simulator)
			+ "（曲线档 " + hour + " = " + curveAt(hour) + "%）");
		lines.add("  周期 " + (periodMillis / 1000) + "s ｜ 平滑 τ=" + Math.round(tauSeconds) + "s（冲量上冲用 "
			+ Math.round(surgeTauSeconds) + "s）｜ 量化 " + step + "%"
			+ " ｜ 班次强度 " + round2(serviceBoost) + " ｜ 停站冲量 +" + dwellSurge + "% ｜ 基准默认 " + defaultBase + "%"
			+ " ｜ 曲线档=" + profileName);
		lines.add("  轮次：已算 " + tickCount + " 拍 ｜ 上一拍写入 " + lastWrittenCount + " 个站台 ｜ 累计到站 " + totalArrivals
			+ " 次 ｜ 在效事件 " + activeEventCount(simulator.getCurrentMillis()) + " 条 ｜ 最忙车站计数 " + maxRate());

		final long maxRate = maxRate();
		final Map<Long, StationAgg> byStation = new TreeMap<>();
		for (final Platform platform : simulator.platforms) {
			final long stationId = stationIdOf(platform);
			final Decision decision = decide(simulator, platform, maxRateRaw());
			final StationAgg agg = byStation.computeIfAbsent(stationId, ignored -> new StationAgg(
				platform.area == null ? "（未归站）" : platform.getStationName(), baseOf(platform)));
			agg.add(decision, platform, currentLevel.getOrDefault(platform.getId(), (double) platform.getCrowdLevel()));
		}

		lines.add("  车站            站台  基准  到站计数  班次因子  冲量  事件     目标  字段  平滑中");
		byStation.forEach((stationId, agg) -> lines.add(String.format(Locale.ENGLISH,
			"  %-14s  %4d  %4d  %8.2f  %8.2f  %4d  %-6s  %4d  %4d  %6.1f",
			agg.name,
			agg.count,
			agg.base,
			agg.rate(),
			maxRate <= 0 || serviceBoost <= 0 ? 1.0 : agg.serviceFactor(),
			agg.surge,
			eventLabelOf(stationId),
			agg.average(agg.targetSum),
			agg.average(agg.fieldSum),
			agg.averageDouble(agg.smoothSum))));

		if (detail) {
			lines.add("  站台明细：");
			for (final Platform platform : simulator.platforms) {
				final Decision decision = decide(simulator, platform, maxRate);
				lines.add(String.format(Locale.ENGLISH, "    %s  %-10s  站名=%s  曲线=%d 基准=%d 因子=%.2f 冲量=%d 事件=%s → 目标=%d 字段=%d 平滑=%.1f %s",
					platform.getHexId(),
					platform.getName(),
					platform.area == null ? "（未归站）" : platform.getStationName(),
					decision.curve(),
					decision.basePercent(),
					decision.serviceFactor(),
					decision.surge(),
					eventLabelOf(stationIdOf(platform)),
					decision.target(),
					platform.getCrowdLevel(),
					currentLevel.getOrDefault(platform.getId(), (double) platform.getCrowdLevel()),
					!enabled ? "（系统关着：这一栏只是「如果打开会是多少」）" : ""));
			}
		}

		lines.add("  口径：目标 = 曲线 × 基准% × 班次因子 × 事件乘法 + 事件加法 + 停站冲量；写入的是**量化后**变了的那些（step=" + step + "%）");
		lines.add("  改：crowd on|off ｜ crowd profile 通勤|全天|夜间|空 ｜ crowd base <车站|站台|all> <0-100> ｜ crowd tune … ｜ crowd event add +40 10 ｜ crowd snap");
		return lines;
	}

	private String eventLabelOf(long stationId) {
		long add = 0;
		double mul = 1.0;
		for (final Event event : events) {
			if (event.stationId == 0 || event.stationId == stationId) {
				add += event.add;
				mul *= event.mul;
			}
		}
		if (add == 0 && Math.abs(mul - 1.0) < 1e-9) {
			return "-";
		}
		return (add == 0 ? "" : (add > 0 ? "+" : "") + add) + (Math.abs(mul - 1.0) < 1e-9 ? "" : "×" + round2(mul));
	}

	private long activeEventCount(long now) {
		long count = 0;
		for (final Event event : events) {
			if (now < event.untilMillis) {
				count++;
			}
		}
		return count;
	}

	/** 逐站汇总（{@code crowd status} 的那张表）：站台级读数按站台数取平均。 */
	private final class StationAgg {

		private final String name;
		private final long base;
		private long count;
		private long targetSum;
		private long fieldSum;
		private long smoothSum;
		private long surge;
		private double rateSum;
		private double serviceSum;

		private StationAgg(String name, long base) {
			this.name = name;
			this.base = base;
		}

		private void add(Decision decision, Platform platform, double smooth) {
			count++;
			targetSum += decision.target();
			fieldSum += platform.getCrowdLevel();
			smoothSum += Math.round(smooth);
			surge = Math.max(surge, decision.surge());
			rateSum += arrivalRate.getOrDefault(groupKeyOf(platform), 0.0);
			serviceSum += decision.serviceFactor();
		}

		private double rate() {
			return count == 0 ? 0 : rateSum / count;
		}

		/** 站台级班次因子的平均（各站台同属一个车站，差别只来自覆盖，平均最直观）。 */
		private double serviceFactor() {
			return count == 0 ? 1.0 : serviceSum / count;
		}

		private long average(long sum) {
			return count == 0 ? 0 : sum / count;
		}

		private double averageDouble(long sum) {
			return count == 0 ? 0 : sum / (double) count;
		}
	}

	/** {@code 09:12}（世界时钟下带游戏内分钟；现实钟点同理）。 */
	private String formatHour(long hour, Simulator simulator) {
		final String prefix = hour < 10 ? "0" : "";
		if (!worldClock) {
			final java.time.LocalTime now = java.time.LocalTime.now();
			return prefix + hour + ":" + (now.getMinute() < 10 ? "0" : "") + now.getMinute();
		}
		final long millisPerDay = Math.max(1L, simulator.getGameMillisPerDay());
		final long minute = simulator.getGameMillisAt(simulator.getCurrentMillis()) * 60 / millisPerDay;
		return prefix + hour + ":" + (minute < 10 ? "0" : "") + minute;
	}

	private static String round2(double value) {
		return String.valueOf(Math.round(value * 100.0) / 100.0);
	}

	private static long clampPercent(long value) {
		return Math.max(MmtrCrowd.MIN_LEVEL, Math.min(MmtrCrowd.MAX_LEVEL, value));
	}

	// ------------------------------------------------------------------ 读写（指令 / 网页）

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		if (!enabled) {
			// 关掉时把"平滑中"清掉：下次打开会直接落到当时的曲线值，而不是从很久以前的半路爬
			currentLevel.clear();
		}
	}

	public boolean isWorldClock() {
		return worldClock;
	}

	public void setWorldClock(boolean worldClock) {
		this.worldClock = worldClock;
	}

	public long getPeriodMillis() {
		return periodMillis;
	}

	public void setPeriodMillis(long value) {
		periodMillis = clampPeriod(value);
	}

	public double getTauSeconds() {
		return tauSeconds;
	}

	public void setTauSeconds(double value) {
		tauSeconds = clampTau(value);
	}

	public double getSurgeTauSeconds() {
		return surgeTauSeconds;
	}

	public void setSurgeTauSeconds(double value) {
		surgeTauSeconds = clampTau(value);
	}

	public long getStep() {
		return step;
	}

	public void setStep(long value) {
		step = clampStep(value);
	}

	public double getServiceBoost() {
		return serviceBoost;
	}

	public void setServiceBoost(double value) {
		serviceBoost = clampServiceBoost(value);
	}

	public long getDwellSurge() {
		return dwellSurge;
	}

	public void setDwellSurge(long value) {
		dwellSurge = clampSurge(value);
	}

	public long getDefaultBase() {
		return defaultBase;
	}

	public void setDefaultBase(long value) {
		defaultBase = clampPercent(value);
	}

	public String getProfileName() {
		return profileName;
	}

	public long[] getCurve() {
		return curve.clone();
	}

	public void setCurve(long[] values) {
		for (int i = 0; i < HOURS && i < values.length; i++) {
			curve[i] = clampPercent(values[i]);
		}
		profileName = "自定义";
	}

	public void setCurveHour(int hour, long value) {
		if (hour >= 0 && hour < HOURS) {
			curve[hour] = clampPercent(value);
			profileName = "自定义";
		}
	}

	public void setStationBase(long stationId, long value) {
		stationBase.put(stationId, clampPercent(value));
	}

	public void setPlatformBase(long platformId, long value) {
		platformBase.put(platformId, clampPercent(value));
	}

	public int clearStationBase(long stationId) {
		final int before = stationBase.size() + platformBase.size();
		stationBase.remove(stationId);
		final int after = stationBase.size() + platformBase.size();
		return before - after;
	}

	public void clearPlatformBase(long platformId) {
		platformBase.remove(platformId);
	}

	public Map<Long, Long> stationBaseView() {
		return new TreeMap<>(stationBase);
	}

	public Map<Long, Long> platformBaseView() {
		return new TreeMap<>(platformBase);
	}

	public List<Event> eventsView() {
		return new ArrayList<>(events);
	}

	public double currentLevelOf(long platformId, double fallback) {
		return currentLevel.getOrDefault(platformId, fallback);
	}

	public long surgeOf(long platformId) {
		return surgeByPlatform.getOrDefault(platformId, 0L);
	}

	public double rateOf(Platform platform) {
		return arrivalRate.getOrDefault(groupKeyOf(platform), 0.0);
	}

	public long getTickCount() {
		return tickCount;
	}

	public long getLastWrittenCount() {
		return lastWrittenCount;
	}

	public long getTotalArrivals() {
		return totalArrivals;
	}

	public long getArrivalCount() {
		return Math.round(arrivalRate.values().stream().mapToDouble(Double::doubleValue).sum());
	}

	// ------------------------------------------------------------------ 参数夹取 / 读 JSON

	private static long clampPeriod(long value) {
		return Math.max(MIN_PERIOD_MILLIS, Math.min(MAX_PERIOD_MILLIS, value));
	}

	private static double clampTau(double value) {
		return Math.max(0, Math.min(3600, value));
	}

	private static long clampStep(long value) {
		return Math.max(1, Math.min(MAX_STEP, value));
	}

	private static double clampServiceBoost(double value) {
		return Math.max(0, Math.min(1.0, value));
	}

	private static long clampSurge(long value) {
		return Math.max(0, Math.min(MAX_DWELL_SURGE, value));
	}

	private static boolean readBoolean(JsonObject o, String key, boolean fallback) {
		try {
			return o.has(key) ? o.get(key).getAsBoolean() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static long readLong(JsonObject o, String key, long fallback) {
		try {
			return o.has(key) ? o.get(key).getAsLong() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static double readDouble(JsonObject o, String key, double fallback) {
		try {
			return o.has(key) ? o.get(key).getAsDouble() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}
}

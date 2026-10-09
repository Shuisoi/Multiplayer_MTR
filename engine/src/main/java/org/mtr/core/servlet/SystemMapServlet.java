package org.mtr.core.servlet;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.Main;
import org.mtr.core.data.NameColorDataBase;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.MmtrMission;
import org.mtr.core.map.*;
import org.mtr.core.operation.ArrivalsRequest;
import org.mtr.core.operation.MmtrMissionControl;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.servlet.WebFeed.Entry;
import org.mtr.core.tool.Utilities;

import java.util.function.Consumer;
import java.util.function.Function;

public final class SystemMapServlet extends ServletBase {

	private final Object2ObjectAVLTreeMap<String, CachedResponse> stationsAndRoutesResponses = new Object2ObjectAVLTreeMap<>();
	private final Object2ObjectAVLTreeMap<String, CachedResponse> departuresResponses = new Object2ObjectAVLTreeMap<>();
	private final Object2ObjectAVLTreeMap<String, CachedResponse> clientsResponses = new Object2ObjectAVLTreeMap<>();

	/*
	 * ------------------------------------------------ 只读接口的快照层（notes/172）
	 *
	 * 这张表是"哪些接口可以不进 tick"的**唯一真源**：表里有名字的接口走
	 * 「已发布的快照 → 够新就在 Jetty 线程直接答 / 过旧才回模拟线程重算一次并发布」，
	 * 表里没有的（写接口、需要请求体的接口）照旧排队到模拟线程。**加一路只读接口 = 这里加一行。**
	 *
	 * 每条的最大年龄**不是刷新率**（刷新率由前端节拍决定，`LIVE_REFRESH_MILLIS` = 2 s），
	 * 而是"同一路接口在这个窗口内可以把前一份重复用在多个请求上"：
	 * 够长 ⇒ 多开几个控制台标签几乎不额外花 tick；够短 ⇒ 网页不会看到旧帧（过期那一拍会有人去重算）。
	 *
	 * 分层依据是"多久变一次"：
	 */
	/** 活数据（网页每一拍都在问：道岔 / 灯 / 车 / 总区间）。 */
	private static final long FEED_LIVE_MAX_AGE_MILLIS = 400L;
	/** 半静态（区间几何、区间图：只在有人改世界、扳岔、设进路时变）。 */
	private static final long FEED_WARM_MAX_AGE_MILLIS = 1_000L;
	/** 静态（轨网 / 线路 / 进向表 / 站台：只在有人修轨道时变）。 */
	private static final long FEED_STATIC_MAX_AGE_MILLIS = 5_000L;

	/**
	 * 接口名 → 快照规格。**包内可见**（不是 private）：成本用例要遍历它，逐路量"这一路现在多贵"。
	 *
	 * <p>键**只用接口名**：这几路都不看第二段路径（`data`）与查询串（`parameters`）——
	 * `SystemMapServlet` 里本来就没用过它们，所以"同一路接口的响应"只有一份。</p>
	 */
	static final Object2ObjectOpenHashMap<String, FeedSpec> FEEDS = new Object2ObjectOpenHashMap<>();
	static {
		FEEDS.put("mmtr-trains", new FeedSpec(SystemMapServlet::getMmtrTrains, FEED_LIVE_MAX_AGE_MILLIS));
		FEEDS.put("mmtr-points", new FeedSpec(SystemMapServlet::getMmtrPoints, FEED_LIVE_MAX_AGE_MILLIS));
		FEEDS.put("mmtr-signals", new FeedSpec(SystemMapServlet::getMmtrSignals, FEED_LIVE_MAX_AGE_MILLIS));
		FEEDS.put("mmtr-total-sections", new FeedSpec(SystemMapServlet::getMmtrTotalSections, FEED_LIVE_MAX_AGE_MILLIS));
		FEEDS.put("mmtr-track-sections", new FeedSpec(SystemMapServlet::getMmtrTrackSections, FEED_WARM_MAX_AGE_MILLIS));
		FEEDS.put("mmtr-block-sections", new FeedSpec(SystemMapServlet::getMmtrBlockSections, FEED_WARM_MAX_AGE_MILLIS));
		FEEDS.put("mmtr-lamps", new FeedSpec(SystemMapServlet::getMmtrLamps, FEED_WARM_MAX_AGE_MILLIS));
		FEEDS.put("mmtr-sections", new FeedSpec(SystemMapServlet::getMmtrSections, FEED_WARM_MAX_AGE_MILLIS));
		FEEDS.put("mmtr-schematic", new FeedSpec(SystemMapServlet::getMmtrSchematic, FEED_WARM_MAX_AGE_MILLIS));
		FEEDS.put("mmtr-topology", new FeedSpec(SystemMapServlet::getMmtrTopology, FEED_STATIC_MAX_AGE_MILLIS));
		FEEDS.put("mmtr-lines", new FeedSpec(SystemMapServlet::getMmtrLines, FEED_STATIC_MAX_AGE_MILLIS));
		FEEDS.put("mmtr-junction-legs", new FeedSpec(SystemMapServlet::getMmtrJunctionLegs, FEED_STATIC_MAX_AGE_MILLIS));
		FEEDS.put("mmtr-platforms", new FeedSpec(SystemMapServlet::getMmtrPlatforms, FEED_STATIC_MAX_AGE_MILLIS));
	}

	/**
	 * 一路只读接口：怎么算（只在模拟线程上算），以及"手上这一份最多容忍多旧"。
	 *
	 * <p>能用它的前提是**纯只读**：不读请求体、不改模拟状态。写接口一律留在 {@link #getContent} 的
	 * switch 里 —— 那是唯一能改状态的地方。</p>
	 */
	record FeedSpec(Function<Simulator, JsonObject> builder, long maxAgeMillis) {
	}

	/**
	 * Cache lifespan for the relatively-static stations / routes payload.
	 */
	private static final long STATIONS_AND_ROUTES_CACHE_MILLIS = 30_000L;
	/**
	 * Cache lifespan for live departures and client positions — short enough for the map UI to feel live, long enough that a busy server isn't recomputing per request.
	 *
	 * <p><b>必须短于控制台的刷新节拍</b>（用户 2026-09-16："所有可变的都需要 0.5s 一次变动"）：
	 * 网页侧是 `LIVE_REFRESH_MILLIS`。原来这里是 3000 ms —— 页面每拍问一次，引擎却三秒才重算一次，
	 * 等于好几拍拿到同一帧，看起来就是"没刷新"。现在留 400 ms：每拍都能拿到新的一帧，
	 * 又不必为每次请求都重算（一个节拍内重复请求仍合并）。</p>
	 *
	 * <p>这一条只覆盖 OBA 那几路。控制台自己的图层接口走 {@link #FEEDS} 的快照层（notes/172），
	 * 那里的窗口是"同一个窗口内多个请求共用一份"，理由与这里相同。</p>
	 */
	private static final long LIVE_DATA_CACHE_MILLIS = 400L;

	/**
	 * Samples per rail in the topology feed's {@code path}.
	 *
	 * <p>A rail's shape is two circular arcs; 32 steps is enough that the polyline is visually
	 * indistinguishable from the arc in a plan view (a 20 m rail segment turns a couple of degrees per
	 * step) while keeping the payload bounded: 33 points × 3 coordinates × 159 rails ≈ 16 K numbers.</p>
	 */
	private static final int MMTR_RAIL_PATH_STEPS = 32;

	/**
	 * A sampled {@code path} coordinate, kept to two decimals.
	 *
	 * <p><b>Do NOT round these to integers</b> (which is what this used to do, for payload size).
	 * Integer rounding turns the arcs back into staircases, and the plan view then shows straight
	 * tracks with hooks and curving tracks as block-sized steps. Measured on the dev world: a nearly
	 * horizontal rail from (-202,74) to (-166,76) had 30 of its 33 samples sitting on the same cell
	 * (z=75) with the last two jumping to 76/77, and rails that really do bend (up to 22.38 blocks off
	 * the straight chord) came out as steps a block wide.</p>
	 *
	 * <p>Two decimals cost ~8 KB more JSON across all rails, and 0.01 blocks is ~0.19 px even at the
	 * console's maximum zoom (0.01 × 2 units/block × 37.5 px/unit) — invisible, so nothing is gained
	 * by dropping them.</p>
	 */
	private static double roundMmtrPathSample(double value) {
		return Math.round(value * 100) / 100.0;
	}

	public SystemMapServlet(ObjectImmutableList<Simulator> simulators) {
		super(simulators);
	}

	/**
	 * 只读接口的入口：`FEEDS` 里有名字的，先问快照层（notes/172）。
	 *
	 * <p>返回非空 ⇒ 这一次请求**根本没进模拟线程**，在 Jetty 线程上就答完了。</p>
	 */
	@Override
	protected @Nullable Entry tryServeFromSnapshot(String endpoint, String data, Object2ObjectAVLTreeMap<String, String> parameters, JsonReader jsonReader, Simulator simulator) {
		final FeedSpec feedSpec = FEEDS.get(endpoint);
		return feedSpec == null ? null : simulator.mmtrWebFeed.serve(endpoint, feedSpec.maxAgeMillis());
	}

	@Override
	public void getContent(String endpoint, String data, Object2ObjectAVLTreeMap<String, String> parameters, JsonReader jsonReader, Simulator simulator, Consumer<@Nullable JsonObject> sendResponse) {
		final FeedSpec feedSpec = FEEDS.get(endpoint);
		if (feedSpec != null) {
			/*
			 * 走到这里说明快照层没答上来：第一次问、或者手上那一份已经过期。这一次由本请求重算，
			 * 结果**发布**出去给后面所有请求共用 —— 于是"多开几个标签"不再等于"多算几遍"。
			 */
			sendResponse.accept(simulator.mmtrWebFeed.publish(endpoint, feedSpec.builder().apply(simulator)));
		} else if (endpoint.equals("directions")) {
			simulator.directionsFinder.addRequest(new DirectionsRequest(jsonReader, directionsResponse -> sendResponse.accept(Utilities.getJsonObjectFromData(directionsResponse)), null));
		} else {
			sendResponse.accept(switch (endpoint) {
				case "stations-and-routes" -> stationsAndRoutesResponses.computeIfAbsent(simulator.dimension, key -> new CachedResponse(SystemMapServlet::getStationsAndRoutes, STATIONS_AND_ROUTES_CACHE_MILLIS)).get(simulator);
				case "departures" -> departuresResponses.computeIfAbsent(simulator.dimension, key -> new CachedResponse(SystemMapServlet::getDepartures, LIVE_DATA_CACHE_MILLIS)).get(simulator);
				case "arrivals" -> Utilities.getJsonObjectFromData(new ArrivalsRequest(jsonReader).getArrivals(simulator));
				case "clients" -> clientsResponses.computeIfAbsent(simulator.dimension, key -> new CachedResponse(SystemMapServlet::getClients, LIVE_DATA_CACHE_MILLIS)).get(simulator);
				case "mmtr-dispatch" -> {
					final boolean ok = new MmtrMissionControl(jsonReader).dispatch(simulator);
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", ok);
					yield result;
				}
				case "mmtr-jobs" -> Utilities.getJsonObjectFromData(simulator.getMmtrJobRegistry());
				case "mmtr-schedule" -> getMmtrSchedule(simulator);
				case "mmtr-job-references" -> getMmtrJobReferences(simulator);
				case "mmtr-job-op" -> {
					final String jobId = jsonReader.getString("jobId", "");
					final String op = jsonReader.getString("op", "");
					final org.mtr.core.mmtr.job.MmtrJobScheduler scheduler = simulator.mmtrJobScheduler;
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					final boolean ok;
					if (scheduler == null) {
						ok = false;
					} else {
						ok = switch (op) {
							case "pause" -> scheduler.pause(jobId);
							case "resume" -> scheduler.resume(jobId);
							case "human" -> scheduler.humanTakeover(jobId);
							case "release" -> scheduler.releaseToAutopilot(jobId);
							default -> false;
						};
					}
					result.addProperty("ok", ok);
					yield result;
				}
				case "mmtr-motion" -> getMmtrMotion(simulator);
				case "mmtr-jobs-upsert" -> {
					final org.mtr.core.mmtr.job.MmtrConsistJob job = new org.mtr.core.mmtr.job.MmtrConsistJob(jsonReader);
					simulator.upsertMmtrJob(job);
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", true);
					yield result;
				}
				case "mmtr-jobs-delete" -> {
					final String jobId = jsonReader.getString("jobId", "");
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", simulator.deleteMmtrJob(jobId));
					yield result;
				}
				case "mmtr-rolling-stock" -> Utilities.getJsonObjectFromData(simulator.getMmtrRollingStock());
				case "mmtr-plan" -> {
					// P1 时刻表生成器的输入层：线路 / 分段密度 / 车底 + 校验结果。
					// 设计文档里写的是 REST 风格 /mtr/api/mmtr/plan/*；本引擎的 servlet 挂在
					// /mtr/api/map/* 下，所以这里按既有惯例给 mmtr-plan* 几个端点（映射关系记在 notes）。
					final com.google.gson.JsonObject result = Utilities.getJsonObjectFromData(simulator.getMmtrPlanInputs());
					final com.google.gson.JsonArray errors = new com.google.gson.JsonArray();
					for (final String error : simulator.mmtrPlanErrors) {
						errors.add(error);
					}
					result.add("errors", errors);
					result.addProperty("valid", errors.isEmpty());
					result.addProperty("configured", !simulator.getMmtrPlanInputs().isEmpty());
					/*
					 * P6 ④ / 指派页：**手工覆盖要看得见**（设计 §10 的验收"手工覆盖优先于自动排班且可见"）。
					 *
					 * 之前只能从"趟次跑到别人名下了"倒推人干过什么；这里把登记过的那几条原样列出来
					 * （[从哪个编组, 从哪一趟（空 = 全部）, 交给谁]），指派页就不再需要靠猜。
					 */
					final com.google.gson.JsonArray assignments = new com.google.gson.JsonArray();
					for (final String[] assignment : simulator.mmtrPlanManualAssignments) {
						final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
						out.addProperty("fromConsistId", assignment.length > 0 ? assignment[0] : "");
						out.addProperty("fromTripId", assignment.length > 1 ? assignment[1] : "");
						out.addProperty("toConsistId", assignment.length > 2 ? assignment[2] : "");
						assignments.add(out);
					}
					result.add("assignments", assignments);
					yield result;
				}
				case "mmtr-plan-line-upsert" -> {
					simulator.upsertMmtrLine(new org.mtr.core.mmtr.plan.MmtrLine(jsonReader));
					yield planResult(simulator);
				}
				case "mmtr-plan-pattern-upsert" -> {
					simulator.upsertMmtrPattern(new org.mtr.core.mmtr.plan.MmtrPattern(jsonReader));
					yield planResult(simulator);
				}
				case "mmtr-plan-fleet-upsert" -> {
					simulator.upsertMmtrFleet(new org.mtr.core.mmtr.plan.MmtrFleet(jsonReader));
					yield planResult(simulator);
				}
				case "mmtr-plan-line-delete" -> {
					final boolean ok = simulator.deleteMmtrLine(jsonReader.getString("lineId", ""));
					final com.google.gson.JsonObject result = planResult(simulator);
					result.addProperty("deleted", ok);
					yield result;
				}
				case "mmtr-plan-events" -> {
					// P5：运行时事件（生效中 + 即将生效 + 全部）—— 前台展示的契约就是"类型/目标/起止/理由/剩余"。
					final long dayTime = simulator.mmtrPlanDayTime();
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("dayTimeMs", dayTime);
					final com.google.gson.JsonArray lines = new com.google.gson.JsonArray();
					for (final String line : simulator.mmtrPlanEvents.describeForFeed(dayTime)) {
						lines.add(line);
					}
					result.add("feed", lines);
					final com.google.gson.JsonArray all = new com.google.gson.JsonArray();
					for (final org.mtr.core.mmtr.plan.MmtrEvent event : simulator.mmtrPlanEvents.all()) {
						final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
						out.addProperty("eventId", event.eventId);
						out.addProperty("kind", event.kind().name());
						out.addProperty("kindName", event.kindName());
						out.addProperty("targetKind", event.targetKind.name());
						out.addProperty("targetName", event.targetName());
						out.addProperty("startMillis", event.startMillis);
						out.addProperty("endMillis", event.endMillis);
						out.addProperty("state", event.stateAt(dayTime).name());
						out.addProperty("remaining", event.remainingText(dayTime));
						out.addProperty("reason", event.reason);
						out.addProperty("describe", event.describe(dayTime));
						all.add(out);
					}
					result.add("events", all);
					yield result;
				}
				case "mmtr-plan-event-upsert" -> {
					simulator.mmtrPlanEvents.put(readPlanEvent(jsonReader));
					simulator.persistMmtrPlanInputs();
					yield planResult(simulator);
				}
				case "mmtr-plan-event-delete" -> {
					final String eventId = jsonReader.getString("eventId", "");
					final boolean removed = jsonReader.getBoolean("end", false)
						? simulator.mmtrPlanEvents.end(eventId, simulator.mmtrPlanDayTime())
						: simulator.mmtrPlanEvents.remove(eventId);
					simulator.persistMmtrPlanInputs();
					final com.google.gson.JsonObject out = planResult(simulator);
					out.addProperty("removed", removed);
					yield out;
				}
				case "mmtr-plan-takeover" -> {
					// P6 ③：AI ↔ 玩家接管（只换执行者：交路与任务不变；玩家开着的车派发器不派）。
					final String consistId = jsonReader.getString("consistId", "");
					final boolean player = jsonReader.getBoolean("player", true);
					final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
					if (consistId.isEmpty()) {
						out.addProperty("ok", false);
						out.addProperty("message", "需要 consistId");
					} else {
						final boolean changed = simulator.setMmtrPlanPlayerDriven(consistId, player);
						out.addProperty("ok", true);
						out.addProperty("changed", changed);
						out.addProperty("player", player);
						out.addProperty("message", (player ? "玩家接管 " : "归还给 AI ") + consistId
							+ (changed ? "" : "（状态本来就是这样，无变化）"));
					}
					yield out;
				}
				case "mmtr-plan-assign" -> {
					// P6 ④：手工指派（优先于自动排班）。fromTripId 空 = 从该车第一趟起。
					final String from = jsonReader.getString("fromConsistId", "");
					final String fromTrip = jsonReader.getString("fromTripId", "");
					final String to = jsonReader.getString("toConsistId", "");
					final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
					if (from.isEmpty() || to.isEmpty()) {
						out.addProperty("ok", false);
						out.addProperty("message", "需要 fromConsistId 与 toConsistId");
					} else {
						simulator.assignMmtrPlanManually(from, fromTrip, to);
						simulator.mmtrRefreshPlanDispatchers();
						out.addProperty("ok", true);
						out.addProperty("message", "已指派：" + from + (fromTrip.isEmpty() ? " 全部" : " 自 " + fromTrip) + " → " + to);
					}
					yield out;
				}
				case "mmtr-plan-diagrams" -> {					// P4：派发器现场 —— 每条线路的周转/N/分车/已派步数（交路的产物在这里看得见）。
					simulator.mmtrRefreshPlanDispatchers();
					final long diagramDayTime = simulator.mmtrPlanDayTime();
					final com.google.gson.JsonArray lines = new com.google.gson.JsonArray();
					simulator.mmtrPlanDispatchers.forEach((lineId, dispatcher) -> {
						final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
						out.addProperty("lineId", lineId);
						out.addProperty("ringMillis", dispatcher.diagram.ringMillis);
						out.addProperty("peakHeadwayMillis", dispatcher.diagram.peakHeadwayMillis);
						out.addProperty("requiredConsists", dispatcher.diagram.requiredConsists);
						out.addProperty("yardSidingId", String.valueOf(dispatcher.yardSidingId));
						out.addProperty("dispatchedTotal", dispatcher.dispatchedTotal);
						out.addProperty("retryCount", dispatcher.retryCount);
						// notes/140 的尾巴：跳过（迟到不补跑）与"正在等哪一步"也要看得见 —— 运营台排查"车为什么没动"就靠这两个数
						out.addProperty("skippedSteps", dispatcher.skippedSteps);
						out.addProperty("complete", dispatcher.isComplete());
						if (dispatcher.diagram.capacityProblem != null) {
							out.addProperty("capacityProblem", dispatcher.diagram.capacityProblem);
						}
						final com.google.gson.JsonArray workings = new com.google.gson.JsonArray();
						final java.util.HashSet<String> reported = new java.util.HashSet<>();
						for (final org.mtr.core.mmtr.plan.MmtrPlanDispatcher.WorkingState state : dispatcher.snapshot()) {
							final com.google.gson.JsonObject w = new com.google.gson.JsonObject();
							w.addProperty("consistId", state.consistId);
							w.addProperty("vehicleId", String.valueOf(state.vehicleId));
							w.addProperty("steps", state.tasks.size());
							w.addProperty("dispatchedSteps", state.dispatchedSteps);
							w.addProperty("awaitingTaskId", state.awaitingTaskId);
							// P6 ③：交路页上的"接管/归还"按钮要知道现在是谁在开（否则按了才知道状态）
							w.addProperty("playerDriven", dispatcher.isPlayerDriven(state.consistId));
							final org.mtr.core.mmtr.task.MmtrTask next = state.nextTask();
							if (next != null) {
								w.addProperty("nextKind", next.kind().name());
								w.addProperty("nextTarget", String.valueOf(next.targetRef));
								w.addProperty("nextDueMs", next.dueMs);
								w.addProperty("nextDescribe", next.describe());
							}
							/*
							 * P6 ⑤：交路页要的是"计划 vs 实际"同一行 —— 所以这里直接带上
							 * {@link MmtrPlanFeed#timeline}（条目窗口三态 + 那串步谁派出去了）。
							 * 前端不再自己拼两份列表，口径就只有一处。
							 */
							for (final org.mtr.core.mmtr.plan.MmtrDiagram.Working candidate : dispatcher.diagram.workings) {
								if (candidate.consistId.equals(state.consistId)) {
									w.add("timeline", org.mtr.core.mmtr.plan.MmtrPlanFeed.timeline(candidate, state, diagramDayTime));
									break;
								}
							}
							workings.add(w);
							reported.add(state.consistId);
						}
						/*
						 * notes/144 §3 ①：**今天没班的编组也要出现在名单里**。
						 *
						 * 手工指派把某个编组的趟次全搬走之后，它就不再有"出库"那条（P3 的 scheduledWorkings
						 * 按"有没有出库"筛），于是运营台上**整辆车像是消失了** —— 操作者会以为配置丢了。
						 * 这里把"有交路记录但今天没班"的编组补一行，写明原因，而不是让它凭空不见。
						 */
						for (final org.mtr.core.mmtr.plan.MmtrDiagram.Working working : dispatcher.diagram.workings) {
							if (reported.contains(working.consistId)) {
								continue;
							}
							final com.google.gson.JsonObject w = new com.google.gson.JsonObject();
							w.addProperty("consistId", working.consistId);
							w.addProperty("vehicleId", "0");
							w.addProperty("steps", 0);
							w.addProperty("dispatchedSteps", 0);
							w.addProperty("awaitingTaskId", "");
							w.addProperty("idle", true);
							w.addProperty("playerDriven", dispatcher.isPlayerDriven(working.consistId));
							w.addProperty("note", working.entries.isEmpty()
								? "今天没有班（趟次已被指派给别人）"
								: "今天没有班（只剩 " + working.entries.size() + " 条收尾条目）");
							// 没班的编组也给一条时间轴（计划还在），前端一行只需认一个字段
							w.add("timeline", org.mtr.core.mmtr.plan.MmtrPlanFeed.timeline(working, null, diagramDayTime));
							workings.add(w);
						}
						out.add("workings", workings);
						lines.add(out);
					});
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.add("lines", lines);
					result.addProperty("configured", !simulator.getMmtrPlanInputs().isEmpty());
					result.addProperty("problems", simulator.mmtrPlanErrors.size());
					yield result;
				}
				/*
				 * P6 ⑤（WEB 六页）要的只读数据：趟次表、现场可选项、强制重排。
				 *
				 * 三个都只是"把已经在内存里的东西按契约端出去"（计算全在纯函数 MmtrPlanFeed 里），
				 * 所以网页那一侧不需要任何新算法，也不需要认识引擎内部。
				 */
				case "mmtr-plan-service-plan" -> {				// 趟次表（乘客视角 · 与车无关，两个方向）
					simulator.mmtrRefreshPlanDispatchers();
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					final com.google.gson.JsonArray plans = new com.google.gson.JsonArray();
					final org.mtr.core.mmtr.plan.MmtrPlanInputs inputs = simulator.getMmtrPlanInputs();
					for (final org.mtr.core.mmtr.plan.MmtrLine line : inputs.lines) {
						final org.mtr.core.mmtr.plan.MmtrPattern pattern = inputs.pattern(line.lineId);
						if (pattern == null) {
							continue;   // 没有密度表 = 这条线不排班（输入层已报"未覆盖运营时段"）
						}
						final double speedKmh = inputs.fleet.consists.isEmpty() ? 0 : inputs.fleet.consists.get(0).maxSpeedKmh;
						plans.add(org.mtr.core.mmtr.plan.MmtrPlanFeed.servicePlan(
							line, pattern, org.mtr.core.mmtr.plan.MmtrRailTravelTimes.of(simulator, line, speedKmh)));
					}
					result.add("lines", plans);
					result.addProperty("dayTimeMs", simulator.mmtrPlanDayTime());
					result.addProperty("configured", !inputs.isEmpty());
					result.addProperty("problems", simulator.mmtrPlanErrors.size());
					yield result;
				}
				case "mmtr-plan-world" -> {						// 现场可选项：站与站台、车辆段与股道（网页下拉框用）
					final it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.mmtr.plan.MmtrPlanFeed.StationInfo> stationInfos = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
					simulator.stations.forEach(station -> stationInfos.add(new org.mtr.core.mmtr.plan.MmtrPlanFeed.StationInfo(station.getId(), station.getName())));
					simulator.platforms.forEach(platform -> {
						final org.mtr.core.data.AreaBase<?, ?> area = platform.area;
						for (final org.mtr.core.mmtr.plan.MmtrPlanFeed.StationInfo info : stationInfos) {
							if (area != null && area.getId() == info.id) {
								info.addPlatform(platform.getId(), platform.getName(), platform.getDwellTime());
								break;
							}
						}
					});
					// 没有站台的站也留在清单里：现场就有这种站，网页上要能看见而不是"凭空消失"
					final it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.mmtr.plan.MmtrPlanFeed.DepotInfo> depotInfos = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
					simulator.depots.forEach(depot -> depotInfos.add(new org.mtr.core.mmtr.plan.MmtrPlanFeed.DepotInfo(depot.getId(), depot.getName())));
					simulator.sidings.forEach(siding -> {
						final org.mtr.core.data.AreaBase<?, ?> area = siding.area;
						for (final org.mtr.core.mmtr.plan.MmtrPlanFeed.DepotInfo info : depotInfos) {
							if (area != null && area.getId() == info.id) {
								info.addSiding(siding.getId(), siding.getName(), simulator.countVehiclesOnSiding(siding.getId()), siding.getRailLength());
								break;
							}
						}
					});
					final com.google.gson.JsonObject worldResult = org.mtr.core.mmtr.plan.MmtrPlanFeed.world(stationInfos, depotInfos);
					worldResult.addProperty("dayTimeMs", simulator.mmtrPlanDayTime());
					yield worldResult;
				}
				case "mmtr-plan-replan" -> {					// 强制滚动重算（网页上的"重新排班"按钮）
					final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
					final int rebuiltLines = simulator.mmtrForceReplan();
					out.addProperty("ok", true);
					out.addProperty("rebuiltLines", rebuiltLines);
					out.addProperty("configured", !simulator.getMmtrPlanInputs().isEmpty());
					out.addProperty("problems", simulator.mmtrPlanErrors.size());
					out.addProperty("message", rebuiltLines > 0 ? "已按当前配置重排 " + rebuiltLines + " 条线路" : "输入没有变化或没有可排的线路");
					yield out;
				}
				case "mmtr-manifest-reset" -> {
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", true);
					result.addProperty("placedSidings", simulator.mmtrResetAndApplyRollingStock());
					yield result;
				}
				case "mmtr-vehicle-op" -> {
					final String op = jsonReader.getString("op", "");
					final String rawVehicleId = jsonReader.getString("vehicleId", "").trim();
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("op", op);
					if (op.equals("clear-all")) {
						simulator.mmtrClearAllVehicles();
						result.addProperty("ok", true);
						yield result;
					}
					boolean ok = false;
					if (rawVehicleId.isEmpty()) {
						result.addProperty("ok", false);
						result.addProperty("error", "vehicleId is required");
					} else {
						try {
							final long vehicleId = Long.parseLong(rawVehicleId);
							// Reserved vehicle-level task sheet (per-train 作业表) - future layer.
							if (op.equals("delete")) {
								ok = simulator.deleteMmtrVehicle(vehicleId);
								if (!ok) {
									result.addProperty("error", "no vehicle with id " + rawVehicleId);
								}
							} else if (op.equals("cab-enter") || op.equals("cab-leave") || op.equals("change-ends")) {
								// B7.6: crew cab ops (key in / key out / 换端) on the consist model. The
								// game side has already checked the driver's key and position; the engine
								// enforces the physical gates (consist model, train at a stand).
								final org.mtr.core.data.Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
								if (vehicle == null) {
									result.addProperty("error", "no vehicle with id " + rawVehicleId);
								} else if (vehicle.getMmtrConsistWalker() == null) {
									result.addProperty("error", "vehicle " + rawVehicleId + " is not a consist-body train");
								} else if (op.equals("cab-enter")) {
									final String cabName = jsonReader.getString("cab", "CAB_A");
									org.mtr.core.mmtr.consist.MmtrCabState.Cab cab = org.mtr.core.mmtr.consist.MmtrCabState.Cab.NONE;
									try {
										cab = org.mtr.core.mmtr.consist.MmtrCabState.Cab.valueOf(cabName);
									} catch (IllegalArgumentException e) {
										result.addProperty("error", "cab must be CAB_A or CAB_B");
									}
									if (cab != org.mtr.core.mmtr.consist.MmtrCabState.Cab.NONE) {
										ok = vehicle.enterMmtrCab(cab);
										if (!ok) {
											result.addProperty("error", "cannot take " + cabName + " (train moving or cab occupied)");
										}
									}
								} else if (op.equals("cab-leave")) {
									ok = vehicle.leaveMmtrCab();
								} else {
									ok = vehicle.changeEndsMmtrMotion();
									if (!ok) {
										result.addProperty("error", "cannot change ends (train moving, no cab manned, or not a consist)");
									}
								}
								if (ok) {
									result.addProperty("activeCab", vehicle.getMmtrActiveCab().name());
								}
							} else {
								result.addProperty("error", "unsupported op '" + op + "'");
							}
						} catch (NumberFormatException e) {
							result.addProperty("error", "vehicleId must be numeric");
						}
					}
					result.addProperty("ok", ok);
					yield result;
				}
				case "mmtr-vehicle-task" -> {
					// Reserved: assign a task sheet (作业表) to one generated train by vehicle id.
					final String rawVehicleId = jsonReader.getString("vehicleId", "").trim();
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", true);
					result.addProperty("reserved", true);
					result.addProperty("message", "per-vehicle task sheets are reserved (vehicleId=" + rawVehicleId + ")");
					yield result;
				}
				case "mmtr-job-states" -> {
					final com.google.gson.JsonArray states = new com.google.gson.JsonArray();
					final org.mtr.core.mmtr.job.MmtrJobScheduler scheduler = simulator.mmtrJobScheduler;
					for (final org.mtr.core.mmtr.job.MmtrConsistJob job : simulator.getMmtrJobRegistry().jobs) {
						final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
						out.addProperty("jobId", job.jobId);
						out.addProperty("startTimeOfDayMs", job.startTimeOfDayMs);
						final org.mtr.core.mmtr.job.MmtrJobScheduler.JobState state = scheduler == null ? null : scheduler.stateOf(job.jobId);
						out.addProperty("state", state == null ? "PENDING" : state.name());
						out.addProperty("step", scheduler == null ? -1 : scheduler.stepIndexOf(job.jobId));
						out.addProperty("totalSteps", job.steps.size());
						out.addProperty("cars", scheduler == null ? job.cars.size() : scheduler.carsOf(job.jobId));
						out.addProperty("paused", scheduler != null && scheduler.isPaused(job.jobId));
						out.addProperty("human", scheduler != null && scheduler.isHumanHeld(job.jobId));
						final String failure = scheduler == null ? null : scheduler.failureOf(job.jobId);
						if (failure != null) {
							out.addProperty("failure", failure);
						}
						states.add(out);
					}
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.add("states", states);
					yield result;
				}
				case "mmtr-junction-legs-upsert" -> {
					final long x = jsonReader.getLong("x", 0);
					final long y = jsonReader.getLong("y", 0);
					final long z = jsonReader.getLong("z", 0);
					final String via = jsonReader.getString("via", "");
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					if (via.isEmpty()) {
						result.addProperty("ok", false);
						yield result;
					}
					final it.unimi.dsi.fastutil.objects.ObjectArrayList<String> legs = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
					jsonReader.iterateStringArray("legs", legs::clear, legs::add);
					result.addProperty("ok", simulator.mmtrJunctionLegsUpsert(x, y, z, via, legs));
					yield result;
				}
				/*
				 * 图层接口（topology / lines / points / junction-legs / signals / sections /
				 * track-sections / block-sections / lamps / platforms / total-sections / schematic）
				 * **不在这张 switch 里** —— 它们是只读接口，走上面的 {@link #FEEDS} 快照层：
				 * 那一层负责"够新就直接答、过旧才回模拟线程重算一次并发布"。
				 *
				 * 单一图层接口（notes/167）：每一层单独一份，字段含义只有一种 ——
				 *
				 *	/mmtr-track-sections  Level 1 轨道区间（无方向、双向共用）—— **占用判定单位**
				 *	/mmtr-block-sections  Level 2 行车区间（有方向、灯到灯）—— **授权单位**，带 L1 成员表
				 *	/mmtr-lamps           每盏灯的**绑定**（它开的段 + 段的状态 + 显示）
				 *	/mmtr-total-sections  **总区间**（地图上一条带 = 一个位置 + 覆盖它的各方向区间）
				 *
				 * `/mmtr-sections` 仍在（运营台在用），它是"区间图层"的综合包；这四个是新代码该用的入口。
				 */
				case "mmtr-command" -> {
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					final String command = jsonReader.getString("command", "");
					if (command.isEmpty()) {
						// 空命令 = 只读日志（控制台轮询游戏端命令结果时用）
						result.addProperty("ok", false);
						result.addProperty("error", "command is required");
					} else if (command.startsWith("signals scan")) {
						// 这条依赖"枚举已加载区块"，只有游戏端能做 → 交给游戏端命令通道
						simulator.mmtrPushCommand(command);
						result.addProperty("ok", true);
						result.addProperty("queued", true);
					} else {
						/*
						 * 中控指令（名词打头）：引擎侧直接执行。
						 *
						 * 引擎本来就持有车辆段、股道、信号、道岔的全部权威状态，所以绝大多数操作
						 * 不需要绕到游戏端，也不需要重启 —— 回复里直接带上受影响的对象 id 与实情，
						 * 调用方可以立刻核对，而不是收到一句"OK 已入队"。
						 */
						final org.mtr.core.mmtr.command.MmtrCommandDispatcher.Result executed = simulator.mmtrExecuteCommand(command);
						result.addProperty("ok", executed.ok);
						result.addProperty("namespace", executed.namespace);
						result.addProperty("verb", executed.verb);
						final com.google.gson.JsonArray affected = new com.google.gson.JsonArray();
						executed.affected.forEach(affected::add);
						result.add("affected", affected);
						final com.google.gson.JsonArray lines = new com.google.gson.JsonArray();
						executed.lines.forEach(lines::add);
						result.add("lines", lines);
						// 同时写进命令日志：网页指令栏与游戏端共用同一份历史
						simulator.mmtrCommandResult((executed.ok ? "[ok] " : "[失败] ") + command);
						executed.lines.forEach(line -> simulator.mmtrCommandResult("    " + line));
					}
					final com.google.gson.JsonArray log = new com.google.gson.JsonArray();
					simulator.mmtrCommandLog.forEach(log::add);
					result.add("log", log);
					yield result;
				}
				case "mmtr-signal-op" -> {
					final long x = jsonReader.getLong("x", 0);
					final long y = jsonReader.getLong("y", 0);
					final long z = jsonReader.getLong("z", 0);
					final float angle = (float) jsonReader.getDouble("angle", 0);
					final int aspects = jsonReader.getInt("aspects", 2);
					final String op = jsonReader.getString("op", "set");
					final String target = jsonReader.getString("target", "");
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					if (op.equals("set") && target.isEmpty() && jsonReader.has("nodeX") && jsonReader.has("nodeY") && jsonReader.has("nodeZ")) {
						// Game-side bind tool upload: infer the read rail from the clicked node +
						// the light facing (covered bind), register BOUND.
						result.addProperty("ok", simulator.mmtrSignalBindAtNode((int) x, (int) y, (int) z, angle, aspects,
							jsonReader.getLong("nodeX", 0), jsonReader.getLong("nodeY", 0), jsonReader.getLong("nodeZ", 0)));
					} else {
						result.addProperty("ok", simulator.mmtrSignalOp((int) x, (int) y, (int) z, angle, aspects, op, target));
					}
					yield result;
				}
				case "mmtr-point-op" -> {
					final long x = jsonReader.getLong("x", 0);
					final long y = jsonReader.getLong("y", 0);
					final long z = jsonReader.getLong("z", 0);
					final String via = jsonReader.getString("via", "");
					// "branch" is optional: a lock/unlock-only op must not clobber the operator branch.
					final boolean hasBranch = jsonReader.has("branch");
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", !via.isEmpty());
					if (!via.isEmpty()) {
						if (hasBranch) {
							simulator.mmtrSetPoint(x, y, z, via, jsonReader.getInt("branch", 0));
						}
						if (jsonReader.getBoolean("lock", false)) {
							simulator.mmtrPointLock(x, y, z, via);
							result.addProperty("locked", true);
						}
						if (jsonReader.getBoolean("unlock", false)) {
							simulator.mmtrPointUnlock(x, y, z, via);
							result.addProperty("locked", false);
						}
					}
					yield result;
				}
				case "mmtr-point-req" -> {
					final long x = jsonReader.getLong("x", 0);
					final long y = jsonReader.getLong("y", 0);
					final long z = jsonReader.getLong("z", 0);
					final String via = jsonReader.getString("via", "");
					final String owner = jsonReader.getString("owner", "");
					final int leg = jsonReader.getInt("leg", 0);
					final long untilMillis = jsonReader.getLong("untilMillis", System.currentTimeMillis() + 10L * 60L * 1000L);
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					if (via.isEmpty() || owner.isEmpty()) {
						result.addProperty("ok", false);
					} else {
						result.addProperty("result", simulator.mmtrPointRequest(x, y, z, via, owner, leg, untilMillis).name());
					}
					yield result;
				}
				case "mmtr-point-rel" -> {
					final long x = jsonReader.getLong("x", 0);
					final long y = jsonReader.getLong("y", 0);
					final long z = jsonReader.getLong("z", 0);
					final String via = jsonReader.getString("via", "");
					final String owner = jsonReader.getString("owner", "");
					final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
					result.addProperty("ok", !via.isEmpty() && !owner.isEmpty());
					if (!via.isEmpty() && !owner.isEmpty()) {
						simulator.mmtrPointRelease(x, y, z, via, owner);
					}
					yield result;
				}
				default -> null;
			});
		}
	}

	private static JsonObject getStationsAndRoutes(Simulator simulator) {
		final StationAndRoutes stationAndRoutes = new StationAndRoutes(simulator.dimensions);
		simulator.stations.forEach(stationAndRoutes::addStation);
		simulator.routes.forEach(stationAndRoutes::addRoute);
		return Utilities.getJsonObjectFromData(stationAndRoutes);
	}

	private static JsonObject getDepartures(Simulator simulator) {
		final long currentMillis = System.currentTimeMillis();
		final Object2ObjectAVLTreeMap<String, Long2ObjectAVLTreeMap<LongArrayList>> departures = new Object2ObjectAVLTreeMap<>();
		simulator.sidings.forEach(siding -> siding.getDeparturesForMap(currentMillis, departures));
		return Utilities.getJsonObjectFromData(new Departures(currentMillis, departures));
	}

	private static JsonObject getClients(Simulator simulator) {
		final long currentMillis = System.currentTimeMillis();
		final Object2ObjectAVLTreeMap<String, Client> clients = new Object2ObjectAVLTreeMap<>();

		simulator.clients.forEach(client -> {
			final String clientId = client.uuid.toString();
			clients.put(clientId, new Client(
				clientId, Main.CLIENT_NAME_RESOLVER == null ? "" : Main.CLIENT_NAME_RESOLVER.apply(client.uuid),
				client.getPosition().getX(), client.getPosition().getZ(),
				simulator.stations.stream().filter(station -> station.inArea(client.getPosition())).map(NameColorDataBase::getHexId).findFirst().orElse("")
			));
		});

		simulator.sidings.forEach(siding -> siding.iterateVehiclesAndRidingEntities((vehicleExtraData, vehicleRidingEntity) -> {
			final String clientId = vehicleRidingEntity.uuid.toString();
			final Client client = clients.get(clientId);
			if (client != null) {
				clients.put(clientId, new Client(
					client,
					Utilities.numberToPaddedHexString(vehicleExtraData.getThisRouteId()),
					Utilities.numberToPaddedHexString(vehicleExtraData.getThisStationId()),
					Utilities.numberToPaddedHexString(vehicleExtraData.getNextStationId())
				));
			}
		}));

		return Utilities.getJsonObjectFromData(new Clients(currentMillis, new ObjectArrayList<>(clients.values())));
	}

	private static JsonObject getMmtrTrains(Simulator simulator) {
		final long currentMillis = System.currentTimeMillis();
		/*
		 * 一次建好、逐车查表的轨索引（notes/172 实测的优化）。
		 *
		 * <p>原来**每辆车**都要在 `simulator.rails` 里线性找"我脚下这根轨"，再在 `positionsToRail` 里
		 * 线性找"这根轨的折线首端"——两次都要对世界里的每一条轨算一遍 `canonicalHex`（字符串拼接）。
		 * 159 轨 / 6 辆车的 dev 世界里，这一路接口的**构建**因此要 179 ms（实测 warmAvg），
		 * 即每刷新一次地图就吃掉三个多 tick；而它是地图页每一拍都在问的那一路。</p>
		 */
		final MmtrRailIndex railIndex = new MmtrRailIndex(simulator);
		final com.google.gson.JsonArray trains = new com.google.gson.JsonArray();
		final com.google.gson.JsonArray sidings = new com.google.gson.JsonArray();
		simulator.sidings.forEach(siding -> {
			final int[] vehicleCount = {0};
			final int[] parkedCount = {0};
			siding.iterateVehicles(vehicle -> {
				vehicleCount[0]++;
				if (!vehicle.getIsOnRoute()) {
					parkedCount[0]++;
				}
				final com.google.gson.JsonObject train = new com.google.gson.JsonObject();
				train.addProperty("vehicleId", String.valueOf(vehicle.getId()));
				train.addProperty("sidingId", String.valueOf(siding.getId()));
				train.addProperty("sidingName", siding.getName());
				train.addProperty("depotName", siding.getDepotName());
				train.addProperty("routeName", vehicle.vehicleExtraData.getThisRouteName());
				train.addProperty("routeNumber", vehicle.vehicleExtraData.getThisRouteNumber());
				train.addProperty("destination", vehicle.vehicleExtraData.getThisRouteDestination());
				train.addProperty("isManualAllowed", vehicle.vehicleExtraData.getIsManualAllowed());
				train.addProperty("isCurrentlyManual", vehicle.isCurrentlyManual());
				train.addProperty("onRoute", vehicle.getIsOnRoute());
				train.addProperty("moving", vehicle.isMoving());
				/*
				 * notes/149：**"车为什么不动"要能从接口上读出来**。
				 *
				 * 现场问题是"任务挂上了、进路 SET/PENDING、车速 0"，而司机台上显示的是"应答信号（按 H）"。
				 * 这三件事其实指向三层不同的门，接口上却一个都看不见：
				 *   - 手动接管（mmtrManualOverride）：车交给人在开 ⇒ 引擎**不允许**自己开（autoActive 要求它关着）；
				 *   - 动车子系统自臂（mmtrMotionAuto）：自动驾驶那一路有没有接上；
				 *   - AWS 点式警告（未确认）：只在**手动**驾驶时才会响 —— 所以"H 提示"本身就是"这车在手动"的证据。
				 * 把这三条吐出来，"按 H 有没有用"这种问题就能用数据回答，而不是靠猜。
				 */
				/*
				 * notes/152：**"车为什么不动"要能一路读到闭塞层**。
				 *
				 * 现场症状是"进路 SET、道岔都拿到、车速 0、授权 RED：停在这架信号前"。
				 * 这里把该车**下一根轨所属区间**与**占用者**摆出来（分"算不算自己"两问），
				 * 于是三种成因一眼可分：前面真有车 / 停着的邻车压进运行区间 / 问话的车自己的影子。
				 */
				final org.mtr.core.mmtr.segment.MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
				if (walker != null) {
					final org.mtr.core.data.Rail nextRail = walker.peekNextRail();
					final String nextRailHex = nextRail == null ? walker.railHex() : nextRail.getHexId();
					train.addProperty("currentRail", String.valueOf(walker.railHex()));
					/*
					 * **车辆在图上画在哪**（2026-09-16 用户："读取车辆位置，在地图页显示"）。
					 *
					 * <p>{@code headX/headZ} 是引擎世界坐标里那个点（采样那一套，方块中心），而网页画轨时
					 * 会把整条 path 按每条轨自己的校正量挪到**端点**上（见 `RailNodesLayer` 的注释：
					 * "采样走方块中心、节点坐标走方块角"）—— 直接拿 headX/headZ 落点，车会偏半格，
					 * 与刚修掉的"信号灯左右分布不均匀"是同一个坑。</p>
					 *
					 * <p>所以这里不发坐标，发**位置在轨上的读数**：{@code railHex}（规范形式，与
					 * `/mmtr-topology` 的 rails[].hex 同一写法）+ {@code railArcM}（车头在**那根轨的弧空间**里的
					 * 弧长）+ {@code railArcLengthM}。前端按 `railArcM / railArcLengthM` 在**它已经画出来的那条
					 * 折线**上插值 —— 曲线轨也对得上，且不用自己认坐标系。</p>
					 *
					 * <p>弧空间的口径：`Rail.mmtrArcOfEndNode` 给某个端点的弧长（≈0 或 ≈轨长），
					 * 而 walker 的 {@code offsetM} 是"**从入口节点往前方**"量的 —— 所以车头弧 = 入口弧 + 偏移，
					 * 入口在远端时换成 轨长 − 偏移。{@code arcIncreasing} 说清行进方向与弧增方向是否一致
					 * （前端画箭头/车头朝向用）。</p>
					 */
					/*
					 * 报出去的弧必须是**沿 /mmtr-topology 那条折线**量的。
					 *
					 * <p>三条坑叠在一起（实测 7 辆车错了 2 辆，探针把 `headX/headZ` 投到折线上才发现）：
					 * ① `walker.currentRail()` 可能是**方向被翻过**的副本；② `enteredFromPosition()` 未必与轨的
					 * 端点逐位相等；③ 更要命的是**弧空间本身**：拓扑发 path 时从"位置图先遇到的那个端点"
					 * （`ends[0]`）开始走（见 getMmtrTopology 里的 `reversed`），而不是从 `railMath` 的弧零点
					 * —— 两者对同一根轨可以差一个整长（那次就是这一条）。</p>
					 *
					 * <p>所以这里：位置靠**投影**（车头一定在轨上，实测距离 0.00 格），再按拓扑那同一个判据
					 * 把弧**翻到折线方向上**；行进方向同样在这套空间里比大小。</p>
					 */
					final org.mtr.core.data.Rail headRail = walker.currentRail();
					final String headHex = headRail == null ? "" : org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(headRail.getHexId());
					final org.mtr.core.data.Rail graphRail = headHex.isEmpty() ? null : railIndex.railOr(headHex, headRail);
					final double headRailLength = graphRail == null ? walker.currentRailLengthM() : graphRail.railMath.getLength();
					// 与拓扑发 path 时同一个判据：折线是从 ends[0] 开始走的，那个端点在弧空间里不是 0 就说明整条倒着走
					final org.mtr.core.data.Position pathStartNode = headHex.isEmpty() ? null : railIndex.firstEnd(headHex);
					final double arcOfPathStart = graphRail == null || pathStartNode == null ? Double.NaN : graphRail.mmtrArcOfEndNode(pathStartNode);
					final boolean pathFlipped = !Double.isNaN(arcOfPathStart) && arcOfPathStart > 0;
					final double[] headXZ = mmtrHeadXZ(vehicle);
					final double headArcInRailMath = graphRail == null || headXZ == null ? Double.NaN : mmtrArcOfPoint(graphRail, headXZ[0], headXZ[1]);
					final org.mtr.core.data.Position aheadNode = walker.aheadNode();
					final double aheadArcInRailMath = graphRail == null || aheadNode == null ? Double.NaN : mmtrArcOfPoint(graphRail, aheadNode.getX(), aheadNode.getZ());
					final double headArcPath = pathFlipped ? headRailLength - headArcInRailMath : headArcInRailMath;
					final double aheadArcPath = pathFlipped ? headRailLength - aheadArcInRailMath : aheadArcInRailMath;
					// 认不出车头（离轨太远）就退回 walker 自己报的偏移；方向认不出时按"弧增"画
					final double railArcM = Double.isNaN(headArcPath) ? walker.offsetM() : headArcPath;
					final boolean arcIncreasing = Double.isNaN(aheadArcPath) || Double.isNaN(headArcPath) || aheadArcPath >= headArcPath;
					train.addProperty("railHex", headHex);
					train.addProperty("railArcLengthM", Math.round(headRailLength * 100.0) / 100.0);
					train.addProperty("railArcM", Math.round(railArcM * 100.0) / 100.0);
					train.addProperty("arcIncreasing", arcIncreasing);
					/*
					 * **每节车**（用户 2026-09-16："列车箭头以圆角箭头画，无动力车厢用矩形，货车用中空四边形"）。
					 *
					 * <p>要按节画就得知道每节车自己在**哪根轨、哪个弧**上 —— 编组体已经算好了：
					 * `MmtrConsistBody.carCenterArcM(i)` 是"沿**主轴**（一串轨腿）从 A 端量的弧"，
					 * 配 `legAtArcM` / `legOffsetM` 就能还原成"哪条腿 + 腿内偏移"，再按与车头**同一套判据**
					 * 翻到折线空间（这条腿的入口节点是不是那根轨的折线首端）。
					 * 长度与动力取自车自己的清单（`VehicleCar`），顺序与编组体一致（都是从 A 端数）。</p>
					 *
					 * <p>`forward`：这节车的"车头方向"是不是指向弧增方向（画箭头用）。
					 * 编组体永远从 A 端往 B 端排腿，而车可能朝 A 端开 —— 所以要看 `travelsTowardB`。</p>
					 */
					train.add("cars", mmtrCarJson(vehicle, walker, railIndex));
					if (nextRailHex != null) {
						final it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.mmtr.signal.MmtrSectionService.Section> sections =
							simulator.mmtrSections.sectionsOfRail(nextRailHex);
						/*
						 * **同一根轨属于两个方向的区间** —— 报出来的必须是"本车这个方向"的那一个
						 * （notes/155 §10 的读数陷阱）。
						 *
						 * 原来直接取 {@code sections.get(0)}：一列车明明在向前跑，运营台上却可能显示
						 * 反向那个区间（在这一版世界里，反向区间是 562 m 长的巨块），于是"六台车的下一区间
						 * 是同一个"这种结论**根本是读数造成的**。判据用区间自己记的走向
						 * （{@code RailSpan#matchesHeading}），与授权链同口径。
						 */
						final org.mtr.core.mmtr.signal.MmtrSectionService.Section section =
							sectionMatchingTravel(sections, walker, nextRailHex);
						if (section != null) {
							train.addProperty("nextSection", section.id);
							// 排除本车之后还占着吗 —— 这一问才是"前方真有车/邻车"的证据
							train.addProperty("nextSectionOccupiedByOthers",
								simulator.mmtrSections.isOccupied(section, null, vehicle.getId()));
							train.addProperty("nextSectionOccupiedAtAll",
								simulator.mmtrSections.isOccupied(section, null, 0));
							final com.google.gson.JsonArray occupants = new com.google.gson.JsonArray();
							for (final long occupant : simulator.mmtrSections.occupantsOf(section, null, 0)) {
								occupants.add(String.valueOf(occupant));
							}
							train.add("nextSectionOccupants", occupants);
						} else {
							train.addProperty("nextSection", "");
						}
					}
				}
				train.addProperty("manualOverride", vehicle.isMmtrManualOverride());
				train.addProperty("motionAuto", vehicle.isMmtrMotionAuto());
				train.addProperty("stoppedAtTarget", vehicle.isMmtrMotionStoppedAtTarget());
				train.addProperty("awsWarning", vehicle.isMmtrAwsWarningPending());
				train.addProperty("speedKmh", Math.round(vehicle.getSpeed() * 3600000.0) / 1000.0);
				train.addProperty("railProgressM", Math.round(vehicle.getRailProgress() * 100.0) / 100.0);
				train.addProperty("doorsOpen", vehicle.vehicleExtraData.getDoorMultiplier() > 0);
				// B7.7: which cab is manned (B-series consist body) - the ops console shows it.
				final org.mtr.core.mmtr.consist.MmtrConsistWalker consistWalker = vehicle.getMmtrConsistWalker();
				if (consistWalker != null) {
					train.addProperty("activeCab", consistWalker.cabs().activeCab().name());
					train.addProperty("cabManned", consistWalker.cabs().isManned());
					// 钥匙归属: "SYSTEM" is the engine's placeholder key on a staged consist (nobody
					// may drive from it), "CREW" is a player's key - with the holder's uuid.
					train.addProperty("cabKeyHolder", consistWalker.cabs().keyHolder().name());
					train.addProperty("cabCrew", consistWalker.cabs().crewUuid() == null ? "" : consistWalker.cabs().crewUuid().toString());
					// B7.6: a consist body has no legacy head position - report the leading face, which
					// is what the map draws the train marker at (and what the driver is looking along).
					final org.mtr.core.mmtr.MmtrMotionSnapshot consistSnapshot = org.mtr.core.mmtr.MmtrMotionSnapshot.ofConsistWalker(consistWalker);
					train.addProperty("headX", Math.round(consistSnapshot.frontX * 100.0) / 100.0);
					train.addProperty("headZ", Math.round(consistSnapshot.frontZ * 100.0) / 100.0);
				} else {
					final Vehicle.PositionAndTiltAngle head = vehicle.getHeadPositionAndTiltAngle();
					if (head != null) {
						train.addProperty("headX", Math.round(head.position().x() * 100.0) / 100.0);
						train.addProperty("headZ", Math.round(head.position().z() * 100.0) / 100.0);
					}
				}
				// C3a: the subsidiary-aspect authority a train holds (main head stays red) - the ops
				// console shows which movement is authorised to enter an occupied section.
				final org.mtr.core.mmtr.signal.MmtrShuntAuthority shuntAuthority = vehicle.getMmtrShuntAuthority();
				if (shuntAuthority != null) {
					train.addProperty("shuntAuthority", shuntAuthority.getKind().name());
					train.addProperty("shuntTargetRail", shuntAuthority.getTargetRailHex());
					train.addProperty("shuntSpeedLimitKmh", shuntAuthority.getSpeedLimitKmh());
					train.addProperty("shuntRemainingS", Math.round(shuntAuthority.remainingMillis(simulator.getCurrentMillis()) / 1000.0));
				}
				// S5: the live 进路 of this train (rails + turnouts + SET/PENDING) - the ops console
				// shows which movement the interlocking has actually set, not just its target rail.
				final org.mtr.core.mmtr.route.MmtrRoute route = vehicle.getMmtrRoute();
				if (route != null) {
					train.add("route", mmtrRouteJson(route));
				}
				// T2: 行车许可 —— 把信号显示翻译成"能走到哪、到那儿该多快"。本片只算不停
				// （停车规则是 T3），出口放在这里是为了让运营台能看见"灯 → 许可"的对应关系。
				final org.mtr.core.mmtr.segment.MmtrMotionPosition authorityWalker = vehicle.getMmtrMotionWalker();
				if (authorityWalker != null) {
					train.add("authority", mmtrAuthorityJson(
						org.mtr.core.mmtr.signal.MmtrMovementAuthority.forVehicle(simulator, authorityWalker, vehicle.getId(), route)));
				}
				final MmtrMission mission = vehicle.getMmtrMission();
				if (mission != null) {
					final com.google.gson.JsonObject missionJson = new com.google.gson.JsonObject();
					missionJson.addProperty("kind", mission.getKind().name());
					missionJson.addProperty("state", mission.getState().name());
					missionJson.addProperty("executor", mission.getExecutor().name());
					missionJson.addProperty("startSidingId", String.valueOf(mission.getStartSidingId()));
					missionJson.addProperty("targetSidingId", String.valueOf(mission.getTargetSidingId()));
					missionJson.addProperty("assignedMillis", mission.getAssignedMillis());
					if (mission.getFailureReason() != null) {
						missionJson.addProperty("failureReason", mission.getFailureReason());
					}
					train.add("mission", missionJson);
				}
				trains.add(train);
			});
			final com.google.gson.JsonObject sidingJson = new com.google.gson.JsonObject();
			sidingJson.addProperty("sidingId", String.valueOf(siding.getId()));
			sidingJson.addProperty("sidingName", siding.getName());
			sidingJson.addProperty("depotName", siding.getDepotName());
			sidingJson.addProperty("manual", siding.getIsManual());
			sidingJson.addProperty("vehiclesTotal", vehicleCount[0]);
			sidingJson.addProperty("vehiclesParked", parkedCount[0]);
			sidings.add(sidingJson);
		});
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.addProperty("currentTime", currentMillis);
		root.add("trains", trains);
		root.add("sidings", sidings);
		// Signal display layer: every rail's block aspect (RED when a train occupies the rail, the
		// yellow chain behind it from the occupancy chain ahead) - the web console colours the
		// track exactly like the in-game signal lights protecting each rail.
		root.add("signals", getMmtrRailAspects(simulator));
		// S5: every live route (SET and PENDING), so the console can show the interlocking state
		// independently of the train markers (and name what a waiting movement is blocked on).
		root.add("routes", getMmtrRoutes(simulator));
		// T5 第一块：敌对进路表（"什么算冲突"）—— **只报不改**，供运营台与诊断看见
		// "这两条进路为什么不能同时成立"。放进 SET 判据是下一步的事。
		final com.google.gson.JsonArray conflicts = new com.google.gson.JsonArray();
		org.mtr.core.mmtr.route.MmtrEnemyRoutes.conflicts(simulator).forEach(conflict -> {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("vehicleA", String.valueOf(conflict.vehicleA));
			out.addProperty("vehicleB", String.valueOf(conflict.vehicleB));
			out.addProperty("kind", conflict.kind);
			out.addProperty("bothSet", conflict.bothSet);
			out.addProperty("detail", conflict.detail);
			conflicts.add(out);
		});
		root.add("conflicts", conflicts);
		// A2/A4: the exact derived view the CLIENT mirror is built from (MmtrRouteRegistry →
		// PacketMmtrRoutes → MmtrClientRoutes → RenderSignalBase). Exposed so an operator can compare
		// what the game shows with what the engine told the clients - and so the narrowing is
		// observable server-side.
		root.add("routeMirror", mmtrRouteMirrorJson(simulator));
		root.add("points", new com.google.gson.JsonArray());
		return root;
	}

	/**
	 * **本车这个方向的那个区间**（notes/155 §10）：同一根轨属于两个方向的区间，报"下一区间"时必须分方向。
	 *
	 * <p>判据是区间自己记的走向（{@code RailSpan#matchesHeading}），与授权链（{@code sectionAt} /
	 * {@code sectionBoundaryAheadM}）同一口径：先按本车的行进方向（从入口节点指向前方节点）取；
	 * 取不到（例如两节点的连线退化成一点）再退回"这根轨上的第一个区间"，至少不空。</p>
	 */
	private static org.mtr.core.mmtr.signal.MmtrSectionService.@Nullable Section sectionMatchingTravel(
		it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.mmtr.signal.MmtrSectionService.Section> sections,
		org.mtr.core.mmtr.segment.MmtrMotionPosition walker, String railHex) {
		if (sections.isEmpty()) {
			return null;
		}
		final org.mtr.core.data.Position from = walker.enteredFromPosition();
		final org.mtr.core.data.Position to = walker.aheadNode();
		if (from != null && to != null) {
			final double dx = to.getX() - from.getX();
			final double dz = to.getZ() - from.getZ();
			final double norm = Math.sqrt(dx * dx + dz * dz);
			if (norm > 1e-6) {
				final double headingX = dx / norm;
				final double headingZ = dz / norm;
				for (final org.mtr.core.mmtr.signal.MmtrSectionService.Section section : sections) {
					for (final org.mtr.core.mmtr.signal.MmtrSectionService.RailSpan span : section.spans) {
						if (span.railHex.equals(railHex) && span.matchesHeading(headingX, headingZ)) {
							return section;
						}
					}
				}
			}
		}
		return sections.get(0);
	}

	/** The rail→next-rail narrowing map and the PENDING entry rails, exactly as mirrored to clients. */
	static com.google.gson.JsonObject mmtrRouteMirrorJson(Simulator simulator) {
		final com.google.gson.JsonObject json = new com.google.gson.JsonObject();
		final com.google.gson.JsonObject nextRails = new com.google.gson.JsonObject();
		simulator.mmtrRoutes.setMainRouteNextRails().forEach((from, nexts) -> {
			final com.google.gson.JsonArray list = new com.google.gson.JsonArray();
			nexts.forEach(list::add);
			nextRails.add(from, list);
		});
		final com.google.gson.JsonArray pendingEntries = new com.google.gson.JsonArray();
		simulator.mmtrRoutes.pendingEntryRails().forEach(pendingEntries::add);
		json.add("nextRails", nextRails);
		json.add("pendingEntries", pendingEntries);
		return json;
	}

	/** One route as the ops feed / a train card shows it. */
	private static com.google.gson.JsonObject mmtrRouteJson(org.mtr.core.mmtr.route.MmtrRoute route) {
		final com.google.gson.JsonObject json = new com.google.gson.JsonObject();
		json.addProperty("vehicleId", String.valueOf(route.getVehicleId()));
		json.addProperty("kind", route.getKind().name());
		json.addProperty("state", route.isEstablished() ? "SET" : "PENDING");
		json.addProperty("entryRail", route.getEntryRailHex() == null ? "" : route.getEntryRailHex());
		json.addProperty("targetRail", route.getTargetRailHex());
		json.addProperty("railCount", route.getRailHexes().size());
		json.addProperty("forkCount", route.getForks().size());
		json.addProperty("requestedMillis", route.getRequestedMillis());
		// T5：**计划时刻**（-1 = 无计划）。与 requestedMillis（实际申请的钟点）并排给出来，
		// 运营台的"计划 vs 实际"就有得比了。
		json.addProperty("plannedMillis", route.getPlannedMillis() == Long.MAX_VALUE ? -1L : route.getPlannedMillis());
		json.addProperty("planStale", route.getPlannedMillis() != Long.MAX_VALUE && route.getPlannedMillis() < route.getRequestedMillis());
		if (!route.isEstablished()) {
			json.addProperty("stateReason", route.getStateReason());
		}
		final com.google.gson.JsonArray rails = new com.google.gson.JsonArray();
		route.getRailHexes().forEach(rails::add);
		json.add("rails", rails);
		return json;
	}

	/** T2: one train's 行车许可 as the ops feed shows it (only present when it has a target). */
	private static com.google.gson.JsonObject mmtrAuthorityJson(org.mtr.core.mmtr.signal.MmtrMovementAuthority authority) {
		final com.google.gson.JsonObject json = new com.google.gson.JsonObject();
		json.addProperty("aspect", authority.aspect.name());
		json.addProperty("cautionOnly", authority.cautionOnly);
		json.addProperty("mustStop", authority.mustStop());
		if (authority.hasTarget()) {
			json.addProperty("targetRail", authority.targetRailHex);
			json.addProperty("targetDistanceM", Math.round(authority.targetDistanceM * 10) / 10.0);
			json.addProperty("targetSpeedKmh", Math.round(authority.targetSpeedKmh));
		}
		json.addProperty("reason", authority.reason);
		return json;
	}

	/** Every live route, ordered by vehicle id (deterministic feed). */
	private static com.google.gson.JsonArray getMmtrRoutes(Simulator simulator) {
		final com.google.gson.JsonArray out = new com.google.gson.JsonArray();
		simulator.mmtrRoutes.snapshot().forEach(route -> out.add(mmtrRouteJson(route)));
		return out;
	}

	/**
	 * MMTR rail signal aspects for the web console: one entry per rail with its display aspect.
	 * A2: the rule (闭塞链 × 进路) lives in {@link org.mtr.core.mmtr.signal.MmtrSignalAspect} and is
	 * shared with the in-game renderer; this method only shapes it for the feed. "Pre-approach"
	 * reservations are not occupancy.
	 */
	private static com.google.gson.JsonArray getMmtrRailAspects(Simulator simulator) {
		final com.google.gson.JsonArray signals = new com.google.gson.JsonArray();
		computeRailAspectMap(simulator).forEach((hex, aspect) -> {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("hex", hex);
			out.addProperty("aspect", aspect);
			signals.add(out);
		});
		return signals;
	}

	/** hex -> display aspect for every rail (shared by the rail feed and the signal registry feed). */
	private static java.util.HashMap<String, String> computeRailAspectMap(Simulator simulator) {
		final java.util.HashMap<String, String> aspects = new java.util.HashMap<>();
		/*
		 * **用 `Simulator` 缓存的那一份视图，不要在这里 new 一个**（notes/172 实测）。
		 *
		 * <p>`Simulator.mmtrSignalAspectView()` 是"进路 × 闭塞"的缓存视图（轨集签名一变才重建，
		 * 每 tick 由 `mmtrRefreshSignalAspectView()` 维护），**车辆自己问信号显示用的就是它**。
		 * 这里原来每请求 `new MmtrSignalAspect(simulator, simulator.mmtrRoutes)` —— 于是：
		 * ①把缓存整个丢掉、每次请求从零重建（实测 `mmtr-trains` 在**一辆车都没有**的世界里要 147 ms，
		 * 而它的世界级部分主要就是这一下）；②网页与游戏各算一套，两边可以不一致 ——
		 * 而 notes/79 立的规矩正是"信号 = 进路 × 闭塞，**单一真源**"。</p>
		 *
		 * <p>线程：本方法只在模拟线程上被调用（快照的构建就在那里），与该视图的维护者同线程。</p>
		 */
		simulator.mmtrSignalAspectView().aspectsForAllRails()
			.forEach((hex, aspect) -> aspects.put(hex, aspect.name()));
		return aspects;
	}

	/**
	 * 一段弧窗上的**采样点**（网页直接画那一段，不必自己实现 MTR 的轨道数学）。
	 *
	 * <p>区间可以落在轨的**一段**上（灯把轨切开），所以点必须按弧窗取，不能整根轨画。</p>
	 */
	private static com.google.gson.JsonArray pointsJson(Simulator simulator, String railHex, double fromM, double toM) {
		final com.google.gson.JsonArray points = new com.google.gson.JsonArray();
		if (toM <= fromM) {
			return points;
		}
		final org.mtr.core.data.Rail rail = simulator.rails.stream().filter(candidate -> candidate.getHexId().equals(railHex)).findFirst().orElse(null);
		if (rail == null) {
			return points;
		}
		final int steps = 8;
		for (int i = 0; i <= steps; i++) {
			final org.mtr.core.tool.Vector point = rail.railMath.getPosition(fromM + (toM - fromM) * i / steps, false);
			points.add(Math.round(point.x() * 100) / 100.0);
			points.add(Math.round(point.z() * 100) / 100.0);
		}
		return points;
	}

	/**
	 * **Level 1 轨道区间**（`/mmtr-track-sections`）：切点只由灯产生、**无方向**、双向共用 ——
	 * **占用判定就在这一层**（一根轨就是一根轨）。
	 *
	 * <p>每条：{@code id / length / occupied / spans[{hex,from,to,points[]}]}。
	 * 顶层还给 {@code count / busyCount / railCount}，一眼看出规模与忙闲。</p>
	 */
	static JsonObject getMmtrTrackSections(org.mtr.core.simulation.Simulator simulator) {
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, org.mtr.core.data.VehiclePosition>>> trees = simulator.mmtrOccupancyTrees();
		final com.google.gson.JsonArray sections = new com.google.gson.JsonArray();
		int busy = 0;
		for (final org.mtr.core.mmtr.signal.MmtrSectionService.TrackSection track : simulator.mmtrSections.allTrackSections()) {
			final boolean occupied = simulator.mmtrSections.isOccupied(track, trees, 0);
			if (occupied) {
				busy++;
			}
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("id", track.id);
			out.addProperty("length", track.lengthM());
			out.addProperty("occupied", occupied);
			final com.google.gson.JsonArray spans = new com.google.gson.JsonArray();
			for (final org.mtr.core.mmtr.signal.MmtrSectionService.TrackSpan span : track.spans) {
				final com.google.gson.JsonObject s = new com.google.gson.JsonObject();
				s.addProperty("hex", span.railHex);
				s.addProperty("from", span.arcFromM);
				s.addProperty("to", span.arcToM);
				s.add("points", pointsJson(simulator, span.railHex, span.arcFromM, span.arcToM));
				spans.add(s);
			}
			out.add("spans", spans);
			sections.add(out);
		}
		final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
		result.add("trackSections", sections);
		result.addProperty("count", simulator.mmtrSections.trackSectionCount());
		result.addProperty("busyCount", busy);
		result.addProperty("railCount", simulator.rails.size());
		return result;
	}

	/**
	 * **Level 2 行车区间**（`/mmtr-block-sections`）：有方向、灯到灯、跨轨；无灯连通块整块一段 ——
	 * **授权（显示与停车）的单位**。
	 *
	 * <p>每条：{@code id / entrySignal / exitSignal / next / aspect / occupied / length / direction /
	 * uncovered（是不是补出来的无灯大区间）/ members[](它由哪些 L1 段拼成) / spans[{hex,from,to,dirOfTravel,points[]}]}。</p>
	 */
	static JsonObject getMmtrBlockSections(org.mtr.core.simulation.Simulator simulator) {
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, org.mtr.core.data.VehiclePosition>>> trees = simulator.mmtrOccupancyTrees();
		final it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<String> restricted = org.mtr.core.mmtr.signal.MmtrJunctionState.unclearedNodeKeys(simulator, trees);
		final com.google.gson.JsonArray sections = new com.google.gson.JsonArray();
		int busy = 0;
		for (final org.mtr.core.mmtr.signal.MmtrSectionService.SectionView view : simulator.mmtrSections.sectionViews(trees, restricted::contains)) {
			if (view.occupied) {
				busy++;
			}
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("id", view.id);
			out.addProperty("entrySignal", view.entrySignalKey);
			out.addProperty("exitSignal", view.exitSignalKey);
			out.addProperty("next", view.nextSectionId);
			out.addProperty("aspect", view.aspect);
			out.addProperty("occupied", view.occupied);
			out.addProperty("length", view.lengthM);
			// 补出来的无灯大区间（没有入口灯）：网页该画成"无信号区段"，不能当"绿灯"画
			out.addProperty("uncovered", view.entrySignalKey == null || view.entrySignalKey.isEmpty());
			final com.google.gson.JsonObject direction = new com.google.gson.JsonObject();
			direction.addProperty("angle", view.direction.angle);
			direction.addProperty("label", view.direction.label());
			direction.addProperty("dx", view.direction.dx);
			direction.addProperty("dz", view.direction.dz);
			out.add("direction", direction);
			final com.google.gson.JsonArray members = new com.google.gson.JsonArray();
			view.memberTrackSectionIds.forEach(members::add);
			out.add("members", members);
			out.addProperty("memberCount", view.memberTrackSectionIds.size());
			final com.google.gson.JsonArray spans = new com.google.gson.JsonArray();
			for (final org.mtr.core.mmtr.signal.MmtrSectionService.RailSpan span : view.spans) {
				final com.google.gson.JsonObject s = new com.google.gson.JsonObject();
				s.addProperty("hex", span.railHex);
				s.addProperty("from", span.arcFromM);
				s.addProperty("to", span.arcToM);
				s.addProperty("dirOfTravel", span.matchesHeading(view.direction.dx, view.direction.dz));
				// 走不到的腿（道岔当前位切掉的那一侧）：守着但不判断路，网页该标出来
				s.addProperty("reachable", span.reachable);
				s.add("points", pointsJson(simulator, span.railHex, span.arcFromM, span.arcToM));
				spans.add(s);
			}
			out.add("spans", spans);
			sections.add(out);
		}
		final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
		result.add("blockSections", sections);
		result.addProperty("count", sections.size());
		result.addProperty("busyCount", busy);
		result.addProperty("lampCount", simulator.mmtrSignals.signals.size());
		return result;
	}

	/**
	 * **总区间**（`/mmtr-total-sections`）：地图上**每个位置只画一条带** —— 几何就是 L1 段，
	 * 每条再带上"覆盖它的各方向行车区间"（含显示与占用）。
	 *
	 * <p>缘起（用户 2026-09-15）：「以错开的一段轨道区间为例，一辆车在其中间，代表着这辆车**既在上行
	 * 区间中，也在下行区间中**，那么这时候就需要引出下一层了，叫做**总区间**，用于显示在地图上。」
	 * 它不是第三层划分：两个方向的行车区间都是 L1 段的并，按"覆盖配对"分组的最大连段**恰好就是 L1 段
	 * 本身**（相邻 L1 段之间必有灯，任一方向的灯都会换掉本方向的区间）。所以这里给的是
	 * **L1 的几何 ＋ 各方向的归属**，不是又一套几何 —— 与 `/mmtr-track-sections` 同一批 id。</p>
	 *
	 * <p>每条：{@code id / length / occupied / directions(覆盖它的方向数) / staggered(错开) /
	 * covers[{section,entrySignal,exitSignal,next,aspect,occupied,uncovered,length,direction}]
	 * / spans[{hex,from,to,points[]}]}；顶层 {@code count / busyCount / staggeredCount}。</p>
	 *
	 * <p>占用只有一份判据（L1 那一条），{@code covers[].occupied} 是"这条区间自己有没有被压住"：
	 * 错开处车压在同一根轨上，**两个方向的区间都会报 true** —— 于是"既在上行、也在下行"在数据里
	 * 是可查的，而地图只画一条带。</p>
	 */
	static JsonObject getMmtrTotalSections(org.mtr.core.simulation.Simulator simulator) {
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, org.mtr.core.data.VehiclePosition>>> trees = simulator.mmtrOccupancyTrees();
		final it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<String> restricted = org.mtr.core.mmtr.signal.MmtrJunctionState.unclearedNodeKeys(simulator, trees);
		final com.google.gson.JsonArray sections = new com.google.gson.JsonArray();
		int busy = 0;
		int staggered = 0;
		for (final org.mtr.core.mmtr.signal.MmtrSectionService.TotalView total : simulator.mmtrSections.totalSectionViews(trees, restricted::contains)) {
			if (total.occupied) {
				busy++;
			}
			if (total.staggered()) {
				staggered++;
			}
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("id", total.track.id);
			out.addProperty("length", total.lengthM());
			out.addProperty("occupied", total.occupied);
			out.addProperty("directions", total.directionCount());
			// 错开：上下行都照到这一处，但两个方向的区间不是同一段路（起止不重合）
			out.addProperty("staggered", total.staggered());
			final com.google.gson.JsonArray covers = new com.google.gson.JsonArray();
			for (final org.mtr.core.mmtr.signal.MmtrSectionService.SectionView cover : total.covers) {
				final com.google.gson.JsonObject c = new com.google.gson.JsonObject();
				c.addProperty("section", cover.id);
				c.addProperty("entrySignal", cover.entrySignalKey);
				c.addProperty("exitSignal", cover.exitSignalKey);
				c.addProperty("next", cover.nextSectionId);
				c.addProperty("aspect", cover.aspect);
				c.addProperty("occupied", cover.occupied);
				c.addProperty("uncovered", cover.entrySignalKey == null || cover.entrySignalKey.isEmpty());
				c.addProperty("length", cover.lengthM());
				final com.google.gson.JsonObject direction = new com.google.gson.JsonObject();
				direction.addProperty("angle", cover.direction.angle);
				direction.addProperty("label", cover.direction.label());
				direction.addProperty("dx", cover.direction.dx);
				direction.addProperty("dz", cover.direction.dz);
				c.add("direction", direction);
				covers.add(c);
			}
			out.add("covers", covers);
			final com.google.gson.JsonArray spans = new com.google.gson.JsonArray();
			for (final org.mtr.core.mmtr.signal.MmtrSectionService.TrackSpan span : total.track.spans) {
				final com.google.gson.JsonObject s = new com.google.gson.JsonObject();
				s.addProperty("hex", span.railHex);
				s.addProperty("from", span.arcFromM);
				s.addProperty("to", span.arcToM);
				s.add("points", pointsJson(simulator, span.railHex, span.arcFromM, span.arcToM));
				spans.add(s);
			}
			out.add("spans", spans);
			sections.add(out);
		}
		final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
		result.add("totalSections", sections);
		result.addProperty("count", sections.size());
		result.addProperty("busyCount", busy);
		result.addProperty("staggeredCount", staggered);
		return result;
	}

	/**
	 * **信号灯状态**（`/mmtr-lamps`）：每盏灯的**绑定** —— 它守的轨、**它开出的行车区间**、
	 * 这些段的占用、由段状态推出的显示、以及"未接入闭塞"。
	 *
	 * <p>这就是"把行车区间状态绑定至信号灯"的对外形态：网页只读这里，不自己算几何、也不去读轨。</p>
	 */
	static JsonObject getMmtrLamps(org.mtr.core.simulation.Simulator simulator) {
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, org.mtr.core.data.VehiclePosition>>> trees = simulator.mmtrOccupancyTrees();
		final it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<String> restricted = org.mtr.core.mmtr.signal.MmtrJunctionState.unclearedNodeKeys(simulator, trees);
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, org.mtr.core.mmtr.signal.MmtrSectionService.LampBinding> bindings =
			simulator.mmtrSections.lampBindings(trees, restricted::contains);
		final com.google.gson.JsonArray lamps = new com.google.gson.JsonArray();
		final java.util.TreeMap<String, org.mtr.core.mmtr.signal.MmtrSectionService.LampBinding> sorted = new java.util.TreeMap<>(bindings);
		sorted.forEach((key, binding) -> {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("key", key);
			final org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry entry = simulator.mmtrSignals.signals.get(key);
			if (entry != null) {
				out.addProperty("x", entry.x);
				out.addProperty("y", entry.y);
				out.addProperty("z", entry.z);
				out.addProperty("angle", entry.angle);
				out.addProperty("aspects", entry.aspects);
				out.addProperty("mode", entry.mode);
				out.addProperty("target", entry.target);
				out.addProperty("boundExplicit", !entry.rails.isEmpty());
			}
			out.addProperty("aspect", binding.aspect);
			out.addProperty("occupied", binding.occupied);
			out.addProperty("unbound", binding.unbound);
			out.addProperty("section", binding.sections.isEmpty() ? "" : binding.sections.get(0).id);
			final com.google.gson.JsonArray sectionIds = new com.google.gson.JsonArray();
			binding.sections.forEach(section -> sectionIds.add(section.id));
			out.add("sections", sectionIds);
			final com.google.gson.JsonArray nextIds = new com.google.gson.JsonArray();
			binding.nextSectionIds(simulator.mmtrSections).forEach(nextIds::add);
			out.add("nextSections", nextIds);
			final com.google.gson.JsonArray rails = new com.google.gson.JsonArray();
			binding.protectedRails.forEach(rails::add);
			out.add("protectedRails", rails);
			lamps.add(out);
		});
		final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
		result.add("lamps", lamps);
		result.addProperty("count", lamps.size());
		return result;
	}

	/**
	 * 站台图层 feed: every platform with the station it belongs to, its number, its dwell time, the rail
	 * it lies on, its two ends and its axis.
	 *
	 * <p><b>What a platform IS (engine side)</b>: a rail marked as a platform. {@link org.mtr.core.data.Rail#checkOrCreateSavedRailAndUpdateTiltAngles}
	 * builds it from the rail's own two endpoints, and {@code SavedRailBase.mmtrGraphRail()} resolves it
	 * back to that rail — so "which rails are platform rails" is the engine's answer, never re-derived
	 * from geometry here (same rule as the points/section feeds).</p>
	 *
	 * <p>The console draws a station marker beside the platform and writes the station name + platform
	 * number along the platform, so it needs three things this feed publishes: the NAME
	 * (station + platform number), the AXIS (which way the platform runs, for the marker and the text
	 * rotation) and the EXTENT (the two ends; the console takes the curve from {@code /mmtr-topology} by
	 * {@code railHex} when it wants a curved line, and falls back to the straight ends when the rail is
	 * missing).</p>
	 *
	 * <p>Note the axis is a two-way axis, not a travel direction: a platform serves trains from either
	 * end, so the console normalises the sign before rotating text.</p>
	 */
	static JsonObject getMmtrPlatforms(org.mtr.core.simulation.Simulator simulator) {
		final com.google.gson.JsonArray platforms = new com.google.gson.JsonArray();
		simulator.platforms.forEach(platform -> {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("platformId", String.valueOf(platform.getId()));
			out.addProperty("platformHex", platform.getHexId());
			out.addProperty("platformName", platform.getName());
			final org.mtr.core.data.AreaBase<?, ?> station = platform.area;
			out.addProperty("stationId", station == null ? "" : String.valueOf(station.getId()));
			out.addProperty("stationHex", station == null ? "" : station.getHexId());
			out.addProperty("stationName", platform.getStationName());
			out.addProperty("dwellMillis", platform.getDwellTime());
			// 站台客量（0–100%）：落盘字段 + 经调制器后的有效值。
			// 游戏侧铺"村民"读的是 effectiveCrowdLevel，所以这里两个都报出来，网页/指令对账时不会看错一栏。
			out.addProperty("crowdLevel", platform.getCrowdLevel());
			out.addProperty("effectiveCrowdLevel", platform.getEffectiveCrowdLevel());
			final org.mtr.core.data.Rail rail = platform.mmtrGraphRail();
			// 规范 hex：与 /mmtr-topology 的 rails[].hex 同一写法，前端按它取那根轨的 path 才不会找不到。
			out.addProperty("railHex", rail == null ? "" : org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(rail.getHexId()));
			final org.mtr.core.data.Position[] ends = platform.mmtrOrderedPositions();
			out.addProperty("x1", ends[0].getX());
			out.addProperty("y1", ends[0].getY());
			out.addProperty("z1", ends[0].getZ());
			out.addProperty("x2", ends[1].getX());
			out.addProperty("y2", ends[1].getY());
			out.addProperty("z2", ends[1].getZ());
			// 站台轴：两端之间的弦方向（归一成单位向量），角度用 MTR 那套 (0 = 南/+z, 90 = 西/−x)。
			final double spanX = ends[1].getX() - ends[0].getX();
			final double spanZ = ends[1].getZ() - ends[0].getZ();
			final double length = Math.hypot(spanX, spanZ);
			final double dx = length == 0 ? 0 : spanX / length;
			final double dz = length == 0 ? 1 : spanZ / length;
			final com.google.gson.JsonObject direction = new com.google.gson.JsonObject();
			direction.addProperty("angle", org.mtr.core.mmtr.signal.MmtrSectionService.angleOfHeading(dx, dz));
			direction.addProperty("label", Math.abs(dz) >= Math.abs(dx) ? (dz > 0 ? "南行" : "北行") : (dx > 0 ? "东行" : "西行"));
			direction.addProperty("dx", dx);
			direction.addProperty("dz", dz);
			out.add("direction", direction);
			out.addProperty("lengthM", Math.round(Math.hypot(spanX, spanZ) * 10) / 10.0);
			platforms.add(out);
		});
		final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
		result.add("platforms", platforms);
		result.addProperty("count", platforms.size());
		return result;
	}

	/**
	 * **规范 hex → 轨 / 折线首端节点**的一次性索引（notes/172）。
	 *
	 * <p>为什么必须成索引：`mmtr-trains` 是地图页每一拍都在问的那一路，而它**每辆车**都要问两件事 ——
	 * "我脚下这根轨在图上是哪一条"（要与拓扑的 `path` 方向对齐，所以按**规范 hex** 认，不能按对象身份）
	 * 与"那条折线从哪个端点开始走"。两问原来都是全表线性扫 + 逐条算 `canonicalHex`，
	 * 于是这一路的成本是 O(车 × 轨)。实测（159 轨 / 6 车）：**构建一次 179 ms** —— 一次刷新吃掉三个多 tick。</p>
	 *
	 * <p>口径与逐次线性扫逐位一致：都按 `simulator.rails` / `positionsToRail` 的迭代顺序取**第一个**命中的
	 * （`putIfAbsent`）。首端节点的判据与 `getMmtrTopology` 收集 `railEnds` 时**同一个判据、同一个顺序**：
	 * 任何"沿折线量"的东西（车辆里程）都必须按这一端算，否则同一根轨会差一个整长
	 * （实测踩过：7 辆车里 2 辆被画到轨的另一头）。</p>
	 */
	private static final class MmtrRailIndex {

		private final Object2ObjectOpenHashMap<String, Rail> railByCanonicalHex = new Object2ObjectOpenHashMap<>();
		private final Object2ObjectOpenHashMap<String, Position> firstEndByCanonicalHex = new Object2ObjectOpenHashMap<>();

		private MmtrRailIndex(Simulator simulator) {
			simulator.rails.forEach(rail -> railByCanonicalHex.putIfAbsent(org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(rail.getHexId()), rail));
			simulator.positionsToRail.forEach((node, neighbourMap) -> neighbourMap.forEach((end, rail) -> firstEndByCanonicalHex.putIfAbsent(org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(rail.getHexId()), node)));
		}

		/** 图上那条轨；索引里没有就用调用方手上那一份（原来写的是 `.orElse(headRail)`）。 */
		private Rail railOr(String canonicalHex, Rail fallback) {
			final Rail indexed = railByCanonicalHex.get(canonicalHex);
			return indexed == null ? fallback : indexed;
		}

		private @Nullable Position firstEnd(String canonicalHex) {
			return firstEndByCanonicalHex.get(canonicalHex);
		}
	}

	/**
	 * **每节车**在图上画在哪：`[{index, railHex, railArcM, railArcLengthM, forward, lengthM, stockId, powered, capacity}]`。
	 *
	 * <p>几何来自编组体 {@code MmtrConsistBody}（车序从 A 端数、与 `VehicleCar` 清单同序）：
	 * 车中心的主轴弧 → `legAtArcM` 得到轨与端点 → `legOffsetM` 得到腿内偏移 →
	 * 按"这条腿的入口是不是那根轨的**折线首端**"翻到与车头同一套弧空间。
	 * 弧空间那一层必须翻：不翻的话，同一根轨会整整差一个轨长（notes/170 §2 的第三个坑）。</p>
	 *
	 * <p>`forward` = 这节车的车头方向是否指向弧增方向：编组体的腿永远从 A 端排到 B 端，
	 * 而车可能朝 A 端开（`travelsTowardB`）。前端拿它决定箭头朝哪边。</p>
	 */
	private static com.google.gson.JsonArray mmtrCarJson(Vehicle vehicle, org.mtr.core.mmtr.segment.MmtrMotionPosition walker, MmtrRailIndex railIndex) {
		final com.google.gson.JsonArray cars = new com.google.gson.JsonArray();
		final org.mtr.core.mmtr.consist.MmtrConsistWalker consistWalker = vehicle.getMmtrConsistWalker();
		if (consistWalker == null) {
			return cars;
		}
		final org.mtr.core.mmtr.consist.MmtrConsistBody body = consistWalker.body();
		// 车自己的清单（`VehicleCar` + 转向架位置），顺序与编组体一致：都从 A 端数
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<org.mtr.core.data.VehicleCar, it.unimi.dsi.fastutil.objects.ObjectArrayList<Vehicle.BogiePosition>>> vehicleCars = vehicle.getVehicleCarsAndPositions();
		final boolean towardB = consistWalker.travelsTowardB();
		for (int i = 0; i < body.carCount(); i++) {
			final double centerArc = body.carCenterArcM(i);
			final org.mtr.core.mmtr.consist.MmtrConsistBody.SpineLeg leg = body.legAtArcM(centerArc);
			if (leg == null) {
				continue;
			}
			final double offsetInLeg = body.legOffsetM(centerArc);
			final String legHex = org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(leg.railHex());
			final org.mtr.core.data.Position firstEnd = railIndex.firstEnd(legHex);
			final boolean legForward = firstEnd != null && firstEnd.equals(leg.entryNode());
			final double legLength = leg.lengthM();
			final com.google.gson.JsonObject car = new com.google.gson.JsonObject();
			car.addProperty("index", i);
			car.addProperty("railHex", legHex);
			car.addProperty("railArcM", Math.round((legForward ? offsetInLeg : legLength - offsetInLeg) * 100.0) / 100.0);
			car.addProperty("railArcLengthM", Math.round(legLength * 100.0) / 100.0);
			car.addProperty("forward", towardB == legForward);
			car.addProperty("lengthM", Math.round(body.carLengthM(i) * 100.0) / 100.0);
			if (i < vehicleCars.size()) {
				final org.mtr.core.data.VehicleCar vehicleCar = vehicleCars.get(i).left();
				car.addProperty("stockId", vehicleCar.getVehicleId());
				car.addProperty("powered", vehicleCar.getMmtrPowered());
				car.addProperty("capacity", vehicleCar.getCapacity());
			}
			cars.add(car);
		}
		return cars;
	}

	/**
	 * 车头（前脸）的世界坐标 x/z：consist 用车体的前脸，老式车用 `getHeadPositionAndTiltAngle()`。
	 * 认不出来返回 null。与 `mmtr-trains` 里 `headX/headZ` 两个字段同一份算法（那边是发给网页看的）。
	 */
	private static double[] mmtrHeadXZ(Vehicle vehicle) {
		final org.mtr.core.mmtr.consist.MmtrConsistWalker consistWalker = vehicle.getMmtrConsistWalker();
		if (consistWalker != null) {
			final org.mtr.core.mmtr.MmtrMotionSnapshot snapshot = org.mtr.core.mmtr.MmtrMotionSnapshot.ofConsistWalker(consistWalker);
			return new double[]{snapshot.frontX, snapshot.frontZ};
		}
		final Vehicle.PositionAndTiltAngle head = vehicle.getHeadPositionAndTiltAngle();
		return head == null ? null : new double[]{head.position().x(), head.position().z()};
	}

	/**
	 * 把一个世界点投到某根轨的**弧空间**上：返回最近处的弧长（米）；离轨超过 3 格就当认不出来（NaN）。
	 *
	 * <p>为什么不用"入口节点 + 偏移"算位置：`walker.currentRail()` 可能是方向被翻过的副本，而
	 * `enteredFromPosition()` 未必与轨的端点逐位相等 —— 两条都会让弧从另一端量起（实测 7 辆车错了 2 辆）。
	 * 车头本身一定落在轨上（实测到采样折线距离 0.00 格），投影是可靠的那条路。</p>
	 *
	 * <p>粗扫 64 段 + 在最近的一段内再细扫 41 点：`railMath.getPosition` 是两段圆弧的解析解，很便宜，
	 * 而一辆车一次请求只算两三回。</p>
	 */
	private static double mmtrArcOfPoint(org.mtr.core.data.Rail rail, double x, double z) {
		final double length = rail.railMath.getLength();
		if (length <= 0) {
			return Double.NaN;
		}
		final int steps = 64;
		double bestArc = 0;
		double bestDistance = Double.MAX_VALUE;
		for (int i = 0; i <= steps; i++) {
			final double arc = length * i / steps;
			final org.mtr.core.tool.Vector point = rail.railMath.getPosition(arc, false);
			final double distance = Math.hypot(point.x() - x, point.z() - z);
			if (distance < bestDistance) {
				bestDistance = distance;
				bestArc = arc;
			}
		}
		final double span = length / steps;
		for (int i = -20; i <= 20; i++) {
			final double arc = Math.max(0, Math.min(length, bestArc + span * i / 20.0));
			final org.mtr.core.tool.Vector point = rail.railMath.getPosition(arc, false);
			final double distance = Math.hypot(point.x() - x, point.z() - z);
			if (distance < bestDistance) {
				bestDistance = distance;
				bestArc = arc;
			}
		}
		return bestDistance > 3 ? Double.NaN : bestArc;
	}

	/**
	 * 闭塞区间 v2 feed (区间图层): every directional block section - the stretch ONE lamp protects,
	 * walked lamp to lamp - with its exit/next section, its aspect, whether it is occupied right now, and
	 * its spans {@code (rail, arcFrom, arcTo)}.
	 *
	 * <p>This is the layer the console could not show before: sections are what the engine actually
	 * divides the line into, and they cross rail ends (in the dev world one is 30 rails / 601 m), so a
	 * per-rail colouring can never make them visible. The arcs let the front end draw a PART of a rail
	 * when a lamp splits it mid-rail.</p>
	 */
	private static JsonObject getMmtrSections(Simulator simulator) {
		final com.google.gson.JsonArray sections = new com.google.gson.JsonArray();
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, org.mtr.core.data.VehiclePosition>>> trees = simulator.mmtrOccupancyTrees();
		final it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<String> restricted = org.mtr.core.mmtr.signal.MmtrJunctionState.unclearedNodeKeys(simulator, trees);
		for (final org.mtr.core.mmtr.signal.MmtrSectionService.SectionView view : simulator.mmtrSections.sectionViews(trees, restricted::contains)) {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("id", view.id);
			// 入口灯单列一份：区间 id 在"一灯多腿"时带 #n 后缀，前端不该拆字符串去还原它。
			out.addProperty("entrySignal", view.entrySignalKey);
			out.addProperty("exitSignal", view.exitSignalKey);
			out.addProperty("next", view.nextSectionId);
			out.addProperty("aspect", view.aspect);
			out.addProperty("occupied", view.occupied);
			out.addProperty("length", view.lengthM);
			/*
			 * **区间属于哪个行车方向**（本轮新增）。
			 *
			 * 双向线路上"同一根物理轨"属于两个方向的各一个区间，所以方向是区间的第一属性，不是可以从
			 * 几何猜出来的附属信息 —— 网页要按方向画成两条带（方案 B），没有这个字段就只能靠 id 猜。
			 */
			final com.google.gson.JsonObject direction = new com.google.gson.JsonObject();
			direction.addProperty("angle", view.direction.angle);
			direction.addProperty("label", view.direction.label());
			direction.addProperty("dx", view.direction.dx);
			direction.addProperty("dz", view.direction.dz);
			out.add("direction", direction);
			final com.google.gson.JsonArray spans = new com.google.gson.JsonArray();
			for (final org.mtr.core.mmtr.signal.MmtrSectionService.RailSpan span : view.spans) {
				final com.google.gson.JsonObject s = new com.google.gson.JsonObject();
				s.addProperty("hex", span.railHex);
				s.addProperty("from", span.arcFromM);
				s.addProperty("to", span.arcToM);
				// 本段是"沿弧增"还是"沿弧减"走过的：前端画方向箭头要用，且它与区间方向一致时才是正向段。
				s.addProperty("dirOfTravel", span.matchesHeading(view.direction.dx, view.direction.dz));
				// Sampled world points along the arc window, so the console can draw a slice WITHOUT
				// re-implementing MTR's two-arc rail maths: a lamp standing mid-rail gives a span shorter
				// than the rail, and where the slice starts is exactly what the layer has to show.
				final org.mtr.core.data.Rail rail = simulator.rails.stream().filter(candidate -> candidate.getHexId().equals(span.railHex)).findFirst().orElse(null);
				final com.google.gson.JsonArray points = new com.google.gson.JsonArray();
				if (rail != null && span.arcToM > span.arcFromM) {
					final int steps = 8;
					for (int i = 0; i <= steps; i++) {
						final double arc = span.arcFromM + (span.arcToM - span.arcFromM) * i / steps;
						final org.mtr.core.tool.Vector point = rail.railMath.getPosition(arc, false);
						points.add(Math.round(point.x() * 100) / 100.0);
						points.add(Math.round(point.z() * 100) / 100.0);
					}
				}
				s.add("points", points);
				spans.add(s);
			}
			out.add("spans", spans);
			sections.add(out);
		}
		final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
		result.add("sections", sections);
		result.addProperty("railCount", simulator.rails.size());
		/*
		 * **按轨索引的成员表：一个点属于哪几个区间**（本轮新增，方案 B 的地基）。
		 *
		 * <p>为什么必须有它：区间是"某方向的一段路"，所以**归属是多值的** —— 双向线路上同一根轨同时属于
		 * 南行和北行的各一个区间。现场实测（2026-09-15，140 轨 / 73 灯）：被区间覆盖的 96 根轨里
		 * **62 根属于 2 个以上区间、最多的一根属于 5 个**。所以旧的"一个节点/一个点 → 一个归属"
		 * 那种单值模型在双向线路上必然错，前端也不该拿着区间列表自己去求交。</p>
		 *
		 * <p>粒度 = "轨 hex + 弧窗"：一根多归属的轨会展开成几条记录（每段弧窗一条），前端直接画。</p>
		 */
		final java.util.LinkedHashMap<String, com.google.gson.JsonObject> memberByKey = new java.util.LinkedHashMap<>();
		final java.util.LinkedHashMap<String, java.util.List<com.google.gson.JsonObject>> membersByKey = new java.util.LinkedHashMap<>();
		for (final org.mtr.core.mmtr.signal.MmtrSectionService.SectionView view : simulator.mmtrSections.sectionViews(trees, restricted::contains)) {
			for (final org.mtr.core.mmtr.signal.MmtrSectionService.RailSpan span : view.spans) {
				if (span.lengthM() <= 1e-9) {
					continue;
				}
				final String key = span.railHex + "@" + Math.round(span.arcFromM * 100) / 100.0 + ".." + Math.round(span.arcToM * 100) / 100.0;
				if (!memberByKey.containsKey(key)) {
					final com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
					entry.addProperty("hex", span.railHex);
					entry.addProperty("from", span.arcFromM);
					entry.addProperty("to", span.arcToM);
					entry.add("members", new com.google.gson.JsonArray());
					memberByKey.put(key, entry);
					membersByKey.put(key, new java.util.ArrayList<>());
				}
				final com.google.gson.JsonObject member = new com.google.gson.JsonObject();
				member.addProperty("section", view.id);
				member.addProperty("angle", view.direction.angle);
				member.addProperty("label", view.direction.label());
				member.addProperty("aspect", view.aspect);
				member.addProperty("occupied", view.occupied);
				membersByKey.get(key).add(member);
			}
		}
		final com.google.gson.JsonArray byRail = new com.google.gson.JsonArray();
		for (final java.util.Map.Entry<String, com.google.gson.JsonObject> entry : memberByKey.entrySet()) {
			final java.util.List<com.google.gson.JsonObject> members = membersByKey.get(entry.getKey());
			// 同一个方向在同一段弧上只算一次（咽喉处区间会重叠，但"几个方向"要看方向数）
			final java.util.TreeSet<Double> angles = new java.util.TreeSet<>();
			final com.google.gson.JsonArray memberArray = (com.google.gson.JsonArray) entry.getValue().get("members");
			for (final com.google.gson.JsonObject member : members) {
				memberArray.add(member);
				angles.add(member.get("angle").getAsDouble());
			}
			entry.getValue().addProperty("memberCount", members.size());
			entry.getValue().addProperty("directionCount", angles.size());
			// 双向轨（两个方向都走）—— 方案 B 里要画两条带的就是它
			entry.getValue().addProperty("bidirectional", angles.size() >= 2);
			byRail.add(entry.getValue());
		}
		result.add("byRail", byRail);
		/*
		 * Level 1 轨道区间（notes/166）：切点只由灯产生、**无方向**、双向共用 —— **占用判定的单位**。
		 *
		 * <p>一并发出来，运营台才能在图上把两层分开画：L2（`sections`，有方向、灯到灯）是**授权**单位，
		 * L1 是**占用**单位。原来只发 L2，网页想画"占用"就只好去读 L2 的 occupied —— 双向线路上同一段
		 * 要取两次，而无灯区的占用（补出来的大区间）根本没有地方表达。</p>
		 */
		final com.google.gson.JsonArray trackSections = new com.google.gson.JsonArray();
		for (final org.mtr.core.mmtr.signal.MmtrSectionService.TrackSection track : simulator.mmtrSections.allTrackSections()) {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("id", track.id);
			out.addProperty("length", track.lengthM());
			out.addProperty("occupied", simulator.mmtrSections.isOccupied(track, trees, 0));
			final com.google.gson.JsonArray trackSpans = new com.google.gson.JsonArray();
			for (final org.mtr.core.mmtr.signal.MmtrSectionService.TrackSpan span : track.spans) {
				final com.google.gson.JsonObject s = new com.google.gson.JsonObject();
				s.addProperty("hex", span.railHex);
				s.addProperty("from", span.arcFromM);
				s.addProperty("to", span.arcToM);
				trackSpans.add(s);
			}
			out.add("spans", trackSpans);
			trackSections.add(out);
		}
		result.add("trackSections", trackSections);
		result.addProperty("trackSectionCount", simulator.mmtrSections.trackSectionCount());
		/*
		 * 区间图层 = **按方向划分的区间**（本函数上半部分已经发完：`sections` + `byRail`）。
		 *
		 * <p>这里原来还有两段：`blocks`（水闸区间）与 `nodes`（**每个节点唯一归属的那个区间**）。
		 * 2026-09-15 按用户裁定删除：区间**只能**由灯划分（节点永不切分），而"一个节点属于一个区间"在
		 * 双向线路上必然错——现场实测被区间覆盖的 96 根轨里 62 根属于 2 个以上区间（最多 5 个）。
		 * 网页要的"一个点属于哪几个区间"由上面的 `byRail` 回答，那是**多值**的。</p>
		 */
		return result;
	}

	/**
	 * 区间图 feed (格对齐的拓扑区间图): the block layer folded onto a 1x1 lattice, ready to draw.
	 *
	 * <p>The console draws a DIAGRAM here, not the world: every track node snaps to a square of a fixed
	 * lattice (16 m per square), rails between two squares become one line, and each line carries the block
	 * index of each travel direction. The engine owns the transform, so the operator UI cannot drift from the
	 * simulation - and the console needs no geometry maths of its own.</p>
	 */
	private static JsonObject getMmtrSchematic(org.mtr.core.simulation.Simulator simulator) {
		final org.mtr.core.mmtr.signal.MmtrBlockSchematic.Schematic schematic = org.mtr.core.mmtr.signal.MmtrBlockSchematic.build(simulator);
		final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
		result.addProperty("cellSize", schematic.cellSize);
		result.addProperty("cellM", schematic.cellM);
		result.addProperty("cellWidth", schematic.cellWidth);
		result.addProperty("cellHeight", schematic.cellHeight);
		result.addProperty("originCellX", schematic.originCellX);
		result.addProperty("originCellZ", schematic.originCellZ);
		result.addProperty("worldWidthM", schematic.worldWidthM);
		result.addProperty("worldHeightM", schematic.worldHeightM);

		final com.google.gson.JsonArray nodes = new com.google.gson.JsonArray();
		for (final org.mtr.core.mmtr.signal.MmtrBlockSchematic.DiagramNode node : schematic.nodes) {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("id", node.id);
			out.addProperty("cellX", node.cellX);
			out.addProperty("cellZ", node.cellZ);
			out.addProperty("x", node.x);
			out.addProperty("z", node.z);
			out.addProperty("merged", node.mergedCount);
			/*
			 * **这一格被哪些区间覆盖**（可能是多个：双向线路上同一段轨同属两个方向的区间）。
			 *
			 * <p>原来这里是单值的 `block`（"这一格归哪个水闸区间"）。那一层已按用户裁定删除：
			 * 区间是某方向的一段路，归属必然是多值的，所以这里必须是数组。空数组 = 没有灯管到这一格。</p>
			 */
			final com.google.gson.JsonArray cellSections = new com.google.gson.JsonArray();
			node.sections.forEach(cellSections::add);
			out.add("sections", cellSections);
			nodes.add(out);
		}
		result.add("nodes", nodes);

		final com.google.gson.JsonArray rails = new com.google.gson.JsonArray();
		for (final org.mtr.core.mmtr.signal.MmtrBlockSchematic.DiagramRail rail : schematic.rails) {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("from", rail.fromNode);
			out.addProperty("to", rail.toNode);
			out.addProperty("x1", rail.x1);
			out.addProperty("z1", rail.z1);
			out.addProperty("x2", rail.x2);
			out.addProperty("z2", rail.z2);
			out.addProperty("rails", rail.rails);
			out.addProperty("length", rail.lengthM);
			out.addProperty("forwardSection", rail.forwardSection);
			out.addProperty("backwardSection", rail.backwardSection);
			rails.add(out);
		}
		result.add("rails", rails);

		final com.google.gson.JsonArray sections = new com.google.gson.JsonArray();
		for (final org.mtr.core.mmtr.signal.MmtrBlockSchematic.DiagramSection section : schematic.sections) {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("index", section.index);
			out.addProperty("id", section.id);
			out.addProperty("entryLamp", section.entryLamp);
			out.addProperty("exitLamp", section.exitLamp);
			// 方向是区间的第一属性（0=南 90=西 180=北 270=东），网页靠它把两个方向画成两条带
			out.addProperty("directionAngle", section.directionAngle);
			out.addProperty("directionLabel", section.directionLabel);
			out.addProperty("occupied", section.occupied);
			out.addProperty("aspect", section.aspect);
			out.addProperty("length", section.lengthM);
			final com.google.gson.JsonArray edges = new com.google.gson.JsonArray();
			for (final int edge : section.railEdges) {
				edges.add(edge);
			}
			out.add("edges", edges);
			final com.google.gson.JsonArray squares = new com.google.gson.JsonArray();
			for (final int node : section.nodeIds) {
				squares.add(node);
			}
			out.add("squares", squares);
			final com.google.gson.JsonArray spans = new com.google.gson.JsonArray();
			section.spans.forEach(spans::add);
			out.add("spans", spans);
			sections.add(out);
		}
		result.add("sections", sections);
		return result;
	}

	/**
	 * Turnout console feed (道岔, P2 UI): every direction-aware (node, approach rail) fork with two
	 * or more ordered continuations, enriched with the operator manual branch, the authority state
	 * (locked / holder / queue) and the ordered legs each with its direction kind. Coordinates and
	 * via rail hex together key one point; leg indexes are the SAME indexes the walker/planner elect
	 * against (straight > left > right > other ordering).
	 */
	private static JsonObject getMmtrPoints(org.mtr.core.simulation.Simulator simulator) {
		final com.google.gson.JsonArray points = new com.google.gson.JsonArray();
		// 一个节点算一次"为什么不是道岔"（同一节点有好几行）
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, String> whyNotTurnoutByNode = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.mmtr.point.MmtrPoint> discovered = org.mtr.core.mmtr.point.MmtrPoint.discoverDirectionAware(simulator);
		for (final org.mtr.core.mmtr.point.MmtrPoint p : discovered) {
			if (p.legs.size() < 2) {
				continue; // pass-throughs / dead ends are not operator forks
			}
			final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("x", p.nodeX);
			o.addProperty("y", p.nodeY);
			o.addProperty("z", p.nodeZ);
			/*
			 * 轨 hex 一律发**规范写法**（两个端点表示里字典序小的那个，见 canonicalHex）。
			 *
			 * <p>拓扑接口（网页画轨用的那份）早就这么做，理由是"同一条实体轨有两个互为逆序的 hex，
			 * 取决于这条 Rail 怎么被声明"。道岔接口这里原来是原始写法，于是同一个节点上
			 * **道岔说的轨名和地图上的轨名对不上**：实测 {@code -19,-60,51} 的两根东向轨，
			 * 道岔发 {@code FFFFFFFFFFFFFFED…}、地图上是 {@code 0000000000000001…}，
			 * 网页按 hex 比对就永远不相等 ⇒ "点亮当前开通那条腿"整条功能静默失效，
			 * 卡片里"接哪两条轨（坐标）"也退化成 hex 前缀（用户 2026-09-14 现场报的正是这个）。</p>
			 */
			o.addProperty("via", org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(p.viaRailHex));
			o.addProperty("form", p.form.name());
			/*
			 * 物理道岔：一处道岔一个位置、两条互斥进路 —— 位置与"哪条进路禁止通行"都直接给出来，
			 * 操作台才能不猜。用户 2026-09-13 的规格：位置 0 = 正线贯通（岔股禁行），
			 * 位置 1 = 岔股开放（正线被断开的那一侧禁行）。
			 */
			final org.mtr.core.mmtr.point.MmtrTurnout turnout = simulator.mmtrTurnout(p.nodeX, p.nodeY, p.nodeZ);
			if (turnout == null) {
				/*
				 * 不是单开道岔的节点：把**为什么不是**一并说清（一行字，与 point why 同一段判定代码）。
				 * 用户 2026-09-14 要求"道岔的呈现要统一" —— 这类节点不该换一套卡片形状让人猜，
				 * 而应在同一张卡片上说明原因（例如"四条线交汇 / 三条线在一个点上交汇"）。
				 */
				final String nodeKey = p.nodeX + "," + p.nodeY + "," + p.nodeZ;
				String why = whyNotTurnoutByNode.get(nodeKey);
				if (why == null) {
					final org.mtr.core.data.Position node = new org.mtr.core.data.Position(p.nodeX, p.nodeY, p.nodeZ);
					why = org.mtr.core.mmtr.point.MmtrTurnout.rejectionReason(node, simulator.positionsToRail.get(node));
					whyNotTurnoutByNode.put(nodeKey, why);
				}
				if (!why.isEmpty()) {
					o.addProperty("whyNotTurnout", why);
				}
			}
			final int turnoutPosition = turnout == null ? -1 : simulator.mmtrTurnoutPosition(p.nodeX, p.nodeY, p.nodeZ);
			final String prohibitedRailHex = turnout == null ? "" : turnout.prohibitedRailHex(turnoutPosition);
			if (turnout != null) {
				o.addProperty("position", turnoutPosition);
				// 与 via / legs 同一套规范写法：网页要拿这些 hex 去和地图上的轨比对（点亮当前开通那条腿）
				o.addProperty("prohibited", org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(prohibitedRailHex));
				o.addProperty("stem", org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(turnout.stemRailHex));
				// 三条轨都给出来：网页要能**独立于当前位置**说出"扳到 0 是接哪条、扳到 1 是接哪条"，
				// 只给"当前禁行的那一条"的话，位置一变操作台就得靠猜另一条是哪根。
				o.addProperty("far", org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(turnout.farRailHex));
				o.addProperty("branch", org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(turnout.branchRailHex));
			}
			final com.google.gson.JsonArray legs = new com.google.gson.JsonArray();
			for (final org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg leg : p.legs) {
				final com.google.gson.JsonObject legJson = new com.google.gson.JsonObject();
				legJson.addProperty("hex", org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(leg.railHex));
				legJson.addProperty("kind", leg.kind.name());
				// 这条腿当前是不是禁止通行（道岔没开通它）：网页/操作台据此画红叉或灰掉
				legJson.addProperty("prohibited", turnout != null && leg.railHex.equals(prohibitedRailHex));
				legs.add(legJson);
			}
			o.add("legs", legs);
			final org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore store = simulator.mmtrPointBranches;
			// 行也是按引擎内部的写法存的：查询时两种写法都试（返回给网页的 via 是规范写法）
			final String viaHex = simulator.mmtrResolveRailHex(p.nodeX, p.nodeY, p.nodeZ, p.viaRailHex);
			final int manual = store.contains(p.nodeX, p.nodeY, p.nodeZ, viaHex) ? store.get(p.nodeX, p.nodeY, p.nodeZ, viaHex) : -1;
			o.addProperty("manual", manual);
			o.addProperty("locked", simulator.mmtrPointAuthority.isLocked(p.nodeX, p.nodeY, p.nodeZ, viaHex));
			final String holder = simulator.mmtrPointAuthority.holder(p.nodeX, p.nodeY, p.nodeZ, viaHex);
			o.addProperty("holder", holder == null ? "" : holder);
			o.addProperty("holderLeg", simulator.mmtrPointAuthority.grantedLeg(p.nodeX, p.nodeY, p.nodeZ, viaHex));
			final com.google.gson.JsonArray queue = new com.google.gson.JsonArray();
			for (final String q : simulator.mmtrPointAuthority.queuedSnapshot(p.nodeX, p.nodeY, p.nodeZ, viaHex)) {
				queue.add(q);
			}
			o.add("queue", queue);
			points.add(o);
		}
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("points", points);
		return root;
	}

	/**
	 * Wayside signal feed (信号机登记表): every registered signal light with its MTR facing
	 * angle, aspect count, mode (AUTO = infer / BOUND = covered bind) and - for rail-bound
	 * lights - the live aspect of the rail it reads.
	 */
	private static JsonObject getMmtrSignals(Simulator simulator) {
		/*
		 * notes/167：灯的显示**只读它自己的绑定**（见下），不再按 target 轨去查 per-rail 表 ——
		 * 那条回路由 `computeRailAspectMap` 提供，仍供"轨的显示"那份 feed 使用。
		 */
		final it.unimi.dsi.fastutil.objects.ObjectArrayList<it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<org.mtr.core.data.Position, org.mtr.core.data.VehiclePosition>>> trees = simulator.mmtrOccupancyTrees();
		final it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<String> restricted = org.mtr.core.mmtr.signal.MmtrJunctionState.unclearedNodeKeys(simulator, trees);
		/*
		 * notes/167（用户：「需要将行车区间状态绑定至信号灯上」）：**每一盏灯都读同一份绑定** ——
		 * 它开的区间（一灯多腿多条）、这些段的占用、以及由段状态推出的显示。
		 *
		 * 原来 BOUND 灯走的是另一条路：按 `target` 那条轨去查 per-rail 显示表 —— 那是"绑在轨上"的读法，
		 * 于是同一盏灯在网页上是"轨的状态"、在游戏里是"段的状态"，两边可以不一致。
		 */
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, org.mtr.core.mmtr.signal.MmtrSectionService.LampBinding> lampBindings =
			simulator.mmtrSections.lampBindings(trees, restricted::contains);
		final com.google.gson.JsonArray signals = new com.google.gson.JsonArray();
		simulator.mmtrSignals.signals.forEach((key, entry) -> {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("key", key);
			out.addProperty("x", entry.x);
			out.addProperty("y", entry.y);
			out.addProperty("z", entry.z);
			out.addProperty("angle", entry.angle);
			out.addProperty("aspects", entry.aspects);
			out.addProperty("mode", entry.mode);
			out.addProperty("target", entry.target);
			/*
			 * 灯的状态 = **它开的那一段**的状态（绑定），不再按 target 轨去查 per-rail 表。
			 * 未接入闭塞的灯 `unbound=true`、`aspect=""`：网页显示"未知"，不要画成绿。
			 */
			final org.mtr.core.mmtr.signal.MmtrSectionService.LampBinding binding = lampBindings.get(key);
			final boolean unbound = binding == null || binding.unbound;
			out.addProperty("unbound", unbound);
			out.addProperty("aspect", binding == null ? "" : binding.aspect);
			out.addProperty("occupied", binding != null && binding.occupied);
			// Whether a lamp opens a section at all. A lamp the blockage layer does not know protects
			// nothing, and the console shows that as "未接入" rather than painting it as if it were green.
			out.addProperty("hasSection", !unbound);
			if (binding != null && !binding.sections.isEmpty()) {
				out.addProperty("section", binding.sections.get(0).id);
				final com.google.gson.JsonArray sectionIds = new com.google.gson.JsonArray();
				binding.sections.forEach(section -> sectionIds.add(section.id));
				out.add("sections", sectionIds);
				final com.google.gson.JsonArray nextIds = new com.google.gson.JsonArray();
				binding.nextSectionIds(simulator.mmtrSections).forEach(nextIds::add);
				out.add("nextSections", nextIds);
			}
			/*
			 * 点选绑定用：这盏灯**现在守哪几根轨**（boundRails）与**可以点哪几根**（candidateRails）。
			 *
			 * 两份名单都由引擎算：网页只负责高亮、以及把点击回传成 signal bind --rail，不自己算几何 ——
			 * "高亮的就是能绑的、显示的就是在守的"必须由同一份数据保证，否则界面与引擎各说各话
			 * （实测已经吃过一次：三处各算一遍、三处错得一样，反而更难查）。
			 */
			final com.google.gson.JsonArray boundRails = new com.google.gson.JsonArray();
			if (!entry.rails.isEmpty()) {
				entry.rails.forEach(boundRails::add);
			} else {
				simulator.mmtrSections.protectedRailsOf(entry).forEach(boundRails::add);
			}
			out.add("boundRails", boundRails);
			out.addProperty("boundExplicit", !entry.rails.isEmpty());
			final com.google.gson.JsonArray candidateRails = new com.google.gson.JsonArray();
			simulator.mmtrSections.candidateRailsOf(entry).forEach(candidateRails::add);
			out.add("candidateRails", candidateRails);
			signals.add(out);
		});
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("signals", signals);
		return root;
	}

	/**
	 * Junction leg tables feed (进向表): every authored (node, via) entry with its ordered
	 * continuation rails. Entries override geometric auto-detection wherever they exist.
	 */
	private static JsonObject getMmtrJunctionLegs(Simulator simulator) {
		final com.google.gson.JsonArray entries = new com.google.gson.JsonArray();
		simulator.mmtrJunctionLegs.legs.forEach((key, legHexes) -> {
			final String[] p = key.split("\\|");
			if (p.length != 2) {
				return;
			}
			final String[] c = p[0].split(",");
			if (c.length != 3) {
				return;
			}
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("x", Long.parseLong(c[0]));
			out.addProperty("y", Long.parseLong(c[1]));
			out.addProperty("z", Long.parseLong(c[2]));
			out.addProperty("via", p[1]);
			final com.google.gson.JsonArray legs = new com.google.gson.JsonArray();
			for (final String hex : legHexes) {
				legs.add(hex);
			}
			out.add("legs", legs);
			entries.add(out);
		});
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("entries", entries);
		return root;
	}

	/**
	 * Rail-topology feed (web track display): every node (degree >= 1, buffers included) with its
	 * neighbour rails, PLUS the full rail segment list with both real endpoints - the map draws the
	 * actual track network underneath the fork markers (topological display), not just the abstract
	 * schematic connections.
	 */
	private static JsonObject getMmtrTopology(org.mtr.core.simulation.Simulator simulator) {
		final com.google.gson.JsonArray nodes = new com.google.gson.JsonArray();
		/*
		 * 节点的 `block` 字段**已删除**（2026-09-15 用户裁定）。
		 *
		 * <p>它原来发的是"这个节点唯一归属哪个水闸区间"。区间是**某方向的一段路**，所以那个"唯一归属"
		 * 在双向线路上必然错（现场实测：被区间覆盖的 96 根轨里 62 根属于 2 个以上区间，最多 5 个）。
		 * 网页要的一格/一点属于哪几个区间，由 `/mmtr-sections` 的 `byRail` 回答（多值）。</p>
		 */
		simulator.positionsToRail.forEach((node, neighbourMap) -> {
			if (neighbourMap.isEmpty()) {
				return;
			}
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("x", node.getX());
			out.addProperty("y", node.getY());
			out.addProperty("z", node.getZ());
			out.addProperty("degree", neighbourMap.size());
			/*
			 * 节点的**游戏内朝向角**（度）。只有游戏端扫描上报过才有这个字段。
			 *
			 * <p>引擎的拓扑原本只有坐标，于是"一盏灯守哪条腿"只能靠灯自己的朝向来猜；实测世界里
			 * 同一个节点上两盏朝向相对的灯守的是相反方向，猜不出来。原版渲染
			 * `RenderSignalBase.getAspectState` 用的是 `BlockNode.getAngle(state) + 90` 这个朝向，
			 * 现在把它原样带给引擎和网页。</p>
			 */
			final Float nodeAngle = simulator.mmtrNodeAngle(node.getX(), node.getY(), node.getZ());
			if (nodeAngle != null) {
				out.addProperty("angle", nodeAngle);
			}
			final com.google.gson.JsonArray neighbours = new com.google.gson.JsonArray();
			neighbourMap.forEach((pos, rail) -> {
				final com.google.gson.JsonObject n = new com.google.gson.JsonObject();
				n.addProperty("x", pos.getX());
				n.addProperty("y", pos.getY());
				n.addProperty("z", pos.getZ());
				n.addProperty("rail", rail.getHexId());
				neighbours.add(n);
			});
			out.add("neighbors", neighbours);
			nodes.add(out);
		});
		// Deduplicated rail segments: collect each rail's two endpoint nodes from the position map.
		final com.google.gson.JsonArray rails = new com.google.gson.JsonArray();
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, org.mtr.core.data.Rail> byHex = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, org.mtr.core.data.Position[]> railEnds = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();
		simulator.positionsToRail.forEach((node, neighbourMap) -> neighbourMap.forEach((pos, rail) -> {
			byHex.putIfAbsent(rail.getHexId(), rail);
			final org.mtr.core.data.Position[] ends = railEnds.computeIfAbsent(rail.getHexId(), k -> new org.mtr.core.data.Position[2]);
			if (ends[0] == null) {
				ends[0] = node;
			} else if (ends[1] == null && !ends[0].equals(node)) {
				ends[1] = node;
			}
		}));
		byHex.forEach((hex, rail) -> {
			final org.mtr.core.data.Position[] ends = railEnds.get(hex);
			if (ends == null || ends[1] == null) {
				return;
			}
			final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			/*
			 * hex 用**规范形式**（两个端点表示里取字典序小的那个）。
			 *
			 * <p>同一条实体轨有两个互为逆序的 hex（取决于这条 Rail 怎么被声明），而网页点选绑定要把
			 * 这个 hex 原样发回来。如果对外发的是"声明顺序"的写法、引擎内部按别的写法存，
			 * 页面点的轨和引擎绑的轨就成了两个字符串 —— 绑定会静默失败（实测踩过：
			 * 接口回 ok、绑定列表却没变）。对外统一成规范形式，两边永远对得上。</p>
			 */
			o.addProperty("hex", org.mtr.core.mmtr.signal.MmtrSectionService.canonicalHex(hex));
			o.addProperty("x1", ends[0].getX());
			o.addProperty("y1", ends[0].getY());
			o.addProperty("z1", ends[0].getZ());
			o.addProperty("x2", ends[1].getX());
			o.addProperty("y2", ends[1].getY());
			o.addProperty("z2", ends[1].getZ());
			/*
			 * The rail's ACTUAL shape along its arc length.
			 *
			 * <p>This used to be omitted on purpose ("pure topology edge: the map connects the rail's two real
			 * nodes with one straight edge, no in-game curve sampling"). That was wrong for a track display:
			 * a RailMath is built from TWO arc segments (h1/k1/r1 and h2/k2/r2), so a rail can bend into a
			 * U or even an S. With only the two endpoints the console has to invent the middle, and any
			 * invented middle (a single arc, a bezier) is visibly a different track than the real one.</p>
			 *
			 * <p>Sampled at a fixed number of steps rather than by a metre interval: the console is a plan
			 * view, so what matters is that the polyline is visually indistinguishable from the arc, and a
			 * fixed step count bounds the payload (32 steps × 159 rails ≈ 16 KB of JSON, two decimals per
			 * coordinate) while the angular error per segment stays a couple of degrees.</p>
			 *
			 * <p><b>The samples are NOT integers</b> (see {@link #roundMmtrPathSample}) — rounding them
			 * makes every arc a staircase again, which is exactly what a track display cannot afford.</p>
			 *
			 * <p>Sampled at arc distances, and the arc space starts at whichever endpoint sorts first - NOT
			 * necessarily {@code ends[0]} here (that is just "the node the position map happened to visit
			 * first"). So the direction is derived from the arc offsets of the two declared endpoints
			 * ({@link org.mtr.core.data.Rail#mmtrArcOfEndNode}) instead of being assumed; the console gets a
			 * plain ordered polyline and never has to know about arc space.</p>
			 */
			final com.google.gson.JsonArray path = new com.google.gson.JsonArray();
			final double length = rail.railMath.getLength();
			if (length > 0) {
				final double arcOfFirst = rail.mmtrArcOfEndNode(ends[0]);
				final boolean reversed = !Double.isNaN(arcOfFirst) && arcOfFirst > 0;
				final int steps = MMTR_RAIL_PATH_STEPS;
				for (int i = 0; i <= steps; i++) {
					final double arcM = length * i / steps;
					final org.mtr.core.tool.Vector point = rail.railMath.getPosition(arcM, reversed);
					final com.google.gson.JsonArray sample = new com.google.gson.JsonArray();
					sample.add(roundMmtrPathSample(point.x()));
					sample.add(roundMmtrPathSample(point.y()));
					sample.add(roundMmtrPathSample(point.z()));
					path.add(sample);
				}
			}
			o.add("path", path);
			// Signal S2: per-direction speed limits (km/h from the MTR rail data) along each travel
			// direction of this edge - the web console colours / labels tracks by speed band + regime.
			o.addProperty("speedLimitKmh1", rail.getSpeedLimitKilometersPerHour(ends[0].compareTo(ends[1]) > 0));
			o.addProperty("speedLimitKmh2", rail.getSpeedLimitKilometersPerHour(ends[1].compareTo(ends[0]) > 0));
			rails.add(o);
		});
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("nodes", nodes);
		root.add("rails", rails);
		return root;
	}

	/** Automatic lines feed (线路自动识别): every detected line with its rails in stroke order. */
	private static JsonObject getMmtrLines(org.mtr.core.simulation.Simulator simulator) {
		final com.google.gson.JsonArray lines = new com.google.gson.JsonArray();
		for (final org.mtr.core.mmtr.line.MmtrLineDetector.MmtrLine line : simulator.mmtrDetectLines()) {
			final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("id", line.id());
			o.addProperty("name", line.name());
			o.addProperty("lengthM", Math.round(line.lengthM * 10.0) / 10.0);
			final com.google.gson.JsonArray rails = new com.google.gson.JsonArray();
			for (final String hex : line.rails) {
				rails.add(hex);
			}
			o.add("rails", rails);
			lines.add(o);
		}
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("lines", lines);
		return root;
	}

	/** Decoupled vehicle motion feed: (segment id + offset) positions for clients/map (no baked routes). */
	private static JsonObject getMmtrMotion(Simulator simulator) {
		final com.google.gson.JsonArray snapshots = new com.google.gson.JsonArray();
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> snapshots.add(Utilities.getJsonObjectFromData(org.mtr.core.mmtr.MmtrMotionSnapshot.from(siding, vehicle)))));
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("snapshots", snapshots);
		return root;
	}

	/**
	 * Task-sheet timetable feed (任务单时间表): every consist job rendered as one row per task
	 * (step) with its kind, target, planned time and a per-step state derived from the scheduler
	 * (DONE for finished steps, RUNNING for the current one, FAILED on a dead job, PENDING after).
	 */
	private static JsonObject getMmtrSchedule(Simulator simulator) {
		final org.mtr.core.mmtr.job.MmtrJobScheduler scheduler = simulator.mmtrJobScheduler;
		final com.google.gson.JsonArray jobs = new com.google.gson.JsonArray();
		for (final org.mtr.core.mmtr.job.MmtrConsistJob job : simulator.getMmtrJobRegistry().jobs) {
			final com.google.gson.JsonObject jobJson = new com.google.gson.JsonObject();
			jobJson.addProperty("jobId", job.jobId);
			jobJson.addProperty("depotId", String.valueOf(job.depotId));
			jobJson.addProperty("sidingId", String.valueOf(job.sidingId));
			jobJson.addProperty("startTimeOfDayMs", job.startTimeOfDayMs);
			jobJson.addProperty("loop", job.loop);
			final String jobState = scheduler == null ? null : scheduler.stateOf(job.jobId) == null ? null : scheduler.stateOf(job.jobId).name();
			jobJson.addProperty("state", jobState == null ? "PENDING" : jobState);
			final int currentStep = scheduler == null ? -1 : scheduler.stepIndexOf(job.jobId);
			jobJson.addProperty("currentStep", currentStep);
			final String failure = scheduler == null ? null : scheduler.failureOf(job.jobId);
			if (failure != null) {
				jobJson.addProperty("failure", failure);
			}
			final com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
			for (int i = 0; i < job.steps.size(); i++) {
				final org.mtr.core.mmtr.job.MmtrJobStep step = job.steps.get(i);
				final com.google.gson.JsonObject row = new com.google.gson.JsonObject();
				row.addProperty("stepIndex", i);
				row.addProperty("stepId", step.stepId);
				row.addProperty("type", step.type.name());
				final boolean isPlatform = step.type != org.mtr.core.mmtr.job.MmtrJobStep.StepType.COUPLE && step.type != org.mtr.core.mmtr.job.MmtrJobStep.StepType.UNCOUPLE && isPlatform(simulator, step.targetId);
				row.addProperty("taskKind", taskKindOf(step, isPlatform));
				row.addProperty("targetKind", step.type == org.mtr.core.mmtr.job.MmtrJobStep.StepType.COUPLE || step.type == org.mtr.core.mmtr.job.MmtrJobStep.StepType.UNCOUPLE || step.type == org.mtr.core.mmtr.job.MmtrJobStep.StepType.CHANGE_ENDS ? "" : isPlatform ? "PLATFORM" : "SIDING");
				row.addProperty("targetId", String.valueOf(step.targetId));
				row.addProperty("plannedMs", step.dueTimeOfDayMs);
				if (step.note != null && !step.note.isEmpty()) {
					row.addProperty("note", step.note);
				}
				row.addProperty("state", stepState(jobState, currentStep, i));
				rows.add(row);
			}
			jobJson.add("rows", rows);
			jobs.add(jobJson);
		}
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("jobs", jobs);
		root.addProperty("currentTime", System.currentTimeMillis());
		return root;
	}

	/** Task-kind label of a step for the timetable (job step → task mapping, same as MmtrTaskFactory). */
	private static String taskKindOf(org.mtr.core.mmtr.job.MmtrJobStep step, boolean isPlatform) {
		return switch (step.type) {
			case MOVE_TO -> isPlatform ? "DRIVE_TO_PLATFORM" : "DRIVE_TO_SIDING";
			case SERVE -> "STATION_SERVICE";
			case CHANGE_ENDS -> "CHANGE_ENDS";
			case COUPLE -> "COUPLE";
			case UNCOUPLE -> "UNCOUPLE";
		};
	}

	private static String stepState(String jobState, int currentStep, int stepIndex) {
		if (jobState == null || jobState.equals("PENDING") || currentStep < 0) {
			return "PENDING";
		}
		return switch (jobState) {
			case "RUNNING" -> stepIndex < currentStep ? "DONE" : stepIndex == currentStep ? "RUNNING" : "PENDING";
			case "DONE" -> "DONE";
			case "FAILED" -> stepIndex < currentStep ? "DONE" : stepIndex == currentStep ? "FAILED" : "PENDING";
			default -> "PENDING";
		};
	}

	/** Whether the given world-object id is a platform (the timetable target-kind split). */
	private static boolean isPlatform(Simulator simulator, long targetId) {
		final boolean[] found = {false};
		simulator.platforms.forEach(platform -> {
			if (platform.getId() == targetId) {
				found[0] = true;
			}
		});
		return found[0];
	}

	/**
	 * P1：计划输入改动后统一回的那一句 —— {@code {ok, problems, errors[]}}。
	 *
	 * <p>**写进去就回问题**（而不是回一个空洞的 ok=true）：网页在保存前就能把"哪一条不对"显示出来，
	 * 与"加载即报错"（设计 §4.3）是同一个口径。</p>
	 */
	private static JsonObject planResult(Simulator simulator) {
		final com.google.gson.JsonObject result = new com.google.gson.JsonObject();
		final com.google.gson.JsonArray errors = new com.google.gson.JsonArray();
		for (final String error : simulator.mmtrPlanErrors) {
			errors.add(error);
		}
		result.addProperty("ok", errors.isEmpty());
		result.addProperty("problems", errors.size());
		result.add("errors", errors);
		return result;
	}

	/**
	 * P5：把接口上的一个事件读成 {@link org.mtr.core.mmtr.plan.MmtrEvent}（四类细分按 {@code kind} 分派）。
	 */
	private static org.mtr.core.mmtr.plan.MmtrEvent readPlanEvent(org.mtr.core.serializer.JsonReader reader) {
		final String eventId = reader.getString("eventId", "");
		final String kind = reader.getString("kind", "PEAK_SURGE").toUpperCase(java.util.Locale.ENGLISH);
		final long startMillis = reader.getLong("startMillis", 0);
		final long endMillis = reader.has("endMillis") ? reader.getLong("endMillis", -1) : -1;
		final org.mtr.core.mmtr.plan.MmtrEvent event = switch (kind) {
			case "PEAK_SURGE" -> new org.mtr.core.mmtr.plan.MmtrEvent.PeakSurge(eventId,
				reader.getLong("stationId", 0), startMillis, endMillis, reader.getLong("headwayMillis", 5L * 60 * 1000));
			case "DELAY" -> new org.mtr.core.mmtr.plan.MmtrEvent.Delay(eventId,
				reader.getString("consistId", ""), startMillis, reader.getLong("delayMillis", 5L * 60 * 1000))
				.withStrategy(reader.getInt("strategy", org.mtr.core.mmtr.plan.MmtrEvent.Delay.STRATEGY_KEEP_TIMETABLE));
			case "FAULT" -> new org.mtr.core.mmtr.plan.MmtrEvent.Fault(eventId,
				reader.getString("consistId", ""), startMillis, reader.getBoolean("vehicleDown", true));
			case "SPEED_RESTRICTION" -> new org.mtr.core.mmtr.plan.MmtrEvent.SpeedRestriction(eventId,
				reader.getString("railHex", ""), startMillis, endMillis, reader.getDouble("speedKmh", 40));
			default -> null;
		};
		if (event != null) {
			event.reason = reader.getString("reason", "");
			event.severity = reader.getInt("severity", 0);
		}
		return event;
	}

	/** Job-editor pickers: in-game depots / sidings / platforms (decimal id strings + display names). */
	private static JsonObject getMmtrJobReferences(Simulator simulator) {
		final com.google.gson.JsonArray depots = new com.google.gson.JsonArray();
		simulator.depots.forEach(depot -> {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("id", String.valueOf(depot.getId()));
			out.addProperty("name", depot.getName());
			depots.add(out);
		});
		final com.google.gson.JsonArray sidings = new com.google.gson.JsonArray();
		simulator.sidings.forEach(siding -> {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("id", String.valueOf(siding.getId()));
			out.addProperty("name", siding.getName());
			out.addProperty("depotId", String.valueOf(siding.area == null ? 0 : siding.area.getId()));
			out.addProperty("depotName", siding.area == null ? "" : siding.area.getName());
			out.addProperty("manual", siding.getIsManual());
			sidings.add(out);
		});
		final com.google.gson.JsonArray platforms = new com.google.gson.JsonArray();
		simulator.platforms.forEach(platform -> {
			final com.google.gson.JsonObject out = new com.google.gson.JsonObject();
			out.addProperty("id", String.valueOf(platform.getId()));
			out.addProperty("name", platform.getName());
			out.addProperty("stationName", platform.area == null ? "" : platform.area.getName());
			platforms.add(out);
		});
		final com.google.gson.JsonArray templates = new com.google.gson.JsonArray();
		for (final org.mtr.core.mmtr.job.MmtrConsistTemplate template : simulator.mmtrConsistTemplates.templates) {
			templates.add(Utilities.getJsonObjectFromData(template));
		}
		final com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.add("depots", depots);
		root.add("sidings", sidings);
		root.add("platforms", platforms);
		root.add("templates", templates);
		return root;
	}
}
package org.mtr.core.mmtr.probe;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Station;
import org.mtr.core.mmtr.MmtrPid;
import org.mtr.core.mmtr.MmtrTaskTarget;
import org.mtr.core.mmtr.job.MmtrConsistJob;
import org.mtr.core.mmtr.job.MmtrJobStep;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **水牌 / PID 的真机世界探针**（notes/354）：拿**现场那份 {@code mmtr-jobs.json}**（10 份作业单、
 * 各 236~244 步）把水牌逐趟打出来，并用**作业单自己那句 {@code note}**（作者写的、与水牌实现无关的
 * 另一份数据）做交叉验证。
 *
 * <h2>为什么要用真作业单，而不是只靠合成用例</h2>
 * <p>合成用例钉的是口径（终点是本趟最后一个停站、下一站允许跨趟、名字取车站名）。这里钉的是
 * **现场的编排真的符合那套口径**：{@code 00101} 是 {@code 海山1台 ⇄ 叶楼2台} 来回 13 次换端 + 回库，
 * 每一步的 note 里都写着站名。于是"水牌说终点是海山"与"作者在海山那一步的 note 里确实写着海山"
 * 是两份独立的数据 —— 两边都说得通才算过。</p>
 *
 * <p>世界不存在时跳过（干净的 CI 上不会红）。</p>
 */
public final class MmtrPidDevWorldProbeTests {

	private static final Path DEV_WORLD_MTR_ROOT = Paths.get("../game/fabric/run/world/mtr");
	private static final String DIMENSION = "minecraft/overworld";

	private static @Nullable Simulator load() {
		if (!Files.isDirectory(DEV_WORLD_MTR_ROOT)) {
			return null;
		}
		return new Simulator(DIMENSION, new String[]{DIMENSION}, DEV_WORLD_MTR_ROOT, false);
	}

	/** 这一步的目标是不是"停在站台"（用公开的解析口，不借 {@link MmtrPid} 的私有判据）。 */
	private static boolean stopsAtPlatform(Simulator simulator, MmtrJobStep step) {
		return step.targetId != 0 && MmtrTaskTarget.resolve(simulator, step.targetId, null, -1).kind() == MmtrTaskTarget.Kind.PLATFORM;
	}

	/** 这一步停的**车站名**（现算；站台/股道/轨目标一律空串）。 */
	private static String stationOf(Simulator simulator, MmtrJobStep step) {
		return stopsAtPlatform(simulator, step) ? MmtrTaskTarget.resolve(simulator, step.targetId, null, -1).stationName() : "";
	}

	private static String noteOf(MmtrJobStep step) {
		return step.note == null ? "" : step.note;
	}

	/**
	 * **从作者写的 note 里挤出站名那一段**：{@code "鸥湾1台 开关门停站" → "鸥湾"}、{@code "开到 叶楼2台" → "叶楼"}；
	 * 挤不出来（"回库（退库到本务股道并停放）"这类没有站台号的）⇒ 空串。
	 *
	 * <p>为什么是"挤前缀"而不是整串比对：note 是**作者手写的简称**，现场的站名可以更长
	 * （实测：作业单写 {@code 叶楼2台}，而世界里那个车站叫 {@code 叶楼村}）。于是判据定为
	 * **世界站名以 note 的站名段开头** —— 作者用简称、引擎用实名都能过，
	 * 而"水牌指到了另一个站"照样红。</p>
	 */
	private static String noteStationToken(String note) {
		final String trimmed = note.replace("开到", "").trim();
		final int platformMark = trimmed.indexOf('台');
		if (platformMark <= 0) {
			return "";
		}
		int end = platformMark;
		while (end > 0 && Character.isDigit(trimmed.charAt(end - 1))) {
			end--;
		}
		return trimmed.substring(0, end).trim();
	}

	/** 把 "note 里的站名段" 与 "世界里的车站名" 的对应关系记一份（给日志/验收看）。 */
	private static void recordToken(java.util.Map<String, String> tokenToEngineName, String note, String engineName) {
		final String token = noteStationToken(note);
		if (!token.isEmpty() && !engineName.isEmpty()) {
			tokenToEngineName.put(token, engineName);
		}
	}

	/**
	 * **已知的"作业单写法 ≠ 世界站名"**（本现场实测两处，2026-10-01）：
	 * 作业单 note 写 {@code 朗园1台} / {@code 叶楼2台}，而世界里的车站叫 {@code 朗源} / {@code 叶楼村}。
	 *
	 * <p>这张表是**显式**的：水牌显示的是**世界里的车站名**（它从 {@code Station} 对象现算），
	 * 所以作业单那边的别称要在这里点名列出来。将来谁改了站名或改了 note，只要没同步更新这张表，
	 * 对不上的那一条就会让用例红 —— 而不是静默地在水牌上显示一个和作业单对不上的名字。</p>
	 */
	private static final java.util.Map<String, String> KNOWN_NOTE_ALIASES = java.util.Map.of(
		"朗园", "朗源",
		"叶楼", "叶楼村",
		"上水", "上水村",
		"莫氏", "莫氏岛"
	);

	/** note 的站名段 → 水牌上应当出现的世界站名（没有别名时就是它自己）。 */
	private static String engineNameForToken(String token) {
		return KNOWN_NOTE_ALIASES.getOrDefault(token, token);
	}

	/** 作业单里所有换端步的下标（升序）。 */
	private static int[] changeEndsIndexes(MmtrConsistJob job) {
		final ObjectArrayList<Integer> indexes = new ObjectArrayList<>();
		for (int i = 0; i < job.steps.size(); i++) {
			if (job.steps.get(i).type == MmtrJobStep.StepType.CHANGE_ENDS) {
				indexes.add(i);
			}
		}
		final int[] out = new int[indexes.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = indexes.get(i);
		}
		return out;
	}

	/**
	 * 逐份作业单：每一趟的终点必须**出自那一趟最后一个站台步的 note**（作者的数据），
	 * 并且必须是世界上真有的车站名。
	 */
	@Test
	public void everyLegTerminusMatchesTheAuthoredNote() {
		final Simulator simulator = load();
		Assumptions.assumeTrue(simulator != null, "dev world save not present");
		final Simulator sim = simulator;

		final Set<String> stationNames = new LinkedHashSet<>();
		for (final Station station : sim.stations) {
			stationNames.add(station.getName());
		}
		System.out.println("[PID] 世界车站=" + stationNames + "  作业单=" + sim.mmtrJobRegistry.jobs.size() + " 份");

		int legs = 0;
		int noteChecks = 0;
		final java.util.Map<String, String> tokenToEngineName = new java.util.TreeMap<>();
		for (final MmtrConsistJob job : sim.mmtrJobRegistry.jobs) {
			final int[] changeEnds = changeEndsIndexes(job);
			int legStart = 0;
			final StringBuilder summary = new StringBuilder();
			for (int leg = 0; leg <= changeEnds.length; leg++) {
				final int legEnd = leg < changeEnds.length ? changeEnds[leg] : job.steps.size();
				if (legStart < legEnd) {
					legs++;
					// 本趟最后一个站台步（作者写 note 的那一步）
					MmtrJobStep lastStop = null;
					for (int i = legStart; i < legEnd; i++) {
						if (stopsAtPlatform(sim, job.steps.get(i))) {
							lastStop = job.steps.get(i);
						}
					}
					final MmtrPid pid = MmtrPid.of(sim, job, legStart);
					assertEquals(job.jobId, pid.serviceNumber(), job.jobId + " 第 " + pid.legIndex() + " 趟：班次号就是作业单号");
					if (lastStop == null) {
						assertEquals("", pid.terminus(), job.jobId + " 步 " + legStart + "：本趟不停站台 ⇒ 终点为空（回库趟）");
						summary.append(" [回库]");
					} else {
						final String expected = stationOf(sim, lastStop);
						assertEquals(expected, pid.terminus(), job.jobId + " 步 " + legStart + "：本趟终点应当是最后一个站台步的站");
						assertTrue(stationNames.contains(pid.terminus()), "终点必须是世界上真有的车站：" + pid.terminus());
						final String token = noteStationToken(noteOf(lastStop));
						if (!token.isEmpty()) {
							// ★ 交叉验证：作者在**最后那个停站步**的 note 里写的站名段，必须就是水牌报的那个站
							// （已知别称见 KNOWN_NOTE_ALIASES：作业单写简称、世界里的车站名更长）
							assertEquals(engineNameForToken(token), pid.terminus(),
								"作业单 note 与水牌终点对不上：note=「" + noteOf(lastStop) + "」水牌终点=「" + pid.terminus() + "」");
							recordToken(tokenToEngineName, noteOf(lastStop), pid.terminus());
							noteChecks++;
						}
						summary.append(' ').append(pid.terminus());
					}
				}
				legStart = legEnd + 1;
			}
			System.out.println("[PID] 作业 " + job.jobId + " 步=" + job.steps.size() + " 趟=" + (changeEnds.length + 1)
				+ " 终点序列=" + summary);
		}
		assertTrue(legs > 0, "至少要看出一趟");
		assertTrue(noteChecks > 0, "至少要有一条 note 参与交叉验证（否则这条用例什么都没验）");
		System.out.println("[PID] 作业单 note 的站名段 → 世界里的车站名：" + tokenToEngineName);
		System.out.println("[PID] 逐趟终点：趟数=" + legs + " 其中 " + noteChecks + " 条与作业单 note 的站名段对上");
	}

	/**
	 * 逐**步**：下一站必须等于"再往前第一个站台步"的站名，且与那一步的 note 对得上；
	 * 同时把水牌的**变化点**打出来（这是给用户看的验收清单：换端翻趟、过站换下一站）。
	 */
	@Test
	public void everyStepNextStationMatchesTheNextAuthoredStop() {
		final Simulator simulator = load();
		Assumptions.assumeTrue(simulator != null, "dev world save not present");
		final Simulator sim = simulator;
		final MmtrConsistJob job = sim.mmtrJobRegistry.jobs.isEmpty() ? null : sim.mmtrJobRegistry.jobs.get(0);
		Assumptions.assumeTrue(job != null, "no jobs in the dev world");

		String previous = null;
		int nextChecks = 0;
		int flips = 0;
		for (int i = 0; i < job.steps.size(); i++) {
			final MmtrPid pid = MmtrPid.of(sim, job, i);
			// 独立的"下一步会停哪个站台"：从 i+1 往后第一个站台步
			MmtrJobStep nextStop = null;
			for (int j = i + 1; j < job.steps.size(); j++) {
				if (stopsAtPlatform(sim, job.steps.get(j))) {
					nextStop = job.steps.get(j);
					break;
				}
			}
			assertEquals(nextStop == null ? "" : stationOf(sim, nextStop), pid.nextStation(),
				job.jobId + " 步 " + i + "（" + noteOf(job.steps.get(i)) + "）：下一站");
			if (nextStop != null) {
				final String token = noteStationToken(noteOf(nextStop));
				if (!token.isEmpty()) {
					assertEquals(engineNameForToken(token), pid.nextStation(),
						"下一站与那一步的 note 对不上：note=「" + noteOf(nextStop) + "」水牌=「" + pid.nextStation() + "」");
					nextChecks++;
				}
			}
			if (previous != null && !previous.equals(pid.describe())) {
				flips++;
			}
			if (previous == null || !previous.equals(pid.describe())) {
				System.out.println("[PID] " + job.jobId + " 步 " + i + " " + job.steps.get(i).type
					+ "（" + noteOf(job.steps.get(i)) + "）→ " + pid.describe());
			}
			previous = pid.describe();
		}
		assertTrue(nextChecks > 0, "至少要有一条 note 参与下一站的交叉验证");
		System.out.println("[PID] 逐站下一站：" + nextChecks + " 条与作业单 note 对上；水牌变化点=" + flips + " 次");
	}

	/**
	 * **反例（口径守卫）**：水牌绝不能给出站台标签（{@code "海山1台"}）那类写法 ——
	 * 它要的是"开往哪个站"。
	 *
	 * <p>判据用"站台标签"这个词本身的构造规则（{@code 车站名 + "站" + 站号 + "台"}）：
	 * 水牌的字段既不许等于任何一个站台目标的标签，也不许带"台"字。
	 * 不去查"不许出现『站』字"—— 车站名本身完全可能带"站"，那会变成一条随世界改名的假反例。</p>
	 */
	@Test
	public void thePidNeverLeaksPlatformLabels() {
		final Simulator simulator = load();
		Assumptions.assumeTrue(simulator != null, "dev world save not present");
		final Simulator sim = simulator;
		final Set<String> platformLabels = new LinkedHashSet<>();
		for (final Platform platform : sim.platforms) {
			platformLabels.add(MmtrTaskTarget.resolve(sim, platform.getId(), null, -1).label());
		}
		int checked = 0;
		for (final MmtrConsistJob job : sim.mmtrJobRegistry.jobs) {
			for (int i = 0; i < job.steps.size(); i++) {
				final MmtrPid pid = MmtrPid.of(sim, job, i);
				for (final String field : new String[]{pid.terminus(), pid.nextStation()}) {
					assertTrue(!field.contains("台"), job.jobId + " 步 " + i + "：水牌里不该出现站台号：" + field);
					assertTrue(!platformLabels.contains(field), job.jobId + " 步 " + i + "：水牌写成了站台标签：" + field);
					checked++;
				}
			}
		}
		assertTrue(checked > 0, "至少要看过一个字段");
		assertTrue(!platformLabels.isEmpty(), "世界上要有站台，这条反例才有意义");
		System.out.println("[PID] 站台标签反例：逐站逐字段看了 " + checked + " 个字段，没有一处写成站台标签（共 "
			+ platformLabels.size() + " 个站台标签，形如 " + platformLabels.iterator().next() + "）");
	}

	/** 车站名/站台名现场长什么样（给用户核对"水牌上的字是不是他要的那几个字"）。 */
	@Test
	public void printTheStationAndPlatformNames() {
		final Simulator simulator = load();
		Assumptions.assumeTrue(simulator != null, "dev world save not present");
		final Simulator sim = simulator;
		for (final Station station : sim.stations) {
			final ObjectArrayList<String> platformNames = new ObjectArrayList<>();
			for (final Platform platform : sim.platforms) {
				if (platform.area != null && platform.area.getId() == station.getId()) {
					platformNames.add(platform.getName());
				}
			}
			System.out.println("[PID] 车站「" + station.getName() + "」站台=" + platformNames);
		}
	}
}

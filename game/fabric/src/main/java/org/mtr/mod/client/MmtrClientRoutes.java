package org.mtr.mod.client;

import org.mtr.core.mmtr.signal.MmtrSectionService;
import org.mtr.mod.mmtr.MmtrSignalChain;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MMTR client-side route mirror (A2): the engine pushes the two derived views the signal renderer
 * needs, so the client never re-implements interlocking logic.
 *
 * <ul>
 *   <li>{@link #nextRails(String)} — a SET main route locks one path through a rail: the renderer
 *       follows only that rail at the fork instead of walking every branch (the old conservative
 *       rule made a signal look worse than the route it protects). The value is a LIST because a
 *       route may traverse the same rail twice (牵出—推进 / 尽头换向); the renderer picks the entry
 *       that shares the node it is walking toward, exactly like the engine does;</li>
 *   <li>{@link #isPendingEntry(String)} — a rail that is the entry of a not-yet-set route shows
 *       danger: the movement is waiting outside its signal;</li>
 *   <li>B3b: {@link #sections(String)} — the block sections the engine cut into a rail (only rails
 *       that a wayside signal actually splits are listed), so the renderer can count sections instead
 *       of whole rails.</li>
 *   <li>notes/291: {@link #sectionBands(String)} — 拿着信号灯时画的**区间带**（弧窗 + 色号 + 行车方向），
 *       由引擎的 {@code MmtrSectionService#sectionOverlay()} 算好（相连两段异色），客户端只显示。</li>
 * </ul>
 *
 * <p>Pure display data, replaced wholesale by {@code PacketMmtrRoutes}; empty means "no route
 * set anywhere", which is exactly the pre-A2 free-driving behaviour.</p>
 */
public final class MmtrClientRoutes {

	private static volatile Map<String, List<String>> nextRails = Collections.emptyMap();
	private static volatile Set<String> pendingEntries = Collections.emptySet();
	private static volatile Set<String> restrictedNodes = Collections.emptySet();
	/** S4: lamp {@code x,y,z} key -&gt; the aspect the ENGINE's v2 model gives that lamp. */
	private static volatile Map<String, String> lampAspects = Collections.emptyMap();
	/**
	 * 绑定工具用：lamp {@code x,y,z} 键 → 这盏灯**守的轨**（一灯多腿时多条）。
	 *
	 * <p>由引擎算、随 {@code PacketMmtrRoutes} 发过来。客户端自己按几何推一遍必然与引擎分叉，
	 * 而"这盏灯到底守哪根轨"正是绑定工具要给人看的东西。</p>
	 */
	private static volatile Map<String, List<String>> lampRails = Collections.emptyMap();
	/**
	 * 区间叠加层用：轨 hex（规范化）→ 覆盖这段轨的**区间带**（notes/291）。
	 *
	 * <p>一条带 = 一个区间在这根轨上的一段弧窗 + 色号 + 行车方向。由引擎算（{@code sectionOverlay()}），
	 * 客户端只按弧窗把它画到轨面上 —— 客户端自己按几何推"这段属于哪个区间"必然与引擎分叉，
	 * 而"看得见边界"正是这个叠加层唯一要保证的事。</p>
	 */
	private static volatile Map<String, List<SectionBand>> sectionBands = Collections.emptyMap();

	private MmtrClientRoutes() {
	}

	/**
	 * 一条区间带（世界渲染用的一段弧窗）：{@code arcFromM..arcToM} 是**ordered-position-1 弧长**，
	 * 与引擎 {@code RailMath.getPosition(arc, false)} 同一套坐标 —— 所以客户端直接用
	 * {@code rail.railMath.getPosition} 取点，不需要任何换算。
	 */
	public static final class SectionBand {
		/** 区间 id（诊断用；也是"这一段属于哪条区间"的唯一标识）。 */
		public final String sectionId;
		/** 客户端调色板下标（引擎的 {@code MmtrSectionOverlay.COLOR_COUNT} 取模）。 */
		public final int colorIndex;
		/** 本区间的行车方向（单位向量，世界 xz）。 */
		public final double headingX;
		public final double headingZ;
		public final double arcFromM;
		public final double arcToM;

		SectionBand(String sectionId, int colorIndex, double headingX, double headingZ, double arcFromM, double arcToM) {
			this.sectionId = sectionId;
			this.colorIndex = colorIndex;
			this.headingX = headingX;
			this.headingZ = headingZ;
			this.arcFromM = arcFromM;
			this.arcToM = arcToM;
		}

		/** 本段长度（米）。 */
		public double lengthM() {
			return Math.abs(arcToM - arcFromM);
		}
	}

	/** Replace the mirror (called from the packet handler on the client thread). */
	public static void update(Map<String, List<String>> next, Set<String> pending) {
		update(next, pending, Collections.emptySet(), Collections.emptyMap());
	}

	/** Replace the mirror including the ④ restricted-junction node keys. */
	public static void update(Map<String, List<String>> next, Set<String> pending, Set<String> restricted) {
		update(next, pending, restricted, Collections.emptyMap());
	}

	/**
	 * Replace the mirror including the S4 lamp aspects: the engine computes the 闭塞区间 v2 aspect per LAMP
	 * (what one lamp protects, walked lamp to lamp), so it ships its conclusion instead of the raw walk and
	 * the client renderer looks its own block position up.
	 */
	public static void update(Map<String, List<String>> next, Set<String> pending, Set<String> restricted, Map<String, String> lampAspectMap) {
		update(next, pending, restricted, lampAspectMap, Collections.emptyMap());
	}

	/**
	 * As above, plus every lamp's **守轨**（一灯多腿时多条），供绑定工具的叠加层显示。
	 *
	 * <p>这份关系由引擎算（它持有节点、朝向、人工绑定与区间那一整套），客户端只显示。</p>
	 */
	public static void update(Map<String, List<String>> next, Set<String> pending, Set<String> restricted, Map<String, String> lampAspectMap, Map<String, List<String>> lampRailMap) {
		update(next, pending, restricted, lampAspectMap, lampRailMap, Collections.emptyList());
	}

	/**
	 * As above, plus the **区间叠加层**的载荷（notes/291）：{@code [轨hex, 区间id, 色号, 方向x‰, 方向z‰, 弧起cm, 弧止cm] × n}。
	 *
	 * <p>数值一律走**整数**（千分位方向 / 厘米弧长）：{@code Double.parseDouble} 在逗号小数点的区域
	 * 设置下会静默解析失败，而弧长算错的表现是"带子画到别的轨上"——那种错查起来极贵。</p>
	 */
	public static void update(Map<String, List<String>> next, Set<String> pending, Set<String> restricted, Map<String, String> lampAspectMap, Map<String, List<String>> lampRailMap, List<String> sectionBandWire) {
		final Map<String, List<String>> copy = new HashMap<>();
		next.forEach((railHex, nexts) -> copy.put(railHex, Collections.unmodifiableList(new java.util.ArrayList<>(nexts))));
		nextRails = Collections.unmodifiableMap(copy);
		pendingEntries = Collections.unmodifiableSet(new HashSet<>(pending));
		restrictedNodes = Collections.unmodifiableSet(new HashSet<>(restricted));
		lampAspects = Collections.unmodifiableMap(new HashMap<>(lampAspectMap));
		final Map<String, List<String>> lampRailsCopy = new HashMap<>();
		lampRailMap.forEach((key, railHexes) -> lampRailsCopy.put(key, Collections.unmodifiableList(new java.util.ArrayList<>(railHexes))));
		lampRails = Collections.unmodifiableMap(lampRailsCopy);
		sectionBands = parseSectionBands(sectionBandWire);
	}

	/**
	 * 把载荷解成"轨 → 区间带"的索引，键用**规范 hex**（{@link MmtrSectionService#canonicalHex}）。
	 *
	 * <p>规范化必须在**入索引**时做，而不是查的时候做：一根实体轨的 hex 有两种互为逆序的写法，
	 * 引擎发的是规范写法，而客户端手里那条 {@code Rail} 的 hex 可能是逆序的 —— 不做这一步，
	 * 表现是"有些轨有带、有些没有"，且随轨怎么被声明而变。</p>
	 */
	private static Map<String, List<SectionBand>> parseSectionBands(List<String> wire) {
		final Map<String, List<SectionBand>> parsed = new HashMap<>();
		for (int i = 0; i + 6 < wire.size(); i += 7) {
			final String railHex = MmtrSectionService.canonicalHex(wire.get(i));
			final List<SectionBand> list = parsed.computeIfAbsent(railHex, ignored -> new java.util.ArrayList<>());
			list.add(new SectionBand(
				wire.get(i + 1),
				parseInt(wire.get(i + 2), 0),
				parseInt(wire.get(i + 3), 0) / 1000.0,
				parseInt(wire.get(i + 4), 0) / 1000.0,
				parseInt(wire.get(i + 5), 0) / 100.0,
				parseInt(wire.get(i + 6), 0) / 100.0
			));
		}
		final Map<String, List<SectionBand>> frozen = new HashMap<>();
		parsed.forEach((railHex, list) -> frozen.put(railHex, Collections.unmodifiableList(list)));
		return Collections.unmodifiableMap(frozen);
	}

	/** 一条坏字段不该把整张叠加层丢掉：解析不了就退回 {@code fallback}（与网页读接口同一条规矩）。 */
	private static int parseInt(String value, int fallback) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public static void clear() {
		nextRails = Collections.emptyMap();
		pendingEntries = Collections.emptySet();
		restrictedNodes = Collections.emptySet();
		lampAspects = Collections.emptyMap();
		lampRails = Collections.emptyMap();
		sectionBands = Collections.emptyMap();
	}

	/**
	 * 覆盖这根轨的**区间带**（可能多条：双向各一条、咽喉上还叠着同一方向的几条）；没有则空表。
	 *
	 * <p>{@code railHex} 传客户端 {@code Rail.getHexId()} 的原文即可（内部会规范化）。</p>
	 */
	public static List<SectionBand> sectionBands(@Nullable String railHex) {
		if (railHex == null) {
			return Collections.emptyList();
		}
		final List<SectionBand> bands = sectionBands.get(MmtrSectionService.canonicalHex(railHex));
		return bands == null ? Collections.emptyList() : bands;
	}

	/** 载荷里有几条区间带（诊断用）。 */
	public static int sectionBandCount() {
		int count = 0;
		for (final List<SectionBand> bands : sectionBands.values()) {
			count += bands.size();
		}
		return count;
	}

	/** 有几根轨带区间带（诊断用）。 */
	public static int sectionRailCount() {
		return sectionBands.size();
	}

	/**
	 * 这盏灯守的轨（一灯多腿时多条）；引擎没给（或这盏灯没接入闭塞层）时返回空表。
	 *
	 * <p>绑定工具的叠加层用它把"点中的那盏灯守哪几根轨"画到世界里。</p>
	 */
	public static List<String> lampRails(int x, int y, int z) {
		return lampRails.getOrDefault(x + "," + y + "," + z, Collections.emptyList());
	}

	/** 这个位置上的轨 hex 是不是这盏灯守的（叠加层判定用）。 */
	public static boolean lampGuards(int x, int y, int z, @Nullable String railHex) {
		return railHex != null && lampRails(x, y, z).contains(railHex);
	}

	/** 有多少盏灯带着守轨信息（诊断用）。 */
	public static int lampBindingCount() {
		return lampRails.size();
	}

	/**
	 * S4: the aspect the engine's 闭塞区间 v2 model gives the lamp at {@code x,y,z}, or null when the engine
	 * has no v2 section for that lamp - the renderer then keeps its local (v1) chain reading.
	 */
	public static String lampAspect(int x, int y, int z) {
		return lampAspects.get(x + "," + y + "," + z);
	}

	public static int lampAspectCount() {
		return lampAspects.size();
	}

	/** The rails a SET main route runs onto after {@code railHex}; empty when no route covers it. */
	public static List<String> nextRails(@Nullable String railHex) {
		if (railHex == null) {
			return Collections.emptyList();
		}
		final List<String> nexts = nextRails.get(railHex);
		return nexts == null ? Collections.emptyList() : nexts;
	}

	/** Whether {@code railHex} is the entry rail of a route that is still waiting to be set. */
	public static boolean isPendingEntry(@Nullable String railHex) {
		return railHex != null && pendingEntries.contains(railHex);
	}

	/**
	 * 客户端**不再自己数区间**（notes/166 R4）：B3b 那条"引擎把每根被切分轨的弧窗+颜色推给客户端，
	 * 客户端自己数"的通道随 v1 整层删除。现在区间与显示的结论都由引擎给（{@code lamps} /
	 * {@code lampRails}），这里恒空 —— 渲染器于是退回"每根轨一个单位"的保守链。
	 */
	public static List<MmtrSignalChain.Section> sections(@Nullable String railHex) {
		return Collections.emptyList();
	}

	/**
	 * ④: whether the junction node {@code x,y,z} is mirrored as "not cleared" (undecided points or a
	 * fouled clearance zone). A step through such a node reads as occupied, so the client shows the same
	 * danger the engine's motion rules enforce.
	 */
	public static boolean isNodeRestricted(@Nullable String nodeKey) {
		return nodeKey != null && restrictedNodes.contains(nodeKey);
	}

	public static int restrictedNodeCount() {
		return restrictedNodes.size();
	}

	public static int nextRailCount() {
		return nextRails.size();
	}

	public static int pendingEntryCount() {
		return pendingEntries.size();
	}
}

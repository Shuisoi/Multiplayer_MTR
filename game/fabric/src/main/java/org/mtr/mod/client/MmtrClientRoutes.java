package org.mtr.mod.client;

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
 * </ul>
 *
 * <p>Pure display data, replaced wholesale by {@code PacketMmtrRoutes}; empty means "no route
 * set anywhere", which is exactly the pre-A2 free-driving behaviour.</p>
 */
public final class MmtrClientRoutes {

	private static volatile Map<String, List<String>> nextRails = Collections.emptyMap();
	private static volatile Set<String> pendingEntries = Collections.emptySet();
	private static volatile Map<String, List<MmtrSignalChain.Section>> sections = Collections.emptyMap();
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

	private MmtrClientRoutes() {
	}

	/** Replace the mirror (called from the packet handler on the client thread). */
	public static void update(Map<String, List<String>> next, Set<String> pending) {
		update(next, pending, Collections.emptyMap(), Collections.emptySet(), Collections.emptyMap());
	}

	/** Replace the mirror including the B3b section map and the ④ restricted-junction node keys. */
	public static void update(Map<String, List<String>> next, Set<String> pending, Map<String, List<MmtrSignalChain.Section>> sectionMap, Set<String> restricted) {
		update(next, pending, sectionMap, restricted, Collections.emptyMap());
	}

	/**
	 * Replace the mirror including the S4 lamp aspects: the engine computes the 闭塞区间 v2 aspect per LAMP
	 * (what one lamp protects, walked lamp to lamp), so it ships its conclusion instead of the raw walk and
	 * the client renderer looks its own block position up.
	 */
	public static void update(Map<String, List<String>> next, Set<String> pending, Map<String, List<MmtrSignalChain.Section>> sectionMap, Set<String> restricted, Map<String, String> lampAspectMap) {
		update(next, pending, sectionMap, restricted, lampAspectMap, Collections.emptyMap());
	}

	/**
	 * As above, plus every lamp's **守轨**（一灯多腿时多条），供绑定工具的叠加层显示。
	 *
	 * <p>这份关系由引擎算（它持有节点、朝向、人工绑定与区间那一整套），客户端只显示。</p>
	 */
	public static void update(Map<String, List<String>> next, Set<String> pending, Map<String, List<MmtrSignalChain.Section>> sectionMap, Set<String> restricted, Map<String, String> lampAspectMap, Map<String, List<String>> lampRailMap) {
		final Map<String, List<String>> copy = new HashMap<>();
		next.forEach((railHex, nexts) -> copy.put(railHex, Collections.unmodifiableList(new java.util.ArrayList<>(nexts))));
		nextRails = Collections.unmodifiableMap(copy);
		pendingEntries = Collections.unmodifiableSet(new HashSet<>(pending));
		final Map<String, List<MmtrSignalChain.Section>> sectionCopy = new HashMap<>();
		sectionMap.forEach((railHex, railSections) -> sectionCopy.put(railHex, Collections.unmodifiableList(new java.util.ArrayList<>(railSections))));
		sections = Collections.unmodifiableMap(sectionCopy);
		restrictedNodes = Collections.unmodifiableSet(new HashSet<>(restricted));
		lampAspects = Collections.unmodifiableMap(new HashMap<>(lampAspectMap));
		final Map<String, List<String>> lampRailsCopy = new HashMap<>();
		lampRailMap.forEach((key, railHexes) -> lampRailsCopy.put(key, Collections.unmodifiableList(new java.util.ArrayList<>(railHexes))));
		lampRails = Collections.unmodifiableMap(lampRailsCopy);
	}

	public static void clear() {
		nextRails = Collections.emptyMap();
		pendingEntries = Collections.emptySet();
		sections = Collections.emptyMap();
		restrictedNodes = Collections.emptySet();
		lampAspects = Collections.emptyMap();
		lampRails = Collections.emptyMap();
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
	 * B3b: the sections of {@code railHex} as mirrored by the engine; empty when the rail is not split
	 * (one section = one rail, so the renderer falls back to the per-rail occupancy test).
	 */
	public static List<MmtrSignalChain.Section> sections(@Nullable String railHex) {
		if (railHex == null) {
			return Collections.emptyList();
		}
		final List<MmtrSignalChain.Section> railSections = sections.get(railHex);
		return railSections == null ? Collections.emptyList() : railSections;
	}

	public static int sectionRailCount() {
		return sections.size();
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

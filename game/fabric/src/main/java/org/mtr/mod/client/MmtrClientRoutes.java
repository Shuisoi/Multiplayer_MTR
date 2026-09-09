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

	private MmtrClientRoutes() {
	}

	/** Replace the mirror (called from the packet handler on the client thread). */
	public static void update(Map<String, List<String>> next, Set<String> pending) {
		update(next, pending, Collections.emptyMap(), Collections.emptySet());
	}

	/** Replace the mirror including the B3b section map and the ④ restricted-junction node keys. */
	public static void update(Map<String, List<String>> next, Set<String> pending, Map<String, List<MmtrSignalChain.Section>> sectionMap, Set<String> restricted) {
		final Map<String, List<String>> copy = new HashMap<>();
		next.forEach((railHex, nexts) -> copy.put(railHex, Collections.unmodifiableList(new java.util.ArrayList<>(nexts))));
		nextRails = Collections.unmodifiableMap(copy);
		pendingEntries = Collections.unmodifiableSet(new HashSet<>(pending));
		final Map<String, List<MmtrSignalChain.Section>> sectionCopy = new HashMap<>();
		sectionMap.forEach((railHex, railSections) -> sectionCopy.put(railHex, Collections.unmodifiableList(new java.util.ArrayList<>(railSections))));
		sections = Collections.unmodifiableMap(sectionCopy);
		restrictedNodes = Collections.unmodifiableSet(new HashSet<>(restricted));
	}

	public static void clear() {
		nextRails = Collections.emptyMap();
		pendingEntries = Collections.emptySet();
		sections = Collections.emptyMap();
		restrictedNodes = Collections.emptySet();
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

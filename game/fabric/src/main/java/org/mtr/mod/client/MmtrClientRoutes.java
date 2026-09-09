package org.mtr.mod.client;

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
 *       danger: the movement is waiting outside its signal.</li>
 * </ul>
 *
 * <p>Pure display data, replaced wholesale by {@code PacketMmtrRoutes}; empty means "no route
 * set anywhere", which is exactly the pre-A2 free-driving behaviour.</p>
 */
public final class MmtrClientRoutes {

	private static volatile Map<String, List<String>> nextRails = Collections.emptyMap();
	private static volatile Set<String> pendingEntries = Collections.emptySet();

	private MmtrClientRoutes() {
	}

	/** Replace the mirror (called from the packet handler on the client thread). */
	public static void update(Map<String, List<String>> next, Set<String> pending) {
		final Map<String, List<String>> copy = new HashMap<>();
		next.forEach((railHex, nexts) -> copy.put(railHex, Collections.unmodifiableList(new java.util.ArrayList<>(nexts))));
		nextRails = Collections.unmodifiableMap(copy);
		pendingEntries = Collections.unmodifiableSet(new HashSet<>(pending));
	}

	public static void clear() {
		nextRails = Collections.emptyMap();
		pendingEntries = Collections.emptySet();
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

	public static int nextRailCount() {
		return nextRails.size();
	}

	public static int pendingEntryCount() {
		return pendingEntries.size();
	}
}

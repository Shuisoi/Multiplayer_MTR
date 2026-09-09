package org.mtr.mod.client;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * MMTR client-side route mirror (A2): the engine pushes the two derived views the signal renderer
 * needs, so the client never re-implements interlocking logic.
 *
 * <ul>
 *   <li>{@link #nextRail(String)} — a SET main route locks one path through a rail: the renderer
 *       follows only that rail at the fork instead of walking every branch (the old conservative
 *       rule made a signal look worse than the route it protects);</li>
 *   <li>{@link #isPendingEntry(String)} — a rail that is the entry of a not-yet-set route shows
 *       danger: the movement is waiting outside its signal.</li>
 * </ul>
 *
 * <p>Pure display data, replaced wholesale by {@code PacketMmtrRoutes}; empty means "no route
 * set anywhere", which is exactly the pre-A2 free-driving behaviour.</p>
 */
public final class MmtrClientRoutes {

	private static volatile Map<String, String> nextRails = Collections.emptyMap();
	private static volatile Set<String> pendingEntries = Collections.emptySet();

	private MmtrClientRoutes() {
	}

	/** Replace the mirror (called from the packet handler on the client thread). */
	public static void update(Map<String, String> next, Set<String> pending) {
		nextRails = Collections.unmodifiableMap(new HashMap<>(next));
		pendingEntries = Collections.unmodifiableSet(new HashSet<>(pending));
	}

	public static void clear() {
		nextRails = Collections.emptyMap();
		pendingEntries = Collections.emptySet();
	}

	/** The rail a SET main route runs onto after {@code railHex}, or null (no route / route ends). */
	public static @Nullable String nextRail(@Nullable String railHex) {
		return railHex == null ? null : nextRails.get(railHex);
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

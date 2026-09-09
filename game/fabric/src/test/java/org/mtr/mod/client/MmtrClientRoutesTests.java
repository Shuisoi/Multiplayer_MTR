package org.mtr.mod.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 client mirror store: the contract the in-game signal renderer relies on. The value is a LIST per
 * rail because a route may traverse the same rail twice (牵出—推进 / 尽头换向) - the renderer picks the
 * candidate that continues from the node it is leaving, exactly like the engine's MmtrSignalAspect.
 */
public final class MmtrClientRoutesTests {

	@AfterEach
	public void clearMirror() {
		MmtrClientRoutes.clear();
	}

	@Test
	public void emptyMirrorMeansNoNarrowing() {
		MmtrClientRoutes.clear();
		assertTrue(MmtrClientRoutes.nextRails("railA").isEmpty(), "no route -> no candidate");
		assertTrue(MmtrClientRoutes.nextRails(null).isEmpty(), "null lookups are safe");
		assertFalse(MmtrClientRoutes.isPendingEntry("railA"));
		assertFalse(MmtrClientRoutes.isPendingEntry(null));
		assertEquals(0, MmtrClientRoutes.nextRailCount());
		assertEquals(0, MmtrClientRoutes.pendingEntryCount());
	}

	@Test
	public void aDoubledRailKeepsEveryCandidateInRouteOrder() {
		final Map<String, List<String>> next = new HashMap<>();
		next.put("railA", List.of("railB"));
		next.put("railB", List.of("railC", "railA"));
		MmtrClientRoutes.update(next, Set.of("railA"));

		assertEquals(List.of("railB"), MmtrClientRoutes.nextRails("railA"));
		assertEquals(List.of("railC", "railA"), MmtrClientRoutes.nextRails("railB"),
			"both occurrences of the doubled rail are available, outbound first");
		assertTrue(MmtrClientRoutes.isPendingEntry("railA"), "the pending entry rail is flagged");
		assertFalse(MmtrClientRoutes.isPendingEntry("railB"));
		assertEquals(2, MmtrClientRoutes.nextRailCount());
		assertEquals(1, MmtrClientRoutes.pendingEntryCount());
	}

	@Test
	public void anUpdateReplacesTheWholeMirrorAndIsDefensivelyCopied() {
		final Map<String, List<String>> first = new HashMap<>();
		first.put("railA", List.of("railB"));
		final Set<String> pending = new java.util.HashSet<>();
		pending.add("railA");
		MmtrClientRoutes.update(first, pending);

		// Mutating the caller's collections afterwards must not leak into the mirror.
		first.put("railX", List.of("railY"));
		pending.clear();
		assertTrue(MmtrClientRoutes.nextRails("railX").isEmpty(), "the store copied the map");
		assertTrue(MmtrClientRoutes.isPendingEntry("railA"), "the store copied the pending set");

		MmtrClientRoutes.update(Map.of("railM", List.of("railN")), Set.of());
		assertTrue(MmtrClientRoutes.nextRails("railA").isEmpty(), "a new mirror replaces the previous one");
		assertEquals(List.of("railN"), MmtrClientRoutes.nextRails("railM"));
	}
}

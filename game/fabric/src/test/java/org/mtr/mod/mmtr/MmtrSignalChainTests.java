package org.mtr.mod.mmtr;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A2 client-side signal chain: the renderer's rule, tested without Minecraft. It must agree with the
 * engine's {@code MmtrSignalAspect}: occupancy chain as the base, a mirrored locked path narrows the
 * fork (per direction, so a rail traversed twice keeps both candidates), travel direction only.
 *
 * <p>Network: entry E(-20..0) -&gt; N(0) -&gt; {S(0..60) | D(0 -&gt; 60,+20)} -&gt; S2(60..120).</p>
 */
public final class MmtrSignalChainTests {

	private static final Position A = new Position(-20, 0, 0);
	private static final Position N = new Position(0, 0, 0);
	private static final Position M = new Position(60, 0, 0);
	private static final Position C = new Position(60, 0, 20);
	private static final Position B = new Position(120, 0, 0);

	private static final String E = "E";
	private static final String S = "S";
	private static final String D = "D";
	private static final String S2 = "S2";

	/** node -> (rail hex -> that rail's other endpoint). */
	private static final class Graph implements MmtrSignalChain.RailGraph {
		private final Map<Position, Map<String, Position>> ends = new HashMap<>();

		private void rail(String hex, Position p1, Position p2) {
			ends.computeIfAbsent(p1, key -> new HashMap<>()).put(hex, p2);
			ends.computeIfAbsent(p2, key -> new HashMap<>()).put(hex, p1);
		}

		@Override
		public Position farEnd(Position node, String railHex) {
			return ends.getOrDefault(node, Map.of()).get(railHex);
		}

		@Override
		public List<MmtrSignalChain.RailEnd> otherRailsAt(Position node, String railHex) {
			final List<MmtrSignalChain.RailEnd> out = new ArrayList<>();
			ends.getOrDefault(node, Map.of()).forEach((hex, far) -> {
				if (!hex.equals(railHex)) {
					out.add(new MmtrSignalChain.RailEnd(hex, far));
				}
			});
			return out;
		}
	}

	private static Graph graph() {
		final Graph graph = new Graph();
		graph.rail(E, A, N);
		graph.rail(S, N, M);
		graph.rail(D, N, C);
		graph.rail(S2, M, B);
		return graph;
	}

	private static int depth(Position startNode, Graph graph, String protectedHex, java.util.Set<String> blocked, Map<String, List<String>> mirror) {
		return MmtrSignalChain.depth(startNode, List.of(protectedHex), graph, blocked::contains, hex -> mirror.getOrDefault(hex, List.of()), 3);
	}

	@Test
	public void occupancyChainGivesRedSingleAndDoubleYellow() {
		assertEquals(0, depth(A, graph(), E, java.util.Set.of(), Map.of()), "clear line is green");
		assertEquals(1, depth(A, graph(), E, java.util.Set.of(E), Map.of()), "the protected rail itself is red");
		assertEquals(2, depth(A, graph(), E, java.util.Set.of(S), Map.of()), "one rail beyond is single yellow");
		assertEquals(3, depth(A, graph(), E, java.util.Set.of(S2), Map.of()), "two rails beyond is double yellow");
	}

	@Test
	public void withoutALockedPathEveryForkBranchCounts() {
		assertEquals(2, depth(A, graph(), E, java.util.Set.of(D), Map.of()),
			"the diverging branch is seen from the entry signal (conservative rule)");
	}

	@Test
	public void aLockedPathNarrowsTheForkToItsOwnRail() {
		final Map<String, List<String>> mirror = new HashMap<>();
		mirror.put(E, List.of(S));
		mirror.put(S, List.of(S2));
		assertEquals(0, depth(A, graph(), E, java.util.Set.of(D), mirror),
			"the locked path is the straight rail: the occupied diverging branch no longer affects this signal");
		assertEquals(2, depth(A, graph(), E, java.util.Set.of(S), mirror), "the locked path's own rail still counts");
	}

	@Test
	public void aDoubledRailKeepsBothCandidatesAndPicksTheRightDirection() {
		// Route E, S, S2, S, E: rail S is traversed out and back, so the mirror lists both nexts.
		final Map<String, List<String>> mirror = new HashMap<>();
		mirror.put(S, List.of(S2, E));
		// Walking OUTBOUND over S (entered at N, leaving at M): the candidate S2 continues from M.
		assertEquals(0, MmtrSignalChain.depth(N, List.of(S), graph(), java.util.Set.of(D)::contains, hex -> mirror.getOrDefault(hex, List.of()), 3),
			"outbound: S2 is the locked continuation, the diverging branch is ignored");
		// Walking BACK over S (entered at M, leaving at N): S2 does not continue from N, E does.
		assertEquals(0, MmtrSignalChain.depth(M, List.of(S), graph(), java.util.Set.of(D)::contains, hex -> mirror.getOrDefault(hex, List.of()), 3),
			"return: the candidate list is filtered by the node being left, so E is followed");
		assertEquals(1, MmtrSignalChain.depth(M, List.of(S), graph(), java.util.Set.of(S)::contains, hex -> mirror.getOrDefault(hex, List.of()), 3),
			"a blocked protected rail is still red");
	}

	@Test
	public void anUnknownRailEndsTheWalk() {
		assertEquals(0, MmtrSignalChain.depth(N, List.of("missing"), graph(), hex -> false, hex -> List.of(), 3),
			"an unknown rail is clear and has no continuation");
		assertEquals(1, MmtrSignalChain.depth(N, List.of("missing"), graph(), hex -> true, hex -> List.of(), 3),
			"a blocked protected rail is red even when the graph cannot continue from it");
	}
}

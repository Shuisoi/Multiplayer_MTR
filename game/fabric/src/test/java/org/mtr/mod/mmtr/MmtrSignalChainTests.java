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
		private final Map<String, Position[]> railEnds = new HashMap<>();

		private void rail(String hex, Position p1, Position p2) {
			ends.computeIfAbsent(p1, key -> new HashMap<>()).put(hex, p2);
			ends.computeIfAbsent(p2, key -> new HashMap<>()).put(hex, p1);
			railEnds.put(hex, p1.compareTo(p2) <= 0 ? new Position[]{p1, p2} : new Position[]{p2, p1});
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

		@Override
		public double entryArc(Position node, String railHex) {
			final Position[] pair = railEnds.get(railHex);
			if (pair == null) {
				return 0;
			}
			return pair[0].equals(node) ? 0 : length(railHex);
		}

		@Override
		public double railLength(Position node, String railHex) {
			return length(railHex);
		}

		private double length(String railHex) {
			final Position[] pair = railEnds.get(railHex);
			return pair == null ? 0 : Math.hypot(pair[1].getX() - pair[0].getX(), pair[1].getZ() - pair[0].getZ());
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

	/**
	 * B3b: with mirrored sections the walk counts SECTIONS. A rail split by a wayside signal is two
	 * steps, so a train standing in the far section leaves the signal protecting the near one at a
	 * caution - the client now agrees with the engine (and with B2, which stops S1 at that signal).
	 */
	@Test
	public void aSplitRailIsCountedInSections() {
		final Graph g = graph();
		final Map<String, List<MmtrSignalChain.Section>> sections = new HashMap<>();
		sections.put(S, List.of(new MmtrSignalChain.Section(0, 30, 11L), new MmtrSignalChain.Section(30, 60, 22L)));
		final java.util.Set<Long> blockedColors = new java.util.HashSet<>();
		final java.util.function.BiPredicate<String, Long> sectionBlocked = (hex, color) -> hex.equals(S) && blockedColors.contains(color);
		final java.util.function.Predicate<String> railBlocked = hex -> hex.equals(S) && !blockedColors.isEmpty();

		assertEquals(0, MmtrSignalChain.depth(N, List.of(S), g, hex -> sections.getOrDefault(hex, List.of()), sectionBlocked, railBlocked, hex -> List.of(), 3),
			"clear line is green");

		blockedColors.add(22L);
		assertEquals(2, MmtrSignalChain.depth(N, List.of(S), g, hex -> sections.getOrDefault(hex, List.of()), sectionBlocked, railBlocked, hex -> List.of(), 3),
			"B3b: the occupied FAR section is one section beyond the protected near one (single yellow)");

		blockedColors.add(11L);
		assertEquals(1, MmtrSignalChain.depth(N, List.of(S), g, hex -> sections.getOrDefault(hex, List.of()), sectionBlocked, railBlocked, hex -> List.of(), 3),
			"the protected (near) section occupied is red");

		// A blocked colour that belongs to NO mirrored section (a legacy/manual block) closes the rail.
		blockedColors.clear();
		blockedColors.add(99L);
		assertEquals(1, MmtrSignalChain.depth(N, List.of(S), g, hex -> sections.getOrDefault(hex, List.of()), sectionBlocked, railBlocked, hex -> List.of(), 3),
			"an unknown blocked colour conservatively closes the whole rail");
	}

	/**
	 * ④ 显示层: a junction the engine mirrored as "not cleared" (undecided points or a fouled clearance
	 * zone) makes a step through that node read as occupied, so the client shows the danger the motion
	 * rules (①/②/③) enforce - instead of a green light in front of a train that is being held.
	 */
	@Test
	public void aRestrictedJunctionCountsAsOccupied() {
		final Graph g = graph();
		final java.util.function.Predicate<String> noneRestricted = key -> false;
		final java.util.function.Predicate<String> nodeNRestricted = key -> key.equals("0,0,0");

		// Walking from A into E the signal's protected rail's far node is N: not cleared -> danger.
		assertEquals(1, MmtrSignalChain.depth(A, List.of(E), g, hex -> List.of(), (hex, color) -> false, hex -> false, nodeNRestricted, hex -> List.of(), 3),
			"the junction at N is not cleared: the signal protecting E shows danger");

		// Once the junction is decided the same walk is green.
		assertEquals(0, MmtrSignalChain.depth(A, List.of(E), g, hex -> List.of(), (hex, color) -> false, hex -> false, noneRestricted, hex -> List.of(), 3),
			"a cleared junction leaves the signal green");
	}
}

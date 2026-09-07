package org.mtr.core.mmtr.line;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;

/**
 * MMTR automatic line detection (线路自动识别): the MTR route model is gone from this fork (trains
 * are mission/job driven), so the web management console needs its own deterministic "lines"
 * derived straight from the rail graph.
 *
 * <p>Model: at every node the rails pair up into continuations - a rail that arrives at a node
 * continues onto its matching partner rail, and a chain of continuations is one line:
 * <ul>
 *   <li>exactly one other rail at the node (pass-through / corner): always a continuation
 *       (a plain bend does not split a line);</li>
 *   <li>two or more other rails (a junction): the continuation is the other rail whose direction
 *       is straightest THROUGH (cos >= 0.9, matching the turnout STRAIGHT threshold); diverging
 *       legs start their own lines;</li>
 *   <li>no other rail: end of line.</li>
 * </ul>
 * Every rail belongs to exactly one line (each pairing is unique), so the partition is precise and
 * stable under future very complex networks: crossings keep their through strokes apart, mainlines
 * stay continuous through junctions, yards/leads that diverge become their own lines, and closed
 * loops chain into one line.</p>
 */
public final class MmtrLineDetector {

	public static final class MmtrLine {
		private String id;
		private String name;
		/** Rails of this line in chain order (each hex exactly once). */
		public final ObjectArrayList<String> rails = new ObjectArrayList<>();
		public double lengthM;

		public String id() {
			return id;
		}

		public String name() {
			return name;
		}

		MmtrLine(String id, ObjectArrayList<String> rails, double lengthM) {
			this.id = id;
			this.name = id;
			this.rails.addAll(rails);
			this.lengthM = lengthM;
		}

		void rename(String id) {
			this.id = id;
			this.name = id;
		}
	}

	private static final class Edge {
		final Rail rail;
		final Position a;
		final Position b;
		final double length;

		Edge(Rail rail, Position a, Position b) {
			this.rail = rail;
			this.a = a;
			this.b = b;
			this.length = rail.railMath.getLength();
		}

		Position other(Position node) {
			return node.equals(a) ? b : a;
		}
	}

	private static final double CONTINUATION_COS = 0.9;

	private MmtrLineDetector() {
	}

	/** Detect the automatic lines of the given rail graph (deterministic; no caching here). */
	public static ObjectArrayList<MmtrLine> detect(org.mtr.core.data.Data data) {
		final Object2ObjectOpenHashMap<Position, Object2ObjectOpenHashMap<Position, Rail>> positionsToRail = data.positionsToRail;
		final Object2ObjectOpenHashMap<String, Edge> edgesByHex = new Object2ObjectOpenHashMap<>();
		final Object2ObjectOpenHashMap<Position, ObjectArrayList<Edge>> adjacency = new Object2ObjectOpenHashMap<>();
		positionsToRail.forEach((node, neighbours) -> neighbours.forEach((other, rail) -> {
			if (edgesByHex.containsKey(rail.getHexId())) {
				return; // each rail is met at both endpoints; build it once
			}
			final Edge edge = new Edge(rail, node, other);
			edgesByHex.put(rail.getHexId(), edge);
			adjacency.computeIfAbsent(node, key -> new ObjectArrayList<>()).add(edge);
			adjacency.computeIfAbsent(other, key -> new ObjectArrayList<>()).add(edge);
		}));

		// Chain every rail through its node pairings, starting from any unclaimed rail.
		final ObjectArrayList<MmtrLine> lines = new ObjectArrayList<>();
		final java.util.Set<String> claimed = new java.util.HashSet<>();
		for (final Edge start : edgesByHex.values()) {
			if (claimed.contains(start.rail.getHexId())) {
				continue;
			}
			final ObjectArrayList<Edge> chain = new ObjectArrayList<>();
			chain.add(start);
			claimed.add(start.rail.getHexId());
			grow(chain, start, start.b, claimed, adjacency);
			grow(chain, start, start.a, claimed, adjacency);
			final ObjectArrayList<String> hexes = new ObjectArrayList<>();
			double length = 0;
			for (final Edge edge : chain) {
				hexes.add(edge.rail.getHexId());
				length += edge.length;
			}
			lines.add(new MmtrLine(String.format("L%02d", lines.size() + 1), hexes, length));
		}
		// Longest lines first get the lowest numbers (stable naming).
		lines.sort((l1, l2) -> Double.compare(l2.lengthM, l1.lengthM));
		for (int i = 0; i < lines.size(); i++) {
			lines.get(i).rename(String.format("L%02d", i + 1));
		}
		return lines;
	}

	/** Extend {@code chain} from {@code current} moving toward its far node {@code toward}. */
	private static void grow(ObjectArrayList<Edge> chain, Edge current, Position toward, java.util.Set<String> claimed, Object2ObjectOpenHashMap<Position, ObjectArrayList<Edge>> adjacency) {
		Position at = toward;
		while (true) {
			final Position from = current.other(at);
			final ObjectArrayList<Edge> candidates = new ObjectArrayList<>();
			final ObjectArrayList<Edge> all = adjacency.get(at);
			if (all != null) {
				for (final Edge edge : all) {
					if (edge != current && !claimed.contains(edge.rail.getHexId())) {
						candidates.add(edge);
					}
				}
			}
			if (candidates.isEmpty()) {
				return; // dead end (or loop closed back onto the chain start)
			}
			final Edge next = straightestThrough(current, from, at, candidates);
			if (next == null) {
				return; // diverging junction: this chain ends, the diverging leg gets its own line
			}
			chain.add(next);
			claimed.add(next.rail.getHexId());
			final Position nextToward = next.other(at);
			if (nextToward == null) {
				return;
			}
			current = next;
			at = nextToward;
		}
	}

	/**
	 * The straightest-through partner of {@code current} at {@code node} (arriving from
	 * {@code from}): unique best cos &gt;= 0.9 when the node offers two or more continuations.
	 */
	private static Edge straightestThrough(Edge current, Position from, Position node, ObjectArrayList<Edge> candidates) {
		if (candidates.size() == 1) {
			return candidates.get(0); // pass-through / plain bend: always continues
		}
		Edge best = null;
		double bestCos = -1.1;
		for (final Edge candidate : candidates) {
			final Position to = candidate.other(node);
			final double ax = node.getX() - from.getX();
			final double az = node.getZ() - from.getZ();
			final double bx = to.getX() - node.getX();
			final double bz = to.getZ() - node.getZ();
			final double la = Math.sqrt(ax * ax + az * az);
			final double lb = Math.sqrt(bx * bx + bz * bz);
			final double cos = la == 0 || lb == 0 ? -2 : (ax * bx + az * bz) / (la * lb);
			if (cos > bestCos + 1e-12 || Math.abs(cos - bestCos) <= 1e-12 && best != null && candidate.rail.getHexId().compareTo(best.rail.getHexId()) < 0) {
				bestCos = cos;
				best = candidate;
			}
		}
		return bestCos >= CONTINUATION_COS ? best : null;
	}
}

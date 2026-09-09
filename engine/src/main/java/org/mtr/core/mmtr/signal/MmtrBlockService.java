package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;

/**
 * 闭塞区间服务 (block sections, direction A): turns "one rail = one block" into sections delimited by
 * the wayside signals that read the rail. A rail's section boundaries are:
 *
 * <ol>
 *   <li>its two end nodes (always - junction/platform/siding geometry stays a boundary);</li>
 *   <li>every registered signal whose {@code target} is this rail, projected onto the rail curve and
 *       kept when it sits on the rail (within {@link #SIGNAL_BIND_TOLERANCE_M}) and away from the end
 *       nodes (within {@link #NODE_SNAP_M}).</li>
 * </ol>
 *
 * <p>Per the user's ruling (2026-09-09): sections are cut by SIGNALS only - a long rail with no
 * signal stays one section, no length-based virtual boundaries. A signal placed right at a node
 * projects onto that node and therefore does not split the rail (the projection is a superset of the
 * node-binding reading, so both readings agree).</p>
 *
 * <p>Coordinates are the rail's <strong>ordered-position-1 arc space</strong>, the same space the
 * shared occupancy trees use: arc 0 = the endpoint that sorts first by {@code Position.compareTo},
 * arc = rail length at the other end. That makes "project occupancy onto a section" a plain interval
 * intersection, with no conversion at the call sites.</p>
 *
 * <p>This class is pure data: it never moves, reserves or stops anything. B1 ships it unwired.</p>
 */
public final class MmtrBlockService {

	/** A signal farther than this from the rail it targets is ignored (loose/overhanging placement). */
	public static final double SIGNAL_BIND_TOLERANCE_M = 8.0;
	/** A projected signal closer than this to an end node is treated as sitting at that node. */
	public static final double NODE_SNAP_M = 2.0;
	/** Curve sampling step for projecting a signal block onto a rail, m. */
	private static final double PROJECTION_STEP_M = 0.25;

	public enum BoundaryKind {
		/** A rail end node. */
		NODE,
		/** A signal that reads this rail, projected onto it. */
		SIGNAL
	}

	/** One section boundary on a rail, in ordered-position-1 arc space. */
	public static final class Boundary {
		public final double arcFromOrdered1M;
		public final BoundaryKind kind;
		/** "node" or the signal registry key {@code x,y,z}. */
		public final String sourceKey;

		private Boundary(double arcFromOrdered1M, BoundaryKind kind, String sourceKey) {
			this.arcFromOrdered1M = arcFromOrdered1M;
			this.kind = kind;
			this.sourceKey = sourceKey;
		}

		@Override
		public String toString() {
			return kind + "@" + Math.round(arcFromOrdered1M * 100.0) / 100.0 + "m(" + sourceKey + ")";
		}
	}

	/** One block section: an arc interval of one rail. */
	public static final class Block {
		public final String railHex;
		public final double arcFromM;
		public final double arcToM;
		public final String id;

		private Block(String railHex, double arcFromM, double arcToM) {
			this.railHex = railHex;
			this.arcFromM = arcFromM;
			this.arcToM = arcToM;
			this.id = railHex + "@" + Math.round(arcFromM * 100.0) / 100.0 + "-" + Math.round(arcToM * 100.0) / 100.0;
		}

		public double lengthM() {
			return arcToM - arcFromM;
		}

		/** Whether {@code arcFromOrdered1M} lies inside this section (half-open: [from, to)). */
		public boolean containsArc(double arcFromOrdered1M) {
			return arcFromOrdered1M >= arcFromM - 1e-9 && arcFromOrdered1M < arcToM - 1e-9;
		}

		@Override
		public String toString() {
			return id;
		}
	}

	private final Simulator simulator;
	private final Object2ObjectOpenHashMap<String, ObjectArrayList<Boundary>> boundariesByRail = new Object2ObjectOpenHashMap<>();
	private final Object2ObjectOpenHashMap<String, ObjectArrayList<Block>> blocksByRail = new Object2ObjectOpenHashMap<>();
	private String signature = "";

	public MmtrBlockService(Simulator simulator) {
		this.simulator = simulator;
	}

	/** Rebuild when the rails or the signal registry changed (rails-signature + signals-signature). */
	public void refresh() {
		final String current = signature();
		if (current.equals(signature) && !blocksByRail.isEmpty()) {
			return;
		}
		signature = current;
		boundariesByRail.clear();
		blocksByRail.clear();
		final Object2ObjectOpenHashMap<String, Rail> railByHex = new Object2ObjectOpenHashMap<>();
		simulator.rails.forEach(rail -> railByHex.put(rail.getHexId(), rail));

		// Resolve every signal to the rail it cuts: an explicit target wins (覆盖绑定), otherwise the
		// nearest rail within tolerance - `signals scan` registers AUTO entries with no target, and
		// without this inference a scanned wayside light would never cut anything.
		final Object2ObjectOpenHashMap<String, ObjectArrayList<Boundary>> signalBoundaries = new Object2ObjectOpenHashMap<>();
		for (final SignalEntry entry : simulator.mmtrSignals.signals.values()) {
			final Rail rail = resolveRail(entry, railByHex);
			if (rail == null) {
				continue;
			}
			final Double arc = projectArc(rail, entry.x + 0.5, entry.y + 0.5, entry.z + 0.5);
			if (arc == null) {
				continue;
			}
			final double length = rail.railMath.getLength();
			if (arc <= NODE_SNAP_M || arc >= length - NODE_SNAP_M) {
				continue; // sitting at an end node: the node is already a boundary
			}
			signalBoundaries.computeIfAbsent(rail.getHexId(), key -> new ObjectArrayList<>())
				.add(new Boundary(arc, BoundaryKind.SIGNAL, MmtrSignalRegistry.key(entry.x, entry.y, entry.z)));
		}
		simulator.rails.forEach(rail -> buildRail(rail, signalBoundaries.get(rail.getHexId())));
	}

	/**
	 * The rail a signal cuts: its bound {@code target} when set, otherwise the nearest rail within
	 * {@link #SIGNAL_BIND_TOLERANCE_M} (AUTO placement inference). This overload resolves against the
	 * simulator's current rails and is the operator-facing entry point ({@link MmtrBlockReport}).
	 */
	public @Nullable Rail resolveRail(SignalEntry entry) {
		final Object2ObjectOpenHashMap<String, Rail> railByHex = new Object2ObjectOpenHashMap<>();
		simulator.rails.forEach(rail -> railByHex.put(rail.getHexId(), rail));
		return resolveRail(entry, railByHex);
	}

	/**
	 * The rail a signal cuts: its bound {@code target} when set, otherwise the nearest rail within
	 * {@link #SIGNAL_BIND_TOLERANCE_M} (AUTO placement inference).
	 */
	private @Nullable Rail resolveRail(SignalEntry entry, Object2ObjectOpenHashMap<String, Rail> railByHex) {
		if (entry.target != null && !entry.target.isEmpty()) {
			final Rail bound = railByHex.get(entry.target);
			if (bound != null) {
				return bound;
			}
			// A stale target (the rail was redrawn) falls through to geometric inference.
		}
		final double x = entry.x + 0.5;
		final double y = entry.y + 0.5;
		final double z = entry.z + 0.5;
		Rail nearest = null;
		double nearestDistanceSq = SIGNAL_BIND_TOLERANCE_M * SIGNAL_BIND_TOLERANCE_M;
		for (final Rail rail : simulator.rails) {
			final Projection projection = project(rail, x, y, z);
			if (projection != null && projection.distanceSq <= nearestDistanceSq) {
				nearestDistanceSq = projection.distanceSq;
				nearest = rail;
			}
		}
		return nearest;
	}

	/** Every section of {@code railHex}, ordered by arc (empty for an unknown rail). */
	public ObjectArrayList<Block> blocksOf(@Nullable String railHex) {
		refresh();
		if (railHex == null || railHex.isEmpty()) {
			return new ObjectArrayList<>();
		}
		final ObjectArrayList<Block> blocks = blocksByRail.get(railHex);
		return blocks == null ? new ObjectArrayList<>() : blocks;
	}

	/** The section containing {@code arcFromOrdered1M} on {@code railHex}, or null. */
	public @Nullable Block blockAt(@Nullable String railHex, double arcFromOrdered1M) {
		for (final Block block : blocksOf(railHex)) {
			if (block.containsArc(arcFromOrdered1M)) {
				return block;
			}
		}
		// The far end of a rail belongs to its last section (half-open intervals).
		final ObjectArrayList<Block> blocks = blocksOf(railHex);
		return blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
	}

	/** The boundaries of {@code railHex} (diagnostics/tests), ordered by arc. */
	public ObjectArrayList<Boundary> boundariesOf(@Nullable String railHex) {
		refresh();
		if (railHex == null || railHex.isEmpty()) {
			return new ObjectArrayList<>();
		}
		final ObjectArrayList<Boundary> boundaries = boundariesByRail.get(railHex);
		return boundaries == null ? new ObjectArrayList<>() : boundaries;
	}

	public int blockCount() {
		refresh();
		final int[] count = {0};
		blocksByRail.values().forEach(blocks -> count[0] += blocks.size());
		return count[0];
	}

	public int railCount() {
		refresh();
		return blocksByRail.size();
	}

	private void buildRail(Rail rail, @Nullable ObjectArrayList<Boundary> signalBoundaries) {
		final String railHex = rail.getHexId();
		final double length = rail.railMath.getLength();
		if (length <= 0) {
			return;
		}
		final ObjectArrayList<Boundary> boundaries = new ObjectArrayList<>();
		boundaries.add(new Boundary(0, BoundaryKind.NODE, "node"));
		boundaries.add(new Boundary(length, BoundaryKind.NODE, "node"));
		if (signalBoundaries != null) {
			for (final Boundary boundary : signalBoundaries) {
				if (hasBoundaryNear(boundaries, boundary.arcFromOrdered1M)) {
					continue; // two lights at the same spot are one boundary
				}
				boundaries.add(boundary);
			}
		}
		boundaries.sort((a, b) -> Double.compare(a.arcFromOrdered1M, b.arcFromOrdered1M));
		boundariesByRail.put(railHex, boundaries);

		final ObjectArrayList<Block> blocks = new ObjectArrayList<>();
		for (int i = 0; i + 1 < boundaries.size(); i++) {
			final double from = boundaries.get(i).arcFromOrdered1M;
			final double to = boundaries.get(i + 1).arcFromOrdered1M;
			if (to - from > 1e-6) {
				blocks.add(new Block(railHex, from, to));
			}
		}
		blocksByRail.put(railHex, blocks);
	}

	private static boolean hasBoundaryNear(ObjectArrayList<Boundary> boundaries, double arc) {
		for (final Boundary boundary : boundaries) {
			if (Math.abs(boundary.arcFromOrdered1M - arc) <= 1e-6) {
				return true;
			}
		}
		return false;
	}

	/** A sampled closest point on a rail curve (arc in ordered-position-1 space). */
	private static final class Projection {
		final double arcFromOrdered1M;
		final double distanceSq;

		Projection(double arcFromOrdered1M, double distanceSq) {
			this.arcFromOrdered1M = arcFromOrdered1M;
			this.distanceSq = distanceSq;
		}
	}

	/**
	 * Closest point on the rail curve to a world position (always returns the best sample, however far
	 * - callers decide whether the distance is acceptable).
	 *
	 * <p>Sampling (0.25 m) rather than a closed-form projection: MTR rails are two-arc curves, and a
	 * signal only needs to land inside the right section, not at millimetre precision.</p>
	 */
	private static @Nullable Projection project(Rail rail, double worldX, double worldY, double worldZ) {
		final double length = rail.railMath.getLength();
		if (length <= 0) {
			return null;
		}
		double bestT = 0;
		double bestDistanceSq = Double.MAX_VALUE;
		for (double t = 0; t <= length + 1e-6; t += PROJECTION_STEP_M) {
			final Vector position = rail.railMath.getPosition(Math.min(t, length), false);
			final double dx = position.x() - worldX;
			final double dy = position.y() - worldY;
			final double dz = position.z() - worldZ;
			final double distanceSq = dx * dx + dy * dy + dz * dz;
			if (distanceSq < bestDistanceSq) {
				bestDistanceSq = distanceSq;
				bestT = Math.min(t, length);
			}
		}
		return new Projection(bestT, bestDistanceSq);
	}

	/**
	 * Project a world position onto the rail curve and return the arc in ordered-position-1 space, or
	 * null when the closest point is farther than {@link #SIGNAL_BIND_TOLERANCE_M}.
	 */
	public static @Nullable Double projectArc(Rail rail, double worldX, double worldY, double worldZ) {
		final Projection projection = project(rail, worldX, worldY, worldZ);
		if (projection == null || projection.distanceSq > SIGNAL_BIND_TOLERANCE_M * SIGNAL_BIND_TOLERANCE_M) {
			return null;
		}
		// No conversion needed: RailMath is always built from the endpoint that sorts first by
		// Position.compareTo, so its arc space already IS the ordered-position-1 space the shared
		// occupancy trees use (Rail#mmtrArcOfEndNode documents the same mapping for nodes).
		return projection.arcFromOrdered1M;
	}

	/**
	 * The arc of an endpoint node on {@code rail} in ordered-position-1 space (0 or the rail length),
	 * or {@code NaN} when {@code node} is not one of the rail's endpoints. Used to find which section a
	 * train enters when it crosses onto the next rail.
	 */
	public static double arcOfNode(Rail rail, @Nullable Position node) {
		return node == null ? Double.NaN : rail.mmtrArcOfEndNode(node);
	}

	private String signature() {
		final StringBuilder out = new StringBuilder();
		final ObjectArrayList<String> railHexes = new ObjectArrayList<>();
		simulator.rails.forEach(rail -> railHexes.add(rail.getHexId()));
		railHexes.sort(null);
		railHexes.forEach(hex -> out.append(hex).append(','));
		out.append('|');
		final ObjectArrayList<String> signalKeys = new ObjectArrayList<>();
		simulator.mmtrSignals.signals.forEach((key, entry) -> signalKeys.add(key + ">" + entry.target));
		signalKeys.sort(null);
		signalKeys.forEach(key -> out.append(key).append(','));
		return out.toString();
	}
}

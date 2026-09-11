package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.VehiclePosition;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.mmtr.signal.MmtrSignalRegistry.SignalEntry;
import org.mtr.core.tool.Vector;

import java.util.Map;

/**
 * 闭塞区间 v2（S1，纯数据）: <strong>灯到灯的跨轨有向区间</strong>.
 *
 * <p>The v1 service ({@link MmtrBlockService}) cut sections <em>inside one rail</em> and treated every
 * rail end node as a boundary. That model cannot represent how signals are actually placed on a real
 * map: a lamp stands next to a node, so its projection arc is 0 or the rail length, and the v1 guard
 * "sitting at an end node -&gt; skip" therefore discarded <strong>every</strong> lamp. The live world
 * ended up partitioned into "one rail = one section" with no lamp involved at all
 * ({@code blocks all}: 134 rails = 134 sections, 0 rails cut by a lamp).</p>
 *
 * <p>v2 restores the real meaning:</p>
 * <blockquote>
 *   A section is what one lamp protects: starting at the lamp, walking in the direction the lamp faces,
 *   up to the next lamp facing the same way (exclusive) - or until the walk cannot continue.
 * </blockquote>
 *
 * <p>Consequences, all deliberate:</p>
 * <ul>
 *   <li>a section <strong>spans rail boundaries</strong>: an ordered list of
 *       {@code (railHex, arcFrom, arcTo)} spans, not a single-rail arc interval;</li>
 *   <li>sections are <strong>directed</strong>: the same arc of the same rail belongs to different
 *       sections in the two travel directions;</li>
 *   <li><strong>only lamps create boundaries</strong> - rail end nodes no longer do.</li>
 * </ul>
 *
 * <p>Arc space is the ordered-position-1 space the shared occupancy trees already use
 * ({@link MmtrBlockService#projectArc} documents the mapping), so occupancy projection and S1 stops can
 * consume these sections without conversion.</p>
 */
public final class MmtrDirectionalBlockService {

	/** How far from a rail a lamp may stand and still be taken as protecting it. */
	public static final double SIGNAL_BIND_TOLERANCE_M = 8.0;

	/** Sampling step for projecting a lamp and for reading a rail's heading. */
	private static final double SAMPLE_STEP_M = 0.25;

	/** Safety caps: a malformed graph must never spin forever. */
	private static final int MAX_RAILS_PER_SECTION = 256;
	private static final double MAX_SECTION_LENGTH_M = 4000;
	/** Two block boundaries closer than this on one rail are the same boundary (sampled arcs wobble). */
	private static final double MIN_BLOCK_PIECE_M = 0.05;
	/** A track-layer node this close to a block boundary is that boundary (its cell starts there). */
	private static final double NODE_OWNER_TOLERANCE_M = 1.0;

	/** One rail's slice of a section, in ordered-position-1 arc space, with its travel direction. */
	public static final class RailSpan {
		public final String railHex;
		public final double arcFromM;
		public final double arcToM;
		/** Unit travel direction (x, z) over this span. */
		public final double headingX;
		public final double headingZ;

		RailSpan(String railHex, double arcFromM, double arcToM, double headingX, double headingZ) {
			this.railHex = railHex;
			this.arcFromM = Math.min(arcFromM, arcToM);
			this.arcToM = Math.max(arcFromM, arcToM);
			this.headingX = headingX;
			this.headingZ = headingZ;
		}

		public double lengthM() {
			return arcToM - arcFromM;
		}

		/** Whether {@code arc} lies in this span (half-open). */
		public boolean containsArc(double arc) {
			return arc >= arcFromM - 1e-6 && arc < arcToM - 1e-6;
		}

		/** Whether a movement heading {@code (x, z)} travels this span the same way. */
		public boolean matchesHeading(double x, double z) {
			return headingX * x + headingZ * z > 0.1;
		}

		@Override
		public String toString() {
			return shortHex(railHex) + "[" + round(arcFromM) + ".." + round(arcToM) + "]";
		}
	}

	/** One directed block section: the movement a single lamp authorises. */
	public static final class Section {
		/** Stable id: the protecting lamp's {@code x,y,z} key (a lamp has one outgoing section). */
		public final String id;
		/** The lamp that starts this section. */
		public final String entrySignalKey;
		/** The lamp that ends it, or empty when the walk ran out (dead end / no further lamp). */
		public @Nullable String exitSignalKey;
		/** Ordered spans, in the direction of travel. */
		public final ObjectArrayList<RailSpan> spans = new ObjectArrayList<>();
		/** Whether the walk stopped because it could not continue rather than at another lamp. */
		public boolean endsAtDeadEnd;

		Section(String id, String entrySignalKey) {
			this.id = id;
			this.entrySignalKey = entrySignalKey;
		}

		public double lengthM() {
			double length = 0;
			for (final RailSpan span : spans) {
				length += span.lengthM();
			}
			return length;
		}

		/** The arc at which this section begins, on the rail it begins on. */
		public double entryArcM() {
			return spans.isEmpty() ? 0 : spans.get(0).arcFromM;
		}

		/** The rail the movement enters this section on. */
		public @Nullable String entryRailHex() {
			return spans.isEmpty() ? null : spans.get(0).railHex;
		}

		@Override
		public String toString() {
			return id + " -> " + (exitSignalKey == null || exitSignalKey.isEmpty() ? "DEAD_END" : exitSignalKey)
				+ " spans=" + spans.size() + " len=" + round(lengthM());
		}
	}
	/** One lamp resolved to the rail it protects and the direction it authorises. */
	public static final class ProtectedRail {
		public final Rail rail;
		public final double arcM;
		public final double headingX;
		public final double headingZ;

		ProtectedRail(Rail rail, double arcM, double headingX, double headingZ) {
			this.rail = rail;
			this.arcM = arcM;
			this.headingX = headingX;
			this.headingZ = headingZ;
		}
	}

	private final Simulator simulator;
	private final Object2ObjectOpenHashMap<String, Rail> railByHex = new Object2ObjectOpenHashMap<>();
	/** Lamp key -> the section it starts. */
	private final Object2ObjectOpenHashMap<String, Section> sectionsBySignal = new Object2ObjectOpenHashMap<>();
	/** Rail hex -> every section covering it (a rail belongs to sections in both directions). */
	private final Object2ObjectOpenHashMap<String, ObjectArrayList<Section>> sectionsByRail = new Object2ObjectOpenHashMap<>();
	/** Section id -> the section that continues it (the exit lamp's section), when the walk ended at a lamp. */
	private final Object2ObjectOpenHashMap<String, Section> followingBySection = new Object2ObjectOpenHashMap<>();
	private String cachedSignature = "";

	public MmtrDirectionalBlockService(Simulator simulator) {
		this.simulator = simulator;
	}

	// ---------------------------------------------------------------- queries

	/** The section a lamp starts, or null when the lamp protects nothing. */
	public @Nullable Section sectionOfSignal(String signalKey) {
		refresh();
		return sectionsBySignal.get(signalKey);
	}

	/** Every section covering {@code railHex} (both directions); empty when unknown. */
	public ObjectArrayList<Section> sectionsOfRail(@Nullable String railHex) {
		refresh();
		if (railHex == null || railHex.isEmpty()) {
			return new ObjectArrayList<>();
		}
		final ObjectArrayList<Section> sections = sectionsByRail.get(railHex);
		return sections == null ? new ObjectArrayList<>() : sections;
	}

	/**
	 * The section containing {@code arcM} of {@code railHex} for a movement heading {@code (headingX,
	 * headingZ)}, or null. The heading filter is what makes this directional: the same point belongs to
	 * different sections in opposite directions.
	 */
	public @Nullable Section sectionAt(@Nullable String railHex, double arcM, double headingX, double headingZ) {
		if (railHex == null || railHex.isEmpty()) {
			return null;
		}
		for (final Section section : sectionsOfRail(railHex)) {
			for (final RailSpan span : section.spans) {
				if (span.railHex.equals(railHex) && span.containsArc(arcM) && span.matchesHeading(headingX, headingZ)) {
					return section;
				}
			}
		}
		return null;
	}

	public int sectionCount() {
		refresh();
		return sectionsBySignal.size();
	}

	/** Whether any directional section covers {@code railHex} (the caller's "is v2 my business here"). */
	public boolean hasSection(@Nullable String railHex) {
		return !sectionsOfRail(railHex).isEmpty();
	}

	/** Every rail hex that carries at least one directional section. */
	public ObjectOpenHashSet<String> railsWithSections() {
		refresh();
		return new ObjectOpenHashSet<>(sectionsByRail.keySet());
	}

	/** How many rails carry at least one directional section (diagnostics). */
	public int railsWithSectionsCount() {
		refresh();
		return sectionsByRail.size();
	}

	/** The section that continues {@code section} in its travel direction, or null at the end of the line. */
	public @Nullable Section following(@Nullable Section section) {
		return section == null ? null : followingBySection.get(section.id);
	}

	/**
	 * 占用投影: whether any vehicle's footprint overlaps {@code section}.
	 *
	 * <p>Section occupancy is a plain interval test per span against the shared occupancy trees - the
	 * span is already an arc window on one rail in the same ordered-position-1 space the trees use, so
	 * no conversion and no write-side change is needed. A section spanning several rails is occupied if
	 * ANY of its spans is.</p>
	 *
	 * @param trees the occupancy trees to test (null = the simulator's live train trees)
	 */
	public boolean isOccupied(Section section, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		return isOccupied(section, trees, 0);
	}

	/**
	 * As above, but ignoring footprints owned by {@code excludeVehicleId} (pass 0 to count every vehicle).
	 *
	 * <p>A vehicle asking about the signal it is about to pass must not read ITS OWN body shadow as the
	 * obstruction: with a shadow whose anchor sits ahead of the head, that paints the block ahead red and
	 * the AWS horn sounds the moment the driver touches the throttle (notes/112 §4).</p>
	 */
	public boolean isOccupied(Section section, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, long excludeVehicleId) {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancyTrees =
			trees == null ? simulator.mmtrOccupancyTrees() : trees;
		if (occupancyTrees == null || occupancyTrees.isEmpty()) {
			return false;
		}
		for (final RailSpan span : section.spans) {
			final Rail rail = railByHex.get(span.railHex);
			if (rail == null || span.lengthM() <= 1e-9) {
				continue;
			}
			final Position[] ordered = rail.mmtrOrderedPositions();
			if (ordered == null || ordered.length < 2) {
				continue;
			}
			for (int i = 0; i < occupancyTrees.size(); i++) {
				final VehiclePosition vehiclePosition = Data.tryGet(occupancyTrees.get(i), ordered[0], ordered[1]);
				if (vehiclePosition != null && vehiclePosition.getClosestOverlap(span.arcFromM, span.arcToM, false, excludeVehicleId) >= 0) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * How far the movement may still run before its section ends, or {@link Double#MAX_VALUE} when it is
	 * not in any section of {@code railHex} for that heading (an unsignalled stretch: nothing to hold at).
	 *
	 * <p>This is the S2 building block for the S1 stop rule ("a movement stops at its section boundary"),
	 * not yet wired: callers decide whether to hold at the boundary or only when the section beyond is
	 * occupied.</p>
	 */
	public double sectionEndAheadM(@Nullable String railHex, double arcM, double headingX, double headingZ) {
		final Section section = sectionAt(railHex, arcM, headingX, headingZ);
		if (section == null) {
			return Double.MAX_VALUE;
		}
		double remaining = 0;
		boolean reached = false;
		for (final RailSpan span : section.spans) {
			if (!reached) {
				if (!span.railHex.equals(railHex) || !span.containsArc(arcM)) {
					continue;
				}
				reached = true;
				// The span is stored low..high; the travel direction decides which end we are heading for.
				remaining += span.matchesHeading(headingX, headingZ) ? span.arcToM - arcM : arcM - span.arcFromM;
				continue;
			}
			remaining += span.lengthM();
		}
		// The arc can sit exactly on the section's far boundary (half-open spans): not in it any more.
		return reached ? Math.max(0, remaining) : Double.MAX_VALUE;
	}

	/**
	 * The section a movement at {@code (railHex, arcM)} heading {@code (headingX, headingZ)} must be
	 * cleared to enter next: its own section while that is clear, otherwise the section beyond it.
	 * Null when the movement is not in a section at all.
	 */
	public @Nullable Section sectionAhead(@Nullable String railHex, double arcM, double headingX, double headingZ, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		final Section current = sectionAt(railHex, arcM, headingX, headingZ);
		if (current == null) {
			return null;
		}
		return isOccupied(current, trees) ? following(current) : current;
	}

	/**
	 * S3: how far ahead (metres) the movement may run before it would ENTER an occupied part of its own
	 * section - or {@link Double#MAX_VALUE} when there is nothing to hold it.
	 *
	 * <p>The rule mirrors v1's structure, only the unit is now the lamp-to-lamp section instead of a
	 * single rail: a movement is held at the checkpoint in front of the blocked stretch it is about to
	 * need. Concretely, walking the section's spans from the one the movement is on:</p>
	 * <ul>
	 *   <li>the span it is on is occupied ahead of it → hold at that occupancy face (that is the
	 *       same-rail rule the caller already applies, so this returns the boundary instead);</li>
	 *   <li>the NEXT span of the section is occupied → hold at the end of the current span, i.e. at the
	 *       lamp/node boundary between them. <strong>This is the case v1 could not express</strong>: the
	 *       boundary is a lamp, which may be several rails ahead of where the movement was stopped
	 *       before;</li>
	 *   <li>nothing occupied → no hold, the movement may run its section out.</li>
	 * </ul>
	 *
	 * @param trees occupancy trees to test (null = the simulator's live train trees)
	 */
	public double sectionBoundaryAheadM(@Nullable String railHex, double arcM, double headingX, double headingZ, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		return sectionBoundaryAheadM(railHex, arcM, headingX, headingZ, trees, 0);
	}

	/**
	 * As above, but <strong>excluding one vehicle's own footprints</strong> ({@code excludeVehicleId}).
	 *
	 * <p>A vehicle's body shadow is stored under its own id, and a train may be asking about a stretch its
	 * own shadow already covers - either because its body is genuinely long or because the shadow's anchor
	 * sits ahead of its head. Counting that as "occupied ahead" makes the train stop at its own feet: with
	 * the stop point at the head, the throttle does nothing and the train can never move far enough to
	 * rewrite the shadow. <strong>Measured on the dev world</strong> (notes/112 §4): a train parked at
	 * offset 5.46 on a 43 m rail wrote its own footprint as [5.5, 37.5), so S1 read a 0.04 m block stop and
	 * the AWS rule read its own shadow as a RED signal ahead. Excluding self is the fix.</p>
	 */
	public double sectionBoundaryAheadM(@Nullable String railHex, double arcM, double headingX, double headingZ, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, long excludeVehicleId) {
		final Section section = sectionAt(railHex, arcM, headingX, headingZ);
		if (section == null) {
			return Double.MAX_VALUE;
		}
		for (int i = 0; i < section.spans.size(); i++) {
			final RailSpan span = section.spans.get(i);
			if (!span.railHex.equals(railHex) || !span.containsArc(arcM)) {
				continue;
			}
			final boolean forward = span.matchesHeading(headingX, headingZ);
			final double toSpanEndM = forward ? span.arcToM - arcM : arcM - span.arcFromM;
			// The stretch of this span the movement still has to cross (from the head to the span's end).
			final double from = forward ? arcM : span.arcFromM;
			final double to = forward ? span.arcToM : arcM;
			if (isSpanOccupied(span.railHex, from, to, trees, excludeVehicleId)) {
				// Someone is inside what we are about to cross: hold where it starts.
				final double toOccupancyM = distanceToOccupancyM(span.railHex, from, to, trees, forward, excludeVehicleId);
				return Math.max(0, toOccupancyM);
			}
			final RailSpan nextSpan = i + 1 < section.spans.size() ? section.spans.get(i + 1) : null;
			if (nextSpan != null && isSpanOccupied(nextSpan.railHex, nextSpan.arcFromM, nextSpan.arcToM, trees, excludeVehicleId)) {
				// The next stretch of our own section is taken: hold at the boundary between them.
				return Math.max(0, toSpanEndM);
			}
			return Double.MAX_VALUE;
		}
		return Double.MAX_VALUE;
	}

	/** Whether any vehicle footprint overlaps the arc window on {@code railHex}. */
	private boolean isSpanOccupied(String railHex, double fromM, double toM, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		return isSpanOccupied(railHex, fromM, toM, trees, 0);
	}

	/**
	 * Whether any footprint OTHER than {@code excludeVehicleId}'s overlaps the arc window (pass 0 to count
	 * every footprint).
	 */
	private boolean isSpanOccupied(String railHex, double fromM, double toM, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, long excludeVehicleId) {
		if (toM - fromM <= 1e-9) {
			return false;
		}
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancyTrees =
			trees == null ? simulator.mmtrOccupancyTrees() : trees;
		if (occupancyTrees == null || occupancyTrees.isEmpty()) {
			return false;
		}
		final Rail rail = railByHex.get(railHex);
		if (rail == null) {
			return false;
		}
		final Position[] ordered = rail.mmtrOrderedPositions();
		if (ordered == null || ordered.length < 2) {
			return false;
		}
		for (int i = 0; i < occupancyTrees.size(); i++) {
			final VehiclePosition vehiclePosition = Data.tryGet(occupancyTrees.get(i), ordered[0], ordered[1]);
			if (vehiclePosition != null && vehiclePosition.getClosestOverlap(fromM, toM, false, excludeVehicleId) >= 0) {
				return true;
			}
		}
		return false;
	}

	/** How far from {@code fromM} the nearest external occupancy inside the window begins. */
	private double distanceToOccupancyM(String railHex, double fromM, double toM, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, boolean forward, long excludeVehicleId) {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancyTrees =
			trees == null ? simulator.mmtrOccupancyTrees() : trees;
		final Rail rail = railByHex.get(railHex);
		if (occupancyTrees == null || rail == null) {
			return 0;
		}
		final Position[] ordered = rail.mmtrOrderedPositions();
		if (ordered == null || ordered.length < 2) {
			return 0;
		}
		double best = Double.MAX_VALUE;
		for (int i = 0; i < occupancyTrees.size(); i++) {
			final VehiclePosition vehiclePosition = Data.tryGet(occupancyTrees.get(i), ordered[0], ordered[1]);
			if (vehiclePosition == null) {
				continue;
			}
			for (final double[] segment : vehiclePosition.segmentsExcluding(excludeVehicleId)) {
				final double start = Math.max(fromM, segment[0]);
				final double end = Math.min(toM, segment[1]);
				if (end - start <= 1e-9) {
					continue;
				}
				best = Math.min(best, forward ? start - fromM : toM - end);
			}
		}
		return best == Double.MAX_VALUE ? 0 : Math.max(0, best);
	}

	/**
	 * S4 (显示层): the section that protects {@code railHex} for a movement entering it from {@code
	 * entryNode} - i.e. the section whose FIRST span starts at that node, which is exactly the movement a
	 * lamp standing there authorises. Null on rails no lamp reaches (the caller then keeps the v1 per-rail
	 * reading).
	 *
	 * <p>The entry node matters: a rail in the middle of a section is protected by it, but the same rail
	 * entering from the other end belongs to the opposite direction's section, which is a different block
	 * and must not be reported as this one.</p>
	 */
	public @Nullable Section sectionProtecting(@Nullable String railHex, @Nullable Position entryNode) {
		if (railHex == null || railHex.isEmpty() || entryNode == null) {
			return null;
		}
		final Rail rail = railByHex.get(railHex);
		if (rail == null) {
			return null;
		}
		final double entryArc = MmtrBlockService.arcOfNode(rail, entryNode);
		if (Double.isNaN(entryArc)) {
			return null;
		}
		for (final Section section : sectionsOfRail(railHex)) {
			for (final RailSpan span : section.spans) {
				if (!span.railHex.equals(railHex)) {
					continue;
				}
				if (Math.abs(span.arcFromM - entryArc) <= 0.5) {
					return section;
				}
				break; // this section covers the rail once; move on to the next candidate section
			}
		}
		return null;
	}

	/**
	 * S4: how far ahead (in SECTIONS, the protected one included) the nearest occupied section sits for a
	 * movement entering {@code railHex} from {@code entryNode}: 1 = the protected section itself, 2 = the
	 * section beyond it, 3 = the one after that, 0 = clear (or no directional section here at all, in
	 * which case the caller falls back to the v1 per-rail chain).
	 *
	 * <p>This is the v2 counterpart of {@code MmtrSignalAspect.chainDepth}: the unit of the walk is the
	 * lamp-to-lamp section instead of a rail, so a train standing three rails ahead inside the same
	 * section now reads as depth 1 (red) rather than as "three blocks away".</p>
	 *
	 * @param restrictedNodes    {@code x,y,z} keys of nodes that cannot be cleared (④: fouled clearance
	 *                           zone or undecided points) - stepping through one counts as occupied
	 * @param maxDepth           chain depth to model (3 = red / single / double yellow / green)
	 */
	public int chainDepth(@Nullable String railHex, @Nullable Position entryNode, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes, int maxDepth) {
		return chainDepth(railHex, entryNode, trees, restrictedNodes, maxDepth, 0);
	}

	/** As above, ignoring {@code excludeVehicleId}'s own footprints (the asking vehicle's body shadow). */
	public int chainDepth(@Nullable String railHex, @Nullable Position entryNode, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes, int maxDepth, long excludeVehicleId) {
		Section section = sectionProtecting(railHex, entryNode);
		if (section == null) {
			return 0;
		}
		for (int depth = 1; depth <= maxDepth; depth++) {
			if (isOccupied(section, trees, excludeVehicleId)) {
				return depth;
			}
			for (final String nodeKey : boundaryNodeKeys(section)) {
				if (restrictedNodes.test(nodeKey)) {
					return depth;
				}
			}
			if (depth == maxDepth) {
				break;
			}
			final Section next = following(section);
			if (next == null) {
				break;
			}
			section = next;
		}
		return 0;
	}

	/**
	 * The {@code x,y,z} keys of the node a section is entered through and the one it leaves through (④:
	 * the caller tests these against its restricted-junction set). Both are rail endpoints, so they key
	 * straight into the same node space the rest of the signal layer uses.
	 */
	public ObjectArrayList<String> boundaryNodeKeys(Section section) {
		final ObjectArrayList<String> keys = new ObjectArrayList<>();
		if (section.spans.isEmpty()) {
			return keys;
		}
		final RailSpan first = section.spans.get(0);
		final RailSpan last = section.spans.get(section.spans.size() - 1);
		final Rail firstRail = railByHex.get(first.railHex);
		final Rail lastRail = railByHex.get(last.railHex);
		if (firstRail != null) {
			// The span runs low..high arc; the movement travels from the arcFrom side when its heading
			// agrees with the increasing-arc direction, else from the arcTo side.
			final boolean travelsUpward = first.matchesHeading(1, 0) || first.matchesHeading(-1, 0)
				? first.headingX * thisRailHeadingX(firstRail, first.arcFromM) + first.headingZ * thisRailHeadingZ(firstRail, first.arcFromM) > 0
				: true;
			final Position entry = nodeAtEndpoint(firstRail, travelsUpward ? first.arcFromM : first.arcToM);
			final Position exitOfFirst = nodeAtEndpoint(firstRail, travelsUpward ? first.arcToM : first.arcFromM);
			if (entry != null) {
				keys.add(MmtrJunctionState.nodeKey(entry));
			}
			if (first == last && exitOfFirst != null) {
				keys.add(MmtrJunctionState.nodeKey(exitOfFirst));
			}
		}
		if (last != first && lastRail != null) {
			final boolean travelsUpward = last.headingX * thisRailHeadingX(lastRail, last.arcFromM) + last.headingZ * thisRailHeadingZ(lastRail, last.arcFromM) > 0;
			final Position exit = nodeAtEndpoint(lastRail, travelsUpward ? last.arcToM : last.arcFromM);
			if (exit != null) {
				keys.add(MmtrJunctionState.nodeKey(exit));
			}
		}
		return keys;
	}

	private static double thisRailHeadingX(Rail rail, double arcM) {
		return headingAt(rail, arcM)[0];
	}

	private static double thisRailHeadingZ(Rail rail, double arcM) {
		return headingAt(rail, arcM)[1];
	}

	/** The end node of {@code rail} nearest to {@code arcM} (arc space is ordered-position-1). */
	private static @Nullable Position nodeAtEndpoint(Rail rail, double arcM) {
		final Position[] ordered = rail.mmtrOrderedPositions();
		if (ordered == null || ordered.length < 2) {
			return null;
		}
		return arcM <= rail.railMath.getLength() / 2 ? ordered[0] : ordered[1];
	}

	/**
	 * S4 (显示层, observable before it is wired): what every lamp would show under the v2 rule.
	 *
	 * <p>Each lamp owns exactly one section, so its display is the occupancy depth of the chain that
	 * STOPS AT IT: 1 = its own section is occupied (red), 2 = the next section is (single yellow), 3 = the
	 * one after that (double yellow), 0 = clear (green). This is the v2 counterpart of
	 * {@code MmtrSignalAspect}: the same red/single/double convention, but the unit is the lamp-to-lamp
	 * section, so a train standing three rails ahead inside the same section reads red here instead of
	 * "three blocks away".</p>
	 */
	public ObjectArrayList<String> describeLampAspects(@Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes) {
		refresh();
		final ObjectArrayList<String> out = new ObjectArrayList<>();
		final ObjectArrayList<String> keys = new ObjectArrayList<>(sectionsBySignal.keySet());
		keys.sort(String::compareTo);
		for (final String key : keys) {
			final Section section = sectionsBySignal.get(key);
			final String aspect = aspectName(depthAt(section, trees, restrictedNodes));
			final Section next = following(section);
			out.add("[blocks-v2] 灯 " + key + " → " + aspect + "（区间 " + section.spans.size() + " 段/长="
				+ round(section.lengthM()) + "，后继=" + (next == null ? "无" : next.id) + "）");
		}
		return out;
	}

	/**
	 * S4 (客户端镜像): every lamp's v2 display, keyed by the lamp's {@code x,y,z} registry key.
	 *
	 * <p>The ENGINE hands the client its conclusion rather than the raw walk: the client renders per lamp
	 * block and can look its own key up, so the two sides cannot disagree and the client needs no copy of
	 * the section walk. Values are the aspect names the engine uses everywhere else
	 * ({@code RED} / {@code SINGLE_YELLOW} / {@code DOUBLE_YELLOW} / {@code GREEN}).</p>
	 */
	public Object2ObjectOpenHashMap<String, String> lampAspectNames(@Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes) {
		refresh();
		final Object2ObjectOpenHashMap<String, String> out = new Object2ObjectOpenHashMap<>();
		for (final Map.Entry<String, Section> entry : sectionsBySignal.entrySet()) {
			out.put(entry.getKey(), aspectName(depthAt(entry.getValue(), trees, restrictedNodes)));
		}
		return out;
	}

	/** The chain depth of the lamp owning {@code section}: 1 red / 2 single / 3 double / 0 clear. */
	private int depthAt(@Nullable Section section, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes) {
		Section walk = section;
		for (int level = 1; level <= 3 && walk != null; level++) {
			boolean hit = isOccupied(walk, trees);
			if (!hit) {
				for (final String nodeKey : boundaryNodeKeys(walk)) {
					if (restrictedNodes.test(nodeKey)) {
						hit = true;
						break;
					}
				}
			}
			if (hit) {
				return level;
			}
			walk = following(walk);
		}
		return 0;
	}

	private static String aspectName(int depth) {
		return depth == 1 ? "RED" : depth == 2 ? "SINGLE_YELLOW" : depth == 3 ? "DOUBLE_YELLOW" : "GREEN";
	}

	/**
	 * 占用转储 (operator diagnostic): every vehicle footprint recorded on {@code railHex} in the live
	 * occupancy trees, as {@code [vehicleId] arcFrom..arcTo}. This is what a "blocked ahead" hold is
	 * actually reading, so it is the first thing to look at when a train refuses to move - and it shows
	 * whose id owns each footprint, which is how a train being held by ITS OWN shadow is spotted.
	 */
	public ObjectArrayList<String> describeOccupancy(String railHex) {
		refresh();
		final ObjectArrayList<String> out = new ObjectArrayList<>();
		final Rail rail = railByHex.get(railHex);
		if (rail == null) {
			out.add("[occ] 找不到轨 " + railHex);
			return out;
		}
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = simulator.mmtrOccupancyTrees();
		if (trees == null) {
			out.add("[occ] 当前没有占用树（simulator 未 sync？）");
			return out;
		}
		final Position[] ordered = rail.mmtrOrderedPositions();
		out.add("[occ] 轨 " + shortHex(railHex) + " 长=" + round(rail.railMath.getLength()) + " 树=" + trees.size());
		for (int i = 0; i < trees.size(); i++) {
			final VehiclePosition vehiclePosition = Data.tryGet(trees.get(i), ordered[0], ordered[1]);
			if (vehiclePosition == null) {
				continue;
			}
			for (final double[] segment : vehiclePosition.segmentsExcluding(Long.MIN_VALUE)) {
				out.add("[occ]   树" + i + " 区间 [" + round(segment[0]) + ", " + round(segment[1]) + ")");
			}
			// The per-footprint ids: this is how a train held by ITS OWN shadow is told apart from one
			// held by a genuinely different vehicle.
			for (final long footprintId : vehiclePosition.footprintIds()) {
				out.add("[occ]   树" + i + " 占用者 id=" + footprintId);
			}
		}
		if (out.size() == 1) {
			out.add("[occ]   该轨上没有外部占用（占用树里没有它）");
		}
		return out;
	}

	/**
	 * WEB 区间图层: one entry per directional section, shaped for the management console's map.
	 *
	 * <p>Each section is what one lamp protects, walked lamp to lamp, so the console can draw the block
	 * boundaries the engine actually uses - including the ones that cross rail ends, which no per-rail
	 * view can show. Spans carry the rail and the arc window so the front end can project them onto the
	 * drawn map (a span may be a PART of a rail when a lamp splits it mid-rail).</p>
	 *
	 * @param trees occupancy trees the "occupied" flag is computed against (null = the live trees)
	 */
	public ObjectArrayList<SectionView> sectionViews(@Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes) {
		refresh();
		final ObjectArrayList<SectionView> out = new ObjectArrayList<>();
		final ObjectArrayList<String> keys = new ObjectArrayList<>(sectionsBySignal.keySet());
		keys.sort(String::compareTo);
		for (final String key : keys) {
			final Section section = sectionsBySignal.get(key);
			final Section next = following(section);
			out.add(new SectionView(
				section.id,
				section.exitSignalKey == null ? "" : section.exitSignalKey,
				next == null ? "" : next.id,
				aspectName(depthAt(section, trees, restrictedNodes)),
				isOccupied(section, trees),
				section.lengthM(),
				section.spans
			));
		}
		return out;
	}

	/** One section as the web console consumes it (see {@link #sectionViews}). */
	public static final class SectionView {
		public final String id;
		public final String exitSignalKey;
		public final String nextSectionId;
		public final String aspect;
		public final boolean occupied;
		public final double lengthM;
		public final ObjectArrayList<RailSpan> spans;

		SectionView(String id, String exitSignalKey, String nextSectionId, String aspect, boolean occupied, double lengthM, ObjectArrayList<RailSpan> spans) {
			this.id = id;
			this.exitSignalKey = exitSignalKey;
			this.nextSectionId = nextSectionId;
			this.aspect = aspect;
			this.occupied = occupied;
			this.lengthM = lengthM;
			this.spans = spans;
		}
	}

	// ---------------------------------------------------------------- 水闸区间 (S6)

	/** One 水闸区间: a stretch of line bounded by the lamps that face INTO it. */
	public static final class GateBlock {
		/**
		 * The lamp that opens this block (the one facing into it); empty for a block that no lamp guards
		 * (the end of the line, a plain siding).
		 */
		public final String entryLampKey;
		/**
		 * A stable id unique across the layer: the entry lamp's key, or {@code 无灯#<rail>|<arc>} for an
		 * unguarded block. Names must be unique because the node assignment and the map colours key on them -
		 * "no lamp" is a property of several blocks at once, not one shared name for all of them.
		 */
		public final String id;
		/** True when the walk ran out (dead end) instead of closing on the next lamp. */
		public final boolean endsOpen;
		public final ObjectArrayList<RailSpan> spans = new ObjectArrayList<>();

		GateBlock(String id, String entryLampKey, boolean endsOpen) {
			this.id = id;
			this.entryLampKey = entryLampKey;
			this.endsOpen = endsOpen;
		}

		GateBlock(String id, String entryLampKey, boolean endsOpen, ObjectArrayList<RailSpan> spans) {
			this(id, entryLampKey, endsOpen);
			this.spans.addAll(spans);
		}

		public double lengthM() {
			double length = 0;
			for (final RailSpan span : spans) {
				length += span.lengthM();
			}
			return length;
		}

		@Override
		public String toString() {
			return (entryLampKey.isEmpty() ? "（无灯 " + id + "）" : entryLampKey) + (endsOpen ? " → 开放端" : " → 下一盏灯")
				+ " 跨 " + spans.size() + " 段 长=" + Math.round(lengthM() * 10) / 10.0;
		}
	}

	/** How far a lamp's walk reaches in one travel direction, in arc order along that direction. */
	private static final class DirectionalReach {
		final String lampKey;
		/** The travel direction every arc in {@code spans} is measured along. */
		final double headingX;
		final double headingZ;
		final boolean endsOpen;
		/** Rail hex -> the arc window of this rail the reach covers, normalised to increasing arc. */
		final Object2ObjectOpenHashMap<String, double[]> windows = new Object2ObjectOpenHashMap<>();

		DirectionalReach(String lampKey, double headingX, double headingZ, boolean endsOpen) {
			this.lampKey = lampKey;
			this.headingX = headingX;
			this.headingZ = headingZ;
			this.endsOpen = endsOpen;
		}

		/** Widen the window this reach covers on a rail (a reach passes a rail once per direction). */
		void add(RailSpan span) {
			final double low = Math.min(span.arcFromM, span.arcToM);
			final double high = Math.max(span.arcFromM, span.arcToM);
			final double[] existing = windows.get(span.railHex);
			if (existing == null) {
				windows.put(span.railHex, new double[]{low, high});
			} else {
				existing[0] = Math.min(existing[0], low);
				existing[1] = Math.max(existing[1], high);
			}
		}
	}

	/**
	 * 水闸区间 (user definition, 2026-09-10; **unique assignment added 2026-09-11**): the block layer is a
	 * clean division of the line - every piece of track AND every track-layer node belongs to exactly ONE
	 * block.
	 *
	 * <p>The rule, in the user's words: a signal is a <em>water gate</em>. A block is closed when every one
	 * of its exits has a lamp facing INTO it; a lamp facing out of it belongs to the NEXT block; a stretch
	 * with no lamp (the end of the line, a plain siding) forms a block by itself. Nodes and turnouts do NOT
	 * cut blocks - they belong to the TRACK layer, which the map's rail/turnout layers already show.</p>
	 *
	 * <p>This matches real practice: a block section is a track-circuit section and the signal stands at its
	 * ENTRANCE, so block boundaries are signal positions and "one section, one train" is what the section is
	 * FOR (see 参考-英铁AWS与TPWS机制 §4).</p>
	 *
	 * <h2>Why the assignment needs a rule, and which one</h2>
	 *
	 * <p>Each lamp's own walk is only its REACH: at a ladder throat several lamps face into the same shared
	 * rails, so those reaches contain each other (measured on the dev world: 287 overlapping pairs over 134
	 * rails). To make the layer an actual division, every position is assigned to the <strong>nearest lamp
	 * UPSTREAM of it</strong> - the first lamp a movement standing there would have had to pass - which is
	 * exactly what the driver sees as "this is my block". Blocks are therefore the reaches CLIPPED where a
	 * nearer lamp takes over, and the clipped pieces still tile the line end to end.</p>
	 *
	 * <p>Facing the opposite way is a different movement, so it may well be a different block; that is the
	 * "directed" half of the model and not an overlap.</p>
	 */
	public ObjectArrayList<GateBlock> gateBlocks() {
		refresh();
		return computeGateBlocks();
	}

	private ObjectArrayList<GateBlock> computeGateBlocks() {
		// Lamp -> the block id the console uses (a lamp-less block is numbered by its order of appearance).
		final ObjectArrayList<GateBlock> out = new ObjectArrayList<>();
		final Object2ObjectOpenHashMap<String, GateBlock> blocksByLamp = new Object2ObjectOpenHashMap<>();
		final Object2ObjectOpenHashMap<String, DirectionalReach> increasing = new Object2ObjectOpenHashMap<>();
		final Object2ObjectOpenHashMap<String, DirectionalReach> decreasing = new Object2ObjectOpenHashMap<>();
		for (final Map.Entry<String, Section> entry : sectionsBySignal.entrySet()) {
			final Section walk = entry.getValue();
			if (walk.spans.isEmpty()) {
				continue;
			}
			addReaches(entry.getKey(), walk, increasing, decreasing);
		}

		final ReachIndex upIndex = new ReachIndex(true);
		final ReachIndex downIndex = new ReachIndex(false);
		increasing.values().forEach(upIndex::add);
		decreasing.values().forEach(downIndex::add);
		final Object2ObjectOpenHashMap<String, ObjectArrayList<DirectionalReach>>[] byDirection = newDirectionArrays(upIndex, downIndex);
		// One pass per rail HEX: the world may hold two rail entities with the same endpoints (the hex id IS
		// the endpoints), and walking both would emit the same track twice - the overlap the layer must not
		// have. Every other part of the engine keys rails by hex too, so one pass is the consistent reading.
		final ObjectOpenHashSet<String> emitted = new ObjectOpenHashSet<>();
		for (final Rail rail : simulator.rails) {
			final String hex = rail.getHexId();
			final double length = rail.railMath.getLength();
			if (length <= 1e-6 || !emitted.add(hex)) {
				continue;
			}
			emitRail(rail, hex, length, byDirection, blocksByLamp, out);
		}
		return out;
	}

	// ---------------------------------------------------------------- the unique assignment

	/**
	 * The rails of one travel direction with every lamp reach that covers them: rail hex -> the reaches on
	 * it, in no particular order (the governing rule picks between them per arc).
	 */
	private static final class ReachIndex {
		final Object2ObjectOpenHashMap<String, ObjectArrayList<DirectionalReach>> byRail = new Object2ObjectOpenHashMap<>();
		/** Which way the arc index runs for this direction, so "upstream" and "downstream" mean something. */
		final boolean upward;

		ReachIndex(boolean upward) {
			this.upward = upward;
		}

		void add(DirectionalReach reach) {
			reach.windows.forEach((railHex, window) -> byRail.computeIfAbsent(railHex, ignored -> new ObjectArrayList<>()).add(reach));
		}
	}

	/** Both directions as one pair: [0] = arc-increasing, [1] = arc-decreasing. */
	@SuppressWarnings("unchecked")
	private static Object2ObjectOpenHashMap<String, ObjectArrayList<DirectionalReach>>[] newDirectionArrays(ReachIndex upIndex, ReachIndex downIndex) {
		return new Object2ObjectOpenHashMap[]{upIndex.byRail, downIndex.byRail};
	}

	/** The reaches covering one rail, per direction; an empty list when no lamp reaches it from that side. */
	@SuppressWarnings("unchecked")
	private static ObjectArrayList<DirectionalReach>[] reachesOn(@Nullable ObjectArrayList<DirectionalReach> up, @Nullable ObjectArrayList<DirectionalReach> down) {
		return new ObjectArrayList[]{
			up == null ? new ObjectArrayList<DirectionalReach>() : up,
			down == null ? new ObjectArrayList<DirectionalReach>() : down,
		};
	}

	/**
	 * Split one lamp's walk into its per-direction reaches.
	 *
	 * <p><strong>One reach per lamp:</strong> the map is keyed by LAMP, not by rail. Keying by rail made the
	 * second lamp on a rail overwrite the first, so the whole rail silently fell to whichever lamp was
	 * rebuilt last - a two-headed 200 m rail came out as one 200 m block instead of one cell per head.</p>
	 *
	 * <p>A block is walked along ONE travel direction (the lamp either faces up the arc or down it), so
	 * comparing two reaches is only meaningful when they run the same way; the two directions are kept
	 * apart here and the uniqueness rule is applied within each of them.</p>
	 */
	private void addReaches(String lampKey, Section walk, Object2ObjectOpenHashMap<String, DirectionalReach> increasing, Object2ObjectOpenHashMap<String, DirectionalReach> decreasing) {
		final boolean endsOpen = walk.exitSignalKey == null || walk.exitSignalKey.isEmpty();
		for (final RailSpan span : walk.spans) {
			final boolean up = span.arcToM > span.arcFromM;
			final Object2ObjectOpenHashMap<String, DirectionalReach> side = up ? increasing : decreasing;
			DirectionalReach reach = side.get(lampKey);
			if (reach == null) {
				reach = new DirectionalReach(lampKey, span.headingX, span.headingZ, endsOpen);
				side.put(lampKey, reach);
			}
			if (span.lengthM() > 1e-9) {
				reach.add(span);
			}
		}
	}

	/** Cut every rail into the pieces owned by their nearest upstream lamp, in arc order. */
	private void emitRail(Rail rail, String hex, double length, Object2ObjectOpenHashMap<String, ObjectArrayList<DirectionalReach>>[] byDirection, Object2ObjectOpenHashMap<String, GateBlock> blocksByLamp, ObjectArrayList<GateBlock> out) {
		final ObjectArrayList<DirectionalReach>[] reaches = reachesOn(byDirection[0].get(hex), byDirection[1].get(hex));
		if (reaches[0].isEmpty() && reaches[1].isEmpty()) {
			// 无信号灯的自己成一个区间: no lamp faces into this rail at all, so it is a block nobody guards.
			// It is NOT merged with its neighbours - merging would need a node, and nodes are not a boundary
			// in this layer - so the whole rail is emitted as one unguarded block.
			final double[] heading = headingAt(rail, 0);
			final ObjectArrayList<RailSpan> spans = new ObjectArrayList<>();
			spans.add(new RailSpan(hex, 0, length, heading[0], heading[1]));
			out.add(new GateBlock(unguardedId(hex, 0), "", true, spans));
			return;
		}

		final java.util.TreeSet<Double> ordered = new java.util.TreeSet<>();
		for (final ObjectArrayList<DirectionalReach> list : reaches) {
			for (final DirectionalReach reach : list) {
				final double[] window = reach.windows.get(hex);
				ordered.add(clamp(window[0], 0, length));
				ordered.add(clamp(window[1], 0, length));
			}
		}
		final ObjectArrayList<Double> boundaries = new ObjectArrayList<>();
		for (final double cut : ordered) {
			if (boundaries.isEmpty() || cut - boundaries.get(boundaries.size() - 1) > MIN_BLOCK_PIECE_M) {
				boundaries.add(cut);
			}
		}
		if (boundaries.isEmpty() || boundaries.get(0) > MIN_BLOCK_PIECE_M) {
			boundaries.add(0, 0.0);
		}
		if (boundaries.get(boundaries.size() - 1) < length - MIN_BLOCK_PIECE_M) {
			boundaries.add(length);
		}

		// Per arc interval, per direction: who owns it. The owner is the nearest lamp upstream that still
		// reaches this far (see governing()), so the cells tile the rail instead of containing each other.
		// A run of intervals with one owner becomes ONE span, so a lamp whose cell is cut in half by a
		// mid-rail head still gets one clean span per piece of track.
		final DirectionalReach[] runningOwner = new DirectionalReach[]{null, null};
		final GateBlock[] runningBlock = new GateBlock[]{null, null};
		final double[] runningFrom = new double[]{0, 0};
		final double[] runningHeadingX = new double[]{0, 0};
		final double[] runningHeadingZ = new double[]{0, 0};
		for (int i = 0; i + 1 < boundaries.size(); i++) {
			final double from = boundaries.get(i);
			final double to = boundaries.get(i + 1);
			final double middle = (from + to) / 2;
			for (int direction = 0; direction < 2; direction++) {
				final DirectionalReach reach = governing(reaches[direction], hex, middle, direction == 0);
				if (reach == runningOwner[direction]) {
					continue; // nobody reaches here, or the same lamp still owns the run
				}
				if (runningOwner[direction] != null) {
					addSpan(runningBlock[direction], new RailSpan(hex, runningFrom[direction], from, runningHeadingX[direction], runningHeadingZ[direction]));
				}
				if (reach == null) {
					runningOwner[direction] = null;
					runningBlock[direction] = null;
					continue;
				}
				runningOwner[direction] = reach;
				runningBlock[direction] = blockFor(reach, blocksByLamp, out);
				runningFrom[direction] = from;
				runningHeadingX[direction] = direction == 0 ? headingAt(rail, middle)[0] : -headingAt(rail, middle)[0];
				runningHeadingZ[direction] = direction == 0 ? headingAt(rail, middle)[1] : -headingAt(rail, middle)[1];
			}
		}
		for (int direction = 0; direction < 2; direction++) {
			if (runningOwner[direction] != null) {
				addSpan(runningBlock[direction], new RailSpan(hex, runningFrom[direction], length, runningHeadingX[direction], runningHeadingZ[direction]));
			}
		}

		// 没有灯照到的弧段自成无灯区间: the tiling can leave a stretch owned by nobody where a lamp's cell
		// begins inside the rail and the rail's far end lies past every window (a stabling road whose entry
		// lamp stands mid-rail, and no other head reaches the tail). The layer must still cover the whole
		// line - a gap is a place where a train would belong to no block at all - so every arc no reach
		// covers gets its own unguarded block.
		final ObjectArrayList<RailSpan> unowned = new ObjectArrayList<>();
		for (int i = 0; i + 1 < boundaries.size(); i++) {
			final double from = boundaries.get(i);
			final double to = boundaries.get(i + 1);
			final double middle = (from + to) / 2;
			final boolean covered = governing(reaches[0], hex, middle, true) != null || governing(reaches[1], hex, middle, false) != null;
			if (covered) {
				continue;
			}
			if (!unowned.isEmpty() && Math.abs(unowned.get(unowned.size() - 1).arcToM - from) < 1e-6
				&& unowned.get(unowned.size() - 1).matchesHeading(headingAt(rail, middle)[0], headingAt(rail, middle)[1])) {
				// Extend the run rather than open a new block: one stretch, one cell.
				final RailSpan last = unowned.remove(unowned.size() - 1);
				unowned.add(new RailSpan(hex, last.arcFromM, to, last.headingX, last.headingZ));
			} else {
				final double[] heading = headingAt(rail, middle);
				unowned.add(new RailSpan(hex, from, to, heading[0], heading[1]));
			}
		}
		if (!unowned.isEmpty()) {
			for (final RailSpan span : unowned) {
				out.add(new GateBlock(unguardedId(hex, span.arcFromM), "", true, ObjectArrayList.of(span)));
			}
		}
	}

	/** The block that a reach belongs to, created on first use and keyed by its entry lamp. */
	private static GateBlock blockFor(DirectionalReach reach, Object2ObjectOpenHashMap<String, GateBlock> blocksByLamp, ObjectArrayList<GateBlock> out) {
		GateBlock block = blocksByLamp.get(reach.lampKey);
		if (block == null) {
			block = new GateBlock(reach.lampKey, reach.lampKey, reach.endsOpen);
			blocksByLamp.put(reach.lampKey, block);
			out.add(block);
		}
		return block;
	}

	/** The unique name of an unguarded block: the rail it starts on, so no two of them share one. */
	private static String unguardedId(String railHex, double arcM) {
		return "无灯#" + railHex + "@" + Math.round(arcM * 10) / 10.0;
	}

	/**
	 * The reach that owns arc {@code arcM} of its rail, or null when no lamp reaches it.
	 *
	 * <p>The owner is the nearest lamp UPSTREAM <em>that actually reaches this far</em>: of the reaches
	 * covering {@code arcM}, the one whose own start is closest to it, i.e. the innermost one. This single
	 * rule settles every case the layer has:</p>
	 *
	 * <ul>
	 * <li>a nearer lamp behind you takes the cell from the one further back, which is what makes the cells
	 * tile the ladder instead of containing each other;</li>
	 * <li>a reach that ENDS before {@code arcM} is ignored rather than winning the stretch past its own end,
	 * which keeps one lamp's cells contiguous (closest-start would otherwise alternate and shred a block);</li>
	 * <li>two lamps meeting nose to nose - one facing east at the rail's middle, one facing west at its far
	 * end - each own their own side, so the inner lamp keeps the cell AHEAD of itself (the outer lamp's
	 * reach stops being the answer there) exactly as the model says it should;</li>
	 * <li>two lamps standing at the same node with the same reach (the four lamps on one 43 m stabling road
	 * in the dev world) are one cell in this layer, not four copies of it.</li>
	 * </ul>
	 *
	 * <p>{@code forward} says which way the arc index runs, so "start" means the end the movement comes
	 * from rather than the smaller index. Ties are broken by the reach's own end and then by the lamp's key,
	 * so the answer never depends on map iteration order.</p>
	 */
	private static @Nullable DirectionalReach governing(ObjectArrayList<DirectionalReach> reaches, @Nullable String railHex, double arcM, boolean forward) {
		if (railHex == null) {
			return null;
		}
		DirectionalReach best = null;
		double bestStart = forward ? -Double.MAX_VALUE : Double.MAX_VALUE;
		double bestEnd = 0;
		String bestLampKey = "";
		for (final DirectionalReach reach : reaches) {
			final double[] window = reach.windows.get(railHex);
			if (window == null || arcM < window[0] - 1e-6 || arcM > window[1] + 1e-6) {
				continue;
			}
			final double start = forward ? window[0] : -window[1];
			final double end = forward ? window[1] : -window[0];
			if (best == null || start > bestStart
				|| (start == bestStart && (end > bestEnd || (end == bestEnd && reach.lampKey.compareTo(bestLampKey) < 0)))) {
				best = reach;
				bestStart = start;
				bestEnd = end;
				bestLampKey = reach.lampKey;
			}
		}
		return best;
	}

	/**
	 * Every TRACK-layer node with the block it belongs to - the user's requirement that each node has
	 * exactly one block (2026-09-10): "每个轨道层每个节点都有且只有一个区间层所属".
	 *
	 * <p>The node is a single physical point, so it needs one canonical direction to be read in: the block
	 * of the movement LEAVING the node along the lexicographically first rail at it. That is deterministic
	 * and independent of which rail the caller happens to be looking at, which is what makes the assignment
	 * checkable ("every node appears exactly once").</p>
	 *
	 * <p>A key mapping to an EMPTY string is one no lamp reaches (an unguarded stretch): the node still
	 * belongs to exactly one block, that block just has no entry lamp.</p>
	 */
	public Object2ObjectOpenHashMap<String, String> nodeOwners() {
		refresh();
		return computeNodeOwners();
	}

	private Object2ObjectOpenHashMap<String, String> computeNodeOwners() {
		final Object2ObjectOpenHashMap<String, String> owners = new Object2ObjectOpenHashMap<>();
		final ObjectArrayList<GateBlock> blocks = gateBlocks();
		for (final Map.Entry<Position, Object2ObjectOpenHashMap<Position, Rail>> entry : simulator.positionsToRail.entrySet()) {
			final Position node = entry.getKey();
			final String nodeKey = node.getX() + "," + node.getY() + "," + node.getZ();
			// Canonical reading of the node: the movement leaving it along the rail whose hex sorts first.
			// Deterministic, and independent of which rail the caller happens to be looking at.
			//
			// The node is located by COMPARING the rail's two ends with it rather than by asking the rail for
			// an arc: the dev world holds two rail entities for one endpoint pair, so the instance held by
			// railByHex (and by the section walks) can be a different object from the one in positionsToRail,
			// and the arc lookup then answers NaN for a node that is plainly on it. One node came out with no
			// block at all that way.
			String bestHex = null;
			String bestOwner = "";
			for (final Rail rail : entry.getValue().values()) {
				if (bestHex != null && rail.getHexId().compareTo(bestHex) >= 0) {
					continue;
				}
				final double length = rail.railMath.getLength();
				// Which end of the rail this node is, read from the rail's own ordered ends (the same ordering
				// Rail uses to build its arc space), rather than by identity against a possibly stale instance.
				final Position[] ordered = rail.mmtrOrderedPositions();
				final int comparison = node.compareTo(ordered[0]);
				if (comparison != 0 && node.compareTo(ordered[1]) != 0) {
					continue;
				}
				final boolean nodeAtLowArc = comparison == 0;
				final double nodeArc = nodeAtLowArc ? 0 : length;
				// Leaving the node means walking AWAY from it: up the arc when the node is the low end, down
				// the arc when it is the high end.
				final double sampleArc = clamp(nodeAtLowArc ? 1 : length - 1, 0, length);
				final double[] heading = headingAt(rail, sampleArc);
				final double[] outgoing = nodeAtLowArc ? heading : negate(heading);
				bestHex = rail.getHexId();
				bestOwner = ownerKey(blocks, bestHex, nodeArc, outgoing);
			}
			owners.put(nodeKey, bestOwner);
		}
		return owners;
	}

	/**
	 * The id of the block owning {@code arcM} of {@code railHex} in the given travel direction ("" = none).
	 *
	 * <p>A node sits ON a cell boundary - spans are half-open, so the arc at a cell's far end is not
	 * "inside" that cell by the strict test - and it must still come out with exactly one owner. The order
	 * of preference is: the cell the movement is inside; else the cell that STARTS at the node (the node is
	 * its entrance, so it is the node the driver reads the lamp from); else the cell that ENDS there (the
	 * node is where that cell's movement runs out). The lowest such arc wins the ties, so the answer does
	 * not depend on which direction the neighbouring cells happen to be walked in.</p>
	 */
	private static String ownerKey(ObjectArrayList<GateBlock> blocks, String railHex, double arcM, double[] heading) {
		GateBlock starting = null;
		double startingArc = 0;
		GateBlock ending = null;
		double endingArc = 0;
		for (final GateBlock block : blocks) {
			for (final RailSpan span : block.spans) {
				if (!span.railHex.equals(railHex)) {
					continue;
				}
				if (span.containsArc(arcM) && span.matchesHeading(heading[0], heading[1])) {
					return block.id;
				}
				if (Math.abs(arcM - span.arcFromM) <= NODE_OWNER_TOLERANCE_M && (starting == null || span.arcFromM < startingArc)) {
					starting = block;
					startingArc = span.arcFromM;
				}
				if (Math.abs(arcM - span.arcToM) <= NODE_OWNER_TOLERANCE_M && (ending == null || span.arcToM < endingArc)) {
					ending = block;
					endingArc = span.arcToM;
				}
			}
		}
		if (starting != null) {
			return starting.id;
		}
		return ending == null ? "" : ending.id;
	}

	private static double[] negate(double[] heading) {
		return new double[]{-heading[0], -heading[1]};
	}

	/**
	 * Whether any vehicle footprint stands inside {@code block} (the occupancy half of the layer, so the
	 * map can show which cells are taken right now - 一区段一车).
	 */
	public boolean isOccupied(GateBlock block, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		return isOccupied(block, trees, 0);
	}

	/** As above, ignoring the footprints of {@code excludeVehicleId} (a train does not occupy itself). */
	public boolean isOccupied(GateBlock block, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, long excludeVehicleId) {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancyTrees =
			trees == null ? simulator.mmtrOccupancyTrees() : trees;
		if (occupancyTrees == null || occupancyTrees.isEmpty()) {
			return false;
		}
		for (final RailSpan span : block.spans) {
			final Rail rail = railByHex.get(span.railHex);
			if (rail == null || span.lengthM() <= 1e-9) {
				continue;
			}
			final Position[] ordered = rail.mmtrOrderedPositions();
			if (ordered == null || ordered.length < 2) {
				continue;
			}
			for (int i = 0; i < occupancyTrees.size(); i++) {
				final VehiclePosition vehiclePosition = Data.tryGet(occupancyTrees.get(i), ordered[0], ordered[1]);
				if (vehiclePosition != null && vehiclePosition.getClosestOverlap(span.arcFromM, span.arcToM, false, excludeVehicleId) >= 0) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The aspect the block's OWN entry lamp shows: depth 0 (clear through the whole block) is green, the
	 * next block occupied is a caution, and the block being occupied is red. Empty when the block has no
	 * entry lamp - nobody guards it, so there is no light to read.
	 */
	public String blockAspect(GateBlock block, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, java.util.function.Predicate<String> restrictedNodes) {
		if (block.entryLampKey.isEmpty()) {
			return "";
		}
		final Section section = sectionsBySignal.get(block.entryLampKey);
		if (section == null) {
			return "";
		}
		return aspectName(depthAt(section, trees, restrictedNodes));
	}

	/**
	 * Everything that decides one node's block, as text (diagnostics): the rail the node is read on, where on
	 * it, the direction a movement leaves it, and every span that could claim it.
	 */
	public String describeNodeResolution(Position node) {
		refresh();
		final Object2ObjectOpenHashMap<Position, Rail> rails = simulator.positionsToRail.get(node);
		if (rails == null) {
			return "节点不在 positionsToRail 里（没有轨接在它上面）";
		}
		final ObjectArrayList<GateBlock> blocks = gateBlocks();
		final StringBuilder out = new StringBuilder();
		for (final Rail rail : rails.values()) {
			final Position[] ordered = rail.mmtrOrderedPositions();
			final int low = node.compareTo(ordered[0]);
			final int high = node.compareTo(ordered[1]);
			out.append("\n  候选轨 ").append(rail.getHexId().substring(0, 20)).append(".. len=").append(Math.round(rail.railMath.getLength() * 10) / 10.0)
				.append(" 是低端=").append(low == 0).append(" 是高端=").append(high == 0);
			if (low != 0 && high != 0) {
				out.append("（这个节点不在这根轨的两端）");
				continue;
			}
			final boolean nodeAtLowArc = low == 0;
			final double nodeArc = nodeAtLowArc ? 0 : rail.railMath.getLength();
			final double[] heading = headingAt(rail, clamp(nodeAtLowArc ? 1 : rail.railMath.getLength() - 1, 0, rail.railMath.getLength()));
			final double[] outgoing = nodeAtLowArc ? heading : negate(heading);
			out.append(" 节点弧=").append(Math.round(nodeArc * 10) / 10.0).append(" 离开方向=(")
				.append(Math.round(outgoing[0] * 100) / 100.0).append(",").append(Math.round(outgoing[1] * 100) / 100.0).append(")");
			for (final GateBlock block : blocks) {
				for (final RailSpan span : block.spans) {
					if (span.railHex.equals(rail.getHexId()) && (span.containsArc(nodeArc) || Math.abs(nodeArc - span.arcFromM) <= NODE_OWNER_TOLERANCE_M || Math.abs(nodeArc - span.arcToM) <= NODE_OWNER_TOLERANCE_M)) {
						out.append("\n    区间 ").append(block.id).append(" 弧[").append(Math.round(span.arcFromM * 10) / 10.0).append(",")
							.append(Math.round(span.arcToM * 10) / 10.0).append(") 同向=").append(span.matchesHeading(outgoing[0], outgoing[1]));
					}
				}
			}
		}
		return out.toString();
	}

	/** The rail onto which {@code section} continues after {@code railHex} (the next span), or null. */
	public @Nullable String nextRailOf(@Nullable Section section, String railHex) {
		if (section == null) {
			return null;
		}
		for (int i = 0; i < section.spans.size() - 1; i++) {
			if (section.spans.get(i).railHex.equals(railHex)) {
				return section.spans.get(i + 1).railHex;
			}
		}
		return null;
	}

	/** Lamp key -> section (diagnostics/tests). */
	public Map<String, Section> allSections() {
		refresh();
		return sectionsBySignal;
	}

	// ---------------------------------------------------------------- build

	/**
	 * Record one walked span.
	 *
	 * <p>A block may name the same rail more than once - a rail that the cell passes twice in the same
	 * direction (a ladder that doubles back), or the same track walked both ways - so the spans are kept as
	 * walked and the feed ships them in order. Dropping "duplicates" here would silently delete the real
	 * track between two runs and open a gap in the layer.</p>
	 */
	private static void addSpan(Section section, RailSpan span) {
		section.spans.add(span);
	}

	/** The same for the block layer: keep every run the tiling produced, in arc order. */
	private static void addSpan(GateBlock block, RailSpan span) {
		block.spans.add(span);
	}

	private void refresh() {
		final String signature = signature();
		if (signature.equals(cachedSignature)) {
			return;
		}
		rebuild();
		cachedSignature = signature;
	}

	private void rebuild() {
		railByHex.clear();
		sectionsBySignal.clear();
		sectionsByRail.clear();
		followingBySection.clear();
		simulator.rails.forEach(rail -> railByHex.put(rail.getHexId(), rail));

		for (final SignalEntry entry : simulator.mmtrSignals.signals.values()) {
			final String key = MmtrSignalRegistry.key(entry.x, entry.y, entry.z);
			if (sectionsBySignal.containsKey(key)) {
				continue;
			}
			final ProtectedRail protectedRail = resolveProtectedRailInternal(entry);
			if (protectedRail == null) {
				continue;
			}
			final Section section = buildSection(key, protectedRail);
			if (section.spans.isEmpty()) {
				continue;
			}
			sectionsBySignal.put(key, section);
			for (final RailSpan span : section.spans) {
				sectionsByRail.computeIfAbsent(span.railHex, ignored -> new ObjectArrayList<>()).add(section);
			}
		}

		// Chain the sections: a section that ended at a lamp is continued by the section that lamp starts.
		for (final Section section : sectionsBySignal.values()) {
			final String exitKey = section.exitSignalKey;
			if (exitKey == null || exitKey.isEmpty()) {
				continue;
			}
			final Section next = sectionsBySignal.get(exitKey);
			if (next != null && next != section) {
				followingBySection.put(section.id, next);
			}
		}
	}

	/**
	 * The rail a lamp protects and the direction it authorises - the public entry point, which refreshes
	 * the rail index first so a caller running before any section query (the game-side bind tool) does not
	 * read a stale index.
	 */
	public @Nullable ProtectedRail resolveProtectedRail(SignalEntry entry) {
		refresh();
		return resolveProtectedRailInternal(entry);
	}

	/**
	 * The rail a lamp protects and the direction it authorises.
	 *
	 * <p>An explicit {@code target} (a BOUND bind) wins. Otherwise the lamp is matched <strong>by
	 * direction</strong>: among the rails within tolerance take the one the lamp stands beside and looks
	 * along. The v1 inference used nearest-rail-only, so a lamp could bind to a rail behind it.</p>
	 *
	 * <p>A lamp standing at an END of a rail counts too, and has to: a wayside head is placed beside the
	 * track at the joint, a metre or two off the axis, so its projection lands a little way INSIDE the rail
	 * it is next to. Requiring the projection to be strictly interior (the first version) sent such a lamp
	 * to the node branch below, which measures the direction of the rail LEAVING the node - for a head
	 * beside a rail's far end that is the opposite way, so the lamp ended up protecting the whole rail
	 * while facing against its own position (found by the two-heads-facing-each-other test).</p>
	 *
	 * <p>Internal: no refresh - {@code rebuild()} calls this while it is itself the refresh.</p>
	 */
	private @Nullable ProtectedRail resolveProtectedRailInternal(SignalEntry entry) {
		final double lampX = entry.x + 0.5;
		final double lampY = entry.y + 0.5;
		final double lampZ = entry.z + 0.5;
		final double[] heading = headingOf(entry.angle);

		if (entry.target != null && !entry.target.isEmpty()) {
			final Rail bound = railByHex.get(entry.target);
			if (bound != null) {
				final Double arc = MmtrBlockService.projectArc(bound, lampX, lampY, lampZ);
				if (arc != null) {
					return new ProtectedRail(bound, arc, heading[0], heading[1]);
				}
			}
			// A stale target falls through to directional inference (the rail was redrawn).
		}

		Rail best = null;
		double bestArc = 0;
		double bestScore = Double.MAX_VALUE;
		for (final Rail rail : simulator.rails) {
			final Double arc = MmtrBlockService.projectArc(rail, lampX, lampY, lampZ);
			if (arc == null) {
				continue;
			}
			final double length = rail.railMath.getLength();
			if (length <= 1e-6) {
				continue;
			}
			// A lamp beside a rail protects it, and the usable stretch is the part ahead of it in the
			// facing direction. The projection may sit anywhere on the rail - including a metre or two from
			// an end, which is where a wayside head actually stands - so there is no interior requirement
			// here; the direction test below is what rejects a lamp that faces back down the line.
			final double[] railHeading = headingAt(rail, arc);
			final double dot = railHeading[0] * heading[0] + railHeading[1] * heading[1];
			if (dot <= 0.1) {
				continue;
			}
			final double aheadM = length - arc;
			if (aheadM <= 1e-3) {
				continue;
			}
			final double score = (1.0 - dot) * 1000 + distanceSq(rail, arc, lampX, lampY, lampZ);
			if (score < bestScore) {
				bestScore = score;
				best = rail;
				bestArc = arc;
			}
		}
		if (best != null) {
			return new ProtectedRail(best, bestArc, heading[0], heading[1]);
		}

		// No rail under the lamp: it stands on a node (the normal case for a wayside signal, and the
		// case v1 could not express). The node is shared by several rails, so pick the one that LEAVES
		// the node along the direction the lamp faces - never the rail that merely ends there, which is
		// the stretch the lamp has already passed and therefore does not protect.
		final Position node = nearestNode(lampX, lampY, lampZ);
		if (node == null) {
			return null;
		}
		final Object2ObjectOpenHashMap<Position, Rail> atNode = simulator.positionsToRail.get(node);
		if (atNode == null) {
			return null;
		}
		Rail chosen = null;
		double chosenDot = 0.1;
		for (final Rail candidate : atNode.values()) {
			final double arc = MmtrBlockService.arcOfNode(candidate, node);
			if (Double.isNaN(arc)) {
				continue;
			}
			final double length = candidate.railMath.getLength();
			if (length <= 1e-6) {
				continue;
			}
			// Direction leaving the node: increasing arc when the node is at arc 0, else decreasing arc.
			final double[] outgoing = outgoingHeading(candidate, arc, length);
			final double dot = outgoing[0] * heading[0] + outgoing[1] * heading[1];
			if (dot > chosenDot) {
				chosenDot = dot;
				chosen = candidate;
			}
		}
		return chosen == null ? null : new ProtectedRail(chosen, MmtrBlockService.arcOfNode(chosen, node), heading[0], heading[1]);
	}

	/**
	 * The node nearest to a world position (a lamp block sits on a node block). Only real graph nodes
	 * count: a rail endpoint with no connecting rails is not a node a signal would stand on, and binding
	 * to it would leave the lamp protecting nothing.
	 */
	private @Nullable Position nearestNode(double x, double y, double z) {
		Position best = null;
		double bestDistanceSq = Double.MAX_VALUE;
		for (final Position node : simulator.positionsToRail.keySet()) {
			final Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(node);
			if (neighbours == null || neighbours.isEmpty()) {
				continue;
			}
			final double dx = node.getX() - x;
			final double dy = node.getY() - y;
			final double dz = node.getZ() - z;
			final double distanceSq = dx * dx + dy * dy + dz * dz;
			if (distanceSq < bestDistanceSq) {
				bestDistanceSq = distanceSq;
				best = node;
			}
		}
		return bestDistanceSq <= SIGNAL_BIND_TOLERANCE_M * SIGNAL_BIND_TOLERANCE_M ? best : null;
	}

	/** Unit heading (x, z) leaving {@code node} along {@code rail} (the node sits at {@code nodeArc}). */
	private static double[] outgoingHeading(Rail rail, double nodeArc, double length) {
		final boolean nodeAtLowArc = nodeArc <= length / 2;
		final double[] positiveArcHeading = headingAt(rail, nodeAtLowArc ? 0 : length);
		return nodeAtLowArc ? positiveArcHeading : new double[]{-positiveArcHeading[0], -positiveArcHeading[1]};
	}

	/**
	 * Walk from the lamp in the direction it faces until the next lamp (exclusive) or the end of the
	 * line, collecting the spans walked.
	 *
	 * <p>Exactly one continuation is followed at a node: the straightest one (highest dot product).
	 * Taking every branch would make one lamp authorise a whole junction fan, which is not what a
	 * wayside signal means; which branch is actually set is the point authority's business, and the S4
	 * display layer narrows by the set route on top of this.</p>
	 */
	private Section buildSection(String entrySignalKey, ProtectedRail protectedRail) {
		final Section section = new Section(entrySignalKey, entrySignalKey);
		final Rail firstRail = protectedRail.rail;
		final String firstHex = firstRail.getHexId();
		final double length = firstRail.railMath.getLength();
		final double[] railHeading = headingAt(firstRail, protectedRail.arcM);
		final boolean forward = railHeading[0] * protectedRail.headingX + railHeading[1] * protectedRail.headingZ > 0;
		final double entryArc = clamp(protectedRail.arcM, 0, length);
		final double exitArc = forward ? length : 0;
		final double headingX = forward ? railHeading[0] : -railHeading[0];
		final double headingZ = forward ? railHeading[1] : -railHeading[1];

		if (Math.abs(exitArc - entryArc) > 1e-6) {
			// A lamp standing mid-rail is a boundary too (v2 keeps v1's geometric cut, not only node binds):
			// truncate the span at the nearest such lamp ahead and end the section there.
			final MidRailLamp midRail = nearestLampOnSpan(firstRail, entryArc, exitArc, forward, headingX, headingZ);
			if (midRail != null) {
				addSpan(section, new RailSpan(firstHex, entryArc, midRail.arcM, headingX, headingZ));
				section.exitSignalKey = midRail.key;
				return section;
			}
			addSpan(section, new RailSpan(firstHex, entryArc, exitArc, headingX, headingZ));
		}

		// The movement leaves the rail straight into another lamp's cell: this lamp's reach starts at the
		// next lamp's position, so this lamp would show nothing but the light standing in front of it. That
		// section is dropped (an empty walk) and the cell it was aiming at belongs to the lamp ahead - the
		// nearest-upstream rule in gateBlocks() then assigns the track to the lamp that actually reaches it.
		if (section.exitSignalKey != null && section.exitSignalKey.equals(entrySignalKey) && section.spans.isEmpty()) {
			return section;
		}

		final Position exitNode = forward ? farNode(firstRail, true) : farNode(firstRail, false);
		final ObjectOpenHashSet<String> pathRails = new ObjectOpenHashSet<>();
		pathRails.add(firstHex);
		walk(section, firstHex, exitNode, headingX, headingZ, 1, pathRails);
		return section;
	}

	/** A lamp standing in the middle of a rail (not at a node): the arc it sits at and its registry key. */
	private static final class MidRailLamp {
		final double arcM;
		final String key;

		MidRailLamp(double arcM, String key) {
			this.arcM = arcM;
			this.key = key;
		}
	}

	/**
	 * The nearest lamp standing on {@code rail} strictly inside the arc range the movement is crossing
	 * <em>that faces into the block being walked</em> (heading {@code headingX, headingZ}), or null. A lamp
	 * on a node is the walk's business (it ends sections by node), so only a lamp whose projection is
	 * strictly inside the rail counts here - this keeps v1's geometric cut (a light beside the middle of a
	 * rail does split it) while the node case stays with the walk.
	 *
	 * <p>The facing test is what makes the cut correct in the OTHER direction (S6, notes/113): a head
	 * facing AWAY belongs to the block on the other side, and letting it truncate this walk is how a
	 * west-facing head at the east end ended up with a 50 m block instead of the whole 100 m rail.</p>
	 */
	private @Nullable MidRailLamp nearestLampOnSpan(Rail rail, double fromArcM, double toArcM, boolean forward, double headingX, double headingZ) {
		final double low = Math.min(fromArcM, toArcM);
		final double high = Math.max(fromArcM, toArcM);
		final double length = rail.railMath.getLength();
		MidRailLamp best = null;
		for (final SignalEntry entry : simulator.mmtrSignals.signals.values()) {
			final Double projected = MmtrBlockService.projectArc(rail, entry.x + 0.5, entry.y + 0.5, entry.z + 0.5);
			if (projected == null) {
				continue;
			}
			final double arc = clamp(projected, 0, length);
			if (arc <= 0.5 || arc >= length - 0.5) {
				continue; // on one of this rail's nodes: not a mid-rail cut
			}
			if (arc < low + 1e-6 || arc > high - 1e-6) {
				continue; // outside the stretch being crossed
			}
			if (!facesInto(entry, headingX, headingZ)) {
				continue; // faces out of this block: it bounds the NEXT one, not this one
			}
			if (best == null || (forward ? arc < best.arcM : arc > best.arcM)) {
				best = new MidRailLamp(arc, MmtrSignalRegistry.key(entry.x, entry.y, entry.z));
			}
		}
		return best;
	}

	/**
	 * True when the lamp's facing points along the travel direction {@code (headingX, headingZ)} - i.e. it
	 * guards the block a movement travelling that way is entering.
	 */
	private static boolean facesInto(SignalEntry entry, double headingX, double headingZ) {
		final double[] lampHeading = headingOf(entry.angle);
		return lampHeading[0] * headingX + lampHeading[1] * headingZ > 0.1;
	}

	/**
	 * The lamp registered AT {@code node} that faces into a movement travelling {@code (headingX, headingZ)}.
	 *
	 * <p>Only a lamp facing into the block ends the walk (S6, notes/113): the boundary between two blocks
	 * carries <em>two</em> heads in the real world - one for each direction of travel - and the one facing
	 * BACK down the line that was just walked protects the stretch already behind the movement, so it must
	 * not cut this block. Counting it did exactly that on the dev world: opposite heads in the same block
	 * made the map show slivers nobody guards (notes/112 §3.1).</p>
	 */
	private @Nullable SignalEntry lampAt(Position node, double headingX, double headingZ) {
		final SignalEntry entry = simulator.mmtrSignals.get((int) node.getX(), (int) node.getY(), (int) node.getZ());
		return entry != null && facesInto(entry, headingX, headingZ) ? entry : null;
	}

	/**
	 * Continue the section from {@code node} (having just left {@code cameFromHex}) in {@code heading}.
	 *
	 * <p><strong>岔口多腿 (user ruling 2026-09-10, route B)</strong>: at a junction the walk follows
	 * <em>every</em> leg that continues in the travel direction, so one lamp protects the whole throat
	 * rather than the single leg it happens to face - which is what a real exit signal does. When a MAIN
	 * route is set through {@code cameFromHex} in this direction, the walk narrows to that route's own
	 * next rail instead (the same rule {@code MmtrSignalAspect} already used for the display).</p>
	 *
	 * <p>The first version followed only the straightest leg. That is wrong for a yard throat: the dev
	 * world's exit lamps then protected one of six parallel stabling roads (notes/107 §4).</p>
	 */
	private void walk(Section section, String cameFromHex, @Nullable Position node, double headingX, double headingZ, int railCount, ObjectOpenHashSet<String> pathRails) {
		if (node == null) {
			section.endsAtDeadEnd = true;
			return;
		}

		// A lamp standing at this node ends the section here when it faces INTO the block being walked: it
		// is the entrance of the next block. A lamp facing the other way guards the stretch the movement
		// has just left, and does not cut this block.
		final SignalEntry lampHere = lampAt(node, headingX, headingZ);
		if (lampHere != null) {
			section.exitSignalKey = MmtrSignalRegistry.key(lampHere.x, lampHere.y, lampHere.z);
			return;
		}

		final ObjectArrayList<Leg> legs = nextLegs(node, cameFromHex, headingX, headingZ);
		if (legs.isEmpty()) {
			section.endsAtDeadEnd = true;
			return;
		}
		if (railCount >= MAX_RAILS_PER_SECTION || section.lengthM() >= MAX_SECTION_LENGTH_M) {
			section.endsAtDeadEnd = true;
			return;
		}

		boolean anyBranchContinued = false;
		for (final Leg leg : legs) {
			final Rail next = leg.rail;
			final String nextHex = next.getHexId();
			// Cycle guard: a rail this branch has already walked cannot be entered twice (a diamond - the
			// same rail reachable two ways - is fine, because each branch carries its own path set).
			if (pathRails.contains(nextHex)) {
				continue;
			}
			final double arcOfNode = MmtrBlockService.arcOfNode(next, node);
			if (Double.isNaN(arcOfNode)) {
				continue;
			}
			final double nextLength = next.railMath.getLength();
			final double[] nextHeading = headingAt(next, arcOfNode);
			final boolean forward = leg.forward;
			final double toArc = forward ? nextLength : 0;
			final double spanHeadingX = forward ? nextHeading[0] : -nextHeading[0];
			final double spanHeadingZ = forward ? nextHeading[1] : -nextHeading[1];
			if (Math.abs(toArc - arcOfNode) > 1e-6) {
				// A lamp standing MID-RAIL is a boundary too: end the section on it instead of walking past.
				final MidRailLamp midRail = nearestLampOnSpan(next, arcOfNode, toArc, forward, spanHeadingX, spanHeadingZ);
				if (midRail != null) {
					addSpan(section, new RailSpan(nextHex, arcOfNode, midRail.arcM, spanHeadingX, spanHeadingZ));
					section.exitSignalKey = midRail.key;
					continue;
				}
				addSpan(section, new RailSpan(nextHex, arcOfNode, toArc, spanHeadingX, spanHeadingZ));
			}
			anyBranchContinued = true;
			final ObjectOpenHashSet<String> branchPath = new ObjectOpenHashSet<>(pathRails);
			branchPath.add(nextHex);
			walk(section, nextHex, forward ? farNode(next, true) : farNode(next, false), spanHeadingX, spanHeadingZ, railCount + 1, branchPath);
		}
		if (!anyBranchContinued) {
			section.endsAtDeadEnd = true;
		}
	}

	/** The lamp registered at {@code node}, if any (a lamp sits on a node block). */
	private @Nullable SignalEntry lampAt(Position node) {
		return simulator.mmtrSignals.get((int) node.getX(), (int) node.getY(), (int) node.getZ());
	}

	/** One continuation at a node: the rail, and whether the travel direction runs up its arc. */
	private static final class Leg {
		final Rail rail;
		final boolean forward;
		/** Where the leg leaves the node: its far node, so callers can match a route. */
		final Position farEnd;

		Leg(Rail rail, boolean forward, Position farEnd) {
			this.rail = rail;
			this.forward = forward;
			this.farEnd = farEnd;
		}
	}

	/**
	 * Every rail the movement may continue onto at {@code node}: all legs that keep the travel direction,
	 * narrowed to the SET MAIN route's own next rail when a route runs through {@code cameFromHex} this
	 * way. Never the rail just left.
	 */
	private ObjectArrayList<Leg> nextLegs(Position node, String cameFromHex, double headingX, double headingZ) {
		final ObjectArrayList<Leg> legs = new ObjectArrayList<>();
		final Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(node);
		if (neighbours == null) {
			return legs;
		}
		for (final Map.Entry<Position, Rail> entry : neighbours.entrySet()) {
			final Rail candidate = entry.getValue();
			if (candidate.getHexId().equals(cameFromHex)) {
				continue;
			}
			final double arc = MmtrBlockService.arcOfNode(candidate, node);
			if (Double.isNaN(arc)) {
				continue;
			}
			final double[] candidateHeading = headingAt(candidate, arc);
			final double dot = candidateHeading[0] * headingX + candidateHeading[1] * headingZ;
			if (dot > 0.1) {
				legs.add(new Leg(candidate, true, entry.getKey()));
			} else if (dot < -0.1) {
				legs.add(new Leg(candidate, false, entry.getKey()));
			}
		}
		if (legs.size() <= 1) {
			return legs;
		}
		final String routeNext = routeNextRailOn(cameFromHex, node);
		if (routeNext == null) {
			return legs; // no route: the whole throat is one block (岔口多腿)
		}
		for (final Leg leg : legs) {
			if (leg.rail.getHexId().equals(routeNext)) {
				final ObjectArrayList<Leg> narrowed = new ObjectArrayList<>();
				narrowed.add(leg);
				return narrowed;
			}
		}
		return legs;
	}

	/**
	 * The rail a route runs onto after {@code curHex} when the route leaves {@code curHex} through
	 * {@code node} - i.e. the route covers this rail in THIS travel direction. Null when no route does (a
	 * shunt keeps the main head at danger and narrows nothing).
	 *
	 * <p>PENDING routes count: the train is committed to that movement even while it waits for the
	 * interlocking, so its own leg is the block it will occupy.</p>
	 */
	private @Nullable String routeNextRailOn(String curHex, Position node) {
		for (final org.mtr.core.mmtr.route.MmtrRoute route : simulator.mmtrRoutes.allRoutes()) {
			if (route.getKind() != org.mtr.core.mmtr.route.MmtrRoute.Kind.MAIN) {
				continue;
			}
			final ObjectArrayList<String> rails = route.getRailHexes();
			for (int i = 0; i + 1 < rails.size(); i++) {
				if (!rails.get(i).equals(curHex)) {
					continue;
				}
				final String nextHex = rails.get(i + 1);
				final Rail nextRail = railByHex.get(nextHex);
				if (nextRail != null && !Double.isNaN(MmtrBlockService.arcOfNode(nextRail, node))) {
					return nextHex;
				}
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- geometry helpers

	/** The rail's two end nodes as {@code [lowArcNode, highArcNode]} (arc 0 first). */
	private static Position @Nullable [] orderedNodes(Rail rail) {
		final Position[] positions = rail.mmtrOrderedPositions();
		if (positions == null || positions.length < 2 || positions[0] == null || positions[1] == null) {
			return null;
		}
		return positions;
	}

	/** The far end node of {@code rail} in the direction of increasing ({@code true}) or decreasing arc. */
	private static @Nullable Position farNode(Rail rail, boolean towardPositiveArc) {
		final Position[] nodes = orderedNodes(rail);
		if (nodes == null) {
			return null;
		}
		// mmtrOrderedPositions() returns the endpoints ordered by Position.compareTo, which is the same
		// ordering RailMath's arc space is built from: index 0 = arc 0.
		return towardPositiveArc ? nodes[1] : nodes[0];
	}

	/** Unit heading (x, z) for an MTR signal facing angle. */
	private static double[] headingOf(float angleDegrees) {
		// MTR stores the signal block's FACING rotation (Minecraft convention: south = 0, west = 90,
		// north = 180, east = 270). DirectionHelper maps SOUTH->0 / WEST->90 / NORTH->180 / EAST->270,
		// and BlockSignalBase.getAngle adds 22.5/45 for the diagonal states.
		final double radians = Math.toRadians(angleDegrees);
		final double sin = Math.sin(radians);
		final double cos = Math.cos(radians);
		// Minecraft facing vectors: rotation 0 -> +Z (south), 90 -> -X (west), 180 -> -Z (north),
		// 270 -> +X (east).
		return new double[]{-sin, -cos};
	}

	/**
	 * Unit heading (x, z) of a rail at {@code arcM}, in the direction of increasing arc.
	 *
	 * <p>Public so the 区间图 (schematic) builder reads a rail's direction the same way this model does,
	 * instead of re-deriving it and drifting from it.</p>
	 */
	public static double[] railHeadingAt(Rail rail, double arcM) {
		return headingAt(rail, arcM);
	}

	/** Unit heading (x, z) of a rail at {@code arcM}, in the direction of increasing arc. */
	private static double[] headingAt(Rail rail, double arcM) {
		final double length = rail.railMath.getLength();
		final double a = clamp(arcM - SAMPLE_STEP_M, 0, length);
		final double b = clamp(arcM + SAMPLE_STEP_M, 0, length);
		final Vector from = rail.railMath.getPosition(a, false);
		final Vector to = rail.railMath.getPosition(b, false);
		final double dx = to.x() - from.x();
		final double dz = to.z() - from.z();
		final double norm = Math.sqrt(dx * dx + dz * dz);
		return norm < 1e-9 ? new double[]{0, 0} : new double[]{dx / norm, dz / norm};
	}

	private static double distanceSq(Rail rail, double arcM, double x, double y, double z) {
		final Vector position = rail.railMath.getPosition(clamp(arcM, 0, rail.railMath.getLength()), false);
		final double dx = position.x() - x;
		final double dy = position.y() - y;
		final double dz = position.z() - z;
		return dx * dx + dy * dy + dz * dz;
	}

	private String signature() {
		int hash = simulator.rails.size() * 31 + 1;
		for (final Rail rail : simulator.rails) {
			hash = hash * 31 + rail.getHexId().hashCode();
		}
		hash = hash * 31 + simulator.mmtrSignals.signals.size();
		for (final Map.Entry<String, SignalEntry> entry : simulator.mmtrSignals.signals.entrySet()) {
			hash = hash * 31 + entry.getKey().hashCode();
			hash = hash * 31 + Float.floatToIntBits(entry.getValue().angle);
			hash = hash * 31 + (entry.getValue().target == null ? 0 : entry.getValue().target.hashCode());
		}
		return Integer.toHexString(hash);
	}

	private static double clamp(double value, double min, double max) {
		return value < min ? min : Math.min(value, max);
	}

	private static String shortHex(String hex) {
		return hex.length() <= 6 ? hex : hex.substring(0, 6);
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}

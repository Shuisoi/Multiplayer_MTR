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
				if (vehiclePosition != null && vehiclePosition.getClosestOverlap(span.arcFromM, span.arcToM, false, 0) >= 0) {
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
			if (isSpanOccupied(span.railHex, from, to, trees)) {
				// Someone is inside what we are about to cross: hold where it starts.
				final double toOccupancyM = distanceToOccupancyM(span.railHex, from, to, trees, forward);
				return Math.max(0, toOccupancyM);
			}
			final RailSpan nextSpan = i + 1 < section.spans.size() ? section.spans.get(i + 1) : null;
			if (nextSpan != null && isSpanOccupied(nextSpan.railHex, nextSpan.arcFromM, nextSpan.arcToM, trees)) {
				// The next stretch of our own section is taken: hold at the boundary between them.
				return Math.max(0, toSpanEndM);
			}
			return Double.MAX_VALUE;
		}
		return Double.MAX_VALUE;
	}

	/** Whether any vehicle footprint overlaps the arc window on {@code railHex}. */
	private boolean isSpanOccupied(String railHex, double fromM, double toM, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
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
			if (vehiclePosition != null && vehiclePosition.getClosestOverlap(fromM, toM, false, 0) >= 0) {
				return true;
			}
		}
		return false;
	}

	/** How far from {@code fromM} the nearest external occupancy inside the window begins. */
	private double distanceToOccupancyM(String railHex, double fromM, double toM, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, boolean forward) {
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
			for (final double[] segment : vehiclePosition.segmentsExcluding(0)) {
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
		Section section = sectionProtecting(railHex, entryNode);
		if (section == null) {
			return 0;
		}
		for (int depth = 1; depth <= maxDepth; depth++) {
			if (isOccupied(section, trees)) {
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
			final ProtectedRail protectedRail = resolveProtectedRail(entry);
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
	 * The rail a lamp protects and the direction it authorises.
	 *
	 * <p>An explicit {@code target} (a BOUND bind) wins. Otherwise the lamp is matched <strong>by
	 * direction</strong>: among the rails within tolerance take the one the lamp stands beside and looks
	 * along. The v1 inference used nearest-rail-only, so a lamp could bind to a rail behind it.</p>
	 */
	public @Nullable ProtectedRail resolveProtectedRail(SignalEntry entry) {
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
			// A lamp that projects into the MIDDLE of a rail protects that rail, and the usable stretch
			// is the part ahead of it in the facing direction.
			final boolean interior = arc > SAMPLE_STEP_M && arc < length - SAMPLE_STEP_M;
			if (!interior) {
				continue;
			}
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
			final MidRailLamp midRail = nearestLampOnSpan(firstRail, entryArc, exitArc, forward);
			if (midRail != null) {
				section.spans.add(new RailSpan(firstHex, entryArc, midRail.arcM, headingX, headingZ));
				section.exitSignalKey = midRail.key;
				return section;
			}
			section.spans.add(new RailSpan(firstHex, entryArc, exitArc, headingX, headingZ));
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
	 * The nearest lamp standing on {@code rail} strictly inside the arc range the movement is crossing, or
	 * null. A lamp on a node is the walk's business (it ends sections by node), so only a lamp whose
	 * projection is strictly inside the rail counts here - this keeps v1's geometric cut (a light beside
	 * the middle of a rail does split it) while the node case stays with the walk.
	 */
	private @Nullable MidRailLamp nearestLampOnSpan(Rail rail, double fromArcM, double toArcM, boolean forward) {
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
			if (best == null || (forward ? arc < best.arcM : arc > best.arcM)) {
				best = new MidRailLamp(arc, MmtrSignalRegistry.key(entry.x, entry.y, entry.z));
			}
		}
		return best;
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

		// A lamp standing at this node ends the section here: both nodes of a boundary carry a lamp (the
		// one facing each way), and either way the lamp marks the block boundary the movement stops at.
		// A lamp facing the same way is NOT exempt - it starts the next section, which begins here.
		final SignalEntry lampHere = lampAt(node);
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
				final MidRailLamp midRail = nearestLampOnSpan(next, arcOfNode, toArc, forward);
				if (midRail != null) {
					section.spans.add(new RailSpan(nextHex, arcOfNode, midRail.arcM, spanHeadingX, spanHeadingZ));
					section.exitSignalKey = midRail.key;
					continue;
				}
				section.spans.add(new RailSpan(nextHex, arcOfNode, toArc, spanHeadingX, spanHeadingZ));
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

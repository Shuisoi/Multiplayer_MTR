package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.VehiclePosition;
import org.mtr.core.mmtr.route.MmtrRoute;
import org.mtr.core.mmtr.route.MmtrRouteRegistry;
import org.mtr.core.simulation.Simulator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 信号显示 = 进路 × 闭塞 (A2, design {@code 行车控制-低速区信号道岔任务集成-设计.md} §2).
 *
 * <p>The single source of truth for "what does the signal protecting rail R show?" — used by the
 * engine feed (ops console) and, once mirrored to clients, by the in-game signal renderer. Before
 * this class the rule was duplicated: {@code SystemMapServlet} walked the occupancy chain server
 * side and {@code RenderSignalBase.mmtrChainDepth} walked it client side, both with the same
 * "at a fork every branch counts" conservative worst case and the same TODO ("until route-locked
 * aspects exist").</p>
 *
 * <p>The rule:</p>
 * <ol>
 *   <li><strong>Base = 闭塞 chain.</strong> Depth 1 = the protected rail itself is occupied (RED),
 *       2 = one rail beyond in the travel direction (SINGLE_YELLOW), 3 = two beyond (DOUBLE_YELLOW),
 *       0 = clear (GREEN). Continuations keep the travel direction; at a fork every branch counts
 *       (worst case).</li>
 *   <li><strong>进路 SET narrows the fork.</strong> When a MAIN route is set over the rail being
 *       walked, the walk follows <em>that route's</em> next rail instead of every branch: the
 *       interlocking has locked one path, so the signal may clear along it (the old conservative
 *       rule made a signal look worse than the route it protects).</li>
 *   <li><strong>进路 PENDING holds its starting signal at danger.</strong> A rail that is the entry
 *       rail of a not-yet-set route shows RED: the movement is waiting outside its signal. Routes
 *       further along the route are unaffected (only the entry rail owns that signal).</li>
 *   <li><strong>调车 (SHUNT) routes never clear a main head.</strong> A shunt is authorised by a
 *       subsidiary aspect with the main head still at danger (C3a) — it narrows nothing here.</li>
 *   <li><strong>No route → free driving.</strong> With no route over the rail the aspect is the
 *       plain occupancy chain (red/yellow/green), i.e. exactly the pre-A2 behaviour.</li>
 * </ol>
 *
 * <p>Pure projection: nothing here reserves, moves or grants anything.</p>
 */
public final class MmtrSignalAspect {

	/** UK-style four-aspect display, most restrictive first. */
	public enum Aspect {
		RED,
		SINGLE_YELLOW,
		DOUBLE_YELLOW,
		GREEN
	}

	/** Max chain depth modelled (two rails beyond the protected one). */
	private static final int MAX_DEPTH = 3;

	private final Simulator simulator;
	private final MmtrRouteRegistry routes;
	private final Object2ObjectOpenHashMap<String, Rail> byHex = new Object2ObjectOpenHashMap<>();
	private final Object2ObjectOpenHashMap<String, Position[]> railEnds = new Object2ObjectOpenHashMap<>();
	/**
	 * ④: explicit occupancy trees to test junction clearance against ({@code null} = read the
	 * simulator's live train trees on every query, which is what the feed and the AWS trigger want).
	 */
	private final @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancyTrees;
	/** Cached restricted-junction keys for {@link #restrictedNodeKeys()} (S4: the v2 walk asks per step). */
	private @Nullable ObjectOpenHashSet<String> restrictedNodeCache;
	private int restrictedNodeSignature = -1;

	public MmtrSignalAspect(Simulator simulator, MmtrRouteRegistry routes) {
		this(simulator, routes, null);
	}

	public MmtrSignalAspect(Simulator simulator, MmtrRouteRegistry routes, @Nullable ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> occupancyTrees) {
		this.simulator = simulator;
		this.routes = routes;
		this.occupancyTrees = occupancyTrees;
		simulator.positionsToRail.forEach((node, neighbourMap) -> neighbourMap.forEach((pos, rail) -> {
			byHex.putIfAbsent(rail.getHexId(), rail);
			final Position[] ends = railEnds.computeIfAbsent(rail.getHexId(), k -> new Position[2]);
			if (ends[0] == null) {
				ends[0] = node;
			} else if (ends[1] == null && !ends[0].equals(node)) {
				ends[1] = node;
			}
		}));
	}

	/** Display aspect of every drawn rail, keyed by hex (ops feed / renderer input). */
	public Map<String, Aspect> aspectsForAllRails() {
		final Map<String, Aspect> aspects = new HashMap<>();
		final ObjectOpenHashSet<String> pendingEntries = routes.pendingEntryRails();
		byHex.keySet().forEach(hex -> aspects.put(hex, aspectOf(hex, pendingEntries)));
		return aspects;
	}

	/**
	 * Display aspect of one rail: the most restrictive of both travel directions (a signal may be
	 * approached from either end, and each end has its own head).
	 */
	public Aspect aspectOf(@Nullable String railHex) {
		return aspectOf(railHex, routes.pendingEntryRails());
	}

	private Aspect aspectOf(@Nullable String railHex, ObjectOpenHashSet<String> pendingEntries) {
		if (railHex == null || railHex.isEmpty() || !byHex.containsKey(railHex)) {
			return Aspect.GREEN;
		}
		if (pendingEntries.contains(railHex)) {
			return Aspect.RED;
		}
		final Position[] ends = railEnds.get(railHex);
		if (ends == null || ends[1] == null) {
			return Aspect.GREEN;
		}
		int best = 0;
		for (final Position entry : ends) {
			final int depth = chainDepth(railHex, entry);
			if (depth > 0 && (best == 0 || depth < best)) {
				best = depth;
			}
		}
		return fromDepth(best);
	}

	/**
	 * Aspect of one rail approached from {@code entryNode} — the direction-specific question the AWS
	 * trigger (A3) asks: "what does the signal I am about to pass show for MY direction?". A null
	 * entry node falls back to the most restrictive of both directions.
	 */
	public Aspect aspectFrom(@Nullable String railHex, @Nullable Position entryNode) {
		if (railHex == null || railHex.isEmpty() || !byHex.containsKey(railHex)) {
			return Aspect.GREEN;
		}
		if (routes.pendingEntryRails().contains(railHex)) {
			return Aspect.RED;
		}
		return entryNode == null ? aspectOf(railHex) : fromDepth(chainDepth(railHex, entryNode));
	}
	private static Aspect fromDepth(int depth) {
		return switch (depth) {
			case 1 -> Aspect.RED;
			case 2 -> Aspect.SINGLE_YELLOW;
			case 3 -> Aspect.DOUBLE_YELLOW;
			default -> Aspect.GREEN;
		};
	}

	/**
	 * How far ahead (in SECTIONS, the protected one included) the nearest occupied section sits when
	 * the signal protecting {@code hex} is approached from {@code entryPos}: 1 = the entry section is
	 * occupied, 2 = the section beyond it, 3 = the one after that, 0 = clear.
	 *
	 * <p>B3b: the walk counts sections, not rails. A rail that a wayside signal splits therefore
	 * contributes two steps (the near section, then the far one) and a train standing in the far
	 * section no longer paints the signal protecting the near one red - which is exactly what B2's S1
	 * stop already promises (the movement may run up to that signal). A rail that is not split keeps
	 * one section = one rail, i.e. the pre-B3b behaviour bit for bit.</p>
	 *
	 * <p>S4: when the 闭塞区间 v2 model reaches this rail, the unit of the walk becomes the
	 * <strong>lamp-to-lamp directional section</strong> instead of a rail - a train standing three rails
	 * ahead inside the same section now reads depth 1 (red) rather than "three blocks away". Rails no lamp
	 * reaches keep the v1 per-rail walk below, bit for bit.</p>
	 */
	private int chainDepth(String hex, Position entryPos) {
		final MmtrDirectionalBlockService directional = simulator.mmtrDirectionalBlocks;
		if (directional.hasSection(hex)) {
			final int directionalDepth = directional.chainDepth(hex, entryPos, occupancyTrees, this::junctionRestrictedKey, MAX_DEPTH);
			if (directionalDepth > 0) {
				return directionalDepth;
			}
			// v2 reads the shared occupancy TREES. A hold that only ever went through the v1 per-section
			// reserved-colour channel (a legacy/manual block, or a caller that reserved a colour without
			// writing a footprint) would otherwise read as GREEN here, so the v1 walk still gets to speak -
			// and being the more restrictive of the two is the safe direction for a signal.
			return v1ChainDepth(hex, entryPos);
		}
		return v1ChainDepth(hex, entryPos);
	}

	private int v1ChainDepth(String hex, Position entryPos) {
		final List<Object[]> level = new ObjectArrayList<>();
		level.add(new Object[]{entryPos, hex, entryArcOf(hex, entryPos)});
		for (int depth = 1; depth <= MAX_DEPTH; depth++) {
			for (final Object[] entry : level) {
				// ④: a step is restricted when its section is occupied, OR the junction it enters through
				// (the signal's own node) cannot be cleared, OR the junction it leaves through cannot be
				// cleared - the two holds the motion rules (①/②/③) enforce are now visible in the display.
				final String stepHex = (String) entry[1];
				final Position stepNode = (Position) entry[0];
				if (sectionBlocked(stepHex, (Double) entry[2])
					|| junctionRestricted(stepNode)
					|| junctionRestricted(farEndOf(stepHex, stepNode))) {
					return depth;
				}
			}
			if (depth == MAX_DEPTH) {
				break;
			}
			final List<Object[]> nextLevel = new ObjectArrayList<>();
			for (final Object[] entry : level) {
				final Position node = (Position) entry[0];
				final String curHex = (String) entry[1];
				final double arc = (Double) entry[2];
				final MmtrBlockService.Block section = simulator.mmtrBlocks.blockAt(curHex, arc);
				final Rail rail = byHex.get(curHex);
				final double railLength = rail == null ? 0 : rail.railMath.getLength();
				if (section != null && section.arcToM < railLength - 1e-9) {
					// Another section on the SAME rail: the next step keeps the entry node.
					nextLevel.add(new Object[]{node, curHex, section.arcToM});
				} else {
					continuations(node, curHex, nextLevel);
				}
			}
			if (nextLevel.isEmpty()) {
				break;
			}
			level.clear();
			level.addAll(nextLevel);
		}
		return 0;
	}

	/** ④: whether {@code node} is a junction that cannot be cleared (fouled zone / undecided points). */
	private boolean junctionRestricted(@Nullable Position node) {
		if (node == null) {
			return false;
		}
		return MmtrJunctionState.isUncleared(simulator, node, occupancyTrees == null ? simulator.mmtrOccupancyTrees() : occupancyTrees);
	}

	/** The same test keyed by the {@code x,y,z} node key the v2 section walk uses. */
	private boolean junctionRestrictedKey(String nodeKey) {
		return restrictedNodeKeys().contains(nodeKey);
	}

	/**
	 * The restricted junction keys for this view's occupancy trees, cached: computing them walks every
	 * node's clearance zone, and the v2 chain asks per step.
	 */
	private ObjectOpenHashSet<String> restrictedNodeKeys() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees =
			occupancyTrees == null ? simulator.mmtrOccupancyTrees() : occupancyTrees;
		final int signature = trees == null ? 0 : trees.hashCode();
		if (restrictedNodeCache == null || restrictedNodeSignature != signature) {
			restrictedNodeCache = MmtrJunctionState.unclearedNodeKeys(simulator, trees);
			restrictedNodeSignature = signature;
		}
		return restrictedNodeCache;
	}

	/** The arc of {@code hex}'s entry node in ordered-position-1 space (0 when it is not an endpoint). */
	private double entryArcOf(String hex, Position entryPos) {
		final Rail rail = byHex.get(hex);
		if (rail == null) {
			return 0;
		}
		final double arc = rail.mmtrArcOfEndNode(entryPos);
		return Double.isNaN(arc) ? 0 : arc;
	}

	/**
	 * Whether the section of {@code hex} containing {@code arc} is occupied. The authoritative source is
	 * the per-section reserved signal colour (B3b); a blocked colour that belongs to NO section - a
	 * legacy MTR block or a manual block - conservatively closes the whole rail, which is exactly the
	 * pre-B3b per-rail reading.
	 */
	private boolean sectionBlocked(String hex, double arc) {
		final Rail rail = byHex.get(hex);
		if (rail == null) {
			return false;
		}
		final ObjectArrayList<MmtrBlockService.Block> sections = simulator.mmtrBlocks.blocksOf(hex);
		if (sections.isEmpty()) {
			return rail.mmtrIsCurrentlyBlocked();
		}
		final MmtrBlockService.Block section = simulator.mmtrBlocks.blockAt(hex, arc);
		if (section != null && rail.mmtrIsSignalColorBlocked(section.signalColor)) {
			return true;
		}
		boolean anySectionColorBlocked = false;
		for (final MmtrBlockService.Block candidate : sections) {
			if (rail.mmtrIsSignalColorBlocked(candidate.signalColor)) {
				anySectionColorBlocked = true;
				break;
			}
		}
		return rail.mmtrIsCurrentlyBlocked() && !anySectionColorBlocked;
	}

	/**
	 * Rails the signal chain may continue onto after {@code curHex}, entered from {@code node}:
	 * the SET main route's own next rail when a route is set over {@code curHex} in THIS direction,
	 * otherwise every branch that keeps the travel direction (the conservative pre-A2 rule).
	 */
	private void continuations(Position node, String curHex, List<Object[]> out) {
		final Position far = farEndOf(curHex, node);
		if (far == null) {
			return;
		}
		final String routeNextHex = routeNextRail(curHex, node, far);
		if (routeNextHex != null) {
			out.add(new Object[]{far, routeNextHex, entryArcOf(routeNextHex, far)});
			return;
		}
		final Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(far);
		if (neighbours == null) {
			return;
		}
		neighbours.forEach((otherEnd, rail) -> {
			if (!rail.getHexId().equals(curHex)) {
				// Continue only in the travel direction (dot product with the incoming heading).
				final double dot = (otherEnd.getX() - far.getX()) * (far.getX() - node.getX()) + (otherEnd.getZ() - far.getZ()) * (far.getZ() - node.getZ());
				if (dot > 0) {
					out.add(new Object[]{far, rail.getHexId(), entryArcOf(rail.getHexId(), far)});
				}
			}
		});
	}

	/**
	 * The rail a SET main route runs onto after {@code curHex}, when the route runs through
	 * {@code curHex} from {@code node} to {@code far} (i.e. in the direction being walked). Returns
	 * null when no main route covers the rail, the route is a shunt, or the route leaves the rail
	 * the other way — the caller then falls back to the conservative all-branch walk.
	 */
	private @Nullable String routeNextRail(String curHex, Position node, Position far) {
		for (final MmtrRoute route : routes.routesOverRail(curHex)) {
			if (route.getKind() != MmtrRoute.Kind.MAIN) {
				continue; // a shunt keeps the main head at danger and narrows nothing
			}
			final ObjectArrayList<String> rails = route.getRailHexes();
			for (int i = 0; i + 1 < rails.size(); i++) {
				if (!rails.get(i).equals(curHex)) {
					continue;
				}
				final String nextHex = rails.get(i + 1);
				if (sharesNode(nextHex, far)) {
					return nextHex;
				}
			}
		}
		return null;
	}

	/** Whether {@code hex} has an endpoint at {@code node}. */
	private boolean sharesNode(String hex, Position node) {
		final Position[] ends = railEnds.get(hex);
		return ends != null && (node.equals(ends[0]) || node.equals(ends[1]));
	}

	/** The far endpoint of {@code hex} when its rail is entered from {@code node}. */
	private @Nullable Position farEndOf(String hex, Position node) {
		final Position[] ends = railEnds.get(hex);
		if (ends == null || ends[1] == null) {
			return null;
		}
		return ends[0].equals(node) ? ends[1] : ends[1].equals(node) ? ends[0] : null;
	}
}

package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
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

	public MmtrSignalAspect(Simulator simulator, MmtrRouteRegistry routes) {
		this.simulator = simulator;
		this.routes = routes;
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
	 * How far ahead (in rails, the protected one included) the nearest occupied rail sits when the
	 * signal protecting {@code hex} is approached from {@code entryPos}: 1 = protected rail
	 * occupied, 2 = one rail beyond, 3 = two rails beyond, 0 = clear.
	 */
	private int chainDepth(String hex, Position entryPos) {
		final List<Object[]> level = new ObjectArrayList<>();
		level.add(new Object[]{entryPos, hex});
		for (int depth = 1; depth <= MAX_DEPTH; depth++) {
			for (final Object[] entry : level) {
				final Rail rail = byHex.get((String) entry[1]);
				if (rail != null && rail.mmtrIsCurrentlyBlocked()) {
					return depth;
				}
			}
			if (depth == MAX_DEPTH) {
				break;
			}
			final List<Object[]> nextLevel = new ObjectArrayList<>();
			for (final Object[] entry : level) {
				continuations((Position) entry[0], (String) entry[1], nextLevel);
			}
			if (nextLevel.isEmpty()) {
				break;
			}
			level.clear();
			level.addAll(nextLevel);
		}
		return 0;
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
			out.add(new Object[]{far, routeNextHex});
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
					out.add(new Object[]{far, rail.getHexId()});
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

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
	/** 这份缓存是**按哪台车**排除足迹算出来的（0 = 不排除任何车）。见 {@link #restrictedNodeKeysExcluding(long)}。 */
	private long restrictedNodeCacheVehicleId;

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
		return aspectFrom(railHex, entryNode, 0);
	}

	/**
	 * As above, but ignoring {@code excludeVehicleId}'s own footprints.
	 *
	 * <p>The AWS trigger asks this about the signal the driver is about to pass, and the asking train must
	 * not read its OWN body shadow as the reason that signal is red: on the dev world a train whose shadow
	 * anchor sat at its head had the block ahead painted red by itself, so the AWS horn sounded the moment
	 * the driver touched the throttle and the emergency brake stopped the train dead (notes/112 §4).</p>
	 */
	public Aspect aspectFrom(@Nullable String railHex, @Nullable Position entryNode, long excludeVehicleId) {
		return aspectFrom(railHex, entryNode, excludeVehicleId, null);
	}

	/**
	 * 同上，并可指定**这一趟实际会走的轨**（{@code allowedRails}；{@code null} = 不知道 ⇒ 按岔口所有分支保守走）。
	 *
	 * <p>只有**行车许可**这一条路会传它（{@code MmtrMovementAuthority} 手里有本车的进路）：进路已经 SET
	 * 时"要走哪一支"是已知的，不该被**别的**支上停着的车扣成红灯（现场：库里第一台车停好之后，
	 * 后面每一台去别的股道的车都被它扣住，五台车再也不动）。灯显/镜面那条路不传（它没有"谁的进路"这个信息），
	 * 于是仍是岔区级的保守显示 —— 这一处不对称是刻意的：**灯守岔区，行车许可按进路**。</p>
	 */
	public Aspect aspectFrom(@Nullable String railHex, @Nullable Position entryNode, long excludeVehicleId, java.util.function.@Nullable Predicate<String> allowedRails) {
		if (railHex == null || railHex.isEmpty() || !byHex.containsKey(railHex)) {
			return Aspect.GREEN;
		}
		if (routes.pendingEntryRails().contains(railHex)) {
			return Aspect.RED;
		}
		return entryNode == null ? aspectOf(railHex) : fromDepth(chainDepth(railHex, entryNode, excludeVehicleId, allowedRails));
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
		return chainDepth(hex, entryPos, 0);
	}

	private int chainDepth(String hex, Position entryPos, long excludeVehicleId) {
		return chainDepth(hex, entryPos, excludeVehicleId, null);
	}

	private int chainDepth(String hex, Position entryPos, long excludeVehicleId, java.util.function.@Nullable Predicate<String> allowedRails) {
		/*
		 * notes/166 R4：链**只走新层**（Level 2 行车区间）。
		 *
		 * 原来这里是两半：v2 的有向区间链 + v1 的逐轨回退链（读"预留信号色"通道），取更严的那个。
		 * v1 连同那条颜色通道整层删除了，所以回退也一起消失 —— 占用现在只有一份来源（占用树），
		 * 而且带 excludeVehicleId 能排除问话列车自己（notes/152：否则车被自己的影子扣住）。
		 */
		final java.util.function.Predicate<String> restricted = excludeVehicleId == 0
			? this::junctionRestrictedKey
			: restrictedNodeKeysExcluding(excludeVehicleId)::contains;
		return simulator.mmtrSections.chainDepth(hex, entryPos, occupancyTrees, restricted, MAX_DEPTH, excludeVehicleId, allowedRails);
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
	 * 这个节点算不算"受限"（净空守不住 / 道岔未定），**可以排除某一列车自己的足迹**（notes/152）。
	 *
	 * <p>{@code excludeVehicleId == 0} 时走原来那份全局缓存（行为逐位不变）。</p>
	 */
	private boolean junctionRestrictedFor(@Nullable Position node, long excludeVehicleId) {
		if (node == null) {
			return false;
		}
		final String key = MmtrJunctionState.nodeKey(node);
		return excludeVehicleId == 0 ? junctionRestrictedKey(key) : restrictedNodeKeysExcluding(excludeVehicleId).contains(key);
	}

	/**
	 * The restricted junction keys for this view's occupancy trees, cached: computing them walks every
	 * node's clearance zone, and the v2 chain asks per step.
	 */
	private ObjectOpenHashSet<String> restrictedNodeKeys() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees =
			occupancyTrees == null ? simulator.mmtrOccupancyTrees() : occupancyTrees;
		final int signature = trees == null ? 0 : trees.hashCode();
		if (restrictedNodeCache == null || restrictedNodeSignature != signature || restrictedNodeCacheVehicleId != 0) {
			restrictedNodeCache = MmtrJunctionState.unclearedNodeKeys(simulator, trees);
			restrictedNodeSignature = signature;
			restrictedNodeCacheVehicleId = 0;
		}
		return restrictedNodeCache;
	}

	/**
	 * 受限节点集合，但**把某一列车自己的足迹排除在外**（notes/152）。
	 *
	 * <p>为什么必须有这一份：问话的车**自己的车体**压在岔区里时，那个岔区被算成"净空守不住"，
	 * 于是它前方那架信号按"受限节点"判红 —— <b>车被自己的车体扣在出发信号前</b>。现场读数：
	 * 进路 SET、道岔全部拿到、车速 0，下一区间"别人占=False、占用者=[它自己]"。
	 * 占用那一层早有豁免（{@code isOccupied(..., excludeVehicleId)}），受限节点这一层原来没有 ——
	 * 两处口径不一致就是这条缺陷的根。</p>
	 *
	 * <p>缓存按 {@code (树签名, 车 id)} 认：同一 tick 里同一台车反复走链只算一次。</p>
	 */
	private ObjectOpenHashSet<String> restrictedNodeKeysExcluding(long vehicleId) {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees =
			occupancyTrees == null ? simulator.mmtrOccupancyTrees() : occupancyTrees;
		final int signature = trees == null ? 0 : trees.hashCode();
		if (restrictedNodeCache == null || restrictedNodeSignature != signature || restrictedNodeCacheVehicleId != vehicleId) {
			restrictedNodeCache = MmtrJunctionState.unclearedNodeKeys(simulator, trees, vehicleId);
			restrictedNodeSignature = signature;
			restrictedNodeCacheVehicleId = vehicleId;
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

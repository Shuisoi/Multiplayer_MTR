package org.mtr.core.mmtr.route;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.MmtrRunPlanner;
import org.mtr.core.mmtr.point.MmtrPointAuthority;
import org.mtr.core.mmtr.point.MmtrTurnout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 进路登记表 (S5): the live {@link MmtrRoute} per train, and the lookup the signal layer needs —
 * "which route, if any, is set over this rail?".
 *
 * <p>Deliberately a derived view, not a second state machine: {@link #refresh(long, MmtrPointAuthority)}
 * recomputes establishment from the authoritative {@link MmtrPointAuthority} (the same source the
 * walker elects from), so the two can never disagree. Requests/grants/queueing stay in the point
 * authority; this registry only names and exposes the movement they belong to.</p>
 */
public final class MmtrRouteRegistry {

	private final Map<Long, MmtrRoute> byVehicle = new HashMap<>();

	/**
	 * Install the route of a train. A re-plan (different movement) replaces the previous route; a
	 * repeat of the SAME movement keeps the live object, so a mission whose self-arm retries while
	 * it waits for the interlocking does not churn the route identity every tick.
	 */
	public MmtrRoute request(MmtrRoute route) {
		final MmtrRoute existing = byVehicle.get(route.getVehicleId());
		if (existing != null && existing.sameMovement(route)) {
			return existing;
		}
		byVehicle.put(route.getVehicleId(), route);
		return route;
	}

	public @Nullable MmtrRoute route(long vehicleId) {
		return byVehicle.get(vehicleId);
	}

	/** Drop a train's route (terminal mission, cancel, vehicle deletion). */
	public boolean release(long vehicleId) {
		return byVehicle.remove(vehicleId) != null;
	}

	/**
	 * Recompute a route's establishment state against the turnout authority.
	 *
	 * <p>T1: SET needs BOTH conditions -</p>
	 * <ol>
	 *   <li>every turnout it still needs is granted to its owner, <em>and</em></li>
	 *   <li>every one of those turnouts is physically set to the position this route's leg demands.</li>
	 * </ol>
	 * <p>The second condition is what makes "two mutually exclusive routes both report SET" structurally
	 * impossible rather than merely unlikely: the turnout authority issues one position per switch, and a
	 * route whose leg disagrees with the owner's position drops to PENDING naming the switch and the
	 * conflict. It also covers the position being moved under a route that was already set (operator or
	 * legacy row write). A crossed turnout is neither required nor reported - its hold was released at
	 * the crossing, so naming it would send the operator looking at a point the train has already left.</p>
	 */
	public void refresh(long vehicleId, MmtrPointAuthority authority) {
		final MmtrRoute route = byVehicle.get(vehicleId);
		if (route == null) {
			return;
		}
		/*
		 * ③ T5: **敌对进路不能同时 SET**（后到让先到）。
		 *
		 * 必须排在下面那两个早退**之前** —— "无岔的进路直接 SET" 这条对**无岔的两条对向进路**同样成立，
		 * 而它们恰恰是最典型的敌对情形（同一段单线对开）。放在早退之后，它们会双双 SET。
		 */
		final String enemy = enemyBlockReason(route);
		if (enemy != null) {
			route.applyState(false, enemy);
			return;
		}
		if (route.getForks().isEmpty()) {
			route.applyState(true, "no turnout in this route");
			return;
		}
		if (route.allForksCrossed()) {
			// Route locking releases sectionally: once the train has crossed every turnout, nothing is
			// left to hold - the movement stays set over the rails ahead.
			route.applyState(true, "all turnouts crossed");
			return;
		}
		final ObjectArrayList<String[]> outstanding = new ObjectArrayList<>();
		for (final String[] fork : route.getForks()) {
			if (!route.isForkCrossed(fork)) {
				outstanding.add(fork);
			}
		}
		boolean allGranted = true;
		String blockedReason = "";
		for (final String[] fork : outstanding) {
			final long fx = Long.parseLong(fork[0]);
			final long fy = Long.parseLong(fork[1]);
			final long fz = Long.parseLong(fork[2]);
			final String via = fork[3];
			/*
			 * T1: 物理互斥**先判**。两件事同时成立时（我既没拿到授权、道岔又被别人按在别的位置），
			 * "没拿到授权"只是表象，真正的原因就在这里 —— 而它比 describeForkWait 更具体、更可操作：
			 * 它点名道岔、持有者、道岔当前在哪一位、以及本条腿需要哪一位。
			 */
			final int demand = authority.turnoutDemand(fx, fy, fz, via, Integer.parseInt(fork[4]));
			if (demand != Integer.MIN_VALUE) {
				final int position = authority.physicalPosition(fx, fy, fz);
				if (position != MmtrPointAuthority.NO_PHYSICAL_HOLDER && position != demand) {
					allGranted = false;
					blockedReason = "物理道岔 " + fx + "," + fy + "," + fz + " 被 " + authority.physicalHolder(fx, fy, fz)
						+ " 按在位置 " + position + "（" + describeTurnoutPosition(position) + "），"
						+ "本车需要位置 " + demand + "（" + describeTurnoutPosition(demand) + "）—— 两条进路互斥";
					break;
				}
			}
			if (!authority.isGrantedTo(fx, fy, fz, via, route.getOwner())) {
				allGranted = false;
				break;   // 逐进向的等待理由交给 describeForkWait（点名道岔与持有人/人工位/队列）
			}
		}
		route.applyState(allGranted, allGranted
			? "all turnouts held by " + route.getOwner()
			: blockedReason.isEmpty() ? MmtrRunPlanner.describeForkWait(outstanding, authority, route.getOwner()) : blockedReason);
	}

	/**
	 * T5: 我是不是被一条**比我优先**的敌对进路压住了。
	 *
	 * <p>裁决用**到达序**（{@code requestedMillis}，同刻按 vehicleId）—— 也就是 T1b 裁决链的**兜底那一档**；
	 * 第一档"计划时刻"要等 P 系列把趟次表造出来（notes/118 已把机制与入口 `requestAtomically(..., priorityMillis)`
	 * 留好，只差填值）。所以本片是"冲突裁决"里**不依赖时刻表**的那一半。</p>
	 *
	 * <p><b>判据只看排名，不看对方当前是不是 SET</b> —— 这一条是被用例逼出来的。
	 * 第一版写的是"只在对方**已经 SET** 时才压我"，看起来更保守，其实有个洞：</p>
	 * <pre>
	 *   refresh(v1) 先跑，此时 v2 还没 refresh 过（established=false）⇒ v1 不受压 → SET；
	 *   refresh(v2) 再跑，看到 v1 已 SET，但**排名上 v2 更优先** ⇒ 也不受压 → 两条都 SET。
	 * </pre>
	 * <p>也就是说"谁先被 refresh"会决定结果，而两条都 SET 正是这条规则要消灭的东西。
	 * 改成**纯排名比较**之后结果与刷新顺序无关：一条进路被压住，当且仅当存在一条排名更高的敌对进路
	 * —— 于是"最优先的那条 SET、其余 PENDING"是唯一解，且必然收敛。</p>
	 *
	 * <p><b>诚实边界</b>：排名更高的那条若长时间上不去（它自己的道岔被第三条进路占着），
	 * 我就会被一直压着。这不是死锁（我什么都没持有，构不成循环等待），但它是一种**优先级反转**。
	 * 兜底是：对方一旦不再需要这条进路（任务终态 / 删车），{@code Vehicle} 会 release 掉它，
	 * 我的压制随之消失。真要治它得等时刻表（按计划时刻而不是到达序裁决）。</p>
	 */
	private @Nullable String enemyBlockReason(MmtrRoute route) {
		for (final MmtrEnemyRoutes.Conflict conflict : MmtrEnemyRoutes.opposingConflicts(allRoutes())) {
			final boolean mineIsA = conflict.vehicleA == route.getVehicleId();
			if (!mineIsA && conflict.vehicleB != route.getVehicleId()) {
				continue;
			}
			final long otherId = mineIsA ? conflict.vehicleB : conflict.vehicleA;
			final MmtrRoute other = byVehicle.get(otherId);
			if (other == null || !outranks(other, route)) {
				continue;
			}
			return "敌对进路：与 v" + otherId + " " + conflict.detail + "；对方按到达序优先，本车退出（只有最优先的一条能 SET）";
		}
		return null;
	}

	/** 到达序优先：先申请的先走；同一时刻按 vehicleId 定序（保证结果确定）。 */
	private static boolean outranks(MmtrRoute other, MmtrRoute mine) {
		return other.getRequestedMillis() != mine.getRequestedMillis()
			? other.getRequestedMillis() < mine.getRequestedMillis()
			: other.getVehicleId() < mine.getVehicleId();
	}

	/** 0 = 正线贯通 / 1 = 岔股开放（{@code MmtrTurnout} 的两个位置），给人看的中文。 */
	private static String describeTurnoutPosition(int position) {
		return position == MmtrTurnout.REVERSE ? "岔股开放" : "正线贯通";
	}

	/**
	 * Every SET route running over {@code railHex}, ordered by vehicle id (deterministic feed /
	 * signal output). PENDING routes are not returned: an unset route does not authorise a proceed
	 * aspect.
	 */
	public ObjectArrayList<MmtrRoute> routesOverRail(@Nullable String railHex) {
		final ObjectArrayList<MmtrRoute> out = new ObjectArrayList<>();
		if (railHex == null || railHex.isEmpty()) {
			return out;
		}
		for (final MmtrRoute route : sorted()) {
			if (route.isEstablished() && route.coversRail(railHex)) {
				out.add(route);
			}
		}
		return out;
	}

	/** The first SET route over {@code railHex} (lowest vehicle id), or null. */
	public @Nullable MmtrRoute routeOverRail(@Nullable String railHex) {
		final ObjectArrayList<MmtrRoute> routes = routesOverRail(railHex);
		return routes.isEmpty() ? null : routes.get(0);
	}

	/** Every live route, ordered by vehicle id (ops feed). */
	public ObjectArrayList<MmtrRoute> snapshot() {
		return new ObjectArrayList<>(sorted());
	}

	/**
	 * A2 client mirror: rail hex -&gt; the next rail(s) of every SET MAIN route running over it, in route
	 * order. A route may traverse the SAME rail twice (牵出—推进 / 尽头换向: the real aassdd shunt's
	 * plan had one rail twice), so the value is a list and the consumer picks the entry that shares the
	 * node it is walking toward — the same rule the engine's {@code MmtrSignalAspect} applies. A plain
	 * rail -&gt; rail map would silently keep only the outbound leg and lose the narrowing on the way
	 * back.
	 */
	public Object2ObjectOpenHashMap<String, ObjectArrayList<String>> setMainRouteNextRails() {
		final Object2ObjectOpenHashMap<String, ObjectArrayList<String>> out = new Object2ObjectOpenHashMap<>();
		for (final MmtrRoute route : sorted()) {
			if (!route.isEstablished() || route.getKind() != MmtrRoute.Kind.MAIN) {
				continue;
			}
			final ObjectArrayList<String> rails = route.getRailHexes();
			for (int i = 0; i + 1 < rails.size(); i++) {
				final String from = rails.get(i);
				final String to = rails.get(i + 1);
				final ObjectArrayList<String> nexts = out.computeIfAbsent(from, key -> new ObjectArrayList<>());
				if (!nexts.contains(to)) {
					nexts.add(to);
				}
			}
		}
		return out;
	}

	/** A2 client mirror: rails that are the entry of a PENDING route — their signal shows danger. */
	public ObjectOpenHashSet<String> pendingEntryRails() {
		final ObjectOpenHashSet<String> out = new ObjectOpenHashSet<>();
		for (final MmtrRoute route : sorted()) {
			if (!route.isEstablished()) {
				final String entry = route.getEntryRailHex();
				if (entry != null && !entry.isEmpty()) {
					out.add(entry);
				}
			}
		}
		return out;
	}

	public int size() {
		return byVehicle.size();
	}

	/**
	 * Every installed route regardless of state (SET or PENDING), in the same deterministic order
	 * {@link #routesOverRail} uses.
	 *
	 * <p>For the 闭塞区间 walk's narrowing: a train whose route is still PENDING is already committed to
	 * that movement, so the block it is about to occupy is that route's own leg - narrowing on SET routes
	 * only would leave it on the whole-throat block until the interlocking grants it, which is exactly the
	 * "why is the whole yard one block" symptom.</p>
	 */
	public ObjectArrayList<MmtrRoute> allRoutes() {
		return new ObjectArrayList<>(sorted());
	}

	public void clear() {
		byVehicle.clear();
	}

	private List<MmtrRoute> sorted() {
		final List<MmtrRoute> routes = new ArrayList<>(byVehicle.values());
		routes.sort((a, b) -> Long.compare(a.getVehicleId(), b.getVehicleId()));
		return routes;
	}
}

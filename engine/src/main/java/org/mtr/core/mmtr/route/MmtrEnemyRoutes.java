package org.mtr.core.mmtr.route;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.point.MmtrTurnout;
import org.mtr.core.simulation.Simulator;

/**
 * 敌对进路表 (enemy routes) —— T5 的第一块：**什么算冲突**。
 *
 * <h3>为什么先做成一张"表"而不是直接接进 SET 判定</h3>
 * <p>设计把它列在 T1b 里；当时我推迟了，理由是：把"共用轨段"直接做成 SET 判据，
 * 会让**当前能 SET 的进路变成 PENDING**（例如两列车先后去同一个站台），有回归已验收行为的风险。
 * 所以先做成一个**可计算、可观测、可单测**的派生对象：它只回答"这两条进路敌对吗、因为什么"，
 * 不改变任何状态。要不要接进 SET、以及怎么接，是下一步的事（而且要等时刻表来裁决先后）。</p>
 *
 * <h3>判据（本片实现的两条）</h3>
 * <ol>
 *   <li><b>同一处道岔要求不同位置</b> —— T1 的物理层已经在**权限**上挡住了（互斥需求在道岔上排队），
 *       这里只是把它**报出来**：让运营台与诊断看见"这两条进路为什么不能同时成立"；</li>
 *   <li><b>共用一根轨且方向相反</b> —— 对向 movement 是真正的敌对。
 *       **同向跟随不算敌对**（notes/82 已定）：那由闭塞（S1）按间隔解决，不由联锁互斥解决。
 *       本判据只认"两条进路穿过这根轨的方向相反"，所以跟随不会被误报。</li>
 * </ol>
 *
 * <h3>本片**没有**实现的第三条：侧面防护区重叠</h3>
 * <p>"两条进路从同一处岔口的不同股道同时穿过"确实敌对（现实里要防侧面冲突），
 * 但它**离开时间就判不出来**：同一个岔口一分钟内被两列车先后穿过是正常运营，同时穿过才是冲突。
 * 而"同时"需要位置/时刻，不是光看两条进路的轨集合能决定的 —— 硬按几何判会把每一次
 * "两列车都要过这个咽喉"都报成敌对（假阳性泛滥，表就没人看了）。运行时的侧面防护由 S1 规则 (4)
 * （岔区清限 10 m）在**位置上**已经执行；本表等时刻表（P 系列）能给出时间窗之后再补这一条。</p>
 */
public final class MmtrEnemyRoutes {

	/** 一条敌对的理由。{@code vehicleA} 恒为较小的 id（输出确定性）。 */
	public static final class Conflict {
		public final long vehicleA;
		public final long vehicleB;
		/** {@code TURNOUT} = 同一处道岔要求不同位置；{@code OPPOSING} = 共用一根轨且方向相反。 */
		public final String kind;
		public final String detail;
		/** 两边是否都已经 SET（两条 SET 进路敌对 = 联锁在说谎，最该被看见的一种）。 */
		public final boolean bothSet;

		private Conflict(long vehicleA, long vehicleB, String kind, String detail, boolean bothSet) {
			this.vehicleA = vehicleA;
			this.vehicleB = vehicleB;
			this.kind = kind;
			this.detail = detail;
			this.bothSet = bothSet;
		}

		@Override
		public String toString() {
			return kind + " v" + vehicleA + " vs v" + vehicleB + (bothSet ? "（两条都 SET）" : "") + "：" + detail;
		}
	}

	private MmtrEnemyRoutes() {
	}

	/**
	 * 当前所有活进路两两比对，列出敌对的那些（按 vehicleId 定序，输出确定）。
	 *
	 * <p>纯派生：只读 {@link MmtrRouteRegistry} 与轨图，不改变任何状态、不扣任何车。</p>
	 */
	public static ObjectArrayList<Conflict> conflicts(Simulator simulator) {
		final ObjectArrayList<Conflict> out = new ObjectArrayList<>();
		final ObjectArrayList<MmtrRoute> routes = simulator.mmtrRoutes.allRoutes();
		for (int i = 0; i < routes.size(); i++) {
			for (int j = i + 1; j < routes.size(); j++) {
				final MmtrRoute a = routes.get(i);
				final MmtrRoute b = routes.get(j);
				final boolean bothSet = a.isEstablished() && b.isEstablished();
				turnoutConflict(simulator, a, b, bothSet, out);
				opposingConflict(a, b, bothSet, out);
			}
		}
		return out;
	}

	/** 判据①：同一处道岔，两条进路要求的**位置**不同 ⇒ 物理上不可能同时成立。 */
	private static void turnoutConflict(Simulator simulator, MmtrRoute a, MmtrRoute b, boolean bothSet, ObjectArrayList<Conflict> out) {
		for (final String[] forkA : a.getForks()) {
			for (final String[] forkB : b.getForks()) {
				if (!sameNode(forkA, forkB)) {
					continue;
				}
				final MmtrTurnout turnout = simulator.mmtrTurnout(Long.parseLong(forkA[0]), Long.parseLong(forkA[1]), Long.parseLong(forkA[2]));
				if (turnout == null) {
					continue;
				}
				final int demandA = turnout.positionForLeg(forkA[3], Integer.parseInt(forkA[4]));
				final int demandB = turnout.positionForLeg(forkB[3], Integer.parseInt(forkB[4]));
				if (demandA == Integer.MIN_VALUE || demandB == Integer.MIN_VALUE || demandA == demandB) {
					continue;
				}
				out.add(new Conflict(a.getVehicleId(), b.getVehicleId(), "TURNOUT",
					"道岔 " + forkA[0] + "," + forkA[1] + "," + forkA[2] + "：v" + a.getVehicleId() + " 要位置 " + demandA
						+ "，v" + b.getVehicleId() + " 要位置 " + demandB + "（一处道岔只有两个位置，两者互斥）",
					bothSet));
			}
		}
	}

	/** 判据②：共用一根轨且**方向相反** ⇒ 真正的敌对。同向跟随不报（交给闭塞）。 */
	private static void opposingConflict(MmtrRoute a, MmtrRoute b, boolean bothSet, ObjectArrayList<Conflict> out) {
		final ObjectOpenHashSet<String> shared = new ObjectOpenHashSet<>(a.getRailHexes());
		shared.retainAll(new ObjectOpenHashSet<>(b.getRailHexes()));
		for (final String hex : shared) {
			if (!crossesInOppositeDirections(hex, a, b)) {
				continue;
			}
			out.add(new Conflict(a.getVehicleId(), b.getVehicleId(), "OPPOSING",
				"共用轨 " + hex + " 且方向相反：v" + a.getVehicleId() + " 与 v" + b.getVehicleId() + " 是对向 movement",
				bothSet));
		}
	}

	/**
	 * 两条进路穿过 {@code hex} 的方向是否相反。
	 *
	 * <p>用"进路序列里这根轨的前后邻居"定方向，**不用几何角度** —— 进路序列是走行的**因果顺序**，
	 * 比拿坐标算朝向可靠（曲线轨、反向铺设的轨都能坑到几何算法）。取不到邻居（这根轨是进路端点）时
	 * **不报**：宁可漏报也不误报 —— 这张表是给人看的，假阳性会把它变成噪声。</p>
	 */
	private static boolean crossesInOppositeDirections(String hex, MmtrRoute a, MmtrRoute b) {
		final String exitA = neighbourAfter(a, hex);
		final String exitB = neighbourAfter(b, hex);
		if (exitA == null || exitB == null || exitA.equals(exitB)) {
			return false;   // 取不到出口，或者同向跟随（notes/82：不算敌对）
		}
		final String entryA = neighbourBefore(a, hex);
		final String entryB = neighbourBefore(b, hex);
		return entryA != null && entryB != null && exitA.equals(entryB) && exitB.equals(entryA);
	}

	private static @Nullable String neighbourAfter(MmtrRoute route, String hex) {
		final int index = route.getRailHexes().indexOf(hex);
		return index >= 0 && index + 1 < route.getRailHexes().size() ? route.getRailHexes().get(index + 1) : null;
	}

	private static @Nullable String neighbourBefore(MmtrRoute route, String hex) {
		final int index = route.getRailHexes().indexOf(hex);
		return index > 0 ? route.getRailHexes().get(index - 1) : null;
	}

	private static boolean sameNode(String[] forkA, String[] forkB) {
		return forkA[0].equals(forkB[0]) && forkA[1].equals(forkB[1]) && forkA[2].equals(forkB[2]);
	}
}

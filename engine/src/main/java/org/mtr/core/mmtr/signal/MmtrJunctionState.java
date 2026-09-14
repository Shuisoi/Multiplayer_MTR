package org.mtr.core.mmtr.signal;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehiclePosition;
import org.mtr.core.simulation.Simulator;

/**
 * ④ 显示层: whether a junction node is "not cleared" - the question a signal standing at (or looking at)
 * a junction has to answer before it can show proceed.
 *
 * <p>Two independent reasons, both already enforced by the motion rules (notes/99-101), so the display
 * can now agree with them:</p>
 *
 * <ol>
 *   <li><strong>岔区被占 (②)</strong> — a vehicle's footprint is inside the node's clearance zone (the
 *       first {@link Vehicle#MMTR_JUNCTION_CLEARANCE_M} metres of every rail meeting at the node). A
 *       movement may not be admitted through the junction while another consist still fouls it.</li>
 *   <li><strong>道岔没人定 (①/③)</strong> — the node is a fork and NO approach has an operator branch
 *       or an authority holder: the points have no position, so no route through the junction can be
 *       set. A real interlocking holds the signal at danger for exactly this reason.</li>
 * </ol>
 *
 * <p>Note the deliberate asymmetry: an operator branch row (or a holder) makes the junction "decided"
 * and the display clears, because the free-driving model lets the points decide the path (the real
 * server presets every fork to branch 0 - {@code Simulator.mmtrDefaultPointsZero}). Only a junction
 * nobody has decided, or one physically fouled, restricts the display.</p>
 */
public final class MmtrJunctionState {

	private MmtrJunctionState() {
	}

	/** The {@code x,y,z} key the mirror uses for a node. */
	public static String nodeKey(Position node) {
		return node.getX() + "," + node.getY() + "," + node.getZ();
	}

	/**
	 * Whether {@code node} is a junction that cannot be cleared right now.
	 *
	 * @param trees the occupancy trees to test the clearance zone against (null = skip that test)
	 */
	public static boolean isUncleared(Simulator simulator, Position node, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		return !reason(simulator, node, trees).isEmpty();
	}

	/**
	 * **为什么这个岔口清不掉**（空串 = 清得掉）。诊断用：
	 * 一盏灯为什么是红的，必须能用一条指令读出来，而不是让人去猜规则。
	 */
	public static String reason(Simulator simulator, Position node, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(node);
		if (neighbours == null || neighbours.size() < 3) {
			return "";
		}
		if (trees != null) {
			final Rail fouled = foulingRail(node, neighbours, trees);
			if (fouled != null) {
				return "岔区净空被占：轨 " + shortHex(fouled.getHexId()) + " 靠这个节点的 " + Vehicle.MMTR_JUNCTION_CLEARANCE_M + " m 内有车足迹";
			}
		}
		/*
		 * **单开道岔不是"没人决定的岔口"**（用户 2026-09-13 决策 (b)：一处道岔只有一个位置、只有 0 或 1，
		 * 默认 0，不存在"未知态"）。下面那条"没人决定"的规则对它是**假警报**：位置 0 时岔股那一侧禁止通行，
		 * 它的行写 -1 甚至根本不写。道岔位置本身就是决定，所以这条规则只留给没有物理道岔模型的老岔口
		 * （网页上那种按进向各设 0/1 的）。
		 *
		 * <p>注意 3 度节点上岔股进向只会看到 **1 条**前方轨（两根正线互为反向，只有一根朝前），所以这条
		 * 早退在现有几何下是防御性的；真正会把灯钉在红色的是上面那条"岔区净空被占"。</p>
		 */
		if (simulator.mmtrTurnout(node.getX(), node.getY(), node.getZ()) != null) {
			return "";
		}
		// A fork nobody has decided: an approach whose ordered legs need a CHOICE (>= 2 legs) has neither
		// an operator branch row nor an authority holder. A degree-3 node whose approaches all have a
		// single forward continuation is a plain pass-through (no rows exist for it and none are needed),
		// so it must never restrict the display.
		for (final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap.Entry<Position, Rail> entry : neighbours.object2ObjectEntrySet()) {
			final Rail via = entry.getValue();
			final String viaHex = via.getHexId();
			final it.unimi.dsi.fastutil.objects.ObjectArrayList<org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg> legs =
				org.mtr.core.mmtr.point.MmtrPoint.computeOrderedLegs(node, entry.getKey(), via, neighbours,
					simulator.mmtrJunctionLegs.get(node.getX(), node.getY(), node.getZ(), viaHex));
			if (legs.size() < 2) {
				continue;
			}
			if (simulator.mmtrPointBranches.contains(node.getX(), node.getY(), node.getZ(), viaHex)) {
				continue; // the operator (or the real server's default preset) decided this approach
			}
			if (simulator.mmtrPointAuthority.holder(node.getX(), node.getY(), node.getZ(), viaHex) != null) {
				continue; // an authority grant decides it
			}
			return "岔口没人决定：进向 " + shortHex(viaHex) + " 有 " + legs.size() + " 条腿，既没有人工位也没有授权";
		}
		return "";
	}

	/**
	 * **人工/意图扳岔的闸门**：岔区净空被占时**不许扳** —— 把道岔从车下抽走是脱轨级事故
	 * （用户 2026-09-14 定："车压在岔上就拒绝人工扳岔"）。
	 *
	 * <p>与灯的"清不掉"判定读**同一段** {@link #foulingRail}：显示的规则与动道岔的规则必须同源，
	 * 否则会出现"这盏灯说岔区被占、道岔却照样能扳"这种自相矛盾的状态。</p>
	 *
	 * @return 说清原因的字符串（可直接回给操作者）；{@code null} = 净空干净，可以扳
	 */
	public static @org.jspecify.annotations.Nullable String blockedThrowReason(Simulator simulator, Position node) {
		return blockedThrowReasonExcept(simulator, node, 0);
	}

	/**
	 * 同上，但**把某一列车排除在外**（{@code excludeVehicleId}；0 = 不排除任何车）。
	 *
	 * <p>给"授权申请改道岔位置"那条路用：净空被**别人**占住时不许改位置，而请求方**自己**压在岔上
	 * 不算 —— 它按着自己的位（T1），本来就该能改自己的需要（换端/折返），否则会把自己锁死。
	 * 判定与上一条读**同一段** {@link #foulingRail}（只是多一个排除项），所以两条路不会走偏。</p>
	 */
	public static @org.jspecify.annotations.Nullable String blockedThrowReasonExcept(Simulator simulator, Position node, long excludeVehicleId) {
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(node);
		if (neighbours == null) {
			return null;
		}
		final Rail fouled = foulingRail(node, neighbours, simulator.mmtrOccupancyTrees(), excludeVehicleId);
		if (fouled == null) {
			return null;
		}
		return "岔区净空被占：轨 " + shortHex(fouled.getHexId()) + " 靠这个节点的 " + Vehicle.MMTR_JUNCTION_CLEARANCE_M
			+ " m 内有车足迹 —— 不许把道岔从车下抽走（等车出清这一段再扳）";
	}

	private static String shortHex(String hex) {
		return hex == null || hex.length() <= 8 ? String.valueOf(hex) : hex.substring(0, 8) + "…";
	}

	/** Every uncleared junction node, keyed {@code x,y,z} (what the client mirror needs). */
	public static ObjectOpenHashSet<String> unclearedNodeKeys(Simulator simulator, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		final ObjectOpenHashSet<String> out = new ObjectOpenHashSet<>();
		simulator.positionsToRail.forEach((node, neighbours) -> {
			if (neighbours.size() >= 3 && isUncleared(simulator, node, trees)) {
				out.add(nodeKey(node));
			}
		});
		return out;
	}

	/** 守不住净空的是哪根轨（没有 = 净空干净）；诊断要能点名到轨。 */
	private static Rail foulingRail(Position node, it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Position, Rail> neighbours, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		return foulingRail(node, neighbours, trees, 0);
	}

	/** 同上，但可以把某一列车的足迹排除在外（{@code excludeVehicleId}；0 = 不排除）。 */
	private static Rail foulingRail(Position node, it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Position, Rail> neighbours, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees, long excludeVehicleId) {
		for (final Rail rail : neighbours.values()) {
			final double length = rail.railMath.getLength();
			if (length <= 0) {
				continue;
			}
			final double nodeArc = MmtrBlockService.arcOfNode(rail, node);
			if (Double.isNaN(nodeArc)) {
				continue;
			}
			final double from = nodeArc <= 1e-9 ? 0 : Math.max(0, length - Vehicle.MMTR_JUNCTION_CLEARANCE_M);
			final double to = nodeArc <= 1e-9 ? Math.min(length, Vehicle.MMTR_JUNCTION_CLEARANCE_M) : length;
			if (to - from <= 1e-9) {
				continue;
			}
			final Position[] ordered = rail.mmtrOrderedPositions();
			for (int i = 0; i < trees.size(); i++) {
				final VehiclePosition vehiclePosition = MmtrDirectionalBlockService.footprintOn(trees.get(i), ordered);
				if (vehiclePosition != null && foulsZone(vehiclePosition, from, to, excludeVehicleId)) {
					return rail;
				}
			}
		}
		return null;
	}

	/**
	 * 车是否真的"占住"了净空区 {@code [from, to]}，而不是仅仅压到边界。
	 *
	 * <p>为什么不能只判 {@code getClosestOverlap >= 0}（原来是这么写的）：那是"有任何重叠"，
	 * 而车是实体 —— 停在股道尽头、尾巴刚探过节点的车会压到净空区的边，于是岔口被判"清不掉"，
	 * 正线上那盏灯因此显示**红**，而它本该显示**单黄**（车其实停在下一段里）。
	 * 这与区间占用是同一个毛病，所以用同一条量纲：重叠要占到**车长的一半**（净空区更短时以区间为准），
	 * 再减掉一点整数格误差的松弛。</p>
	 */
	private static boolean foulsZone(VehiclePosition vehiclePosition, double from, double to) {
		return foulsZone(vehiclePosition, from, to, 0);
	}

	/** 同上，但可以把某一列车的足迹排除在外（{@code excludeVehicleId}；0 = 不排除）。 */
	private static boolean foulsZone(VehiclePosition vehiclePosition, double from, double to, long excludeVehicleId) {
		for (final double[] segment : vehiclePosition.segmentsExcluding(excludeVehicleId)) {
			final double footFrom = Math.min(segment[0], segment[1]);
			final double footTo = Math.max(segment[0], segment[1]);
			final double overlap = Math.min(to, footTo) - Math.max(from, footFrom);
			if (overlap <= 0) {
				continue;
			}
			final double footLength = footTo - footFrom;
			final double required = Math.max(0.5, 0.5 * Math.min(footLength, Math.max(to - from, footLength)) - 1.0);
			if (overlap >= required) {
				return true;
			}
		}
		return false;
	}
}

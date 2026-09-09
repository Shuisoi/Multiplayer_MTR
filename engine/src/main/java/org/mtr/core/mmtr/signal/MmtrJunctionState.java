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
		final it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Position, Rail> neighbours = simulator.positionsToRail.get(node);
		if (neighbours == null || neighbours.size() < 3) {
			return false;
		}
		if (trees != null && clearanceZoneFouled(node, neighbours, trees)) {
			return true;
		}
		// A fork nobody has decided: no operator branch row and no authority holder on ANY approach.
		for (final Rail via : neighbours.values()) {
			final String viaHex = via.getHexId();
			if (simulator.mmtrPointBranches.contains(node.getX(), node.getY(), node.getZ(), viaHex)) {
				return false;
			}
			if (simulator.mmtrPointAuthority.holder(node.getX(), node.getY(), node.getZ(), viaHex) != null) {
				return false;
			}
		}
		return true;
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

	/** Whether any vehicle's footprint lies inside the clearance zone of {@code node}. */
	private static boolean clearanceZoneFouled(Position node, it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<Position, Rail> neighbours, ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
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
				final VehiclePosition vehiclePosition = org.mtr.core.data.Data.tryGet(trees.get(i), ordered[0], ordered[1]);
				if (vehiclePosition != null && vehiclePosition.getClosestOverlap(from, to, false, 0) >= 0) {
					return true;
				}
			}
		}
		return false;
	}
}

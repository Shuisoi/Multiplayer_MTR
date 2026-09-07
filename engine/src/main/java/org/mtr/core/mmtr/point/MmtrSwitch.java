package org.mtr.core.mmtr.point;

import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * A turnout (道岔) resolved at the rail graph level. A real switch is NOT the node alone: at a
 * node shared by several lines the same coordinate may hide two independent switches (one per
 * running line). So a switch is keyed by (node, approach rail): coming along {@code viaRail} into
 * {@code node}, the train chooses between {@code branch0} (the straightest continuation) and
 * {@code branch1} (the nearest diverging rail). Branch is operator-set 0/1 (never auto), persisted
 * across restarts, and later exposed to a task/AI layer.
 */
public final class MmtrSwitch {

	public final long nodeX, nodeY, nodeZ;
	public final String viaRailHex;   // the rail a train arrives on
	public final String branch0Hex;   // straightest continuation
	public final String branch1Hex;   // nearest diverging continuation
	public int branch;                // operator setting: 0 or 1 (defaults to 0)

	public MmtrSwitch(long nodeX, long nodeY, long nodeZ, String viaRailHex, String branch0Hex, String branch1Hex) {
		this.nodeX = nodeX;
		this.nodeY = nodeY;
		this.nodeZ = nodeZ;
		this.viaRailHex = viaRailHex;
		this.branch0Hex = branch0Hex;
		this.branch1Hex = branch1Hex;
	}

	public String key() {
		return nodeX + "," + nodeY + "," + nodeZ + "|" + viaRailHex;
	}

	@Override
	public String toString() {
		return key() + " -> " + (branch == 0 ? branch0Hex : branch1Hex);
	}
}

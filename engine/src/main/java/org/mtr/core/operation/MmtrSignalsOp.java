package org.mtr.core.operation;

import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.simulation.Simulator;

/**
 * MMTR wayside signal command (game-side tool / scan upload): register a placed MTR signal
 * light with its facing angle and aspect count, optionally covered-bound to a read target.
 * When {@code node} coordinates are given the read rail is inferred from the clicked node and
 * the light facing ({@code Simulator#mmtrSignalBindAtNode}); otherwise the raw registry op
 * (set with target = rail hex, or remove) is applied.
 */
public final class MmtrSignalsOp implements SerializedDataBase {

	private long x;
	private long y;
	private long z;
	private float angle;
	private int aspects = 2;
	private String op = "set";
	private String target = "";
	private boolean hasNode;
	private long nodeX;
	private long nodeY;
	private long nodeZ;

	public MmtrSignalsOp(long x, long y, long z, float angle, int aspects, String op, String target,
		boolean hasNode, long nodeX, long nodeY, long nodeZ) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.angle = angle;
		this.aspects = aspects;
		this.op = op == null ? "set" : op;
		this.target = target == null ? "" : target;
		this.hasNode = hasNode;
		this.nodeX = nodeX;
		this.nodeY = nodeY;
		this.nodeZ = nodeZ;
	}

	public MmtrSignalsOp(ReaderBase readerBase) {
		updateData(readerBase);
	}

	@Override
	public void updateData(ReaderBase readerBase) {
		x = readerBase.getLong("x", 0);
		y = readerBase.getLong("y", 0);
		z = readerBase.getLong("z", 0);
		angle = (float) readerBase.getDouble("angle", 0);
		aspects = readerBase.getInt("aspects", 2);
		op = readerBase.getString("op", "set");
		target = readerBase.getString("target", "");
		hasNode = readerBase.getBoolean("hasNode", false);
		nodeX = readerBase.getLong("nodeX", 0);
		nodeY = readerBase.getLong("nodeY", 0);
		nodeZ = readerBase.getLong("nodeZ", 0);
	}

	@Override
	public void serializeData(WriterBase writerBase) {
		writerBase.writeLong("x", x);
		writerBase.writeLong("y", y);
		writerBase.writeLong("z", z);
		writerBase.writeDouble("angle", angle);
		writerBase.writeInt("aspects", aspects);
		writerBase.writeString("op", op);
		if (!target.isEmpty()) {
			writerBase.writeString("target", target);
		}
		writerBase.writeBoolean("hasNode", hasNode);
		writerBase.writeLong("nodeX", nodeX);
		writerBase.writeLong("nodeY", nodeY);
		writerBase.writeLong("nodeZ", nodeZ);
	}

	public void apply(Simulator simulator) {
		if (hasNode) {
			simulator.mmtrSignalBindAtNode((int) x, (int) y, (int) z, angle, aspects, nodeX, nodeY, nodeZ);
		} else {
			simulator.mmtrSignalOp((int) x, (int) y, (int) z, angle, aspects, op, target);
		}
	}
}

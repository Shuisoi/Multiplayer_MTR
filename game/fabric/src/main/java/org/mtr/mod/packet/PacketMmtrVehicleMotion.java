package org.mtr.mod.packet;

import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.client.MmtrVehicleMotionClient;

/**
 * **列车运动流**的服务端 → 客户端搬运包（notes/369 ①）。
 *
 * <h2>这一层只做一件事：把 16 位字搬过去</h2>
 * <p>线格式（记录种类、字节数、位域）**全部**在引擎侧
 * （{@code org.mtr.core.mmtr.net.MmtrMotionFrame}）：那里是纯 Java、有用例把"每条记录多少字节"钉死。
 * 这里只是把它打包成 {@code writeChar} 序列 —— MTR 的 mapping 层没有字节数组，而 {@code char} 正好 2 字节
 * （{@code javap} 实测 {@code PacketBufferSender/Receiver} 两边逐一对称）。</p>
 *
 * <h2>为什么不用 JSON</h2>
 * <p>JSON 的键名就是这个包的全部开销。旧协议"每秒一份 7.4 KB 整份快照"里的九成是路线名/站名/编组
 * 这些**不变**的东西，而位置每帧都要到 —— 两件事挤在一条通道上，于是要么费带宽、要么卡
 * （notes/368 实测：客户端平均 7313 B/包、1 s 一个）。</p>
 */
public final class PacketMmtrVehicleMotion extends PacketHandler {

	/**
	 * 一个包最多带多少 16 位字。防御性的上限：一帧正常是"视距内车辆数 × 十几字节"
	 * （实测视野内 1–2 辆），32 辆车 + 若干边沿记录也就几百字节。收到超限的包只读前这么多，
	 * 而不是按对方给的数字分配内存。
	 */
	private static final int MAX_CHARS = 8192;

	private final char[] frame;

	public PacketMmtrVehicleMotion(PacketBufferReceiver packetBufferReceiver) {
		final int length = Math.max(0, Math.min(packetBufferReceiver.readInt(), MAX_CHARS));
		frame = new char[length];
		for (int i = 0; i < length; i++) {
			frame[i] = packetBufferReceiver.readChar();
		}
	}

	public PacketMmtrVehicleMotion(char[] frame) {
		this.frame = frame;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeInt(frame.length);
		for (final char value : frame) {
			packetBufferSender.writeChar(value);
		}
	}

	/**
	 * 客户端侧：解码并落到镜像上。
	 *
	 * <p>运行在客户端主线程（与 MTR 既有 S2C 包同一个口径：{@code PacketOpenBlockEntityScreen}
	 * 在 {@code runClient} 里直接开界面），所以这里可以安全地写镜像状态。</p>
	 */
	@Override
	public void runClient() {
		MmtrVehicleMotionClient.receive(frame);
	}
}

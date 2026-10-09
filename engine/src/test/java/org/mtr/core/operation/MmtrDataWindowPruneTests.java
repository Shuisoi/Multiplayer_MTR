package org.mtr.core.operation;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.ClientData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **客户端主动拉数据是"按窗口替换"，不是合并**（notes/410 §事故：前视窗口把玩家身后的轨删掉）。
 *
 * <h2>为什么要有这条用例</h2>
 * <p>2026-10-09 现场症状：钢轨与列车随移动**不规律地消失/出现**（"走到节点左边就只剩左边的轨"）。
 * 根因是客户端那条"前视拉取"（{@code MmtrDynamicLoad}）把 {@code DataRequest} 的**圆心放到了
 * 相机前方 192 米**、半径只有 128 米 —— 而 {@link DataResponse#write()} 的语义是：</p>
 *
 * <pre>
 *   data.rails.removeIf(rail -&gt; !railsToKeep.contains(rail.getHexId()));   // 窗口外的全删
 *   data.rails.addAll(rails);                                              // 窗口内的补上
 * </pre>
 *
 * <p>于是玩家身后与两侧的轨，被每次响应合法地删掉；一移动窗口就移动，删除集合随之变化。</p>
 *
 * <h2>本用例钉住的三件事（都是**引擎侧的事实**，不是客户端的实现细节）</h2>
 * <ol>
 *   <li><b>前视窗口会删掉窗口外的轨</b>：圆心在相机前方时，身后的轨确实会没 —— 这就是现场那一眼；</li>
 *   <li><b>相机窗口不会删</b>：只要窗口覆盖相机（并大到足以含住前方），同一份客户端数据一条不少；</li>
 *   <li><b>删只发生在"这一份响应里有新东西"的时候</b>（第 54 行那个 {@code if}）：所以症状是"不规律"
 *       —— 只在移动进新区域、有新内容到达的那一次才发作。</li>
 * </ol>
 *
 * <p>第 3 条特别容易被误读成"偶尔"。它不是随机：**没新内容的那一次响应根本不删**，这也解释了为什么
 * 站着不动时看不到症状。</p>
 */
public final class MmtrDataWindowPruneTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	/** 身后的轨：x ∈ [0, 20]。 */
	private static final Rail BEHIND = rail(0, 20);
	/** 前方窗口内的轨：x ∈ [200, 220]。 */
	private static final Rail IN_WINDOW = rail(200, 220);
	/** 更前方、只在某个窗口里才第一次出现的轨：x ∈ [300, 320]。 */
	private static final Rail FURTHER = rail(300, 320);

	private static Rail rail(int x1, int x2) {
		return Rail.newSidingRail(new Position(x1, 0, 0), Angle.E, new Position(x2, 0, 0), Angle.E, Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
	}

	private static Simulator newSimulator(String savePath) {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
		simulator.rails.add(BEHIND);
		simulator.rails.add(IN_WINDOW);
		simulator.rails.add(FURTHER);
		simulator.sync();
		return simulator;
	}

	/** 服务器按 {@code DataRequest} 的窗口组一份响应，客户端照 {@code PacketRequestData} 那条路落地。 */
	private static void applyWindow(Simulator simulator, ClientData client, int centreX, long radius) {
		final DataRequest request = new DataRequest(UUID.randomUUID(), new Position(centreX, 0, 0), radius);
		request.writeExistingIds(client);
		final DataResponse response = request.getData(simulator);
		final JsonObject json = Utilities.getJsonObjectFromData(response);
		new DataResponse(new JsonReader(json), client).write();
		System.out.println("[WINDOW] 圆心 x=" + centreX + " 半径 " + radius + " ⇒ 客户端轨 " + hexes(client) + "（" + client.rails.size() + " 条）");
	}

	private static String hexes(ClientData client) {
		final StringBuilder builder = new StringBuilder();
		client.rails.forEach(rail -> {
			if (builder.length() > 0) {
				builder.append(", ");
			}
			builder.append(rail.railMath.minX);
		});
		return builder.toString();
	}

	private static boolean has(ClientData client, Rail rail) {
		return client.railIdMap.containsKey(rail.getHexId());
	}

	@Test
	public void aForwardShiftedWindowDeletesTheRailsBehindTheCamera() {
		final Simulator simulator = newSimulator("build/mmtr-data-window-forward");
		final ClientData client = new ClientData();

		// 第一步：一次覆盖相机的宽窗口，客户端拿到身后的轨与前方窗口内的轨（更远那条还没进来）。
		applyWindow(simulator, client, 0, 250);
		assertTrue(has(client, BEHIND), "the wide, camera-centred window brings the rail behind the camera");
		assertTrue(has(client, IN_WINDOW), "and the rail ahead of it");
		assertNull(client.railIdMap.get(FURTHER.getHexId()), "the further rail is outside that window, so the client does not have it yet");

		// 第二步：圆心在相机前方 192 米、半径 128 米 —— 本类第一版就是这么拉的。窗口里有新东西（FURTHER），
		// 于是 DataResponse.write() 的删除分支被触发，身后的轨被合法删掉。
		applyWindow(simulator, client, 192, 128);
		assertTrue(has(client, FURTHER), "the forward window does bring the new rail further ahead");
		assertTrue(has(client, IN_WINDOW), "the rail inside the forward window survives");
		assertTrue(!has(client, BEHIND), "★ 身后的轨被这次响应删掉了 —— 这就是现场「走到节点左边就只剩左边的轨」");
	}

	@Test
	public void aCameraCentredWindowNeverDeletesAnyRailItCovers() {
		final Simulator simulator = newSimulator("build/mmtr-data-window-camera");
		final ClientData client = new ClientData();

		applyWindow(simulator, client, 0, 250);
		assertTrue(has(client, BEHIND), "the client has the rail behind the camera");

		// 修好之后的形状：圆心 = 相机，半径 = max(渲染距离 × 16, lead + slice) = 320 —— 窗口覆盖相机，
		// 也含住前方。这一份里同样有新东西（FURTHER），删除分支照旧会被触发，但窗口把它盖住了。
		applyWindow(simulator, client, 0, 320);
		assertTrue(has(client, BEHIND), "★ 相机窗口不会删掉身后的轨（修好之后的行为）");
		assertTrue(has(client, IN_WINDOW), "前方的轨仍在");
		assertTrue(has(client, FURTHER), "更前方的轨也进来了");
		assertEquals(3, client.rails.size(), "一条都没少");
	}

	@Test
	public void aResponseWithoutNewContentPrunesNothing() {
		final Simulator simulator = newSimulator("build/mmtr-data-window-keeponly");
		final ClientData client = new ClientData();

		applyWindow(simulator, client, 0, 250);
		assertTrue(has(client, BEHIND), "the client has the rail behind the camera");

		// 同一个窗口再来一次：里面全是客户端已有的东西 ⇒ rails 为空 ⇒ DataResponse.write() 的删除分支
		// 根本不跑（第 54 行的 if）。这条解释了症状为什么是"不规律"而不是"每次移动都掉"。
		applyWindow(simulator, client, 0, 250);
		assertTrue(has(client, BEHIND), "a keep-only response prunes nothing - even a window that does not cover a rail");
		assertEquals(2, client.rails.size(), "the client still has exactly the rails it had");
	}

	@Test
	public void theRequestWindowIsWhatDecidesWhatSurvives() {
		// 同一份客户端数据、同一条轨：窗口盖住它就活，窗口漏掉它就死 —— 与"这轨离客户端远不远"无关，
		// 只与**这一份响应的 railsToKeep**有关。所以规矩只有一条：客户端的窗口必须覆盖相机。
		final Simulator simulator = newSimulator("build/mmtr-data-window-decides");
		final ClientData covered = new ClientData();
		applyWindow(simulator, covered, 0, 250);
		applyWindow(simulator, covered, 0, 320);
		assertTrue(has(covered, BEHIND), "covered ⇒ survives");

		final ClientData missed = new ClientData();
		applyWindow(simulator, missed, 0, 250);
		applyWindow(simulator, missed, 192, 128);
		assertTrue(!has(missed, BEHIND), "missed by the window ⇒ deleted, even though nothing about the rail changed");
	}

	@Test
	public void theFixtureRailsAreWhereTheAssertionsThinkTheyAre() {
		assertNotNull(rail(0, 20).getHexId());
		assertEquals(20, BEHIND.railMath.getLength(), 0.5, "BEHIND is the 20 m rail at x = 0..20");
		assertEquals(200.0, IN_WINDOW.railMath.minX, 0.5, "IN_WINDOW starts at x = 200");
		assertEquals(300.0, FURTHER.railMath.minX, 0.5, "FURTHER starts at x = 300");
	}
}

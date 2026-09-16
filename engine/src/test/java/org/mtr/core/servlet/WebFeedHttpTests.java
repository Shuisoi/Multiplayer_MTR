package org.mtr.core.servlet;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mtr.core.Main;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照层（notes/172）的**端到端**用例：重复杂请求不再花 tick，内容没变就回 304。
 *
 * <p>要证的那句话是：「网页刷新地图不再吃掉游戏 tick」。所以判据不是"响应对不对"，
 * 而是 **{@link Simulator#getMmtrWebRunCount()} 有没有涨** —— 那个数就是"这一路请求
 * 在模拟线程上跑了几次"。快速连问两次、或者带 ETag 再问一次，它都不该动。</p>
 */
public final class WebFeedHttpTests {

	private static final int PORT = 8890; // 不与 RuntimeTests 的 8889 抢端口
	private static final Path TEST_DIRECTORY = Paths.get("build/test-data-web-feed");

	private static Rail through(Position p1, Position p2) {
		final double bearing = Math.toDegrees(Math.atan2(p2.getZ() - p1.getZ(), p2.getX() - p1.getX()));
		return Rail.newRail(p1, Angle.fromAngle((float) bearing), p2, Angle.fromAngle((float) (bearing + 180)),
			Rail.Shape.QUADRATIC, 0, new it.unimi.dsi.fastutil.objects.ObjectArrayList<>(), 80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** 一次 HTTP 往返的结果（状态码 / ETag / 正文）。 */
	private record HttpResult(int status, @Nullable String etag, String body) {
	}

	private static HttpResult request(String path, @Nullable String ifNoneMatch, @Nullable String postBody) {
		final HttpUriRequest httpUriRequest = postBody == null ? new HttpGet("http://localhost:" + PORT + path) : new HttpPost("http://localhost:" + PORT + path);
		if (httpUriRequest instanceof HttpPost) {
			try {
				((HttpPost) httpUriRequest).setEntity(new StringEntity(postBody, java.nio.charset.StandardCharsets.UTF_8));
			} catch (Exception e) {
				throw new IllegalStateException("could not attach the request body", e);
			}
		}
		if (ifNoneMatch != null) {
			httpUriRequest.addHeader("If-None-Match", ifNoneMatch);
		}
		try (final CloseableHttpClient httpClient = HttpClients.createDefault()) {
			try (final CloseableHttpResponse httpResponse = httpClient.execute(httpUriRequest)) {
				final String etag = httpResponse.getFirstHeader("ETag") == null ? null : httpResponse.getFirstHeader("ETag").getValue();
				final String body = httpResponse.getEntity() == null ? "" : EntityUtils.toString(httpResponse.getEntity());
				return new HttpResult(httpResponse.getStatusLine().getStatusCode(), etag, body);
			}
		} catch (Exception e) {
			throw new IllegalStateException("HTTP request to " + path + " failed", e);
		}
	}

	@Test
	public void repeatedReadsAreAnsweredWithoutTouchingTheSimulatorThread() throws Exception {
		if (Files.exists(TEST_DIRECTORY)) {
			org.apache.commons.io.FileUtils.deleteDirectory(TEST_DIRECTORY.toFile());
		}
		final Main main = new Main(TEST_DIRECTORY, PORT, true, false, null, "test");
		try {
			final Simulator simulator = main.getSimulator("test");
			assertNotNull(simulator);
			// 一条 200 m 轨 + 起点一盏朝东的灯：够让区间/灯/拓扑这些接口有东西可发。
			simulator.rails.add(through(new Position(0, 0, 0), new Position(200, 0, 0)));
			simulator.sync();
			simulator.mmtrSignals.put(0, 0, 0, 270, 4, "AUTO", "");
			Thread.sleep(200);

			// ① 第一次：快照层手上没有这一路 ⇒ 这一次请求回模拟线程算，并把它发布出去。
			//    （这一刻还没有 ETag 可给：响应是"刚算出来"直接回的那一条，不是已发布的那一份。）
			final HttpResult first = request("/mtr/api/map/mmtr-track-sections", null, null);
			assertEquals(200, first.status);
			assertTrue(first.body.contains("\"trackSections\""), "第一次就该拿到真数据：" + first.body);
			final long afterFirst = simulator.getMmtrWebRunCount();
			assertTrue(afterFirst >= 1L, "第一次总得有人算一遍");

			// ② 紧接着再问：手上那一份还够新（轨道区间 1 s 窗口）⇒ 直接由 Jetty 线程答，tick 一次都不该花。
			final HttpResult second = request("/mtr/api/map/mmtr-track-sections", null, null);
			assertEquals(200, second.status);
			assertNotNull(second.etag, "从快照答出来的必须带 ETag");
			assertTrue(second.body.contains("\"trackSections\""), "内容照旧");
			assertEquals(afterFirst, simulator.getMmtrWebRunCount(), "够新的一拍不能再进模拟线程");

			// ②b 同一代再问一次：**连响应文本都该一模一样**（"同一代同一份"正是 304 能成立的前提；
			//     第一次那条不是从快照答的，所以它的 currentTime 是响应时刻、与这一条不同，不该拿来比）。
			final HttpResult secondAgain = request("/mtr/api/map/mmtr-track-sections", null, null);
			assertEquals(second.body, secondAgain.body, "同一代发的应该是同一份（含信封）");
			assertEquals(second.etag, secondAgain.etag);
			assertEquals(afterFirst, simulator.getMmtrWebRunCount(), "同一代内再问还是不该进 tick");

			// ③ 带 ETag 再问：内容没变 ⇒ 304，同样不进模拟线程。
			final HttpResult third = request("/mtr/api/map/mmtr-track-sections", second.etag, null);
			assertEquals(304, third.status, "内容没变就该是 304");
			assertEquals("", third.body, "304 不带正文");
			assertEquals(afterFirst, simulator.getMmtrWebRunCount(), "304 更不该进模拟线程");

			// ④ 静态那一档（拓扑 5 s 窗口）：隔一会儿再问仍在窗口内 ⇒ 还是不进 tick。
			final HttpResult topologyFirst = request("/mtr/api/map/mmtr-topology", null, null);
			assertEquals(200, topologyFirst.status);
			final long afterTopology = simulator.getMmtrWebRunCount();
			Thread.sleep(1_200L);
			final HttpResult topologySecond = request("/mtr/api/map/mmtr-topology", null, null);
			assertEquals(200, topologySecond.status);
			assertEquals(afterTopology, simulator.getMmtrWebRunCount(), "静态接口在窗口内重复问也不该进 tick");
			assertEquals(topologyFirst.body, topologySecond.body);
			assertNotEquals(0, simulator.getMmtrWebServedFromSnapshot(), "快照命中数要真的在涨");

			// ⑤ 写接口照旧走模拟线程：网页扳岔/下指令不能被快照层悄悄吞掉。
			final long beforeWrite = simulator.getMmtrWebRunCount();
			final HttpResult write = request("/mtr/api/map/mmtr-point-op", null, "{\"x\":0,\"y\":0,\"z\":0,\"via\":\"\"}");
			assertEquals(200, write.status);
			assertTrue(simulator.getMmtrWebRunCount() > beforeWrite, "写接口必须在模拟线程上执行");

			System.out.println("[WEB-FEED] webRuns=" + simulator.getMmtrWebRunCount()
				+ " servedFromSnapshot=" + simulator.getMmtrWebServedFromSnapshot()
				+ " published=" + simulator.getMmtrWebPublished()
				+ " queue=" + simulator.getMmtrWebQueueSize());
		} finally {
			main.stop();
		}
	}
}

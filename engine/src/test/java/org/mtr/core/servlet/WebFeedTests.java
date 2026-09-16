package org.mtr.core.servlet;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.mtr.core.servlet.WebFeed.Entry;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 快照发布器（notes/172）的**语义**用例：谁答、谁去重算、什么时候能回 304。
 *
 * <p>这些事情错了不会报错，只会表现为"网页偶尔看到旧的一帧"或者"tick 又被吃掉了"，
 * 所以判据必须钉在代码里，而不是靠现场感觉。</p>
 */
public final class WebFeedTests {

	private static JsonObject body(int value) {
		final JsonObject jsonObject = new JsonObject();
		jsonObject.addProperty("value", value);
		return jsonObject;
	}

	/** 窗口内重复请求读同一份：多开几个标签不再等于多算几遍。 */
	@Test
	public void aFreshEntryAnswersEveryRequesterWithinItsWindow() {
		final WebFeed webFeed = new WebFeed();
		final JsonObject published = webFeed.publish("mmtr-points", body(1));

		final Entry first = webFeed.serve("mmtr-points", 5_000L);
		final Entry second = webFeed.serve("mmtr-points", 5_000L);
		assertNotNull(first);
		assertNotNull(second);
		assertSame(published, first.body, "答的就是发布出去的那一份");
		assertSame(first, second, "同一代只应有一个 Entry 对象");
		assertEquals(1L, webFeed.getPublished(), "没有人重算过第二次");
		assertEquals(2L, webFeed.getServedFromSnapshot(), "两次都算「从快照答出」");
	}

	/** 从来没有过的那一路：这一次请求负责算（返回 null 让调用方回模拟线程）。 */
	@Test
	public void anUnknownEndpointAsksTheCallerToBuildIt() {
		final WebFeed webFeed = new WebFeed();
		assertNull(webFeed.serve("mmtr-topology", 1_000L));
		assertEquals(0L, webFeed.getServedFromSnapshot(), "没答出去就不算快照命中");
	}

	/** 过期之后**只有一个人**回去重算，其余请求读手上这一份 —— 否则十个标签就是十份重算。 */
	@Test
	public void onlyOneRequesterPaysForAStaleEntry() {
		final WebFeed webFeed = new WebFeed();
		final JsonObject published = webFeed.publish("mmtr-signals", body(1));

		// maxAge 给 -1 ⇒ 恒判过期（免得"刚好落在同一毫秒里"这种偶发通过）。
		assertNull(webFeed.serve("mmtr-signals", -1L), "第一个发现过期的负责去重算");
		final Entry other = webFeed.serve("mmtr-signals", -1L);
		assertNotNull(other, "后面那些不该跟着排队");
		assertSame(published, other.body);
	}

	/** 内容没变 ⇒ 代次不动 ⇒ 客户端能一路拿到 304（"世界没动"那一档的网络开销就是这么省下来的）。 */
	@Test
	public void anUnchangedBodyKeepsItsVersionAndEtag() {
		final WebFeed webFeed = new WebFeed();
		webFeed.publish("mmtr-topology", body(1));
		final Entry first = webFeed.serve("mmtr-topology", 5_000L);
		assertNotNull(first);

		// 重建会 new 一个新的 JsonObject：内容相同也必须沿用代次，否则 304 永远开不出来。
		webFeed.publish("mmtr-topology", body(1));
		final Entry sameContent = webFeed.serve("mmtr-topology", 5_000L);
		assertNotNull(sameContent);
		assertNotSame(first, sameContent, "发布的是新的一份");
		assertEquals(first.etag(), sameContent.etag(), "内容没变就还是那一版");

		webFeed.publish("mmtr-topology", body(2));
		final Entry changed = webFeed.serve("mmtr-topology", 5_000L);
		assertNotNull(changed);
		assertNotEquals(first.etag(), changed.etag(), "内容变了代次就要变");
		assertEquals(3L, webFeed.getPublished());
	}

	/** 响应文本与字节都只算一次（序列化与编码搬到了 Jetty 线程上，同一代不该重复做）。 */
	@Test
	public void theResponseIsBuiltOncePerVersion() {
		final WebFeed webFeed = new WebFeed();
		webFeed.publish("mmtr-lamps", body(1));
		final Entry entry = webFeed.serve("mmtr-lamps", 5_000L);
		assertNotNull(entry);

		final AtomicInteger calls = new AtomicInteger();
		final String text = entry.responseText(() -> {
			calls.incrementAndGet();
			return "{\"data\":1}";
		});
		assertEquals("{\"data\":1}", text);
		assertSame(text, entry.responseText(() -> {
			calls.incrementAndGet();
			return "不该被算出来的第二份";
		}));
		assertEquals(1, calls.get(), "同一代的响应文本只构建一次");

		final byte[] bytes = entry.responseBytes(() -> {
			calls.incrementAndGet();
			return "不该被算出来的第三份";
		});
		assertEquals(10, bytes.length);
		assertEquals(text, new String(bytes, StandardCharsets.UTF_8));
		assertEquals(1, calls.get(), "字节是同一份文本编码出来的，不重新构建");
	}

	/** 长时间没人看 ⇒ 快照丢掉（大 JSON 不该一直占着内存）。 */
	@Test
	public void anIdleFeedDropsItsSnapshots() {
		final WebFeed webFeed = new WebFeed();
		webFeed.publish("mmtr-points", body(1));
		assertEquals(1, webFeed.size());

		webFeed.sweep(System.currentTimeMillis());
		assertEquals(1, webFeed.size(), "刚问过就不该清");

		webFeed.sweep(System.currentTimeMillis() + 10 * 60_000L);
		assertEquals(0, webFeed.size(), "十分钟没人问就该丢掉");
	}

	/** 无人问津时零开销：不发请求就永远不会有重建（这一层没有定时器）。 */
	@Test
	public void nothingHappensWithoutRequests() {
		final WebFeed webFeed = new WebFeed();
		assertEquals(0L, webFeed.getPublished());
		assertEquals(0L, webFeed.getServedFromSnapshot());
		assertEquals(0, webFeed.size());
	}
}

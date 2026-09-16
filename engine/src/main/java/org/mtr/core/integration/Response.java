package org.mtr.core.integration;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import org.mtr.core.generated.integration.ResponseSchema;
import org.mtr.core.tool.Utilities;

public final class Response extends ResponseSchema {

	@Nullable
	public final JsonObject data;

	public Response(int code, String text, @Nullable JsonObject data) {
		this(code, System.currentTimeMillis(), text, data);
	}

	/**
	 * 带显式时刻的信封。
	 *
	 * <p>只读接口走快照发布（notes/172）：同一份数据要能被多个请求复用，所以 {@code currentTime}
	 * 记的是**这份数据的构建时刻**，不是响应时刻 —— 否则每条响应都不一样，"内容没变"就永远判不出来，
	 * 客户端也就拿不到 304。要精确的响应时刻看 HTTP 头 {@code X-MMTR-Snapshot-Age-Millis}。</p>
	 */
	public Response(int code, long currentTime, String text, @Nullable JsonObject data) {
		super(code, currentTime, text, 1);
		this.data = data;
	}

	public JsonObject getJson() {
		final JsonObject jsonObject = Utilities.getJsonObjectFromData(this);
		if (data != null) {
			jsonObject.add("data", data);
		}
		return jsonObject;
	}
}

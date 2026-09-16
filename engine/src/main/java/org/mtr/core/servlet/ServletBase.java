package org.mtr.core.servlet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.Nullable;
import org.mtr.core.integration.Response;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.servlet.WebFeed.Entry;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Common HTTP plumbing for every servlet exposed by the simulator.
 *
 * <p>Routes incoming requests to the right {@link Simulator} (via the {@code dimension} query
 * parameter, or all simulators when {@code dimensions=all}), parses the request body once into
 * a {@link JsonReader}, then defers to the subclass-implemented
 * {@link #getContent(String, String, Object2ObjectAVLTreeMap, JsonReader, Simulator, Consumer)}
 * which runs on the simulator thread to keep state mutation single-threaded.</p>
 *
 * <p><b>只读接口的例外（notes/172）</b>：{@link #tryServeFromSnapshot} 允许一路纯只读接口
 * 直接从已发布的快照作答，整条路径在 Jetty 线程上走完，**不进模拟线程**。这条路径是"网页刷新不再
 * 吃掉游戏 tick"的关键 —— 嵌入式运行时模拟线程就是 MC 服务端主线程。</p>
 */
@Log4j2
public abstract class ServletBase extends HttpServlet {

	private final ObjectImmutableList<Simulator> simulators;

	protected ServletBase(ObjectImmutableList<Simulator> simulators) {
		this.simulators = simulators;
	}

	@Override
	protected void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
		doPost(httpServletRequest, httpServletResponse);
	}

	@Override
	protected void doPost(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
		AsyncContext asyncContext = null;
		try {
			asyncContext = httpServletRequest.startAsync();
			asyncContext.setTimeout(0);
			final JsonElement jsonElement = JsonParser.parseReader(httpServletRequest.getReader());
			final JsonReader jsonReader = new JsonReader(jsonElement.isJsonNull() ? new JsonObject() : jsonElement);

			if (tryGetParameter(httpServletRequest, "dimensions").equals("all")) {
				simulators.forEach(simulator -> run(httpServletRequest, null, null, jsonReader, simulator));
				buildResponseObject(httpServletResponse, asyncContext, null, HttpResponseStatus.OK);
			} else {
				int dimension = 0;
				try {
					dimension = Integer.parseInt(tryGetParameter(httpServletRequest, "dimension"));
				} catch (NumberFormatException e) {
					// Fall back to dimension 0 when the query parameter is missing or malformed.
					log.debug("Falling back to dimension 0; could not parse 'dimension' query parameter", e);
				}

				if (dimension < 0 || dimension >= simulators.size()) {
					buildResponseObject(httpServletResponse, asyncContext, null, HttpResponseStatus.BAD_REQUEST, "Invalid Dimension");
				} else {
					run(httpServletRequest, httpServletResponse, asyncContext, jsonReader, simulators.get(dimension));
				}
			}
		} catch (Exception e) {
			log.error("Failed to handle servlet request to {}", httpServletRequest.getRequestURI(), e);
			final HttpResponseStatus httpResponseStatus = e instanceof JsonParseException ? HttpResponseStatus.BAD_REQUEST : HttpResponseStatus.INTERNAL_SERVER_ERROR;
			if (asyncContext != null) {
				buildResponseObject(httpServletResponse, asyncContext, null, httpResponseStatus, "Servlet Exception");
			} else {
				try {
					httpServletResponse.setStatus(httpResponseStatus.code);
					httpServletResponse.addHeader("Content-Type", getMimeType("json"));
					httpServletResponse.addHeader("Access-Control-Allow-Origin", "*");
					httpServletResponse.getWriter().write(new Response(httpResponseStatus.code, httpResponseStatus.description, null).getJson().toString());
				} catch (Exception responseException) {
					log.error("Failed to send fallback servlet error response", responseException);
				}
			}
		}
	}

	/**
	 * Subclass hook: produce the JSON body for one resolved request.
	 *
	 * @param endpoint     first path segment after the servlet's base path
	 * @param data         second path segment (typically a hex id), or {@code ""} if absent
	 * @param parameters   query-string parameters keyed by name
	 * @param jsonReader   reader over the parsed request body
	 * @param simulator    simulator the request was routed to
	 * @param sendResponse callback fed either the response body, or {@code null} to signal a 404
	 */
	protected abstract void getContent(String endpoint, String data, Object2ObjectAVLTreeMap<String, String> parameters, JsonReader jsonReader, Simulator simulator, Consumer<@Nullable JsonObject> sendResponse);

	/**
	 * 钩子：这一路接口能不能**不碰模拟线程**就答出来（notes/172）。
	 *
	 * <p>返回 {@code null}（默认）＝照旧排队到模拟线程去算；返回快照＝由本条 Jetty 线程直接写出去，
	 * 这一次请求对游戏 tick 的开销是 <b>0</b>。</p>
	 *
	 * <p>只有**纯只读**接口可以接这个钩子 —— 写接口必须回到模拟线程，那是唯一改状态的地方。
	 * 实现方（见 {@code SystemMapServlet.FEEDS}）自己保证这一点。</p>
	 */
	protected @Nullable Entry tryServeFromSnapshot(String endpoint, String data, Object2ObjectAVLTreeMap<String, String> parameters, JsonReader jsonReader, Simulator simulator) {
		return null;
	}

	private void run(HttpServletRequest httpServletRequest, @Nullable HttpServletResponse httpServletResponse, @Nullable AsyncContext asyncContext, JsonReader jsonReader, Simulator simulator) {
		final String endpoint;
		final String data;
		final String path = httpServletRequest.getPathInfo();
		if (path != null) {
			final String[] pathSplit = path.substring(1).split("\\.")[0].split("/");
			endpoint = pathSplit.length > 0 ? pathSplit[0] : "";
			data = pathSplit.length > 1 ? pathSplit[1] : "";
		} else {
			endpoint = "";
			data = "";
		}

		final Object2ObjectAVLTreeMap<String, String> parameters = new Object2ObjectAVLTreeMap<>();
		httpServletRequest.getParameterMap().forEach((key, values) -> {
			if (values.length > 0) {
				parameters.put(key, values[0]);
			}
		});

		/*
		 * 快照直发：已经有发布好的那一份就在本线程写完，不排队。
		 *
		 * `dimensions=all` 的场合没有可写的响应（httpServletResponse == null），所以不走这里 ——
		 * 否则只会把"这一路有人想要"的标记立起来、却没人去算，那一路就永远停在旧的一份上。
		 */
		if (httpServletResponse != null && asyncContext != null) {
			final Entry snapshot = tryServeFromSnapshot(endpoint, data, parameters, jsonReader, simulator);
			if (snapshot != null) {
				sendSnapshot(httpServletRequest, httpServletResponse, asyncContext, snapshot, endpoint, data);
				return;
			}
		}

		// 走模拟线程：读接口的"第一次/过期那一拍"与全部写接口都在这里。排队时带上接口名，慢了好点名。
		simulator.runWeb(endpoint, () -> getContent(endpoint, data, parameters, jsonReader, simulator, (@Nullable JsonObject jsonObject) -> {
			if (httpServletResponse != null && asyncContext != null) {
				buildResponseObject(httpServletResponse, asyncContext, jsonObject, jsonObject == null ? HttpResponseStatus.NOT_FOUND : HttpResponseStatus.OK, endpoint, data);
			}
		}));
	}

	/**
	 * 写出一份快照：带 {@code ETag}，并处理条件请求。
	 *
	 * <p>客户端带着同一个 {@code ETag} 回来时就回 304 —— 那一刻**连序列化都不做**，
	 * 于是"世界没动"的那些拍只花一次头部比较（快照的代次只在内容真的变了时才前进，见 {@link WebFeed#publish}）。</p>
	 */
	private void sendSnapshot(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, AsyncContext asyncContext, Entry snapshot, String endpoint, String data) {
		final String etag = snapshot.etag();
		httpServletResponse.addHeader("ETag", etag);
		httpServletResponse.addHeader("X-MMTR-Snapshot-Age-Millis", String.valueOf(Math.max(0L, System.currentTimeMillis() - snapshot.builtAtMillis)));
		if (etag.equals(httpServletRequest.getHeader("If-None-Match"))) {
			try {
				httpServletResponse.setStatus(HttpResponseStatus.NOT_MODIFIED.code);
				asyncContext.complete();
			} catch (Exception e) {
				log.error("Failed to complete a 304 snapshot response", e);
			}
			return;
		}
		sendResponse(httpServletResponse, asyncContext, snapshot.responseBytes(() -> buildResponseJson(snapshot.body, HttpResponseStatus.OK, snapshot.builtAtMillis, endpoint, data)), getMimeType("json"), HttpResponseStatus.OK);
	}

	/**
	 * Asynchronously stream {@code content} to {@code httpServletResponse} as a UTF-8 byte array
	 * with the given {@code contentType} and HTTP status. Used by every servlet to flush its
	 * JSON / static-asset payload from the simulator thread without blocking it.
	 */
	public static void sendResponse(HttpServletResponse httpServletResponse, AsyncContext asyncContext, String content, String contentType, HttpResponseStatus httpResponseStatus) {
		sendResponse(httpServletResponse, asyncContext, content.getBytes(StandardCharsets.UTF_8), contentType, httpResponseStatus);
	}

	/**
	 * As above, but takes the payload already encoded.
	 *
	 * <p>快照路径用它：同一代的字节只编码一次（{@link WebFeed.Entry#responseBytes}），
	 * 于是"多个标签读同一份"连编码都不重复。</p>
	 */
	public static void sendResponse(HttpServletResponse httpServletResponse, AsyncContext asyncContext, byte[] contentBytes, String contentType, HttpResponseStatus httpResponseStatus) {
		try {
			final ServletOutputStream servletOutputStream = httpServletResponse.getOutputStream();
			final int[] contentPosition = {0};
			httpServletResponse.addHeader("Content-Type", contentType);
			httpServletResponse.addHeader("Access-Control-Allow-Origin", "*");
			if (httpResponseStatus == HttpResponseStatus.REDIRECT) {
				httpServletResponse.addHeader("Location", new String(contentBytes, StandardCharsets.UTF_8));
			}
			servletOutputStream.setWriteListener(new WriteListener() {
				@Override
				public void onWritePossible() {
					try {
						while (servletOutputStream.isReady()) {
							final int remainingBytes = contentBytes.length - contentPosition[0];
							if (remainingBytes <= 0) {
								httpServletResponse.setStatus(httpResponseStatus.code);
								asyncContext.complete();
								return;
							}
							final int chunkSize = Math.min(remainingBytes, 8192);
							servletOutputStream.write(contentBytes, contentPosition[0], chunkSize);
							contentPosition[0] += chunkSize;
						}
					} catch (Exception e) {
						log.error("Failed to write servlet response", e);
					}
				}

				@Override
				public void onError(Throwable throwable) {
					log.error("Servlet write callback failed", throwable);
					asyncContext.complete();
				}
			});
		} catch (Exception e) {
			log.error("Failed to send servlet response", e);
		}
	}

	/**
	 * Map a file extension to its MIME type. Falls back to {@code "text/<ext>"} for anything not
	 * explicitly listed — sufficient for the small set of static files the dashboard ships.
	 */
	public static String getMimeType(String fileName) {
		final String[] fileNameSplit = fileName.split("\\.");
		final String fileExtension = fileNameSplit.length == 0 ? "" : fileNameSplit[fileNameSplit.length - 1];
		return switch (fileExtension) {
			case "js" -> "text/javascript";
			case "json" -> "application/json";
			default -> "text/" + fileExtension;
		};
	}

	protected static String removeLastSlash(String text) {
		if (text.isEmpty()) {
			return text;
		} else if (text.charAt(text.length() - 1) == '/') {
			return text.substring(0, text.length() - 1);
		} else {
			return text;
		}
	}

	private static void buildResponseObject(HttpServletResponse httpServletResponse, AsyncContext asyncContext, @Nullable JsonObject data, HttpResponseStatus httpResponseStatus, String... parameters) {
		sendResponse(httpServletResponse, asyncContext, buildResponseJson(data, httpResponseStatus, System.currentTimeMillis(), parameters), getMimeType("json"), httpResponseStatus);
	}

	/**
	 * 信封（`{code, currentTime, text, version, data}`）的文本。
	 *
	 * <p>抽出来是为了快照路径能**复用同一份**：同一个代次只构建一次（见
	 * {@link WebFeed.Entry#responseText}）。{@code currentTime} 由调用方给：
	 * 快照接口给的是**数据构建时刻**，其余接口给的是响应时刻（notes/172）。</p>
	 */
	private static String buildResponseJson(@Nullable JsonObject data, HttpResponseStatus httpResponseStatus, long currentTime, String... parameters) {
		final StringBuilder reasonPhrase = new StringBuilder(httpResponseStatus.description);
		final String trimmedParameters = Arrays.stream(parameters).filter(parameter -> !parameter.isEmpty()).collect(Collectors.joining(", "));
		if (!trimmedParameters.isEmpty()) {
			reasonPhrase.append(" - ").append(trimmedParameters);
		}
		return new Response(httpResponseStatus.code, currentTime, reasonPhrase.toString(), data).getJson().toString();
	}

	private static String tryGetParameter(HttpServletRequest httpServletRequest, String parameter) {
		return httpServletRequest.getParameterMap().getOrDefault(parameter, new String[]{""})[0];
	}
}

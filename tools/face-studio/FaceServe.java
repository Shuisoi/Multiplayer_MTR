import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * **车辆动态面工作室的本地服务器**（notes/359 · F2）—— 只用 JDK 的 {@code com.sun.net.httpserver}，零依赖。
 *
 * <h2>三个根</h2>
 * <ul>
 *   <li>{@code /} 及静态文件 —— 工作室自己的页面（{@code --web}，默认 = 本 class 所在的 tools/face-studio）；</li>
 *   <li>{@code /data/<工作区相对路径>} —— **只读**地把工作区里的锚点 JSON 喂给页面（{@code --root}），
 *       于是 {@code ?anchors=/data/sandbox/.../mmtr_anchors_x.json} 这条链接能直接打开一块真面，
 *       不用每次手动选文件。<b>这里永远不给写能力</b>；</li>
 *   <li>{@code POST /save} —— 绘制器保存用的**唯一**写入口（F2 后半段）。body = {@code {"path":"<工作区相对路径>","content":"<文本>"}}。</li>
 * </ul>
 *
 * <h2>为什么绑回环 + 白名单 + 先备份</h2>
 * <p>这是一个"给作者在自己机器上开着的工具"，不是服务：只绑 {@code 127.0.0.1}。</p>
 * <p>写端点只认三个目录（规范化之后必须落在里面）且扩展名必须是 {@code .json} 或 {@code .java}：</p>
 * <ul>
 *   <li>{@code sandbox/} —— 试画、探针、截图、以及「导出 Java 骨架」的落点；</li>
 *   <li>{@code mmtr/tools/} —— 工作室自己、打包器的车辆配置（面文档的**事实来源**就在这里）；</li>
 *   <li>{@code mmtr/game/fabric/run/resourcepacks/} —— 直接改一份已解包/在用的资源包。</li>
 * </ul>
 * <p><b>为什么连 {@code .java} 也放开了</b>（F4 加的）：工作室的「导出 Java 骨架」要给扩展作者
 * 一份能直接编译的 {@code .java}，而它得落在工作区里（这台机器上 java 进程的 {@code %TEMP%} 不可写，
 * 见 {@code MmtrFaceExtensionTests} 里同一条注释）。所以写端点的判据从"必须是 .json"放宽成
 * "必须是 .json 或 .java"——**目录白名单与先备份两条一个字都没放松**，而且
 * {@code mmtr/game/} 的 Java **不在**任何白名单目录里（那条硬规矩靠白名单挡着，不靠扩展名）。</p>
 * <p>其余一律 403 并回一句人话；覆盖已有文件前先在**同目录**写一份 {@code <文件名>.bak-studio}；
 * 目录不存在就创建；除了 {@code POST /save} 之外的方法与路径仍然是 405 / 404。
 * 目录穿越（{@code ..}、绝对路径）在规范化之后会被 {@code startsWith} 挡掉 —— 与 {@code /data} 同一套守卫。</p>
 */
public final class FaceServe {

	private static final Map<String, String> CONTENT_TYPES = new LinkedHashMap<>();

	/** 写端点允许落地的目录（工作区相对路径，用 {@code /} 写，解析时按平台走）。 */
	private static final String[] WRITABLE_DIRS = {
			"sandbox",
			"mmtr/tools",
			"mmtr/game/fabric/run/resourcepacks",
	};

	/** 请求体上限：面文档是文本 JSON，8 MiB 已经很宽松了（防的是"手滑指向一个巨大的东西"）。 */
	private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

	/**
	 * 写端点允许的扩展名。
	 *
	 * <p>{@code .json} 是面文档与车辆配置；{@code .java} 是「导出 Java 骨架」（F4 加的，
	 * 见类注释里那段"为什么连 .java 也放开了"）。别的后缀仍然一律 403 —— 这条放宽只服务于
	 * "把工作室生成的那份骨架交到作者手里"，不是开放任意写。</p>
	 */
	private static final String[] WRITABLE_EXTENSIONS = {".json", ".java"};

	static {
		CONTENT_TYPES.put("html", "text/html; charset=utf-8");
		CONTENT_TYPES.put("js", "text/javascript; charset=utf-8");
		CONTENT_TYPES.put("mjs", "text/javascript; charset=utf-8");   // ★ 工作室的模块用 .mjs（Node 也能直接跑）
		CONTENT_TYPES.put("css", "text/css; charset=utf-8");
		CONTENT_TYPES.put("json", "application/json; charset=utf-8");
		CONTENT_TYPES.put("png", "image/png");
		CONTENT_TYPES.put("svg", "image/svg+xml");
	}

	public static void main(String[] args) throws Exception {
		final Map<String, String> options = new LinkedHashMap<>();
		for (int i = 0; i < args.length - 1; i++) {
			if (args[i].startsWith("--")) {
				options.put(args[i].substring(2), args[i + 1]);
			}
		}
		final Path web = Path.of(options.getOrDefault("web", ".")).toAbsolutePath().normalize();
		final Path root = Path.of(options.getOrDefault("root", ".")).toAbsolutePath().normalize();
		final int port = Integer.parseInt(options.getOrDefault("port", "8910"));
		if (!Files.isDirectory(web)) {
			throw new IllegalArgumentException("--web 不是目录：" + web);
		}

		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server.createContext("/data/", exchange -> serve(exchange, root, true));
		server.createContext("/save", exchange -> save(exchange, root));
		server.createContext("/", exchange -> serve(exchange, web, false));
		server.setExecutor(null);
		server.start();
		System.out.println("[FACE-STUDIO] 动态面工作室： http://127.0.0.1:" + port + "/");
		System.out.println("[FACE-STUDIO] 页面 = " + web);
		System.out.println("[FACE-STUDIO] 数据根（/data 只读）= " + root);
		System.out.println("[FACE-STUDIO] 写端点 = POST /save（白名单：" + String.join("、", WRITABLE_DIRS) + "；只写 "
				+ String.join("/", WRITABLE_EXTENSIONS) + "）");
		System.out.println("[FACE-STUDIO] 停止：Ctrl+C，或 face-studio.ps1 -Action stop");
	}

	// ---- 只读：页面与 /data ------------------------------------------------------------------------

	private static void serve(HttpExchange exchange, Path base, boolean dataMount) throws IOException {
		// 写入口只有 POST /save 一个：别的地方来了 POST 就明说，不要静默地当成 GET 处理
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			respondError(exchange, 405, "这里只支持 GET（写只有 POST /save 一个入口）。");
			return;
		}
		final String path = exchange.getRequestURI().getPath();
		String relative = dataMount ? path.substring("/data".length()) : path;
		if (relative.isEmpty() || relative.equals("/")) {
			relative = "/index.html";
		}
		// 目录穿越守卫：规范化之后必须还在这个根里
		final Path file = base.resolve(relative.substring(1)).normalize();
		if (!file.startsWith(base) || !Files.isRegularFile(file)) {
			respond(exchange, 404, "text/plain; charset=utf-8", ("not found: " + path).getBytes(StandardCharsets.UTF_8));
			return;
		}
		final String name = file.getFileName().toString();
		final int dot = name.lastIndexOf('.');
		final String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
		respond(exchange, 200, CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"), Files.readAllBytes(file));
	}

	// ---- 写：POST /save ---------------------------------------------------------------------------

	/** {@code POST /save}：body = {@code {"path":"…","content":"…"}}。 */
	private static void save(HttpExchange exchange, Path root) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			respondError(exchange, 405, "/save 只认 POST（body = {\"path\":\"…\",\"content\":\"…\"}）。");
			return;
		}
		final byte[] body;
		try {
			body = readBody(exchange);
		} catch (IOException e) {
			respondError(exchange, 400, "读请求体失败：" + e.getMessage());
			return;
		}
		if (body.length == 0) {
			respondError(exchange, 400, "请求体是空的，期望 {\"path\":\"…\",\"content\":\"…\"}。");
			return;
		}

		final Map<String, String> fields;
		try {
			fields = parseSaveBody(new String(body, StandardCharsets.UTF_8));
		} catch (IllegalArgumentException e) {
			respondError(exchange, 400, "请求体读不了：" + e.getMessage());
			return;
		}
		final String relative = fields.getOrDefault("path", "").trim();
		final String content = fields.getOrDefault("content", "");
		if (relative.isEmpty()) {
			respondError(exchange, 400, "path 是空的 —— 要写哪个文件？（工作区相对路径，例如 sandbox\\face-studio\\a.json）");
			return;
		}

		// ① 规范化后必须还在工作区里（挡目录穿越与绝对路径）
		final Path target = root.resolve(relative.replace('\\', '/')).normalize();
		if (!target.startsWith(root)) {
			respondError(exchange, 403, "path 跑到工作区外面去了：" + relative + "（只允许工作区内的相对路径）");
			return;
		}
		// ② 白名单目录
		final String allowed = allowedDirOf(root, target);
		if (allowed == null) {
			respondError(exchange, 403, "只能保存到这些目录里的 " + String.join("/", WRITABLE_EXTENSIONS) + "："
					+ String.join("、", WRITABLE_DIRS)
					+ "。你写的是 " + root.relativize(target) + " —— 想改引擎源码/构建产物请另走 git。");
			return;
		}
		// ③ 只写 .json / .java
		final String name = target.getFileName() == null ? "" : target.getFileName().toString();
		if (!writableExtension(name)) {
			respondError(exchange, 403, "只允许写 " + String.join(" 或 ", WRITABLE_EXTENSIONS) + "（这一个文件是「" + name
					+ "」）—— 面文档与车辆配置是 JSON，导出 Java 骨架是 .java。");
			return;
		}

		String backupPath = "";
		try {
			final Path parent = target.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			if (Files.isRegularFile(target)) {
				final Path backup = target.resolveSibling(name + ".bak-studio");
				Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
				backupPath = backup.toString();
			}
			Files.write(target, content.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			respondError(exchange, 500, "写文件失败：" + e.getMessage());
			return;
		}

		System.out.println("[FACE-STUDIO] 已保存 " + target + (backupPath.isEmpty() ? "（新建）" : "（备份 " + backupPath + "）")
				+ "：" + content.length() + " 字符");
		final Map<String, String> result = new LinkedHashMap<>();
		result.put("saved", target.toString());
		result.put("backup", backupPath);
		respondJson(exchange, 200, result);
	}

	/** 目标落在哪个白名单目录里（不在就 null）。 */
	private static String allowedDirOf(Path root, Path target) {
		for (final String dir : WRITABLE_DIRS) {
			final Path base = root.resolve(dir).normalize();
			if (target.startsWith(base)) {
				return dir;
			}
		}
		return null;
	}

	/** 文件名是不是写端点允许的后缀（{@link #WRITABLE_EXTENSIONS}，大小写不敏感）。 */
	private static boolean writableExtension(String name) {
		final String lower = name.toLowerCase(Locale.ROOT);
		for (final String extension : WRITABLE_EXTENSIONS) {
			if (lower.endsWith(extension)) {
				return true;
			}
		}
		return false;
	}

	private static byte[] readBody(HttpExchange exchange) throws IOException {
		try (final java.io.InputStream in = exchange.getRequestBody()) {
			final byte[] all = in.readNBytes(MAX_BODY_BYTES + 1);
			if (all.length > MAX_BODY_BYTES) {
				throw new IOException("请求体超过 " + MAX_BODY_BYTES + " 字节上限");
			}
			return all;
		}
	}

	/**
	 * 只解析 {@code {"path":"…","content":"…"}} 这两个**字符串**键（零依赖，所以不引 JSON 库）。
	 * 认键序、认空白；值不是字符串、JSON 不合法、有别的类型 ⇒ 抛 {@link IllegalArgumentException}（带人话）。
	 */
	static Map<String, String> parseSaveBody(String text) {
		final Map<String, String> out = new LinkedHashMap<>();
		int i = skipWhitespace(text, 0);
		if (i >= text.length() || text.charAt(i) != '{') {
			throw new IllegalArgumentException("最外层不是一个对象（要以 { 开头）");
		}
		i = skipWhitespace(text, i + 1);
		if (i < text.length() && text.charAt(i) == '}') {
			return out;
		}
		while (i < text.length()) {
			if (text.charAt(i) != '"') {
				throw new IllegalArgumentException("键要从双引号开始（第 " + i + " 个字符处是「" + text.charAt(i) + "」）");
			}
			final StringBuilder key = new StringBuilder();
			i = readString(text, i, key);
			i = skipWhitespace(text, i);
			if (i >= text.length() || text.charAt(i) != ':') {
				throw new IllegalArgumentException("键「" + key + "」后面少了冒号");
			}
			i = skipWhitespace(text, i + 1);
			if (i >= text.length() || text.charAt(i) != '"') {
				throw new IllegalArgumentException("键「" + key + "」的值必须是字符串（面文档是文本）");
			}
			final StringBuilder value = new StringBuilder();
			i = readString(text, i, value);
			out.put(key.toString(), value.toString());
			i = skipWhitespace(text, i);
			if (i < text.length() && text.charAt(i) == ',') {
				i = skipWhitespace(text, i + 1);
				continue;
			}
			if (i < text.length() && text.charAt(i) == '}') {
				i = skipWhitespace(text, i + 1);
				if (i != text.length()) {
					throw new IllegalArgumentException("对象结束之后还有多余内容");
				}
				return out;
			}
			throw new IllegalArgumentException("键「" + key + "」后面既不是逗号也不是右花括号");
		}
		throw new IllegalArgumentException("对象没有闭合（少了右花括号）");
	}

	/** 从 {@code text[from]} 的引号开始读一个字符串（含转义），返回闭合引号之后的下标。 */
	private static int readString(String text, int from, StringBuilder out) {
		int i = from + 1;
		while (i < text.length()) {
			final char c = text.charAt(i);
			if (c == '"') {
				return i + 1;
			}
			if (c != '\\') {
				out.append(c);
				i++;
				continue;
			}
			if (i + 1 >= text.length()) {
				throw new IllegalArgumentException("转义符后面没有字符");
			}
			final char escape = text.charAt(i + 1);
			switch (escape) {
				case '"': out.append('"'); i += 2; break;
				case '\\': out.append('\\'); i += 2; break;
				case '/': out.append('/'); i += 2; break;
				case 'b': out.append('\b'); i += 2; break;
				case 'f': out.append('\f'); i += 2; break;
				case 'n': out.append('\n'); i += 2; break;
				case 'r': out.append('\r'); i += 2; break;
				case 't': out.append('\t'); i += 2; break;
				case 'u': {
					if (i + 5 >= text.length()) {
						throw new IllegalArgumentException("\\u 转义不完整");
					}
					final String hex = text.substring(i + 2, i + 6);
					try {
						out.append((char) Integer.parseInt(hex, 16));
					} catch (NumberFormatException e) {
						throw new IllegalArgumentException("\\u" + hex + " 不是十六进制");
					}
					i += 6;
					break;
				}
				default:
					throw new IllegalArgumentException("不认识的转义 \\" + escape);
			}
		}
		throw new IllegalArgumentException("字符串没有闭合（少了右引号）");
	}

	private static int skipWhitespace(String text, int from) {
		int i = from;
		while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
			i++;
		}
		return i;
	}

	// ---- 回话 ---------------------------------------------------------------------------------------

	private static void respondError(HttpExchange exchange, int status, String message) throws IOException {
		final Map<String, String> body = new LinkedHashMap<>();
		body.put("error", message);
		respondJson(exchange, status, body);
	}

	private static void respondJson(HttpExchange exchange, int status, Map<String, String> fields) throws IOException {
		final List<String> parts = new ArrayList<>();
		for (final Map.Entry<String, String> entry : fields.entrySet()) {
			parts.add('"' + escapeJson(entry.getKey()) + "\":\"" + escapeJson(entry.getValue()) + '"');
		}
		final String json = "{" + String.join(",", parts) + "}";
		respond(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
	}

	/** Windows 路径里有反斜杠，JSON 里必须转义（不然页面读到的路径是坏的）。 */
	private static String escapeJson(String text) {
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			final char c = text.charAt(i);
			switch (c) {
				case '"': out.append("\\\""); break;
				case '\\': out.append("\\\\"); break;
				case '\n': out.append("\\n"); break;
				case '\r': out.append("\\r"); break;
				case '\t': out.append("\\t"); break;
				default:
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
			}
		}
		return out.toString();
	}

	private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		// 改完页面/JSON 刷新就该看到新的：一律不缓存
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, body.length);
		try (final OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}
}

package org.mtr.core.servlet;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 从磁盘目录发前端静态文件的 servlet（**按字节**，不是按字符串）。
 *
 * <p>为什么不能复用 {@link WebServlet}：那条链路的内容类型是 {@code Function<String, String>}，
 * 发送时用 {@code content.getBytes(UTF_8)}。这对文本没问题，但对 woff2 / png 这类二进制资源
 * 必然损坏——从磁盘读二进制还会直接抛 {@code MalformedInputException}，于是回退成 index.html
 * （实测：请求字体拿到 422 字节的首页，而不是 400 多 KB 的 woff2）。</p>
 *
 * <p>这是"前后端分离"的落点：Java 只负责把目录里的文件原样发出去，网页怎么构建、什么时候更新
 * 都由前端自己决定（改完 <code>npm run build</code>，刷新浏览器即可，不必打 jar、不必重启服务端）。</p>
 */
@Log4j2
public final class StaticFileServlet extends HttpServlet {

	/** 静态根目录（已规范化）。 */
	private final Path base;
	/** 相对根目录的索引文件，访问 "/" 或目录时使用。 */
	private final String indexFile;
	/** URL 前缀（一般是 "/"）。 */
	private final String expectedPath;

	public StaticFileServlet(Path base, String expectedPath, String indexFile) {
		this.base = base.toAbsolutePath().normalize();
		this.expectedPath = expectedPath;
		this.indexFile = indexFile;
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		final AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(0);
		try {
			String path = request.getRequestURI();
			if (path.startsWith(expectedPath)) {
				path = path.substring(expectedPath.length());
			}
			if (path.startsWith("/")) {
				path = path.substring(1);
			}
			if (path.isEmpty() || path.endsWith("/")) {
				path = path + indexFile;
			}
			// 去掉查询串并规范化；越出根目录一律拒绝（防目录穿越）。
			final Path resolved = base.resolve(path).normalize();
			if (!resolved.startsWith(base) || !Files.isRegularFile(resolved)) {
				// 未知路径回退到首页（单页应用的行为），与 jar 内嵌那份的语义保持一致。
				final Path index = base.resolve(indexFile);
				write(response, asyncContext, Files.isRegularFile(index) ? Files.readAllBytes(index) : null, indexFile);
				return;
			}
			write(response, asyncContext, Files.readAllBytes(resolved), path);
		} catch (Exception exception) {
			log.warn("Failed to serve static file {}", request.getRequestURI(), exception);
			try {
				write(response, asyncContext, null, indexFile);
			} catch (Exception ignored) {
				asyncContext.complete();
			}
		}
	}

	private void write(HttpServletResponse response, AsyncContext asyncContext, byte @Nullable [] content, String fileName) throws Exception {
		final byte[] body = content == null ? new byte[0] : content;
		response.setStatus(content == null ? 404 : 200);
		response.addHeader("Content-Type", mimeType(fileName));
		response.addHeader("Access-Control-Allow-Origin", "*");
		// 前端资源带内容哈希，可以放心长效缓存；index.html 不带哈希，必须每次校验。
		response.addHeader("Cache-Control", fileName.equals(indexFile) ? "no-cache" : "public, max-age=31536000, immutable");
		final var outputStream = response.getOutputStream();
		final int[] position = {0};
		outputStream.setWriteListener(new WriteListener() {
			@Override
			public void onWritePossible() {
				try {
					while (outputStream.isReady()) {
						final int remaining = body.length - position[0];
						if (remaining <= 0) {
							asyncContext.complete();
							return;
						}
						final int chunk = Math.min(remaining, 8192);
						outputStream.write(body, position[0], chunk);
						position[0] += chunk;
					}
				} catch (Exception exception) {
					log.warn("Failed to write static file {}", fileName, exception);
					asyncContext.complete();
				}
			}

			@Override
			public void onError(Throwable throwable) {
				log.warn("Static file write error {}", fileName, throwable);
				asyncContext.complete();
			}
		});
	}

	/** 常见前端资源的 MIME 类型（Jetty 默认表对 woff2 等不认识，这里显式列出）。 */
	private static String mimeType(String fileName) {
		final int dot = fileName.lastIndexOf('.');
		final String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
		return switch (extension) {
			case "html", "htm" -> "text/html; charset=utf-8";
			case "js", "mjs" -> "text/javascript; charset=utf-8";
			case "css" -> "text/css; charset=utf-8";
			case "json" -> "application/json; charset=utf-8";
			case "map" -> "application/json; charset=utf-8";
			case "svg" -> "image/svg+xml";
			case "png" -> "image/png";
			case "jpg", "jpeg" -> "image/jpeg";
			case "gif" -> "image/gif";
			case "webp" -> "image/webp";
			case "ico" -> "image/x-icon";
			case "woff2" -> "font/woff2";
			case "woff" -> "font/woff";
			case "ttf" -> "font/ttf";
			case "otf" -> "font/otf";
			case "txt" -> "text/plain; charset=utf-8";
			case "wasm" -> "application/wasm";
			default -> "application/octet-stream";
		};
	}
}

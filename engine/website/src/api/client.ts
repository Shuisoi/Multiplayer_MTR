/**
 * 引擎 HTTP 接口客户端。
 *
 * <p>前端与服务端是分开的两套东西：Java 只负责提供静态文件与这些 HTTP 接口
 * （见 `engine/src/main/java/org/mtr/core/servlet/StaticFileServlet.java`），
 * **所有判断都在服务端做**，网页只负责把返回的数据画出来。所以这里不做任何业务推断，
 * 只负责取数、剥信封、转成前端实体。</p>
 *
 * <p>接口基址默认走同源相对路径 `"/mtr/api/"`。开发时前端由 Vite 提供（5173），
 * 由 `vite.config.ts` 把 `/mtr/api` 代理到引擎（8888）；生产时前端由引擎自己提供，
 * 同源直连，两种情况下这个相对路径都成立，不需要区分环境。</p>
 *
 * <h2>快照与 304（notes/172）</h2>
 * <p>引擎侧的只读接口是"**发布一份快照、多个请求共用**"（见 `WebFeed`），响应带 `ETag`，
 * 而那一份的快照代次**只在内容真的变了时才前进**。所以这里把 ETag 连同数据记下来，
 * 下次问同一条路径时带上 `If-None-Match`：服务端回 304 就说明"还是那一份"，
 * 直接把上次的数据还回去 —— 不占带宽、不重新解析 JSON，图层也就不会无谓地重建。</p>
 */

/** 引擎所有接口的统一信封：`{code, currentTime, text, version, data}`。 */
interface Envelope<T> {
	readonly code: number;
	readonly currentTime: number;
	readonly text: string;
	readonly data: T;
}

const BASE = "/mtr/api/";

/**
 * 每一条 GET 路径最近一次的「快照指纹 + 数据」。
 *
 * <p>只对 GET 生效：写接口没有快照语义（引擎也不会给它 ETag），拿旧数据当"这次写成功了"是危险的。</p>
 */
const responseCache = new Map<string, {readonly etag: string; readonly data: unknown}>();

/** 剥信封并做三处守卫（HTTP 状态 / 引擎自己的 code / data 在不在）。 */
async function unwrapResponse<T>(url: string, response: Response): Promise<T> {
	if (!response.ok) {
		throw new Error(`${url} 返回 HTTP ${response.status}`);
	}
	const envelope = (await response.json()) as Envelope<T>;
	if (typeof envelope.code === "number" && envelope.code !== 0 && envelope.code !== 200) {
		throw new Error(`引擎拒绝请求（code ${envelope.code}）：${envelope.text ?? "无说明"}`);
	}
	if (envelope.data === undefined) {
		throw new Error(`${url} 的返回里没有 data 字段`);
	}
	return envelope.data;
}

/**
 * 请求一个接口并剥掉信封。
 *
 * <p>`code` 不是 0/200 时直接抛错，把服务端的 `text` 带出来——这样界面上能看到引擎自己说的话，
 * 而不是一个没头没尾的 "fetch failed"。</p>
 */
export async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
	const url = BASE + path.replace(/^\/+/, "");
	const method = (init?.method ?? "GET").toUpperCase();
	const cached = method === "GET" ? responseCache.get(url) : undefined;

	const headers = new Headers(init?.headers);
	headers.set("Accept", "application/json");
	if (cached !== undefined) {
		headers.set("If-None-Match", cached.etag);
	}

	const response = await fetch(url, {...init, headers});
	if (response.status === 304) {
		if (cached !== undefined) {
			// 引擎说"还是那一份"：上次的数据继续用，连 JSON 都不必再解析一遍。
			return cached.data as T;
		}
		// 本地没有缓存却收到 304（浏览器自己的缓存参与了再验证）：去掉条件头老老实实再问一次。
		headers.delete("If-None-Match");
		const retry = await fetch(url, {...init, headers});
		return unwrapResponse<T>(url, retry);
	}

	const data = await unwrapResponse<T>(url, response);
	const etag = response.headers.get("ETag");
	if (method === "GET" && etag !== null) {
		responseCache.set(url, {etag, data});
	}
	return data;
}

/** POST 一个 JSON 请求体并剥信封。 */
export function postJson<T>(path: string, body: unknown): Promise<T> {
	return requestJson<T>(path, {
		method: "POST",
		headers: {"Content-Type": "application/json"},
		body: JSON.stringify(body),
	});
}

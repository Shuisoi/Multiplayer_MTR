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
 */

/** 引擎所有接口的统一信封：`{code, currentTime, text, version, data}`。 */
interface Envelope<T> {
	readonly code: number;
	readonly text: string;
	readonly data: T;
}

const BASE = "/mtr/api/";

/**
 * 请求一个接口并剥掉信封。
 *
 * <p>`code` 不是 0/200 时直接抛错，把服务端的 `text` 带出来——这样界面上能看到引擎自己说的话，
 * 而不是一个没头没尾的 "fetch failed"。</p>
 */
export async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
	const url = BASE + path.replace(/^\/+/, "");
	const response = await fetch(url, {headers: {Accept: "application/json"}, ...init});
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

/** POST 一个 JSON 请求体并剥掉信封。 */
export function postJson<T>(path: string, body: unknown): Promise<T> {
	return requestJson<T>(path, {
		method: "POST",
		headers: {"Content-Type": "application/json"},
		body: JSON.stringify(body),
	});
}

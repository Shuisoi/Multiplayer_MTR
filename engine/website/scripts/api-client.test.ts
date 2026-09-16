/*
 * 接口客户端的 **ETag / 304** 用例（notes/172）。
 *
 * 跑法：`npm run test:api-client`（= `node --experimental-strip-types --test scripts/api-client.test.ts`）。
 *
 * 为什么值得单独一条：引擎侧只读接口现在"内容没变就不换代次"，于是浏览器每拍都靠 304 省下整份正文。
 * 这件事一旦写错，**坏法是静默的**：
 *   · 忘了带 `If-None-Match` ⇒ 每拍重传整份 JSON（看起来只是"有点慢"）；
 *   · 收到 304 却当成失败 ⇒ 页面直接报错、图空掉；
 *   · 把 304 的"没有正文"当成"数据是空的" ⇒ 地图上一片空白，而引擎其实是好的。
 * 所以这里用假 fetch 把三条都钉住。
 */
import assert from "node:assert/strict";
import {test} from "node:test";
import {requestJson} from "../src/api/client.ts";

/** 造一个只回答固定几拍的假 fetch，并把每次请求记下来给断言看。 */
function fakeFetch(rounds: readonly Response[]): {readonly calls: {url: string; method: string; ifNoneMatch: string | null}[]; readonly fetchImpl: typeof fetch} {
	const calls: {url: string; method: string; ifNoneMatch: string | null}[] = [];
	let index = 0;
	const fetchImpl = (async (input: string | URL | Request, init?: RequestInit) => {
		const headers = new Headers(init?.headers);
		calls.push({url: String(input), method: (init?.method ?? "GET").toUpperCase(), ifNoneMatch: headers.get("If-None-Match")});
		const response = rounds[Math.min(index, rounds.length - 1)]!;
		index++;
		return response.clone();
	}) as typeof fetch;
	return {calls, fetchImpl};
}

/** 一条 200：信封 + ETag。 */
function ok(body: unknown, etag: string): Response {
	return new Response(JSON.stringify({code: 200, currentTime: 1, text: "OK", data: body}), {status: 200, headers: {"ETag": etag, "Content-Type": "application/json"}});
}

function notModified(): Response {
	return new Response(null, {status: 304});
}

test("第一次拿数据、第二次带 If-None-Match、304 时把上次的数据还回去", async () => {
	const {calls, fetchImpl} = fakeFetch([ok({sections: [1, 2]}, "\"a1\""), notModified()]);
	globalThis.fetch = fetchImpl;

	const first = await requestJson<{sections: number[]}>("map/mmtr-track-sections");
	assert.deepEqual(first, {sections: [1, 2]});
	assert.equal(calls[0]!.ifNoneMatch, null, "手上没有 ETag 时不该带条件头");

	const second = await requestJson<{sections: number[]}>("map/mmtr-track-sections");
	assert.deepEqual(second, {sections: [1, 2]}, "304 时该把上次的数据还回去，而不是报错或给空值");
	assert.equal(calls[1]!.ifNoneMatch, "\"a1\"", "第二次必须带上上一次的 ETag");
});

test("不同路径各记各的 ETag（不会把拓扑的指纹发给灯）", async () => {
	const {calls, fetchImpl} = fakeFetch([ok({a: 1}, "\"topo\""), ok({b: 2}, "\"lamps\"")]);
	globalThis.fetch = fetchImpl;

	await requestJson("map/mmtr-topology");
	await requestJson("map/mmtr-lamps");

	assert.equal(calls[0]!.ifNoneMatch, null);
	assert.equal(calls[1]!.ifNoneMatch, null, "另一条路径是第一次问，不该带条件头");
	assert.equal(calls[1]!.url.endsWith("/mtr/api/map/mmtr-lamps"), true);
});

test("写接口不带条件头，也不吃 304 缓存", async () => {
	const {calls, fetchImpl} = fakeFetch([ok({ok: true}, "\"x\"")]);
	globalThis.fetch = fetchImpl;

	// 先用 GET 把这条路径的指纹记下来，再 POST 同一条路径：POST 不该带 If-None-Match。
	await requestJson("map/mmtr-point-op");
	await requestJson("map/mmtr-point-op", {method: "POST", headers: {"Content-Type": "application/json"}, body: "{}"});

	assert.equal(calls[1]!.method, "POST");
	assert.equal(calls[1]!.ifNoneMatch, null, "写接口必须真的执行，不能拿缓存糊弄过去");
});

test("本地没有缓存却收到 304 时，去掉条件头再问一次（不把它当失败）", async () => {
	const {calls, fetchImpl} = fakeFetch([notModified(), ok({recovered: true}, "\"b2\"")]);
	globalThis.fetch = fetchImpl;

	const data = await requestJson<{recovered: boolean}>("map/mmtr-signals");
	assert.deepEqual(data, {recovered: true});
	assert.equal(calls.length, 2, "应该重试一次");
	assert.equal(calls[0]!.ifNoneMatch, null);
	assert.equal(calls[1]!.ifNoneMatch, null, "重试必须去掉条件头");
});

test("引擎的 code 非 0/200 时把引擎自己的话带出来", async () => {
	globalThis.fetch = (async () => new Response(JSON.stringify({code: 404, currentTime: 1, text: "Not Found - mmtr-nope", data: null}), {status: 200})) as typeof fetch;
	await assert.rejects(() => requestJson("map/mmtr-nope"), /Not Found - mmtr-nope/);
});

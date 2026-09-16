/*
 * **右侧动作按钮**在数据层的那一半（`api/topology.ts` 的指令通道）：`npm run test:map-actions`。
 *
 * 为什么值得一条：按钮本身只是"按下 → 发一条指令"，真正会错的地方全在这条链上，而且**错了都静默**：
 *   · 指令字符串写歪一个词（`point unlock --all` → `point unlock all`）⇒ 引擎回 usage，界面把 usage 当成功提示；
 *   · 走了 `mmtr-point-op` 那条路（逐处扳岔的原子接口）⇒ 界面以为解了，引擎那边一把锁都没动；
 *   · 忘了剥信封 ⇒ 拿到的 `lines[0]` 是 undefined，提示条变成"已下发解锁指令"。
 * 所以这里用假 fetch 把"发到哪、发什么、怎么解包"三条钉住（与 `api-client.test.ts` 同一套手法）。
 */
import assert from "node:assert/strict";
import {test} from "node:test";
import {runMmtrCommand, unlockAllPoints} from "../src/api/command.ts";

/** 记下每次请求的 URL / 方法 / 正文，并回一条固定的引擎信封。 */
function fakeFetch(body: unknown): {readonly calls: {url: string; method: string; body: string}[]; readonly fetchImpl: typeof fetch} {
	const calls: {url: string; method: string; body: string}[] = [];
	const fetchImpl = (async (input: string | URL | Request, init?: RequestInit) => {
		calls.push({url: String(input), method: (init?.method ?? "GET").toUpperCase(), body: String(init?.body ?? "")});
		return new Response(JSON.stringify({code: 200, currentTime: 1, text: "OK", data: body}), {status: 200, headers: {"Content-Type": "application/json"}});
	}) as typeof fetch;
	return {calls, fetchImpl};
}

test("「解锁所有人工锁岔」发的是 `point unlock --all`，走指令通道、且是 POST", async () => {
	const {calls, fetchImpl} = fakeFetch({ok: true, namespace: "point", verb: "unlock", affected: [], lines: ["已解锁全部人工锁：清掉 3 把（含界面上没有对应进向的那些）"]});
	globalThis.fetch = fetchImpl;

	const result = await unlockAllPoints();

	assert.equal(calls.length, 1);
	assert.equal(calls[0]!.method, "POST", "写操作必须是 POST");
	assert.equal(calls[0]!.url.endsWith("/mtr/api/map/mmtr-command"), true, `应当走指令通道，实际 ${calls[0]!.url}`);
	assert.deepEqual(JSON.parse(calls[0]!.body), {command: "point unlock --all"}, "指令字符串一个词都不能错：引擎按词分派");
	assert.equal(result.ok, true);
	assert.equal(result.lines[0], "已解锁全部人工锁：清掉 3 把（含界面上没有对应进向的那些）", "提示条要显示引擎自己那句话（信封要剥掉）");
});

test("指令原样透传（以后加按钮时不必再写一套发请求的代码）", async () => {
	const {calls, fetchImpl} = fakeFetch({ok: true, namespace: "point", verb: "locks", affected: ["-170,-60,-511|H"], lines: ["人工锁 1 把"]});
	globalThis.fetch = fetchImpl;

	const result = await runMmtrCommand("point locks");

	assert.deepEqual(JSON.parse(calls[0]!.body), {command: "point locks"});
	assert.deepEqual(result.affected, ["-170,-60,-511|H"], "affected 要原样带出来（探针与界面都可能要用）");
});

test("引擎说失败时 `ok=false` 也要原样回来（界面据此报错，而不是当成成功）", async () => {
	const {fetchImpl} = fakeFetch({ok: false, namespace: "point", verb: "unlock", affected: [], lines: ["point unlock 需要 <x> <y> <z> --via=<轨hex>，或 point unlock --all"]});
	globalThis.fetch = fetchImpl;

	const result = await unlockAllPoints();
	assert.equal(result.ok, false);
	assert.match(result.lines[0] ?? "", /point unlock/);
});

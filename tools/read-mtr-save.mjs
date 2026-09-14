// 解析 MTR 存档文件（MessagePack）里的 depot / siding 条目，按坐标配对。
//
// 为什么需要：manifest（mmtr-rolling-stock.json）要按 depotId + sidingId 生成列车，而
// "哪条股道属于哪个 depot、哪条是第一股道"在文件里没有直接写 —— depot 文件只有
// position1/position2，siding 文件也只有 position1/position2，所以只能按坐标归属判断。
//
// 用法：node tools/read-mtr-save.mjs <worldRoot>
//   worldRoot 例如 mmtr/game/fabric/run/world/mtr/minecraft/overworld

import {readFileSync, readdirSync, statSync} from "node:fs";
import {join} from "node:path";

/** 极简 MessagePack 解码器（只用到 map/array/str/int/float/nil/bool/bin）。 */
function decode(buffer, offset = 0) {
	const view = buffer;
	const byte = view[offset];
	offset += 1;

	if (byte <= 0x7f) return {value: byte, offset};                       // positive fixint
	if (byte >= 0xe0) return {value: byte - 0x100, offset};               // negative fixint
	if (byte >= 0x80 && byte <= 0x8f) return decodeMap(view, offset, byte & 0x0f);
	if (byte >= 0x90 && byte <= 0x9f) return decodeArray(view, offset, byte & 0x0f);
	if (byte >= 0xa0 && byte <= 0xbf) return decodeString(view, offset, byte & 0x1f);
	if (byte === 0xc0) return {value: null, offset};
	if (byte === 0xc2) return {value: false, offset};
	if (byte === 0xc3) return {value: true, offset};
	if (byte === 0xc4) { const n = view[offset]; return {value: view.subarray(offset + 1, offset + 1 + n), offset: offset + 1 + n}; }
	if (byte === 0xc5) { const n = view.readUInt16BE(offset); return {value: view.subarray(offset + 2, offset + 2 + n), offset: offset + 2 + n}; }
	if (byte === 0xca) return {value: view.readFloatBE(offset), offset: offset + 4};
	if (byte === 0xcb) return {value: view.readDoubleBE(offset), offset: offset + 8};
	if (byte === 0xcc) return {value: view[offset], offset: offset + 1};
	if (byte === 0xcd) return {value: view.readUInt16BE(offset), offset: offset + 2};
	if (byte === 0xce) return {value: view.readUInt32BE(offset), offset: offset + 4};
	if (byte === 0xcf) { const value = Number(view.readBigUInt64BE(offset)); return {value, offset: offset + 8}; }
	if (byte === 0xd0) return {value: view.readInt8(offset), offset: offset + 1};
	if (byte === 0xd1) return {value: view.readInt16BE(offset), offset: offset + 2};
	if (byte === 0xd2) return {value: view.readInt32BE(offset), offset: offset + 4};
	if (byte === 0xd3) { const value = Number(view.readBigInt64BE(offset)); return {value, offset: offset + 8}; };
	if (byte === 0xd9) { const n = view[offset]; return decodeString(view, offset + 1, n); }
	if (byte === 0xda) { const n = view.readUInt16BE(offset); return decodeString(view, offset + 2, n); }
	if (byte === 0xdb) { const n = view.readUInt32BE(offset); return decodeString(view, offset + 4, n); }
	if (byte === 0xdc) { const n = view.readUInt16BE(offset); return decodeArray(view, offset + 2, n); }
	if (byte === 0xdd) { const n = view.readUInt32BE(offset); return decodeArray(view, offset + 4, n); }
	if (byte === 0xde) { const n = view.readUInt16BE(offset); return decodeMap(view, offset + 2, n); }
	if (byte === 0xdf) { const n = view.readUInt32BE(offset); return decodeMap(view, offset + 4, n); }
	throw new Error("未知的 MessagePack 标记 0x" + byte.toString(16) + " @ " + (offset - 1));
}

function decodeString(view, offset, length) {
	return {value: view.toString("utf8", offset, offset + length), offset: offset + length};
}

function decodeArray(view, offset, length) {
	const out = [];
	for (let i = 0; i < length; i++) {
		const result = decode(view, offset);
		out.push(result.value);
		offset = result.offset;
	}
	return {value: out, offset};
}

function decodeMap(view, offset, length) {
	const out = {};
	for (let i = 0; i < length; i++) {
		const key = decode(view, offset);
		offset = key.offset;
		const value = decode(view, offset);
		offset = value.offset;
		out[String(key.value)] = value.value;
	}
	return {value: out, offset};
}

/**
 * 文件名 → 64 位 ID（无符号）。
 *
 * <p>实测验证（用 manifest 里已知的 987654）：manifest 记的是 `id=849401984139021720`，
 * 而它的 depot 文件名是 `0BC9AE9AB01E4D98` —— 文件名就是该 id 的十六进制
 * （`0x0BC9AE9AB01E4D98` = 849401984139021720）。所以**按十六进制直接读**即可，
 * 不要按字节反转（反转会得到另一个数，实测把 test1 的 id 读错成 9743473256111740477）。</p>
 *
 * <p>注意 manifest 里存的是**有符号** 64 位十进制，超出 2^63 的值要按补码转回负数。</p>
 */
function fileToId(name) {
	return BigInt("0x" + name);
}

/** 有符号写法（manifest 里用的形式）。 */
function toSigned(value) {
	return value >= 0x8000000000000000n ? value - 0x10000000000000000n : value;
}

function readDir(root, kind) {
	const dir = join(root, kind);
	const out = [];
	let groups;
	try {
		groups = readdirSync(dir);
	} catch {
		return out;
	}
	for (const group of groups) {
		const groupDir = join(dir, group);
		let files;
		try {
			if (!statSync(groupDir).isDirectory()) continue;
			files = readdirSync(groupDir);
		} catch {
			continue;
		}
		for (const file of files) {
			const full = join(groupDir, file);
			let parsed;
			try {
				parsed = decode(readFileSync(full)).value;
			} catch (error) {
				out.push({file, group, error: String(error.message)});
				continue;
			}
			let id = null;
			try {
				id = fileToId(file);
			} catch { /* 文件名不是 16 位十六进制就跳过 */ }
			out.push({file, group, id, signedId: id === null ? null : toSigned(id).toString(), data: parsed});
		}
	}
	return out;
}

const root = process.argv[2];
if (!root) {
	console.error("用法：node tools/read-mtr-save.mjs <worldRoot>");
	process.exit(1);
}

const depots = readDir(root, "depots");
const sidings = readDir(root, "sidings");

console.log("=== depots ===");
for (const d of depots) {
	if (d.error) { console.log(`  ${d.file} 解析失败: ${d.error}`); continue; }
	console.log(`  ${d.data.name}  id=${d.signedId} (0x${d.id.toString(16).toUpperCase()})  file=${d.file}  ${JSON.stringify({p1: d.data.position1, p2: d.data.position2})}`);
}

console.log("");
console.log("=== sidings ===");
for (const s of sidings) {
	if (s.error) { console.log(`  ${s.file} 解析失败: ${s.error}`); continue; }
	const cars = (s.data.vehicleCars ?? []).map(c => c.vehicleId).join("+");
	console.log(`  id=${s.signedId}  file=${s.file}  cars=[${cars}]  ${JSON.stringify({p1: s.data.position1, p2: s.data.position2, railLength: s.data.railLength})}`);
}

// 按坐标配对：siding 的两个端点应当落在 depot 的两个端点附近（同一片道岔区内）
function distance(a, b) {
	if (!a || !b) return Infinity;
	// 只比 x 与 z：MTR 的 depot/siding Position 里 y 是"无限制"哨兵（64 位极值），不是真实高度。
	const ax = a.x, az = a.z;
	const bx = b.x, bz = b.z;
	if ([ax, az, bx, bz].some(v => typeof v !== "number")) return Infinity;
	return Math.hypot(ax - bx, az - bz);
}

console.log("");
console.log("=== 每个 siding 到每个 depot 的最小端点距离（越小越可能是它的股道）===");
for (const s of sidings) {
	if (s.error) continue;
	const row = depots.filter(d => !d.error).map(d => {
		const value = Math.min(
			distance(s.data.position1, d.data.position1), distance(s.data.position1, d.data.position2),
			distance(s.data.position2, d.data.position1), distance(s.data.position2, d.data.position2),
		);
		return `${d.data.name}:${Number.isFinite(value) ? value.toFixed(1) : "-"}`;
	});
	console.log(`  "${s.data.name ?? ""}" (${s.file})  →  ${row.join("   ")}`);
}


// MMTR resource pack zip writer, shared by pack_vehicle.js (one car) and pack_consist.js (a whole
// formation). Written by hand rather than with Compress-Archive: Windows PowerShell 5.1's
// Compress-Archive stores entries with "\" separators, which Java/Minecraft cannot resolve as
// resource paths - the pack then loads with no models at all. Entry names must always use "/".
//
// The output is deterministic: entries are sorted, the DOS timestamp is fixed, and deflate level is
// pinned, so re-running a pack produces a byte-identical zip (used as a regression check).
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

function writeZip(stageDir, zipPath) {
	const crcTable = (() => { const t = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c; } return t; })();
	const crc32 = buf => { let c = 0xFFFFFFFF; for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 0xFF] ^ (c >>> 8); return (c ^ 0xFFFFFFFF) >>> 0; };
	const walk = (dir, prefix, out) => { for (const name of fs.readdirSync(dir).sort()) { const full = path.join(dir, name); const rel = prefix ? prefix + '/' + name : name; if (fs.statSync(full).isDirectory()) walk(full, rel, out); else out.push([rel, full]); } return out; };
	const files = walk(stageDir, '', []);
	const chunks = []; const central = []; let offset = 0;
	const dosTime = 0, dosDate = 0x21; // fixed 1980-01-01, so the same input always produces the same zip
	for (const [rel, full] of files) {
		const data = fs.readFileSync(full);
		const deflated = zlib.deflateRawSync(data, { level: 9 });
		const useDeflate = deflated.length < data.length;
		const body = useDeflate ? deflated : data;
		const method = useDeflate ? 8 : 0;
		const nameBuf = Buffer.from(rel, 'utf8');
		const local = Buffer.alloc(30);
		local.writeUInt32LE(0x04034b50, 0);
		local.writeUInt16LE(20, 4);
		local.writeUInt16LE(0x0800, 6); // UTF-8 entry names
		local.writeUInt16LE(method, 8);
		local.writeUInt16LE(dosTime, 10);
		local.writeUInt16LE(dosDate, 12);
		local.writeUInt32LE(crc32(data), 14);
		local.writeUInt32LE(body.length, 18);
		local.writeUInt32LE(data.length, 22);
		local.writeUInt16LE(nameBuf.length, 26);
		local.writeUInt16LE(0, 28);
		chunks.push(local, nameBuf, body);
		central.push({ nameBuf, method, crc: crc32(data), compressed: body.length, uncompressed: data.length, offset });
		offset += local.length + nameBuf.length + body.length;
	}
	const centralStart = offset;
	for (const e of central) {
		const head = Buffer.alloc(46);
		head.writeUInt32LE(0x02014b50, 0);
		head.writeUInt16LE(20, 4);
		head.writeUInt16LE(20, 6);
		head.writeUInt16LE(0x0800, 8);
		head.writeUInt16LE(e.method, 10);
		head.writeUInt16LE(dosTime, 12);
		head.writeUInt16LE(dosDate, 14);
		head.writeUInt32LE(e.crc, 16);
		head.writeUInt32LE(e.compressed, 20);
		head.writeUInt32LE(e.uncompressed, 24);
		head.writeUInt16LE(e.nameBuf.length, 28);
		head.writeUInt16LE(0, 30);
		head.writeUInt16LE(0, 32);
		head.writeUInt16LE(0, 34);
		head.writeUInt16LE(0, 36);
		head.writeUInt32LE(0, 38);
		head.writeUInt32LE(e.offset, 42);
		chunks.push(head, e.nameBuf);
		offset += head.length + e.nameBuf.length;
	}
	const end = Buffer.alloc(22);
	end.writeUInt32LE(0x06054b50, 0);
	end.writeUInt16LE(0, 4);
	end.writeUInt16LE(0, 6);
	end.writeUInt16LE(central.length, 8);
	end.writeUInt16LE(central.length, 10);
	end.writeUInt32LE(offset - centralStart, 12);
	end.writeUInt32LE(centralStart, 16);
	end.writeUInt16LE(0, 20);
	chunks.push(end);
	fs.writeFileSync(zipPath, Buffer.concat(chunks));
}

module.exports = { writeZip };

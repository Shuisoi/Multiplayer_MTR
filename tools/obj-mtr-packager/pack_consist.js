// MMTR consist packager: several vehicle models in ONE MTR resource pack.
//
// The single-car packager (pack_vehicle.js) writes one vehicle per pack. A real formation needs more
// than one model - an 8-car HST is 2 cab cars (the rear one rotated 180 degrees, so it is its own
// model id) plus a coach - and MTR resolves every model id from the same mtr_custom_resources.json.
// This script runs the existing per-car packager once per car into a private staging dir, then merges
// the staging trees into a single pack and writes one vehicles array.
//
// Config (see consist/hst8.json): { packName, description, outputDir, packFormat, cars: [ <the
// per-car params pack_vehicle.js already understands> ] }.
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawnSync } = require('child_process');
const { writeZip } = require('./zip.js');
const { resolveRoot } = require('./paths.js');

function fail(message) {
	console.error('CONSIST PACK FAILED: ' + message);
	process.exit(1);
}

const configPath = process.argv[2];
if (!configPath) fail('usage: node pack_consist.js <consist.json>');
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
config.outputDir = resolveRoot(config.outputDir);
const cars = config.cars || [];
if (!cars.length) fail('config has no "cars"');

const packName = config.packName || 'consist';
const workDir = config.workDir || path.join(os.tmpdir(), 'mmtr-consist-' + packName);
fs.rmSync(workDir, { recursive: true, force: true });
fs.mkdirSync(workDir, { recursive: true });

const stage = path.join(workDir, 'stage');
const stageMtr = path.join(stage, 'assets', 'mtr');
fs.mkdirSync(stageMtr, { recursive: true });
// The per-car packager always writes a zip; point it at a scratch dir so only the merged pack survives.
fs.mkdirSync(path.join(workDir, 'zips'), { recursive: true });

const vehicles = [];
const ids = [];
const seenIds = new Set();

for (let i = 0; i < cars.length; i++) {
	const car = cars[i];
	const id = car.id;
	if (!id) fail('car ' + i + ' has no id');
	if (seenIds.has(id)) fail('duplicate car id: ' + id);
	seenIds.add(id);
	ids.push(id);

	const carStage = path.join(workDir, 'car_' + i);
	const carParams = Object.assign({}, car, {
		stagingDir: carStage,
		outputDir: path.join(workDir, 'zips'),
		outputPackName: id + '_tmp'
	});
	const paramsPath = path.join(workDir, 'params_' + i + '.json');
	fs.writeFileSync(paramsPath, JSON.stringify(carParams, null, 1));

	const result = spawnSync(process.execPath, [path.join(__dirname, 'pack_vehicle.js'), paramsPath], { stdio: 'inherit' });
	if (result.status !== 0) fail('packaging ' + id + ' failed with ' + result.status);

	const carMtr = path.join(carStage, 'assets', 'mtr');
	for (const entry of fs.readdirSync(carMtr)) {
		const from = path.join(carMtr, entry);
		if (entry === 'mtr_custom_resources.json') {
			const parsed = JSON.parse(fs.readFileSync(from, 'utf8'));
			for (const vehicle of (parsed.vehicles || [])) {
				if (vehicles.some(existing => existing.id === vehicle.id)) fail('duplicate vehicle id across cars: ' + vehicle.id);
				vehicles.push(vehicle);
			}
			continue;
		}
		fs.cpSync(from, path.join(stageMtr, entry), { recursive: true });
	}
	console.log('merged car ' + id);
}

// One resource json for the whole pack: MTR reads every vehicle id from it.
fs.writeFileSync(path.join(stageMtr, 'mtr_custom_resources.json'), JSON.stringify({ vehicles: vehicles, signs: [], rails: [], objects: [], lifts: [] }));
fs.writeFileSync(path.join(stage, 'pack.mcmeta'), JSON.stringify({ pack: { pack_format: config.packFormat || 18, description: config.description || (packName + ' auto pack') } }));

const zipOut = path.join(config.outputDir || '.', packName + '.zip');
if (fs.existsSync(zipOut)) fs.unlinkSync(zipOut);
writeZip(stage, zipOut);
console.log('zip', zipOut, fs.statSync(zipOut).size + ' bytes');
console.log('vehicles:', ids.join(', '));

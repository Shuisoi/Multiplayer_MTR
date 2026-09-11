import {AfterViewInit, ChangeDetectionStrategy, Component, computed, DestroyRef, effect, ElementRef, inject, output, signal, viewChild} from "@angular/core";
import {OrbitControls} from "three/addons/controls/OrbitControls.js";
import * as THREE from "three";
import {Line2} from "three/addons/lines/Line2.js";
import {LineGeometry} from "three/addons/lines/LineGeometry.js";
import {LineMaterial} from "three/addons/lines/LineMaterial.js";
import {MmtrLayersService} from "../../service/mmtr-layers.service";
import {MmtrPoint, MmtrPointsService} from "../../service/mmtr-points.service";
import {MmtrSchematicBlock, MmtrSchematicService} from "../../service/mmtr-schematic.service";
import {MmtrTopologyService} from "../../service/mmtr-topology.service";
import {MmtrTrainState, MmtrTrainsService} from "../../service/mmtr-trains.service";

/** A train marker, in CSS pixels relative to the canvas centre. */
interface TrainMarker {
	vehicleId: string;
	x: number;
	y: number;
	label: string;
	parked: boolean;
	moving: boolean;
}

/**
 * 运营台地图 (rewritten from scratch, 2026-09-11).
 *
 * <p>Deliberately small: the previous component carried the whole MTR website drawing stack (station blobs,
 * four weights of station-to-station connections, one-way arrows, automatic-line tiers, player avatars,
 * station text labels, live-signal cores, block paints) - about 1300 lines, most of it drawing things this
 * console never needed. It was deleted and this file replaces it with a handful of primitives and two views.</p>
 *
 * <p><strong>Two views, one canvas.</strong> {@code world} is the real track graph: a faint 50 m lattice,
 * rails as thin gray lines, forks as dots, trains as labelled markers. {@code schematic} is the ENGINE's
 * 区间图 - the block layer folded onto a 1x1 lattice; every coordinate comes from {@code mmtr-schematic}, so
 * the console derives no geometry of its own.</p>
 *
 * <p>Everything is drawn on demand (a data signal, a layer toggle, a view switch) into a scene that is
 * thrown away and rebuilt each time - no incremental geometry bookkeeping. The camera is an orthographic
 * pan/zoom camera, so framing a view is just a pan plus a zoom.</p>
 */
@Component({
	selector: "app-map",
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./map.component.html",
	styleUrl: "./map.component.scss",
})
export class MapComponent implements AfterViewInit {
	private readonly topologyService = inject(MmtrTopologyService);
	private readonly schematicService = inject(MmtrSchematicService);
	private readonly trainsService = inject(MmtrTrainsService);
	private readonly pointsService = inject(MmtrPointsService);
	private readonly destroyRef = inject(DestroyRef);
	protected readonly layers = inject(MmtrLayersService);
	protected readonly feedback = this.pointsService.feedback;

	/** A train marker was clicked (the console drawer owns dispatch). */
	readonly trainClicked = output<string>();

	private readonly wrapperRef = viewChild<ElementRef<HTMLDivElement>>("wrapper");
	private readonly canvasRef = viewChild<ElementRef<HTMLCanvasElement>>("canvas");

	/** Train markers, projected to CSS pixels whenever the data or the camera moves. */
	readonly trainMarkers = signal<TrainMarker[]>([]);
	/** The fork the operator opened, or empty. */
	readonly selectedNodeKey = signal("");
	readonly loading = computed(() => !this.topologyService.loaded());

	// ---------------------------------------------------------------- scene

	private readonly scene = new THREE.Scene();
	private readonly camera = new THREE.OrthographicCamera(0, 0, 0, 0, -2000, 2000);
	private controls: OrbitControls | undefined;
	private renderer: THREE.WebGLRenderer | undefined;
	private readonly content = new THREE.Group();
	private readonly materials: LineMaterial[] = [];
	private readonly geometries = new WeakMap<Line2, LineGeometry>();
	private framedWorld = false;
	private framedSchematic = false;

	private static readonly WORLD_Y = 0;
	/** Where the 区间图 is parked: the two views must never overlap in scene space. */
	private static readonly SCHEMATIC_OFFSET_X = 6000;
	/** Line widths in device pixels (the LineMaterial convention). */
	private static readonly W_GRID = 1;
	private static readonly W_RAIL = 2.5;
	private static readonly W_RAIL_HOT = 4.5;
	private static readonly W_MARK = 1.5;
	/** Marker radii in world metres (this view is world-scale). */
	private static readonly TRAIN_RADIUS = 3.2;
	private static readonly FORK_RADIUS = 1.6;
	private static readonly COLOUR_GRID = 0x171C22;
	private static readonly COLOUR_RAIL = 0x9AA1AA;
	private static readonly COLOUR_IDLE = 0x4B5563;
	private static readonly COLOUR_LOCKED = 0xFF4D4F;
	private static readonly COLOUR_MANUAL = 0xFFB300;
	private static readonly COLOUR_TRAIN_MOVING = 0xFFFFFF;
	private static readonly COLOUR_TRAIN_PARKED = 0x8B949C;
	private static readonly COLOUR_OCCUPIED = 0xFF1744;
	private static readonly PALETTE = [0x64B5F6, 0x4DD0E1, 0xBA68C8];
	/** Signal aspects as the console draws them (green means "as clear as it gets": no overlay). */
	private static readonly ASPECT_COLOURS: Record<string, number | undefined> = {
		GREEN: undefined,
		RED: 0xFF4D4F,
		SINGLE_YELLOW: 0xFFB300,
		DOUBLE_YELLOW: 0xFFE082,
	};

	constructor() {
		// World view: redraw when its data or its layer toggles change.
		effect(() => {
			this.topologyService.rails();
			this.trainsService.trains();
			this.pointsService.points();
			this.layers.view();
			this.layers.rails();
			this.layers.points();
			this.layers.signals();
			this.redraw();
		});
		// 区间图: redraw when the engine's diagram or the view changes.
		effect(() => {
			this.schematicService.data();
			this.layers.view();
			this.redraw();
		});
	}

	ngAfterViewInit() {
		const canvas = this.canvasRef()?.nativeElement;
		const wrapper = this.wrapperRef()?.nativeElement;
		if (!canvas || !wrapper) {
			return;
		}
		const renderer = new THREE.WebGLRenderer({antialias: true, canvas});
		renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
		this.renderer = renderer;
		this.scene.background = new THREE.Color(0x0B0D10);
		this.scene.add(this.content);

		this.controls = new OrbitControls(this.camera, wrapper);
		this.controls.enableRotate = false;
		this.controls.mouseButtons = {LEFT: THREE.MOUSE.PAN, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.PAN};
		this.controls.touches.ONE = THREE.TOUCH.PAN;
		this.controls.zoomToCursor = true;
		this.controls.target.set(0, 0, 0);
		// The markers are DOM, so they follow the camera only if a camera move re-projects them.
		this.controls.addEventListener("change", () => {
			this.projectTrainMarkers();
			this.renderer?.render(this.scene, this.camera);
		});

		const resize = () => {
			const width = canvas.clientWidth;
			const height = canvas.clientHeight;
			if (width <= 0 || height <= 0) {
				return;
			}
			renderer.setSize(width * devicePixelRatio, height * devicePixelRatio, false);
			this.camera.left = -width / 2;
			this.camera.right = width / 2;
			this.camera.top = height / 2;
			this.camera.bottom = -height / 2;
			this.camera.updateProjectionMatrix();
			this.materials.forEach(material => material.resolution.set(width, height));
			this.projectTrainMarkers();
			renderer.render(this.scene, this.camera);
		};
		resize();
		const observer = new ResizeObserver(resize);
		observer.observe(canvas);
		this.destroyRef.onDestroy(() => {
			observer.disconnect();
			renderer.dispose();
			this.materials.forEach(material => material.dispose());
		});
	}

	// ---------------------------------------------------------------- drawing

	/** Throw the old scene away and draw the current view from the current data. */
	private redraw() {
		if (!this.renderer) {
			return;
		}
		this.clearContent();
		if (this.layers.view() === "schematic") {
			this.drawSchematic();
		} else {
			this.drawWorld();
		}
		this.projectTrainMarkers();
		this.renderer.render(this.scene, this.camera);
	}

	private clearContent() {
		this.content.children.slice().forEach(child => {
			this.content.remove(child);
			this.geometries.get(child as Line2)?.dispose();
		});
	}

	/** The real track graph: a faint 50 m lattice, the rails, the forks, the trains. */
	private drawWorld() {
		const rails = this.topologyService.rails();
		if (rails.length === 0) {
			return;
		}
		const bounds = MapComponent.bounds(rails);
		for (let x = Math.floor(bounds.minX / 50) * 50; x <= bounds.maxX + 50; x += 50) {
			this.line([x, -bounds.minZ - 25, MapComponent.WORLD_Y, x, -bounds.maxZ + 25, MapComponent.WORLD_Y], MapComponent.COLOUR_GRID, MapComponent.W_GRID);
		}
		for (let z = Math.floor(bounds.minZ / 50) * 50; z <= bounds.maxZ + 50; z += 50) {
			this.line([bounds.minX - 25, -z, MapComponent.WORLD_Y, bounds.maxX + 25, -z, MapComponent.WORLD_Y], MapComponent.COLOUR_GRID, MapComponent.W_GRID);
		}
		if (this.layers.rails()) {
			for (const rail of rails) {
				this.line([rail.x1, -rail.z1, MapComponent.WORLD_Y + 0.1, rail.x2, -rail.z2, MapComponent.WORLD_Y + 0.1], MapComponent.COLOUR_RAIL, MapComponent.W_RAIL);
			}
		}
		// 信号显示: the aspect of the signal protecting each rail, laid over it - the console's core reading.
		if (this.layers.signals()) {
			const aspectByHex = new Map<string, string>();
			this.trainsService.signals().forEach(aspect => aspectByHex.set(aspect.hex, aspect.aspect));
			for (const rail of rails) {
				const colour = MapComponent.ASPECT_COLOURS[aspectByHex.get(rail.hex) ?? "GREEN"];
				if (colour !== undefined) {
					this.line([rail.x1, -rail.z1, MapComponent.WORLD_Y + 0.35, rail.x2, -rail.z2, MapComponent.WORLD_Y + 0.35], colour, MapComponent.W_RAIL_HOT);
				}
			}
		}
		if (this.layers.points()) {
			for (const point of this.pointsService.points()) {
				const colour = point.manual >= 0 ? MapComponent.COLOUR_MANUAL : (point.locked ? MapComponent.COLOUR_LOCKED : MapComponent.COLOUR_IDLE);
				this.circle(point.x + 0.5, -(point.z + 0.5), MapComponent.FORK_RADIUS, colour, MapComponent.W_MARK);
			}
		}
		for (const train of this.trainsService.trains()) {
			if (train.headX === undefined || train.headZ === undefined) {
				continue;
			}
			this.circle(train.headX, -train.headZ, MapComponent.TRAIN_RADIUS,
				train.moving ? MapComponent.COLOUR_TRAIN_MOVING : MapComponent.COLOUR_TRAIN_PARKED,
				train.moving ? MapComponent.W_RAIL_HOT : MapComponent.W_RAIL);
		}
		if (!this.framedWorld) {
			this.framedWorld = true;
			this.move((bounds.minX + bounds.maxX) / 2, -(bounds.minZ + bounds.maxZ) / 2);
		}
	}

	/** The ENGINE's 区间图: the 1x1 lattice, one line per lattice edge, each block's colour on top. */
	private drawSchematic() {
		const schematic = this.schematicService.data();
		if (schematic.nodes.length === 0) {
			return;
		}
		const step = schematic.cellSize;
		const width = Math.max(1, schematic.cellWidth) * step;
		const height = Math.max(1, schematic.cellHeight) * step;
		const offset = MapComponent.SCHEMATIC_OFFSET_X;
		for (let column = 0; column <= schematic.cellWidth; column++) {
			this.line([offset + column * step, 0, MapComponent.WORLD_Y, offset + column * step, -height, MapComponent.WORLD_Y], MapComponent.COLOUR_GRID, MapComponent.W_GRID);
		}
		for (let row = 0; row <= schematic.cellHeight; row++) {
			this.line([offset, -row * step, MapComponent.WORLD_Y, offset + width, -row * step, MapComponent.WORLD_Y], MapComponent.COLOUR_GRID, MapComponent.W_GRID);
		}
		const tints = new Map<number, number>();
		const tint = (index: number) => {
			if (!tints.has(index)) {
				tints.set(index, MapComponent.PALETTE[tints.size % MapComponent.PALETTE.length]);
			}
			return tints.get(index) ?? MapComponent.PALETTE[0];
		};
		// The lattice first, then each block's own colour over it: a block IS the path it owns.
		for (const rail of schematic.rails) {
			this.line([offset + rail.x1, -rail.z1, MapComponent.WORLD_Y + 0.1, offset + rail.x2, -rail.z2, MapComponent.WORLD_Y + 0.1], MapComponent.COLOUR_IDLE, MapComponent.W_RAIL);
		}
		for (const block of schematic.blocks) {
			const colour = block.occupied ? MapComponent.COLOUR_OCCUPIED : (block.lamp ? tint(block.index) : MapComponent.COLOUR_IDLE);
			for (const edge of block.edges) {
				const rail = schematic.rails[edge];
				if (rail) {
					this.line([offset + rail.x1, -rail.z1, MapComponent.WORLD_Y + 0.3, offset + rail.x2, -rail.z2, MapComponent.WORLD_Y + 0.3], colour,
						block.occupied ? MapComponent.W_RAIL_HOT : MapComponent.W_RAIL);
				}
			}
		}
		const owner = new Map<number, MmtrSchematicBlock>();
		schematic.blocks.forEach(block => block.squares.forEach(square => owner.set(square, block)));
		for (const node of schematic.nodes) {
			const block = owner.get(node.id);
			const colour = block?.occupied ? MapComponent.COLOUR_OCCUPIED : (block?.lamp ? tint(block.index) : MapComponent.COLOUR_IDLE);
			this.circle(offset + node.x, -node.z, step * 0.16, colour, MapComponent.W_MARK);
		}
		if (!this.framedSchematic) {
			this.framedSchematic = true;
			const canvas = this.canvasRef()?.nativeElement;
			const fit = canvas ? Math.min(canvas.clientWidth / width, canvas.clientHeight / height) : 1;
			if (fit > 0 && fit < 1) {
				this.camera.zoom = Math.max(0.05, fit * 0.9);
				this.camera.updateProjectionMatrix();
			}
			this.move(offset + width / 2, -height / 2);
		}
	}

	// ---------------------------------------------------------------- primitives

	/** One straight line of the drawing. */
	private line(positions: number[], colour: number, width: number) {
		this.add(positions, colour, width);
	}

	/** A closed ring: a fork, a train, a lattice node. */
	private circle(x: number, z: number, radius: number, colour: number, width: number) {
		const positions: number[] = [];
		for (let i = 0; i <= 16; i++) {
			const angle = (i / 16) * Math.PI * 2;
			positions.push(x + Math.cos(angle) * radius, z + Math.sin(angle) * radius, MapComponent.WORLD_Y + 0.5);
		}
		this.add(positions, colour, width);
	}

	private add(positions: number[], colour: number, width: number) {
		const geometry = new LineGeometry();
		geometry.setPositions(positions);
		const line = new Line2(geometry, this.material(colour, width));
		line.computeLineDistances();
		this.geometries.set(line, geometry);
		this.content.add(line);
	}

	/** Materials are pooled per (colour, width): a redraw reuses them instead of leaking new ones. */
	private material(colour: number, width: number): LineMaterial {
		const lineWidth = width * devicePixelRatio;
		const existing = this.materials.find(material => material.color.getHex() === colour && material.linewidth === lineWidth);
		if (existing) {
			return existing;
		}
		const material = new LineMaterial({color: colour, linewidth: lineWidth, transparent: true, opacity: 0.95});
		const canvas = this.canvasRef()?.nativeElement;
		if (canvas) {
			material.resolution.set(canvas.clientWidth, canvas.clientHeight);
		}
		this.materials.push(material);
		return material;
	}

	private move(x: number, y: number) {
		if (!this.controls) {
			return;
		}
		this.camera.position.x = x;
		this.camera.position.y = y;
		this.controls.target.set(x, y, 0);
		this.controls.update();
	}

	// ---------------------------------------------------------------- overlays

	/** Train markers are DOM: they carry text, which the line renderer cannot draw. */
	private projectTrainMarkers() {
		if (this.layers.view() !== "world") {
			this.trainMarkers.set([]);
			return;
		}
		const markers: TrainMarker[] = [];
		for (const train of this.trainsService.trains()) {
			if (train.headX === undefined || train.headZ === undefined) {
				continue;
			}
			markers.push({
				vehicleId: train.vehicleId,
				x: (train.headX - this.camera.position.x) * this.camera.zoom,
				y: (-train.headZ + this.camera.position.y) * this.camera.zoom,
				label: MapComponent.trainLabel(train),
				parked: !train.moving,
				moving: train.moving,
			});
		}
		this.trainMarkers.set(markers);
	}

	/** The fork rows of the selected node (one row per approach rail). */
	protected forkRows() {
		const key = this.selectedNodeKey();
		return key ? this.pointsService.points().filter(point => MmtrPointsService.nodeKey(point) === key) : [];
	}

	/**
	 * A plain click (not a pan) picks the nearest fork or train, in world metres.
	 *
	 * <p>The drawing is lines and rings on a canvas, so hit testing is the cheap way to make them clickable:
	 * a click is converted back to world coordinates and matched against the two data sets the scene is drawn
	 * from. A drag is ignored, because that is the operator panning the map.</p>
	 */
	protected onCanvasClick(event: MouseEvent) {
		if (MapComponent.dragged) {
			MapComponent.dragged = false;
			return;
		}
		const canvas = this.canvasRef()?.nativeElement;
		if (!canvas) {
			return;
		}
		const bounds = canvas.getBoundingClientRect();
		const worldX = this.camera.position.x + (event.clientX - bounds.left - bounds.width / 2) / this.camera.zoom;
		const worldZ = -this.camera.position.y - (event.clientY - bounds.top - bounds.height / 2) / this.camera.zoom;
		if (this.layers.view() !== "world") {
			return;
		}
		let bestPoint: MmtrPoint | undefined;
		let bestDistance = 12;
		for (const point of this.pointsService.points()) {
			const distance = Math.hypot(point.x + 0.5 - worldX, point.z + 0.5 - worldZ);
			if (distance < bestDistance) {
				bestDistance = distance;
				bestPoint = point;
			}
		}
		if (bestPoint) {
			this.selectFork(bestPoint);
			return;
		}
		let bestTrain = "";
		let bestTrainDistance = 10;
		for (const train of this.trainsService.trains()) {
			if (train.headX === undefined || train.headZ === undefined) {
				continue;
			}
			const distance = Math.hypot(train.headX - worldX, train.headZ - worldZ);
			if (distance < bestTrainDistance) {
				bestTrainDistance = distance;
				bestTrain = train.vehicleId;
			}
		}
		if (bestTrain) {
			this.selectTrain(bestTrain);
		}
	}

	protected onCanvasDown() {
		MapComponent.dragged = false;
	}

	protected onCanvasMove(event: MouseEvent) {
		if (event.buttons !== 0) {
			MapComponent.dragged = true;
		}
	}

	/** Whether the pointer is mid-drag (a pan): a click that ends a pan must not select anything. */
	private static dragged = false;

	protected selectFork(point: MmtrPoint) {
		this.selectedNodeKey.set(MmtrPointsService.nodeKey(point));
	}

	protected closeFork() {
		this.selectedNodeKey.set("");
	}

	protected throwBranch(point: MmtrPoint, leg: number) {
		this.pointsService.setBranch(point, leg);
	}

	protected clearBranch(point: MmtrPoint) {
		this.pointsService.clearBranch(point);
	}

	protected toggleLock(point: MmtrPoint) {
		this.pointsService.setLocked(point, !point.locked);
	}

	protected selectTrain(vehicleId: string) {
		this.trainClicked.emit(vehicleId);
	}

	protected shortHex(hex: string) {
		return hex.length > 8 ? hex.substring(0, 8) : hex;
	}

	protected kindText(kind: string) {
		return kind === "STRAIGHT" ? "直" : (kind === "LEFT" ? "左" : (kind === "RIGHT" ? "右" : "其他"));
	}

	protected statusText(point: MmtrPoint) {
		if (point.locked) {
			return point.manual >= 0 ? "锁定 · 人工已设" : "锁定 · 未设";
		}
		if (point.manual >= 0) {
			return "人工已设";
		}
		if (point.holder) {
			return `授权 ${point.holder}@${point.holderLeg}`;
		}
		return point.queue.length > 0 ? `排队 ${point.queue.length}` : "空闲";
	}

	private static trainLabel(train: MmtrTrainState) {
		const name = train.routeName || train.routeNumber || train.sidingName || train.vehicleId;
		return train.moving ? `${name} · ${Math.round(train.speedKmh)} km/h` : `${name} · 停`;
	}

	private static bounds(rails: {x1: number; z1: number; x2: number; z2: number}[]) {
		let minX = Number.MAX_VALUE;
		let maxX = -Number.MAX_VALUE;
		let minZ = Number.MAX_VALUE;
		let maxZ = -Number.MAX_VALUE;
		for (const rail of rails) {
			minX = Math.min(minX, rail.x1, rail.x2);
			maxX = Math.max(maxX, rail.x1, rail.x2);
			minZ = Math.min(minZ, rail.z1, rail.z2);
			maxZ = Math.max(maxZ, rail.z1, rail.z2);
		}
		return {minX, maxX, minZ, maxZ};
	}
}

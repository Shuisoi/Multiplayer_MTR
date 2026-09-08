import * as THREE from "three";
import {AfterViewInit, ChangeDetectionStrategy, ChangeDetectorRef, Component, CUSTOM_ELEMENTS_SCHEMA, DestroyRef, effect, ElementRef, inject, isDevMode, output, signal, viewChild} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import SETTINGS from "../../utility/settings";
import {ANIMATION_DURATION_MILLIS, ARROW_SPACING, CLIENT_IMAGE_PADDING, CLIENT_IMAGE_SIZE} from "../../utility/map.constants";
import {MapDataService} from "../../service/map-data.service";
import {connectStations, connectWith45} from "../../utility/drawing";
import {OrbitControls} from "three/examples/jsm/controls/OrbitControls.js";
import {LineMaterial} from "three/examples/jsm/lines/LineMaterial.js";
import {LineGeometry} from "three/examples/jsm/lines/LineGeometry.js";
import {Line2} from "three/examples/jsm/lines/Line2.js";
import Stats from "three/examples/jsm/libs/stats.module.js";
import {rotate, trig45} from "../../data/utilities";
import {SplitNamePipe} from "../../pipe/split-name.pipe";
import {ThemeService} from "../../service/theme.service";
import {MapSelectionService} from "../../service/map-selection.service";
import {ProgressSpinnerModule} from "primeng/progressspinner";
import {ClientsService} from "../../service/clients.service";
import {MmtrTrainsService} from "../../service/mmtr-trains.service";
import {MmtrPoint, MmtrPointsService} from "../../service/mmtr-points.service";
import {MmtrTopologyService} from "../../service/mmtr-topology.service";
import {MmtrLinesService} from "../../service/mmtr-lines.service";
import {MmtrLayersService} from "../../service/mmtr-layers.service";
import {TooltipModule} from "primeng/tooltip";
import {NgOptimizedImage} from "@angular/common";
import {TranslocoDirective} from "@jsverse/transloco";

const blackColor = 0x000000;
const whiteColor = 0xFFFFFF;
const grayColorLight = 0xDDDDDD;
const grayColorDark = 0x222222;
const materialWithVertexColors = new THREE.MeshBasicMaterial({vertexColors: true});
const lineMaterialStationConnectionThin = new LineMaterial({color: 0xFFFFFF, linewidth: 4 * SETTINGS.scale * devicePixelRatio, vertexColors: true});
const lineMaterialStationConnectionThick = new LineMaterial({color: 0xFFFFFF, linewidth: 8 * SETTINGS.scale * devicePixelRatio, vertexColors: true});
const lineMaterialNormal = new LineMaterial({color: 0xFFFFFF, linewidth: 6 * SETTINGS.scale * devicePixelRatio, vertexColors: true});
const lineMaterialNormalDashed = new LineMaterial({color: 0xFFFFFF, linewidth: 6 * SETTINGS.scale * devicePixelRatio, vertexColors: true, dashed: true});
const lineMaterialThin = new LineMaterial({color: 0xFFFFFF, linewidth: 3 * SETTINGS.scale * devicePixelRatio, vertexColors: true});
const lineMaterialThinDashed = new LineMaterial({color: 0xFFFFFF, linewidth: 3 * SETTINGS.scale * devicePixelRatio, vertexColors: true, dashed: true});
/** P2 rail topology layer: fat ROUND white edges (fully opaque), black halo pass underneath so the
 * white edges stay readable on light themes too. Edges are pure topology - one line per real rail. */
const lineMaterialRailHalo = new LineMaterial({color: 0x000000, linewidth: 9 * SETTINGS.scale * devicePixelRatio, transparent: true, opacity: 0.45});
const lineMaterialRailCore = new LineMaterial({color: 0xFFFFFF, linewidth: 5 * SETTINGS.scale * devicePixelRatio, depthWrite: false});
/** MMTR signal layer (信号显示): aspect-coloured cores drawn over the white topology rails. */
const lineMaterialSignalRed = new LineMaterial({color: 0xFF4D4F, linewidth: 6 * SETTINGS.scale * devicePixelRatio, depthWrite: false});
const lineMaterialSignalYellow = new LineMaterial({color: 0xFFB300, linewidth: 6 * SETTINGS.scale * devicePixelRatio, depthWrite: false});
const lineMaterialSignalDoubleYellow = new LineMaterial({color: 0xFFE082, linewidth: 6 * SETTINGS.scale * devicePixelRatio, depthWrite: false});

@Component({
	selector: "app-map",
	changeDetection: ChangeDetectionStrategy.OnPush,
	imports: [
		TooltipModule,
		ProgressSpinnerModule,
		TranslocoDirective,
		SplitNamePipe,
		NgOptimizedImage,
	],
	templateUrl: "./map.component.html",
	styleUrl: "./map.component.scss",
	schemas: [CUSTOM_ELEMENTS_SCHEMA],
})
export class MapComponent implements AfterViewInit {
	private readonly changeDetectorRef = inject(ChangeDetectorRef);
	private readonly destroyRef = inject(DestroyRef);
	private readonly mapDataService = inject(MapDataService);
	private readonly mapSelectionService = inject(MapSelectionService);
	private readonly clientsService = inject(ClientsService);
	private readonly mmtrTrainsService = inject(MmtrTrainsService);
	readonly mmtrPointsService = inject(MmtrPointsService);
	private readonly mmtrTopologyService = inject(MmtrTopologyService);
	private readonly mmtrLinesService = inject(MmtrLinesService);
	readonly mmtrLayersService = inject(MmtrLayersService);
	private readonly themeService = inject(ThemeService);

	readonly stationClicked = output<string>();
	readonly clientClicked = output<string>();
	private readonly wrapperRef = viewChild<ElementRef<HTMLDivElement>>("wrapper");
	private readonly canvasRef = viewChild<ElementRef<HTMLCanvasElement>>("canvas");
	private readonly statsRef = viewChild<ElementRef<HTMLDivElement>>("stats");
	readonly clientGroupsOnRoute = signal<ClientGroupOnRoute[]>([]);
	readonly textLabels = signal<TextLabel[]>([]);
	/** MMTR: live mission-driven train markers (task belongs to the train; the marker shows it). */
	readonly trainMarkers = signal<TrainMarker[]>([]);
	/** P2: fork (道岔) markers on the map - one per junction node with 2+ continuations, coloured by
	 * state (locked red / operator green / auto-held amber / idle gray). Clicking selects the node
	 * and opens the point console. */
	readonly pointMarkers = signal<PointMarker[]>([]);
	/** P2: the fork rows of the selected node (one per approach rail), fed to the point console. */
	readonly selectedNodePoints = signal<MmtrPoint[]>([]);
	/** MMTR: live rail signal aspects (per-rail block display), polled with the trains feed. */
	protected readonly liveSignals = this.mmtrTrainsService.signals;
	private selectedNodeKey = "";
	readonly clientImageSize = CLIENT_IMAGE_SIZE;
	readonly loading = this.mapDataService.mapLoading;

	private timeoutId = 0;
	private animationFrameId = 0;
	private clientPositions: Record<string, { x: number, y: number }> = {};

	private railLayer: THREE.Group | undefined;
	/** MMTR signal layer: aspect-coloured cores over the white rail topology. */
	private signalLayer: THREE.Group | undefined;
	private readonly lineGroups = new Map<string, THREE.Group>();
	private readonly liveLineMaterials: LineMaterial[] = [];
	private static readonly RAIL_Z_INDEX = 0;
	/** Monochrome line styling: gray tiers + focus emphasis (black & white console). */
	private static readonly LINE_GRAYS = [0xFFFFFF, 0xD4DAE0, 0xAEB6BE, 0x8B949C];
	private static readonly LINE_NAMES = ["L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8"];

	private readonly clientGroupsOnRouteRaw: {
		clients: { id: string, name: string }[],
		x: number,
		y: number,
	}[] = [];

	private readonly scene = new THREE.Scene();
	private readonly camera = new THREE.OrthographicCamera(0, 0, 0, 0, -200, 200);
	private controls: OrbitControls | undefined;
	private stationGeometry: THREE.BufferGeometry | undefined;
	private oneWayArrowGeometry: THREE.BufferGeometry | undefined;
	private lineGeometryStationConnectionThin: LineGeometry | undefined;
	private lineGeometryStationConnectionThick: LineGeometry | undefined;
	private lineGeometryNormal: LineGeometry | undefined;
	private lineGeometryNormalDashed: LineGeometry | undefined;
	private lineGeometryThin: LineGeometry | undefined;
	private lineGeometryThinDashed: LineGeometry | undefined;
	private pointsForLineConnection: Record<string, [number, number, boolean][]> = {};

	private viewReadyRetries = 0;

	private canvas() {
		return this.canvasRef()!.nativeElement; // only used after the view is initialised
	}

	constructor() {
		// P2: whenever the turnout feed refreshes (3s poll + after each write), reproject the fork
		// markers and keep the open console row data current (hidden when the 道岔 layer is off).
		effect(() => {
			this.mmtrPointsService.points();
			if (!this.mmtrLayersService.points()) {
				this.pointMarkers.set([]);
				this.selectedNodePoints.set([]);
				return;
			}
			this.updatePointOverlays();
		});
		// Rail topology + automatic lines: rebuilt whenever data or layer visibility changes.
		effect(() => {
			this.mmtrTopologyService.rails();
			this.mmtrLinesService.lines();
			this.mmtrLayersService.rails();
			this.mmtrLayersService.linesLayer();
			this.mmtrLayersService.visibleLines();
			this.mmtrLayersService.focusedLine();
			this.applyRailLayer();
			this.applySignalLayer();
		});
		// MMTR live overlay (信号 + 车辆): repaint whenever the trains feed refreshes (3s poll) and
		// after every map move - vehicle positions and signal aspects follow the simulation.
		effect(() => {
			this.mmtrTrainsService.trains();
			this.mmtrTrainsService.signals();
			this.mmtrTrainsService.lastUpdated();
			const canvas = this.canvasRef()?.nativeElement;
			if (!this.loading() && this.controls && canvas && canvas.clientWidth > 0 && canvas.clientHeight > 0) {
				this.applySignalLayer();
				this.updateLabels();
			}
		});
	}

	/**
	 * (Re)build the track layers of the management console:
	 * <ul>
	 *   <li>base layer (开关: 轨道): one fat ROUND white edge per real rail, pure topology, with a
	 *       dark halo underneath for light-theme legibility;</li>
	 *   <li>lines layer (开关: 线路): when automatic lines are available, every rail of a VISIBLE
	 *       line is redrawn on top in its own monochrome gray tier (focused line = bold white);
	 *       rails of hidden lines simply disappear from the lines view.</li>
	 * </ul>
	 */
	private applyRailLayer() {
		const rails = this.mmtrTopologyService.rails();
		this.clearRailLayer();
		if (rails.length === 0) {
			return;
		}
		const group = new THREE.Group();
		if (this.mmtrLayersService.rails()) {
			for (const rail of rails) {
				const geometry = new LineGeometry();
				geometry.setPositions([
					rail.x1, -rail.z1, MapComponent.RAIL_Z_INDEX,
					rail.x2, -rail.z2, MapComponent.RAIL_Z_INDEX,
				]);
				const halo = new Line2(geometry, lineMaterialRailHalo);
				halo.computeLineDistances();
				const core = new Line2(geometry, lineMaterialRailCore);
				core.computeLineDistances();
				group.add(halo, core);
			}
		}
		this.railLayer = group;
		this.scene.add(this.railLayer);

		// Lines overlay (grayscale tiers, redrawn over the base edges).
		const lines = this.mmtrLinesService.lines();
		this.clearLineLayers();
		if (this.mmtrLayersService.linesLayer() && lines.length > 0) {
			const visible = new Set(this.mmtrLayersService.visibleLines());
			const focused = this.mmtrLayersService.focusedLine();
			lines.forEach((line, index) => {
				if (!visible.has(line.id)) {
					return;
				}
				const isFocused = line.id === focused;
				const gray = MapComponent.LINE_GRAYS[index % MapComponent.LINE_GRAYS.length];
				const haloWidth = (isFocused ? 11 : 9) * SETTINGS.scale * devicePixelRatio;
				const coreWidth = (isFocused ? 7 : 5) * SETTINGS.scale * devicePixelRatio;
				const haloMat = new LineMaterial({color: 0x000000, linewidth: haloWidth, transparent: true, opacity: isFocused ? 0.7 : 0.45});
				const coreMat = new LineMaterial({color: isFocused ? 0xFFFFFF : gray, linewidth: coreWidth, depthWrite: false});
				this.liveLineMaterials.push(haloMat, coreMat);
				const layerGroup = new THREE.Group();
				for (const hex of line.rails) {
					const rail = rails.find(candidate => candidate.hex === hex);
					if (!rail) {
						continue;
					}
					const geometry = new LineGeometry();
					geometry.setPositions([
						rail.x1, -rail.z1, MapComponent.RAIL_Z_INDEX + 1,
						rail.x2, -rail.z2, MapComponent.RAIL_Z_INDEX + 1,
					]);
					const halo = new Line2(geometry, haloMat);
					halo.computeLineDistances();
					const core = new Line2(geometry, coreMat);
					core.computeLineDistances();
					layerGroup.add(halo, core);
				}
				if (layerGroup.children.length > 0) {
					this.scene.add(layerGroup);
					this.lineGroups.set(line.id, layerGroup);
				}
			});
		}
	}

	private clearRailLayer() {
		if (this.railLayer) {
			this.railLayer.children.forEach(child => {
				if ((child as unknown as Line2).isLine2) {
					(child as unknown as Line2).geometry.dispose();
				}
			});
			this.scene.remove(this.railLayer);
			this.railLayer = undefined;
		}
	}

	/**
	 * MMTR signal layer (信号显示): one coloured core per rail whose live aspect is not clear -
	 * red = occupied block, single yellow = the next rail is occupied, double yellow = two rails
	 * ahead (the same chain the in-game signal lights display). Rebuilt on every trains-feed
	 * refresh (3s) and map change; clear rails stay white underneath.
	 */
	private applySignalLayer() {
		this.clearSignalLayer();
		const rails = this.mmtrTopologyService.rails();
		const aspects = this.mmtrTrainsService.signals();
		if (rails.length === 0 || aspects.length === 0) {
			return;
		}
		const canvas = this.canvasRef()?.nativeElement;
		if (canvas && canvas.clientWidth > 0 && canvas.clientHeight > 0) {
			[lineMaterialSignalRed, lineMaterialSignalYellow, lineMaterialSignalDoubleYellow].forEach(material => material.resolution.set(canvas.clientWidth, canvas.clientHeight));
		}
		const aspectByHex = new Map<string, string>();
		aspects.forEach(aspect => aspectByHex.set(aspect.hex, aspect.aspect));
		const group = new THREE.Group();
		for (const rail of rails) {
			const aspect = aspectByHex.get(rail.hex);
			if (!aspect || aspect === "GREEN") {
				continue;
			}
			const geometry = new LineGeometry();
			geometry.setPositions([
				rail.x1, -rail.z1, MapComponent.RAIL_Z_INDEX + 0.5,
				rail.x2, -rail.z2, MapComponent.RAIL_Z_INDEX + 0.5,
			]);
			const material = aspect === "RED" ? lineMaterialSignalRed : aspect === "DOUBLE_YELLOW" ? lineMaterialSignalDoubleYellow : lineMaterialSignalYellow;
			const line = new Line2(geometry, material);
			line.computeLineDistances();
			group.add(line);
		}
		this.signalLayer = group;
		this.scene.add(this.signalLayer);
	}

	private clearSignalLayer() {
		if (this.signalLayer) {
			this.signalLayer.children.forEach(child => {
				if ((child as unknown as Line2).isLine2) {
					(child as unknown as Line2).geometry.dispose();
				}
			});
			this.scene.remove(this.signalLayer);
			this.signalLayer = undefined;
		}
	}

	private clearLineLayers() {
		this.lineGroups.forEach(group => {
			group.children.forEach(child => {
				if ((child as unknown as Line2).isLine2) {
					(child as unknown as Line2).geometry.dispose();
				}
			});
			this.scene.remove(group);
		});
		this.lineGroups.clear();
		this.liveLineMaterials.forEach(material => material.dispose());
		this.liveLineMaterials.length = 0;
	}

	/** Project the fork markers and the open console selection onto the current camera view. */
	private updatePointOverlays() {
		const canvas = this.canvasRef()?.nativeElement;
		if (!canvas || canvas.clientWidth === 0 || canvas.clientHeight === 0 || !this.controls) {
			return;
		}
		const halfCanvasWidth = canvas.clientWidth / 2;
		const halfCanvasHeight = canvas.clientHeight / 2;
		const grouped = new Map<string, MmtrPoint[]>();
		for (const point of this.mmtrPointsService.points()) {
			if (point.legs.length < 2) {
				continue;
			}
			const key = `${point.x},${point.y},${point.z}`;
			if (!grouped.has(key)) {
				grouped.set(key, []);
			}
			grouped.get(key)!.push(point);
		}
		const newMarkers: PointMarker[] = [];
		for (const [key, points] of grouped) {
			const first = points[0];
			const canvasX = (first.x - this.camera.position.x) * this.camera.zoom;
			const canvasY = (first.z + this.camera.position.y) * this.camera.zoom;
			if (Math.abs(canvasX) > halfCanvasWidth || Math.abs(canvasY) > halfCanvasHeight) {
				continue;
			}
			const anyLocked = points.some(point => point.locked);
			const anyManual = points.some(point => point.manual >= 0);
			const anyHeld = points.some(point => point.holder !== "");
			newMarkers.push({
				key,
				count: points.length,
				state: anyLocked ? "locked" : anyManual ? "manual" : anyHeld ? "held" : "idle",
				x: canvasX + halfCanvasWidth,
				y: canvasY + halfCanvasHeight,
			});
		}
		newMarkers.sort((a, b) => a.state.localeCompare(b.state));
		this.pointMarkers.set(newMarkers);
		this.refreshSelection();
	}

	/** Keep the console selection in sync with the live feed (rows refresh after every poll/write). */
	private refreshSelection() {
		if (this.selectedNodeKey === "") {
			this.selectedNodePoints.set([]);
			return;
		}
		const selected = this.mmtrPointsService.points().filter(point => point.legs.length >= 2 && `${point.x},${point.y},${point.z}` === this.selectedNodeKey);
		this.selectedNodePoints.set(selected);
	}

	/** Open (or close when re-clicked) the point console for the given junction node. */
	protected selectNode(key: string) {
		this.selectedNodeKey = this.selectedNodeKey === key ? "" : key;
		this.refreshSelection();
	}

	protected closeNode() {
		this.selectedNodeKey = "";
		this.refreshSelection();
	}

	protected pointStatus(point: MmtrPoint): string {
		const parts: string[] = [];
		if (point.locked) {
			parts.push("锁定");
		}
		if (point.manual >= 0 && point.manual < point.legs.length) {
			parts.push(`人工 → ${point.manual}:${this.kindText(point.legs[point.manual].kind)}`);
		} else if (point.manual < 0 && !point.locked && point.holder === "") {
			parts.push("未设等待");
		}
		if (point.holder !== "") {
			parts.push(`自动:${point.holder}@${point.holderLeg}`);
		}
		if (point.queue.length > 0) {
			parts.push(`排队:${point.queue.length}`);
		}
		return parts.join(" · ");
	}

	protected kindText(kind: string): string {
		switch (kind) {
			case "STRAIGHT": return "直";
			case "LEFT": return "左";
			case "RIGHT": return "右";
			default: return "他";
		}
	}

	protected formText(form: string): string {
		switch (form) {
			case "FORK": return "分叉";
			case "TEE": return "丁字";
			case "MULTI": return "多支";
			default: return form;
		}
	}

	protected shortHex(hex: string): string {
		const digits = hex.replace(/-/g, "");
		return digits.length > 10 ? `…${digits.slice(-10)}` : hex;
	}

	protected throwBranch(point: MmtrPoint, leg: number) {
		this.mmtrPointsService.setBranch(point, leg);
	}

	protected clearBranch(point: MmtrPoint) {
		this.mmtrPointsService.clearBranch(point);
	}

	protected toggleLock(point: MmtrPoint) {
		this.mmtrPointsService.setLocked(point, !point.locked);
	}

	ngAfterViewInit() {
		// Defensive startup: the template queries must be resolved before the WebGL scene is built.
		// If a query is still unavailable at this lifecycle point, retry on the next tick instead of
		// throwing (seen in production where the point/layer effects flush before view init).
		if (!this.wrapperRef() || !this.canvasRef() || !this.statsRef()) {
			if (this.viewReadyRetries++ < 30) {
				setTimeout(() => this.ngAfterViewInit(), 16);
			} else {
				console.warn("[mmtr-map] map view queries never resolved; map disabled");
			}
			return;
		}
		const stats = isDevMode() ? new Stats() : undefined;
		if (stats) {
			this.statsRef()!.nativeElement.append(stats.dom);
		}

		this.scene.background = new THREE.Color(this.getBackgroundColor()).convertLinearToSRGB();
		const renderer = new THREE.WebGLRenderer({antialias: true, canvas: this.canvas()});
		renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
		let hasUpdate = false;
		let needsCenter = true;
		let animationStartX = 0;
		let animationStartY = 0;
		let animationTargetX = 0;
		let animationTargetY = 0;
		let animationStartTime = 0;
		let lineNormalDashed: Line2 | undefined;
		let lineThinDashed: Line2 | undefined;

		const draw = (scheduleRedraw: boolean) => {
			hasUpdate = true;
			if (scheduleRedraw) {
				window.clearTimeout(this.timeoutId);
				this.timeoutId = window.setTimeout(() => {
					hasUpdate = true;
					this.createStationBlobs();
					this.createStationConnections();
					this.createLines(() => {
						lineNormalDashed?.computeLineDistances();
						lineThinDashed?.computeLineDistances();
					});
				}, 100);
			}
		};

		const animate = () => {
			const animationProgress = Date.now() - animationStartTime;
			if (animationProgress < ANIMATION_DURATION_MILLIS) {
				const animationPercentage = (1 - Math.cos(Math.PI * animationProgress / ANIMATION_DURATION_MILLIS)) / 2;
				this.moveMap(
					animationStartX + (animationTargetX - animationStartX) * animationPercentage,
					animationStartY + (animationTargetY - animationStartY) * animationPercentage,
				);
			}

		const {clientWidth, clientHeight} = this.canvas();
			if (clientWidth !== renderer.domElement.width || clientHeight !== renderer.domElement.height) {
				renderer.setSize(clientWidth * devicePixelRatio, clientHeight * devicePixelRatio, false);
				this.camera.left = -clientWidth / 2;
				this.camera.right = clientWidth / 2;
				this.camera.top = clientHeight / 2;
				this.camera.bottom = -clientHeight / 2;
				(this.camera as unknown as { aspect: number }).aspect = clientWidth / clientHeight;
				lineMaterialStationConnectionThin.resolution.set(clientWidth, clientHeight);
				lineMaterialStationConnectionThick.resolution.set(clientWidth, clientHeight);
				lineMaterialNormal.resolution.set(clientWidth, clientHeight);
				lineMaterialNormalDashed.resolution.set(clientWidth, clientHeight);
				lineMaterialThin.resolution.set(clientWidth, clientHeight);
				lineMaterialThinDashed.resolution.set(clientWidth, clientHeight);
				lineMaterialRailHalo.resolution.set(clientWidth, clientHeight);
				lineMaterialRailCore.resolution.set(clientWidth, clientHeight);
				lineMaterialSignalRed.resolution.set(clientWidth, clientHeight);
				lineMaterialSignalYellow.resolution.set(clientWidth, clientHeight);
				lineMaterialSignalDoubleYellow.resolution.set(clientWidth, clientHeight);
				this.liveLineMaterials.forEach(material => material.resolution.set(clientWidth, clientHeight));
				this.camera.updateProjectionMatrix();
			}

			renderer.render(this.scene, this.camera);

			if (hasUpdate) {
				this.updateLabels();
				hasUpdate = false;
			}

			stats?.update();
			this.animationFrameId = requestAnimationFrame(animate);
		};
		this.animationFrameId = requestAnimationFrame(animate);

		this.controls = new OrbitControls(this.camera, this.wrapperRef()!.nativeElement);
		this.controls.target.set(0, 0, 0);
		this.controls.update();
		this.controls.mouseButtons = {LEFT: THREE.MOUSE.PAN, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.PAN};
		this.controls.touches.ONE = THREE.TOUCH.PAN;
		this.controls.zoomToCursor = true;
		this.controls.zoomSpeed = 2;
		this.controls.addEventListener("change", () => draw(true));
		const resizeHandler = () => draw(true);
		window.addEventListener("resize", resizeHandler);

		this.mapDataService.drawMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
			this.scene.background = new THREE.Color(this.getBackgroundColor()).convertLinearToSRGB();
			this.scene.clear();
			this.applyRailLayer();

			if (needsCenter) {
				this.centerMap();
				needsCenter = false;
			}

			this.stationGeometry = new THREE.BufferGeometry();
			this.createStationBlobs();
			this.scene.add(new THREE.Mesh(this.stationGeometry, materialWithVertexColors));

			this.lineGeometryStationConnectionThin = new LineGeometry();
			this.lineGeometryStationConnectionThick = new LineGeometry();
			this.lineGeometryNormal = new LineGeometry();
			this.lineGeometryNormalDashed = new LineGeometry();
			this.lineGeometryThin = new LineGeometry();
			this.lineGeometryThinDashed = new LineGeometry();
			this.oneWayArrowGeometry = new THREE.BufferGeometry();
			this.createStationConnections();
			this.createLines(() => {
				// empty
			});

			const lineStationConnectionThin = new Line2(this.lineGeometryStationConnectionThin, lineMaterialStationConnectionThin);
			lineStationConnectionThin.computeLineDistances();
			this.scene.add(lineStationConnectionThin);

			const lineStationConnectionThick = new Line2(this.lineGeometryStationConnectionThick, lineMaterialStationConnectionThick);
			lineStationConnectionThick.computeLineDistances();
			this.scene.add(lineStationConnectionThick);

			const lineNormal = new Line2(this.lineGeometryNormal, lineMaterialNormal);
			lineNormal.computeLineDistances();
			this.scene.add(lineNormal);

			lineNormalDashed = new Line2(this.lineGeometryNormalDashed, lineMaterialNormalDashed);
			lineNormalDashed.computeLineDistances();
			this.scene.add(lineNormalDashed);

			const lineThin = new Line2(this.lineGeometryThin, lineMaterialThin);
			lineThin.computeLineDistances();
			this.scene.add(lineThin);

			lineThinDashed = new Line2(this.lineGeometryThinDashed, lineMaterialThinDashed);
			lineThinDashed.computeLineDistances();
			this.scene.add(lineThinDashed);

			this.scene.add(new THREE.Mesh(this.oneWayArrowGeometry, materialWithVertexColors));
			this.updateLabels();
			draw(false);
		});

		this.mapSelectionService.updateSelection.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
			if (!this.mapDataService.mapLoading()) {
				draw(true);
			}
		});

		this.mapDataService.animateMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(({x, z}) => {
			animationStartX = this.camera.position.x;
			animationStartY = this.camera.position.y;
			animationTargetX = x;
			animationTargetY = -z;
			animationStartTime = Date.now();
		});

		this.mapDataService.animateClient.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(id => {
			const client = this.clientPositions[id];
			if (client) {
				animationStartX = this.camera.position.x;
				animationStartY = this.camera.position.y;
				animationTargetX = client.x;
				animationTargetY = client.y;
				animationStartTime = Date.now();
			}
		});

		this.clientsService.dataProcessed.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
			if (!this.mapDataService.mapLoading()) {
				draw(true);
			}
		});

		this.destroyRef.onDestroy(() => {
			cancelAnimationFrame(this.animationFrameId);
			window.clearTimeout(this.timeoutId);
			window.removeEventListener("resize", resizeHandler);
			renderer.dispose();
			this.controls?.dispose();
		});
	}

	trackByIcon(index: number, icon: string): string {
		return icon;
	}

	private createStationBlobs() {
		const positions: number[] = [];
		const colors: number[] = [];
		const backgroundColor = this.getBackgroundColor();
		const newClientImagePadding = CLIENT_IMAGE_PADDING * SETTINGS.scale / this.camera.zoom / 2;
		const newClientImageSize = CLIENT_IMAGE_PADDING * SETTINGS.scale / this.camera.zoom / 2;
		this.clientPositions = {};
		this.clientGroupsOnRouteRaw.length = 0;

		const createShape = (radius: number, newWidth: number, newHeight: number) => {
			const newRadius = radius * SETTINGS.scale / this.camera.zoom;
			const shape = new THREE.Shape();
			const toRadians = (angle: number) => angle * Math.PI / 180;
			shape.moveTo(-newWidth, newHeight + newRadius);
			shape.arc(0, -newRadius, newRadius, toRadians(90), toRadians(180));
			shape.lineTo(-newWidth - newRadius, -newHeight);
			shape.arc(newRadius, 0, newRadius, toRadians(180), toRadians(270));
			shape.lineTo(newWidth, -newHeight - newRadius);
			shape.arc(0, newRadius, newRadius, toRadians(270), toRadians(360));
			shape.lineTo(newWidth + newRadius, newHeight);
			shape.arc(-newRadius, 0, newRadius, toRadians(0), toRadians(90));
			shape.lineTo(-newWidth, newHeight + newRadius);
			return shape;
		};

		const processShape = (x: number, y: number, radius: number, newWidth: number, newHeight: number, newRotate: boolean, offset: number, color: number) => {
			const shapePoints = createShape(radius, newWidth, newHeight).getPoints(2);
			for (let i = 1; i < shapePoints.length; i++) {
				positions.push(x, y, offset);
				const point1 = new THREE.Vector2(shapePoints[i - 1].x + x, shapePoints[i - 1].y + y).rotateAround(new THREE.Vector2(x, y), newRotate ? Math.PI / 4 : 0);
				positions.push(point1.x, point1.y, offset);
				const point2 = new THREE.Vector2(shapePoints[i].x + x, shapePoints[i].y + y).rotateAround(new THREE.Vector2(x, y), newRotate ? Math.PI / 4 : 0);
				positions.push(point2.x, point2.y, offset);
				MapComponent.setColor(color, colors, 3);
			}
		};

		this.mapDataService.stationsForMap().forEach(({station, rotate, width, height}) => {
			const {id, x, z} = station;
			const stationSelected = this.mapSelectionService.selectedStations.length === 0 || this.mapSelectionService.selectedStations.includes(id);
			const adjustZ = stationSelected ? 20 : 0;
			const newWidth = width * 3 * SETTINGS.scale / this.camera.zoom;
			const newHeight = height * 3 * SETTINGS.scale / this.camera.zoom;
			processShape(x, -z, 7, newWidth, newHeight, rotate, adjustZ - 1, this.getColor(blackColor, whiteColor, grayColorLight, grayColorDark, stationSelected));
			processShape(x, -z, 5, newWidth, newHeight, rotate, adjustZ, this.getColor(whiteColor, blackColor, backgroundColor, backgroundColor, stationSelected));

			const clientGroups = this.clientsService.clientGroupsForStation()[id];
			if (clientGroups) {
				const clientCount = clientGroups.clients.length;
				const newClientImageWidth = newClientImageSize * clientCount + newClientImagePadding * (clientCount - 1);
				processShape(x, -z, 7, newClientImageWidth, newClientImageSize, false, adjustZ - 1, this.getColor(blackColor, whiteColor, grayColorLight, grayColorDark, stationSelected));
				processShape(x, -z, 5, newClientImageWidth, newClientImageSize, false, adjustZ, this.getColor(whiteColor, blackColor, backgroundColor, backgroundColor, stationSelected));
			}
		});

		this.clientsService.allClients().forEach(({id, rawX, rawZ}) => this.clientPositions[id] = {x: rawX, y: -rawZ});
		Object.entries(this.clientsService.clientGroupsForRoute()).forEach(([routeKey, {clients, x, z, route, routeStationId1, routeStationId2}]) => {
			const points = this.pointsForLineConnection[routeKey];
			if (points) {
				let closestX = 0;
				let closestY = 0;
				let shortestDistance = Number.MAX_SAFE_INTEGER;
				for (let i = 1; i < points.length; i++) {
					const [x1, z1] = points[i - 1];
					const [x2, z2] = points[i];
					const {closestPoint, distance} = MapComponent.closestPointAndDistanceToSegment(x1, -z1, x2, -z2, x, -z);
					if (distance < shortestDistance) {
						shortestDistance = distance;
						closestX = closestPoint.x;
						closestY = closestPoint.y;
					}
				}

				const color = route?.color ?? 0;
				const lineSelected = this.mapSelectionService.selectedStations.length === 0 || this.mapSelectionService.selectedStationConnections.some(stationConnection => stationConnection.routeColor === color && stationConnection.stationIds[0] === routeStationId1 && stationConnection.stationIds[1] === routeStationId2);
				const adjustZ = lineSelected ? 18 : -2;
				const clientCount = clients.length;
				const newClientImageWidth = newClientImageSize * clientCount + newClientImagePadding * (clientCount - 1);
				processShape(closestX, closestY, 5, newClientImageWidth, newClientImageSize, false, adjustZ, this.getColor(color, color, grayColorLight, grayColorDark, lineSelected));
				clients.forEach(({id}) => this.clientPositions[id] = {x: closestX, y: closestY});
				this.clientGroupsOnRouteRaw.push({clients, x: closestX, y: closestY});
			}
		});

		if (this.stationGeometry) {
			this.stationGeometry.setAttribute("position", new THREE.BufferAttribute(new Float32Array(positions), 3));
			this.stationGeometry.setAttribute("color", new THREE.BufferAttribute(new Float32Array(colors), 3));
		}
	}

	private createStationConnections() {
		lineMaterialStationConnectionThin.dashed = this.mapDataService.interchangeStyle() === "DOTTED";
		lineMaterialStationConnectionThin.dashSize = 8 * SETTINGS.scale / this.camera.zoom;
		lineMaterialStationConnectionThin.gapSize = 4 * SETTINGS.scale / this.camera.zoom;

		const positionsThin = [0, 0, -10000, 0, 0, -10000];
		const positionsThick = [0, 0, -10000, 0, 0, -10000];
		const colorsThin = [0, 0, 0, 0, 0, 0];
		const colorsThick = [0, 0, 0, 0, 0, 0];
		const backgroundColor = this.getBackgroundColor();

		this.mapDataService.stationConnections().forEach(({x1, z1, x2, z2, stationId1, stationId2, start45}) => {
			const selected = this.mapSelectionService.selectedStations.length === 0 || this.mapSelectionService.selectedStations.includes(stationId1) && this.mapSelectionService.selectedStations.includes(stationId2);
			const adjustZ = selected ? 20 : 0;

			const write = (offset: number, positions: number[], colors: number[], color: number) => {
				const points: [number, number][] = [];
				connectWith45(points, x1, z1, x2, z2, start45);
				positions.push(points[0][0], -points[0][1], -10000);
				MapComponent.setColor(color, colors);
				points.forEach(point => {
					positions.push(point[0], -point[1], -offset + adjustZ);
					MapComponent.setColor(color, colors);
				});
				positions.push(points[points.length - 1][0], -points[points.length - 1][1], -10000);
				MapComponent.setColor(color, colors);
			};

			write(this.mapDataService.interchangeStyle() === "DOTTED" ? 1 : 0, positionsThin, colorsThin, this.mapDataService.interchangeStyle() === "DOTTED" ? this.getColor(blackColor, whiteColor, grayColorLight, grayColorDark, selected) : this.getColor(whiteColor, blackColor, backgroundColor, backgroundColor, selected));
			write(this.mapDataService.interchangeStyle() === "DOTTED" ? 2 : 1, positionsThick, colorsThick, this.mapDataService.interchangeStyle() === "DOTTED" ? backgroundColor : this.getColor(blackColor, whiteColor, grayColorLight, grayColorDark, selected));
		});

		if (this.lineGeometryStationConnectionThin) {
			this.lineGeometryStationConnectionThin.setPositions(positionsThin);
			this.lineGeometryStationConnectionThin.setColors(colorsThin);
		}

		if (this.lineGeometryStationConnectionThick) {
			this.lineGeometryStationConnectionThick.setPositions(positionsThick);
			this.lineGeometryStationConnectionThick.setColors(colorsThick);
		}
	}

	private createLines(refreshDashedLines: () => void) {
		this.pointsForLineConnection = {};

		lineMaterialNormalDashed.dashSize = 8 * SETTINGS.scale / this.camera.zoom;
		lineMaterialNormalDashed.gapSize = 4 * SETTINGS.scale / this.camera.zoom;
		lineMaterialThinDashed.dashSize = 16 * SETTINGS.scale / this.camera.zoom;
		lineMaterialThinDashed.gapSize = 16 * SETTINGS.scale / this.camera.zoom;

		const positionsNormal = [0, 0, -10000, 0, 0, -10000];
		const positionsNormalDashed = [0, 0, -10000, 0, 0, -10000];
		const positionsThin = [0, 0, -10000, 0, 0, -10000];
		const positionsThinDashed = [0, 0, -10000, 0, 0, -10000];
		const positionsArrow: number[] = [];
		const colorsNormal = [0, 0, 0, 0, 0, 0];
		const colorsNormalDashed = [0, 0, 0, 0, 0, 0];
		const colorsThin = [0, 0, 0, 0, 0, 0];
		const colorsThinDashed = [0, 0, 0, 0, 0, 0];
		const colorsArrow: number[] = [];
		const backgroundColor = this.getBackgroundColor();

		const drawArrow = (color: number, angle: number, x: number, y: number, z: number) => {
			const [offset1X, offset1Y] = rotate(3 * SETTINGS.scale / this.camera.zoom, 0, angle);
			const [offset2X, offset2Y] = rotate(0, 3 * SETTINGS.scale / this.camera.zoom, angle);
			positionsArrow.push(x - offset1X, -(y - offset1Y), -z);
			positionsArrow.push(x + offset2X, -(y + offset2Y), -z);
			positionsArrow.push(x + offset1X, -(y + offset1Y), -z);
			positionsArrow.push(x, -y, -z);
			positionsArrow.push(x + offset1X, -(y + offset1Y), -z);
			positionsArrow.push(x + offset1X - offset2X, -(y + offset1Y - offset2Y), -z);
			positionsArrow.push(x - offset1X - offset2X, -(y - offset1Y - offset2Y), -z);
			positionsArrow.push(x - offset1X, -(y - offset1Y), -z);
			positionsArrow.push(x, -y, -z);
			MapComponent.setColor(color, colorsArrow, 9);
		};

		this.mapDataService.lineConnections().forEach(({lineConnectionParts, direction1, direction2, x1, z1, x2, z2, stationId1, stationId2, length, relativeLength}) => {
			const hidden = length * this.camera.zoom < 10;
			for (let i = 0; i < lineConnectionParts.length; i++) {
				const {color, offset1, offset2, oneWay} = lineConnectionParts[i];
				const colorInt = parseInt(color.split("|")[0]);
				const lineSelected = this.mapSelectionService.selectedStations.length === 0 || this.mapSelectionService.selectedStationConnections.some(stationConnection => stationConnection.routeColor === colorInt && stationConnection.stationIds[0] === stationId1 && stationConnection.stationIds[1] === stationId2);
				const newColorInt = this.getColor(colorInt, colorInt, grayColorLight, grayColorDark, lineSelected);
				const noService = false; // TODO
				const colorOffset = (i - lineConnectionParts.length / 2 + 0.5) * 6 * SETTINGS.scale;
				const routeTypeVisibility = this.mapDataService.routeTypeVisibility()[color.split("|")[1]];
				const dashed = routeTypeVisibility === "DASHED";
				const hollow = routeTypeVisibility === "HOLLOW" || dashed;
				const adjustZ = lineSelected ? 20 : 0;
				const lineZ = (hollow ? (oneWay === 0 ? -8 : -12) : (oneWay === 0 ? -2 : -5)) - relativeLength + adjustZ;

				// z layers
				// 2-3     solid    two-way line
				// 4       solid    one-way arrows
				// 5-6     solid    one-way line
				// 7       hollow   white fill
				// 8-9     hollow   two-way line
				// 10      hollow   one-way arrows
				// 11      hollow   white fill
				// 12-13   hollow   one-way line

				const [points, oneWayPoints] = connectStations(
					x1,
					z1,
					x2,
					z2,
					direction1,
					direction2,
					offset1 * 6 * SETTINGS.scale / this.camera.zoom,
					offset2 * 6 * SETTINGS.scale / this.camera.zoom,
					colorOffset / this.camera.zoom,
					this.canvas().clientWidth,
					this.canvas().clientHeight,
					oneWay,
				);

				if (points.length >= 2) {
					points.forEach(([x, y, offset]) => {
						const newZ = hidden || offset ? -10000 : lineZ;
						(noService ? positionsNormalDashed : positionsNormal).push(x, -y, newZ);
						MapComponent.setColor(newColorInt, (noService ? colorsNormalDashed : colorsNormal));
						if (hollow) {
							(dashed ? positionsThinDashed : positionsThin).push(x, -y, newZ + 1);
							MapComponent.setColor(backgroundColor, (dashed ? colorsThinDashed : colorsThin));
						}
					});

					this.pointsForLineConnection[ClientsService.getRouteConnectionKey(stationId1, stationId2, colorInt)] = points;
				}

				if (oneWayPoints.length >= 2) {
					oneWayPoints.forEach(([point1X, point1Y, point2X, point2Y, angle]) => {
						const differenceX = point2X - point1X;
						const differenceY = point2Y - point1Y;
						const distance = Math.sqrt(differenceX * differenceX + differenceY * differenceY);
						const scaledArrowSpacing = ARROW_SPACING * SETTINGS.scale / Math.min(5, this.camera.zoom);
						const arrowCount = Math.floor(distance / scaledArrowSpacing);
						const padding = (distance - arrowCount * scaledArrowSpacing) / 2;
						const [hollowArrowPaddingX, hollowArrowPaddingY] = trig45(-angle + 2, 1.5 * Math.SQRT2 * SETTINGS.scale / this.camera.zoom);

						for (let j = 0; j < arrowCount; j++) {
							const offset = distance === 0 ? 0 : (padding + scaledArrowSpacing * (j + 0.5)) / distance;
							const x = point1X + differenceX * offset;
							const y = point1Y + differenceY * offset;
							if (hollow) {
								drawArrow(newColorInt, angle, x - hollowArrowPaddingX, y - hollowArrowPaddingY, (hollow ? 10 : 4) - adjustZ);
								drawArrow(newColorInt, angle, x + hollowArrowPaddingX, y + hollowArrowPaddingY, (hollow ? 10 : 4) - adjustZ);
							}
							drawArrow(backgroundColor, angle, x, y, (hollow ? 10 : 4) - adjustZ);
						}
					});
				}
			}
		});

		if (this.lineGeometryNormal) {
			this.lineGeometryNormal.dispose();
			this.lineGeometryNormal.setPositions(positionsNormal);
			this.lineGeometryNormal.setColors(colorsNormal);
		}

		if (this.lineGeometryNormalDashed) {
			this.lineGeometryNormalDashed.dispose();
			this.lineGeometryNormalDashed.setPositions(positionsNormalDashed);
			this.lineGeometryNormalDashed.setColors(colorsNormalDashed);
		}

		if (this.lineGeometryThin) {
			this.lineGeometryThin.dispose();
			this.lineGeometryThin.setPositions(positionsThin);
			this.lineGeometryThin.setColors(colorsThin);
		}

		if (this.lineGeometryThinDashed) {
			this.lineGeometryThinDashed.dispose();
			this.lineGeometryThinDashed.setPositions(positionsThinDashed);
			this.lineGeometryThinDashed.setColors(colorsThinDashed);
		}

		if (this.oneWayArrowGeometry) {
			this.oneWayArrowGeometry.setAttribute("position", new THREE.BufferAttribute(new Float32Array(positionsArrow), 3));
			this.oneWayArrowGeometry.setAttribute("color", new THREE.BufferAttribute(new Float32Array(colorsArrow), 3));
		}

		refreshDashedLines();
	}

	private updateLabels() {
		const halfCanvasWidth = this.canvas().clientWidth / 2;
		const halfCanvasHeight = this.canvas().clientHeight / 2;
		const newTextLabels: TextLabel[] = [];
		let renderedTextCount = 0;

		this.mapDataService.stationsForMap().forEach(({station, rotate, width, height}) => {
			const {id, name, getIcons, x, z} = station;
			const canvasX = (x - this.camera.position.x) * this.camera.zoom;
			const canvasY = (z + this.camera.position.y) * this.camera.zoom;
			const clientGroup = this.clientsService.clientGroupsForStation()[id];

			if (Math.abs(canvasX) <= halfCanvasWidth && Math.abs(canvasY) <= halfCanvasHeight && (clientGroup || renderedTextCount < SETTINGS.maxText * 2) && (this.mapSelectionService.selectedStations.length === 0 || this.mapSelectionService.selectedStations.includes(id))) {
				const newWidth = width * 3 * SETTINGS.scale;
				const newHeight = height * 3 * SETTINGS.scale;
				const clientsHeight = clientGroup ? CLIENT_IMAGE_SIZE * SETTINGS.scale / 2 : 0;
				const clientsWidth = clientGroup ? clientsHeight * clientGroup.clients.length + CLIENT_IMAGE_PADDING * SETTINGS.scale * (clientGroup.clients.length - 1) / 2 : 0;
				const rotatedSize = (newHeight + newWidth) * Math.SQRT1_2;
				const textOffset = Math.max(rotate ? rotatedSize : newHeight, clientsHeight) + 9 * SETTINGS.scale;
				const icons = getIcons(type => this.mapDataService.routeTypeVisibility()[type] === "HIDDEN");
				newTextLabels.push({
					hoverOverride: false,
					id,
					text: name,
					icons,
					shouldRenderText: !!clientGroup || renderedTextCount < SETTINGS.maxText,
					clients: clientGroup?.clients,
					clientImagePadding: CLIENT_IMAGE_PADDING * SETTINGS.scale,
					x: canvasX + halfCanvasWidth,
					y: canvasY + halfCanvasHeight - textOffset,
					stationWidth: Math.max(rotate ? rotatedSize : newWidth, clientsWidth) * 2 + 18 * SETTINGS.scale,
					stationHeight: Math.max(rotate ? rotatedSize : newHeight, clientsHeight) * 2 + 18 * SETTINGS.scale,
				});
				renderedTextCount++;
			}
		});

		const newClientGroupsOnRoute: ClientGroupOnRoute[] = [];
		this.clientGroupsOnRouteRaw.forEach(({clients, x, y}) => {
			const canvasX = (x - this.camera.position.x) * this.camera.zoom;
			const canvasY = (-y + this.camera.position.y) * this.camera.zoom;
			newClientGroupsOnRoute.push({
				clients,
				clientImagePadding: CLIENT_IMAGE_PADDING * SETTINGS.scale,
				x: canvasX + halfCanvasWidth,
				y: canvasY + halfCanvasHeight - CLIENT_IMAGE_SIZE * SETTINGS.scale / 2,
			});
		});

		this.clientsService.allClientsNotInStationOrRoute().forEach(({id, name, rawX, rawZ}) => {
			const canvasX = (rawX - this.camera.position.x) * this.camera.zoom;
			const canvasY = (rawZ + this.camera.position.y) * this.camera.zoom;
			newClientGroupsOnRoute.push({
				clients: [{id, name}],
				clientImagePadding: CLIENT_IMAGE_PADDING * SETTINGS.scale,
				x: canvasX + halfCanvasWidth,
				y: canvasY + halfCanvasHeight - CLIENT_IMAGE_SIZE * SETTINGS.scale / 2,
			});
		});

		const newTrainMarkers: TrainMarker[] = [];
		// MMTR live trains: every consist with a position - parked stock in the depot too (grey,
		// labelled 库) and service trains coloured by mission state. Depot trains stand close
		// together, so markers at the same spot fan out by a few pixels.
		const stacked = new Map<string, number>();
		this.mmtrTrainsService.trains().forEach(train => {
			if (train.headX === undefined || train.headZ === undefined) {
				return;
			}
			const canvasX = (train.headX - this.camera.position.x) * this.camera.zoom;
			const canvasY = (train.headZ + this.camera.position.y) * this.camera.zoom;
			if (Math.abs(canvasX) > halfCanvasWidth || Math.abs(canvasY) > halfCanvasHeight) {
				return;
			}
			const stackKey = `${Math.round(canvasX)},${Math.round(canvasY)}`;
			const stackIndex = stacked.get(stackKey) ?? 0;
			stacked.set(stackKey, stackIndex + 1);
			const parked = !train.onRoute;
			newTrainMarkers.push({
				vehicleId: train.vehicleId,
				label: parked ? "库" : train.vehicleId.slice(-4),
				missionState: train.mission?.state ?? "",
				parked,
				moving: train.moving,
				color: parked ? "" : this.missionColor(train.mission?.state ?? ""),
				x: canvasX + halfCanvasWidth,
				y: canvasY + halfCanvasHeight + stackIndex * 16,
			});
		});
		this.textLabels.set(newTextLabels);
		this.clientGroupsOnRoute.set(newClientGroupsOnRoute);
		this.trainMarkers.set(newTrainMarkers);
		this.updatePointOverlays();
		this.changeDetectorRef.detectChanges();
	}

	private centerMap() {
		this.moveMap(-this.mapDataService.centerX(), this.mapDataService.centerY());
	}

	private moveMap(x: number, y: number) {
		if (this.controls) {
			this.camera.position.x = x;
			this.camera.position.y = y;
			this.controls.target.set(this.camera.position.x, this.camera.position.y, 0);
			this.controls.update();
		}
	}

	private getColor(lightColorNormal: number, darkColorNormal: number, lightColorDisabled: number, darkColorDisabled: number, isSelected: boolean) {
		if (isSelected) {
			return this.isDarkTheme() ? darkColorNormal : lightColorNormal;
		} else {
			return this.isDarkTheme() ? darkColorDisabled : lightColorDisabled;
		}
	}

	private getBackgroundColor() {
		const backgroundColorComponents = getComputedStyle(document.body).getPropertyValue("--background-color").match(/#[a-f\d]+/g)?.map(value => parseInt(value.substring(1), 16));
		return backgroundColorComponents ? backgroundColorComponents[0] : 0;
	}

	private isDarkTheme() {
		return this.themeService.isDarkTheme();
	}

	private static setColor(color: number, colors: number[], times = 1) {
		const r = (color >> 16) & 0xFF;
		const g = (color >> 8) & 0xFF;
		const b = color & 0xFF;
		for (let i = 0; i < times; i++) {
			colors.push(r / 0xFF, g / 0xFF, b / 0xFF);
		}
	}

	// Thanks ChatGPT
	private static closestPointAndDistanceToSegment(x1: number, y1: number, x2: number, y2: number, pointX: number, pointY: number): { closestPoint: { x: number, y: number }, distance: number } {
		const dx = x2 - x1;
		const dy = y2 - y1;

		// Handle case where segment is a single point
		if (dx === 0 && dy === 0) {
			return {closestPoint: {x: x1, y: y1}, distance: Math.hypot(pointX - x1, pointY - y1)};
		}

		// Compute projection scalar t
		const t = ((pointX - x1) * dx + (pointY - y1) * dy) / (dx * dx + dy * dy);

		// Clamp t to [0, 1] to restrict to the segment
		const tClamped = Math.max(0, Math.min(1, t));

		// Compute closest point on the segment
		const closestX = x1 + tClamped * dx;
		const closestY = y1 + tClamped * dy;

		return {closestPoint: {x: closestX, y: closestY}, distance: Math.hypot(pointX - closestX, pointY - closestY)};
	}

	/** MMTR marker colour by mission state (empty = no active task). */
	readonly missionColor = (state: string): string => {
		switch (state) {
			case "ASSIGNED": return "#f6c343";
			case "DISPATCHED": return "#4fb0ff";
			case "AT_TARGET": return "#3ddc84";
			case "FAILED": return "#ff6d6d";
			default: return "#9aa0a6";
		}
	};
}

interface ClientGroupOnRoute {
	readonly clients: { id: string; name: string }[];
	readonly clientImagePadding: number;
	readonly x: number;
	readonly y: number;
}

interface TrainMarker {
	readonly vehicleId: string;
	readonly label: string;
	readonly missionState: string;
	readonly parked: boolean;
	readonly moving: boolean;
	readonly color: string;
	readonly x: number;
	readonly y: number;
}

interface PointMarker {
	readonly key: string;
	readonly count: number;
	readonly state: "locked" | "manual" | "held" | "idle";
	readonly x: number;
	readonly y: number;
}

interface TextLabel {
	hoverOverride: boolean;
	readonly id: string;
	readonly text: string;
	readonly icons: string[];
	readonly shouldRenderText: boolean;
	readonly clients?: { id: string; name: string }[];
	readonly clientImagePadding: number;
	readonly x: number;
	readonly y: number;
	readonly stationWidth: number;
	readonly stationHeight: number;
}

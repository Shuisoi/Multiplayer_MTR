import {ChangeDetectionStrategy, Component, CUSTOM_ELEMENTS_SCHEMA, DestroyRef, inject, signal} from "@angular/core";
import {takeUntilDestroyed} from "@angular/core/rxjs-interop";
import {interval} from "rxjs";
import {MapComponent} from "./component/map/map.component";
import {MmtrOpsPanelComponent} from "./component/mmtr-ops-panel/mmtr-ops-panel.component";
import {MmtrLinesPanelComponent} from "./component/mmtr-lines-panel/mmtr-lines-panel.component";
import {MmtrTrainsService} from "./service/mmtr-trains.service";
import {MmtrPointsService} from "./service/mmtr-points.service";
import {MmtrLinesService} from "./service/mmtr-lines.service";
import {MmtrLayersService} from "./service/mmtr-layers.service";
import {MmtrSectionsService} from "./service/mmtr-sections.service";
import {MmtrSchematicService} from "./service/mmtr-schematic.service";
import {ThemeService} from "./service/theme.service";

/**
 * MMTR 运营台 (management console, 黑白极简): full-screen real-network map with automatic-line
 * rendering and layer toggles, a status bar (fleet / turnouts / lines / clock) and a right-hand
 * operations drawer (车队 · 任务 · 作业单). The original MTR station website chrome (search,
 * departures, directions, station panels) is removed - this console is the server's operator UI.
 */
@Component({
	selector: "app-root",
	changeDetection: ChangeDetectionStrategy.OnPush,
	imports: [
		MapComponent,
		MmtrOpsPanelComponent,
		MmtrLinesPanelComponent,
	],
	templateUrl: "./app.component.html",
	styleUrl: "./app.component.scss",
	schemas: [CUSTOM_ELEMENTS_SCHEMA],
})
export class AppComponent {
	private readonly trainsService = inject(MmtrTrainsService);
	private readonly pointsService = inject(MmtrPointsService);
	private readonly linesService = inject(MmtrLinesService);
	private readonly sectionsService = inject(MmtrSectionsService);
	private readonly schematicService = inject(MmtrSchematicService);
	private readonly themeService = inject(ThemeService);
	private readonly destroyRef = inject(DestroyRef);

	readonly layersService = inject(MmtrLayersService);
	protected readonly drawerOpen = signal(false);
	protected readonly clock = signal("--:--:--");

	protected vehicles = () => this.trainsService.trains().length;
	protected onRoute = () => this.trainsService.trains().filter(train => train.onRoute).length;
	protected forks = () => this.pointsService.points().length;
	protected manualPoints = () => this.pointsService.points().filter(point => point.manual >= 0).length;
	protected lockedPoints = () => this.pointsService.points().filter(point => point.locked).length;
	protected lineCount = () => this.linesService.lines().length;
	/** 区间图 summary: how many blocks the engine's division has, and how many hold a train right now. */
	protected sectionCount = () => this.sectionsService.blockCount();
	protected occupiedSectionCount = () => this.sectionsService.occupiedCount();
	/** 区间图: which figure is drawn (world map or the lattice diagram) and how big it is. */
	protected view = () => this.layersService.view();
	protected squareCount = () => this.schematicService.squareCount();
	protected cellMeters = () => this.schematicService.data().cellM;

	constructor() {
		// Monochrome console: the management view is dark (white strokes, gray tiers).
		if (!this.themeService.isDarkTheme()) {
			this.themeService.setTheme(true);
		}
		const updateClock = () => {
			const now = new Date();
			this.clock.set(
				[now.getHours(), now.getMinutes(), now.getSeconds()].map(value => String(value).padStart(2, "0")).join(":"),
			);
		};
		updateClock();
		interval(1000).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(updateClock);
	}

	protected toggleDrawer() {
		this.drawerOpen.set(!this.drawerOpen());
	}

	/** Switch the drawn figure: the world map, or the 区间图 (the block layer on a 1x1 lattice). */
	protected setView(view: "world" | "schematic") {
		this.layersService.view.set(view);
	}

	/** Layer switches back to their defaults (world view, all layers on, no focus). */
	protected resetLayers() {
		this.layersService.view.set("world");
		this.layersService.rails.set(true);
		this.layersService.linesLayer.set(true);
		this.layersService.points.set(true);
		this.layersService.sections.set(true);
		this.layersService.visibleLines.set([]);
		this.layersService.focusedLine.set("");
	}
}

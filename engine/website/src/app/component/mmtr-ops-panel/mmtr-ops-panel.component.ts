import {ChangeDetectionStrategy, Component, CUSTOM_ELEMENTS_SCHEMA, inject} from "@angular/core";
import {ProgressSpinnerModule} from "primeng/progressspinner";
import {DividerModule} from "primeng/divider";
import {MmtrTrainsService, MmtrTrainState} from "../../service/mmtr-trains.service";

@Component({
	selector: "app-mmtr-ops-panel",
	changeDetection: ChangeDetectionStrategy.OnPush,
	imports: [
		ProgressSpinnerModule,
		DividerModule,
	],
	templateUrl: "./mmtr-ops-panel.component.html",
	styleUrl: "./mmtr-ops-panel.component.scss",
	schemas: [CUSTOM_ELEMENTS_SCHEMA],
})
export class MmtrOpsPanelComponent {
	private readonly mmtrTrainsService = inject(MmtrTrainsService);

	protected readonly trains = this.mmtrTrainsService.trains;
	protected readonly sidings = this.mmtrTrainsService.sidings;
	protected readonly loading = this.mmtrTrainsService.loading;

	protected readonly allActive = () => this.trains().filter(train => !train.mission || !["COMPLETE", "FAILED", "CANCELED"].includes(train.mission!.state));

	protected missionLabel(train: MmtrTrainState): string {
		if (!train.mission) {
			return "无任务 / idle";
		}
		const mission = train.mission;
		const kind: Record<string, string> = {PASSENGER: "客运", FREIGHT: "货运", MANEUVER: "调车"};
		const state: Record<string, string> = {ASSIGNED: "已指派", DISPATCHED: "已发车", AT_TARGET: "已到目标", COMPLETE: "完成", FAILED: "失败", CANCELED: "取消"};
		return `${kind[mission.kind] ?? mission.kind} · ${state[mission.state] ?? mission.state} · ${mission.executor}`;
	}

	protected statusLabel(train: MmtrTrainState): string {
		if (train.onRoute) {
			return train.moving ? `运行中 ${train.speedKmh.toFixed(0)} km/h` : "在线停站";
		}
		return "库内";
	}

	/** Dispatch a parked manual train to the terminal of its current path (MANEUVER). */
	protected dispatch(train: MmtrTrainState) {
		this.mmtrTrainsService.dispatch(train.vehicleId, "MANEUVER");
	}

	protected canDispatch(train: MmtrTrainState): boolean {
		return !train.onRoute && train.isManualAllowed;
	}
}
import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {DimensionService} from "./dimension.service";
import {LIVE_REFRESH_INTERVAL_MILLIS} from "../utility/refresh.constants";

export interface MmtrMissionState {
	kind: string;
	state: string;
	executor: string;
	startSidingId: number;
	targetSidingId: number;
	assignedMillis: number;
	failureReason?: string;
}

export interface MmtrTrainState {
	vehicleId: number;
	sidingId: number;
	sidingName: string;
	depotName: string;
	routeName: string;
	routeNumber: string;
	destination: string;
	isManualAllowed: boolean;
	isCurrentlyManual: boolean;
	onRoute: boolean;
	moving: boolean;
	speedKmh: number;
	railProgressM: number;
	doorsOpen: boolean;
	headX?: number;
	headZ?: number;
	mission?: MmtrMissionState;
}

export interface MmtrSignalState { id: string; }

export interface MmtrSidingState {
	sidingId: number;
	sidingName: string;
	depotName: string;
	manual: boolean;
	vehiclesTotal: number;
	vehiclesParked: number;
}

/**
 * Polls the engine "mmtr-trains" SystemMap endpoint: live trains with their assigned missions
 * (the task belongs to the train) and siding occupancy. Self-heals when the dimension changes
 * because each poll re-reads the current dimension index.
 */
@Injectable({providedIn: "root"})
export class MmtrTrainsService {
	public readonly trains = signal<MmtrTrainState[]>([]);
	public readonly sidings = signal<MmtrSidingState[]>([]);
	public readonly loading = signal(true);
	public readonly lastUpdated = signal(0);

	private readonly httpClient = inject(HttpClient);
	private readonly dimensionService = inject(DimensionService);
	private readonly destroyRef = inject(DestroyRef);
	private timeoutId = 0;

	constructor() {
		this.poll();
		this.destroyRef.onDestroy(() => clearTimeout(this.timeoutId));
	}

	private getUrl() {
		return `${document.location.origin}${document.location.pathname}mtr/api/map/mmtr-trains?dimension=${this.dimensionService.getDimensionIndex()}`;
	}

	private poll() {
		this.httpClient.get<{ data: { currentTime: number, trains: MmtrTrainState[], sidings: MmtrSidingState[], signals?: MmtrSignalState[], points?: MmtrSignalState[] } }>(this.getUrl()).subscribe({
			next: response => {
				this.trains.set(response.data.trains ?? []);
				this.sidings.set(response.data.sidings ?? []);
				this.lastUpdated.set(Date.now());
				this.loading.set(false);
				this.schedule();
			},
			error: error => {
				console.error("mmtr-trains feed failed", error);
				this.loading.set(false);
				this.schedule();
			},
		});
	}
	private schedule() {
		clearTimeout(this.timeoutId);
		this.timeoutId = setTimeout(() => this.poll(), LIVE_REFRESH_INTERVAL_MILLIS) as unknown as number;
	}

	/**
	 * Dispatch entry: assign a mission to a parked train and start it headlessly (AUTOPILOT).
	 * Fire-and-forget; the next poll reflects the updated train state.
	 */
	public dispatch(vehicleId: number, kind = "MANEUVER") {
		const url = `${document.location.origin}${document.location.pathname}mtr/api/map/mmtr-dispatch?dimension=${this.dimensionService.getDimensionIndex()}`;
		this.httpClient.post<{ data: { ok: boolean } }>(url, {vehicleId, kind, startNow: true}).subscribe({
			next: () => this.refresh(),
			error: error => console.error("mmtr dispatch failed", error),
		});
	}

	/** Force an immediate feed refresh (after a dispatch or mission change). */
	public refresh() {
		clearTimeout(this.timeoutId);
		this.poll();
	}

}
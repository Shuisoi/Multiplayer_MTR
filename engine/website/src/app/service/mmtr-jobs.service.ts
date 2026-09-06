import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {DimensionService} from "./dimension.service";
import {LIVE_REFRESH_INTERVAL_MILLIS} from "../utility/refresh.constants";

export interface MmtrJobSummary {
	jobId: string;
	startTimeOfDayMs: number;
	steps: { stepId?: string, type?: string }[];
}

export interface MmtrJobStateSummary {
	jobId: string;
	startTimeOfDayMs: number;
	state: string;
	step: number;
	totalSteps: number;
	failure?: string;
}

/**
 * Web editor data for consist jobs (diagrams). Reads the engine's mmtr-jobs document plus the
 * live executor states; write endpoints (upsert/delete) are ready for the full editor UI.
 */
@Injectable({providedIn: "root"})
export class MmtrJobsService {
	public readonly jobs = signal<MmtrJobSummary[]>([]);
	public readonly states = signal<MmtrJobStateSummary[]>([]);
	public readonly loading = signal(true);

	private readonly httpClient = inject(HttpClient);
	private readonly dimensionService = inject(DimensionService);
	private readonly destroyRef = inject(DestroyRef);
	private timeoutId = 0;

	constructor() {
		this.poll();
		this.destroyRef.onDestroy(() => clearTimeout(this.timeoutId));
	}

	private mapUrl(endpoint: string) {
		return `${document.location.origin}${document.location.pathname}mtr/api/map/${endpoint}?dimension=${this.dimensionService.getDimensionIndex()}`;
	}

	private poll() {
		this.httpClient.get<{ data: { jobs: MmtrJobSummary[] } }>(this.mapUrl("mmtr-jobs")).subscribe({
			next: response => {
				this.jobs.set(response.data?.jobs ?? []);
				this.loading.set(false);
				this.fetchStates();
			},
			error: error => {
				console.error("mmtr-jobs feed failed", error);
				this.loading.set(false);
				this.schedule();
			},
		});
	}

	private fetchStates() {
		this.httpClient.get<{ data: { states: MmtrJobStateSummary[] } }>(this.mapUrl("mmtr-job-states")).subscribe({
			next: response => {
				this.states.set(response.data?.states ?? []);
				this.schedule();
			},
			error: error => {
				console.error("mmtr-job-states failed", error);
				this.schedule();
			},
		});
	}

	private schedule() {
		clearTimeout(this.timeoutId);
		this.timeoutId = setTimeout(() => this.poll(), LIVE_REFRESH_INTERVAL_MILLIS) as unknown as number;
	}
}

import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {DimensionService} from "./dimension.service";
import {LIVE_REFRESH_INTERVAL_MILLIS} from "../utility/refresh.constants";

/** One step of a consist job. Ids travel as decimal strings (64-bit ids must not lose precision in JS). */
export interface MmtrJobStep {
	stepId?: string;
	type: "MOVE_TO" | "SERVE" | "COUPLE" | "UNCOUPLE";
	/** In-game target object id: platform/siding (MOVE_TO/SERVE). */
	targetId?: string;
	/** COUPLE only: stable id of the other consist job whose spawned stock is coupled onto this one. */
	targetJobId?: string;
	/** UNCOUPLE only: car index to cut after. */
	targetIndex?: number;
	/** Latest allowed completion time, milliseconds after in-game midnight. */
	dueTimeOfDayMs: number;
	note?: string;
}

/** One car of the job's rolling stock; mirrors the engine MmtrCarSpec (stable, editor-authored). */
export interface MmtrCarSpec {
	vehicleId: string;
	length: number;
	width: number;
	capacity: number;
	bogie1Position: number;
	bogie2Position: number;
	couplingPadding1: number;
	couplingPadding2: number;
}

/** A consist job (车底作业单): one consist for one day, spawned on sidingId at startTimeOfDayMs. */
export interface MmtrConsistJob {
	jobId: string;
	depotId: string;
	sidingId: string;
	startTimeOfDayMs: number;
	repeatDaily: boolean;
	cars: MmtrCarSpec[];
	steps: MmtrJobStep[];
}

/** Backwards-compatible short alias used by older call sites. */
export type MmtrJobSummary = MmtrConsistJob;

export interface MmtrJobStateSummary {
	jobId: string;
	startTimeOfDayMs: number;
	state: string;
	step: number;
	totalSteps: number;
	failure?: string;
}

/** In-game objects the editor pickers reference (decimal id + display names). */
export interface MmtrDepotRef {
	id: string;
	name: string;
}

export interface MmtrSidingRef {
	id: string;
	name: string;
	depotId: string;
	depotName: string;
	manual?: boolean;
}

export interface MmtrPlatformRef {
	id: string;
	name: string;
	stationName: string;
}

export interface MmtrJobReferences {
	depots: MmtrDepotRef[];
	sidings: MmtrSidingRef[];
	platforms: MmtrPlatformRef[];
}

const EMPTY_REFERENCES: MmtrJobReferences = {depots: [], sidings: [], platforms: []};

/**
 * Web data plane for consist jobs (车底作业单): reads the engine's mmtr-jobs document plus the
 * live executor states and the in-game depot/siding/platform references; writes jobs back through
 * the mmtr-jobs-upsert / mmtr-jobs-delete endpoints (the job editor UI).
 */
@Injectable({providedIn: "root"})
export class MmtrJobsService {
	public readonly jobs = signal<MmtrConsistJob[]>([]);
	public readonly states = signal<MmtrJobStateSummary[]>([]);
	public readonly references = signal<MmtrJobReferences>(EMPTY_REFERENCES);
	public readonly loading = signal(true);
	public readonly writeFeedback = signal("");
	private feedbackTimer = 0;

	private readonly httpClient = inject(HttpClient);
	private readonly dimensionService = inject(DimensionService);
	private readonly destroyRef = inject(DestroyRef);
	private timeoutId = 0;

	constructor() {
		this.poll();
		this.destroyRef.onDestroy(() => {
			clearTimeout(this.timeoutId);
			clearTimeout(this.feedbackTimer);
		});
	}

	private mapUrl(endpoint: string) {
		return `${document.location.origin}${document.location.pathname}mtr/api/map/${endpoint}?dimension=${this.dimensionService.getDimensionIndex()}`;
	}

	private poll() {
		this.httpClient.get<{ data: { jobs: MmtrConsistJob[] } }>(this.mapUrl("mmtr-jobs")).subscribe({
			next: response => {
				this.jobs.set(response.data?.jobs ?? []);
				this.loading.set(false);
				this.fetchStates();
				this.fetchReferences();
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

	private fetchReferences() {
		this.httpClient.get<{ data: MmtrJobReferences }>(this.mapUrl("mmtr-job-references")).subscribe({
			next: response => {
				this.references.set(response.data ?? EMPTY_REFERENCES);
			},
			error: error => {
				console.error("mmtr-job-references failed", error);
			},
		});
	}

	private schedule() {
		clearTimeout(this.timeoutId);
		this.timeoutId = setTimeout(() => this.poll(), LIVE_REFRESH_INTERVAL_MILLIS) as unknown as number;
	}

	/** Force an immediate re-poll (after a write) so the list reflects the change quickly. */
	public refresh() {
		clearTimeout(this.timeoutId);
		this.poll();
	}

	/** Persist a job (create or full replace by jobId). Returns true when the engine accepted it. */
	public upsert(job: MmtrConsistJob) {
		const url = this.mapUrl("mmtr-jobs-upsert");
		return this.httpClient.post<{ data: { ok: boolean } }>(url, job);
	}

	/** Remove a job by id. Returns true when the engine removed it. */
	public delete(jobId: string) {
		const url = this.mapUrl("mmtr-jobs-delete");
		return this.httpClient.post<{ data: { ok: boolean } }>(url, {jobId});
	}

	public setFeedback(text: string) {
		this.writeFeedback.set(text);
		clearTimeout(this.feedbackTimer);
		this.feedbackTimer = setTimeout(() => this.writeFeedback.set(""), 5000) as unknown as number;
	}

	/** A blank job for the "new job" editor (no cars/steps; jobId auto-generated on save). */
	public emptyJob(): MmtrConsistJob {
		return {
			jobId: "",
			depotId: "0",
			sidingId: "0",
			startTimeOfDayMs: 7 * 3_600_000,
			repeatDaily: true,
			cars: [],
			steps: [],
		};
	}
}
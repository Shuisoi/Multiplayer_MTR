import {HttpClient} from "@angular/common/http";
import {DestroyRef, inject, Injectable, signal} from "@angular/core";
import {LIVE_REFRESH_INTERVAL_MILLIS} from "../utility/refresh.constants";

export interface MmtrScheduleRow {
	stepIndex: number;
	stepId: string;
	type: string;
	taskKind: string;
	targetKind: string;
	targetId: string;
	plannedMs: number;
	note?: string;
	state: "PENDING" | "RUNNING" | "DONE" | "FAILED";
}

export interface MmtrScheduleJob {
	jobId: string;
	depotId: string;
	sidingId: string;
	startTimeOfDayMs: number;
	loop: boolean;
	state: "PENDING" | "RUNNING" | "DONE" | "FAILED";
	currentStep: number;
	failure?: string;
	rows: MmtrScheduleRow[];
}

/**
 * Task-sheet timetable feed (任务单时间表): every consist job rendered as rows of tasks with
 * planned times and per-step state - the OP/player view of a 车底作业单 as a schedule.
 */
@Injectable({providedIn: "root"})
export class MmtrScheduleService {
	public readonly jobs = signal<MmtrScheduleJob[]>([]);
	public readonly loading = signal(true);
	public readonly lastUpdated = signal(0);

	private readonly httpClient = inject(HttpClient);
	private readonly destroyRef = inject(DestroyRef);
	private timeoutId = 0;

	constructor() {
		this.poll();
		this.destroyRef.onDestroy(() => clearTimeout(this.timeoutId));
	}

	private getUrl() {
		return `${document.location.origin}${document.location.pathname}mtr/api/map/mmtr-schedule`;
	}

	private poll() {
		this.httpClient.get<{data: {jobs: MmtrScheduleJob[]}}>(this.getUrl()).subscribe({
			next: response => {
				this.jobs.set(response.data.jobs ?? []);
				this.lastUpdated.set(Date.now());
				this.loading.set(false);
				this.schedule();
			},
			error: error => {
				console.error("mmtr-schedule feed failed", error);
				this.loading.set(false);
				this.schedule();
			},
		});
	}

	private schedule() {
		clearTimeout(this.timeoutId);
		this.timeoutId = setTimeout(() => this.poll(), LIVE_REFRESH_INTERVAL_MILLIS) as unknown as number;
	}

	/** Force an immediate refresh (after job edits). */
	public refresh() {
		clearTimeout(this.timeoutId);
		this.poll();
	}

	/** Planned time: engine-relative clock (ms since the job-clock anchor) as T+mm:ss / hh:mm:ss. */
	public static timeLabel(ms: number): string {
		if (!ms || ms < 0) {
			return "--";
		}
		const totalSeconds = Math.floor(ms / 1000);
		const hh = Math.floor(totalSeconds / 3600);
		const mm = Math.floor((totalSeconds % 3600) / 60);
		const ss = totalSeconds % 60;
		const pad = (value: number) => String(value).padStart(2, "0");
		return hh > 0 ? `${pad(hh)}:${pad(mm)}:${pad(ss)}` : `T+${pad(mm)}:${pad(ss)}`;
	}

	/** Short Chinese task label for the timetable row. */
	public static taskLabel(kind: string): string {
		switch (kind) {
			case "DRIVE_TO_PLATFORM": return "开往站台";
			case "DRIVE_TO_SIDING": return "开往股道";
			case "STATION_SERVICE": return "停站作业";
			case "DRIVE_TURNBACK": return "折返掉头";
			case "DRIVE_TO_CONSIST": return "连挂走行";
			case "FREIGHT_WORK": return "货运作业";
			case "COUPLE": return "连挂";
			case "UNCOUPLE": return "摘解";
			default: return kind;
		}
	}
}

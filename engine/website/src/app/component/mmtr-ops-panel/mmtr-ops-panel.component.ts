import {ChangeDetectionStrategy, Component, CUSTOM_ELEMENTS_SCHEMA, inject, signal} from "@angular/core";
import {ProgressSpinnerModule} from "primeng/progressspinner";
import {DividerModule} from "primeng/divider";
import {MmtrTrainsService, MmtrTrainState} from "../../service/mmtr-trains.service";
import {MmtrConsistJob, MmtrJobStateSummary, MmtrJobsService} from "../../service/mmtr-jobs.service";
import {MmtrScheduleService} from "../../service/mmtr-schedule.service";
import {MmtrSignalsService} from "../../service/mmtr-signals.service";
import {MmtrJobEditorComponent} from "../mmtr-job-editor/mmtr-job-editor.component";

const JOB_STATE_TEXT: Record<string, string> = {
	PENDING: "等待发车",
	RUNNING: "运行中",
	DONE: "已完成",
	FAILED: "已失败",
};

@Component({
	selector: "app-mmtr-ops-panel",
	changeDetection: ChangeDetectionStrategy.OnPush,
	imports: [
		ProgressSpinnerModule,
		DividerModule,
		MmtrJobEditorComponent,
	],
	templateUrl: "./mmtr-ops-panel.component.html",
	styleUrl: "./mmtr-ops-panel.component.scss",
	schemas: [CUSTOM_ELEMENTS_SCHEMA],
})
export class MmtrOpsPanelComponent {
	private readonly mmtrTrainsService = inject(MmtrTrainsService);
	private readonly mmtrJobsService = inject(MmtrJobsService);
	private readonly mmtrScheduleService = inject(MmtrScheduleService);

	protected readonly trains = this.mmtrTrainsService.trains;
	protected readonly sidings = this.mmtrTrainsService.sidings;
	protected readonly loading = this.mmtrTrainsService.loading;
	protected readonly dispatchFeedback = this.mmtrTrainsService.dispatchFeedback;

	protected readonly jobs = this.mmtrJobsService.jobs;
	protected readonly jobStates = this.mmtrJobsService.states;
	protected readonly jobReferences = this.mmtrJobsService.references;
	protected readonly jobsLoading = this.mmtrJobsService.loading;
	protected readonly jobsFeedback = this.mmtrJobsService.writeFeedback;

	protected readonly scheduleJobs = this.mmtrScheduleService.jobs;
	protected readonly scheduleLoading = this.mmtrScheduleService.loading;

	// --- Wayside signals + OP command input (指令栏/信号机管理) ---
	private readonly mmtrSignalsService = inject(MmtrSignalsService);
	protected readonly managedSignals = this.mmtrSignalsService.signals;
	protected readonly signalRails = this.mmtrSignalsService.rails;
	protected readonly signalFeedback = this.mmtrSignalsService.feedback;
	protected readonly commandLog = this.mmtrSignalsService.commandLog;
	protected readonly signalLoading = this.mmtrSignalsService.loading;
	protected readonly cmdInput = signal("");
	protected readonly sigX = signal("0");
	protected readonly sigY = signal("-60");
	protected readonly sigZ = signal("0");
	protected readonly sigAngle = signal("0");
	protected readonly sigAspects = signal("2");
	protected readonly sigTarget = signal("");

	protected runCommand() {
		this.mmtrSignalsService.runCommand(this.cmdInput());
		this.cmdInput.set("");
	}

	protected onCmdInput(event: Event) {
		this.cmdInput.set((event.target as HTMLInputElement).value);
	}

	protected onCommandKey(event: KeyboardEvent) {
		if (event.key === "Enter") {
			this.runCommand();
		}
	}

	protected onSigKeyInput(event: Event, field: "x" | "y" | "z" | "angle" | "aspects") {
		const value = (event.target as HTMLInputElement).value;
		if (field === "x") this.sigX.set(value);
		if (field === "y") this.sigY.set(value);
		if (field === "z") this.sigZ.set(value);
		if (field === "angle") this.sigAngle.set(value);
		if (field === "aspects") this.sigAspects.set(value);
	}

	protected onSigTarget(event: Event) {
		this.sigTarget.set((event.target as HTMLSelectElement).value);
	}

	protected registerSignal() {
		const x = Number(this.sigX());
		const y = Number(this.sigY());
		const z = Number(this.sigZ());
		const angle = Number(this.sigAngle());
		const aspects = Number(this.sigAspects()) || 2;
		const target = this.sigTarget();
		if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z) || !Number.isFinite(angle)) {
			this.mmtrSignalsService.setFeedback("✗ 坐标/角度需为数字");
			return;
		}
		this.mmtrSignalsService.setSignal(x, y, z, angle, aspects, target);
	}

	protected removeSignal(entry: {x: number, y: number, z: number}) {
		this.mmtrSignalsService.removeSignal(entry.x, entry.y, entry.z);
	}

	protected aspectColor(aspect: string): string {
		switch (aspect) {
			case "RED": return "#ff4d4f";
			case "SINGLE_YELLOW": return "#ffb300";
			case "DOUBLE_YELLOW": return "#ffe082";
			case "GREEN": return "#3df59a";
			default: return "#9aa0a6";
		}
	}

	/** undefined = list view; otherwise the job being edited in the inline editor. */
	protected readonly editingJob = signal<MmtrConsistJob | undefined>(undefined);

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

	// ---- Consist jobs (作业单) ----

	protected stateOf(jobId: string): MmtrJobStateSummary | undefined {
		return this.jobStates().find(state => state.jobId === jobId);
	}

	protected openNewJob() {
		this.editingJob.set(this.mmtrJobsService.emptyJob());
	}

	protected openEditJob(job: MmtrConsistJob) {
		this.editingJob.set(job);
	}

	protected jobOp(job: MmtrConsistJob, op: "pause" | "resume" | "human" | "release") {
		this.mmtrJobsService.jobOp(job.jobId, op).subscribe({
			next: response => {
				const label = op === "pause" ? "已暂停" : op === "resume" ? "已继续" : op === "human" ? "已切人工（AI 让位）" : "已交还 AI";
				this.mmtrJobsService.setFeedback(response.data?.ok ? `${label}：${job.jobId}` : `操作失败：${job.jobId}`);
				this.mmtrJobsService.refresh();
			},
			error: error => {
				console.error("mmtr job op failed", error);
				this.mmtrJobsService.setFeedback("操作请求失败：" + (error.status ?? "网络错误"));
			},
		});
	}

	protected closeJobEditor() {
		this.editingJob.set(undefined);
	}

	protected timeOfDay(ms: number): string {
		const totalMinutes = Math.floor(ms / 60_000);
		const hh = Math.floor(totalMinutes / 60);
		const mm = totalMinutes % 60;
		return `${String(hh).padStart(2, "0")}:${String(mm).padStart(2, "0")}`;
	}

	protected jobStatusText(state?: string): string {
		return state === undefined ? "" : (JOB_STATE_TEXT[state] ?? state);
	}

	protected jobProgress(state?: MmtrJobStateSummary): string {
		if (!state || state.totalSteps <= 0) {
			return "等待发车";
		}
		if (state.state === "RUNNING" && state.step >= 0) {
			return `步骤 ${state.step + 1}/${state.totalSteps}`;
		}
		if (state.state === "DONE") {
			return `${state.totalSteps} 步 · 全部完成`;
		}
		if (state.state === "FAILED") {
			return `${state.totalSteps} 步 · 已失败`;
		}
		return `${state.totalSteps} 步 · 等待发车`;
	}

	protected jobStateClass(state?: string): string {
		switch (state) {
			case "RUNNING": return "on-route";
			case "DONE": return "mission";
			case "FAILED": return "failed";
			default: return "";
		}
	}

	protected jobSub(job: MmtrConsistJob): string {
		const parts = [`发车 ${this.timeOfDay(job.startTimeOfDayMs)}`];
		if (job.repeatDaily) {
			parts.push("每日");
		}
		parts.push(`${job.cars?.length ?? 0} 节车 · ${job.steps?.length ?? 0} 步`);
		const siding = this.jobReferences().sidings.find(ref => ref.id === job.sidingId);
		if (siding) {
			parts.push(`${siding.depotName} · ${siding.name}`);
		} else if (job.sidingId && job.sidingId !== "0") {
			parts.push(`股道 ${job.sidingId}`);
		}
		return parts.join(" · ");
	}

	// --- Task-sheet timetable (任务单时间表) helpers ---

	protected scheduleTimeLabel(ms: number): string {
		return MmtrScheduleService.timeLabel(ms);
	}

	protected scheduleTaskLabel(kind: string): string {
		return MmtrScheduleService.taskLabel(kind);
	}

	/** Human target label: station name for platforms / depot·name for sidings, else short id. */
	protected scheduleTargetLabel(kind: string, id: string): string {
		if (!id || id === "0") {
			return "-";
		}
		if (kind === "PLATFORM") {
			const platform = this.jobReferences().platforms.find(ref => ref.id === id);
			if (platform) {
				return platform.stationName ? `站 ${platform.stationName}` : `站台 ${id}`;
			}
		}
		if (kind === "SIDING") {
			const siding = this.jobReferences().sidings.find(ref => ref.id === id);
			if (siding) {
				return `${siding.depotName || "车场"}·${siding.name}`;
			}
		}
		return id.length > 10 ? `…${id.slice(-8)}` : id;
	}

	protected scheduleStateText(state: string): string {
		return JOB_STATE_TEXT[state] ?? state;
	}
}
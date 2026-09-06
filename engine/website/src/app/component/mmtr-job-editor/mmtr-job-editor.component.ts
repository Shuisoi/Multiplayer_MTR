import {ChangeDetectionStrategy, Component, CUSTOM_ELEMENTS_SCHEMA, EventEmitter, inject, Input, OnChanges, Output} from "@angular/core";
import {FormsModule} from "@angular/forms";
import {SelectModule} from "primeng/select";
import {CheckboxModule} from "primeng/checkbox";
import {TooltipModule} from "primeng/tooltip";
import {MmtrCarSpec, MmtrConsistJob, MmtrJobsService, MmtrJobStep} from "../../service/mmtr-jobs.service";

/** Editable step view: server shape plus the HH:MM text edited in the UI. */
interface MmtrJobStepEdit extends MmtrJobStep {
	dueText: string;
}

const STEP_TYPE_OPTIONS = [
	{label: "MOVE_TO 运行", value: "MOVE_TO"},
	{label: "SERVE 停站", value: "SERVE"},
	{label: "COUPLE 连挂", value: "COUPLE"},
	{label: "UNCOUPLE 摘挂", value: "UNCOUPLE"},
];

const CAR_DEFAULTS = (): MmtrCarSpec => ({
	vehicleId: "",
	length: 20,
	width: 3,
	capacity: 400,
	bogie1Position: -7,
	bogie2Position: 7,
	couplingPadding1: 0.5,
	couplingPadding2: 0.5,
});

function timeToText(ms: number): string {
	const totalMinutes = Math.floor((ms || 0) / 60_000);
	const hh = Math.floor(totalMinutes / 60) % 24;
	const mm = totalMinutes % 60;
	return `${String(hh).padStart(2, "0")}:${String(mm).padStart(2, "0")}`;
}

function textToMillis(text: string): number | undefined {
	if (!text) {
		return undefined;
	}
	const match = /^(\d{1,2}):(\d{1,2})$/.exec(text.trim());
	if (!match) {
		return undefined;
	}
	const hh = Number(match[1]);
	const mm = Number(match[2]);
	if (hh > 23 || mm > 59) {
		return undefined;
	}
	return (hh * 60 + mm) * 60_000;
}

/**
 * Consist-job (车底作业单) editor: header (spawn siding / time / daily repeat), rolling-stock
 * cars and the ordered step list with per-step deadlines. Saves through mmtr-jobs-upsert and
 * deletes through mmtr-jobs-delete; the owning ops panel shows/hides this form.
 */
@Component({
	selector: "app-mmtr-job-editor",
	changeDetection: ChangeDetectionStrategy.OnPush,
	imports: [
		FormsModule,
		SelectModule,
		CheckboxModule,
		TooltipModule,
	],
	templateUrl: "./mmtr-job-editor.component.html",
	styleUrl: "./mmtr-job-editor.component.scss",
	schemas: [CUSTOM_ELEMENTS_SCHEMA],
})
export class MmtrJobEditorComponent implements OnChanges {
	@Input() job!: MmtrConsistJob;
	@Output() readonly closed = new EventEmitter<void>();

	private readonly mmtrJobsService = inject(MmtrJobsService);

	protected readonly references = this.mmtrJobsService.references;
	protected readonly typeOptions = STEP_TYPE_OPTIONS;
	protected readonly typeLabels: Record<string, string> = {
		MOVE_TO: "运行",
		SERVE: "停站",
		COUPLE: "连挂",
		UNCOUPLE: "摘挂",
	};

	protected jobId = "";
	protected sidingId = "0";
	protected depotId = "0";
	protected startTime = "07:00";
	protected repeatDaily = true;
	protected cars: MmtrCarSpec[] = [];
	protected steps: MmtrJobStepEdit[] = [];
	protected error = "";
	protected selectedTemplateId = "";

	protected readonly isNew = () => this.jobId === "";

	ngOnChanges(): void {
		this.resetFrom(this.job);
	}

	protected resetFrom(job: MmtrConsistJob) {
		this.jobId = job.jobId ?? "";
		this.sidingId = job.sidingId || "0";
		this.depotId = job.depotId || "0";
		this.startTime = timeToText(job.startTimeOfDayMs);
		this.repeatDaily = job.repeatDaily;
		this.cars = (job.cars ?? []).map(car => ({...car}));
		this.steps = (job.steps ?? []).map(step => ({...step, dueText: timeToText(step.dueTimeOfDayMs), note: step.note ?? ""}));
		this.error = "";
	}

	protected sidingOptions() {
		return this.references().sidings.map(siding => ({
			id: siding.id,
			label: `${siding.depotName} · ${siding.name}${siding.manual ? "（手动）" : ""}`,
		}));
	}

	protected sidingLabel(sidingId: string): string {
		const ref = this.references().sidings.find(siding => siding.id === sidingId);
		return ref ? `${ref.depotName} · ${ref.name}` : `股道 ${sidingId}`;
	}

	protected onSidingChange(sidingId: string) {
		this.sidingId = sidingId;
		const ref = this.references().sidings.find(siding => siding.id === sidingId);
		this.depotId = ref ? ref.depotId : "0";
	}


	/** COUPLE target picker: every other consist job whose stock could be coupled (daily respawn keeps ids stable). */

	/** 编组代码：服务端命名模板（车列一次维护、处处引用）。选择后填充到下方车辆列表（可再手工微调）。 */
	protected templateOptions() {
		return this.references().templates.map(template => ({
			id: template.id,
			label: `${template.id}${template.name ? " · " + template.name : ""}（${template.cars.length} 节）`,
		}));
	}

	protected applyTemplate(templateId: string) {
		const template = this.references().templates.find(t => t.id === templateId);
		if (!template) {
			return;
		}
		this.cars = template.cars.map(spec => ({...spec}));
		this.selectedTemplateId = templateId;
	}

	/** Engine semantics surfaced to the author: COUPLE must precede the source job's spawn; UNCOUPLE is a yard-final cut. */
	protected stepHint(step: MmtrJobStep): string {
		if (step.type === "COUPLE") {
			const targetJobId = (step.targetJobId ?? "").trim();
			if (!targetJobId) {
				return "连挂对象是另一条作业单：其挂车会在本作业单刷车时并入同一编组（引用按 jobId，每日重启后仍有效）。";
			}
			const target = this.mmtrJobsService.jobs().find(job => job.jobId === targetJobId);
			const startMs = textToMillis(this.startTime) ?? 0;
			if (!target) {
				return "目标作业单不存在——请先选择有效的作业单。";
			}
			if (target.startTimeOfDayMs <= startMs) {
				return "注意：引擎要求 COUPLE 先于目标作业单刷车——请把本作业单发车时刻设得早于目标作业单，否则运行时会因排序失败。";
			}
			return "✓ 目标作业单晚于本作业单发车：刷车时自动并入其车列，源作业单标记为已并编。";
		}
		if (step.type === "UNCOUPLE") {
			return "车场最终切分：停场时按 targetIndex 切开；切分后本作业单不再出库（尾部车列写回股道留场，供后续作业单/次日使用）。";
		}
		return "";
	}
	protected coupleTargetOptions() {
		return this.mmtrJobsService.jobs()
			.filter(job => job.jobId !== this.jobId)
			.map(job => ({id: job.jobId, label: `${job.jobId} · 发车 ${timeToText(job.startTimeOfDayMs)} · ${job.cars?.length ?? 0} 节`}));
	}
	/** Datasheet entries shown in the target id autocomplete (platforms first, then sidings). */
	protected targetSuggestions(): string[] {
		const suggestions: string[] = [];
		for (const platform of this.references().platforms) {
			suggestions.push(`${platform.id} ｜ ${platform.stationName} ${platform.name}站台`);
		}
		for (const siding of this.references().sidings) {
			suggestions.push(`${siding.id} ｜ [股道] ${siding.depotName} · ${siding.name}`);
		}
		return suggestions;
	}

	protected parseTargetId(raw: string): string {
		const trimmed = (raw ?? "").trim();
		const separator = trimmed.indexOf("｜");
		return separator >= 0 ? trimmed.slice(0, separator).trim() : trimmed;
	}

	protected addCar() {
		this.cars = [...this.cars, CAR_DEFAULTS()];
	}

	protected removeCar(index: number) {
		this.cars = this.cars.filter((_, i) => i !== index);
	}

	protected duplicateCar(index: number) {
		const copy = {...this.cars[index]};
		this.cars = [...this.cars.slice(0, index + 1), copy, ...this.cars.slice(index + 1)];
	}

	protected addStep() {
		const step: MmtrJobStepEdit = {
			stepId: `step-${this.steps.length + 1}`,
			type: "MOVE_TO",
			targetId: "",
			targetIndex: -1,
			dueTimeOfDayMs: this.steps.length === 0 ? textToMillis(this.startTime)! : 0,
			note: "",
			dueText: this.steps.length === 0 ? this.startTime : "00:00",
		};
		this.steps = [...this.steps, step];
	}

	protected removeStep(index: number) {
		this.steps = this.steps.filter((_, i) => i !== index);
	}

	protected moveStep(index: number, delta: number) {
		const target = index + delta;
		if (target < 0 || target >= this.steps.length) {
			return;
		}
		const next = [...this.steps];
		[next[index], next[target]] = [next[target], next[index]];
		this.steps = next;
	}

	protected save() {
		const startMillis = textToMillis(this.startTime);
		if (startMillis === undefined) {
			this.error = "发车时刻格式应为 HH:MM（24 小时制）";
			return;
		}
		let jobId = this.jobId.trim();
		if (jobId === "") {
			// Auto-generate a readable unique id when the author leaves it blank.
			const now = new Date();
			const stamp = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, "0")}${String(now.getDate()).padStart(2, "0")}-${String(now.getHours()).padStart(2, "0")}${String(now.getMinutes()).padStart(2, "0")}`;
			jobId = `J-${stamp}-${Math.floor(Math.random() * 900 + 100)}`;
		}
		if (this.mmtrJobsService.jobs().some(job => job.jobId === jobId && job.jobId !== this.jobId)) {
			this.error = `作业单 id ${jobId} 已存在`;
			return;
		}
		if (!this.sidingId || this.sidingId === "0") {
			this.error = "请选择刷车股道（发车 depot/siding）";
			return;
		}
		if (this.cars.length === 0) {
			this.error = "请至少添加一节车（编组）";
			return;
		}
		for (let i = 0; i < this.cars.length; i++) {
			if (!this.cars[i].vehicleId.trim()) {
				this.error = `第 ${i + 1} 节车缺少车型 id（vehicleId）`;
				return;
			}
		}
		for (let i = 0; i < this.steps.length; i++) {
			const step = this.steps[i];
			const dueMillis = textToMillis(step.dueText);
			if (dueMillis === undefined) {
				this.error = `步骤 ${i + 1}（${step.stepId ?? ""}）截止时刻格式应为 HH:MM`;
				return;
			}
			step.dueTimeOfDayMs = dueMillis;
			if (step.type === "UNCOUPLE") {
				if (step.targetIndex === undefined || step.targetIndex < 0) {
					this.error = `步骤 ${i + 1}（摘挂）需要指定摘开车厢位置 targetIndex（从 0 起）`;
					return;
				}
			} else if (step.type === "COUPLE") {
				const targetJobId = (step.targetJobId ?? "").trim();
				if (!targetJobId || targetJobId === jobId || !this.mmtrJobsService.jobs().some(job => job.jobId === targetJobId)) {
					this.error = `步骤 ${i + 1}（连挂）需要选择另一条作业单（编组来源）`;
					return;
				}
				step.targetJobId = targetJobId;
			} else {
				const targetId = this.parseTargetId(step.targetId ?? "");
				if (!targetId || targetId === "0") {
					this.error = `步骤 ${i + 1}（${this.typeLabels[step.type] ?? step.type}）缺少目标 id`;
					return;
				}
				step.targetId = targetId;
			}
		}
		const payload: MmtrConsistJob = {
			jobId,
			depotId: this.depotId,
			sidingId: this.sidingId,
			startTimeOfDayMs: startMillis,
			repeatDaily: this.repeatDaily,
			cars: this.cars.map(car => ({
				vehicleId: car.vehicleId.trim(),
				length: Number(car.length),
				width: Number(car.width),
				capacity: Number(car.capacity),
				bogie1Position: Number(car.bogie1Position),
				bogie2Position: Number(car.bogie2Position),
				couplingPadding1: Number(car.couplingPadding1),
				couplingPadding2: Number(car.couplingPadding2),
			})),
			steps: this.steps.map(step => {
				const out: MmtrJobStep = {
					stepId: step.stepId,
					type: step.type,
					dueTimeOfDayMs: step.dueTimeOfDayMs,
				};
				if (step.type === "UNCOUPLE") {
					out.targetIndex = step.targetIndex;
				} else if (step.type === "COUPLE") {
					out.targetJobId = step.targetJobId;
				} else {
					out.targetId = this.parseTargetId(step.targetId ?? "");
				}
				if (step.note) {
					out.note = step.note;
				}
				return out;
			}),
		};		this.mmtrJobsService.upsert(payload).subscribe({
			next: response => {
				this.mmtrJobsService.refresh();
				this.mmtrJobsService.setFeedback(response.data?.ok ? `✓ 作业单 ${jobId} 已保存` : `✗ 保存 ${jobId} 失败`);
				this.closed.emit();
			},
			error: error => {
				console.error("mmtr job upsert failed", error);
				this.error = "保存请求失败：" + (error.status ?? "网络错误");
			},
		});
	}

	protected requestDelete = false;

	protected confirmDelete() {
		if (!this.requestDelete) {
			this.requestDelete = true;
			return;
		}
		this.mmtrJobsService.delete(this.jobId).subscribe({
			next: response => {
				this.mmtrJobsService.refresh();
				this.mmtrJobsService.setFeedback(response.data?.ok ? `已删除作业单 ${this.jobId}` : `删除 ${this.jobId} 失败`);
				this.closed.emit();
			},
			error: error => {
				console.error("mmtr job delete failed", error);
				this.requestDelete = false;
				this.error = "删除请求失败：" + (error.status ?? "网络错误");
			},
		});
	}

	protected cancel() {
		this.closed.emit();
	}
}
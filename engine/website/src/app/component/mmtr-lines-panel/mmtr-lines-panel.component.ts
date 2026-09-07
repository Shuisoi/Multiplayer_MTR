import {ChangeDetectionStrategy, Component, effect, inject} from "@angular/core";
import {MmtrLayersService} from "../../service/mmtr-layers.service";
import {MmtrLine, MmtrLinesService} from "../../service/mmtr-lines.service";

/**
 * Line console (线路): lists the engine's automatic lines, toggles their visibility on the map and
 * focuses one line at a time (focused lines render bold white). Layer state lives in
 * MmtrLayersService so the map reacts to the same signals.
 */
@Component({
	selector: "app-mmtr-lines-panel",
	changeDetection: ChangeDetectionStrategy.OnPush,
	templateUrl: "./mmtr-lines-panel.component.html",
	styleUrl: "./mmtr-lines-panel.component.scss",
})
export class MmtrLinesPanelComponent {
	private readonly linesService = inject(MmtrLinesService);
	private readonly layersService = inject(MmtrLayersService);

	protected readonly lines = this.linesService.lines;
	protected readonly visibleLines = this.layersService.visibleLines;
	protected readonly focusedLine = this.layersService.focusedLine;

	protected totalRails = () => this.lines().reduce((sum, line) => sum + line.rails.length, 0);

	constructor() {
		// First load: everything visible (and keep new lines visible as they appear).
		effect(() => {
			const available = this.lines();
			const current = new Set(this.visibleLines());
			const next = [...this.visibleLines()];
			available.forEach(line => {
				if (!current.has(line.id)) {
					next.push(line.id);
				}
			});
			if (next.length !== current.size) {
				this.layersService.visibleLines.set(next);
			}
		});
	}

	protected toggle(line: MmtrLine) {
		const next = [...this.visibleLines()];
		const index = next.indexOf(line.id);
		if (index >= 0) {
			next.splice(index, 1);
		} else {
			next.push(line.id);
		}
		this.layersService.visibleLines.set(next);
	}

	protected all(visible: boolean) {
		this.layersService.visibleLines.set(visible ? this.lines().map(line => line.id) : []);
	}

	protected focus(lineId: string) {
		this.layersService.focusedLine.set(this.focusedLine() === lineId ? "" : lineId);
	}
}

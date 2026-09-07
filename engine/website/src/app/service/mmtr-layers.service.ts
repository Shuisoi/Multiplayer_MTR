import {Injectable, signal} from "@angular/core";

/**
 * Map layer state of the management console (黑白极简): which layers are shown and which
 * automatic lines are visible / focused. Everything is a plain signal so map effects react.
 */
@Injectable({providedIn: "root"})
export class MmtrLayersService {
	/** Raw track topology edges (fat white, plain). */
	public readonly rails = signal(true);
	/** Colored-by-line rendering (per visible line a distinct monochrome gray tier). */
	public readonly linesLayer = signal(true);
	/** Fork (道岔) markers + console. */
	public readonly points = signal(true);
	/** Visible line ids (all by default). */
	public readonly visibleLines = signal<string[]>([]);
	/** Focused line id (empty = none); focused lines draw bold white. */
	public readonly focusedLine = signal("");
}

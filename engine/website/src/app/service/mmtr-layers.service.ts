import {Injectable, signal} from "@angular/core";

/**
 * Map layer state of the management console (黑白极简): which layers are shown and which
 * automatic lines are visible / focused. Everything is a plain signal so map effects react.
 */
@Injectable({providedIn: "root"})
export class MmtrLayersService {
	/**
	 * Which figure the console draws: the world map (real geometry) or the 区间图 (the block layer folded
	 * onto a 1x1 lattice). They are different drawings of the same simulation, so they are exclusive.
	 */
	public readonly view = signal<"world" | "schematic">("world");
	/** Raw track topology edges (fat white, plain). */
	public readonly rails = signal(true);
	/** Colored-by-line rendering (per visible line a distinct monochrome gray tier). */
	public readonly linesLayer = signal(true);
	/** Fork (道岔) markers + console. */
	public readonly points = signal(true);
	/** 信号显示: rails coloured by the aspect of the signal protecting them (red / yellow / double yellow). */
	public readonly signals = signal(true);
	/** Visible line ids (all by default). */
	public readonly visibleLines = signal<string[]>([]);
	/** Focused line id (empty = none); focused lines draw bold white. */
	public readonly focusedLine = signal("");
}

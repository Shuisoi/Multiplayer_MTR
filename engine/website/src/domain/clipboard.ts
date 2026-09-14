/**
 * 把文本放进剪贴板，并**如实回答有没有成功**。
 *
 * <h3>为什么不能写成 `void navigator.clipboard?.writeText(text)`</h3>
 * <p>那样写有三个静默失败口，实测都踩得上：</p>
 * <ol>
 *   <li><b>不 await</b>：`writeText` 返回 Promise，被拒时没人接，控制台之外无声无息 ——
 *       用户点了"复制坐标"，什么都没发生，也没有任何提示；</li>
 *   <li><b>`?.`</b>：接口不存在时整句变成 no-op。剪贴板接口在非安全上下文
 *       （比如用局域网 IP 打开控制台）里就是 undefined；</li>
 *   <li><b>没有兜底</b>：浏览器会以 `NotAllowedError`（权限被拒）或"文档没有焦点"为由拒绝，
 *       这两种情况下 `document.execCommand('copy')` 仍然可用，不试就白丢一次机会。
 *       实测：在无头浏览器里直接调 `writeText` 就是 `NotAllowedError / Write permission denied`。</li>
 * </ol>
 *
 * <p>所以这里按"先现代、后老办法"依次尝试，把**实际哪条路成功**记下来，
 * 并把最后一次尝试挂到 `window.__mmtrLastCopy`：界面要能对用户说实话
 * （"已复制 X"还是"没能复制，这是内容，请手动复制"），验收脚本也要能核对
 * "复制出来的到底是不是那个坐标" —— 否则这条功能只能靠眼睛声称它能用。</p>
 */

/** 一次复制尝试的结果。 */
export interface CopyResult {
	/** 文本是否真的进了剪贴板。 */
	readonly ok: boolean;
	/** 实际进剪贴板的文本（`ok=false` 时是"本该复制"的文本，界面拿它给用户手动复制）。 */
	readonly text: string;
	/** 哪条路成功：`clipboard-api` / `execCommand`；失败时是最后一条尝试的路径。 */
	readonly via: string;
	/** 失败原因（成功时为空串）。 */
	readonly error: string;
}

/**
 * 复制文本。
 *
 * <h3>试的顺序：**同步的老办法先行**（2026-09-13 实测纠正）</h3>
 * <p>原来先试 `navigator.clipboard.writeText`、失败再退回 `execCommand`。实测这个顺序有个致命处：
 * `writeText` 是异步的，它被拒（`NotAllowedError: Write permission denied`）时，**用户手势那一瞬已经过去了**，
 * 再回头做 `execCommand` 就晚了 —— 于是两条都失败，用户看到的就是"点了复制，剪贴板里什么都没有"
 * （页面还会说"已复制"，因为原来只看了第一条路的结果）。</p>
 *
 * <p>现在的顺序：</p>
 * <ol>
 *   <li><b>{@code execCommand('copy')} 同步先试</b>：它必须在手势里同步跑，不需要权限、也不需要安全上下文，
 *       兼容性反而是最好的（Chromium/Firefox 都还支持）；</li>
 *   <li>不行再试异步的 {@code navigator.clipboard.writeText}（现代接口，安全上下文 + 权限 + 有手势时可用）。</li>
 * </ol>
 * <p>两条都失败时，返回里带上**各自的失败原因与运行环境**（{@link describeEnvironment}），
 * 界面把它显示出来 —— 否则"复制不了"只能靠猜。</p>
 *
 * @param text 要复制的文本（不会做任何加工：复制出来的必须与调用方给的完全一致）
 */
export async function copyText(text: string): Promise<CopyResult> {
	const failures: string[] = [];

	// ① 同步：临时 textarea + execCommand（在手势里同步执行，最稳）
	try {
		if (legacyCopy(text)) {
			return record({ok: true, text, via: "execCommand", error: ""});
		}
		failures.push("execCommand('copy') 返回 false");
	} catch (error) {
		failures.push(`execCommand: ${describeError(error)}`);
	}

	// ② 现代剪贴板接口
	if (typeof navigator !== "undefined" && navigator.clipboard !== undefined && typeof navigator.clipboard.writeText === "function") {
		try {
			await navigator.clipboard.writeText(text);
			return record({ok: true, text, via: "clipboard-api", error: ""});
		} catch (error) {
			failures.push(`clipboard.writeText: ${describeError(error)}`);
		}
	} else {
		failures.push("navigator.clipboard 不存在（非安全上下文？http + 局域网 IP 打开时就是这种情况）");
	}

	return record({ok: false, text, via: "none", error: failures.join("；")});
}

/**
 * 复制失败时值得一并显示的环境事实。
 *
 * <p>这三项能一眼分开三种"复制不了"：非安全上下文（接口都不存在）、窗口没有焦点、
 * 站点权限被拒（接口在、也有焦点，但写入被拒）。</p>
 */
export function describeEnvironment(): string {
	if (typeof window === "undefined" || typeof document === "undefined") {
		return "（不在浏览器环境里）";
	}
	const hasApi = typeof navigator !== "undefined" && navigator.clipboard !== undefined;
	return [
		`secureContext=${window.isSecureContext}`,
		`hasFocus=${document.hasFocus()}`,
		`clipboardApi=${hasApi}`,
		`visibility=${document.visibilityState}`,
	].join(" · ");
}

/**
 * `document.execCommand('copy')` 那条路。
 *
 * <p>用 `position: fixed` 而不是 `absolute`：`absolute` 会让 textarea 参与文档流/滚动尺寸，
 * 在页面处于某处滚动位置时可能把视图拽动一下。`readOnly` 与 `aria-hidden` 避免触发手机键盘与读屏。</p>
 */
function legacyCopy(text: string): boolean {
	if (typeof document === "undefined" || typeof document.execCommand !== "function") {
		return false;
	}
	const area = document.createElement("textarea");
	area.value = text;
	area.setAttribute("readonly", "");
	area.setAttribute("aria-hidden", "true");
	area.style.position = "fixed";
	area.style.top = "0";
	area.style.left = "0";
	area.style.width = "1px";
	area.style.height = "1px";
	area.style.padding = "0";
	area.style.border = "none";
	area.style.outline = "none";
	area.style.boxShadow = "none";
	area.style.background = "transparent";
	area.style.opacity = "0";
	document.body.appendChild(area);
	try {
		area.select();
		area.setSelectionRange(0, text.length);
		return document.execCommand("copy");
	} finally {
		document.body.removeChild(area);
	}
}

function describeError(error: unknown): string {
	if (error instanceof Error) {
		return error.name === "" ? error.message : `${error.name}: ${error.message}`;
	}
	return String(error);
}

/** 记下这次尝试：界面用它说话，验收脚本用它核对"复制出来的到底是不是那个坐标"。 */
function record(result: CopyResult): CopyResult {
	if (typeof window !== "undefined") {
		(window as unknown as {__mmtrLastCopy?: CopyResult}).__mmtrLastCopy = result;
	}
	return result;
}

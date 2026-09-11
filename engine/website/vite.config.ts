import {defineConfig} from "vite";
import vue from "@vitejs/plugin-vue";
import {fileURLToPath, URL} from "node:url";

/**
 * web 控制台构建配置 (Vite + Vue 3 + TS + Naive UI)。
 *
 * 两个硬约定，改动前先看这里：
 *  1. `base: "/"` —— 引擎的静态服务把 index.html 挂在 `/`，资源必须从根路径解析
 *     （用 `--base-href a` / `base: "a"` 会让 /a/*.js 落到 index 回退，页面永远白屏）。
 *  2. `build.outDir = "dist/website/browser"` —— 引擎的 Gradle 任务 `WebserverSetup`
 *     固定从 `website/dist/website/browser/` 递归读文件、生成 WebserverResources.java 嵌进 jar。
 *     输出目录改了，jar 里就没有前端。
 */
export default defineConfig({
	base: "/",
	plugins: [vue()],
	resolve: {
		alias: {
			"@": fileURLToPath(new URL("./src", import.meta.url)),
		},
	},
	build: {
		outDir: "dist/website/browser",
		emptyOutDir: true,
		// 中文字体子集本身就近 1 MB，别让 Vite 再 inline/base64 一遍。
		assetsInlineLimit: 4096,
		chunkSizeWarningLimit: 1200,
	},
	server: {
		port: 5173,
		// 本地开发时把引擎接口代理过来，前端代码里永远用相对路径 /mtr/api/...
		proxy: {
			"/mtr/api": "http://127.0.0.1:8888",
		},
	},
});

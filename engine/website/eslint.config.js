import js from "@eslint/js";
import tseslint from "typescript-eslint";
import pluginVue from "eslint-plugin-vue";

/** 前端 lint：TS + Vue SFC（模板里的可访问性规则先按控制台的实际需求放开两条）。 */
export default tseslint.config(
	{ignores: ["dist/**", "node_modules/**"]},
	js.configs.recommended,
	...tseslint.configs.recommended,
	...pluginVue.configs["flat/recommended"],
	{
		files: ["**/*.vue"],
		languageOptions: {
			parserOptions: {
				parser: tseslint.parser,
				extraFileExtensions: [".vue"],
			},
		},
		rules: {
			"vue/multi-word-component-names": "off",
			"vue/max-attributes-per-line": "off",
			"vue/singleline-html-element-content-newline": "off",
		},
	},
	{
		rules: {
			"@typescript-eslint/no-unused-vars": ["error", {argsIgnorePattern: "^_"}],
			"no-console": ["warn", {allow: ["warn", "error"]}],
		},
	},
);

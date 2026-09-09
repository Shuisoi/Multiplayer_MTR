// 统一的路径根解析：让打包器配置可以入库而不带机器相关的绝对路径。
//
// 配置文件里把工作区根写成占位符 ${MC_ROOT}，例如：
//     "sourceObj": "${MC_ROOT}/assets/models/blender/HST_H/hst_h.obj"
// 运行期由本模块替换成真实路径。解析优先级：
//     1. 环境变量 MC_ROOT（由 env/workspace.env.ps1|bat 导出）
//     2. 从本文件位置推断：mmtr/tools/obj-mtr-packager -> ../../.. = MC
const path = require('path');

const MC_ROOT = process.env.MC_ROOT || path.resolve(__dirname, '..', '..', '..');

function resolveRoot(value) {
	if (typeof value !== 'string') return value;
	return value.replace(/\$\{MC_ROOT\}/g, MC_ROOT);
}

module.exports = { MC_ROOT, resolveRoot };

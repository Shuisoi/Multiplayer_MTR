#!/usr/bin/env python3
"""把 C# 端 (ShuisoiSimUniverse) 的字体搬进 web 控制台，并子集化为 woff2。

来源字体（`APP/ShuisoiSimUniverse/Fonts/`，参数可用 --source 覆盖）：

    din1451alt.ttf                Alte DIN 1451 Mittelschrift  西文/数字（231 字形，直接全量转 woff2）
    HarmonyOS_Sans_SC_Regular.ttf HarmonyOS Sans SC Regular    正文（Regular，2.9 万字形 → 子集）
    HarmonyOS_Sans_SC_Bold.ttf    HarmonyOS Sans SC Bold       标题/强调（同上）
    DreamHanSerifCN-W24.ttf       Dream Han Serif CN W24       只给品牌名用的衬线（15.8 MB → 只留用到的字）

为什么必须子集化：中文字体全量 8 MB / 16 MB，而页面要嵌进引擎 jar 里随服务端下发；
子集到「GB2312 常用汉字 + ASCII + 常用标点 + 全角符号」后每份 ≈ 0.9 MB，DIN 只要 5 KB。

用法：
    python tools/fonts/build-fonts.py                       # 用默认路径
    python tools/fonts/build-fonts.py --source <Fonts 目录>
    python tools/fonts/build-fonts.py --brand-text "水手的控制器模拟宇宙"

依赖：fontTools >= 4.40、brotli（`python -m pip install fonttools brotli`）。
输出：engine/website/src/assets/fonts/*.woff2（提交进仓库，构建时不需要 Python）。
"""

import argparse
import os
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

# Windows 控制台默认不是 UTF-8，中文提示会变成乱码。
if hasattr(sys.stdout, "reconfigure"):
	sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# 需要的字形集合：GB2312 的两级汉字 + ASCII + CJK 标点 + 全角形式 + 少量符号。
def target_characters(brand_text: str) -> str:
	characters = []
	# GB2312 level 1 + 2：把所有合法双字节码位解出来，就是最常用的一批汉字。
	for high in range(0xB0, 0xF8):
		for low in range(0xA1, 0xFF):
			try:
				characters.append(bytes([high, low]).decode("gb2312"))
			except UnicodeDecodeError:
				pass
	characters.extend(chr(code) for code in list(range(0x20, 0x7F)) + list(range(0x3000, 0x3040)) + list(range(0xFF00, 0xFF61)))
	characters.extend(["\u2018", "\u2019", "\u201c", "\u201d", "\u2026", "\u2014", "\u2192", "\u2190", "\u00b0", "\u00d7", "\u2265", "\u2264", "\u00a0", "\u2500", "\u2502", "\u2514", "\u251c", "\u25cf", "\u25cb", "\u25b2", "\u25bc"])
	characters.extend(brand_text)
	return "".join(dict.fromkeys(characters))


def build(source_dir: str, output_dir: str, brand_text: str) -> int:
	os.makedirs(output_dir, exist_ok=True)
	text = target_characters(brand_text)
	print(f"目标字形 {len(text)} 个")

	jobs = [
		# 输出名, 源文件, 是否子集（DIN 只 231 字形，全量即可，省得丢符号）
		("din1451alt-regular", "din1451alt.ttf", False),
		("harmonyos-sans-sc-regular", "HarmonyOS_Sans_SC_Regular.ttf", True),
		("harmonyos-sans-sc-bold", "HarmonyOS_Sans_SC_Bold.ttf", True),
		("dream-han-serif-cn-w24", "DreamHanSerifCN-W24.ttf", True),
	]

	failed = 0
	for name, file_name, do_subset in jobs:
		source_path = os.path.join(source_dir, file_name)
		output_path = os.path.join(output_dir, name + ".woff2")
		if not os.path.exists(source_path):
			print(f"  !! 缺少源字体 {source_path}")
			failed += 1
			continue
		arguments = [source_path, "--flavor=woff2", "--no-hinting", f"--output-file={output_path}"]
		if do_subset:
			if name.startswith("dream-han"):
				# 品牌衬线只留品牌文案那几个字。它的 GPOS/GSUB 在子集化时会产出 pyftsubset 自己都读不回来
				# 的 VarStore（fontTools 4.62 的真实缺陷），而品牌名只是几个字、用不到任何 layout 特性，
				# 所以直接把 OpenType layout 表整体丢掉。
				arguments.insert(1, f"--text={brand_text}")
				arguments.append("--drop-tables+=BASE,GDEF,GPOS,GSUB")
			else:
				arguments.insert(1, f"--text={text}")
				arguments.append("--layout-features=*")
		else:
			# 不子集时必须显式保留全部字形：只给 --flavor 的话 pyftsubset 会按默认把字体削成空壳。
			arguments.insert(1, "--glyphs=*")
		subset.main(arguments)
		glyphs = TTFont(output_path)["maxp"].numGlyphs
		print(f"  {name:26} {glyphs:6} 字形  {os.path.getsize(output_path) / 1024:9.1f} KB")
	return failed


def main() -> int:
	parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
	parser.add_argument("--source", default=r"C:\Users\30354\Desktop\APP\ShuisoiSimUniverse\Fonts", help="C# 端 Fonts 目录")
	parser.add_argument("--output", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "engine", "website", "src", "assets", "fonts"), help="输出目录")
	parser.add_argument("--brand-text", default="水手的控制器模拟宇宙", help="品牌字体需要保留的文案")
	options = parser.parse_args()
	output_dir = os.path.abspath(options.output)
	print(f"源: {options.source}\n输出: {output_dir}")
	failed = build(options.source, output_dir, options.brand_text)
	if failed:
		print(f"{failed} 个字体未生成")
	return 1 if failed else 0


if __name__ == "__main__":
	sys.exit(main())

#!/usr/bin/env python3
"""生成 Android 侧第三方许可数据，输出到 app/src/main/assets/licenses/。

数据来源是 AGP 自己产出的 "SDK dependencies" 报告——它就是实际打进 APK 的
Maven 依赖列表（含传递依赖），由构建自动更新，不需要手工维护：

    app/build/outputs/sdk-dependencies/<variant>/sdkDependencies.txt

输出为结构化 JSON（供应用内许可页单页展示），结构见 README「第三方许可清单」：
    { "licenses": [{name, text, components: [{name, version}]}] }

用法（在仓库根目录）：
    python tools/generate_android_licenses.py

依赖变化后重跑本脚本即可；Apache-2.0 全文取自 tools/apache-2.0.txt（离线）。
"""

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SDK_DEPS = REPO / "app/build/outputs/sdk-dependencies/release/sdkDependencies.txt"
APACHE_TEXT = REPO / "tools/apache-2.0.txt"
OUTPUT = REPO / "app/src/main/assets/licenses/android-dependencies.json"


def main() -> int:
    if not SDK_DEPS.is_file():
        print(f"找不到依赖报告：{SDK_DEPS}", file=sys.stderr)
        print("请先构建一次（release 或 debug）以便 AGP 生成该文件。", file=sys.stderr)
        return 1

    text = SDK_DEPS.read_text(encoding="utf-8", errors="replace")
    coords = sorted(set(re.findall(
        r'maven_library\s*\{\s*groupId:\s*"([^"]+)"\s*'
        r'artifactId:\s*"([^"]+)"\s*version:\s*"([^"]+)"',
        text,
    )))
    if not coords:
        print(f"没能从 {SDK_DEPS} 解析出任何依赖坐标", file=sys.stderr)
        return 1

    data = {
        "licenses": [
            {
                "name": "Apache License 2.0",
                "text": APACHE_TEXT.read_text(encoding="utf-8"),
                # 名称与版本分开存放，便于界面把版本显示成次要信息
                "components": [
                    {"name": f"{group}:{artifact}", "version": version}
                    for group, artifact, version in coords
                ],
            }
        ],
    }

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"已写入 {OUTPUT.relative_to(REPO)}（{len(coords)} 个组件）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""生成 Android 侧第三方许可清单，输出到 app/src/main/assets/licenses/。

数据来源是 AGP 自己产出的 "SDK dependencies" 报告——它就是实际打进 APK 的
Maven 依赖列表（含传递依赖），由构建自动更新，不需要手工维护：

    app/build/outputs/sdk-dependencies/<variant>/sdkDependencies.txt

用法（在仓库根目录）：
    python tools/generate_android_licenses.py

依赖变化后重跑本脚本即可；许可全文取自 tools/apache-2.0.txt（离线，无需联网）。
"""

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SDK_DEPS = REPO / "app/build/outputs/sdk-dependencies/release/sdkDependencies.txt"
APACHE_TEXT = REPO / "tools/apache-2.0.txt"
OUTPUT = REPO / "app/src/main/assets/licenses/android-dependencies.txt"

HEADER = """Android third-party components
==============================

This app bundles the following components, all distributed under the
Apache License, Version 2.0. 本应用包含以下第三方组件，均以 Apache License 2.0 分发。

Generated from the Android Gradle plugin's SDK dependency report
(app/build/outputs/sdk-dependencies/release/sdkDependencies.txt), which lists
the Maven dependencies actually packaged into the APK, transitive ones included.
Regenerate with: python tools/generate_android_licenses.py

--------------------------------------------------------------------------------
Components ({count})
--------------------------------------------------------------------------------

"""


def main() -> int:
    if not SDK_DEPS.is_file():
        print(f"找不到依赖报告：{SDK_DEPS}", file=sys.stderr)
        print("请先构建一次 release（或 debug）以便 AGP 生成该文件。", file=sys.stderr)
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

    parts = [HEADER.format(count=len(coords))]
    parts += [f"{g}:{a}:{v}\n" for g, a, v in coords]
    parts.append("\n" + "=" * 80 + "\n\n")
    parts.append(APACHE_TEXT.read_text(encoding="utf-8"))

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text("".join(parts), encoding="utf-8")
    print(f"已写入 {OUTPUT.relative_to(REPO)} （{len(coords)} 个组件）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

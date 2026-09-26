#!/usr/bin/env python3
"""生成 Rust 侧第三方许可数据，输出到 app/src/main/assets/licenses/。

流程：调用 cargo-about 按 android-bridge/about.hbs 输出中间文本 → 解析 →
写成与 Android 侧同构的结构化 JSON（供应用内许可页分级展示）。

中间文本的格式由 about.hbs 定义，用固定标记分段：
    ===LICENSE=== 许可名 / ===USEDBY=== 逐行 crate / ===TEXT=== 正文 / ===END=== 块结束

用法（在仓库根目录）：
    python tools/generate_rust_licenses.py

依赖变化后（改 android-bridge/Cargo.toml、更新上游 rev）重跑即可。
首次需先安装 cargo-about：
    cargo install --locked --features cli cargo-about
"""

import json
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BRIDGE = REPO / "android-bridge"
TEMPLATE = "about.hbs"
OUTPUT = REPO / "app/src/main/assets/licenses/rust-dependencies.json"

MARK_LICENSE = "===LICENSE==="
MARK_USEDBY = "===USEDBY==="
MARK_TEXT = "===TEXT==="
MARK_END = "===END==="


def run_cargo_about() -> str:
    """调用 cargo-about，返回它写到 stdout 的中间文本。"""
    if shutil.which("cargo") is None:
        raise SystemExit("找不到 cargo，请先安装 Rust 工具链")

    result = subprocess.run(
        ["cargo", "about", "generate", TEMPLATE],
        cwd=BRIDGE,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if result.returncode != 0:
        print("cargo-about 执行失败，其输出如下：\n", file=sys.stderr)
        print(result.stderr or result.stdout, file=sys.stderr)
        print(
            "若提示没有 about 子命令，请先执行：\n"
            "    cargo install --locked --features cli cargo-about",
            file=sys.stderr,
        )
        raise SystemExit(result.returncode)
    return result.stdout


def parse(raw: str):
    """把中间文本解析成 [(许可名, [crate 行], 正文)]。"""
    sections = []
    for block in raw.split(MARK_END):
        if MARK_LICENSE not in block:
            continue
        head, _, rest = block.partition(MARK_LICENSE)
        name, _, rest = rest.partition(MARK_USEDBY)
        used_by, _, text = rest.partition(MARK_TEXT)

        crates = [line.strip() for line in used_by.splitlines() if line.strip()]
        sections.append((name.strip(), crates, text.strip("\n")))
    if not sections:
        raise SystemExit("没能从 cargo-about 的输出里解析出任何许可段落，模板可能被改动了")
    return sections


def make_license_id(name: str, taken: set) -> str:
    """同一个许可名可能出现多次（正文与版权行不同），逐个编号以保证唯一。"""
    base = name.replace(" ", "-")
    candidate, n = base, 1
    while candidate in taken:
        n += 1
        candidate = f"{base}-{n}"
    taken.add(candidate)
    return candidate


def main() -> int:
    raw = run_cargo_about()
    sections = parse(raw)

    licenses = []
    entries: dict[tuple[str, str], list[str]] = {}
    taken: set = set()

    for name, crates, text in sections:
        license_id = make_license_id(name, taken)
        licenses.append({"id": license_id, "name": name, "text": text})
        for line in crates:
            # 形如 "crate-name 1.2.3"；版本可能带 git 前缀，按首个空格切分
            crate, _, version = line.partition(" ")
            entries.setdefault((crate, version.strip()), []).append(license_id)

    data = {
        "title": "Rust dependencies",
        "licenses": licenses,
        "entries": [
            {"name": crate, "version": version, "licenseIds": ids}
            for (crate, version), ids in sorted(entries.items())
        ],
    }

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(
        f"已写入 {OUTPUT.relative_to(REPO)}"
        f"（{len(data['entries'])} 个 crate、{len(licenses)} 个许可段落）"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

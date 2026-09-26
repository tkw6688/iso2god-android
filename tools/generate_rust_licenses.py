#!/usr/bin/env python3
"""生成 Rust 侧第三方许可数据，输出到 app/src/main/assets/licenses/。

流程：调用 cargo-about 按 android-bridge/about.hbs 输出中间文本 → 解析 →
写成与 Android 侧同构的结构化 JSON（供应用内许可页单页展示）。

中间文本的格式由 about.hbs 定义，用固定标记分段：
    ===LICENSE=== 许可名 / ===USEDBY=== 逐行 crate / ===TEXT=== 正文 / ===END=== 块结束

cargo-about 按 crate 输出许可原文：同为 MIT 的各份文本只差标题行、版权行与
换行宽度，全文精确比较会得到几十份"不同"的 MIT。因此剥掉起头的声明头，
把正文折叠空白后作归并键——正文只存一份，版权行集中放进 copyrights 数组。

用法（在仓库根目录）：
    python tools/generate_rust_licenses.py

依赖变化后（改 android-bridge/Cargo.toml、更新上游 rev）重跑即可。
首次需先安装 cargo-about：
    cargo install --locked --features cli cargo-about
"""

import json
import re
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

# 版权归属行："Copyright (c) …" 或 "Copyright © …"。
# Unicode 许可里的全大写 "COPYRIGHT AND PERMISSION NOTICE" 是小节标题，
# 不匹配本模式，其 "Copyright © 1991-2023" 行位于该标题之后也不会被误收。
ATTRIB_RE = re.compile(r"(?i)^copyright\s*(\(c\)|©)")


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


def scan_header(name: str, text: str) -> tuple[list[str], str, bool]:
    """扫描文本起头的声明头，返回（版权行, 去头部正文, 是否剥掉了标题行）。

    许可文件的头部形态不统一：有的直接以 "Copyright (c) …" 起头，有的先有
    一行 "The MIT License (MIT)" 标题。逐行跳过空行与标题行（含许可名的短
    行）、收走版权行，遇到正文即停。返回的正文只作归并比较用，展示正文见
    build_licenses —— Unicode 这类许可的标题行属于原文，不能剥。
    """
    copyrights: list[str] = []
    titled = False
    lines = text.splitlines()
    i, n = 0, len(lines)
    while i < n:
        line = lines[i].strip()
        if not line:
            i += 1
            continue
        if ATTRIB_RE.match(line):
            # "Copyright (c) <year> <copyright holders>" 是模板占位行，不是真实归属
            if "<year>" not in line:
                copyrights.append(line)
            i += 1
            continue
        if len(line) < 60 and name.lower() in line.lower():
            titled = True
            i += 1
            continue
        break
    return copyrights, "\n".join(lines[i:]).strip("\n"), titled


def build_licenses(sections) -> list[dict]:
    """把解析出的段落按（许可名，去头部折叠空白后的正文）归并成许可节数组。"""
    groups: dict[tuple[str, str], dict] = {}
    for name, crates, text in sections:
        copyrights, body, titled = scan_header(name, text)
        group = groups.setdefault(
            (name, " ".join(body.split())),
            {"name": name, "text": None, "titled": False, "copyrights": [], "crates": []},
        )
        for line in copyrights:
            if line not in group["copyrights"]:
                group["copyrights"].append(line)
        group["crates"].extend(crates)
        # 展示正文：优先取不带标题行的第一份（干净正文）；整组都带标题行时，
        # 保留第一份的原文（标题、版权行都在，许可原文不做删改）
        if group["text"] is None:
            group["text"] = body if not titled else text.strip("\n")
            group["titled"] = titled
        elif group["titled"] and not titled:
            group["text"] = body
            group["titled"] = titled

    licenses = []
    for group in groups.values():
        item = {
            "name": group["name"],
            "text": group["text"],
            "components": [
                # 形如 "crate-name 1.2.3"；版本可能带 git 后缀，按首个空格切分
                {"name": line.partition(" ")[0], "version": line.partition(" ")[2].strip()}
                for line in sorted(set(group["crates"]))
            ],
        }
        if group["copyrights"]:
            item["copyrights"] = group["copyrights"]
        licenses.append(item)
    return licenses


def write_output(licenses: list[dict]) -> None:
    data = {"licenses": licenses}
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    total = sum(len(item["components"]) for item in licenses)
    print(
        f"已写入 {OUTPUT.relative_to(REPO)}"
        f"（{total} 个组件条目、{len(licenses)} 个许可节，双许可 crate 重复计入）"
    )


def main() -> int:
    write_output(build_licenses(parse(run_cargo_about())))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

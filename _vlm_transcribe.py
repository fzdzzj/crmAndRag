"""用 Qwen-VL 把课件页面 PNG 自动转写为结构化 Markdown（A1：视觉转写自动化）。

背景：中文课件 PDF 是图片型（文本层几乎为空，见 rag-kb/sources.md §一），此前只能人工逐页视觉精读。
本脚本把 render.py 产出的 PNG 批量过 Qwen-VL，产出可复现的转写原料，供 _drafts 精读参考/纠错。

放置：正常应放在 <rag 工作区>/_extract/vlm_transcribe.py，此时 --root 默认即工作区根。
也可从任意位置运行，用 --root 指向 rag 工作区、--out 指向可写目录。

用法：
    set DASHSCOPE_API_KEY=sk-...                       # 只从环境变量读，绝不落盘
    python vlm_transcribe.py --dry-run                  # 只列待处理，不调用
    python vlm_transcribe.py --deck 索引优化技术 --limit 2   # 试点 2 页
    python vlm_transcribe.py                            # 全量（幂等，跳过已转写）
"""
from __future__ import annotations

import argparse
import base64
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from openai import OpenAI

BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
DEFAULT_MODEL = "qwen-vl-max"

# 转写提示词：遵循 rag-kb/AGENTS.md §6 与 sources.md §四——不猜测、不补全、不清就标注
PROMPT = """你是精确的课件转写引擎。把这张幻灯片截图的全部内容转写为结构化 Markdown：
1. 逐字提取所有可见文字：标题、副标题、正文、要点、图表标注、页眉页脚、页码、版权/水印行。
2. 代码截图：用三个反引号包裹完整转写，尽量保留缩进与符号；无法确认的字符标注（截图不清）。
3. 架构图/流程图：用文字加箭头描述节点与连接关系；有分层或分组要说明层级。
4. 表格：转成 Markdown 表格，保留行列与表头。
5. 图中极小、模糊或被截断而无法确认的文字，一律标注“（截图不清）”，禁止猜测补全成看似合理的值。
6. 只转写图中确实存在的内容，不要添加解释、背景知识或图里没有的信息，不要编造数字/模型名/文献。
7. 直接输出转写结果，不要寒暄。"""


def discover_images(pages: Path, pptx_media: Path, deck_filter: str | None):
    """产出 (deck, page_label, png_path)，按课件名 + 页码排序。"""
    items: list[tuple[str, str, Path]] = []
    if pages.exists():
        for deck_dir in sorted(pages.iterdir()):
            if not deck_dir.is_dir():
                continue
            if deck_filter and deck_filter not in deck_dir.name:
                continue
            for png in sorted(deck_dir.glob("*.png")):
                items.append((deck_dir.name, png.stem, png))
    if pptx_media.exists() and (not deck_filter or "pptx" in deck_filter.lower()
                                or "论文分享" in deck_filter):
        for png in sorted(pptx_media.glob("*.png")):
            items.append(("RAG论文分享", png.stem, png))
    return items


def encode_image(png: Path) -> str:
    return base64.b64encode(png.read_bytes()).decode("ascii")


def call_vlm(client: OpenAI, model: str, png: Path, max_retries: int = 4) -> str:
    """调用 Qwen-VL，带指数退避重试（429/5xx/超时）。"""
    data_url = f"data:image/png;base64,{encode_image(png)}"
    last_err: Exception | None = None
    for attempt in range(max_retries):
        try:
            resp = client.chat.completions.create(
                model=model,
                messages=[{
                    "role": "user",
                    "content": [
                        {"type": "text", "text": PROMPT},
                        {"type": "image_url", "image_url": {"url": data_url}},
                    ],
                }],
            )
            return (resp.choices[0].message.content or "").strip()
        except Exception as e:  # noqa: BLE001 - 网络/限流/服务端错误统一退避重试
            last_err = e
            wait = 2 ** attempt
            print(f"    ! 第{attempt + 1}次失败（{type(e).__name__}: {e}），{wait}s 后重试", flush=True)
            time.sleep(wait)
    raise RuntimeError(f"VLM 调用连续失败：{last_err}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Qwen-VL 课件页面自动转写")
    ap.add_argument("--root", default=None, help="rag 工作区根（默认脚本上上级目录）")
    ap.add_argument("--out", default=None, help="转写产物目录（默认 <root>/_extract/vlm）")
    ap.add_argument("--deck", help="只处理名字含此子串的课件（如 索引优化技术）")
    ap.add_argument("--limit", type=int, help="本次最多转写多少页（试点用）")
    ap.add_argument("--model", default=DEFAULT_MODEL, help=f"视觉模型（默认 {DEFAULT_MODEL}）")
    ap.add_argument("--force", action="store_true", help="已存在也重新转写")
    ap.add_argument("--dry-run", action="store_true", help="只列出待处理，不调用 API")
    ap.add_argument("--sleep", type=float, default=0.5, help="每页间隔秒数（限流）")
    args = ap.parse_args()

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent
    pages = root / "_extract" / "pages"
    pptx_media = root / "_extract" / "pptx_media"
    out_dir = Path(args.out).resolve() if args.out else root / "_extract" / "vlm"

    api_key = os.environ.get("DASHSCOPE_API_KEY")
    if not api_key and not args.dry_run:
        print("错误：未设置环境变量 DASHSCOPE_API_KEY（本脚本只从环境读，不落盘）", file=sys.stderr)
        return 2

    images = discover_images(pages, pptx_media, args.deck)
    if not images:
        print(f"没有匹配的 PNG（root={root}；检查 _extract/pages 是否已由 render.py 生成，或 --deck 是否过窄）")
        return 1

    def out_path(deck: str, page_label: str) -> Path:
        return out_dir / deck / f"{page_label}.md"

    pending = [(d, p, path) for (d, p, path) in images if args.force or not out_path(d, p).exists()]
    print(f"root={root}\nout={out_dir}\n发现 {len(images)} 张，待转写 {len(pending)} 张"
          f"（模型={args.model}{'，dry-run' if args.dry_run else ''}）")
    if args.limit:
        pending = pending[: args.limit]
        print(f"--limit 生效：本次只处理前 {len(pending)} 张")
    for d, p, _ in pending:
        print(f"  - {d}/{p}")
    if args.dry_run:
        return 0

    client = OpenAI(api_key=api_key, base_url=BASE_URL)
    out_dir.mkdir(parents=True, exist_ok=True)
    done = failed = 0
    for i, (deck, page_label, png) in enumerate(pending, 1):
        target = out_path(deck, page_label)
        target.parent.mkdir(parents=True, exist_ok=True)
        try:
            text = call_vlm(client, args.model, png)
        except Exception as e:  # noqa: BLE001
            print(f"[{i}/{len(pending)}] {deck}/{page_label} FAILED: {e}", flush=True)
            failed += 1
            continue
        try:
            rel = png.relative_to(root)
        except ValueError:
            rel = png.name
        header = (f"<!-- VLM 自动转写 | 源: {rel} | 模型: {args.model} "
                  f"| 时间: {datetime.now(timezone.utc).isoformat(timespec='seconds')} -->\n"
                  f"<!-- 机器转写，仅供 _drafts 精读参考；不确定处已标（截图不清），引用前请对照原图核对 -->\n\n")
        target.write_text(header + text + "\n", encoding="utf-8")
        done += 1
        print(f"[{i}/{len(pending)}] {deck}/{page_label} -> {len(text)} 字符 -> {target}", flush=True)
        time.sleep(args.sleep)

    print(f"\n完成：成功 {done} / 失败 {failed} / 目标 {len(pending)}；产物在 {out_dir}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())

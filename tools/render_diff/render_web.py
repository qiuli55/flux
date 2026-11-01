#!/usr/bin/env python3
"""把 doc/design-cases 里的用例渲染成网页截图，作为三端对比的基准图。

用法::

    python3 tools/render_diff/render_web.py --out build/render-diff/web
    python3 tools/render_diff/render_web.py doc/design-cases/01-basic-shapes.json --out /tmp/web

依赖 playwright + chromium（``python3 -m playwright install chromium``）。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "backend"))

from flux.core.design_render import render_design_html  # noqa: E402


def iter_cases(target: Path) -> list[Path]:
    if target.is_dir():
        return sorted(target.glob("*.json"))
    return [target]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="渲染设计用例的网页截图")
    parser.add_argument(
        "case",
        nargs="?",
        type=Path,
        default=REPO_ROOT / "doc" / "design-cases",
        help="用例文件或目录",
    )
    parser.add_argument("--out", type=Path, default=REPO_ROOT / "build" / "render-diff" / "web")
    parser.add_argument("--keep-html", action="store_true", help="同时保存生成的 HTML")
    args = parser.parse_args(argv)

    from playwright.sync_api import sync_playwright

    cases = iter_cases(args.case)
    if not cases:
        print("没有找到用例", file=sys.stderr)
        return 2
    args.out.mkdir(parents=True, exist_ok=True)

    failures = 0
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            args=[
                "--force-device-scale-factor=1",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--disable-software-rasterizer",
            ]
        )
        for case_path in cases:
            design = json.loads(case_path.read_text(encoding="utf-8"))
            meta = design.get("meta") or {}
            case_id = str(meta.get("case") or case_path.stem)
            width, height = (meta.get("viewport") or [390, 844])[:2]
            scale = float(meta.get("scale") or 2)

            html_text = render_design_html(design, page_title=case_id)
            if args.keep_html:
                (args.out / f"{case_id}.html").write_text(html_text, encoding="utf-8")

            page = browser.new_page(
                viewport={"width": int(width), "height": int(height)},
                device_scale_factor=scale,
            )
            page.set_content(html_text, wait_until="load")
            # 预览页会把画板等比缩放到视口内；对比需要 1:1 像素，这里关掉缩放并对齐到 (0,0)，
            # 否则元素截图会因为小数坐标多出 1px 边。
            page.add_style_tag(
                content=(
                    "section.device{transform:none !important;position:fixed !important;"
                    "left:0 !important;top:0 !important;margin:0 !important}"
                )
            )
            page.evaluate("document.querySelectorAll('section.device').forEach(function(d){d.style.transform='none';})")
            page.evaluate("document.fonts && document.fonts.ready")
            page.wait_for_timeout(120)

            devices = page.query_selector_all("section.device")
            if not devices:
                print(f"[skip] {case_id}: 没有画板")
                page.close()
                continue
            for index, device in enumerate(devices):
                screen_id = device.get_attribute("data-id") or f"s{index}"
                target = args.out / f"{case_id}__{screen_id}.png"
                # 只截画板内容层：外框圆角与投影是三端固定的设备外观，不参与内容对比。
                surface = device.query_selector("div.screen") or device
                # 预览页一次只显示一张画板；对比需要逐张出图，这里手动切到当前画板。
                if not device.is_visible():
                    page.evaluate(
                        "function (i) {"
                        "document.querySelectorAll('section.device').forEach(function (d, j) {"
                        "d.classList.toggle('active', j === i);});}",
                        index,
                    )
                    page.wait_for_timeout(30)
                box = surface.bounding_box()
                if not box:
                    print(f"[skip] {case_id}__{screen_id}: 画板未显示")
                    continue
                # 元素截图会等待布局“稳定”，画板固定定位时这一步在无头环境下会卡住；
                # 这里直接用页面截图 + 裁剪，坐标是 CSS 像素，实际输出仍按 device_scale_factor 放大。
                page.screenshot(
                    path=str(target),
                    clip={"x": box["x"], "y": box["y"], "width": box["width"], "height": box["height"]},
                    animations="disabled",
                )
                print(f"[ok] {target}")
            page.close()
        browser.close()

    return 0 if failures == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())

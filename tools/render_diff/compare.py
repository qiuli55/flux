#!/usr/bin/env python3
"""两张三端渲染截图做像素对比。

用法::

    python3 tools/render_diff/compare.py a.png b.png --out build/render-diff/case

输出:
  * ``diff.png``   差异热图（红=超阈值像素，越亮差异越大）
  * ``report.json`` 指标明细
  * 退出码 1 表示超出容差，可直接用于 CI 卡口。

指标口径与 doc/design-spec.json 的验收标准一致：像素差 <0.5%、换行 100% 一致。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

#: 单通道差值超过该值视为“不同像素”（8/255 ≈ 3%，容忍抗锯齿与次像素差异）。
DEFAULT_THRESHOLD = 8
#: 不同像素占比上限，与契约验收标准一致。
DEFAULT_TOLERANCE = 0.005
#: 分块报告的行列数，用于定位偏差区域。
BLOCK_GRID = 16


def load_rgba(path: Path) -> np.ndarray:
    with Image.open(path) as image:
        return np.asarray(image.convert("RGBA"), dtype=np.int16)


def compare(a: np.ndarray, b: np.ndarray, threshold: int) -> dict:
    channel_diff = np.abs(a - b)
    pixel_diff = channel_diff.max(axis=2)
    total = int(pixel_diff.size)
    bad = pixel_diff > threshold
    bad_count = int(bad.sum())
    p95 = float(np.percentile(pixel_diff, 95)) if total else 0.0

    h, w = pixel_diff.shape
    rows = max(1, min(BLOCK_GRID, h))
    cols = max(1, min(BLOCK_GRID, w))
    block_h = max(1, h // rows)
    block_w = max(1, w // cols)
    blocks = []
    for row in range(rows):
        for col in range(cols):
            cell = pixel_diff[
                row * block_h : (row + 1) * block_h if row < rows - 1 else h,
                col * block_w : (col + 1) * block_w if col < cols - 1 else w,
            ]
            blocks.append(
                {
                    "row": row,
                    "col": col,
                    "x": col * block_w,
                    "y": row * block_h,
                    "badRatio": round(float((cell > threshold).mean()), 6),
                    "meanAbs": round(float(cell.mean()), 3),
                }
            )
    blocks.sort(key=lambda item: item["badRatio"], reverse=True)

    return {
        "size": [w, h],
        "totalPixels": total,
        "badPixels": bad_count,
        "badRatio": round(bad_count / total, 6) if total else 0.0,
        "meanAbs": round(float(pixel_diff.mean()), 3) if total else 0.0,
        "maxAbs": int(pixel_diff.max()) if total else 0,
        "p95Abs": round(p95, 3),
        "threshold": threshold,
        "worstBlocks": blocks[:8],
    }


def write_heatmap(reference: np.ndarray, diff: np.ndarray, out: Path, threshold: int) -> None:
    height, width = diff.shape
    heat = np.zeros((height, width, 4), dtype=np.uint8)
    # 底图：参考图去饱和后压暗，避免热图被内容淹没。
    gray = reference[:, :, :3].mean(axis=2, keepdims=True)
    heat[:, :, :3] = (gray * 0.25).astype(np.uint8)
    heat[:, :, 3] = 255
    strong = diff > threshold
    heat[:, :, 0] = np.where(strong, 255, heat[:, :, 0])
    heat[:, :, 1] = np.where(strong, np.clip(96 - diff, 0, 96), heat[:, :, 1])
    heat[:, :, 2] = np.where(strong, 96, heat[:, :, 2])
    Image.fromarray(heat, mode="RGBA").save(out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="对比两张渲染截图的像素差异")
    parser.add_argument("reference", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--out", type=Path, help="输出目录，默认与 reference 同级")
    parser.add_argument("--threshold", type=int, default=DEFAULT_THRESHOLD)
    parser.add_argument("--tolerance", type=float, default=DEFAULT_TOLERANCE)
    parser.add_argument("--json", action="store_true", help="只输出 JSON 结果")
    args = parser.parse_args(argv)

    if not args.reference.exists() or not args.candidate.exists():
        print(f"文件不存在: {args.reference} / {args.candidate}", file=sys.stderr)
        return 2

    ref = load_rgba(args.reference)
    cand = load_rgba(args.candidate)
    if ref.shape != cand.shape:
        result = {
            "ok": False,
            "reason": "size-mismatch",
            "referenceSize": [int(ref.shape[1]), int(ref.shape[0])],
            "candidateSize": [int(cand.shape[1]), int(cand.shape[0])],
        }
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1

    metrics = compare(ref, cand, args.threshold)
    out_dir = args.out or args.reference.parent
    out_dir.mkdir(parents=True, exist_ok=True)
    write_heatmap(ref, np.abs(ref - cand).max(axis=2), out_dir / "diff.png", args.threshold)

    ok = metrics["badRatio"] <= args.tolerance
    result = {"ok": ok, **metrics, "reference": str(args.reference), "candidate": str(args.candidate)}
    (out_dir / "report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    if args.json:
        print(json.dumps(result, ensure_ascii=False))
    else:
        print(
            f"{'PASS' if ok else 'FAIL'}  bad={metrics['badRatio'] * 100:.3f}% "
            f"(<= {args.tolerance * 100:.3f}%)  mean={metrics['meanAbs']}  p95={metrics['p95Abs']}  "
            f"max={metrics['maxAbs']}  -> {out_dir}"
        )
        if not ok:
            for block in metrics["worstBlocks"][:3]:
                print(
                    f"  热点 x={block['x']} y={block['y']} "
                    f"bad={block['badRatio'] * 100:.1f}% mean={block['meanAbs']}"
                )
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

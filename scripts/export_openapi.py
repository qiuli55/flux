"""导出 OpenAPI 契约到 docs/openapi.json（主规格 §12 / §17.5）。

前端（apps/*）与插件开发者以该文件为唯一接口契约，因此它必须随代码变更
一同提交。CI 中会重新导出并与之比对，出现差异即视为契约漂移。

用法：python scripts/export_openapi.py [--check]
    --check  只校验 docs/openapi.json 与当前代码是否一致，不写盘（CI 用）
"""

from __future__ import annotations

import argparse
import difflib
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
BACKEND_DIR = REPO_ROOT / "backend"
OUTPUT_PATH = REPO_ROOT / "docs" / "openapi.json"

sys.path.insert(0, str(BACKEND_DIR))

from flux.main import VERSION, create_app  # noqa: E402


def build_schema() -> dict:
    app = create_app()
    schema = app.openapi()
    schema["info"]["version"] = VERSION
    return schema


def main() -> int:
    parser = argparse.ArgumentParser(description="导出 Flux 的 OpenAPI 契约")
    parser.add_argument(
        "--check",
        action="store_true",
        help="只校验不写盘；与磁盘上的 openapi.json 不一致时以非零码退出",
    )
    args = parser.parse_args()

    rendered = json.dumps(build_schema(), indent=2, ensure_ascii=False, sort_keys=True) + "\n"

    if args.check:
        if not OUTPUT_PATH.exists():
            print(f"缺少契约文件：{OUTPUT_PATH}，请先运行 python scripts/export_openapi.py")
            return 1
        existing = OUTPUT_PATH.read_text(encoding="utf-8")
        if existing != rendered:
            print(f"契约漂移：{OUTPUT_PATH} 与当前代码不一致，请重新导出并提交")
            diff = list(
                difflib.unified_diff(
                    existing.splitlines(),
                    rendered.splitlines(),
                    fromfile=str(OUTPUT_PATH),
                    tofile="generated",
                    lineterm="",
                    n=2,
                )
            )
            if diff:
                print("差异（最多显示前 120 行）：")
                print("\n".join(diff[:120]))
            return 1
        print(f"契约一致：{OUTPUT_PATH}")
        return 0

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(rendered, encoding="utf-8")
    path_count = len(build_schema()["paths"])
    print(f"已写入 {OUTPUT_PATH}（{path_count} 条路径，version={VERSION}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
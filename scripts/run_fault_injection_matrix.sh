#!/usr/bin/env bash
# P0-2 Run 生命周期故障注入矩阵：脚本化运行并落盘证据（设计 §3.2）。
#
# 8 项注入场景在两处覆盖（映射见 backend/tests/test_run_lifecycle_faults.py 顶部表格）：
#   - 启动/空闲/硬超时、属主死亡重启对账：tests/test_run_supervisor.py（TC-15A~F）
#   - Cancel 升级、进程死而 Run 非终态、终态竞争、幂等边界：tests/test_run_lifecycle_faults.py
#
# 用法：bash scripts/run_fault_injection_matrix.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${FLUX_FAULT_EVIDENCE_DIR:-/tmp/flux-acceptance-v2}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$OUT_DIR/run-lifecycle-faults-$STAMP.txt"

mkdir -p "$OUT_DIR"
cd "$ROOT/backend"

{
  echo "# Flux Run 生命周期故障注入矩阵（P0-2）"
  echo "# 时间：$STAMP"
  echo "# 命令：pytest tests/test_run_lifecycle_faults.py tests/test_run_supervisor.py -v"
  echo
  PYTHONPATH="$ROOT/backend" "$ROOT/.venv/bin/python" -m pytest \
    tests/test_run_lifecycle_faults.py tests/test_run_supervisor.py -v
} 2>&1 | tee "$OUT"

echo
echo "证据已写入：$OUT"

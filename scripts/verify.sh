#!/usr/bin/env bash
# AI Engineering OS —— 本地「一条命令自检」（主规格 §19.1：M0 验收标准「每个 PR 上 CI 通过」）
#
# 检查顺序固定：
#   虚拟环境 → ruff 静态检查 → ruff 格式检查 → OpenAPI 契约 → 数据库迁移往返 → pytest
# CI（.github/workflows/ci.yml）直接调用本脚本，因此本地与 CI 使用完全相同的判据。
#
# 用法：bash scripts/verify.sh（等价于 make verify）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

VENV_PY="${REPO_ROOT}/.venv/bin/python"
VENV_RUFF="${REPO_ROOT}/.venv/bin/ruff"
VENV_ALEMBIC="${REPO_ROOT}/.venv/bin/alembic"
VENV_PYTEST="${REPO_ROOT}/.venv/bin/pytest"

# 迁移往返用的临时 SQLite 库，只在本次自检期间存在
VERIFY_DB="/tmp/aios_verify.db"
VERIFY_DB_URL="sqlite+aiosqlite:////tmp/aios_verify.db"

if [[ ! -x "${VENV_PY}" ]]; then
  echo "未找到 ${VENV_PY}，请先运行 make setup" >&2
  exit 1
fi

echo "自检仓库根目录：${REPO_ROOT}"
echo "使用虚拟环境：${VENV_PY}"
echo

echo "[1/5] ruff 静态检查"
(cd backend && "${VENV_RUFF}" check .)

echo "[2/5] ruff 格式检查"
(cd backend && "${VENV_RUFF}" format --check .)

echo "[3/5] OpenAPI 契约检查"
"${VENV_PY}" scripts/export_openapi.py --check

echo "[4/5] 数据库迁移往返（SQLite 临时库：先 upgrade head，再 downgrade base）"
rm -f "${VERIFY_DB}"
(cd backend && "${VENV_ALEMBIC}" -x db_url="${VERIFY_DB_URL}" upgrade head)
(cd backend && "${VENV_ALEMBIC}" -x db_url="${VERIFY_DB_URL}" downgrade base)
rm -f "${VERIFY_DB}"

echo "[5/5] pytest 用例"
(cd backend && "${VENV_PYTEST}")

echo
echo "全部自检通过"

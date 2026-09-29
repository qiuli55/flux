# AI Engineering OS —— M0 工程化交付：本地开发与「一条命令自检」入口
#
# 约定（主规格 §17.2 仓库结构 / §18 部署）：
#   * 虚拟环境固定在仓库根 .venv，与 CI（.github/workflows/ci.yml）保持一致；
#   * ruff 与 pytest 的配置写在 backend/pyproject.toml 中，且其中的路径相对 backend/ 生效，
#     所以这些目标都会先 `cd $(BACKEND)`，再通过 `../$(PY)` 调用虚拟环境里的工具。
#
# 首次使用：make setup && make verify

VENV := .venv
PY := $(VENV)/bin/python
BACKEND := backend

.DEFAULT_GOAL := help

.PHONY: help setup lint fmt test migrate openapi openapi-check verify run up down clean

help:
	@echo "AI Engineering OS 本地开发命令"
	@echo ""
	@echo "  make help           显示本帮助"
	@echo "  make setup          创建 $(VENV) 虚拟环境并安装 $(BACKEND)/requirements.txt 的全部依赖"
	@echo "  make lint           在 $(BACKEND)/ 下运行 ruff 静态检查（不修改文件）"
	@echo "  make fmt            在 $(BACKEND)/ 下运行 ruff 格式化并写盘"
	@echo "  make test           在 $(BACKEND)/ 下运行 pytest 全部用例"
	@echo "  make migrate        在 $(BACKEND)/ 下按 AIOS_DATABASE_URL 执行 alembic upgrade head"
	@echo "  make openapi        导出 OpenAPI 契约到 docs/openapi.json"
	@echo "  make openapi-check  校验 docs/openapi.json 与当前代码是否一致（CI 用，不写盘）"
	@echo "  make verify         执行 scripts/verify.sh 全量自检（ruff / 契约 / 迁移往返 / pytest）"
	@echo "  make run            以热重载方式启动后端 API（http://127.0.0.1:8000，文档 /docs）"
	@echo "  make up             用 Docker Compose 构建并后台启动 postgres / redis / backend"
	@echo "  make down           停止并移除 Docker Compose 启动的服务与网络"
	@echo "  make clean          清理 __pycache__ / .pytest_cache / .ruff_cache 缓存目录"

setup:
	python3 -m venv $(VENV)
	$(VENV)/bin/pip install -r $(BACKEND)/requirements.txt
	@echo "依赖安装完成；接着可运行 make verify"

lint:
	cd $(BACKEND) && ../$(PY) -m ruff check .

fmt:
	cd $(BACKEND) && ../$(PY) -m ruff format .

test:
	cd $(BACKEND) && ../$(PY) -m pytest

# 数据库地址取值优先级：命令行 -x 覆盖 > 环境变量 AIOS_DATABASE_URL（含仓库根 .env）
# > aios.config 默认值（sqlite+aiosqlite:///./aios.db）。env_file 固定指向仓库根 .env，
# 与 cwd 无关（见 backend/aios/config.py），故 make migrate 与 make run 读到同一份配置。
migrate:
	cd $(BACKEND) && ../$(PY) -m alembic -x db_url="$(AIOS_DATABASE_URL)" upgrade head

openapi:
	$(PY) scripts/export_openapi.py

openapi-check:
	$(PY) scripts/export_openapi.py --check

verify:
	bash scripts/verify.sh

run:
	$(PY) -m uvicorn aios.main:app --reload --app-dir $(BACKEND)

up:
	docker compose up -d --build

down:
	docker compose down

clean:
	find . -type d -name '__pycache__' -prune -exec rm -rf {} +
	find . -type d -name '.pytest_cache' -prune -exec rm -rf {} +
	find . -type d -name '.ruff_cache' -prune -exec rm -rf {} +
	@echo "已清理 __pycache__ / .pytest_cache / .ruff_cache"

"""运行日志上传与查询。

客户端把「上一次启动 → 本次启动」之间的日志打包（.log / .zip）上传，
这里按用户分目录落盘，并把区间元数据写进 run_logs 表。
"""
from __future__ import annotations

import hashlib
import logging
from datetime import datetime, timezone
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from fastapi.responses import FileResponse
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..config import settings
from ..db import get_db
from ..deps import get_current_user
from ..models import RunLog, User
from ..schemas import LogListOut, LogOut, OkOut

router = APIRouter(prefix="/api/logs", tags=["logs"])
log = logging.getLogger("flux_cloud.logs")

_CHUNK = 1024 * 1024


def _parse_dt(value: str | None) -> datetime | None:
    if not value:
        return None
    text = value.strip()
    if not text:
        return None
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(timezone.utc).replace(tzinfo=None)
    return parsed


def _safe_suffix(filename: str) -> str:
    suffix = Path(filename or "").suffix.lower()
    return suffix if suffix in {".log", ".txt", ".zip", ".gz", ".tar", ".json"} else ".log"


def _user_dir(user_id: int) -> Path:
    path = settings.log_dir / str(user_id)
    path.mkdir(parents=True, exist_ok=True)
    return path


@router.post("/upload", response_model=LogOut)
async def upload(
    file: UploadFile = File(...),
    run_started_at: str = Form(default=""),
    prev_run_started_at: str = Form(default=""),
    client_version: str = Form(default=""),
    os_info: str = Form(default=""),
    note: str = Form(default=""),
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> LogOut:
    limit = settings.max_log_mb * 1024 * 1024
    original_name = Path(file.filename or "run.log").name[:255]
    stored_name = f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')}-{user.id}-{hashlib.sha1(original_name.encode()).hexdigest()[:8]}{_safe_suffix(original_name)}"
    target = _user_dir(user.id) / stored_name

    digest = hashlib.sha256()
    size = 0
    try:
        with target.open("wb") as out:
            while True:
                chunk = await file.read(_CHUNK)
                if not chunk:
                    break
                size += len(chunk)
                if size > limit:
                    raise HTTPException(
                        status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                        f"日志包超过 {settings.max_log_mb} MB 上限",
                    )
                digest.update(chunk)
                out.write(chunk)
    except HTTPException:
        target.unlink(missing_ok=True)
        raise
    except Exception:  # noqa: BLE001 - 落盘失败一律回滚，别留半个文件
        target.unlink(missing_ok=True)
        log.exception("写入日志包失败 user=%s", user.id)
        raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR, "日志保存失败") from None

    if size == 0:
        target.unlink(missing_ok=True)
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "日志内容为空")

    record = RunLog(
        user_id=user.id,
        stored_name=stored_name,
        original_name=original_name,
        size_bytes=size,
        sha256=digest.hexdigest(),
        run_started_at=_parse_dt(run_started_at),
        prev_run_started_at=_parse_dt(prev_run_started_at),
        client_version=(client_version or "")[:64] or None,
        os_info=(os_info or "")[:255] or None,
        note=(note or "").strip()[:4000] or None,
    )
    db.add(record)
    db.commit()
    return LogOut.model_validate(record)


@router.get("", response_model=LogListOut)
def list_logs(
    limit: int = 20,
    offset: int = 0,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> LogListOut:
    limit = max(1, min(limit, 100))
    offset = max(0, offset)
    total = db.scalar(select(func.count(RunLog.id)).where(RunLog.user_id == user.id)) or 0
    rows = db.scalars(
        select(RunLog)
        .where(RunLog.user_id == user.id)
        .order_by(RunLog.created_at.desc(), RunLog.id.desc())
        .limit(limit)
        .offset(offset)
    ).all()
    return LogListOut(items=[LogOut.model_validate(row) for row in rows], total=total)


def _owned(db: Session, user: User, log_id: int) -> RunLog:
    record = db.scalar(select(RunLog).where(RunLog.id == log_id, RunLog.user_id == user.id))
    if record is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "日志不存在")
    return record


@router.get("/{log_id}/download")
def download(
    log_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> FileResponse:
    record = _owned(db, user, log_id)
    path = _user_dir(user.id) / record.stored_name
    if not path.is_file():
        raise HTTPException(status.HTTP_410_GONE, "日志文件已不存在")
    return FileResponse(path, filename=record.original_name, media_type="application/octet-stream")


@router.delete("/{log_id}", response_model=OkOut)
def delete_log(
    log_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> OkOut:
    record = _owned(db, user, log_id)
    (_user_dir(user.id) / record.stored_name).unlink(missing_ok=True)
    db.delete(record)
    db.commit()
    return OkOut(message="已删除")

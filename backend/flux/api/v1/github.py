"""GitHub API：账号授权、仓库浏览与提交。

主授权链路是 Authorization Code（PKCE）：`/oauth/start` 返回 GitHub 授权页地址，
浏览器回跳后 App 把 code + state 交给 `/oauth/callback` 换票。设备码与手工令牌
作为旧客户端回退保留。令牌由服务层加密落盘，本层响应里永远不出现令牌。
仓库读取覆盖用户可见的全部仓库（含私有），提交与建 PR 是显式动作，不会被 Agent
静默触发（MCP 侧另有权限门与审批标记）。
"""

from __future__ import annotations

import html

from fastapi import APIRouter, Depends, Query
from fastapi.responses import HTMLResponse

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.errors import AIOSError, NotFoundError
from flux.schemas.api import (
    GitHubCommitRequest,
    GitHubOAuthCallbackRequest,
    GitHubPullRequestCreate,
    GitHubTokenRequest,
)

router = APIRouter(prefix="/github", tags=["github"])


@router.get("/status")
async def github_status(container: Container = Depends(get_container)) -> dict[str, object]:
    """当前 GitHub 登录态（不含令牌）。"""
    return ok(container.github.status())


@router.post("/device/start")
async def start_device_login(
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """申请设备码：返回授权地址与用户码，前端引导用户去浏览器授权。"""
    return ok(await container.github.start_device_login())


@router.get("/device/{flow_id}")
async def device_login_state(
    flow_id: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    """轮询设备码登录结果：pending / success / failed / expired / cancelled。"""
    state = await container.github.device_login_state(flow_id)
    if state is None:
        raise NotFoundError(f"登录会话不存在或已过期：{flow_id}", details={"flow_id": flow_id})
    return ok(state)


@router.post("/oauth/start")
async def start_oauth(
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """创建 state + PKCE，返回 GitHub 授权页地址（App 用系统浏览器打开）。"""
    return ok(await container.github.start_oauth())


@router.post("/oauth/callback")
async def complete_oauth(
    payload: GitHubOAuthCallbackRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """App 回传浏览器拿到的 code + state，服务端换票并保存登录态。"""
    return ok(await container.github.complete_oauth(payload.code, payload.state))


@router.get("/oauth/callback", response_class=HTMLResponse)
async def complete_oauth_browser(
    code: str = Query(default=""),
    state: str = Query(default=""),
    container: Container = Depends(get_container),
) -> HTMLResponse:
    """浏览器直达的后端 HTTPS 回调（桌面 / 网页以后用）；返回一张结果页。"""
    if not code.strip():
        return _oauth_html("授权失败", "缺少授权码，请回到 Flux 重新连接 GitHub", ok=False)
    try:
        status = await container.github.complete_oauth(code, state)
    except AIOSError as exc:
        return _oauth_html("授权失败", exc.message, ok=False)
    login = str(status.get("login") or "").strip()
    detail = (
        f"已连接 @{login}，可以回到 Flux 继续。"
        if login
        else "已连接 GitHub，可以回到 Flux 继续。"
    )
    return _oauth_html("授权成功", detail, ok=True)


def _oauth_html(title: str, message: str, *, ok: bool) -> HTMLResponse:
    color = "#059669" if ok else "#e11d48"
    body = (
        "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\" />"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />"
        f"<title>{html.escape(title)}</title></head>"
        "<body style=\"margin:0;min-height:100vh;display:flex;align-items:center;"
        "justify-content:center;background:#f6f7f9;color:#1d1f23;"
        "font-family:system-ui,-apple-system,sans-serif;\">"
        "<main style=\"max-width:420px;padding:32px 24px;text-align:center;\">"
        f"<h1 style=\"font-size:20px;margin:0 0 10px;color:{color};\">"
        f"{html.escape(title)}</h1>"
        "<p style=\"font-size:14px;line-height:1.7;color:#52565e;margin:0;\">"
        f"{html.escape(message)}</p>"
        "</main></body></html>"
    )
    return HTMLResponse(body, status_code=200 if ok else 400)


@router.post("/token")
async def save_token(
    payload: GitHubTokenRequest, container: Container = Depends(get_container)
) -> dict[str, object]:
    """保存手工提供的令牌（Personal Access Token 回退路径）。"""
    return ok(await container.github.save_token(payload.token, kind=payload.kind))


@router.post("/logout")
async def logout(container: Container = Depends(get_container)) -> dict[str, object]:
    """退出 GitHub 账号：删除本地加密登录态。"""
    return ok(container.github.logout())


@router.get("/repos")
async def list_repos(
    page: int = Query(default=1, ge=1),
    per_page: int = Query(default=50, ge=1, le=100),
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """列出当前账号可见的全部仓库（含私有 / 组织仓库）。"""
    return ok(await container.github.list_repos(page=page, per_page=per_page))


@router.get("/repos/{owner}/{repo}")
async def get_repo(owner: str, repo: str, container: Container = Depends(get_container)) -> dict:
    return ok(await container.github.get_repo(owner, repo))


@router.get("/repos/{owner}/{repo}/branches")
async def list_branches(
    owner: str, repo: str, container: Container = Depends(get_container)
) -> dict[str, object]:
    return ok(await container.github.list_branches(owner, repo))


@router.get("/repos/{owner}/{repo}/file")
async def read_file(
    owner: str,
    repo: str,
    path: str = Query(min_length=1),
    ref: str | None = Query(default=None),
    container: Container = Depends(get_container),
) -> dict[str, object]:
    return ok(await container.github.read_file(owner, repo, path, ref=ref))


@router.post("/repos/{owner}/{repo}/commit")
async def commit_files(
    owner: str,
    repo: str,
    payload: GitHubCommitRequest,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    """把一组文件提交到指定分支（逐个文件走 Contents API）。"""
    files = [item.model_dump() for item in payload.files]
    return ok(
        await container.github.commit_files(
            owner,
            repo,
            branch=payload.branch,
            message=payload.message,
            files=files,
        )
    )


@router.post("/repos/{owner}/{repo}/pulls")
async def create_pull_request(
    owner: str,
    repo: str,
    payload: GitHubPullRequestCreate,
    container: Container = Depends(get_container),
) -> dict[str, object]:
    return ok(
        await container.github.create_pull_request(
            owner,
            repo,
            title=payload.title,
            head=payload.head,
            base=payload.base,
            body=payload.body,
        )
    )


@router.get("/repos/{owner}/{repo}/pulls")
async def list_pull_requests(
    owner: str,
    repo: str,
    state: str = Query(default="open"),
    container: Container = Depends(get_container),
) -> dict[str, object]:
    return ok(await container.github.list_pull_requests(owner, repo, state=state))


__all__ = ["router"]

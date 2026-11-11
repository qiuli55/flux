"""GitHub API：账号授权、仓库浏览与提交。

授权走 OAuth App 设备码流程；令牌由服务层加密落盘，本层响应里永远不出现令牌。
仓库读取覆盖用户可见的全部仓库（含私有），提交与建 PR 是显式动作，不会被 Agent
静默触发（MCP 侧另有权限门与审批标记）。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query

from flux.api.deps import get_container
from flux.api.response import ok
from flux.container import Container
from flux.errors import NotFoundError
from flux.schemas.api import (
    GitHubCommitRequest,
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

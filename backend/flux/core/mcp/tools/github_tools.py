"""GitHub 工具：列仓库 / 读文件 / 提交 / 建 PR。

读操作只要 `file.read`；写操作（提交、建 PR）声明 `github.create_pr` 能力并标记
`approval_capable`，由权限策略与审批层决定是否直接放行。工具只透传到
`container.github`，不在这里另写一套 GitHub 调用。
"""

from __future__ import annotations

from typing import Any

from flux.core.mcp.tools.base import (
    ToolContext,
    ToolSpec,
    optional_str,
    reject_unknown,
    require_str,
)
from flux.enums import Capability
from flux.errors import ValidationError

GITHUB_LIST_REPOS_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "page": {"type": "integer", "minimum": 1, "description": "页码，默认 1"},
        "per_page": {
            "type": "integer",
            "minimum": 1,
            "maximum": 100,
            "description": "每页数量，默认 30",
        },
    },
    "additionalProperties": False,
}


def _int_param(params: dict[str, Any], key: str, default: int) -> int:
    if key not in params:
        return default
    value = params[key]
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValidationError(f"参数 {key} 必须是整数", details={"param": key})
    return value


async def github_list_repos(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"page", "per_page"})
    page = _int_param(params, "page", 1)
    per_page = _int_param(params, "per_page", 30)
    if per_page < 1 or per_page > 100:
        raise ValidationError("参数 per_page 必须在 1..100 之间", details={"param": "per_page"})
    return await ctx.container.github.list_repos(page=page, per_page=per_page)


GITHUB_READ_FILE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "owner": {"type": "string", "description": "仓库所有者（用户或组织）"},
        "repo": {"type": "string", "description": "仓库名"},
        "path": {"type": "string", "description": "仓库内文件路径，如 src/main.py"},
        "ref": {"type": "string", "description": "分支 / tag / commit；不传用默认分支"},
    },
    "required": ["owner", "repo", "path"],
    "additionalProperties": False,
}


async def github_read_file(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"owner", "repo", "path", "ref"})
    return await ctx.container.github.read_file(
        require_str(params, "owner"),
        require_str(params, "repo"),
        require_str(params, "path"),
        ref=optional_str(params, "ref"),
    )


GITHUB_COMMIT_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "owner": {"type": "string", "description": "仓库所有者"},
        "repo": {"type": "string", "description": "仓库名"},
        "branch": {"type": "string", "description": "目标分支（必须已存在）"},
        "message": {"type": "string", "description": "提交信息"},
        "files": {
            "type": "array",
            "minItems": 1,
            "maxItems": 50,
            "items": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "仓库内路径"},
                    "content": {"type": "string", "description": "文件完整内容（UTF-8 文本）"},
                },
                "required": ["path", "content"],
                "additionalProperties": False,
            },
            "description": "要写入的文件清单（整文件覆盖）",
        },
    },
    "required": ["owner", "repo", "branch", "message", "files"],
    "additionalProperties": False,
}


async def github_commit(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"owner", "repo", "branch", "message", "files"})
    files = params.get("files")
    if not isinstance(files, list) or not files:
        raise ValidationError("files 必须是非空数组", details={"param": "files"})
    if len(files) > 50:
        raise ValidationError("files 一次最多 50 个", details={"param": "files"})
    cleaned: list[dict[str, Any]] = []
    for item in files:
        if not isinstance(item, dict):
            raise ValidationError("files 的每一项都必须是对象", details={"param": "files"})
        path = item.get("path")
        content = item.get("content")
        if not isinstance(path, str) or not path.strip():
            raise ValidationError("files[].path 必须是非空字符串", details={"param": "files"})
        if not isinstance(content, str):
            raise ValidationError("files[].content 必须是字符串", details={"param": "files"})
        cleaned.append({"path": path, "content": content})
    return await ctx.container.github.commit_files(
        require_str(params, "owner"),
        require_str(params, "repo"),
        branch=require_str(params, "branch"),
        message=require_str(params, "message"),
        files=cleaned,
    )


GITHUB_CREATE_PR_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "owner": {"type": "string", "description": "仓库所有者"},
        "repo": {"type": "string", "description": "仓库名"},
        "title": {"type": "string", "description": "PR 标题"},
        "head": {"type": "string", "description": "源分支"},
        "base": {"type": "string", "description": "目标分支（如 main）"},
        "body": {"type": "string", "description": "PR 说明"},
    },
    "required": ["owner", "repo", "title", "head", "base"],
    "additionalProperties": False,
}


async def github_create_pr(ctx: ToolContext, params: dict[str, Any]) -> dict[str, Any]:
    reject_unknown(params, {"owner", "repo", "title", "head", "base", "body"})
    return await ctx.container.github.create_pull_request(
        require_str(params, "owner"),
        require_str(params, "repo"),
        title=require_str(params, "title"),
        head=require_str(params, "head"),
        base=require_str(params, "base"),
        body=optional_str(params, "body") or "",
    )


def github_tools() -> tuple[ToolSpec, ...]:
    return (
        ToolSpec(
            name="github.list_repos",
            title="列出 GitHub 仓库",
            description=(
                "列出当前 GitHub 账号可见的全部仓库（含私有与组织仓库）。"
                "可按 page / per_page 翻页，用来找到要改的目标仓库。"
            ),
            input_schema=GITHUB_LIST_REPOS_SCHEMA,
            capability=Capability.FILE_READ,
            handler=github_list_repos,
        ),
        ToolSpec(
            name="github.read_file",
            title="读取 GitHub 仓库文件",
            description=(
                "读取 GitHub 仓库里某个文件的文本内容（可指定 ref）。"
                "用于看远端仓库当前状态；本地工作区文件仍用 workspace.read。"
            ),
            input_schema=GITHUB_READ_FILE_SCHEMA,
            capability=Capability.FILE_READ,
            handler=github_read_file,
        ),
        ToolSpec(
            name="github.commit",
            title="提交到 GitHub 仓库",
            description=(
                "把一组文件（整文件内容）提交到 GitHub 仓库的指定分支。"
                "这是远端写操作：必须给出明确的 owner / repo / branch / message。"
            ),
            input_schema=GITHUB_COMMIT_SCHEMA,
            capability=Capability.GITHUB_CREATE_PR,
            handler=github_commit,
            approval_capable=True,
        ),
        ToolSpec(
            name="github.create_pr",
            title="创建 Pull Request",
            description="在 GitHub 仓库里从 head 分支向 base 分支创建 Pull Request。",
            input_schema=GITHUB_CREATE_PR_SCHEMA,
            capability=Capability.GITHUB_CREATE_PR,
            handler=github_create_pr,
            approval_capable=True,
        ),
    )


__all__ = ["github_tools"]

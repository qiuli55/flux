"""GitHub 账号与仓库访问。

授权走 OAuth App 的设备码流程（无回调地址、无 client_secret）：本服务向 GitHub
申请 device_code + user_code，用户在自己的浏览器里授权，之后轮询换取 access_token。
设备码流程若未在 OAuth App 里开启（GitHub 返回 `device_flow_disabled`），可以退回
「粘贴 Personal Access Token」的方式，两条路最终落到同一份加密登录态。

令牌用密钥库主密钥（`SecretBox`）信封加密后落盘（0600），`status()` 只回登录名 /
头像 / scope 这类展示字段，绝不下发令牌本身。仓库读写都走 REST，私有仓库只要
scope 里有 `repo` 就能看到。
"""

from __future__ import annotations

import base64
import json
import os
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx

from flux.config import Settings
from flux.core.agent_runtime.platforms import secure_file
from flux.core.key_vault.service import KeyVaultService
from flux.errors import ConfigurationError, NotFoundError, ProviderError, ValidationError
from flux.logging import get_logger

logger = get_logger(__name__)

_DEVICE_CODE_URL = "https://github.com/login/device/code"
_ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
_DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

#: 一次设备码登录的最长寿命（GitHub 侧 900 秒），留一点余量兜底
_DEVICE_MAX_SECONDS = 15 * 60.0
#: 已结束的登录会话在内存里保留多久（够前端轮询到终态即可）
_LOGIN_KEEP_SECONDS = 10 * 60.0
#: 列仓库单页上限（GitHub 允许 100）
_MAX_PER_PAGE = 100
#: 令牌密文文件上限：正常只有几百字节
_MAX_ACCOUNT_BYTES = 1 << 20

_PENDING = "pending"


@dataclass
class _DeviceLogin:
    flow_id: str
    device_code: str
    user_code: str
    verification_uri: str
    interval: float
    created_at: float
    expires_at: float
    status: str = _PENDING
    message: str = ""
    next_poll_at: float = 0.0

    def public(self) -> dict[str, Any]:
        return {
            "flow_id": self.flow_id,
            "status": self.status,
            "user_code": self.user_code,
            "verification_uri": self.verification_uri,
            "message": self.message,
            "expires_in": max(0, int(self.expires_at - time.monotonic())),
        }


class GitHubService:
    """GitHub 登录态、仓库浏览与提交。登录会话只存内存，进程重启即失效。"""

    def __init__(self, settings: Settings, key_vault: KeyVaultService) -> None:
        self._settings = settings
        self._vault = key_vault
        self._logins: dict[str, _DeviceLogin] = {}
        self._client: httpx.AsyncClient | None = None
        self._account: dict[str, Any] | None = None
        self._loaded = False

    # --- 路径与连接 ---

    @property
    def account_path(self) -> Path:
        return self._settings.github_account_path

    def _http(self) -> httpx.AsyncClient:
        if self._client is None:
            self._client = httpx.AsyncClient(
                base_url=self._settings.github_api_base,
                timeout=self._settings.github_http_timeout_seconds,
                headers={
                    "Accept": "application/vnd.github+json",
                    "X-GitHub-Api-Version": "2022-11-28",
                    "User-Agent": "flux-agent",
                },
            )
        return self._client

    async def aclose(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    # --- 登录态落盘 ---

    def _load(self) -> dict[str, Any] | None:
        if self._loaded:
            return self._account
        self._loaded = True
        path = self.account_path
        try:
            if not path.is_file() or path.stat().st_size > _MAX_ACCOUNT_BYTES:
                self._account = None
                return None
            data = json.loads(path.read_text(encoding="utf-8"))
            sealed = str(data.get("token") or "")
            secret = self._vault.open_text(base64.b64decode(sealed)) if sealed else ""
        except (OSError, ValueError, KeyError):
            logger.warning("GitHub 登录态不可读 path=%s", path)
            self._account = None
            return None
        if not secret:
            self._account = None
            return None
        self._account = {**data, "secret": secret}
        return self._account

    def _persist(
        self,
        token: str,
        profile: dict[str, Any],
        *,
        kind: str,
        scopes: str,
    ) -> None:
        path = self.account_path
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            os.chmod(path.parent, 0o700)
        except OSError:
            pass
        record = {
            "token": base64.b64encode(self._vault.seal_text(token)).decode("ascii"),
            "login": profile.get("login"),
            "name": profile.get("name"),
            "avatar_url": profile.get("avatar_url"),
            "token_kind": kind,
            "scopes": scopes,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        tmp = path.with_name(path.name + ".tmp")
        try:
            tmp.write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
            secure_file(tmp)
            tmp.replace(path)
        except OSError as exc:
            raise ConfigurationError(
                "GitHub 登录态写入失败",
                details={"path": str(path), "error": str(exc)},
            ) from exc
        if not secure_file(path):
            logger.warning("GitHub 登录态文件权限弱化 path=%s", path)
        self._account = {**record, "secret": token}
        self._loaded = True
        logger.info("GitHub 账号已保存 login=%s kind=%s", record.get("login"), kind)

    def _token(self) -> str:
        account = self._load()
        if account is None:
            raise ConfigurationError(
                "尚未连接 GitHub 账号",
                details={"hint": "先在 App 里登录 GitHub，或粘贴 Personal Access Token"},
            )
        return str(account.get("secret") or "")

    # --- 状态 ---

    def status(self) -> dict[str, Any]:
        account = self._load()
        if account is None:
            return {
                "logged_in": False,
                "login": None,
                "name": None,
                "avatar_url": None,
                "token_kind": None,
                "scopes": None,
                "updated_at": None,
                "device_flow_ready": bool((self._settings.github_client_id or "").strip()),
                "pending_login": self._latest_pending(),
            }
        return {
            "logged_in": True,
            "login": account.get("login"),
            "name": account.get("name"),
            "avatar_url": account.get("avatar_url"),
            "token_kind": account.get("token_kind"),
            "scopes": account.get("scopes"),
            "updated_at": account.get("updated_at"),
            "device_flow_ready": bool((self._settings.github_client_id or "").strip()),
            "pending_login": self._latest_pending(),
        }

    # --- 设备码登录 ---

    async def start_device_login(self) -> dict[str, Any]:
        """申请设备码；返回授权地址与用户码，前端引导用户去浏览器授权。"""
        self._reap()
        existing = self._latest_pending()
        if existing is not None:
            return existing
        client_id = (self._settings.github_client_id or "").strip()
        if not client_id:
            raise ConfigurationError(
                "未配置 GitHub OAuth App 的 client_id",
                details={"setting": "FLUX_GITHUB_CLIENT_ID"},
            )
        data = await self._post_form(
            _DEVICE_CODE_URL,
            {
                "client_id": client_id,
                "scope": self._settings.github_scopes,
            },
        )
        if data.get("error"):
            raise ProviderError(
                _device_error_message(str(data.get("error"))),
                details={"error": data.get("error")},
            )
        device_code = str(data.get("device_code") or "")
        user_code = str(data.get("user_code") or "")
        verification_uri = str(data.get("verification_uri") or "")
        if not device_code or not user_code:
            raise ProviderError("GitHub 未返回设备码", details={"response": data})
        interval = float(data.get("interval") or 5)
        now = time.monotonic()
        login = _DeviceLogin(
            flow_id=uuid.uuid4().hex[:12],
            device_code=device_code,
            user_code=user_code,
            verification_uri=verification_uri,
            interval=max(1.0, interval),
            created_at=now,
            expires_at=now
            + min(float(data.get("expires_in") or _DEVICE_MAX_SECONDS), _DEVICE_MAX_SECONDS),
        )
        self._logins[login.flow_id] = login
        logger.info("GitHub 设备码登录开始 flow=%s", login.flow_id)
        return login.public()

    async def device_login_state(self, flow_id: str) -> dict[str, Any] | None:
        login = self._logins.get((flow_id or "").strip())
        if login is None:
            return None
        now = time.monotonic()
        if login.status == _PENDING and now >= login.expires_at:
            login.status = "expired"
            login.message = "授权码已过期，请重新发起登录"
        if login.status == _PENDING and now >= login.next_poll_at:
            await self._poll_device(login)
        return login.public()

    async def _poll_device(self, login: _DeviceLogin) -> None:
        login.next_poll_at = time.monotonic() + login.interval
        client_id = (self._settings.github_client_id or "").strip()
        try:
            data = await self._post_form(
                _ACCESS_TOKEN_URL,
                {
                    "client_id": client_id,
                    "device_code": login.device_code,
                    "grant_type": _DEVICE_GRANT,
                },
            )
        except ProviderError as exc:
            login.message = exc.message
            return
        error = str(data.get("error") or "")
        if error == "authorization_pending":
            return
        if error == "slow_down":
            login.interval += 5
            return
        if error:
            login.status = "expired" if error == "expired_token" else "failed"
            login.message = _device_error_message(error)
            return
        token = str(data.get("access_token") or "")
        if not token:
            login.status = "failed"
            login.message = "GitHub 未返回 access_token"
            return
        try:
            profile, scopes = await self._fetch_user(token)
        except ProviderError as exc:
            login.status = "failed"
            login.message = exc.message
            return
        self._persist(token, profile, kind="oauth", scopes=scopes or str(data.get("scope") or ""))
        login.status = "success"
        login.message = ""
        logger.info("GitHub 设备码登录成功 flow=%s", login.flow_id)

    def _latest_pending(self) -> dict[str, Any] | None:
        pending = [item for item in self._logins.values() if item.status == _PENDING]
        if not pending:
            return None
        return max(pending, key=lambda item: item.created_at).public()

    def _reap(self) -> None:
        now = time.monotonic()
        for flow_id, login in list(self._logins.items()):
            finished = login.status != _PENDING
            if finished and now - login.created_at > _LOGIN_KEEP_SECONDS:
                self._logins.pop(flow_id, None)

    # --- 令牌 ---

    async def save_token(self, token: str, *, kind: str = "pat") -> dict[str, Any]:
        """校验并保存手工提供的令牌（Personal Access Token / 其他 OAuth 令牌）。"""
        value = (token or "").strip()
        if not value:
            raise ValidationError("令牌不能为空", details={"param": "token"})
        profile, scopes = await self._fetch_user(value)
        self._persist(value, profile, kind=kind, scopes=scopes)
        return self.status()

    def logout(self) -> dict[str, Any]:
        removed = False
        path = self.account_path
        try:
            if path.exists():
                path.unlink()
                removed = True
        except OSError as exc:
            raise ConfigurationError(
                "删除 GitHub 登录态失败",
                details={"path": str(path), "error": str(exc)},
            ) from exc
        self._account = None
        self._loaded = True
        for login in self._logins.values():
            if login.status == _PENDING:
                login.status = "cancelled"
                login.message = "已退出登录"
        logger.info("GitHub 账号已退出 removed=%s", removed)
        return {"removed": removed, "status": self.status()}

    # --- 仓库 ---

    async def list_repos(self, *, page: int = 1, per_page: int = 50) -> dict[str, Any]:
        page = max(1, int(page))
        per_page = min(max(1, int(per_page)), _MAX_PER_PAGE)
        rows = await self._request(
            "GET",
            "/user/repos",
            params={
                "affiliation": "owner,collaborator,organization_member",
                "sort": "updated",
                "direction": "desc",
                "per_page": per_page,
                "page": page,
            },
        )
        repos = [_repo_view(item) for item in (rows or []) if isinstance(item, dict)]
        return {"repos": repos, "page": page, "per_page": per_page, "count": len(repos)}

    async def get_repo(self, owner: str, repo: str) -> dict[str, Any]:
        data = await self._request("GET", f"/repos/{_seg(owner)}/{_seg(repo)}")
        return _repo_view(data if isinstance(data, dict) else {})

    async def list_branches(self, owner: str, repo: str) -> list[dict[str, Any]]:
        rows = await self._request(
            "GET",
            f"/repos/{_seg(owner)}/{_seg(repo)}/branches",
            params={"per_page": _MAX_PER_PAGE},
        )
        return [
            {"name": item.get("name"), "sha": (item.get("commit") or {}).get("sha")}
            for item in (rows or [])
            if isinstance(item, dict)
        ]

    async def read_file(
        self,
        owner: str,
        repo: str,
        path: str,
        *,
        ref: str | None = None,
    ) -> dict[str, Any]:
        target = _clean_path(path)
        params = {"ref": ref} if ref else None
        try:
            data = await self._request(
                "GET",
                f"/repos/{_seg(owner)}/{_seg(repo)}/contents/{_quote_path(target)}",
                params=params,
            )
        except NotFoundError as exc:
            raise NotFoundError(f"仓库里没有这个文件：{target}", details={"path": target}) from exc
        if isinstance(data, list):
            raise ValidationError("目标路径是目录，请指定具体文件", details={"path": target})
        if not isinstance(data, dict):
            raise ProviderError("GitHub 返回了无法识别的文件内容", details={"path": target})
        content = str(data.get("content") or "")
        encoding = str(data.get("encoding") or "base64")
        if encoding == "base64":
            raw = base64.b64decode(content.encode("ascii"))
            text = raw.decode("utf-8", errors="replace")
        else:
            text = content
        return {
            "path": data.get("path") or target,
            "ref": ref,
            "sha": data.get("sha"),
            "size": data.get("size"),
            "content": text,
            "html_url": data.get("html_url"),
        }

    async def commit_files(
        self,
        owner: str,
        repo: str,
        *,
        branch: str,
        message: str,
        files: list[dict[str, Any]],
    ) -> dict[str, Any]:
        """按文件逐个提交到指定分支（Contents API）；已存在的文件自动带上 sha。"""
        if not files:
            raise ValidationError("files 不能为空", details={"param": "files"})
        commit_message = (message or "").strip()
        if not commit_message:
            raise ValidationError("提交信息不能为空", details={"param": "message"})
        target_branch = (branch or "").strip()
        if not target_branch:
            raise ValidationError("必须指定分支", details={"param": "branch"})

        results: list[dict[str, Any]] = []
        for item in files:
            target = _clean_path(str(item.get("path") or ""))
            content = item.get("content")
            if not isinstance(content, str):
                raise ValidationError(
                    "每个文件都必须带字符串类型的 content", details={"path": target}
                )
            sha = await self._existing_sha(owner, repo, target, target_branch)
            body: dict[str, Any] = {
                "message": commit_message,
                "content": base64.b64encode(content.encode("utf-8")).decode("ascii"),
                "branch": target_branch,
            }
            if sha:
                body["sha"] = sha
            data = await self._request(
                "PUT",
                f"/repos/{_seg(owner)}/{_seg(repo)}/contents/{_quote_path(target)}",
                json=body,
            )
            commit = (data or {}).get("commit") if isinstance(data, dict) else {}
            results.append(
                {
                    "path": target,
                    "created": not bool(sha),
                    "commit": (commit or {}).get("sha"),
                    "html_url": ((commit or {}).get("html_url")),
                }
            )
        return {
            "owner": owner,
            "repo": repo,
            "branch": target_branch,
            "message": commit_message,
            "files": results,
        }

    async def create_pull_request(
        self,
        owner: str,
        repo: str,
        *,
        title: str,
        head: str,
        base: str,
        body: str = "",
    ) -> dict[str, Any]:
        values = {"title": title, "head": head, "base": base, "body": body}
        if not (title or "").strip() or not (head or "").strip() or not (base or "").strip():
            raise ValidationError("title / head / base 都不能为空", details={"param": "title"})
        data = await self._request(
            "POST",
            f"/repos/{_seg(owner)}/{_seg(repo)}/pulls",
            json=values,
        )
        return _pr_view(data if isinstance(data, dict) else {})

    async def list_pull_requests(
        self, owner: str, repo: str, *, state: str = "open"
    ) -> list[dict[str, Any]]:
        rows = await self._request(
            "GET",
            f"/repos/{_seg(owner)}/{_seg(repo)}/pulls",
            params={"state": state, "per_page": _MAX_PER_PAGE},
        )
        return [_pr_view(item) for item in (rows or []) if isinstance(item, dict)]

    async def _existing_sha(self, owner: str, repo: str, path: str, branch: str) -> str | None:
        try:
            data = await self._request(
                "GET",
                f"/repos/{_seg(owner)}/{_seg(repo)}/contents/{_quote_path(path)}",
                params={"ref": branch},
            )
        except NotFoundError:
            return None
        if isinstance(data, dict):
            value = str(data.get("sha") or "")
            return value or None
        return None

    # --- HTTP 基础 ---

    async def _post_form(self, url: str, data: dict[str, Any]) -> dict[str, Any]:
        try:
            response = await self._http().post(
                url, data=data, headers={"Accept": "application/json"}
            )
        except httpx.HTTPError as exc:
            raise ProviderError(f"连接 GitHub 失败：{exc}", details={"url": url}) from exc
        try:
            payload = response.json()
        except ValueError as exc:
            raise ProviderError(
                f"GitHub 返回了非 JSON 响应（{response.status_code}）", details={"url": url}
            ) from exc
        if response.status_code >= 400 and not payload.get("error"):
            payload = {**payload, "error": "http_error"}
        return payload if isinstance(payload, dict) else {}

    async def _request(self, method: str, path: str, **kwargs: Any) -> Any:
        token = self._token()
        try:
            response = await self._http().request(
                method,
                path,
                headers={"Authorization": f"Bearer {token}"},
                **kwargs,
            )
        except httpx.TimeoutException as exc:
            raise ProviderError("GitHub 请求超时", details={"path": path}) from exc
        except httpx.HTTPError as exc:
            raise ProviderError(f"连接 GitHub 失败：{exc}", details={"path": path}) from exc
        if response.status_code == 404:
            raise NotFoundError("GitHub 资源不存在", details={"path": path})
        if response.status_code == 401:
            raise ProviderError(
                "GitHub 令牌已失效，请重新登录",
                details={"status": 401, "path": path},
            )
        if response.status_code == 403:
            raise ProviderError(
                "GitHub 拒绝了请求（权限不足或触发限流）",
                details={"status": 403, "path": path, "body": _tail(response.text)},
            )
        if response.status_code >= 400:
            raise ProviderError(
                f"GitHub 请求失败（{response.status_code}）",
                details={
                    "status": response.status_code,
                    "path": path,
                    "body": _tail(response.text),
                },
            )
        if response.status_code == 204 or not response.content:
            return None
        try:
            return response.json()
        except ValueError as exc:
            raise ProviderError("GitHub 返回了非 JSON 响应", details={"path": path}) from exc

    async def _fetch_user(self, token: str) -> tuple[dict[str, Any], str]:
        try:
            response = await self._http().get("/user", headers={"Authorization": f"Bearer {token}"})
        except httpx.HTTPError as exc:
            raise ProviderError(f"连接 GitHub 失败：{exc}", details={"path": "/user"}) from exc
        if response.status_code == 401:
            raise ProviderError("GitHub 令牌无效或已过期", details={"status": 401})
        if response.status_code >= 400:
            raise ProviderError(
                f"GitHub 用户信息获取失败（{response.status_code}）",
                details={"status": response.status_code, "body": _tail(response.text)},
            )
        try:
            profile = response.json()
        except ValueError as exc:
            raise ProviderError("GitHub 返回了非 JSON 响应", details={"path": "/user"}) from exc
        scopes = response.headers.get("x-oauth-scopes", "")
        return (profile if isinstance(profile, dict) else {}), scopes


def _repo_view(item: dict[str, Any]) -> dict[str, Any]:
    owner = item.get("owner")
    owner = owner if isinstance(owner, dict) else {}
    permissions = item.get("permissions")
    return {
        "full_name": item.get("full_name"),
        "name": item.get("name"),
        "owner": owner.get("login"),
        "private": bool(item.get("private")),
        "description": item.get("description"),
        "default_branch": item.get("default_branch"),
        "language": item.get("language"),
        "updated_at": item.get("updated_at"),
        "html_url": item.get("html_url"),
        "permissions": permissions if isinstance(permissions, dict) else {},
    }


def _pr_view(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "number": item.get("number"),
        "title": item.get("title"),
        "state": item.get("state"),
        "html_url": item.get("html_url"),
        "head": (item.get("head") or {}).get("ref"),
        "base": (item.get("base") or {}).get("ref"),
        "draft": bool(item.get("draft")),
        "merged": bool(item.get("merged")),
    }


def _seg(value: str) -> str:
    return quote((value or "").strip(), safe="")


def _quote_path(path: str) -> str:
    return quote(path, safe="/")


def _clean_path(path: str) -> str:
    value = (path or "").strip().lstrip("/")
    if not value:
        raise ValidationError("文件路径不能为空", details={"param": "path"})
    if ".." in value.split("/"):
        raise ValidationError("文件路径不能包含 ..", details={"param": "path"})
    return value


def _device_error_message(code: str) -> str:
    if code == "device_flow_disabled":
        return (
            "该 GitHub OAuth App 未启用 Device Flow：请到 GitHub → Settings → "
            "Developer settings → OAuth Apps → 对应应用，勾选 Enable Device Flow 后重试；"
            "也可以直接粘贴 Personal Access Token 登录。"
        )
    if code in {"expired_token", "code_expired"}:
        return "授权码已过期，请重新发起登录"
    if code == "access_denied":
        return "用户取消了 GitHub 授权"
    if code == "incorrect_client_credentials":
        return "GitHub OAuth App 的 client_id 不正确"
    return f"GitHub 授权失败：{code}" if code else "GitHub 授权失败"


def _tail(text: str, limit: int = 300) -> str:
    return (text or "").strip()[-limit:]


__all__ = ["GitHubService"]

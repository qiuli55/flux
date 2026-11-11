"""GitHub 服务：令牌加密落盘、仓库读取与提交路径。

用 httpx.MockTransport 拦住真实网络，验证三条边界：
- 令牌只以密文落盘，状态响应永远不下发令牌；
- 仓库列表保留 `private` 标记（私有仓库与公开仓库一视同仁地返回）；
- 提交文件时先取 sha 再 PUT，内容按 base64 上传。
"""

from __future__ import annotations

import base64
import json
from collections.abc import Callable
from pathlib import Path

import httpx
import pytest

from flux.config import Settings
from flux.core.github.service import GitHubService
from flux.errors import ConfigurationError

TOKEN = "ghp_test_token_123"
USER = {"login": "qiuli55", "name": "Qiuli", "avatar_url": "https://example.com/a.png"}


class _FakeVault:
    """最小密钥库替身：只验证「落盘是密文」这一条边界。"""

    def seal_text(self, text: str) -> bytes:
        return b"v1:" + base64.b64encode(text.encode("utf-8"))

    def open_text(self, ciphertext: bytes) -> str:
        if not ciphertext.startswith(b"v1:"):
            raise ConfigurationError("密钥不匹配")
        return base64.b64decode(ciphertext[3:]).decode("utf-8")


def _service(tmp_path: Path, handler: Callable[[httpx.Request], httpx.Response]) -> GitHubService:
    settings = Settings(
        env="test",
        log_level="WARNING",
        github_client_id="client-test",
        github_account_file=str(tmp_path / "gh" / "account.json"),
    )
    service = GitHubService(settings, _FakeVault())  # type: ignore[arg-type]
    service._client = httpx.AsyncClient(
        base_url="https://api.github.com",
        transport=httpx.MockTransport(handler),
        headers={"Accept": "application/vnd.github+json"},
    )
    return service


def _logged_in(service: GitHubService) -> None:
    service._account = {"secret": TOKEN}
    service._loaded = True


async def test_save_token_stores_ciphertext_and_status_never_returns_it(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/user"
        assert request.headers["authorization"] == f"Bearer {TOKEN}"
        return httpx.Response(200, json=USER, headers={"x-oauth-scopes": "repo, read:org"})

    service = _service(tmp_path, handler)
    try:
        status = await service.save_token(TOKEN)
        assert status["logged_in"] is True
        assert status["login"] == "qiuli55"
        assert TOKEN not in json.dumps(status)
        assert status["pending_login"] is None

        assert service.account_path.is_file()
        raw = service.account_path.read_text(encoding="utf-8")
        assert TOKEN not in raw
        assert (service.account_path.stat().st_mode & 0o777) == 0o600

        removed = service.logout()
        assert removed["removed"] is True
        assert removed["status"]["logged_in"] is False
        assert not service.account_path.exists()
    finally:
        await service.aclose()


async def test_list_repos_keeps_private_flag(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/user/repos"
        assert request.url.params["affiliation"] == "owner,collaborator,organization_member"
        rows = [
            {
                "full_name": "qiuli55/flux",
                "name": "flux",
                "private": True,
                "owner": {"login": "qiuli55"},
                "default_branch": "main",
            },
            {
                "full_name": "qiuli55/site",
                "name": "site",
                "private": False,
                "owner": {"login": "qiuli55"},
                "default_branch": "main",
            },
        ]
        return httpx.Response(200, json=rows)

    service = _service(tmp_path, handler)
    _logged_in(service)
    try:
        page = await service.list_repos(page=1, per_page=50)
        assert page["count"] == 2
        assert [repo["private"] for repo in page["repos"]] == [True, False]
        assert page["repos"][0]["full_name"] == "qiuli55/flux"
        assert page["repos"][0]["owner"] == "qiuli55"
    finally:
        await service.aclose()


async def test_commit_files_uploads_base64_and_sets_branch(tmp_path: Path) -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET":
            return httpx.Response(404, json={"message": "Not Found"})
        seen["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={"commit": {"sha": "c1", "html_url": "https://example.com/c1"}},
        )

    service = _service(tmp_path, handler)
    _logged_in(service)
    try:
        result = await service.commit_files(
            "qiuli55",
            "flux",
            branch="feat/github",
            message="feat: 接入 GitHub",
            files=[{"path": "docs/a.md", "content": "你好"}],
        )
        body = seen["body"]
        assert isinstance(body, dict)
        assert base64.b64decode(body["content"]).decode("utf-8") == "你好"
        assert body["branch"] == "feat/github"
        assert "sha" not in body
        assert result["files"][0]["created"] is True
        assert result["files"][0]["commit"] == "c1"
    finally:
        await service.aclose()


async def test_requests_require_a_saved_token(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:  # pragma: no cover
        raise AssertionError("未登录时不应该发出请求")

    service = _service(tmp_path, handler)
    try:
        with pytest.raises(ConfigurationError):
            await service.list_repos()
    finally:
        await service.aclose()

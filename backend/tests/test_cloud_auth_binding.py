"""安全审查 P0/P1 的账号云绑定与信任边界用例。

覆盖：
- P0-1：云 access token 验签通过**不等于**本机主人。只有 sub 在允许名单里、
  或等于本机绑定主人时才授权；缺 exp / typ 不符 / 签名错一律拒绝。REST 与
  WebSocket 共用同一判断。
- P0-2：未鉴权的公网请求不能改写服务器单例 `cloud.json` / 绑定主人，也不能读到
  服务器单例里的主人身份；只有本机主人（回环直连或有效 FLUX_AUTH_TOKEN）可以。
- P1-2：WebSocket 接受属于本实例的云 access token（Authorization 或 cookie）。

JWT 全部用标准库构造，测试用假密钥，不读也不打印任何真实凭据。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from flux.cloud.owner import CloudOwner, load_owner, save_owner
from flux.cloud.session import CloudClient, CloudSession, save_session
from flux.config import Settings
from flux.main import create_app

#: 测试专用假密钥（不是任何真实部署的密钥）。
SECRET = "unit-test-cloud-secret-6f1d0c"
LOCAL_TOKEN = "unit-test-local-owner-token-9a2b"

PUBLIC_CLIENT = {"client": ("203.0.113.9", 41234)}
LOOPBACK_CLIENT = {"client": ("127.0.0.1", 41234)}

HEALTH = "/api/v1/health"


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def make_token(
    *,
    sub: str = "allowed-user",
    typ: str | None = "access",
    include_exp: bool = True,
    exp: float | None = None,
    secret: str = SECRET,
    issuer: str | None = None,
    audience: str | None = None,
    alg: str = "HS256",
) -> str:
    """构造一枚 HS256 JWT（标准库，不引入 PyJWT）。"""
    now = int(time.time())
    payload: dict[str, object] = {"sub": sub, "iat": now}
    if typ is not None:
        payload["typ"] = typ
    if include_exp:
        payload["exp"] = now + 3600 if exp is None else exp
    if issuer is not None:
        payload["iss"] = issuer
    if audience is not None:
        payload["aud"] = audience
    header = {"alg": alg, "typ": "JWT"}
    signing_input = ".".join(
        _b64(json.dumps(part, separators=(",", ":"), sort_keys=True).encode())
        for part in (header, payload)
    )
    signature = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return f"{signing_input}.{_b64(signature)}"


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _cloud_settings(settings: Settings, **updates: object) -> Settings:
    base = {
        "auth_token": LOCAL_TOKEN,
        "cloud_secret_key": SECRET,
        "cloud_allowed_users": "allowed-user",
        "cloud_base_url": "https://cloud.invalid",
    }
    base.update(updates)
    return settings.model_copy(update=base)


@pytest.fixture()
def cloud_settings(settings: Settings) -> Settings:
    return _cloud_settings(settings)


@pytest.fixture()
def cloud_client(cloud_settings: Settings, db_schema: None) -> Iterator[TestClient]:
    app = create_app(cloud_settings)
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        yield test_client


# ---------------------------------------------------------------------------
# P0-1：云令牌必须属于本实例
# ---------------------------------------------------------------------------


def test_allowed_cloud_user_passes_rest(cloud_client: TestClient) -> None:
    response = cloud_client.get(HEALTH, headers=_auth(make_token(sub="allowed-user")))
    assert response.status_code == 200


def test_cloud_user_outside_allowlist_is_rejected(cloud_client: TestClient) -> None:
    """签名正确但 sub 不在允许名单、也不是绑定主人：不能拿到本机 shell。"""
    response = cloud_client.get(HEALTH, headers=_auth(make_token(sub="attacker")))
    assert response.status_code == 401
    assert response.json()["code"] == "unauthenticated"


def test_cloud_token_without_exp_is_rejected(cloud_client: TestClient) -> None:
    response = cloud_client.get(HEALTH, headers=_auth(make_token(include_exp=False)))
    assert response.status_code == 401


def test_cloud_token_with_wrong_typ_is_rejected(cloud_client: TestClient) -> None:
    response = cloud_client.get(HEALTH, headers=_auth(make_token(typ="refresh")))
    assert response.status_code == 401


def test_cloud_token_expired_is_rejected(cloud_client: TestClient) -> None:
    response = cloud_client.get(HEALTH, headers=_auth(make_token(exp=time.time() - 60)))
    assert response.status_code == 401


def test_cloud_token_with_bad_signature_is_rejected(cloud_client: TestClient) -> None:
    response = cloud_client.get(
        HEALTH, headers=_auth(make_token(secret="wrong-secret-not-the-shared-one"))
    )
    assert response.status_code == 401


def test_cloud_token_with_empty_sub_is_rejected(cloud_client: TestClient) -> None:
    response = cloud_client.get(HEALTH, headers=_auth(make_token(sub="")))
    assert response.status_code == 401


def test_bound_owner_passes_without_allowlist(settings: Settings, db_schema: None) -> None:
    """没配 FLUX_CLOUD_ALLOWED_USERS：只有本机绑定主人的 sub 能授权。"""
    configured = _cloud_settings(settings, cloud_allowed_users="")
    save_owner(configured.cloud_owner_path, CloudOwner(user_id="owner-user", username="Owner"))
    app = create_app(configured)
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        assert (
            test_client.get(HEALTH, headers=_auth(make_token(sub="owner-user"))).status_code == 200
        )
        assert (
            test_client.get(HEALTH, headers=_auth(make_token(sub="other-user"))).status_code == 401
        )


def test_bound_owner_passes_even_when_allowlist_configured(
    settings: Settings, db_schema: None
) -> None:
    """允许名单与绑定主人是"或"：本机绑定过的主人不会被名单遗漏而锁在门外。"""
    configured = _cloud_settings(settings)
    save_owner(configured.cloud_owner_path, CloudOwner(user_id="owner-user", username="Owner"))
    app = create_app(configured)
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        assert (
            test_client.get(HEALTH, headers=_auth(make_token(sub="owner-user"))).status_code == 200
        )
        assert test_client.get(HEALTH, headers=_auth(make_token(sub="attacker"))).status_code == 401


def test_issuer_and_audience_are_enforced_when_configured(
    settings: Settings, db_schema: None
) -> None:
    configured = _cloud_settings(settings, cloud_issuer="flux-cloud", cloud_audience="flux-app")
    app = create_app(configured)
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        good = make_token(issuer="flux-cloud", audience="flux-app")
        assert test_client.get(HEALTH, headers=_auth(good)).status_code == 200
        wrong_iss = make_token(issuer="evil-cloud", audience="flux-app")
        assert test_client.get(HEALTH, headers=_auth(wrong_iss)).status_code == 401
        wrong_aud = make_token(issuer="flux-cloud", audience="another-app")
        assert test_client.get(HEALTH, headers=_auth(wrong_aud)).status_code == 401


# ---------------------------------------------------------------------------
# P0-2：服务器单例会话 / 主人绑定只能由本机主人改写
# ---------------------------------------------------------------------------


def _session_for(user_id: str = "allowed-user", username: str = "") -> CloudSession:
    return CloudSession(
        access_token=make_token(sub=user_id),
        refresh_token="unit-test-refresh-token-value",
        expires_at=time.time() + 1800,
        user_id=user_id,
        username=username,
    )


def _mock_exchange(monkeypatch: pytest.MonkeyPatch, session: CloudSession) -> None:
    async def fake_exchange(self: CloudClient, code: str) -> CloudSession:  # noqa: ARG001
        return session

    monkeypatch.setattr(CloudClient, "exchange_code", fake_exchange)


def _mock_refresh(monkeypatch: pytest.MonkeyPatch, session: CloudSession) -> None:
    async def fake_refresh(self: CloudClient, refresh_token: str) -> CloudSession:  # noqa: ARG001
        return session

    monkeypatch.setattr(CloudClient, "refresh", fake_refresh)


def test_public_exchange_only_sets_client_cookies(
    settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """公网（非回环、无本机令牌）兑换登录码：只回自己的 cookie，不写服务器单例。"""
    configured = _cloud_settings(settings)
    app = create_app(configured)
    _mock_exchange(monkeypatch, _session_for("attacker", "Mallory"))
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        response = test_client.post("/api/v1/cloud/exchange", json={"code": "code-12345678"})
        assert response.status_code == 200, response.text
        assert response.json()["data"]["access_token"]
        assert "flux_auth_token" in response.headers.get("set-cookie", "")
        assert not configured.cloud_session_path.exists()
        assert not configured.cloud_owner_path.exists()


def test_loopback_exchange_binds_server_owner(
    settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """同机直连（回环、无转发头）兑换：可以写服务器单例并绑定主人。"""
    configured = _cloud_settings(settings)
    app = create_app(configured)
    _mock_exchange(monkeypatch, _session_for("allowed-user", "Owner"))
    with TestClient(app, **LOOPBACK_CLIENT) as test_client:
        response = test_client.post("/api/v1/cloud/exchange", json={"code": "code-12345678"})
        assert response.status_code == 200, response.text
        assert configured.cloud_session_path.exists()
        owner = load_owner(configured.cloud_owner_path)
        assert owner is not None and owner.user_id == "allowed-user"


def test_forwarded_loopback_exchange_does_not_bind_server_owner(
    settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """回环来源但带转发头 = 经代理的公网请求，不能借"看起来像本机"写服务器单例。"""
    configured = _cloud_settings(settings)
    app = create_app(configured)
    _mock_exchange(monkeypatch, _session_for("attacker", "Mallory"))
    with TestClient(app, **LOOPBACK_CLIENT) as test_client:
        response = test_client.post(
            "/api/v1/cloud/exchange",
            json={"code": "code-12345678"},
            headers={"X-Forwarded-For": "203.0.113.9"},
        )
        assert response.status_code == 200, response.text
        assert not configured.cloud_session_path.exists()
        assert not configured.cloud_owner_path.exists()


def test_public_with_local_token_exchange_binds_server_owner(
    settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """非回环但带有效 FLUX_AUTH_TOKEN，同样视为本机主人。"""
    configured = _cloud_settings(settings)
    app = create_app(configured)
    _mock_exchange(monkeypatch, _session_for("allowed-user", "Owner"))
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        response = test_client.post(
            "/api/v1/cloud/exchange",
            json={"code": "code-12345678"},
            headers=_auth(LOCAL_TOKEN),
        )
        assert response.status_code == 200, response.text
        assert configured.cloud_session_path.exists()
        assert load_owner(configured.cloud_owner_path) is not None


def test_public_logout_cannot_clear_server_session(settings: Settings, db_schema: None) -> None:
    """未鉴权的公网退出只能清自己的 cookie，不能清服务器单例 / 解绑主人。"""
    configured = _cloud_settings(settings)
    save_session(configured.cloud_session_path, _session_for("allowed-user"))
    save_owner(configured.cloud_owner_path, CloudOwner(user_id="allowed-user", username="Owner"))
    app = create_app(configured)
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        response = test_client.post("/api/v1/cloud/logout")
        assert response.status_code == 200
        assert configured.cloud_session_path.exists()
        assert load_owner(configured.cloud_owner_path) is not None
        # 客户端 cookie 仍要被清掉。
        assert "flux_auth_token" in response.headers.get("set-cookie", "")


def test_loopback_logout_clears_server_session(settings: Settings, db_schema: None) -> None:
    configured = _cloud_settings(settings)
    save_session(configured.cloud_session_path, _session_for("allowed-user"))
    save_owner(configured.cloud_owner_path, CloudOwner(user_id="allowed-user", username="Owner"))
    app = create_app(configured)
    with TestClient(app, **LOOPBACK_CLIENT) as test_client:
        response = test_client.post("/api/v1/cloud/logout")
        assert response.status_code == 200
        assert not configured.cloud_session_path.exists()
        assert not configured.cloud_owner_path.exists()


def test_session_does_not_leak_server_owner(settings: Settings, db_schema: None) -> None:
    """GET /session 不得把服务器单例里的主人身份透露给没带对应用户令牌的请求。"""
    configured = _cloud_settings(settings)
    save_session(configured.cloud_session_path, _session_for("owner-user", "Alice"))
    save_owner(configured.cloud_owner_path, CloudOwner(user_id="owner-user", username="Alice"))
    app = create_app(configured)
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        response = test_client.get("/api/v1/cloud/session")
        assert response.status_code == 200
        payload = response.json()["data"]
        assert payload["authenticated"] is False
        assert payload["user"] is None
        assert "Alice" not in response.text
        assert "owner-user" not in response.text


def test_session_reports_presented_allowed_token_not_server_owner(
    settings: Settings, db_schema: None
) -> None:
    """客户端出示自己的云令牌时只回报它自己的身份，不夹带服务器单例主人。"""
    configured = _cloud_settings(settings)
    save_session(configured.cloud_session_path, _session_for("owner-user", "Alice"))
    save_owner(configured.cloud_owner_path, CloudOwner(user_id="owner-user", username="Alice"))
    app = create_app(configured)
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        response = test_client.get(
            "/api/v1/cloud/session", headers=_auth(make_token(sub="allowed-user"))
        )
        assert response.status_code == 200
        payload = response.json()["data"]
        assert payload["authenticated"] is True
        assert payload["user"] == {"id": "allowed-user", "username": ""}
        assert "Alice" not in response.text


def test_public_refresh_uses_cookie_without_writing_server_session(
    settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Web 续期只靠 HttpOnly refresh cookie；公网续期不写服务器单例。"""
    configured = _cloud_settings(settings)
    app = create_app(configured)
    _mock_refresh(monkeypatch, _session_for("allowed-user"))
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        test_client.cookies.set(
            "flux_refresh_token", "unit-test-refresh-token-value", path="/api/v1/cloud"
        )
        response = test_client.post("/api/v1/cloud/refresh")
        assert response.status_code == 200, response.text
        assert response.json()["data"]["authenticated"] is True
        assert "flux_auth_token" in response.headers.get("set-cookie", "")
        assert not configured.cloud_session_path.exists()
        assert not configured.cloud_owner_path.exists()


# ---------------------------------------------------------------------------
# P1-2：WebSocket 与 REST 共用云令牌绑定判断
# ---------------------------------------------------------------------------


@pytest.fixture()
def ws_cloud_settings(apply_settings: Settings) -> Settings:
    return _cloud_settings(apply_settings)


@pytest.mark.skipif(os.name == "nt", reason="native PTY is not enabled on Windows")
def test_websocket_accepts_allowed_cloud_token_header(
    ws_cloud_settings: Settings, db_schema: None
) -> None:
    app = create_app(ws_cloud_settings)
    token = make_token(sub="allowed-user")
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        created = test_client.post("/api/v1/terminal/pty/sessions", headers=_auth(token))
        assert created.status_code == 200, created.text
        session_id = created.json()["data"]["id"]
        with test_client.websocket_connect(
            f"/api/v1/terminal/pty/sessions/{session_id}/ws", headers=_auth(token)
        ) as websocket:
            websocket.send_json({"type": "stop", "force": True})


@pytest.mark.skipif(os.name == "nt", reason="native PTY is not enabled on Windows")
def test_websocket_accepts_allowed_cloud_token_cookie(
    ws_cloud_settings: Settings, db_schema: None
) -> None:
    app = create_app(ws_cloud_settings)
    token = make_token(sub="allowed-user")
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        created = test_client.post("/api/v1/terminal/pty/sessions", headers=_auth(token))
        assert created.status_code == 200, created.text
        session_id = created.json()["data"]["id"]
        with test_client.websocket_connect(
            f"/api/v1/terminal/pty/sessions/{session_id}/ws",
            headers={"Cookie": f"flux_auth_token={token}"},
        ) as websocket:
            websocket.send_json({"type": "stop", "force": True})


@pytest.mark.skipif(os.name == "nt", reason="native PTY is not enabled on Windows")
def test_websocket_rejects_cloud_token_outside_instance(
    ws_cloud_settings: Settings, db_schema: None
) -> None:
    app = create_app(ws_cloud_settings)
    allowed = make_token(sub="allowed-user")
    attacker = make_token(sub="attacker")
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        created = test_client.post("/api/v1/terminal/pty/sessions", headers=_auth(allowed))
        assert created.status_code == 200, created.text
        session_id = created.json()["data"]["id"]
        path = f"/api/v1/terminal/pty/sessions/{session_id}/ws"
        with (
            pytest.raises(WebSocketDisconnect) as exc,
            test_client.websocket_connect(path, headers=_auth(attacker)),
        ):
            pass
        assert exc.value.code == 1008
        # 签名对但没有 exp 的令牌同样不能进。
        no_exp = make_token(sub="allowed-user", include_exp=False)
        with (
            pytest.raises(WebSocketDisconnect) as exc2,
            test_client.websocket_connect(path, headers=_auth(no_exp)),
        ):
            pass
        assert exc2.value.code == 1008

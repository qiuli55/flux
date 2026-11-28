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
    username: str | None = None,
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
    if username is not None:
        payload["username"] = username
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


def test_public_exchange_allowed_user_only_sets_client_cookies(
    settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """公网（非回环、无本机令牌）兑换：允许名单用户只回自己的 cookie，不写服务器单例。"""
    configured = _cloud_settings(settings)
    app = create_app(configured)
    _mock_exchange(monkeypatch, _session_for("allowed-user", "Allowed"))
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        response = test_client.post("/api/v1/cloud/exchange", json={"code": "code-12345678"})
        assert response.status_code == 200, response.text
        assert response.json()["data"]["access_token"]
        assert "flux_auth_token" in response.headers.get("set-cookie", "")
        assert not configured.cloud_session_path.exists()
        assert not configured.cloud_owner_path.exists()


def test_public_exchange_outside_instance_is_rejected_without_cookie(
    settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """验签通过但用户不属于本实例：403 + 中文说明，且不种登录 cookie。"""
    configured = _cloud_settings(settings)
    app = create_app(configured)
    _mock_exchange(monkeypatch, _session_for("attacker", "Mallory"))
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        response = test_client.post("/api/v1/cloud/exchange", json={"code": "code-12345678"})
        assert response.status_code == 403, response.text
        payload = response.json()
        assert payload["code"] == "permission_denied"
        assert "不是本实例允许的用户" in payload["message"]
        assert "FLUX_CLOUD_ALLOWED_USERS" in payload["message"]
        # 关键：不能"假装登录成功"给浏览器种下可登录 cookie。
        assert "flux_auth_token=" not in response.headers.get("set-cookie", "")
        assert not configured.cloud_session_path.exists()
        assert not configured.cloud_owner_path.exists()


def test_public_exchange_without_owner_or_allowlist_is_rejected(
    settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """既没允许名单也没绑定主人：公网官方 Web 兑换必须失败并提示先绑定主人。"""
    configured = _cloud_settings(settings, cloud_allowed_users="")
    app = create_app(configured)
    _mock_exchange(monkeypatch, _session_for("any-user", "Anyone"))
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        response = test_client.post("/api/v1/cloud/exchange", json={"code": "code-12345678"})
        assert response.status_code == 403, response.text
        payload = response.json()
        assert payload["code"] == "permission_denied"
        assert "还没有绑定主人" in payload["message"]
        assert "flux_auth_token=" not in response.headers.get("set-cookie", "")


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
    _mock_exchange(monkeypatch, _session_for("allowed-user", "Allowed"))
    with TestClient(app, **LOOPBACK_CLIENT) as test_client:
        response = test_client.post(
            "/api/v1/cloud/exchange",
            json={"code": "code-12345678"},
            headers={"X-Forwarded-For": "203.0.113.9"},
        )
        assert response.status_code == 200, response.text
        assert "flux_auth_token" in response.headers.get("set-cookie", "")
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


def test_loopback_logout_only_clears_client_cookie(settings: Settings, db_schema: None) -> None:
    """同机直连的普通「退出登录」只清 cookie：服务器会话与主人绑定必须保留。"""
    configured = _cloud_settings(settings)
    save_session(configured.cloud_session_path, _session_for("allowed-user"))
    save_owner(configured.cloud_owner_path, CloudOwner(user_id="allowed-user", username="Owner"))
    app = create_app(configured)
    with TestClient(app, **LOOPBACK_CLIENT) as test_client:
        response = test_client.post("/api/v1/cloud/logout")
        assert response.status_code == 200
        assert "flux_auth_token" in response.headers.get("set-cookie", "")
        # 关键行为变更：不再因为"是本机主人"就解绑整台机器。
        assert configured.cloud_session_path.exists()
        owner = load_owner(configured.cloud_owner_path)
        assert owner is not None and owner.user_id == "allowed-user"


def test_loopback_unbind_clears_server_session_and_owner(
    settings: Settings, db_schema: None
) -> None:
    """专用解绑端点：本机主人调用才清除服务器单例并解绑主人。"""
    configured = _cloud_settings(settings)
    save_session(configured.cloud_session_path, _session_for("allowed-user"))
    save_owner(configured.cloud_owner_path, CloudOwner(user_id="allowed-user", username="Owner"))
    app = create_app(configured)
    with TestClient(app, **LOOPBACK_CLIENT) as test_client:
        response = test_client.post("/api/v1/cloud/unbind")
        assert response.status_code == 200, response.text
        assert not configured.cloud_session_path.exists()
        assert not configured.cloud_owner_path.exists()


def test_public_unbind_cannot_clear_server_session(settings: Settings, db_schema: None) -> None:
    """未鉴权的公网请求调用解绑端点：401，且服务器会话 / 主人绑定保持原样。"""
    configured = _cloud_settings(settings)
    save_session(configured.cloud_session_path, _session_for("allowed-user"))
    save_owner(configured.cloud_owner_path, CloudOwner(user_id="allowed-user", username="Owner"))
    app = create_app(configured)
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        response = test_client.post("/api/v1/cloud/unbind")
        assert response.status_code == 401, response.text
        assert response.json()["code"] == "unauthenticated"
        assert configured.cloud_session_path.exists()
        owner = load_owner(configured.cloud_owner_path)
        assert owner is not None and owner.user_id == "allowed-user"


def test_public_unbind_with_local_token_succeeds(settings: Settings, db_schema: None) -> None:
    """非回环但带有效 FLUX_AUTH_TOKEN，同样视为本机主人，可以解绑。"""
    configured = _cloud_settings(settings)
    save_session(configured.cloud_session_path, _session_for("allowed-user"))
    save_owner(configured.cloud_owner_path, CloudOwner(user_id="allowed-user", username="Owner"))
    app = create_app(configured)
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        response = test_client.post("/api/v1/cloud/unbind", headers=_auth(LOCAL_TOKEN))
        assert response.status_code == 200, response.text
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


def test_public_refresh_outside_instance_is_rejected_without_cookie(
    settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """续期回来的用户不属于本实例：与 exchange 对称，403 且不种登录 cookie。"""
    configured = _cloud_settings(settings)
    app = create_app(configured)
    _mock_refresh(monkeypatch, _session_for("attacker", "Mallory"))
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        test_client.cookies.set(
            "flux_refresh_token", "unit-test-refresh-token-value", path="/api/v1/cloud"
        )
        response = test_client.post("/api/v1/cloud/refresh")
        assert response.status_code == 403, response.text
        assert response.json()["code"] == "permission_denied"
        assert "flux_auth_token=" not in response.headers.get("set-cookie", "")
        assert not configured.cloud_session_path.exists()


def test_session_uses_username_claim_for_allowed_user(settings: Settings, db_schema: None) -> None:
    """允许名单用户没有主人文件可查时，用 JWT 里的 username claim 回填展示名。"""
    configured = _cloud_settings(settings)
    save_owner(
        configured.cloud_owner_path,
        CloudOwner(user_id="owner-user", username="ServerOwner"),
    )
    app = create_app(configured)
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        response = test_client.get(
            "/api/v1/cloud/session",
            headers=_auth(make_token(sub="allowed-user", username="AllowedName")),
        )
        assert response.status_code == 200
        payload = response.json()["data"]
        assert payload["authenticated"] is True
        assert payload["user"] == {"id": "allowed-user", "username": "AllowedName"}
        # 不能把服务器单例里的主人 username 泄露给别的用户。
        assert "ServerOwner" not in response.text


def test_refresh_response_reports_session_username(
    settings: Settings, db_schema: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    """允许名单用户的展示名也可来自 CloudSession.username（账号云返回值）。"""
    configured = _cloud_settings(settings)
    app = create_app(configured)
    _mock_refresh(monkeypatch, _session_for("allowed-user", "AllowedName"))
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        test_client.cookies.set(
            "flux_refresh_token", "unit-test-refresh-token-value", path="/api/v1/cloud"
        )
        response = test_client.post("/api/v1/cloud/refresh")
        assert response.status_code == 200, response.text
        user = response.json()["data"]["user"]
        assert user == {"id": "allowed-user", "username": "AllowedName"}


# ---------------------------------------------------------------------------
# P1：升级时**不得**把遗留 cloud.json 会话自动升为本机主人
# ---------------------------------------------------------------------------
#
# 历史事实：#41 修复**之前**，未鉴权的公网 `/cloud/exchange` 等就能把任意账号写进
# 服务器单例 `~/.flux/cloud.json`。因此从旧版本升级时，这份残留会话不能证明它属于
# 本机主人；启动期自动 `save_owner` 会把当时写进该文件的账号（可能是攻击者）升成
# 持久主人。绑定只应发生在本机主人鉴权之后（回环直连或有效 FLUX_AUTH_TOKEN）。


def test_create_app_does_not_promote_legacy_session_to_owner(
    settings: Settings, db_schema: None
) -> None:
    """启动时存在遗留会话、尚无主人文件：不得写主人文件，也不能因此放行。"""
    configured = _cloud_settings(settings, cloud_allowed_users="")
    save_session(configured.cloud_session_path, _session_for("legacy-user", "Legacy"))
    assert load_owner(configured.cloud_owner_path) is None

    app = create_app(configured)

    # 关键：启动期不写主人文件；残留会话仍留在磁盘上，但它不构成授权。
    assert load_owner(configured.cloud_owner_path) is None
    assert configured.cloud_session_path.exists()
    with TestClient(app, **PUBLIC_CLIENT) as test_client:
        response = test_client.get(HEALTH, headers=_auth(make_token(sub="legacy-user")))
        assert response.status_code == 401


def test_create_app_does_not_override_existing_owner(settings: Settings, db_schema: None) -> None:
    """已有合法主人时，遗留会话不参与启动流程，不会被升主或覆盖。"""
    configured = _cloud_settings(settings)
    save_session(configured.cloud_session_path, _session_for("legacy-user", "Legacy"))
    save_owner(
        configured.cloud_owner_path,
        CloudOwner(user_id="owner-user", username="Owner"),
    )
    create_app(configured)
    owner = load_owner(configured.cloud_owner_path)
    assert owner is not None and owner.user_id == "owner-user"


def test_create_app_does_not_bind_session_without_user_id(
    settings: Settings, db_schema: None
) -> None:
    """遗留会话没有 user_id 时不产生空绑定（fail-closed）。"""
    configured = _cloud_settings(settings, cloud_allowed_users="")
    save_session(configured.cloud_session_path, _session_for("", ""))
    create_app(configured)
    assert load_owner(configured.cloud_owner_path) is None


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

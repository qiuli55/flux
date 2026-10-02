"""Flux MCP 能力面测试（目标架构 §3.2 / §3.3 / §3.5）。

覆盖三条硬判据：
1. `tools/list` 恰好 4 个工具，且硬禁令工具（apply / git.push / secret / shell）不在面上；
2. 鉴权 fail-closed——无令牌、坏令牌、已撤销令牌一律 401，连 initialize 都进不来；
3. 越权不静默降级——能力不足是显式失败（isError），并留下 mcp.tool_denied 事件。

这里的令牌全部走真实签发路径（`/api/v1/agents/{id}/tokens`），不直接往库里塞哈希——
否则测的是测试自己造的假数据，而不是真实的签发/校验链路。
"""

from __future__ import annotations

import json

from fastapi.testclient import TestClient

from flux.core.mcp.server import DEFAULT_PROTOCOL_VERSION
from flux.core.mcp.tools import ALL_TOOLS, FORBIDDEN_TOOL_NAMES
from flux.enums import Capability
from flux.errors import AuthenticationError

from .conftest import create_agent, issue_token

PROPOSAL = {
    "summary": "修复待办服务的两处缺陷",
    "changes": [
        {
            "path": "todo_service/store.py",
            "content": "def list_todos():\n    return []\n",
            "reason": "按完成状态过滤",
        }
    ],
}


def rpc(client: TestClient, token: str | None, method: str, params: dict | None = None):
    body: dict = {"jsonrpc": "2.0", "id": 1, "method": method}
    if params is not None:
        body["params"] = params
    headers = {"Authorization": f"Bearer {token}"} if token is not None else {}
    return client.post("/mcp", json=body, headers=headers)


def call(client: TestClient, token: str, name: str, arguments: dict):
    response = rpc(client, token, "tools/call", {"name": name, "arguments": arguments})
    assert response.status_code == 200, response.text
    return response.json()["result"]


def payload(result: dict) -> dict:
    assert result["isError"] is False, result["content"][0]["text"]
    return json.loads(result["content"][0]["text"])


# --- 1. 工具面 ---


def test_tools_list_exposes_exactly_the_four_phase2_tools(mcp_client: TestClient) -> None:
    token = issue_token(mcp_client)
    result = rpc(mcp_client, token, "tools/list").json()["result"]
    names = sorted(tool["name"] for tool in result["tools"])
    assert names == ["context.get", "proposal.create", "workspace.diff", "workspace.read"]
    for tool in result["tools"]:
        assert tool["description"] and tool["inputSchema"]["type"] == "object"


def test_forbidden_capabilities_are_absent_from_the_surface(mcp_client: TestClient) -> None:
    """§3.5 硬禁令：apply / git.push / secret / shell 不是"忘了注册"，是没有开关能打开。"""
    listed = {tool.name for tool in ALL_TOOLS}
    assert listed & FORBIDDEN_TOOL_NAMES == set()
    token = issue_token(
        mcp_client, scopes=[c for c in Capability if c is not Capability.SECRET_ACCESS]
    )
    result = rpc(mcp_client, token, "tools/list").json()["result"]
    assert {tool["name"] for tool in result["tools"]} & FORBIDDEN_TOOL_NAMES == set()


def test_every_tool_declares_a_capability_and_a_handler() -> None:
    for tool in ALL_TOOLS:
        assert isinstance(tool.capability, Capability)
        assert callable(tool.handler)


# --- 2. 鉴权 fail-closed ---


def test_every_method_requires_a_token(mcp_client: TestClient) -> None:
    for method in ("initialize", "tools/list", "ping"):
        response = rpc(mcp_client, None, method, {})
        assert response.status_code == 401, method
        assert response.headers["www-authenticate"] == "Bearer"
        assert response.json()["code"] == AuthenticationError.code


def test_malformed_and_unknown_tokens_are_rejected(mcp_client: TestClient) -> None:
    assert rpc(mcp_client, "not-a-token", "tools/list").status_code == 401
    assert rpc(mcp_client, "fxt_" + "0" * 64, "tools/list").status_code == 401


def test_revoked_token_stops_working_on_the_next_request(mcp_client: TestClient) -> None:
    codex_id = create_agent(mcp_client, name="codex")
    token = issue_token(mcp_client, agent_id="codex")
    assert rpc(mcp_client, token, "tools/list").status_code == 200

    listed = mcp_client.get(f"/api/v1/agents/{codex_id}/tokens").json()["data"]
    assert (
        mcp_client.delete(f"/api/v1/agents/{codex_id}/tokens/{listed[0]['id']}").status_code == 200
    )

    response = rpc(mcp_client, token, "tools/list")
    assert response.status_code == 401
    assert "无效或已撤销" in response.json()["message"]


def test_token_endpoints_never_expose_the_hash(mcp_client: TestClient) -> None:
    codex_id = create_agent(mcp_client, name="codex")
    issued = mcp_client.post(
        f"/api/v1/agents/{codex_id}/tokens", json={"scopes": ["file.read"], "label": "本地调试"}
    ).json()["data"]
    assert issued["token"].startswith("fxt_")
    assert "token_hash" not in issued
    listed = mcp_client.get(f"/api/v1/agents/{codex_id}/tokens").json()["data"][0]
    assert "token_hash" not in listed and "token" not in listed
    assert listed["label"] == "本地调试" and listed["revoked"] is False


def test_secret_scope_can_never_be_granted(mcp_client: TestClient) -> None:
    codex_id = create_agent(mcp_client, name="codex")
    response = mcp_client.post(
        f"/api/v1/agents/{codex_id}/tokens", json={"scopes": ["secret.access"]}
    )
    assert response.status_code == 422
    assert "不允许授予" in response.json()["message"]


def test_unknown_scope_is_rejected(mcp_client: TestClient) -> None:
    """未知能力在入参校验层就被挡下（pydantic + service 两道都不放行）。"""
    codex_id = create_agent(mcp_client, name="codex")
    response = mcp_client.post(
        f"/api/v1/agents/{codex_id}/tokens", json={"scopes": ["file.destroy"]}
    )
    assert response.status_code == 422
    assert response.json()["code"] == "validation_error"


# --- 3. 协议层 ---


def test_initialize_echoes_supported_version_and_falls_back(mcp_client: TestClient) -> None:
    token = issue_token(mcp_client)
    result = rpc(mcp_client, token, "initialize", {"protocolVersion": "2025-03-26"}).json()[
        "result"
    ]
    assert result["protocolVersion"] == "2025-03-26"
    assert result["serverInfo"]["name"] == "flux"
    assert result["capabilities"]["tools"]["listChanged"] is False

    fresh = rpc(mcp_client, token, "initialize", {"protocolVersion": "1999-01-01"}).json()["result"]
    assert fresh["protocolVersion"] == DEFAULT_PROTOCOL_VERSION


def test_notification_gets_no_body(mcp_client: TestClient) -> None:
    token = issue_token(mcp_client)
    response = mcp_client.post(
        "/mcp",
        json={"jsonrpc": "2.0", "method": "notifications/initialized"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 202
    assert response.content == b""


def test_protocol_errors_for_unknown_method_tool_and_batch(mcp_client: TestClient) -> None:
    token = issue_token(mcp_client)
    unknown = rpc(mcp_client, token, "tools/apply_everything").json()["error"]
    assert unknown["code"] == -32601

    missing = rpc(mcp_client, token, "tools/call", {"name": "workspace.apply", "arguments": {}})
    assert missing.json()["error"]["code"] == -32602
    assert "未知工具" in missing.json()["error"]["message"]

    batch = mcp_client.post(
        "/mcp",
        json=[{"jsonrpc": "2.0", "id": 1, "method": "ping"}],
        headers={"Authorization": f"Bearer {token}"},
    )
    assert batch.json()["error"]["code"] == -32600

    broken = mcp_client.post(
        "/mcp", content=b"{not json", headers={"Authorization": f"Bearer {token}"}
    )
    assert broken.json()["error"]["code"] == -32700


def test_unknown_argument_is_rejected_instead_of_ignored(mcp_client: TestClient) -> None:
    token = issue_token(mcp_client, scopes=[Capability.FILE_READ])
    result = call(mcp_client, token, "workspace.read", {"path": "a.py", "sudo": True})
    assert result["isError"] is True
    assert "未定义的入参" in result["content"][0]["text"]


# --- 4. 只读工具 ---


def test_workspace_read_returns_content_and_hash(mcp_client: TestClient, workspace_root) -> None:
    (workspace_root / "hello.py").write_text("print('hi')\n", encoding="utf-8")
    token = issue_token(mcp_client, scopes=[Capability.FILE_READ])
    data = payload(call(mcp_client, token, "workspace.read", {"path": "hello.py"}))
    assert data["content"] == "print('hi')\n"
    assert data["truncated"] is False and data["size"] == len("print('hi')\n")
    # sha256 必须与文件真实内容一致，否则人审时拿它核验就是个摆设
    import hashlib

    assert data["sha256"] == hashlib.sha256(b"print('hi')\n").hexdigest()


def test_workspace_read_refuses_to_escape_the_root(mcp_client: TestClient, workspace_root) -> None:
    token = issue_token(mcp_client, scopes=[Capability.FILE_READ])
    for path in ("../outside.py", "/etc/passwd"):
        result = call(mcp_client, token, "workspace.read", {"path": path})
        assert result["isError"] is True, path


def test_context_get_packages_task_and_brain(mcp_client: TestClient) -> None:
    project = mcp_client.post(
        "/api/v1/projects", json={"name": "待办服务", "repository": "/opt/flux/mcp-demo-task"}
    ).json()["data"]
    mcp_client.post(
        f"/api/v1/projects/{project['id']}/memory",
        json={"section": "coding_rules", "content": "测试命令固定为 python3 -m pytest"},
    )
    task = mcp_client.post(
        "/api/v1/tasks",
        json={
            "description": "修掉 store.list 与 store.remove 的两处缺陷",
            "project_id": project["id"],
        },
    ).json()["data"]

    token = issue_token(mcp_client, scopes=[Capability.FILE_READ])
    data = payload(call(mcp_client, token, "context.get", {"task_id": task["id"]}))

    assert data["task_id"] == task["id"] and data["project_id"] == project["id"]
    assert "修掉 store.list" in data["content"]
    assert "python3 -m pytest" in data["content"]
    assert data["used"] <= data["budget"]
    assert [entry["kind"] for entry in data["entries"]] == ["task", "brain"]
    assert data["dropped_count"] == 0


def test_context_get_degrades_to_reference_when_budget_is_tight(mcp_client: TestClient) -> None:
    mcp_client.post("/api/v1/projects", json={"name": "预算紧张的项目", "repository": None}).json()[
        "data"
    ]
    token = issue_token(mcp_client, scopes=[Capability.FILE_READ])
    data = payload(call(mcp_client, token, "context.get", {"budget": 32}))
    assert data["used"] <= 32


def test_context_get_rejects_bad_uuid(mcp_client: TestClient) -> None:
    token = issue_token(mcp_client, scopes=[Capability.FILE_READ])
    result = call(mcp_client, token, "context.get", {"task_id": "12345"})
    assert result["isError"] is True
    assert "必须是合法 UUID" in result["content"][0]["text"]


# --- 5. 写入工具：proposal.create ---


def test_proposal_create_enqueues_for_review_and_stamps_the_agent(
    mcp_client: TestClient, workspace_root
) -> None:
    (workspace_root / "todo_service").mkdir()
    (workspace_root / "todo_service" / "store.py").write_text(
        "def list_todos():\n    return None\n", encoding="utf-8"
    )
    codex_id = create_agent(mcp_client, name="codex-minimax")
    token = issue_token(
        mcp_client, agent_id="codex-minimax", scopes=[Capability.FILE_READ, Capability.FILE_WRITE]
    )

    data = payload(call(mcp_client, token, "proposal.create", {"payload": PROPOSAL}))
    assert data["count"] == 1
    # P3-16：provenance 落的是 canonical UUID，名字只是 display_name
    assert data["agent"] == codex_id
    change = data["changes"][0]
    assert change["file_path"] == "todo_service/store.py"
    assert change["status"] == "pending"
    assert (change["added_lines"], change["removed_lines"], change["hunks"]) == (1, 1, 1)

    # 人审队列里能查到，且 provider 端盖章的 agent 与 provenance 一致
    stored = mcp_client.get(f"/api/v1/workspace/changes/{change['change_id']}").json()["data"]
    assert stored["agent_source"] == codex_id
    assert stored["original_content"] == "def list_todos():\n    return None\n"
    # 提案绝不落盘（§3.5）：磁盘上还是旧内容
    assert (workspace_root / "todo_service" / "store.py").read_text() == (
        "def list_todos():\n    return None\n"
    )

    diff = payload(call(mcp_client, token, "workspace.diff", {"change_id": change["change_id"]}))
    assert diff["diff"].startswith("---") and diff["status"] == "pending"


def test_proposal_create_accepts_a_json_string_and_new_files(
    mcp_client: TestClient, workspace_root
) -> None:
    token = issue_token(mcp_client, scopes=[Capability.FILE_READ, Capability.FILE_WRITE])
    raw = json.dumps(
        {
            "summary": "新增模块",
            "changes": [
                {"path": "todo_service/__init__.py", "content": "from .store import TodoStore\n"}
            ],
        },
        ensure_ascii=False,
    )
    data = payload(call(mcp_client, token, "proposal.create", {"payload": raw}))
    assert data["changes"][0]["file_path"] == "todo_service/__init__.py"
    assert data["changes"][0]["added_lines"] == 1


def test_proposal_create_ignores_client_supplied_agent_identity(
    mcp_client: TestClient, workspace_root
) -> None:
    """客户端不能自报身份：payload 里写 agent 也不作数，provenance 只认令牌。"""
    codex_id = create_agent(mcp_client, name="codex")
    token = issue_token(
        mcp_client, agent_id="codex", scopes=[Capability.FILE_READ, Capability.FILE_WRITE]
    )
    spoofed = {**PROPOSAL, "agent": "flux-builtin", "agent_id": "flux-builtin"}
    data = payload(call(mcp_client, token, "proposal.create", {"payload": spoofed}))
    stored = mcp_client.get(f"/api/v1/workspace/changes/{data['changes'][0]['change_id']}").json()[
        "data"
    ]
    assert stored["agent_source"] == codex_id


def test_proposal_create_rejects_declared_agent_that_is_not_the_token_identity(
    mcp_client: TestClient, workspace_root
) -> None:
    """P3-16 §4.4 / TC-16C：请求声明的 agent_id 与令牌不一致 → 直接拒绝，提案不入队。"""
    create_agent(mcp_client, name="agent-a")
    other_id = create_agent(mcp_client, name="agent-b")
    token = issue_token(
        mcp_client, agent_id="agent-a", scopes=[Capability.FILE_READ, Capability.FILE_WRITE]
    )
    result = call(
        mcp_client,
        token,
        "proposal.create",
        {"payload": PROPOSAL, "agent_id": other_id},
    )
    assert result["isError"] is True
    assert "冒充" in result["content"][0]["text"]
    assert mcp_client.get("/api/v1/workspace/changes").json()["data"] == []


def test_proposal_create_rejects_path_escape(mcp_client: TestClient) -> None:
    token = issue_token(mcp_client, scopes=[Capability.FILE_READ, Capability.FILE_WRITE])
    bad = {"summary": "越界", "changes": [{"path": "../outside.py", "content": "x\n"}]}
    result = call(mcp_client, token, "proposal.create", {"payload": bad})
    assert result["isError"] is True
    assert "不得越出项目根" in result["content"][0]["text"]


def test_proposal_create_without_change_is_reported_not_silently_accepted(
    mcp_client: TestClient, workspace_root
) -> None:
    (workspace_root / "same.py").write_text("x = 1\n", encoding="utf-8")
    token = issue_token(mcp_client, scopes=[Capability.FILE_READ, Capability.FILE_WRITE])
    same = {"summary": "无改动", "changes": [{"path": "same.py", "content": "x = 1\n"}]}
    result = call(mcp_client, token, "proposal.create", {"payload": same})
    assert result["isError"] is True
    assert "没有任何有效改动" in result["content"][0]["text"]


# --- 6. 越权：显式失败 + 留痕 ---


def test_read_only_token_cannot_create_proposals_and_leaves_an_event(
    mcp_client: TestClient,
) -> None:
    observer_id = create_agent(mcp_client, name="observer", role="reviewer")
    token = issue_token(mcp_client, agent_id="observer", scopes=[Capability.FILE_READ])
    result = call(mcp_client, token, "proposal.create", {"payload": PROPOSAL})
    assert result["isError"] is True
    assert "权限不足" in result["content"][0]["text"]
    assert "file.write" in result["content"][0]["text"]

    bus = mcp_client.app.state.container.bus
    denied = [payload_ for event, payload_ in bus.history if event == "mcp.tool_denied"]
    assert denied and denied[-1]["agent_id"] == observer_id
    assert denied[-1]["capability"] == "file.write"
    # 被拒的提案没有进审核队列
    assert mcp_client.get("/api/v1/workspace/changes").json()["data"] == []


# --- 7. 密钥与 Flux 内部数据（§3.5 硬禁令 / §7 安全边界）---


def test_workspace_read_never_returns_secret_files(mcp_client: TestClient, workspace_root) -> None:
    """Secret 读取是硬禁令：`.env` / 私钥一律 403，不因为"只是读一下"而放行。"""
    (workspace_root / ".env").write_text("DEEPSEEK_API_KEY=sk-真实密钥\n", encoding="utf-8")
    (workspace_root / "deploy.key").write_text("PRIVATE KEY\n", encoding="utf-8")
    (workspace_root / "tls.pem").write_text("CERT\n", encoding="utf-8")
    secrets_dir = workspace_root / "config"
    secrets_dir.mkdir()
    (secrets_dir / "id_rsa").write_text("ssh-rsa\n", encoding="utf-8")

    token = issue_token(mcp_client, scopes=[Capability.FILE_READ])
    for path in (".env", "deploy.key", "tls.pem", "config/id_rsa"):
        result = call(mcp_client, token, "workspace.read", {"path": path})
        assert result["isError"] is True, path
        text = result["content"][0]["text"]
        assert "permission_denied" in text and "密钥" in text, path


def test_workspace_read_allows_secret_templates(mcp_client: TestClient, workspace_root) -> None:
    """`.env.example` 这类模板没有真实密钥，读得到（否则新人没法照着填）。"""
    (workspace_root / ".env.example").write_text("DEEPSEEK_API_KEY=\n", encoding="utf-8")
    token = issue_token(mcp_client, scopes=[Capability.FILE_READ])
    data = payload(call(mcp_client, token, "workspace.read", {"path": ".env.example"}))
    assert data["content"] == "DEEPSEEK_API_KEY=\n"


def test_workspace_read_refuses_flux_internal_files(mcp_client: TestClient, workspace_root) -> None:
    """`.flux/` 是平台内部目录（备份/锁）：Agent 读不到，也就谈不上拿备份绕过审核。"""
    internal = workspace_root / ".flux" / "backups"
    internal.mkdir(parents=True)
    (internal / "store.py.bak").write_text("旧内容\n", encoding="utf-8")

    token = issue_token(mcp_client, scopes=[Capability.FILE_READ])
    result = call(mcp_client, token, "workspace.read", {"path": ".flux/backups/store.py.bak"})
    assert result["isError"] is True
    assert "validation_error" in result["content"][0]["text"]


def test_proposal_create_refuses_secrets_and_flux_internal_paths(
    mcp_client: TestClient, workspace_root
) -> None:
    """提案同样不得触碰密钥与 `.flux/`：前者的禁令与读取同源，后者是回滚依据。"""
    token = issue_token(mcp_client, scopes=[Capability.FILE_READ, Capability.FILE_WRITE])
    for path, code in ((".env", "permission_denied"), (".flux/fake.bak", "validation_error")):
        bad = {"summary": "越权", "changes": [{"path": path, "content": "x\n"}]}
        result = call(mcp_client, token, "proposal.create", {"payload": bad})
        assert result["isError"] is True, path
        assert code in result["content"][0]["text"], path
    # 被拒的提案一条都不进审核队列
    assert mcp_client.get("/api/v1/workspace/changes").json()["data"] == []


def test_successful_calls_are_recorded_without_arguments(
    mcp_client: TestClient, workspace_root
) -> None:
    """事件里不能出现入参——proposal 入参可能带整份文件内容。"""
    (workspace_root / "a.py").write_text("a = 1\n", encoding="utf-8")
    token = issue_token(mcp_client, scopes=[Capability.FILE_READ])
    call(mcp_client, token, "workspace.read", {"path": "a.py"})
    bus = mcp_client.app.state.container.bus
    recorded = [payload_ for event, payload_ in bus.history if event == "mcp.tool_called"]
    assert recorded[-1]["tool"] == "workspace.read"
    assert "arguments" not in recorded[-1] and "a = 1" not in json.dumps(recorded[-1])


# --- 8. 统一身份：Token 只能绑定真实 Agent（P3-16 §4 / TC-16B、TC-16D）---


def test_token_cannot_be_issued_for_an_unregistered_agent(mcp_client: TestClient) -> None:
    """TC-16B：签发边界就挡下——不存在的 Agent UUID 拿不到令牌，且不为它建档案。"""
    fake_id = "0f5b6f4c-0000-0000-0000-000000000000"
    response = mcp_client.post(f"/api/v1/agents/{fake_id}/tokens", json={"scopes": ["file.read"]})
    assert response.status_code == 404
    assert response.json()["code"] == "not_found"
    assert all(agent["id"] != fake_id for agent in mcp_client.get("/api/v1/agents").json()["data"])


def test_token_bound_to_a_vanished_agent_is_rejected(mcp_client: TestClient) -> None:
    """TC-16B：库里存在但注册表解析不到的令牌，鉴权 fail-closed → 401，绝不降级放行。"""
    import hashlib
    import sqlite3
    import uuid

    raw = "fxt_" + "a" * 64
    db_path = mcp_client.app.state.container.settings.database_url.split("///", 1)[1]
    connection = sqlite3.connect(db_path)
    try:
        connection.execute(
            "INSERT INTO agent_tokens (id, agent_id, label, token_hash, scopes, revoked_at)"
            " VALUES (?, ?, ?, ?, ?, NULL)",
            (
                str(uuid.uuid4()),
                "0f5b6f4c-0000-0000-0000-000000000000",  # 合法 UUID，但注册表里没有
                "野令牌",
                hashlib.sha256(raw.encode()).hexdigest(),
                json.dumps(["file.read"]),
            ),
        )
        connection.commit()
    finally:
        connection.close()

    response = rpc(mcp_client, raw, "tools/list")
    assert response.status_code == 401
    assert "canonical" in response.json()["message"]


def test_proposals_carry_their_own_agent_attribution(
    mcp_client: TestClient, workspace_root
) -> None:
    """TC-16D：两个 Agent 各提一个提案，provenance 各归其主（可追溯到 Agent → Proposal）。"""
    dsh_id = create_agent(mcp_client, name="dsh-agent")
    codex_id = create_agent(mcp_client, name="codex-agent")
    grants = [Capability.FILE_READ, Capability.FILE_WRITE]
    dsh_token = issue_token(mcp_client, agent_id="dsh-agent", scopes=grants)
    codex_token = issue_token(mcp_client, agent_id="codex-agent", scopes=grants)

    first = payload(
        call(
            mcp_client,
            dsh_token,
            "proposal.create",
            {
                "payload": {
                    "summary": "DSH 的提案",
                    "changes": [{"path": "a.py", "content": "a = 1\n"}],
                }
            },
        )
    )
    second = payload(
        call(
            mcp_client,
            codex_token,
            "proposal.create",
            {
                "payload": {
                    "summary": "Codex 的提案",
                    "changes": [{"path": "b.py", "content": "b = 2\n"}],
                }
            },
        )
    )

    def _stored(change_id: str) -> dict:
        return mcp_client.get(f"/api/v1/workspace/changes/{change_id}").json()["data"]

    assert _stored(first["changes"][0]["change_id"])["agent_source"] == dsh_id
    assert _stored(second["changes"][0]["change_id"])["agent_source"] == codex_id

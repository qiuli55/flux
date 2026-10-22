"""Flux 设备代理：把一台 Flux 实例注册到账号云，并保持云端中转通道。

用法（在要受控的电脑 / 服务器上跑）：

    python scripts/device_agent.py \
        --cloud https://flux.qiuli55.top \
        --token  <账号 access token> \
        --name   "我的电脑" \
        --platform windows \
        --local  http://127.0.0.1:8801 \
        --local-token <本机 FLUX_AUTH_TOKEN，可留空表示回环免鉴权>

它做三件事：
1. 用账号 access token 调 `POST /api/devices` 注册自己，拿到 device_token；
2. 用 device_token 连 `WS /api/devices/{id}/relay`，断线自动重连（指数退避）；
3. 收到云端转发的请求就打到本机 Flux，再把状态码 / 头 / body 原样回传。

边界：只转发请求-响应，不做 SSE 流式转发；终端实时输出在云端中转模式下按请求返回，
长流会等到结束或 60s 超时。手机与设备之间不直连，设备不需要公网地址。
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import logging
import signal
import sys

import httpx
import websockets

LOG = logging.getLogger("flux.device_agent")

HEARTBEAT_SECONDS = 30
RECONNECT_MAX_SECONDS = 30
FORWARD_HEADERS = {"content-type", "accept", "accept-language"}


async def register(client: httpx.AsyncClient, args: argparse.Namespace) -> tuple[str, str]:
    response = await client.post(
        "/api/devices",
        headers={"Authorization": f"Bearer {args.token}"},
        json={"name": args.name, "platform": args.platform, "version": args.version},
        timeout=30,
    )
    response.raise_for_status()
    payload = response.json()
    LOG.info("设备已注册 device_id=%s name=%s", payload["device_id"], payload["name"])
    return payload["device_id"], payload["device_token"]


async def forward(local: httpx.AsyncClient, message: dict) -> dict:
    path = str(message.get("path") or "")
    query = str(message.get("query") or "")
    url = f"/{path}" + (f"?{query}" if query else "")
    headers = {
        key: value
        for key, value in (message.get("headers") or {}).items()
        if key.lower() in FORWARD_HEADERS
    }
    body = base64.b64decode(str(message.get("body_b64") or ""))
    try:
        response = await local.request(
            str(message.get("method") or "GET"),
            url,
            content=body or None,
            headers=headers,
        )
    except httpx.HTTPError as exc:
        LOG.warning("本机请求失败 %s %s: %s", message.get("method"), url, exc)
        return {
            "type": "res",
            "id": message.get("id"),
            "status": 502,
            "headers": {"content-type": "application/json; charset=utf-8"},
            "body_b64": base64.b64encode(
                json.dumps({"detail": f"设备侧无法访问本机 Flux：{exc}"}, ensure_ascii=False).encode()
            ).decode(),
        }
    return {
        "type": "res",
        "id": message.get("id"),
        "status": response.status_code,
        "headers": {
            key: value
            for key, value in response.headers.items()
            if key.lower() in {"content-type", "cache-control", "etag"}
        },
        "body_b64": base64.b64encode(response.content).decode(),
    }


async def relay_once(
    client: httpx.AsyncClient,
    local: httpx.AsyncClient,
    args: argparse.Namespace,
    device_id: str,
    device_token: str,
) -> None:
    ws_url = args.cloud.replace("https://", "wss://").replace("http://", "ws://").rstrip("/")
    ws_url = f"{ws_url}/api/devices/{device_id}/relay"
    async with websockets.connect(
        ws_url,
        additional_headers={"Authorization": f"Bearer {device_token}"},
        ping_interval=20,
        ping_timeout=20,
        max_size=16 * 1024 * 1024,
    ) as socket:
        LOG.info("中转通道已连接 %s", ws_url)

        async def beat() -> None:
            while True:
                await asyncio.sleep(HEARTBEAT_SECONDS)
                try:
                    await client.post(
                        f"/api/devices/{device_id}/heartbeat",
                        headers={"Authorization": f"Bearer {device_token}"},
                        timeout=15,
                    )
                except httpx.HTTPError as exc:
                    LOG.debug("心跳失败：%s", exc)

        beat_task = asyncio.create_task(beat())
        try:
            async for raw in socket:
                try:
                    message = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                if message.get("type") != "req":
                    continue
                reply = await forward(local, message)
                await socket.send(json.dumps(reply, ensure_ascii=False))
        finally:
            beat_task.cancel()


async def run(args: argparse.Namespace) -> None:
    headers = {"X-Device-Runner": "flux-device-agent"}
    async with httpx.AsyncClient(base_url=args.cloud.rstrip("/"), headers=headers) as client:
        local_headers = {}
        if args.local_token:
            local_headers["Authorization"] = f"Bearer {args.local_token}"
        async with httpx.AsyncClient(
            base_url=args.local.rstrip("/"), headers=local_headers, timeout=60
        ) as local:
            device_id, device_token = await register(client, args)
            backoff = 2
            while True:
                try:
                    await relay_once(client, local, args, device_id, device_token)
                    backoff = 2
                except (websockets.WebSocketException, OSError) as exc:
                    LOG.warning("中转断开：%s；%ss 后重连", exc, backoff)
                    await asyncio.sleep(backoff)
                    backoff = min(backoff * 2, RECONNECT_MAX_SECONDS)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Flux 设备代理（账号云中转）")
    parser.add_argument("--cloud", default="https://flux.qiuli55.top", help="账号云地址")
    parser.add_argument("--token", required=True, help="账号 access token")
    parser.add_argument("--name", required=True, help="设备名（同账号下唯一）")
    parser.add_argument("--platform", default="windows", help="windows / linux / server")
    parser.add_argument("--version", default="", help="Flux 版本，可留空")
    parser.add_argument("--local", default="http://127.0.0.1:8801", help="本机 Flux 地址")
    parser.add_argument("--local-token", default="", help="本机 FLUX_AUTH_TOKEN，可留空")
    parser.add_argument("--verbose", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())

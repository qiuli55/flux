"""网页正文提取：把公开网页变成可编辑的文档 / 单文件页面。

「提取到创作」由服务端完成，不依赖用户电脑上的 Agent：手机端把当前页面的
outerHTML（如果拿得到）一并提交，服务端优先用它；拿不到时再按 URL 抓取公开
页面。抓取只走 http / https，带超时、大小上限与内网地址拦截；提取失败（页面没有
正文、抓取被站点挡住等）时退化为「标题 + 链接 + 摘录」，仍然产出一份可用产物。

实现刻意保持轻量：标准库 HTMLParser 建一棵最小 DOM，再用正文候选打分 + 白名单
渲染成 Markdown / 安全 HTML。不引入浏览器引擎，也不执行页面脚本。
"""

from __future__ import annotations

import asyncio
import html as html_lib
import ipaddress
import re
import socket
from dataclasses import dataclass
from html.parser import HTMLParser
from typing import Any
from urllib.parse import urljoin, urlsplit, urlunsplit

import httpx

from flux.errors import BadRequestError

#: 服务端单次抓取的响应体上限；网页正文远小于这个值。
MAX_FETCH_BYTES = 3 * 1024 * 1024
#: 客户端提交的 HTML 上限（outerHTML 可能比抓取到的字节大）。
MAX_HTML_CHARS = 8 * 1024 * 1024
#: 正文少于这个长度就不算提取成功，退化为链接 + 标题 + 摘录。
MIN_CONTENT_CHARS = 120
#: 抓取超时：连接 5 秒，整体 20 秒；超时就降级，不让一次提取拖住请求。
FETCH_TIMEOUT = httpx.Timeout(20.0, connect=5.0)
FETCH_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 FluxExtract/1.0"
    ),
    "Accept": "text/html,application/xhtml+xml;q=0.9,text/plain;q=0.8,*/*;q=0.1",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
}
MAX_REDIRECTS = 5
REDIRECT_CODES = {301, 302, 303, 307, 308}


class ExtractFetchError(Exception):
    """抓取失败（超时 / 站点拒绝 / 非网页 / 太大）；调用方降级为链接 + 标题。"""


class UnsafeExtractTarget(BadRequestError):
    """目标指向本机 / 内网或不是 http(s)，按 400 拒绝而不是抓取。"""


# ------------------------------------------------------------------ 纯 URL 校验


def normalize_extract_url(raw: Any) -> str:
    """校验并规范化提取目标；不合格时抛中文 400。"""
    if not isinstance(raw, str):
        raise BadRequestError("url 必须是字符串")
    text = raw.strip()
    if not text:
        raise BadRequestError("url 不能为空")
    if len(text) > 2048:
        raise BadRequestError("url 过长（最多 2048 个字符）")
    parts = urlsplit(text)
    scheme = parts.scheme.lower()
    if scheme not in ("http", "https"):
        raise BadRequestError("只支持提取 http / https 网页")
    if not parts.hostname:
        raise BadRequestError("url 缺少主机名")
    if parts.username or parts.password:
        raise BadRequestError("url 不能包含用户名或密码")
    netloc = parts.netloc.rsplit("@", 1)[-1]
    path = parts.path or "/"
    return urlunsplit((scheme, netloc, path, parts.query, ""))


def is_public_ip(value: str) -> bool:
    """公网地址才允许服务端抓取，避免把提取接口变成内网探测器。

    `is_global` 同时排除私网、回环、链路本地、保留、组播、未指定和运营商级 NAT。
    """
    try:
        ip = ipaddress.ip_address(value)
    except ValueError:
        return False
    return ip.is_global


def _is_local_hostname(host: str) -> bool:
    value = host.rstrip(".").lower()
    if value in {"localhost", "localhost.localdomain"}:
        return True
    return value.endswith((".localhost", ".local", ".internal", ".lan", ".home"))


def validate_extract_target(url: str) -> None:
    """同步可做的安全检查：本机名 / 私网 IP 直接拒绝；域名解析交给抓取阶段。"""
    parts = urlsplit(url)
    host = (parts.hostname or "").strip().lower()
    if not host:
        raise UnsafeExtractTarget("url 缺少主机名")
    if _is_local_hostname(host):
        raise UnsafeExtractTarget("不能提取本机或内网地址")
    # 纯 IP 字面量：私网 / 回环 / 链路本地 / 保留地址一律拒绝
    try:
        ipaddress.ip_address(host)
    except ValueError:
        return
    if not is_public_ip(host):
        raise UnsafeExtractTarget("不能提取本机或内网地址")


async def _ensure_public_host(url: str) -> None:
    """抓取前解析域名并拒绝解析到内网的地址（防 DNS rebinding）。"""
    validate_extract_target(url)
    parts = urlsplit(url)
    host = (parts.hostname or "").strip()
    port = parts.port or (443 if parts.scheme.lower() == "https" else 80)
    try:
        infos = await asyncio.get_running_loop().getaddrinfo(
            host, port, type=socket.SOCK_STREAM
        )
    except socket.gaierror as exc:
        raise ExtractFetchError("找不到目标网站，已改为保存链接与标题") from exc
    for info in infos:
        address = str(info[4][0])
        if not is_public_ip(address):
            raise UnsafeExtractTarget("不能提取解析到内网的地址")


# ------------------------------------------------------------------ 抓取


def _decode_html(data: bytes, content_type: str) -> str:
    """按 Content-Type / meta charset 解码；坏字节替换后再统一清洗。"""
    charset = ""
    match = re.search(r"charset=([\w-]+)", content_type, re.IGNORECASE)
    if match:
        charset = match.group(1)
    if not charset:
        head = data[:4096].decode("ascii", "ignore")
        meta = re.search(r"<meta[^>]+charset=[\"']?([\w-]+)", head, re.IGNORECASE)
        if meta:
            charset = meta.group(1)
    for candidate in (charset, "utf-8", "gb18030"):
        if not candidate:
            continue
        try:
            return data.decode(candidate)
        except (LookupError, UnicodeDecodeError):
            continue
    return data.decode("utf-8", "replace")


async def fetch_page_html(url: str) -> str:
    """抓取公开网页并返回 HTML；网络 / 状态码 / 非网页 / 超大统一抛 [ExtractFetchError]。

    重定向手动跟随，每一跳都重新做内网检查，避免被重定向到 127.0.0.1 / 169.254.x。
    """
    current = url
    async with httpx.AsyncClient(
        timeout=FETCH_TIMEOUT,
        follow_redirects=False,
        trust_env=False,
        headers=FETCH_HEADERS,
    ) as client:
        for _ in range(MAX_REDIRECTS + 1):
            await _ensure_public_host(current)
            try:
                async with client.stream("GET", current) as response:
                    if response.status_code in REDIRECT_CODES:
                        location = response.headers.get("location")
                        if not location:
                            raise ExtractFetchError("目标网站返回了空重定向")
                        current = urljoin(current, location)
                        continue
                    if response.status_code >= 400:
                        raise ExtractFetchError(
                            f"目标网站返回 HTTP {response.status_code}，已改为保存链接与标题"
                        )
                    content_type = response.headers.get("content-type", "").lower()
                    if content_type and not any(
                        kind in content_type
                        for kind in ("text/html", "application/xhtml+xml", "text/plain")
                    ):
                        raise ExtractFetchError("目标地址不是网页，已改为保存链接与标题")
                    buffer = bytearray()
                    async for chunk in response.aiter_bytes():
                        buffer.extend(chunk)
                        if len(buffer) > MAX_FETCH_BYTES:
                            raise ExtractFetchError(
                                "网页超过服务端抓取上限，已改为保存链接与标题"
                            )
                    return _decode_html(bytes(buffer), content_type)
            except httpx.TimeoutException as exc:
                raise ExtractFetchError("抓取网页超时，已改为保存链接与标题") from exc
            except httpx.HTTPError as exc:
                raise ExtractFetchError("无法连接目标网站，已改为保存链接与标题") from exc
    raise ExtractFetchError("重定向次数过多，已改为保存链接与标题")


# ------------------------------------------------------------------ 最小 DOM


_VOID_TAGS = {
    "area",
    "base",
    "br",
    "col",
    "embed",
    "hr",
    "img",
    "input",
    "link",
    "meta",
    "param",
    "source",
    "track",
    "wbr",
}

#: 这些标签既不参与正文打分，也不会渲染进结果。
_SKIP_TAGS = {
    "script",
    "style",
    "noscript",
    "template",
    "svg",
    "canvas",
    "iframe",
    "nav",
    "header",
    "footer",
    "aside",
    "form",
    "button",
    "select",
    "textarea",
    "input",
    "video",
    "audio",
    "object",
    "embed",
}


class _Node:
    __slots__ = ("tag", "attrs", "children", "parent")

    def __init__(self, tag: str, attrs: dict[str, str], parent: _Node | None) -> None:
        self.tag = tag
        self.attrs = attrs
        self.children: list[_Node | str] = []
        self.parent = parent


class _DomParser(HTMLParser):
    """容错的 HTML → 树；不执行脚本，不关心属性值里的样式细节。"""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.root = _Node("#document", {}, None)
        self._stack: list[_Node] = [self.root]

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        node = _Node(
            tag.lower(),
            {key.lower(): (value or "") for key, value in attrs},
            self._stack[-1],
        )
        self._stack[-1].children.append(node)
        if node.tag not in _VOID_TAGS:
            self._stack.append(node)

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        node = _Node(
            tag.lower(),
            {key.lower(): (value or "") for key, value in attrs},
            self._stack[-1],
        )
        self._stack[-1].children.append(node)

    def handle_endtag(self, tag: str) -> None:
        target = tag.lower()
        for index in range(len(self._stack) - 1, 0, -1):
            if self._stack[index].tag == target:
                del self._stack[index:]
                return

    def handle_data(self, data: str) -> None:
        self._stack[-1].children.append(data)


def _iter_nodes(node: _Node):
    for child in node.children:
        if isinstance(child, _Node):
            yield child
            yield from _iter_nodes(child)


def _node_text(node: _Node | str) -> str:
    if isinstance(node, str):
        return node
    if node.tag in _SKIP_TAGS:
        return ""
    return " ".join(
        part for part in (_node_text(child) for child in node.children) if part
    )


def _clean_text(value: str, *, preserve_ws: bool = False) -> str:
    value = value.replace("\x00", "").replace("�", "")
    if preserve_ws:
        return value
    return re.sub(r"\s+", " ", value).strip()


def _inline_text(value: str) -> str:
    """行内文本保留词间空格（`Hello <strong>world</strong>` 不能变成 `Hello**world**`）。"""
    value = value.replace("\x00", "").replace("�", "")
    return re.sub(r"\s+", " ", value)


def _class_id(node: _Node) -> str:
    return f"{node.attrs.get('class', '')} {node.attrs.get('id', '')}".lower()


def _contains_tag(node: _Node, tag: str) -> bool:
    return any(child.tag == tag for child in _iter_nodes(node))


# ------------------------------------------------------------------ 正文选择


_POSITIVE_HINTS = (
    "article",
    "content",
    "entry",
    "main",
    "post",
    "story",
    "markdown",
    "prose",
    "body",
    "detail",
    "rich_media",
    "text",
)
_NEGATIVE_HINTS = (
    "comment",
    "sidebar",
    "footer",
    "nav",
    "menu",
    "share",
    "related",
    "recommend",
    "advert",
    "banner",
    "cookie",
    "header",
    "meta",
    "breadcrumb",
    "toolbar",
    "subscribe",
    "popup",
    "modal",
)


def _candidate_score(node: _Node) -> float:
    text = _node_text(node)
    text_len = len(_clean_text(text))
    if text_len < 60:
        return 0.0
    link_len = sum(
        len(_clean_text(_node_text(child)))
        for child in _iter_nodes(node)
        if child.tag == "a"
    )
    paragraphs = sum(1 for child in _iter_nodes(node) if child.tag == "p")
    headings = sum(
        1 for child in _iter_nodes(node) if child.tag in {"h1", "h2", "h3", "h4", "h5", "h6"}
    )
    score = float(text_len) + paragraphs * 90.0 + headings * 45.0
    score -= link_len * 1.4
    hints = _class_id(node)
    if any(hint in hints for hint in _POSITIVE_HINTS):
        score += 250.0
    if any(hint in hints for hint in _NEGATIVE_HINTS):
        score -= 350.0
    if node.tag == "article":
        score += 300.0
    elif node.tag == "main" or node.attrs.get("role") == "main":
        score += 240.0
    return score


def _select_content(root: _Node) -> _Node | None:
    candidates: list[tuple[float, _Node]] = []
    body: _Node | None = None
    for node in _iter_nodes(root):
        if node.tag == "body":
            body = node
        if node.tag in _SKIP_TAGS:
            continue
        if node.tag in {"article", "main", "section", "div", "td"} or node.attrs.get(
            "role"
        ) == "main":
            score = _candidate_score(node)
            if score > 0:
                candidates.append((score, node))
    if candidates:
        candidates.sort(key=lambda item: item[0], reverse=True)
        return candidates[0][1]
    return body


# ------------------------------------------------------------------ 元信息


def _meta_content(root: _Node, *names: str) -> str | None:
    wanted = {name.lower() for name in names}
    for node in _iter_nodes(root):
        if node.tag != "meta":
            continue
        key = (node.attrs.get("property") or node.attrs.get("name") or "").lower()
        if key in wanted:
            content = _clean_text(node.attrs.get("content", ""))
            if content:
                return content
    return None


def _first_tag_text(root: _Node, tag: str) -> str | None:
    for node in _iter_nodes(root):
        if node.tag == tag:
            text = _clean_text(_node_text(node))
            if text:
                return text
    return None


def _resolve_title(
    root: _Node, url: str, provided: str | None, fallback: str = "网页提取"
) -> str:
    for candidate in (
        _first_tag_text(root, "title"),
        _meta_content(root, "og:title", "twitter:title"),
        _first_tag_text(root, "h1"),
        (provided or "").strip() or None,
    ):
        if candidate:
            return candidate[:160]
    host = urlsplit(url).hostname or ""
    return host or fallback


def _resolve_excerpt(root: _Node, content: _Node | None, limit: int = 140) -> str:
    described = _meta_content(root, "description", "og:description", "twitter:description")
    if described:
        return described[:limit]
    if content is not None:
        for node in _iter_nodes(content):
            if node.tag == "p":
                text = _clean_text(_node_text(node))
                if len(text) >= 20:
                    return text[:limit]
        text = _clean_text(_node_text(content))
        if text:
            return text[:limit]
    return ""


# ------------------------------------------------------------------ Markdown 渲染


class _MarkdownRenderer:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url

    def render(self, node: _Node) -> str:
        text = self._render_children(node)
        text = re.sub(r"[ \t]+\n", "\n", text)
        text = re.sub(r"\n{3,}", "\n\n", text)
        return text.strip()

    def _render_children(self, node: _Node) -> str:
        return "".join(self._render(child) for child in node.children)

    def _inline(self, node: _Node) -> str:
        return self._render_children(node).strip()

    def _render(self, node: _Node | str) -> str:
        if isinstance(node, str):
            return _inline_text(node)
        if node.tag in _SKIP_TAGS:
            return ""
        tag = node.tag
        if tag in {"h1", "h2", "h3", "h4", "h5", "h6"}:
            level = int(tag[1])
            return f"\n\n{'#' * level} {self._inline(node)}\n\n"
        if tag == "p":
            text = self._inline(node)
            return f"\n\n{text}\n\n" if text else ""
        if tag in {"article", "main", "section", "div", "body", "td", "figure"}:
            return self._render_children(node)
        if tag == "figcaption":
            text = self._inline(node)
            return f"\n\n*{text}*\n\n" if text else ""
        if tag == "br":
            return "  \n"
        if tag == "hr":
            return "\n\n---\n\n"
        if tag == "ul":
            return "\n\n" + self._render_list(node, ordered=False) + "\n\n"
        if tag == "ol":
            return "\n\n" + self._render_list(node, ordered=True) + "\n\n"
        if tag == "li":
            return "- " + self._inline(node)
        if tag == "blockquote":
            body = self._render_children(node).strip()
            quoted = "\n".join(f"> {line}" if line else ">" for line in body.splitlines())
            return f"\n\n{quoted}\n\n"
        if tag == "pre":
            code = _clean_text(_node_text(node), preserve_ws=True).strip("\n")
            return f"\n\n```\n{code}\n```\n\n"
        if tag == "code":
            text = _clean_text(_node_text(node), preserve_ws=True)
            return f"`{text}`" if text else ""
        if tag == "a":
            text = self._inline(node) or node.attrs.get("href", "")
            href = self._safe_url(node.attrs.get("href", ""), allow_mailto=True)
            return f"[{text}]({href})" if href else text
        if tag == "img":
            alt = _clean_text(node.attrs.get("alt", ""))
            src = self._safe_url(node.attrs.get("src", ""))
            return f"![{alt}]({src})" if src else ""
        if tag in {"strong", "b"}:
            text = self._inline(node)
            return f"**{text}**" if text else ""
        if tag in {"em", "i"}:
            text = self._inline(node)
            return f"*{text}*" if text else ""
        if tag == "table":
            return "\n\n" + self._render_table(node) + "\n\n"
        if tag in {"tr", "thead", "tbody", "tfoot"}:
            return self._render_children(node)
        if tag in {"th", "td"}:
            return self._inline(node)
        if tag == "dl":
            return "\n\n" + self._render_children(node).strip() + "\n\n"
        if tag == "dt":
            text = self._inline(node)
            return f"\n**{text}**\n" if text else ""
        if tag == "dd":
            text = self._inline(node)
            return f": {text}\n" if text else ""
        return self._render_children(node)

    def _render_list(self, node: _Node, *, ordered: bool) -> str:
        lines: list[str] = []
        index = 0
        for child in node.children:
            if not isinstance(child, _Node) or child.tag != "li":
                continue
            index += 1
            marker = f"{index}. " if ordered else "- "
            text = self._inline(child)
            if text:
                lines.append(marker + text)
        return "\n".join(lines)

    def _render_table(self, node: _Node) -> str:
        rows: list[list[str]] = []
        for row in _iter_nodes(node):
            if row.tag != "tr":
                continue
            cells = [
                self._inline(cell)
                for cell in row.children
                if isinstance(cell, _Node) and cell.tag in {"th", "td"}
            ]
            if cells:
                rows.append(cells)
        if not rows:
            return ""
        width = max(len(row) for row in rows)
        normalized = [row + [""] * (width - len(row)) for row in rows]
        lines = ["| " + " | ".join(normalized[0]) + " |"]
        lines.append("| " + " | ".join("---" for _ in range(width)) + " |")
        for row in normalized[1:]:
            lines.append("| " + " | ".join(row) + " |")
        return "\n".join(lines)

    def _safe_url(self, raw: str, *, allow_mailto: bool = False) -> str:
        value = (raw or "").strip()
        if not value or value.startswith("#"):
            return ""
        absolute = urljoin(self.base_url, value)
        scheme = urlsplit(absolute).scheme.lower()
        if scheme in {"http", "https"}:
            return absolute
        if allow_mailto and scheme == "mailto":
            return absolute
        return ""


_HTML_ALLOWED_TAGS = {
    "p",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "ul",
    "ol",
    "li",
    "blockquote",
    "pre",
    "code",
    "a",
    "img",
    "strong",
    "em",
    "b",
    "i",
    "br",
    "hr",
    "table",
    "thead",
    "tbody",
    "tfoot",
    "tr",
    "th",
    "td",
    "figure",
    "figcaption",
    "dl",
    "dt",
    "dd",
    "section",
    "article",
    "div",
    "span",
}


class _SafeHtmlRenderer:
    """白名单渲染：丢掉脚本 / 事件处理器 / 样式，只保留可读结构。"""

    def __init__(self, base_url: str) -> None:
        self.base_url = base_url

    def render(self, node: _Node) -> str:
        parts: list[str] = []
        for child in node.children:
            parts.append(self._render(child))
        return "".join(parts).strip()

    def _render(self, node: _Node | str) -> str:
        if isinstance(node, str):
            return html_lib.escape(_clean_text(node, preserve_ws=True))
        if node.tag in _SKIP_TAGS:
            return ""
        if node.tag not in _HTML_ALLOWED_TAGS:
            # 未知标签不保留自身，但把可读的子节点带出来（如 <article><p>…）。
            return "".join(self._render(child) for child in node.children)
        tag = node.tag
        if tag == "br":
            return "<br />"
        if tag == "hr":
            return "<hr />"
        if tag == "img":
            src = self._safe_url(node.attrs.get("src", ""))
            if not src:
                return ""
            alt = html_lib.escape(_clean_text(node.attrs.get("alt", "")), quote=True)
            return f'<img src="{html_lib.escape(src, quote=True)}" alt="{alt}" loading="lazy" />'
        if tag == "a":
            href = self._safe_url(node.attrs.get("href", ""))
            inner = "".join(self._render(child) for child in node.children)
            if not href:
                return inner
            return f'<a href="{html_lib.escape(href, quote=True)}">{inner}</a>'
        attrs = ""
        if tag == "code":
            classes = node.attrs.get("class", "")
            language = ""
            for token in classes.split():
                if token.startswith("language-"):
                    language = token.removeprefix("language-")[:32]
                    break
            if language and re.fullmatch(r"[\w+#.-]+", language):
                attrs = f' class="language-{language}"'
        inner = "".join(self._render(child) for child in node.children)
        return f"<{tag}{attrs}>{inner}</{tag}>"

    def _safe_url(self, raw: str) -> str:
        value = (raw or "").strip()
        if not value or value.startswith("#"):
            return ""
        absolute = urljoin(self.base_url, value)
        return absolute if urlsplit(absolute).scheme.lower() in {"http", "https"} else ""


# ------------------------------------------------------------------ 结果


@dataclass
class ExtractedPage:
    """提取结果：要么有正文，要么退化为链接 + 标题 + 摘录。"""

    title: str
    excerpt: str
    content: str
    extracted: bool
    note: str | None = None


def _fallback_content(
    *,
    kind: str,
    title: str,
    url: str,
    excerpt: str,
    note: str | None,
    client_title: str | None,
) -> ExtractedPage:
    title = (title or client_title or _host_title(url) or "网页提取")[:160]
    excerpt = (excerpt or "").strip()
    if not excerpt:
        excerpt = "该页面没有可提取的正文，已保留标题与原始链接。"
    reason = (note or "").strip()
    if kind == "doc":
        lines = [f"# {title}", "", f"> 来源：[{url}]({url})", ""]
        lines.extend([excerpt, ""])
        if reason:
            lines.extend([f"> {reason}", ""])
        content = "\n".join(lines).strip() + "\n"
    else:
        reason_html = f"<p class=\"flux-note\">{html_lib.escape(reason)}</p>" if reason else ""
        content = _page_shell(
            title=title,
            url=url,
            body=(
                f"<p>{html_lib.escape(excerpt)}</p>"
                f"{reason_html}"
                f"<p><a href=\"{html_lib.escape(url, quote=True)}\">打开原始网页</a></p>"
            ),
        )
    return ExtractedPage(
        title=title,
        excerpt=excerpt[:140],
        content=content,
        extracted=False,
        note=reason or None,
    )


def _host_title(url: str) -> str:
    return (urlsplit(url).hostname or "").strip() or "网页提取"


def _page_shell(*, title: str, url: str, body: str) -> str:
    safe_title = html_lib.escape(title)
    safe_url = html_lib.escape(url, quote=True)
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>{safe_title}</title>
  <style>
    :root {{ color-scheme: light; }}
    body {{ margin: 0; background: #ffffff; color: #1d1f23; }}
    main {{ max-width: 760px; margin: 0 auto; padding: 40px 22px 64px; }}
    h1 {{ font-size: 26px; line-height: 1.3; letter-spacing: -0.02em; margin: 0 0 14px; }}
    h2 {{ font-size: 21px; line-height: 1.35; margin: 32px 0 12px; }}
    h3 {{ font-size: 17px; margin: 26px 0 10px; }}
    p, li, dd {{ font-size: 15px; line-height: 1.8; color: #33363c; }}
    a {{ color: #1a5fd0; text-decoration: none; }}
    a:hover {{ text-decoration: underline; }}
    blockquote {{ margin: 18px 0; padding: 2px 0 2px 14px;
      border-left: 3px solid #d8dbe0; color: #5a5f68; }}
    pre {{ overflow-x: auto; padding: 14px; background: #f5f6f8;
      border-radius: 10px; font-size: 13px; line-height: 1.6; }}
    code {{ font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }}
    img {{ max-width: 100%; height: auto; border-radius: 8px; }}
    table {{ width: 100%; border-collapse: collapse; margin: 18px 0; font-size: 14px; }}
    th, td {{ border: 1px solid #e2e5ea; padding: 8px 10px; text-align: left; }}
    .flux-source {{ font-size: 12px; color: #8a9099; word-break: break-all; }}
    .flux-note {{ font-size: 13px; color: #8a6d3b; background: #fbf7ec;
      padding: 10px 12px; border-radius: 8px; }}
    footer {{ margin-top: 40px; padding-top: 16px; border-top: 1px solid #eceef1;
      font-size: 12px; color: #8a9099; }}
  </style>
</head>
<body>
  <main>
    <h1>{safe_title}</h1>
    <p class="flux-source">来源：<a href="{safe_url}">{safe_url}</a></p>
    {body}
    <footer>由 Flux 提取自原始网页，版权归原作者所有。</footer>
  </main>
</body>
</html>
"""


def extract_page(
    *,
    html: str,
    url: str,
    kind: str,
    title: str | None = None,
    note: str | None = None,
) -> ExtractedPage:
    """从 HTML 提取正文；正文不足时返回链接 + 标题 + 摘录的降级结果。"""
    cleaned = html.replace("\x00", "").replace("�", "")
    parser = _DomParser()
    try:
        parser.feed(cleaned)
        parser.close()
    except Exception:  # noqa: BLE001 - 坏 HTML 只应降级，不应让提取接口 500
        return _fallback_content(
            kind=kind,
            title=(title or _host_title(url))[:160],
            url=url,
            excerpt="",
            note=note or "网页结构无法解析，已改为保存链接与标题",
            client_title=title,
        )

    root = parser.root
    resolved_title = _resolve_title(root, url, title)
    content = _select_content(root)
    if content is None:
        return _fallback_content(
            kind=kind,
            title=resolved_title,
            url=url,
            excerpt=_resolve_excerpt(root, None),
            note=note,
            client_title=title,
        )

    markdown = _MarkdownRenderer(url).render(content)
    if len(_clean_text(markdown)) < MIN_CONTENT_CHARS:
        return _fallback_content(
            kind=kind,
            title=resolved_title,
            url=url,
            excerpt=_resolve_excerpt(root, content),
            note=note or "页面正文过短，已改为保存链接、标题与摘录",
            client_title=title,
        )

    excerpt = _resolve_excerpt(root, content)
    if kind == "doc":
        source = f"> 来源：[{url}]({url})"
        content_text = markdown
        if not re.match(r"^\s*#\s+", content_text):
            content_text = f"# {resolved_title}\n\n{content_text}"
        content_text = f"{content_text.strip()}\n\n{source}\n"
        if note:
            content_text += f"\n> {note}\n"
        return ExtractedPage(
            title=resolved_title,
            excerpt=excerpt,
            content=content_text,
            extracted=True,
            note=note or None,
        )

    fragment = _SafeHtmlRenderer(url).render(content)
    body = fragment
    if note:
        body = f"{body}\n<p class=\"flux-note\">{html_lib.escape(note)}</p>"
    return ExtractedPage(
        title=resolved_title,
        excerpt=excerpt,
        content=_page_shell(title=resolved_title, url=url, body=body),
        extracted=True,
        note=note or None,
    )


def fallback_page(
    *, url: str, kind: str, title: str | None = None, note: str | None = None
) -> ExtractedPage:
    """完全没有 HTML 时（抓取失败）的降级产物：链接 + 标题 + 摘录。"""
    return _fallback_content(
        kind=kind,
        title=(title or "").strip()[:160],
        url=url,
        excerpt="",
        note=note,
        client_title=title,
    )


def validate_client_html(raw: Any) -> str | None:
    """客户端提交的 HTML 只做类型 / 体积校验；空串等同没提交。"""
    if raw is None:
        return None
    if not isinstance(raw, str):
        raise BadRequestError("html 必须是字符串")
    if not raw.strip():
        return None
    if len(raw) > MAX_HTML_CHARS:
        raise BadRequestError("html 超过服务端上限（最多 8 MiB）")
    return raw


__all__ = [
    "ExtractFetchError",
    "ExtractedPage",
    "MAX_FETCH_BYTES",
    "MAX_HTML_CHARS",
    "UnsafeExtractTarget",
    "extract_page",
    "fallback_page",
    "fetch_page_html",
    "is_public_ip",
    "normalize_extract_url",
    "validate_client_html",
    "validate_extract_target",
]

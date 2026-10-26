"""设计稿 → 单文件可交互 HTML（App 内网页预览）。

设计稿只存在本机 Luma Studio（画布 JSON），手机端原本只能看 Canvas 演示。把同一份 JSON
直接渲染成 HTML：App 用 WebView 打开即可离线交互，不需要起服务、也不需要联网。

渲染规则严格对齐移动端 ``DesignScreens.kt`` 的 ``drawDesignNode`` / ``drawNav`` /
``drawSlider`` / ``drawSwitch`` / ``drawNodeText`` 与 ``DesignDemoPreview`` 的演示语义
（默认圆角、默认填充、卡片投影、滑杆/开关几何、按钮切屏规则），否则"网页预览"会和
App 内演示对不上。

坐标：画板与节点都用设计单位（≈dp），节点坐标相对父节点；整块画板用 CSS transform
等比缩放到视口内，交互命中直接用元素自身矩形换算，不受缩放影响。
"""

from __future__ import annotations

import html
import json
import re
from typing import Any

#: 与移动端 V2Colors 对齐：绘制默认值都基于这几个色（见 DesignScreens.kt defaultFill）。
ACCENT = "#0891B2"
INK = "#0F172A"
SHELL_TOP = "#F4F6F9"
SWITCH_OFF = "#E2E8F0"
INPUT_FILL = "#F8FAFC"

_HEX_RE = re.compile(r"^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
_RGB_RE = re.compile(r"^rgba?\(([^)]*)\)$", re.IGNORECASE)
_COLOR_TOKEN_RE = re.compile(r"#[0-9a-fA-F]{3,8}|rgba?\([^)]*\)")

#: 演示里的"返回"类文案；命中即回上一屏（与 demoNextScreen 一致）。
_BACK_WORDS = ("返回", "取消", "关闭", "退出")


def render_design_html(design: dict[str, Any], *, page_title: str | None = None) -> str:
    """把一份设计稿渲染成自包含的单文件 HTML（无外部依赖，可直接 loadDataWithBaseURL）。"""
    design = design if isinstance(design, dict) else {}
    raw_screens = design.get("screens")
    screens = [item for item in (raw_screens or []) if isinstance(item, dict)]
    name = str(design.get("name") or "未命名设计稿")
    bg = _single_color(design.get("bg"), SHELL_TOP)
    title = (page_title or name).strip() or "设计稿演示"

    meta = [
        {"id": str(screen.get("id") or ""), "name": str(screen.get("name") or "")}
        for screen in screens
    ]

    if screens:
        devices = "\n".join(
            _screen_html(screen, bg) for screen in screens if _screen_size(screen) is not None
        )
        body_main = (
            '<main class="stage"><div class="holder" id="holder">\n'
            f"{devices}\n"
            "</div></main>\n"
            '<footer class="foot">\n'
            '  <div class="hint">点按钮切屏 · 拖动滑杆 · 点开关切换</div>\n'
            '  <div class="pager">\n'
            '    <button class="pg" id="prev" type="button" aria-label="上一屏">&lsaquo;</button>\n'
            '    <div class="dots" id="dots"></div>\n'
            '    <button class="pg" id="next" type="button" aria-label="下一屏">&rsaquo;</button>\n'
            "  </div>\n"
            "</footer>"
        )
        header_count = f'<div class="count" id="count">1 / {len(screens)}</div>'
    else:
        body_main = '<main class="stage"><div class="empty">这份设计稿还没有画板</div></main>'
        header_count = ""

    screens_json = json.dumps(meta, ensure_ascii=False).replace("</", "<\\/")

    return _TEMPLATE.format(
        title=html.escape(title),
        name=html.escape(name),
        count=header_count,
        main=body_main,
        screens_json=screens_json,
        css=_CSS,
        back_words=json.dumps(list(_BACK_WORDS), ensure_ascii=False),
    )


# ------------------------------------------------------------------ 画板 / 节点


def _screen_size(screen: dict[str, Any]) -> tuple[float, float] | None:
    w = _num(screen.get("w"), 0.0)
    h = _num(screen.get("h"), 0.0)
    return (w, h) if w > 0 and h > 0 else None


def _screen_html(screen: dict[str, Any], bg: str) -> str:
    size = _screen_size(screen)
    if size is None:
        return ""
    w, h = size
    raw_nodes = screen.get("nodes")
    nodes = raw_nodes if isinstance(raw_nodes, list) else []
    inner = "\n".join(_node_html(node) for node in nodes if isinstance(node, dict))
    return (
        f'<section class="device" data-w="{_num_str(w)}" data-h="{_num_str(h)}" '
        f'data-id="{html.escape(str(screen.get("id") or ""), quote=True)}" '
        f'data-name="{html.escape(str(screen.get("name") or ""), quote=True)}" '
        f'style="width:{_px(w)};height:{_px(h)}">\n'
        f'<div class="screen" style="background:{bg}">\n{inner}\n</div>\n'
        "</section>"
    )


def _node_html(node: dict[str, Any]) -> str:
    w = _num(node.get("w"), 0.0)
    h = _num(node.get("h"), 0.0)
    if w <= 0 or h <= 0:
        return ""
    opacity = _num(node.get("opacity"), 1.0)
    if opacity <= 0.01:
        return ""

    ntype = str(node.get("type") or "text")
    text = str(node.get("text") or "")
    base = [
        f"left:{_px(_num(node.get('x'), 0.0))}",
        f"top:{_px(_num(node.get('y'), 0.0))}",
        f"width:{_px(w)}",
        f"height:{_px(h)}",
    ]
    if opacity < 1:
        base.append(f"opacity:{_num_str(opacity)}")

    radius = max(_num(node.get("radius"), _default_radius(ntype, w, h)), 0.0)
    stroke = node.get("stroke")
    if isinstance(stroke, str) and stroke.strip():
        base.append(f"border:1px solid {_single_color(stroke, 'transparent')}")

    fill = node.get("fill")
    attrs = {
        "data-id": str(node.get("id") or ""),
        "data-type": ntype,
        "data-name": str(node.get("name") or ""),
        "data-text": text,
    }
    children = node.get("children")
    child_html = ""
    if isinstance(children, list):
        child_html = "".join(
            _node_html(child) for child in children if isinstance(child, dict)
        )

    if ntype == "text":
        if fill is None:
            classes = ["n", "t-text"]
            inner = _text_span(node, text)
            return _element(classes, base, attrs, inner + child_html)
        # 带底色的文本节点：先画圆角矩形，再叠文字（Compose: hasShape 同上）
        classes = ["n", "t-text"]
        base.append(f"background:{_background(fill, _default_fill(ntype))}")
        base.append(f"border-radius:{_px(radius)}")
        return _element(classes, base, attrs, _text_span(node, text) + child_html)

    if ntype in ("button", "pill"):
        base.append(f"background:{_background(fill, _default_fill(ntype))}")
        base.append(f"border-radius:{_px(radius)}")
        attrs["role"] = "button"
        attrs["tabindex"] = "0"
        return _element(["n", f"t-{ntype}"], base, attrs, _text_span(node, text) + child_html)

    if ntype == "slider":
        value = _clamp(_num(node.get("value"), 0.5), 0.0, 1.0)
        fill_color = _single_color(fill, ACCENT)
        track_color = _single_color(node.get("track"), SWITCH_OFF)
        base.append(f"--v:{_num_str(value)}")
        base.append(f"border-radius:{_px(radius)}")
        inner = (
            f'<div class="sl-track" style="background:{track_color}"></div>'
            f'<div class="sl-fill" style="background:{fill_color}"></div>'
            '<div class="sl-thumb"></div>'
            f'<div class="sl-ring" style="background:{_alpha_css(fill_color, 0.22)}"></div>'
        )
        return _element(["n", "t-slider"], base, attrs, inner + child_html)

    if ntype == "switch":
        knob = max(h - 6.0, 1.0)
        base.append(f"border-radius:{_px(radius)}")
        base.append(f"--c-on:{_single_color(fill, ACCENT)}")
        base.append(f"--knob:{_px(knob)}")
        base.append(f"--knob-top:{_px((h - knob) / 2.0)}")
        classes = ["n", "t-switch"] + (["on"] if node.get("on") is True else [])
        return _element(classes, base, attrs, '<div class="sw-knob"></div>' + child_html)

    if ntype == "nav":
        raw_items = node.get("items")
        items = [str(item) for item in raw_items] if isinstance(raw_items, list) else []
        items = [item for item in items if item.strip()] or ["首页", "任务", "我的"]
        active = int(_clamp(_num(node.get("active"), 0.0), 0.0, float(len(items) - 1)))
        size = _num(node.get("fontSize"), 10.5)
        cells = []
        for i, item in enumerate(items):
            on = i == active
            style = (
                f"font-size:{_px(size)};"
                f"font-weight:{600 if on else 400};"
                f"color:{ACCENT if on else _alpha_css(INK, 0.42)}"
            )
            cells.append(
                f'<div class="nav-cell{" on" if on else ""}">'
                f'<span style="{style}">{html.escape(item)}</span>'
                f'<i style="background:{ACCENT}"></i></div>'
            )
        attrs["data-items"] = json.dumps(items, ensure_ascii=False)
        base.append(f"background:{_background(fill, '#FFFFFF')}")
        base.append(f"border-radius:{_px(radius)}")
        return _element(["n", "t-nav"], base, attrs, "".join(cells) + child_html)

    if ntype == "input":
        pad = _num(node.get("padLeft"), 12.0)
        base.append(f"background:{_background(fill, _default_fill(ntype))}")
        base.append(f"border-radius:{_px(radius)}")
        inner = (
            f'<input type="text" autocomplete="off" '
            f'placeholder="{html.escape(text, quote=True)}" '
            f'style="{_text_css(node)}padding-left:{_px(pad)}">'
        )
        return _element(["n", "t-input"], base, attrs, inner + child_html)

    # card / circle / decor / 未知类型：都是"底色圆角矩形"（圆或方形另算）
    base.append(f"background:{_background(fill, _default_fill(ntype))}")
    classes = ["n"]
    if ntype == "circle" or (ntype == "decor" and abs(w - h) < 0.01):
        base.append("border-radius:50%")
        classes.append("t-circle" if ntype == "circle" else "t-decor")
    else:
        base.append(f"border-radius:{_px(radius)}")
        if ntype == "card":
            base.append("box-shadow:0 6px 0 rgba(0,0,0,.05)")
        if ntype == "decor":
            classes.append("t-decor")
    if ntype == "card":
        classes.append("t-card")
    return _element(classes, base, attrs, child_html)


def _text_span(node: dict[str, Any], text: str) -> str:
    """文字块：垂直居中、最多 3 行、超出省略（与 drawNodeText 一致）。"""
    if not text.strip():
        return ""
    return f'<span class="tx" style="{_text_css(node)}">{html.escape(text)}</span>'


def _element(
    classes: list[str], style: list[str], attrs: dict[str, str], inner: str
) -> str:
    attr_text = "".join(
        f' {key}="{html.escape(value, quote=True)}"' for key, value in attrs.items()
    )
    return (
        f'<div class="{html.escape(" ".join(classes), quote=True)}"'
        f' style="{html.escape(";".join(style), quote=True)}"{attr_text}>{inner}</div>'
    )


# ------------------------------------------------------------------ 样式计算


def _text_css(node: dict[str, Any], default_size: float = 13.0) -> str:
    size = _num(node.get("fontSize"), default_size)
    weight = int(_clamp(_num(node.get("fontWeight"), 400.0), 1.0, 1000.0))
    line_height = _num(node.get("lineHeight"), 1.35)
    spacing = _num(node.get("letterSpacing"), 0.0)
    align = str(node.get("align") or "")
    text_align = {"center": "center", "right": "right"}.get(align, "left")
    color = _single_color(node.get("color"), INK)
    return (
        f"font-size:{_px(size)};"
        f"font-weight:{weight};"
        f"line-height:{_num_str(line_height)};"
        f"letter-spacing:{_px(spacing)};"
        f"text-align:{text_align};"
        f"color:{color}"
    )


def _default_radius(ntype: str, w: float, h: float) -> float:
    """与设计工作室的 defaultRadius 完全一致。"""
    if ntype in ("button", "pill", "input", "slider", "switch"):
        return h / 2.0
    if ntype in ("card", "nav"):
        return 18.0
    if ntype == "circle":
        return w / 2.0
    return 0.0


def _default_fill(ntype: str) -> str:
    """与设计工作室的 defaultFill 完全一致。"""
    if ntype == "button":
        return ACCENT
    if ntype == "pill":
        return _alpha_css(ACCENT, 0.12)
    if ntype in ("card", "nav"):
        return "#FFFFFF"
    if ntype == "input":
        return INPUT_FILL
    if ntype == "decor":
        return _alpha_css(ACCENT, 0.12)
    if ntype in ("switch", "slider"):
        return ACCENT
    return _alpha_css(INK, 0.08)


def _background(raw: Any, fallback: str) -> str:
    """填充：带 gradient 的取所有色标做竖向渐变，否则按单色处理。"""
    value = raw.strip() if isinstance(raw, str) else ""
    if "gradient" in value.lower():
        colors = [_single_color(token, fallback) for token in _COLOR_TOKEN_RE.findall(value)]
        if len(colors) >= 2:
            return f"linear-gradient(180deg, {', '.join(colors)})"
    return _single_color(raw, fallback)


def _single_color(raw: Any, fallback: str) -> str:
    """等价于 designColor：支持 #rgb / #rrggbb / #aarrggbb / rgb() / rgba()。

    注意 #aarrggbb 是 Android 的 ARGB 顺序，CSS 不认识，必须转成 rgba()。
    """
    value = raw.strip() if isinstance(raw, str) else ""
    if not value:
        return fallback
    parsed = _parse_color(value)
    if parsed is not None:
        r, g, b, a = parsed
        return f"#{r:02x}{g:02x}{b:02x}" if a >= 255 else f"rgba({r},{g},{b},{a / 255:.3f})"
    if "gradient" in value.lower():
        tokens = _COLOR_TOKEN_RE.findall(value)
        if tokens:
            return _single_color(tokens[-1], fallback)
    return fallback


def _parse_color(value: str) -> tuple[int, int, int, int] | None:
    """解析成 (r, g, b, a)，a 取 0..255。"""
    match = _HEX_RE.match(value)
    if match:
        hex_value = match.group(1)
        try:
            if len(hex_value) == 3:
                hex_value = "".join(char * 2 for char in hex_value)
            if len(hex_value) == 8:
                return (
                    int(hex_value[2:4], 16),
                    int(hex_value[4:6], 16),
                    int(hex_value[6:8], 16),
                    int(hex_value[0:2], 16),
                )
            return (
                int(hex_value[0:2], 16),
                int(hex_value[2:4], 16),
                int(hex_value[4:6], 16),
                255,
            )
        except ValueError:
            return None
    match = _RGB_RE.match(value)
    if match:
        parts = [part.strip() for part in match.group(1).split(",")]
        if len(parts) >= 3:
            try:
                r = _clamp(float(parts[0]), 0.0, 255.0)
                g = _clamp(float(parts[1]), 0.0, 255.0)
                b = _clamp(float(parts[2]), 0.0, 255.0)
                alpha = _clamp(float(parts[3]), 0.0, 1.0) if len(parts) >= 4 else 1.0
            except ValueError:
                return None
            return (int(round(r)), int(round(g)), int(round(b)), int(round(alpha * 255)))
    return None


def _alpha_css(color: str, alpha: float) -> str:
    parsed = _parse_color(color)
    if parsed is None:
        return color
    r, g, b, _ = parsed
    return f"rgba({r},{g},{b},{alpha:.3f})"


# ------------------------------------------------------------------ 数值格式


def _num(value: Any, fallback: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return fallback


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def _num_str(value: float) -> str:
    text = f"{round(value, 3):.3f}".rstrip("0").rstrip(".")
    return text or "0"


def _px(value: float) -> str:
    return f"{_num_str(value)}px"


# ------------------------------------------------------------------ 模板

_TEMPLATE = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="robots" content="noindex,nofollow">
<meta name="theme-color" content="#0B0E12">
<title>{title}</title>
<style>{css}</style>
</head>
<body>
<div class="wrap">
  <header class="top">
    <div class="badge">演</div>
    <div class="meta">
      <div class="title">演示预览</div>
      <div class="sub">{name}</div>
    </div>
    {count}
  </header>
  {main}
</div>
<script>
(function () {{
  "use strict";
  var SCREENS = {screens_json};
  var devices = Array.prototype.slice.call(document.querySelectorAll(".device"));
  var holder = document.getElementById("holder");
  var stage = document.querySelector(".stage");
  var countEl = document.getElementById("count");
  var prevEl = document.getElementById("prev");
  var nextEl = document.getElementById("next");
  var dotsEl = document.getElementById("dots");
  var index = 0;
  var dragSlider = null;

  function norm(value) {{
    return String(value == null ? "" : value).toLowerCase().replace(/[^\\p{{L}}\\p{{N}}]/gu, "");
  }}

  // 与移动端 demoNextScreen 同一套规则：返回类回上一屏；文案命中其他画板名/尾段/id 跳那屏；
  // 其余顺延到下一屏，末屏不动。
  function nextScreen(text, current) {{
    var raw = String(text == null ? "" : text).trim();
    if ({back_words}.some(function (word) {{ return raw.indexOf(word) >= 0; }})) {{
      return current > 0 ? current - 1 : null;
    }}
    var value = norm(raw);
    if (value) {{
      for (var i = 0; i < SCREENS.length; i++) {{
        if (i === current) continue;
        var screen = SCREENS[i] || {{}};
        var name = String(screen.name || "");
        var whole = norm(name);
        var tail = norm(name.indexOf("·") >= 0 ? name.slice(name.indexOf("·") + 1) : name);
        var id = norm(screen.id);
        if ((tail && (tail.indexOf(value) >= 0 || value.indexOf(tail) >= 0)) ||
            id.indexOf(value) >= 0 || whole.indexOf(value) >= 0) {{
          return i;
        }}
      }}
    }}
    return current + 1 < SCREENS.length ? current + 1 : null;
  }}

  function fit() {{
    var device = devices[index];
    if (!device || !stage) return;
    var dw = parseFloat(device.getAttribute("data-w")) || 1;
    var dh = parseFloat(device.getAttribute("data-h")) || 1;
    var scale = Math.min(stage.clientWidth / dw, stage.clientHeight / dh) * 0.98;
    if (!isFinite(scale) || scale <= 0) scale = 1;
    device.style.transform = "scale(" + scale + ")";
    holder.style.width = (dw * scale) + "px";
    holder.style.height = (dh * scale) + "px";
  }}

  function show(target) {{
    if (!devices.length) return;
    if (target < 0 || target >= devices.length) return;
    index = target;
    devices.forEach(function (device, i) {{
      device.classList.toggle("active", i === target);
    }});
    if (countEl) countEl.textContent = (target + 1) + " / " + devices.length;
    if (dotsEl) {{
      Array.prototype.forEach.call(dotsEl.children, function (dot, i) {{
        dot.classList.toggle("on", i === target);
      }});
    }}
    if (prevEl) prevEl.disabled = target === 0;
    if (nextEl) nextEl.disabled = target === devices.length - 1;
    fit();
  }}

  function setSlider(el, clientX) {{
    var rect = el.getBoundingClientRect();
    var width = rect.width || 1;
    var value = Math.max(0, Math.min(1, (clientX - rect.left) / width));
    el.style.setProperty("--v", String(value));
  }}

  if (devices.length) {{
    devices.forEach(function (_, i) {{
      var dot = document.createElement("button");
      dot.type = "button";
      dot.className = "dot";
      dot.setAttribute("aria-label", "第 " + (i + 1) + " 屏");
      dot.addEventListener("click", function () {{ show(i); }});
      if (dotsEl) dotsEl.appendChild(dot);
    }});

    if (prevEl) prevEl.addEventListener("click", function () {{ show(index - 1); }});
    if (nextEl) nextEl.addEventListener("click", function () {{ show(index + 1); }});

    holder.addEventListener("pointerdown", function (event) {{
      var slider = event.target.closest ? event.target.closest('[data-type="slider"]') : null;
      if (!slider) return;
      event.preventDefault();
      dragSlider = slider;
      if (slider.setPointerCapture) {{
        try {{ slider.setPointerCapture(event.pointerId); }} catch (e) {{}}
      }}
      setSlider(slider, event.clientX);
    }});

    holder.addEventListener("pointermove", function (event) {{
      if (!dragSlider) return;
      event.preventDefault();
      setSlider(dragSlider, event.clientX);
    }});

    function endDrag() {{ dragSlider = null; }}
    holder.addEventListener("pointerup", endDrag);
    holder.addEventListener("pointercancel", endDrag);
    window.addEventListener("pointerup", endDrag);

    holder.addEventListener("click", function (event) {{
      var node = event.target.closest ? event.target.closest("[data-type]") : null;
      if (!node) return;
      var type = node.getAttribute("data-type");
      if (type === "button" || type === "pill") {{
        var label = node.getAttribute("data-text") || node.getAttribute("data-name");
        var target = nextScreen(label, index);
        if (target !== null) show(target);
      }} else if (type === "switch") {{
        node.classList.toggle("on");
      }} else if (type === "nav") {{
        var items;
        try {{ items = JSON.parse(node.getAttribute("data-items") || "[]"); }}
        catch (e) {{ items = []; }}
        if (!items.length) items = ["首页", "任务", "我的"];
        var rect = node.getBoundingClientRect();
        var cell = rect.width / items.length;
        var active = Math.round((event.clientX - rect.left) / (cell || 1));
        active = Math.max(0, Math.min(items.length - 1, active));
        Array.prototype.forEach.call(node.querySelectorAll(".nav-cell"), function (cellEl, i) {{
          cellEl.classList.toggle("on", i === active);
        }});
        var next = nextScreen(items[active], index);
        if (next !== null) show(next);
      }}
    }});

    holder.addEventListener("keydown", function (event) {{
      if (event.key !== "Enter" && event.key !== " ") return;
      var node = event.target.closest ? event.target.closest('[data-type="button"],[data-type="pill"]') : null;
      if (!node) return;
      event.preventDefault();
      node.click();
    }});

    document.addEventListener("keydown", function (event) {{
      if (event.key === "ArrowLeft") show(index - 1);
      if (event.key === "ArrowRight") show(index + 1);
    }});

    window.addEventListener("resize", fit);
    window.addEventListener("orientationchange", function () {{ setTimeout(fit, 120); }});
    if (window.ResizeObserver) new ResizeObserver(fit).observe(stage);
    show(0);
  }}
}})();
</script>
</body>
</html>
"""

_CSS = """
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
html,body{height:100%;background:#0B0E12}
body{
  color:#fff;overflow:hidden;
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;
  -webkit-font-smoothing:antialiased;
}
.wrap{position:fixed;inset:0;display:flex;flex-direction:column}
.top{display:flex;align-items:center;gap:10px;flex:none;padding:calc(10px + env(safe-area-inset-top)) 18px 4px}
.badge{width:26px;height:26px;border-radius:50%;background:rgba(255,255,255,.12);display:flex;align-items:center;justify-content:center;font-size:11px;color:#fff;flex:none}
.meta{flex:1;min-width:0}
.title{font-size:12px;font-weight:500;color:#fff}
.sub{font-size:9px;color:rgba(255,255,255,.45);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.count{flex:none;font-size:9px;color:rgba(255,255,255,.4);font-variant-numeric:tabular-nums}
.stage{flex:1;min-height:0;display:flex;align-items:center;justify-content:center;padding:2px 20px}
.holder{position:relative;flex:none}
.device{position:absolute;top:0;left:0;transform-origin:top left;border-radius:26px;overflow:hidden;display:none;background:#fff;box-shadow:0 26px 60px rgba(0,0,0,.55)}
.device.active{display:block}
.screen{position:absolute;inset:0}
.empty{font-size:12px;color:rgba(255,255,255,.45)}
.foot{flex:none;display:flex;flex-direction:column;align-items:center;gap:8px;padding:6px 18px calc(10px + env(safe-area-inset-bottom))}
.hint{font-size:8.5px;color:rgba(255,255,255,.28)}
.pager{display:flex;align-items:center;gap:4px;padding:4px 8px;border-radius:999px;background:rgba(255,255,255,.10)}
.pg{width:26px;height:26px;padding:0;border:0;border-radius:50%;background:rgba(255,255,255,.14);color:rgba(255,255,255,.82);font:inherit;font-size:15px;line-height:1;display:flex;align-items:center;justify-content:center;cursor:pointer}
.pg:disabled{background:rgba(255,255,255,.05);color:rgba(255,255,255,.22);cursor:default}
.dots{display:flex;align-items:center;gap:3px;padding:0 4px}
.dot{width:6px;height:6px;padding:0;border:0;border-radius:50%;background:rgba(255,255,255,.28);cursor:pointer;transition:width .15s,height .15s,background .15s}
.dot.on{width:8px;height:8px;background:#fff}

.n{position:absolute}
.t-text{display:flex;align-items:center;overflow:hidden}
.t-button,.t-pill{display:flex;align-items:center;overflow:hidden;cursor:pointer;-webkit-tap-highlight-color:transparent;transition:filter .12s ease}
.t-button:active,.t-pill:active{filter:brightness(.94)}
.t-card{overflow:visible}
.t-decor,.t-circle{pointer-events:none}
.t-nav{display:flex;overflow:hidden;cursor:pointer}
.t-input{display:flex;align-items:center;overflow:hidden}
.t-input input{width:100%;height:100%;border:0;outline:0;background:transparent;font:inherit;color:inherit;padding:0}
.t-input input::placeholder{color:currentColor;opacity:.5}
.t-slider{cursor:pointer;touch-action:none;-webkit-tap-highlight-color:transparent}
.t-switch{background:#E2E8F0;cursor:pointer;transition:background-color .18s ease;-webkit-tap-highlight-color:transparent}
.t-switch.on{background:var(--c-on,#0891B2)}
.sw-knob{position:absolute;top:var(--knob-top,3px);left:3px;width:var(--knob,28px);height:var(--knob,28px);border-radius:50%;background:#fff;box-shadow:0 1px 2px rgba(0,0,0,.18);transition:left .18s cubic-bezier(.2,.8,.2,1)}
.t-switch.on .sw-knob{left:calc(100% - var(--knob,28px) - 3px)}

.tx{display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:3;overflow:hidden;width:100%;white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word}

.sl-track,.sl-fill{position:absolute;left:0;top:50%;height:4px;margin-top:-2px;border-radius:2px}
.sl-track{width:100%}
.sl-fill{width:calc(var(--v, .5) * 100%)}
.sl-thumb,.sl-ring{position:absolute;top:50%;left:calc(var(--v, .5) * 100%);border-radius:50%}
.sl-thumb{width:16px;height:16px;margin-top:-8px;margin-left:-8px;background:#fff}
.sl-ring{width:20px;height:20px;margin-top:-10px;margin-left:-10px;pointer-events:none}

.nav-cell{flex:1;min-width:0;position:relative;display:flex;align-items:center;justify-content:center}
.nav-cell span{transform:translateY(-2px);max-width:100%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.nav-cell i{position:absolute;bottom:4px;left:50%;width:12px;height:2px;margin-left:-6px;border-radius:1px;opacity:0}
.nav-cell.on i{opacity:.9}
"""

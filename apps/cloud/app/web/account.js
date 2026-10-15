/* Flux Cloud · 账号页 */
(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };

  var toastTimer = null;
  function toast(message, isError) {
    var el = $("toast");
    el.textContent = message;
    el.className = "toast show" + (isError ? " err" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.className = "toast"; }, 2800);
  }

  function pageError(message) {
    var el = $("pageAlert");
    if (!message) { el.className = "alert alert-error"; return; }
    el.textContent = message;
    el.className = "alert alert-error show";
  }

  function pickMessage(payload, fallback) {
    if (!payload) return fallback;
    var detail = payload.detail;
    if (typeof detail === "string") return detail;
    if (Array.isArray(detail) && detail.length) {
      return detail.map(function (item) { return item.msg || ""; }).filter(Boolean).join("；") || fallback;
    }
    return payload.message || fallback;
  }

  function formatBytes(bytes) {
    if (!bytes && bytes !== 0) return "—";
    var units = ["B", "KB", "MB", "GB"];
    var value = bytes;
    var i = 0;
    while (value >= 1024 && i < units.length - 1) { value /= 1024; i += 1; }
    return (i === 0 ? value : value.toFixed(1)) + " " + units[i];
  }

  function formatTime(value) {
    if (!value) return "—";
    var date = new Date(value);
    if (isNaN(date.getTime())) return String(value);
    var pad = function (n) { return String(n).padStart(2, "0"); };
    return (
      date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate()) +
      " " + pad(date.getHours()) + ":" + pad(date.getMinutes())
    );
  }

  function escapeHtml(text) {
    return String(text == null ? "" : text).replace(/[&<>"']/g, function (ch) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch];
    });
  }

  var refreshing = null;

  async function rawApi(path, options) {
    var opts = options || {};
    var init = {
      method: opts.method || "GET",
      credentials: "include",
      headers: { Accept: "application/json" },
    };
    if (opts.body instanceof FormData) {
      init.body = opts.body;
    } else if (opts.body !== undefined) {
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(opts.body);
    }
    var res = await fetch(path, init);
    var payload = null;
    try { payload = await res.json(); } catch (err) { payload = null; }
    if (!res.ok) {
      var error = new Error(pickMessage(payload, "请求失败（HTTP " + res.status + "）"));
      error.status = res.status;
      throw error;
    }
    return payload;
  }

  async function api(path, options) {
    try {
      return await rawApi(path, options);
    } catch (err) {
      if (err.status !== 401) throw err;
      // access token 过期：先续期再重试一次
      if (!refreshing) {
        refreshing = rawApi("/api/auth/refresh", { method: "POST" }).finally(function () {
          refreshing = null;
        });
      }
      try {
        await refreshing;
      } catch (refreshErr) {
        location.href = "/login?next=" + encodeURIComponent(location.pathname);
        throw err;
      }
      return await rawApi(path, options);
    }
  }

  async function loadProfile() {
    var me = await api("/api/auth/me");
    var user = me.user;
    $("whoName").textContent = user.username;
    $("userId").textContent = "#" + user.id;
    $("vUsername").textContent = user.username;
    $("deleteUsername").textContent = user.username;
    $("vEmail").textContent = user.email || "未设置";
    $("vCreated").textContent = formatTime(user.created_at);
    $("vLastLogin").textContent = formatTime(user.last_login_at);
    if (user.avatar_url) {
      var avatar = $("avatar");
      avatar.src = user.avatar_url;
      avatar.hidden = false;
    }
    $("vPassword").innerHTML = user.must_change_password
      ? '<span class="pill pill-warn">初始密码，建议修改</span>'
      : '<span class="pill pill-ok">已设置</span>';

    var providers = me.providers || {};
    var note = [];
    if (providers.github) note.push("github");
    if (providers.google) note.push("google");
    $("providerNote").textContent = note.length ? note.join(" · ") : "未启用";

    var list = $("identityList");
    if (!me.identities.length) {
      list.innerHTML = '<div class="empty">当前只用邮箱密码登录</div>';
    } else {
      list.innerHTML = me.identities
        .map(function (item) {
          var label = item.provider === "github" ? "GitHub" : item.provider === "google" ? "Google" : item.provider;
          var account = item.provider_login || item.provider_email || "—";
          return (
            '<div class="row"><div class="row-main">' +
            '<span class="row-name">' + escapeHtml(label) + "</span>" +
            '<span class="row-meta">' + escapeHtml(account) + "</span>" +
            '<span class="row-meta">绑定于 ' + formatTime(item.created_at) + "</span>" +
            "</div><span class=\"pill pill-ok\">已绑定</span></div>"
          );
        })
        .join("");
    }
  }

  async function loadInitialPassword() {
    var data = await api("/api/auth/reveal");
    if (!data || !data.password) return;
    $("initialPassword").textContent = data.password;
    $("initialAlert").className = "alert alert-ok show";
  }

  function renderLogs(payload) {
    $("logCount").textContent = payload.total + " 条";
    var list = $("logList");
    if (!payload.items.length) {
      list.innerHTML = '<div class="empty">还没有上传过运行日志</div>';
      return;
    }
    list.innerHTML = payload.items
      .map(function (item) {
        var range =
          "区间 " + formatTime(item.prev_run_started_at) + " → " + formatTime(item.run_started_at);
        var meta = [
          formatBytes(item.size_bytes),
          item.client_version ? "v" + item.client_version : null,
          item.os_info || null,
        ].filter(Boolean).join(" · ");
        return (
          '<div class="row"><div class="row-main">' +
          '<span class="row-name">' + escapeHtml(item.original_name) + "</span>" +
          '<span class="row-meta">' + escapeHtml(range) + "</span>" +
          '<span class="row-meta">' + escapeHtml(meta || "—") + "</span>" +
          '<span class="row-meta">上传于 ' + formatTime(item.created_at) + "</span>" +
          "</div><div class=\"row-actions\">" +
          '<button class="btn btn-sm" data-download="' + item.id + '">下载</button>' +
          '<button class="btn btn-sm btn-danger" data-delete="' + item.id + '">删除</button>' +
          "</div></div>"
        );
      })
      .join("");

    list.querySelectorAll("[data-download]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        var id = btn.getAttribute("data-download");
        location.href = "/api/logs/" + id + "/download";
      });
    });
    list.querySelectorAll("[data-delete]").forEach(function (btn) {
      btn.addEventListener("click", async function () {
        var id = btn.getAttribute("data-delete");
        if (!confirm("确定删除这条日志？删除后无法恢复。")) return;
        btn.disabled = true;
        try {
          await api("/api/logs/" + id, { method: "DELETE" });
          toast("已删除");
          await refreshLogs();
        } catch (err) {
          btn.disabled = false;
          toast(err.message || "删除失败", true);
        }
      });
    });
  }

  async function refreshLogs() {
    try {
      renderLogs(await api("/api/logs?limit=20"));
    } catch (err) {
      $("logList").innerHTML = '<div class="empty">' + escapeHtml(err.message || "日志加载失败") + "</div>";
    }
  }

  async function submitPassword(event) {
    event.preventDefault();
    pageError("");
    var next = $("newPassword").value;
    if (next.length < 8) { toast("新密码至少 8 位", true); return; }
    var button = $("passwordBtn");
    button.disabled = true;
    try {
      var payload = { new_password: next };
      var current = $("currentPassword").value;
      if (current) payload.current_password = current;
      await api("/api/auth/password", { method: "POST", body: payload });
      toast("密码已更新");
      $("passwordForm").reset();
      $("initialAlert").className = "alert alert-ok";
      await loadProfile();
    } catch (err) {
      toast(err.message || "修改失败", true);
    } finally {
      button.disabled = false;
    }
  }

  async function submitDelete() {
    var username = $("deleteUsername").textContent;
    var typed = $("deleteConfirm").value.trim();
    if (typed !== username) {
      toast("请输入完整用户名以确认注销", true);
      return;
    }
    if (!confirm("账号及其全部数据（登录渠道、会话、已上传日志）将被永久删除，且无法恢复。确定要继续吗？")) {
      return;
    }
    var button = $("deleteAccount");
    button.disabled = true;
    button.textContent = "注销中…";
    try {
      var body = { confirm_username: typed };
      var password = $("deletePassword").value;
      if (password) body.password = password;
      await api("/api/auth/delete", { method: "POST", body: body });
      toast("账号已注销");
      setTimeout(function () { location.href = "/login"; }, 900);
    } catch (err) {
      button.disabled = false;
      button.textContent = "永久注销账号";
      toast(err.message || "注销失败", true);
    }
  }

  async function boot() {
    if (!document.getElementById("logList")) return;

    try {
      await loadProfile();
    } catch (err) {
      location.href = "/login?next=" + encodeURIComponent(location.pathname);
      return;
    }

    $("passwordForm").addEventListener("submit", submitPassword);
    $("logout").addEventListener("click", async function () {
      try { await api("/api/auth/logout", { method: "POST" }); } catch (err) { /* 忽略 */ }
      location.href = "/login";
    });
    $("deleteAccount").addEventListener("click", submitDelete);
    $("copyPassword").addEventListener("click", async function () {
      var text = $("initialPassword").textContent;
      try {
        await navigator.clipboard.writeText(text);
        toast("已复制到剪贴板");
      } catch (err) {
        toast("复制失败，请手动选择文本", true);
      }
    });
    $("ackPassword").addEventListener("click", async function () {
      try {
        await api("/api/auth/reveal/ack", { method: "POST" });
      } catch (err) { /* 忽略 */ }
      $("initialAlert").className = "alert alert-ok";
      toast("已确认，请妥善保管密码");
    });

    await loadInitialPassword().catch(function () { /* 无初始密码 */ });
    await refreshLogs();
  }

  boot();
})();

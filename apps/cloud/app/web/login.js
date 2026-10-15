/* Flux Cloud · 登录 / 注册页 */
(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };
  var params = new URLSearchParams(location.search);
  var nextUrl = params.get("next") || "/account";
  var mode = "login";

  var ICONS = {
    github:
      '<svg viewBox="0 0 16 16" aria-hidden="true" fill="currentColor">' +
      '<path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z"/></svg>',
    google:
      '<svg viewBox="0 0 48 48" aria-hidden="true">' +
      '<path fill="#4285F4" d="M45.12 24.5c0-1.56-.14-3.06-.4-4.5H24v8.51h11.84c-.51 2.75-2.06 5.08-4.39 6.64v5.52h7.11c4.16-3.83 6.56-9.47 6.56-16.17Z"/>' +
      '<path fill="#34A853" d="M24 46c5.94 0 10.92-1.97 14.56-5.33l-7.11-5.52c-1.97 1.32-4.49 2.1-7.45 2.1-5.73 0-10.58-3.87-12.31-9.07H4.34v5.7C7.96 41.07 15.4 46 24 46Z"/>' +
      '<path fill="#FBBC05" d="M11.69 28.18C11.25 26.86 11 25.45 11 24s.25-2.86.69-4.18v-5.7H4.34A21.9 21.9 0 0 0 2 24c0 3.55.85 6.91 2.34 9.88l7.35-5.7Z"/>' +
      '<path fill="#EA4335" d="M24 10.75c3.23 0 6.13 1.11 8.41 3.29l6.31-6.31C34.91 4.18 29.93 2 24 2 15.4 2 7.96 6.93 4.34 14.12l7.35 5.7c1.73-5.2 6.58-9.07 12.31-9.07Z"/></svg>',
  };

  var toastTimer = null;
  function toast(message, isError) {
    var el = $("toast");
    el.textContent = message;
    el.className = "toast show" + (isError ? " err" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.className = "toast"; }, 2600);
  }

  function showError(message) {
    var el = $("alert");
    el.textContent = message;
    el.className = "alert alert-error show";
  }

  function clearError() {
    $("alert").className = "alert alert-error";
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

  async function api(path, options) {
    var opts = options || {};
    var init = {
      method: opts.method || "GET",
      credentials: "include",
      headers: { Accept: "application/json" },
    };
    if (opts.body !== undefined) {
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

  function renderProviders(providers) {
    var row = $("oauthRow");
    var divider = $("oauthDivider");
    var enabled = [];
    if (providers.github) enabled.push("github");
    if (providers.google) enabled.push("google");
    if (!enabled.length) {
      row.hidden = true;
      divider.hidden = true;
      $("providerNote").textContent = "未启用第三方登录";
      return;
    }
    row.innerHTML = enabled
      .map(function (name) {
        var label = name === "github" ? "使用 GitHub 继续" : "使用 Google 继续";
        return (
          '<button class="btn-oauth" type="button" data-provider="' + name + '">' +
          ICONS[name] + "<span>" + label + "</span></button>"
        );
      })
      .join("");
    row.querySelectorAll("button[data-provider]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        var provider = btn.getAttribute("data-provider");
        btn.disabled = true;
        location.href =
          "/api/auth/oauth/" + provider + "/start?next=" + encodeURIComponent(nextUrl);
      });
    });
    $("providerNote").textContent = enabled.join(" · ");
  }

  function setMode(next) {
    mode = next;
    document.querySelectorAll(".tab").forEach(function (tab) {
      tab.setAttribute("aria-selected", String(tab.getAttribute("data-mode") === mode));
    });
    var isRegister = mode === "register";
    $("authTitle").textContent = isRegister ? "创建 Flux 账号" : "登录 Flux 账号";
    $("submit").textContent = isRegister ? "注册并登录" : "登录";
    $("formHint").textContent = isRegister
      ? "注册后即可在桌面端用同一账号登录。"
      : "用邮箱注册后，桌面端可直接用同一账号登录。";
    $("fieldEmail").hidden = !isRegister;
    $("fieldIdentifier").hidden = isRegister;
    $("fieldUsername").hidden = !isRegister;
    $("password").setAttribute("autocomplete", isRegister ? "new-password" : "current-password");
    clearError();
  }

  async function submit(event) {
    event.preventDefault();
    clearError();

    var password = $("password").value;
    if (password.length < 8) {
      showError("密码至少 8 位");
      return;
    }

    var button = $("submit");
    button.disabled = true;
    button.textContent = mode === "register" ? "注册中…" : "登录中…";
    try {
      if (mode === "register") {
        var email = $("email").value.trim();
        var username = $("username").value.trim();
        if (!email) { throw new Error("请填写邮箱"); }
        var body = { email: email, password: password };
        if (username) body.username = username;
        await api("/api/auth/register", { method: "POST", body: body });
      } else {
        var identifier = $("identifier").value.trim();
        if (!identifier) { throw new Error("请填写邮箱或用户名"); }
        await api("/api/auth/login", {
          method: "POST",
          body: { identifier: identifier, password: password },
        });
      }
      toast("登录成功，正在进入…");
      location.href = nextUrl;
    } catch (err) {
      showError(err.message || "操作失败，请重试");
      button.disabled = false;
      button.textContent = mode === "register" ? "注册并登录" : "登录";
    }
  }

  async function boot() {
    var errorParam = params.get("error");
    if (errorParam) showError(errorParam);

    document.querySelectorAll(".tab").forEach(function (tab) {
      tab.addEventListener("click", function () { setMode(tab.getAttribute("data-mode")); });
    });
    $("form").addEventListener("submit", submit);

    try {
      await api("/api/auth/me");
      location.href = nextUrl;
      return;
    } catch (err) {
      /* 未登录属正常情况 */
    }

    try {
      renderProviders(await api("/api/auth/providers"));
    } catch (err) {
      $("providerNote").textContent = "渠道状态获取失败";
    }
  }

  boot();
})();

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json.Nodes;
using Flux.Desktop.Models;
using Flux.Desktop.Services;

namespace Flux.Desktop.Tests;

/// <summary>
/// Solo 纯逻辑自测（零第三方依赖，避免离线环境还原包失败）。
/// 断言失败立即返回非零退出码，可直接接进 CI 或本地脚本。
/// </summary>
internal static class Program
{
    private static int _failed;

    private static int Main()
    {
        CheckFlowStep();
        CheckStatusLabels();
        CheckDiffKind();
        CheckRunTerminal();
        CheckSessionConflict();
        CheckChatKey();
        CheckTitle();
        CheckTotals();
        CheckDesignOps();
        CheckBrowserProxy();

        if (_failed == 0)
        {
            Console.WriteLine("desktop-selftest: all checks passed");
            return 0;
        }

        Console.Error.WriteLine($"desktop-selftest: {_failed} check(s) failed");
        return 1;
    }

    private static void CheckFlowStep()
    {
        Equal("新建任务在第 1 步", 1, SoloRules.FlowStep("pending", false));
        Equal("有确认后进入第 2 步", 2, SoloRules.FlowStep("pending", true));
        Equal("运行中第 4 步", 4, SoloRules.FlowStep("running", true));
        Equal("等待决策第 4 步", 4, SoloRules.FlowStep("waiting_for_user_decision", true));
        Equal("失败也停在执行步", 4, SoloRules.FlowStep("failed", true));
        Equal("完成第 7 步", 7, SoloRules.FlowStep("completed", true));
    }

    private static void CheckStatusLabels()
    {
        Equal("运行中文案", "执行中", SoloRules.StatusLabel("running"));
        Equal("运行中色键", "run", SoloRules.StatusTone("running"));
        Equal("等待色键", "wait", SoloRules.StatusTone("waiting_for_user_decision"));
        Equal("失败色键", "fail", SoloRules.StatusTone("failed"));
        True("running 是活动态", SoloRules.IsActiveStatus("running"));
        True("completed 是终态", SoloRules.IsTerminalStatus("completed"));
        True("pending 不是终态", !SoloRules.IsTerminalStatus("pending"));
    }

    private static void CheckDiffKind()
    {
        Equal("新增行", "ins", SoloRules.DiffKind("+ var x = 1;"));
        Equal("删除行", "del", SoloRules.DiffKind("- var x = 1;"));
        Equal("块头", "hunk", SoloRules.DiffKind("@@ -1,3 +1,4 @@"));
        Equal("文件头不算增删", "plain", SoloRules.DiffKind("+++ b/a.cs"));
        Equal("上下文行", "plain", SoloRules.DiffKind("  keep"));
    }

    private static void CheckRunTerminal()
    {
        foreach (var status in new[] { "completed", "failed", "timeout", "cancelled", "interrupted" })
        {
            True($"Run {status} 是终态", SoloRules.IsRunTerminal(status));
        }

        True("Run running 未终止", !SoloRules.IsRunTerminal("running"));
        True("Run cancelling 仍在轮询", !SoloRules.IsRunTerminal("cancelling"));
        Equal("Run 文案", "已停止", SoloRules.RunLabel("cancelled"));
    }

    private static void CheckSessionConflict()
    {
        True(
            "会话冲突被识别",
            SoloRules.IsSessionConflict("session \"flux-abc\" already exists"));
        True("空错误不是冲突", !SoloRules.IsSessionConflict(null));
        True("其他失败不是冲突", !SoloRules.IsSessionConflict("Agent 执行出错"));
    }

    private static void CheckChatKey()
    {
        Equal("任务命名空间", "flux.solo.agent.t1", SoloRules.AgentChatKey("t1"));
        Equal("空任务命名空间", "flux.solo.agent.none", SoloRules.AgentChatKey(null));
        Equal("短 id 截断", "12345678", SoloRules.ShortId("1234567890"));
        Equal("空 id", "—", SoloRules.ShortId(null));
    }

    private static void CheckTitle()
    {
        Equal("首行标题", "先做登录", SoloRules.Title("先做登录\n再做权限"));
        True("空描述有兜底", SoloRules.Title("   ").Length > 0);
        True("长标题截断", SoloRules.Title(new string('a', 60)).Length <= 27);
    }

    private static void CheckTotals()
    {
        var items = new List<WorkspaceChange>
        {
            Change("a.cs", 3, 1, "pending"),
            Change("a.cs", 2, 0, "applied"),
            Change("b.cs", 0, 4, "pending"),
        };
        var totals = SoloRules.Totals(items);
        Equal("文件去重", 2, totals.Files);
        Equal("增加行汇总", 5, totals.Added);
        Equal("删除行汇总", 5, totals.Removed);
    }

    private static void CheckDesignOps()
    {
        var design = (JsonObject)JsonNode.Parse("""
        {
          "name": "draft",
          "screens": [
            {
              "id": "s1", "name": "首页", "w": 390, "h": 844,
              "nodes": [
                { "id": "n1", "type": "card", "x": 10, "y": 20, "w": 300, "h": 120, "radius": 16, "children": [
                  { "id": "n1a", "type": "text", "x": 0, "y": 0, "w": 100, "h": 20, "text": "标题" }
                ]},
                { "id": "n2", "type": "text", "x": 10, "y": 160, "w": 200, "h": 24, "text": "副标题" }
              ]
            },
            { "id": "s2", "name": "详情", "w": 390, "h": 844, "nodes": [] }
          ]
        }
        """)!;

        True("找到第 1 块画板", DesignOps.FindScreen(design, 0) is not null);
        True("越界画板为空", DesignOps.FindScreen(design, 5) is null);

        var ask = DesignOps.BuildAskScreen(DesignOps.FindScreen(design, 0)!);
        Equal("AI 快照画板宽", "390", ask["w"]!.ToJsonString());
        Equal("AI 快照顶层节点数", 2, ask["nodes"]!.AsArray().Count);
        var firstChild = ask["nodes"]!.AsArray()[0]!.AsObject()["children"]!.AsArray();
        Equal("AI 快照展开子层", 1, firstChild.Count);

        // set：白名单过滤 + 数值归一化，每个被处理的键计 1 项
        var sets = (JsonArray)JsonNode.Parse("""
        [
          { "op": "set", "target": "n2", "set": { "text": "新标题", "w": 0, "radius": -8, "opacity": 3, "fontSize": 2, "fontWeight": 0, "unknown": 1 } },
          { "op": "set", "target": "missing", "set": { "text": "x" } },
          { "set": { "target": "n2" } }
        ]
        """)!;
        Equal("set 只计白名单键", 6, DesignOps.ApplyOps(design, 0, sets, out var setError));
        True("有修改时无错误", setError is null);
        var n2 = DesignOps.FindScreen(design, 0)!["nodes"]!.AsArray()[1]!.AsObject();
        Equal("文本已更新", "新标题", n2["text"]!.GetValue<string>());
        Equal("宽最小 1", "1", n2["w"]!.ToJsonString());
        Equal("圆角最小 0", "0", n2["radius"]!.ToJsonString());
        Equal("透明度上限 1", "1", n2["opacity"]!.ToJsonString());
        Equal("字号最小 6", "6", n2["fontSize"]!.ToJsonString());
        Equal("字重 0 归一 400", "400", n2["fontWeight"]!.ToJsonString());
        True("白名单外的键不写入", n2["unknown"] is null);

        // add / reorder / remove：重复 id 与缺失目标不计入
        var structure = (JsonArray)JsonNode.Parse("""
        [
          { "op": "add", "node": { "id": "n3", "type": "text", "x": 0, "y": 0, "w": 100, "h": 20, "text": "新增" } },
          { "op": "add", "index": 0, "node": { "id": "n0", "type": "text", "x": 0, "y": 0, "w": 100, "h": 20, "text": "置顶" } },
          { "op": "add", "node": { "id": "n1", "type": "text" } },
          { "op": "reorder", "target": "n3", "index": 0 },
          { "op": "remove", "target": "n0" },
          { "op": "remove", "target": "missing" }
        ]
        """)!;
        Equal("结构操作计数", 4, DesignOps.ApplyOps(design, 0, structure, out var structureError));
        True("结构操作无错误", structureError is null);
        var order = string.Join(",", DesignOps.FindScreen(design, 0)!["nodes"]!.AsArray()
            .Select(n => n!["id"]!.GetValue<string>()));
        Equal("节点顺序", "n3,n1,n2", order);

        // 子层快照：最多 80 个、只展开两层
        var wide = new JsonObject { ["id"] = "wide", ["type"] = "card", ["children"] = new JsonArray() };
        var wideChildren = wide["children"]!.AsArray();
        for (var i = 0; i < 100; i++)
        {
            wideChildren.Add(new JsonObject { ["id"] = $"c{i}", ["type"] = "text" });
        }
        var wideSnap = DesignOps.SnapshotNodes(new JsonArray(wide))[0]!.AsObject();
        Equal("子层最多 80", 80, wideSnap["children"]!.AsArray().Count);

        var deep = (JsonObject)JsonNode.Parse("""
        { "id": "a", "type": "card", "children": [
          { "id": "b", "type": "card", "children": [
            { "id": "c", "type": "card", "children": [ { "id": "d", "type": "text" } ] }
          ]}
        ]}
        """)!;
        var level2 = DesignOps.SnapshotNodes(new JsonArray(deep))[0]!.AsObject()["children"]!.AsArray()[0]!
            .AsObject()["children"]!.AsArray()[0]!.AsObject();
        True("快照只展开两层", level2["children"] is null);

        // 无可用修改与画板越界的报错文案
        Equal("空补丁无修改", 0, DesignOps.ApplyOps(design, 0, new JsonArray(), out var emptyError));
        Equal("空补丁报错", "AI 给的修改无法应用", emptyError);
        Equal("越界画板不应用", 0, DesignOps.ApplyOps(design, 9, new JsonArray(), out var screenError));
        Equal("越界画板报错", "当前画板不存在", screenError);
    }

    private static void CheckBrowserProxy()
    {
        // 模式解析：未知值一律回落到跟随系统
        Equal("空模式跟随系统", BrowserProxyMode.System, BrowserProxy.ParseMode(null));
        Equal("direct 模式", BrowserProxyMode.Direct, BrowserProxy.ParseMode(" DIRECT "));
        Equal("custom 模式", BrowserProxyMode.Custom, BrowserProxy.ParseMode("custom"));
        Equal("未知模式跟随系统", BrowserProxyMode.System, BrowserProxy.ParseMode("global"));

        // 代理地址归一化
        True("省略协议默认 http", BrowserProxy.TryNormalizeProxy("127.0.0.1:7890", out var n1, out _));
        Equal("默认协议结果", "http://127.0.0.1:7890", n1);
        True("socks5 保留", BrowserProxy.TryNormalizeProxy("socks5://127.0.0.1:7891", out var n2, out _));
        Equal("socks5 结果", "socks5://127.0.0.1:7891", n2);
        True("协议大小写归一", BrowserProxy.TryNormalizeProxy("SOCKS5://127.0.0.1:7891", out var n3, out _));
        Equal("协议小写结果", "socks5://127.0.0.1:7891", n3);
        True("尾部斜杠归一", BrowserProxy.TryNormalizeProxy("http://127.0.0.1:7890/", out var n4, out _));
        Equal("尾部斜杠结果", "http://127.0.0.1:7890", n4);
        True("IPv6 代理", BrowserProxy.TryNormalizeProxy("[::1]:7890", out var n5, out _));
        Equal("IPv6 代理结果", "http://[::1]:7890", n5);

        True("空代理报错", !BrowserProxy.TryNormalizeProxy("  ", out _, out var e1));
        True("空代理有提示", e1.Length > 0);
        True("https 代理不支持", !BrowserProxy.TryNormalizeProxy("https://127.0.0.1:7890", out _, out _));
        True("缺端口报错", !BrowserProxy.TryNormalizeProxy("127.0.0.1", out _, out _));
        True("端口 0 报错", !BrowserProxy.TryNormalizeProxy("127.0.0.1:0", out _, out _));
        True("端口越界报错", !BrowserProxy.TryNormalizeProxy("127.0.0.1:70000", out _, out _));
        True("带路径报错", !BrowserProxy.TryNormalizeProxy("127.0.0.1:7890/path", out _, out _));
        True("带空格报错", !BrowserProxy.TryNormalizeProxy("127.0.0.1 :7890", out _, out _));

        // 偏好 → 配置
        Equal("direct 偏好", BrowserProxyMode.Direct, BrowserProxy.FromPrefs("direct", "", out _).Mode);
        var custom = BrowserProxy.FromPrefs("custom", "127.0.0.1:7890", out var customError);
        Equal("custom 偏好模式", BrowserProxyMode.Custom, custom.Mode);
        Equal("custom 偏好地址", "http://127.0.0.1:7890", custom.ProxyUrl);
        True("custom 无错误", customError is null);
        var broken = BrowserProxy.FromPrefs("custom", "not a proxy", out var brokenError);
        Equal("非法 custom 回退系统", BrowserProxyMode.System, broken.Mode);
        True("非法 custom 有错误", brokenError is not null && brokenError.Length > 0);

        // 地址栏解析
        True("完整 URL 直通", BrowserProxy.TryResolveAddress("https://example.com/a?b=1", out var a1));
        Equal("完整 URL 结果", "https://example.com/a?b=1", a1.AbsoluteUri);
        True("裸域名补 https", BrowserProxy.TryResolveAddress("example.com", out var a2));
        Equal("裸域名结果", "https://example.com/", a2.AbsoluteUri);
        True("IP 补 http", BrowserProxy.TryResolveAddress("127.0.0.1:8801", out var a3));
        Equal("IP 结果", "http://127.0.0.1:8801/", a3.AbsoluteUri);
        True("关键词走搜索", BrowserProxy.TryResolveAddress("hello world", out var a4));
        Equal("搜索前缀", "https://www.bing.com/search?q=hello%20world", a4.AbsoluteUri);
        True("空输入不解析", !BrowserProxy.TryResolveAddress("   ", out _));
        True("非 http 协议不解析", !BrowserProxy.TryResolveAddress("ftp://example.com", out _));

        // 私网 / 回环拦截
        foreach (var url in new[]
                 {
                     "http://127.0.0.1:8801/", "http://localhost:8801/", "http://10.0.0.5/",
                     "http://192.168.1.10/", "http://172.16.0.1/", "http://172.31.255.255/",
                     "http://169.254.1.1/", "http://[::1]/", "http://[fe80::1]/", "http://[fd00::1]/",
                     "http://nas/", "http://printer.local/", "http://2130706433:8801/",
                 })
        {
            True($"拦截 {url}", BrowserProxy.IsBlocked(new Uri(url)));
        }

        foreach (var url in new[]
                 {
                     "https://www.bing.com", "http://172.32.0.1/", "http://172.15.0.1/",
                     "https://example.com", "http://8.8.8.8/",
                 })
        {
            True($"放行 {url}", !BrowserProxy.IsBlocked(new Uri(url)));
        }

        True("file 协议拦掉", BrowserProxy.IsBlocked(new Uri("file:///etc/passwd")));

        // 平台参数
        var direct = new BrowserProxyConfig(BrowserProxyMode.Direct, "");
        var httpCustom = new BrowserProxyConfig(BrowserProxyMode.Custom, "http://127.0.0.1:7890");
        var socksCustom = new BrowserProxyConfig(BrowserProxyMode.Custom, "socks5://127.0.0.1:7891");
        var system = new BrowserProxyConfig(BrowserProxyMode.System, "");

        True("系统代理不传参数", BrowserProxy.ToWebView2Arguments(system) is null);
        Equal("直连参数", "--no-proxy-server", BrowserProxy.ToWebView2Arguments(direct));
        Equal("自定义参数", "--proxy-server=http://127.0.0.1:7890", BrowserProxy.ToWebView2Arguments(httpCustom));

        Equal("WebKit 系统模式", 0, BrowserProxy.ToWebKitProxyMode(system));
        Equal("WebKit 直连模式", 1, BrowserProxy.ToWebKitProxyMode(direct));
        Equal("WebKit 自定义模式", 2, BrowserProxy.ToWebKitProxyMode(httpCustom));
        True("WebKit 系统无 URI", BrowserProxy.ToWebKitProxyUri(system) is null);
        Equal("WebKit http URI", "http://127.0.0.1:7890", BrowserProxy.ToWebKitProxyUri(httpCustom));
        Equal("WebKit socks 映射", "socks://127.0.0.1:7891", BrowserProxy.ToWebKitProxyUri(socksCustom));

        Equal("系统代理文案", "跟随系统代理", BrowserProxy.Describe(system));
        Equal("直连文案", "直连 · 不使用代理", BrowserProxy.Describe(direct));
        Equal("自定义文案", "自定义 · socks5://127.0.0.1:7891", BrowserProxy.Describe(socksCustom));
        Equal("首页地址", "https://www.bing.com", BrowserProxy.HomeUrl);
    }

    private static WorkspaceChange Change(string path, int added, int removed, string status)
        => new("id", null, null, null, path, "modify", "", added, removed, status, null, null, null, null);

    private static void Equal<T>(string name, T expected, T actual)
    {
        if (EqualityComparer<T>.Default.Equals(expected, actual))
        {
            return;
        }

        _failed++;
        Console.Error.WriteLine($"[FAIL] {name}: expected={expected} actual={actual}");
    }

    private static void True(string name, bool value)
    {
        if (value)
        {
            return;
        }

        _failed++;
        Console.Error.WriteLine($"[FAIL] {name}");
    }
}

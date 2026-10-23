using System;
using System.Collections.Generic;
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

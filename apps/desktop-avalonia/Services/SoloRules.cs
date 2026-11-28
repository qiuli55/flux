using System;
using System.Collections.Generic;
using System.Linq;

namespace Flux.Desktop.Services;

/// <summary>
/// Solo 任务的纯逻辑：状态→流程步、文案、diff 行分类、Run 终态判断。
/// 不依赖 UI 与网络，便于单测；ViewModel 只做数据搬运。
/// </summary>
public static class SoloRules
{
    public static readonly string[] FlowNames =
    [
        "需求确认", "方案规划", "任务分解", "执行中", "测试验证", "自动落盘", "完成",
    ];

    /// <summary>任务状态映射到七步流程中的当前步（1 起）。</summary>
    public static int FlowStep(string? status, bool hasConfirmation)
        => status switch
        {
            "completed" => 7,
            "running" => 4,
            "waiting_for_user_decision" => 4,
            "failed" or "cancelled" => 4,
            _ => hasConfirmation ? 2 : 1,
        };

    public static bool IsActiveStatus(string? status)
        => status is "running" or "waiting_for_user_decision";

    public static bool IsTerminalStatus(string? status)
        => status is "completed" or "failed" or "cancelled";

    public static string StatusLabel(string? status)
        => status switch
        {
            "running" => "执行中",
            "waiting_for_user_decision" => "等待你的决策",
            "completed" => "已完成",
            "failed" => "失败",
            "cancelled" => "已取消",
            "pending" => "待执行",
            _ => status ?? "未创建",
        };

    /// <summary>任务状态色键：run / wait / ok / fail / idle。</summary>
    public static string StatusTone(string? status)
        => status switch
        {
            "running" => "run",
            "waiting_for_user_decision" => "wait",
            "completed" => "ok",
            "failed" => "fail",
            "cancelled" => "idle",
            _ => "idle",
        };

    /// <summary>DSH Run 是否已落终态（终态后停止轮询）。</summary>
    public static bool IsRunTerminal(string? status)
        => status is "completed" or "failed" or "timeout" or "cancelled" or "interrupted";

    /// <summary>
    /// 是否为「会话已存在」冲突：DSH runtime 每次 Run 都起新进程，旧会话落盘后再 prompt
    /// 同一 id 会被拒（session "..." already exists）。命中后调用方降级为新会话重发一次。
    /// </summary>
    public static bool IsSessionConflict(string? error)
        => !string.IsNullOrEmpty(error)
           && error.Contains("already exists", StringComparison.OrdinalIgnoreCase)
           && error.Contains("session", StringComparison.OrdinalIgnoreCase);

    public static string RunLabel(string? status)
        => status switch
        {
            "pending" => "排队中",
            "starting" => "启动中",
            "running" => "执行中",
            "completed" => "已完成",
            "failed" => "失败",
            "timeout" => "超时",
            "cancelling" => "停止中",
            "cancelled" => "已停止",
            "interrupted" => "已中断",
            _ => status ?? "未知",
        };

    /// <summary>diff 行分类：ins / del / hunk / plain。</summary>
    public static string DiffKind(string line)
    {
        if (line.StartsWith("@@", StringComparison.Ordinal))
        {
            return "hunk";
        }

        if (line.StartsWith("+++", StringComparison.Ordinal) || line.StartsWith("---", StringComparison.Ordinal))
        {
            return "plain";
        }

        if (line.StartsWith("+", StringComparison.Ordinal))
        {
            return "ins";
        }

        return line.StartsWith("-", StringComparison.Ordinal) ? "del" : "plain";
    }

    /// <summary>单 Agent 会话按任务分命名空间；没有任务时落全局命名空间。</summary>
    public static string AgentChatKey(string? taskId)
        => $"flux.solo.agent.{taskId ?? "none"}";

    /// <summary>粘贴大文本阈值：与手机 / Web 同一个常量。</summary>
    public const int LargePasteThreshold = 10_000;

    /// <summary>后端单条任务消息正文上限。</summary>
    public const int ComposerTextMax = 8_000;

    /// <summary>粘贴模式取值：file = 始终作为文件（默认），text = 始终直接粘贴。</summary>
    public static bool IsLargePaste(string? text, string? mode)
    {
        if (mode == "text" || string.IsNullOrEmpty(text)) return false;
        if (text.Length < LargePasteThreshold) return false;
        return text.Contains('\n') || text.Contains('\r');
    }

    /// <summary>用首行做安全文件名；空白时退回「粘贴.txt」。</summary>
    public static string PasteFileName(string? text)
    {
        var first = (text ?? "")
            .Split('\n')
            .Select(line => line.Trim())
            .FirstOrDefault(line => line.Length > 0) ?? "";
        var cleaned = new string(first
            .Select(ch => "\\/:*?\"<>|".Contains(ch) || char.IsControl(ch) ? ' ' : ch)
            .ToArray());
        cleaned = string.Join(' ', cleaned.Split(' ', StringSplitOptions.RemoveEmptyEntries));
        if (cleaned.Length > 24) cleaned = cleaned[..24];
        return cleaned.Length == 0 ? "粘贴.txt" : cleaned + ".txt";
    }

    /// <summary>改回输入框；超上限时返回中文原因，不静默截断。</summary>
    public static (string? Text, string? Error) RevealPastedText(string? currentText, string? pastedText)
    {
        var current = currentText ?? "";
        var pasted = pastedText ?? "";
        var merged = current.Trim().Length == 0
            ? pasted
            : current.TrimEnd().EndsWith(pasted.Trim(), StringComparison.Ordinal)
                ? current
                : current.TrimEnd() + "\n\n" + pasted;
        if (merged.Length > ComposerTextMax)
        {
            return (null, $"改回输入框会超过 {ComposerTextMax} 字上限，请先精简当前输入");
        }
        return (merged, null);
    }

    public static string ShortId(string? id)
        => string.IsNullOrEmpty(id) ? "—" : id.Length > 8 ? id[..8] : id;

    /// <summary>列表标题取描述首行并截断，避免长需求把左栏撑爆。</summary>
    public static string Title(string? description)
    {
        var text = (description ?? "").Trim();
        if (text.Length == 0)
        {
            return "未命名任务";
        }

        var line = text.Split('\n')[0].Trim();
        return line.Length > 26 ? line[..26] + "…" : line;
    }

    /// <summary>本轮改动汇总：文件数、增加行、删除行。</summary>
    public static (int Files, int Added, int Removed) Totals(IEnumerable<Models.WorkspaceChange> items)
    {
        var list = items.ToList();
        return (list.Select(c => c.FilePath).Distinct().Count(), list.Sum(c => c.AddedLines), list.Sum(c => c.RemovedLines));
    }
}

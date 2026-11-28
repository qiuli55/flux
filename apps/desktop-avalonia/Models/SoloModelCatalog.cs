using System.Collections.Generic;
using System.Linq;

namespace Flux.Desktop.Models;

/// <summary>
/// 选模数据源（桌面 / Web / 手机同口径）。
///
/// 权威来源是后端 `GET /agents` 里该 Agent 的 `spec.model_catalog`：
/// 只含**当前 Agent** 的通道（订阅账号 + 兼容密钥 + 原生），每条带来源与思考能力。
/// 不再以 DSH 目录当权威；DSH 目录只在老服务端没有 model_catalog 时兜底。
/// </summary>
public sealed record CatalogChoice(
    string? KeyId,
    string? AccountId,
    string Model,
    string Label,
    string ChannelId = "",
    string ChannelKind = "native",
    string ChannelLabel = "",
    int? ContextWindow = null,
    string[]? Capabilities = null,
    string? ThinkingKind = null,
    string[]? ThinkingLevels = null,
    string? ThinkingDefault = null);

public static class SoloModelCatalog
{
    /// <summary>该 Agent 的模型清单；没有 model_catalog 时用旧字段或 DSH 目录兜底。</summary>
    public static List<CatalogChoice> Build(
        AgentSpecDetail? spec,
        DshModelsResponse? fallback = null)
    {
        var choices = new List<CatalogChoice>();
        if (spec?.ModelCatalog is { Length: > 0 } catalog)
        {
            foreach (var item in catalog)
            {
                var keyId = string.IsNullOrWhiteSpace(item.KeyId) ? null : item.KeyId;
                var accountId = string.IsNullOrWhiteSpace(item.AccountId) ? null : item.AccountId;
                choices.Add(new CatalogChoice(
                    keyId,
                    accountId,
                    item.Id,
                    string.IsNullOrWhiteSpace(item.Label) ? item.Id : item.Label,
                    item.ChannelId ?? "",
                    item.ChannelKind ?? "native",
                    item.ChannelLabel ?? "",
                    item.ContextWindow,
                    item.Capabilities,
                    item.Thinking?.Kind,
                    item.Thinking?.Levels,
                    item.Thinking?.Default));
            }
            return choices;
        }

        if (spec is not null)
        {
            foreach (var model in spec.Models ?? [])
            {
                choices.Add(new CatalogChoice(null, null, model, model, "native", "native", "本机默认"));
            }
            foreach (var item in spec.KeyModels ?? [])
            {
                choices.Add(new CatalogChoice(
                    item.KeyId,
                    null,
                    item.Model,
                    string.IsNullOrWhiteSpace(item.KeyName)
                        ? item.Model
                        : $"{item.Model} · {item.KeyName}",
                    $"key:{item.KeyId}",
                    "key",
                    $"密钥库 · {item.KeyName}"));
            }
            if (choices.Count > 0)
            {
                return choices;
            }
        }

        foreach (var model in fallback?.Models ?? [])
        {
            choices.Add(new CatalogChoice(null, null, model, model, "native", "native", "本机默认"));
        }
        foreach (var item in fallback?.KeyModels ?? [])
        {
            choices.Add(new CatalogChoice(
                item.KeyId,
                null,
                item.Model,
                string.IsNullOrWhiteSpace(item.KeyName)
                    ? item.Model
                    : $"{item.Model} · {item.KeyName}",
                $"key:{item.KeyId}",
                "key",
                $"密钥库 · {item.KeyName}"));
        }
        return choices;
    }

    /// <summary>上下文长度的展示串；未知返回 null，不写假数据。</summary>
    public static string? ContextLabel(int? window)
    {
        if (window is null or <= 0)
        {
            return null;
        }
        if (window >= 1_000_000 && window % 1_000_000 == 0)
        {
            return $"{window / 1_000_000}M ctx";
        }
        if (window >= 1_000 && window % 1_000 == 0)
        {
            return $"{window / 1_000}k ctx";
        }
        return $"{window} ctx";
    }

    /// <summary>列表副行：来源 · 上下文 · 能力标签；没有的项不写。</summary>
    public static string? DetailLine(CatalogChoice choice)
    {
        var parts = new List<string>();
        if (!string.IsNullOrWhiteSpace(choice.ChannelLabel))
        {
            parts.Add(choice.ChannelLabel);
        }
        if (ContextLabel(choice.ContextWindow) is { } context)
        {
            parts.Add(context);
        }
        foreach (var capability in choice.Capabilities ?? [])
        {
            if (!string.IsNullOrWhiteSpace(capability))
            {
                parts.Add(capability);
            }
        }
        return parts.Count == 0 ? null : string.Join(" · ", parts);
    }
}

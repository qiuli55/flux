using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Input;
using Avalonia.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Flux.Desktop.Models;
using Flux.Desktop.Services;

namespace Flux.Desktop.ViewModels;

/// <summary>项目列表：展示工作区里登记的项目，点卡片回到 Solo 继续下达任务。</summary>
public partial class ProjectsViewModel : ViewModelBase
{
    private readonly Func<FluxApiClient?> _api;
    private readonly Action<string> _navigate;

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private string _hint = "";

    [ObservableProperty]
    private bool _hasProjects;

    public ProjectsViewModel()
        : this(() => null, _ => { })
    {
    }

    public ProjectsViewModel(Func<FluxApiClient?> api, Action<string> navigate)
    {
        _api = api;
        _navigate = navigate;
    }

    public ObservableCollection<ProjectCard> Projects { get; } = new();

    [RelayCommand]
    private async Task Refresh() => await RefreshAsync();

    public async Task RefreshAsync()
    {
        var api = _api();
        if (api is null)
        {
            Hint = "后端尚未就绪。";
            AppLog.Warn("项目页刷新跳过：后端未就绪");
            return;
        }

        IsBusy = true;
        try
        {
            var projects = (await api.ListProjectsAsync())?.Data ?? new List<Project>();
            Projects.Clear();
            foreach (var project in projects)
            {
                Projects.Add(new ProjectCard(project, OpenCommand));
            }

            HasProjects = Projects.Count > 0;
            Hint = HasProjects ? $"共 {Projects.Count} 个项目" : "还没有登记项目";
            AppLog.Info($"项目页刷新 · {Projects.Count} 个");
        }
        catch (Exception ex)
        {
            Hint = $"加载失败：{ex.Message}";
            AppLog.Error("项目页刷新失败", ex);
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private void Open(ProjectCard? card)
    {
        AppLog.Info($"项目页打开 · {card?.Name ?? "solo"}");
        _navigate("solo");
    }
}

/// <summary>项目卡片。</summary>
public sealed class ProjectCard
{
    public ProjectCard(Project project, ICommand open)
    {
        Id = project.Id;
        Name = project.Name;
        Repository = project.Repository ?? "—";
        Initial = string.IsNullOrWhiteSpace(project.Name) ? "P" : project.Name[..1].ToUpperInvariant();
        Tag = string.IsNullOrWhiteSpace(project.Repository) ? "本地项目" : "Git 仓库";
        OpenCommand = open;
    }

    public string Id { get; }

    public string Name { get; }

    public string Repository { get; }

    public string Initial { get; }

    public string Tag { get; }

    public ICommand OpenCommand { get; }
}

/// <summary>Agent 面板：展示注册表里的 Agent 能力与默认模型。</summary>
public partial class AgentsViewModel : ViewModelBase
{
    private static readonly IBrush Brand = new SolidColorBrush(Color.Parse("#3DF5A8"));
    private static readonly IBrush Muted = new SolidColorBrush(Color.Parse("#6C7674"));

    private readonly Func<FluxApiClient?> _api;

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private string _hint = "";

    [ObservableProperty]
    private string _dshText = "";

    [ObservableProperty]
    private bool _hasAgents;

    public AgentsViewModel()
        : this(() => null)
    {
    }

    public AgentsViewModel(Func<FluxApiClient?> api)
    {
        _api = api;
    }

    public ObservableCollection<AgentCard> Agents { get; } = new();

    [RelayCommand]
    private async Task Refresh() => await RefreshAsync();

    public async Task RefreshAsync()
    {
        var api = _api();
        if (api is null)
        {
            Hint = "后端尚未就绪。";
            AppLog.Warn("Agent 页刷新跳过：后端未就绪");
            return;
        }

        IsBusy = true;
        try
        {
            var agents = (await api.ListAgentDetailsAsync())?.Data ?? new List<AgentDetail>();
            Agents.Clear();
            foreach (var agent in agents)
            {
                Agents.Add(new AgentCard(agent, Brand, Muted));
            }

            var models = (await api.DshModelsAsync())?.Data;
            var dsh = (await api.DshStatusAsync())?.Data;
            HasAgents = Agents.Count > 0;
            DshText = dsh is null
                ? "DSH 状态未知"
                : $"DSH {dsh.Provider ?? "—"} / {dsh.Model ?? "—"} · 原生模型 {models?.Models.Length ?? 0} · 密钥模型 {models?.KeyModels?.Length ?? 0}";
            Hint = HasAgents ? $"共 {Agents.Count} 个 Agent" : "注册表为空，可先导入 Agent";
            AppLog.Info($"Agent 页刷新 · {Agents.Count} 个 · {DshText}");
        }
        catch (Exception ex)
        {
            Hint = $"加载失败：{ex.Message}";
            AppLog.Error("Agent 页刷新失败", ex);
        }
        finally
        {
            IsBusy = false;
        }
    }
}

/// <summary>Agent 卡片。</summary>
public sealed class AgentCard
{
    public AgentCard(AgentDetail agent, IBrush activeBrush, IBrush mutedBrush)
    {
        Id = agent.Id;
        Name = agent.Spec.Name;
        Role = agent.Spec.Role ?? "—";
        Runtime = agent.Spec.Runtime ?? "—";
        StateText = agent.State switch
        {
            "active" or "READY" => "就绪",
            "idle" => "空闲",
            "disabled" => "已停用",
            _ => agent.State,
        };
        StateBrush = agent.State is "active" or "READY" ? activeBrush : mutedBrush;
        ModelText = agent.Spec.Model ?? "跟随全局默认";
        var keyModels = agent.Spec.KeyModels?.Length ?? 0;
        ModelsText = $"原生 {agent.Spec.Models?.Length ?? 0} · 密钥 {keyModels}";
        Initial = string.IsNullOrWhiteSpace(Name) ? "A" : Name[..1].ToUpperInvariant();
    }

    public string Id { get; }

    public string Name { get; }

    public string Role { get; }

    public string Runtime { get; }

    public string StateText { get; }

    public IBrush StateBrush { get; }

    public string ModelText { get; }

    public string ModelsText { get; }

    public string Initial { get; }
}

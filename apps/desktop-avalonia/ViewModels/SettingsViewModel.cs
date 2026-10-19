using System;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Flux.Desktop.Services;

namespace Flux.Desktop.ViewModels;

/// <summary>
/// 设置页（web 端 SettingsPage）：外观偏好（即时生效并落盘）+ 工作区事实（只读）+ 关于。
/// 三个外观开关直接驱动外壳，与 web 端共享 prefs store 的做法同构。
/// </summary>
public sealed partial class SettingsViewModel : ViewModelBase
{
    private readonly Func<FluxApiClient?> _api;
    private readonly MainWindowViewModel _shell;

    public SettingsViewModel(Func<FluxApiClient?> api, MainWindowViewModel shell)
    {
        _api = api;
        _shell = shell;
    }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsAppearance))]
    [NotifyPropertyChangedFor(nameof(IsWorkspace))]
    [NotifyPropertyChangedFor(nameof(IsAbout))]
    private string _selectedTab = "appearance";

    [ObservableProperty]
    private string _workspaceRoot = "—";

    [ObservableProperty]
    private string _apiPrefix = "—";

    [ObservableProperty]
    private bool _isResetConfirmOpen;

    public bool IsAppearance => SelectedTab == "appearance";

    public bool IsWorkspace => SelectedTab == "workspace";

    public bool IsAbout => SelectedTab == "about";

    /// <summary>展开侧栏：显示导航项文字，关闭则只显示图标。</summary>
    public bool RailWide
    {
        get => _shell.IsRailWide;
        set
        {
            _shell.IsRailWide = value;
            OnPropertyChanged();
        }
    }

    /// <summary>显示底部状态栏。</summary>
    public bool StatusBar
    {
        get => _shell.IsStatusBarVisible;
        set
        {
            _shell.IsStatusBarVisible = value;
            OnPropertyChanged();
        }
    }

    /// <summary>减少动效：关掉状态栏脉冲动画。</summary>
    public bool ReduceMotion
    {
        get => _shell.IsReduceMotion;
        set
        {
            _shell.IsReduceMotion = value;
            OnPropertyChanged();
        }
    }

    public async Task RefreshAsync()
    {
        ApiPrefix = _shell.Account.BackendUrl + "/api/v1";

        var api = _api();
        if (api is null)
        {
            WorkspaceRoot = "—";
            return;
        }

        var root = await api.WorkspaceRootAsync().ConfigureAwait(true);
        WorkspaceRoot = root?.Data?.Root ?? "获取失败：后端未响应 /workspace/root";
    }

    [RelayCommand]
    private void SelectTab(string? tab) => SelectedTab = tab ?? "appearance";

    [RelayCommand]
    private void OpenResetConfirm() => IsResetConfirmOpen = true;

    [RelayCommand]
    private void CancelReset() => IsResetConfirmOpen = false;

    [RelayCommand]
    private void ConfirmReset()
    {
        _shell.ResetPrefs();
        IsResetConfirmOpen = false;
        OnPropertyChanged(nameof(RailWide));
        OnPropertyChanged(nameof(StatusBar));
        OnPropertyChanged(nameof(ReduceMotion));
    }
}

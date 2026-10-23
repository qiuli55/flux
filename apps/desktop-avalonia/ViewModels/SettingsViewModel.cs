using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Flux.Desktop.Models;
using Flux.Desktop.Services;

namespace Flux.Desktop.ViewModels;

/// <summary>
/// 设置页（web 端 SettingsPage）：外观偏好（即时生效并落盘）+ 工作区事实（只读）
/// + 密钥库（API Key Vault）。
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
    [NotifyPropertyChangedFor(nameof(IsKeys))]
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

    public bool IsKeys => SelectedTab == "keys";

    public bool IsAbout => SelectedTab == "about";

    // --- 密钥库 ---

    public ObservableCollection<ApiKeyEntry> Keys { get; } = new();

    public string[] ProviderOptions { get; } = new[] { "deepseek", "openai", "openai_compatible", "anthropic" };

    public string[] WireApiOptions { get; } = new[] { "", "chat", "responses" };

    [ObservableProperty]
    private bool _isKeysBusy;

    [ObservableProperty]
    private string _keyStatus = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsEditingKey))]
    [NotifyPropertyChangedFor(nameof(KeyFormTitle))]
    private string? _editingKeyId;

    [ObservableProperty]
    private string _keyName = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsCompatProvider))]
    private string _keyProvider = "deepseek";

    [ObservableProperty]
    private string _keyBaseUrl = "";

    [ObservableProperty]
    private string _keyApiKey = "";

    [ObservableProperty]
    private string _keyModelsText = "";

    [ObservableProperty]
    private string _keyWireApi = "";

    [ObservableProperty]
    private bool _keyEnabled = true;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsDeleteKeyOpen))]
    private string? _deleteKeyId;

    [ObservableProperty]
    private string _deleteKeyName = "";

    public bool IsEditingKey => !string.IsNullOrEmpty(EditingKeyId);

    public bool IsDeleteKeyOpen => !string.IsNullOrEmpty(DeleteKeyId);

    public string KeyFormTitle => IsEditingKey ? "编辑密钥" : "添加密钥";

    public bool IsCompatProvider => KeyProvider == "openai_compatible";

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
            KeyStatus = "后端未连接";
            return;
        }

        var root = await api.WorkspaceRootAsync().ConfigureAwait(true);
        WorkspaceRoot = root?.Data?.Root ?? "获取失败：后端未响应 /workspace/root";

        if (IsKeys)
        {
            await LoadKeysAsync().ConfigureAwait(true);
        }
    }

    private async Task LoadKeysAsync()
    {
        var api = _api();
        if (api is null)
        {
            KeyStatus = "后端未连接";
            return;
        }
        IsKeysBusy = true;
        try
        {
            var res = await api.ListApiKeysAsync().ConfigureAwait(true);
            Keys.Clear();
            foreach (var entry in res?.Data ?? new List<ApiKeyEntry>())
            {
                Keys.Add(entry);
            }
            KeyStatus = res is null ? "读取失败：后端未响应 /keys" : $"共 {Keys.Count} 条密钥";
        }
        finally
        {
            IsKeysBusy = false;
        }
    }

    [RelayCommand]
    private async Task SelectTab(string? tab)
    {
        SelectedTab = tab ?? "appearance";
        if (IsKeys)
        {
            await LoadKeysAsync().ConfigureAwait(true);
        }
    }

    [RelayCommand]
    private void StartCreateKey()
    {
        EditingKeyId = null;
        KeyName = "";
        KeyProvider = "deepseek";
        KeyBaseUrl = "";
        KeyApiKey = "";
        KeyModelsText = "";
        KeyWireApi = "";
        KeyEnabled = true;
        DeleteKeyId = null;
        KeyStatus = "";
    }

    [RelayCommand]
    private void StartEditKey(ApiKeyEntry? entry)
    {
        if (entry is null) return;
        EditingKeyId = entry.Id;
        KeyName = entry.Name;
        KeyProvider = entry.Provider;
        KeyBaseUrl = entry.BaseUrl;
        KeyApiKey = "";
        KeyModelsText = string.Join("\n", entry.Models);
        KeyWireApi = entry.WireApi;
        KeyEnabled = entry.Enabled;
        DeleteKeyId = null;
        KeyStatus = "留空 API Key 表示不修改";
    }

    [RelayCommand]
    private void CancelKeyEdit()
    {
        EditingKeyId = null;
        DeleteKeyId = null;
        KeyStatus = "";
    }

    [RelayCommand]
    private async Task SaveKeyAsync()
    {
        var api = _api();
        if (api is null)
        {
            KeyStatus = "后端未连接";
            return;
        }
        var name = (KeyName ?? "").Trim();
        var models = (KeyModelsText ?? "")
            .Split(new[] { '\n', ',', '，' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Distinct()
            .ToArray();
        if (name.Length == 0)
        {
            KeyStatus = "请填写密钥名称";
            return;
        }
        if (models.Length == 0)
        {
            KeyStatus = "至少填写一个模型 id";
            return;
        }
        if (string.IsNullOrEmpty(EditingKeyId) && string.IsNullOrWhiteSpace(KeyApiKey))
        {
            KeyStatus = "请填写 API Key";
            return;
        }

        var baseUrl = (KeyBaseUrl ?? "").Trim().TrimEnd('/');
        var wireApi = IsCompatProvider ? (KeyWireApi ?? "") : "";
        IsKeysBusy = true;
        try
        {
            ApiEnvelope<ApiKeyEntry>? res;
            if (string.IsNullOrEmpty(EditingKeyId))
            {
                res = await api.CreateApiKeyAsync(
                    name, KeyProvider, baseUrl, KeyApiKey.Trim(), models, wireApi, KeyEnabled)
                    .ConfigureAwait(true);
            }
            else
            {
                object body = string.IsNullOrWhiteSpace(KeyApiKey)
                    ? new
                    {
                        name,
                        provider = KeyProvider,
                        base_url = baseUrl,
                        models,
                        wire_api = wireApi,
                        enabled = KeyEnabled,
                    }
                    : new
                    {
                        name,
                        provider = KeyProvider,
                        base_url = baseUrl,
                        api_key = KeyApiKey.Trim(),
                        models,
                        wire_api = wireApi,
                        enabled = KeyEnabled,
                    };
                res = await api.UpdateApiKeyAsync(EditingKeyId, body).ConfigureAwait(true);
            }
            if (res?.Success == true)
            {
                StartCreateKey();
                await LoadKeysAsync().ConfigureAwait(true);
                KeyStatus = "已保存";
            }
            else
            {
                KeyStatus = res?.Message ?? "保存失败";
            }
        }
        finally
        {
            IsKeysBusy = false;
        }
    }

    [RelayCommand]
    private async Task ToggleKeyAsync(ApiKeyEntry? entry)
    {
        if (entry is null) return;
        var api = _api();
        if (api is null) return;
        var res = await api.UpdateApiKeyAsync(entry.Id, new { enabled = !entry.Enabled })
            .ConfigureAwait(true);
        if (res?.Success != true)
        {
            KeyStatus = res?.Message ?? "更新失败";
        }
        await LoadKeysAsync().ConfigureAwait(true);
    }

    [RelayCommand]
    private void AskDeleteKey(ApiKeyEntry? entry)
    {
        DeleteKeyId = entry?.Id;
        DeleteKeyName = entry?.Name ?? "";
    }

    [RelayCommand]
    private void CancelDeleteKey()
    {
        DeleteKeyId = null;
        DeleteKeyName = "";
    }

    [RelayCommand]
    private async Task ConfirmDeleteKeyAsync()
    {
        if (string.IsNullOrEmpty(DeleteKeyId)) return;
        var api = _api();
        if (api is null) return;
        var res = await api.DeleteApiKeyAsync(DeleteKeyId).ConfigureAwait(true);
        if (res?.Success == true)
        {
            DeleteKeyId = null;
            DeleteKeyName = "";
            StartCreateKey();
            await LoadKeysAsync().ConfigureAwait(true);
            KeyStatus = "已删除";
        }
        else
        {
            KeyStatus = res?.Message ?? "删除失败";
        }
    }

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

using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace Flux.Desktop.ViewModels;

/// <summary>左上「概览」等 M1 尚未实现的页面占位。</summary>
public partial class PlaceholderPageViewModel(string title, string subtitle) : ViewModelBase
{
    [ObservableProperty]
    private string _title = title;

    [ObservableProperty]
    private string _subtitle = subtitle;
}

/// <summary>账户页：展示云账号与本地运行信息，提供登录/登出。</summary>
public partial class AccountViewModel : ViewModelBase
{
    [ObservableProperty]
    private bool _isAuthenticated;

    [ObservableProperty]
    private string _username = "未登录";

    [ObservableProperty]
    private string _subtitle = "本地单用户模式";

    [ObservableProperty]
    private string _avatarText = "本";

    [ObservableProperty]
    private string _userIdText = "—";

    [ObservableProperty]
    private string _workspaceRoot = "";

    [ObservableProperty]
    private string _dataDir = "";

    [ObservableProperty]
    private string _backendUrl = "";

    [ObservableProperty]
    private string _callbackUrl = "";

    public IRelayCommand? LoginCommand { get; init; }

    public IRelayCommand? LogoutCommand { get; init; }
}

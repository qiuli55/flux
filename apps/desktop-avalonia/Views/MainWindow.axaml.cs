using System;
using System.ComponentModel;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Flux.Desktop.ViewModels;

namespace Flux.Desktop.Views;

public partial class MainWindow : Window
{
    private MainWindowViewModel? _viewModel;

    public MainWindow()
    {
        InitializeComponent();

        PaletteInput.KeyDown += OnPaletteInputKeyDown;
        DataContextChanged += OnDataContextChanged;

        // Esc 只在命令面板打开时由窗口消费；否则放行给 IDE（补全弹窗等）自行处理。
        AddHandler(KeyDownEvent, OnWindowKeyDown, RoutingStrategies.Tunnel);
    }

    private void OnWindowKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key != Key.Escape || _viewModel?.IsPaletteOpen != true)
        {
            return;
        }

        e.Handled = true;
        _viewModel.ClosePaletteCommand.Execute(null);
    }

    private void OnDataContextChanged(object? sender, EventArgs e)
    {
        if (_viewModel is not null)
        {
            _viewModel.PropertyChanged -= OnViewModelPropertyChanged;
        }

        _viewModel = DataContext as MainWindowViewModel;

        if (_viewModel is not null)
        {
            _viewModel.PropertyChanged += OnViewModelPropertyChanged;
        }
    }

    /// <summary>命令面板打开后把焦点交给输入框，否则敲键盘没反应。</summary>
    private void OnViewModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName != nameof(MainWindowViewModel.IsPaletteOpen) || _viewModel?.IsPaletteOpen != true)
        {
            return;
        }

        // 等这一帧布局完成、面板真正可见后再抢焦点
        Dispatcher.UIThread.Post(() => PaletteInput.Focus(), DispatcherPriority.Background);
    }

    private void OnPaletteInputKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter)
        {
            return;
        }

        e.Handled = true;
        _viewModel?.RunPaletteSelectionCommand.Execute(null);
    }

    /// <summary>点击命令面板条目：激活对应页面并收起面板。</summary>
    private void OnPaletteItemClick(object? sender, RoutedEventArgs e)
    {
        if (sender is Control { DataContext: NavItem item })
        {
            _viewModel?.RunPaletteItemCommand.Execute(item);
        }
    }
}

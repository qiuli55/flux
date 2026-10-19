using System;
using System.ComponentModel;
using Avalonia.Controls;
using Avalonia.Input;
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
}

using System;
using Avalonia.Controls;
using Flux.Desktop.ViewModels;

namespace Flux.Desktop.Views;

public partial class SettingsView : UserControl
{
    private SettingsViewModel? _viewModel;

    public SettingsView()
    {
        InitializeComponent();
        DataContextChanged += OnDataContextChanged;
        DetachedFromVisualTree += (_, _) => DetachViewModel();
    }

    private void OnDataContextChanged(object? sender, EventArgs e)
    {
        DetachViewModel();
        if (DataContext is not SettingsViewModel viewModel) return;
        _viewModel = viewModel;
        viewModel.KeyFieldFocusRequested += OnKeyFieldFocusRequested;
    }

    private void DetachViewModel()
    {
        if (_viewModel is null) return;
        _viewModel.KeyFieldFocusRequested -= OnKeyFieldFocusRequested;
        _viewModel = null;
    }

    /// <summary>探测结果里的「去修改」：聚焦现有编辑表单中最可能需要改的字段。</summary>
    private void OnKeyFieldFocusRequested(string reasonCode)
    {
        Control? target = reasonCode switch
        {
            "invalid_key" => KeyApiKeyBox,
            "model_unavailable" => KeyModelsBox,
            "network_unreachable" => KeyBaseUrlBox,
            _ => null,
        };
        target?.Focus();
    }
}

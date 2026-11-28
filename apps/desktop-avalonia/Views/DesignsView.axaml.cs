using System;
using System.Collections.Generic;
using System.Linq;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
using Flux.Desktop.Models;
using Flux.Desktop.ViewModels;

namespace Flux.Desktop.Views;

/// <summary>设计页（Luma）：设计稿列表、画布、AI 整屏修改与历史版本回滚。</summary>
public partial class DesignsView : UserControl
{
    public DesignsView()
    {
        InitializeComponent();
    }

    private DesignsViewModel? ViewModel => DataContext as DesignsViewModel;

    /// <summary>一次画布手势开始：宿主压一格撤销快照。</summary>
    private void OnCanvasEditStarted(object? sender, EventArgs e) => ViewModel?.BeginCanvasEdit();

    /// <summary>手势结束：把最终几何写回原始 JSON。</summary>
    private void OnCanvasFramesCommitted(object? sender, IReadOnlyList<DesignFrameChange> changes) =>
        ViewModel?.CommitCanvasEdit(changes);

    private void OnCanvasDeleteRequested(object? sender, IReadOnlyList<string> ids) =>
        ViewModel?.DeleteCanvasNodes(ids);

    private void OnCanvasSelectionChanged(object? sender, IReadOnlyList<string> ids) =>
        ViewModel?.SelectCanvasNode(ids);

    private void OnCanvasUndoRequested(object? sender, EventArgs e) =>
        ViewModel?.UndoEditCommand.Execute(null);

    private void OnCanvasRedoRequested(object? sender, EventArgs e) =>
        ViewModel?.RedoEditCommand.Execute(null);

    /// <summary>焦点在按钮 / 输入框上时，快捷键也能撤销重做（画布聚焦时由画布自己处理）。</summary>
    private void OnViewKeyDown(object? sender, KeyEventArgs e)
    {
        if (ViewModel is not { IsCanvasEditing: true } vm) return;
        // 正在输入框里编辑时，Ctrl+Z 应该先撤销文字，而不是撤销画布。
        if (e.Source is TextBox) return;
        var ctrl = e.KeyModifiers.HasFlag(KeyModifiers.Control) ||
            e.KeyModifiers.HasFlag(KeyModifiers.Meta);
        if (!ctrl) return;
        switch (e.Key)
        {
            case Key.Z:
                if (e.KeyModifiers.HasFlag(KeyModifiers.Shift)) vm.RedoEditCommand.Execute(null);
                else vm.UndoEditCommand.Execute(null);
                e.Handled = true;
                break;
            case Key.Y:
                vm.RedoEditCommand.Execute(null);
                e.Handled = true;
                break;
        }
    }

    /// <summary>画布演示态点按钮/导航时切屏；走 SelectScreenCommand 复用同一套状态更新。</summary>
    private void OnCanvasScreenRequested(object? sender, int index)
    {
        if (ViewModel is not { } vm || index < 0 || index >= vm.Screens.Count)
        {
            return;
        }

        vm.SelectScreenCommand.Execute(vm.Screens[index]);
    }

    private async void OnImportClick(object? sender, RoutedEventArgs e)
    {
        if (ViewModel is not { } vm || TopLevel.GetTopLevel(this)?.StorageProvider is not { } storage)
        {
            return;
        }

        var files = await storage.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "导入设计稿",
            AllowMultiple = false,
            FileTypeFilter = new[]
            {
                new FilePickerFileType("Luma 设计稿")
                {
                    Patterns = new[] { "*.svg", "*.fig", "*.sketch" },
                },
            },
        });

        if (files.FirstOrDefault()?.TryGetLocalPath() is { Length: > 0 } path)
        {
            await vm.ImportAsync(path);
        }
    }

    private async void OnExportClick(object? sender, RoutedEventArgs e)
    {
        if (ViewModel is not { } vm || TopLevel.GetTopLevel(this)?.StorageProvider is not { } storage)
        {
            return;
        }

        var name = string.IsNullOrWhiteSpace(vm.Title) ? "design" : vm.Title;
        var file = await storage.SaveFilePickerAsync(new FilePickerSaveOptions
        {
            Title = "导出 SVG",
            SuggestedFileName = name + ".svg",
            DefaultExtension = "svg",
            FileTypeChoices = new[]
            {
                new FilePickerFileType("SVG") { Patterns = new[] { "*.svg" } },
            },
        });

        if (file?.TryGetLocalPath() is { Length: > 0 } path)
        {
            await vm.ExportSvgAsync(path);
        }
    }
}

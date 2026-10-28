using System.Linq;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Platform.Storage;
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

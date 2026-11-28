using System;
using System.Collections.Specialized;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Flux.Desktop.Services;
using Flux.Desktop.ViewModels;

namespace Flux.Desktop.Views;

/// <summary>Solo 页：切换任务/Agent 时把对话滚动到底，避免新消息落在视野外。</summary>
public partial class SoloView : UserControl
{
    private SoloViewModel? _vm;

    public SoloView()
    {
        InitializeComponent();
        DataContextChanged += OnDataContextChanged;
    }

    private void OnDataContextChanged(object? sender, EventArgs e)
    {
        if (_vm is not null)
        {
            _vm.Messages.CollectionChanged -= OnMessagesChanged;
            _vm.AgentTurns.CollectionChanged -= OnTurnsChanged;
        }

        _vm = DataContext as SoloViewModel;
        if (_vm is null)
        {
            return;
        }

        _vm.Messages.CollectionChanged += OnMessagesChanged;
        _vm.AgentTurns.CollectionChanged += OnTurnsChanged;
        HookPaste("DraftBox");
        HookPaste("AgentBox");
        ScrollToEnd("ChatScroll");
        ScrollToEnd("AgentScroll");
    }

    /// <summary>
    /// 粘贴拦截：达到 1 万字且含换行时按偏好直传附件，不弹询问。
    /// 先同步标记 Handled，避免默认粘贴在异步读剪贴板前执行。
    /// </summary>
    private void HookPaste(string name)
    {
        var box = this.FindControl<TextBox>(name);
        if (box is null)
        {
            return;
        }

        box.RemoveHandler(TextBox.PastingFromClipboardEvent, OnPasting);
        box.AddHandler(TextBox.PastingFromClipboardEvent, OnPasting);
    }

    private async void OnPasting(object? sender, RoutedEventArgs e)
    {
        if (sender is not TextBox box || _vm is null)
        {
            return;
        }

        e.Handled = true;
        var clipboard = TopLevel.GetTopLevel(this)?.Clipboard;
        if (clipboard is null)
        {
            return;
        }

        var text = await clipboard.GetTextAsync();
        if (string.IsNullOrEmpty(text))
        {
            return;
        }

        if (SoloRules.IsLargePaste(text, UiPrefs.Load().PasteMode))
        {
            await _vm.AttachPastedTextAsync(text);
            return;
        }

        // 不满足阈值：手动按同样语义插到光标处，保持普通粘贴体验。
        var current = box.Text ?? "";
        var start = Math.Clamp(box.SelectionStart, 0, current.Length);
        var end = Math.Clamp(box.SelectionEnd, start, current.Length);
        box.Text = current[..start] + text + current[end..];
        box.CaretIndex = start + text.Length;
    }

    private void OnMessagesChanged(object? sender, NotifyCollectionChangedEventArgs e)
        => ScrollToEnd("ChatScroll");

    private void OnTurnsChanged(object? sender, NotifyCollectionChangedEventArgs e)
        => ScrollToEnd("AgentScroll");

    private void ScrollToEnd(string name)
    {
        var scroll = this.FindControl<ScrollViewer>(name);
        if (scroll is null)
        {
            return;
        }

        Dispatcher.UIThread.Post(
            () => scroll.Offset = new Vector(scroll.Offset.X, scroll.Extent.Height),
            DispatcherPriority.Background);
    }
}

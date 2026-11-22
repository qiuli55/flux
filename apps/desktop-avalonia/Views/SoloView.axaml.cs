using System;
using System.Collections.Specialized;
using System.ComponentModel;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Flux.Desktop.ViewModels;

namespace Flux.Desktop.Views;

/// <summary>
/// Solo 页：切任务默认贴底；用户向上翻时不再抢滚动，由「回到最新」手动回到底部。
/// 流式正文每 50ms 合帧刷新，只有贴底状态才跟随。
/// </summary>
public partial class SoloView : UserControl
{
    private const double StickThreshold = 80;

    private SoloViewModel? _vm;
    private ScrollViewer? _chatScroll;
    private bool _stickToBottom = true;

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
            _vm.PropertyChanged -= OnViewModelPropertyChanged;
        }

        if (_chatScroll is not null)
        {
            _chatScroll.ScrollChanged -= OnChatScrollChanged;
        }
        _chatScroll = this.FindControl<ScrollViewer>("ChatScroll");
        if (_chatScroll is not null)
        {
            _chatScroll.ScrollChanged += OnChatScrollChanged;
        }

        _vm = DataContext as SoloViewModel;
        if (_vm is null)
        {
            return;
        }

        _vm.Messages.CollectionChanged += OnMessagesChanged;
        _vm.AgentTurns.CollectionChanged += OnTurnsChanged;
        _vm.PropertyChanged += OnViewModelPropertyChanged;
        _stickToBottom = true;
        ScrollToEnd("ChatScroll");
        ScrollToEnd("AgentScroll");
        UpdateJumpButton();
    }

    private void OnMessagesChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        // 切任务 / 新建草稿会清空列表：视为新会话，重新贴底。
        if (e.Action == NotifyCollectionChangedAction.Reset)
        {
            _stickToBottom = true;
        }

        if (_stickToBottom)
        {
            ScrollToEnd("ChatScroll");
        }
        else
        {
            UpdateJumpButton();
        }
    }

    private void OnTurnsChanged(object? sender, NotifyCollectionChangedEventArgs e)
        => ScrollToEnd("AgentScroll");

    private void OnViewModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(SoloViewModel.StreamLiveText)
            or nameof(SoloViewModel.StreamVisible)
            or nameof(SoloViewModel.StreamTypingVisible)
            or nameof(SoloViewModel.StreamActivityOpen)
            or nameof(SoloViewModel.StreamHasText))
        {
            if (_stickToBottom)
            {
                ScrollToEnd("ChatScroll");
            }
            else
            {
                UpdateJumpButton();
            }
        }
    }

    private void OnChatScrollChanged(object? sender, ScrollChangedEventArgs e)
    {
        var scroll = _chatScroll;
        if (scroll is null)
        {
            return;
        }

        var distance = scroll.Extent.Height - (scroll.Offset.Y + scroll.Viewport.Height);
        _stickToBottom = distance <= StickThreshold;
        UpdateJumpButton();
    }

    private void OnJumpLatest(object? sender, RoutedEventArgs e)
    {
        _stickToBottom = true;
        ScrollToEnd("ChatScroll");
        UpdateJumpButton();
    }

    private void UpdateJumpButton()
    {
        var button = this.FindControl<Button>("JumpLatest");
        if (button is not null)
        {
            button.IsVisible = !_stickToBottom;
        }
    }

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

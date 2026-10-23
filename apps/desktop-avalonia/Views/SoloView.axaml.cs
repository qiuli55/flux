using System;
using System.Collections.Specialized;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Threading;
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
        ScrollToEnd("ChatScroll");
        ScrollToEnd("AgentScroll");
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

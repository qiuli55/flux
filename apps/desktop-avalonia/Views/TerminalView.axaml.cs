using System;
using System.Collections.Specialized;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Threading;
using Flux.Desktop.ViewModels;

namespace Flux.Desktop.Views;

/// <summary>Terminal 页：新事件到达时把输出滚到底。</summary>
public partial class TerminalView : UserControl
{
    private TerminalViewModel? _vm;

    public TerminalView()
    {
        InitializeComponent();
        DataContextChanged += OnDataContextChanged;
    }

    private void OnDataContextChanged(object? sender, EventArgs e)
    {
        if (_vm is not null)
        {
            _vm.Events.CollectionChanged -= OnEventsChanged;
        }

        _vm = DataContext as TerminalViewModel;
        if (_vm is null)
        {
            return;
        }

        _vm.Events.CollectionChanged += OnEventsChanged;
        ScrollToEnd();
    }

    private void OnEventsChanged(object? sender, NotifyCollectionChangedEventArgs e)
        => ScrollToEnd();

    private void ScrollToEnd()
    {
        var scroll = this.FindControl<ScrollViewer>("TerminalScroll");
        if (scroll is null)
        {
            return;
        }

        Dispatcher.UIThread.Post(
            () => scroll.Offset = new Vector(scroll.Offset.X, scroll.Extent.Height),
            DispatcherPriority.Background);
    }
}

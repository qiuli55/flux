using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Flux.Desktop.ViewModels;
using Flux.Desktop.Views;

namespace Flux.Desktop;

public partial class App : Application
{
    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var viewModel = new MainWindowViewModel();
            var window = new MainWindow { DataContext = viewModel };

            desktop.MainWindow = window;
            desktop.ShutdownRequested += (_, _) => viewModel.Shutdown();

            window.Opened += async (_, _) => await viewModel.StartAsync();
        }

        base.OnFrameworkInitializationCompleted();
    }
}

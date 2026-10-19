using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;

namespace Flux.Desktop.Views;

/// <summary>
/// 二元开关（对齐 web 端 .fx-switch）。点击即翻转 <see cref="IsOn"/>，
/// 通过 TwoWay 绑定回写到界面偏好。
/// </summary>
public partial class FluxSwitch : UserControl
{
    public static readonly StyledProperty<bool> IsOnProperty =
        AvaloniaProperty.Register<FluxSwitch, bool>(nameof(IsOn));

    static FluxSwitch()
    {
        IsOnProperty.Changed.AddClassHandler<FluxSwitch>((control, _) => control.ApplyState());
    }

    public FluxSwitch()
    {
        InitializeComponent();
        ApplyState();
    }

    public bool IsOn
    {
        get => GetValue(IsOnProperty);
        set => SetValue(IsOnProperty, value);
    }

    protected override void OnPointerPressed(PointerPressedEventArgs e)
    {
        base.OnPointerPressed(e);
        IsOn = !IsOn;
        e.Handled = true;
    }

    private void ApplyState()
    {
        Track.Classes.Set("on", IsOn);
        Knob.Classes.Set("on", IsOn);
    }
}

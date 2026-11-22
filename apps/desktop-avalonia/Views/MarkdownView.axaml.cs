using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Controls.Primitives;
using Avalonia.Layout;
using Avalonia.Media;
using Markdig;
using Markdig.Syntax;
using Markdig.Syntax.Inlines;

namespace Flux.Desktop.Views;

/// <summary>
/// 轻量 Markdown 渲染（Markdig AST → Avalonia 控件）。
///
/// 覆盖助手正文常见语法：标题、段落、无序/有序列表、引用、围栏代码块（带语言与复制）、
/// 分隔线，以及行内的粗体 / 斜体 / 行内代码 / 链接。表格与 HTML 原样跳过。
/// </summary>
public partial class MarkdownView : UserControl
{
    private static readonly MarkdownPipeline Pipeline =
        new MarkdownPipelineBuilder().UseEmphasisExtras().Build();

    private static readonly IBrush TextBrush = Brush.Parse("#D8DEE9");
    private static readonly IBrush MutedBrush = Brush.Parse("#8A93A6");
    private static readonly IBrush AccentBrush = Brush.Parse("#3DF5A8");
    private static readonly IBrush LineBrush = Brush.Parse("#1E2635");
    private static readonly IBrush CodeBackground = Brush.Parse("#0C111B");
    private static readonly IBrush CodeTextBrush = Brush.Parse("#C8D0E4");
    private static readonly IBrush InlineCodeBackground = Brush.Parse("#141A24");
    private static readonly FontFamily MonoFont = new("Cascadia Code, JetBrains Mono, Menlo, Consolas, monospace");

    private string _rendered = "";

    public static readonly StyledProperty<string> MarkdownProperty =
        AvaloniaProperty.Register<MarkdownView, string>(nameof(Markdown), "");

    public MarkdownView()
    {
        InitializeComponent();
    }

    public string Markdown
    {
        get => GetValue(MarkdownProperty);
        set => SetValue(MarkdownProperty, value);
    }

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == MarkdownProperty)
        {
            Render(change.GetNewValue<string>() ?? "");
        }
    }

    private void Render(string markdown)
    {
        if (markdown == _rendered)
        {
            return;
        }
        _rendered = markdown;

        var root = this.FindControl<StackPanel>("Root");
        if (root is null)
        {
            return;
        }

        root.Children.Clear();
        if (string.IsNullOrWhiteSpace(markdown))
        {
            return;
        }

        var document = Markdig.Markdown.Parse(markdown, Pipeline);
        foreach (var block in document)
        {
            var control = BuildBlock(block);
            if (control is not null)
            {
                root.Children.Add(control);
            }
        }
    }

    private Control? BuildBlock(Block block)
    {
        switch (block)
        {
            case HeadingBlock heading:
                var headingText = new TextBlock
                {
                    FontSize = heading.Level switch
                    {
                        1 => 15,
                        2 => 14,
                        3 => 13,
                        _ => 12,
                    },
                    FontWeight = FontWeight.SemiBold,
                    Foreground = TextBrush,
                    TextWrapping = TextWrapping.Wrap,
                    Margin = new Thickness(0, 6, 0, 2),
                };
                if (heading.Inline is not null)
                {
                    AppendInlines(headingText.Inlines!, heading.Inline);
                }
                return headingText;

            case ParagraphBlock paragraph:
                var paragraphText = BuildParagraph(paragraph);
                paragraphText.Margin = new Thickness(0, 0, 0, 4);
                return paragraphText;

            case ListBlock list:
                return BuildList(list);

            case QuoteBlock quote:
                var quotePanel = new StackPanel { Spacing = 2 };
                foreach (var child in quote)
                {
                    var control = BuildBlock(child);
                    if (control is not null)
                    {
                        quotePanel.Children.Add(control);
                    }
                }
                return new Border
                {
                    BorderBrush = LineBrush,
                    BorderThickness = new Thickness(2, 0, 0, 0),
                    Padding = new Thickness(8, 2, 0, 2),
                    Child = quotePanel,
                };

            case FencedCodeBlock fenced:
                return BuildCode(fenced, fenced.Info ?? "");

            case CodeBlock code:
                return BuildCode(code, "");

            case ThematicBreakBlock:
                return new Border
                {
                    Height = 1,
                    Background = LineBrush,
                    Margin = new Thickness(0, 6),
                };

            case HtmlBlock:
                return null;

            default:
                return null;
        }
    }

    private static TextBlock BuildParagraph(LeafBlock block, double fontSize = 12)
    {
        var text = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            FontSize = fontSize,
            LineHeight = fontSize * 1.6,
            Foreground = TextBrush,
        };
        if (block.Inline is not null)
        {
            AppendInlines(text.Inlines!, block.Inline);
        }
        return text;
    }

    private static void AppendInlines(InlineCollection target, ContainerInline container)
    {
        foreach (var inline in container)
        {
            switch (inline)
            {
                case LiteralInline literal:
                    target.Add(new Run(literal.Content.ToString()));
                    break;

                case CodeInline code:
                    target.Add(new InlineUIContainer(new Border
                    {
                        Background = InlineCodeBackground,
                        BorderBrush = LineBrush,
                        BorderThickness = new Thickness(1),
                        CornerRadius = new CornerRadius(3),
                        Padding = new Thickness(3, 0),
                        Child = new TextBlock
                        {
                            Text = code.Content,
                            FontFamily = MonoFont,
                            FontSize = 11,
                            Foreground = TextBrush,
                        },
                    }));
                    break;

                case EmphasisInline emphasis:
                    var bold = emphasis.DelimiterCount >= 2;
                    var italic = emphasis.DelimiterChar == '~' || emphasis.DelimiterCount == 1;
                    target.Add(new Run(Flatten(emphasis))
                    {
                        FontWeight = bold ? FontWeight.SemiBold : FontWeight.Normal,
                        FontStyle = italic && !bold ? FontStyle.Italic : FontStyle.Normal,
                        Foreground = TextBrush,
                    });
                    break;

                case LinkInline link when !link.IsImage:
                    target.Add(new Run(Flatten(link))
                    {
                        Foreground = AccentBrush,
                        TextDecorations = TextDecorations.Underline,
                    });
                    break;

                case LinkInline link:
                    target.Add(new Run(Flatten(link)));
                    break;

                case LineBreakInline:
                    target.Add(new Run("\n"));
                    break;

                case ContainerInline nested:
                    AppendInlines(target, nested);
                    break;
            }
        }
    }

    private static string Flatten(ContainerInline container)
    {
        var builder = new StringBuilder();
        foreach (var child in container)
        {
            switch (child)
            {
                case LiteralInline literal:
                    builder.Append(literal.Content.ToString());
                    break;
                case CodeInline code:
                    builder.Append(code.Content);
                    break;
                case LineBreakInline:
                    builder.Append('\n');
                    break;
                case ContainerInline nested:
                    builder.Append(Flatten(nested));
                    break;
            }
        }
        return builder.ToString();
    }

    private static Control BuildList(ListBlock list)
    {
        var panel = new StackPanel
        {
            Spacing = 3,
            Margin = new Thickness(4, 0, 0, 4),
        };
        var index = 1;
        foreach (var item in list.OfType<ListItemBlock>())
        {
            var row = new Grid
            {
                ColumnDefinitions = new ColumnDefinitions("20,*"),
            };
            row.Children.Add(new TextBlock
            {
                Text = list.IsOrdered ? $"{index}." : "•",
                FontSize = 12,
                Foreground = MutedBrush,
                VerticalAlignment = VerticalAlignment.Top,
            });
            var body = new StackPanel { Spacing = 2 };
            foreach (var child in item)
            {
                if (child is ParagraphBlock paragraph)
                {
                    body.Children.Add(BuildParagraph(paragraph));
                }
                else if (child is ListBlock nested)
                {
                    body.Children.Add(BuildList(nested));
                }
            }
            Grid.SetColumn(body, 1);
            row.Children.Add(body);
            panel.Children.Add(row);
            index += 1;
        }
        return panel;
    }

    private Control BuildCode(LeafBlock block, string language)
    {
        var code = (block.Lines.ToString() ?? "").TrimEnd('\n');
        var header = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto"),
            Margin = new Thickness(10, 5, 10, 5),
        };
        header.Children.Add(new TextBlock
        {
            Text = string.IsNullOrWhiteSpace(language) ? "code" : language,
            FontFamily = MonoFont,
            FontSize = 10,
            Foreground = MutedBrush,
            VerticalAlignment = VerticalAlignment.Center,
        });

        var copy = new Button
        {
            Content = "复制",
            FontSize = 10,
            Padding = new Thickness(8, 1),
            Background = Brushes.Transparent,
            Foreground = MutedBrush,
            BorderThickness = new Thickness(0),
        };
        copy.Click += async (_, _) => await CopyCodeAsync(code);
        Grid.SetColumn(copy, 1);
        header.Children.Add(copy);

        var body = new SelectableTextBlock
        {
            Text = code,
            FontFamily = MonoFont,
            FontSize = 11,
            Foreground = CodeTextBrush,
            TextWrapping = TextWrapping.NoWrap,
            Margin = new Thickness(10, 0, 10, 8),
        };
        var scroller = new ScrollViewer
        {
            Content = body,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
        };

        var stack = new StackPanel();
        stack.Children.Add(header);
        stack.Children.Add(new Border { Height = 1, Background = LineBrush });
        stack.Children.Add(scroller);

        return new Border
        {
            Background = CodeBackground,
            BorderBrush = LineBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(6),
            ClipToBounds = true,
            Margin = new Thickness(0, 4, 0, 4),
            Child = stack,
        };
    }

    private async Task CopyCodeAsync(string code)
    {
        var clipboard = TopLevel.GetTopLevel(this)?.Clipboard;
        if (clipboard is null)
        {
            return;
        }
        try
        {
            await clipboard.SetTextAsync(code);
        }
        catch
        {
            // 剪贴板被系统占用时忽略，不打断阅读
        }
    }
}

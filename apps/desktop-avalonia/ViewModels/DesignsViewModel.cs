using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Input;
using Avalonia.Media;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Flux.Desktop.Models;
using Flux.Desktop.Services;

namespace Flux.Desktop.ViewModels;

/// <summary>
/// 设计页（Luma）：左侧设计稿列表，中间画布，右侧 AI 修改与历史版本。
/// 所有写操作都落到原始 JsonObject 上再整份保存，避免反序列化模型丢字段。
/// </summary>
public partial class DesignsViewModel : ViewModelBase
{
    private readonly Func<FluxApiClient?> _api;

    /// <summary>整份设计稿的真源：保存、AI 补丁、回滚都以它为准。</summary>
    private JsonObject? _raw;

    private int _version;
    private string _designId = "";
    private int _activeScreenIndex;

    /// <summary>AI 请求前的画板快照；应用预览后仍保留，供一键撤销。</summary>
    private JsonObject? _aiBackup;

    private DesignAiPlan? _aiPlan;
    private CancellationTokenSource? _aiCts;
    private CancellationTokenSource? _aiApplyCts;
    private DateTime _aiStartedAt;

    private readonly DispatcherTimer _aiTimer = new() { Interval = TimeSpan.FromMilliseconds(500) };

    /// <summary>画布手工编辑的撤销 / 重做栈：保存原始 JSON 快照，避免反序列化丢字段。</summary>
    private readonly Stack<JsonObject> _undoStack = new();
    private readonly Stack<JsonObject> _redoStack = new();

    /// <summary>最近一次保存 / 打开时的 JSON 文本，用于判断当前是否真的偏离已保存版本。</summary>
    private string _savedRawText = "";

    /// <summary>本次手势按下时的快照；只有真的改了东西才压进撤销栈。</summary>
    private JsonObject? _pendingEdit;

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private bool _hasUnsavedChanges;

    [ObservableProperty]
    private bool _canUndo;

    [ObservableProperty]
    private bool _canRedo;

    [ObservableProperty]
    private bool _isCanvasEditing;

    [ObservableProperty]
    private string _canvasHint = "点按钮 / 滑杆 / 开关可交互；打开「编辑画布」可拖动、缩放和吸附。";

    // ---- 右侧「选中图层」轻编辑面板 ----
    [ObservableProperty]
    private bool _hasCanvasSelection;

    [ObservableProperty]
    private bool _hasNodeSelection;

    [ObservableProperty]
    private bool _nodeMulti;

    [ObservableProperty]
    private string _selectedNodeLabel = "";

    [ObservableProperty]
    private string _nodeText = "";

    [ObservableProperty]
    private string _nodeFill = "";

    [ObservableProperty]
    private string _nodeColor = "";

    [ObservableProperty]
    private string _nodeStroke = "";

    [ObservableProperty]
    private string _nodeX = "";

    [ObservableProperty]
    private string _nodeY = "";

    [ObservableProperty]
    private string _nodeW = "";

    [ObservableProperty]
    private string _nodeH = "";

    [ObservableProperty]
    private string _nodeFontSize = "";

    [ObservableProperty]
    private string _nodeRadius = "";

    [ObservableProperty]
    private string _nodeOpacity = "";

    private string _selectedNodeId = "";


    [ObservableProperty]
    private bool _isAiBusy;

    [ObservableProperty]
    private bool _hasDesigns;

    [ObservableProperty]
    private bool _hasDesign;

    [ObservableProperty]
    private bool _hasScreens;

    [ObservableProperty]
    private bool _hasRevisions;

    [ObservableProperty]
    private string _hint = "";

    [ObservableProperty]
    private string _title = "";

    [ObservableProperty]
    private string _meta = "";

    [ObservableProperty]
    private string _metaDetail = "";

    [ObservableProperty]
    private string _aiPrompt = "";

    [ObservableProperty]
    private string _aiState = "";

    [ObservableProperty]
    private string _aiReply = "";

    [ObservableProperty]
    private string _aiElapsedText = "";

    /// <summary>待确认的预览稿；非空时画布显示它，真源 `_raw` 尚未改变。</summary>
    [ObservableProperty]
    private DesignDocument? _previewDocument;

    [ObservableProperty]
    private IReadOnlyList<DesignHighlight>? _highlights;

    [ObservableProperty]
    private bool _hasAiPreview;

    [ObservableProperty]
    private bool _isAiApplying;

    /// <summary>应用后还没保存：允许一键撤销或点保存写入服务端。</summary>
    [ObservableProperty]
    private bool _hasUnsavedAi;

    [ObservableProperty]
    private DesignDocument? _document;

    public DesignsViewModel()
        : this(() => null)
    {
    }

    public DesignsViewModel(Func<FluxApiClient?> api)
    {
        _api = api;
        _aiTimer.Tick += (_, _) =>
        {
            if (!IsAiBusy)
            {
                return;
            }

            var seconds = Math.Max(0, (int)(DateTime.UtcNow - _aiStartedAt).TotalSeconds);
            AiElapsedText = $"已用时 {seconds}s · 可随时取消";
        };
    }

    public ObservableCollection<DesignAiChangeView> AiChanges { get; } = new();

    /// <summary>当前画板索引（-1 表示没有画板）。</summary>
    public int ActiveScreenIndex
    {
        get => _activeScreenIndex;
        private set
        {
            if (_activeScreenIndex == value)
            {
                return;
            }

            _activeScreenIndex = value;
            OnPropertyChanged();
            foreach (var tab in Screens)
            {
                tab.IsActive = tab.Index == value;
            }
        }
    }

    public ObservableCollection<DesignCard> Designs { get; } = new();

    public ObservableCollection<DesignScreenTab> Screens { get; } = new();

    public ObservableCollection<DesignRevisionView> Revisions { get; } = new();

    [RelayCommand]
    private async Task Refresh() => await RefreshAsync();

    public async Task RefreshAsync()
    {
        var api = _api();
        if (api is null)
        {
            Hint = "后端尚未就绪。";
            AppLog.Warn("设计页刷新跳过：后端未就绪");
            return;
        }

        IsBusy = true;
        try
        {
            var designs = (await api.ListDesignsAsync())?.Data ?? new List<DesignSummary>();
            Designs.Clear();
            foreach (var design in designs)
            {
                Designs.Add(new DesignCard(design, OpenCommand));
            }

            HasDesigns = Designs.Count > 0;
            Hint = HasDesigns ? $"共 {Designs.Count} 份设计稿" : "还没有设计稿";

            // 刷新时保留已打开的那份，首次进入则自动打开第一份
            var keep = Designs.FirstOrDefault(card => card.Id == _designId) ?? Designs.FirstOrDefault();
            if (keep is not null)
            {
                await LoadAsync(keep, keep.Id != _designId);
            }
            else
            {
                ClearDesign();
            }

            AppLog.Info($"设计页刷新 · {Designs.Count} 份");
        }
        catch (Exception ex)
        {
            Hint = $"加载失败：{ex.Message}";
            AppLog.Error("设计页刷新失败", ex);
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task Open(DesignCard? card)
    {
        if (card is not null)
        {
            await LoadAsync(card, card.Id != _designId);
        }
    }

    /// <summary>导入 .svg/.fig/.sketch；成功后刷新列表并直接打开新导入的设计稿。</summary>
    public async Task ImportAsync(string filePath)
    {
        var api = _api();
        if (api is null)
        {
            Hint = "后端尚未就绪。";
            return;
        }

        var fileName = Path.GetFileName(filePath);
        IsBusy = true;
        Hint = $"正在导入 {fileName}…";
        try
        {
            var envelope = await api.ImportDesignAsync(filePath);
            if (envelope is null || envelope.Success == false || envelope.Data is not JsonObject raw)
            {
                Hint = envelope?.Message ?? "导入失败";
                return;
            }

            var newId = raw["id"]?.ToString() ?? "";
            var warnings = envelope.Metadata?["warnings"] is JsonArray array ? array.Count : 0;
            await RefreshAsync();

            var card = Designs.FirstOrDefault(item => item.Id == newId);
            if (card is not null)
            {
                await LoadAsync(card, true);
            }

            Hint = warnings > 0
                ? $"已导入 {card?.Name ?? fileName}，有 {warnings} 条导入提示"
                : $"已导入 {card?.Name ?? fileName}";
            AppLog.Info($"设计页导入 · {newId} warnings={warnings}");
        }
        catch (Exception ex)
        {
            Hint = $"导入失败：{ex.Message}";
            AppLog.Error("设计稿导入失败", ex);
        }
        finally
        {
            IsBusy = false;
        }
    }

    /// <summary>导出当前画板为 SVG（找不到画板 id 时整份导出）。</summary>
    public async Task ExportSvgAsync(string filePath)
    {
        var api = _api();
        if (api is null || _raw is null || _designId.Length == 0)
        {
            Hint = "先打开一份设计稿再导出。";
            return;
        }

        var screen = "all";
        if (DesignOps.FindScreen(_raw, ActiveScreenIndex)?["id"]?.ToString() is { Length: > 0 } screenId)
        {
            screen = screenId;
        }

        IsBusy = true;
        Hint = "正在导出 SVG…";
        try
        {
            var (svg, error) = await api.ExportDesignSvgAsync(_designId, screen);
            if (svg is null)
            {
                Hint = error ?? "导出失败";
                return;
            }

            await File.WriteAllBytesAsync(filePath, svg);
            var size = svg.Length >= 1024 ? $"{svg.Length / 1024} KB" : $"{svg.Length} B";
            Hint = $"已导出 {Path.GetFileName(filePath)}（{size}）";
            AppLog.Info($"设计页导出 · {_designId} screen={screen} bytes={svg.Length}");
        }
        catch (Exception ex)
        {
            Hint = $"导出失败：{ex.Message}";
            AppLog.Error("设计稿导出失败", ex);
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    private async Task Preview() => await PreviewAsync();

    /// <summary>把后端渲染的交互 HTML 写到临时文件并用系统浏览器打开。</summary>
    public async Task PreviewAsync()
    {
        var api = _api();
        if (api is null || _designId.Length == 0)
        {
            Hint = "先打开一份设计稿再预览。";
            return;
        }

        IsBusy = true;
        Hint = "正在生成网页预览…";
        try
        {
            var envelope = await api.GetDesignPreviewAsync(_designId);
            if (envelope is null || envelope.Success == false || envelope.Data?.Html is not { Length: > 0 } html)
            {
                Hint = envelope?.Message ?? "预览生成失败";
                return;
            }

            // snap 版 Chromium 只能读 $HOME 下的非隐藏路径：/tmp 与 ~/.flux 都会 ERR_ACCESS_DENIED。
            var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            var dir = string.IsNullOrEmpty(home)
                ? Path.Combine(Path.GetTempPath(), "flux-preview")
                : Path.Combine(home, "Flux", "preview");
            Directory.CreateDirectory(dir);
            var file = Path.Combine(dir, $"flux-preview-{_designId}.html");
            await File.WriteAllTextAsync(file, html);
            Process.Start(new ProcessStartInfo(file) { UseShellExecute = true });
            Hint = $"已在浏览器打开预览（{envelope.Data.ScreenCount} 屏 · {file}）";
            AppLog.Info($"设计页预览 · {_designId} screens={envelope.Data.ScreenCount} html={html.Length}");
        }
        catch (Exception ex)
        {
            Hint = $"预览失败：{ex.Message}";
            AppLog.Error("设计稿预览失败", ex);
        }
        finally
        {
            IsBusy = false;
        }
    }

    private async Task LoadAsync(DesignCard card, bool resetScreen)
    {
        var api = _api();
        if (api is null)
        {
            return;
        }

        IsBusy = true;
        try
        {
            var envelope = await api.GetDesignAsync(card.Id);
            if (envelope is null || envelope.Success == false || envelope.Data is not JsonObject raw)
            {
                Hint = envelope?.Message ?? "打开设计稿失败";
                return;
            }

            _designId = card.Id;
            if (resetScreen)
            {
                _activeScreenIndex = 0;
            }

            foreach (var item in Designs)
            {
                item.IsSelected = item.Id == _designId;
            }

            ClearAiPlan();
            _aiBackup = null;
            HasUnsavedAi = false;
            AiState = "";
            AiReply = "";
            AiElapsedText = "";
            ApplyDocument(raw);
            _savedRawText = raw.ToJsonString();
            HasUnsavedChanges = false;
            ResetHistory();
            await LoadRevisionsAsync(api);
            AppLog.Info($"设计页打开 · {card.Id} v{_version}");
        }
        catch (Exception ex)
        {
            Hint = $"打开失败：{ex.Message}";
            AppLog.Error("打开设计稿失败", ex);
        }
        finally
        {
            IsBusy = false;
        }
    }

    private void ApplyDocument(JsonObject raw)
    {
        _raw = raw;
        _version = ReadInt(raw, "version");
        var document = JsonSerializer.Deserialize<DesignDocument>(raw, DesignJson.Options);
        Document = document;

        Screens.Clear();
        List<DesignScreen> screens = document?.Screens ?? new List<DesignScreen>();
        for (var i = 0; i < screens.Count; i++)
        {
            Screens.Add(new DesignScreenTab(i, screens[i], SelectScreenCommand));
        }

        HasScreens = screens.Count > 0;
        HasDesign = true;
        ActiveScreenIndex = screens.Count == 0 ? -1 : Math.Clamp(_activeScreenIndex, 0, screens.Count - 1);

        Title = string.IsNullOrWhiteSpace(document?.Name) ? _designId : document!.Name!;
        var layers = screens.Sum(screen => CountLayers(screen.Nodes));
        Meta = $"v{_version} · {screens.Count} 屏 · {layers} 层";
        MetaDetail = screens.Count == 0
            ? "这份设计稿还没有画板"
            : $"当前画板：{Screens[Math.Max(ActiveScreenIndex, 0)].Label}";
        Hint = $"已打开 {Title}";
    }

    private void ClearDesign()
    {
        _raw = null;
        _version = 0;
        _designId = "";
        Document = null;
        Screens.Clear();
        Revisions.Clear();
        ActiveScreenIndex = -1;
        HasDesign = false;
        HasScreens = false;
        HasRevisions = false;
        Title = "";
        Meta = "";
        MetaDetail = "";
        AiState = "";
        AiReply = "";
        AiElapsedText = "";
        ClearAiPlan();
        _aiBackup = null;
        HasUnsavedAi = false;
        HasUnsavedChanges = false;
        _savedRawText = "";
        ResetHistory();
    }

    private async Task LoadRevisionsAsync(FluxApiClient api)
    {
        var revisions = (await api.ListDesignRevisionsAsync(_designId))?.Data ?? new List<DesignRevision>();
        Revisions.Clear();
        foreach (var revision in revisions)
        {
            Revisions.Add(new DesignRevisionView(revision, RestoreCommand));
        }

        HasRevisions = Revisions.Count > 0;
    }

    [RelayCommand]
    private void SelectScreen(DesignScreenTab? tab)
    {
        if (tab is null)
        {
            return;
        }

        ActiveScreenIndex = tab.Index;
        MetaDetail = $"当前画板：{tab.Label}";
    }

    // ------------------------------------------------------------ 画布编辑 / 撤销

    partial void OnIsCanvasEditingChanged(bool value)
    {
        CanvasHint = value
            ? "拖图层移动 · 拖手柄缩放 · Shift 等比 · Shift 旋转 15° · 滚轮平移 · Ctrl+滚轮缩放 · 空格拖动 · Ctrl+Z 撤销"
            : "点按钮 / 滑杆 / 开关可交互；打开「编辑画布」可拖动、缩放和吸附。";
    }

    [RelayCommand]
    private void ToggleCanvasEditing() => IsCanvasEditing = !IsCanvasEditing;

    /// <summary>画布手势开始：记下快照，真要改了才在提交时压栈。</summary>
    public void BeginCanvasEdit()
    {
        if (_raw is null) return;
        _pendingEdit = (JsonObject)_raw.DeepClone();
    }

    /// <summary>手势结束：把最终几何写回原始 JSON，再刷新只读视图。</summary>
    public void CommitCanvasEdit(IReadOnlyList<DesignFrameChange> changes)
    {
        if (_raw is null || changes.Count == 0) return;
        var changed = false;
        foreach (var change in changes)
        {
            changed |= DesignOps.SetNodeFrame(
                _raw,
                change.Id,
                change.X,
                change.Y,
                change.W,
                change.H,
                change.Rotation);
        }
        if (!changed)
        {
            _pendingEdit = null;
            return;
        }

        if (_pendingEdit is not null)
        {
            PushUndo(_pendingEdit);
        }
        HasUnsavedChanges = SavedDiffers();
        RefreshCanvasDocument();
        AiState = "画布已修改，点「保存」写入设计稿";
    }

    public void DeleteCanvasNodes(IReadOnlyList<string> ids)
    {
        if (_raw is null || ids.Count == 0) return;
        var snapshot = (JsonObject)_raw.DeepClone();
        if (DesignOps.RemoveNodes(_raw, ids) == 0) return;
        PushUndo(snapshot);
        HasUnsavedChanges = SavedDiffers();
        RefreshCanvasDocument();
        AiState = $"已删除 {ids.Count} 个图层，点「保存」写入设计稿";
    }

    [RelayCommand]
    private void UndoEdit()
    {
        if (_raw is null || _undoStack.Count == 0) return;
        _pendingEdit = null;
        _redoStack.Push((JsonObject)_raw.DeepClone());
        _raw = _undoStack.Pop();
        HasUnsavedChanges = SavedDiffers();
        UpdateHistory();
        RefreshCanvasDocument();
        AiState = "已撤销上一步画布修改";
    }

    [RelayCommand]
    private void RedoEdit()
    {
        if (_raw is null || _redoStack.Count == 0) return;
        _pendingEdit = null;
        _undoStack.Push((JsonObject)_raw.DeepClone());
        _raw = _redoStack.Pop();
        HasUnsavedChanges = SavedDiffers();
        UpdateHistory();
        RefreshCanvasDocument();
        AiState = "已重做上一步画布修改";
    }

    private bool SavedDiffers() =>
        _raw is not null && _savedRawText.Length > 0 &&
        !string.Equals(_raw.ToJsonString(), _savedRawText, StringComparison.Ordinal);

    private void UpdateHistory()
    {
        CanUndo = _undoStack.Count > 0;
        CanRedo = _redoStack.Count > 0;
    }

    private void ResetHistory()
    {
        _undoStack.Clear();
        _redoStack.Clear();
        _pendingEdit = null;
        UpdateHistory();
    }

    /// <summary>只刷新画布绑定的只读文档，不重建页签 / 不重置当前画板。</summary>
    private void RefreshCanvasDocument()
    {
        if (_raw is null) return;
        try
        {
            var document = JsonSerializer.Deserialize<DesignDocument>(_raw, DesignJson.Options);
            Document = document;
            var layers = (document?.Screens ?? new List<DesignScreen>())
                .Sum(screen => CountLayers(screen.Nodes));
            Meta = $"v{_version} · {Screens.Count} 屏 · {layers} 层";
        }
        catch (Exception ex)
        {
            AppLog.Error("画布编辑后刷新文档失败", ex);
        }
    }

    /// <summary>画布选中变化：单选时读出可编辑属性，多选时只显示数量。</summary>
    public void SelectCanvasNode(IReadOnlyList<string> ids)
    {
        HasCanvasSelection = ids.Count > 0;
        NodeMulti = ids.Count > 1;
        if (ids.Count != 1 || _raw is null || DesignOps.GetNode(_raw, ids[0]) is not { } node)
        {
            _selectedNodeId = "";
            HasNodeSelection = false;
            SelectedNodeLabel = ids.Count > 1 ? $"已选 {ids.Count} 个图层" : "";
            return;
        }

        _selectedNodeId = ids[0];
        HasNodeSelection = true;
        var type = (Text(node["type"]) is { Length: > 0 } t ? t : "text");
        var name = Text(node["name"]);
        SelectedNodeLabel = (name.Length > 0 ? name : type) + " · " + type;
        NodeText = Text(node["text"]);
        NodeFill = Text(node["fill"]);
        NodeColor = Text(node["color"]);
        NodeStroke = node["stroke"] is JsonObject stroke ? Text(stroke["color"]) : Text(node["stroke"]);
        NodeX = NumberText(node["x"]);
        NodeY = NumberText(node["y"]);
        NodeW = NumberText(node["w"]);
        NodeH = NumberText(node["h"]);
        NodeFontSize = NumberText(node["fontSize"]);
        NodeRadius = node["radius"] is JsonObject radius
            ? NumberText(radius["tl"])
            : NumberText(node["radius"]);
        NodeOpacity = NumberText(node["opacity"]);
    }

    /// <summary>把右侧面板的数值 / 文本一次性写回图层；空字段表示不修改。</summary>
    [RelayCommand]
    private void ApplyNodeEdits()
    {
        if (_raw is null || _selectedNodeId.Length == 0) return;
        if (DesignOps.GetNode(_raw, _selectedNodeId) is not { } node)
        {
            AiState = "选中的图层已不存在";
            return;
        }

        var snapshot = (JsonObject)_raw.DeepClone();
        var set = new JsonObject();
        if (NodeText.Length > 0) set["text"] = NodeText;
        if (NodeFill.Trim().Length > 0) set["fill"] = NodeFill.Trim();
        if (NodeColor.Trim().Length > 0) set["color"] = NodeColor.Trim();

        void Number(string key, string raw)
        {
            if (double.TryParse(raw.Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out var value))
            {
                set[key] = value;
            }
        }

        Number("x", NodeX);
        Number("y", NodeY);
        Number("w", NodeW);
        Number("h", NodeH);
        Number("fontSize", NodeFontSize);
        Number("radius", NodeRadius);
        Number("opacity", NodeOpacity);

        var changed = 0;
        var stroke = NodeStroke.Trim();
        if (stroke.Equals("none", StringComparison.OrdinalIgnoreCase))
        {
            node.Remove("stroke");
            changed++;
        }
        else if (stroke.Length > 0)
        {
            set["stroke"] = stroke;
        }

        changed += DesignOps.ApplyPatch(node, set);
        if (changed == 0)
        {
            AiState = "没有可应用的修改";
            return;
        }

        PushUndo(snapshot);
        HasUnsavedChanges = SavedDiffers();
        UpdateHistory();
        RefreshCanvasDocument();
        SelectCanvasNode(new[] { _selectedNodeId });
        AiState = "图层已修改，点「保存」写入设计稿";
    }

    [RelayCommand]
    private void DeleteSelectedNode()
    {
        if (_selectedNodeId.Length == 0) return;
        DeleteCanvasNodes(new[] { _selectedNodeId });
    }

    private void PushUndo(JsonObject snapshot)
    {
        _pendingEdit = null;
        _undoStack.Push(snapshot);
        _redoStack.Clear();
        UpdateHistory();
    }

    private static string Text(JsonNode? value)
    {
        if (value is null) return "";
        return value is JsonValue json && json.TryGetValue<string>(out var text) ? text : "";
    }

    private static string NumberText(JsonNode? value)
    {
        if (value is null) return "";
        if (value is JsonValue json && json.TryGetValue<double>(out var number))
        {
            return Math.Abs(number - Math.Round(number)) < 1e-9
                ? ((int)Math.Round(number)).ToString(CultureInfo.InvariantCulture)
                : number.ToString("0.###", CultureInfo.InvariantCulture);
        }
        return Text(value);
    }

    [RelayCommand]
    private async Task Ask() => await AskAsync();

    /// <summary>
    /// 让 AI 生成修改计划：请求期间显示「理解 → 生成」阶段与已用时，可取消；
    /// 结果只生成预览稿与变更清单，用户点「应用全部」后才写入画布。
    /// </summary>
    private async Task AskAsync()
    {
        var api = _api();
        var prompt = AiPrompt.Trim();
        if (api is null || _raw is null)
        {
            AiState = "设计稿还没加载";
            return;
        }

        if (prompt.Length == 0)
        {
            AiState = "写一句想怎么改，比如「标题再大一点」";
            return;
        }

        if (prompt.Length > 400)
        {
            AiState = "指令太长，控制在 400 字内";
            return;
        }

        var screen = DesignOps.FindScreen(_raw, ActiveScreenIndex);
        if (screen is null)
        {
            AiState = "当前画板不存在";
            return;
        }

        var backup = (JsonObject)_raw.DeepClone();
        var payload = new JsonObject
        {
            ["design"] = _designId,
            ["mode"] = "screen",
            ["screen"] = DesignOps.BuildAskScreen(screen),
            ["nodes"] = new JsonArray(),
            ["prompt"] = prompt,
        };

        ClearAiPlan();
        _aiBackup = backup;
        _aiCts?.Cancel();
        _aiCts = new CancellationTokenSource();
        var token = _aiCts.Token;
        IsAiBusy = true;
        IsAiApplying = false;
        AiReply = "";
        AiState = "正在理解整屏…";
        _aiStartedAt = DateTime.UtcNow;
        AiElapsedText = "已用时 0s · 可随时取消";
        _aiTimer.Start();
        try
        {
            // 上游是一次性返回：1200ms 后把阶段换成「正在生成修改」，
            // 不假装模型逐 token 输出；真正的逐步变化发生在拿到 ops 之后。
            _ = Task.Run(async () =>
            {
                try
                {
                    await Task.Delay(1200, token);
                    if (!token.IsCancellationRequested && IsAiBusy)
                    {
                        await Dispatcher.UIThread.InvokeAsync(() => AiState = "正在生成修改…");
                    }
                }
                catch (OperationCanceledException)
                {
                    // 用户取消：阶段文案由 CancelAi 负责。
                }
            }, token);

            var response = await api.AskDesignAiAsync(_designId, payload, token);
            if (token.IsCancellationRequested)
            {
                return;
            }

            if (response is null || response.Success == false || response.Data is null)
            {
                AiState = response?.Message ?? "调用 AI 失败";
                return;
            }

            var ops = response.Data.Ops;
            if (ops is null || ops.Count == 0)
            {
                AiState = "AI 没有给出可应用的修改，请换个说法再试";
                return;
            }

            var plan = DesignAiPlanner.Plan(_raw, ActiveScreenIndex, ops, response.Data.Reply ?? "");
            if (!plan.HasChanges)
            {
                AiState = "AI 给的修改无法应用到当前画板";
                return;
            }

            _aiPlan = plan;
            PreviewDocument = plan.Preview.Deserialize<DesignDocument>(DesignJson.Options);
            Highlights = plan.Changes
                .Where(change => change.HasFrame)
                .Select(change => new DesignHighlight
                {
                    Id = change.NodeId,
                    Kind = change.Kind switch
                    {
                        DesignChangeKind.Added => "added",
                        DesignChangeKind.Removed => "removed",
                        DesignChangeKind.Reordered => "reordered",
                        _ => "changed",
                    },
                    X = change.X,
                    Y = change.Y,
                    W = change.W,
                    H = change.H,
                })
                .ToList();
            AiChanges.Clear();
            foreach (var change in plan.Changes)
            {
                AiChanges.Add(new DesignAiChangeView(change));
            }

            HasAiPreview = true;
            AiState = plan.StatLine + "，确认后应用";
            AiReply = plan.Reply;
            AppLog.Info($"设计页 AI 计划 · {_designId} applied={plan.Applied} changes={plan.Changes.Count}");
        }
        catch (OperationCanceledException)
        {
            AiState = "已取消这次修改";
        }
        catch (Exception ex)
        {
            AiState = $"AI 修改失败：{ex.Message}";
            AppLog.Error("设计页 AI 修改失败", ex);
        }
        finally
        {
            _aiTimer.Stop();
            AiElapsedText = "";
            IsAiBusy = false;
            if (token.IsCancellationRequested && !HasAiPreview)
            {
                AiState = "已取消这次修改";
            }
        }
    }

    [RelayCommand]
    private async Task ApplyAi()
    {
        var plan = _aiPlan;
        if (plan is null || IsAiApplying)
        {
            return;
        }

        _aiApplyCts?.Cancel();
        _aiApplyCts = new CancellationTokenSource();
        var token = _aiApplyCts.Token;
        IsAiApplying = true;
        try
        {
            for (var i = 0; i < plan.Changes.Count; i++)
            {
                if (token.IsCancellationRequested) break;
                AiState = $"正在应用第 {i + 1}/{plan.Changes.Count} 项：{plan.Changes[i].Title}";
                Highlights = plan.Changes
                    .Take(i + 1)
                    .Where(change => change.HasFrame)
                    .Select(change => new DesignHighlight
                    {
                        Id = change.NodeId,
                        Kind = change.Kind switch
                        {
                            DesignChangeKind.Added => "added",
                            DesignChangeKind.Removed => "removed",
                            DesignChangeKind.Reordered => "reordered",
                            _ => "changed",
                        },
                        X = change.X,
                        Y = change.Y,
                        W = change.W,
                        H = change.H,
                    })
                    .ToList();
                await Task.Delay(140, token);
            }

            if (token.IsCancellationRequested)
            {
                return;
            }

            _raw = plan.Preview;
            ApplyDocument(_raw);
            // AI 修改也进同一套撤销栈：Ctrl+Z 与「撤销 AI 修改」都能回到应用前。
            if (_aiBackup is not null)
            {
                _undoStack.Push((JsonObject)_aiBackup.DeepClone());
                _redoStack.Clear();
                UpdateHistory();
            }
            HasUnsavedChanges = SavedDiffers();
            _aiPlan = null;
            HasAiPreview = false;
            HasUnsavedAi = true;
            PreviewDocument = null;
            Highlights = null;
            AiChanges.Clear();
            AiReply = plan.Reply;
            AiState = $"已应用 {plan.Applied} 项修改，点「保存」写入设计稿";
            Hint = "AI 修改已在画布上，尚未保存";
            AppLog.Info($"设计页 AI 应用 · {_designId} applied={plan.Applied}");
        }
        finally
        {
            IsAiApplying = false;
        }
    }

    [RelayCommand]
    private void CancelAi()
    {
        _aiCts?.Cancel();
        _aiApplyCts?.Cancel();
        IsAiBusy = false;
        IsAiApplying = false;
        _aiTimer.Stop();
        AiElapsedText = "";
        var hadSomething = HasAiPreview || _aiPlan is not null;
        _aiPlan = null;
        HasAiPreview = false;
        PreviewDocument = null;
        Highlights = null;
        AiChanges.Clear();
        if (hadSomething)
        {
            AiState = "已取消这次修改，画布保持原样";
            AiReply = "";
        }
    }

    [RelayCommand]
    private void UndoAi()
    {
        if (_aiBackup is null)
        {
            return;
        }

        _raw = (JsonObject)_aiBackup.DeepClone();
        ApplyDocument(_raw);
        HasUnsavedAi = false;
        HasUnsavedChanges = SavedDiffers();
        _pendingEdit = null;
        UpdateHistory();
        AiState = "已撤销 AI 修改";
        AiReply = "";
        Hint = "已撤销 AI 修改";
    }

    [RelayCommand]
    private async Task Save() => await SaveAsync();

    private async Task SaveAsync()
    {
        var api = _api();
        if (api is null || _raw is null || _designId.Length == 0)
        {
            AiState = "设计稿还没加载";
            return;
        }

        IsBusy = true;
        AiState = "正在保存…";
        try
        {
            var saved = await api.SaveDesignAsync(_designId, _raw, _version);
            if (saved is null || saved.Success == false || saved.Data is not JsonObject savedRaw)
            {
                AiState = saved?.Message ?? "保存失败";
                return;
            }

            ApplyDocument(savedRaw);
            _savedRawText = savedRaw.ToJsonString();
            HasUnsavedChanges = false;
            ResetHistory();
            HasUnsavedAi = false;
            _aiBackup = null;
            AiState = $"已保存 v{_version}";
            await LoadRevisionsAsync(api);
            AppLog.Info($"设计页保存 · {_designId} v{_version}");
        }
        catch (Exception ex)
        {
            AiState = $"保存失败：{ex.Message}";
            AppLog.Error("设计稿保存失败", ex);
        }
        finally
        {
            IsBusy = false;
        }
    }

    private void ClearAiPlan()
    {
        _aiPlan = null;
        HasAiPreview = false;
        PreviewDocument = null;
        Highlights = null;
        AiChanges.Clear();
    }

    [RelayCommand]
    private async Task Restore(DesignRevisionView? revision)
    {
        var api = _api();
        if (revision is null || _raw is null || api is null)
        {
            return;
        }

        IsBusy = true;
        AiState = $"正在恢复到 v{revision.Version}…";
        try
        {
            var response = await api.RevertDesignAsync(_designId, revision.Version, _version);
            if (response is null || response.Success == false || response.Data is not JsonObject raw)
            {
                AiState = response?.Message ?? "恢复失败";
                return;
            }

            ClearAiPlan();
            _aiBackup = null;
            HasUnsavedAi = false;
            ApplyDocument(raw);
            _savedRawText = raw.ToJsonString();
            HasUnsavedChanges = false;
            ResetHistory();
            await LoadRevisionsAsync(api);
            AiState = $"已恢复到 v{revision.Version}（当前 v{_version}）";
            AppLog.Info($"设计页回滚 · {_designId} 恢复到 v{revision.Version}");
        }
        catch (Exception ex)
        {
            AiState = $"恢复失败：{ex.Message}";
            AppLog.Error("设计稿回滚失败", ex);
        }
        finally
        {
            IsBusy = false;
        }
    }

    private static int CountLayers(IEnumerable<DesignNode>? nodes)
        => nodes?.Sum(node => 1 + CountLayers(node.Children)) ?? 0;

    private static int ReadInt(JsonObject raw, string key)
    {
        if (raw[key] is JsonValue value && value.TryGetValue<double>(out var number)
            && !double.IsNaN(number) && !double.IsInfinity(number))
        {
            return (int)number;
        }

        return 0;
    }

    /// <summary>供卡片与历史行共用的时间格式。</summary>
    internal static string FormatTime(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            return "—";
        }

        return DateTimeOffset.TryParse(raw, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var time)
            ? time.ToLocalTime().ToString("MM-dd HH:mm", CultureInfo.InvariantCulture)
            : raw;
    }
}

/// <summary>左侧设计稿卡片。</summary>
public sealed partial class DesignCard : ObservableObject
{
    public DesignCard(DesignSummary summary, ICommand open)
    {
        Id = summary.Id;
        Name = string.IsNullOrWhiteSpace(summary.Name) ? "未命名设计稿" : summary.Name;
        Preview = summary.Preview;
        MetaText = $"v{summary.Version} · {summary.ScreenCount} 屏 · {summary.LayerCount} 层";
        UpdatedText = DesignsViewModel.FormatTime(summary.UpdatedAt);
        OpenCommand = open;
    }

    public string Id { get; }

    public string Name { get; }

    public DesignPreview? Preview { get; }

    public string MetaText { get; }

    public string UpdatedText { get; }

    public ICommand OpenCommand { get; }

    [ObservableProperty]
    private bool _isSelected;
}

/// <summary>画板上方的屏幕页签。</summary>
public sealed partial class DesignScreenTab : ObservableObject
{
    public DesignScreenTab(int index, DesignScreen screen, ICommand select)
    {
        Index = index;
        Name = string.IsNullOrWhiteSpace(screen.Name) ? $"画板 {index + 1}" : screen.Name;
        Label = $"{Name} · {Number(screen.W)}×{Number(screen.H)}";
        SelectCommand = select;
    }

    public int Index { get; }

    public string Name { get; }

    public string Label { get; }

    public ICommand SelectCommand { get; }

    [ObservableProperty]
    private bool _isActive;

    private static string Number(double? value)
        => value is null ? "—" : Math.Round(value.Value, 0).ToString(CultureInfo.InvariantCulture);
}

/// <summary>AI 变更清单的一行：标题 + 人话说明 + 类型色点。</summary>
public sealed class DesignAiChangeView
{
    public DesignAiChangeView(DesignChange change)
    {
        Kind = change.Kind switch
        {
            DesignChangeKind.Added => "新增",
            DesignChangeKind.Removed => "删除",
            DesignChangeKind.Reordered => "层序",
            _ => "修改",
        };
        Title = change.Title;
        Detail = change.Detail;
        Tone = new SolidColorBrush(Color.Parse(change.Kind switch
        {
            DesignChangeKind.Added => "#10B981",
            DesignChangeKind.Removed => "#E11D48",
            DesignChangeKind.Reordered => "#64748B",
            _ => "#0891B2",
        }));
    }

    public string Kind { get; }

    public string Title { get; }

    public string Detail { get; }

    public IBrush Tone { get; }
}

/// <summary>历史版本行。</summary>
public sealed class DesignRevisionView
{
    public DesignRevisionView(DesignRevision revision, ICommand restore)
    {
        Version = revision.Version;
        Label = $"v{revision.Version}";
        MetaText = $"{revision.ScreenCount} 屏 · {revision.LayerCount} 层 · {Size(revision.SizeBytes)}";
        TimeText = DesignsViewModel.FormatTime(revision.UpdatedAt);
        RestoreCommand = restore;
    }

    public int Version { get; }

    public string Label { get; }

    public string MetaText { get; }

    public string TimeText { get; }

    public ICommand RestoreCommand { get; }

    private static string Size(long bytes)
        => bytes >= 1024 * 1024
            ? $"{bytes / 1024d / 1024d:0.#} MB"
            : bytes >= 1024
                ? $"{bytes / 1024d:0.#} KB"
                : $"{bytes} B";
}

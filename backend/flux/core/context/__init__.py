"""上下文机制（FLUX_CONTEXT_MECHANISM）的落地代码。

- `ContentStore`：内容寻址原文库，去重 + 无损压缩（§3.5 / §8.4）。
- `ContextStepRepository`：step 事件日志的读写入口 + 反向索引查询（§3.3 / §7.2）。
- `ContextLifecycle`：按 turn / run 级联删除，含孤儿 blob 回收（§8.2）。
- `build_handoff` / `HandoffBrief`：交接文档生成（§8.3 / §9 九节）。
- `catalog_line` / `render_catalog`：目录行渲染，agent 进入冷库的唯一入口（§3.4 / §6.4）。
- `extract_tokens` / `normalize_token`：反向索引词条抽取与归一化（§7.2）。
- `FeedMetrics`：投喂度量（压缩率 / 必留项命中率 / 重建一致率，§5.6）。
"""

from flux.core.context.catalog import catalog_line, render_catalog
from flux.core.context.handoff import HandoffBrief, build_handoff
from flux.core.context.index import extract_tokens, normalize_token
from flux.core.context.lifecycle import ContextLifecycle
from flux.core.context.metrics import FeedMetrics
from flux.core.context.repository import ContextStepRepository
from flux.core.context.store import ContentStore

__all__ = [
    "ContentStore",
    "ContextLifecycle",
    "ContextStepRepository",
    "FeedMetrics",
    "HandoffBrief",
    "build_handoff",
    "catalog_line",
    "extract_tokens",
    "normalize_token",
    "render_catalog",
]

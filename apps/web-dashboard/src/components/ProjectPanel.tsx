/** 左栏 · 项目与文件：项目列表/登记、工作区扫描画像、本次需求的上下文文件。 */
import { useState, type FormEvent, type JSX } from "react";

import type { Project, ScanOutcome } from "../api/types";
import { MAX_CONTEXT_FILES } from "../data/context";
import { Button, EmptyState, ErrorBanner, MetaField, Panel, PanelHeader, Tag } from "./ui";

function ListValue({ items, empty = "未发现" }: { items: string[]; empty?: string }): JSX.Element {
  if (items.length === 0) return <span className="text-faint">{empty}</span>;
  return <span className="break-words">{items.join("、")}</span>;
}

export function ProjectPanel({
  projects,
  selectedId,
  onSelect,
  onCreate,
  createBusy,
  scan,
  scanBusy,
  onScan,
  contextPaths,
  onAddPath,
  onRemovePath,
  createError,
  scanError,
  onDismissCreateError,
  onDismissScanError,
}: {
  projects: Project[];
  selectedId: string | null;
  onSelect: (projectId: string) => void;
  onCreate: (name: string, repository: string) => void;
  createBusy: boolean;
  scan: ScanOutcome | null;
  scanBusy: boolean;
  onScan: () => void;
  contextPaths: string[];
  onAddPath: (path: string) => void;
  onRemovePath: (path: string) => void;
  createError: string | null;
  scanError: string | null;
  onDismissCreateError: () => void;
  onDismissScanError: () => void;
}): JSX.Element {
  const [name, setName] = useState("");
  const [repository, setRepository] = useState("");
  const [manualPath, setManualPath] = useState("");

  const selected = projects.find((project) => project.id === selectedId) ?? null;
  const profile = scan?.profile ?? null;

  function handleCreate(event: FormEvent) {
    event.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) return;
    onCreate(trimmed, repository.trim());
    setName("");
    setRepository("");
  }

  function handleAddPath(event: FormEvent) {
    event.preventDefault();
    const trimmed = manualPath.trim();
    if (!trimmed) return;
    onAddPath(trimmed);
    setManualPath("");
  }

  return (
    <Panel className="flex-1">
      <PanelHeader
        title="项目与文件"
        subtitle={selected ? selected.name : "尚未选择项目"}
        actions={
          <Button onClick={onScan} disabled={!selectedId} busy={scanBusy} title="扫描服务端配置的工作区根目录">
            扫描工作区
          </Button>
        }
      />

      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-3">
        {/* 登记项目 */}
        <form onSubmit={handleCreate} className="space-y-1.5">
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="项目名称（必填）"
            className="w-full rounded-md border border-line bg-surface-2 px-2 py-1.5 text-xs text-text outline-none placeholder:text-faint focus:border-accent/60"
          />
          <input
            value={repository}
            onChange={(event) => setRepository(event.target.value)}
            placeholder="仓库标识，可选，如 flux"
            className="w-full rounded-md border border-line bg-surface-2 px-2 py-1.5 text-xs text-text outline-none placeholder:text-faint focus:border-accent/60"
          />
          <Button type="submit" tone="primary" busy={createBusy} className="w-full">
            登记项目
          </Button>
        </form>

        {createError ? <ErrorBanner message={createError} onDismiss={onDismissCreateError} /> : null}

        {/* 项目列表 */}
        <div>
          <p className="mb-1 text-[11px] text-faint">已登记项目（{projects.length}）</p>
          {projects.length === 0 ? (
            <p className="text-xs text-faint">还没有项目，先登记一个再扫描工作区。</p>
          ) : (
            <ul className="space-y-1">
              {projects.map((project) => {
                const active = project.id === selectedId;
                return (
                  <li key={project.id}>
                    <button
                      type="button"
                      onClick={() => onSelect(project.id)}
                      className={`w-full rounded-md border px-2 py-1.5 text-left transition-colors ${
                        active
                          ? "border-accent/50 bg-accent/10"
                          : "border-line bg-surface-2 hover:bg-surface-3"
                      }`}
                    >
                      <span className="block truncate text-xs font-medium text-text">{project.name}</span>
                      <span className="block truncate text-[11px] text-faint">
                        {project.repository || "未关联仓库"}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>

        {scanError ? <ErrorBanner message={scanError} onDismiss={onDismissScanError} /> : null}

        {/* 扫描画像 */}
        {profile ? (
          <div className="rounded-md border border-line bg-surface-2 p-2.5">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-xs font-semibold text-text">工作区画像</span>
              {profile.truncated ? <Tag className="text-warn">已按上限裁剪</Tag> : <Tag>完整</Tag>}
            </div>
            <dl className="grid grid-cols-2 gap-x-3 gap-y-2">
              <MetaField label="主语言">{profile.primary_language ?? "未知"}</MetaField>
              <MetaField label="包管理器">{profile.package_manager ?? "未知"}</MetaField>
              <MetaField label="文件数">{profile.files_scanned}</MetaField>
              <MetaField label="Git 分支">
                {profile.git_repository ? (profile.git_branch ?? "（无提交）") : "不是 Git 仓库"}
              </MetaField>
              <MetaField label="语言">
                <ListValue items={profile.languages} />
              </MetaField>
              <MetaField label="框架">
                <ListValue items={profile.frameworks} />
              </MetaField>
              <MetaField label="入口文件">
                <ListValue items={profile.entry_points} />
              </MetaField>
              <MetaField label="测试命令">
                <ListValue items={profile.test_commands} />
              </MetaField>
              <MetaField label="构建命令">
                <ListValue items={profile.build_commands} />
              </MetaField>
              <MetaField label="根目录">{profile.root}</MetaField>
            </dl>
            <p className="mt-2 text-[11px] leading-relaxed text-faint">
              扫描结果已写入 Project Brain（{scan?.recorded.length ?? 0} 条记忆）。
            </p>
          </div>
        ) : null}

        {/* 顶层结构 */}
        {profile ? (
          <div>
            <p className="mb-1 text-[11px] text-faint">
              顶层结构（{profile.structure.length}）· 点击文件加入本次需求上下文
            </p>
            <ul className="max-h-48 space-y-0.5 overflow-y-auto rounded-md border border-line bg-surface-2 p-1.5">
              {profile.structure.length === 0 ? (
                <li className="px-1 py-1 text-xs text-faint">工作区根目录为空。</li>
              ) : (
                profile.structure.map((entry) => {
                  const isDir = entry.endsWith("/");
                  const label = isDir ? entry.slice(0, -1) : entry;
                  const alreadyAdded = contextPaths.includes(entry);
                  if (isDir) {
                    return (
                      <li
                        key={entry}
                        className="flex items-center gap-1.5 px-1 py-1 text-xs text-muted"
                        title="扫描只返回顶层结构，深层文件请用下方输入框手动加入"
                      >
                        <span className="text-accent">▸</span>
                        <span className="truncate font-mono">{label}/</span>
                      </li>
                    );
                  }
                  return (
                    <li key={entry}>
                      <button
                        type="button"
                        onClick={() => onAddPath(entry)}
                        disabled={alreadyAdded || contextPaths.length >= MAX_CONTEXT_FILES}
                        className="flex w-full items-center gap-1.5 rounded px-1 py-1 text-left text-xs text-text hover:bg-surface-3 disabled:opacity-50"
                      >
                        <span className="text-faint">·</span>
                        <span className="truncate font-mono">{label}</span>
                        {alreadyAdded ? <span className="ml-auto text-[10px] text-add">已加入</span> : null}
                      </button>
                    </li>
                  );
                })
              )}
            </ul>
          </div>
        ) : (
          <EmptyState>
            选择项目后点「扫描工作区」。扫描只读、不写用户文件，返回项目画像与顶层结构。
          </EmptyState>
        )}

        {/* 上下文文件 */}
        <div className="rounded-md border border-line bg-surface-2 p-2.5">
          <p className="text-xs font-semibold text-text">
            本次需求的上下文文件（{contextPaths.length}/{MAX_CONTEXT_FILES}）
          </p>
          <p className="mt-1 text-[11px] leading-relaxed text-faint">
            扫描只给顶层结构，不是完整递归列表；深层文件请手动输入相对工作区根的路径。
          </p>
          {contextPaths.length === 0 ? (
            <p className="mt-2 text-xs text-faint">尚未指定文件，生成提案时不携带文件上下文。</p>
          ) : (
            <ul className="mt-2 flex flex-wrap gap-1">
              {contextPaths.map((path) => (
                <li key={path}>
                  <button
                    type="button"
                    onClick={() => onRemovePath(path)}
                    className="inline-flex items-center gap-1 rounded border border-line bg-surface-3 px-1.5 py-0.5 text-[11px] font-mono text-muted hover:text-danger"
                    title="从上下文移除"
                  >
                    {path}
                    <span aria-hidden="true">×</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          <form onSubmit={handleAddPath} className="mt-2 flex gap-1.5">
            <input
              value={manualPath}
              onChange={(event) => setManualPath(event.target.value)}
              placeholder="backend/flux/main.py"
              className="min-w-0 flex-1 rounded-md border border-line bg-surface-1 px-2 py-1.5 font-mono text-[11px] text-text outline-none placeholder:text-faint focus:border-accent/60"
            />
            <Button type="submit" disabled={contextPaths.length >= MAX_CONTEXT_FILES}>
              加入
            </Button>
          </form>
        </div>
      </div>
    </Panel>
  );
}

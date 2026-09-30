/**
 * 中栏顶部 · 需求输入条（原 ChangeReview 里的表单原样搬出来）。
 *
 * 只负责收集一句话需求并触发 handleGenerate；请求参数、错误与摘要的语义都在 App 里，
 * 这里不做任何数据加工。
 */
import type { FormEvent, JSX } from "react";

import { Button, ErrorBanner, Panel } from "./ui";

export function RequirementBar({
  projectName,
  contextCount,
  instruction,
  onInstructionChange,
  onGenerate,
  generateBusy,
  generateError,
  onDismissGenerateError,
  generateSummary,
}: {
  projectName: string;
  contextCount: number;
  instruction: string;
  onInstructionChange: (value: string) => void;
  onGenerate: () => void;
  generateBusy: boolean;
  generateError: string | null;
  onDismissGenerateError: () => void;
  generateSummary: string | null;
}): JSX.Element {
  function handleGenerate(event: FormEvent) {
    event.preventDefault();
    onGenerate();
  }

  return (
    <Panel className="shrink-0">
      <form onSubmit={handleGenerate} className="space-y-2 p-3">
        <div className="flex items-center justify-between gap-2">
          <span className="truncate text-xs font-semibold text-text">需求</span>
          <span className="truncate text-[11px] text-faint">
            {projectName} · 上下文文件 {contextCount} 个
          </span>
        </div>
        <textarea
          value={instruction}
          onChange={(event) => onInstructionChange(event.target.value)}
          rows={2}
          maxLength={4000}
          placeholder="用一句话描述需求，例如：把登录接口加上参数校验与错误处理"
          className="w-full resize-y rounded-md border border-line bg-surface-2 px-2.5 py-2 text-xs leading-relaxed text-text outline-none placeholder:text-faint focus:border-accent/60"
        />
        <div className="flex items-center justify-between gap-2">
          <span className="text-[11px] text-faint">
            Developer Agent 会读取上下文文件的现状，产出待审阅提案（不写用户文件）。
          </span>
          <Button type="submit" tone="primary" busy={generateBusy} disabled={!instruction.trim()}>
            让 AI 改
          </Button>
        </div>
        {generateError ? <ErrorBanner message={generateError} onDismiss={onDismissGenerateError} /> : null}
        {generateSummary ? (
          <div className="rounded-md border border-accent/30 bg-accent/5 px-2.5 py-1.5 text-xs text-muted">
            <span className="text-accent">AI 摘要：</span>
            {generateSummary}
          </div>
        ) : null}
      </form>
    </Panel>
  );
}

/**
 * 助手正文 Markdown：GFM（表格/任务列表/删除线）+ 代码块复制。
 * 仅渲染 React 元素、不使用 dangerouslySetInnerHTML；链接限制 http/https/mailto。
 */
import { isValidElement, memo, useDeferredValue, useRef, useState, type ReactNode } from "react";
import Markdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";

interface MarkdownTextProps {
  text: string;
  streaming?: boolean;
  className?: string;
}

function extractLanguage(children: ReactNode): string {
  const first = Array.isArray(children) ? children[0] : children;
  if (isValidElement<{ className?: string }>(first)) {
    const className = first.props.className;
    if (typeof className === "string") {
      const match = /language-([\w-]+)/.exec(className);
      if (match?.[1]) return match[1];
    }
  }
  return "";
}

const CodeBlock: NonNullable<Components["pre"]> = ({ node, children, ...props }) => {
  const preRef = useRef<HTMLPreElement>(null);
  const [copied, setCopied] = useState(false);
  const language = extractLanguage(children);
  void node;

  const handleCopy = async () => {
    const text = preRef.current?.textContent ?? "";
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1_500);
    } catch {
      // 剪贴板不可用时静默失败，不打断阅读
    }
  };

  return (
    <div className="md-code">
      <div className="md-code-head">
        <span className="md-code-lang">{language || "text"}</span>
        <button type="button" className="md-code-copy" onClick={() => void handleCopy()}>
          {copied ? "已复制" : "复制"}
        </button>
      </div>
      <pre ref={preRef} {...props}>
        {children}
      </pre>
    </div>
  );
};

const SafeLink: NonNullable<Components["a"]> = ({ node, href, children, ...props }) => {
  void node;
  if (!href || !/^(https?:|mailto:)/i.test(href)) {
    return <span>{children}</span>;
  }
  return (
    <a {...props} href={href} target="_blank" rel="noopener noreferrer">
      {children}
    </a>
  );
};

const components: Components = {
  pre: CodeBlock,
  a: SafeLink,
};

export const MarkdownText = memo(function MarkdownText({
  text,
  streaming = false,
  className = "",
}: MarkdownTextProps) {
  // 流式时正文每 50ms 变一次：defer 让 React 优先处理滚动与输入，Markdown 解析稍后跟上
  const deferred = useDeferredValue(text);
  const classes = `md-body${streaming ? " is-streaming" : ""}${className ? ` ${className}` : ""}`;

  return (
    <div className={classes}>
      <Markdown remarkPlugins={[remarkGfm]} components={components}>
        {deferred}
      </Markdown>
    </div>
  );
});

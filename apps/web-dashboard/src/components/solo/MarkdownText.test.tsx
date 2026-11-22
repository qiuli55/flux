// @vitest-environment jsdom
/**
 * MarkdownText：GFM 渲染与安全链接的接线测试。
 * 只验证组件输出的 DOM 形态，不覆盖 react-markdown 自身的解析细节。
 */
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { MarkdownText } from "./MarkdownText";

afterEach(cleanup);

describe("MarkdownText", () => {
  it("渲染 GFM 表格与任务列表", () => {
    const { container } = render(
      <MarkdownText text={"| 名称 | 值 |\n| --- | --- |\n| a | 1 |\n\n- [x] 已完成\n- [ ] 待办"} />,
    );
    expect(container.querySelector("table")).not.toBeNull();
    expect(container.querySelector("td")?.textContent).toBe("a");
    expect(container.querySelectorAll('input[type="checkbox"]')).toHaveLength(2);
  });

  it("代码块显示语言标签与复制按钮", () => {
    const { container } = render(
      <MarkdownText text={"```ts\nconst answer = 42;\n```"} />,
    );
    expect(container.querySelector(".md-code-lang")?.textContent).toBe("ts");
    expect(screen.getByRole("button", { name: "复制" })).toBeTruthy();
    expect(container.querySelector(".md-code pre code")?.textContent).toContain("const answer");
  });

  it("只放行 http/https/mailto 链接", () => {
    const { container } = render(
      <MarkdownText text={"[safe](https://example.com) [bad](javascript:alert(1))"} />,
    );
    const links = container.querySelectorAll("a");
    expect(links).toHaveLength(1);
    expect(links[0]?.getAttribute("href")).toBe("https://example.com");
    expect(links[0]?.getAttribute("rel")).toBe("noopener noreferrer");
    expect(container.textContent).toContain("bad");
  });
});

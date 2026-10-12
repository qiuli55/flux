/**
 * 页面注册表：路由 id → 页面组件。
 * Shell 依据当前路由从本表取组件渲染；认证页由 App 单独分流。
 * 变更审核、Agent、模型、终端、版本控制、测试、日志都已并入 IDE / Solo / 设置，不再单独注册。
 */
import type { ReactElement } from "react";
import { Overview } from "./Overview";
import { Solo } from "./Solo";
import { Ide } from "./Ide";
import { Tasks } from "./Tasks";
import { Skills, Connectors, Marketplace, Cost, Diagnostics, Settings } from "./Platform";
import { Account } from "./Auth";

export interface PageProps {
  navigate: (id: string) => void;
}

type PageFn = (p: PageProps) => ReactElement;

const REGISTRY: Record<string, PageFn> = {
  overview: () => <Overview />,
  solo: () => <Solo />,
  ide: () => <Ide />,
  tasks: () => <Tasks />,
  skills: () => <Skills />,
  connectors: () => <Connectors />,
  marketplace: () => <Marketplace />,
  cost: () => <Cost />,
  diagnostics: () => <Diagnostics />,
  settings: () => <Settings />,
  account: () => <Account />,
};

export function renderPage(id: string, props: PageProps): ReactElement {
  const fn = REGISTRY[id];
  return fn ? fn(props) : <Overview />;
}

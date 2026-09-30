/**
 * 文件类型图标（手写内联 SVG，不引图标库）。
 *
 * fileIconKey() 给出细粒度图标键，这里把键收敛成几组描边字形，只换配色，
 * 这样新增扩展名时不需要再画一套路径。
 */
import type { JSX } from "react";

import { fileIconKey } from "../utils/fileTree";

type IconGroup = "folder" | "code" | "markup" | "data" | "doc" | "database" | "plain";

/** 图标键 → 字形分组 */
const ICON_GROUPS: Record<string, IconGroup> = {
  dir: "folder",
  typescript: "code",
  tsx: "code",
  javascript: "code",
  python: "code",
  shell: "code",
  stylesheet: "markup",
  html: "markup",
  svg: "markup",
  json: "data",
  yaml: "data",
  toml: "data",
  lock: "data",
  config: "data",
  markdown: "doc",
  text: "doc",
  database: "database",
  file: "plain",
};

/** 分组 → 描边路径（24x24 视图框） */
const GROUP_PATHS: Record<IconGroup, string[]> = {
  folder: [
    "M3 7.5A1.5 1.5 0 0 1 4.5 6h4.2l2 2.2h8.8A1.5 1.5 0 0 1 21 9.7v8.8A1.5 1.5 0 0 1 19.5 20h-15A1.5 1.5 0 0 1 3 18.5v-11Z",
  ],
  plain: ["M14 3H7a1.5 1.5 0 0 0-1.5 1.5v15A1.5 1.5 0 0 0 7 21h10a1.5 1.5 0 0 0 1.5-1.5V7.5L14 3Z", "M14 3v4.5h4.5"],
  code: [
    "M14 3H7a1.5 1.5 0 0 0-1.5 1.5v15A1.5 1.5 0 0 0 7 21h10a1.5 1.5 0 0 0 1.5-1.5V7.5L14 3Z",
    "M14 3v4.5h4.5",
    "M9.5 13.5 8 15l1.5 1.5",
    "M14.5 13.5 16 15l-1.5 1.5",
  ],
  markup: [
    "M14 3H7a1.5 1.5 0 0 0-1.5 1.5v15A1.5 1.5 0 0 0 7 21h10a1.5 1.5 0 0 0 1.5-1.5V7.5L14 3Z",
    "M14 3v4.5h4.5",
    "M10.5 13l-2 2.5 2 2.5",
    "M13.5 13l2 2.5-2 2.5",
  ],
  data: [
    "M9.8 4.5C8.3 4.5 7.8 5.5 7.8 7v2.3c0 1-.6 1.6-1.5 2.7.9 1.1 1.5 1.7 1.5 2.7V17c0 1.5.5 2.5 2 2.5",
    "M14.2 4.5c1.5 0 2 1 2 2.5v2.3c0 1 .6 1.6 1.5 2.7-.9 1.1-1.5 1.7-1.5 2.7V17c0 1.5-.5 2.5-2 2.5",
  ],
  doc: [
    "M14 3H7a1.5 1.5 0 0 0-1.5 1.5v15A1.5 1.5 0 0 0 7 21h10a1.5 1.5 0 0 0 1.5-1.5V7.5L14 3Z",
    "M14 3v4.5h4.5",
    "M8.5 13.5h7",
    "M8.5 17h4.5",
  ],
  database: [
    "M12 3.5c3.6 0 6.5 1.1 6.5 2.5S15.6 8.5 12 8.5 5.5 7.4 5.5 6 8.4 3.5 12 3.5Z",
    "M5.5 6v12c0 1.4 2.9 2.5 6.5 2.5s6.5-1.1 6.5-2.5V6",
    "M5.5 12c0 1.4 2.9 2.5 6.5 2.5s6.5-1.1 6.5-2.5",
  ],
};

/** 分组 → 配色（沿用既有 design token） */
const GROUP_TONES: Record<IconGroup, string> = {
  folder: "text-accent",
  code: "text-info",
  markup: "text-warn",
  data: "text-muted",
  doc: "text-muted",
  database: "text-info",
  plain: "text-faint",
};

export function FileIcon({
  name,
  kind,
  className = "",
}: {
  name: string;
  kind: "dir" | "file";
  className?: string;
}): JSX.Element {
  const group = ICON_GROUPS[fileIconKey(name, kind)] ?? "plain";
  const paths = GROUP_PATHS[group];
  return (
    <svg
      viewBox="0 0 24 24"
      className={`h-3.5 w-3.5 shrink-0 ${GROUP_TONES[group]} ${className}`}
      fill="none"
      aria-hidden="true"
    >
      {paths.map((d) => (
        <path
          key={d}
          d={d}
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      ))}
    </svg>
  );
}

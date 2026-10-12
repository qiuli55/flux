/**
 * 图标库：统一的线条图标（24×24 viewBox，stroke=currentColor）。
 * 只收录本演示用到的图标，路径数据全部内联，避免引入图标依赖。
 */
import type { SVGProps } from "react";

const P: Record<string, string[]> = {
  home: ["M4 11l8-7 8 7v8a1 1 0 0 1-1 1h-4v-6h-6v6H5a1 1 0 0 1-1-1z"],
  grid: ["M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z"],
  files: ["M4 4h6l2 2h8v12a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z"],
  search: ["M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z", "M20 20l-4-4"],
  git: ["M12 3v18", "M7 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4z", "M7 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z", "M17 8l-5 5-5-1"],
  terminal: ["M5 7l5 5-5 5", "M13 17h6"],
  debug: ["M12 3a7 7 0 0 0-7 7v3a7 7 0 0 0 14 0v-3a7 7 0 0 0-7-7z", "M5 12H3M21 12h-2M12 3V1M12 23v-2"],
  puzzle: ["M10 4a2 2 0 1 1 4 0v2h3a1 1 0 0 1 1 1v3h2a2 2 0 1 1 0 4h-2v3a1 1 0 0 1-1 1h-3v-2a2 2 0 1 0-4 0v2H6a1 1 0 0 1-1-1v-3H3a2 2 0 1 1 0-4h2V7a1 1 0 0 1 1-1h4z"],
  bot: ["M5 8h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z", "M12 4v4", "M9 13h.01M15 13h.01", "M9 16h6"],
  sparkles: ["M12 4l1.6 4.4L18 10l-4.4 1.6L12 16l-1.6-4.4L6 10l4.4-1.6z", "M18 15l.8 2.2L21 18l-2.2.8L18 21l-.8-2.2L15 18l2.2-.8z"],
  layers: ["M12 3l9 5-9 5-9-5z", "M3 13l9 5 9-5"],
  diff: ["M6 4v16M18 4v16", "M9 8h6M12 5v6", "M9 17h6"],
  settings: ["M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z", "M19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.4-2.3 1a7 7 0 0 0-2-1.2L14 3h-4l-.6 2.7a7 7 0 0 0-2 1.2l-2.3-1-2 3.4 2 1.5A7 7 0 0 0 5 12c0 .4 0 .8.1 1.2l-2 1.5 2 3.4 2.3-1a7 7 0 0 0 2 1.2L10 21h4l.6-2.7a7 7 0 0 0 2-1.2l2.3 1 2-3.4-2-1.5c.1-.4.1-.8.1-1.2z"],
  plug: ["M9 3v5M15 3v5", "M7 8h10v3a5 5 0 0 1-10 0z", "M12 16v5"],
  store: ["M4 9l1-5h14l1 5a3 3 0 0 1-6 0 3 3 0 0 1-6 0 3 3 0 0 1-6 0z", "M5 9v11h14V9"],
  chart: ["M4 20V4", "M4 20h16", "M8 20v-6M13 20v-10M18 20v-4"],
  users: ["M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6z", "M3 20a6 6 0 0 1 12 0", "M16 11a3 3 0 1 0 0-6", "M21 20a6 6 0 0 0-4-5.6"],
  plus: ["M12 5v14M5 12h14"],
  chevron: ["M9 6l6 6-6 6"],
  chevdown: ["M6 9l6 6 6-6"],
  check: ["M5 13l4 4L19 7"],
  close: ["M6 6l12 12M18 6L6 18"],
  play: ["M7 5l12 7-12 7z"],
  pause: ["M8 5v14M16 5v14"],
  refresh: ["M20 12a8 8 0 1 1-2.3-5.6", "M20 4v4h-4"],
  bell: ["M6 16V11a6 6 0 1 1 12 0v5l1 2H5z", "M10 20a2 2 0 0 0 4 0"],
  command: ["M9 6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3z"],
  code: ["M9 8l-4 4 4 4M15 8l4 4-4 4"],
  database: ["M12 3c4 0 7 1.3 7 3s-3 3-7 3-7-1.3-7-3 3-3 7-3z", "M5 6v12c0 1.7 3 3 7 3s7-1.3 7-3V6", "M5 12c0 1.7 3 3 7 3s7-1.3 7-3"],
  shield: ["M12 3l8 3v6c0 5-3.4 8-8 9-4.6-1-8-4-8-9V6z", "M9 12l2 2 4-4"],
  zap: ["M13 3L5 14h6l-1 7 8-11h-6z"],
  clock: ["M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16z", "M12 8v4l3 2"],
  filter: ["M4 5h16l-6 7v6l-4-2v-4z"],
  more: ["M6 12h.01M12 12h.01M18 12h.01"],
  external: ["M14 4h6v6", "M20 4l-8 8", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"],
  edit: ["M4 20h4L19 9l-4-4L4 16z", "M14 6l4 4"],
  trash: ["M5 7h14M9 7V5h6v2M6 7l1 13h10l1-13"],
  download: ["M12 4v10", "M8 11l4 4 4-4", "M5 20h14"],
  upload: ["M12 20V10", "M8 13l4-4 4 4", "M5 4h14"],
  lock: ["M6 11h12v9H6z", "M9 11V8a3 3 0 0 1 6 0v3"],
  mail: ["M4 6h16v12H4z", "M4 7l8 6 8-6"],
  user: ["M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M5 20a7 7 0 0 1 14 0"],
  eye: ["M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6z", "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"],
  branch: ["M6 4v10a4 4 0 0 0 4 4h4", "M6 4a2 2 0 1 1 0-.01M18 16a2 2 0 1 1 0 4 2 2 0 0 1 0-4z", "M6 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"],
  cpu: ["M7 7h10v10H7z", "M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3"],
  server: ["M4 4h16v6H4zM4 14h16v6H4z", "M7 7h.01M7 17h.01"],
  activity: ["M3 12h4l2-6 4 12 2-6h6"],
  book: ["M5 5a2 2 0 0 1 2-2h11v16H7a2 2 0 0 0-2 2z", "M19 3v16"],
  rocket: ["M12 3c3 1 5 4 5 7l-3 3H10L7 10c0-3 2-6 5-7z", "M10 13l-3 5 5-3M14 13l3 5-5-3"],
  star: ["M12 4l2.5 5 5.5.8-4 3.9.9 5.5L12 16.6 7.1 19.2l.9-5.5-4-3.9 5.5-.8z"],
  heart: ["M12 20s-7-4.5-7-9.5A3.5 3.5 0 0 1 12 8a3.5 3.5 0 0 1 7 2.5C19 15.5 12 20 12 20z"],
  package: ["M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M4 7.5l8 4.5 8-4.5M12 12v9"],
  brain: ["M9 4a3 3 0 0 0-3 3 3 3 0 0 0-1 5 3 3 0 0 0 2 3 3 3 0 0 0 2 3 3 3 0 0 0 3-1V4.5A3 3 0 0 0 9 4z", "M15 4a3 3 0 0 1 3 3 3 3 0 0 1 1 5 3 3 0 0 1-2 3 3 3 0 0 1-2 3 3 3 0 0 1-3-1V4.5A3 3 0 0 1 15 4z"],
  globe: ["M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16z", "M4 12h16", "M12 4c2 3 2 13 0 16M12 4c-2 3-2 13 0 16"],
  key: ["M4 14a4 4 0 1 0 5.7 0l6-6H19l2-2-2-2-2 2v2l-6 6A4 4 0 0 0 4 14z", "M8 14h.01"],
  credit: ["M3 6h18v12H3z", "M3 10h18", "M6 15h4"],
  folder: ["M4 6h5l2 2h9v10H4z"],
  file: ["M6 3h7l5 5v13H6z", "M13 3v5h5"],
  filter2: ["M4 6h16M7 12h10M10 18h4"],
  arrowright: ["M5 12h14", "M13 6l6 6-6 6"],
  logo: [],
};

export interface IconProps extends SVGProps<SVGSVGElement> {
  name: keyof typeof P | string;
}

export function Icon({ name, className, ...rest }: IconProps) {
  const paths = P[name] ?? P.grid;
  return (
    <svg
      className={className ? `icon ${className}` : "icon"}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...rest}
    >
      {paths.map((d, i) => (
        <path key={i} d={d} />
      ))}
    </svg>
  );
}

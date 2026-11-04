/**
 * Bot 头像：形状 × 颜色确定性生成，不支持上传图片。
 *
 * 契约来源是后端 `flux/core/group/avatar.py`：
 * AVATAR_SHAPES / AVATAR_COLORS 的顺序与取值必须与之保持一致，
 * 换形状或颜色只需两边同时改，这里不负责校验（非法值静默回退默认）。
 */
export const AVATAR_SHAPES = [
  "circle",
  "squircle",
  "hexagon",
  "triangle",
  "diamond",
  "star",
  "flower",
  "burst",
  "shield",
  "drop",
  "cloud",
  "gear",
  "egg",
  "blob",
] as const;

export const AVATAR_COLORS = [
  "violet",
  "indigo",
  "sky",
  "teal",
  "emerald",
  "lime",
  "amber",
  "orange",
  "rose",
  "fuchsia",
  "slate",
  "cyan",
] as const;

export type AvatarShape = (typeof AVATAR_SHAPES)[number];
export type AvatarColor = (typeof AVATAR_COLORS)[number];

export const DEFAULT_AVATAR_SHAPE: AvatarShape = "circle";
export const DEFAULT_AVATAR_COLOR: AvatarColor = "violet";

/** 深色底上都能看清的实心色。 */
const COLOR_HEX: Record<AvatarColor, string> = {
  violet: "#8b6cf6",
  indigo: "#6d7ef8",
  sky: "#4aa8f0",
  teal: "#21b3a6",
  emerald: "#2ecc8f",
  lime: "#9ad84a",
  amber: "#e8b25e",
  orange: "#f08a4b",
  rose: "#f0708f",
  fuchsia: "#d464e8",
  slate: "#8794a3",
  cyan: "#43d6d6",
};

/** 每种形状的眼睛基线（避免在多边形尖角处显得贴边）。 */
const FACE_Y: Partial<Record<AvatarShape, number>> = {
  triangle: 38,
  star: 34,
  shield: 34,
  drop: 30,
  cloud: 35,
  gear: 33,
};

function normalizeShape(value: string | undefined): AvatarShape {
  return (AVATAR_SHAPES as readonly string[]).includes(value ?? "")
    ? (value as AvatarShape)
    : DEFAULT_AVATAR_SHAPE;
}

function normalizeColor(value: string | undefined): AvatarColor {
  return (AVATAR_COLORS as readonly string[]).includes(value ?? "")
    ? (value as AvatarColor)
    : DEFAULT_AVATAR_COLOR;
}

/** 颜色的十六进制值（选择器里的实心色块用）。 */
export function avatarColorHex(color: string | undefined): string {
  return COLOR_HEX[normalizeColor(color)];
}

/** 极坐标多边形顶点串（用于星星 / 爆炸形）。 */
function radialPoints(
  cx: number,
  cy: number,
  outer: number,
  inner: number,
  spikes: number,
  rotation = -Math.PI / 2,
): string {
  const points: string[] = [];
  for (let index = 0; index < spikes * 2; index += 1) {
    const radius = index % 2 === 0 ? outer : inner;
    const angle = rotation + (index * Math.PI) / spikes;
    points.push(
      `${(cx + Math.cos(angle) * radius).toFixed(2)},${(cy + Math.sin(angle) * radius).toFixed(2)}`,
    );
  }
  return points.join(" ");
}

/** 形状本体：stroke 与 fill 同色，多个基本图形并集后看不到接缝。 */
function ShapeBody({ shape, fill }: { shape: AvatarShape; fill: string }) {
  const common = { fill, stroke: fill, strokeWidth: 4, strokeLinejoin: "round" as const };

  switch (shape) {
    case "squircle":
      return <rect x="6" y="6" width="52" height="52" rx="16" {...common} />;
    case "hexagon":
      return <path d="M32 5 53.7 17.5v25L32 55 10.3 42.5v-25z" {...common} />;
    case "triangle":
      return <path d="M32 8 57 51H7Z" {...common} />;
    case "diamond":
      return <path d="M32 6 58 32 32 58 6 32Z" {...common} />;
    case "star":
      return <polygon points={radialPoints(32, 32, 26, 11, 5)} {...common} />;
    case "burst":
      return <polygon points={radialPoints(32, 32, 29, 20, 12)} {...common} />;
    case "flower":
      return (
        <g {...common}>
          {[0, 60, 120, 180, 240, 300].map((degree) => {
            const angle = (degree * Math.PI) / 180;
            return (
              <circle
                key={degree}
                cx={32 + Math.cos(angle) * 11}
                cy={32 + Math.sin(angle) * 11}
                r="13"
              />
            );
          })}
          <circle cx="32" cy="32" r="15" />
        </g>
      );
    case "shield":
      return <path d="M32 5 55 13V29C55 43 45 52 32 58 19 52 9 43 9 29V13Z" {...common} />;
    case "drop":
      return (
        <path d="M32 5C45 19 52 28 52 37.5A20 20 0 0 1 12 37.5C12 28 19 19 32 5Z" {...common} />
      );
    case "cloud":
      return (
        <g {...common}>
          <circle cx="23" cy="36" r="11" />
          <circle cx="32" cy="29" r="13" />
          <circle cx="42" cy="36" r="10" />
          <rect x="18" y="36" width="28" height="12" rx="6" />
        </g>
      );
    case "gear":
      return (
        <g {...common}>
          {[0, 45, 90, 135, 180, 225, 270, 315].map((degree) => (
            <rect
              key={degree}
              x="28.5"
              y="2"
              width="7"
              height="13"
              rx="2.5"
              transform={`rotate(${degree} 32 32)`}
            />
          ))}
          <circle cx="32" cy="32" r="19" />
        </g>
      );
    case "egg":
      return (
        <path
          d="M32 6C42 6 50 20 50 32C50 46 42 58 32 58C22 58 14 46 14 32C14 20 22 6 32 6Z"
          {...common}
        />
      );
    case "blob":
      return (
        <path
          d="M34 5C48 4 60 15 58 29C56 43 46 60 31 59C16 58 4 46 6 31C8 16 20 6 34 5Z"
          {...common}
        />
      );
    case "circle":
    default:
      return <circle cx="32" cy="32" r="26" {...common} />;
  }
}

interface BotAvatarProps {
  shape?: string;
  color?: string;
  /** 渲染边长（px），默认 32 */
  size?: number;
  /** bot 名称：用于 title / aria-label */
  name?: string;
  className?: string;
}

/** 统一的 bot 头像。未知形状/颜色回退到 circle / violet。 */
export function BotAvatar({ shape, color, size = 32, name, className }: BotAvatarProps) {
  const resolvedShape = normalizeShape(shape);
  const resolvedColor = normalizeColor(color);
  const fill = COLOR_HEX[resolvedColor];
  const faceY = FACE_Y[resolvedShape] ?? 32;
  const label = name?.trim() || "Bot 头像";

  return (
    <svg
      viewBox="0 0 64 64"
      width={size}
      height={size}
      className={className}
      role="img"
      aria-label={label}
      style={{ flex: "none" }}
    >
      <title>{label}</title>
      <ShapeBody shape={resolvedShape} fill={fill} />
      <circle cx="24.5" cy={faceY} r="3.4" fill="#0a0c0e" opacity="0.82" />
      <circle cx="39.5" cy={faceY} r="3.4" fill="#0a0c0e" opacity="0.82" />
      <circle cx="25.6" cy={faceY - 1.2} r="1.1" fill="#ffffff" opacity="0.5" />
      <circle cx="40.6" cy={faceY - 1.2} r="1.1" fill="#ffffff" opacity="0.5" />
    </svg>
  );
}

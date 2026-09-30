/** 展示层格式化工具。 */

function pad(value: number): string {
  return value.toString().padStart(2, "0");
}

/** HH:MM:SS，用于任务时间线 */
export function formatTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "--:--:--";
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** HH:MM，用于最近活动 */
export function formatClock(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "--:--";
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** UUID 等长标识的缩短展示 */
export function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id;
}

/** 带符号的行数，如 +12 / -3 */
export function signed(value: number, sign: "+" | "-"): string {
  return `${sign}${value}`;
}
/**
 * 「粘贴大文本自动作为文件」的纯规则（桌面 / 手机 / Web 共用同一套阈值）。
 *
 * 一次粘贴达到 10000 个字符、且包含换行（避免一条超长链接被误判）时，
 * 模式为「始终作为文件」就直接走附件通道；不到阈值或选择「始终直接粘贴」时照常插入输入框。
 * 图片粘贴不走文本规则。
 */

export type PasteMode = "file" | "text";

export const LARGE_PASTE_THRESHOLD = 10_000;
/** 后端单条消息正文上限：改回输入框前要守住。 */
export const COMPOSER_TEXT_MAX = 8_000;

const SAFE_FILE_NAME = /[\\/:*?"<>|\u0000-\u001f\u007f]/g;

export function isLargePaste(text: string, mode: PasteMode): boolean {
  return mode === "file" && text.length >= LARGE_PASTE_THRESHOLD && /[\r\n]/.test(text);
}

export function isImagePaste(items: ArrayLike<DataTransferItem> | null | undefined): boolean {
  if (!items) return false;
  return Array.from(items).some((item) => item.type.startsWith("image/"));
}

/** 用粘贴内容首行做安全文件名；空白时退回「粘贴.txt」。 */
export function pastedFileName(text: string): string {
  const firstLine = text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find((line) => line.length > 0) ?? "";
  const cleaned = firstLine.replace(SAFE_FILE_NAME, " ").replace(/\s+/g, " ").trim().slice(0, 24);
  return cleaned ? `${cleaned}.txt` : "粘贴.txt";
}

/** 改回输入框：超过后端正文上限时明确拒绝，不静默截断。 */
export function revealPastedText(
  currentText: string,
  pastedText: string,
): { text?: string; error?: string } {
  const merged = currentText.trim().length === 0
    ? pastedText
    : currentText.trimEnd().endsWith(pastedText.trim())
      ? currentText
      : `${currentText.trimEnd()}\n\n${pastedText}`;
  if (merged.length > COMPOSER_TEXT_MAX) {
    return { error: `改回输入框会超过 ${COMPOSER_TEXT_MAX} 字上限，请先精简当前输入` };
  }
  return { text: merged };
}

export const PASTE_MODE_OPTIONS: { value: PasteMode; label: string }[] = [
  { value: "file", label: "始终作为文件" },
  { value: "text", label: "始终直接粘贴" },
];

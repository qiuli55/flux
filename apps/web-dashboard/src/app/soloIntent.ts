/**
 * Solo 意图队列：让全局外壳（顶栏按钮/命令面板）触发 Solo 页内部才有的工具面板。
 *
 * 与 app/commands.tsx 的 IDE 意图同构：Solo 页挂载时注册处理器，卸载时注销；
 * 未挂载时先排队，待其挂载后取走执行。这样全局导航收敛成一套后，
 * 「项目列表」「Agent 面板」两个 Solo 独有入口仍可从外壳触发。
 */
export type SoloIntent = "projects" | "agents";

let handler: ((intent: SoloIntent) => void) | null = null;
let pending: SoloIntent[] = [];

/** Solo 页注册意图处理器；注册时若有排队意图会立即执行。卸载时传 null 注销。 */
export function setSoloIntentHandler(next: ((intent: SoloIntent) => void) | null): void {
  handler = next;
  if (!next || pending.length === 0) return;
  const queue = pending;
  pending = [];
  queue.forEach((intent) => next(intent));
}

/** 派发 Solo 意图：面板未挂载时排队等待。 */
export function queueSoloIntent(intent: SoloIntent): void {
  if (handler) { handler(intent); return; }
  pending.push(intent);
}

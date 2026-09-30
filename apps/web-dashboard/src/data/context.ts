/**
 * 上下文文件与打开标签的统一上限。
 *
 * 5 个上下文文件是后端 DeveloperProposalFlow 的硬约束，两处 UI（项目面板、文件浏览器）
 * 必须取同一个数字，所以集中在这里而不是各自写死；打开标签上限只约束前端界面。
 */
export const MAX_CONTEXT_FILES = 5;
export const MAX_OPEN_FILES = 6;

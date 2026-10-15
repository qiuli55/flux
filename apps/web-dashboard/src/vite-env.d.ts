/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 覆盖 API 基址，默认同源 /api/v1 */
  readonly VITE_API_BASE?: string;
  /** 账号云（B 机）地址，未登录时跳它的登录页，默认 https://flux.qiuli55.top */
  readonly VITE_FLUX_CLOUD_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare global {
  interface Window {
    fluxDesktop?: {
      isDesktop: boolean;
      platform: string;
      chooseWorkspaceRoot?: () => Promise<string | null>;
      onWorkspaceChanged?: (callback: (root: string) => void) => () => void;
    };
  }
}

export {};

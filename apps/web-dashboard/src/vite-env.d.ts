/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 覆盖 API 基址，默认同源 /api/v1 */
  readonly VITE_API_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
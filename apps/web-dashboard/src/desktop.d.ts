interface FluxDesktopBridge {
  readonly isDesktop: boolean;
  readonly platform: string;
  chooseWorkspaceRoot: () => Promise<string | null>;
  onWorkspaceChanged: (callback: (root: string) => void) => () => void;
}

declare global {
  interface Window {
    fluxDesktop?: FluxDesktopBridge;
  }
}

export {};
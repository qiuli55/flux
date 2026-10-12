/**
 * 本地界面偏好：侧栏宽度、状态栏显隐、减少动效。
 * 用 useSyncExternalStore 做极简外部 store，保证设置页改动即时反映到外壳（同一进程内同步）。
 * 持久化在 localStorage（键 flux.fx.prefs）；减少动效同时开关 html 上的 fx-reduce-motion 类。
 */
import { useSyncExternalStore } from "react";

export interface Prefs { wide: boolean; statusbar: boolean; reduceMotion: boolean }

const KEY = "flux.fx.prefs";
const DEFAULTS: Prefs = { wide: false, statusbar: true, reduceMotion: false };

function read(): Prefs {
  try { return { ...DEFAULTS, ...(JSON.parse(localStorage.getItem(KEY) ?? "{}") as Partial<Prefs>) }; }
  catch { return { ...DEFAULTS }; }
}

let state: Prefs = read();
const subs = new Set<() => void>();

function apply() {
  localStorage.setItem(KEY, JSON.stringify(state));
  document.documentElement.classList.toggle("fx-reduce-motion", state.reduceMotion);
}

function emit() {
  apply();
  subs.forEach((fn) => fn());
}

/** 写入单项偏好并广播。 */
export function setPref<K extends keyof Prefs>(key: K, value: Prefs[K]): void {
  state = { ...state, [key]: value };
  emit();
}

function subscribe(fn: () => void): () => void {
  subs.add(fn);
  return () => { subs.delete(fn); };
}

/** 订阅全部偏好。 */
export function usePrefs(): Prefs {
  return useSyncExternalStore(subscribe, () => state);
}

/** 在模块加载时应用一次（减少动效类），避免首帧闪烁。 */
apply();

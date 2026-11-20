import { useLayoutEffect } from "react";
import { useLeaveGuard } from "../routing/router";

const MESSAGE = "上报内容还没有提交，离开后会丢失。确定离开吗？";

/**
 * 未提交内容的保护：刷新 / 关闭标签页由浏览器原生提示，点应用内链接离开先确认。
 * 用 useLayoutEffect 注册 beforeunload：守卫要和成功页的 DOM 在同一次提交里同步解除；被动 effect 的清理会晚于 DOM，
 * 留下“页面已显示提交成功，关闭标签页却仍被拦截”的窗口。
 */
export function useUnsavedGuard(active: boolean, message = MESSAGE, routeActive = true): void {
  useLeaveGuard(active && routeActive, message);
  useLayoutEffect(() => {
    if (!active) return;
    function onBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault();
    }
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [active]);
}

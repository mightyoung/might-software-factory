import "@testing-library/jest-dom/vitest";
import { afterEach } from "vitest";
import { clearAllDrafts } from "../features/session/draftStash";
import { navigate, setTabOpener } from "../routing/router";

// jsdom 没有多标签页：测试里“新标签页”等价于在原地打开同一个地址。
const openInPlace = (url: string) => navigate(url);
const noClose = () => {};
setTabOpener(openInPlace, noClose);

// 草稿箱是模块级内存状态：每个用例之后清空，避免用例之间互相恢复草稿。
afterEach(() => { clearAllDrafts(); setTabOpener(openInPlace, noClose); });

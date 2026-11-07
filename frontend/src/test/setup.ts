import "@testing-library/jest-dom/vitest";
import { afterEach } from "vitest";
import { clearAllDrafts } from "../features/session/draftStash";

// 草稿箱是模块级内存状态：每个用例之后清空，避免用例之间互相恢复草稿。
afterEach(() => clearAllDrafts());

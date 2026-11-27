import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { OfficeDialog } from "./OfficeDialog";

const prototype = HTMLDialogElement.prototype;
const originals = ["showModal", "close"].map(name => [name, Object.getOwnPropertyDescriptor(prototype, name)] as const);
beforeEach(() => {
  Object.defineProperties(prototype, {
    showModal: { configurable: true, value(this: HTMLDialogElement) { this.setAttribute("open", ""); } },
    close: { configurable: true, value(this: HTMLDialogElement) { this.removeAttribute("open"); } },
  });
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  for (const [name, descriptor] of originals) {
    if (descriptor) Object.defineProperty(prototype, name, descriptor);
    else Reflect.deleteProperty(prototype, name);
  }
});

function open(onClose = vi.fn()) {
  render(<OfficeDialog kind="sheet" theme="light" onClose={onClose} />);
  const frame = screen.getByTitle("在线工作簿编辑器") as HTMLIFrameElement;
  const send = (type: string, data?: object, origin = location.origin) => {
    fireEvent(window, new MessageEvent("message", {
      origin, source: frame.contentWindow, data: { channel: "might-office-v1", type, ...data },
    }));
  };
  send("ready");
  const post = vi.spyOn(frame.contentWindow!, "postMessage").mockImplementation(() => {});
  const close = screen.getByRole("button", { name: "关闭表格" });
  return { frame, send, post, close, onClose };
}

it("checks the iframe, keeps the same unsaved editor on Escape, and discards only after confirmation", () => {
  const nativeConfirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  const { frame, send, post, close, onClose } = open();
  close.focus();
  fireEvent.click(close);
  expect(post).toHaveBeenCalledExactlyOnceWith({ channel: "might-office-v1", type: "close-check" }, location.origin);
  send("close-state", { dirty: false }, "https://unrelated.invalid");
  expect(onClose).not.toHaveBeenCalled();
  send("close-state", { dirty: true });
  const prompt = screen.getByRole("alertdialog", { name: "丢弃未导出的修改？" });
  expect(within(prompt).getByRole("button", { name: "继续编辑" })).toHaveFocus();
  fireEvent(prompt, new Event("cancel", { bubbles: true, cancelable: true }));
  expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  expect(screen.getByTitle("在线工作簿编辑器")).toBe(frame);
  expect(close).toHaveFocus();
  expect(post).toHaveBeenCalledTimes(1);
  expect(onClose).not.toHaveBeenCalled();
  fireEvent.click(close);
  send("close-state", { dirty: true });
  fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "丢弃并关闭" }));
  expect(onClose).toHaveBeenCalledTimes(1);
  expect(nativeConfirm).not.toHaveBeenCalled();
});

it("a clean iframe closes after the close check without a confirmation", () => {
  const { send, close, onClose } = open();
  fireEvent.click(close);
  send("close-state", { dirty: false });
  expect(onClose).toHaveBeenCalledTimes(1);
  expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
});

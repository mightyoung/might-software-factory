import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ChangePasswordDialog } from "./ChangePasswordDialog";
import { SessionError } from "../domain/session";

const NEW = "correct horse battery";

function setup(onSubmit = vi.fn(async () => {})) {
  const onClose = vi.fn();
  render(<ChangePasswordDialog onSubmit={onSubmit} onClose={onClose} />);
  return { onSubmit, onClose };
}
function fill(current = "oldpass", next = NEW, confirm = NEW) {
  fireEvent.change(screen.getByLabelText("当前密码"), { target: { value: current } });
  fireEvent.change(screen.getByLabelText("新密码"), { target: { value: next } });
  fireEvent.change(screen.getByLabelText("再次输入新密码"), { target: { value: confirm } });
  fireEvent.click(screen.getByRole("button", { name: "修改密码" }));
}
afterEach(cleanup);

describe("修改密码对话框", () => {
  it("本地校验不通过时不发请求：过短、确认不一致、超 72 字节", () => {
    const { onSubmit } = setup();
    fill("old", "short", "short");
    expect(screen.getByText("新密码至少 15 个字符")).toBeInTheDocument();
    fill("old", NEW, NEW + "x");
    expect(screen.getByText("两次输入的新密码不一致")).toBeInTheDocument();
    fill("old", "密".repeat(25), "密".repeat(25));
    expect(screen.getByText(/不能超过 72 字节/)).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("原样提交：不去除空白", async () => {
    const { onSubmit } = setup();
    fill(" old ", `  ${NEW}  `, `  ${NEW}  `);
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(" old ", `  ${NEW}  `));
  });

  it("当前密码错误：标在该字段并清空它", async () => {
    setup(vi.fn(async () => { throw new SessionError("invalid_credentials"); }));
    fill();
    expect(await screen.findByText("当前密码不正确")).toBeInTheDocument();
    expect(screen.getByLabelText("当前密码")).toHaveValue("");
    expect(screen.getByLabelText("新密码")).toHaveValue(NEW);
  });

  it("被限流：显示倒计时并暂时禁用提交", async () => {
    setup(vi.fn(async () => { throw new SessionError("rate_limited", false, 30); }));
    fill();
    expect(await screen.findByRole("alert")).toHaveTextContent("尝试过于频繁");
    expect(screen.getByRole("button", { name: /等待 \d+ 秒/ })).toBeDisabled();
  });

  it("服务不可用：提醒结果不确定，密码可能已生效", async () => {
    setup(vi.fn(async () => { throw new SessionError("unavailable"); }));
    fill();
    expect(await screen.findByRole("alert")).toHaveTextContent("新密码已生效");
  });

  it("Esc 关闭；提交中不能关闭", async () => {
    const { onClose } = setup();
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    expect(onClose).toHaveBeenCalled();
  });
});

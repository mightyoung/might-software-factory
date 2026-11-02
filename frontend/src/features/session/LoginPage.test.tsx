import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionError } from "../../domain/session";
import { LoginPage } from "./LoginPage";

const { login } = vi.hoisted(() => ({ login: vi.fn() }));
vi.mock("./SessionProvider", () => ({ useSession: () => ({ login, requiresTenantCode: true }) }));

beforeEach(() => { vi.useFakeTimers(); login.mockReset(); });
afterEach(() => { cleanup(); vi.clearAllTimers(); vi.restoreAllMocks(); vi.useRealTimers(); });

async function submit() {
  fireEvent.change(screen.getByLabelText("租户编码"), { target: { value: "synthetic-tenant" } });
  fireEvent.change(screen.getByLabelText("账号"), { target: { value: "synthetic-user" } });
  fireEvent.change(screen.getByLabelText("密码"), { target: { value: "synthetic-password" } });
  await act(async () => { fireEvent.submit(screen.getByRole("button").closest("form")!); });
}

describe("LoginPage rate limit feedback", () => {
  it("shows bounded waiting seconds, clears the password, blocks submits, and permits manual retry at expiry", async () => {
    login.mockRejectedValue(new SessionError("rate_limited", false, 3));
    render(<LoginPage notice="none" />);
    await submit();
    expect(screen.getByRole("alert")).toHaveTextContent("登录请求过于频繁，请稍后重试。");
    expect(screen.getByRole("button", { name: "等待 3 秒" })).toBeDisabled();
    expect(screen.getByLabelText("密码")).toHaveValue("");
    await act(async () => { fireEvent.submit(screen.getByRole("button").closest("form")!); });
    expect(login).toHaveBeenCalledTimes(1);
    act(() => { vi.advanceTimersByTime(2_000); });
    expect(screen.getByRole("button", { name: "等待 1 秒" })).toBeDisabled();
    act(() => { vi.advanceTimersByTime(1_000); });
    expect(screen.getByRole("button", { name: "登录" })).toBeEnabled();
    expect(login).toHaveBeenCalledTimes(1); // no retry without another manual submit
    await submit();
    expect(login).toHaveBeenCalledTimes(2);
  });

  it("uses a finite local pause without a Retry-After header and never retries automatically", async () => {
    login.mockRejectedValue(new SessionError("rate_limited"));
    render(<LoginPage notice="none" />);
    await submit();
    expect(screen.getByRole("button", { name: "等待 5 秒" })).toBeDisabled();
    expect(screen.getByRole("alert")).not.toHaveTextContent("账号或密码不正确");
    act(() => { vi.advanceTimersByTime(5_000); });
    expect(screen.getByRole("button", { name: "登录" })).toBeEnabled();
    expect(login).toHaveBeenCalledTimes(1);
  });

  it.each([
    ["invalid_credentials", "账号或密码不正确。"],
    ["forbidden", "当前账号暂时无法登录。"],
    ["unavailable", "服务暂时不可用，请稍后重试。"],
  ] as const)("keeps %s distinct from a rate limit", async (kind, message) => {
    login.mockRejectedValue(new SessionError(kind));
    render(<LoginPage notice="none" />);
    await submit();
    expect(screen.getByRole("alert")).toHaveTextContent(message);
    expect(screen.getByRole("button", { name: "登录" })).toBeEnabled();
  });

  it("cleans up its countdown when unmounted", async () => {
    login.mockRejectedValue(new SessionError("rate_limited", false, 300));
    const interval = vi.spyOn(window, "setInterval");
    const clearInterval = vi.spyOn(window, "clearInterval");
    const view = render(<LoginPage notice="none" />);
    await submit();
    const timer = interval.mock.results.at(-1)!.value;
    view.unmount();
    expect(clearInterval).toHaveBeenCalledWith(timer);
    expect(login).toHaveBeenCalledTimes(1);
  });
});

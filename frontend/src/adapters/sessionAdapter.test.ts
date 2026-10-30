import { describe, expect, it } from "vitest";
import { SessionError } from "../domain/session";
import { createSyntheticSessionAdapter } from "./sessionAdapter";

async function failureOf(promise: Promise<unknown>): Promise<SessionError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof SessionError) return error;
    throw error;
  }
  throw new Error("expected SessionError");
}

describe("合成会话适配器", () => {
  it("演示账号登录后 bootstrap 只含身份、租户、有权应用和权限摘要", async () => {
    const adapter = createSyntheticSessionAdapter();
    const session = await adapter.login({ username: " Demo.Safety ", password: "x" });
    expect(session.actor.displayName).toBe("演示安全员");
    expect(session.applications.map((app) => app.id)).toEqual(["safety"]);
    expect(session.permissions).toContain("safety.finding.read");
    expect(session.permissions).not.toContain("safety.risk.read");
    expect(Object.keys(session).sort()).toEqual(["actor", "applications", "permissions", "tenant"]);
  });

  it("账号不存在与密码为空给出同一种失败，不暴露账号是否存在", async () => {
    const adapter = createSyntheticSessionAdapter();
    const unknown = await failureOf(adapter.login({ username: "nobody", password: "x" }));
    const emptyPassword = await failureOf(adapter.login({ username: "demo.admin", password: "" }));
    expect(unknown.kind).toBe("invalid_credentials");
    expect(emptyPassword.kind).toBe("invalid_credentials");
    expect(unknown.message).toBe(emptyPassword.message);
    expect(await adapter.restore()).toBeNull();
  });

  it("登录后可恢复会话，退出后清除", async () => {
    const adapter = createSyntheticSessionAdapter();
    expect(await adapter.restore()).toBeNull();
    await adapter.login({ username: "demo.manager", password: "x" });
    expect((await adapter.restore())?.actor.id).toBe("synthetic:demo.manager");
    await adapter.logout();
    expect(await adapter.restore()).toBeNull();
  });

  it("菜单按应用按需读取：未登录为 unauthenticated，未开通的应用为 forbidden", async () => {
    const adapter = createSyntheticSessionAdapter();
    expect((await failureOf(adapter.loadMenu("safety"))).kind).toBe("unauthenticated");
    await adapter.login({ username: "demo.safety", password: "x" });
    expect((await adapter.loadMenu("safety")).applicationId).toBe("safety");
    expect((await failureOf(adapter.loadMenu("equipment"))).kind).toBe("forbidden");
  });

  it("已取消的请求抛出 AbortError 且不改变会话", async () => {
    const adapter = createSyntheticSessionAdapter();
    const controller = new AbortController();
    controller.abort();
    await expect(adapter.login({ username: "demo.admin", password: "x" }, controller.signal)).rejects.toMatchObject({
      name: "AbortError",
    });
    expect(await adapter.restore()).toBeNull();
  });

  it("不读写浏览器存储", async () => {
    const adapter = createSyntheticSessionAdapter();
    await adapter.login({ username: "demo.admin", password: "secret-value" });
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });
});

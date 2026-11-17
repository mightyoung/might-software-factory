import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { SessionAdapter } from "../../adapters/sessionAdapter";
import { SessionError, type SessionBootstrap } from "../../domain/session";
import { SessionProvider, useSession, type SessionContextValue } from "./SessionProvider";
import { createEmptyDraft } from "../../domain/findingDraft";
import { clearAllDrafts, peekDraft, stashDraft } from "./draftStash";
import { useAppMenu } from "./useAppMenu";
const account = (id = "a", tenant = "t"): SessionBootstrap => ({ actor: { id, displayName: id }, identityScope: { accountId: id, memberId: id }, tenant: { id: tenant, name: tenant }, applications: [{ id: "platform", name: "系统设置", entryPath: "platform/permissions" }], permissions: ["platform.permission.manage"] });
let current!: SessionContextValue;
function Probe() { current = useSession();const menu = useAppMenu(current.state.status === "signedIn" ? "platform" : null);return <><output data-testid="session">{JSON.stringify(current.state)}</output><output data-testid="menu">{menu.state.status}</output></>; }
function setup() { const adapter: SessionAdapter = { restore: vi.fn(async () => account()), login: vi.fn(async () => account("b")), logout: vi.fn(async () => {}), loadMenu: vi.fn(async () => ({ applicationId: "platform" as const, title: "设置", nodes: [] })) };render(<SessionProvider adapter={adapter}><Probe /></SessionProvider>);return adapter; }
afterEach(() => { cleanup();clearAllDrafts(); });
it("refreshes bootstrap and invalidates the current application's menu", async () => {
  const adapter = setup();await screen.findByText("ready");vi.mocked(adapter.restore).mockResolvedValue({ ...account(), permissions: ["platform.permission.read"] });
  await act(async () => { await current.refresh(); });
  expect(current.state).toMatchObject({ status: "signedIn", session: { permissions: ["platform.permission.read"] } });
  await waitFor(() => expect(adapter.loadMenu).toHaveBeenCalledTimes(2));
});
it.each(["null", "unavailable", "forbidden", "different tenant"])("fails closed when bootstrap refresh returns %s", async result => {
  const adapter = setup();await screen.findByText("ready");
  if(result === "null")vi.mocked(adapter.restore).mockResolvedValue(null);
  else if(result === "different tenant")vi.mocked(adapter.restore).mockResolvedValue(account("a", "other"));
  else vi.mocked(adapter.restore).mockRejectedValue(new SessionError(result as "unavailable" | "forbidden"));
  await act(async () => { await expect(current.refresh()).rejects.toBeDefined(); });
  expect(current.state.status).toBe("signedOut");
});
it.each(["actor", "tenant", "member"])("discards a late refresh after logout and changing %s", async changed => {
  const adapter = setup();await screen.findByText("ready");let complete!: (session: SessionBootstrap) => void;
  const next = changed === "actor" ? account("b") : changed === "tenant" ? account("a", "other") : { ...account(), identityScope: { accountId: "a", memberId: "other-member" } };
  vi.mocked(adapter.login).mockResolvedValue(next);
  vi.mocked(adapter.restore).mockImplementation(() => new Promise(resolve => { complete = resolve; }));
  let pending!: Promise<unknown>;
  act(() => { pending = current.refresh().catch(error => error); });
  await act(async () => { await current.logout();await current.login("b", "secret"); });
  await act(async () => { complete(account());await pending; });
  expect(current.state).toEqual({ status: "signedIn", session: next });
});
it("ignores an old identity's late menu 401 after another identity signs in", async () => {
  const adapter = setup();await screen.findByText("ready");let fail!: (error: unknown) => void;
  vi.mocked(adapter.loadMenu).mockImplementationOnce(() => new Promise((_resolve, reject) => { fail = reject; }));
  const pending = current.loadMenu("platform").catch(error => error);
  await act(async () => { await current.logout();await current.login("b", "secret"); });
  await act(async () => { fail(new SessionError("unauthenticated"));await pending; });
  expect(current.state).toMatchObject({ status: "signedIn", session: { actor: { id: "b" } } });
});

it("rejects an old identity's refresh callback even when called after the new login", async () => {
  const adapter = setup();await screen.findByText("ready");const oldRefresh = current.refresh;
  await act(async () => { await current.logout();await current.login("b", "secret"); });
  await expect(oldRefresh()).rejects.toMatchObject({ name: "AbortError" });expect(adapter.restore).toHaveBeenCalledTimes(1);
  expect(current.state).toMatchObject({ status: "signedIn", session: { actor: { id: "b" } } });
});

it.each(["unauthenticated", "unavailable"] as const)("preserves the owner's unrelated draft on refresh %s", async kind => {
  const adapter = setup();await screen.findByText("ready");const draft = { draft: createEmptyDraft(new Date()), organizationId: "org" };stashDraft("t/a", draft);
  vi.mocked(adapter.restore).mockRejectedValue(new SessionError(kind));
  await act(async () => { await expect(current.refresh()).rejects.toBeDefined(); });expect(peekDraft("t/a")).toBe(draft);
});

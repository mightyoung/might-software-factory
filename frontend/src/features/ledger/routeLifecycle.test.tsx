import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { navigate } from "../../routing/router";
import { App } from "../../App";
import type { SessionAdapter } from "../../adapters/sessionAdapter";
import { clearAllDrafts } from "../session/draftStash";
import { adapter, data, ID, OTHER_ID, RECORD } from "./testFixture";
import type { DefinitionData, LedgerRecord } from "./model";

const input = vi.hoisted(() => ({ editMounts: 0 }));
// Model a user filling the first editable input as soon as it is committed,
// before the page's passive effects can clear stale route data.
vi.mock("./EntityLedgerForm", async importOriginal => {
  const actual = await importOriginal<typeof import("./EntityLedgerForm")>();
  const { useLayoutEffect } = await import("react");
  return { EntityLedgerForm(props: Parameters<typeof actual.EntityLedgerForm>[0]) {
    useLayoutEffect(() => {
      if (props.use === "EDIT" && input.editMounts++ === 0) {
        fireEvent.change(document.getElementById("entity-field-code")!, { target: { value: "immediate edit" } });
      }
    }, []);
    return <actual.EntityLedgerForm {...props} />;
  } };
});
afterEach(() => { cleanup();clearAllDrafts();window.history.replaceState(null, "", "/");input.editMounts = 0; });
it("does not expose a stale edit form before its route definition arrives, retaining the first immediate input", async () => {
  const session = { actor: { id: "a", displayName: "业务人员" }, tenant: { id: "t", name: "厂区" }, applications: [{ id: "inventory", name: "库存", entryPath: "apps/inventory/entities/widget" }], permissions: [], canonicalPermissions: ["widget.read", "widget.create", "widget.update"] };
  const sessions: SessionAdapter = { restore: vi.fn(async () => session), login: vi.fn(async () => session), logout: vi.fn(async () => {}), loadMenu: vi.fn(async () => ({ applicationId: "inventory", title: "库存", nodes: [] })) };
  const a = adapter();let resolve!: (value: DefinitionData) => void;
  vi.mocked(a.definition).mockImplementation(async use => use === "EDIT" ? await new Promise<DefinitionData>(done => { resolve = done; }) : data(use));
  window.history.replaceState(null, "", `/t/t/apps/inventory/entities/widget/records/${ID}`);
  render(<App mode="http" sessionAdapter={sessions} createEntityLedger={() => a} />);
  await screen.findByText("W-01");fireEvent.click(screen.getByRole("button", { name: "修改物品" }));
  expect(a.definition).toHaveBeenLastCalledWith("EDIT", expect.any(AbortSignal));
  expect(input.editMounts).toBe(0);
  expect(screen.queryByLabelText("编号 *")).not.toBeInTheDocument();
  await act(async () => { resolve(data("EDIT")); });
  expect(screen.getByLabelText("编号 *")).toHaveValue("immediate edit");
  fireEvent.click(screen.getByRole("button", { name: "保存" }));
  await screen.findByRole("heading", { name: "保存成功" });
  expect(a.update).toHaveBeenCalledWith(expect.anything(), expect.anything(), expect.objectContaining({ code: "immediate edit" }), expect.any(String));
});

it("does not show record A while the route for record B is loading", async () => {
  const session = { actor: { id: "a", displayName: "业务人员" }, tenant: { id: "t", name: "厂区" }, applications: [{ id: "inventory", name: "库存", entryPath: "apps/inventory/entities/widget" }], permissions: [], canonicalPermissions: ["widget.read", "widget.update"] };
  const sessions: SessionAdapter = { restore: vi.fn(async () => session), login: vi.fn(async () => session), logout: vi.fn(async () => {}), loadMenu: vi.fn(async () => ({ applicationId: "inventory", title: "库存", nodes: [] })) };
  const a = adapter();let resolve!: (value: LedgerRecord) => void;
  vi.mocked(a.record).mockImplementation(async (_definition, id) => id === OTHER_ID ? await new Promise<LedgerRecord>(done => { resolve = done; }) : { ...RECORD, values: { ...RECORD.values, code: "record A" } });
  window.history.replaceState(null, "", `/t/t/apps/inventory/entities/widget/records/${ID}`);
  render(<App mode="http" sessionAdapter={sessions} createEntityLedger={() => a} />);
  await screen.findByText("record A");
  await act(async () => { navigate(`/t/t/apps/inventory/entities/widget/records/${OTHER_ID}`); });
  expect(screen.queryByText("record A")).not.toBeInTheDocument();expect(screen.queryByRole("button", { name: "修改物品" })).not.toBeInTheDocument();
  await act(async () => { resolve({ ...RECORD, id: OTHER_ID, values: { ...RECORD.values, code: "record B" } }); });
  expect(screen.getByText("record B")).toBeInTheDocument();expect(screen.queryByText("record A")).not.toBeInTheDocument();
});

import { afterEach, expect, it, vi } from "vitest";

afterEach(() => { window.dispatchEvent(new Event("pagehide"));vi.useRealTimers();vi.restoreAllMocks(); document.body.innerHTML = ""; });

it("keeps an export action announcement through a passive comparison of the same snapshot", async () => {
  vi.useFakeTimers();
  document.body.innerHTML = '<p id="status" role="status"></p><p id="error" hidden></p><input id="file" type="file"><button id="import"></button><input id="name"><button id="export"></button><button id="export-text"></button>';
  const get = <T extends HTMLElement>(selector: string) => document.querySelector<T>(selector)!;
  const createObjectURL = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:fixture");
  vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  let name = "原文档";
  let mutation: ((event: { type: number }) => void) | undefined;
  const unit = {
    getId: () => "local", getName: () => name, save: () => ({ name }),
    setName(value: string) { name = value; mutation?.({ type: 2 }); },
  };
  const api = {
    getCurrentLifecycleStage: () => 1,
    Enum: { LifecycleStages: { Steady: 1 } },
    Event: { CommandExecuted: "command", LifeCycleChanged: "lifecycle" },
    addEvent(event: string, handler: typeof mutation) {
      if (event === "command") mutation = handler;
      return { dispose() {} };
    },
  };
  // Load the independently compiled editor for this test without importing its
  // vendor ambient declarations into the workbench typecheck.
  const { startLocalDocumentSession } = await vi.importActual<{
    startLocalDocumentSession(options: Record<string, unknown>): void;
  }>("../../../office/local-document-session");
  startLocalDocumentSession({
    univer: { dispose() {} }, univerAPI: api, unit, name: "文档",
    create: () => unit, validate: () => unit.save(), serialize: JSON.stringify,
    filename: (snapshot: { name: string }) => snapshot.name, plainText: () => "内容",
  });
  const display = get("#status");
  const input = get<HTMLInputElement>("#name");
  input.value = "新文件名";
  input.dispatchEvent(new Event("change"));
  get("#export-text").click();
  expect(createObjectURL).toHaveBeenCalledTimes(1);
  expect(display.textContent).toContain("尚未导出为 JSON");
  vi.advanceTimersByTime(80);
  expect(display.textContent).toContain("尚未导出为 JSON");
  const closeAfterText = new Event("beforeunload", { cancelable: true });
  window.dispatchEvent(closeAfterText);
  expect(closeAfterText.defaultPrevented).toBe(true);

  unit.setName("另外的修改");
  get("#export").click();
  vi.advanceTimersByTime(80);
  expect(display.textContent).toContain("已发起本机文件下载");
  const closeAfterJson = new Event("beforeunload", { cancelable: true });
  window.dispatchEvent(closeAfterJson);
  expect(closeAfterJson.defaultPrevented).toBe(false);

  unit.setName("导出后又编辑");
  vi.advanceTimersByTime(80);
  expect(display.textContent).toBe("有尚未导出的修改");
});

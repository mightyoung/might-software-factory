import { describe, expect, it, vi } from "vitest";
import type { BrowserContext, Page, Response } from "@playwright/test";
import { captureFindingCreateDefinition } from "../../e2e/findingForm";

function response(page: Page, json: Response["json"]) {
  return { url: () => "http://localhost/api/v1/entities/finding/definition?use=CREATE", request: () => ({ method: () => "GET" }), frame: () => ({ page: () => page }), json } as Response;
}
function context() {
  let emit!: (response: Response) => Promise<void>;
  const context = { waitForEvent: vi.fn((_event, predicate: (response: Response) => Promise<boolean>) => new Promise<Response>(resolve => {
    emit = async response => { if (await predicate(response)) resolve(response); };
  })) } as unknown as BrowserContext;
  return { context, emit: (response: Response) => emit(response) };
}
describe("finding report definition capture", () => {
  it("reads the opened page's body immediately and ignores matching responses from other tabs", async () => {
    const popup = {} as Page, other = {} as Page, events = context();
    const captured = captureFindingCreateDefinition(events.context, Promise.resolve(popup));
    const unrelated = vi.fn(async () => ({ wrong: true }));await events.emit(response(other, unrelated));expect(unrelated).not.toHaveBeenCalled();
    let navigationStarted = false;
    const body = { meta: { schemaVersion: "1" }, data: { use: "CREATE" } };
    const json = vi.fn(async () => { if (navigationStarted) throw new Error("response body was discarded by navigation");return body; });
    const received = response(popup, json);
    await events.emit(received);
    navigationStarted = true;
    expect(await captured).toEqual({ response: received, body });expect(json).toHaveBeenCalledTimes(1);
  });
  it("does not finish capture until the complete response body has arrived", async () => {
    const popup = {} as Page, events = context();let complete!: (value: unknown) => void;
    const json = vi.fn(() => new Promise(resolve => { complete = resolve; }));
    let finished = false;
    const captured = captureFindingCreateDefinition(events.context, Promise.resolve(popup)).then(value => { finished = true;return value; });
    const emitted = events.emit(response(popup, json));await vi.waitFor(() => expect(json).toHaveBeenCalled());
    expect(finished).toBe(false);complete({ data: "complete" });await emitted;
    expect((await captured).body).toEqual({ data: "complete" });
  });
});

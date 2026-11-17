import { describe, expect, it, vi } from "vitest";
import type { APIResponse, BrowserContext, Page, Route } from "@playwright/test";
import { captureFindingCreateDefinition } from "../../e2e/findingForm";

type Body = Awaited<ReturnType<APIResponse["body"]>>;
const rawBody = (text: string) => ({ toString: () => text }) as Body;
function request(page: Page, body: APIResponse["body"]) {
  const response = { body, status: () => 200 } as APIResponse;
  const route = { request: () => ({ method: () => "GET", frame: () => ({ page: () => page }) }), fetch: vi.fn(async () => response), fulfill: vi.fn(async () => {}), fallback: vi.fn(async () => {}), abort: vi.fn(async () => {}) };
  return { response, route };
}
function context() {
  let forward!: (route: Route) => Promise<void>;
  const context = { route: vi.fn(async (_matches, handler: typeof forward) => { forward = handler; }), unroute: vi.fn(async () => {}), waitForEvent: vi.fn(() => { throw new Error("CDP response-body capture must not be used"); }) };
  return { context: context as unknown as BrowserContext, emit: (route: ReturnType<typeof request>["route"]) => forward(route as unknown as Route), unroute: context.unroute };
}
describe("finding report definition forwarding", () => {
  it("forwards the actual popup request once with unchanged response bytes and ignores other tabs", async () => {
    const popup = {} as Page, events = context();
    const bytes = rawBody('{ "meta": {"schemaVersion":"1"}, "data":{"use":"CREATE"} }\n');
    const wrong = request({} as Page, vi.fn(async () => bytes)), original = request(popup, vi.fn(async () => bytes));
    const captured = await captureFindingCreateDefinition(events.context, Promise.resolve(popup), async () => {
      await events.emit(wrong.route);await events.emit(original.route);
    });
    expect(wrong.route.fallback).toHaveBeenCalledTimes(1);expect(wrong.route.fetch).not.toHaveBeenCalled();
    expect(original.route.fetch).toHaveBeenCalledExactlyOnceWith({ maxRedirects: 0, maxRetries: 0 });
    expect(original.route.fulfill).toHaveBeenCalledExactlyOnceWith({ response: original.response, body: bytes });
    expect(captured).toEqual({ response: original.response, body: JSON.parse(bytes.toString()) });
    expect(events.unroute).toHaveBeenCalledTimes(1);expect(original.route.abort).not.toHaveBeenCalled();
  });
  it("waits for the full upstream body before fulfilling the browser request", async () => {
    const popup = {} as Page, events = context();let complete!: (bytes: Body) => void;
    const original = request(popup, vi.fn(() => new Promise(resolve => { complete = resolve; })));
    let handling!: Promise<void>;
    const captured = captureFindingCreateDefinition(events.context, Promise.resolve(popup), async () => { handling = events.emit(original.route); });
    await vi.waitFor(() => expect(original.response.body).toHaveBeenCalled());expect(original.route.fulfill).not.toHaveBeenCalled();
    const bytes = rawBody('{"data":"complete"}');complete(bytes);await handling;
    expect((await captured).body).toEqual({ data: "complete" });expect(original.route.fulfill).toHaveBeenCalledExactlyOnceWith({ response: original.response, body: bytes });
  });
  it("cleans up interception and fails without retrying when forwarding fails", async () => {
    const popup = {} as Page, events = context(), original = request(popup, vi.fn());
    const failure = new Error("upstream unavailable");original.route.fetch.mockRejectedValue(failure);
    await expect(captureFindingCreateDefinition(events.context, Promise.resolve(popup), async () => { await events.emit(original.route); })).rejects.toBe(failure);
    expect(original.route.fetch).toHaveBeenCalledTimes(1);expect(original.route.abort).toHaveBeenCalledTimes(1);expect(events.unroute).toHaveBeenCalledTimes(1);
  });
});

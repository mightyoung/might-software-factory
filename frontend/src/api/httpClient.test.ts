import { describe, expect, it, vi } from "vitest";
import { ApiError, createHttpClient } from "./httpClient";

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers });
}

describe("createHttpClient", () => {
  it("fetches the CSRF cookie before the first write and sends it as a header", async () => {
    let token: string | null = null;
    const fetchImpl = vi.fn(async (path: RequestInfo | URL, _init?: RequestInit) => {
      if (String(path).endsWith("/auth/csrf")) token = "t1";
      return new Response(null, { status: 204 });
    });
    const client = createHttpClient({ fetchImpl: fetchImpl as typeof fetch, readCookie: () => token });
    await client.post("/api/v1/x", { body: { a: 1 } });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    const init = fetchImpl.mock.calls[1]![1] as RequestInit;
    expect((init.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBe("t1");
    expect(init.credentials).toBe("same-origin");
  });

  it("does not send a CSRF header on reads", async () => {
    const fetchImpl = vi.fn(async () => json(200, { ok: true }));
    const client = createHttpClient({ fetchImpl: fetchImpl as typeof fetch, readCookie: () => "t" });
    await client.get("/api/v1/x");
    const init = (fetchImpl.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect(init.headers).not.toHaveProperty("X-XSRF-TOKEN");
  });

  it("maps the unified error body, traceId and Retry-After", async () => {
    const body = { code: "RATE_LIMITED", message: "m", traceId: "tr-1", retryable: true, fieldErrors: [{ field: "a", reason: "NotBlank" }] };
    const fetchImpl = vi.fn(async () => json(429, body, { "Retry-After": "7" }));
    const client = createHttpClient({ fetchImpl: fetchImpl as typeof fetch });
    const error = (await client.get("/api/v1/x").catch((e) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 429, code: "RATE_LIMITED", traceId: "tr-1", retryable: true, retryAfterSeconds: 7 });
    expect(error.fieldErrors).toEqual(body.fieldErrors);
  });

  it("does not echo non-JSON error pages and reports network failure as status 0", async () => {
    const html = createHttpClient({ fetchImpl: (async () => new Response("<html>", { status: 502 })) as typeof fetch });
    expect(await html.get("/x").catch((e) => e)).toMatchObject({ status: 502, code: "UNKNOWN", retryable: true });
    const down = createHttpClient({ fetchImpl: (async () => { throw new TypeError("fail"); }) as typeof fetch });
    expect(await down.get("/x").catch((e) => e)).toMatchObject({ status: 0, code: "NETWORK", retryable: true });
  });

  it("rethrows aborts untouched", async () => {
    const ctl = new AbortController();
    ctl.abort();
    const client = createHttpClient({ fetchImpl: (async () => { throw new DOMException("Aborted", "AbortError"); }) as typeof fetch });
    expect(await client.get("/x", { signal: ctl.signal }).catch((e) => e)).toMatchObject({ name: "AbortError" });
  });
});

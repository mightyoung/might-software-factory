import { createHttpAppAdapters } from "./httpAppAdapters";
import { createSyntheticAppAdapters } from "./syntheticAppAdapters";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SessionError } from "../domain/session";
import { syntheticSessionAdapter } from "./sessionAdapter";
import { createFindingStoreFactory, createSessionAdapter } from "./sessionAdapterFactory";

afterEach(() => vi.unstubAllGlobals());

describe("API mode boundary", () => {
  it("uses the synthetic adapter only for the documented demo modes", () => {
    for (const mode of [undefined, "", "synthetic"]) {
      expect(createSessionAdapter(mode)).toBe(syntheticSessionAdapter);
      expect(createFindingStoreFactory(mode)).toBeUndefined();
    }
  });

  it("requires tenant selection and a separate store factory in HTTP mode", () => {
    expect(createSessionAdapter("http").requiresTenantCode).toBe(true);
    expect(createFindingStoreFactory("http")).toBeTypeOf("function");
  });

  it("rejects unknown modes instead of silently entering a synthetic session", () => {
    for (const mode of ["htp", "HTTP", "mock"]) {
      expect(() => createSessionAdapter(mode)).toThrow("Unsupported VITE_API_MODE");
      expect(() => createFindingStoreFactory(mode)).toThrow("Unsupported VITE_API_MODE");
    }
  });

  it("does not replace failed HTTP authentication with the demo adapter", async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError("offline"));
    vi.stubGlobal("fetch", fetch);

    await expect(createSessionAdapter("http").login({
      username: "demo.safety", password: "synthetic-password", tenantCode: "demo",
    })).rejects.toEqual(new SessionError("unavailable", true));
    expect(fetch).toHaveBeenCalled();
  });
});

// The entry composes one mode; factories still rebuild scoped transports per identity.
describe("production adapter composition", () => {
  it("creates fresh HTTP stores without acquiring a synthetic session", async () => {
    const adapters = createHttpAppAdapters();
    expect(adapters.sessionAdapter.requiresTenantCode).toBe(true);
    const first = adapters.createFindingStore();
    const next = adapters.createFindingStore();
    expect(next).not.toBe(first);
    expect(next.ledger).not.toBe(first.ledger);
    expect(next.commands).not.toBe(first.commands);
    expect(adapters.createPermissionAdmin()).not.toBe(adapters.createPermissionAdmin());
    const fetch = vi.fn().mockRejectedValue(new TypeError("offline"));
    vi.stubGlobal("fetch", fetch);
    await expect(adapters.sessionAdapter.login({
      username: "demo.safety", password: "demo", tenantCode: "demo",
    })).rejects.toEqual(new SessionError("unavailable", true));
    expect(fetch).toHaveBeenCalled();
  });

  it("preserves the shared in-memory ledger and isolated permission editors for demo mode", () => {
    const adapters = createSyntheticAppAdapters();
    expect(adapters.sessionAdapter).toBe(syntheticSessionAdapter);
    expect(adapters.createFindingStore()).toBe(adapters.createFindingStore());
    expect(adapters.createPermissionAdmin()).not.toBe(adapters.createPermissionAdmin());
  });
});

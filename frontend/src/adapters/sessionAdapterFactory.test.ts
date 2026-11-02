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

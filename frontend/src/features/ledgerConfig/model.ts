import type { HttpClient } from "../../api/httpClient";

import type { components } from "../../api/generated/ledger-configurations";

export type LedgerField = components["schemas"]["Field"];
export type LedgerConfiguration = components["schemas"]["Definition"];
export type ConfigurationSnapshot = components["schemas"]["Snapshot"];
export type ConfigurationSummary = components["schemas"]["Summary"];

export interface LedgerConfigAdapter {
  list(signal?: AbortSignal): Promise<ConfigurationSummary[]>;
  versions(key: string, signal?: AbortSignal): Promise<ConfigurationSnapshot[]>;
  save(
    key: string,
    definition: LedgerConfiguration,
    expectedVersion: number,
    requestId: string,
  ): Promise<ConfigurationSnapshot>;
  restore(
    key: string,
    sourceVersion: number,
    expectedVersion: number,
    requestId: string,
  ): Promise<ConfigurationSnapshot>;
  publish(
    key: string,
    version: number,
    expectedPublishedVersion: number | null,
  ): Promise<ConfigurationSnapshot>;
}
const base = "/api/v1/admin/ledger-configurations";
export function createHttpLedgerConfig(
  client: HttpClient,
): LedgerConfigAdapter {
  const path = (key: string) => `${base}/${encodeURIComponent(key)}`;
  return {
    async list(signal) {
      return (
        await client.get<{ items: ConfigurationSummary[] }>(base, { signal })
      ).items;
    },
    async versions(key, signal) {
      return (
        await client.get<{ versions: ConfigurationSnapshot[] }>(
          `${path(key)}/versions`,
          { signal },
        )
      ).versions;
    },
    save: (key, definition, expectedVersion, requestId) =>
      client.post(`${path(key)}/versions`, {
        body: { definition, expectedVersion, requestId },
      }),
    restore: (key, sourceVersion, expectedVersion, requestId) =>
      client.post(`${path(key)}/restore`, {
        body: { sourceVersion, expectedVersion, requestId },
      }),
    publish: (key, version, expectedPublishedVersion) =>
      client.post(`${path(key)}/publish`, {
        body: { version, expectedVersion: version, expectedPublishedVersion },
      }),
  };
}

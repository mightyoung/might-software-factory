import type { HttpClient } from "../../api/httpClient";
import type { components } from "../../api/generated/published-ledgers";

export type Values = components["schemas"]["Record"]["values"];
export type Capabilities = components["schemas"]["Capabilities"];
export type PublishedLedger = components["schemas"]["Ledger"];
export type LedgerRecord = components["schemas"]["Record"];
export type RecordDetail = components["schemas"]["Detail"];
export type Query = components["schemas"]["Query"] & { filters: components["schemas"]["Filter"][] };
export type RecordPage = components["schemas"]["Page"];
export interface PublishedLedgerAdapter {
  list(signal?: AbortSignal): Promise<PublishedLedger[]>;
  query(key: string, query: Query, signal?: AbortSignal): Promise<RecordPage>;
  detail(key: string, id: string, signal?: AbortSignal): Promise<RecordDetail>;
  create(
    key: string,
    request: {
      requestId: string;
      definitionVersion: number;
      organizationId: string;
      values: Values;
    },
  ): Promise<LedgerRecord>;
  update(
    key: string,
    id: string,
    request: { requestId: string; expectedVersion: number; values: Values },
  ): Promise<LedgerRecord>;
  remove(
    key: string,
    id: string,
    request: { requestId: string; expectedVersion: number },
  ): Promise<unknown>;
}
export function createPublishedLedgerAdapter(
  client: HttpClient,
): PublishedLedgerAdapter {
  const path = (key: string) => "/api/v1/ledgers/" + encodeURIComponent(key);
  return {
    list: async (signal) =>
      (
        await client.get<{ items: PublishedLedger[] }>("/api/v1/ledgers", {
          signal,
        })
      ).items,
    query: (key, query, signal) =>
      client.post(path(key) + "/records/query", { body: query, signal }),
    detail: (key, id, signal) =>
      client.get(path(key) + "/records/" + encodeURIComponent(id), { signal }),
    create: (key, request) =>
      client.post(path(key) + "/records", { body: request }),
    update: (key, id, request) =>
      client.put(path(key) + "/records/" + encodeURIComponent(id), {
        body: request,
      }),
    remove: (key, id, request) =>
      client.post(
        path(key) + "/records/" + encodeURIComponent(id) + "/delete",
        { body: request },
      ),
  };
}

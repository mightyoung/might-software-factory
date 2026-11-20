import type { PageRequest, PageResult, QueryValues } from "../domain/ledger";
import type { FindingRecord } from "../domain/finding";
import type { FindingDefinition } from "./findingDefinition";

export type FindingView = "all" | "confirmation" | "acceptance";

export interface FindingReadOptions {
  /** Cancellation signal for the current UI read; it does not imply a transport or route. */
  signal?: AbortSignal;
}

export type FindingLedgerAccessFailureKind = "unauthenticated" | "forbidden";
export type FindingLedgerReadFailureKind = FindingLedgerAccessFailureKind | "unknown";

/** Semantic UI boundary only. A future transport must map documented server behavior explicitly. */
export class FindingLedgerAccessError extends Error {
  constructor(readonly kind: FindingLedgerAccessFailureKind) {
    super(`Finding ledger read ${kind}`);
    this.name = "FindingLedgerAccessError";
  }
}

export function classifyFindingLedgerReadFailure(error: unknown): FindingLedgerReadFailureKind {
  return error instanceof FindingLedgerAccessError ? error.kind : "unknown";
}

export interface FindingListRequest {
  query: QueryValues;
  page: PageRequest;
  view: FindingView;
}

export interface FindingDetailSnapshot {
  record: FindingRecord | null;
  definition: FindingDefinition | null;
}

/** UI-facing seam only. No HTTP route or server payload is implied by this type. */
export interface FindingLedgerAdapter {
  /** Present only for the real HTTP definition consumer, never inferred from failures. */
  readonly publishedDefinition?: true;
  list(input: FindingListRequest, options?: FindingReadOptions): Promise<PageResult<FindingRecord> & { definition?: FindingDefinition }>;
  get(id: string, options?: FindingReadOptions): Promise<FindingRecord | null>;
  /** Published transports return the current detail definition with its record in one result. */
  getDetailSnapshot?(id: string, options?: FindingReadOptions): Promise<FindingDetailSnapshot>;
}

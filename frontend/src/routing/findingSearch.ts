import { findingStates, type FindingState } from "../domain/finding";

const stateParameter = "findingState";

/** Only the existing finding deep-link parameters are interpreted. The browser URL stays raw. */
export function findingSearchInput(search: string) {
  const query = new URLSearchParams(search);
  return {
    tab: query.get("tab"),
    report: query.get("report"),
    caseId: query.get("caseId"),
    related: query.get("related"),
    relatedCode: query.get("relatedCode"),
    findingState: query.get(stateParameter),
  };
}

export function validateFindingSearch(input: Record<string, unknown>) {
  const standalone = input.tab === "1";
  return {
    standalone,
    findingState: validateFindingState(input.findingState),
    reporting: standalone && input.report === "1",
    caseId: typeof input.caseId === "string" ? input.caseId : null,
    relatedId: typeof input.related === "string" ? input.related : null,
    relatedCode: typeof input.relatedCode === "string" ? input.relatedCode : null,
  };
}

export type FindingSearch = ReturnType<typeof validateFindingSearch>;

export function validateFindingState(value: unknown): FindingState | null {
  return findingStates.find(state => state === value) ?? null;
}

/** Change only the named key, without reserializing unknown, duplicate or encoded siblings. */
function replaceParameter(search: string, key: string, value?: string): string {
  const parts = search ? search.slice(1).split("&") : [];
  const keep = parts.filter(part => !new URLSearchParams("?" + part).has(key));
  if (value !== undefined) keep.push(`${key}=${value}`);
  else if (keep.length === parts.length) return search;
  return keep.length ? "?" + keep.join("&") : "";
}

export function withoutFindingSelection(search: string): string {
  return replaceParameter(search, "caseId");
}

export function withFindingState(search: string, state: FindingState | null): string {
  return replaceParameter(search, stateParameter, state ?? undefined);
}

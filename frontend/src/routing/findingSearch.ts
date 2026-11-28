/** Only the existing finding deep-link parameters are interpreted. The browser URL stays raw. */
export function findingSearchInput(search: string) {
  const query = new URLSearchParams(search);
  return {
    tab: query.get("tab"),
    report: query.get("report"),
    caseId: query.get("caseId"),
    related: query.get("related"),
    relatedCode: query.get("relatedCode"),
  };
}

export function validateFindingSearch(input: Record<string, unknown>) {
  const standalone = input.tab === "1";
  return {
    standalone,
    reporting: standalone && input.report === "1",
    caseId: typeof input.caseId === "string" ? input.caseId : null,
    relatedId: typeof input.related === "string" ? input.related : null,
    relatedCode: typeof input.relatedCode === "string" ? input.relatedCode : null,
  };
}

export type FindingSearch = ReturnType<typeof validateFindingSearch>;

/** Remove every selection key (including encoded spellings) without rewriting other parameters. */
export function withoutFindingSelection(search: string): string {
  if (!search) return search;
  const parts = search.slice(1).split("&");
  const keep = parts.filter((part) => !new URLSearchParams("?" + part).has("caseId"));
  return keep.length === parts.length ? search : keep.length ? "?" + keep.join("&") : "";
}

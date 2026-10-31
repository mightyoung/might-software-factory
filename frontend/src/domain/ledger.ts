export interface DateRange {
  from: string;
  to: string;
}

/** The UI-only query shape. It is deliberately not an HTTP/OpenAPI request body. */
export type QueryValue = string | DateRange;
export type QueryValues = Readonly<Record<string, QueryValue>>;

export interface TextQueryField<T> {
  key: string;
  label: string;
  kind: "text";
  placeholder?: string;
  read: (record: T) => string;
}

export interface EnumQueryField<T> {
  key: string;
  label: string;
  kind: "enum";
  options: readonly { value: string; label: string }[];
  read: (record: T) => string;
}

export interface DateRangeQueryField<T> {
  key: string;
  label: string;
  kind: "dateRange";
  read: (record: T) => string;
}

export interface NumberQueryField<T> {
  key: string;
  label: string;
  kind: "number";
  operator: "equals" | "atLeast" | "atMost";
  step?: number | "any";
  read: (record: T) => number | null;
}

export interface ReferenceQueryField<T> {
  key: string;
  label: string;
  kind: "reference";
  /** Only caller-supplied, already-authorized choices are rendered. */
  options: readonly { id: string; label: string }[];
  read: (record: T) => string | null;
}

export type LedgerQueryField<T> =
  | TextQueryField<T>
  | EnumQueryField<T>
  | DateRangeQueryField<T>
  | NumberQueryField<T>
  | ReferenceQueryField<T>;

export interface LedgerColumn<T> {
  key: string;
  label: string;
  kind: "text" | "status";
  read: (record: T) => string;
  tone?: (record: T) => string;
}

export interface LedgerSchema<T> {
  entityKey: string;
  title: string;
  queryFields: readonly LedgerQueryField<T>[];
  columns: readonly LedgerColumn<T>[];
  recordKey: (record: T) => string;
  /** 给人看（含读屏）的记录名称，例如业务编号；缺省时退回 recordKey。键可能是 UUID，不适合直接朗读。 */
  recordLabel?: (record: T) => string;
}

export interface PageRequest {
  index: number;
  size: number;
}

export interface PageResult<T> {
  items: readonly T[];
  page: PageRequest;
  total: number;
  pageCount: number;
}

/** A frontend adapter seam; implementations must not assume this is the server contract. */
export interface LedgerAdapter<T> {
  list(input: { query: QueryValues; page: PageRequest }): Promise<PageResult<T>>;
  get(id: string): Promise<T | null>;
}

export function createEmptyQuery<T>(fields: readonly LedgerQueryField<T>[]): Record<string, QueryValue> {
  return Object.fromEntries(
    fields.map((field) => [field.key, field.kind === "dateRange" ? { from: "", to: "" } : ""]),
  );
}

export function hasQueryValue(value: QueryValue | undefined): boolean {
  if (typeof value === "string") return value.trim().length > 0;
  return Boolean(value?.from || value?.to);
}

export function matchesQuery<T>(
  record: T,
  fields: readonly LedgerQueryField<T>[],
  query: QueryValues,
): boolean {
  return fields.every((field) => {
    const value = query[field.key];
    if (!hasQueryValue(value)) return true;

    if (field.kind === "text") {
      return field.read(record).toLocaleLowerCase().includes(String(value).trim().toLocaleLowerCase());
    }

    if (field.kind === "enum") {
      return field.read(record) === value;
    }

    if (field.kind === "number") {
      if (typeof value !== "string" || value.trim() === "") return true;
      const target = Number(value);
      const actual = field.read(record);
      if (!Number.isFinite(target) || actual === null) return false;
      if (field.operator === "atLeast") return actual >= target;
      if (field.operator === "atMost") return actual <= target;
      return actual === target;
    }

    if (field.kind === "reference") {
      return typeof value === "string" && field.read(record) === value;
    }

    if (typeof value === "string") return true;
    const day = field.read(record).slice(0, 10);
    return (!value.from || day >= value.from) && (!value.to || day <= value.to);
  });
}

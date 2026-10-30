import type { LedgerSchema, PageRequest, QueryValues } from "../domain/ledger";

/** This module mirrors only the shared OpenAPI component shape; it does not call an API. */
export const ENTITY_QUERY_CONTRACT = {
  version: "1.0.0",
  sourcePath: "contracts/openapi/entity-query.yaml",
  sourceCommit: "3d08108831e4283a38f618944bae30662d13063b",
  status: "contract-only",
} as const;

export const ENTITY_QUERY_PAGE_SIZES = [20, 50, 100] as const;
export type EntityQueryPageSize = (typeof ENTITY_QUERY_PAGE_SIZES)[number];

export type EntityFieldType =
  | "TEXT"
  | "NUMBER"
  | "MONEY"
  | "DATE"
  | "DATETIME"
  | "BOOLEAN"
  | "ENUM"
  | "UUID"
  | "USER"
  | "ORGANIZATION"
  | "REFERENCE"
  | "IMAGE"
  | "ATTACHMENT";

export const ENTITY_QUERY_OPERATORS = [
  "EQ",
  "GT",
  "GTE",
  "LT",
  "LTE",
  "BETWEEN",
  "IN",
  "CONTAINS",
  "IS_EMPTY",
  "IS_NOT_EMPTY",
  "IS_NULL",
  "IS_NOT_NULL",
  "IN_SUBTREE",
] as const;
export type EntityQueryOperator = (typeof ENTITY_QUERY_OPERATORS)[number];

const unaryOperators = new Set<EntityQueryOperator>(["IS_EMPTY", "IS_NOT_EMPTY", "IS_NULL", "IS_NOT_NULL"]);
type UnaryOperator = "IS_EMPTY" | "IS_NOT_EMPTY" | "IS_NULL" | "IS_NOT_NULL";
type ValuedOperator = Exclude<EntityQueryOperator, UnaryOperator>;
export type EntityQueryScalar = string | number | boolean;
export type EntityQueryValue = EntityQueryScalar | readonly EntityQueryScalar[];

export type EntityQueryFilterCondition =
  | { field: string; operator: UnaryOperator; value?: never }
  | { field: string; operator: ValuedOperator; value: EntityQueryValue };

export interface EntityQuerySortField {
  field: string;
  direction: "ASC" | "DESC";
}

export type EntityQueryPage =
  | { number: number; cursor?: null }
  | { cursor: string; number?: null };

export interface EntityQueryRequest {
  filterAST?: { operator: "AND"; conditions: readonly EntityQueryFilterCondition[] } | null;
  sort?: readonly EntityQuerySortField[] | null;
  page: EntityQueryPage;
  size: EntityQueryPageSize;
  fields?: readonly string[] | null;
}

export interface EntityQueryResponse<T> {
  data: {
    items: readonly T[];
    page: {
      size: EntityQueryPageSize;
      number?: number;
      nextCursor?: string;
      /** The contract says the server omits this when an authorized total is unavailable. */
      total?: number;
    };
  };
  meta: { requestId: string; traceId: string; schemaVersion: string };
}

/** Mirrors EntitySchema / EntityFieldSchema from the contract. Supply a code-reviewed registry. */
export interface RegisteredEntityFieldV1 {
  type: EntityFieldType;
  nullable: boolean;
  selectable: boolean;
  sortable: boolean;
  operators: readonly EntityQueryOperator[];
  maxLength: number;
  enumValues: readonly string[];
}

export interface RegisteredEntityV1 {
  key: string;
  schemaVersion: string;
  idField: string;
  defaultFields: readonly string[];
  fields: Readonly<Record<string, RegisteredEntityFieldV1>>;
}

export type LedgerFieldBinding = Readonly<Record<string, string>>;

export class EntityQueryMappingError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "EntityQueryMappingError";
  }
}

/**
 * Builds a v1 request from a UI schema and explicit registered-field bindings.
 * This is a request-shape adapter only. It neither resolves authorization nor calls a server.
 * A view/tab filter must be explicitly modeled by a registered query field before using it here.
 */
export function mapLedgerQueryToEntityRequest<T>(input: {
  ledgerSchema: LedgerSchema<T>;
  registeredSchema: RegisteredEntityV1;
  fieldBindings: LedgerFieldBinding;
  query: QueryValues;
  page: PageRequest;
  sort?: readonly EntityQuerySortField[];
  fields?: readonly string[];
}): EntityQueryRequest {
  const { ledgerSchema, registeredSchema, fieldBindings, query, page } = input;
  const wirePage = mapOffsetPage(page);
  const conditions: EntityQueryFilterCondition[] = [];
  const knownQueryKeys = new Set(ledgerSchema.queryFields.map((field) => field.key));

  for (const field of ledgerSchema.queryFields) {
    const value = query[field.key];
    if (!hasValue(value)) continue;

    const wireFieldName = fieldBindings[field.key];
    if (!wireFieldName) {
      throw new EntityQueryMappingError(`No registered field binding for active query field "${field.key}"`);
    }
    const wireField = getRegisteredField(registeredSchema, wireFieldName);

    if (field.kind === "text") {
      const text = String(value).trim();
      if (text.length === 0) continue;
      requireType(wireField, wireFieldName, ["TEXT"]);
      requireOperator(wireField, wireFieldName, "CONTAINS");
      if (text.length > wireField.maxLength) {
        throw new EntityQueryMappingError(`Query text exceeds registered maxLength for "${wireFieldName}"`);
      }
      conditions.push(createEntityQueryFilter(wireFieldName, "CONTAINS", text));
      continue;
    }

    if (field.kind === "enum") {
      requireType(wireField, wireFieldName, ["ENUM"]);
      requireOperator(wireField, wireFieldName, "EQ");
      if (typeof value !== "string" || !wireField.enumValues.includes(value)) {
        throw new EntityQueryMappingError(`Value is not registered for enum field "${wireFieldName}"`);
      }
      conditions.push(createEntityQueryFilter(wireFieldName, "EQ", value));
      continue;
    }

    if (field.kind === "number") {
      requireType(wireField, wireFieldName, ["NUMBER", "MONEY"]);
      const operator = field.operator === "atLeast" ? "GTE" : field.operator === "atMost" ? "LTE" : "EQ";
      requireOperator(wireField, wireFieldName, operator);
      const numericValue = typeof value === "string" && value.trim() !== "" ? Number(value) : Number.NaN;
      if (!Number.isFinite(numericValue)) {
        throw new EntityQueryMappingError(`Value for numeric field "${field.key}" must be finite`);
      }
      conditions.push(createEntityQueryFilter(wireFieldName, operator, numericValue));
      continue;
    }

    if (field.kind === "reference") {
      requireType(wireField, wireFieldName, ["REFERENCE", "UUID", "USER", "ORGANIZATION"]);
      requireOperator(wireField, wireFieldName, "EQ");
      if (typeof value !== "string" || value.trim() === "") {
        throw new EntityQueryMappingError(`Reference field "${field.key}" requires a selected ID`);
      }
      if (["UUID", "USER", "ORGANIZATION"].includes(wireField.type) && !isCanonicalUuid(value)) {
        throw new EntityQueryMappingError(`Reference value for "${wireFieldName}" must be a canonical UUID`);
      }
      if (wireField.type === "REFERENCE" && value.length > 256) {
        throw new EntityQueryMappingError(`Reference value for "${wireFieldName}" exceeds 256 characters`);
      }
      conditions.push(createEntityQueryFilter(wireFieldName, "EQ", value));
      continue;
    }

    if (typeof value === "string") {
      throw new EntityQueryMappingError(`Date range query field "${field.key}" must contain a date range`);
    }
    requireType(wireField, wireFieldName, ["DATE"]);
    const { from, to } = value;
    if (from && !isValidDate(from)) {
      throw new EntityQueryMappingError(`Invalid DATE lower bound for "${field.key}"`);
    }
    if (to && !isValidDate(to)) {
      throw new EntityQueryMappingError(`Invalid DATE upper bound for "${field.key}"`);
    }
    if (from && to && from > to) {
      throw new EntityQueryMappingError(`Date range lower bound exceeds upper bound for "${field.key}"`);
    }
    if (from) {
      requireOperator(wireField, wireFieldName, "GTE");
      conditions.push(createEntityQueryFilter(wireFieldName, "GTE", from));
    }
    if (to) {
      requireOperator(wireField, wireFieldName, "LTE");
      conditions.push(createEntityQueryFilter(wireFieldName, "LTE", to));
    }
  }

  for (const key of Object.keys(query)) {
    if (!knownQueryKeys.has(key) && hasValue(query[key])) {
      throw new EntityQueryMappingError(`No UI field schema exists for active query value "${key}"`);
    }
  }
  if (conditions.length > 50) {
    throw new EntityQueryMappingError("The contract allows at most 50 filter conditions");
  }

  const sort = mapSort(input.sort, registeredSchema);
  const fields = mapProjection(input.fields, registeredSchema);
  return {
    ...(conditions.length > 0 ? { filterAST: { operator: "AND" as const, conditions } } : {}),
    ...(sort !== undefined ? { sort } : {}),
    page: wirePage,
    size: mapPageSize(page.size),
    ...(fields !== undefined ? { fields } : {}),
  };
}

/** Creates unary conditions without a value; an empty string remains a distinct valued input. */
export function createEntityQueryFilter(
  field: string,
  operator: EntityQueryOperator,
  value?: EntityQueryValue | null,
): EntityQueryFilterCondition {
  if (!FIELD_KEY.test(field)) throw new EntityQueryMappingError(`Invalid contract field key "${field}"`);
  if (unaryOperators.has(operator)) {
    if (value !== undefined) {
      throw new EntityQueryMappingError(`${operator} does not accept a value`);
    }
    return { field, operator: operator as UnaryOperator };
  }
  if (value === undefined || value === null) {
    throw new EntityQueryMappingError(`${operator} requires a non-null typed value`);
  }
  if ((operator === "IN" || operator === "BETWEEN") && !Array.isArray(value)) {
    throw new EntityQueryMappingError(`${operator} requires an array value`);
  }
  if (operator === "BETWEEN" && Array.isArray(value) && value.length !== 2) {
    throw new EntityQueryMappingError("BETWEEN requires exactly two values");
  }
  if (operator === "IN" && Array.isArray(value) && (value.length < 1 || value.length > 100)) {
    throw new EntityQueryMappingError("IN requires between 1 and 100 values");
  }
  if (Array.isArray(value) && value.some((entry) => entry === null || entry === undefined)) {
    throw new EntityQueryMappingError(`${operator} array values must not contain null`);
  }
  return { field, operator: operator as ValuedOperator, value: value as EntityQueryValue };
}

function mapOffsetPage(page: PageRequest): EntityQueryPage {
  if (!Number.isInteger(page.index) || page.index < 1) {
    throw new EntityQueryMappingError("Page number must be a positive integer");
  }
  const size = mapPageSize(page.size);
  if ((page.index - 1) * size > 10_000) {
    throw new EntityQueryMappingError("Page number exceeds the contract maximum offset");
  }
  return { number: page.index };
}

function mapPageSize(size: number): EntityQueryPageSize {
  if (!ENTITY_QUERY_PAGE_SIZES.includes(size as EntityQueryPageSize)) {
    throw new EntityQueryMappingError("Page size must be 20, 50, or 100 under contract v1.0.0");
  }
  return size as EntityQueryPageSize;
}

function mapSort(
  sort: readonly EntityQuerySortField[] | undefined,
  schema: RegisteredEntityV1,
): readonly EntityQuerySortField[] | undefined {
  if (sort === undefined) return undefined;
  if (sort.length > 3) throw new EntityQueryMappingError("The contract allows at most 3 explicit sort fields");
  const used = new Set<string>();
  for (const item of sort) {
    if (!FIELD_KEY.test(item.field)) throw new EntityQueryMappingError(`Invalid sort field "${item.field}"`);
    if (used.has(item.field)) throw new EntityQueryMappingError(`Duplicate sort field "${item.field}"`);
    used.add(item.field);
    const field = getRegisteredField(schema, item.field);
    if (!field.sortable) throw new EntityQueryMappingError(`Registered field "${item.field}" is not sortable`);
    if (item.direction !== "ASC" && item.direction !== "DESC") {
      throw new EntityQueryMappingError(`Invalid sort direction for "${item.field}"`);
    }
  }
  return sort.map((item) => ({ field: item.field, direction: item.direction }));
}

function mapProjection(fields: readonly string[] | undefined, schema: RegisteredEntityV1): readonly string[] | undefined {
  if (fields === undefined) return undefined;
  if (fields.length > 50) throw new EntityQueryMappingError("The contract allows at most 50 output fields");
  const used = new Set<string>();
  for (const name of fields) {
    if (!FIELD_KEY.test(name)) throw new EntityQueryMappingError(`Invalid output field "${name}"`);
    if (used.has(name)) throw new EntityQueryMappingError(`Duplicate output field "${name}"`);
    used.add(name);
    const field = getRegisteredField(schema, name);
    if (!field.selectable) throw new EntityQueryMappingError(`Registered field "${name}" is not selectable`);
  }
  return [...fields];
}

function getRegisteredField(schema: RegisteredEntityV1, name: string): RegisteredEntityFieldV1 {
  const field = schema.fields[name];
  if (!field) throw new EntityQueryMappingError(`Field "${name}" is not present in the registered schema`);
  return field;
}

function requireType(field: RegisteredEntityFieldV1, name: string, allowed: readonly EntityFieldType[]): void {
  if (!allowed.includes(field.type)) {
    throw new EntityQueryMappingError(`Registered type ${field.type} for "${name}" cannot represent this UI query`);
  }
}

function requireOperator(field: RegisteredEntityFieldV1, name: string, operator: EntityQueryOperator): void {
  if (!field.operators.includes(operator)) {
    throw new EntityQueryMappingError(`Operator ${operator} is not registered for "${name}"`);
  }
}

function hasValue(value: QueryValues[string] | undefined): boolean {
  if (typeof value === "string") return value.trim().length > 0;
  return Boolean(value?.from || value?.to);
}

function isValidDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00.000Z`);
  return !Number.isNaN(parsed.valueOf()) && parsed.toISOString().slice(0, 10) === value;
}

function isCanonicalUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

const FIELD_KEY = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;

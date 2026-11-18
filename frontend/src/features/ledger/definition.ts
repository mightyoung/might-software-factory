import { exactKeys, FIELD_KEY, FIELD_TYPES, IDENTIFIER, LedgerProtocolError, object, PERMISSION, PROTECTED, requireProtocol as check, strings, type DefinitionData, type LedgerDefinition, type LedgerUse } from "./model";

const USES = new Set(["READ", "CREATE", "EDIT"]);
const QUERY_TYPES: Record<string, string> = { TEXT: "TEXT", MULTILINE_TEXT: "TEXT", UUID: "UUID", REFERENCE: "UUID", INTEGER: "NUMBER", DATE_TIME: "DATETIME", ENUM: "ENUM" };
const OPERATORS = new Set(["EQ", "GT", "GTE", "LT", "LTE", "BETWEEN", "IN", "CONTAINS", "IS_EMPTY", "IS_NOT_EMPTY", "IS_NULL", "IS_NOT_NULL"]);
const dictionary = (value: unknown) => object(value) && Object.keys(value).length <= 128 && Object.keys(value).every(key => FIELD_KEY.test(key) && !["__proto__", "constructor", "prototype"].includes(key));
const text = (value: unknown) => typeof value === "string" && value.length > 0 && value.length <= 4000;

export function parseDefinition(value: unknown, app: string, entity: string, use: LedgerUse): DefinitionData {
  check(object(value) && exactKeys(value, ["definition", "use", "effectiveActions"]) && value.use === use && strings(value.effectiveActions));
  const d = value.definition;
  check(object(d) && exactKeys(d, ["appKey", "entityKey", "definitionVersion", "title", "fields", "inputFields", "querySchema", "layout", "actions", "references"]));
  check(d.appKey === app && d.entityKey === entity && IDENTIFIER.test(app) && IDENTIFIER.test(entity) && text(d.definitionVersion) && text(d.title));
  check(dictionary(d.fields) && dictionary(d.inputFields) && dictionary(d.references) && object(d.layout) && object(d.actions) && object(d.querySchema));
  const fields = d.fields as Record<string, Record<string, unknown>>, inputs = d.inputFields as Record<string, Record<string, unknown>>, refs = d.references as Record<string, Record<string, unknown>>;
  for (const [key, field] of Object.entries(fields)) {
    check(!PROTECTED.has(key) && object(field) && exactKeys(field, ["label", "type", "nullable", "valueLabels"]) && text(field.label) && FIELD_TYPES.has(String(field.type)) && typeof field.nullable === "boolean" && object(field.valueLabels) && Object.values(field.valueLabels).every(label => typeof label === "string"));
  }
  for (const ref of Object.values(refs)) check(object(ref) && exactKeys(ref, ["label"]) && text(ref.label));
  for (const [key, field] of Object.entries(inputs)) {
    check(!PROTECTED.has(key) && object(field) && exactKeys(field, ["label", "type", "required", "maxLength", "allowedValues", "referenceKey", "uses"]) && text(field.label) && FIELD_TYPES.has(String(field.type)) && typeof field.required === "boolean" && Number.isSafeInteger(field.maxLength) && Number(field.maxLength) >= 0 && strings(field.allowedValues) && strings(field.uses) && field.uses.every(v => USES.has(v) && v !== "READ"));
    check(field.referenceKey === null || (typeof field.referenceKey === "string" && Object.hasOwn(refs, field.referenceKey)));
    check(field.type !== "REFERENCE" || field.referenceKey !== null);
  }
  const q = d.querySchema;
  check(exactKeys(q, ["key", "schemaVersion", "idField", "defaultFields", "fields"]) && q.key === entity && text(q.schemaVersion) && q.idField === "id" && strings(q.defaultFields) && dictionary(q.fields));
  const queryFields = q.fields as Record<string, Record<string, unknown>>;
  for (const [key, f] of Object.entries(queryFields)) {
    check(object(f) && exactKeys(f, ["type", "nullable", "selectable", "sortable", "operators", "maxLength", "enumValues"]) && typeof f.nullable === "boolean" && typeof f.selectable === "boolean" && typeof f.sortable === "boolean" && strings(f.operators) && f.operators.every(op => OPERATORS.has(op)) && Number.isSafeInteger(f.maxLength) && strings(f.enumValues));
    check(key === "id" ? f.type === "UUID" && !f.nullable && f.selectable && f.sortable : Object.hasOwn(fields, key) && f.type === QUERY_TYPES[String(fields[key]!.type)]);
  }
  check(Object.hasOwn(queryFields, "id") && q.defaultFields.every(key => Object.hasOwn(queryFields, key) && queryFields[key]!.selectable));
  check(exactKeys(d.layout, ["list", "detail", "create", "edit", "query"]));
  for (const [layout, raw] of Object.entries(d.layout)) {
    check(strings(raw) && raw.length <= 128 && new Set(raw).size === raw.length);
    for (const key of raw) {
      check(layout === "query" ? Object.hasOwn(queryFields, key) : layout === "create" || layout === "edit" ? Object.hasOwn(inputs, key) && (inputs[key]!.uses as string[]).includes(layout === "create" ? "CREATE" : "EDIT") : Object.hasOwn(fields, key));
    }
  }
  check(exactKeys(d.actions, ["read", "create", "update"]));
  for (const action of Object.values(d.actions)) check(object(action) && exactKeys(action, ["label", "permission"]) && text(action.label) && typeof action.permission === "string" && PERMISSION.test(action.permission));
  const permissions = Object.values(d.actions).map(action => (action as Record<string, string>).permission);
  check((value.effectiveActions as string[]).every(permission => permissions.includes(permission)));
  return value as unknown as DefinitionData;
}

export function actionForUse(definition: LedgerDefinition, use: LedgerUse) {
  return definition.actions[use === "READ" ? "read" : use === "CREATE" ? "create" : "update"];
}
export function assertDefinitionVersion(version: string, definition: LedgerDefinition) {
  if (version !== definition.definitionVersion) throw new LedgerProtocolError();
}

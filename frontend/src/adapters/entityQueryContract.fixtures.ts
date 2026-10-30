import type { LedgerSchema } from "../domain/ledger";
import type { RegisteredEntityV1 } from "./entityQueryContract";

/** Synthetic contract-test fixtures only; these names are not an approved production registry. */
export interface ContractFixtureRecord {
  description: string;
  state: string;
  reportedDate: string;
  score: number;
}

export const contractFixtureLedgerSchema: LedgerSchema<ContractFixtureRecord> = {
  entityKey: "contract-fixture",
  title: "Contract fixture",
  recordKey: (record) => record.description,
  queryFields: [
    { key: "keyword", label: "Keyword", kind: "text", read: (record) => record.description },
    {
      key: "state",
      label: "State",
      kind: "enum",
      options: [{ value: "OPEN", label: "Open" }, { value: "CLOSED", label: "Closed" }],
      read: (record) => record.state,
    },
    { key: "reportedDate", label: "Reported date", kind: "dateRange", read: (record) => record.reportedDate },
    { key: "score", label: "Score", kind: "number", operator: "atLeast", read: (record) => record.score },
  ],
  columns: [],
};

export const contractFixtureRegisteredSchema: RegisteredEntityV1 = {
  key: "contractFixture",
  schemaVersion: "fixture-1",
  idField: "id",
  defaultFields: ["id", "description", "state", "reportedDate"],
  fields: {
    id: {
      type: "UUID", nullable: false, selectable: true, sortable: true,
      operators: ["EQ"], maxLength: 0, enumValues: [],
    },
    description: {
      type: "TEXT", nullable: true, selectable: true, sortable: true,
      operators: ["EQ", "CONTAINS", "IS_EMPTY", "IS_NOT_EMPTY", "IS_NULL", "IS_NOT_NULL"],
      maxLength: 80, enumValues: [],
    },
    state: {
      type: "ENUM", nullable: false, selectable: true, sortable: true,
      operators: ["EQ", "IN"], maxLength: 0, enumValues: ["OPEN", "CLOSED"],
    },
    reportedDate: {
      type: "DATE", nullable: false, selectable: true, sortable: true,
      operators: ["EQ", "GTE", "LTE", "BETWEEN"], maxLength: 0, enumValues: [],
    },
    createdAt: {
      type: "DATETIME", nullable: true, selectable: true, sortable: true,
      operators: ["EQ", "GTE", "LTE", "BETWEEN", "IS_NULL", "IS_NOT_NULL"], maxLength: 0, enumValues: [],
    },
    score: {
      type: "NUMBER", nullable: false, selectable: true, sortable: true,
      operators: ["EQ", "GT", "GTE", "LT", "LTE", "BETWEEN"], maxLength: 0, enumValues: [],
    },
    price: {
      type: "MONEY", nullable: false, selectable: true, sortable: true,
      operators: ["EQ", "GT", "GTE", "LT", "LTE", "BETWEEN"], maxLength: 0, enumValues: [],
    },
    internalNote: {
      type: "TEXT", nullable: false, selectable: false, sortable: false,
      operators: [], maxLength: 80, enumValues: [],
    },
  },
};

export const contractFixtureFieldBindings = {
  keyword: "description",
  state: "state",
  reportedDate: "reportedDate",
  score: "score",
} as const;

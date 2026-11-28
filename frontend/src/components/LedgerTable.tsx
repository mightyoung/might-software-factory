import { CheckboxInput } from "@astryxdesign/core/CheckboxInput";
import { tableFeatures, useTable, type RowData } from "@tanstack/react-table";
import { useMemo } from "react";
import type { LedgerColumn } from "../domain/ledger";
import { useAppMode } from "../mode";

const tableFeaturesForLedger = tableFeatures({});

interface LedgerTableProps<T> {
  columns: readonly LedgerColumn<T>[];
  rows: readonly T[];
  getKey: (row: T) => string;
  /** 勾选框等控件的可读名称；缺省使用 getKey。 */
  getLabel?: (row: T) => string;
  selectedIds: ReadonlySet<string>;
  onToggleRow: (id: string) => void;
  onTogglePage: () => void;
  onOpen: (row: T) => void;
}

export function LedgerTable<T extends RowData>({
  columns,
  rows,
  getKey,
  getLabel,
  selectedIds,
  onToggleRow,
  onTogglePage,
  onOpen,
}: LedgerTableProps<T>) {
  const contextMode = useAppMode();
  const demo = (import.meta.env.MSF_BROWSER_MODE ?? contextMode) === "synthetic";
  // TanStack rows cache accessor results. Rebuild when the schema or identity reader changes.
  const data = useMemo(() => [...rows], [rows, columns, getKey]);
  const columnDefs = useMemo(() => columns.map((column) => ({
    id: column.key,
    accessorFn: (row: T) => column.read(row),
  })), [columns]);
  const table = useTable({
    features: tableFeaturesForLedger,
    data,
    columns: columnDefs,
    getRowId: getKey,
  });
  const visibleRows = table.getRowModel().rows;
  const selectedCount = visibleRows.filter((row) => selectedIds.has(row.id)).length;
  const someSelected = selectedCount > 0 && selectedCount < visibleRows.length;
  const pageSelection = someSelected ? "indeterminate" : selectedCount > 0;

  return (
    <div className="table-scroll-region">
      <table className="ledger-table">
        <thead>
          <tr>
            <th className="selection-column">
              <CheckboxInput
                size="sm"
                isLabelHidden
                label="选择本页记录"
                value={pageSelection}
                isDisabled={visibleRows.length === 0}
                onChange={onTogglePage}
              />
            </th>
            {columns.map((column) => (
              <th key={column.key} scope="col">
                {column.label}
              </th>
            ))}
            <th scope="col" className="table-action-column">
              查看
            </th>
          </tr>
        </thead>
        <tbody>
          {visibleRows.map((row) => {
            const { id, original } = row;
            return (
              <tr key={id} className="ledger-row">
                <td className="selection-column" onClick={(event) => event.stopPropagation()}>
                  <CheckboxInput
                    size="sm"
                    isLabelHidden
                    label={`选择 ${getLabel ? getLabel(original) : id}`}
                    value={selectedIds.has(id)}
                    onChange={() => onToggleRow(id)}
                  />
                </td>
                {columns.map((column) => {
                  const value = row.getValue<string>(column.key);
                  return (
                    <td key={column.key}>
                      {column.kind === "status" ? (
                        <span className={`status-label status-${column.tone?.(original) ?? "default"}`}>
                          {value}
                        </span>
                      ) : (
                        <span className="cell-ellipsis" title={value}>{value}</span>
                      )}
                    </td>
                  );
                })}
                <td className="table-action-column">
                  <button className="text-button" type="button" onClick={() => onOpen(original)}>
                    详情
                  </button>
                </td>
              </tr>
            );
          })}
          {visibleRows.length === 0 && (
            <tr>
              <td colSpan={columns.length + 2} className="empty-cell">
                当前筛选条件下没有{demo ? "演示" : ""}记录。
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

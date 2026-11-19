import { CheckboxInput } from "@astryxdesign/core/CheckboxInput";
import type { LedgerColumn } from "../domain/ledger";
import { useAppMode } from "../mode";

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

export function LedgerTable<T>({
  columns,
  rows,
  getKey,
  getLabel,
  selectedIds,
  onToggleRow,
  onTogglePage,
  onOpen,
}: LedgerTableProps<T>) {
  const demo = useAppMode() === "synthetic";
  const selectedCount = rows.filter((row) => selectedIds.has(getKey(row))).length;
  const someSelected = selectedCount > 0 && selectedCount < rows.length;
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
                isDisabled={rows.length === 0}
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
          {rows.map((row) => {
            const id = getKey(row);
            return (
              <tr key={id} className="ledger-row">
                <td className="selection-column" onClick={(event) => event.stopPropagation()}>
                  <CheckboxInput
                    size="sm"
                    isLabelHidden
                    label={`选择 ${getLabel ? getLabel(row) : id}`}
                    value={selectedIds.has(id)}
                    onChange={() => onToggleRow(id)}
                  />
                </td>
                {columns.map((column) => (
                  <td key={column.key}>
                    {column.kind === "status" ? (
                      <span className={`status-label status-${column.tone?.(row) ?? "default"}`}>
                        {column.read(row)}
                      </span>
                    ) : (
                      <span className="cell-ellipsis" title={column.read(row)}>
                        {column.read(row)}
                      </span>
                    )}
                  </td>
                ))}
                <td className="table-action-column">
                  <button className="text-button" type="button" onClick={() => onOpen(row)}>
                    详情
                  </button>
                </td>
              </tr>
            );
          })}
          {rows.length === 0 && (
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

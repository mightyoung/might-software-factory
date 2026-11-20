import { Button } from "@astryxdesign/core/Button";
import type { LedgerConfiguration } from "./model";

export const ledgerViews = [
  { property: "listFields", label: "列表字段", toggle: "列表显示" },
  { property: "queryFields", label: "查询字段", toggle: "允许查询" },
] as const;

export function LedgerViewOrder({
  definition,
  disabled,
  onChange,
}: {
  definition: LedgerConfiguration;
  disabled: boolean;
  onChange: (property: "listFields" | "queryFields", keys: string[]) => void;
}) {
  function move(property: "listFields" | "queryFields", index: number, offset: number) {
    const keys = [...definition[property]];
    const destination = index + offset;
    if (disabled || destination < 0 || destination >= keys.length) return;
    [keys[index], keys[destination]] = [keys[destination], keys[index]];
    onChange(property, keys);
  }

  return (
    <>
      {ledgerViews.map(({ property, label }) => (
        <fieldset key={property}>
          <legend>{label}排序</legend>
          {definition[property].length === 0 ? (
            <p>请先勾选{label}。</p>
          ) : (
            <ol aria-label={label}>
              {definition[property].map((key, index) => {
                const field = definition.fields.find((candidate) => candidate.key === key);
                const name = field?.label ? `${field.label}（${key}）` : key || "未命名字段";
                return (
                  <li key={index}>
                    <div className="config-actions">
                      <span>{name}</span>
                      <Button
                        label={`上移${label} ${name}`}
                        variant="secondary"
                        isDisabled={disabled || index === 0}
                        onClick={() => move(property, index, -1)}
                      />
                      <Button
                        label={`下移${label} ${name}`}
                        variant="secondary"
                        isDisabled={disabled || index === definition[property].length - 1}
                        onClick={() => move(property, index, 1)}
                      />
                    </div>
                  </li>
                );
              })}
            </ol>
          )}
        </fieldset>
      ))}
    </>
  );
}

import { Button } from "@astryxdesign/core/Button";
import { useLayoutEffect, useRef } from "react";
import type { LedgerConfiguration } from "./model";

const views = [
  { property: "listFields", label: "列表字段" },
  { property: "queryFields", label: "查询字段" },
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
  const focusedAction = useRef<HTMLButtonElement | null>(null);
  useLayoutEffect(() => {
    // Moving a keyed DOM row can still blur its focused child in Chrome.
    focusedAction.current?.focus();
    focusedAction.current = null;
  });

  function move(property: "listFields" | "queryFields", index: number, offset: number, button: HTMLButtonElement) {
    const keys = [...definition[property]];
    const destination = index + offset;
    if (disabled || destination < 0 || destination >= keys.length) return;
    if (button === document.activeElement) focusedAction.current = button;
    [keys[index], keys[destination]] = [keys[destination], keys[index]];
    onChange(property, keys);
  }

  return (
    <>
      {views.map(({ property, label }) => {
        const occurrences = new Map<string, number>();
        return (
          <fieldset key={property}>
            <legend>{label}排序</legend>
            {definition[property].length === 0 ? (
              <p>请先勾选{label}。</p>
            ) : (
              <ol aria-label={label}>
                {definition[property].map((key, index) => {
                  const field = definition.fields.find((candidate) => candidate.key === key);
                  const name = field?.label ? `${field.label}（${key}）` : key || "未命名字段";
                  const occurrence = occurrences.get(key) ?? 0;
                  occurrences.set(key, occurrence + 1);
                  return (
                    <li key={`${key}\0${occurrence}`}>
                      <div className="config-actions">
                        <span>{name}</span>
                        <Button
                          label={`上移${label} ${name}`}
                          variant="secondary"
                          isDisabled={disabled || index === 0}
                          onClick={(event) => move(property, index, -1, event.currentTarget)}
                        />
                        <Button
                          label={`下移${label} ${name}`}
                          variant="secondary"
                          isDisabled={disabled || index === definition[property].length - 1}
                          onClick={(event) => move(property, index, 1, event.currentTarget)}
                        />
                      </div>
                    </li>
                  );
                })}
              </ol>
            )}
          </fieldset>
        );
      })}
    </>
  );
}

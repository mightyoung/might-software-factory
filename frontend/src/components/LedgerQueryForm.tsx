import { useId, useRef, type FormEvent } from "react";
import { Button } from "@astryxdesign/core/Button";
import { Icon } from "@astryxdesign/core/Icon";
import "./LedgerQueryForm.css";
import type { DateRange, LedgerQueryField, LedgerSchema, QueryValue, QueryValues } from "../domain/ledger";

interface LedgerQueryFormProps<T> {
  schema: LedgerSchema<T>;
  values: QueryValues;
  onChange: (key: string, value: QueryValue) => void;
  onSearch: () => void;
  onReset: () => void;
}

function dateRangeValue(value: QueryValue | undefined): DateRange {
  return typeof value === "string" || !value ? { from: "", to: "" } : value;
}

function QueryFieldControl<T>({
  field,
  value,
  onChange,
}: {
  field: LedgerQueryField<T>;
  value: QueryValue | undefined;
  onChange: (key: string, value: QueryValue) => void;
}) {
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  if (field.kind === "text") {
    const text = typeof value === "string" ? value : "";
    return (
      <div className="query-field ledger-search-field">
        <label htmlFor={id}>{field.label}</label>
        <div className="ledger-search-control" onClick={(event) => {
          if (event.target === event.currentTarget) input.current?.focus({ preventScroll: true });
        }}>
          <input
            ref={input}
            id={id}
            type="search"
            value={text}
            placeholder={field.placeholder}
            enterKeyHint="search"
            onChange={(event) => onChange(field.key, event.currentTarget.value)}
          />
          {text && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              label={`清除 ${field.label}`}
              tooltip="清除"
              icon={<Icon icon="close" size="sm" color="secondary" />}
              isIconOnly
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => {
                onChange(field.key, "");
                input.current?.focus({ preventScroll: true });
              }}
            />
          )}
        </div>
      </div>
    );
  }

  if (field.kind === "enum") {
    return (
      <label className="query-field" key={field.key}>
        <span>{field.label}</span>
        <select
          value={typeof value === "string" ? value : ""}
          onChange={(event) => onChange(field.key, event.currentTarget.value)}
        >
          <option value="">全部状态</option>
          {field.options.map((option) => (
            <option value={option.value} key={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
    );
  }

  if (field.kind === "number") {
    return (
      <label className="query-field" key={field.key}>
        <span>{field.label}</span>
        <input
          type="number"
          step={field.step ?? "any"}
          value={typeof value === "string" ? value : ""}
          onChange={(event) => onChange(field.key, event.currentTarget.value)}
        />
      </label>
    );
  }

  if (field.kind === "reference") {
    return (
      <label className="query-field" key={field.key}>
        <span>{field.label}</span>
        <select
          value={typeof value === "string" ? value : ""}
          onChange={(event) => onChange(field.key, event.currentTarget.value)}
        >
          <option value="">选择已授权对象</option>
          {field.options.map((option) => (
            <option value={option.id} key={option.id}>
              {option.label}
            </option>
          ))}
        </select>
      </label>
    );
  }

  const range = dateRangeValue(value);
  return (
    <fieldset className="query-field query-date-range" key={field.key}>
      <legend>{field.label}</legend>
      <div className="date-range-controls">
        <label>
          <span className="visually-hidden">{field.label}起</span>
          <input
            type={field.kind === "dateTimeRange" ? "datetime-local" : "date"}
            aria-label={`${field.label}起`}
            value={range.from}
            onChange={(event) => onChange(field.key, { ...range, from: event.currentTarget.value })}
          />
        </label>
        <span aria-hidden="true" className="date-range-separator">
          至
        </span>
        <label>
          <span className="visually-hidden">{field.label}止</span>
          <input
            type={field.kind === "dateTimeRange" ? "datetime-local" : "date"}
            aria-label={`${field.label}止`}
            value={range.to}
            onChange={(event) => onChange(field.key, { ...range, to: event.currentTarget.value })}
          />
        </label>
      </div>
      {field.kind === "dateTimeRange" && <span className="field-hint">北京时间（Asia/Shanghai）</span>}
    </fieldset>
  );
}

export function LedgerQueryForm<T>({ schema, values, onChange, onSearch, onReset }: LedgerQueryFormProps<T>) {
  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onSearch();
  }

  return (
    <form className="query-panel" onSubmit={handleSubmit}>
      <div className="query-fields">
        {schema.queryFields.map((field) => (
          <QueryFieldControl
            key={field.key}
            field={field}
            value={values[field.key]}
            onChange={onChange}
          />
        ))}
      </div>
      <div className="query-actions">
        <Button className="ledger-query-action" label="查询" variant="primary" size="lg" type="submit" />
        <Button className="ledger-query-action" label="重置" variant="secondary" size="lg" type="button" onClick={onReset} />
      </div>
    </form>
  );
}

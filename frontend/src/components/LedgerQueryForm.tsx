import type { FormEvent } from "react";
import { InternationalizationProvider } from "@astryxdesign/core/i18n";
import { TextInput } from "@astryxdesign/core/TextInput";
import "./LedgerQueryForm.css";
import type { DateRange, LedgerQueryField, LedgerSchema, QueryValue, QueryValues } from "../domain/ledger";

const queryControlMessages = {
  "zh-CN": {
    "@astryx.textInput.clearLabel": "清除 {label}",
    "@astryx.inputClearButton.tooltip": "清除",
  },
};

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
  if (field.kind === "text") {
    return (
      <TextInput
        key={field.key}
        type="search"
        hasClear
        label={field.label}
        value={typeof value === "string" ? value : ""}
        placeholder={field.placeholder}
        enterKeyHint="search"
        size="lg"
        containerClassName="query-field ledger-search-field"
        controlClassName="ledger-search-control"
        onChange={(next) => onChange(field.key, next)}
      />
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
    <InternationalizationProvider locale="zh-CN" overrides={queryControlMessages}>
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
          <button className="button button-primary" type="submit">
            查询
          </button>
          <button className="button button-subtle" type="button" onClick={onReset}>
            重置
          </button>
        </div>
      </form>
    </InternationalizationProvider>
  );
}

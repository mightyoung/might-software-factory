export interface ItemOption { id: string; label: string }
/** Ordered detail rows with explicit per-row validation and no silent duplicate removal. */
export function MultiItemRows({ values, options, disabled, errors, itemLabel, title, addLabel, emptyLabel, unavailableLabel, onChange }: {
  itemLabel: string; title: string; addLabel: string; emptyLabel: string; unavailableLabel: string;
  values: string[]; options: ItemOption[]; disabled: boolean; errors: Record<number, string>; onChange(values: string[]): void;
}) {
  return <fieldset disabled={disabled}>
    <legend>{title}</legend>
    {values.map((value, index) => <div className="form-field" key={index}>
      <label htmlFor={`detail-item-${index}`}>第 {index + 1} 件{itemLabel}</label>
      <select id={`detail-item-${index}`} value={value} aria-invalid={Boolean(errors[index])} aria-describedby={errors[index] ? `detail-error-${index}` : undefined}
        onChange={event => onChange(values.map((item, i) => i === index ? event.target.value : item))}>
        <option value="">{emptyLabel}</option>
        {value && !options.some(option => option.id === value) && <option value={value}>{unavailableLabel}</option>}
        {options.map(option => <option value={option.id} key={option.id}>{option.label}</option>)}
      </select>
      {errors[index] && <span role="alert" id={`detail-error-${index}`}>{errors[index]}</span>}
      <button type="button" onClick={() => onChange(values.filter((_, i) => i !== index))} disabled={values.length === 1}>移除第 {index + 1} 件</button>
    </div>)}
    <button type="button" disabled={values.length >= 100} onClick={() => onChange([...values, ""])}>{addLabel}</button>
  </fieldset>;
}

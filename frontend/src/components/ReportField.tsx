import type { ReactNode } from "react";

/**
 * 演示模式下，当前后端创建接口没有的字段会标注；真实模式不显示（后端 v3 已有这些字段）。
 * 见 docs/frontend-contract-gap.md。
 */
const NOT_WIRED_TAG = "后端暂未接入";

export function NotWiredTag() {
  return <span className="inline-tag"> {NOT_WIRED_TAG}</span>;
}

export interface ReportFieldProps {
  id: string;
  label: string;
  error?: string;
  hint?: ReactNode;
  required?: boolean;
  /** 当前后端没有对应字段，真实写入后不会被保存。 */
  notWired?: boolean;
  children: ReactNode;
}

/** 控件的 aria-describedby：提示与错误文字的 id 约定为 `${id}-hint` / `${id}-error`。 */
export function describedByIds(id: string, { hint, error }: { hint?: boolean; error?: boolean }): string | undefined {
  return [hint ? `${id}-hint` : null, error ? `${id}-error` : null].filter(Boolean).join(" ") || undefined;
}

export function ReportField({ id, label, error, hint, required, notWired, children }: ReportFieldProps) {
  return (
    <div className="report-field">
      <label htmlFor={id}>
        {label}
        {required && <span className="required-mark" aria-hidden="true"> *</span>}
        {notWired && <NotWiredTag />}
      </label>
      {children}
      {hint && <p className="field-hint" id={`${id}-hint`}>{hint}</p>}
      {error && (
        <p className="field-error" id={`${id}-error`}>
          {error}
        </p>
      )}
    </div>
  );
}

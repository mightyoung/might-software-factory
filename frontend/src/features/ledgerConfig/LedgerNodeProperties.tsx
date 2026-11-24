import type { LedgerConfiguration } from "./model";

type Workflow = NonNullable<LedgerConfiguration["workflow"]>;
type Node = Workflow["nodes"][number];
const actions = {
  enter: "进入节点", submit: "提交", approve: "通过", return: "退回",
} as const;
const rules = {
  hidden: "隐藏", readonly: "只读", editable: "可编辑", required: "可编辑且必填",
} as const;
const sources = {
  members: "指定成员", roles: "角色", positions: "岗位",
  departmentManager: "发起人的部门主管", field: "台账人员字段",
} as const;

export function LedgerNodeProperties({ node, fields, states, onChange }: {
  node: Node;
  fields: LedgerConfiguration["fields"];
  states: string[];
  onChange: (patch: Partial<Node>) => void;
}) {
  const approval = node.approval;
  return <>
    <fieldset>
      <legend>状态动作</legend>
      {Object.entries(actions).map(([action, label]) => {
        const key = action as keyof typeof actions;
        const value = node.transitions?.[key] ?? "";
        return <label key={action}>
          {label}后的状态
          <select value={value} onChange={(event) => {
            const transitions = { ...node.transitions };
            if (event.target.value) transitions[key] = event.target.value;
            else delete transitions[key];
            onChange({ transitions });
          }}>
            <option value="">保留当前状态</option>
            {value && !states.includes(value) && (
              <option value={value}>{value}（状态已移除）</option>
            )}
            {states.filter(Boolean).map((state, index) => (
              <option key={index} value={state}>{state}</option>
            ))}
          </select>
        </label>;
      })}
    </fieldset>
    <fieldset>
      <legend>节点字段规则</legend>
      {fields.map((field, index) => <label key={index}>
        {field.label || field.key}的规则
        <select value={node.fieldRules?.[field.key] ?? ""} onChange={(event) => {
          const fieldRules = { ...node.fieldRules };
          if (event.target.value) fieldRules[field.key] = event.target.value as keyof typeof rules;
          else delete fieldRules[field.key];
          onChange({ fieldRules });
        }}>
          <option value="">沿用台账字段定义</option>
          {Object.entries(rules).map(([value, label]) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </select>
      </label>)}
    </fieldset>
    <fieldset>
      <legend>审批人配置</legend>
      <label>
        审批人来源
        <select value={approval?.source ?? ""} onChange={(event) => onChange({
          approval: event.target.value ? {
            source: event.target.value as NonNullable<Node["approval"]>["source"],
            mode: approval?.mode ?? "any",
            targets: [],
            ...(approval?.fallbackMember ? { fallbackMember: approval.fallbackMember } : {}),
          } : undefined,
        })}>
          <option value="">未配置审批</option>
          {Object.entries(sources).map(([value, label]) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </select>
      </label>
      {approval && <>
        <label>
          多人办理方式
          <select value={approval.mode} onChange={(event) => onChange({
            approval: { ...approval, mode: event.target.value as typeof approval.mode },
          })}>
            <option value="any">任一人办理</option>
            <option value="all">全员会签</option>
            <option value="sequential">按顺序办理</option>
          </select>
        </label>
        {approval.source === "field" ? <label>
          存储成员标识的文本字段
          <select value={approval.targets[0] ?? ""} onChange={(event) => onChange({
            approval: { ...approval, targets: event.target.value ? [event.target.value] : [] },
          })}>
            <option value="">请选择字段</option>
            {approval.targets[0] && !fields.some((field) => (
              field.key === approval.targets[0] && field.type === "text"
            )) && <option value={approval.targets[0]}>{approval.targets[0]}（字段不可用）</option>}
            {fields.filter((field) => field.type === "text").map((field, index) => (
              <option key={index} value={field.key}>{field.label || field.key}</option>
            ))}
          </select>
        </label> : approval.source !== "departmentManager" && <label>
          审批目标（每行一个，按顺序）
          <textarea
            value={approval.targets.join("\n")}
            placeholder={`${sources[approval.source]} UUID`}
            rows={4}
            onChange={(event) => onChange({
              approval: {
                ...approval,
                targets: event.target.value ? event.target.value.split(/\r?\n/) : [],
              },
            })}
          />
        </label>}
        <label>
          待分派责任人成员标识
          <input
            placeholder="成员 UUID"
            maxLength={128}
            value={approval.fallbackMember ?? ""}
            onChange={(event) => onChange({
              approval: { ...approval, fallbackMember: event.target.value || undefined },
            })}
          />
        </label>
      </>}
    </fieldset>
  </>;
}

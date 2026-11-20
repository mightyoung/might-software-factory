import { Button } from "@astryxdesign/core/Button";
import type { LedgerConfiguration } from "./model";

export function LedgerViewPreview({
  definition,
}: {
  definition: LedgerConfiguration;
}) {
  return (
    <section aria-label="页面预览">
      <h2>页面预览</h2>
      <p>{definition.name || "未命名台账"}</p>
      <fieldset disabled aria-label="查询条件预览">
        <legend>查询条件预览</legend>
        {definition.queryFields.length === 0 && <p>尚未选择查询字段。</p>}
        {definition.queryFields.map((key, index) => {
          const field = definition.fields.find((item) => item.key === key);
          if (!field) return <p key={index}>未找到字段（{key}）</p>;
          return (
            <label key={index}>
              {field.label || field.key || "未命名字段"}（查询预览）
              {field.type === "boolean" ? (
                <select defaultValue="">
                  <option value="">全部</option>
                  <option value="true">是</option>
                  <option value="false">否</option>
                </select>
              ) : (
                <input type={field.type === "text" ? "text" : field.type} />
              )}
            </label>
          );
        })}
        {definition.queryFields.length > 0 && (
          <Button label="查询预览" isDisabled variant="secondary" />
        )}
      </fieldset>
      <div className="config-preview">
        {definition.listFields.length ? (
          <table aria-label="列表预览">
            <thead>
              <tr>
                {definition.listFields.map((key, index) => {
                  const field = definition.fields.find((item) => item.key === key);
                  return (
                    <th key={index} scope="col">
                      {field ? field.label || field.key || "未命名字段" : `未找到字段（${key}）`}
                    </th>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              <tr>
                <td colSpan={definition.listFields.length}>暂无预览记录。</td>
              </tr>
            </tbody>
          </table>
        ) : <p>尚未选择列表字段。</p>}
      </div>
    </section>
  );
}

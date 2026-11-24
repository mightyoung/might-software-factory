import { Button } from "@astryxdesign/core/Button";
import { useRef, useState, type PointerEvent } from "react";
import type { LedgerConfiguration } from "./model";
import "./ledger-workflow.css";

type Workflow = NonNullable<LedgerConfiguration["workflow"]>;
type Node = Workflow["nodes"][number];

export function defaultWorkflow(): Workflow {
  return {
    nodes: [
      { id: "start", kind: "start", label: "登记", x: 180, y: 500 },
      { id: "end", kind: "end", label: "完成", x: 820, y: 500 },
    ],
    edges: [{ from: "start", to: "end" }],
  };
}

export function LedgerWorkflowEditor({
  workflow,
  disabled,
  onChange,
}: {
  workflow: Workflow;
  disabled: boolean;
  onChange: (workflow: Workflow) => void;
}) {
  const [selectedId, setSelectedId] = useState("start");
  const [preview, setPreview] = useState<{
    id: string;
    x: number;
    y: number;
  } | null>(null);
  const canvas = useRef<HTMLDivElement>(null);
  const drag = useRef<{
    id: string;
    x: number;
    y: number;
  } | null>(null);
  const selected =
    workflow.nodes.find((node) => node.id === selectedId) ?? workflow.nodes[0];
  const order: Node[] = [];
  let next = workflow.nodes.find((node) => node.kind === "start");
  while (next && !order.includes(next)) {
    order.push(next);
    const edge = workflow.edges.find((edge) => edge.from === next?.id);
    next = workflow.nodes.find((node) => node.id === edge?.to);
  }
  const connected =
    order.length === workflow.nodes.length &&
    order.at(-1)?.kind === "end" &&
    order.slice(1, -1).every((node) => node.kind === "step") &&
    workflow.edges.length === order.length - 1;
  const routeLocked = disabled || !connected;
  const displayNodes =
    !disabled && preview ? patchNode(preview, preview.id) : workflow.nodes;
  const index = order.indexOf(selected);
  const canEditStep = !routeLocked && selected?.kind === "step";

  function patchNode(patch: Partial<Node>, id: string) {
    return workflow.nodes.map((node) =>
      node.id === id ? { ...node, ...patch } : node,
    );
  }
  function update(patch: Partial<Node>, id = selected?.id) {
    if (disabled || !id) return;
    onChange({ ...workflow, nodes: patchNode(patch, id) });
  }
  function route(nodes: Node[]) {
    onChange({
      nodes,
      edges: nodes
        .slice(1)
        .map((node, index) => ({ from: nodes[index].id, to: node.id })),
    });
  }
  function insert() {
    if (routeLocked || workflow.nodes.length >= 32) return;
    const position = selected?.kind === "end" ? index : index + 1;
    const nodes = [...order];
    const before = nodes[position - 1];
    const after = nodes[position];
    const node: Node = {
      id: "step_" + crypto.randomUUID().replaceAll("-", ""),
      kind: "step",
      label: "办理",
      x: Math.round((before.x + after.x) / 2),
      y: Math.round((before.y + after.y) / 2),
    };
    nodes.splice(position, 0, node);
    route(nodes);
    setSelectedId(node.id);
  }
  function move(offset: number) {
    if (
      !canEditStep ||
      index + offset <= 0 ||
      index + offset >= order.length - 1
    )
      return;
    const nodes = [...order];
    [nodes[index], nodes[index + offset]] = [
      nodes[index + offset],
      nodes[index],
    ];
    route(nodes);
  }
  function position(value: number) {
    return Math.max(0, Math.min(1000, Math.round(value)));
  }
  function dragNode(event: PointerEvent<HTMLButtonElement>, commit = false) {
    const current = drag.current;
    const rect = canvas.current?.getBoundingClientRect();
    const node = workflow.nodes.find((node) => node.id === current?.id);
    if (commit) {
      drag.current = null;
      setPreview(null);
    }
    if (disabled || !current || !node || !rect?.width || !rect.height) return;
    const dx = event.clientX - current.x;
    const dy = event.clientY - current.y;
    const point = {
      x: position(node.x + ((dx / rect.width) * 1000) / 0.84),
      y: position(node.y + ((dy / rect.height) * 1000) / 0.8),
    };
    if (commit) {
      if (Math.hypot(dx, dy) > 4) update(point, node.id);
    } else {
      setPreview({ id: node.id, ...point });
    }
  }

  return (
    <section aria-label="流程设计" className="workflow-editor">
      <h2>流程设计</h2>
      <div className="workflow-canvas" ref={canvas} aria-label="流程画布">
        <svg
          viewBox="0 0 1000 1000"
          preserveAspectRatio="none"
          aria-hidden="true"
        >
          {workflow.edges.map((edge, index) => {
            const from = displayNodes.find((node) => node.id === edge.from);
            const to = displayNodes.find((node) => node.id === edge.to);
            return from && to ? (
              <line key={index} x1={from.x} y1={from.y} x2={to.x} y2={to.y} />
            ) : null;
          })}
        </svg>
        {displayNodes.map((node) => (
          <button
            key={node.id}
            type="button"
            className="workflow-node"
            style={{
              left: `${8 + node.x * 0.084}%`,
              top: `${10 + node.y * 0.08}%`,
            }}
            aria-label={`流程节点 ${node.label || "未命名"}`}
            aria-pressed={selected?.id === node.id}
            onClick={() => setSelectedId(node.id)}
            onKeyDown={(event) => {
              const offsets: Record<string, [number, number]> = {
                ArrowLeft: [-25, 0],
                ArrowRight: [25, 0],
                ArrowUp: [0, -25],
                ArrowDown: [0, 25],
              };
              const offset = offsets[event.key];
              if (disabled || !offset) return;
              event.preventDefault();
              setSelectedId(node.id);
              update(
                {
                  x: position(node.x + offset[0]),
                  y: position(node.y + offset[1]),
                },
                node.id,
              );
            }}
            onPointerDown={(event) => {
              setSelectedId(node.id);
              if (disabled || event.button !== 0) return;
              drag.current = {
                id: node.id,
                x: event.clientX,
                y: event.clientY,
              };
              event.currentTarget.setPointerCapture?.(event.pointerId);
            }}
            onPointerMove={dragNode}
            onPointerCancel={() => {
              drag.current = null;
              setPreview(null);
            }}
            onPointerUp={(event) => dragNode(event, true)}
          >
            {node.label || "未命名"}
          </button>
        ))}
      </div>
      <p>拖动或方向键调整位置。</p>
      <p aria-label="办理路径">
        {connected
          ? order.map((node) => node.label || "未命名").join(" → ")
          : "流程未连通，请重置后配置。"}
      </p>
      <label>
        选中流程节点
        <select
          value={selected?.id ?? ""}
          onChange={(event) => setSelectedId(event.target.value)}
        >
          {workflow.nodes.map((node) => (
            <option key={node.id} value={node.id}>
              {node.label || "未命名"}
            </option>
          ))}
        </select>
      </label>
      {selected && (
        <fieldset disabled={disabled}>
          <legend>节点属性</legend>
          <label>
            节点名称
            <input
              maxLength={120}
              value={selected.label}
              onChange={(event) => update({ label: event.target.value })}
            />
          </label>
          <div className="workflow-coordinates">
            {(["x", "y"] as const).map((axis) => (
              <label key={axis}>
                {axis === "x" ? "节点横坐标" : "节点纵坐标"}
                <input
                  type="number"
                  min={0}
                  max={1000}
                  value={selected[axis]}
                  onChange={(event) =>
                    update({ [axis]: position(Number(event.target.value)) })
                  }
                />
              </label>
            ))}
          </div>
        </fieldset>
      )}
      <div className="config-actions">
        <Button
          label="重置为登记到完成"
          variant="secondary"
          isDisabled={disabled}
          onClick={() => {
            if (!disabled) {
              onChange(defaultWorkflow());
              setSelectedId("start");
            }
          }}
        />
        <Button
          label="插入办理节点"
          variant="secondary"
          isDisabled={routeLocked || workflow.nodes.length >= 32}
          onClick={insert}
        />
        <Button
          label="提前办理"
          variant="secondary"
          isDisabled={!canEditStep || index <= 1}
          onClick={() => move(-1)}
        />
        <Button
          label="延后办理"
          variant="secondary"
          isDisabled={!canEditStep || index >= order.length - 2}
          onClick={() => move(1)}
        />
        <Button
          label="删除办理节点"
          variant="secondary"
          isDisabled={!canEditStep}
          onClick={() => {
            if (canEditStep) {
              route(order.filter((node) => node.id !== selected.id));
              setSelectedId("start");
            }
          }}
        />
      </div>
    </section>
  );
}

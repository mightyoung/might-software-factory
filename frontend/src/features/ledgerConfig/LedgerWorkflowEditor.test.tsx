import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { defaultWorkflow, LedgerWorkflowEditor } from "./LedgerWorkflowEditor";

afterEach(cleanup);

function Editor() {
  const [workflow, setWorkflow] = useState(defaultWorkflow);
  return (
    <>
      <LedgerWorkflowEditor
        workflow={workflow}
        disabled={false}
        onChange={setWorkflow}
      />
      <output aria-label="配置结果">{JSON.stringify(workflow)}</output>
    </>
  );
}
const result = () =>
  JSON.parse(screen.getByLabelText("配置结果").textContent || "{}");

describe("LedgerWorkflowEditor", () => {
  it("inserts and renames steps, changes the route independently from positions and reconnects after deletion", () => {
    render(<Editor />);
    expect(screen.getByLabelText("办理路径")).toHaveTextContent("登记 → 完成");
    fireEvent.click(screen.getByRole("button", { name: "插入办理节点" }));
    fireEvent.change(screen.getByLabelText("节点名称"), {
      target: { value: "复核" },
    });
    fireEvent.change(screen.getByLabelText("节点横坐标"), {
      target: { value: "700" },
    });
    fireEvent.click(screen.getByRole("button", { name: "插入办理节点" }));
    fireEvent.change(screen.getByLabelText("节点名称"), {
      target: { value: "批准" },
    });
    expect(screen.getByLabelText("办理路径")).toHaveTextContent(
      "登记 → 复核 → 批准 → 完成",
    );
    fireEvent.click(screen.getByRole("button", { name: "提前办理" }));
    expect(screen.getByLabelText("办理路径")).toHaveTextContent(
      "登记 → 批准 → 复核 → 完成",
    );
    expect(
      result().nodes.find((node: { label: string }) => node.label === "复核").x,
    ).toBe(700);
    fireEvent.click(screen.getByRole("button", { name: "删除办理节点" }));
    expect(screen.getByLabelText("办理路径")).toHaveTextContent(
      "登记 → 复核 → 完成",
    );
    expect(result().edges).toHaveLength(2);
  });

  it("moves selected nodes with the keyboard and clamps their coordinates", () => {
    render(<Editor />);
    const start = screen.getByRole("button", { name: "流程节点 登记" });
    fireEvent.keyDown(start, { key: "ArrowRight" });
    expect(screen.getByLabelText("节点横坐标")).toHaveValue(205);
    fireEvent.change(screen.getByLabelText("节点横坐标"), {
      target: { value: "-5" },
    });
    expect(screen.getByLabelText("节点横坐标")).toHaveValue(0);
    fireEvent.keyDown(start, { key: "ArrowLeft" });
    expect(screen.getByLabelText("节点横坐标")).toHaveValue(0);
    expect(result().edges).toEqual([{ from: "start", to: "end" }]);
  });

  it("previews a drag with connected lines before persisting its release", () => {
    const onChange = vi.fn();
    vi.stubGlobal("PointerEvent", MouseEvent);
    render(
      <LedgerWorkflowEditor
        workflow={defaultWorkflow()}
        disabled={false}
        onChange={onChange}
      />,
    );
    const canvas = screen.getByLabelText("流程画布");
    vi.spyOn(canvas, "getBoundingClientRect").mockReturnValue({
      x: 0,
      y: 0,
      width: 1000,
      height: 500,
      top: 0,
      left: 0,
      right: 1000,
      bottom: 500,
      toJSON() {},
    });
    const start = screen.getByRole("button", { name: "流程节点 登记" });
    const before = start.style.left;
    fireEvent.pointerDown(start, { button: 0, clientX: 180, clientY: 250 });
    fireEvent.pointerMove(start, { clientX: 264, clientY: 290 });
    expect(start.style.left).not.toBe(before);
    expect(canvas.querySelector("line")).toHaveAttribute("x1", "280");
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.pointerUp(start, { clientX: 264, clientY: 290 });
    expect(onChange).toHaveBeenCalledOnce();
    expect(onChange.mock.calls[0][0].nodes[0]).toMatchObject({
      x: 280,
      y: 600,
    });
    vi.unstubAllGlobals();
  });

  it("preserves unrelated edges when editing a branched draft", () => {
    const workflow = defaultWorkflow();
    workflow.nodes.push({ id: "branch", label: "复核", kind: "step", x: 500, y: 200 });
    workflow.edges.push({ from: "start", to: "branch" });
    const onChange = vi.fn();
    render(<LedgerWorkflowEditor workflow={workflow} disabled={false} onChange={onChange} />);
    fireEvent.click(screen.getByRole("button", { name: "流程节点 复核" }));
    for (const name of ["插入办理节点", "提前办理", "延后办理", "删除办理节点"]) {
      const button = screen.getByRole("button", { name });
      expect(button).toHaveAttribute("aria-disabled", "true");
      fireEvent.click(button);
    }
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("节点名称"), { target: { value: "重新复核" } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ edges: workflow.edges }));
  });

  it("can explicitly restore a default route for an empty draft", () => {
    const onChange = vi.fn();
    render(
      <LedgerWorkflowEditor
        workflow={{ nodes: [], edges: [] }}
        disabled={false}
        onChange={onChange}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "重置为登记到完成" }));
    expect(onChange).toHaveBeenCalledWith(defaultWorkflow());
  });

  it("permits selection while preventing edits in read-only mode", () => {
    const onChange = vi.fn();
    render(
      <LedgerWorkflowEditor
        workflow={defaultWorkflow()}
        disabled
        onChange={onChange}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "流程节点 完成" }));
    expect(screen.getByLabelText("节点名称")).toHaveValue("完成");
    expect(screen.getByLabelText("节点名称")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "插入办理节点" }));
    fireEvent.keyDown(screen.getByRole("button", { name: "流程节点 完成" }), {
      key: "ArrowLeft",
    });
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe("node business properties", () => {
  function BusinessEditor() {
    const [workflow, setWorkflow] = useState(defaultWorkflow);
    return <>
      <LedgerWorkflowEditor workflow={workflow} fields={[{ key: "owner", label: "经办人", type: "text", required: false }, { key: "quantity", label: "数量", type: "number", required: true }]} disabled={false} onChange={setWorkflow} />
      <output aria-label="配置结果">{JSON.stringify(workflow)}</output>
    </>;
  }
  it("keeps node policies and the state catalog when changing the route", () => {
    render(<BusinessEditor />);
    fireEvent.click(screen.getByRole("button", { name: "添加业务状态" }));
    fireEvent.change(screen.getByLabelText("业务状态 1"), { target: { value: "待复核" } });
    fireEvent.change(screen.getByLabelText("进入节点后的状态"), { target: { value: "待复核" } });
    fireEvent.change(screen.getByLabelText("数量的规则"), { target: { value: "required" } });
    fireEvent.change(screen.getByLabelText("审批人来源"), { target: { value: "members" } });
    fireEvent.change(screen.getByLabelText("多人办理方式"), { target: { value: "sequential" } });
    fireEvent.change(screen.getByLabelText("审批目标（每行一个，按顺序）"), { target: { value: "member_1\nmember_2" } });
    fireEvent.change(screen.getByLabelText("待分派责任人成员标识"), { target: { value: "manager_1" } });
    fireEvent.click(screen.getByRole("button", { name: "插入办理节点" }));
    expect(result().states).toEqual(["待复核"]);
    expect(result().nodes[0]).toMatchObject({ transitions: { enter: "待复核" }, fieldRules: { quantity: "required" }, approval: { source: "members", targets: ["member_1", "member_2"], mode: "sequential", fallbackMember: "manager_1" } });
    fireEvent.change(screen.getByLabelText("业务状态 1"), { target: { value: "等待审批" } });
    expect(result().nodes[0].transitions.enter).toBe("等待审批");
    fireEvent.click(screen.getByRole("button", { name: "重置为登记到完成" }));
    expect(result().states).toEqual(["等待审批"]);
    expect(result().nodes).toEqual(defaultWorkflow().nodes);
  });
  it("does not steal another state's actions while typing through its name", () => {
    function Editor() {
      const initial = defaultWorkflow();
      initial.states = ["A", "B"];
      initial.nodes[0].transitions = { enter: "A", approve: "B" };
      const [workflow, setWorkflow] = useState(initial);
      return <><LedgerWorkflowEditor workflow={workflow} disabled={false} onChange={setWorkflow} /><output aria-label="配置结果">{JSON.stringify(workflow)}</output></>;
    }
    render(<Editor />);
    const name = screen.getByLabelText("业务状态 1");
    fireEvent.change(name, { target: { value: "B" } });
    expect(result().nodes[0].transitions).toEqual({ enter: "A", approve: "B" });
    fireEvent.change(name, { target: { value: "B2" } });
    expect(result().states).toEqual(["B2", "B"]);
    expect(result().nodes[0].transitions).toEqual({ enter: "B2", approve: "B" });
  });

  it("switches approver sources without retaining incompatible targets", () => {
    render(<BusinessEditor />);
    fireEvent.change(screen.getByLabelText("审批人来源"), { target: { value: "field" } });
    const fields = screen.getByLabelText("存储成员标识的文本字段");
    expect(fields).not.toHaveTextContent("数量");
    fireEvent.change(fields, { target: { value: "owner" } });
    expect(result().nodes[0].approval.targets).toEqual(["owner"]);
    fireEvent.change(screen.getByLabelText("审批人来源"), { target: { value: "departmentManager" } });
    expect(result().nodes[0].approval.targets).toEqual([]);
    fireEvent.change(screen.getByLabelText("审批人来源"), { target: { value: "" } });
    expect(result().nodes[0]).not.toHaveProperty("approval");
  });
});

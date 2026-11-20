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

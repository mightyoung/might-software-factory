import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { FindingsLedger } from "./FindingsLedger";

describe("隐患台账分页与详情", () => {
  it("查询按字段生成条件，重置会恢复全部演示记录", async () => {
    render(
      <FindingsLedger
        view="all"
        title="隐患台账"
        description="合成样例"
      />,
    );

    await screen.findByText("DEMO-HZ-001");
    fireEvent.change(screen.getByRole("searchbox", { name: "隐患编号 / 描述" }), { target: { value: "隔离" } });
    fireEvent.click(screen.getByRole("button", { name: "查询" }));
    await screen.findByText("共 1 条演示记录");
    expect(screen.getByText("DEMO-HZ-001")).toBeInTheDocument();
    expect(screen.queryByText("DEMO-HZ-002")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "重置" }));
    await screen.findByText("共 7 条演示记录");
    expect(screen.getByText("DEMO-HZ-001")).toBeInTheDocument();
    expect(screen.queryByText("DEMO-HZ-006")).not.toBeInTheDocument();
    expect(screen.getByRole("searchbox", { name: "隐患编号 / 描述" })).toHaveValue("");
  });

  it("选择范围只包括当前页，翻页后清空选择", async () => {
    render(
      <FindingsLedger
        view="all"
        title="隐患台账"
        description="合成样例"
      />,
    );

    await screen.findByText("DEMO-HZ-001");
    fireEvent.click(screen.getByRole("checkbox", { name: "选择 DEMO-HZ-001" }));
    expect(screen.getByText("已选 1 条（当前页）")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    await screen.findByText("DEMO-HZ-006");
    await waitFor(() => expect(screen.getByText("已选 0 条（当前页）")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("checkbox", { name: "选择本页记录" }));
    expect(screen.getByRole("checkbox", { name: "选择 DEMO-HZ-006" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "选择 DEMO-HZ-007" })).toBeChecked();
    expect(screen.getByText("已选 2 条（当前页）")).toBeInTheDocument();
  });

  it("显示与流程状态对应的详情，未接后端时禁用审批动作", async () => {
    render(
      <FindingsLedger
        view="all"
        title="隐患台账"
        description="合成样例"
      />,
    );
    await screen.findByText("DEMO-HZ-001");
    fireEvent.click(screen.getAllByRole("button", { name: "详情" })[0]);

    expect(await screen.findByRole("heading", { name: "隐患详情" })).toBeInTheDocument();
    expect(screen.getByText("待一人确认")).toBeInTheDocument();
    expect(screen.getByText("待专业评估")).toBeInTheDocument();
    expect(screen.getByText(/审批与派单均为本地演示/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "确认发现" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "退回补充" })).toBeDisabled();

    const detail = screen.getByRole("complementary", { name: "隐患详情面板" });
    expect(within(detail).getByText("一人确认")).toBeInTheDocument();
  });
});

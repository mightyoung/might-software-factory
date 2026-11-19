import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { LoanLossForm } from "./LoanLossForm";
import type { LoanAdapter, LoanRecord } from "./model";

it("requires a reason and explicit confirmation, then reuses the original key after an unknown outcome", async () => {
  const toolId = crypto.randomUUID();
  const loan: LoanRecord = {
    id: crypto.randomUUID(), version: 3, state: "ISSUED", definitionVersion: "historical",
    organizationId: crypto.randomUUID(), custodyScopeId: crypto.randomUUID(), purpose: "生产",
    toolIds: [toolId], returnItems: [{ toolId, label: "一号量规", state: "REQUESTED" }], createdAt: "", updatedAt: "",
  };
  const result: LoanRecord = { ...loan, state: "SETTLED", version: 4,
    returnItems: [{ toolId, label: "一号量规", state: "LOST", reason: "盘点后确认遗失" }] };
  const command = vi.fn().mockRejectedValueOnce(new Error("Disconnected")).mockResolvedValueOnce(result);
  const adapter = { command, lossDetail: vi.fn().mockResolvedValue(result) } as unknown as LoanAdapter;
  const props = { adapter, record: loan, owner: crypto.randomUUID(), identityKey: "keeper", onSaved: vi.fn(), onUnauthenticated: vi.fn() };
  const view = render(<LoanLossForm {...props} />);
  fireEvent.change(screen.getByRole("combobox"), { target: { value: toolId } });
  expect(screen.getByText("核对遗失结论")).toBeDisabled();
  fireEvent.change(screen.getByLabelText("遗失原因"), { target: { value: "盘点后确认遗失" } });
  fireEvent.click(screen.getByText("核对遗失结论"));
  expect(command).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText("确认遗失结案"));
  await screen.findByText(/提交结果尚未确认/);
  view.unmount();
  render(<LoanLossForm {...props} record={result} />);
  fireEvent.click(screen.getByText("核对后重试原请求"));
  await waitFor(() => expect(props.onSaved).toHaveBeenCalledWith(result));
  expect(command.mock.calls[1]).toEqual(command.mock.calls[0]);
  expect(command.mock.calls[0]?.slice(0, 3)).toEqual(["close-lost", loan.id,
    { definitionVersion: "historical", expectedVersion: 3, toolId, reason: "盘点后确认遗失" }]);
});

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ReferencePicker } from "./ReferencePicker";
import { adapter, DEF, ID, OTHER_ID } from "./testFixture";
import type { LedgerReferenceData } from "./model";
afterEach(cleanup);
describe("authorized reference choices", () => {
  it("ignores an old request even when its producer resolves after cancellation", async () => {
    let old!: (page: LedgerReferenceData) => void;
    const a = adapter();vi.mocked(a.reference).mockImplementation((_d, _key, _use, search) => search === "" ? new Promise(resolve => { old = resolve; }) : Promise.resolve({ options: [{ id: OTHER_ID, label: "新候选" }], hasMore: false }));
    render(<ReferencePicker id="ref" label="范围" referenceKey="scope" definition={DEF} use="CREATE" adapter={a} value="" disabled={false} onChange={vi.fn()} onValidated={vi.fn()} onUnauthenticated={vi.fn()} />);
    await waitFor(() => expect(a.reference).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByRole("searchbox", { name: "检索范围" }), { target: { value: "new" } });
    expect(await screen.findByRole("option", { name: "新候选" })).toBeInTheDocument();
    old({ options: [{ id: ID, label: "旧候选" }], hasMore: false });
    await waitFor(() => expect(screen.queryByRole("option", { name: "旧候选" })).not.toBeInTheDocument());
  });
  it("never treats a restored UUID outside current options as a verified selection", async () => {
    const a = adapter(), verified = vi.fn();render(<ReferencePicker id="ref" label="范围" referenceKey="scope" definition={DEF} use="CREATE" adapter={a} value={ID} disabled={false} onChange={vi.fn()} onValidated={verified} onUnauthenticated={vi.fn()} />);
    expect(await screen.findByText("原选择尚未核实，请从当前候选重新选择。")).toBeInTheDocument();expect(verified).not.toHaveBeenCalled();expect(screen.getByRole("combobox")).toHaveValue("");
  });
});

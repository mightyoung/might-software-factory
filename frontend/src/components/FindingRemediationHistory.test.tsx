import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { SubmittedRemediation } from "../domain/finding";
import { FindingRemediationHistory } from "./FindingRemediationHistory";

const findingId = "11111111-1111-4111-8111-111111111111";
const itemId = "22222222-2222-4222-8222-222222222222";
const fileId = "33333333-3333-4333-8333-333333333333";
const submission: SubmittedRemediation = {
  id: "44444444-4444-4444-8444-444444444444", roundNo: 2, statement: "护栏已加固\n现场已复核", submittedAt: "2026-11-08T01:00:00Z",
  submitterMemberId: "55555555-5555-4555-8555-555555555555", submitterName: "整改人甲",
  evidence: [{ itemId, fileId, sha256: "a".repeat(64), verificationLevel: "CONTENT" }],
};
afterEach(cleanup);

describe("FindingRemediationHistory", () => {
  it("renders persisted submission facts and an authorized same-origin content route", () => {
    render(<FindingRemediationHistory findingId={findingId} submissions={[submission]} truncated />);
    expect(screen.getByText("第 2 轮整改")).toBeInTheDocument();
    expect(screen.getByText(/护栏已加固/)).toHaveTextContent("现场已复核");
    expect(screen.getByText(/提交人：整改人甲/)).toHaveTextContent("2026-11-08 09:00");
    expect(screen.getByText("仅显示最近 20 次整改提交。")).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "下载证据 1" });
    expect(link).toHaveAttribute("href", `/api/v1/findings/${findingId}/remediation-rounds/2/evidence-items/${itemId}/content`);
    expect(link).toHaveAttribute("download");
    expect(screen.getByText("a".repeat(64))).toBeInTheDocument();
    expect(screen.getByText("校验级别：内容校验")).toBeInTheDocument();
    expect(screen.queryByText(/验收通过/)).not.toBeInTheDocument();
  });

  it("shows scanner-published evidence without exposing internal protocol labels", () => {
    render(<FindingRemediationHistory findingId={findingId} submissions={[{ ...submission,
      evidence: [{ ...submission.evidence[0]!, verificationLevel: "SCANNED_CONTENT_V2" }],
    }]} />);
    expect(screen.getByRole("link", { name: "下载证据 1" })).toBeInTheDocument();
    expect(screen.getByText("校验级别：内容校验")).toBeInTheDocument();
    expect(screen.queryByText(/SCANNED_CONTENT_V2/)).not.toBeInTheDocument();
  });

  it("defaults optional old-server history to an unknown empty state", () => {
    render(<FindingRemediationHistory findingId={findingId} />);
    expect(screen.getByText("后端暂未提供已提交整改记录。")).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it.each([
    { findingId: "https://untrusted.test", roundNo: 2, itemId, fileId },
    { findingId, roundNo: 2, itemId: "../../other", fileId },
    { findingId, roundNo: 2, itemId: `${itemId}\n`, fileId },
    { findingId, roundNo: 2, itemId, fileId: "javascript:alert(1)" },
    { findingId, roundNo: 0, itemId, fileId },
    { findingId, roundNo: 1.5, itemId, fileId },
  ])("does not construct downloads from invalid references: %j", context => {
    render(<FindingRemediationHistory findingId={context.findingId} submissions={[{
      ...submission, roundNo: context.roundNo, submitterName: null,
      evidence: [{ ...submission.evidence[0]!, itemId: context.itemId, fileId: context.fileId }],
    }]} />);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByText("证据 1 的引用无效，无法下载。")).toBeInTheDocument();
    expect(screen.getByText(/提交人：—/)).toBeInTheDocument();
  });
});

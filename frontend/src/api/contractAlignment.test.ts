import { describe, expect, it } from "vitest";
import { findingStates, type FindingState } from "../domain/finding";
import type { components } from "./generated/finding-ledger";

/** 严格的类型相等：两边只要差一个成员，下面的赋值就无法通过类型检查。 */
type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends <T>() => T extends B ? 1 : 2 ? true : false;

type ContractState = NonNullable<components["schemas"]["FindingListItem"]["state"]>;
type ContractDetailState = components["schemas"]["FindingDetail"]["state"];
type ContractPageSize = components["schemas"]["FindingPageMetadata"]["size"];

// 这些常量只为触发编译期检查：后端契约改了状态枚举，前端 `npm run typecheck` 会在这里失败。
export const frontendStateMatchesListContract: Equal<FindingState, ContractState> = true;
export const frontendStateMatchesDetailContract: Equal<FindingState, ContractDetailState> = true;
export const pageSizesMatchContract: Equal<20 | 50 | 100, ContractPageSize> = true;

describe("前端领域类型与后端契约对齐", () => {
  it("状态枚举与契约在编译期相等；运行时再核对一次成员，防止类型检查被绕过", () => {
    const contractStates: readonly ContractState[] = [
      "DISCOVERED",
      "PERSON_CONFIRM_PENDING",
      "SAFETY_CONFIRM_PENDING",
      "REPORTER_SUPPLEMENT_PENDING",
      "SAFETY_DISPATCH_PENDING",
      "RECTIFYING",
      "ACCEPTANCE_PENDING",
      "NOT_ESTABLISHED",
      "CLOSED",
    ];
    expect([...findingStates].sort()).toEqual([...contractStates].sort());
    expect(frontendStateMatchesListContract && frontendStateMatchesDetailContract && pageSizesMatchContract).toBe(true);
  });
});

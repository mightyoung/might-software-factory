// Shared browser scenario for the contract fixture and the isolated HTTPS database.
export const reviewStates = ["待复核", "待补正"];
export const reviewProperties = {
  transitions: { enter: reviewStates[0], return: reviewStates[1] },
  fieldRules: { quantity: "required" },
  approval: {
    source: "members",
    mode: "sequential",
    targets: ["11111111-1111-4111-a111-111111111111", "22222222-2222-4222-a222-222222222222"],
    fallbackMember: "33333333-3333-4333-a333-333333333333",
  },
};

export async function configureReviewNode(page) {
  for (const [index, state] of reviewStates.entries()) {
    await page.getByRole("button", { name: "添加业务状态", exact: true }).click();
    await page.getByLabel(`业务状态 ${index + 1}`, { exact: true }).fill(state);
  }
  await page.getByLabel("进入节点后的状态").selectOption(reviewStates[0]);
  await page.getByLabel("退回后的状态").selectOption(reviewStates[1]);
  await page.getByLabel("数量的规则").selectOption("required");
  await page.getByLabel("审批人来源").selectOption("members");
  await page.getByLabel("多人办理方式").selectOption("sequential");
  await page.getByLabel("审批目标（每行一个，按顺序）").fill(reviewProperties.approval.targets.join("\n"));
  await page.getByLabel("待分派责任人成员标识").fill(reviewProperties.approval.fallbackMember);
}

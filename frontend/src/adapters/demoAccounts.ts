import type { DemoApplicationId } from "../domain/session";

interface DemoAccount {
  displayName: string;
  description: string;
  applications: readonly DemoApplicationId[];
  permissions: readonly string[];
}

const SAFETY_READ = ["safety.finding.read", "safety.inbox.read", "safety.acceptance.read"] as const;
/** 上报隐患：与“读取”分开授权，只读人员看不到“新建上报”。 */
const FINDING_REPORT = "safety.finding.report";

/** 合成演示账号：权限不同，用来演示受限导航。不是真实人员或真实授权。 */
export const DEMO_ACCOUNTS: Readonly<Record<string, DemoAccount>> = {
  "demo.safety": {
    displayName: "演示安全员",
    description: "仅隐患治理：看不到风险管控、排查任务和其他应用",
    applications: ["safety"],
    permissions: [...SAFETY_READ, FINDING_REPORT],
  },
  "demo.viewer": {
    displayName: "演示只读人员",
    description: "只能查看隐患台账：没有待确认、整改验收，也不能新建上报",
    applications: ["safety"],
    permissions: ["safety.finding.read"],
  },
  "demo.manager": {
    displayName: "演示部门负责人",
    description: "安全全部功能 + 设备管理",
    applications: ["safety", "equipment"],
    permissions: [...SAFETY_READ, FINDING_REPORT, "safety.risk.read", "safety.inspection.read", "equipment.overview.read"],
  },
  "demo.admin": {
    displayName: "演示管理员",
    description: "三个应用的全部入口",
    applications: ["safety", "equipment", "platform"],
    permissions: [
      ...SAFETY_READ,
      FINDING_REPORT,
      "safety.risk.read",
      "safety.inspection.read",
      "equipment.overview.read",
      "platform.permission.read",
      "platform.permission.manage",
    ],
  },
};

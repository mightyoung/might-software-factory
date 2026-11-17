import { expect, type BrowserContext, type Locator, type Page, type Response } from "@playwright/test";

const DEFINITION_PATH = "/api/v1/entities/finding/definition";

/** 详情、新建上报都在新标签页打开：点击后接住新页面，等它加载出来。 */
export async function openInTab(page: Page, click: () => Promise<void>): Promise<Page> {
  const opened = page.context().waitForEvent("page");
  await click();
  const tab = await opened;
  await tab.waitForLoadState("domcontentloaded");
  return tab;
}

/** Read the popup's response body at the event, before waiting for later page navigation. */
export async function captureFindingCreateDefinition(context: BrowserContext, opened: Promise<Page>) {
  let body: Awaited<ReturnType<Response["json"]>>;
  const response = await context.waitForEvent("response", async response => {
    const url = new URL(response.url());
    if (response.request().method() !== "GET" || url.pathname !== DEFINITION_PATH || url.searchParams.get("use") !== "CREATE") return false;
    if (response.frame().page() !== await opened) return false;
    body = await response.json();
    return true;
  });
  return { response, body };
}

/** Observe the form's own authorized CREATE response; labels are published contract data. Returns the report tab as `page`. */
export async function openFindingReport(ledger: Page) {
  const opened = ledger.waitForEvent("popup");
  const definition = captureFindingCreateDefinition(ledger.context(), opened);
  await ledger.getByRole("button", { name: "新建上报" }).click();
  const [page, { response, body }] = await Promise.all([opened, definition]);
  expect(response.status()).toBe(200);
  await page.waitForLoadState("domcontentloaded");
  expect(body.meta.schemaVersion).toBe("1");
  expect(body.data.use).toBe("CREATE");
  const action = body.data.actions["finding.create"];
  expect(action.binding).toEqual({ method: "POST", permission: "finding.create", path: "/api/v1/findings" });
  expect(action.idempotencyKeyRequired).toBe(true);
  expect(body.data.references["report-organization"].binding).toEqual({
    resourceKey: "report-organization", method: "POST", permission: "finding.create",
    path: "/api/v1/entities/finding/references/report-organization/query",
  });
  const organizationLabel = body.data.fields.organizationId.presentation.label;
  expect(organizationLabel).toEqual(expect.any(String));
  expect(organizationLabel.trim()).not.toBe("");
  expect(action.label).toEqual(expect.any(String));
  expect(action.label.trim()).not.toBe("");
  const panel = page.getByRole("complementary", { name: "新建上报面板" });
  const organization = panel.getByRole("combobox", { name: organizationLabel, exact: true });
  await expect(organization).toBeVisible();
  await expect(organization).toBeEnabled();
  await expect(organization).toHaveValue(""); // A bounded reference page never silently selects a candidate.
  return { page, panel, organization, submit: panel.getByRole("button", { name: action.label, exact: true }) };
}

export async function selectReportOrganization(organization: Locator, option: { id: string; name: string; code: string }) {
  const candidate = organization.locator(`option[value="${option.id}"]`);
  await expect(candidate).toHaveText(`${option.name}（${option.code}）`);
  await organization.selectOption(option.id);
  await expect(organization).toHaveValue(option.id);
}

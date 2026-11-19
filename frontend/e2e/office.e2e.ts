import { expect, test } from "@playwright/test";
import { readFile } from "node:fs/promises";

test("主题跟随系统、手动选择和刷新恢复", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await page.goto("/");
  const root = page.locator("html");
  const selector = page.getByRole("combobox", { name: "主题" });
  await expect(root).toHaveAttribute("data-theme", "dark");
  await selector.selectOption("light");
  await page.reload();
  await expect(selector).toHaveValue("light");
  await expect(root).toHaveAttribute("data-theme", "light");
  await selector.selectOption("system");
  await expect(root).toHaveAttribute("data-theme", "dark");
  await page.emulateMedia({ colorScheme: "light" });
  await expect(root).toHaveAttribute("data-theme", "light");
});

test("在线表格按需加载，导出的原生工作簿可以重新导入", async ({ page }) => {
  const officeRequests: string[] = [];
  page.on("request", request => {
    if (new URL(request.url()).pathname.startsWith("/office/")) officeRequests.push(request.url());
  });
  await page.goto("/");
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号", { exact: true }).fill("demo");
  await page.getByLabel("密码", { exact: true }).fill("demo");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  const open = page.getByRole("button", { name: "在线表格" });
  await expect(open).toBeVisible();
  expect(officeRequests).toEqual([]);
  await open.click();
  const dialog = page.getByRole("dialog", { name: "在线表格" });
  const editor = dialog.frameLocator("iframe");
  const save = editor.getByRole("button", { name: "导出工作簿 JSON" });
  await expect(save).toBeEnabled();
  async function exportWorkbook() {
    const [download] = await Promise.all([page.waitForEvent("download"), save.click()]);
    const path = await download.path();
    if (!path) throw Error("导出文件不可用");
    return JSON.parse(await readFile(path, "utf8"));
  }
  const original = await exportWorkbook();
  const sheet = original.sheets[original.sheetOrder[0]];
  sheet.cellData = { 0: { 0: { t: 1, v: "工装量具" } } };
  await editor.locator('input[type="file"]').setInputFiles({
    name: "工装.json", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(original)),
  });
  await expect(editor.getByRole("status")).toContainText("已导入 工装.json");
  await dialog.getByRole("combobox", { name: "主题" }).selectOption("dark");
  await expect(editor.locator("html")).toHaveAttribute("data-theme", "dark");
  expect((await exportWorkbook()).sheets[sheet.id].cellData[0][0].v).toBe("工装量具");
  await editor.locator('input[type="file"]').setInputFiles({
    name: "无效.json", mimeType: "application/json", buffer: Buffer.from("{}"),
  });
  await expect(editor.getByRole("alert")).toBeVisible();
  expect((await exportWorkbook()).sheets[sheet.id].cellData[0][0].v).toBe("工装量具");
  const nameBox = editor.locator('input[type="text"]:not([aria-label]):visible').first();
  await nameBox.fill("A1");
  await nameBox.press("Enter");
  await editor.locator('canvas[id^="univer-sheet-main-canvas_"]').click({ position: { x: 90, y: 35 } });
  await page.keyboard.type("updated-tool");
  await page.keyboard.press("Enter");
  await expect(editor.getByRole("status")).toContainText("尚未导出");
  const close = dialog.getByRole("button", { name: "关闭表格" });
  const cancelDiscard = page.waitForEvent("dialog").then(prompt => prompt.dismiss());
  await close.click();
  await cancelDiscard;
  await expect(dialog).toBeVisible();
  expect((await exportWorkbook()).sheets[sheet.id].cellData[0][0].v).toBe("updated-tool");
  await close.click();
  await expect(dialog).toHaveCount(0);
  await expect(open).toBeFocused();
});

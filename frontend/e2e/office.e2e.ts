import { expect, test, type Locator, type Page } from "@playwright/test";
import { readFile } from "node:fs/promises";

// Univer waits 3 seconds after rendering before entering Steady, in addition to cold asset loading.
const officeStartupTimeout = 15_000;

async function signIn(page: Page) {
  await page.goto("/");
  await page.getByLabel("租户编码").fill("demo");
  await page.getByLabel("账号", { exact: true }).fill("demo");
  await page.getByLabel("密码", { exact: true }).fill("demo");
  await page.getByRole("button", { name: "登录", exact: true }).click();
}

async function downloadSnapshot(page: Page, save: Locator) {
  const [download] = await Promise.all([page.waitForEvent("download"), save.click()]);
  const path = await download.path();
  if (!path) throw Error("无法读取导出的文件");
  return JSON.parse(await readFile(path, "utf8"));
}

async function cancelOfficeClose(page: Page, close: Locator) {
  const nativeDialogs: string[] = [];
  const native = async (dialog: import("@playwright/test").Dialog) => {
    nativeDialogs.push(dialog.type());
    await dialog.dismiss();
  };
  page.on("dialog", native);
  await close.focus();
  await page.keyboard.press("Enter");
  const prompt = page.getByRole("alertdialog", { name: "丢弃未导出的修改？" });
  const keep = prompt.getByRole("button", { name: "继续编辑" });
  await expect(keep).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(prompt.getByRole("button", { name: "丢弃并关闭" })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(keep).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(prompt).toHaveCount(0);
  await expect(close).toBeFocused();
  expect(nativeDialogs).toEqual([]);
  page.off("dialog", native);
}

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
  await signIn(page);
  const open = page.getByRole("button", { name: "在线表格" });
  await expect(open).toBeVisible();
  expect(officeRequests).toEqual([]);
  await open.click();
  const dialog = page.getByRole("dialog", { name: "在线表格" });
  const editor = dialog.frameLocator("iframe");
  const save = editor.getByRole("button", { name: "导出工作簿 JSON" });
  await expect(save).toBeEnabled({ timeout: officeStartupTimeout });
  const exportWorkbook = () => downloadSnapshot(page, save);
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
  const nameBox = editor.locator('input[type="text"]:not([aria-label]):not(#name):visible').first();
  await nameBox.fill("A1");
  await nameBox.press("Enter");
  await editor.locator('canvas[id^="univer-sheet-main-canvas_"]').click({ position: { x: 90, y: 35 } });
  await page.keyboard.type("updated-tool");
  await page.keyboard.press("Enter");
  await expect(editor.getByRole("status")).toContainText("尚未导出");
  const close = dialog.getByRole("button", { name: "关闭表格" });
  await cancelOfficeClose(page, close);
  await expect(dialog).toBeVisible();
  expect((await exportWorkbook()).sheets[sheet.id].cellData[0][0].v).toBe("updated-tool");
  await editor.getByRole("textbox", { name: "工作簿名称" }).fill("未导出变更");
  await expect(editor.getByRole("status")).toContainText("尚未导出");
  await close.click();
  await page.getByRole("alertdialog", { name: "丢弃未导出的修改？" })
    .getByRole("button", { name: "丢弃并关闭" }).click();
  await expect(dialog).toHaveCount(0);
  await expect(open).toBeFocused();
});

test("工装作业指导书可以在在线文档中重新打开、切换主题并导出", async ({ page }) => {
  const requestedEditors: string[] = [];
  page.on("request", request => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith("/office/") && path.endsWith(".html")) requestedEditors.push(path);
  });
  await signIn(page);
  const open = page.getByRole("button", { name: "在线文档", exact: true });
  await expect(open).toBeVisible();
  expect(requestedEditors).toEqual([]);
  await open.click();
  const dialog = page.getByRole("dialog", { name: "在线文档" });
  const frame = dialog.frameLocator('iframe[title="在线文档编辑器"]');
  const save = frame.getByRole("button", { name: "导出文档 JSON" });
  await expect(save).toBeEnabled({ timeout: officeStartupTimeout });
  expect(requestedEditors).toEqual(["/office/docs.html"]);
  const exportDocument = () => downloadSnapshot(page, save);
  const sample = await exportDocument();
  const title = "工装点检作业指导书";
  const instruction = "领用前检查定位销并记录工装编号。";
  expect(sample.format).toBe("might-office-document");
  sample.document.title = title;
  sample.document.body.dataStream = title + "\r" + instruction + "\r\n";
  sample.document.body.paragraphs = [{ startIndex: title.length }, { startIndex: title.length + 1 + instruction.length }];
  sample.document.body.textRuns = [{ st: 0, ed: title.length, ts: { bl: 1 } }];
  const file = frame.locator('input[type="file"]');
  await file.setInputFiles({ name: "作业指导书.json", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(sample)) });
  await expect(frame.getByRole("status")).toContainText("作业指导书.json");
  await dialog.getByRole("combobox", { name: "主题" }).selectOption("dark");
  await expect(frame.locator("html")).toHaveAttribute("data-theme", "dark");
  const result = await exportDocument();
  expect(result.document.body.dataStream).toBe(sample.document.body.dataStream);
  expect(result.document.body.textRuns).toEqual(expect.arrayContaining([expect.objectContaining({ st: 0, ed: title.length, ts: expect.objectContaining({ bl: 1 }) })]));
  await file.setInputFiles({ name: "错误类型.json", mimeType: "application/json", buffer: Buffer.from('{"sheets":{},"sheetOrder":[]}') });
  await expect(frame.getByRole("alert")).toBeVisible();
  expect((await exportDocument()).document.body.dataStream).toBe(sample.document.body.dataStream);
  await dialog.getByRole("button", { name: "关闭文档" }).click();
  await expect(dialog).toHaveCount(0);
  await expect(open).toBeFocused();
});

for (const kind of ["工作簿", "文档"] as const) {
  test(`${kind}名称可修改、随文件保存并在导入后恢复`, async ({ page }) => {
    const errors: string[] = [];
    page.on("pageerror", error => errors.push(error.message));
    await signIn(page);
    const isDocument = kind === "文档";
    const editorName = isDocument ? "在线文档" : "在线表格";
    const closeName = isDocument ? "关闭文档" : "关闭表格";
    await page.getByRole("button", { name: editorName, exact: true }).click();
    const dialog = page.getByRole("dialog", { name: editorName });
    const editor = dialog.frameLocator("iframe");
    const name = editor.getByRole("textbox", { name: `${kind}名称` });
    await expect(name).toBeEnabled({ timeout: officeStartupTimeout });
    await expect(name).toHaveValue(`未命名${kind}`);
    await name.fill("   工装/点检:2026   ");
    await name.press("Enter");
    await expect(name).toHaveValue("工装/点检:2026");
    await expect(editor.getByRole("status")).toContainText("尚未导出");
    await name.fill("   ");
    await name.press("Enter");
    await expect(editor.getByRole("alert")).toContainText("名称不能为空");
    await expect(name).toHaveValue("工装/点检:2026");
    await name.fill("工装/点检:2026交接");
    await expect(editor.getByRole("status")).toContainText("尚未导出");
    await cancelOfficeClose(page, dialog.getByRole("button", { name: closeName }));
    await expect(dialog).toBeVisible();
    const save = editor.getByRole("button", { name: `导出${kind} JSON` });
    const [download] = await Promise.all([page.waitForEvent("download"), save.click()]);
    expect(download.suggestedFilename()).toBe("工装_点检_2026交接.json");
    const path = await download.path();
    if (!path) throw Error("无法读取导出的文件");
    const snapshot = JSON.parse(await readFile(path, "utf8"));
    expect(isDocument ? snapshot.document.title : snapshot.name).toBe("工装/点检:2026交接");
    if (isDocument) snapshot.document.title = "接班作业指导书";
    else snapshot.name = "接班工装清单";
    const importedName = isDocument ? snapshot.document.title : snapshot.name;
    await editor.locator('input[type="file"]').setInputFiles({
      name: "交接.json", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(snapshot)),
    });
    await expect(name).toHaveValue(importedName);
    const roundtrip = await downloadSnapshot(page, save);
    expect(isDocument ? roundtrip.document.title : roundtrip.name).toBe(importedName);
    await dialog.getByRole("button", { name: closeName }).click();
    await expect(dialog).toHaveCount(0);
    expect(errors).toEqual([]);
  });
}

test("作业指导书可导出中文多段落 TXT，关闭前仍提醒保存完整文档", async ({ page }) => {
  await signIn(page);
  await page.getByRole("button", { name: "在线文档", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "在线文档" });
  const editor = dialog.frameLocator("iframe");
  const saveJson = editor.getByRole("button", { name: "导出文档 JSON" });
  const saveText = editor.getByRole("button", { name: "导出纯文本 TXT" });
  await expect(saveText).toBeEnabled({ timeout: officeStartupTimeout });
  const sample = await downloadSnapshot(page, saveJson);
  const paragraphs = ["工装点检指导书", "\t确认定位销完好。", "", "记录工装编号并交接。"];
  sample.document.body.dataStream = paragraphs.join("\r") + "\r\n";
  let offset = 0;
  sample.document.body.paragraphs = paragraphs.map(text => {
    offset += text.length;
    const paragraph = { startIndex: offset };
    offset += 1;
    return paragraph;
  });
  sample.document.body.textRuns = [{ st: 0, ed: paragraphs[0].length, ts: { bl: 1 } }];
  await editor.locator('input[type="file"]').setInputFiles({
    name: "作业指导书.json", mimeType: "application/json", buffer: Buffer.from(JSON.stringify(sample)),
  });
  const name = editor.getByRole("textbox", { name: "文档名称" });
  await name.fill("工装/点检:交接");
  await name.press("Enter");
  const [download] = await Promise.all([page.waitForEvent("download"), saveText.click()]);
  expect(download.suggestedFilename()).toBe("工装_点检_交接.txt");
  const path = await download.path();
  if (!path) throw Error("无法读取纯文本文件");
  const text = await readFile(path, "utf8");
  expect(text).toBe(paragraphs.join("\n"));
  expect(text).not.toMatch(/[\u0000-\u0008\u000b-\u001f\u007f]/);
  await expect(editor.getByRole("status")).toContainText("尚未导出为 JSON");
  await cancelOfficeClose(page, dialog.getByRole("button", { name: "关闭文档" }));
  await expect(dialog).toBeVisible();
  const complete = await downloadSnapshot(page, saveJson);
  expect(complete.document.body.dataStream).toBe(sample.document.body.dataStream);
  expect(complete.document.body.textRuns).toEqual(expect.arrayContaining([
    expect.objectContaining({ st: 0, ed: paragraphs[0].length, ts: expect.objectContaining({ bl: 1 }) }),
  ]));
  await dialog.getByRole("button", { name: "关闭文档" }).click();
  await expect(dialog).toHaveCount(0);
});

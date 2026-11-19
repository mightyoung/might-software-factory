export const officeEditors = {
  sheet: {
    title: "在线表格",
    page: "/office/index.html",
    frameTitle: "在线工作簿编辑器",
    closeLabel: "关闭表格",
    discardPrompt: "工作簿有尚未导出的修改。确认丢弃并关闭？",
  },
  doc: {
    title: "在线文档",
    page: "/office/docs.html",
    frameTitle: "在线文档编辑器",
    closeLabel: "关闭文档",
    discardPrompt: "文档有尚未导出的修改。确认丢弃并关闭？",
  },
} as const;

export type OfficeEditorKind = keyof typeof officeEditors;

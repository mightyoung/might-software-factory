import { createContext, useContext } from "react";

/** 运行模式：synthetic 为内存演示数据；http 为同源服务端。由启动入口一次性决定，界面据此决定措辞，不再从各适配器的能力去猜。 */
export type AppMode = "synthetic" | "http";

const ModeContext = createContext<AppMode>("synthetic");

export const ModeProvider = ModeContext.Provider;

export function useAppMode(): AppMode {
  return useContext(ModeContext);
}

export function parseAppMode(value: string | undefined): AppMode {
  if (value === undefined || value === "" || value === "synthetic") return "synthetic";
  if (value === "http") return "http";
  throw new Error("Unsupported VITE_API_MODE; choose synthetic or http");
}

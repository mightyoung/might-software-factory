import type { ApiError } from "./httpClient";

/**
 * 服务端统一错误（见契约 ErrorResponse）按 HTTP 状态归类。各适配器只把这个类别映射为自己界面语义的
 * 失败种类，不再各自重复写状态码判断。404 在契约里是“不存在或无权，不区分”，所以单独成类，由适配器决定怎么呈现。
 */
export type HttpFailureClass =
  | "invalid" // 400
  | "unauthenticated" // 401
  | "forbidden" // 403
  | "notVisible" // 404
  | "conflict" // 409，具体子码看 ApiError.code
  | "workflow" // 422
  | "rateLimited" // 429
  | "unavailable"; // 其余（含 5xx、网络）

export function classifyHttpError(error: ApiError): HttpFailureClass {
  switch (error.status) {
    case 400:
      return "invalid";
    case 401:
      return "unauthenticated";
    case 403:
      return "forbidden";
    case 404:
      return "notVisible";
    case 409:
      return "conflict";
    case 422:
      return "workflow";
    case 429:
      return "rateLimited";
    default:
      return "unavailable";
  }
}

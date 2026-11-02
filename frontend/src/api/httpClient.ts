import type { components } from "./generated/session-api";

type ErrorBody = components["schemas"]["ErrorResponse"];

/** 服务端统一错误形状（code/message/traceId/retryable/fieldErrors）的前端表示；status 0 表示没有收到响应。 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly traceId: string | null,
    readonly retryable: boolean,
    readonly fieldErrors: ErrorBody["fieldErrors"] = [],
    readonly retryAfterSeconds: number | null = null,
    readonly currentVersion: number | null = null,
  ) {
    super(`HTTP ${status} ${code}`);
    this.name = "ApiError";
  }
}

export interface HttpRequest {
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

export interface HttpClient {
  /** 成功返回解析后的 JSON；204 返回 undefined。其他情况抛 ApiError（取消抛 AbortError）。 */
  get<T>(path: string, request?: HttpRequest): Promise<T>;
  post<T>(path: string, request?: HttpRequest): Promise<T>;
}

export interface HttpClientOptions {
  fetchImpl?: typeof fetch;
  /** 读取 XSRF-TOKEN cookie；测试可注入。 */
  readCookie?: (name: string) => string | null;
}

const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const CSRF_PATH = "/api/v1/auth/csrf";

function documentCookie(name: string): string | null {
  for (const part of document.cookie.split("; ")) {
    const eq = part.indexOf("=");
    if (eq > 0 && part.slice(0, eq) === name) return decodeURIComponent(part.slice(eq + 1));
  }
  return null;
}

function parseRetryAfter(value: string | null): number | null {
  if (!value || !/^\d+$/.test(value)) return null;
  return Number(value);
}

async function toApiError(response: Response): Promise<ApiError> {
  let body: Partial<ErrorBody> | null = null;
  try {
    body = (await response.json()) as Partial<ErrorBody>;
  } catch {
    // 非 JSON 错误体（例如反代的 502 页面）：不回显内容。
  }
  return new ApiError(
    response.status,
    typeof body?.code === "string" ? body.code : "UNKNOWN",
    typeof body?.traceId === "string" ? body.traceId : null,
    typeof body?.retryable === "boolean" ? body.retryable : response.status === 429 || response.status >= 502,
    Array.isArray(body?.fieldErrors) ? body.fieldErrors : [],
    parseRetryAfter(response.headers.get("Retry-After")),
    typeof body?.currentVersion === "number" ? body.currentVersion : null,
  );
}

/** 同源 Cookie 会话 + 双提交 CSRF。令牌每次从 cookie 现读（登录会轮换），不缓存、不进存储。 */
export function createHttpClient(options: HttpClientOptions = {}): HttpClient {
  const fetchImpl = options.fetchImpl ?? ((...args) => fetch(...args));
  const readCookie = options.readCookie ?? documentCookie;

  async function send<T>(method: "GET" | "POST", path: string, request: HttpRequest = {}): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json", ...request.headers };
    if (method === "POST") {
      let token = readCookie(CSRF_COOKIE);
      if (!token) {
        await send<void>("GET", CSRF_PATH, { signal: request.signal });
        token = readCookie(CSRF_COOKIE);
      }
      if (token) headers[CSRF_HEADER] = token;
    }
    if (request.body !== undefined) headers["Content-Type"] = "application/json";

    let response: Response;
    try {
      response = await fetchImpl(path, {
        method,
        headers,
        credentials: "same-origin",
        body: request.body === undefined ? undefined : JSON.stringify(request.body),
        signal: request.signal,
      });
    } catch (error) {
      if (request.signal?.aborted) throw error;
      throw new ApiError(0, "NETWORK", null, true);
    }
    if (!response.ok) throw await toApiError(response);
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }

  return {
    get: (path, request) => send("GET", path, request),
    post: (path, request) => send("POST", path, request),
  };
}

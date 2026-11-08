// 按 contracts/openapi/{session-api,finding-ledger,finding-workflow}.yaml 的**书面描述**写的本地模拟服务，
// 仅用于没有后端时开发和测试前端；它不是后端，不代表真实行为、权限或持久化。内存数据，重启即清空。
// 账号：loginKey=demo / password=demo / tenantCode=demo。
import { createReviewCycleFixture } from "./review-cycle-fixture.mjs";
import { syntheticFindingDefinition } from "./definition-fixture.mjs";
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { pathToFileURL } from "node:url";

const STATES = ["DISCOVERED", "PERSON_CONFIRM_PENDING", "SAFETY_CONFIRM_PENDING", "REPORTER_SUPPLEMENT_PENDING", "SAFETY_DISPATCH_PENDING", "RECTIFYING", "ACCEPTANCE_PENDING", "NOT_ESTABLISHED", "CLOSED"];
const ORGS = [
  { id: "11111111-1111-4111-8111-111111111111", code: "WS-A", name: "一车间" },
  { id: "22222222-2222-4222-8222-222222222222", code: "WS-B", name: "二车间" },
];
const WHITESPACE_ONLY = new RegExp("^[\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]*$");
const field = (type, operators, extra = {}) => ({ type, nullable: false, selectable: true, sortable: false, operators, maxLength: 0, enumValues: [], ...extra });
const SCHEMA = {
  key: "finding", schemaVersion: "5", idField: "id",
  defaultFields: ["id", "code", "organizationId", "organizationName", "description", "discoveredAt", "sourceCategory", "urgency", "location", "interimMeasure", "backfill", "sourceEventId", "riskId", "grade", "state", "roundNo", "createdAt"],
  fields: {
    id: field("UUID", ["EQ", "IN"], { sortable: true }),
    code: field("TEXT", ["EQ", "CONTAINS"], { sortable: true, maxLength: 64 }),
    organizationId: field("UUID", []), organizationName: field("TEXT", [], { nullable: true }),
    description: field("TEXT", [], { maxLength: 20000 }),
    discoveredAt: field("DATETIME", ["EQ", "GT", "GTE", "LT", "LTE", "BETWEEN"], { sortable: true }),
    sourceCategory: field("ENUM", ["EQ", "IN"], { nullable: true, enumValues: ["FIELD_OBSERVATION", "INSPECTION", "PERSON_REPORT"] }),
    urgency: field("ENUM", ["EQ", "IN"], { nullable: true, enumValues: ["NORMAL", "URGENT"] }),
    location: field("TEXT", [], { nullable: true, maxLength: 200 }), interimMeasure: field("TEXT", [], { nullable: true, maxLength: 2000 }),
    backfill: field("BOOLEAN", [], { nullable: true }), sourceEventId: field("UUID", [], { nullable: true }), riskId: field("UUID", [], { nullable: true }),
    grade: field("ENUM", ["EQ", "IN"], { enumValues: ["UNKNOWN", "GENERAL", "MAJOR"] }),
    state: field("ENUM", ["EQ", "IN"], { sortable: true, enumValues: STATES }),
    roundNo: field("NUMBER", []), version: field("NUMBER", []),
    reviewCycle: field("NUMBER", []), reviewProtocol: field("ENUM", [], { enumValues: ["LEGACY", "CYCLE_V1"] }),
    createdAt: field("DATETIME", ["EQ", "GT", "GTE", "LT", "LTE", "BETWEEN"], { sortable: true }), updatedAt: field("DATETIME", ["EQ", "GT", "GTE", "LT", "LTE", "BETWEEN"]),
  },
};

// 派单候选成员：24 个“张”开头（用于分页）+ 李四、王五；memberId 稳定且按此顺序升序。
const MEMBERS = [
  ...Array.from({ length: 24 }, (_, i) => `张${"一二三四五六七八九十"[i % 10]}${i >= 10 ? i : ""}`),
  "李四",
  "王五",
].map((displayName, i) => ({ memberId: `00000000-0000-4000-8000-${String(i + 1).padStart(12, "0")}`, displayName }));
// 当前轮是否已有责任人：用 Symbol 键保存，不会出现在 JSON 响应里。
const ASSIGNED = Symbol("assigned");
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const shanghaiDay = (d) => new Date(d.getTime() + 8 * 3600_000).toISOString().slice(0, 10).replaceAll("-", "");
const cp = (s) => [...s].length;

export function createMockServer(options = {}) {
  // Test-owned mutable projection: no HTTP administration endpoint or real authorization.
  const navigation = () => options.navigation?.() ?? { enabled: true, permissions: ["finding.read", "finding.create"] };
  const cycleFixture = options.reviewCycles === true ? createReviewCycleFixture() : null;
  const sessions = new Map();
  let demoPassword = "demo"; // 改密后变化；重启恢复为 demo
  let passwordFailures = 0; // 连续 5 次当前密码错误后 429，仅用于覆盖限流提示
  const findings = [];
  const reviewEntries = new Map();
  const receipts = new Map(); // Idempotency-Key -> { body, response }
  const evidenceSets = new Map(); // Idempotency-Key -> { id, at }
  const uploads = new Map(); // Idempotency-Key -> 上传意图
  const seqByDay = new Map();

  function addFinding(input, now = new Date()) {
    const day = shanghaiDay(now);
    const seq = (seqByDay.get(day) ?? 0) + 1;
    seqByDay.set(day, seq);
    const org = ORGS.find((o) => o.id === input.organizationId);
    const discovered = new Date(input.discoveredAt);
    const row = {
      id: randomUUID(), code: `YH-${day}-${String(seq).padStart(4, "0")}`, organizationId: org.id, organizationName: org.name,
      description: input.description, discoveredAt: discovered.toISOString(), sourceCategory: input.sourceCategory, urgency: input.urgency,
      location: input.location ?? null, interimMeasure: input.interimMeasure ?? null, backfill: now.getTime() - discovered.getTime() > 24 * 3600_000,
      sourceEventId: null, riskId: null, relatedFindingId: input.relatedFindingId ?? null, [ASSIGNED]: (input.state ?? "PERSON_CONFIRM_PENDING") === "RECTIFYING", grade: "UNKNOWN", state: input.state ?? "PERSON_CONFIRM_PENDING", roundNo: 1, version: 1, reviewCycle: 1, reviewProtocol: "LEGACY",
      createdAt: now.toISOString(), updatedAt: now.toISOString(),
    };
    findings.unshift(row);
    return row;
  }
  const t = Date.now();
  addFinding({ organizationId: ORGS[0].id, description: "搬运区域隔离设施损坏", discoveredAt: new Date(t - 3 * 86400_000).toISOString(), sourceCategory: "FIELD_OBSERVATION", urgency: "NORMAL", location: "A 工房搬运区" }, new Date(t - 2 * 86400_000));
  addFinding({ organizationId: ORGS[1].id, description: "设备防护罩固定件松动", discoveredAt: new Date(t - 86400_000).toISOString(), sourceCategory: "INSPECTION", urgency: "URGENT", location: "B 工房设备 03", state: "RECTIFYING" }, new Date(t - 86400_000));
  addFinding({ organizationId: ORGS[0].id, description: "通道临时堆放周转物料", discoveredAt: new Date(t - 3600_000).toISOString(), sourceCategory: "PERSON_REPORT", urgency: "NORMAL", location: "东侧通道", state: "ACCEPTANCE_PENDING" }, new Date(t - 1800_000));

  const cookies = (req) => Object.fromEntries((req.headers.cookie ?? "").split("; ").filter(Boolean).map((c) => [c.slice(0, c.indexOf("=")), c.slice(c.indexOf("=") + 1)]));
  function send(res, status, body, headers = {}) {
    res.writeHead(status, { ...(body === undefined ? {} : { "Content-Type": "application/json" }), ...headers });
    res.end(body === undefined ? undefined : JSON.stringify(body));
  }
  const err = (res, status, code, message, extra = {}, headers = {}) =>
    send(res, status, { code, message, traceId: randomUUID(), retryable: status === 503, fieldErrors: [], ...extra }, headers);
  const meta = () => ({ requestId: randomUUID(), traceId: randomUUID(), schemaVersion: "5" });

  async function readJson(req) {
    const chunks = [];
    for await (const c of req) chunks.push(c);
    try { return JSON.parse(Buffer.concat(chunks).toString("utf8") || "null"); } catch { return undefined; }
  }

  function matches(row, conditions) {
    return conditions.every(({ field: f, operator, value }) => {
      const v = row[f];
      if (operator === "EQ") return v === value;
      if (operator === "IN") return Array.isArray(value) && value.includes(v);
      if (operator === "CONTAINS") return typeof v === "string" && v.toLowerCase().includes(String(value).toLowerCase());
      return false;
    });
  }

  function validateCreate(b) {
    const errors = [];
    const known = ["organizationId", "description", "discoveredAt", "sourceEventId", "riskId", "grade", "sourceCategory", "urgency", "location", "interimMeasure", "relatedFindingId"];
    for (const k of Object.keys(b)) if (!known.includes(k)) errors.push({ field: k, reason: "UnknownProperty" });
    if (!ORGS.some((o) => o.id === b.organizationId)) errors.push({ field: "organizationId", reason: "NotInScope" });
    if (typeof b.description !== "string" || b.description.length < 1 || b.description.length > 20000) errors.push({ field: "description", reason: "Size" });
    if (typeof b.discoveredAt !== "string" || Number.isNaN(Date.parse(b.discoveredAt))) errors.push({ field: "discoveredAt", reason: "Pattern" });
    if (b.grade !== "UNKNOWN") errors.push({ field: "grade", reason: "Pattern" });
    if (!["FIELD_OBSERVATION", "INSPECTION", "PERSON_REPORT"].includes(b.sourceCategory)) errors.push({ field: "sourceCategory", reason: "Pattern" });
    if (!["NORMAL", "URGENT"].includes(b.urgency)) errors.push({ field: "urgency", reason: "Pattern" });
    if (typeof b.location !== "string" || cp(b.location) > 200 || WHITESPACE_ONLY.test(b.location)) errors.push({ field: "location", reason: "Pattern" });
    if (b.interimMeasure !== undefined && (typeof b.interimMeasure !== "string" || cp(b.interimMeasure) > 2000)) errors.push({ field: "interimMeasure", reason: "Size" });
    return errors;
  }

  return createServer(async (req, res) => {
    const url = new URL(req.url, "http://x");
    const path = url.pathname;
    const jar = cookies(req);
    const session = sessions.get(jar.SESSION);
    const csrfOk = () => Boolean(jar["XSRF-TOKEN"]) && req.headers["x-xsrf-token"] === jar["XSRF-TOKEN"];
    const newCsrf = () => `XSRF-TOKEN=${randomUUID()}; Path=/; SameSite=Lax`;

    if (req.method === "GET" && path === "/api/v1/auth/csrf") return send(res, 204, undefined, { "Set-Cookie": newCsrf() });

    if (req.method === "POST" || req.method === "PUT") {
      if (!csrfOk()) return err(res, 403, "ACTION_DENIED", "CSRF token missing or mismatched");
    }

    if (req.method === "POST" && path === "/api/v1/auth/login") {
      const b = await readJson(req);
      if (!b || typeof b.loginKey !== "string" || typeof b.password !== "string" || typeof b.tenantCode !== "string" || !b.loginKey || !b.password || !b.tenantCode)
        return err(res, 400, "INVALID_REQUEST", "Malformed login");
      const fixtureIdentity = cycleFixture?.login(b.loginKey);
      if ((!fixtureIdentity && b.loginKey !== "demo") || b.password !== (fixtureIdentity ? "demo" : demoPassword) || b.tenantCode !== "demo") return err(res, 401, "AUTH_REQUIRED", "Invalid credentials");
      const id = randomUUID();
      sessions.set(id, fixtureIdentity ?? { actorId: randomUUID(), accountId: randomUUID(), tenantId: randomUUID(), memberId: randomUUID() });
      return send(res, 204, undefined, { "Set-Cookie": [`SESSION=${id}; Path=/; HttpOnly; SameSite=Lax`, newCsrf()] });
    }
    if (req.method === "POST" && path === "/api/v1/auth/logout") {
      sessions.delete(jar.SESSION);
      return send(res, 204, undefined, { "Set-Cookie": ["SESSION=; Path=/; Max-Age=0", "XSRF-TOKEN=; Path=/; Max-Age=0"] });
    }

    if (!path.startsWith("/api/v1/")) return err(res, 404, "RESOURCE_NOT_VISIBLE", "Not found");
    if (!session) return err(res, 401, "AUTH_REQUIRED", "Authentication required");

    const visibleFindings = session.mockReviewRole && cycleFixture ? [cycleFixture.finding] : findings;
    const access = session.mockReviewRole ? { enabled: true, permissions: ["finding.read", ...(session.mockReviewRole === "REPORTER" ? ["finding.create"] : [])] } : navigation();
    const permits = permission => access.enabled === true && access.permissions.includes(permission);
    const identity = { actorId: session.actorId, accountId: session.accountId, tenantId: session.tenantId, memberId: session.memberId };
    const displayName = session.mockReviewRole ? `MOCK ${session.mockReviewRole}` : "模拟用户";
    if (req.method === "GET" && (path === "/api/v1/session/bootstrap" || /^\/api\/v1\/applications\/[^/]+\/menu$/.test(path))) {
      const state = session.mockReviewRole ? { enabled: true, permissions: ["finding.read", ...(session.mockReviewRole === "REPORTER" ? ["finding.create"] : [])] } : navigation();
      if (state.failure) return err(res, state.failure, "DEPENDENCY_UNAVAILABLE", "Navigation unavailable");
      const enabled = state.enabled && state.permissions.includes("finding.read");
      const permissions = enabled ? state.permissions : [];
      const authorization = { tenantAuthEpoch: 1, memberAuthEpoch: 1, applicationVersion: state.enabled === null ? null : 1 };
      if (path === "/api/v1/session/bootstrap") return send(res, 200, { data: {
        schemaVersion: 1, identity: { ...identity, tenantCode: "demo", tenantName: "演示租户（模拟服务）", displayName },
        applications: enabled ? [{ id: "safety", name: "生产安全", entryPath: "safety/findings" }] : [], permissions, authorization,
      } });
      if (path !== "/api/v1/applications/safety/menu" || !enabled) return err(res, 403, "ACTION_DENIED", "Application unavailable");
      return send(res, 200, { data: { schemaVersion: 1, applicationId: "safety", title: "生产安全",
        nodes: [{ id: "findings", label: "隐患管理", path: "safety/findings", permission: "finding.read" }], permissions, authorization } });
    }
    if (req.method === "POST" && path === "/api/v1/account/password") {
      const b = await readJson(req);
      const bytes = (s) => Buffer.byteLength(s, "utf8");
      const known = b && typeof b === "object" && Object.keys(b).every((k) => k === "currentPassword" || k === "newPassword");
      if (!known || typeof b.currentPassword !== "string" || typeof b.newPassword !== "string" || !b.currentPassword || bytes(b.currentPassword) > 72 || cp(b.newPassword) < 15 || bytes(b.newPassword) > 72)
        return err(res, 400, "INVALID_REQUEST", "Validation failed", { fieldErrors: [{ field: "newPassword", reason: "Size" }] });
      if (passwordFailures >= 5) return err(res, 429, "RATE_LIMITED", "Too many attempts", { retryable: true }, { "Retry-After": "2" });
      if (b.currentPassword !== demoPassword) {
        passwordFailures += 1;
        return err(res, 401, "AUTH_REQUIRED", "Reauthentication failed"); // 与“会话失效”同码，前端需要自行区分
      }
      passwordFailures = 0;
      demoPassword = b.newPassword;
      sessions.clear(); // 凭据代数前进：所有旧会话作废
      return send(res, 204, undefined, { "X-Trace-Id": randomUUID(), "Set-Cookie": ["SESSION=; Path=/; Max-Age=0", "XSRF-TOKEN=; Path=/; Max-Age=0"] });
    }
    if (req.method === "GET" && path === "/api/v1/session/current")
      return send(res, 200, { data: { ...identity, tenantCode: "demo", tenantName: "演示租户（模拟服务）", displayName } });
    if (req.method === "GET" && path === "/api/v1/organizations/reportable") return send(res, 200, { data: ORGS });
    if (req.method === "GET" && path === "/api/v1/entities/finding/schema") return send(res, 200, { data: SCHEMA, meta: meta() });

    if (req.method === "GET" && path === "/api/v1/entities/finding/definition") {
      const use = url.searchParams.get("use");
      if (!["READ", "CREATE"].includes(use) || [...url.searchParams.keys()].length !== 1) return err(res, 400, "INVALID_REQUEST", "Use must be READ or CREATE");
      if (access.failure) return err(res, access.failure, "DEPENDENCY_UNAVAILABLE", "Definition unavailable");
      if (!permits(use === "READ" ? "finding.read" : "finding.create")) return err(res, 403, "ACTION_DENIED", "Definition unavailable");
      return send(res, 200, { data: syntheticFindingDefinition(SCHEMA, use), meta: { ...meta(), schemaVersion: "1" } });
    }
    if (req.method === "POST" && path === "/api/v1/entities/finding/references/report-organization/query") {
      if (access.failure) return err(res, access.failure, "DEPENDENCY_UNAVAILABLE", "Reference unavailable");
      if (!permits("finding.create")) return err(res, 403, "ACTION_DENIED", "Reference unavailable");
      const b = await readJson(req);
      const limit = b?.limit === undefined ? 20 : b.limit;
      const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
      if (!b || Array.isArray(b) || typeof b.query !== "string" || cp(b.query) > 100 || b.query.includes("\0")
        || !Number.isInteger(limit) || limit < 1 || limit > 50
        || (b.afterId != null && (typeof b.afterId !== "string" || !uuid.test(b.afterId)))
        || Object.keys(b).some(key => !["query", "limit", "afterId"].includes(key))) return err(res, 400, "INVALID_REQUEST", "Invalid reference query");
      const keyword = b.query.toLowerCase();
      const matches = ORGS.filter(org => (!b.afterId || org.id > b.afterId.toLowerCase()) && (org.code.toLowerCase().includes(keyword) || org.name.toLowerCase().includes(keyword)));
      const items = matches.slice(0, limit);
      return send(res, 200, { data: { items, ...(matches.length > limit ? { nextAfterId: items.at(-1).id } : {}) }, meta: { ...meta(), schemaVersion: "1" } });
    }

    if (req.method === "POST" && path === "/api/v1/entities/finding/query") {
      const b = await readJson(req);
      const size = b?.size;
      const number = b?.page?.number;
      if (!b || ![20, 50, 100].includes(size) || !Number.isInteger(number) || number < 1 || (number - 1) * size > 10000)
        return err(res, 400, "INVALID_REQUEST", "Invalid page");
      const conditions = b.filterAST?.conditions ?? [];
      for (const c of conditions) if (!SCHEMA.fields[c.field]?.operators.includes(c.operator)) return err(res, 400, "INVALID_REQUEST", "Unsupported filter");
      const rows = visibleFindings.filter((r) => matches(r, conditions)); // 已按 createdAt DESC 排列（新建插到头部）
      const total = rows.length;
      return send(res, 200, { data: { items: rows.slice((number - 1) * size, number * size), page: { number, size, total, pageCount: Math.ceil(total / size) } }, meta: meta() });
    }
    const upload = /^\/api\/v1\/findings\/([^/]+)\/remediation-rounds\/([1-9][0-9]*)\/evidence-(sets?|uploads)(?:\/([^/]+)(?:\/(content|cancel))?)?$/.exec(path);
    if (upload) {
      const [, rawId, round, kind, intentId, action] = upload;
      const row = findings.find((r) => r.id === decodeURIComponent(rawId));
      if (!row || !session) return err(res, session ? 404 : 401, session ? "RESOURCE_NOT_VISIBLE" : "AUTH_REQUIRED", "Not visible");
      if (row.state !== "RECTIFYING" || Number(round) !== row.roundNo) return err(res, 404, "RESOURCE_NOT_VISIBLE", "Not visible");
      const view = (u) => ({ data: { uploadIntentId: u.id, state: u.state, expiresAt: u.expiresAt, ...(u.cancel ? { cancelRequested: true } : {}) }, meta: { requestId: randomUUID(), traceId: randomUUID(), schemaVersion: "2" } });
      const key = req.headers["idempotency-key"];
      if (kind === "set" && req.method === "GET") {
        const set = [...evidenceSets.values()].filter((x) => x.findingId === row.id).at(-1);
        if (!set) return err(res, 404, "RESOURCE_NOT_VISIBLE", "Not visible");
        const statusUrl = (u) => `/api/v1/findings/${rawId}/remediation-rounds/${round}/evidence-uploads/${u.id}`;
        const items = [...uploads.values()].filter((u) => u.setId === set.id).map((u) => ({ ...view(u).data, statusUrl: statusUrl(u) }));
        return send(res, 200, { data: { evidenceSetId: set.id, evidenceRevision: 0, uploads: items }, meta: { requestId: randomUUID(), traceId: randomUUID(), schemaVersion: "2" } }, { "Cache-Control": "no-store" });
      }
      if (kind === "sets" && req.method === "POST") {
        if (typeof key !== "string" || !UUID_RE.test(key)) return err(res, 400, "INVALID_REQUEST", "Idempotency-Key must be a UUID");
        const b = await readJson(req);
        if (!b || Object.keys(b).join() !== "expectedFindingVersion" || b.expectedFindingVersion !== row.version) return err(res, b?.expectedFindingVersion === undefined ? 400 : 409, b?.expectedFindingVersion === undefined ? "INVALID_REQUEST" : "VERSION_CONFLICT", "Bad version", { currentVersion: row.version });
        const prior = evidenceSets.get(key);
        const set = prior ?? { id: randomUUID(), at: new Date().toISOString(), findingId: row.id };
        evidenceSets.set(key, set);
        return send(res, prior ? 200 : 201, { data: { evidenceSetId: set.id, state: "UPLOADING", initialEvidenceRevision: 0, createdAt: set.at }, meta: { requestId: randomUUID(), traceId: randomUUID(), schemaVersion: "1" } }, { "Cache-Control": "no-store" });
      }
      if (kind === "uploads" && !intentId && req.method === "POST") {
        if (typeof key !== "string" || !UUID_RE.test(key)) return err(res, 400, "INVALID_REQUEST", "Idempotency-Key must be a UUID");
        const b = await readJson(req);
        const ok = b && ["evidenceSetId", "expectedFindingVersion", "expectedEvidenceRevision", "filename", "declaredMediaType", "declaredSizeBytes"].every((k) => k in b) && Object.keys(b).length === 6;
        if (!ok) return err(res, 400, "INVALID_REQUEST", "Declaration must have exactly six fields");
        if (![...evidenceSets.values()].some((x) => x.id === b.evidenceSetId)) return err(res, 404, "RESOURCE_NOT_VISIBLE", "Not visible");
        if (b.expectedFindingVersion !== row.version) return err(res, 409, "VERSION_CONFLICT", "Stale", { currentVersion: row.version });
        if (!["image/png", "image/jpeg"].includes(b.declaredMediaType)) return err(res, 415, "INVALID_REQUEST", "Media type not allowed");
        if (!Number.isInteger(b.declaredSizeBytes) || b.declaredSizeBytes < 1 || b.declaredSizeBytes > 67108864) return err(res, 413, "INVALID_REQUEST", "Too large");
        const prior = uploads.get(key);
        const u = prior ?? { id: randomUUID(), state: "INTENT_CREATED", size: b.declaredSizeBytes, type: b.declaredMediaType, name: b.filename, setId: b.evidenceSetId, expiresAt: new Date(Date.now() + 3600_000).toISOString(), polls: 0 };
        uploads.set(key, u);
        return send(res, prior ? 200 : 201, { ...view(u), data: { ...view(u).data, statusUrl: `${path}/${u.id}` } }, { "Cache-Control": "no-store", Location: `${path}/${u.id}` });
      }
      const intent = [...uploads.values()].find((u) => u.id === intentId?.toLowerCase());
      if (kind === "uploads" && intentId && !intent) return err(res, 404, "RESOURCE_NOT_VISIBLE", "Not visible");
      if (intent && action === "content" && req.method === "PUT") {
        if (intent.received) return err(res, 409, "ALREADY_RECEIVED", "Already received", {}, { Location: `${path.replace(/\/content$/, "")}` });
        if (req.headers["content-type"] !== intent.type) return err(res, 415, "INVALID_REQUEST", "Content-Type must equal the declared type");
        const chunks = [];
        for await (const chunk of req) chunks.push(chunk);
        if (Buffer.concat(chunks).length !== intent.size || Number(req.headers["content-length"]) !== intent.size) return err(res, 400, "INVALID_REQUEST", "Length differs from declaration");
        intent.received = true;
        intent.state = "QUARANTINED";
        return send(res, 202, view(intent), { "Cache-Control": "no-store" });
      }
      if (intent && !action && req.method === "GET") {
        // 每次查询推进一步，模拟异步扫描：QUARANTINED → SCANNING → 终态（文件名以 reject 开头则被拒绝）。
        if (intent.received && intent.state === "QUARANTINED") intent.state = "SCANNING";
        else if (intent.state === "SCANNING") intent.state = /^reject/i.test(intent.name) ? "REJECTED" : "VERIFIED_PENDING_FINALIZATION";
        return send(res, 200, view(intent), { "Cache-Control": "no-store" });
      }
      if (intent && action === "cancel" && req.method === "POST") {
        if (!["VERIFIED_PENDING_FINALIZATION", "REJECTED", "FAILED", "CANCELLED", "EXPIRED"].includes(intent.state)) intent.cancel = true;
        return send(res, 200, view(intent), { "Cache-Control": "no-store" });
      }
      return err(res, 404, "RESOURCE_NOT_VISIBLE", "Not visible");
    }
    const candidates = /^\/api\/v1\/findings\/([^/]+)\/dispatch-candidates$/.exec(path);
    if (req.method === "GET" && candidates) {
      const row = findings.find((r) => r.id === decodeURIComponent(candidates[1]));
      if (!row) return err(res, 404, "RESOURCE_NOT_VISIBLE", "Not visible");
      const allowed = new Set(["q", "limit", "afterMemberId"]);
      const keys = [...url.searchParams.keys()];
      const q = url.searchParams.get("q") ?? "";
      const limitText = url.searchParams.get("limit");
      const after = url.searchParams.get("afterMemberId");
      const limit = limitText === null ? 20 : /^[1-9][0-9]*$/.test(limitText) ? Number(limitText) : NaN;
      if (keys.some((k) => !allowed.has(k)) || new Set(keys).size !== keys.length || cp(q) > 100 || !(limit >= 1 && limit <= 50) || (after !== null && !UUID_RE.test(after)))
        return err(res, 400, "INVALID_QUERY", "Invalid query");
      if (!(row.state === "SAFETY_DISPATCH_PENDING" || (row.state === "RECTIFYING" && !row[ASSIGNED]))) return err(res, 409, "COMMAND_CONFLICT", "Not awaiting dispatch");
      const eligible = MEMBERS.filter((m) => m.displayName.startsWith(q) && (after === null || m.memberId > after.toLowerCase()));
      const pageItems = eligible.slice(0, limit);
      const hasMore = eligible.length > limit;
      return send(res, 200, { data: pageItems, findingVersion: row.version, roundNo: row.roundNo, state: row.state, hasMore, nextAfterMemberId: hasMore ? pageItems.at(-1).memberId : null }, { "Cache-Control": "no-store" });
    }
    const detail = /^\/api\/v1\/entities\/finding\/([^/]+)$/.exec(path);
    if (req.method === "GET" && detail) {
      const row = visibleFindings.find((r) => r.id === decodeURIComponent(detail[1]));
      return row ? send(res, 200, { data: row, meta: meta() }) : err(res, 404, "RESOURCE_NOT_VISIBLE", "Not visible");
    }

    // Legacy browser fixture only: one mock account represents both review roles.
    // These hints are not evidence of real server authorization or assignment.
    const reviewHistory = /^\/api\/v1\/findings\/([^/]+)\/review-history$/.exec(path);
    if (req.method === "GET" && reviewHistory) {
      const row = visibleFindings.find(r => r.id === decodeURIComponent(reviewHistory[1]));
      if (!row) return err(res, 404, "RESOURCE_NOT_VISIBLE", "Not visible");
      if (session.mockReviewRole && cycleFixture) return send(res, 200, cycleFixture.history(session));
      const stage = row.state === "PERSON_CONFIRM_PENDING" ? "PERSON" : row.state === "SAFETY_CONFIRM_PENDING" ? "SAFETY" : null;
      return send(res, 200, { data: {
        entries: reviewEntries.get(row.id) ?? [], currentVersion: row.version, currentState: row.state,
        reviewCycle: row.reviewCycle, reviewProtocol: row.reviewProtocol,
        processDefinitionKey: row.state === "DISCOVERED" ? null : "findingRemediationV2", reviewStage: stage,
        availableReviewActions: stage ? ["APPROVE", "NOT_ESTABLISHED"] : [], blockedReason: stage ? null : "NO_REVIEW_ACTION_AT_CURRENT_STATE",
        hasMore: false, nextCursor: null,
      }, meta: meta() });
    }

    const cycleCommand = /^\/api\/v1\/findings\/([^/]+)\/(review-decisions|supplements)$/.exec(path);
    if (req.method === "POST" && cycleCommand) {
      if (!cycleFixture || !session.mockReviewRole || cycleCommand[1] !== cycleFixture.finding.id) return err(res, 404, "RESOURCE_NOT_VISIBLE", "Not visible");
      const result = cycleFixture.command(session, path, await readJson(req), req.headers["idempotency-key"]);
      return send(res, result.status, result.body);
    }

    if (req.method === "POST" && path === "/api/v1/findings") {
      const key = req.headers["idempotency-key"];
      if (typeof key !== "string" || !/^[0-9a-f-]{36}$/i.test(key)) return err(res, 400, "INVALID_REQUEST", "Idempotency-Key must be a UUID");
      const b = await readJson(req);
      if (!b || typeof b !== "object") return err(res, 400, "INVALID_REQUEST", "Malformed body");
      const canonical = JSON.stringify(b);
      const prior = receipts.get(key);
      if (prior) return prior.body === canonical ? send(res, 200, prior.response) : err(res, 409, "IDEMPOTENCY_CONFLICT", "Same key, different request");
      const fieldErrors = validateCreate(b);
      if (fieldErrors.length) return err(res, 400, "INVALID_REQUEST", "Validation failed", { fieldErrors });
      if (b.relatedFindingId !== undefined) {
        const parent = typeof b.relatedFindingId === "string" && UUID_RE.test(b.relatedFindingId) ? findings.find((r) => r.id === b.relatedFindingId.toLowerCase()) : undefined;
        if (!parent) return err(res, 404, "RESOURCE_NOT_VISIBLE", "Not visible");
        if (parent.state !== "NOT_ESTABLISHED") return err(res, 422, "WORKFLOW_RULE", "Parent must be NOT_ESTABLISHED");
      }
      const row = addFinding(b);
      const response = { data: { findingId: row.id, code: row.code, state: row.state, roundNo: row.roundNo, version: row.version } };
      receipts.set(key, { body: canonical, response });
      return send(res, 200, response);
    }
    const command = /^\/api\/v1\/findings\/([^/]+)\/(workflow-initialization|confirm\/person|confirm\/safety|close-not-established|dispatch|verifications)$/.exec(path);
    if (req.method === "POST" && command) {
      const key = req.headers["idempotency-key"];
      if (typeof key !== "string" || !/^[0-9a-f-]{36}$/i.test(key)) return err(res, 400, "INVALID_REQUEST", "Idempotency-Key must be a UUID");
      const b = await readJson(req);
      if (!b || typeof b !== "object") return err(res, 400, "INVALID_REQUEST", "Malformed body");
      const row = visibleFindings.find((r) => r.id === decodeURIComponent(command[1]));
      if (!row) return err(res, 404, "RESOURCE_NOT_VISIBLE", "Not visible");
      if (row.reviewProtocol === "CYCLE_V1") return err(res, 409, "COMMAND_CONFLICT", "MOCK V3 requires cycle commands");
      const receiptKey = `${key}`;
      const canonical = JSON.stringify([path, b]);
      const prior = receipts.get(receiptKey);
      if (prior) return prior.body === canonical ? send(res, 200, prior.response) : err(res, 409, "IDEMPOTENCY_CONFLICT", "Same key, different request");

      const fieldErrors = [];
      if (!Number.isInteger(b.expectedVersion) || b.expectedVersion < 1) fieldErrors.push({ field: "expectedVersion", reason: "Min" });
      const reasonOk = typeof b.reason === "string" && cp(b.reason) <= 4000 && b.reason.trim().length > 0;
      const op = command[2];
      if (op === "confirm/person" && !reasonOk) fieldErrors.push({ field: "reason", reason: "NotBlank" });
      if (op === "confirm/safety") {
        if (!reasonOk) fieldErrors.push({ field: "reason", reason: "NotBlank" });
        if (!["GENERAL", "MAJOR"].includes(b.grade)) fieldErrors.push({ field: "grade", reason: "Pattern" });
      }
      if (op === "close-not-established") {
        if (!["PERSON", "SAFETY"].includes(b.stage)) fieldErrors.push({ field: "stage", reason: "Pattern" });
        if (!reasonOk) fieldErrors.push({ field: "reason", reason: "NotBlank" });
      }
      if (op === "dispatch") {
        if (!MEMBERS.some((m) => m.memberId === b.assigneeMemberId)) fieldErrors.push({ field: "assigneeMemberId", reason: "NotInScope" });
        if (typeof b.dueAt !== "string" || Number.isNaN(Date.parse(b.dueAt))) fieldErrors.push({ field: "dueAt", reason: "Pattern" });
        if (typeof b.requirements !== "string" || cp(b.requirements) > 4000 || b.requirements.trim().length === 0) fieldErrors.push({ field: "requirements", reason: "NotBlank" });
      }
      if (op === "verifications") {
        if (!["PASS", "FAIL"].includes(b.decision)) fieldErrors.push({ field: "decision", reason: "Pattern" });
        else if (b.decision === "FAIL" && !reasonOk) fieldErrors.push({ field: "reason", reason: "NotBlank" });
      }
      if (fieldErrors.length) return err(res, 400, "INVALID_REQUEST", "Validation failed", { fieldErrors });
      if (b.expectedVersion !== row.version) return err(res, 409, "VERSION_CONFLICT", "Stale version", { currentVersion: row.version });

      const from = { "workflow-initialization": "DISCOVERED", "confirm/person": "PERSON_CONFIRM_PENDING", "confirm/safety": "SAFETY_CONFIRM_PENDING", verifications: "ACCEPTANCE_PENDING" }[op];
      const stateOk =
        op === "close-not-established"
          ? row.state === (b.stage === "PERSON" ? "PERSON_CONFIRM_PENDING" : "SAFETY_CONFIRM_PENDING")
          : op === "dispatch"
            ? row.state === "SAFETY_DISPATCH_PENDING" || (row.state === "RECTIFYING" && !row[ASSIGNED])
            : row.state === from;
      if (!stateOk) return err(res, 409, "COMMAND_CONFLICT", "State does not allow this command");
      if (op === "workflow-initialization") row.state = "PERSON_CONFIRM_PENDING";
      else if (op === "confirm/person") row.state = "SAFETY_CONFIRM_PENDING";
      else if (op === "confirm/safety") { row.state = "SAFETY_DISPATCH_PENDING"; row.grade = b.grade; }
      else if (op === "close-not-established") row.state = "NOT_ESTABLISHED";
      else if (op === "dispatch") { row.state = "RECTIFYING"; row[ASSIGNED] = true; }
      else if (b.decision === "PASS") row.state = "CLOSED";
      else { row.state = "RECTIFYING"; row.roundNo += 1; row[ASSIGNED] = false; }
      if (op === "confirm/person" || op === "confirm/safety") {
        const stage = op === "confirm/person" ? "PERSON" : "SAFETY";
        reviewEntries.set(row.id, [...(reviewEntries.get(row.id) ?? []), {
          id: `legacy-confirmation:${row.id}:${stage}`, origin: "LEGACY_CONFIRMATION", reviewCycle: 1,
          stage, decision: "APPROVE", actorMemberId: session.memberId,
          actorDisplay: { label: null, provenance: "UNAVAILABLE" }, occurredAt: new Date().toISOString(),
          text: b.reason, recordedGrade: null, nextReviewCycle: null,
        }]);
      }
      row.version += 1;
      row.updatedAt = new Date().toISOString();
      const response = { data: { findingId: row.id, code: row.code, state: row.state, roundNo: row.roundNo, version: row.version } };
      receipts.set(receiptKey, { body: canonical, response });
      return send(res, 200, response);
    }
    return err(res, 404, "RESOURCE_NOT_VISIBLE", "Not found");
  });
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const port = Number(process.env.PORT ?? 8080);
  createMockServer({ reviewCycles: process.env.MOCK_REVIEW_CYCLES === "1" }).listen(port, "127.0.0.1", () => console.log(`contract mock listening on http://127.0.0.1:${port} (demo/demo/demo)`));
}

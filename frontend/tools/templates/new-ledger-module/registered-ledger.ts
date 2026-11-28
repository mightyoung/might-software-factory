import type { ApplicationEntry, MenuNode } from "../../../src/domain/session";
import { IDENTIFIER, PERMISSION } from "../../../src/features/ledger/model";
import { tenantPath } from "../../../src/routing/paths";

interface RegistrationExample {
  appKey: string;
  appName: string;
  entityKey: string;
  label: string;
  readPermission: string;
}

/** Shapes for a synthetic contract fixture. Only the server may grant access or register an HTTP entity. */
export function createLedgerRegistration({ appKey, appName, entityKey, label, readPermission }: RegistrationExample) {
  if (!IDENTIFIER.test(appKey) || !IDENTIFIER.test(entityKey)) throw new Error("使用合法的应用和台账标识");
  if (!PERMISSION.test(readPermission)) throw new Error("使用服务端登记的权限代码");
  const path = `apps/${appKey}/entities/${entityKey}`;
  return {
    entry: { id: appKey, name: appName, entryPath: path } satisfies ApplicationEntry,
    menu: { id: entityKey, label, path, permission: readPermission } satisfies MenuNode,
    href: (tenantId: string) => tenantPath(tenantId, path),
  };
}

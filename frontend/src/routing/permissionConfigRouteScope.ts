import { createContext } from "react";
import type { PermissionAdminAdapter } from "../adapters/permissionAdmin";

export interface PermissionConfigRouteProps {
  adapter: PermissionAdminAdapter;
  canManage: boolean;
  onUnauthenticated(): void;
  onPermissionsChanged?(): Promise<void>;
}

/** Supplied only after the matched settings route passes the existing authorization gate. */
export const PermissionConfigRouteScope = createContext<PermissionConfigRouteProps | null>(null);

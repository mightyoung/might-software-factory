import { createContext } from "react";

/** Supplied only inside the authorized, identity-keyed settings view. */
export const LedgerConfigRouteScope = createContext<{
  active: boolean;
  canManage: boolean;
  canPublish: boolean;
  onUnauthenticated(): void;
} | null>(null);

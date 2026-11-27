import { createContext } from "react";

/** Only the authorized, identity-keyed workbench renderer supplies this route scope. */
export const PublishedLedgerRouteScope = createContext<{
  active: boolean;
  onUnauthenticated(): void;
} | null>(null);

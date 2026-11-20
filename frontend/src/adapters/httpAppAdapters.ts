import { createHttpClient } from "../api/httpClient";
import { createHttpLoans } from "../features/toolLoans/model";
import { createHttpEntityLedger } from "../features/ledger/httpEntityLedger";
import { createHttpPermissionAdmin } from "./httpPermissionAdmin";
import { createHttpFindingCommands } from "./httpFindingCommands";
import { createHttpFindingEvidence } from "./httpFindingEvidence";
import { createHttpFindingLedger } from "./httpFindingLedger";
import { createHttpFindingSubmit } from "./httpFindingSubmit";
import { createHttpSessionAdapter } from "./httpSessionAdapter";
import type { ComponentProps } from "react";
import type { AppCore } from "../App";

export function createHttpAppSession() {
  return createHttpSessionAdapter(createHttpClient());
}

export const httpAppAdapterFactories: Omit<ComponentProps<typeof AppCore>, "mode" | "sessionAdapter"> = {
  createFindingStore: () => {
    const client = createHttpClient();
    return {
      ledger: createHttpFindingLedger(client),
      submit: createHttpFindingSubmit(client),
      commands: createHttpFindingCommands(client),
      evidence: createHttpFindingEvidence(client),
    };
  },
  createPermissionAdmin: () => createHttpPermissionAdmin(createHttpClient()),
  createLoans: () => createHttpLoans(createHttpClient()),
  createEntityLedger: (app, entity) => createHttpEntityLedger(createHttpClient(), app, entity),
};

export function createHttpAppAdapters(): Omit<ComponentProps<typeof AppCore>, "mode"> {
  return { sessionAdapter: createHttpAppSession(), ...httpAppAdapterFactories };
}

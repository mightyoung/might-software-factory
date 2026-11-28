import { syntheticPresentation } from "./syntheticPresentation";
import { syntheticSessionAdapter } from "./sessionAdapter";
import { syntheticFindingStore } from "./syntheticFindingStore";
import { createSyntheticPermissionAdmin } from "./syntheticPermissionAdmin";

export function createSyntheticAppAdapters() {
  return {
    demoPresentation: syntheticPresentation,
    sessionAdapter: syntheticSessionAdapter,
    createFindingStore: () => syntheticFindingStore,
    createPermissionAdmin: createSyntheticPermissionAdmin,
  };
}

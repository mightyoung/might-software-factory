import { syntheticSessionAdapter } from "./sessionAdapter";
import { syntheticFindingStore } from "./syntheticFindingStore";
import { createSyntheticPermissionAdmin } from "./syntheticPermissionAdmin";

export function createSyntheticAppAdapters() {
  return {
    sessionAdapter: syntheticSessionAdapter,
    createFindingStore: () => syntheticFindingStore,
    createPermissionAdmin: createSyntheticPermissionAdmin,
  };
}

import { findingLedgerSchema } from "../features/findings/schema";
import { validateFindingDraft } from "../domain/findingDraft";
import {
  SyntheticFindingFields,
  SyntheticFindingActions,
  SyntheticFindingNotice,
} from "../components/SyntheticFindingDetails";
import { DemoLoginAccounts } from "../features/session/DemoLoginAccounts";

export const syntheticFindingPresentation = {
  schema: findingLedgerSchema,
  validateDraft: validateFindingDraft,
  Fields: SyntheticFindingFields,
  Actions: SyntheticFindingActions,
  Notice: SyntheticFindingNotice,
};

export const syntheticPresentation = {
  finding: syntheticFindingPresentation,
  LoginAccounts: DemoLoginAccounts,
};

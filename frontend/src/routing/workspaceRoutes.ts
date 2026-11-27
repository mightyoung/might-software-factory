import { createRootRoute, createRoute, createRouter } from "@tanstack/react-router";
import { parseEntityRoute } from "./entityPaths";
import { findingSearchInput, validateFindingSearch, type FindingSearch } from "./findingSearch";
import { tenantPath } from "./paths";
import { getWorkspaceHistory } from "./router";

const findingPaths = new Set(["safety/findings", "safety/inbox", "safety/acceptance"]);

export function createWorkspaceRouteMatcher<T extends { rest: string }>(definitions: readonly T[]) {
  const rootRoute = createRootRoute();
  const tenantRoute = createRoute({ getParentRoute: () => rootRoute, path: "t/$tenant", caseSensitive: true });
  const staticRoutes = definitions.map((definition) => createRoute({
    getParentRoute: () => tenantRoute, path: definition.rest, caseSensitive: true,
    ...(findingPaths.has(definition.rest) && {
      validateSearch: (input: Record<string, unknown>) => ({ finding: validateFindingSearch(input) }),
    }),
  }));
  const entityPath = "apps/$app/entities/$entity";
  const entityRoutes = [entityPath, `${entityPath}/create`, `${entityPath}/records/$record`, `${entityPath}/records/$record/edit`].map((path) =>
    createRoute({ getParentRoute: () => tenantRoute, path, caseSensitive: true }),
  );
  const routeTree = rootRoute.addChildren([tenantRoute.addChildren([...staticRoutes, ...entityRoutes])]);
  const makeRouter = () => createRouter({ routeTree, history: getWorkspaceHistory(), trailingSlash: "preserve" });
  let router: ReturnType<typeof makeRouter> | undefined;
  const getRouter = () => (router ??= makeRouter());

  return {
    findingSearch(tenant: string, rest: string, search: string): FindingSearch | undefined {
      if (!findingPaths.has(rest)) return;
      // Bypass the default search JSON coercion: the legacy contract chooses the first literal value.
      return getRouter().matchRoutes(tenantPath(tenant, rest), findingSearchInput(search)).at(-1)?.search.finding;
    },
    match(tenant: string, rest: string): { route: T | undefined; entityRoute: ReturnType<typeof parseEntityRoute> } {
      const pathname = tenantPath(tenant, rest);
      // Router matching decodes params and fuzzy remainders. A malformed escape
      // is an unknown route, not a reason to crash the workbench.
      try { decodeURI(pathname); } catch { return { route: undefined, entityRoute: null }; }
      const [, params, found] = getRouter().getMatchedRoutes(pathname);
      if (!found || params["**"]) return { route: undefined, entityRoute: null };
      const route = definitions[staticRoutes.findIndex((candidate) => candidate === found)];
      // Keep the former raw, case-sensitive contract rather than accepting aliases.
      if (route?.rest === rest) return { route, entityRoute: null };
      return { route: undefined, entityRoute: entityRoutes.some((candidate) => candidate === found) ? parseEntityRoute(rest) : null };
    },
  };
}

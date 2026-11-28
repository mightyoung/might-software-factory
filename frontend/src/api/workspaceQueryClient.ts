import { QueryClient } from "@tanstack/react-query";

/** Call only inside a session/adapter boundary. Reads are never persisted or retried without intent. */
export function createWorkspaceQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { gcTime: 0, networkMode: "always", retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false },
    },
  });
}

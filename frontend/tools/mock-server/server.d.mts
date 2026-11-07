export interface MockServer {
  listen(port: number, host: string, callback: () => void): unknown;
  close(callback?: () => void): unknown;
  address(): { port: number } | string | null;
}
export interface MockNavigation {
  enabled: boolean | null;
  permissions: string[];
  failure?: number;
}
export function createMockServer(options?: { navigation?: () => MockNavigation; reviewCycles?: boolean }): MockServer;

export interface MockServer {
  listen(port: number, host: string, callback: () => void): unknown;
  close(callback?: () => void): unknown;
  address(): { port: number } | string | null;
}
export function createMockServer(): MockServer;

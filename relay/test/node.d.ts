// The few Node APIs the vector test uses. Declared here rather than pulling in
// @types/node, whose globals clash with the Workers types the relay compiles against.
declare module "node:fs" {
  export function readFileSync(path: string, encoding: "utf8"): string;
  export function writeFileSync(path: string, data: string): void;
}
declare module "node:url" {
  export function fileURLToPath(url: URL): string;
}
declare const process: { env: Record<string, string | undefined> };

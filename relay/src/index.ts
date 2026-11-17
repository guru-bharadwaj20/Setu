// Setu relay Worker entry. Only the default export may live here; the routes
// are in app.ts so tests can import them.

import { handle, type Deps } from "./app";
import { fromHex, signerFromSeed, type Signer } from "./packet";
import { D1Store } from "./store";

interface Env {
  DB: D1Database;
  RATE_LIMIT_PER_MINUTE?: string;
  RELAY_SEED?: string;
  RESCUER_TOKEN?: string;
}

let cachedSigner: { seed: string; signer: Signer } | null = null;

async function depsFromEnv(env: Env): Promise<Deps> {
  let signer: Signer | null = null;
  if (env.RELAY_SEED) {
    if (cachedSigner?.seed !== env.RELAY_SEED) cachedSigner = { seed: env.RELAY_SEED, signer: await signerFromSeed(fromHex(env.RELAY_SEED)) };
    signer = cachedSigner.signer;
  }
  return {
    store: new D1Store(env.DB),
    now: () => Math.floor(Date.now() / 1000),
    rateLimit: Number(env.RATE_LIMIT_PER_MINUTE ?? "60"),
    signer,
    rescuerToken: env.RESCUER_TOKEN ?? null,
  };
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    return handle(req, await depsFromEnv(env));
  },

  async scheduled(_event: ScheduledEvent, env: Env): Promise<void> {
    const d = await depsFromEnv(env);
    const now = d.now();
    await d.store.purge(now, Math.floor(now / 60) - 2);
  },
};

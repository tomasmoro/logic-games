// =============================================================================
// Edge Function: game-percentile
// -----------------------------------------------------------------------------
// Endpoint HTTP fino sobre el RPC public.get_score_percentile.
// El cálculo pesado vive en PostgreSQL (usa el índice game_id, score); esta
// función solo: valida JWT, valida input y reenvía la identidad del usuario.
//
// Se reenvía el header Authorization del llamador a supabase-js, de modo que
// el RPC se ejecuta con el rol `authenticated` (respetando GRANTs y RLS).
//
// POST /functions/v1/game-percentile
// body: { "game_id": "uuid", "score": 1234 }
// resp: { "better_than_pct": 87.5, "total_players": 1042, "rank": 131 }
// =============================================================================
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "Método no permitido" }, 405);

  const authHeader = req.headers.get("Authorization");
  if (!authHeader) return json({ error: "Falta Authorization" }, 401);

  // Validación de input
  let payload: { game_id?: string; score?: number };
  try {
    payload = await req.json();
  } catch {
    return json({ error: "JSON inválido" }, 400);
  }

  const { game_id, score } = payload;
  const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  if (!game_id || !uuidRe.test(game_id)) {
    return json({ error: "game_id inválido" }, 400);
  }
  if (typeof score !== "number" || !Number.isFinite(score) || score < 0) {
    return json({ error: "score inválido" }, 400);
  }

  // Cliente con la identidad del usuario (RLS + GRANTs se aplican)
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader } } },
  );

  const { data, error } = await supabase
    .rpc("get_score_percentile", { p_game_id: game_id, p_score: Math.trunc(score) })
    .single();

  if (error) return json({ error: error.message }, 400);

  return json(data);
});

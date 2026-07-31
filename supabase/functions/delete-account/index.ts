// =============================================================================
// Edge Function: delete-account
// -----------------------------------------------------------------------------
// Borra de forma permanente la cuenta del usuario que llama. El cliente NUNCA
// tiene `service_role` (por eso esto no puede vivir en el cliente): se valida
// el JWT del llamador con la clave anónima y, solo tras identificarlo, se usa
// un cliente `service_role` para borrar su propia fila de auth.users.
//
// El ON DELETE CASCADE del esquema (public.users → auth.users, y todo lo que
// referencia public.users.id: progreso, logros, tiempos por nivel…) se encarga
// de limpiar el resto en una sola operación.
//
// POST /functions/v1/delete-account
// body: (vacío)
// resp: { "deleted": true }
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

  // 1) Identifica al llamador con su propio JWT (respeta RLS, sin privilegios extra).
  const callerClient = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader } } },
  );
  const { data: userData, error: userError } = await callerClient.auth.getUser();
  if (userError || !userData.user) {
    return json({ error: "Sesión inválida" }, 401);
  }

  // 2) Borra con `service_role`, y SOLO el id ya verificado del propio llamador.
  const adminClient = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );
  const { error: deleteError } = await adminClient.auth.admin.deleteUser(userData.user.id);
  if (deleteError) return json({ error: deleteError.message }, 400);

  return json({ deleted: true });
});

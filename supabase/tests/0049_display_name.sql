-- =============================================================================
-- Test de la validación de `display_name` (migración 0049)
-- -----------------------------------------------------------------------------
-- CÓMO SE EJECUTA
-- Contra la BD real, tal cual: TODO va dentro de una transacción que termina en
-- ROLLBACK, así que no deja rastro (ni nombres cambiados ni patrones nuevos).
-- Desde el MCP de Supabase: `execute_sql` con este archivo entero.
--
-- QUÉ COMPRUEBA
--   1. La tabla de casos: cada nombre y el veredicto que DEBE dar la RPC.
--   2. Que el UPDATE directo a PostgREST está cerrado (el revoke de la 0049).
--
-- CUÁNDO HAY QUE VOLVER A CORRERLO
-- Siempre que se añada un patrón a `blocked_display_name_patterns`. Cada patrón
-- nuevo puede llevarse por delante nombres legítimos: `pene` como subcadena
-- rechazaba "Penelope", y `nazi` rechazaba "Nazir". Los casos de la sección
-- "legítimos" existen justamente para que eso salte aquí y no en una reseña de
-- una estrella.
-- =============================================================================

begin;

-- La RPC lee `auth.uid()`. Se suplanta a un usuario real cualquiera; el UPDATE
-- que haga la RPC se deshace con el ROLLBACK del final.
select set_config(
    'request.jwt.claims',
    json_build_object(
        'sub',  (select id::text from public.users order by created_at limit 1),
        'role', 'authenticated'
    )::text,
    true
);

set local role authenticated;

with casos(nombre, espera_ok, espera_motivo) as (values
    -- Legítimos: NO se pueden rechazar. Los tres últimos son los falsos positivos
    -- que tenía la lista de la 0044 y que la 0049 corrigió con `whole_word`.
    ('Tomi',                 true,  null),
    ('Ana María',            true,  null),          -- espacio interior permitido
    ('El_Kraken_99',         true,  null),
    ('Ñoño',                 true,  null),
    ('  Tomi  ',             true,  null),          -- se recorta, no se rechaza
    ('Penelope',             true,  null),          -- contiene "pene"
    ('Nazir',                true,  null),          -- contiene "nazi"
    ('Conocedor',            true,  null),          -- contiene "cono"
    -- Forma y longitud
    ('ab',                   false, 'too_short'),
    (repeat('X', 30),        false, 'too_long'),
    ('Tomi  doble',          false, 'invalid_chars'),   -- espacio doble
    ('🎮Neon',               false, 'invalid_chars'),
    ('<b>hola</b>',          false, 'invalid_chars'),
    ('Кortex',               false, 'invalid_chars'),   -- К cirílica: fuera del alfabeto
    -- Reservados (suplantar a la marca o al soporte)
    ('admin',                false, 'blocked'),
    ('4dm1n',                false, 'blocked'),         -- leet: clave "admin"
    ('K0RT3X',               false, 'blocked'),         -- leet: clave "kortex"
    ('KortexSupport',        false, 'blocked'),
    -- Ofensivos, incluidas las variantes que solo caza la normalización
    ('PutaMadre',            false, 'blocked'),
    ('P_U_T_A',              false, 'blocked'),         -- separadores: clave "puta"
    ('sh1t',                 false, 'blocked'),         -- leet: clave "shit"
    ('Hitler88',             false, 'blocked'),
    ('Pene',                 false, 'blocked'),         -- palabra completa
    ('P e n e',              false, 'blocked'),         -- espacios: clave "pene"
    ('Coño',                 false, 'blocked')
)
select
    c.nombre,
    coalesce(r.res->>'reason', '—')                                   as motivo_obtenido,
    coalesce(c.espera_motivo, '—')                                    as motivo_esperado,
    case
        when (r.res->>'ok')::boolean = c.espera_ok
         and coalesce(r.res->>'reason', '') = coalesce(c.espera_motivo, '')
        then 'PASA'
        else 'FALLA'
    end                                                               as veredicto
from casos c
-- LATERAL para llamar a la RPC UNA sola vez por caso (no una por columna leída).
cross join lateral (select public.set_display_name(c.nombre) as res) r
order by veredicto desc, c.nombre;

-- -----------------------------------------------------------------------------
-- La otra mitad del arreglo: que la RPC sea el ÚNICO camino
-- -----------------------------------------------------------------------------
-- Sin esto, la validación de arriba es solo el camino educado: un PATCH a
-- /rest/v1/users se saltaría la blocklist entera. Debe devolver 'PASA'.
create or replace function pg_temp.intento_patch_directo() returns text language plpgsql as $fn$
begin
    update public.users set display_name = 'ColadoPorLaPuerta' where id = auth.uid();
    return 'FALLA · el UPDATE directo pasó: el revoke de la 0049 no está aplicado';
exception
    when insufficient_privilege then return 'PASA · ' || sqlerrm;
end;
$fn$;

select 'UPDATE directo a public.users' as caso, pg_temp.intento_patch_directo() as veredicto;

rollback;

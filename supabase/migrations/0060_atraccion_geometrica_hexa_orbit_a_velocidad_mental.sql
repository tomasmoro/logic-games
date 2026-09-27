-- =============================================================================
-- Recategoriza "Atracción Geométrica" (Polarity Collision) y "Hexa Orbit" de
-- Visión Espacial (`spatial`) a Velocidad Mental (`mental_speed`).
--
-- Encajan mejor ahí: ambos son de reacción rápida bajo presión de tiempo
-- creciente (partículas/velocidad del puntero acelerando) más que de
-- orientación/rotación espacial, que es lo que caracteriza al resto de la
-- categoría (Hyper Cubo, Quantum Merge, Flujo de Energía...).
--
-- Solo cambia `category_id`; slug, nombre, descripción y `engine_config` de
-- cada juego quedan igual. Coincide con `GameCategory.MENTAL_SPEED` en
-- `GameCatalog.kt` para POLARITY_COLLISION y HEXA_ORBIT.
-- =============================================================================

update public.games
set category_id = (select id from public.categories where slug = 'mental_speed')
where id in (
    '66666666-6666-4666-8666-666666666666', -- Atracción Geométrica / Polarity Collision
    'a9f2c86b-3e74-4d51-9c08-6b1d40e7a25f'   -- Hexa Orbit
);

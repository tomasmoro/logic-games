-- =============================================================================
-- Ajuste de balance de "Quantum Merge": esferas un 30% más grandes.
--
-- La escala de radios del cliente (`QuantumTier`) se multiplicó por 1,3 —petición
-- de diseño: más presencia en pantalla— y con ella el carril superior del
-- contenedor, para conservar la proporción entre la esfera mayor que entrega el
-- dispensador y la boca por la que entra.
--
-- Esta migración solo pone al día el `engine_config` sembrado en 0031, que
-- documenta esa geometría para poder afinarla desde backend en el futuro: dejar
-- `dangerLineY = 18` cuando el juego ya usa 23.4 sería una mentira que el
-- siguiente que lea la tabla daría por buena. El cliente sigue siendo la fuente
-- de verdad y no lee este JSON.
--
-- Idempotente: `||` fusiona claves, así que re-ejecutarla no cambia nada.
-- =============================================================================

update public.games
set engine_config = engine_config || '{"dangerLineY": 23.4, "dropY": 10.4, "tierRadiusScale": 1.3}'::jsonb,
    updated_at = now()
where slug = 'quantum_merge';

-- =============================================================================
-- 0032 — Neon Pulse pasa a ser una partida INFINITA POR HORDAS
-- -----------------------------------------------------------------------------
-- El juego ya no es una corrida de 30 s con vidas: ahora se juega hasta agotar
-- las vidas y el contenido llega en hordas cada vez más exigentes (más nodos,
-- soltados más seguido y encendidos menos tiempo, con topes prefijados para que
-- siga siendo jugable). Desde la horda 6 los nodos se desplazan y cada 5 hordas
-- puede caer un corazón que devuelve una vida si al jugador le falta alguna.
--
-- Esta migración solo actualiza la fila ya sembrada en 0019: su descripción (que
-- seguía anunciando "corrida por tiempo") y su `engine_config` documental, que
-- espeja las constantes de `NeonPulseModel.kt` / `WaveSpec.forWave` — el cliente
-- sigue siendo la fuente de verdad del balance. No se reescribe 0019 porque ya
-- está aplicada (CLAUDE.md §5).
--
-- No toca `user_progress`: las partidas antiguas siguen siendo válidas: el
-- puntaje se compara igual y `reached_metric`, que antes guardaba la mejor racha,
-- ahora guarda la horda alcanzada (ambas "más alto es mejor", así que el ranking
-- no se rompe).
-- =============================================================================

update public.games
set
    description = 'Partida infinita por hordas: toca los nodos de energía antes de que su anillo de tiempo colapse y deja apagarse solos a los nodos trampa. Cada horda llega más rápida y con menos margen, desde la sexta los nodos se mueven y cada cinco hordas puede aparecer un corazón que devuelve una vida. Solo terminas cuando te quedas sin vidas.',
    engine_config = '{
        "endless": true,
        "initialLives": 3,
        "maxLives": 5,
        "pointsPerHit": 100,
        "waveClearBonus": 50,
        "waveBaseNodes": 6,
        "waveNodesStep": 1,
        "waveMaxNodes": 20,
        "spawnIntervalStartMs": 1500,
        "spawnIntervalStepMs": 80,
        "spawnIntervalMinMs": 380,
        "nodeLifeStartMs": 1700,
        "nodeLifeStepMs": 70,
        "nodeLifeMinMs": 620,
        "trapUnlockWave": 3,
        "trapChanceStart": 0.10,
        "trapChanceStep": 0.025,
        "trapChanceMax": 0.32,
        "moveUnlockWave": 6,
        "moveSpeedStart": 0.06,
        "moveSpeedStep": 0.018,
        "moveSpeedMax": 0.30,
        "heartEveryWaves": 5,
        "heartLifeMs": 2600
    }'::jsonb
where id = 'ffffffff-ffff-4fff-8fff-ffffffffffff';

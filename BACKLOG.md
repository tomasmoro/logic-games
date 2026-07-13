# Backlog — KortexGames

Lista de mejoras/pendientes conocidos, para no perderlos. No es el roadmap de
fases (ver CLAUDE.md §2); son deudas y detalles a retomar.

## Cuenta / sincronización

- [ ] **Login con Google en iOS.** Android ya usa Credential Manager (real). iOS
  sigue con fallback documentado; falta el SDK GoogleSignIn (o el flujo OAuth por
  navegador) + config en Xcode. Ref: `data/remote/auth/GoogleAuthClient.ios.kt`.
- [ ] **Confirmar cuenta por email (UX).** Si en Supabase se reactiva "Confirm
  email", el registro marca éxito sin sesión (el usuario queda invitado). Añadir
  el estado "revisa tu correo" en `AuthViewModel` para cubrir ese caso.

## Juegos / progresión

- [x] **Antesala (intro) de cada juego.** HECHO. Nuevo componente neón reutilizable
  `ui/components/GameIntroScreen.kt`: pantalla previa común a todos los juegos con
  icono (placeholder vacío por ahora), título, descripción, botón de **ayuda**
  (no-op de momento), CTA **Comenzar** (único bucle de atención: latido + halo) y,
  si el juego tiene niveles, un **carril horizontal de niveles** (`LevelStripState`:
  superados con check, frontera resaltada, bloqueados con candado; el elegido lanza
  "Comenzar"). Los juegos LEVELED (Water Sort, Energy Flow) usan la intro en su fase
  `LEVEL_SELECT`; los ENDLESS (Memoria, Reflejos, Burbujas, Atracción) ya **no
  arrancan en `init`**: quedan en `GameStatus.IDLE` mostrando la intro y empiezan al
  pulsar "Comenzar" (nuevo/reusado intent `Start`) — esto además arregla que la
  secuencia de Memoria sonara antes de empezar. El componente `LevelSelector.kt`
  (rejilla) queda **sustituido** por este carril y se eliminó.

  **Pendiente (polish):**
  - **Iconos por juego.** Hoy el "héroe" de la intro es un placeholder vacío (por
    petición). Falta diseñar/asignar un icono por juego; hueco ya previsto en
    `GameIntroScreen(icon = ...)` (basta pasar un `ImageVector`). Punto natural para
    guardarlo: un campo `icon` en `GameInfo`/`GameCatalog` o junto a `GameProgressions`.
  - **Contenido del botón de ayuda.** El botón "?" existe pero es un no-op; falta la
    hoja/diálogo de "cómo se juega" (reglas, ejemplos) por juego.

- [x] **Neon Block Grid (Block Puzzle 8×8, Visión Espacial).** HECHO (primer pase).
  Juego ENDLESS completo en `game/blockgrid/`: dominio puro (`BlockGridModel`),
  motor con líneas simultáneas y puntuación cuadrática (`BlockGridEngine` + tests),
  MVI (`BlockGridContract`/`ViewModel`) y pantalla con drag & drop, fantasma gris
  y limpieza fade+shrink (`BlockGridScreen`). Registrado en catálogo, rutas,
  AdManager y seed Supabase (`0013_seed_neon_block_grid.sql`).

  **Pendiente (polish):**
  - **Animación de retorno de pieza rechazada.** Hoy, si el drop falla, la pieza
    reaparece en su slot sin transición; falta animar el "vuelo de vuelta" a la mano.
  - **Limpieza escalonada por línea.** El fade+shrink es simultáneo para todas las
    celdas; una onda por línea (stagger desde la pieza colocada) daría más "juice".
  - **Ponderar el generador de mano.** Hoy las 23 formas salen uniformes; ponderar
    por tamaño (menos 3×3/líneas de 5) suavizaría la dificultad inicial.
  - **SFX propios.** Reutiliza TAP/SUCCESS/ERROR; valorar un SFX de "romper línea"
    dedicado y otro de anclaje más "seco".

- [ ] **Desafíos (challenges) por juego.** Feature de retención inspirada en el
  mockup: bajo la intro, una tarjeta **DESAFÍO** con un objetivo acotado en el
  tiempo y barra de progreso, p. ej. *"Llega al nivel 20 en los próximos 13.5
  minutos — 18/20"*. Al cumplirlo, recompensa (monedas/estrella/tema desbloqueable).

  **Alcance propuesto (primer pase):**
  - **Modelo de dominio** `Challenge` (en `domain/model`): `gameId`, tipo de objetivo
    (`enum ChallengeGoal { REACH_LEVEL, REACH_SCORE, WIN_ROUNDS, BEAT_TIME }`),
    `target: Int`, `deadline` (instant absoluto), `progress: Int`, `reward`. Cerrar
    dominios con `enum`/`sealed`, no strings (CLAUDE.md §4).
  - **Generación:** un desafío activo por juego, derivado del récord actual
    (`playerProgressRepository`), p. ej. `target = maxUnlocked + 2`, `deadline = now +
    N min`. Determinista por día para que reabrir la app no lo re-tire.
  - **Persistencia:** local-first (SQLDelight, patrón de `PlayerProgress.sq`); a
    futuro, sincronizar y alimentar el **leaderboard de Desafíos Diarios** (§1 de
    CLAUDE.md) vía Supabase.
  - **UI:** tarjeta neón bajo el título/carril en `GameIntroScreen` (slot ya cómodo
    de añadir): etiqueta "DESAFÍO", texto del objetivo, `CircularProgressRing`/barra
    con `progress/target`, y cuenta atrás del `deadline`. Reutilizar `LogicColors`/
    `LogicGradients`.
  - **Progreso:** al terminar una partida, el ViewModel actualiza el `progress` del
    desafío activo (comparando `reachedMetric`) y dispara la recompensa al cumplirse.

  Ref: `ui/components/GameIntroScreen.kt` (slot de tarjeta), `game/GameProgression.kt`,
  `domain/model`, `data/local` (nueva tabla vía migración `.sqm`).

- [ ] **Energy Flow: medir la stat por TIEMPO, no por nivel.** El récord debería ser
  cuánto tarda en resolver los niveles (menor = mejor), no el nivel alcanzado.

  ⚠️ **Tensión con lo ya hecho:** Energy Flow ahora es LEVELED con selector de
  niveles (récord = nivel máx). Medir por tiempo lo volvería ENDLESS y quitaría la
  progresión por niveles. Alternativa que concilia ambos: mantener los niveles y
  mostrar el **mejor tiempo POR nivel** (récord de tiempo por cada nivel superado),
  lo que sí requeriría guardar tiempo-por-nivel (no cabe en `best_metric` único;
  implicaría ampliar el esquema o una tabla de tiempos por nivel).

  **Factibilidad (analizada): SÍ, sin tocar la estructura de `user_progress`.**
  - La columna `completion_time_ms` YA existe en `user_progress` y `BaseGameEngine`
    ya calcula el tiempo activo (excluye pausas). Es decir, el tiempo por partida ya
    se registra hoy; no falta infraestructura de datos.
  - Encaja directo en el sistema de progresión nuevo (igual que Reflejos): cambiar
    en `GameProgressions` la entrada de Energy Flow de
    `(LEVELED, HIGHER_IS_BETTER, "Nivel máx", LEVEL)` a algo como
    `(ENDLESS, LOWER_IS_BETTER, "Mejor tiempo", MILLIS)`, y que
    `EnergyFlowEngine.reachedMetric()` devuelva `completionTimeMs.toInt()` **solo si
    resolvió todas las rondas** (si no, null → no cuenta como récord de tiempo).
    `player_game_progress.best_metric` guardaría los ms (menor gana); ningún cambio
    de esquema (mismo patrón crudo que Reflejos).
  - Caveat: el tiempo solo es comparable mientras el set de rondas sea FIJO (hoy 3).
    Si Energy Flow pasa a curva paramétrica (ver ítem de progresión), habría que
    normalizar por nivel (tiempo/nivel) o medir por nivel individual.
  Ref: `game/energyflow/EnergyFlowEngine.kt`, `game/GameProgression.kt`.

- [ ] **Hallazgos del security advisor (preexistentes, revisados 2026-07-10).**
  Ninguno introducido por los seeds de juegos; los "reales" pendientes:
  - Particiones `user_progress_2026_*` y `_default` con RLS activo pero **sin
    políticas propias** (INFO). El acceso pasa por la tabla madre (que sí tiene
    políticas), pero conviene confirmar que PostgREST no expone las particiones
    directamente. Relacionado con el ítem de automatizar particiones.
  - Extensión `citext` instalada en `public` (WARN): moverla a un schema propio
    en una migración nueva.
  - **Leaked password protection desactivada** en Auth (WARN): activarla en el
    dashboard (chequeo contra HaveIBeenPwned), sin impacto en código.
  - `get_score_percentile` SECURITY DEFINER ejecutable por `authenticated`: es
    **por diseño** (RPC de percentiles que solo devuelve agregados, CLAUDE.md §5);
    no requiere acción.

- [ ] **Starport Escape: pulido.**
  - Más niveles diseñados (hoy 10, verificados por BFS; el catálogo cicla a
    partir del 11). El solver está en el scratchpad de la sesión — considerar
    versionarlo en `tools/` para diseñar tandas nuevas.
  - Tutorial visual en el nivel 1 (mano/flecha que sugiere el primer gesto).
  - Sonido propio de "deslizamiento" (hoy reutiliza TAP del catálogo de SFX).
  - Valorar variar el borde de la esclusa entre niveles (el motor ya es
    genérico; solo es diseño de niveles).

## Logros

- [~] **Logros (achievements).** Conexión al backend HECHA; faltan evaluador de
  desbloqueo y UI. Hecho en este pase:
  - **Seed del catálogo** (`0012_seed_achievements.sql`, idempotente): 16 logros
    base que cubren los 6 tipos de `achievement_condition` (games_played,
    total_score, streak_days, daily_goal_completed, perfect_accuracy y
    category_mastery para Memoria/Cálculo/Lenguaje). UUIDs fijos.
  - **Catálogo en código** (`game/achievements/AchievementCatalog.kt` +
    `AchievementIds`), espejo del seed con los mismos UUID. En código (no leído de
    Supabase) porque `achievements` solo es legible por `authenticated` y el
    invitado debe verlo offline — mismo criterio que `GameCatalog`.
  - **Dominio** (`domain/model/Achievement.kt`): `AchievementCondition`,
    `Achievement`, `UserAchievement`, `AchievementStatus` (con `isUnlocked`/`fraction`).
  - **Datos local-first**: tabla SQLDelight `UserAchievementEntity` (`Achievements.sq`
    + migración `2.sqm`, v2→v3), `LocalAchievementsDataSource` (+impl),
    `RemoteAchievementsDataSource` (`user_achievements`), y
    `AchievementsRepository`(+Impl) con fusión bidireccional (mayor progreso gana;
    `unlockedAt` más temprano gana). **Solo se suben desbloqueos** a la nube (el
    `unlocked_at NOT NULL` de la tabla no distingue "en progreso"); el progreso
    parcial se queda local y es recalculable. Cableado en `AppGraph` + `sync()` al
    autenticarse.

  **Pendiente:**
  - **Evaluador de desbloqueo.** Nada llama aún a `recordProgress`: falta el motor
    que, tras cada partida / cambio de racha / meta diaria, calcule el avance de cada
    condición desde las estadísticas (`ProgressRepository`, `DailyGoalManager`,
    streak) y llame a `AchievementsRepository.recordProgress`. Devuelve si desbloqueó
    (para celebración tipo `FireworksOverlay`).
  - **UI de Logros.** Pantalla/entrada que consuma `observeAll()` (grid de tarjetas
    con estado desbloqueado/bloqueado + barra de progreso). Falta también mapear el
    `icon_key`/slug a un `ImageVector` (Material Rounded, nunca emoji, CLAUDE.md §9.5).

## Técnico / limpieza
- [ ] **Automatizar particiones de `user_progress`.** La tabla está particionada
  por mes sobre `created_at`, pero solo existen las particiones jul/ago/sep 2026
  (`0001_initial_schema.sql`). A partir de **octubre 2026** todos los inserts caen
  en `user_progress_default`; funciona (no se pierden datos) pero se pierde el
  *partition pruning* y la purga barata (`DROP` de mes viejo) que justifican el
  particionado. Automatizar la creación mensual anticipada (job/cron con `SECURITY
  DEFINER` que haga `create table … partition of …`, o la extensión `pg_partman`).
  Nueva migración; no editar la `0001` ya aplicada. Ref: `supabase/migrations/`.

## Extras

- [ ] **Mejorar niveles de crucigrama y Word Connect** (HACERLO MANUAL) Menos letras igual cantidad de 
      palabras.
- [ ] **Crear torneos de juegos y rankings**

- [ ] **Neon Circuit Flow — catálogo de niveles + solver.** Hoy solo hay
      `test5x5` (hardcoded) y "Siguiente nivel" cicla sobre él. Falta diseñar
      niveles 5×5→8×8 de dificultad creciente y verificarlos con un solver
      (resolubilidad + unicidad/teselado), como se hizo con Starport. Ref:
      `game/neoncircuit/NeonCircuitLevels.kt`.
- [ ] **Neon Circuit Flow — SFX de "estática" por celda.** El avance de cable
      (`CellAdvanced`) solo da háptica; el catálogo `SoundEffect` no tiene aún un
      sonido de estática suave. Añadir el asset y cablearlo en
      `NeonCircuitViewModel.onEngineEvent`.
- [ ] **Neon Circuit Flow — seed en Supabase.** Falta la migración que inserta el
      juego (`GameIds.NEON_CIRCUIT`) en la tabla `games` para que persista score y
      percentiles, como las `0013/0014/0015` de los últimos juegos.
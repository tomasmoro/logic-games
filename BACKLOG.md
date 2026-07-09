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

- [~] **Sistema de niveles/progresión por juego.** Núcleo IMPLEMENTADO (alcance
  elegido: tabla sincronizada en Supabase). Hecho:
  - Backend: tabla `player_game_progress` (best_metric, last_level) + RLS
    (`0010_player_game_progress.sql`). El servidor no interpreta la métrica; el
    cliente resuelve la mejor marca (local-first) y sube ya resuelta.
  - Dominio: `ProgressionKind`/`MetricDirection`/`GameProgression` + registro
    `GameProgressions` (`game/GameProgression.kt`); `GameResult.reachedMetric` y
    modelo `PlayerGameProgress`.
  - Motores: cada uno reporta el valor ALCANZADO (`BaseGameEngine.reachedMetric()`).
  - Datos: SQLDelight `PlayerProgress.sq` + local/remote datasources +
    `PlayerProgressRepositoryImpl` (local-first con fusión bidireccional: mejor
    marca gana; `lastLevel` más reciente gana). Sync al autenticarse (AppGraph).
  - UI: la tarjeta del catálogo muestra el récord ("Nivel máx 7" / "Mejor 320 ms").
  - **Selección/continuación de niveles (LEVELED) — HECHO.** Water Sort y Energy
    Flow ahora tienen **curva de dificultad paramétrica** (`configForLevel(N)`,
    nivel 1 = config base) y juegan **un nivel elegido** por partida
    (`startAtLevel`). El selector de niveles vive dentro de la **antesala/intro**
    (ver ítem "Antesala (intro) de cada juego"): un carril horizontal muestra los
    niveles superados (con check), el actual/frontera (número en acento) y los
    bloqueados (candado); el elegido se resalta y "Comenzar" lo lanza. Los VMs
    tienen fase `LEVEL_SELECT`/`PLAYING` y observan `playerProgressRepository` para
    el nivel máx desbloqueado. Game-over con "Siguiente nivel"/"Repetir"/"Elegir
    nivel" (`GameOverOverlay` extendido, opcional para no afectar a los no-LEVELED).

  **Pendiente (polish, siguiente pase):**
  - [x] Badge "¡Nuevo récord!" en el overlay de fin de partida — HECHO. En vez del
    snapshot por-ViewModel se resuelve en la **capa de repositorio** (más DRY y sin
    tocar la DI de los VMs ENDLESS): `PlayerProgressRepository.recordResult` devuelve
    si batió el récord PREVIO (solo si había marca anterior; la primera no cuenta),
    `ProgressRepository.saveResult` lo propaga en un nuevo `SaveOutcome`
    (percentil + `isNewRecord`) y cada VM lo pasa a `GameOverInfo.isNewRecord`. El
    `GameOverOverlay` muestra la píldora animada + **fuegos artificiales neón**
    (`FireworksOverlay`, puntual, no en bucle) con **sonido/háptica arcade**
    (`SoundEffect.SUCCESS` por estallido, háptica HEAVY en el primero) sincronizados
    a cada explosión. Nota: reusa el SFX `sfx_success` existente; si se quiere una
    fanfarria propia, añadir un `.wav` nuevo + entrada en `SoundEffect`.
  - Menor: en la **antesala (intro)** el `AdManager` sigue contando "gameplay" (la
    ruta del juego está activa aunque el jugador aún no haya pulsado "Comenzar").
    Los juegos ENDLESS quedan en `GameStatus.IDLE` durante la intro, así que la
    condición podría afinarse para pausar el contador mientras el estado sea IDLE /
    fase `LEVEL_SELECT` (`onEnterMenuOrPause`).
  - SQLDelight: `PlayerGameProgressEntity` se añadió con migración versionada
    (`1.sqm`, v1→v2), así que las instalaciones existentes la reciben vía onUpgrade
    (no hace falta reinstalar). Es la PRIMERA migración `.sqm` del proyecto: las
    próximas altas de tabla/columna deben seguir el mismo patrón (nuevo `.sqm`).

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

- [ ] **Unificar assets de audio.** Los `.wav` están duplicados en
  `androidApp/src/main/res/raw/` (Android) y `shared/.../composeResources/files/`
  (iOS, añadido para arreglar el sonido en iOS). Unificar en una sola fuente
  (p. ej. mover Android a composeResources también) para no mantener dos copias.

- [ ] **Automatizar particiones de `user_progress`.** La tabla está particionada
  por mes sobre `created_at`, pero solo existen las particiones jul/ago/sep 2026
  (`0001_initial_schema.sql`). A partir de **octubre 2026** todos los inserts caen
  en `user_progress_default`; funciona (no se pierden datos) pero se pierde el
  *partition pruning* y la purga barata (`DROP` de mes viejo) que justifican el
  particionado. Automatizar la creación mensual anticipada (job/cron con `SECURITY
  DEFINER` que haga `create table … partition of …`, o la extensión `pg_partman`).
  Nueva migración; no editar la `0001` ya aplicada. Ref: `supabase/migrations/`.

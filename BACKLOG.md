# Backlog — KortexGames

Lista de mejoras/pendientes conocidos, para no perderlos. No es el roadmap de
fases (ver CLAUDE.md §2); son deudas y detalles a retomar.

## Cuenta / sincronización

- [x] **Sync bidireccional (descarga nube → local).** Hecho: `syncPending()` ahora
  hace push (local→nube) y luego pull (`RemoteProgressDataSource.fetchAll()` →
  `LocalProgressDataSource.mergeRemote()`), deduplicando por `remoteId`. Al iniciar
  sesión en otro dispositivo o tras reinstalar, el historial de la nube se descarga
  a SQLDelight. Ref: `data/repository/ProgressRepositoryImpl.kt`.
- [ ] **Leer el plan real (premium).** `AuthRepositoryImpl` fija `PlanType.FREE`
  siempre; no consulta `public.users.plan_type`. Un usuario premium seguiría
  viendo anuncios. Ref: `data/repository/AuthRepositoryImpl.kt`.
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

  **Pendiente (polish, siguiente pase):**
  - Badge "¡Nuevo récord!" en el overlay de fin de partida. Requiere que cada
    ViewModel capture el récord PREVIO al empezar (snapshot de
    `playerProgressRepository.observe(gameId)`) y lo compare con `reachedMetric`.
  - "Continuar nivel N / Empezar de nuevo" en juegos LEVELED. El `lastLevel` ya se
    persiste y sincroniza, pero los motores aún no arrancan en un nivel dado; Water
    Sort/Energy Flow juegan una lista fija de rondas. Falta la **curva de dificultad
    paramétrica** (nivel N → config generada) para reanudar de verdad.
  - SQLDelight: `PlayerGameProgressEntity` se añadió con migración versionada
    (`1.sqm`, v1→v2), así que las instalaciones existentes la reciben vía onUpgrade
    (no hace falta reinstalar). Es la PRIMERA migración `.sqm` del proyecto: las
    próximas altas de tabla/columna deben seguir el mismo patrón (nuevo `.sqm`).

  **Problema actual:** `GameEngine.finish()` guarda `difficultyLevel = difficulty`
  (la dificultad de INICIO, siempre 1), no el nivel alcanzado. No existe estado de
  progresión por juego: solo hay log de partidas (`user_progress`).

  **Modelo — separar 3 conceptos:**
  1. *Log de partidas* → ya existe (`user_progress`).
  2. *Récord por juego* → tu mejor marca (nivel máx / botones máx). FALTA.
  3. *Punto de reanudación* → dónde retomas la próxima vez. FALTA.

  **Dos tipos de juego** (declarar en `GameInfo` un `progression: ProgressionKind`
  + etiqueta de métrica):
  - `LEVELED`: niveles discretos que suben → reanudar/desbloquear (Water Sort,
    Energy Flow, Bubble Math, Polarity). Métrica: "Nivel N".
  - `ENDLESS`: una corrida hasta fallar → récord (Memoria = máx. botones/secuencia,
    Reflejos = mejor tiempo). Métrica: "Mejor: X".

  **Requisito:** el motor debe reportar el valor ALCANZADO, no el de inicio (Water
  Sort: ronda/nivel alcanzado; Memoria: longitud máx de secuencia). `saveResult`
  actualiza el récord con `max(...)`.

  **Dónde guardar (mínimo viable, recomendado):**
  - *Récord* → derivarlo del historial (`max` sobre `user_progress`). Sin infra
    nueva; se sincroniza gratis cuando exista la descarga nube→local.
  - *Reanudación* (`lastLevel`) → DataStore local por juego (patrón `DailyGoalStore`).
    Comodidad por-dispositivo; promocionable a fila sincronizada si se quiere que
    siga a la cuenta.

  **Alternativa (más ambiciosa):** tabla `player_game_progress` en Supabase
  (best/max/lastLevel por juego) sincronizada entre dispositivos → implica
  migración SQL + RLS + sync; depende de resolver antes la descarga nube→local.

  **Nota Water Sort:** hoy juega una lista fija de 3 rondas (`roundConfigs`) por
  sesión. Para progresión infinita hay que pasar a una curva de dificultad
  paramétrica (nivel N → config generada), persistir `lastLevel` y arrancar ahí.

  **UX:** tarjeta del catálogo muestra la métrica ("Nivel máx 7" / "Mejor: 12");
  juegos LEVELED ofrecen "Continuar nivel N / Empezar de nuevo"; game-over marca
  "Nuevo récord" al superar el `bestMetric`.

- [ ] **Energy Flow: medir la stat por TIEMPO, no por nivel.** El récord debería ser
  cuánto tarda en resolver los niveles (menor = mejor), no el nivel alcanzado.

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

## Bugs conocidos

- [ ] **Polarity Collision: colisiones de color a veces se cuentan como fallo.**
  Cuando un asteroide violeta impacta el sector violeta del centro (y análogamente
  con todos los colores), a veces se registra como mismatch (`ERROR` + penalización)
  aunque visualmente el color coincide.

  **Causa probable:** el sector de impacto se calcula con la posición **post-step**
  de la partícula (`atan2(py - centerY, px - centerX)` en `step()`), que ya está
  DENTRO del disco y ha sido desviada tangencialmente por la curva magnética justo
  antes del impacto. Cerca de los límites entre sectores, ese ángulo cae en el
  sector contiguo al que el jugador ve, y `targetSector != particle.colorIndex`
  marca fallo. Con pasos de tiempo discretos (dt) el punto muestreado puede estar
  bastante pasado el borde, agravándolo.

  **Arreglo sugerido:** calcular el ángulo de impacto en el **punto de cruce del
  borde** (interpolar entre la posición previa y la nueva hasta `catchRadius`), no en
  la posición final; muestrear `rotationRad` de forma coherente con ese instante; y/o
  añadir una pequeña tolerancia angular (snap) cerca de los límites de sector.
  Ref: `game/polarity/PolarityCollisionEngine.kt` (`step()`, `sectorFromAngle()`).

## Logros

- [ ] **Logros (achievements).** Las tablas `achievements` / `user_achievements`
  existen en el backend, pero no hay lógica en la app (desbloqueo, UI, sync).

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

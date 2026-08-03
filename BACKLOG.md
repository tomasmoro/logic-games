# Backlog — KortexGames

Lista de mejoras/pendientes conocidos, para no perderlos. No es el roadmap de
fases (ver CLAUDE.md §2); son deudas y detalles a retomar.

## Cuenta / sincronización

- [x] **Login con Google en iOS.** HECHO (código). Implementado el flujo OAuth 2.0
  *Authorization Code + PKCE* sobre `ASWebAuthenticationSession` en Kotlin/Native
  puro (sin el pod GoogleSignIn ni cocoapods): `GoogleAuthClient.ios.kt` obtiene un
  ID token de Google que `AuthRepositoryImpl` canjea por sesión Supabase igual que
  Android. La lógica sensible (SHA-256, base64url, reto PKCE, URL, parseo del
  callback) vive en `data/remote/auth/GoogleOAuth.kt` con tests en
  `GoogleOAuthTest` (vectores RFC 7636).

  **Pendiente (config del usuario, no código):**
  - Crear un **OAuth client id tipo iOS** en Google Cloud (con el bundle id de
    `iosApp`) y pegarlo en `SupabaseConfig.GOOGLE_IOS_CLIENT_ID`.
  - Añadir ese client id a *Authorized Client IDs* del proveedor Google en Supabase
    (GoTrue valida el `aud` del token). Nota: `ASWebAuthenticationSession` intercepta
    el esquema de callback por sí mismo, así que NO hace falta tocar `Info.plist`.
  - Verificar el test en host: `./gradlew :shared:testAndroidHostTest --tests
    "com.kortexgames.app.data.remote.auth.GoogleOAuthTest"` (hoy el árbol no
    compila por WIP de blockgrid; correrlo al integrar).
- [ ] **Completar el branding del OAuth consent screen (Google Cloud).** Falta
  añadir en la pestaña **Branding** del proyecto de Google Cloud (ver
  `docs/google-signin-setup.md` §1.2): **logo** de la app (requiere subir el
  icono a revisión de Google, tarda un poco), **página principal/homepage** de la
  app y **política de privacidad**. Sin esto, la pantalla de consentimiento que ve
  el usuario al loguearse con Google se ve genérica ("app no verificada") y,
  sobre todo, **es requisito para pasar la app de Testing a Production** (sin
  logo + homepage + política de privacidad, Google no deja publicarla más allá
  de los test users).

  **Pendiente (config/assets, no código):**
  - Página de privacidad: hoy no existe ninguna (ni web ni in-app); hay que
    redactarla y alojarla en una URL pública (GitHub Pages, o una página simple
    en el dominio que se use para la app) antes de poder pegarla en Branding.
  - Homepage: idem, puede ser tan simple como una landing mínima o la ficha de
    la store una vez publicada.
  - Logo: usar el icono real de la app (`androidApp` `AppIcon`/
    `iosApp/.../Assets.xcassets/AppIcon.appiconset`), Google pide un tamaño
    mínimo cuadrado (revisar el requisito exacto en el formulario de Branding).
- [ ] **Confirmar cuenta por email (UX).** Si en Supabase se reactiva "Confirm
  email", el registro marca éxito sin sesión (el usuario queda invitado). Añadir
  el estado "revisa tu correo" en `AuthViewModel` para cubrir ese caso.

## Juegos / progresión

- [x] **Tetris Neón (antes "Neon Block Grid" — Block Puzzle 8×8, Visión Espacial).**
  HECHO. Juego ENDLESS completo en `game/blockgrid/` (el paquete/ids mantienen el
  nombre técnico `blockgrid`/`NEON_BLOCK_GRID`; solo cambió el nombre visible a
  **"Tetris Neón"** en `GameCatalog`): dominio puro (`BlockGridModel`), motor con
  líneas simultáneas y puntuación cuadrática (`BlockGridEngine` + tests), MVI
  (`BlockGridContract`/`ViewModel`) y pantalla con drag & drop, fantasma gris y
  limpieza fade+shrink (`BlockGridScreen`). Registrado en catálogo, rutas,
  AdManager y seed Supabase (`0013_seed_neon_block_grid.sql`).

  **Hecho después del primer pase:**
  - **Celebración de combo (fuegos + guirnaldas).** Al romper líneas se lanza
    `FireworksCelebration`: N fuegos artificiales escalonados (uno por línea, tope
    `MAX_FIREWORKS`) y, solo en hitos grandes —combo de 5+ líneas
    (`GARLAND_COMBO_THRESHOLD`) o **vaciado total** del tablero (`isPerfectClear`)—,
    una cortina de guirnaldas neón cayendo en espiral. El motor detecta el vaciado
    total y viaja como dato aparte del conteo (`LinesCleared.isPerfectClear` →
    `ShowComboAnim.showGarlands`); la UI solo pinta. Esto cubre la parte de "juice"
    que faltaba tras el primer pase.
  - **Vuelo de vuelta de pieza rechazada.** Si un drop cae en hueco inválido o se
    cancela el gesto, la pieza ya no reaparece de golpe: vuela de vuelta a su hueco
    encogiéndose al tamaño de la mano (`ReturningPieceOverlay`, ~260 ms
    `FastOutSlowIn`). Dirigido por el efecto `AnimatePieceReturn` (nuevo), que el
    motor/VM emiten en `PlacementRejected` (ahora lleva `pieceId`) y en
    `DragCancelled`; la UI conoce el destino por el centro de cada hueco
    (`slotCenters`) y oculta el slot mientras dura el vuelo para que aterrice sobre
    un hueco vacío.
  - **Limpieza escalonada (onda desde la pieza).** El fade+shrink ya no es
    simultáneo: cada celda `Clearing` arranca con una demora proporcional a su
    distancia al centro de la pieza recién colocada (`clearOrigin`/`CLEAR_STAGGER_SPAN`
    sobre el reloj único de `BoardCanvas`), así la ruptura se propaga como una onda
    desde donde el jugador soltó. Sin temporizadores por celda: todo se recalcula
    por frame a partir del reloj + la distancia.

  **Pendiente (polish):**
  - **SFX propios.** Reutiliza TAP/SUCCESS/ERROR; valorar un SFX de "romper línea"
    dedicado, otro de anclaje más "seco" y un remate sonoro para el vaciado total
    (hoy el hito de perfect clear solo se celebra visualmente).

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

- [x] **Energy Flow: medir la stat por TIEMPO, no por nivel.** HECHO como **mejor
  tiempo POR nivel** (la alternativa que concilia ambos mundos), no como el cambio a
  ENDLESS que proponía la nota original — ese cambio habría tirado el selector/curva
  paramétrica de niveles que ya está en producción. El récord de la tarjeta sigue
  siendo "Nivel máx"; además ahora se guarda y muestra el mejor tiempo de cada nivel.

  **Cómo quedó (mecanismo GENÉRICO, reutilizable por cualquier juego LEVELED):**
  - `GameProgression` tiene un flag `tracksLevelTime`; Flujo de Energía lo activa. Un
    juego LEVELED cuyos niveles se *completan* (no se fallan) puede activarlo sin
    lógica propia y hereda todo lo de abajo.
  - Nuevo dominio `LevelBestTime` + tabla local `LevelBestTimeEntity`
    (`LevelTime.sq`, migración `3.sqm`) y remota `player_level_time`
    (`0018_player_level_time.sql`, con RLS propia), una fila por (juego, nivel).
  - `PlayerProgressRepository.recordResult` graba el tiempo del nivel completado
    (menor gana) en la MISMA ruta local-first (funciona en invitado/offline); `sync()`
    fusiona nube↔local (gana el menor). `observeLevelTimes(gameId)` alimenta la UI.
  - UI: el carril de niveles (`LevelStripState.bestTimes`) muestra un badge de
    cronómetro con el mejor tiempo bajo cada nivel superado; formateo en
    `formatDurationShort` (con test).
  - Se resolvió así el caveat de la nota original (el tiempo no era comparable entre
    niveles al pasar a curva paramétrica): al medir POR nivel, cada tiempo es
    comparable consigo mismo.

  Migración `0018` **aplicada** al proyecto Supabase real (tabla `player_level_time`
  con RLS propia); el security advisor no arrojó hallazgos nuevos.

  Ref: `game/GameProgression.kt`, `data/repository/PlayerProgressRepositoryImpl.kt`,
  `domain/model/{Models,TimeFormat}.kt`, `ui/components/GameIntroScreen.kt`.

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

## Anuncios (AdMob)
- [x] **Modelo de tiempo del `AdManager` (Fase 0).** HECHO. El contador ya no cuenta
      solo el juego activo: corre **desde que la app entra a primer plano**
      (`onAppForeground`/`onAppBackground`, cableado en `MainActivity`) e incluye
      menús. Al cruzar los 3 min NO interrumpe la partida: marca un intersticial
      *pendiente* y lo cobra en el próximo **breakpoint** (`onAdBreakpoint`). Único
      hook central hoy: salir de un juego, detectado en `App.kt` vía
      `Routes.isGameRoute` (arreglado de paso: la lista a mano omitía `NEON_DEFUSER`).
      Seam de intersticial simétrico al de rewarded (`InterstitialAdPresenter` +
      `SimulatedInterstitialAdPresenter` para dev), presentado por un colector único
      en `App.kt`.
- [x] **Breakpoint de "avanzar de nivel" (juegos LEVELED).** HECHO. Los 8 juegos
      LEVELED (Crucigrama, Water Sort, Energy Flow, Word Connect, Neon Screws, Neon
      Lexicon, Starport, Neon Circuit) llaman `adManager.onAdBreakpoint()` en su intent
      `NextLevel`, antes de `playLevel(currentLevel + 1)`: al avanzar de nivel (sin salir
      de la ruta, que el hook central de `App.kt` no cubre) se cobra el intersticial
      pendiente. Los ENDLESS no aplican (no avanzan de nivel; su corte es salir del
      juego). `onAdBreakpoint` es no-op si no hay anuncio pendiente. El `AdManager` se
      inyecta en cada ViewModel LEVELED (constructor) desde su `Screen` (`graph.adManager`).
      Filtro escalable de "es LEVELED": `GameProgressions.forId(gameId).kind`.
      Pendiente menor: unificar el patrón (hoy es un one-liner replicado por juego) si
      surge una base común de ViewModel LEVELED.
- [ ] **Hook de foreground/background en iOS.** Hoy `onAppForeground`/`onAppBackground`
      solo los llama `MainActivity` (Android). En iOS el contador arranca en foreground
      y no se pausa al ir a background (el `MainViewController` no observa el lifecycle
      de la escena). Suscribirse a `UIApplication` willResignActive/didBecomeActive y
      reenviar al `AdManager`. Impacto bajo (las corrutinas se estrangulan en background)
      pero conviene para exactitud del contador.
- [x] **SDK real de AdMob en Android (pasos A2–A6).** HECHO. `play-services-ads`
      (25.4.0) en el catálogo + `androidMain` de `shared`; App ID como `meta-data` del
      manifest vía placeholder `admobAppId` (por defecto el App ID de PRUEBA de Google,
      real desde `secrets.properties`/`ADMOB_APP_ID`). Seam `expect/actual`
      `installPlatformAdPresenters(adManager, context)`: el `actual` de Android inicializa
      `MobileAds` y registra `AdMobInterstitialAdPresenter`/`AdMobRewardedAdPresenter`
      (cargan bajo demanda en `Dispatchers.Main`, presentan con la Activity de
      `CurrentActivityHolder`, mapean cierre→resultado); el de iOS mantiene los simulados.
      Verificado: `:androidApp:assembleDebug` en verde con el App ID en el manifest mergeado.
      (El `MobileAds.initialize` se movió de este seam a `AdConsentManager`, ver A7.)
- [x] **IDs de AdMob por tipo de build.** HECHO. `debug` usa siempre los IDs de PRUEBA y
      `release` los reales, sin pasos manuales antes de publicar. App ID: placeholder
      `admobAppId` resuelto por `buildType` en `androidApp/build.gradle.kts` (aviso por
      consola si se ensambla release sin `ADMOB_APP_ID`). Ad units: `generateSecrets`
      emite `AdMobSecrets` desde `secrets.properties`
      (`ADMOB_INTERSTITIAL_UNIT_ID`/`ADMOB_REWARDED_UNIT_ID`) y `AdMobConfig` elige real
      vs prueba según `FLAG_DEBUGGABLE` + si hay valor configurado. **Pendiente al
      publicar: crear los bloques en AdMob (intersticial + recompensado) y pegar los tres
      IDs en `secrets.properties`.** Verificado: manifest de release con el ID real y el
      de debug con el de prueba.
- [x] **Consentimiento GDPR/UMP en Android (paso A7).** HECHO. `user-messaging-platform`
      (4.0.0) + `AdConsentManager` (androidMain): `requestConsentInfoUpdate` →
      `loadAndShowConsentFormIfRequired` → inicializa `MobileAds` solo cuando
      `canRequestAds()`. Lo dispara `MainActivity.onCreate` (el formulario necesita una
      Activity). `installPlatformAdPresenters` ya NO inicializa el SDK (solo registra
      presentadores). Nota dev: para forzar el formulario fuera de la UE, añadir un
      `ConsentDebugSettings` con geografía EEA + hashed id del dispositivo (por-dispositivo,
      no fijado en código). Verificado con `:androidApp:assembleDebug`.
- [ ] **AdMob iOS (Parte B) + precarga.** Falta: el puente Swift + CocoaPods/SPM para
      `GADInterstitialAd`/`GADRewardedAd` (reemplazar el `actual` iOS que hoy usa los
      simulados) **y su propio flujo UMP** en iOS; y **precargar** `InterstitialAd`/
      `RewardedAd` en vez de cargar en cada `show()` para quitar latencia. Los ad units
      de iOS serán otros (AdMob los da por plataforma) y hoy no los cubre `AdMobSecrets`.

## Técnico / limpieza
- [ ] **La recompensa diaria no se reclama desde ningún sitio.**
  `DailyGoalManager.claimReward()` (y `DailyGoalState.canClaim`) existen y
  persisten la fecha de reclamación, pero ninguna pantalla los invoca: el antiguo
  `DailyGoalCard` recibía un `onClaim` que nunca llegaba a llamar, y el rediseño
  de la tarjeta de entrenamiento (`TrainingCard` en `ui/home/HomeScreen.kt`)
  eliminó ese parámetro muerto. Decidir **qué otorga** la recompensa (monedas,
  estrella, tema) y añadir el gesto de reclamarla en el estado "completado" de la
  tarjeta, o retirar la API si el objetivo diario se queda sin premio material.
- [ ] **Automatizar particiones de `user_progress`.** La tabla está particionada
  por mes sobre `created_at`, pero solo existen las particiones jul/ago/sep 2026
  (`0001_initial_schema.sql`). A partir de **octubre 2026** todos los inserts caen
  en `user_progress_default`; funciona (no se pierden datos) pero se pierde el
  *partition pruning* y la purga barata (`DROP` de mes viejo) que justifican el
  particionado. Automatizar la creación mensual anticipada (job/cron con `SECURITY
  DEFINER` que haga `create table … partition of …`, o la extensión `pg_partman`).
  Nueva migración; no editar la `0001` ya aplicada. Ref: `supabase/migrations/`.

## Extras

- [ ] **Crear torneos de juegos y rankings**
- [ ] **Neon Circuit Flow — SFX de "estática" por celda.** El avance de cable
      (`CellAdvanced`) solo da háptica; el catálogo `SoundEffect` no tiene aún un
      sonido de estática suave. Añadir el asset y cablearlo en
      `NeonCircuitViewModel.onEngineEvent`.
- [ ] **Starport — pre-generar el siguiente nivel en background.** La generación
      procedural (BFS del solver) corre síncrona en `onStart`: los niveles 10×10
      más densos tardan ~300 ms en JVM de escritorio (más en móvil) la primera
      vez (después quedan en caché de sesión). Lanzar `StarportLevels.forNumber
      (n+1)` en `Dispatchers.Default` al completar el nivel `n` para que "Siguiente
      nivel" abra instantáneo.
- [ ] **Neon Sudoku Matrix — enriquecer la banda DIFICIL con más técnicas.** El
      rater offline (`tools/sudoku/generate_bank.py`) implementa singles, pointing/
      claiming, pares/triples y X-Wing; por encima marca EXPERTO. La calibración
      mostró que la dificultad "por técnica" es casi bimodal, así que FACIL/MEDIO/
      DIFICIL hoy se separan por nº de pistas y solo EXPERTO exige técnica avanzada.
      Añadir XY-Wing y Swordfish al rater movería parte de los actuales EXPERTO a un
      DIFICIL "de técnica" genuino y afinaría la frontera. Solo cambia la generación
      offline + re-subir el banco (`0025_create_sudoku_puzzles.sql` / seed CSV); el
      cliente no se toca.
- [ ] **Neon Sudoku Matrix — test unitario de `SudokuBank.parse` y rotación.**
      Cubrir el parseo del CSV (líneas malformadas se saltan, no abortan) y la
      rotación "no repetir" del repositorio (`SudokuPuzzleRepositoryImpl` +
      `selectNextByDifficulty`). La validez de los puzzles ya se garantiza offline
      en generación; falta blindar la capa de carga en el cliente.
- [x] **Neon Sudoku Matrix — feature de pista usando `solution`.** HECHO. La
      validación de celdas dejó de comparar por duplicados de fila/columna/bloque
      (`recomputeConflicts`, eliminada) y ahora compara cada valor escrito
      directamente contra `SudokuPuzzle.solution` (`solutionDigitAt` en
      `NeonSudokuViewModel`): un dígito mal colocado se marca al instante en su
      propia celda, sin esperar a que el resto del grupo se complete ni obligar al
      jugador a deshacer partidas enteras para encontrar el error. Además, botón
      "Pista" en el teclado (deshabilitado si la celda seleccionada no aplica, ver
      `NeonSudokuUiState.hintAvailable`): al pulsarlo se lanza DIRECTO el anuncio
      recompensado (sin diálogo de confirmación previo — pulsar el botón ya es la
      confirmación, a diferencia de "revivir") y, si se gana la recompensa, revela
      el dígito correcto de la celda elegida (capturada en `hintTargetPosition`
      del ViewModel, no en la selección "en vivo", por si cambia mientras el
      anuncio carga). Sin límite por partida — cada pista cuesta un anuncio.
      `NeonSudokuSavedState` persiste ahora también la solución para poder
      reanudar partidas guardadas.

  - [ ] **Proximos juegos** Unir puntos evitando puntos rojos. Anagramas. Recordar parejas. Encontrar parejas(juego de a dos tambien)
        Torre de Hanoi. acertijo, deslizar piezas para encajar una cuadricula. 
  - [ ] Ver sonidos, sonidos todo el tiempo puede hartar
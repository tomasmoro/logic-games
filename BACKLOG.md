# Backlog — KortexGames

Lista de mejoras/pendientes conocidos, para no perderlos. No es el roadmap de
fases (ver CLAUDE.md §2); son deudas y detalles a retomar.

## Cuenta / sincronización

- [ ] **Botón oficial de Sign in with Apple.** El login con Apple ya funciona
  (`AppleAuthClient`), pero el botón reutiliza `SocialLoginButton` —contorno neutro
  con un icono genérico de `Login`— para que Google y Apple se vean como hermanos.
  Las Human Interface Guidelines de Apple piden **su marca oficial** (logotipo de la
  manzana, fondo negro o blanco). El texto sí es el literal que exigen. Es un riesgo
  BAJO de rechazo comparado con no ofrecer el login (guideline 4.8, ya resuelto),
  pero si Apple lo objeta, la vía es añadir el path del logotipo como `ImageVector`
  en `KortexIcons` — nada de emojis (§9.5) ni de imágenes rasterizadas.

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

- [ ] **Línea Neón — pulido pendiente.** El juego está completo y publicado
  (`game/neonline/`, seed `0030_seed_neon_line.sql`), pero quedan detalles que no
  bloquean nada:
  - **Sistema de pistas.** El generador es determinista, así que la solución de
    referencia se puede regenerar con la misma semilla sin almacenarla en el
    nivel: bastaría con exponerla desde `NeonLineLevels` para iluminar las N
    siguientes celdas. Si se añade, tiene que **penalizar el score** como pide la
    regla de puntuación (hoy solo se penalizan los reinicios).
  - **Variar la dificultad por encima del techo de densidad.** La banda de bloques
    está topada al 11–25 % porque más allá casi ningún reparto disperso tiene
    solución (ver el KDoc de `NeonLineLevels`). Hoy la única palanca que queda tras
    el escalón 9 es dónde caen los bloques (`interiorRatio`). Si hiciera falta más
    recorrido, la vía sería mecánica nueva (celdas que hay que pisar en cierto
    orden, casillas de un solo sentido), no más bloques.
  - **Mejor tiempo por nivel.** Está activado (`tracksLevelTime = true`) pero la
    pantalla aún no lo muestra en el carril de niveles.

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
- [ ] **`app-ads.txt` en la raíz de `tomasmoro.github.io` (verificación de AdMob).**
      AdMob no puede verificar la app: rastrea `https://tomasmoro.github.io/app-ads.txt`
      y da 404. Causa: el sitio se publica como **Pages de proyecto**
      (`tomasmoro.github.io/logic-games/`), y el rastreador SOLO mira la raíz del
      dominio. El contenido ya está versionado en `site/app-ads.txt`; falta crear el
      repo de usuario `tomasmoro/tomasmoro.github.io` (público) con ese archivo en su
      raíz y activar Pages. Sin esto los anuncios siguen sirviéndose, pero pierden la
      demanda programática que exige `app-ads.txt` (menor eCPM).
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
- [x] **AdMob iOS (Parte B), sin CocoaPods.** HECHO. En vez de cinterop manual o
      CocoaPods (que el proyecto evita, ver login con Google), el SDK vive **solo en
      Swift**: `IosAdBridge` (interfaz Kotlin, `shared/iosMain`) la implementa
      `AdMobBridge.swift` (`iosApp`) con `GADInterstitialAd`/`GADRewardedAd` reales,
      publicándose en `IosAdBridgeHolder` desde `iOSApp.swift` al arrancar.
      `BridgedInterstitialAdPresenter`/`BridgedRewardedAdPresenter` adaptan las
      callbacks de Swift al contrato `suspend` del `AdManager`; si Swift no ha
      registrado el puente todavía (paquete SPM sin enlazar), el `actual` de iOS cae a
      los simulados sin romper nada. Incluye consentimiento UMP + prompt de ATT
      (`AdMobBridge.requestConsentAndStart()`, llamado desde `iOSApp.swift.init()`) y
      **precarga básica** (recarga el siguiente anuncio tras cada cierre).
      **Pendiente manual, no scripteable:** añadir el paquete SPM en Xcode (File → Add
      Package Dependencies → `https://github.com/googleads/swift-package-manager-google-mobile-ads.git`)
      y marcar **los DOS** productos en el target `iosApp`: `GoogleMobileAds` **y**
      `GoogleUserMessagingPlatform` (dependencia transitiva del mismo paquete, pero
      Xcode no la enlaza sola — hay que tildarla aparte). API "Swift-first" del SDK
      v13 (sin prefijo `GAD`/`UMP`, `import UserMessagingPlatform` aparte).
      **Ad units reales: HECHO.** `AdMobBridge.swift` ya no fija los IDs: los pide a
      `IosAdUnits` (`shared/iosMain/core/ads`), que aplica la misma política que
      `AdMobConfig` de Android — unidad real solo si el binario NO es de depuración
      (`Platform.isDebugBinary`, el análogo de `FLAG_DEBUGGABLE`) **y** hay una
      configurada. Los valores llegan por `secrets.properties`
      (`ADMOB_IOS_INTERSTITIAL_UNIT_ID` / `ADMOB_IOS_REWARDED_UNIT_ID`) vía la tarea
      `generateSecrets`, que los inyecta en `AdMobSecrets`.

      **App ID y SKAdNetwork: HECHO.** El `Info.plist` ya lleva el
      `GADApplicationIdentifier` real de la app iOS y las 50 `SKAdNetworkItems` de la
      lista oficial de Google. Esa lista crece cada cierto tiempo: conviene recopiarla
      de `developers.google.com/admob/ios/3p-skadnetworks` antes de cada envío
      importante (si se queda vieja no rompe nada, solo se pierde algo de demanda).

      **Pendiente para publicar:**
      - **Rellenar `ADMOB_IOS_INTERSTITIAL_UNIT_ID` y `ADMOB_IOS_REWARDED_UNIT_ID`** en
        `secrets.properties` con las unidades reales de la app iOS de AdMob. Mientras
        estén vacías, un release servirá anuncios de PRUEBA y no monetizará.
      - **Afinar la precarga** (reintento/backoff).
- [ ] **Verificar en dispositivo el consentimiento diferido.** El formulario UMP (y el
      ATT de iOS) ya no se piden al arrancar: los dispara `beginAdConsentFlow` cuando
      termina la bienvenida jugable de la primera apertura (`OnboardingGate
      .isFirstRunOver`), y hasta entonces el `AdManager` está suspendido
      (`adsSuspended`) — ni intersticiales ni contador, y los rewarded se conceden
      gratis. Falta comprobarlo en dispositivo real con `ConsentDebugSettings`
      (geografía EEA forzada): que el formulario aparezca justo al salir del tercer
      juego y que el primer intersticial llegue con normalidad después.

## Moderación / UGC

- [ ] **Mecanismo de denuncia de nombres.** La validación ya está (migración 0049:
      `set_display_name` con blocklist + CHECK de forma, y `authenticated` sin UPDATE
      sobre `public.users`), pero un filtro automático no es lo mismo que moderación.
      La política de UGC de Play y la guideline 1.2 de Apple piden además **poder
      denunciar** el contenido de otro usuario, y el ranking muestra nombres ajenos
      (`get_game_ranking` devuelve la ventana de vecinos con su `display_name`).

      Alcance mínimo suficiente: pulsación larga sobre una fila del ranking →
      "Denunciar nombre" → `mailto:` al soporte que ya figura en `site/index.html`,
      con el nombre denunciado en el asunto. No hace falta tabla ni backend para la
      primera versión; el volumen a esta escala lo absorbe el correo.

      Cuando haya volumen: tabla `reported_names` con RLS (una denuncia por usuario y
      nombre), y ampliar `blocked_display_name_patterns` en caliente desde lo
      denunciado — la lista está pensada para eso (se lee en cada llamada, no hay
      caché que invalidar).

      Ref: `ui/components` (fila de ranking), `supabase/tests/0049_display_name.sql`
      (correr el script tras CADA patrón nuevo: cada uno puede rechazar nombres
      legítimos, como `pene` hacía con "Penelope").

## Técnico / limpieza
- [ ] **Silenciar el warning de bundle id del framework `Shared`.** Cada build de
  iOS avisa: *"Cannot infer a bundle ID from packages of source files and exported
  dependencies, use the bundle name instead: Shared"*. Es **inofensivo hoy**:
  `shared/build.gradle.kts` declara `isStatic = true`, así que `Shared` se enlaza
  dentro del binario de la app y su `CFBundleIdentifier` nunca llega al `.app` (los
  únicos frameworks embebidos son GoogleMobileAds y UserMessagingPlatform). Se calla
  añadiendo `binaryOption("bundleId", "com.kortexgames.shared")` junto a `baseName`
  en el bloque `binaries.framework`. Hacerlo si algún día `Shared` pasa a dinámico,
  porque entonces sí sería un bundle id real dentro del paquete.
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
- [ ] **Activar R8/ProGuard en `release` (minify + mapping.txt).** Hoy
  `androidApp/build.gradle.kts` tiene `isMinifyEnabled = false`: el AAB no va
  ofuscado ni reducido, y Play Console avisa (advertencia, no bloqueante) de
  que falta archivo de desofuscación asociado al code de versión. Activarlo
  reduce tamaño de la app y protege el código, pero R8 puede romper en
  silencio lo que se usa por reflection si faltan reglas `-keep`
  (kotlinx.serialization de los modelos de Supabase, clases generadas por
  SQLDelight, Compose) — son bugs que solo aparecen en `release`, nunca en
  `debug`. No conviene meterlo justo antes de subir una build a testear;
  hacerlo en un pase propio:
  1. Crear `androidApp/proguard-rules.pro` con las reglas `-keep` para
     Supabase (`io.github.jan-tennert.supabase`), `kotlinx.serialization` y
     las clases `*Entity`/`*Queries` generadas por SQLDelight.
  2. `isMinifyEnabled = true` + `shrinkResources = true` en `release`.
  3. Probar la app ENTERA en `release` (no debug) en dispositivo real: los
     18 juegos, login (Google + email + invitado→sync), anuncios,
     borrado de cuenta — antes de dar por buena la build.
  4. `bundleRelease` empaqueta `mapping.txt` dentro del AAB automáticamente
     (no hace falta subirlo a mano en Play Console).

## Extras

- [ ] **Bienvenida jugable — pulido pendiente.** El flujo ya existe (`FirstRunGames`
      + `ui/onboarding/FirstRunFlow`, encadenado en `App.kt`) como un **hub**:
      `FirstRunWelcomeScreen` → juego → **vuelve al hub** (celebra el que se acaba de
      jugar con `FireworksOverlay` localizado + rebote del check + `SoundEffect
      .LEVEL_UP`, y **desbloquea** la tarjeta siguiente con su propia animación de
      candado→número) → siguiente juego → hub → … → puerta de sesión. El patrón
      juego→hub→juego es el mismo sin importar cuántos haya en `FirstRunGames
      .sequence` (hoy Ordena las Pociones, Hexa Orbit y Línea Neón): añadir un
      cuarto juego a esa lista no toca `App.kt` ni `FirstRunWelcomeScreen`.
      Las tarjetas son interactivas (`WelcomeCardState` en `FirstRunWelcomeScreen`):
      solo la CURRENT es clicable y lleva directo al juego (mismo destino que el
      CTA); DONE queda marcada y quieta; LOCKED se ve gris/atenuada con candado y no
      reacciona al toque. La entrada de toda la pantalla es además escalonada
      (`StaggeredReveal` con un contador secuencial, no solo en las tarjetas).
      `GameOverOverlay` se adapta solo (lee `LocalFirstRunFlow`, igual que
      `GameIntroScreen`): durante la bienvenida el cartel de fin de partida muestra
      un único CTA ("Volver"), sin "repetir nivel"/"siguiente nivel"/"elegir
      nivel" — ninguno de esos tiene sentido cuando el siguiente paso es volver al
      hub. De paso se corrigió el hueco de 2dp (ahora `CardItemGap`, 14dp) entre el
      panel de ranking/"inicia sesión para comparar" y los botones de abajo, que
      quedaban pegados en todos los juegos, no solo en la bienvenida.
      La misión diaria (`DailyGoalManager`) ya reconoce el "día 1": `OnboardingGate
      .firstRunDate` guarda la fecha del primer juego de la bienvenida (una sola
      vez, nunca se pisa); si "hoy" coincide, la misión del día pasa a ser
      `firstRunMissionGames()` (= `FirstRunGames.sequence`, mismo orden) en vez del
      sorteo habitual de `dailyMissionGames`. Así, terminar la bienvenida deja el
      entrenamiento del día ya completo (los 3 juegos que se acaban de jugar quedan
      marcados en "Tu misión de hoy" de la Home, con la recompensa lista para
      reclamar) — antes el sorteo casi nunca coincidía con esos tres juegos y el
      jugador se encontraba con que tenía que jugar tres MÁS justo después de haber
      jugado tres. Escalable igual que el resto: un cuarto juego en `FirstRunGames
      .sequence` entra solo, sin tocar `DailyGoalManager` ni `firstRunMissionGames`.
      Queda por decidir: (a) hoy se **avanza al salir** del juego, no al completar un
      nivel — si se quiere exigir partida jugada, el gancho es `FirstRunFlow
      .onGameStarted`; (b) enlazar la bienvenida con el **desafío semanal** cuando
      exista (Fase 7), que es de donde salieron estos tres juegos; (c) rematar la
      llegada al login con el percentil de lo que acaba de jugar ("eres mejor que el
      X%") como argumento para crear la cuenta.
- [ ] **Crear torneos de juegos y rankings**
- [ ] **Atracción Geométrica — segunda oportunidad con anuncio.** El juego ya es
      infinito y por vidas (`PolarityConfig.INITIAL_LIVES`), así que encaja el
      mismo patrón de revivir que Burbujas de Cálculo y Neon Pulse:
      `ReviveAdOverlay` + una fase `REVIVE_OFFER` al llegar a 0 vidas, una sola vez
      por partida. No se ha añadido ahora para no mezclarlo con el cambio de
      formato (y porque toca el balance: revivir en la oleada 6 no vale lo mismo
      que en la 1).
- [ ] **Atracción Geométrica — telemetría de balance de la lluvia.** Falta medir
      en partidas reales cuántos meteoros se cazan por lluvia
      (`PolarityCollisionState.showerCaught`) y en qué oleada muere la gente, para
      ajustar `SHOWER_SPAWN_INTERVAL_SEC`, `SHOWER_HIT_SCORE` y
      `WAVE_DIFFICULTY_STEP`. Los valores actuales son una primera estimación.
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
## Textos / internacionalización

- [ ] **Migrar los textos ya existentes a `strings.xml`.** El catálogo de recursos
      ya está montado (`shared/src/commonMain/composeResources/values/strings.xml`)
      y la regla para código nuevo está en CLAUDE.md §10, pero de momento **solo lo
      usan el módulo de notificaciones y la sección de notificaciones de Ajustes**.
      El resto de pantallas siguen con sus literales embebidos (~90 `text = "..."`
      en 16 archivos de `commonMain`, más los de cada juego). Migrar por pantallas,
      no de golpe: Home → Perfil → Ajustes → catálogo de juegos → overlays comunes
      (`GameOverOverlay`, `GameIntroScreen`, `GameHelp`) → juego a juego.
- [ ] **Añadir el primer idioma extra (`values-en/strings.xml`).** No se ha creado
      todavía a propósito: con la UI aún mayormente en literales españoles, un
      dispositivo en inglés vería la app medio traducida, que es peor que verla
      entera en español. Tiene sentido en cuanto la migración anterior cubra las
      pantallas principales.

## Notificaciones

- [ ] **Push: "otro jugador superó tu récord".** El tipo
      `NotificationKind.RECORD_BROKEN` y su copy ya existen, pero **nadie lo emite**:
      hace falta FCM (Android) + APNs (iOS), una tabla de device tokens en Supabase
      con RLS, y un trigger/Edge Function que detecte la marca superada y envíe el
      mensaje. El seam está documentado en el KDoc de `NotificationScheduler`: se
      añade un emisor que produce el mismo `ReadyNotification`, sin tocar planner ni
      copy. Ojo: requiere cuenta de Apple Push y proyecto Firebase.
- [ ] **Android — reprogramar las alarmas tras un reinicio.** `AlarmManager` pierde
      todo lo pendiente al reiniciar el móvil. Hoy se recupera solo cuando el usuario
      vuelve a abrir la app (el `NotificationsManager` replanifica al observar el
      estado). Lo correcto es un receiver de `BOOT_COMPLETED` (+ permiso
      `RECEIVE_BOOT_COMPLETED`) que dispare la replanificación sin esperar a esa
      apertura — que es justo la que el aviso pretende provocar.
- [ ] **Deep links desde la notificación.** Al tocar un aviso se abre la Home.
      Debería llevar a la pantalla que corresponde (el juego del récord, la misión
      diaria). Requiere rutas externas en el `NavHost`; el punto único a tocar es
      `NotificationAlarmReceiver.openAppIntent` en Android y el `userInfo` de la
      `UNNotificationRequest` en iOS.
- [ ] **Medir la conversión de la antesala de permiso.** Los umbrales de
      `NotificationPrimingPolicy` (1ª oferta tras 1 partida, 2ª tras 5, máximo dos)
      son una apuesta razonada, no un dato. Con telemetría de "antesala mostrada →
      aceptada → permiso concedido" se pueden mover con criterio; en particular, la
      2ª oferta a las 5 partidas es la más discutible.
- [ ] **Medir antes de subir la frecuencia.** La cadena actual es conservadora (un
      aviso por tarde como mucho, y nada más allá de 14 días de inactividad). Antes
      de añadir tipos nuevos conviene tener datos de apertura: notificar de más es la
      vía rápida a que el usuario silencie la app entera.

## Valoraciones (tiendas)

- [ ] **iOS: activar la invitación a valorar al publicar en la App Store.** Hoy
      `storeReviewLink` (`core/review/StoreReviewLink.ios.kt`) devuelve `null` a
      propósito, así que en iOS el diálogo no aparece nunca: no hay ficha a la que
      mandar al jugador. Al publicar, poner ahí la URL con sufijo de reseña
      (`https://apps.apple.com/app/id<APP_ID>?action=write-review`) — no hace falta
      tocar nada más: política, gestor y diálogo ya están escritos para las dos
      plataformas.
- [ ] **Valorar el uso de las APIs nativas de reseña in-app.** Play tiene
      *In-App Review* (`com.google.android.play:review`) y iOS
      `SKStoreReviewController`: puntúan sin salir de la app y convierten bastante
      mejor que abrir la ficha. No se usan hoy porque **ambas prohíben preceder su
      flujo con una pregunta propia** ("¿te gusta la app?"), que es justo el diálogo
      que pidió el usuario, y porque el sistema decide si mostrarlas (cuota) sin
      decir si aparecieron. Si algún día se prefiere conversión sobre control del
      momento, el cambio es sustituir el `openUri` de `App.kt` por un seam
      expect/actual y retirar el diálogo propio, no adaptarlo.
- [ ] **Medir los umbrales de `ReviewPromptPolicy`.** 1ª oferta a las 10 partidas,
      2ª a las 40 con 30 días de respiro y máximo dos: es una apuesta razonada, no un
      dato. Con telemetría de "mostrada → aceptada" se pueden mover con criterio.

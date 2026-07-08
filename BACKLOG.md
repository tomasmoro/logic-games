# Backlog — KortexGames

Lista de mejoras/pendientes conocidos, para no perderlos. No es el roadmap de
fases (ver CLAUDE.md §2); son deudas y detalles a retomar.

## Cuenta / sincronización

- [ ] **Sync bidireccional (descarga nube → local).** Hoy `syncPending()` solo
  **sube** local→nube. Al iniciar sesión en otro dispositivo o tras reinstalar,
  el historial de la nube NO se descarga y la app se ve vacía. Falta el pull
  (traer `user_progress` del usuario a SQLDelight al autenticarse).
  Ref: `data/repository/ProgressRepositoryImpl.kt`.
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

- [ ] **Sistema de niveles/progresión por juego.** (Diseño acordado; falta
  implementar. Alcance a decidir al retomar — recomendado: "mínimo viable".)

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

## Logros

- [ ] **Logros (achievements).** Las tablas `achievements` / `user_achievements`
  existen en el backend, pero no hay lógica en la app (desbloqueo, UI, sync).

## Técnico / limpieza

- [ ] **Unificar assets de audio.** Los `.wav` están duplicados en
  `androidApp/src/main/res/raw/` (Android) y `shared/.../composeResources/files/`
  (iOS, añadido para arreglar el sonido en iOS). Unificar en una sola fuente
  (p. ej. mover Android a composeResources también) para no mantener dos copias.

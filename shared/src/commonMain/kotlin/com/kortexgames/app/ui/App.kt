package com.kortexgames.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.theme.LogicGamesTheme
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.FirstRunGames
import com.kortexgames.app.game.GameCatalog
import com.kortexgames.app.game.bubblemath.BubbleMathScreen
import com.kortexgames.app.game.crucigrama.CrucigramaNeonScreen
import com.kortexgames.app.game.energyflow.EnergyFlowScreen
import com.kortexgames.app.game.memory.SequenceMemoryScreen
import com.kortexgames.app.game.hypergate.HypergateScreen
import com.kortexgames.app.game.quantummerge.QuantumMergeScreen
import com.kortexgames.app.game.blockgrid.BlockGridScreen
import com.kortexgames.app.game.wordsearch.NeonLexiconScreen
import com.kortexgames.app.game.screws.ScrewGameScreen
import com.kortexgames.app.game.neoncircuit.NeonCircuitScreen
import com.kortexgames.app.game.neonline.NeonLineScreen
import com.kortexgames.app.game.neon2048.Neon2048Screen
import com.kortexgames.app.game.neonpulse.NeonPulseScreen
import com.kortexgames.app.game.defuser.DefuserScreen
import com.kortexgames.app.game.hypercube.HyperCubeScreen
import com.kortexgames.app.game.neonsudoku.NeonSudokuScreen
import com.kortexgames.app.game.polarity.PolarityCollisionScreen
import com.kortexgames.app.game.starport.StarportScreen
import com.kortexgames.app.game.watersort.WaterSortScreen
import com.kortexgames.app.game.wordconnect.WordConnectScreen
import com.kortexgames.app.ui.auth.AuthScreen
import com.kortexgames.app.ui.components.ImmersiveMode
import com.kortexgames.app.ui.components.NotificationPrimingDialog
import com.kortexgames.app.ui.components.RandomGameFab
import com.kortexgames.app.ui.games.GameListScreen
import com.kortexgames.app.ui.home.HomeScreen
import com.kortexgames.app.ui.navigation.AnimatedBottomBar
import com.kortexgames.app.ui.navigation.Routes
import com.kortexgames.app.ui.navigation.TopLevelTab
import com.kortexgames.app.ui.onboarding.FirstRunFlow
import com.kortexgames.app.ui.onboarding.FirstRunWelcomeScreen
import com.kortexgames.app.ui.onboarding.LocalFirstRunFlow
import com.kortexgames.app.ui.profile.ProfileScreen
import com.kortexgames.app.ui.settings.SettingsScreen
import com.kortexgames.app.ui.splash.SplashScreen
import kotlinx.coroutines.launch

/**
 * Raíz de la app Compose Multiplatform, compartida por Android e iOS.
 *
 * Arranca con la **splash de marca** ([SplashScreen]): el logo neón prendiendo. Esa
 * animación es además la ventana de carga del arranque —mientras corre, el
 * [AppGraph.startup] resuelve la puerta de onboarding, la sesión y el historial—,
 * así que cuando cede el paso, la pantalla siguiente ya tiene todos sus datos y
 * solo le queda animarse a la vista.
 *
 * El destino de después lo decide la **puerta de onboarding**
 * ([AppGraph.onboardingGate]), que tiene dos etapas en la primera apertura: primero
 * la **bienvenida jugable** —una pantalla que presenta los tres [FirstRunGames] y
 * luego esos juegos, antes de pedirle nada al jugador— y después el login. A partir
 * de ahí, siempre Home.
 */
@Composable
fun App(graph: AppGraph) {
    LogicGamesTheme {
        // La splash NO se salta aunque los datos lleguen antes: es identidad de
        // marca, y su duración está acotada (~2,4 s) por diseño.
        var splashDone by remember { mutableStateOf(false) }
        // Para cuando la splash termina, la puerta ya está resuelta (el arranque la
        // espera). El caso degradado —DataStore agotó el tope de espera y sigue en
        // `null`— cae en `null == false` → false → Home: preferimos no enseñar la
        // bienvenida a quien ya la pasó antes que enseñarla dos veces.
        val hasDecided by graph.onboardingGate.hasDecided.collectAsStateWithLifecycle()
        val introGamesPlayed by graph.onboardingGate.introGamesPlayed.collectAsStateWithLifecycle()

        // Crossfade: la Home entra POR ENCIMA de la splash aún encendida (el logo no
        // se apaga solo), así que su revelado escalonado de tarjetas ya está en
        // marcha cuando queda visible y no hay ningún instante a negro entre medias.
        Crossfade(targetState = splashDone, animationSpec = tween(420), label = "arranque") { ready ->
            if (ready) {
                MainNavigation(
                    graph = graph,
                    startAtAuth = hasDecided == false,
                    // Mismo criterio degradado que arriba: si DataStore no llegó a
                    // emitir, la bienvenida se da por vista en vez de repetirla.
                    introGamesPlayed = introGamesPlayed ?: FirstRunGames.size,
                )
            } else {
                SplashScreen(
                    awaitData = { graph.startup.awaitReady() },
                    onFinished = { splashDone = true },
                )
            }
        }
    }
}

/**
 * Navegación principal: un [Scaffold] con [AnimatedBottomBar] (visible solo en las
 * pestañas raíz) y un [NavHost] con transiciones **sutiles** de opacidad + escala
 * (CLAUDE.md §9.4 — nada de deslizamientos largos que mareen).
 *
 * Integra el [AppGraph.adManager]: entrar a una ruta de juego marca "juego
 * activo" (corre el contador de anuncios); cualquier otra ruta lo pausa.
 *
 * @param startAtAuth true si es la primera apertura (aún no pasó la puerta de sesión).
 * @param introGamesPlayed cuántos juegos de la bienvenida quedaron ya atrás; con
 *        [startAtAuth] decide si la app arranca en la bienvenida, en el login o en Home.
 */
@Composable
private fun MainNavigation(graph: AppGraph, startAtAuth: Boolean, introGamesPlayed: Int) {
        val navController = rememberNavController()

        // Bienvenida jugable de la primera apertura (null en cualquier otro caso). Se
        // fija una sola vez, igual que el destino inicial: cuando el jugador la
        // termina, el propio flujo queda inactivo (step == total) sin recrear nada.
        val firstRun = remember {
            if (!startAtAuth || introGamesPlayed >= FirstRunGames.size) {
                null
            } else {
                FirstRunFlow(
                    routes = FirstRunGames.sequence.mapNotNull { Routes.gameRoute(it) },
                    initialStep = introGamesPlayed,
                    // Reinstalación sobre una versión que sí pasó por el alta: si ya
                    // hay constancia de aceptación, no se vuelve a pedir.
                    legalAlreadyAccepted = graph.legalConsentStore.acceptedVersion.value != null,
                    // Se escribe en el scope de la app y no en el de la composición: la
                    // pantalla del juego se desmonta al navegar al siguiente, y la
                    // escritura debe completarse igualmente.
                    onStepCompleted = { step ->
                        graph.appScope.launch { graph.onboardingGate.markIntroGamePlayed(step) }
                    },
                    onLegalAccepted = {
                        graph.appScope.launch { graph.legalConsentStore.accept() }
                    },
                    onSkipped = {
                        graph.appScope.launch { graph.onboardingGate.skipIntroGames() }
                    },
                )
            }
        }

        // El destino inicial se fija una sola vez: si el usuario resuelve la puerta
        // durante la sesión, el flag cambia pero no queremos recrear el NavHost.
        //
        // Mientras la bienvenida siga activa (queden juegos por despachar) se arranca
        // SIEMPRE en el hub, sea la primera vez (nada jugado) o una reapertura a
        // mitad de camino (el hub adapta su texto y sus tarjetas al progreso real):
        // un único punto de entrada, sin casos especiales por cuántos juegos van.
        val startDestination = remember {
            when {
                firstRun != null && firstRun.isActive -> Routes.FIRST_RUN_WELCOME
                startAtAuth -> Routes.AUTH_ONBOARDING
                else -> Routes.HOME
            }
        }
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        // Salida de un juego. Lo normal es volver atrás; pero si el juego que se
        // abandona es el paso actual de la bienvenida, vuelve SIEMPRE al hub —nunca
        // directo al siguiente juego— para que celebre el que se acaba de despachar
        // y ofrezca seguir o parar ahí. Es el mismo patrón sin importar cuántos
        // juegos tenga la bienvenida (ver KDoc de [FirstRunFlow]).
        //
        // Se avanza al SALIR y no al completar un nivel a propósito: la bienvenida es
        // un gancho, no una jaula (ver [FirstRunFlow.advance]).
        val exitGame: () -> Unit = exit@{
            val route = navController.currentBackStackEntry?.destination?.route
            val flow = firstRun
            if (route == null || flow == null || !flow.isActive || flow.currentRoute != route) {
                navController.popBackStack()
                return@exit
            }
            flow.advance()
            navController.navigate(Routes.FIRST_RUN_WELCOME) {
                popUpTo(route) { inclusive = true }
            }
        }

        // Anuncios — breakpoint de "salir de un juego": el contador de intersticiales
        // corre desde que la app entra a primer plano (lo gobierna el lifecycle de
        // plataforma), no solo mientras se juega. Aquí detectamos el único momento
        // seguro para cobrar un intersticial pendiente sin cortar la partida: cuando la
        // ruta anterior era un juego y la nueva no (el usuario volvió al menú). Es un
        // hook central: cubre los 30 juegos vía Routes.isGameRoute, sin listas a mano.
        var previousRoute by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(currentRoute) {
            if (Routes.isGameRoute(previousRoute) && !Routes.isGameRoute(currentRoute)) {
                graph.adManager.onAdBreakpoint()
            }
            previousRoute = currentRoute
        }

        // Colector único de intersticiales: cuando un breakpoint marca que toca
        // anuncio, lo presenta (delega en el presentador de plataforma del AdManager).
        LaunchedEffect(Unit) {
            graph.adManager.adEvents.collect { graph.adManager.showInterstitialAd() }
        }

        // Inmersión en TODA la app (no solo en la partida): la barra de navegación del
        // sistema se esconde también en Home/Catálogo/Perfil. Se activa una sola vez en
        // la raíz, y no por ruta, a propósito: si solo se ocultara en unas pantallas, la
        // barra aparecería y desaparecería al cambiar de pestaña —un salto de layout
        // constante— y el fondo azul noche se cortaría abajo con la banda del sistema.
        // Sigue siendo recuperable con un deslizamiento desde el borde inferior.
        ImmersiveMode(enabled = true)

        // Antesala del permiso de notificaciones. Se monta en la raíz —y no dentro de
        // una pantalla— porque el momento en que procede ofrecerla no pertenece a
        // ninguna: aparece cuando el jugador vuelve al menú tras su primera partida,
        // sea cual sea la pestaña en la que caiga.
        //
        // La condición de ruta es tan importante como la del propio permiso: durante
        // una partida NO se interrumpe jamás. El estado se mantiene en el manager, así
        // que la oferta sigue pendiente y se muestra al salir del juego.
        //
        // Tampoco sobre el hub de bienvenida ni la puerta de sesión: mientras el
        // jugador está en la primera apertura ya tiene una decisión propia que tomar
        // en cada pantalla (seguir jugando o no, cuenta o invitado), y encadenarle
        // encima una segunda petición —el permiso de notificaciones— convierte su
        // primer minuto en una fila de diálogos. La oferta queda pendiente para el
        // primer regreso al menú, que es donde siempre estuvo pensada.
        val showPriming by graph.notificationsManager.shouldShowPriming.collectAsStateWithLifecycle()
        val isFirstRunScreen = currentRoute == Routes.FIRST_RUN_WELCOME || currentRoute == Routes.AUTH_ONBOARDING
        if (showPriming && !Routes.isGameRoute(currentRoute) && !isFirstRunScreen) {
            NotificationPrimingDialog(
                onAccept = { graph.notificationsManager.acceptPriming() },
                onDecline = { graph.notificationsManager.declinePriming() },
            )
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            floatingActionButton = {
                // Dado flotante de "juego al azar": solo en las pestañas raíz,
                // aparece/desaparece con un escalado suave.
                AnimatedVisibility(
                    visible = TopLevelTab.isTopLevel(currentRoute),
                    enter = fadeIn() + scaleIn(initialScale = 0.6f),
                    exit = fadeOut() + scaleOut(targetScale = 0.6f),
                ) {
                    RandomGameFab(
                        onClick = { Routes.randomGameRoute()?.let { navController.navigate(it) } },
                        // "Lanzar el dado": sonido de dado + háptica fuerte (respeta ajustes).
                        onRoll = {
                            graph.audio.playSound(SoundEffect.DICE_ROLL)
                            graph.audio.hapticFeedback(HapticFeedback.HEAVY)
                        },
                        // "Aterrizaje": toque háptico suave al terminar el giro.
                        onLand = { graph.audio.hapticFeedback(HapticFeedback.LIGHT) },
                    )
                }
            },
            bottomBar = {
                // La barra entra/sale deslizando desde abajo al alternar entre
                // pestañas raíz y pantallas de juego (inmersión total en la partida).
                AnimatedVisibility(
                    visible = TopLevelTab.isTopLevel(currentRoute),
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                ) {
                    AnimatedBottomBar(
                        currentRoute = currentRoute,
                        onSelect = { tab -> navController.navigateToTab(tab.route) },
                    )
                }
            },
        ) { padding ->
            // El flujo de bienvenida se publica para TODA la navegación: quien lo
            // consume es la antesala de cada juego ([GameIntroScreen]), a la que la
            // navegación no puede inyectarle nada porque la monta el propio juego.
            CompositionLocalProvider(LocalFirstRunFlow provides firstRun) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(padding),
                // Transición por defecto: fade + escala corta (no invasiva).
                enterTransition = { fadeIn(tween(250)) + scaleIn(initialScale = 0.96f, animationSpec = tween(250)) },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(250)) },
                popExitTransition = { fadeOut(tween(200)) + scaleOut(targetScale = 0.96f, animationSpec = tween(200)) },
            ) {
                // Hub de la bienvenida de primera apertura: se entra aquí antes del
                // primer juego y se VUELVE aquí al salir de cada uno (ver `exitGame`),
                // así que esta misma pantalla presenta, celebra y despide según el
                // progreso — nunca hace falta una ruta por juego. Al continuar (o
                // saltar) se sale del backstack: el hub ya cumplió su papel.
                composable(Routes.FIRST_RUN_WELCOME) {
                    val flow = firstRun
                    FirstRunWelcomeScreen(
                        games = FirstRunGames.sequence.mapNotNull { id ->
                            GameCatalog.games.firstOrNull { it.id == id }
                        },
                        completedCount = flow?.step ?: 0,
                        justCompletedIndex = flow?.justCompletedStep,
                        onCelebrationConsumed = { flow?.celebrationShown() },
                        // "Nivel superado" reutiliza el mismo SFX/háptica que un
                        // récord batido: es el mismo lenguaje de logro en toda la app.
                        onCelebrationBurst = {
                            graph.audio.playSound(SoundEffect.LEVEL_UP)
                            graph.audio.hapticFeedback(HapticFeedback.SUCCESS)
                        },
                        showLegalNotice = flow?.needsLegalNotice == true,
                        onStart = {
                            flow?.onWelcomeAccepted()
                            navController.navigate(flow?.currentRoute ?: Routes.AUTH_ONBOARDING) {
                                popUpTo(Routes.FIRST_RUN_WELCOME) { inclusive = true }
                            }
                        },
                        onSkip = {
                            flow?.skip()
                            navController.navigate(Routes.AUTH_ONBOARDING) {
                                popUpTo(Routes.FIRST_RUN_WELCOME) { inclusive = true }
                            }
                        },
                    )
                }
                // Puerta de primera apertura: al resolver salta a Home y se saca del
                // backstack para que "atrás" no vuelva a la bienvenida.
                composable(Routes.AUTH_ONBOARDING) {
                    AuthScreen(
                        graph = graph,
                        isOnboarding = true,
                        onFinished = {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.AUTH_ONBOARDING) { inclusive = true }
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                // Login a demanda desde Home/Perfil: al terminar, vuelve atrás.
                composable(Routes.AUTH) {
                    AuthScreen(
                        graph = graph,
                        isOnboarding = false,
                        onFinished = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.HOME) {
                    HomeScreen(
                        graph = graph,
                        onQuickPlay = { navController.navigate(Routes.MEMORY) },
                        onSeeGames = { navController.navigateToTab(Routes.GAMES) },
                        onOpenGame = { route -> navController.navigate(route) },
                        onOpenAuth = { navController.navigate(Routes.AUTH) },
                    )
                }
                composable(Routes.GAMES) {
                    GameListScreen(
                        graph = graph,
                        onOpenGame = { route -> navController.navigate(route) },
                    )
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(
                        graph = graph,
                        onOpenAuth = { navController.navigate(Routes.AUTH) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        graph = graph,
                        onBack = { navController.popBackStack() },
                        onOpenAuth = { navController.navigate(Routes.AUTH) },
                        // Cuenta borrada: la sesión ya cerró, saca al usuario del backstack
                        // de Ajustes/Perfil y lo deja en Home como invitado.
                        onAccountDeleted = {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.HOME) { inclusive = true }
                            }
                        },
                    )
                }
                // Salida de juego: TODOS usan el mismo [exitGame] en vez de un
                // popBackStack suelto. Así, el día que la bienvenida cambie de juegos,
                // el encadenado sigue funcionando sin tocar la navegación.
                composable(Routes.MEMORY) {
                    SequenceMemoryScreen(graph, exitGame)
                }
                composable(Routes.WATER_SORT) {
                    WaterSortScreen(graph, exitGame)
                }
                composable(Routes.BUBBLE_MATH) {
                    BubbleMathScreen(graph, exitGame)
                }
                composable(Routes.ENERGY_FLOW) {
                    EnergyFlowScreen(graph, exitGame)
                }
                composable(Routes.POLARITY_COLLISION) {
                    PolarityCollisionScreen(graph, exitGame)
                }
                composable(Routes.CRUCIGRAMA_NEON) {
                    CrucigramaNeonScreen(graph, exitGame)
                }
                composable(Routes.WORD_CONNECT) {
                    WordConnectScreen(graph, exitGame)
                }
                composable(Routes.NEON_SCREWS) {
                    ScrewGameScreen(graph, exitGame)
                }
                composable(Routes.NEON_BLOCK_GRID) {
                    BlockGridScreen(graph, exitGame)
                }
                composable(Routes.NEON_LEXICON) {
                    NeonLexiconScreen(graph, exitGame)
                }
                composable(Routes.STARPORT_ESCAPE) {
                    StarportScreen(graph, exitGame)
                }
                composable(Routes.NEON_CIRCUIT) {
                    NeonCircuitScreen(graph, exitGame)
                }
                composable(Routes.NEON_LINE) {
                    NeonLineScreen(graph, exitGame)
                }
                composable(Routes.HYPERGATE) {
                    HypergateScreen(graph, exitGame)
                }
                composable(Routes.NEON_PULSE) {
                    NeonPulseScreen(graph, exitGame)
                }
                composable(Routes.NEON_2048) {
                    Neon2048Screen(graph, exitGame)
                }
                composable(Routes.NEON_SUDOKU) {
                    NeonSudokuScreen(graph, exitGame)
                }
                composable(Routes.NEON_DEFUSER) {
                    DefuserScreen(graph, exitGame)
                }
                composable(Routes.HYPER_CUBE) {
                    HyperCubeScreen(graph, exitGame)
                }
                composable(Routes.QUANTUM_MERGE) {
                    QuantumMergeScreen(graph) { navController.popBackStack() }
                }
            }
            }
        }
}

/**
 * Navega a una pestaña raíz con el patrón estándar de bottom-nav: un único
 * destino en cima de pila y **preservando el estado** de cada pestaña (scroll,
 * formularios) al volver a ella.
 */
private fun NavController.navigateToTab(route: String) {
    val startDestinationId = graph.findStartDestination().id
    navigate(route) {
        // Vuelve al inicio del grafo guardando el estado para no apilar pestañas.
        popUpTo(startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

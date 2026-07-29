package com.example.kortexgames.game

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RocketLaunch
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.ui.components.GameHelp
import com.example.kortexgames.ui.components.HelpStep
import com.example.kortexgames.ui.components.KortexIcons
import com.example.kortexgames.ui.components.iconHelpArt
import com.example.kortexgames.ui.components.motifHelpArt

/**
 * # Catálogo de diseños de ayuda ("¿Cómo se juega?")
 *
 * Fuente **única** del contenido de la pantalla de ayuda de cada minijuego. Cada juego
 * inyecta su [GameHelp] a la pantalla de ayuda **genérica**
 * ([com.example.kortexgames.ui.components.GameHelpSheet]) —expuesta por la antesala
 * ([com.example.kortexgames.ui.components.GameIntroScreen]) y por el menú de pausa
 * ([com.example.kortexgames.ui.components.GamePauseControls])—, de modo que una única
 * pantalla reutilizable se personaliza por juego sin duplicar UI.
 *
 * Tener todos los diseños centralizados aquí (petición del usuario: "un archivo donde se
 * almacenen los diseños de cada ayuda de cada juego") facilita revisar y ajustar los
 * textos de ayuda en un solo sitio, sin rebuscar en cada pantalla de juego.
 *
 * ## Anatomía de un diseño
 * - [GameHelp.summary]: el objetivo en una frase (lo que antes era el `helpText` suelto).
 * - [GameHelp.steps]: los pasos ordenados de "cómo se juega".
 * - [GameHelp.tips]: consejos/atajos opcionales.
 * - [GameHelp.art]: arte de cabecera; por convención el **motivo** del juego
 *   ([motifHelpArt]) para compartir identidad con la antesala y la Home, o su icono
 *   ([iconHelpArt]) cuando aún no tiene motivo. Un juego puede pasar aquí un dibujo
 *   propio si necesita algo más específico (el parámetro es un `@Composable` libre).
 * - [GameHelp.accent]: el color de la categoría del juego, para que la ayuda herede su
 *   identidad visual.
 */
object GameHelpContent {

    /** Neon Sudoku Matrix (Pensamiento Lógico). */
    val neonSudoku = GameHelp(
        title = "Neon Sudoku Matrix",
        summary = "Completa la matriz 9×9 sin repetir ningún dígito.",
        accent = CategoryPalette.Logic,
        art = motifHelpArt(GameMotif.SUDOKU_GRID),
        diagram = { accent -> SudokuHelpDiagram(accent) },
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Pencil,
                title = "Rellena la rejilla",
                text = "Cada fila, columna y bloque 3×3 debe contener los dígitos del 1 al 9 sin repetirse.",
            ),
            HelpStep(
                icon = KortexIcons.Play,
                title = "Coloca números",
                text = "Toca una celda y elige un número del teclado inferior.",
            ),
            HelpStep(
                icon = KortexIcons.Pencil,
                title = "Anota hipótesis",
                text = "Activa el lápiz para apuntar candidatos pequeños mientras razonas.",
            ),
        ),
        tips = listOf(
            "¿Atascado? Selecciona una celda y pulsa Pista para revelar su número tras un anuncio.",
        ),
    )

    /** Tetris Neón / block puzzle (Visión Espacial). */
    val blockGrid = GameHelp(
        title = "Tetris Neón",
        summary = "Encaja las piezas y rompe líneas para no quedarte sin sitio.",
        accent = CategoryPalette.SpatialVision,
        art = motifHelpArt(GameMotif.TETROMINO),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Play,
                title = "Arrastra piezas",
                text = "Lleva cada pieza al tablero desde la bandeja inferior.",
            ),
            HelpStep(
                icon = KortexIcons.Check,
                title = "Completa líneas",
                text = "Llena una fila o columna entera para romperla y hacer sitio.",
            ),
            HelpStep(
                icon = KortexIcons.Warning,
                title = "No te bloquees",
                text = "La partida termina cuando ninguna de las piezas disponibles cabe.",
            ),
        ),
    )

    /** Memoria de Secuencias (Memoria). */
    val sequenceMemory = GameHelp(
        title = "Memoria de Secuencias",
        summary = "Observa la secuencia de notas y repítela en orden.",
        accent = CategoryPalette.Memory,
        art = motifHelpArt(GameMotif.SEQUENCE_GRID),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Star,
                title = "Mira y memoriza",
                text = "Los pads se iluminan uno a uno formando una secuencia.",
            ),
            HelpStep(
                icon = KortexIcons.Play,
                title = "Repite el orden",
                text = "Toca los pads en el mismo orden en que se encendieron.",
            ),
            HelpStep(
                icon = KortexIcons.Trophy,
                title = "Alarga la cadena",
                text = "Cada acierto añade un paso más a la secuencia.",
            ),
        ),
    )

    /** Sopa de Letras Neón (Lenguaje y Vocabulario). */
    val neonLexicon = GameHelp(
        title = "Sopa de Letras Neón",
        summary = "Encuentra todas las palabras escondidas en la cuadrícula.",
        accent = CategoryPalette.Language,
        art = motifHelpArt(GameMotif.WORD_SEARCH),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Play,
                title = "Traza palabras",
                text = "Desliza el dedo sobre las letras para marcar cada palabra.",
            ),
            HelpStep(
                icon = KortexIcons.Star,
                title = "En cualquier dirección",
                text = "Las palabras van en horizontal, vertical o diagonal.",
            ),
            HelpStep(
                icon = KortexIcons.Check,
                title = "Complétalas todas",
                text = "Encuentra todas las de la lista para superar el nivel.",
            ),
        ),
    )

    /** Hypergate (Reflejos). */
    val hypergate = GameHelp(
        title = "Hypergate",
        summary = "Iguala la polaridad del escudo a cada proyectil antes del impacto.",
        accent = CategoryPalette.Reflexes,
        art = motifHelpArt(GameMotif.HYPERGATE),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Play,
                title = "Cambia de polaridad",
                text = "Toca en cualquier parte para alternar el color del escudo.",
            ),
            HelpStep(
                icon = KortexIcons.Shield,
                title = "Iguala para absorber",
                text = "Haz que el escudo coincida con el proyectil justo antes de que llegue.",
            ),
            HelpStep(
                icon = KortexIcons.Warning,
                title = "No falles",
                text = "Si los colores no coinciden en el impacto, chocarás.",
            ),
        ),
    )

    /** Burbujas de Cálculo (Cálculo Mental). */
    val bubbleMath = GameHelp(
        title = "Burbujas de Cálculo",
        summary = "Revienta la burbuja con el resultado correcto antes de que caiga.",
        accent = CategoryPalette.MentalMath,
        art = motifHelpArt(GameMotif.MATH_BUBBLES),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Play,
                title = "Resuelve y toca",
                text = "Calcula la operación y revienta la burbuja del resultado correcto.",
            ),
            HelpStep(
                icon = KortexIcons.Warning,
                title = "Antes del suelo",
                text = "Acierta antes de que las burbujas toquen la parte de abajo.",
            ),
            HelpStep(
                icon = KortexIcons.Streak,
                title = "Encadena combos",
                text = "Los aciertos seguidos suben el combo y la puntuación.",
            ),
        ),
    )

    /** Neon Starport Escape (Pensamiento Lógico). */
    val starport = GameHelp(
        title = "Neon Starport Escape",
        summary = "Despeja el camino para que la nave insignia escape por la esclusa.",
        accent = CategoryPalette.Logic,
        art = iconHelpArt(Icons.Rounded.RocketLaunch),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Play,
                title = "Desliza las naves",
                text = "Cada nave se mueve solo a lo largo de su eje.",
            ),
            HelpStep(
                icon = KortexIcons.Check,
                title = "Abre el paso",
                text = "Reordénalas para dejar libre el carril de la nave insignia.",
            ),
            HelpStep(
                icon = KortexIcons.Trophy,
                title = "Escapa",
                text = "Saca la nave insignia por la esclusa para superar el nivel.",
            ),
        ),
    )

    /** Neon Defuser / buscaminas (Atención y Concentración). */
    val defuser = GameHelp(
        title = "Neon Defuser",
        summary = "Desactiva el panel sin detonar ninguna mina.",
        accent = CategoryPalette.Attention,
        art = motifHelpArt(GameMotif.MINESWEEPER),
        diagram = { accent -> DefuserHelpDiagram(accent) },
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Play,
                title = "Revela celdas",
                text = "Toca una celda para descubrirla. El primer toque siempre es seguro.",
            ),
            HelpStep(
                icon = KortexIcons.Hint,
                title = "Lee los números",
                text = "El número indica cuántas de las 8 celdas contiguas ocultan una mina.",
            ),
            HelpStep(
                icon = KortexIcons.Shield,
                title = "Marca el peligro",
                text = "Un toque prolongado coloca un escudo donde crees que hay una mina.",
            ),
            HelpStep(
                icon = KortexIcons.Scan,
                title = "Escanea una celda",
                text = "¿Bloqueado? Mira un anuncio y elige una celda para inspeccionarla: " +
                    "si es mina queda desactivada. Usos limitados por partida.",
            ),
        ),
    )

    /** Crucigrama Neón (Lenguaje y Vocabulario). */
    val crucigrama = GameHelp(
        title = "Crucigrama Neón",
        summary = "Escribe palabras y colócalas en su sitio dentro del crucigrama.",
        accent = CategoryPalette.Language,
        art = motifHelpArt(GameMotif.CROSSWORD),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Pencil,
                title = "Escribe con el teclado",
                text = "Forma palabras con las letras del teclado inferior.",
            ),
            HelpStep(
                icon = KortexIcons.Check,
                title = "Se colocan solas",
                text = "Si la palabra es correcta, se sitúa automáticamente en su hueco.",
            ),
        ),
        tips = listOf(
            "¿Bloqueado? Pide una pista para descubrir una letra.",
        ),
    )

    /** Neon Circuit Flow / flow free (Resolución de Problemas). */
    val neonCircuit = GameHelp(
        title = "Neon Circuit Flow",
        summary = "Conecta cada par de nodos del mismo color sin cruzar cables.",
        accent = CategoryPalette.ProblemSolving,
        art = motifHelpArt(GameMotif.CIRCUIT_FLOW),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Play,
                title = "Tiende cables",
                text = "Arrastra desde un nodo hasta su gemelo del mismo color.",
            ),
            HelpStep(
                icon = KortexIcons.Warning,
                title = "Sin cruces",
                text = "Dos cables no pueden cruzarse ni pisarse.",
            ),
            HelpStep(
                icon = KortexIcons.Check,
                title = "Energiza el circuito",
                text = "Conéctalos todos y rellena el tablero para completar el nivel.",
            ),
        ),
    )

    /** Atracción Geométrica / polaridad (Visión Espacial). */
    val polarity = GameHelp(
        title = "Atracción Geométrica",
        summary = "Captura las piezas de tu color y esquiva las contrarias.",
        accent = CategoryPalette.SpatialVision,
        art = motifHelpArt(GameMotif.POLARITY_SECTORS),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Refresh,
                title = "Rota el círculo",
                text = "Gira los sectores para presentar el color adecuado a cada pieza.",
            ),
            HelpStep(
                icon = KortexIcons.Check,
                title = "Captura tu color",
                text = "Recibe las piezas que coinciden con el sector para puntuar.",
            ),
            HelpStep(
                icon = KortexIcons.Timer,
                title = "Contra el reloj",
                text = "Evita las piezas contrarias antes de que se acabe el tiempo.",
            ),
        ),
    )

    /** Neon Grid 2048 (Cálculo Mental). */
    val neon2048 = GameHelp(
        title = "Neon Grid 2048",
        summary = "Fusiona fichas iguales hasta llegar a 2048.",
        accent = CategoryPalette.MentalMath,
        art = motifHelpArt(GameMotif.NUMBER_TILES),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Play,
                title = "Desliza el tablero",
                text = "Al deslizar, todas las fichas se van hasta el borde en esa dirección.",
            ),
            HelpStep(
                icon = KortexIcons.Check,
                title = "Fusiona iguales",
                text = "Dos fichas con el mismo número se suman en una sola.",
            ),
            HelpStep(
                icon = KortexIcons.Warning,
                title = "Cuida el espacio",
                text = "Cada movimiento añade una ficha nueva; no llenes el tablero.",
            ),
        ),
    )

    /** Tornillos Neón (Visión Espacial). Aún sin motivo propio: ayuda solo con texto. */
    val screws = GameHelp(
        title = "Tornillos Neón",
        summary = "Desatornilla y recoloca los pernos para soltar todas las placas.",
        accent = CategoryPalette.SpatialVision,
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Play,
                title = "Mueve tornillos",
                text = "Desatornilla un perno y colócalo en un hueco de su color.",
            ),
            HelpStep(
                icon = KortexIcons.Check,
                title = "Libera las placas",
                text = "Una placa cae cuando le quitas su último tornillo.",
            ),
        ),
        tips = listOf(
            "Las placas tapan agujeros y cuelgan si les queda un solo tornillo: planifica el orden.",
        ),
    )

    /** Ordena las Pociones / water sort (Pensamiento Lógico). */
    val waterSort = GameHelp(
        title = "Ordena las Pociones",
        summary = "Vierte colores iguales hasta dejar cada tubo de un solo color.",
        accent = CategoryPalette.Logic,
        art = motifHelpArt(GameMotif.POTIONS),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Play,
                title = "Vierte de tubo a tubo",
                text = "Toca un tubo origen y luego el destino para trasvasar el color de arriba.",
            ),
            HelpStep(
                icon = KortexIcons.Check,
                title = "Solo sobre su igual",
                text = "Un color solo se vierte sobre el mismo color o sobre un tubo vacío.",
            ),
            HelpStep(
                icon = KortexIcons.Trophy,
                title = "Ordena todo",
                text = "Completa cada tubo con un único color para ganar.",
            ),
        ),
    )

    /** Palabras Conectadas / word connect (Lenguaje y Vocabulario). */
    val wordConnect = GameHelp(
        title = "Palabras Conectadas",
        summary = "Une las letras de la rueda para formar todas las palabras del panel.",
        accent = CategoryPalette.Language,
        art = motifHelpArt(GameMotif.WORD_WHEEL),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Play,
                title = "Arrastra por las letras",
                text = "Desliza el dedo sobre las letras de la rueda para encadenar una palabra.",
            ),
            HelpStep(
                icon = KortexIcons.Check,
                title = "Rellena el panel",
                text = "Cada palabra válida se revela en su hueco del panel superior.",
            ),
            HelpStep(
                icon = KortexIcons.Trophy,
                title = "Descúbrelas todas",
                text = "Encuentra todas las palabras para completar el nivel.",
            ),
        ),
    )

    /** Neon Pulse (Reflejos). */
    val neonPulse = GameHelp(
        title = "Neon Pulse",
        summary = "Toca los nodos coral antes de que su anillo se cierre.",
        accent = CategoryPalette.Reflexes,
        art = motifHelpArt(GameMotif.NEON_PULSE),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Play,
                title = "Toca los coral",
                text = "Pulsa cada nodo coral antes de que su anillo se cierre del todo.",
            ),
            HelpStep(
                icon = KortexIcons.Warning,
                title = "Evita los rojos",
                text = "¡No toques los nodos rojos!",
            ),
            HelpStep(
                icon = KortexIcons.Timer,
                title = "30 segundos",
                text = "La ronda dura 30 s y cada vez aparecen más rápido.",
            ),
        ),
    )

    /** Flujo de Energía / pipes (Visión Espacial). */
    val energyFlow = GameHelp(
        title = "Flujo de Energía",
        summary = "Gira las piezas para llevar la energía de la batería a la bombilla.",
        accent = CategoryPalette.SpatialVision,
        art = motifHelpArt(GameMotif.ENERGY_PIPES),
        steps = listOf(
            HelpStep(
                icon = KortexIcons.Refresh,
                title = "Rota las tuberías",
                text = "Toca una pieza para girarla y orientar su tramo de tubo.",
            ),
            HelpStep(
                icon = KortexIcons.Check,
                title = "Conecta el circuito",
                text = "Enlaza la batería con la bombilla formando un camino continuo.",
            ),
        ),
    )
}

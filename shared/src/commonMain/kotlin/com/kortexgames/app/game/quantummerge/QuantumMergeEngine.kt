package com.kortexgames.app.game.quantummerge

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.game.BaseGameEngine
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.physics.FrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * # QuantumMergeEngine — motor de física 2D y fusión (Fase 2)
 *
 * Simulador de cuerpos rígidos circulares escrito desde cero (sin Box2D ni ninguna librería
 * externa) para el minijuego **Quantum Merge**: esferas de energía que caen por gravedad dentro de
 * un contenedor abierto por arriba, chocan de forma elástica y se fusionan al peldaño siguiente
 * cuando dos del mismo [QuantumTier] se tocan.
 *
 * Todo ocurre en las unidades de mundo fijas de [QuantumWorld] (ver la Decisión 1 en
 * `QuantumMergeModels.kt`): el motor no sabe qué es un píxel.
 *
 * ## 1. Por qué paso de tiempo FIJO (y no el `dt` del frame)
 * `withFrameNanos` entrega un delta que depende del refresco real del dispositivo: 16,6 ms a
 * 60 Hz, 8,3 ms a 120 Hz, y cualquier cosa si el sistema tose. Integrar con ese delta variable
 * haría que **el juego se sintiera distinto en cada móvil**: con Euler explícito el error de
 * integración crece con el paso, así que una pila de esferas que en un móvil de 120 Hz reposa
 * estable, en uno de 60 Hz tiembla y se hunde. Aquí el frame solo **alimenta un acumulador** y la
 * simulación avanza siempre en pasos idénticos de [FIXED_DT_SEC] (1/120 s), ejecutando 0, 1 o
 * varios sub-pasos por frame. Resultado: física idéntica en todos los dispositivos, y el mismo
 * tuning vale para todos.
 *
 * [MAX_SUBSTEPS] corta la llamada *espiral de la muerte*: si un frame llega tardísimo, simular
 * todos los sub-pasos atrasados costaría aún más tiempo, que generaría un frame más largo, que
 * pediría más sub-pasos… Preferimos perder unos milisegundos de simulación (cámara lenta
 * imperceptible) a bloquear el hilo.
 *
 * ## 2. Por qué Euler semi-implícito
 * El integrador es `v += a·dt` **y luego** `p += v·dt` (semi-implícito o simpléctico), no el Euler
 * explícito que usaría la velocidad anterior. Cuesta exactamente lo mismo y es el mínimo aceptable
 * en un sistema con contactos: el explícito **inyecta energía** en cada rebote y una pila acabaría
 * vibrando sola hasta explotar.
 *
 * ## 3. Por qué un buffer mutable en vez de estado inmutable dentro del tick
 * `HypergateEngine.step()` es una función pura `(State, dt) -> State` y ahí es lo correcto: mueve
 * n proyectiles independientes. Aquí no vale: la resolución de contactos recorre **n²/2 parejas y
 * repite el barrido [SOLVER_ITERATIONS] veces por sub-paso**, y cada pareja retoca posiciones y
 * velocidades de ambos cuerpos. Hacerlo con `data class` inmutables asignaría del orden de miles de
 * objetos por frame y el recolector de basura se comería el presupuesto de 8 ms.
 *
 * La solución es la estándar en motores de física: dentro del tick se trabaja sobre un buffer de
 * [Body] mutables **reutilizado entre frames** (nunca se libera, solo crece), y el estado inmutable
 * de MVI se materializa **una sola vez por frame** en [publish]. La frontera de inmutabilidad sigue
 * intacta de cara afuera: nadie fuera del motor ve un [Body].
 *
 * ## 4. Coste por frame y por qué O(n²) es suficiente
 * La detección es de fuerza bruta: todas las parejas. Con un contenedor lleno hay ~35 esferas →
 * 595 parejas × 3 iteraciones × ~2 sub-pasos ≈ 3.500 comprobaciones por frame, cada una una resta y
 * una raíz cuadrada. Es despreciable. Una rejilla espacial (*spatial hash*) solo empezaría a
 * compensar por encima de ~150 cuerpos, y ese número es inalcanzable: la partida termina cuando el
 * contenedor se llena, mucho antes.
 *
 * @param random inyectable para tests deterministas del dispensador.
 */
class QuantumMergeEngine(
    scope: CoroutineScope,
    audio: AudioAndHapticManager,
    difficulty: Int = 1,
    private val random: Random = Random.Default,
) : BaseGameEngine<QuantumMergeState>(GameIds.QUANTUM_MERGE, difficulty, scope, audio) {

    /**
     * Nivel elegido, resuelto desde el `difficultyLevel` 1-based que exige `BaseGameEngine`. Manda
     * sobre la escala de radios y la altura de la línea de peligro (ver [QuantumDifficulty]).
     *
     * Se declara **antes** que [_state] porque este lo necesita para su valor inicial: las
     * propiedades se inicializan en orden de declaración.
     */
    private val level: QuantumDifficulty = QuantumDifficulty.fromLevel(difficulty)

    /**
     * El estado arranca ya con el [level] puesto, sin esperar a `start()`.
     *
     * Importa porque la antesala lee el nivel **del estado** para marcar la ficha elegida: si el
     * motor recién construido publicara el valor por defecto, cambiar de dificultad no se vería
     * reflejado en el selector hasta empezar la partida.
     */
    private val _state = MutableStateFlow(QuantumMergeState(difficulty = level))
    override val state: StateFlow<QuantumMergeState> = _state.asStateFlow()

    /**
     * Canal de efectos de la simulación (fusiones, rebotes, derrota). `BUFFERED` para no perder un
     * evento si el colector va un instante por detrás; se consume una sola vez (semántica one-shot).
     */
    private val _effects = Channel<QuantumMergeEffect>(Channel.BUFFERED)

    /** Flujo de efectos que el ViewModel colecta para audio/háptica y reenvío a la UI. */
    val effects: Flow<QuantumMergeEffect> = _effects.receiveAsFlow()

    private val frameClock = FrameClock(maxDtSec = MAX_DT_SEC)

    /** Tiempo de frame aún no consumido por los sub-pasos de tamaño [FIXED_DT_SEC]. */
    private var accumulatorSec = 0f

    // --- Buffer de simulación (ver punto 3 del KDoc de clase) --------------------------------
    /**
     * Cuerpos de trabajo. Los `[0, bodyCount)` primeros están vivos; el resto son objetos
     * **reciclados** que esperan a que una fusión o un lanzamiento necesite uno nuevo. La lista
     * nunca encoge: su tamaño se estabiliza en el máximo de esferas que la partida llegó a tener.
     */
    private val bodies = ArrayList<Body>()
    private var bodyCount = 0

    // --- Resto del estado de partida (se materializa en [publish]) ---------------------------
    private var nextSphereId = 1L
    private var nextFlashId = 1L
    private var currentDrop: Sphere? = null
    private var nextTier = QuantumTier.QUARK
    private var aimX = QuantumWorld.WIDTH / 2f
    private var reloadSec = 0f
    private val flashes = ArrayList<MergeFlash>()
    private var score = 0
    private var merges = 0
    private var drops = 0
    private var bestTier = QuantumTier.QUARK
    private var dangerProgress = 0f

    /** Enfriamiento del sonido de rebote (ver [tryEmitBounce]). */
    private var bounceCooldownSec = 0f

    /**
     * Tiers que este nivel puede entregar: cuantos más, más difícil emparejar.
     *
     * Es el tercer eje de la dificultad —junto al tamaño y al techo— y el único que actúa sobre la
     * **planificación**: con tres tiers en juego casi siempre hay una pareja obvia; con los cinco,
     * el jugador tiene que reservar zonas del contenedor para tiers que quizá tarden en repetirse.
     * Acelerar la caída o la recarga sería el eje equivocado — este juego no va de velocidad.
     */
    private val spawnPool: List<QuantumTier> =
        // Se deriva de [level] y no del `difficulty` crudo para que un valor fuera de rango no
        // produzca una mezcla imposible (geometría de Fácil con la variedad del nivel más alto).
        QuantumTier.SPAWN_POOL.take((3 + level.ordinal).coerceIn(3, QuantumTier.SPAWN_POOL.size))

    override fun onStart() {
        frameClock.reset()
        accumulatorSec = 0f
        bodyCount = 0
        flashes.clear()
        score = 0
        merges = 0
        drops = 0
        bestTier = QuantumTier.QUARK
        dangerProgress = 0f
        bounceCooldownSec = 0f
        reloadSec = 0f
        nextSphereId = 1L
        nextFlashId = 1L
        aimX = QuantumWorld.WIDTH / 2f

        val first = randomSpawnTier()
        currentDrop = Sphere(
            id = nextSphereId++,
            tier = first,
            radiusScale = level.radiusScale,
            x = QuantumWorld.clampInsideWalls(aimX, first.radiusFor(level.radiusScale)),
            y = level.dropY,
        )
        nextTier = randomSpawnTier()
        publish()
    }

    /** Descartar el delta acumulado evita un salto de física al reanudar. */
    override fun onPause() {
        frameClock.reset()
        accumulatorSec = 0f
    }

    /**
     * Mueve la mira del dispensador. [worldX] llega **en unidades de mundo** (la pantalla ya hizo
     * la conversión; ver `QuantumMergeIntent.MoveDropper`) y se recorta a las paredes: el dedo
     * puede salirse del contenedor, la esfera no.
     */
    fun moveDropper(worldX: Float) {
        if (status.value != GameStatus.RUNNING) return
        val radius = currentDrop?.radius ?: nextTier.radiusFor(level.radiusScale)
        aimX = QuantumWorld.clampInsideWalls(worldX, radius)
        currentDrop = currentDrop?.copy(x = aimX)
        publish()
    }

    /**
     * Suelta la esfera del dispensador: deja de estar sostenida y pasa a ser un cuerpo más de la
     * simulación. Se le da una pizca de velocidad inicial ([DROP_INITIAL_SPEED]) en lugar de cero
     * porque arrancar exactamente parada hace que el primer instante de caída se vea "flotante".
     *
     * No-op si no hay esfera lista (recarga en curso) o si la partida no está en RUNNING.
     */
    fun dropSphere() {
        if (status.value != GameStatus.RUNNING) return
        val sphere = currentDrop ?: return

        obtainBody().set(sphere.copy(vy = DROP_INITIAL_SPEED))
        currentDrop = null
        reloadSec = RELOAD_SEC
        drops++
        _effects.trySend(QuantumMergeEffect.PlaySound(QuantumMergeEffect.PlaySound.Cue.DROP))
        publish()
    }

    /**
     * Avanza la simulación con el timestamp absoluto del frame (`withFrameNanos`).
     *
     * Reparte el tiempo real del frame en sub-pasos fijos (ver punto 1 del KDoc de clase),
     * materializa el estado inmutable **una sola vez** al final y comprueba la derrota.
     *
     * @param frameNanos tiempo monotónico del frame actual.
     */
    fun onFrame(frameNanos: Long) {
        if (status.value != GameStatus.RUNNING) return

        // `null` = primer frame tras arrancar/reanudar o dt==0: no integramos este frame.
        val dtSec = frameClock.tick(frameNanos) ?: return

        accumulatorSec += dtSec
        var steps = 0
        while (accumulatorSec >= FIXED_DT_SEC && steps < MAX_SUBSTEPS) {
            accumulatorSec -= FIXED_DT_SEC
            updatePhysics(FIXED_DT_SEC)
            steps++
        }
        // Se agotaron los sub-pasos permitidos: tiramos el atraso restante en vez de arrastrarlo
        // al frame siguiente (espiral de la muerte, ver KDoc de clase).
        if (steps == MAX_SUBSTEPS) accumulatorSec = 0f

        if (steps > 0) publish()
        if (dangerProgress >= 1f) endByOverflow()
    }

    /**
     * **Un sub-paso completo de simulación** de [dtSec] segundos (siempre [FIXED_DT_SEC]).
     *
     * Orden de las etapas, que no es arbitrario:
     *  1. [integrate] — gravedad, rozamiento con el aire y avance de posiciones.
     *  2. [collideWalls] — **impacto** contra el contenedor: proyección + rebote con restitución.
     *     Se hace una sola vez, aquí, porque un rebote es un evento del choque, no del solver.
     *  3. ([resolveContacts] + [solveWallContacts]) × [SOLVER_ITERATIONS] — fusiones y choques.
     *     Se repite porque **corregir una pareja descoloca a sus vecinas**: en una pila, separar A
     *     de B mete a B dentro de C. Un solo barrido dejaría solapes residuales que el ojo ve como
     *     esferas "mordidas"; los barridos sucesivos son una relajación de Gauss-Seidel.
     *  4. Temporizadores: derrota por desbordamiento, destellos, recarga del dispensador.
     *  5. [compact] — retira de la lista los cuerpos consumidos por fusiones.
     *
     * ## Por qué el suelo se resuelve DENTRO del bucle (y no solo antes)
     * Es el detalle del que depende que una pila se quede de verdad quieta, y costó un test
     * descubrirlo. Si entre barridos solo se recortaran posiciones, el contenedor no podría
     * **absorber velocidad** durante la relajación: cada barrido las esferas de arriba empujan a la
     * de abajo contra el suelo, nadie le quita esa velocidad hasta el sub-paso siguiente, y la pila
     * acaba en un estado absurdo —posiciones inmóviles pero con velocidad de caída permanente—.
     * Además de verse mal, rompía la derrota: la regla exige esferas *asentadas*, y ninguna lo
     * estaba nunca. Tratando el suelo como un contacto más del solver, el apoyo se propaga hacia
     * arriba un eslabón por barrido y la pila converge a velocidad cero.
     *
     * Ese es también el motivo de que [SOLVER_ITERATIONS] sea generoso: hacen falta tantos barridos
     * como alto sea el montón para que la información del suelo llegue a la cima.
     *
     * Las fusiones solo se buscan en el **primer** barrido: en los siguientes los cuerpos ya se
     * están separando y volver a evaluarlas encadenaría fusiones dentro del mismo sub-paso, lo que
     * haría desaparecer cadenas enteras en un fotograma sin que el jugador viera el proceso.
     */
    private fun updatePhysics(dtSec: Float) {
        integrate(dtSec)
        collideWalls()

        var iteration = 0
        while (iteration < SOLVER_ITERATIONS) {
            resolveContacts(allowMerge = iteration == 0)
            solveWallContacts()
            iteration++
        }
        settleFallVelocity(dtSec)

        updateDangerTimers(dtSec)
        ageFlashes(dtSec)
        updateDropper(dtSec)
        bounceCooldownSec = max(0f, bounceCooldownSec - dtSec)
        compact()
    }

    /**
     * Integración semi-implícita de Euler: primero la velocidad (gravedad + amortiguación), después
     * la posición **con la velocidad ya actualizada** (ver punto 2 del KDoc de clase).
     *
     * El recorte a [MAX_SPEED] no es cosmético, es la garantía **anti-túnel**: a velocidad máxima un
     * cuerpo avanza `420/120 = 3,5` unidades por sub-paso, muy por debajo del contacto más estrecho
     * posible (dos [QuantumTier.QUARK] suman 8,8 de radios). Así ninguna pareja puede atravesarse
     * sin que al menos un sub-paso detecte el solape.
     */
    private fun integrate(dtSec: Float) {
        // Rozamiento con el aire como factor exponencial linealizado: a `dt` fijo es constante.
        val damping = (1f - LINEAR_DAMPING * dtSec).coerceAtLeast(0f)
        for (i in 0 until bodyCount) {
            val body = bodies[i]
            body.previousY = body.y
            body.vy += GRAVITY * dtSec
            body.vx *= damping
            body.vy *= damping

            val speed = hypot(body.vx, body.vy)
            if (speed > MAX_SPEED) {
                val scale = MAX_SPEED / speed
                body.vx *= scale
                body.vy *= scale
            }

            body.x += body.vx * dtSec
            body.y += body.vy * dtSec
        }
    }

    /**
     * Colisión contra el contenedor (AABB de **tres** lados: dos paredes y el suelo).
     *
     * No hay techo a propósito: la caja está abierta por arriba y una esfera puede asomar por
     * encima tras un rebote fuerte — es justo la situación que vigila la línea de peligro.
     *
     * Se resuelve por proyección: se devuelve el cuerpo al borde y se refleja la componente normal
     * de la velocidad multiplicada por la restitución. Por debajo de [REST_SPEED_THRESHOLD] el
     * rebote se anula del todo; sin ese corte, una esfera apoyada en el suelo daría saltitos
     * infinitesimales para siempre (la gravedad reinyecta velocidad en cada paso) y la pila nunca
     * se vería quieta.
     */
    private fun collideWalls() {
        for (i in 0 until bodyCount) {
            val body = bodies[i]
            val radius = body.radius

            if (body.x < radius) {
                body.x = radius
                if (body.vx < 0f) body.vx = -body.vx * WALL_RESTITUTION
            } else if (body.x > QuantumWorld.WIDTH - radius) {
                body.x = QuantumWorld.WIDTH - radius
                if (body.vx > 0f) body.vx = -body.vx * WALL_RESTITUTION
            }

            val floorY = QuantumWorld.HEIGHT - radius
            if (body.y > floorY) {
                val impactSpeed = body.vy
                body.y = floorY
                if (impactSpeed > 0f) {
                    body.vy = if (impactSpeed < REST_SPEED_THRESHOLD) 0f else -impactSpeed * WALL_RESTITUTION
                    // Rozamiento con el suelo: sustituye al rodamiento que no simulamos (las
                    // esferas no giran), para que no patinen eternamente sobre la base.
                    body.vx *= (1f - FLOOR_FRICTION)
                    tryEmitBounce(impactSpeed)
                }
            }
        }
    }

    /**
     * El contenedor **como contacto del solver**: recorta la posición y anula la velocidad que
     * entra en la pared, sin restitución.
     *
     * Se ejecuta tras cada barrido (ver el porqué en [updatePhysics]). Que aquí NO haya rebote es
     * deliberado: el rebote ya se aplicó en [collideWalls] cuando el cuerpo llegó con velocidad de
     * impacto; repetirlo en cada iteración multiplicaría el rebote por [SOLVER_ITERATIONS]. Lo que
     * el solver necesita del contenedor no es que devuelva energía, sino que **absorba** la que los
     * cuerpos de arriba le transmiten empujando hacia abajo.
     */
    private fun solveWallContacts() {
        for (i in 0 until bodyCount) {
            val body = bodies[i]
            val radius = body.radius

            if (body.x < radius) {
                body.x = radius
                if (body.vx < 0f) body.vx = 0f
            } else if (body.x > QuantumWorld.WIDTH - radius) {
                body.x = QuantumWorld.WIDTH - radius
                if (body.vx > 0f) body.vx = 0f
            }

            val floorY = QuantumWorld.HEIGHT - radius
            if (body.y > floorY) {
                body.y = floorY
                if (body.vy > 0f) body.vy = 0f
            }
        }
    }

    /**
     * Barrido de todas las parejas: detecta solapes círculo-círculo y los resuelve, ya sea
     * **fusionando** (mismo tier) o **separando y rebotando** (tiers distintos).
     *
     * La detección es la del teorema de Pitágoras encapsulada en el modelo
     * ([Sphere.penetrationWith]): dos círculos chocan cuando la distancia entre centros es menor
     * que la suma de radios. Aquí se calcula sobre los [Body] mutables para no construir `Sphere`
     * intermedios, pero es literalmente la misma fórmula.
     *
     * @param allowMerge si este barrido puede consumir parejas del mismo tier (solo el primero).
     */
    private fun resolveContacts(allowMerge: Boolean) {
        // El límite se captura ANTES del bucle: una fusión añade el cuerpo hijo al final del
        // buffer, y visitarlo en este mismo barrido podría encadenar fusiones en cascada dentro de
        // un solo fotograma. Que espere al sub-paso siguiente es lo que hace visible la cadena.
        val count = bodyCount
        for (i in 0 until count) {
            val a = bodies[i]
            if (!a.alive) continue

            for (j in i + 1 until count) {
                val b = bodies[j]
                if (!b.alive) continue

                val dx = b.x - a.x
                val dy = b.y - a.y
                val distance = hypot(dx, dy)
                val penetration = (a.radius + b.radius) - distance
                if (penetration <= 0f) continue

                if (allowMerge && a.tier == b.tier) {
                    merge(a, b)
                    break // `a` ya no existe: no tiene sentido seguir comparándola.
                }

                // Normal unitaria en la dirección a→b. Con los centros exactamente superpuestos la
                // dirección es indefinida (0/0): se empuja en vertical para desempatar, cualquier
                // eje sirve mientras sea determinista.
                val nx: Float
                val ny: Float
                if (distance > CONTACT_EPSILON) {
                    nx = dx / distance
                    ny = dy / distance
                } else {
                    nx = 0f
                    ny = 1f
                }

                separate(a, b, nx, ny, penetration)
                applyImpulse(a, b, nx, ny)
            }
        }
    }

    /**
     * **Separación por profundidad de penetración.** Es la mitad "posicional" del choque: deshace
     * el solape geométrico que el paso de integración creó al mover los cuerpos.
     *
     * ## La matemática
     * Conocidas la normal `n` (unitaria, de `a` hacia `b`) y la penetración `p`, hay que apartar
     * los centros exactamente `p` a lo largo de `n`. El reparto **no es a partes iguales**: se hace
     * proporcional a la masa inversa de cada cuerpo,
     *
     * ```
     * desplazamiento_a = p · (1/mₐ) / (1/mₐ + 1/m_b)   (en dirección −n)
     * desplazamiento_b = p · (1/m_b) / (1/mₐ + 1/m_b)  (en dirección +n)
     * ```
     *
     * que equivale a `p · m_b/(mₐ+m_b)` y `p · mₐ/(mₐ+m_b)`: **el cuerpo pesado apenas se mueve y el
     * ligero se aparta casi todo**. Es lo que hace creíble que una [QuantumTier.STAR] cayendo sobre
     * un [QuantumTier.QUARK] lo expulse en vez de detenerse.
     *
     * ## Los dos ajustes que evitan el temblor
     * Corregir el 100 % de la penetración cada sub-paso hace **rebotar la corrección**: los cuerpos
     * se pasan de largo, vuelven a solaparse al frame siguiente y la pila vibra visiblemente. Por
     * eso, como en todo motor de contactos:
     *  - se ignora una tolerancia [PENETRATION_SLOP] de solape (un solape microscópico es invisible
     *    y permite que los contactos en reposo se queden **quietos** en vez de corregirse sin fin), y
     *  - se aplica solo el [CORRECTION_RATIO] de lo que queda, dejando que la convergencia la
     *    completen las iteraciones del solver y los sub-pasos siguientes.
     */
    private fun separate(a: Body, b: Body, nx: Float, ny: Float, penetration: Float) {
        val inverseMassSum = a.inverseMass + b.inverseMass
        val correction = max(penetration - PENETRATION_SLOP, 0f) * CORRECTION_RATIO / inverseMassSum
        a.x -= nx * correction * a.inverseMass
        a.y -= ny * correction * a.inverseMass
        b.x += nx * correction * b.inverseMass
        b.y += ny * correction * b.inverseMass
    }

    /**
     * **Intercambio de velocidades del choque** (la mitad "dinámica"): impulso normal con
     * restitución + fricción tangencial.
     *
     * ## Impulso normal
     * Con `v_rel = v_b − v_a` y su proyección sobre la normal `vₙ = v_rel · n`:
     *  - Si `vₙ >= 0` los cuerpos ya se están **separando** (el contacto lo resolvió un barrido
     *    anterior): aplicar impulso aquí los pegaría de vuelta. Se sale.
     *  - Si no, la magnitud del impulso que produce una separación con coeficiente de restitución
     *    `e` es la fórmula clásica de choque elástico entre dos cuerpos:
     *
     * ```
     * j = −(1 + e) · vₙ / (1/mₐ + 1/m_b)
     * vₐ −= j · n / mₐ        v_b += j · n / m_b
     * ```
     *
     * Repartir por masa inversa conserva el momento lineal exactamente (lo que uno pierde, el otro
     * lo gana) y hace que la esfera ligera salga despedida mientras la pesada apenas se inmuta.
     *
     * **`e` se anula por debajo de [REST_SPEED_THRESHOLD]**: sin ese corte, la gravedad reinyecta
     * cada sub-paso una velocidad diminuta que el rebote devuelve, y una pila supuestamente en
     * reposo hierve para siempre. Es el mismo criterio que en el suelo ([collideWalls]).
     *
     * ## Fricción tangencial
     * Las esferas no giran (ver `Sphere`), así que sin fricción se comportarían como canicas sobre
     * hielo: resbalarían indefinidamente unas sobre otras y la pila nunca se asentaría. Se aplica un
     * impulso que frena la componente **tangencial** de la velocidad relativa, acotado al modelo de
     * Coulomb (`|jt| <= μ·j`): la fricción nunca puede empujar más de lo que empujó el propio
     * contacto, que es lo que impide que un roce lateral acelere un cuerpo.
     */
    private fun applyImpulse(a: Body, b: Body, nx: Float, ny: Float) {
        val relativeVx = b.vx - a.vx
        val relativeVy = b.vy - a.vy
        val normalSpeed = relativeVx * nx + relativeVy * ny
        if (normalSpeed >= 0f) return

        val inverseMassSum = a.inverseMass + b.inverseMass
        val restitution = if (-normalSpeed < REST_SPEED_THRESHOLD) 0f else RESTITUTION
        val normalImpulse = -(1f + restitution) * normalSpeed / inverseMassSum

        a.vx -= normalImpulse * nx * a.inverseMass
        a.vy -= normalImpulse * ny * a.inverseMass
        b.vx += normalImpulse * nx * b.inverseMass
        b.vy += normalImpulse * ny * b.inverseMass

        tryEmitBounce(-normalSpeed)

        // Componente tangencial = velocidad relativa menos su proyección sobre la normal.
        val tangentVx = relativeVx - normalSpeed * nx
        val tangentVy = relativeVy - normalSpeed * ny
        val tangentSpeed = hypot(tangentVx, tangentVy)
        if (tangentSpeed < CONTACT_EPSILON) return

        val tx = tangentVx / tangentSpeed
        val ty = tangentVy / tangentSpeed
        val maxFriction = FRICTION * normalImpulse
        val tangentImpulse = (-tangentSpeed / inverseMassSum).coerceIn(-maxFriction, maxFriction)

        a.vx -= tangentImpulse * tx * a.inverseMass
        a.vy -= tangentImpulse * ty * a.inverseMass
        b.vx += tangentImpulse * tx * b.inverseMass
        b.vy += tangentImpulse * ty * b.inverseMass
    }

    /**
     * **Fusión**: las dos esferas se consumen y nace una del tier siguiente en el punto medio
     * exacto, con un pequeño impulso hacia arriba.
     *
     * ## Velocidad de la esfera nacida
     * No hereda la de ninguna de las dos madres ni arranca parada: se toma la **media ponderada por
     * masa**, que es la velocidad del centro de masas del par —el resultado de conservar el momento
     * lineal `(mₐvₐ + m_bv_b)/(mₐ+m_b)`—. Sin esto, fusionar dos esferas que caían rápido daría un
     * cuerpo inmóvil suspendido en el aire, y la pila daría un tirón antinatural.
     *
     * Sobre esa velocidad se resta [MERGE_POP_SPEED] en vertical (recordatorio: `−y` es hacia
     * arriba). Ese "pop" no es decorativo: separa a la recién nacida de los cuerpos que la rodean
     * dándole sitio para resolver sus solapes, y es la señal cinética de que ha ocurrido algo bueno.
     *
     * ## Doble consumo
     * Ambas madres se marcan muertas **antes** de crear la hija, y todos los bucles comprueban
     * `alive`. Sin esa guarda, un contacto triple (tres esferas iguales tocándose) podría fusionar
     * la misma esfera dos veces en el mismo barrido: se crearía masa de la nada y el puntaje se
     * dispararía.
     *
     * Si el tier ya es el máximo, [QuantumTier.next] devuelve `null` y se aplica la regla de
     * **aniquilación**: ambas desaparecen y otorgan [ANNIHILATION_BONUS]. Es la única forma de
     * liberar el espacio que ocupan dos [QuantumTier.SINGULARITY]; sin ella el contenedor quedaría
     * bloqueado por dos cuerpos enormes e inmortales.
     */
    private fun merge(a: Body, b: Body) {
        a.alive = false
        b.alive = false
        merges++

        val midX = (a.x + b.x) * 0.5f
        val midY = (a.y + b.y) * 0.5f
        val born = a.tier.next()

        if (born == null) {
            score += ANNIHILATION_BONUS
            addFlash(midX, midY, a.radius * ANNIHILATION_FLASH_SCALE, a.tier.accent)
            emitMergeFeedback(a.tier)
            return
        }

        val totalMass = a.mass + b.mass
        val momentumVx = (a.vx * a.mass + b.vx * b.mass) / totalMass
        val momentumVy = (a.vy * a.mass + b.vy * b.mass) / totalMass

        obtainBody().set(
            id = nextSphereId++,
            tier = born,
            radiusScale = level.radiusScale,
            x = QuantumWorld.clampInsideWalls(midX, born.radiusFor(level.radiusScale)),
            y = midY,
            vx = momentumVx,
            vy = momentumVy - MERGE_POP_SPEED,
        )

        score += born.mergeScore
        if (born.ordinal > bestTier.ordinal) bestTier = born
        addFlash(midX, midY, born.radiusFor(level.radiusScale), born.accent)
        emitMergeFeedback(born)
    }

    /**
     * **Una esfera no puede llevar más velocidad de caída que la caída que de verdad ocurrió.**
     *
     * Cierra el último resquicio del artefacto descrito en [updatePhysics]. Aunque el solver
     * propague el apoyo del suelo, en una torre alta la relajación deja un residuo: la gravedad
     * reinyecta velocidad en cada sub-paso a *todos* los cuerpos, y en la cima queda algo de `vy`
     * que ningún barrido llegó a cancelar. El síntoma es una pila visualmente inmóvil cuyas esferas
     * afirman estar cayendo — y como la derrota exige esferas **asentadas**, el juego no podía
     * terminar nunca.
     *
     * La corrección compara la velocidad con el desplazamiento **real** del sub-paso: si el cuerpo
     * decía caer a 9 u/s pero solo bajó lo equivalente a 0,2, es que algo lo está sujetando, y su
     * velocidad se recorta a lo que de verdad se movió. Es la idea de la dinámica basada en
     * posiciones (PBD), donde la velocidad se *deriva* del movimiento en vez de imponerse.
     *
     * Solo se recorta la componente **hacia abajo**, y ese matiz es el que salva el rebote: una
     * esfera que acaba de botar tiene `vy` negativa que aún no se ha materializado en movimiento
     * (lo hará el sub-paso siguiente), y aplicarle esta misma regla la dejaría pegada al suelo.
     * Hacia abajo, en cambio, no hay falso positivo posible: la gravedad es constante y lo que no
     * se movió es porque estaba apoyado.
     */
    private fun settleFallVelocity(dtSec: Float) {
        val inverseDt = 1f / dtSec
        for (i in 0 until bodyCount) {
            val body = bodies[i]
            if (body.vy <= 0f) continue
            val actualFallSpeed = (body.y - body.previousY) * inverseDt
            if (actualFallSpeed < body.vy) body.vy = max(actualFallSpeed, 0f)
        }
    }

    /**
     * Acumula el tiempo que cada esfera lleva **asentada** por encima de la línea de peligro y
     * publica el peor caso como [dangerProgress].
     *
     * La condición es doble —estar arriba **y** casi parada— y es lo que hace justa la regla: toda
     * esfera lanzada cruza la zona superior a gran velocidad, y penalizar el mero hecho de estar
     * ahí mataría la partida en el primer lanzamiento. Al exigir que esté quieta, solo cuenta la
     * que se ha quedado realmente encallada arriba. En cuanto vuelve a moverse (porque una fusión
     * hundió la pila bajo ella) el contador se reinicia y el jugador recupera su margen.
     */
    private fun updateDangerTimers(dtSec: Float) {
        var worst = 0f
        for (i in 0 until bodyCount) {
            val body = bodies[i]
            val settled = hypot(body.vx, body.vy) < SETTLED_SPEED
            body.aboveLineSec =
                if (settled && body.y < level.dangerLineY) body.aboveLineSec + dtSec else 0f
            if (body.aboveLineSec > worst) worst = body.aboveLineSec
        }
        dangerProgress = (worst / OVERFLOW_LIMIT_SEC).coerceIn(0f, 1f)
    }

    /** Envejece los destellos de fusión y retira los consumidos (ver [MergeFlash]). */
    private fun ageFlashes(dtSec: Float) {
        var i = 0
        while (i < flashes.size) {
            val aged = flashes[i].copy(ageSec = flashes[i].ageSec + dtSec)
            if (aged.isExpired) {
                flashes.removeAt(i)
            } else {
                flashes[i] = aged
                i++
            }
        }
    }

    /**
     * Recarga del dispensador. El hueco entre lanzamientos existe por dos razones: da tiempo a que
     * la esfera anterior se despegue de la boca del dispensador (si no, la siguiente nacería dentro
     * de ella) y evita que un jugador rápido vacíe la mano llenando el contenedor sin pensar.
     */
    private fun updateDropper(dtSec: Float) {
        if (currentDrop != null) return
        reloadSec -= dtSec
        if (reloadSec > 0f) return

        currentDrop = Sphere(
            id = nextSphereId++,
            tier = nextTier,
            radiusScale = level.radiusScale,
            x = QuantumWorld.clampInsideWalls(aimX, nextTier.radiusFor(level.radiusScale)),
            y = level.dropY,
        )
        nextTier = randomSpawnTier()
    }

    /**
     * Cierra la partida por desbordamiento del contenedor. Guarda contra la doble finalización:
     * [onFrame] la llama en el mismo frame en el que el temporizador se completa, y `finish()` ya
     * habrá cambiado el estado a FINISHED.
     */
    private fun endByOverflow() {
        if (status.value != GameStatus.RUNNING) return
        _effects.trySend(QuantumMergeEffect.PlaySound(QuantumMergeEffect.PlaySound.Cue.GAME_OVER))
        _effects.trySend(QuantumMergeEffect.Vibrate(QuantumMergeEffect.Vibrate.Cue.GAME_OVER))
        finish()
    }

    /**
     * Puntaje = suma de los [QuantumTier.mergeScore] de todo lo fusionado (más los bonos de
     * aniquilación), que es lo que el motor ya viene acumulando en [score].
     *
     * No lleva componente de tiempo ni de eficiencia, a diferencia de los juegos por niveles: aquí
     * lanzar mucho no es "hacer trampa", es la mecánica, y quien lanza sin pensar **se penaliza
     * solo** llenando el contenedor y terminando la partida antes. La escala superlineal de
     * [QuantumTier.mergeScore] ya premia la cadena larga sobre el volumen de lanzamientos.
     */
    override fun calculateScore(): Int = score

    /**
     * Precisión = fracción de lanzamientos que acabaron combinando. Es la lectura honesta de "cuánto
     * de lo que solté sirvió para algo" en vez de amontonarse. Se recorta a 100 porque una cadena
     * puede producir más fusiones que lanzamientos (una sola esfera desencadena varias).
     */
    override fun currentAccuracy(): Double =
        if (drops == 0) 100.0 else (merges * 100.0 / drops).coerceAtMost(100.0)

    /** Récord = mejor puntaje de la corrida (ENDLESS/POINTS, ver `GameProgressions`). */
    override fun reachedMetric(): Int? = score.takeIf { it > 0 }

    // --- Utilidades internas ------------------------------------------------------------------

    /**
     * Emite el sonido de rebote solo si el golpe es **audible** y no ha sonado hace nada.
     *
     * En un contenedor lleno hay decenas de contactos por sub-paso; sin estos dos filtros el juego
     * sería una ametralladora de clics. El umbral de velocidad descarta los roces de reposo (que
     * son contactos permanentes, no golpes) y el enfriamiento acota la cadencia a ~11 por segundo
     * incluso cuando una esfera grande cae sobre media pila a la vez.
     */
    private fun tryEmitBounce(impactSpeed: Float) {
        if (impactSpeed < BOUNCE_MIN_SPEED || bounceCooldownSec > 0f) return
        bounceCooldownSec = BOUNCE_COOLDOWN_SEC
        _effects.trySend(QuantumMergeEffect.PlaySound(QuantumMergeEffect.PlaySound.Cue.BOUNCE))
    }

    /**
     * Feedback de una fusión: sonido siempre, y háptica cuya intensidad depende del tier nacido.
     * El golpe fuerte se reserva a partir de [BIG_MERGE_TIER] para que signifique algo: si toda
     * fusión vibrara igual, la mano dejaría de distinguir el logro de la rutina.
     */
    private fun emitMergeFeedback(tier: QuantumTier) {
        _effects.trySend(QuantumMergeEffect.PlaySound(QuantumMergeEffect.PlaySound.Cue.MERGE))
        _effects.trySend(
            QuantumMergeEffect.Vibrate(
                if (tier.ordinal >= BIG_MERGE_TIER.ordinal) QuantumMergeEffect.Vibrate.Cue.MERGE_BIG
                else QuantumMergeEffect.Vibrate.Cue.MERGE_SMALL,
            ),
        )
    }

    private fun addFlash(x: Float, y: Float, radius: Float, accent: TierAccent) {
        flashes += MergeFlash(id = nextFlashId++, x = x, y = y, radius = radius, accent = accent)
    }

    /** Tier aleatorio del [spawnPool] (uniforme: ningún tier de la base debe escasear). */
    private fun randomSpawnTier(): QuantumTier = spawnPool[random.nextInt(spawnPool.size)]

    /**
     * Reserva un cuerpo del buffer: reutiliza el primer hueco libre (los que dejó [compact]) y solo
     * asigna memoria la primera vez que la partida alcanza ese número de esferas simultáneas.
     */
    private fun obtainBody(): Body {
        if (bodyCount == bodies.size) bodies.add(Body())
        return bodies[bodyCount++]
    }

    /**
     * Compacta el buffer moviendo los cuerpos vivos al principio. Los muertos no se borran: se
     * **intercambian** hacia la cola, donde quedan disponibles para [obtainBody]. El buffer es a la
     * vez la lista de simulación y el pool de reciclaje.
     */
    private fun compact() {
        var write = 0
        for (read in 0 until bodyCount) {
            if (!bodies[read].alive) continue
            if (write != read) {
                val temp = bodies[write]
                bodies[write] = bodies[read]
                bodies[read] = temp
            }
            write++
        }
        bodyCount = write
    }

    /**
     * Materializa el estado inmutable que consume la UI. Es el **único** punto donde la simulación
     * mutable cruza la frontera de MVI, y se llama una vez por frame (no una por sub-paso): la
     * pantalla no puede dibujar estados intermedios, así que copiarlos sería trabajo tirado.
     */
    private fun publish() {
        val spheres = ArrayList<Sphere>(bodyCount)
        for (i in 0 until bodyCount) {
            val body = bodies[i]
            if (body.alive) spheres += body.toSphere()
        }
        _state.value = QuantumMergeState(
            activeSpheres = spheres,
            currentDropSphere = currentDrop,
            nextSphereTier = nextTier,
            aimX = aimX,
            flashes = flashes.toList(),
            score = score,
            merges = merges,
            drops = drops,
            bestTier = bestTier,
            dangerProgress = dangerProgress,
            difficulty = level,
        )
    }

    /**
     * Cuerpo mutable de trabajo: el gemelo interno de [Sphere] durante el tick.
     *
     * Existe solo para evitar asignar miles de `data class` por frame (ver punto 3 del KDoc de la
     * clase). Es `private` e intransferible: fuera del motor solo circulan [Sphere] inmutables.
     */
    private class Body {
        var id: Long = 0L
        var tier: QuantumTier = QuantumTier.QUARK

        /** Escala de radios de la partida; espejo de [Sphere.radiusScale]. */
        var radiusScale: Float = 1f
        var x: Float = 0f
        var y: Float = 0f
        var vx: Float = 0f
        var vy: Float = 0f
        var aboveLineSec: Float = 0f

        /** Altura al empezar el sub-paso; base del recorte de [settleFallVelocity]. */
        var previousY: Float = 0f

        /** `false` = consumido por una fusión; espera a que [compact] lo mande al pool. */
        var alive: Boolean = false

        val radius: Float get() = tier.radiusFor(radiusScale)

        /** Masa proporcional al área, espejo de [Sphere.mass] (allí está el porqué de `r²`). */
        val mass: Float get() = radius * radius

        /**
         * `1/m`, porque aparece en **todas** las fórmulas de contacto (separación, impulso normal,
         * fricción) y la división es la operación cara del bucle interno.
         */
        val inverseMass: Float get() = 1f / mass

        fun set(sphere: Sphere) = set(
            id = sphere.id,
            tier = sphere.tier,
            radiusScale = sphere.radiusScale,
            x = sphere.x,
            y = sphere.y,
            vx = sphere.vx,
            vy = sphere.vy,
        )

        fun set(
            id: Long,
            tier: QuantumTier,
            radiusScale: Float,
            x: Float,
            y: Float,
            vx: Float,
            vy: Float,
        ) {
            this.id = id
            this.tier = tier
            this.radiusScale = radiusScale
            this.x = x
            this.y = y
            this.vx = vx
            this.vy = vy
            // Un cuerpo recién nacido (fusión o lanzamiento) no tiene historia: si heredara la
            // `previousY` del cuerpo reciclado que ocupa este hueco, [settleFallVelocity] leería un
            // desplazamiento inventado en su primer sub-paso.
            previousY = y
            aboveLineSec = 0f
            alive = true
        }

        fun toSphere(): Sphere = Sphere(
            id = id,
            tier = tier,
            radiusScale = radiusScale,
            x = x,
            y = y,
            vx = vx,
            vy = vy,
            aboveLineSec = aboveLineSec,
        )
    }

    private companion object {
        // --- Bucle temporal -------------------------------------------------------------------
        /** Recorte del delta de frame, heredado del resto de motores del repo (ver `FrameClock`). */
        const val MAX_DT_SEC = 0.05f

        /** Paso fijo de simulación: 120 Hz, el doble del refresco típico (ver KDoc de clase). */
        const val FIXED_DT_SEC = 1f / 120f

        /** Tope de sub-pasos por frame; corta la espiral de la muerte tras un frame muy largo. */
        const val MAX_SUBSTEPS = 6

        /**
         * Barridos de relajación del solver de contactos por sub-paso.
         *
         * El apoyo del suelo sube un eslabón por barrido, así que este número marca la **altura de
         * pila** que puede quedarse perfectamente quieta: con 10, una torre de diez esferas
         * converge dentro del mismo sub-paso. Cuesta poco —el barrido es una resta y una raíz por
         * pareja— y es lo que separa una pila sólida de una que tiembla.
         */
        const val SOLVER_ITERATIONS = 10

        // --- Física ---------------------------------------------------------------------------
        /**
         * Gravedad en unidades de mundo/s². Con 320, una esfera tarda ~0,9 s en cruzar el
         * contenedor entero: lo bastante rápido para no aburrir y lo bastante lento para que el
         * jugador vea dónde va a caer y pueda corregir la mira.
         */
        const val GRAVITY = 320f

        /** Rozamiento con el aire (fracción de velocidad perdida por segundo). Muy suave. */
        const val LINEAR_DAMPING = 0.12f

        /**
         * Restitución entre esferas. Deliberadamente baja: son bolas de energía densas que se
         * asientan, no pelotas de goma. Un valor alto haría la pila caótica e imposible de planear.
         */
        const val RESTITUTION = 0.2f

        /** Restitución contra paredes y suelo, un pelín mayor para que el contenedor "suene" duro. */
        const val WALL_RESTITUTION = 0.25f

        /** Coeficiente de Coulomb entre esferas (acota el impulso tangencial). */
        const val FRICTION = 0.35f

        /** Frenado horizontal al tocar el suelo; compensa que las esferas no ruedan. */
        const val FLOOR_FRICTION = 0.18f

        /** Por debajo de esta velocidad de impacto no hay rebote: el contacto se considera reposo. */
        const val REST_SPEED_THRESHOLD = 14f

        /** Velocidad por debajo de la cual una esfera cuenta como "asentada" para la derrota. */
        const val SETTLED_SPEED = 6f

        /** Tope de velocidad; garantía anti-túnel (ver [integrate]). */
        const val MAX_SPEED = 420f

        /** Solape tolerado sin corregir; evita el temblor de los contactos en reposo. */
        const val PENETRATION_SLOP = 0.05f

        /** Fracción de la penetración que se corrige por iteración (Baumgarte suave). */
        const val CORRECTION_RATIO = 0.8f

        /** Umbral para considerar una distancia "no nula" y poder normalizar sin dividir por cero. */
        const val CONTACT_EPSILON = 1e-4f

        // --- Reglas de juego ------------------------------------------------------------------
        /** Empujón vertical de la esfera recién fusionada (hacia arriba: se resta a `vy`). */
        const val MERGE_POP_SPEED = 34f

        /** Velocidad inicial del lanzamiento; evita el arranque "flotante" desde cero. */
        const val DROP_INITIAL_SPEED = 10f

        /** Tiempo de recarga del dispensador entre lanzamientos. */
        const val RELOAD_SEC = 0.35f

        /**
         * Margen de gracia sobre la línea de peligro. 2,2 s es tiempo suficiente para que una fusión
         * en curso hunda la pila y salve la partida —el desenlace más satisfactorio del juego—, pero
         * no tanto como para poder ignorar el aviso.
         */
        const val OVERFLOW_LIMIT_SEC = 2.2f

        /** Bono por aniquilar dos esferas del tier máximo. */
        const val ANNIHILATION_BONUS = 1_500

        /** El destello de la aniquilación es mayor que el de una fusión normal. */
        const val ANNIHILATION_FLASH_SCALE = 1.6f

        /** A partir de este tier, la fusión merece háptica fuerte. */
        val BIG_MERGE_TIER = QuantumTier.ATOM

        // --- Feedback -------------------------------------------------------------------------
        /** Velocidad de impacto mínima para que un choque suene. */
        const val BOUNCE_MIN_SPEED = 55f

        /** Enfriamiento entre sonidos de rebote (~11 por segundo como máximo). */
        const val BOUNCE_COOLDOWN_SEC = 0.09f
    }
}

# ============================================================================
# Reglas de R8 para el build `release` (y `benchmark`, que lo calca con initWith).
#
# Responsabilidad: dejar que R8 encoja y OFUSQUE el AAB sin romper lo que se
# resuelve por nombre en tiempo de ejecución.
#
# Criterio: NO se añaden keeps "por si acaso" — cada keep de más es código que
# viaja legible y baja el porcentaje de ofuscación que mide Play Console. Antes
# de añadir una regla, comprueba que no venga ya de:
#   1. El archivo por defecto de AGP (`proguard-android-optimize.txt`): ya trae
#      los `-keepattributes` de reflexión (Signature, InnerClasses,
#      EnclosingMethod, RuntimeVisible*Annotations) y el keep de `values()` /
#      `valueOf()` de los enums.
#   2. Las reglas embebidas de cada librería (`META-INF/proguard/*.pro` dentro
#      del jar/aar, que R8 aplica solo):
#        - kotlinx.serialization -> Companion, serializer(), $$serializer y las
#          anotaciones. Los nombres JSON no dependen de los campos: el plugin
#          los escribe como literales en el descriptor del $$serializer (y aquí
#          además todos los DTO llevan @SerialName explícito), así que R8 puede
#          renombrar los campos sin romper la lectura de Supabase.
#        - kotlinx.coroutines, kotlinx.datetime -> lo suyo.
#        - Ktor -> campos volatile de AtomicFU y, sobre todo, los
#          `HttpClientEngineContainer` que carga el ServiceLoader: es así como
#          Supabase encuentra el motor OkHttp sin nombrarlo en el código.
#        - AdMob, UMP, Credential Manager, Play Services, Compose -> consumer
#          rules de sus AAR.
# El config completo y ya fusionado que usó el último build se puede leer en
# `build/outputs/mapping/release/configuration.txt`.
#
# Los componentes del manifest (MainActivity, LogicGamesApp y el
# NotificationAlarmReceiver de `shared`) los mantiene AGP solo, generando keeps
# a partir del manifest fusionado: no hace falta declararlos aquí.
# ============================================================================

# --- Trazas de crash legibles --------------------------------------------------
# Sin estos atributos los stacktraces de Play Console llegan sin fichero ni línea
# y el mapping.txt no puede reconstruirlos. `-renamesourcefileattribute` sustituye
# el nombre real del .kt por un literal: no se filtra la estructura del proyecto,
# pero el mapping que viaja dentro del AAB sigue desofuscando la traza.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Room (llega solo, vía AdMob) -----------------------------------------------
# Nadie usa Room en este proyecto —la BD local es SQLDelight—, pero
# `play-services-ads-api` arrastra `androidx.work:work-runtime:2.7.0`, que a su vez
# depende de `androidx.room:room-runtime:2.2.5`. Esa versión de Room es tan antigua
# que su regla embebida es solo `-keep class * extends androidx.room.RoomDatabase`,
# SIN el `<init>()`: conserva el nombre de la clase generada `WorkDatabase_Impl`
# pero no su constructor por defecto, y R8 en full mode (por defecto desde AGP 8)
# lo borra por no verlo llamado desde ningún sitio. Room la instancia con
# `Class.forName(...).newInstance()`, así que al arrancar peta en el
# `InitializationProvider` de androidx.startup con "Failed to create an instance of
# androidx.work.impl.WorkDatabase" y la app no abre.
#
# Esto es exactamente lo que declaran las versiones modernas de Room; se replica
# aquí porque no podemos tocar la regla que viene dentro del AAR de la 2.2.5.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}

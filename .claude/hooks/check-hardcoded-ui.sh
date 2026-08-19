#!/usr/bin/env bash
# Hook PostToolUse (Write|Edit) del proyecto Logic Games.
#
# Por qué existe: el CLAUDE.md exige en la §9.2 que los colores salgan siempre
# de LogicColors/MaterialTheme y en la §10 que todo texto de UI nuevo viva en
# strings.xml. Es fácil que a mitad de una sesión larga se cuele un
# `Color(0xFF...)` o un `Text("texto suelto")` sin querer. Este hook NO
# bloquea nada (solo avisa) porque el patrón es heurístico: puede haber falsos
# positivos (comentarios, tests, un literal legítimo), así que la decisión
# final la sigue tomando quien revisa, no el hook.
set -euo pipefail

file_path=$(jq -r '.tool_input.file_path // .tool_response.filePath // empty' 2>/dev/null || true)
[ -n "$file_path" ] || exit 0
[ -f "$file_path" ] || exit 0

# Solo Compose de la app: no core/theme (es la fuente de verdad de colores) ni
# nada fuera de ui/ o game/.
case "$file_path" in
  */shared/src/commonMain/kotlin/com/kortexgames/app/core/theme/*)
    exit 0
    ;;
  */shared/src/commonMain/kotlin/com/kortexgames/app/ui/*|*/shared/src/commonMain/kotlin/com/kortexgames/app/game/*)
    ;;
  *)
    exit 0
    ;;
esac

hits=$(grep -nE 'Color\(0x[0-9A-Fa-f]{6,8}\)|Text\(\s*"' "$file_path" 2>/dev/null || true)
[ -n "$hits" ] || exit 0

count=$(printf '%s\n' "$hits" | wc -l | tr -d ' ')
sample=$(printf '%s\n' "$hits" | head -3)

jq -n \
  --arg file "${file_path#*/shared/}" \
  --arg count "$count" \
  --arg sample "$sample" \
  '{systemMessage: ("CLAUDE.md §9.2/§10: " + $count + " posible(s) color/string hardcodeado en " + $file + " -> revisa:\n" + $sample)}'

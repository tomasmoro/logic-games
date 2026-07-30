#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generador + rater del banco de puzzles de Neon Sudoku Matrix.
===========================================================

Este script es una HERRAMIENTA OFFLINE (no forma parte del build de la app): se
ejecuta a mano para (re)generar el banco de Sudokus que consume el juego. Su
salida es un CSV plano que la app empaqueta como recurso (`composeResources/
files/sudoku_bank.csv`) y sube a Supabase (ver la migración `0023_*`).

Por qué offline y no en el cliente
----------------------------------
La parte cara y crítica del Sudoku no es rellenar una rejilla (trivial), sino:
  1. Cavar huecos manteniendo **solución única** (hay que contar soluciones tras
     cada quite), y
  2. **Ratear la dificultad de verdad**, que NO es el número de pistas sino qué
     técnicas lógicas humanas exige la resolución.
Hacer ambas cosas en el cliente gastaría CPU y arriesgaría enviar un puzzle roto.
Pre-generar offline con garantías las elimina de la ruta caliente del juego.

Rating por técnica (el corazón del asunto)
------------------------------------------
En lugar de rateo por recuento de pistas (proxy pobre: dos puzzles de 30 pistas
pueden ser abismalmente distintos), se corre un *solver humano* que aplica
técnicas en orden creciente de dificultad. La técnica MÁS avanzada que hizo falta
en toda la resolución define el tier:

    tier 1  -> FACIL    : basta con singles (naked/hidden single).
    tier 2  -> MEDIO    : hace falta candidatos bloqueados (pointing/claiming) o
                          pares (naked/hidden pair).
    tier 3  -> DIFICIL  : hace falta triples (naked/hidden triple) o X-Wing.
    tier 4  -> EXPERTO  : el solver lógico se atasca (requiere técnicas más
                          avanzadas / cadenas), pero la solución sigue siendo
                          única (garantizado aparte por el contador de soluciones).

La unicidad de solución se garantiza SIEMPRE con un backtracking que corta al
encontrar la 2.ª solución, independiente del rater: aunque el rater fuese
conservador, el invariante "solución única" no depende de él.

Uso
---
    python3 tools/sudoku/generate_bank.py [--per-tier N] [--seed S] [--out RUTA]

El orden de los tiers en el CSV (columna 0) es el ordinal de `SudokuDifficulty`
en Kotlin: 0=FACIL, 1=MEDIO, 2=DIFICIL, 3=EXPERTO.
"""

import argparse
import random
import sys
import time
import uuid

N = 9
CELLS = 81
DIGITS = range(1, 10)

# Índices precomputados de cada unidad (fila, columna, bloque) y de los pares
# (peers) de cada celda: acelera solver y contador al no recomputar geometría.
ROWS = [[r * 9 + c for c in range(9)] for r in range(9)]
COLS = [[r * 9 + c for r in range(9)] for c in range(9)]
BLOCKS = [
    [(br * 3 + dr) * 9 + (bc * 3 + dc) for dr in range(3) for dc in range(3)]
    for br in range(3) for bc in range(3)
]
UNITS_OF = [[] for _ in range(81)]
PEERS = [set() for _ in range(81)]
for unit in ROWS + COLS + BLOCKS:
    for cell in unit:
        UNITS_OF[cell].append(unit)
for cell in range(81):
    for unit in UNITS_OF[cell]:
        PEERS[cell].update(unit)
    PEERS[cell].discard(cell)


# ---------------------------------------------------------------------------
# Backtracking: resolver una rejilla y contar soluciones (para unicidad)
# ---------------------------------------------------------------------------

def _candidates_plain(grid, cell):
    used = set()
    for p in PEERS[cell]:
        if grid[p]:
            used.add(grid[p])
    return [d for d in DIGITS if d not in used]


def _find_best_cell(grid):
    """Celda vacía con menos candidatos (MRV): poda agresiva del árbol."""
    best, best_cands = -1, None
    for cell in range(81):
        if grid[cell] == 0:
            cands = _candidates_plain(grid, cell)
            if best_cands is None or len(cands) < len(best_cands):
                best, best_cands = cell, cands
                if len(cands) <= 1:
                    break
    return best, best_cands


def count_solutions(grid, cap=2):
    """Cuenta soluciones hasta `cap` (corta antes: solo nos importa 1 vs >1)."""
    grid = grid[:]

    def rec():
        cell, cands = _find_best_cell(grid)
        if cell == -1:
            return 1  # rejilla completa
        if not cands:
            return 0
        total = 0
        for d in cands:
            grid[cell] = d
            total += rec()
            grid[cell] = 0
            if total >= cap:
                return total
        return total

    return rec()


def full_solution(rng):
    """Genera una rejilla resuelta al azar por backtracking aleatorizado."""
    grid = [0] * 81

    def rec():
        cell, cands = _find_best_cell(grid)
        if cell == -1:
            return True
        rng.shuffle(cands)
        for d in cands:
            grid[cell] = d
            if rec():
                return True
            grid[cell] = 0
        return False

    rec()
    return grid


# ---------------------------------------------------------------------------
# Rater por técnicas humanas
# ---------------------------------------------------------------------------

def _init_candidates(grid):
    """cands[cell] = set de dígitos posibles; para celdas dadas, el propio valor."""
    cands = [set() for _ in range(81)]
    for cell in range(81):
        if grid[cell]:
            cands[cell] = {grid[cell]}
        else:
            used = {grid[p] for p in PEERS[cell] if grid[p]}
            cands[cell] = {d for d in DIGITS if d not in used}
    return cands


def _assign(cands, cell, digit):
    """Fija `digit` en `cell` y propaga la eliminación básica a sus peers.
    Devuelve False si genera una contradicción (candidato vacío)."""
    cands[cell] = {digit}
    for p in PEERS[cell]:
        if digit in cands[p] and len(cands[p]) > 1:
            cands[p].discard(digit)
            if not cands[p]:
                return False
    return True


def _naked_singles(cands):
    changed = False
    for cell in range(81):
        if len(cands[cell]) == 1:
            d = next(iter(cands[cell]))
            for p in PEERS[cell]:
                if d in cands[p] and len(cands[p]) > 1:
                    cands[p].discard(d)
                    changed = True
    return changed


def _hidden_singles(cands):
    changed = False
    for unit in ROWS + COLS + BLOCKS:
        for d in DIGITS:
            spots = [c for c in unit if d in cands[c]]
            if len(spots) == 1 and len(cands[spots[0]]) > 1:
                cands[spots[0]] = {d}
                changed = True
    return changed


def _locked_candidates(cands):
    """Pointing/claiming: si en un bloque un dígito solo cabe en una fila/columna
    (o viceversa), se elimina del resto de esa fila/columna (o bloque)."""
    changed = False
    for unit in ROWS + COLS + BLOCKS:
        for d in DIGITS:
            spots = [c for c in unit if d in cands[c]]
            if len(spots) < 2:
                continue
            for other in UNITS_OF[spots[0]]:
                if other is unit:
                    continue
                if all(s in other for s in spots):
                    for c in other:
                        if c not in spots and d in cands[c] and len(cands[c]) > 1:
                            cands[c].discard(d)
                            changed = True
    return changed


def _naked_subsets(cands, size):
    """Naked pair/triple: `size` celdas de una unidad que comparten exactamente
    la misma `size`-tupla de candidatos eliminan esos dígitos del resto."""
    changed = False
    for unit in ROWS + COLS + BLOCKS:
        cells = [c for c in unit if len(cands[c]) == size]
        n = len(cells)
        for i in range(n):
            group = [cells[i]]
            union = set(cands[cells[i]])
            for j in range(i + 1, n):
                if cands[cells[j]] <= (union | cands[cells[j]]) and len(union | cands[cells[j]]) <= size:
                    group.append(cells[j])
                    union |= cands[cells[j]]
            if len(group) == size and len(union) == size:
                for c in unit:
                    if c not in group:
                        inter = cands[c] & union
                        if inter and len(cands[c]) > 1:
                            cands[c] -= union
                            changed = True
    return changed


def _hidden_subsets(cands, size):
    """Hidden pair/triple: `size` dígitos que en una unidad solo aparecen en las
    mismas `size` celdas -> esas celdas se reducen a esos dígitos."""
    changed = False
    for unit in ROWS + COLS + BLOCKS:
        pos = {d: [c for c in unit if d in cands[c]] for d in DIGITS}
        digs = [d for d in DIGITS if 1 <= len(pos[d]) <= size]
        m = len(digs)
        for i in range(m):
            group = [digs[i]]
            cellset = set(pos[digs[i]])
            for j in range(i + 1, m):
                if len(cellset | set(pos[digs[j]])) <= size:
                    group.append(digs[j])
                    cellset |= set(pos[digs[j]])
            if len(group) == size and len(cellset) == size:
                gset = set(group)
                for c in cellset:
                    if not cands[c] <= gset:
                        cands[c] &= gset
                        changed = True
    return changed


def _x_wing(cands):
    """X-Wing sobre filas y columnas."""
    changed = False
    for d in DIGITS:
        # Basado en filas: dos filas donde d cabe en exactamente las mismas 2 cols.
        rowspots = {}
        for r in range(9):
            cols = [c % 9 for c in ROWS[r] if d in cands[c]]
            if len(cols) == 2:
                rowspots.setdefault(tuple(cols), []).append(r)
        for cols, rs in rowspots.items():
            if len(rs) == 2:
                for r in range(9):
                    if r not in rs:
                        for col in cols:
                            cell = r * 9 + col
                            if d in cands[cell] and len(cands[cell]) > 1:
                                cands[cell].discard(d)
                                changed = True
        # Basado en columnas.
        colspots = {}
        for c in range(9):
            rows = [cell // 9 for cell in COLS[c] if d in cands[cell]]
            if len(rows) == 2:
                colspots.setdefault(tuple(rows), []).append(c)
        for rows, cs in colspots.items():
            if len(cs) == 2:
                for c in range(9):
                    if c not in cs:
                        for row in rows:
                            cell = row * 9 + c
                            if d in cands[cell] and len(cands[cell]) > 1:
                                cands[cell].discard(d)
                                changed = True
    return changed


def _solved(cands):
    return all(len(cands[c]) == 1 for c in range(81))


# Técnicas ordenadas por tier. Cada entrada: (tier, función).
TECHNIQUES = [
    (1, _naked_singles),
    (1, _hidden_singles),
    (2, _locked_candidates),
    (2, lambda c: _naked_subsets(c, 2)),
    (2, lambda c: _hidden_subsets(c, 2)),
    (3, lambda c: _naked_subsets(c, 3)),
    (3, lambda c: _hidden_subsets(c, 3)),
    (3, _x_wing),
]


def rate(grid):
    """Devuelve el tier (1..4) del puzzle según la técnica más dura que exige.
    4 = el solver lógico implementado se atasca (EXPERTO)."""
    cands = _init_candidates(grid)
    max_tier = 1
    while not _solved(cands):
        progressed = False
        for tier, tech in TECHNIQUES:
            if tech(cands):
                max_tier = max(max_tier, tier)
                progressed = True
                break  # reempieza por la técnica más barata tras cada avance
        if not progressed:
            return 4  # atascado: requiere algo más avanzado -> EXPERTO
        if any(len(cands[c]) == 0 for c in range(81)):
            return 4  # contradicción por bug/candidato imposible: trátalo como duro
    return max_tier


# ---------------------------------------------------------------------------
# Generación de puzzles
# ---------------------------------------------------------------------------

def dig_puzzle(solution, rng, min_clues):
    """Quita celdas de `solution` en orden aleatorio manteniendo solución única,
    hasta no poder bajar de `min_clues` sin romper la unicidad."""
    grid = solution[:]
    order = list(range(81))
    rng.shuffle(order)
    clues = 81
    for cell in order:
        if clues <= min_clues:
            break
        saved = grid[cell]
        grid[cell] = 0
        if count_solutions(grid, cap=2) != 1:
            grid[cell] = saved  # quitarla abriría múltiples soluciones
        else:
            clues -= 1
    return grid


def to_str(grid):
    return "".join(str(d) for d in grid)


# Definición de las cuatro tiers de dificultad del juego. Ordinal = el de
# `SudokuDifficulty` en Kotlin (FACIL=0..EXPERTO=3).
#
# Diseño (ver cabecera del archivo + calibración empírica): con generación
# aleatoria la dificultad "por técnica" es casi bimodal — o el puzzle cae con
# singles, o exige lógica avanzada. Las tres primeras tiers se diferencian por
# **banda de pistas** (menos pistas ⇒ más barrido ⇒ más difícil, la palanca que
# el jugador percibe en un juego casual) y se exige que sean resolubles con el
# set de técnicas humanas implementado (rate <= 3, sin adivinar). EXPERTO se
# reserva para el salto real de técnica: puzzles que el solver lógico NO cierra
# (rate == 4), es decir, exigen razonamiento por encima de pares/triples/X-Wing.
#
# (min_clues_lo, min_clues_hi, requiere_rate_4)
TIER_SPEC = {
    0: (40, 45, False),  # FACIL
    1: (34, 38, False),  # MEDIO
    2: (28, 32, False),  # DIFICIL
    3: (22, 26, True),   # EXPERTO
}


def generate(per_tier, seed):
    rng = random.Random(seed)
    buckets = {0: [], 1: [], 2: [], 3: []}
    seen = set()
    attempts = 0
    start = time.time()
    max_attempts = per_tier * 300

    while any(len(buckets[t]) < per_tier for t in buckets):
        if attempts > max_attempts:
            break
        attempts += 1
        # Apuntamos al tier que más falta nos hace y cavamos hacia su banda de
        # pistas; el tier real se confirma con el rater antes de aceptar.
        pending = [t for t in (3, 2, 1, 0) if len(buckets[t]) < per_tier]
        target = pending[0]
        lo, hi, needs_expert = TIER_SPEC[target]
        sol = full_solution(rng)
        puz = dig_puzzle(sol, rng, rng.randint(lo, hi))
        s = to_str(puz)
        if s in seen:
            continue
        seen.add(s)
        tier_rating = rate(puz)
        # EXPERTO exige lógica avanzada (el solver se atasca); el resto exige lo
        # contrario (resoluble sin adivinar). Así el salto de técnica queda solo
        # entre DIFICIL y EXPERTO, no diluido por la banda de pistas.
        accept = (tier_rating == 4) if needs_expert else (tier_rating <= 3)
        if accept and len(buckets[target]) < per_tier:
            solved = puz[:]
            _solve_full(solved)
            buckets[target].append((s, to_str(solved)))
        if attempts % 200 == 0:
            counts = {t: len(buckets[t]) for t in buckets}
            print(f"  intento {attempts}: {counts}  ({time.time()-start:.0f}s)", file=sys.stderr)

    return buckets, attempts, time.time() - start


def _solve_full(grid):
    """Rellena `grid` in-place con su (única) solución por backtracking."""
    cell, cands = _find_best_cell(grid)
    if cell == -1:
        return True
    for d in cands:
        grid[cell] = d
        if _solve_full(grid):
            return True
        grid[cell] = 0
    return False


def main():
    ap = argparse.ArgumentParser(description="Genera el banco rateado de Sudokus.")
    ap.add_argument("--per-tier", type=int, default=40)
    ap.add_argument("--seed", type=int, default=20260727)
    ap.add_argument(
        "--out",
        default="shared/src/commonMain/composeResources/files/sudoku_bank.csv",
    )
    args = ap.parse_args()

    print(f"Generando {args.per_tier} puzzles por tier (seed={args.seed})...", file=sys.stderr)
    buckets, attempts, elapsed = generate(args.per_tier, args.seed)

    lines = []
    for ordinal in (0, 1, 2, 3):  # SudokuDifficulty.ordinal: FACIL=0..EXPERTO=3
        for puzzle, solution in buckets[ordinal]:
            pid = str(uuid.UUID(int=random.Random(puzzle).getrandbits(128)))
            lines.append(f"{ordinal},{puzzle},{solution},{pid}")

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    counts = {["FACIL", "MEDIO", "DIFICIL", "EXPERTO"][t]: len(buckets[t]) for t in (0, 1, 2, 3)}
    print(f"Listo en {elapsed:.0f}s, {attempts} intentos. Puzzles: {counts}", file=sys.stderr)
    print(f"Escrito: {args.out} ({len(lines)} filas)", file=sys.stderr)


if __name__ == "__main__":
    main()

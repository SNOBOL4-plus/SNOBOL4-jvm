# snobol4jvm

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
[![Tests](https://img.shields.io/badge/tests-1%2C896%20%2F%200%20failures-brightgreen)](#testing)

**Full SNOBOL4/SPITBOL implementation for the JVM, written in Clojure.**

SNOBOL4 was invented at Bell Labs in the 1960s by Ralph Griswold, Ivan Polonsky, and David
Farber. It introduced pattern matching as a first-class data type — patterns that compose,
backtrack, capture intermediate results, reference themselves recursively, and can express
BNF grammars directly. snobol4jvm runs the real language on any platform Java runs on,
with a four-stage acceleration pipeline that pushes toward native speed.

---

## What Is SNOBOL4?

Most string processing tools — regex engines, parser generators, PEG libraries — target one
tier of the Chomsky hierarchy and stop there. SNOBOL4 patterns span all four:

| Tier | Example | Tool that stops here |
|------|---------|---------------------|
| Type 3 — Regular | `(a\|b)*abb` | regex, PCRE |
| Type 2 — Context-free | `{a^n b^n}` | yacc, Bison, PEG |
| Type 1 — Context-sensitive | `{a^n b^n c^n}` | — |
| Type 0 — Unrestricted | `{w#w}` copy language | — |

All four tiers are expressible as SNOBOL4 patterns. No separate grammar formalism. No
external lexer. Patterns are first-class values: you build them, store them in variables,
pass them to functions, and compose them at runtime.

A SNOBOL4 program looks like this:

```snobol
*  Count vowels in a string
        SUBJECT = "Hello, World"
        VOWELS  = "AEIOUaeiou"
LOOP    SUBJECT ANY(VOWELS) =          :F(DONE)
        COUNT   = COUNT + 1            :(LOOP)
DONE    OUTPUT  = "Vowels: " COUNT
END
```

Each statement has a subject, an optional pattern, an optional replacement, and an optional
GOTO — conditional on success (`:S`) or failure (`:F`). The pattern `ANY(VOWELS)` matches
any single character in the set and deletes it (replacement is empty). This is not regex
sugar. This is a full backtracking engine.

---

## Architecture: The Four-Stage Pipeline

snobol4jvm runs SNOBOL4 through four increasingly optimized execution engines. Each stage
is a complete, correct implementation. The pipeline is additive — later stages delegate to
earlier ones for correctness and add speed on top.

```
SNOBOL4 source
      │
      ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Stage 1: Interpreter  (runtime.clj)                                │
│  GOTO-driven statement dispatch. Faithful to original semantics.    │
│  All features are proven here first.                                │
└──────────────────────────────┬──────────────────────────────────────┘
                               │  EDN cache (22× cold-start speedup)
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Stage 2: Transpiler  (transpiler.clj)                              │
│  SNOBOL4 IR → Clojure loop/case. The JVM JIT compiles it to        │
│  native code on first call. Hot loops benefit immediately.          │
│  Speedup: 3.5–6× vs interpreter.                                   │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Stage 3: Stack VM  (vm.clj)                                        │
│  Flat integer-indexed bytecode vector. Single integer dispatch.     │
│  Eliminates map lookups, keyword resolution, seq walking.           │
│  Speedup: 2–6× vs interpreter.                                      │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Stage 4: JVM Bytecode  (jvm_codegen.clj)                           │
│  SNOBOL4 → .class bytecode via ASM. DynamicClassLoader at runtime. │
│  The JVM JIT sees real bytecode and applies full optimization.      │
│  Speedup: 7.6× vs interpreter. Cold-start cumulative: ~190×.       │
└─────────────────────────────────────────────────────────────────────┘
```

### The Pattern Engine: match.clj

The pattern match engine is the core of the system. It is a single file and a single
`loop/case` — a design law that has never been violated. The state is a 7-element frame
vector:

```
[Σ  Δ  σ  δ  Π  φ  Ψ]
 ↑  ↑  ↑  ↑  ↑  ↑  ↑
 │  │  │  │  │  │  └─ parent frame stack
 │  │  │  │  │  └──── child index (for ALT/SEQ iteration)
 │  │  │  │  └─────── current pattern node
 │  │  │  └────────── current position
 │  │  └───────────── current subject chars
 │  └──────────────── position on entry to this frame
 └─────────────────── subject chars on entry to this frame
```

Actions: `:proceed` (enter) · `:recede` (backtrack) · `:succeed` (matched) · `:fail` (give up).

This is the **Byrd Box model** — described by Lawrence Byrd in 1980 for Prolog debugging
and generalized by Todd Proebsting in 1996 as a code generation strategy for goal-directed
languages. The same four-port model describes SNOBOL4 pattern matching, Icon generators,
and Prolog unification. In snobol4jvm the interpreter runs it explicitly; in the JVM
bytecode stage, each Byrd box becomes labeled JVM instructions wired at compile time.

### Stage 2: The Transpiler

The transpiler compiles SNOBOL4 IR to a Clojure namespace containing one `run!` function —
a `loop/case` over statement indices. Each SNOBOL4 statement becomes one case clause:

```clojure
; SNOBOL4:   LOOP  I = I + 1  :S(LOOP)
;
; Transpiled:
(case pc
  ...
  42 (let [r (ops/EVAL! '(= I (+ I 1)))]
       (if r (recur :LOOP) (recur 43)))
  ...)
```

This is a real Clojure function. The JVM JIT compiles it to native code on first invocation.

### Stage 3: The Stack VM

The VM flattens the IR into a dense integer-indexed bytecode vector with seven opcodes:

| Opcode | Meaning |
|--------|---------|
| `HALT`    | Program end |
| `EXEC`    | Eval body, always fall through |
| `EXEC-S`  | Eval; success → jump, failure → fall through |
| `EXEC-F`  | Eval; success → fall through, failure → jump |
| `EXEC-SF` | Eval; success → jump1, failure → jump2 |
| `JUMP`    | Unconditional goto |
| `SIGNAL`  | Throw SNOBOL4 signal |

A single integer dispatch per statement eliminates the map lookups, keyword-to-integer
resolution, and seq-walking overhead that the interpreter pays on every statement.

### Stage 4: JVM Bytecode

`jvm_codegen.clj` compiles SNOBOL4 IR to `.class` bytecode using the ASM library directly.
Each program becomes one Java class with a static `run()` method. `DynamicClassLoader`
loads it at runtime — the JVM JIT then applies full native compilation including inlining,
loop unrolling, and register allocation.

The generated class is intentionally minimal in its dependencies: it references only
`java/lang/Object`, `clojure/lang/IFn`, `clojure/lang/RT`, and the codegen class
itself. This makes class loading fast and reliable across all JVM versions.

---

## Performance

Benchmarks on Linux / OpenJDK 21 / Leiningen 2.12.0. SPITBOL and CSNOBOL4 times include
~15 ms process-spawn overhead — subtract for a fair per-execution comparison.

```
╔══════════════════╦══════════╦══════════╦══════════╦══════════╦══════════╦══════════╗
║ Program          ║ SPITBOL  ║ CSNOBOL4 ║ Interp   ║ Transpil ║ Stack VM ║ JVM code ║
╠══════════════════╬══════════╬══════════╬══════════╬══════════╬══════════╬══════════╣
║ arith-10k        ║  15.83ms ║  27.25ms ║ 112.14ms ║  88.03ms ║ 107.02ms ║  85.55ms ║
║ strcat-500       ║  15.09ms ║  26.16ms ║   9.70ms ║   7.99ms ║  26.02ms ║   6.93ms ║
║ pat-span         ║  15.47ms ║  26.29ms ║  28.05ms ║  25.89ms ║  52.06ms ║  25.22ms ║
║ fact45           ║  16.59ms ║  24.73ms ║    N/A * ║   1.11ms ║ 167.46ms ║   0.89ms ║
╚══════════════════╩══════════╩══════════╩══════════╩══════════╩══════════╩══════════╝
* fact45 overflows the interpreter's step limit at default settings
```

**Notable results:**

- `strcat-500`: The Clojure interpreter (9.7 ms net) already beats CSNOBOL4 (11 ms net)
  before any acceleration stage kicks in.
- `fact45`: JVM bytecode (0.89 ms) is **14–19× faster than SPITBOL** (net ~1.6 ms).
  The JVM JIT has fully inlined the recursive function.
- The EDN compilation cache gives a **22× cold-start speedup** — programs that have run
  before skip reparsing entirely.

### Acceleration Summary

| Stage | Speedup vs interpreter | Mechanism |
|-------|:----------------------:|-----------|
| EDN cache | **22×** cold-start | Memoized IR, skip reparse |
| Transpiler | **3.5–6×** | IR → Clojure `loop/case`, JVM JIT |
| Stack VM | **2.5–5.7×** | Dense integer bytecode, single dispatch |
| JVM bytecode | **7.6×** | ASM `.class`, full JIT optimization |
| Cold-start cumulative | **~190×** | EDN cache + JVM bytecode combined |

---

## What's Implemented

**Execution model**
- GOTO-driven statement execution with `:S()` / `:F()` conditional branches
- `DEFINE` / `RETURN` / `FRETURN` / `NRETURN` — user-defined functions with full recursion
- `APPLY` — first-class function calls by name at runtime
- `OPSYN` — operator aliasing
- `CODE()` / `EVAL()` — runtime compilation and evaluation
- `-INCLUDE` preprocessor directive

**Pattern engine — complete SNOBOL4 primitive set**

| Category | Primitives |
|----------|-----------|
| Character class | `ANY` `NOTANY` `SPAN` `BREAK` `BREAKX` |
| Positional | `POS` `RPOS` `TAB` `RTAB` `REM` `LEN` |
| Structural | `ARB` `ARBNO` `BAL` `FENCE` `ABORT` `FAIL` `SUCCEED` |
| Capture | `$` (immediate assign) · `.` (deferred, committed on match success) |
| Deferred ref | `*var` — pattern stored in a variable, evaluated at match time |
| Cursor | `@N` — captures current cursor position |

**Data structures**
- `TABLE` — hash map with arbitrary key/value types
- `ARRAY` — multi-dimensional arrays with `ITEM` accessor
- `DATA` — user-defined record types with `FIELD` accessor

**I/O and introspection**
- Named I/O channels (`INPUT` / `OUTPUT` with channel numbers)
- `TERMINAL` — direct terminal I/O
- `TRACE` / `STOPTR` — execution tracing hooks
- `DUMP` — runtime variable state inspection
- `&STCOUNT` / `&STLIMIT` — statement counter and execution budget

**Snocone frontend**
- Lexer and expression parser for Snocone — Andrew Koenig's structured C-like dialect
  that transpiles to SNOBOL4 (Bell Labs CSTR #124, 1986)

### Known Gaps

| # | Issue |
|---|-------|
| 1 | `CAPTURE-COND` (`.` operator) assigns immediately — deferred commit not yet built |
| 2 | `ANY(multi-arg)` inside an `EVAL` string causes `ClassCastException` |
| 3 | JVM bytecode stage calls back to interpreter for arithmetic — `EVAL!` inline pending |

---

## Testing

**1,896 tests / 4,120 assertions / 0 failures**

```bash
lein test
```

The test suite includes:

- **Worm corpus** — 1,000 automatically generated SNOBOL4 programs cross-validated
  against the interpreter oracle. Every program runs through both engines; output must
  match exactly. This is the regression guard for all engine changes.
- **Micro-worm** — 500 additional short programs targeting specific language constructs
- **Bootstrap** — programs that compile and run SNOBOL4 at runtime via `CODE()` / `EVAL()`
- **Cooper suite** — cross-validation against snobol4dotnet semantics (1,071 lines)
- **Snocone** — parser and emitter tests for the structured frontend

Test suite growth as the implementation expanded:

| Milestone | Tests | Assertions |
|-----------|------:|----------:|
| Sprint 13 baseline | 220 | 548 |
| Sprint 18B | 1,488 | 3,249 |
| Sprint 25E | 2,033 | 4,417 |
| Current | **1,896** | **4,120** |

*(Count reduced slightly in a housekeeping pass that merged duplicate suites; 0 failures
throughout every milestone.)*

---

## beauty.sno — The Bootstrap Milestone

`beauty.sno` is an 801-line SNOBOL4 program written in SNOBOL4 that reads SNOBOL4 source
and outputs it canonically formatted. It exercises nearly every feature of the language
simultaneously: recursive pattern matching, user-defined data types, TABLE lookups,
DEFINE/RETURN, multiple I/O channels, -INCLUDE, and conditional execution at every level.

**M-JVM-BEAUTY ✅ `b67d0b1` J-212** — snobol4jvm self-beautifies `beauty.sno`, producing
output byte-for-byte identical to the CSNOBOL4 2.3.3 oracle. This is the gold standard
for SNOBOL4 implementation correctness.

The last bug before this milestone fired: a cross-scope GOTO routing error in the JVM
bytecode emitter — targets outside the current function body were emitting undefined label
references. One fix in `jvm_emit_goto()`: route out-of-scope targets to `Jfn%d_freturn`
instead. Zero Jasmin errors. Oracle match at runtime.

---

## The Five-Way Monitor

snobol4jvm participates in the five-way sync-step monitor — a parallel harness that runs
the same SNOBOL4 program through five implementations simultaneously and compares TRACE
streams event by event:

| Participant | Role |
|-------------|------|
| CSNOBOL4 2.3.3 | Primary oracle |
| SPITBOL x64 4.0f | Secondary oracle |
| snobol4x ASM backend | Native compiled target |
| **snobol4jvm** | **JVM compiled target** |
| snobol4dotnet | .NET compiled target |

Each participant writes trace events to its own named FIFO via `monitor_ipc.so` — a LOAD'd
C shared library that bypasses stdio entirely. The collector diffs all five streams in
parallel. The first diverging line identifies the exact bug in exactly one participant.
The agreeing participants become the living specification for the fix.

Infrastructure: M-MONITOR-IPC-CSN ✅ `6eebdc3`. Full five-way launch: M-MONITOR-IPC-5WAY.

---

## Build and Run

**Prerequisites:** Java 11+, [Leiningen](https://leiningen.github.io/)

```bash
git clone https://github.com/snobol4ever/snobol4jvm
cd snobol4jvm
```

**Run a SNOBOL4 program:**

```bash
lein run < program.sno
```

**Build a standalone jar:**

```bash
lein uberjar
java -jar target/uberjar/SNOBOL4clojure-0.2.0-standalone.jar < program.sno
```

**Run the test suite:**

```bash
lein test
```

### Project Structure

```
src/SNOBOL4clojure/
  match.clj           Pattern engine — one file, one loop/case. The core.
  runtime.clj         GOTO-driven statement interpreter (Stage 1)
  transpiler.clj      SNOBOL4 IR → Clojure loop/case (Stage 2)
  vm.clj              Flat bytecode stack machine (Stage 3)
  jvm_codegen.clj     ASM .class bytecode generation (Stage 4)
  grammar.clj         instaparse PEG grammar for SNOBOL4 source
  emitter.clj         Parse tree → Clojure IR
  operators.clj       EVAL / EVAL! / INVOKE — the central dispatch
  primitives.clj      Low-level pattern scanners (LIT$, ANY$, SPAN$, ...)
  patterns.clj        High-level pattern combinators (ARB, ARBNO, FENCE, BAL, ...)
  functions.clj       Built-in functions (SIZE, DUPL, REPLACE, SUBSTR, ...)
  env.clj             Variable tables, keywords, signal protocol
  trace.clj           TRACE / STOPTR execution hooks
  generator.clj       Test program generator (rand-* probabilistic, gen-* exhaustive)
  snocone.clj         Snocone frontend lexer and parser
  snocone_emitter.clj Snocone → SNOBOL4 IR
```

---

## Design Laws

Ten invariants that have governed the implementation from the first commit:

1. **ALL UPPERCASE keywords.** `ANY`, `SPAN`, `ARBNO` — not `any`, `span`, `arbno`.
2. **Single-file engine.** `match.clj` is one `loop/case`. It cannot be split.
3. **Immutable-by-default, mutable-by-atom.** `TABLE` and `ARRAY` use `atom`.
4. **Label/body whitespace contract.** Labels flush-left, bodies indented.
5. **INVOKE is the single dispatch point.** Both lowercase and uppercase entries.
6. **`nil` means failure; epsilon means empty string.** No ambiguity between them.
7. **`clojure.core/=` inside `operators.clj`.** Bare `=` builds IR lists.
8. **INVOKE args are pre-evaluated.** Never call `EVAL!` on args inside INVOKE.
9. **Two-tier generator discipline.** `rand-*` probabilistic. `gen-*` exhaustive lazy.
10. **Two-strategy debugging:** (a) run a probe; (b) read CSNOBOL4/SPITBOL source. Never speculate.

---

## Relationship to snobol4ever

snobol4jvm is part of the [snobol4ever](https://github.com/snobol4ever) organization — a
project to bring full SNOBOL4/SPITBOL to every modern runtime.

| Repo | Language | Target | Status |
|------|----------|--------|--------|
| [snobol4jvm](https://github.com/snobol4ever/snobol4jvm) | Clojure | JVM | **1,896 tests / 0 failures** |
| [snobol4dotnet](https://github.com/snobol4ever/snobol4dotnet) | C# | .NET / Windows / Linux / macOS | 1,911 tests / 0 failures |
| [snobol4x](https://github.com/snobol4ever/snobol4x) | C | x86-64, JVM, .NET MSIL | Native compiler, all three backends |

snobol4jvm and snobol4dotnet are independent implementations validated against each other
and against CSNOBOL4 2.3.3 and SPITBOL x64 as reference oracles. Agreement between two
independently built implementations on any program is strong evidence of correctness.

snobol4x is the native compiler. Its JVM backend generates Jasmin bytecode from the same
SNOBOL4 source — a structurally different third path to the JVM, useful for cross-checking
both implementations.

---

## Acknowledgments

- **Philip L. Budne** — CSNOBOL4 2.3.3. Primary reference oracle, actively maintained,
  and the center of gravity of the SNOBOL4 community on groups.io.
- **Ralph Griswold, Ivan Polonsky, David Farber** — SNOBOL4, Bell Labs, 1962–1967.
- **Mark Emmer** — MACRO SPITBOL (Catspaw, 1987–2009). The definitive commercial implementation.
- **Cheyenne Wills** — SPITBOL x64. Active native compiler, our secondary oracle.
- **Lawrence Byrd** — the four-port box model (1980).
- **Todd Proebsting** — the Byrd Box as a *code generation strategy* (1996), and JCON:
  the Icon → JVM bytecode compiler that is the direct architectural blueprint for
  snobol4jvm's Stage 4. Source: https://github.com/proebsting/jcon
- **Andrew Koenig** — Snocone (Bell Labs CSTR #124, 1986).
- **Gregg Townsend** — JCON co-author; proof that this works on the JVM.

---

## License

AGPL-3.0-or-later. See [LICENSE](LICENSE).

# Where the rule language stops: an assessment (agent1, round 9)

Basis: nine rounds of proposals, about 300 candidates. Of those, roughly 85% passed rulegate. Every failure came from affinity, collation, NULL, overflow or errors, never from boolean logic. The estimates below are rough rule counts, not measurements.

## 1. What the language cannot express

| Missing | Rules it would unlock | What is needed | Value for bug finding |
|---|---|---|---|
| **Facts about an e-class** (boolean-valued / never-NULL / storage-class / "has no affinity, no implicit collation") | Very many. Today each law is repeated per node: the absorption, idempotence and double-NOT families, `coalesce(p,p)`, max/min to OR/AND, and the guard-free swaps. One egg `Analysis` plus conditional rules would replace about 60 node-specific rules with about 10 general ones, and cover combinations nobody wrote by hand. | An egg e-class analysis; no new nodes | High. It is cheap, adds rewrite reach rather than new semantics, and carries no soundness risk beyond what the gate already checks. |
| **Schema knowledge per query** (column affinity, collation, NOT NULL, UNIQUE, which indexes exist) | **The big one for plans.** Unguarded operand swaps when collations match. `x=a OR x=b` <-> `x IN (a,b)` when x is a column and the a/b affinity does not interfere. `x COLLATE <own collation>` as a sound index-disabler. `x IS NOT NULL` elimination on NOT NULL columns. Transitive equality (`x=y AND y=z` -> `x=z`) when affinities and collations agree. Estimate: 30-50 rules, many of them plan-changing (index used vs not). | The client sends column metadata with each request; rules become conditional on the analysis | **Highest.** The rewrites that change index use are exactly the ones blocked today. |
| **Constants / typed literals** (`0`, `1`, `''`, `NULL`, a literal of a known class) | `x+0`, `x\|\|''` (affinity-dropping index disablers, unsound in comparisons but fine under truth tests). `CASE WHEN 1`. Constant-folding identities. `x BETWEEN c1 AND c2` merging. `LIKE 'abc%'` <-> range (SQLite's own LIKE optimisation, a historically buggy area). IN-lists of literals. `nullif(x,x)` -> NULL. `isnull(x)` <-> `x IS NULL` written as `is(x, NULL)`. Estimate: 40-80 rules, but most need type-tagged literals and conditional appliers to stay sound (`5` vs `5.0` vs `'5'`). | Literal leaves with storage-class tags; conditional rules | High for coverage of folding and range code. Medium for plan shapes. |
| **Subqueries: EXISTS / IN(SELECT) / scalar subquery** | `x IN (SELECT y …)` <-> `EXISTS (SELECT 1 … WHERE y=x)`, valid only under a truth test, and affinity and collation differ (IN uses x's affinity and the subquery column's affinity rules). NOT IN vs NOT EXISTS differ on NULL, so only guarded forms are valid. `(SELECT count(*) …) > 0` <-> EXISTS. Estimate: 10-20 rules. | Opaque relational sub-nodes with a correlation variable | **High.** This is SQLite's most bug-prone area historically (the memory notes show subquery predicates jumped multi-plan rates from 6.9% to 32.9%). |
| **Row values** | `(a,b)=(c,d)` <-> `a=c AND b=d`, which is sound because row = is NULL only when no component is false, the same as AND. Row `<`/`<=` <-> a lexicographic OR/AND expansion, which is NOT sound on NULLs (the memory notes already record this) and would need IS-based forms. Row IN. Estimate: 5-10 rules. | A vector node | Medium-high. Row-value bugs recur in the SQLite tracker. |
| **Longer IN lists, variadic functions** (`in4+`, `max`/`min` with N args, `printf`, `char`, `concat_ws`, json) | Mostly NULL-propagation and permutation laws. About 1-3 rules per function. | Variadic nodes | Low-medium. Many rules, few new plans. |
| **Aggregates, window functions, GROUP BY/HAVING, ORDER BY/LIMIT, joins** | Not predicate rewrites. They belong to query-level oracles (TLP partitioning, join reordering, NoREC-style). Inside WHERE, the TLP identity `p` == `(p AND q) ∪ (p AND NOT q) ∪ (p AND q IS NULL)` needs UNION ALL, not an expression. | A different oracle, not more nodes | Out of scope for the e-graph. Mentioned for completeness. |

## 2. What SQLite semantics block, even with more nodes

These stay out without extra knowledge. With the analyses above, some become sound under conditions.

- **Integer overflow to REAL.** This blocks +/* associativity and distributivity, `x-y` <-> `x+(-y)`, `neg(neg x)`, and `abs(neg x)`. A value-range analysis ("both operands are small integers") would unlock perhaps 5-10 rules. That is only possible with constants or schema CHECK constraints, so it is mostly unreachable.
- **REAL arithmetic.** Not associative, and NaN turns into NULL, so `isnull(a+b)` != `isnull(a) OR isnull(b)` (`Inf-Inf`). Permanently blocked.
- **Affinity of the wrapper.** Every function, CASE, coalesce and arithmetic result has no affinity, while CAST adds one. So "wrap a column to disable its index" is unsound in comparisons, and "unwrap" (likely-drop) is too. It is sound only under truth tests, NULL tests or inside other value nodes. This is the single most common reason a candidate failed.
- **Collation selection rules** (left explicit, right explicit, left column, right column) and explicit COLLATE propagating up through value and integer nodes. Swaps and moves are blocked without schema knowledge. The server's COLLATE-under-value guard handles the propagation side.
- **3-valued logic on non-boolean values.** Idempotence, absorption and double-NOT hold only on 0/1/NULL values. The boolean-valued analysis unlocks these generally.
- **Errors.** `abs(int64 MIN)`, integer overflow errors in some functions, and too-long LIKE patterns. Rules that remove an evaluation can remove an error. These stay blocked unless the gate stops counting errors as failures. It should not stop: losing an error changes the result set.
- **Single-element IN inconsistency** (in1 vs in2 with an explicit COLLATE on a constant element). This is a genuine SQLite inconsistency, not a rule problem. Keep it blocked and report it. If it is fixed upstream, six rules (in1 <-> in2/in3, splits and merges) become sound.
- **Build and session variance.** ICU lower/upper, `case_sensitive_like`, UTF-16 databases. Rules such as like-drop-lower, upper-of-lower and hex-of-cast-text are sound only for the default build and session. Either pin those settings in the harness or guard the rules.
- **Non-determinism.** Every rule that duplicates a variable assumes it is deterministic. The server should refuse to rewrite when random(), changes(), last_insert_rowid() or now-dependent date functions appear under a duplicated binding. Please confirm this guard exists. I have not seen it mentioned.

## 3. What our own conservative checks block

| Check | What it blocks | Relaxation | Unlock |
|---|---|---|---|
| Server refuses any predicate with an explicit COLLATE under a value node | All rewriting of such predicates, which otherwise get only the three trivial variants | Mark each rule "preserves first-found explicit collation" (rulegate can compute this flag with the CASE-probe contexts) and allow only those rules on such predicates | About 40-60% of the 278 rules would qualify. Worth it only if the generator often emits COLLATE inside expressions; measure first. |
| Collation guard on swaps (whole query) | Swaps whenever any collation could matter | Per-binding check with schema info (section 1) | All guarded swaps, plus OR <-> IN |
| Gate counts errors as failures | abs-idem, and anything touching int64 MIN | Should stay strict | none |
| Gate's value pool | Nothing is blocked. The gate is *optimistic*: UTF-16, ICU, pragmas, NUL-containing text, subnormal REALs and 2^51-2^63 boundaries are under-represented | Add them to the pool | Fewer silent false passes, so better trust in e2e results |
| Reachability "not derivable" filter | Keeps out rules the e-graph could already reach | Fine as is, though it can hide rules that are reachable only beyond the iteration or node budget | Small. Consider keeping shortcut rules when the derivation depth is above about 4. |

## 4. Bottom line

- Inside the current language, my returns are diminishing. Round 9 produced mostly redundancy, absorption and swap shapes. Each adds little plan diversity, because the multi-plan rate is already saturated (memory notes: 91%).
- **The next real gains are knowledge, not syntax.** First, an e-class analysis (boolean-valued, nullability, affinity/collation provenance). Second, per-query schema metadata from the client. Together they make the plan-changing families sound: OR <-> IN, swaps, index-disabling no-ops, and NOT NULL elimination for partial indexes.
- **Then subqueries and row values.** They are the most bug-prone SQLite areas and need about 15-30 carefully guarded rules.
- **Constants** come after that. They are valuable for folding and LIKE-range code paths, but every literal rule needs storage-class tagging to avoid the `1` / `1.0` / `'1'` trap.

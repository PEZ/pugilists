# Pugilist DC Experiments

Committed baseline (pre-dD): **1490 bytes** (9 bytes headroom). Codesize limit: 1499.
Working baseline (with dD): **1481 bytes** (18 bytes headroom).

All codesizes below from clean builds (`./gradlew clean build`).

## Context

The original Pugilist used `fastMoveFactors` (unsegmented, rolling depth 1) alongside segmented arrays for surf. The new DC-based Pugilist has no equivalent global regularization. Three subagent analyses converge: DC's `1/d²` kernel over-segments from tick 1 — no global prior. This gap disproportionately hurts against weak/nano bots with situation-independent targeting, and likely explains the 84% rumble regression (237/281 negative in BotCompare 2.5.6 vs 2.5.4).

Additionally, the observation vector lacks approach/retreat information (radial velocity component). This is orthogonal to existing dimensions — velocity captures speed along the bot's heading, but not toward/away from us. Obs[5] is always 0 for surf, making it a free slot.

## Experiments

### A. Soft Kernel (`d*d + K`)

**Hypothesis**: Adding a constant K to the denominator provides automatic unsegmented behavior when data is sparse (d² << K), while preserving full segmentation when data is abundant (d² >> K). Classic kernel density estimation regularization.

**Change in `dcFill`**:
```java
// From:
scores[(int) o[0]] += (w.charAt(6) + i) / (d * d);
// To:
scores[(int) o[0]] += (w.charAt(6) + i) / (d * d + 5);
```

**Estimated byte cost**: ~4 bytes
**Status**: Tested — +0.66% (20-bot), +1.80% (worst-drops)

---

### B. Broader Kernel via `+d` in Numerator

**Hypothesis**: Adding `d` to the numerator creates a `1/d` background contribution (softer than `1/d²`), acting as implicit unsegmented learning. Self-adjusting: surf (recency=1) gets strong broadening early; gun (recency=20) naturally dampened.

**Change in `dcFill`**:
```java
// From:
scores[(int) o[0]] += (w.charAt(6) + i) / (d * d);
// To:
scores[(int) o[0]] += (w.charAt(6) + i + d) / (d * d);
```

Decomposes to: `(recency+i)/d² + 1/d`

**Estimated byte cost**: ~3 bytes
**Status**: Tested — +0.51% (20-bot), +2.48% (worst-drops), lost Sedan Unsegmented Recency (`+i`)

**Hypothesis**: Adding a pure `i` term (no dependence on distance `d`) provides a completely unsegmented recency-weighted channel. Self-balancing: when situational match is good (d small), segmented dominates; when poor (d large), unsegmented takes over.

**Change in `dcFill`**:
```java
// From:
scores[(int) o[0]] += (w.charAt(6) + i) / (d * d);
// To:
scores[(int) o[0]] += (w.charAt(6) + i) / (d * d) + i;
```

**Estimated byte cost**: ~3-4 bytes
**Status**: Tested — FAILED, -13.01% (20-bot), overwhelms DC scoring

---

### D. Approach/Retreat in Obs[5] (Both Gun and Surf)

**Hypothesis**: Delta distance (approaching vs retreating) is orthogonal to existing dimensions. Velocity captures speed along the bot's heading but doesn't decompose into radial vs lateral. A bot at distance=400 approaching has very different future positions than one retreating. Obs[5] is always 0 for surf (wasted) — replacing the ternary with `dD` for both gun and surf removes the wallSmooth reverse call (saving bytes) and adds approach/retreat info.

**Changes**:
```java
// New static field in Pugilist:
static double dD;

// Replace `enemyDistance = e.getDistance();` with:
dD = enemyDistance - (enemyDistance = e.getDistance());

// In initObs, replace obs[5]:
// From:
surfable ? 0 : Pugilist.wallSmooth(orbitCenter, loc, direction)
// To:
Pugilist.dD
```

Timing: surf wave gets previous scan's dD (one-tick lag, harmless), gun gets current dD.
Loses wallSmooth reverse for gun — trades a moderate-value gun dimension for a high-value universal one.

**Estimated byte cost**: Net -9 bytes (1490 -> 1481)
**Status**: Implemented — now the working baseline. All experiments above tested with dD present.

---

### E. Approach/Retreat in Obs[5] (Surf Only)

**Hypothesis**: Same as D but only for surf — keep wallSmooth reverse for gun. Assign `ew.obs[5]` after initObs instead of modifying initObs.

**Changes**:
```java
// New static field in Pugilist:
static double dD;

// Replace `enemyDistance = e.getDistance();` with:
dD = enemyDistance - (enemyDistance = e.getDistance());

// After ew.initObs(...):
ew.obs[5] = dD;
```

**Estimated byte cost**: ~6-8 bytes (no ternary savings, just adding field + assignment)
**Status**: Not tested

---

### F. Approach/Retreat as New 8th Dimension

**Hypothesis**: Add as a completely new dimension without sacrificing any existing one. Requires extending obs array, weight strings, and loop bound.

**Changes**: Extend obs to 8 elements, change `j < 7` to `j < 8`, add weight char at position 6 and move recency to position 7 in both GW and SW.

**Estimated byte cost**: ~15-20 bytes (way over budget)
**Status**: Skipped — over budget

---

## Results

| Experiment | Bytes | APS (20-bot) | APS (worst-drops) | Notes |
|------------|-------|--------------|--------------------|-------|
| Pre-dD     | 1490  | —            | —                  | Committed master before dD |
| D. dD only | 1481  | 67.67%       | 63.27%             | Working baseline, all experiments on top of this |
| A. d*d+5   | 1485  | 68.33%       | 65.07%             | NeophytePRAL 52.71→61.81 |
| B. +d num  | 1484  | 68.18%       | 65.75%             | Lost Sedan+Spark; best worst-drops |
| C. +i      | 1484  | 54.66%       | —                  | FAILED: +i overwhelms DC, 11/20 wins |

## Benchmark Commands

```bash
# Quick sanity (1 match per opponent):
bb benchmark pez.mini.Pugilist 35

# Stable stats (2 matches):
bb benchmark pez.mini.Pugilist 70

# Compare against baseline commit:
bb benchmark pez.mini.Pugilist 70 35 <baseline-commit>

# Worst-drops roster:
bb benchmark pez.mini.Pugilist 70 35 HEAD config/worst-drops-254-256.edn
```

# Pugilist DC Experiments

Committed baseline (pre-dD): **1490 bytes** (9 bytes headroom). Codesize limit: 1499.
Current baseline (H2+H5: clamped stick via orbit helper): **1479 bytes** (20 bytes headroom).

All codesizes below from clean builds (`./gradlew clean build`).

## Context

The original Pugilist used `fastMoveFactors` (unsegmented, rolling depth 1) alongside segmented arrays for surf. The new DC-based Pugilist has no equivalent global regularization. Three subagent analyses converge: DC's `1/d²` kernel over-segments from tick 1 — no global prior. This gap disproportionately hurts against weak/nano bots with situation-independent targeting, and likely explains the 84% rumble regression (237/281 negative in BotCompare 2.5.6 vs 2.5.4).

Additionally, the observation vector lacks approach/retreat information (radial velocity component). This is orthogonal to existing dimensions — velocity captures speed along the bot's heading, but not toward/away from us. Obs[5] (wallSmooth reverse for gun, always 0 for surf) is a free slot for surf.

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
**Status**: Tested — +0.51% (20-bot), +2.48% (worst-drops), lost Sedan

---

### C. Unsegmented Recency (`+i`)

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

**Hypothesis**: Delta distance (approaching vs retreating) is orthogonal to existing dimensions. Velocity captures speed along the bot's heading but doesn't decompose into radial vs lateral. A bot at distance=400 approaching has very different future positions than one retreating. Obs[5] stores wallSmooth reverse for gun but is always 0 for surf (wasted) — replacing it with `dD` for both gun and surf removes the wallSmooth reverse call (saving bytes) and adds approach/retreat info.

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

**Hypothesis**: Same as D but only for surf — keep wallSmooth reverse in obs[5] for gun. Override surf's obs[5] (normally 0) with dD after initObs.

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

**Estimated byte cost**: ~15-20 bytes (estimated)
**Status**: Tested as G+F — see G+F below

---

### G. Reverse WallSmooth for Both Gun and Surf (no dD)

**Hypothesis**: The original code gave surf 0 for obs[5] while gun got reverse wallSmooth. Surf might benefit from knowing the enemy's wall constraint — a wall-pinned enemy has more predictable firing angles. Test by removing the ternary entirely: unconditional `wallSmooth(orbitCenter, loc, direction)` for both. This drops dD to test reverse wallSmooth in isolation.

**Changes**:
```java
// In initObs, replace obs[5]:
// From (dD baseline):
Pugilist.dD
// To:
Pugilist.wallSmooth(orbitCenter, loc, direction)
// Also: remove dD field and revert enemyDistance assignment
```

**Byte cost**: -11 bytes vs original (1490 → 1479), -2 bytes vs dD baseline
**Status**: Tested — +0.24% (20-bot), +0.06% (worst-drops). Foilist 38.54→50.36 (flipped to WIN)

---

### G+A. Reverse WallSmooth + Soft Kernel

**Hypothesis**: Combine G (reverse wallSmooth for surf) with A (soft kernel d*d+5). Should get Foilist benefit from wall info AND weak-bot benefit from regularization.

**Result**: The soft kernel blurs the wall segmentation that helps against Foilist. CunobelinDC flipped to win (52.92%) but Foilist went back to loss (38.21%). The two modifications pull in opposite directions.

**Byte cost**: 1483 bytes (16 bytes headroom)
**Status**: Tested — -0.38% (20-bot), +0.93% (worst-drops). Foilist regressed to 38.21% (loss)

---

### G+F. Reverse WallSmooth + dD as 8th Dimension

**Hypothesis**: Combine G (reverse wallSmooth) with F (dD as new 8th obs dimension). Keep all 6 existing dimensions, add dD at obs[7] with weight 12. Gets both wall info AND approach/retreat without sacrificing anything.

**Result**: The extra dimension dilutes the DC kernel — more dimensions spread the distance metric thinner, weakening the wall precision that beats Foilist. Foilist regressed to 35.14%, CunobelinDC also lost. The curse of dimensionality outweighs the info gain.

**Byte cost**: 1494 bytes (5 bytes headroom)
**Status**: Tested — -0.94% (20-bot), +1.33% (worst-drops). Foilist 35.14% (loss), CunobelinDC 45.17% (loss), 18/20 wins

---

### A+G+F. Soft Kernel + Reverse WallSmooth + 8th Dimension

**Hypothesis**: The 8-dim kernel dilution (G+F) might be compensated by soft kernel regularization (A). The `+5` constant provides a floor that prevents over-segmentation in higher dimensions.

**Result**: Soft kernel helps the 8-dim framework (67.55% vs 66.97% raw G+F) but not enough to beat G alone. Lost Sedan (47.55%). Foilist improved to 43.42% but still a loss.

**Byte cost**: 1498 bytes (1 byte headroom)
**Status**: Tested — -0.36% (20-bot), +2.35% (worst-drops, 15/15 wins!). 17/20 wins on 20-bot

---

### B+G+F. Broader Kernel + Reverse WallSmooth + 8th Dimension

**Hypothesis**: B's `+d` numerator scales with distance, which is naturally larger in higher dimensions. This should provide better regularization than A's fixed `+5` for the 8-dim kernel.

**Result**: Best 8-dim combination. The `+d` broadening compensates for dimensionality dilution. Tied with A for best overall APS. Worst-drops essentially tied with B alone. Foilist close to win (48.20%).

**Byte cost**: 1497 bytes (2 bytes headroom)
**Status**: Tested — +0.43% (20-bot), +2.44% (worst-drops). 18/20 wins

---

### H. Clamped Stick Distance (wallSmoothedDestination only)

**Hypothesis**: The wallSmoothed destination uses `enemyDistance / 5.0` as its stick distance, which at close range (e.g. dist=100 → stick=20px) barely clears the bot's own radius. The old Pugilist used `minMax(enemyDistance / 1.7, 40, 150)` — a clamped range ensuring meaningful wall clearance. Test clamping the destination stick while leaving wallSmooth probe stick unchanged.

**Changes**:
```java
// In wallSmoothedDestination, replace stick:
// From:
enemyDistance / 5.0
// To:
Math.max(40, Math.min(150, enemyDistance / 1.7))
```

**Byte cost**: 1491 bytes (8 bytes headroom)
**Status**: Tested — +0.33% (20-bot). Lost Sedan

---

### H2. Clamped Stick Distance (both wallSmooth and wallSmoothedDestination)

**Hypothesis**: H showed the clamped stick helps overall but the inconsistency between probe stick (`enemyDistance/5.0`) and destination stick (clamped) might cause mismatches. Clamp both for consistency.

**Changes**: Apply same `Math.max(40, Math.min(150, enemyDistance / 1.7))` to both `wallSmooth` and `wallSmoothedDestination` stick parameters.

**Byte cost**: 1503 bytes (4 OVER LIMIT)
**Status**: Tested — +0.37% (20-bot). Sedan held (back to win). Cannot ship without byte savings

---

### H3. Clamped Stick — Drop Upper Bound (both functions)

**Hypothesis**: H2 is 4 bytes over limit. Dropping `Math.min(150, ...)` simplifies to `Math.max(40, enemyDistance / 1.7)`. At typical Robocode distances the upper clamp rarely fires (150px stick requires dist > 255), so behavioral change is minimal. Saves bytecode from removing Math.min call + constant.

**Changes**:
```java
// Both wallSmooth and wallSmoothedDestination stick:
// From:
enemyDistance / 5.0
// To:
Math.max(40, enemyDistance / 1.7)
```

**Estimated byte cost**: 1491 bytes (8 bytes headroom)
**Status**: Tested — -2.63% (20-bot). Foilist 36.45% (LOSS). Upper bound matters!

---

### H4. Clamped Stick — Extract to Static Field (both functions)

**Hypothesis**: Computing the full-clamp stick once in a static field and referencing it in both functions saves duplicating the expression. Field read (getstatic) is cheaper than repeated Math.max/Math.min calls.

**Changes**:
```java
// New static field:
static double stk;

// In onScannedRobot, after enemyDistance = e.getDistance():
stk = Math.max(40, Math.min(150, enemyDistance / 1.7));

// Both functions use stk instead of enemyDistance / 5.0
```

**Estimated byte cost**: 1493 bytes (6 bytes headroom)
**Status**: Tested — -0.48% (20-bot). Slight regression

---

### H3+H4. Extract Stick — Lower Bound Only

**Hypothesis**: Combine H3 (drop upper clamp) with H4 (extract to field). Minimal expression, single computation, dual reference.

**Changes**:
```java
static double stk;
// After enemyDistance = e.getDistance():
stk = Math.max(40, enemyDistance / 1.7);
// Both functions use stk
```

**Estimated byte cost**: 1487 bytes (12 bytes headroom)
**Status**: Tested — -3.13% (20-bot). Worst of the H variants — confirms upper bound essential

---

### H5. Extract Orbit-Project Helper

**Hypothesis**: Both `wallSmooth` and `wallSmoothedDestination` compute `project(from, absoluteBearing(from, toward) - direction * (PI/2 + 0.25 - (w / 100.0)), stick)`. Extracting to a helper method saves duplicating the expression at the cost of method overhead. Likely marginal with only 2 call sites — testing to verify.

**Changes**:
```java
static Point2D orbitProject(Point2D from, Point2D toward, double direction, double w) {
    return project(from, absoluteBearing(from, toward)
            - direction * (Math.PI / 2 + 0.25 - (w / 100.0)), enemyDistance / 5.0);
}
// wallSmoothedDestination: return orbitProject(location, enemyLocation, direction, s - 1);
// wallSmooth: replace project(...) with orbitProject(from, toward, direction, w++)
```

**Estimated byte cost**: 1467 bytes (32 bytes headroom!) — saves 12 bytes vs baseline
**Status**: Tested — 67.61% (within noise of G baseline 67.91%). No behavioral change, pure byte savings.

---

### H2+H5. Full Clamped Stick via Orbit Helper

**Hypothesis**: H5 saves 12 bytes. H2 costs 24 bytes over G. Together, H2's full clamp fits inside H5's savings — same 1479 bytes as G baseline. Best of both: proper wall stick clamping with zero byte cost.

**Changes**: H5's orbit helper with `Math.max(40, Math.min(150, enemyDistance / 1.7))` as stick.

**Byte cost**: 1479 bytes (20 bytes headroom) — same as G!
**Status**: Tested — 66.54% (20-bot), 18/20 wins. Within variance of H2 (68.28%)

---

### H2+H5+B+F. Full Combo: Clamped Stick + Orbit Helper + Broader Kernel + 8th Dim

**Hypothesis**: Stack all promising modifications: H5's orbit helper for byte savings, H2's full clamp for wall clearance, B's `+d` broader kernel for regularization, F's dD as 8th dimension. B+G+F was the best combo at 68.34% — adding clamped stick should help wall-heavy opponents.

**Changes**: H2+H5 base + `+d` in numerator + dD as 8th obs dimension + 8-char weight strings.

**Byte cost**: 1497 bytes (2 bytes headroom)
**Status**: Tested — 67.48% (20-bot), 18/20 wins. Below B+G+F (68.34%); clamp doesn't help this combo

---

## Results

| Experiment | Bytes | Rounds | APS (20-bot) | APS (worst-drops) | Notes |
|------------|-------|--------|--------------|--------------------|---------|
| Pre-dD     | 1490  | —      | —            | —                  | Committed master before dD |
| D. dD only | 1481  | 70     | 67.67%       | 63.27%             | Working baseline, all experiments on top of this |
| A. d*d+5   | 1485  | 70     | 68.33%       | 65.07%             | NeophytePRAL 52.71→61.81 |
| B. +d num  | 1484  | 70     | 68.18%       | 65.75%             | Lost Sedan+Spark; best worst-drops |
| C. +i      | 1484  | 70     | 54.66%       | —                  | FAILED: +i overwhelms DC, 11/20 wins |
| G. revWS   | 1479  | 140    | 67.67%       | 63.33%             | Foilist WIN; 19/20 wins (avg of 67.91+67.43) |
| G+A        | 1483  | 70     | 67.29%       | 64.20%             | Foilist back to loss; soft kernel blurs wall info |
| G+F        | 1494  | 70     | 66.97%       | 64.60%             | 8th dim dilutes kernel; Foilist 35.14%, 18/20 wins |
| A+G+F      | 1498  | 70     | 67.55%       | 65.62%             | 15/15 worst-drops wins! Spark flipped; 17/20 wins |
| B+G+F      | 1497  | 70     | 68.34%       | 65.71%             | Best 8-dim combo; +d scales with dimensions, 18/20 |
| H. clamp dest | 1491 | 70   | 68.24%       | 64.19%             | Lost Sedan; clamped wallSmoothedDestination stick |
| H2. clamp both | 1503 | 70  | 68.28%       | 64.94%             | Sedan back; 15/15 wd wins; OVER LIMIT by 4 bytes |
| H3. no upper  | 1491  | 70   | 65.28%       | 63.58%             | Upper bound matters! Foilist 36.45% |
| H4. extract full | 1493 | 70 | 67.43%      | 64.63%             | Slight regression |
| H3+H4         | 1487  | 70   | 64.78%       | 64.35%             | Worst H variant; confirms upper bound essential |
| H5. orbit helper | 1467 | 70 | 67.61%      | 64.64%             | **-12 bytes!** No behavioral change |
| H2+H5         | 1479  | 140  | 66.69%       | 65.38%             | **Adopted baseline**; avg of 66.54+66.84, 19/20 wins |
| H2+H5+B+F     | 1497  | 70   | 67.48%       | 64.93%             | 15/15 wd wins; below B+G+F on 20-bot |

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

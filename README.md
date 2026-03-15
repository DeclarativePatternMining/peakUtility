# PeakUtility

**PeakUtility** is a Constraint Programming (CP) based miner for **High-Utility Itemsets (HUI)** and **Utility-Peak Itemsets (UPI)** in transactional databases. It uses the [Choco-solver](https://choco-solver.org/) library and implements eight pruning rules (R1–R8) through a dedicated propagator (`PropPeakUtility`).

- **HUI mode**: finds all itemsets whose combined utility ≥ a given threshold.
- **UPI mode**: enforces the *peak* condition — no item can be added to or removed from the pattern without strictly lowering its utility.

---

## Table of Contents

1. [Requirements](#requirements)
2. [Build](#build)
3. [Available Programs](#available-programs)
   - [PeakUtility (Global constraint)](#1-peakutility-main-miner)
   - [HuimCpModel (CP model)](#2-huimcpmodel-alternative-cp-model)
   - [EfimRunner (EFIM baseline)](#3-efimrunner-efim-baseline)
   - [HUIToUPIPostprocessing](#4-huitoupipostprocessing)
   - [UPIToUMI](#5-upitoumifilter)
   - [PeakPatternDiffValidator](#6-peakpatterndiffvalidator)
   - [PeakPatternRuleTester](#7-peakpatternruletester)
4. [Scripts](#scripts)
   - [run_efim.sh](#run_efimsh)
   - [run_compare_efim_peakutility.sh](#run_compare_efim_peakutilitysh)
   - [run_heuristics_benchmark.sh](#run_heuristics_benchmarksh)
   - [run_incremental_rule_ablation.sh](#run_incremental_rule_ablationsh)
   - [run_rule8_k_activation.sh](#run_rule8_k_activationsh)
   - [run_rule8_k_impact.sh](#run_rule8_k_impactsh)
5. [Datasets](#datasets)
6. [Architecture Overview](#architecture-overview)

---

## Requirements

| Tool | Version |
|---|---|
| Java | ≥ 11 |
| Maven | ≥ 3.6 |
| (Optional) spmf.jar | for `EfimRunner` |

---

## Build

```bash
mvn package -DskipTests
```

This produces **7 fat JARs** inside `target/`:

| JAR | Main class |
|---|---|
| `peakutility-jar-with-dependencies.jar` | `cp26.mining.examples.PeakUtility` |
| `simple-cp-jar-with-dependencies.jar` | `cp26.mining.patterns.model.HuimCpModel` |
| `efim-runner-jar-with-dependencies.jar` | `cp26.mining.patterns.efim.EfimRunner` |
| `hui-to-upi-jar-with-dependencies.jar` | `cp26.mining.examples.peakUtility.postprocessing.HUIToUPIPostprocessing` |
| `upi-to-umi-jar-with-dependencies.jar` | `cp26.mining.examples.tools.UPIToUMI` |
| `pattern-validator-jar-with-dependencies.jar` | `cp26.mining.examples.tools.PeakPatternDiffValidator` |
| `rule-tester-jar-with-dependencies.jar` | `cp26.mining.examples.tools.PeakPatternRuleTester` |

---

## Available Programs

> **Prerequisite for all programs below:** the fat JARs must be built first.
> ```bash
> mvn package -DskipTests
> ```

### 1. PeakUtility (Global constraint)

```bash
java -jar target/peakutility-jar-with-dependencies.jar \
  <inputFile> <outputFile> <minUtil%> <mode> \
  [timeoutMs] [depthK] [varHeuristic] [valHeuristic] [rulesMask]
```

| # | Argument | Default | Description |
|---|---|---|---|
| 1 | `inputFile` | — | Path to the HUI-format dataset |
| 2 | `outputFile` | — | Path to write the result patterns |
| 3 | `minUtil%` | — | Minimum utility as a decimal percentage (e.g. `0.01` = 1%) |
| 4 | `mode` | `HUI` | `HUI` or `UPI` |
| 5 | `timeoutMs` | `300000` | Timeout in milliseconds (0 = no timeout) |
| 6 | `depthK` | `3` | Lookahead depth for Rule 8 |
| 7 | `varHeuristic` | `ITEM_UTIL_ASC` | Variable selection strategy (see below) |
| 8 | `valHeuristic` | `MIN` | Value selection: `MIN` or `MAX` |
| 9 | `rulesMask` | `11111111` | 8-bit binary string enabling/disabling each rule R1–R8 |

**Variable heuristics:**

| Value | Description |
|---|---|
| `ITEM_UTIL_ASC` | Lowest cumulative item utility first |
| `ITEM_UTIL_DESC` | Highest cumulative item utility first |
| `TWU_ASC` | Lowest TWU first |
| `TWU_DESC` | Highest TWU first |
| `CHOCO_INPUT_ORDER` | Choco default input order |
| `CHOCO_FIRST_FAIL` | Choco First Fail |
| `CHOCO_DOM_OVER_WDEG` | Choco Dom/WDeg |
| `CHOCO_DEFAULT` | Choco default |

**Rules mask** — each character (0 or 1) enables/disables the corresponding rule:

| Position | Rule | Description |
|---|---|---|
| 1 | R1 | Cover feasibility lower bound |
| 2 | R2 | Cover feasibility upper bound |
| 3 | R3 | TWU-based utility upper bound |
| 4 | R4 | Utility upper bound (tighter) |
| 5 | R5 | Utility upper bound (tightest) |
| 6 | R6 | Full-assignment peak check (subset dominance) |
| 7 | R7 | Full-assignment peak check (superset dominance) |
| 8 | R8 | Partial-assignment k-depth lookahead |

**Examples:**

```bash
# Mine UPI patterns at 1% minimum utility, 5-second timeout, all rules enabled
java -jar target/peakutility-jar-with-dependencies.jar \
  datasets/Data_SPMF/connect_utility_spmf.txt \
  results_upi.txt \
  0.01 UPI 5000 3 TWU_DESC MIN 11111111

# Mine HUI patterns only (rules R1-R5, no peak rules)
java -jar target/peakutility-jar-with-dependencies.jar \
  datasets/test_dataset/tiny_manual.txt \
  results_hui.txt \
  0.05 HUI 0 3 TWU_DESC MIN 11111100
```

**Output format** — one pattern per line:
```
1 3 7 #UTIL: 42
```

**Machine-readable summary line** printed to stdout:
```
RESULT  algorithm=PeakUtility  patterns=N  timeoutReached=false  nodes=...  solverTimeSec=...  rule8ExecCount=...  rule8PruneCount=...
```

---

### 2. HuimCpModel (CP model)

A reified CP model for HUI mining.

```bash
java -jar target/simple-cp-jar-with-dependencies.jar \
  <file> <outPath> <minUtil%> [mode] [depthK] [varSel] [valSel] [timeoutSec]
```

| # | Argument | Default | Description |
|---|---|---|---|
| 1 | `file` | `datasets/test_dataset/cp26.txt` | Input dataset |
| 2 | `outPath` | `datasets/output_huim_cp.txt` | Output file |
| 3 | `minUtil%` | — | Minimum utility percentage (e.g. `0.01`) |
| 4 | `mode` | `HUI` | `HUI` or `UPI` |
| 5 | `depthK` | `3` | Depth K for UPI mode |
| 6 | `varSel` | `input` | Variable selection strategy |
| 7 | `valSel` | `max` | Value selection: `min` or `max` |
| 8 | `timeoutSec` | `1200` | Timeout in seconds (20 min) |

**Example:**

```bash
# With pre-built JAR (after mvn package -DskipTests)
java -jar target/simple-cp-jar-with-dependencies.jar \
  datasets/test_dataset/tiny_manual.txt \
  /tmp/output_huim.txt \
  0.05 HUI 3 input max 60

# Without pre-built JAR (via Maven)
mvn -q exec:java \
  -Dexec.mainClass=cp26.mining.patterns.model.HuimCpModel \
  -Dexec.args="datasets/test_dataset/tiny_manual.txt /tmp/output_huim.txt 0.05 HUI 3 input max 60"
```

---

### 3. EfimRunner (EFIM baseline)

> Requires `spmf.jar`. You can download it from [philippe-fournier-viger.com/spmf](https://www.philippe-fournier-viger.com/spmf/index.php?link=download.php).

```bash
java -jar target/efim-runner-jar-with-dependencies.jar \
  <dataset> <minUtil> [spmfJarPath] [timeoutMinutes]
```

| # | Argument | Default | Description |
|---|---|---|---|
| 1 | `dataset` | — | Dataset name or path (resolved relative to `datasets/`) |
| 2 | `minUtil` | — | Minimum utility as an **absolute integer** |
| 3 | `spmfJarPath` | `SPMF/spmf.jar` | Path to the SPMF jar |
| 4 | `timeoutMinutes` | `5` | Hard kill timeout in minutes |

Output is written to `datasets/patterns/<dataset>/HUI.txt`.

**Example:**

```bash
java -jar target/efim-runner-jar-with-dependencies.jar \
  connect_utility_spmf 10000 SPMF/spmf.jar 10
```

---

### 4. HUIToUPIPostprocessing

Post-processes a HUI result file (from EFIM or PeakUtility) and retains only true **UPI** patterns.

```bash
java -jar target/hui-to-upi-jar-with-dependencies.jar \
  [inputFile.txt [outputFile.txt] [timeoutSec]]
```

| Argument | Default | Description |
|---|---|---|
| `inputFile` | foodmart dataset | Path to the HUI pattern file, or a dataset name |
| `outputFile` | auto-generated | Path to write UPI results |
| `timeoutSec` | none | Optional deadline in seconds |

**Examples:**

```bash
# From a named dataset (resolves datasets/patterns/<name>/HUI.txt automatically)
java -jar target/hui-to-upi-jar-with-dependencies.jar connect_utility_spmf

# From an explicit file with output path and timeout
java -jar target/hui-to-upi-jar-with-dependencies.jar \
  datasets/patterns/connect_utility_spmf/HUI.txt \
  datasets/patterns/connect_utility_spmf/UPI.txt \
  120
```

---

### 5. UPIToUMI (filter)

Filters a UPI pattern file to **UMI (Utility-Maximal Itemsets)**.

```bash
java -jar target/upi-to-umi-jar-with-dependencies.jar \
  <inputUPI.txt> <outputUMI.txt>
```

**Example:**

```bash
java -jar target/upi-to-umi-jar-with-dependencies.jar \
  datasets/patterns/connect_utility_spmf/UPI.txt \
  datasets/patterns/connect_utility_spmf/UMI.txt
```

---

### 6. PeakPatternDiffValidator

Correctness and debugging tool. Compares two pattern files (e.g., produced with a rule enabled vs. disabled), reports patterns unique to each file, and verifies whether those patterns satisfy the true UPI peak condition on the raw database.

```bash
java -jar target/pattern-validator-jar-with-dependencies.jar \
  <dataset> <onFile> <offFile> [maxReport]
```

| # | Argument | Default | Description |
|---|---|---|---|
| 1 | `dataset` | — | Raw HUI-format dataset |
| 2 | `onFile` | — | Pattern file A (e.g., rule enabled) |
| 3 | `offFile` | — | Pattern file B (e.g., rule disabled) |
| 4 | `maxReport` | `20` | Max number of invalid patterns to print |

**Example:**

```bash
java -jar target/pattern-validator-jar-with-dependencies.jar \
  datasets/test_dataset/tiny_manual.txt \
  results_rule_on.txt \
  results_rule_off.txt \
  50
```

---

### 7. PeakPatternRuleTester

Unit-tests one specific hand-picked itemset against the `PropPeakUtility` propagator. Fixes the pattern in the CP model, calls `propagate()`, and reports whether a contradiction is raised and which rule triggered it.

```bash
java -jar target/rule-tester-jar-with-dependencies.jar \
  <dataset> <minUtil> <rulesMask> <itemsCsv>
```

| # | Argument | Description |
|---|---|---|
| 1 | `dataset` | Raw HUI-format dataset |
| 2 | `minUtil` | Integer minimum utility threshold |
| 3 | `rulesMask` | 8-bit binary string (e.g. `11111111`) |
| 4 | `itemsCsv` | Comma-separated item indices, e.g. `"1,5,10"` |

**Example:**

```bash
java -jar target/rule-tester-jar-with-dependencies.jar \
  datasets/test_dataset/tiny_manual.txt \
  20 11111111 "1,3,5"
```

---

## Scripts

All scripts assume they are run from the **project root** and that `mvn package` has been run beforehand.

### run_efim.sh

Runs EFIM on a single dataset.

```bash
bash scripts/run_efim.sh <dataset> <minUtil> [spmfJarPath] [timeoutMinutes]
# Example:
bash scripts/run_efim.sh connect_utility_spmf 10000 SPMF/spmf.jar 5
```

Output: `datasets/patterns/<dataset>/HUI.txt`

---

### run_compare_efim_peakutility.sh

Runs both EFIM and PeakUtility (HUI and UPI modes) on one dataset or all of `test_dataset/`, then computes pattern-set intersections.

```bash
bash scripts/run_compare_efim_peakutility.sh \
  [datasetSelector] [minUtil] [timeoutSec] [depthK] \
  [varHeur] [valHeur] [rulesMask] [spmfJar]
# Defaults: test_dataset, 10, 60, 3, TWU_DESC, MAX, 11111111
```

Output: `datasets/results/compare_efim_peakutility/summary.tsv`  
Columns: `EFIM_HUI | PEAK_HUI | HUI_intersection | EFIM_UPI | PEAK_UPI | UPI_intersection`

---

### run_heuristics_benchmark.sh

Benchmarks all 14 variable × value heuristic combinations (7 variable selectors × 2 value selectors) for PeakUtility.

```bash
bash scripts/run_heuristics_benchmark.sh \
  <dataset> <minUtilAbs> <timeoutSecPerRun> [mode] [depthK] [rulesMask]
# Example:
bash scripts/run_heuristics_benchmark.sh connect_utility_spmf 10000 60 UPI 3 11111111
```

Output: `datasets/results/heuristics/<dataset>/heuristics_benchmark.csv`

---

### run_incremental_rule_ablation.sh

Ablation study: runs PeakUtility with progressively more rules enabled (R1 only → R1+R2 → … → all R1–R8) to quantify each rule's individual contribution.

```bash
bash scripts/run_incremental_rule_ablation.sh \
  <dataset> <minUtilAbs> [timeoutSec=60] [depthK=3] \
  [mode=UPI] [varHeuristic=TWU_DESC] [valHeuristic=MIN]
```

Output:
- `datasets/results/ablation_<dataset>_mu<minUtil>/ablation.csv`
- `datasets/results/ablation_<dataset>_mu<minUtil>/ablation_table.tex`

---

### run_rule8_k_activation.sh

Measures how many times Rule 8 fires (executions and prunes) across a sweep of `depthK` values.

```bash
bash scripts/run_rule8_k_activation.sh \
  <dataset> <minUtilAbs> <timeoutSecPerRun> \
  [mode] [rulesMask] [varHeuristic] [valHeuristic] [kListCsv]
# Default kList: "1,2,3,5,10,20,50"
# Note: rulesMask bit 8 must be '1'
```

Output: `datasets/results/rule8_k_activation/<dataset>/rule8_k_activation.csv` + `.tex`

---

### run_rule8_k_impact.sh

Benchmarks PeakUtility performance (patterns found, solver time, nodes explored) across a sweep of `depthK` values, studying the tradeoff between Rule 8 aggressiveness and overhead.

```bash
bash scripts/run_rule8_k_impact.sh \
  <dataset> <minUtilAbs> <timeoutSecPerRun> \
  [mode] [rulesMask] [varHeuristic] [valHeuristic] [kListCsv]
# Default kList: "1,2,3,5,10,20,50,100"
```

Output: `datasets/results/rule8_k_impact/<dataset>/rule8_k_impact.csv` + `.tex`

---

## Datasets

### `datasets/Data_SPMF/`

| File | Description |
|---|---|
| `accidents_utility_spmf.txt` | Accidents dataset |
| `BMS_utility_spmf.txt` | BMS (e-commerce clickstream) dataset |
| `chess_utility_spmf.txt` | Chess game dataset |
| `connect_utility_spmf.txt` | Connect-4 game dataset |
| `ecommerce_utility_spmf.txt` | E-commerce transactions dataset |
| `foodmart_utility_spmf.txt` | FoodMart retail dataset |
| `fruithut_utility_spmf.txt` | Fruithut retail dataset |
| `kosarak_utility_spmf.txt` | Kosarak click-stream dataset |
| `mushroom_utility_spmf.txt` | Mushroom dataset |
| `pumsb_utility_spmf.txt` | PUMSB census dataset |
| `retail_utility_spmf.txt` | Retail transactions dataset |

### `datasets/SYNTH/`

47 synthetic datasets `S01_synth.txt` – `S47_synth.txt`, used for large-scale benchmarking across varying density, size, and utility distributions.

### `datasets/test_dataset/`

| File | Description |
|---|---|
| `tiny_manual.txt` | Minimal hand-crafted dataset for quick sanity checks |
| `cp26.txt` | Small cp26 reference dataset |
| `dense_high_utility.txt` | Dense transactions, high utility values |
| `dense_small_uniform.txt` | Dense transactions, uniform utility distribution |
| `sparse_small_skewed.txt` | Sparse transactions, skewed utility distribution |
| `correlated_items.txt` | Items with correlated co-occurrence |
| `anticorrelated_items.txt` | Items with anticorrelated co-occurrence |

---

## Architecture Overview

```
cp26.mining
├── examples
│   ├── PeakUtility.java                     ← Main miner (CLI entry point)
│   ├── peakUtility/postprocessing/
│   │   └── HUIToUPIPostprocessing.java      ← HUI → UPI post-filter
│   └── tools/
│       ├── UPIToUMI.java                    ← UPI → UMI filter
│       ├── PeakPatternDiffValidator.java    ← Correctness validator
│       └── PeakPatternRuleTester.java       ← Rule unit tester
└── patterns
    ├── constraints/
    │   └── PropPeakUtility.java             ← Core Choco propagator (R1–R8)
    ├── efim/
    │   └── EfimRunner.java                  ← EFIM baseline runner
    ├── io/
    │   ├── HUIReader.java                   ← Dataset reader
    │   ├── TransactionalDatabase.java       ← In-memory DB (BitSet, TWU, utilities)
    │   └── values/                          ← Value reader interfaces
    ├── model/
    │   └── HuimCpModel.java                 ← Alternative CP model
    ├── search/strategy/selectors/           ← Variable selectors (TWU, ItemUtil, …)
    └── util/
        ├── UtilityList_0.java               ← Utility list per item
        └── Custom_Element.java             ← (tid, iutil, rutil) entry
```

## Run PeakUtility
```
mvn -q -DskipTests exec:java \
  -Dexec.mainClass=cp26.mining.examples.PeakUtility \
  -Dexec.args="datasets/Data_SPMF/mushroom_utility_spmf.txt datasets/output_peakutil.txt 0.05 HUI 60000 3 TWU_DESC MAX"
```

## EFIM baseline
```
bash scripts/run_efim.sh datasets/Data_SPMF/mushroom_utility_spmf.txt 0,05 SPMF/spmf.jar 5
```

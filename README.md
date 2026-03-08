# CP26 HUIM (Anonymous Submission Package)

This repository contains an anonymous, HUIM-only codebase for CP26 evaluation.

## Scope

Implemented approaches:
- CP decomposition model (`org.cp26.huim.runners.HuimCpRunner`)
- Global CP propagators (`org.cp26.huim.global.*`)
- Peak Utility extension (`org.cp26.huim.global.PropPeakUtility`)

Out of scope (removed from this package):
- Non-HUIM models
- IDE/OS artifacts
- Benchmark result dumps and temporary outputs

## Requirements

- Java 17+
- Maven 3.8+

## Build

```bash
mvn -q test
```

## Run

### 1) CP decomposition

```bash
mvn -q clean compile exec:java \
  -Dexec.mainClass="org.cp26.huim.runners.HuimCpRunner" \
  -Dexec.args="--file data/foodmart.txt --output out_cp.txt --minutil 1000"
```

### 2) Global CP propagator

```bash
mvn -q clean compile exec:java \
  -Dexec.mainClass="org.cp26.huim.runners.HUIMGlobalRunner" \
  -Dexec.args="--file data/foodmart.txt --minutil 1000 --projection ARRAY"
```

## Optional constraints (supported by runners)

- `--include` comma-separated item IDs
- `--exclude` comma-separated item IDs
- `--minsize` / `--maxsize`
- `--maxutil`
- `--tumin` / `--tumax`
- `--projection` (`ARRAY` or `BITSET`) for global/peak utility propagators

## Data

Sample datasets are included under `data/` in SPMF utility format:

`items : transactionUtility : utilities`

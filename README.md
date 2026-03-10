# PeakUtility (CP26 anonymized)

This repository contains a CP model for High-Utility Itemset (HUI) and Utility-Peak Itemset (UPI) mining.

## Layout
- `datasets/` input datasets and generated patterns
- `scripts/` experiment and comparison scripts
- `src/` source code
- `archive/` previous code versions and removed modules

## Build
```
mvn -q -DskipTests compile
```

## Run PeakUtility
```
mvn -q -DskipTests exec:java \
  -Dexec.mainClass=cp26.mining.examples.PeakUtility \
  -Dexec.args="datasets/Data_SPMF/mushroom_utility_spmf.txt datasets/output_peakutil.txt 20000 HUI 60000 3 TWU_DESC MAX"
```

## EFIM baseline
```
bash scripts/run_efim.sh datasets/Data_SPMF/mushroom_utility_spmf.txt 20000 SPMF/spmf.jar 5
```

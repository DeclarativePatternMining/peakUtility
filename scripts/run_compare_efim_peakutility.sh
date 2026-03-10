#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Usage:
#   bash scripts/run_compare_efim_peakutility.sh [datasetSelector] [minUtil] [timeoutSec] [depthK] [varHeur] [valHeur] [spmfJar]
#
# datasetSelector:
#   - "test_dataset" (default): run all files in datasets/test_dataset/*.txt
#   - explicit file path
#   - file name inside datasets/test_dataset (e.g. tiny_manual.txt)
#   - dataset base name without extension (e.g. tiny_manual)

DATASET_SELECTOR="${1:-test_dataset}"
MINUTIL="${2:-10}"
TIMEOUT_SEC="${3:-60}"
DEPTHK="${4:-3}"
VAR_HEUR="${5:-TWU_DESC}"
VAL_HEUR="${6:-MAX}"
SPMF_JAR="${7:-$ROOT_DIR/SPMF/spmf.jar}"

if ! [[ "$TIMEOUT_SEC" =~ ^[0-9]+$ ]] || [ "$TIMEOUT_SEC" -le 0 ]; then
  echo "Error: timeoutSec must be a positive integer."
  exit 1
fi

if ! [[ "$MINUTIL" =~ ^-?[0-9]+$ ]]; then
  echo "Error: minUtil must be an integer."
  exit 1
fi

resolve_single_dataset() {
  local selector="$1"
  if [ -f "$selector" ]; then
    echo "$selector"
    return 0
  fi
  if [ -f "$ROOT_DIR/datasets/test_dataset/$selector" ]; then
    echo "$ROOT_DIR/datasets/test_dataset/$selector"
    return 0
  fi
  if [ -f "$ROOT_DIR/datasets/test_dataset/$selector.txt" ]; then
    echo "$ROOT_DIR/datasets/test_dataset/$selector.txt"
    return 0
  fi
  return 1
}

declare -a DATASETS=()
if [ "$DATASET_SELECTOR" = "test_dataset" ]; then
  while IFS= read -r f; do
    DATASETS+=("$f")
  done < <(find "$ROOT_DIR/datasets/test_dataset" -maxdepth 1 -type f -name "*.txt" | sort)
else
  if resolved="$(resolve_single_dataset "$DATASET_SELECTOR")"; then
    DATASETS+=("$resolved")
  else
    echo "Error: dataset '$DATASET_SELECTOR' not found."
    echo "Expected one of:"
    echo "  - test_dataset"
    echo "  - explicit file path"
    echo "  - file name/base name inside datasets/test_dataset"
    exit 1
  fi
fi

if [ "${#DATASETS[@]}" -eq 0 ]; then
  echo "Error: no dataset found to run."
  exit 1
fi

OUT_ROOT="$ROOT_DIR/datasets/results/compare_efim_peakutility"
mkdir -p "$OUT_ROOT"
SUMMARY="$OUT_ROOT/summary.tsv"
COUNTS_MD="$OUT_ROOT/summary_counts.md"

echo -e "dataset\tEFIM_HUI\tPEAK_HUI\tHUI_INTERSECTION\tEFIM_UPI\tPEAK_UPI\tUPI_INTERSECTION" > "$SUMMARY"

normalize_items_only() {
  local in_file="$1"
  local out_file="$2"
  if [ ! -f "$in_file" ]; then
    : > "$out_file"
    return
  fi
  perl -ne '
    chomp;
    next if /^\s*$/;
    my ($left) = split(/\s+#UTIL:/, $_, 2);
    next unless defined $left;
    my @items = grep { length($_) } split(/\s+/, $left);
    @items = sort { $a <=> $b } @items;
    print join(" ", @items), "\n";
  ' "$in_file" | sort -u > "$out_file"
}

count_lines() {
  local f="$1"
  if [ -f "$f" ]; then
    wc -l < "$f" | tr -d ' '
  else
    echo 0
  fi
}

echo "Compiling project once..."
(cd "$ROOT_DIR" && mvn -q -DskipTests clean compile >/dev/null)

for ds in "${DATASETS[@]}"; do
  base="$(basename "$ds" .txt)"
  out_dir="$OUT_ROOT/$base"
  mkdir -p "$out_dir"

  efim_hui="$out_dir/efim_HUI.txt"
  efim_upi="$out_dir/efim_UPI.txt"
  peak_hui="$out_dir/peak_HUI.txt"
  peak_upi="$out_dir/peak_UPI.txt"

  timeout_ms=$((TIMEOUT_SEC * 1000))
  timeout_min=$(( (TIMEOUT_SEC + 59) / 60 ))
  if [ "$timeout_min" -lt 1 ]; then
    timeout_min=1
  fi

  echo "[RUN] dataset=$base minutil=$MINUTIL timeout=${TIMEOUT_SEC}s depthK=$DEPTHK var=$VAR_HEUR val=$VAL_HEUR"

  # 1) EFIM HUI
  (cd "$ROOT_DIR" && bash ./scripts/run_efim.sh "$ds" "$MINUTIL" "$SPMF_JAR" "$timeout_min" >/dev/null || true)
  efim_raw_hui="$ROOT_DIR/datasets/patterns/$base/HUI.txt"
  if [ -f "$efim_raw_hui" ]; then
    cp "$efim_raw_hui" "$efim_hui"
  else
    : > "$efim_hui"
    echo "  [WARN] EFIM did not produce HUI file for $base (format/timeout/empty). Continuing with empty EFIM result."
  fi

  # 2) EFIM UPI via postprocessing
  (cd "$ROOT_DIR" && mvn -q -DskipTests exec:java \
    -Dexec.mainClass="cp26.mining.examples.peakUtility.postprocessing.HUIToUPIPostprocessing" \
    -Dexec.args="$efim_hui $efim_upi $TIMEOUT_SEC" >/dev/null)

  # 3) PeakUtility HUI
  (cd "$ROOT_DIR" && mvn -q -DskipTests exec:java \
    -Dexec.mainClass="cp26.mining.examples.PeakUtility" \
    -Dexec.args="$ds $peak_hui $MINUTIL HUI $timeout_ms $DEPTHK $VAR_HEUR $VAL_HEUR" >/dev/null)

  # 4) PeakUtility UPI
  (cd "$ROOT_DIR" && mvn -q -DskipTests exec:java \
    -Dexec.mainClass="cp26.mining.examples.PeakUtility" \
    -Dexec.args="$ds $peak_upi $MINUTIL UPI $timeout_ms $DEPTHK $VAR_HEUR $VAL_HEUR" >/dev/null)

  # 5) Compare itemsets (ignoring utility values)
  efim_hui_norm="$out_dir/efim_HUI.items.txt"
  efim_upi_norm="$out_dir/efim_UPI.items.txt"
  peak_hui_norm="$out_dir/peak_HUI.items.txt"
  peak_upi_norm="$out_dir/peak_UPI.items.txt"

  normalize_items_only "$efim_hui" "$efim_hui_norm"
  normalize_items_only "$efim_upi" "$efim_upi_norm"
  normalize_items_only "$peak_hui" "$peak_hui_norm"
  normalize_items_only "$peak_upi" "$peak_upi_norm"

  hui_intersection=$(comm -12 "$efim_hui_norm" "$peak_hui_norm" | wc -l | tr -d ' ')
  upi_intersection=$(comm -12 "$efim_upi_norm" "$peak_upi_norm" | wc -l | tr -d ' ')

  efim_hui_count=$(count_lines "$efim_hui_norm")
  peak_hui_count=$(count_lines "$peak_hui_norm")
  efim_upi_count=$(count_lines "$efim_upi_norm")
  peak_upi_count=$(count_lines "$peak_upi_norm")

  echo -e "$base\t$efim_hui_count\t$peak_hui_count\t$hui_intersection\t$efim_upi_count\t$peak_upi_count\t$upi_intersection" >> "$SUMMARY"
done

{
  echo "| Dataset | EFIM HUI | PeakUtility HUI | EFIM UPI | PeakUtility UPI |"
  echo "|---|---:|---:|---:|---:|"
  awk -F'\t' 'NR>1 { printf("| %s | %s | %s | %s | %s |\n", $1, $2, $3, $5, $6) }' "$SUMMARY"
} > "$COUNTS_MD"

echo "Done. Summary TSV : $SUMMARY"
echo "Done. Counts table: $COUNTS_MD"

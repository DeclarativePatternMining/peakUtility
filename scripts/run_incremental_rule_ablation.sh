#!/usr/bin/env bash
set -euo pipefail

if [ -z "${BASH_VERSION:-}" ]; then
  echo "Error: run this script with bash (not sh)." >&2
  echo "Use: bash ./scripts/run_incremental_rule_ablation.sh ..." >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || exit 1

DATASET_ARG="${1:-}"
MIN_UTIL="${2:-}"
TIMEOUT_SEC="${3:-60}"
DEPTH_K="${4:-3}"
MODE="${5:-UPI}"
VAR_HEUR="${6:-TWU_DESC}"
VAL_HEUR="${7:-MIN}"

if [ -z "$DATASET_ARG" ] || [ -z "$MIN_UTIL" ]; then
  echo "Usage: bash ./scripts/run_incremental_rule_ablation.sh <dataset> <minUtilAbs> [timeoutSec=60] [depthK=3] [mode=UPI] [varHeuristic=TWU_DESC] [valHeuristic=MIN]" >&2
  exit 1
fi

if ! [[ "$MIN_UTIL" =~ ^[0-9]+$ ]]; then
  echo "minUtilAbs must be a non-negative integer" >&2
  exit 1
fi
if ! [[ "$TIMEOUT_SEC" =~ ^[0-9]+$ ]] || [ "$TIMEOUT_SEC" -le 0 ]; then
  echo "timeoutSec must be a positive integer" >&2
  exit 1
fi
if ! [[ "$DEPTH_K" =~ ^[0-9]+$ ]] || [ "$DEPTH_K" -le 0 ]; then
  echo "depthK must be a positive integer" >&2
  exit 1
fi

resolve_dataset_path() {
  local arg="$1"
  if [ -f "$arg" ]; then
    echo "$(cd "$(dirname "$arg")" && pwd)/$(basename "$arg")"
    return
  fi
  local candidates=(
    "$REPO_ROOT/datasets/$arg"
    "$REPO_ROOT/datasets/$arg.txt"
    "$REPO_ROOT/datasets/Data_SPMF/$arg"
    "$REPO_ROOT/datasets/Data_SPMF/$arg.txt"
    "$REPO_ROOT/datasets/RADIS/$arg"
    "$REPO_ROOT/datasets/RADIS/$arg.txt"
    "$REPO_ROOT/dataset/Data_SPMF/$arg"
    "$REPO_ROOT/dataset/Data_SPMF/$arg.txt"
    "$REPO_ROOT/dataset/RADIS/$arg"
    "$REPO_ROOT/dataset/RADIS/$arg.txt"
  )
  for c in "${candidates[@]}"; do
    if [ -f "$c" ]; then
      echo "$c"
      return
    fi
  done
  # Fallback: search by file name in common dataset directories.
  local found
  found="$(find "$REPO_ROOT/data" "$REPO_ROOT/dataset" -type f \( -name "$arg" -o -name "$arg.txt" \) 2>/dev/null | head -n 1 || true)"
  if [ -n "$found" ]; then
    echo "$found"
    return
  fi
  echo ""
}

DATASET="$(resolve_dataset_path "$DATASET_ARG")"
if [ -z "$DATASET" ]; then
  echo "Dataset not found for '$DATASET_ARG'." >&2
  echo "Checked in: datasets/Data_SPMF, datasets/RADIS, dataset/Data_SPMF, dataset/RADIS, plus explicit path." >&2
  exit 1
fi

DATASET_KEY="$(basename "$DATASET" .txt)"
OUT_DIR="datasets/results/ablation_${DATASET_KEY}_mu${MIN_UTIL}"
CSV_OUT="$OUT_DIR/ablation.csv"
TEX_OUT="$OUT_DIR/ablation_table.tex"
mkdir -p "$OUT_DIR"

extract_field() {
  local line="$1"
  local key="$2"
  echo "$line" | tr '\t' '\n' | awk -F'=' -v k="$key" '$1==k{print $2}'
}

escape_latex() {
  printf '%s' "$1" | sed -e 's/[\\]/\\textbackslash{}/g' \
                         -e 's/_/\\_/g' \
                         -e 's/&/\\\&/g' \
                         -e 's/%/\\%/g' \
                         -e 's/#/\\#/g' \
                         -e 's/\$/\\$/g' \
                         -e 's/{/\\{/g' \
                         -e 's/}/\\}/g'
}

masks=(
  "all_on:11111111"
  "disable_R1:01111111"
  "disable_R2:10111111"
  "disable_R3:11011111"
  "disable_R4:11101111"
  "disable_R5:11110111"
  "disable_R6:11111011"
  "disable_R7:11111101"
  "disable_R8:11111110"
)

echo "Compiling project classes..."
mvn -q -DskipTests compile || exit 1

printf "variant,mask,itemsets_found,nodes,solver_time_s\n" > "$CSV_OUT"

for entry in "${masks[@]}"; do
  variant="${entry%%:*}"
  mask="${entry##*:}"
  out_file="$OUT_DIR/${variant}_patterns.txt"

  echo "Running $variant (mask=$mask)..."

  timeout_ms="$((TIMEOUT_SEC * 1000))"
  log="$(mvn -q -DskipTests exec:java \
    -Dexec.mainClass=cp26.mining.examples.PeakUtility \
    -Dexec.args="$DATASET $out_file $MIN_UTIL $MODE $timeout_ms $DEPTH_K $VAR_HEUR $VAL_HEUR $mask" 2>&1)"

  result_line="$(echo "$log" | awk '/^RESULT\talgorithm=PeakUtility/{line=$0} END{print line}')"

  if [ -z "$result_line" ]; then
    echo "Failed to parse RESULT line for $variant" >&2
    echo "--- begin log ---" >&2
    echo "$log" >&2
    echo "--- end log ---" >&2
    printf "%s,%s,NA,NA,NA\n" "$variant" "$mask" >> "$CSV_OUT"
    continue
  fi

  patterns="$(extract_field "$result_line" "patterns")"
  nodes="$(extract_field "$result_line" "nodes")"
  solver_time_s="$(extract_field "$result_line" "solverTimeSec")"

  printf "%s,%s,%s,%s,%s\n" "$variant" "$mask" "$patterns" "$nodes" "$solver_time_s" >> "$CSV_OUT"
done

{
  ds_tex="$(escape_latex "$DATASET")"
  echo "\\begin{table}[ht]"
  echo "\\centering"
  echo "\\caption{Rule ablation on ${ds_tex} (minUtil=${MIN_UTIL}, timeout=${TIMEOUT_SEC}s).}"
  echo "\\begin{tabular}{lrrr}"
  echo "\\hline"
  echo "Variant & Itemsets & Nodes & Time (s) \\\\"
  echo "\\hline"

  tail -n +2 "$CSV_OUT" | while IFS=',' read -r variant _mask itemsets nodes solver_time_s; do
    vtex="$(escape_latex "$variant")"
    echo "${vtex} & ${itemsets} & ${nodes} & ${solver_time_s} \\\\"
  done

  echo "\\hline"
  echo "\\end{tabular}"
  echo "\\end{table}"
} > "$TEX_OUT"

echo "Done."
echo "CSV : $CSV_OUT"
echo "LaTeX: $TEX_OUT"

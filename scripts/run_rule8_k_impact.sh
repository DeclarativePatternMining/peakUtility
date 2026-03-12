#!/usr/bin/env bash
set -euo pipefail

if [ -z "${BASH_VERSION:-}" ]; then
  echo "Error: run this script with bash (not sh)."
  echo "Use: bash ./scripts/run_rule8_k_impact.sh ..."
  exit 1
fi

# Benchmark impact of Rule 8 depth parameter k (depthK) on PeakUtility.
#
# Usage:
#   bash ./scripts/run_rule8_k_impact.sh <dataset> <minUtilAbs> <timeoutSecPerRun> [mode] [rulesMask] [varHeuristic] [valHeuristic] [kListCsv]
#
# Example:
#   bash ./scripts/run_rule8_k_impact.sh foodmart_utility_spmf 10000 60 UPI 111111 ITEM_UTIL_ASC MIN "3,5,10,20,50,100"
#   bash ./scripts/run_rule8_k_impact.sh chess_utility_spmf 150000 60 UPI 111111 ITEM_UTIL_ASC MIN "3,5,10,20,50,100"
#
# Output:
#   datasets/results/rule8_k_impact/<dataset>/rule8_k_impact.csv
#   datasets/results/rule8_k_impact/<dataset>/rule8_k_impact.log
#   datasets/results/rule8_k_impact/<dataset>/rule8_k_impact_table.tex

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/../pom.xml" ]; then
  ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
elif [ -f "$SCRIPT_DIR/pom.xml" ]; then
  ROOT_DIR="$SCRIPT_DIR"
else
  echo "Error: cannot locate project root (pom.xml) from script directory '$SCRIPT_DIR'"
  exit 1
fi

if [ "$#" -lt 3 ] || [ "$#" -gt 8 ]; then
  echo "Usage: bash ./scripts/run_rule8_k_impact.sh <dataset> <minUtilAbs> <timeoutSecPerRun> [mode] [rulesMask] [varHeuristic] [valHeuristic] [kListCsv]"
  exit 1
fi

DATASET_ARG="$1"
MINUTIL="$2"
TIMEOUT_SEC="$3"
MODE="${4:-UPI}"
RULES_MASK="${5:-11111111}"
VAR_HEUR="${6:-TWU_DESC}"
VAL_HEUR="${7:-MIN}"
K_LIST_CSV="${8:-1,2,3,5,10,20,50,100}"

if ! [[ "$MINUTIL" =~ ^[0-9]+$ ]]; then
  echo "Error: minUtilAbs must be a non-negative integer."
  exit 1
fi
if ! [[ "$TIMEOUT_SEC" =~ ^[0-9]+$ ]] || [ "$TIMEOUT_SEC" -le 0 ]; then
  echo "Error: timeoutSecPerRun must be a positive integer."
  exit 1
fi
if ! [[ "$RULES_MASK" =~ ^[01]{8}$ ]]; then
  echo "Error: rulesMask must be 8 chars of 0/1 (e.g. 11111111)."
  exit 1
fi

# Rule 8 must be enabled to study k impact.
if [ "${RULES_MASK:7:1}" != "1" ]; then
  echo "Error: rulesMask must enable Rule 8 (8th bit = 1). Current mask: $RULES_MASK"
  exit 1
fi

resolve_dataset_path() {
  local arg="$1"
  if [ -f "$arg" ]; then
    echo "$(cd "$(dirname "$arg")" && pwd)/$(basename "$arg")"
    return
  fi
  local candidates=(
    "$ROOT_DIR/datasets/$arg"
    "$ROOT_DIR/datasets/$arg.txt"
    "$ROOT_DIR/datasets/Data_SPMF/$arg"
    "$ROOT_DIR/datasets/Data_SPMF/$arg.txt"
    "$ROOT_DIR/datasets/RADIS/$arg"
    "$ROOT_DIR/datasets/RADIS/$arg.txt"
    "$ROOT_DIR/dataset/Data_SPMF/$arg"
    "$ROOT_DIR/dataset/Data_SPMF/$arg.txt"
    "$ROOT_DIR/dataset/RADIS/$arg"
    "$ROOT_DIR/dataset/RADIS/$arg.txt"
  )
  for c in "${candidates[@]}"; do
    if [ -f "$c" ]; then
      echo "$c"
      return
    fi
  done
  local by_name
  by_name="$(find "$ROOT_DIR/data" "$ROOT_DIR/dataset" -type f \( -name "$arg" -o -name "$arg.txt" \) 2>/dev/null | head -n 1 || true)"
  if [ -n "$by_name" ]; then
    echo "$by_name"
    return
  fi
  echo ""
}

DATASET_PATH="$(resolve_dataset_path "$DATASET_ARG")"
if [ -z "$DATASET_PATH" ]; then
  echo "Error: dataset not found for '$DATASET_ARG'."
  echo "Checked in: datasets/Data_SPMF, datasets/RADIS, dataset/Data_SPMF, dataset/RADIS, plus explicit path."
  exit 1
fi

DATASET_NAME="$(basename "$DATASET_PATH")"
DATASET_KEY="${DATASET_NAME%.*}"
OUT_DIR="$ROOT_DIR/datasets/results/rule8_k_impact/$DATASET_KEY"
mkdir -p "$OUT_DIR"

CSV_FILE="$OUT_DIR/rule8_k_impact.csv"
LOG_FILE="$OUT_DIR/rule8_k_impact.log"
RUN_OUT_DIR="$OUT_DIR/runs"
TEX_FILE="$OUT_DIR/rule8_k_impact_table.tex"
mkdir -p "$RUN_OUT_DIR"

echo "dataset=$DATASET_PATH minUtil=$MINUTIL timeoutSecPerRun=$TIMEOUT_SEC mode=$MODE rules=$RULES_MASK var=$VAR_HEUR val=$VAL_HEUR kList=$K_LIST_CSV" > "$LOG_FILE"
echo "run_ts,k,rule8_execs,rule8_prunes,itemsets_found,timeout_reached,nodes,solver_time_sec,mode,rules_mask,var_heuristic,val_heuristic" > "$CSV_FILE"

cd "$ROOT_DIR"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"

IFS=',' read -r -a K_VALUES <<< "$K_LIST_CSV"
if [ "${#K_VALUES[@]}" -eq 0 ]; then
  echo "Error: kListCsv is empty."
  exit 1
fi

for k in "${K_VALUES[@]}"; do
  k="$(echo "$k" | xargs)"
  if ! [[ "$k" =~ ^[0-9]+$ ]]; then
    echo "Error: invalid k value '$k' in list '$K_LIST_CSV' (must be non-negative integers)." >&2
    exit 1
  fi

  run_ts="$(date +%Y-%m-%dT%H:%M:%S)"
  out_file="$RUN_OUT_DIR/k_${k}.txt"

  timeout_ms="$((TIMEOUT_SEC * 1000))"
  cmd_output="$(mvn -q -DskipTests exec:java \
      -Dexec.mainClass=cp26.mining.examples.PeakUtility \
      -Dexec.args="$DATASET_PATH $out_file $MINUTIL $MODE $timeout_ms $k $VAR_HEUR $VAL_HEUR $RULES_MASK" 2>&1 || true)"

  echo "[$run_ts] k=$k" | tee -a "$LOG_FILE"
  echo "$cmd_output" >> "$LOG_FILE"

  result_line="$(echo "$cmd_output" | awk -F'\t' '/^RESULT\talgorithm=PeakUtility/ {print; exit}')"
  if [ -z "$result_line" ]; then
    echo "$run_ts,$k,0,0,0,true,0,0,$MODE,$RULES_MASK,$VAR_HEUR,$VAL_HEUR" >> "$CSV_FILE"
    continue
  fi

  rule8_execs="$(echo "$result_line" | tr '\t' '\n' | awk -F'=' '$1=="rule8ExecCount"{print $2}')"
  rule8_prunes="$(echo "$result_line" | tr '\t' '\n' | awk -F'=' '$1=="rule8PruneCount"{print $2}')"
  itemsets_found="$(echo "$result_line" | tr '\t' '\n' | awk -F'=' '$1=="patterns"{print $2}')"
  timeout_reached="$(echo "$result_line" | tr '\t' '\n' | awk -F'=' '$1=="timeoutReached"{print $2}')"
  nodes="$(echo "$result_line" | tr '\t' '\n' | awk -F'=' '$1=="nodes"{print $2}')"
  solver_time="$(echo "$result_line" | tr '\t' '\n' | awk -F'=' '$1=="solverTimeSec"{print $2}')"

  echo "$run_ts,$k,${rule8_execs:-0},${rule8_prunes:-0},${itemsets_found:-0},${timeout_reached:-true},${nodes:-0},${solver_time:-0},$MODE,$RULES_MASK,$VAR_HEUR,$VAL_HEUR" >> "$CSV_FILE"
done

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

{
  ds_tex="$(escape_latex "$DATASET_PATH")"
  echo "\\begin{table}[ht]"
  echo "\\centering"
  echo "\\caption{Rule 8 depth $k$ impact on ${ds_tex} (minUtil=${MINUTIL}, timeout=${TIMEOUT_SEC}s, mode=${MODE}, rules=${RULES_MASK}, var=${VAR_HEUR}, val=${VAL_HEUR}).}"
  echo "\\begin{tabular}{rrrrrr}"
  echo "\\hline"
  echo "$k$ & Rule8 execs & Rule8 prunes & Itemsets & Nodes & Time (s) \\\\"
  echo "\\hline"

  tail -n +2 "$CSV_FILE" | while IFS=',' read -r _run_ts k rule8_execs rule8_prunes itemsets timeout nodes solver_time _mode _rules _var _val; do
    echo "${k} & ${rule8_execs} & ${rule8_prunes} & ${itemsets} & ${nodes} & ${solver_time} \\\\"
  done

  echo "\\hline"
  echo "\\end{tabular}"
  echo "\\end{table}"
} > "$TEX_FILE"

echo "Benchmark finished."
echo "CSV: $CSV_FILE"
echo "LOG: $LOG_FILE"
echo "LaTeX: $TEX_FILE"

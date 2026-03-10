#!/usr/bin/env bash
set -euo pipefail

if [ -z "${BASH_VERSION:-}" ]; then
  echo "Error: run this script with bash (not sh)."
  echo "Use: bash ./scripts/run_heuristics_benchmark.sh ..."
  exit 1
fi

# Benchmark var/val heuristics (project + Choco baseline) on PeakUtilityIncremental.
#
# Usage:
#   bash ./scripts/run_heuristics_benchmark.sh <dataset> <minUtilAbs> <timeoutSecPerRun> [mode] [depthK] [rulesMask]
#   bash ./scripts/run_heuristics_benchmark.sh <dataset> <minUtilAbs> <mode> <timeoutSecPerRun> [depthK] [rulesMask]
#
# Example:
#   bash ./scripts/run_heuristics_benchmark.sh spmf_foodmart 10000 60 HUI 3 111111
#
# Output:
#   datasets/results/heuristics/<dataset>/heuristics_benchmark.csv
#   datasets/results/heuristics/<dataset>/heuristics_benchmark.log

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/../pom.xml" ]; then
  ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
elif [ -f "$SCRIPT_DIR/pom.xml" ]; then
  ROOT_DIR="$SCRIPT_DIR"
else
  echo "Error: cannot locate project root (pom.xml) from script directory '$SCRIPT_DIR'"
  exit 1
fi

if [ "$#" -lt 3 ] || [ "$#" -gt 6 ]; then
  echo "Usage: bash ./scripts/run_heuristics_benchmark.sh <dataset> <minUtilAbs> <timeoutSecPerRun> [mode] [depthK] [rulesMask]"
  echo "   or: bash ./scripts/run_heuristics_benchmark.sh <dataset> <minUtilAbs> <mode> <timeoutSecPerRun> [depthK] [rulesMask]"
  exit 1
fi

DATASET_ARG="$1"
MINUTIL="$2"

# Accept both:
#  A) dataset minUtil timeout [mode] [depth] [rules]
#  B) dataset minUtil mode timeout [depth] [rules]
if [[ "${3:-}" =~ ^[0-9]+$ ]]; then
  TIMEOUT_SEC="$3"
  MODE="${4:-UPI}"
  DEPTHK="${5:-3}"
  RULES_MASK="${6:-111111}"
else
  MODE="${3:-UPI}"
  TIMEOUT_SEC="${4:-60}"
  DEPTHK="${5:-3}"
  RULES_MASK="${6:-111111}"
fi

# Common typo accepted
if [ "$MODE" = "PUI" ]; then
  MODE="UPI"
fi

if ! [[ "$MINUTIL" =~ ^[0-9]+$ ]]; then
  echo "Error: minUtilAbs must be a non-negative integer."
  exit 1
fi
if ! [[ "$TIMEOUT_SEC" =~ ^[0-9]+$ ]] || [ "$TIMEOUT_SEC" -le 0 ]; then
  echo "Error: timeoutSecPerRun must be a positive integer."
  exit 1
fi
if ! [[ "$DEPTHK" =~ ^[0-9]+$ ]]; then
  echo "Error: depthK must be a non-negative integer."
  exit 1
fi
if ! [[ "$RULES_MASK" =~ ^[01]{6}$ ]]; then
  echo "Error: rulesMask must be 6 chars of 0/1 (e.g. 111111)."
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
  echo "Error: dataset not found for '$DATASET_ARG' (checked explicit path, datasets/<arg>, datasets/<arg>.txt)"
  exit 1
fi

DATASET_NAME="$(basename "$DATASET_PATH")"
DATASET_KEY="${DATASET_NAME%.*}"
OUT_DIR="$ROOT_DIR/datasets/results/heuristics/$DATASET_KEY"
mkdir -p "$OUT_DIR"

CSV_FILE="$OUT_DIR/heuristics_benchmark.csv"
LOG_FILE="$OUT_DIR/heuristics_benchmark.log"
RUN_OUT_DIR="$OUT_DIR/heuristics_runs"
mkdir -p "$RUN_OUT_DIR"

echo "dataset=$DATASET_PATH minUtil=$MINUTIL timeoutSecPerRun=$TIMEOUT_SEC mode=$MODE depthK=$DEPTHK rules=$RULES_MASK" > "$LOG_FILE"
echo "note=rulesMask is ignored by PeakUtility CLI" >> "$LOG_FILE"
echo "run_ts,var_heuristic,val_heuristic,mode,itemsets_found,timeout_reached,nodes,fails,decisions,solver_time_sec" > "$CSV_FILE"

VARS=(
  ITEM_UTIL_ASC
  ITEM_UTIL_DESC
  TWU_ASC
  TWU_DESC
  CHOCO_INPUT_ORDER
  CHOCO_FIRST_FAIL
  CHOCO_DOM_OVER_WDEG
)
VALS=(MIN MAX)

cd "$ROOT_DIR"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"

run_one() {
  local var_heur="$1"
  local val_heur="$2"
  local out_file="$RUN_OUT_DIR/${var_heur}_${val_heur}.txt"
  local run_ts
  run_ts="$(date +%Y-%m-%dT%H:%M:%S)"

  local cmd_output
  local timeout_ms=$((TIMEOUT_SEC * 1000))
  if ! cmd_output="$(mvn -q -DskipTests exec:java \
      -Dexec.mainClass=cp26.mining.examples.PeakUtility \
      -Dexec.args="$DATASET_PATH $out_file $MINUTIL $MODE $timeout_ms $DEPTHK $var_heur $val_heur" 2>&1)"; then
    echo "[$run_ts] ERROR var=$var_heur val=$val_heur" | tee -a "$LOG_FILE"
    echo "$cmd_output" >> "$LOG_FILE"
    echo "$run_ts,$var_heur,$val_heur,$MODE,0,true,0,0,0,0" >> "$CSV_FILE"
    return
  fi

  echo "[$run_ts] DONE var=$var_heur val=$val_heur" | tee -a "$LOG_FILE"
  echo "$cmd_output" >> "$LOG_FILE"

  local result_line
  result_line="$(echo "$cmd_output" | awk -F'\t' '/^RESULT\talgorithm=PeakUtility/ {print; exit}')"
  local itemsets_found timeout_reached nodes fails decisions solver_time
  if [ -n "$result_line" ]; then
    itemsets_found="$(echo "$result_line" | tr '\t' '\n' | awk -F'=' '$1=="patterns"{print $2}')"
    timeout_reached="$(echo "$result_line" | tr '\t' '\n' | awk -F'=' '$1=="timeoutReached"{print $2}')"
    nodes="$(echo "$result_line" | tr '\t' '\n' | awk -F'=' '$1=="nodes"{print $2}')"
    fails="$(echo "$result_line" | tr '\t' '\n' | awk -F'=' '$1=="fails"{print $2}')"
    decisions="$(echo "$result_line" | tr '\t' '\n' | awk -F'=' '$1=="decisions"{print $2}')"
    solver_time="$(echo "$result_line" | tr '\t' '\n' | awk -F'=' '$1=="solverTimeSec"{print $2}')"
  else
    # Fallback parser for PeakUtility output format.
    itemsets_found="$(echo "$cmd_output" | awk -F':' '/Patterns Found/{gsub(/ /,"",$2); print $2; exit}')"
    nodes="$(echo "$cmd_output" | awk -F':' '/Nodes \/ Fails/{split($2,a,"/"); gsub(/ /,"",a[1]); print a[1]; exit}')"
    fails="$(echo "$cmd_output" | awk -F':' '/Nodes \/ Fails/{split($2,a,"/"); gsub(/ /,"",a[2]); print a[2]; exit}')"
    timeout_reached="$(echo "$cmd_output" | awk -F'[()]' '/Timeout/{if ($2 ~ /reached/) print "true"; else print "false"; exit}')"
    local exec_ms
    exec_ms="$(echo "$cmd_output" | awk -F':' '/Execution Time/{gsub(/ ms/,"",$2); gsub(/ /,"",$2); print $2; exit}')"
    if [[ "$exec_ms" =~ ^[0-9]+$ ]]; then
      solver_time="$(awk -v ms="$exec_ms" 'BEGIN{printf "%.6f", ms/1000.0}')"
    else
      solver_time="0"
    fi
    decisions="0"
  fi

  echo "$run_ts,$var_heur,$val_heur,$MODE,${itemsets_found:-0},${timeout_reached:-true},${nodes:-0},${fails:-0},${decisions:-0},${solver_time:-0}" >> "$CSV_FILE"
}

for var_heur in "${VARS[@]}"; do
  for val_heur in "${VALS[@]}"; do
    run_one "$var_heur" "$val_heur"
  done
done

echo "Benchmark finished."
echo "CSV: $CSV_FILE"
echo "LOG: $LOG_FILE"

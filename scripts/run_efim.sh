#!/usr/bin/env bash
set -euo pipefail

# Resolve project root so this works from any current directory, even if script is moved.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/SPMF/EfimRunner.java" ]; then
  ROOT_DIR="$SCRIPT_DIR"
elif [ -f "$SCRIPT_DIR/../SPMF/EfimRunner.java" ]; then
  ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
else
  echo "Error: cannot locate SPMF/EfimRunner.java from script directory '$SCRIPT_DIR'"
  exit 1
fi

# Usage:
#   ./run_efim.sh <dataset> <minUtil> [spmfJarPath] [timeoutMinutes]
#   TO=5 ./run_efim.sh <dataset> <minUtil> [spmfJarPath]
#
# Examples:
#   ./run_efim.sh spmf_foodmart 10000
#   ./run_efim.sh datasets/spmf_foodmart.txt 10000 SPMF/spmf.jar
#   ./run_efim.sh datasets/spmf_foodmart.txt 10000 SPMF/spmf.jar 5

if [ "$#" -lt 2 ] || [ "$#" -gt 4 ]; then
  echo "Usage: ./run_efim.sh <dataset> <minUtil> [spmfJarPath] [timeoutMinutes]"
  echo
  echo "<dataset> can be:"
  echo "  - dataset key in datasets/ (e.g. spmf_foodmart)"
  echo "  - explicit path to a .txt dataset"
  echo "<timeoutMinutes>: optional timeout in minutes (default: TO env var or 5)"
  echo
  echo "Output: datasets/patterns/<dataset>/HUI.txt"
  exit 1
fi

DATASET="$1"
MINUTIL="$2"
JAR_PATH="${3:-$ROOT_DIR/SPMF/spmf.jar}"
TIMEOUT_MINUTES="${4:-${TO:-5}}"

if [ ! -f "$JAR_PATH" ]; then
  echo "Error: spmf jar not found at '$JAR_PATH'"
  exit 1
fi
if ! [[ "$TIMEOUT_MINUTES" =~ ^[0-9]+$ ]] || [ "$TIMEOUT_MINUTES" -le 0 ]; then
  echo "Error: timeoutMinutes/TO must be a positive integer."
  exit 1
fi

# Resolve dataset to an absolute path when possible.
DATASET_ARG="$DATASET"
if [ -f "$DATASET" ]; then
  DATASET_ARG="$(cd "$(dirname "$DATASET")" && pwd)/$(basename "$DATASET")"
elif [ -f "$ROOT_DIR/datasets/$DATASET" ]; then
  DATASET_ARG="$ROOT_DIR/datasets/$DATASET"
elif [ -f "$ROOT_DIR/datasets/$DATASET.txt" ]; then
  DATASET_ARG="$ROOT_DIR/datasets/$DATASET.txt"
fi

javac "$ROOT_DIR/SPMF/EfimRunner.java"
(
  cd "$ROOT_DIR"
  java -cp "$ROOT_DIR/SPMF:$JAR_PATH" EfimRunner "$DATASET_ARG" "$MINUTIL" "$JAR_PATH" "$TIMEOUT_MINUTES"
)

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ "$#" -lt 2 ] || [ "$#" -gt 4 ]; then
  echo "Usage: $0 <datasetPathOrName> <minUtil> [spmfJarPath] [timeoutMinutes]"
  echo "Examples:"
  echo "  $0 data/test_dataset/tiny_manual_spmf.txt 10"
  echo "  $0 tiny_manual_spmf 10"
  echo "  $0 foodmart 10000 src/main/java/org/cp26/huim/efim/spmf.jar 15"
  exit 1
fi

DATASET="$1"
MINUTIL="$2"
JAR_PATH="${3:-$ROOT_DIR/src/main/java/org/cp26/huim/efim/spmf.jar}"
TIMEOUT_MIN="${4:-15}"

if [ ! -f "$JAR_PATH" ]; then
  echo "Error: spmf.jar not found at '$JAR_PATH'"
  exit 1
fi

cd "$ROOT_DIR"
mvn -q -DskipTests compile exec:java \
  -Dexec.mainClass="org.cp26.huim.efim.EfimRunner" \
  -Dexec.args="$DATASET $MINUTIL $JAR_PATH $TIMEOUT_MIN"

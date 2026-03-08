#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

MINUTIL="${1:-10}"
DEPTHK="${2:-3}"
PROJECTION="${3:-ARRAY}"
VARSEL="${4:-input}"
VALSEL="${5:-max}"
TIMEOUT_MS="${6:-60000}"

OUT_ROOT="$ROOT_DIR/results/compare"
mkdir -p "$OUT_ROOT"
SUMMARY="$OUT_ROOT/summary_minutil_${MINUTIL}.tsv"
COUNTS_MD="$OUT_ROOT/summary_counts_minutil_${MINUTIL}.md"

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

for ds in "$ROOT_DIR"/data/test_dataset/*_spmf.txt; do
  base="$(basename "$ds" .txt)"
  out_dir="$OUT_ROOT/$base"
  mkdir -p "$out_dir"

  efim_hui="$out_dir/efim_HUI.txt"
  efim_upi="$out_dir/efim_UPI.txt"
  peak_hui="$out_dir/peak_HUI.txt"
  peak_upi="$out_dir/peak_UPI.txt"

  echo "[RUN] dataset=$base minutil=$MINUTIL"

  # 1) EFIM HUI
  "$ROOT_DIR/scripts/run_efim.sh" "$ds" "$MINUTIL"
  cp "$ROOT_DIR/data/patterns/$base/HUI.txt" "$efim_hui"

  # 2) EFIM UPI (postprocessing)
  mvn -q -DskipTests compile exec:java \
    -Dexec.mainClass="org.cp26.huim.postprocessing.HUIToUPIPostprocessing" \
    -Dexec.args="$efim_hui $efim_upi" >/dev/null

  # 3) PeakUtility HUI
  mvn -q -DskipTests compile exec:java \
    -Dexec.mainClass="org.cp26.huim.runners.PeakUtilityRunner" \
    -Dexec.args="--file $ds --output $peak_hui --minutil $MINUTIL --mode HUI --depthk $DEPTHK --projection $PROJECTION --varsel $VARSEL --valsel $VALSEL --timeoutms $TIMEOUT_MS" >/dev/null

  # 4) PeakUtility UPI
  mvn -q -DskipTests compile exec:java \
    -Dexec.mainClass="org.cp26.huim.runners.PeakUtilityRunner" \
    -Dexec.args="--file $ds --output $peak_upi --minutil $MINUTIL --mode UPI --depthk $DEPTHK --projection $PROJECTION --varsel $VARSEL --valsel $VALSEL --timeoutms $TIMEOUT_MS" >/dev/null

  # 5) Compare itemsets (ignore utility token values)
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

echo "Done. Summary TSV: $SUMMARY"
echo "Done. Counts table: $COUNTS_MD"

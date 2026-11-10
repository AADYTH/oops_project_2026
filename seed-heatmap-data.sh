#!/usr/bin/env bash
# Seeds enough data through the real FOLIX API for the disease map heatmap to
# show a region. Based on the actual endpoints/payloads used in the backend's
# own tests (FolixLocationHttpTest, FolixAuthControllerTest).
#
# The heatmap only shows a region once it has (see FolixDiseaseMapAggregatorTest):
#   - >= 3 scan reports
#   - from >= 3 DISTINCT farmers
#   - in the same ~11km region cell
#   - within the last 30 days
# So this registers 3 farmers, gives each a location in the same cell, and
# has each submit one matching report.
#
# Usage:
#   chmod +x seed-heatmap-data.sh
#   ./seed-heatmap-data.sh
#
# Adjust API_BASE / ORIGIN below to match your setup first.

set -euo pipefail

API_BASE="http://localhost:8080"
# Must match whatever `cropscanner.cors.allowed-origin` is set to in your
# application.properties, or the PUT/POST calls below may be rejected by the
# CORS/Origin check. If you're serving the frontend from a different port
# (e.g. 5500 for Live Server), change this to match.
ORIGIN="http://localhost:5500"

PASSWORD="correct horse 42"

# Three points inside the SAME ~11km grid cell (see FolixRegionGridTest:
# anything in roughly 11.20-11.30 / 75.70-75.80 lands in the same cell as
# 11.2587,75.7804). Nudged slightly so they aren't identical.
declare -a FARMERS=(
  "Asha Nair|asha@example.com|11.2560|75.7790"
  "Bala Menon|bala@example.com|11.2600|75.7810"
  "Chandini Rao|chandini@example.com|11.2570|75.7830"
)

REPORT_JSON='{
  "crop": "Tomato", "plantPart": "leaf", "disease": "Early blight", "severity": "moderate",
  "confidence": "high", "symptoms": ["Brown concentric-ring spots"],
  "reasoning": "Concentric target-like rings are typical of early blight.",
  "recommendedActions": ["Remove affected leaves."], "needsHumanReview": false
}'

for entry in "${FARMERS[@]}"; do
  IFS='|' read -r name email lat lng <<< "$entry"

  echo "== $name ($email) =="

  echo "  registering..."
  curl -s -o /dev/null -w "    register: %{http_code}\n" \
    -X POST "$API_BASE/api/auth/register" \
    -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
    -d "{\"name\":\"$name\",\"email\":\"$email\",\"password\":\"$PASSWORD\"}" \
    || true

  echo "  logging in..."
  COOKIE_JAR=$(mktemp)
  curl -s -c "$COOKIE_JAR" -o /dev/null -w "    login: %{http_code}\n" \
    -X POST "$API_BASE/api/auth/login" \
    -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
    -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}"

  echo "  setting location..."
  curl -s -b "$COOKIE_JAR" -o /dev/null -w "    location: %{http_code}\n" \
    -X PUT "$API_BASE/api/farmer/location" \
    -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
    -d "{\"consent\":true,\"latitude\":$lat,\"longitude\":$lng}"

  echo "  submitting scan report..."
  curl -s -b "$COOKIE_JAR" -o /dev/null -w "    report: %{http_code}\n" \
    -X POST "$API_BASE/api/scan/reports" \
    -H "Content-Type: application/json" -H "Origin: $ORIGIN" \
    -d "$REPORT_JSON"

  rm -f "$COOKIE_JAR"
  echo
done

echo "Done. Checking the map endpoint..."
curl -s "$API_BASE/api/folix/disease-map" | python3 -m json.tool 2>/dev/null \
  || curl -s "$API_BASE/api/folix/disease-map"
echo
echo "If \"regions\" is still empty: most likely the CORS Origin check rejected"
echo "one of the calls above (check the HTTP codes printed for each step -"
echo "201/200 is good, anything else means that step didn't take), or the"
echo "backend uses a different port than 8080. Re-run with the codes shown."

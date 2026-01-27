#!/bin/bash

###############################################################################
# Edge Cases Test Suite for Materialized View Optimization
# Tests boundary conditions and error handling
###############################################################################

BASE_URL="http://localhost:8080/api/materialized-views"
CATALOG_URL="http://localhost:8080/api/catalog-ops/execute"
PASS=0
FAIL=0
TOTAL=0

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

print_result() {
  ((TOTAL++))
  if [ "$1" = "PASS" ]; then
    echo -e "${GREEN}✅ Test $TOTAL PASSED${NC}: $2"
    ((PASS++))
  else
    echo -e "${RED}❌ Test $TOTAL FAILED${NC}: $2"
    echo "   Expected: $3"
    echo "   Got: $4"
    ((FAIL++))
  fi
}

echo "========================================="
echo "   Edge Cases Test Suite"
echo "========================================="

###############################################################################
# Test 1: Empty Query List
###############################################################################

echo ""
echo "Test 1: Empty query list should return error"

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{"queries": [], "minOccurrences": 2}')

SUCCESS=$(echo "$RESULT" | jq -r '.success')
ERROR_MSG=$(echo "$RESULT" | jq -r '.errorMessage')

if [ "$SUCCESS" = "false" ] && [[ "$ERROR_MSG" == *"empty"* ]]; then
  print_result "PASS" "Empty query list returns error"
else
  print_result "FAIL" "Empty query list should return error" "success=false" "success=$SUCCESS"
fi

###############################################################################
# Test 2: Single Query (Below minOccurrences)
###############################################################################

echo ""
echo "Test 2: Single query should return error"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": ["SELECT country, COUNT(*) FROM db1.A GROUP BY country"],
    "minOccurrences": 2
  }')

SUCCESS=$(echo "$RESULT" | jq -r '.success')
ERROR_MSG=$(echo "$RESULT" | jq -r '.errorMessage')

if [ "$SUCCESS" = "false" ] && [[ "$ERROR_MSG" == *"2 queries"* ]]; then
  print_result "PASS" "Single query returns appropriate error"
else
  print_result "FAIL" "Single query should return error" "success=false" "success=$SUCCESS"
fi

###############################################################################
# Test 3: No Common Patterns (Different Aggregations)
###############################################################################

echo ""
echo "Test 3: No common patterns should create 0 MVs"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT country, COUNT(*) FROM db1.A GROUP BY country",
      "SELECT country, SUM(area_code) FROM db1.A GROUP BY country"
    ],
    "minOccurrences": 2
  }')

MV_COUNT=$(echo "$RESULT" | jq -r '.stats.materializedViewsCreated')
SUCCESS=$(echo "$RESULT" | jq -r '.success')

if [ "$SUCCESS" = "true" ] && [ "$MV_COUNT" = "0" ]; then
  print_result "PASS" "No common patterns returns 0 MVs"
else
  print_result "FAIL" "No common patterns should return 0 MVs" "mvs=0" "mvs=$MV_COUNT"
fi

###############################################################################
# Test 4: Rewrite Without MV in Registry
###############################################################################

echo ""
echo "Test 4: Rewrite without MV should return no match"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/rewrite" \
  -H "Content-Type: application/json" \
  -d '{"query": "SELECT country, COUNT(*) FROM db1.A GROUP BY country"}')

MATCHED=$(echo "$RESULT" | jq -r '.matched')

if [ "$MATCHED" = "false" ]; then
  print_result "PASS" "Rewrite without MV returns no match"
else
  print_result "FAIL" "Rewrite without MV should return no match" "false" "$MATCHED"
fi

###############################################################################
# Test 5: Invalid SQL Syntax
###############################################################################

echo ""
echo "Test 5: Invalid SQL should return error"

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELEKT country FROM db1.A",
      "SELEKT country FROM db1.A"
    ],
    "minOccurrences": 2
  }')

SUCCESS=$(echo "$RESULT" | jq -r '.success')

if [ "$SUCCESS" = "false" ]; then
  print_result "PASS" "Invalid SQL returns error"
else
  print_result "FAIL" "Invalid SQL should return error" "success=false" "success=$SUCCESS"
fi

###############################################################################
# Test 6: Non-existent Table
###############################################################################

echo ""
echo "Test 6: Non-existent table should return error"

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT country, COUNT(*) FROM db1.NonExistentTable GROUP BY country",
      "SELECT country, COUNT(*) FROM db1.NonExistentTable GROUP BY country"
    ],
    "minOccurrences": 2
  }')

SUCCESS=$(echo "$RESULT" | jq -r '.success')

if [ "$SUCCESS" = "false" ]; then
  print_result "PASS" "Non-existent table returns error"
else
  print_result "FAIL" "Non-existent table should return error" "success=false" "success=$SUCCESS"
fi

###############################################################################
# Test 7: Empty Registry Status
###############################################################################

echo ""
echo "Test 7: Empty registry status"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X GET "$BASE_URL/registry")
SUCCESS=$(echo "$RESULT" | jq -r '.success')
TOTAL_MVS=$(echo "$RESULT" | jq -r '.totalMVs')

if [ "$SUCCESS" = "true" ] && [ "$TOTAL_MVS" = "0" ]; then
  print_result "PASS" "Empty registry returns correct status"
else
  print_result "FAIL" "Empty registry status" "success=true, totalMVs=0" "success=$SUCCESS, totalMVs=$TOTAL_MVS"
fi

###############################################################################
# Test 8: Clear Empty Registry
###############################################################################

echo ""
echo "Test 8: Clear empty registry"

RESULT=$(curl -s -X DELETE "$BASE_URL/registry")
MVS_REMOVED=$(echo "$RESULT" | jq -r '.mvsRemoved')
SUCCESS=$(echo "$RESULT" | jq -r '.success')

if [ "$SUCCESS" = "true" ] && [ "$MVS_REMOVED" = "0" ]; then
  print_result "PASS" "Clear empty registry returns 0 removed"
else
  print_result "FAIL" "Clear empty registry" "success=true, mvsRemoved=0" "success=$SUCCESS, mvsRemoved=$MVS_REMOVED"
fi

###############################################################################
# Test 9: Large Query Set (Performance)
###############################################################################

echo ""
echo "Test 9: Large query set (10 queries)"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.area_code > 100 GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.area_code > 200 GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.area_code > 300 GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.area_code > 400 GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE B.area_code > 100 GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE B.area_code > 200 GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE B.area_code > 300 GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE B.area_code > 400 GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE B.area_code > 500 GROUP BY A.country"
    ],
    "minOccurrences": 2
  }')

SUCCESS=$(echo "$RESULT" | jq -r '.success')
MV_COUNT=$(echo "$RESULT" | jq -r '.stats.materializedViewsCreated')
ANALYSIS_TIME=$(echo "$RESULT" | jq -r '.stats.analysisTimeMs')

if [ "$SUCCESS" = "true" ] && [ "$MV_COUNT" = "1" ]; then
  print_result "PASS" "Large query set processed successfully (${ANALYSIS_TIME}ms)"
else
  print_result "FAIL" "Large query set" "success=true, mvs=1" "success=$SUCCESS, mvs=$MV_COUNT"
fi

###############################################################################
# Test 10: minOccurrences = 1 (Should create MV even with 1 occurrence)
###############################################################################

echo ""
echo "Test 10: minOccurrences=1 should accept single pattern"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country",
      "SELECT country, SUM(area_code) FROM db1.A GROUP BY country"
    ],
    "minOccurrences": 1
  }')

SUCCESS=$(echo "$RESULT" | jq -r '.success')
MV_COUNT=$(echo "$RESULT" | jq -r '.stats.materializedViewsCreated')

if [ "$SUCCESS" = "true" ] && [ "$MV_COUNT" = "2" ]; then
  print_result "PASS" "minOccurrences=1 creates MVs for unique patterns"
else
  print_result "FAIL" "minOccurrences=1" "success=true, mvs=2" "success=$SUCCESS, mvs=$MV_COUNT"
fi

###############################################################################
# Final Summary
###############################################################################

echo ""
echo "========================================="
echo "        EDGE CASES SUMMARY"
echo "========================================="
echo -e "Total Tests:  $TOTAL"
echo -e "${GREEN}Passed:       $PASS${NC}"
echo -e "${RED}Failed:       $FAIL${NC}"
echo "========================================="

if [ $FAIL -eq 0 ]; then
  echo -e "${GREEN}✅ ALL EDGE CASE TESTS PASSED!${NC}"
  exit 0
else
  echo -e "${RED}❌ SOME TESTS FAILED${NC}"
  exit 1
fi

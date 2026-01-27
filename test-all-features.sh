#!/bin/bash

###############################################################################
# Comprehensive Test Suite for Materialized View Optimization
# Tests all implemented features after refactoring
###############################################################################

BASE_URL="http://localhost:8080/api/materialized-views"
CATALOG_URL="http://localhost:8080/api/catalog-ops/execute"
PASS=0
FAIL=0
TOTAL=0

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

print_test() {
  echo ""
  echo "========================================="
  echo "Test $1: $2"
  echo "========================================="
}

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

###############################################################################
# Setup: Create test databases and tables
###############################################################################

echo "========================================="
echo "Setting up test database and tables..."
echo "========================================="

# Create database and tables
curl -s -X POST "$CATALOG_URL" -H "Content-Type: text/plain" \
  -d "CREATE DATABASE db1" > /dev/null 2>&1

curl -s -X POST "$CATALOG_URL" -H "Content-Type: text/plain" \
  -d "CREATE TABLE db1.A (id int, country varchar(20), area_code int)" > /dev/null 2>&1

curl -s -X POST "$CATALOG_URL" -H "Content-Type: text/plain" \
  -d "CREATE TABLE db1.B (id int, country varchar(20), area_code int)" > /dev/null 2>&1

curl -s -X POST "$CATALOG_URL" -H "Content-Type: text/plain" \
  -d "CREATE TABLE db1.C (id int, country varchar(20), area_code int)" > /dev/null 2>&1

curl -s -X POST "$CATALOG_URL" -H "Content-Type: text/plain" \
  -d "CREATE DATABASE db2" > /dev/null 2>&1

curl -s -X POST "$CATALOG_URL" -H "Content-Type: text/plain" \
  -d "CREATE TABLE db2.D (id int, country varchar(20), area_code int)" > /dev/null 2>&1

echo "✅ Database and tables created"

###############################################################################
# Test 1: Basic JOIN Aggregation (Filter-Agnostic Matching)
###############################################################################

print_test "1" "JOIN Aggregation - Filter-Agnostic Matching"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.area_code > 100 GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE B.area_code < 500 GROUP BY A.country"
    ],
    "minOccurrences": 2
  }')

MV_COUNT=$(echo "$RESULT" | jq -r '.stats.materializedViewsCreated')
if [ "$MV_COUNT" = "1" ]; then
  print_result "PASS" "Created 1 MV for 3 JOIN queries with different filters"
else
  print_result "FAIL" "Created 1 MV for 3 JOIN queries with different filters" "1" "$MV_COUNT"
fi

###############################################################################
# Test 2: Query Rewrite with Filter-Agnostic Match
###############################################################################

print_test "2" "Query Rewrite - Use MV for new filter"

RESULT=$(curl -s -X POST "$BASE_URL/rewrite" \
  -H "Content-Type: application/json" \
  -d '{"query": "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.area_code > 200 GROUP BY A.country"}')

MATCHED=$(echo "$RESULT" | jq -r '.matched')
if [ "$MATCHED" = "true" ]; then
  print_result "PASS" "Query with new filter matched existing MV"
else
  print_result "FAIL" "Query with new filter matched existing MV" "true" "$MATCHED"
fi

###############################################################################
# Test 3: Column Aliases (Semantic Matching)
###############################################################################

print_test "3" "Column Aliases - Semantic Matching"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*) as cnt FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country",
      "SELECT A.country, COUNT(*) as total FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country"
    ],
    "minOccurrences": 2
  }')

MV_COUNT=$(echo "$RESULT" | jq -r '.stats.materializedViewsCreated')
if [ "$MV_COUNT" = "1" ]; then
  print_result "PASS" "Different aliases share same MV (semantic matching)"
else
  print_result "FAIL" "Different aliases share same MV" "1" "$MV_COUNT"
fi

###############################################################################
# Test 4: ORDER BY Clause
###############################################################################

print_test "4" "ORDER BY Support"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country ORDER BY country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country ORDER BY country DESC",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country"
    ],
    "minOccurrences": 2
  }')

MV_COUNT=$(echo "$RESULT" | jq -r '.stats.materializedViewsCreated')
if [ "$MV_COUNT" = "1" ]; then
  print_result "PASS" "ORDER BY clauses ignored in pattern matching"
else
  print_result "FAIL" "ORDER BY clauses ignored in pattern matching" "1" "$MV_COUNT"
fi

###############################################################################
# Test 5: LIMIT Clause
###############################################################################

print_test "5" "LIMIT Support"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country LIMIT 10",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country LIMIT 100",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country"
    ],
    "minOccurrences": 2
  }')

MV_COUNT=$(echo "$RESULT" | jq -r '.stats.materializedViewsCreated')
if [ "$MV_COUNT" = "1" ]; then
  print_result "PASS" "LIMIT clauses ignored in pattern matching"
else
  print_result "FAIL" "LIMIT clauses ignored in pattern matching" "1" "$MV_COUNT"
fi

###############################################################################
# Test 6: HAVING Clause
###############################################################################

print_test "6" "HAVING Support"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country HAVING COUNT(*) > 10",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country HAVING COUNT(*) > 100",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country"
    ],
    "minOccurrences": 2
  }')

MV_COUNT=$(echo "$RESULT" | jq -r '.stats.materializedViewsCreated')
if [ "$MV_COUNT" = "1" ]; then
  print_result "PASS" "HAVING clauses ignored in pattern matching"
else
  print_result "FAIL" "HAVING clauses ignored in pattern matching" "1" "$MV_COUNT"
fi

###############################################################################
# Test 7: Cross-Database Joins
###############################################################################

print_test "7" "Cross-Database Joins"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db2.D ON A.id = D.id GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db2.D ON A.id = D.id WHERE A.area_code > 100 GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db2.D ON A.id = D.id WHERE D.area_code < 500 GROUP BY A.country"
    ],
    "minOccurrences": 2
  }')

MV_COUNT=$(echo "$RESULT" | jq -r '.stats.materializedViewsCreated')
if [ "$MV_COUNT" = "1" ]; then
  print_result "PASS" "Cross-database joins create shared MV"
else
  print_result "FAIL" "Cross-database joins create shared MV" "1" "$MV_COUNT"
fi

###############################################################################
# Test 8: Multiple Aggregation Functions
###############################################################################

print_test "8" "Multiple Aggregation Functions"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*), SUM(A.area_code) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country",
      "SELECT A.country, COUNT(*), SUM(A.area_code) FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.area_code > 100 GROUP BY A.country",
      "SELECT A.country, COUNT(*), SUM(A.area_code) FROM db1.A JOIN db1.B ON A.id = B.id WHERE B.area_code < 500 GROUP BY A.country"
    ],
    "minOccurrences": 2
  }')

MV_COUNT=$(echo "$RESULT" | jq -r '.stats.materializedViewsCreated')
if [ "$MV_COUNT" = "1" ]; then
  print_result "PASS" "Multiple aggregation functions share MV"
else
  print_result "FAIL" "Multiple aggregation functions share MV" "1" "$MV_COUNT"
fi

###############################################################################
# Test 9: Three-Way Joins
###############################################################################

print_test "9" "Three-Way Joins"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id WHERE A.area_code > 100 GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id WHERE C.area_code < 500 GROUP BY A.country"
    ],
    "minOccurrences": 2
  }')

MV_COUNT=$(echo "$RESULT" | jq -r '.stats.materializedViewsCreated')
if [ "$MV_COUNT" = "1" ]; then
  print_result "PASS" "Three-way joins create shared MV"
else
  print_result "FAIL" "Three-way joins create shared MV" "1" "$MV_COUNT"
fi

###############################################################################
# Test 10: Registry Operations
###############################################################################

print_test "10" "Registry Status API"

RESULT=$(curl -s -X GET "$BASE_URL/registry")
SUCCESS=$(echo "$RESULT" | jq -r '.success')
TOTAL_MVS=$(echo "$RESULT" | jq -r '.totalMVs')

if [ "$SUCCESS" = "true" ] && [ "$TOTAL_MVS" = "1" ]; then
  print_result "PASS" "Registry status API returns correct count"
else
  print_result "FAIL" "Registry status API returns correct count" "success=true, totalMVs=1" "success=$SUCCESS, totalMVs=$TOTAL_MVS"
fi

###############################################################################
# Test 11: Clear Registry
###############################################################################

print_test "11" "Clear Registry API"

RESULT=$(curl -s -X DELETE "$BASE_URL/registry")
MVS_REMOVED=$(echo "$RESULT" | jq -r '.mvsRemoved')

if [ "$MVS_REMOVED" = "1" ]; then
  print_result "PASS" "Clear registry removed 1 MV"
else
  print_result "FAIL" "Clear registry removed 1 MV" "1" "$MVS_REMOVED"
fi

# Verify registry is empty
RESULT=$(curl -s -X GET "$BASE_URL/registry")
TOTAL_MVS=$(echo "$RESULT" | jq -r '.totalMVs')

if [ "$TOTAL_MVS" = "0" ]; then
  print_result "PASS" "Registry is empty after clear"
else
  print_result "FAIL" "Registry is empty after clear" "0" "$TOTAL_MVS"
fi

###############################################################################
# Test 12: Combined Features (ORDER BY + LIMIT + HAVING + Aliases)
###############################################################################

print_test "12" "Combined Features"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

RESULT=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*) as cnt FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country HAVING COUNT(*) > 10 ORDER BY country LIMIT 100",
      "SELECT A.country, COUNT(*) as total FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country HAVING COUNT(*) > 50 ORDER BY country DESC LIMIT 50",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country"
    ],
    "minOccurrences": 2
  }')

MV_COUNT=$(echo "$RESULT" | jq -r '.stats.materializedViewsCreated')
if [ "$MV_COUNT" = "1" ]; then
  print_result "PASS" "Combined features (ORDER BY + LIMIT + HAVING + Aliases) share MV"
else
  print_result "FAIL" "Combined features share MV" "1" "$MV_COUNT"
fi

###############################################################################
# Test 13: Rewrite with Combined Features
###############################################################################

print_test "13" "Rewrite with Combined Features"

RESULT=$(curl -s -X POST "$BASE_URL/rewrite" \
  -H "Content-Type: application/json" \
  -d '{"query": "SELECT A.country, COUNT(*) as my_count FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country HAVING COUNT(*) > 1000 ORDER BY country LIMIT 10"}')

MATCHED=$(echo "$RESULT" | jq -r '.matched')
REPLACEMENT_COUNT=$(echo "$RESULT" | jq -r '.replacementCount')

if [ "$MATCHED" = "true" ] && [ "$REPLACEMENT_COUNT" -gt 0 ]; then
  print_result "PASS" "Query with combined features matched and was rewritten"
else
  print_result "FAIL" "Query with combined features matched" "matched=true, replacementCount>0" "matched=$MATCHED, replacementCount=$REPLACEMENT_COUNT"
fi

###############################################################################
# Test 14: No Match for Different Aggregation
###############################################################################

print_test "14" "No Match for Different Aggregation Function"

curl -s -X DELETE "$BASE_URL/registry" > /dev/null

# Create MV with COUNT(*)
curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.area_code > 100 GROUP BY A.country"
    ],
    "minOccurrences": 2
  }' > /dev/null

# Try to rewrite with SUM (should not match)
RESULT=$(curl -s -X POST "$BASE_URL/rewrite" \
  -H "Content-Type: application/json" \
  -d '{"query": "SELECT A.country, SUM(A.area_code) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country"}')

MATCHED=$(echo "$RESULT" | jq -r '.matched')

if [ "$MATCHED" = "false" ]; then
  print_result "PASS" "Different aggregation function did not match"
else
  print_result "FAIL" "Different aggregation function should not match" "false" "$MATCHED"
fi

###############################################################################
# Test 15: No Match for Different GROUP BY
###############################################################################

print_test "15" "No Match for Different GROUP BY Columns"

# Try to rewrite with different GROUP BY (should not match)
RESULT=$(curl -s -X POST "$BASE_URL/rewrite" \
  -H "Content-Type: application/json" \
  -d '{"query": "SELECT A.country, A.area_code, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country, A.area_code"}')

MATCHED=$(echo "$RESULT" | jq -r '.matched')

if [ "$MATCHED" = "false" ]; then
  print_result "PASS" "Different GROUP BY columns did not match"
else
  print_result "FAIL" "Different GROUP BY should not match" "false" "$MATCHED"
fi

###############################################################################
# Final Summary
###############################################################################

echo ""
echo "========================================="
echo "           TEST SUMMARY"
echo "========================================="
echo -e "Total Tests:  $TOTAL"
echo -e "${GREEN}Passed:       $PASS${NC}"
echo -e "${RED}Failed:       $FAIL${NC}"
echo "========================================="

if [ $FAIL -eq 0 ]; then
  echo -e "${GREEN}✅ ALL TESTS PASSED!${NC}"
  exit 0
else
  echo -e "${RED}❌ SOME TESTS FAILED${NC}"
  exit 1
fi

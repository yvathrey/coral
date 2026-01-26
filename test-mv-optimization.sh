#!/bin/bash

# Materialized View Optimization - Comprehensive Test Suite
# This script tests the MV optimizer with various join patterns

BASE_URL="http://localhost:8080/api/materialized-views/optimize"

# Color codes for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo "=========================================="
echo "Materialized View Optimizer - Test Suite"
echo "=========================================="
echo ""

# Function to run a test
run_test() {
    local test_num=$1
    local test_name=$2
    local expected_mv_count=$3
    local expected_replacements=$4
    local json_data=$5

    echo -e "${BLUE}TEST $test_num: $test_name${NC}"
    echo "Expected MVs: $expected_mv_count, Expected Replacements: $expected_replacements"
    echo ""

    response=$(curl -s -X POST "$BASE_URL" \
        -H "Content-Type: application/json" \
        -d "$json_data")

    # Extract stats
    mv_count=$(echo "$response" | grep -o '"materializedViewsCreated":[0-9]*' | grep -o '[0-9]*')
    replacements=$(echo "$response" | grep -o '"totalReplacements":[0-9]*' | grep -o '[0-9]*')

    echo "Response:"
    echo "$response" | jq '.' 2>/dev/null || echo "$response"
    echo ""

    # Validate results
    if [ "$mv_count" = "$expected_mv_count" ] && [ "$replacements" = "$expected_replacements" ]; then
        echo -e "${GREEN}✓ PASS${NC}"
    else
        echo -e "${RED}✗ FAIL - Expected MVs: $expected_mv_count, Got: $mv_count | Expected Replacements: $expected_replacements, Got: $replacements${NC}"
    fi

    echo ""
    echo "=========================================="
    echo ""
    sleep 1
}

# ===========================================
# TEST SUITE
# ===========================================

# -------------------------------------------
# Category 1: Basic 2-Table Joins
# -------------------------------------------

echo -e "${YELLOW}=== Category 1: Basic 2-Table Joins ===${NC}"
echo ""

# Test 1: Simple INNER JOIN (baseline test)
run_test "1.1" "Simple INNER JOIN - 2 tables" 1 3 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.country = '\''US'\''",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id WHERE B.area_code > 100"
  ]
}'

# Test 2: Different column join condition
run_test "1.2" "Join on different column" 1 2 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.country = B.country",
    "SELECT * FROM db1.A JOIN db1.B ON A.country = B.country WHERE A.id > 10",
    "SELECT * FROM db1.C JOIN db1.D ON C.id = D.id"
  ]
}'

# Test 3: No common pattern
run_test "1.3" "No common pattern (negative test)" 0 0 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id",
    "SELECT * FROM db1.C JOIN db1.D ON C.id = D.id",
    "SELECT * FROM db1.A JOIN db1.E ON A.id = E.id"
  ]
}'

# -------------------------------------------
# Category 2: Multi-Table Joins (3+ tables)
# -------------------------------------------

echo -e "${YELLOW}=== Category 2: Multi-Table Joins ===${NC}"
echo ""

# Test 4: 3-table join with common 2-table pattern
run_test "2.1" "3-table join - common A JOIN B" 1 3 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.D ON B.id = D.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.E ON B.id = E.id"
  ]
}'

# Test 5: 4-table join with common 3-table pattern
run_test "2.2" "4-table join - common A JOIN B JOIN C" 1 2 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id JOIN db1.D ON C.id = D.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id JOIN db1.E ON C.id = E.id"
  ]
}'

# Test 6: Complex 5-table join
run_test "2.3" "5-table join" 1 2 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id JOIN db1.D ON C.id = D.id JOIN db1.E ON D.id = E.id WHERE A.country = '\''US'\''",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id JOIN db1.D ON C.id = D.id JOIN db1.E ON D.id = E.id WHERE A.country = '\''UK'\''"
  ]
}'

# -------------------------------------------
# Category 3: Different Join Types
# -------------------------------------------

echo -e "${YELLOW}=== Category 3: Different Join Types ===${NC}"
echo ""

# Test 7: LEFT JOIN
run_test "3.1" "LEFT JOIN pattern" 1 3 '{
  "queries": [
    "SELECT * FROM db1.A LEFT JOIN db1.B ON A.id = B.id",
    "SELECT * FROM db1.A LEFT JOIN db1.B ON A.id = B.id WHERE A.country IS NOT NULL",
    "SELECT * FROM db1.A LEFT JOIN db1.B ON A.id = B.id WHERE B.id IS NULL"
  ]
}'

# Test 8: RIGHT JOIN
run_test "3.2" "RIGHT JOIN pattern" 1 2 '{
  "queries": [
    "SELECT * FROM db1.A RIGHT JOIN db1.B ON A.id = B.id",
    "SELECT * FROM db1.A RIGHT JOIN db1.B ON A.id = B.id WHERE B.country = '\''CA'\''"
  ]
}'

# Test 9: FULL OUTER JOIN
run_test "3.3" "FULL OUTER JOIN pattern" 1 2 '{
  "queries": [
    "SELECT * FROM db1.A FULL OUTER JOIN db1.B ON A.id = B.id",
    "SELECT * FROM db1.A FULL OUTER JOIN db1.B ON A.id = B.id WHERE A.id IS NOT NULL OR B.id IS NOT NULL"
  ]
}'

# Test 10: Mixed join types (should detect separately)
run_test "3.4" "Mixed join types" 0 0 '{
  "queries": [
    "SELECT * FROM db1.A INNER JOIN db1.B ON A.id = B.id",
    "SELECT * FROM db1.A LEFT JOIN db1.B ON A.id = B.id",
    "SELECT * FROM db1.A RIGHT JOIN db1.B ON A.id = B.id"
  ]
}'

# -------------------------------------------
# Category 4: Complex Join Conditions
# -------------------------------------------

echo -e "${YELLOW}=== Category 4: Complex Join Conditions ===${NC}"
echo ""

# Test 11: Multiple join conditions (AND)
run_test "4.1" "Multiple AND conditions" 1 2 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id AND A.country = B.country",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id AND A.country = B.country WHERE A.area_code > 0"
  ]
}'

# Test 12: Complex expression in join
run_test "4.2" "Complex join expressions" 1 2 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id AND A.area_code = B.area_code",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id AND A.area_code = B.area_code WHERE A.code IS NOT NULL"
  ]
}'

# -------------------------------------------
# Category 5: Nested and Complex Patterns
# -------------------------------------------

echo -e "${YELLOW}=== Category 5: Nested and Complex Patterns ===${NC}"
echo ""

# Test 13: Nested joins - same pattern at different levels
run_test "5.1" "Nested join pattern" 1 3 '{
  "queries": [
    "SELECT * FROM (SELECT * FROM db1.A JOIN db1.B ON A.id = B.id) ab JOIN db1.C ON ab.id = C.id",
    "SELECT * FROM (SELECT * FROM db1.A JOIN db1.B ON A.id = B.id) ab JOIN db1.D ON ab.id = D.id",
    "SELECT * FROM (SELECT * FROM db1.A JOIN db1.B ON A.id = B.id) ab JOIN db1.E ON ab.id = E.id"
  ]
}'

# Test 14: Star schema pattern (fact table + multiple dimensions)
run_test "5.2" "Star schema - fact + dimensions" 1 3 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON A.id = C.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.D ON A.id = D.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.E ON A.id = E.id"
  ]
}'

# -------------------------------------------
# Category 6: Edge Cases
# -------------------------------------------

echo -e "${YELLOW}=== Category 6: Edge Cases ===${NC}"
echo ""

# Test 15: Same query multiple times
run_test "6.1" "Identical queries" 1 3 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id"
  ]
}'

# Test 16: Only 2 queries (minimum for pattern detection)
run_test "6.2" "Minimum queries (2)" 1 2 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id"
  ]
}'

# Test 17: Very long query with many joins
run_test "6.3" "Long query chain" 1 2 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id JOIN db1.D ON C.id = D.id JOIN db1.E ON D.id = E.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id JOIN db1.D ON C.id = D.id JOIN db1.E ON D.id = E.id WHERE A.country = '\''MX'\''"
  ]
}'

# -------------------------------------------
# Category 7: Real-World Scenarios
# -------------------------------------------

echo -e "${YELLOW}=== Category 7: Real-World Scenarios ===${NC}"
echo ""

# Test 19: Analytics dashboard queries
run_test "7.1" "Analytics dashboard pattern" 1 4 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.datepartition = '\''2024-01-01'\''",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.datepartition = '\''2024-01-02'\''",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.datepartition = '\''2024-01-03'\''",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.datepartition = '\''2024-01-04'\''"
  ]
}'

# Test 20: Report queries with different filters
run_test "7.2" "Report queries - common base" 1 5 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.country = '\''US'\''",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.country = '\''CA'\''",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.country = '\''UK'\''",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.country = '\''FR'\''",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.country = '\''DE'\''"
  ]
}'

# Test 21: ETL pipeline pattern
run_test "7.3" "ETL pipeline - staging joins" 1 3 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id WHERE A.datepartition = '\''2024-01-15'\''",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.D ON B.id = D.id WHERE A.datepartition = '\''2024-01-15'\''",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.E ON B.id = E.id WHERE A.datepartition = '\''2024-01-15'\''"
  ]
}'

# -------------------------------------------
# Category 8: Multiple Common Patterns
# -------------------------------------------

echo -e "${YELLOW}=== Category 8: Multiple Common Patterns ===${NC}"
echo ""

# Test 22: Two different common patterns in same query set
run_test "8.1" "Two different patterns" 2 4 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.id > 10",
    "SELECT * FROM db1.C JOIN db1.D ON C.id = D.id",
    "SELECT * FROM db1.C JOIN db1.D ON C.id = D.id WHERE C.country = '\''US'\''"
  ]
}'

# Test 23: Hierarchical patterns (pattern within pattern)
run_test "8.2" "Hierarchical patterns" 1 3 '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.D ON B.id = D.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.E ON B.id = E.id"
  ]
}'

echo ""
echo "=========================================="
echo "Test Suite Complete!"
echo "=========================================="

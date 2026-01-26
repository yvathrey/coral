#!/bin/bash

# Test script for Materialized View Optimization - Aggregation Support
# Tests both simple aggregations and aggregations on joins

BASE_URL="http://localhost:8080/api/materialized-views/optimize"
HEADER="Content-Type: application/json"

echo "======================================================================"
echo "MATERIALIZED VIEW AGGREGATION OPTIMIZATION TEST SUITE"
echo "======================================================================"
echo ""
echo "This tests the new aggregation support with both STRING_BASED and HASH_BASED filtering"
echo ""

# Helper function to run test
run_test() {
    local test_name="$1"
    local test_data="$2"
    local expected_mvs="$3"
    local strategy="${4:-HASH_BASED}"  # Default to HASH_BASED

    echo "----------------------------------------------------------------------"
    echo "TEST: $test_name"
    echo "Strategy: $strategy"
    echo "Expected MVs: $expected_mvs"
    echo "----------------------------------------------------------------------"

    # Run the test
    response=$(curl -s -X POST "$BASE_URL" \
        -H "$HEADER" \
        -d "$test_data")

    # Extract stats
    success=$(echo "$response" | jq -r '.success')
    mvs_created=$(echo "$response" | jq -r '.stats.materializedViewsCreated')
    total_replacements=$(echo "$response" | jq -r '.stats.totalReplacements')

    echo ""
    echo "RESULT:"
    echo "  Success: $success"
    echo "  MVs Created: $mvs_created"
    echo "  Total Replacements: $total_replacements"

    # Check if test passed
    if [ "$mvs_created" == "$expected_mvs" ]; then
        echo "  ✅ PASSED - Created expected number of MVs"
    else
        echo "  ❌ FAILED - Expected $expected_mvs MVs, got $mvs_created"
        echo ""
        echo "Full response:"
        echo "$response" | jq '.'
    fi

    echo ""
}

# =============================================================================
# CATEGORY 1: Simple Aggregations (GROUP BY on single table)
# =============================================================================

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "CATEGORY 1: SIMPLE AGGREGATIONS (GROUP BY on single table)"
echo "═══════════════════════════════════════════════════════════════════════"
echo ""

# TEST 1.1: Same aggregation with different filters - should create 1 MV (most general)
run_test "1.1: Same GROUP BY, different filters - keep most general" '{
  "queries": [
    "SELECT country, COUNT(*) as cnt FROM db1.A GROUP BY country",
    "SELECT country, COUNT(*) as cnt FROM db1.A WHERE area_code > 100 GROUP BY country",
    "SELECT country, COUNT(*) as cnt FROM db1.A WHERE area_code > 100 AND id < 1000 GROUP BY country"
  ],
  "minOccurrences": 2,
  "filterStrategy": "HASH_BASED"
}' 1

# TEST 1.2: Different GROUP BY columns - should create separate MVs
run_test "1.2: Different GROUP BY columns - keep all" '{
  "queries": [
    "SELECT country, COUNT(*) as cnt FROM db1.A GROUP BY country",
    "SELECT area_code, COUNT(*) as cnt FROM db1.A GROUP BY area_code",
    "SELECT country, COUNT(*) as cnt FROM db1.A GROUP BY country"
  ],
  "minOccurrences": 2,
  "filterStrategy": "HASH_BASED"
}' 1

# TEST 1.3: Different aggregate functions - should create separate MVs
run_test "1.3: Different aggregate functions - keep all" '{
  "queries": [
    "SELECT country, COUNT(*) as cnt FROM db1.A GROUP BY country",
    "SELECT country, SUM(id) as total FROM db1.A GROUP BY country",
    "SELECT country, COUNT(*) as cnt FROM db1.A GROUP BY country"
  ],
  "minOccurrences": 2,
  "filterStrategy": "HASH_BASED"
}' 1

# TEST 1.4: Same aggregation with no filter vs with filter - keep no filter version
run_test "1.4: No filter vs with filter - keep no filter (most general)" '{
  "queries": [
    "SELECT country, COUNT(*) as cnt FROM db1.A WHERE area_code > 50 GROUP BY country",
    "SELECT country, COUNT(*) as cnt FROM db1.A GROUP BY country"
  ],
  "minOccurrences": 2,
  "filterStrategy": "HASH_BASED"
}' 1

# =============================================================================
# CATEGORY 2: Aggregations on Joins
# =============================================================================

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "CATEGORY 2: AGGREGATIONS ON JOINS"
echo "═══════════════════════════════════════════════════════════════════════"
echo ""

# TEST 2.1: Same join + aggregation with different filters
run_test "2.1: Join + GROUP BY, different filters - keep most general" '{
  "queries": [
    "SELECT A.country, COUNT(*) as cnt FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country",
    "SELECT A.country, COUNT(*) as cnt FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.area_code > 100 GROUP BY A.country",
    "SELECT A.country, COUNT(*) as cnt FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.area_code > 100 AND B.id > 50 GROUP BY A.country"
  ],
  "minOccurrences": 2,
  "filterStrategy": "HASH_BASED"
}' 1

# TEST 2.2: Different joins with same aggregation
run_test "2.2: Different joins, same GROUP BY - keep all" '{
  "queries": [
    "SELECT A.country, COUNT(*) as cnt FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country",
    "SELECT A.country, COUNT(*) as cnt FROM db1.A JOIN db1.C ON A.id = C.id GROUP BY A.country",
    "SELECT A.country, COUNT(*) as cnt FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country"
  ],
  "minOccurrences": 2,
  "filterStrategy": "HASH_BASED"
}' 1

# TEST 2.3: Multi-table join with aggregation and filters
run_test "2.3: 3-table join + GROUP BY, different filters - keep most general" '{
  "queries": [
    "SELECT A.country, COUNT(*) as cnt FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id GROUP BY A.country",
    "SELECT A.country, COUNT(*) as cnt FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id WHERE A.area_code > 50 GROUP BY A.country"
  ],
  "minOccurrences": 2,
  "filterStrategy": "HASH_BASED"
}' 1

# =============================================================================
# CATEGORY 3: Mixed Patterns (Joins + Aggregations)
# =============================================================================

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "CATEGORY 3: MIXED PATTERNS (both joins and aggregations)"
echo "═══════════════════════════════════════════════════════════════════════"
echo ""

# TEST 3.1: Mix of pure joins and aggregations
run_test "3.1: Pure joins + aggregations - separate handling" '{
  "queries": [
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id",
    "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id",
    "SELECT A.country, COUNT(*) as cnt FROM db1.A GROUP BY A.country",
    "SELECT A.country, COUNT(*) as cnt FROM db1.A WHERE area_code > 100 GROUP BY A.country"
  ],
  "minOccurrences": 2,
  "filterStrategy": "HASH_BASED"
}' 2

# =============================================================================
# CATEGORY 4: Comparison with STRING_BASED
# =============================================================================

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "CATEGORY 4: STRING_BASED vs HASH_BASED COMPARISON"
echo "═══════════════════════════════════════════════════════════════════════"
echo ""

# TEST 4.1: Same aggregation test with STRING_BASED
run_test "4.1: Simple aggregation (STRING_BASED)" '{
  "queries": [
    "SELECT country, COUNT(*) as cnt FROM db1.A GROUP BY country",
    "SELECT country, COUNT(*) as cnt FROM db1.A WHERE area_code > 100 GROUP BY country"
  ],
  "minOccurrences": 2,
  "filterStrategy": "STRING_BASED"
}' 1 "STRING_BASED"

# TEST 4.2: Same aggregation test with HASH_BASED
run_test "4.2: Simple aggregation (HASH_BASED)" '{
  "queries": [
    "SELECT country, COUNT(*) as cnt FROM db1.A GROUP BY country",
    "SELECT country, COUNT(*) as cnt FROM db1.A WHERE area_code > 100 GROUP BY country"
  ],
  "minOccurrences": 2,
  "filterStrategy": "HASH_BASED"
}' 1 "HASH_BASED"

# =============================================================================
# CATEGORY 5: Edge Cases
# =============================================================================

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "CATEGORY 5: EDGE CASES"
echo "═══════════════════════════════════════════════════════════════════════"
echo ""

# TEST 5.1: Aggregation with HAVING clause
run_test "5.1: Aggregation with HAVING clause" '{
  "queries": [
    "SELECT country, COUNT(*) as cnt FROM db1.A GROUP BY country HAVING COUNT(*) > 10",
    "SELECT country, COUNT(*) as cnt FROM db1.A GROUP BY country"
  ],
  "minOccurrences": 2,
  "filterStrategy": "HASH_BASED"
}' 1

# TEST 5.2: Multiple GROUP BY columns
run_test "5.2: Multiple GROUP BY columns, different filters" '{
  "queries": [
    "SELECT country, area_code, COUNT(*) as cnt FROM db1.A GROUP BY country, area_code",
    "SELECT country, area_code, COUNT(*) as cnt FROM db1.A WHERE id > 100 GROUP BY country, area_code"
  ],
  "minOccurrences": 2,
  "filterStrategy": "HASH_BASED"
}' 1

echo ""
echo "======================================================================"
echo "TEST SUITE COMPLETE"
echo "======================================================================"
echo ""
echo "Summary:"
echo "  - Category 1: Simple aggregations (GROUP BY on single table)"
echo "  - Category 2: Aggregations on joins"
echo "  - Category 3: Mixed patterns (joins + aggregations)"
echo "  - Category 4: STRING_BASED vs HASH_BASED comparison"
echo "  - Category 5: Edge cases (HAVING, multiple GROUP BY)"
echo ""
echo "Next steps:"
echo "  1. Review any failed tests above"
echo "  2. Check console logs for detailed debugging output"
echo "  3. Verify that join-based functionality still works: ./test-mv-optimization.sh"
echo ""

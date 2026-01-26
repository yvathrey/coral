#!/bin/bash
# Quick validation test for MV optimizer

echo "Running quick validation tests..."
echo ""

# Test 1: Basic functionality
echo "Test 1: Basic 2-table join (should create 1 MV, 2 replacements)"
curl -s -X POST http://localhost:8080/api/optimize-materialized-view \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id",
      "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id"
    ]
  }' | jq '.stats'
echo ""

# Test 2: 3-table join
echo "Test 2: 3-table join pattern (should create 1 MV, 3 replacements)"
curl -s -X POST http://localhost:8080/api/optimize-materialized-view \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.C ON B.id = C.id",
      "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.D ON B.id = D.id",
      "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id JOIN db1.E ON B.id = E.id"
    ]
  }' | jq '.stats'
echo ""

# Test 3: No common pattern
echo "Test 3: No common pattern (should create 0 MVs, 0 replacements)"
curl -s -X POST http://localhost:8080/api/optimize-materialized-view \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id",
      "SELECT * FROM db1.C JOIN db1.D ON C.id = D.id"
    ]
  }' | jq '.stats'
echo ""

# Test 4: Multiple patterns
echo "Test 4: Multiple patterns (should create 2 MVs, 4 replacements)"
curl -s -X POST http://localhost:8080/api/optimize-materialized-view \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id",
      "SELECT * FROM db1.A JOIN db1.B ON A.id = B.id",
      "SELECT * FROM db1.C JOIN db1.D ON C.id = D.id",
      "SELECT * FROM db1.C JOIN db1.D ON C.id = D.id"
    ]
  }' | jq '.stats'
echo ""

echo "Quick tests complete!"

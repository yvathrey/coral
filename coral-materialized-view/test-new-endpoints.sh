#!/bin/bash

# Quick Test Script for New Two-Stage Endpoints

BASE_URL="http://localhost:8080/api/materialized-views"

echo "Testing New Two-Stage API Endpoints..."
echo ""

# Test 1: Clear Registry
echo "1. Testing DELETE /api/materialized-views/registry"
response=$(curl -s -X DELETE "$BASE_URL/registry")
echo "Response: $response"
echo ""

# Test 2: Analyze (Stage 1)
echo "2. Testing POST /api/materialized-views/analyze (Stage 1)"
response=$(curl -s -X POST "$BASE_URL/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.area_code > 100 GROUP BY A.country"
    ],
    "minOccurrences": 2
  }')
echo "$response" | jq '{success, stats, mvCount: (.materializedViews | length)}'
echo ""

# Test 3: Registry Status
echo "3. Testing GET /api/materialized-views/registry"
response=$(curl -s -X GET "$BASE_URL/registry")
echo "$response" | jq '{totalMVs, storageLocation}'
echo ""

# Test 4: Rewrite (Stage 2)
echo "4. Testing POST /api/materialized-views/rewrite (Stage 2)"
response=$(curl -s -X POST "$BASE_URL/rewrite" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.area_code > 100 GROUP BY A.country"
  }')
echo "$response" | jq '{matched, mvUsed}'
echo ""

# Test 5: Verify existing endpoint still works
echo "5. Testing existing POST /api/materialized-views/optimize (should still work)"
response=$(curl -s -X POST "$BASE_URL/optimize" \
  -H "Content-Type: application/json" \
  -d '{
    "queries": [
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id GROUP BY A.country",
      "SELECT A.country, COUNT(*) FROM db1.A JOIN db1.B ON A.id = B.id WHERE A.id > 10 GROUP BY A.country"
    ],
    "minOccurrences": 2
  }')
echo "$response" | jq '{success, stats}'
echo ""

echo "✅ All endpoint tests complete!"
echo ""
echo "If all responses show success: true, the implementation is working!"

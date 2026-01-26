#!/bin/bash

# Test script for Materialized View Optimization endpoint
# Usage: ./test-endpoint.sh

CORAL_SERVICE_URL="http://localhost:8080"
ENDPOINT="${CORAL_SERVICE_URL}/api/materialized-views/optimize"

echo "======================================================================="
echo "Testing Materialized View Optimization Endpoint"
echo "======================================================================="
echo ""

# Check if service is running
echo "1. Checking if Coral Service is running..."
if curl -s -f "${CORAL_SERVICE_URL}" > /dev/null 2>&1; then
    echo "   ✓ Coral Service is running at ${CORAL_SERVICE_URL}"
else
    echo "   ✗ Coral Service is NOT running!"
    echo ""
    echo "   Please start the service first:"
    echo "   cd coral-service"
    echo "   ../gradlew bootRun --args='--spring.profiles.active=localMetastore'"
    echo ""
    exit 1
fi

echo ""
echo "======================================================================="
echo "TEST 1: Basic Join Optimization"
echo "======================================================================="
echo ""

cat > /tmp/test-request-1.json <<'EOF'
{
  "queries": [
    "SELECT * FROM A JOIN B ON A.id = B.id JOIN C ON B.id = C.id",
    "SELECT * FROM A JOIN B ON A.id = B.id JOIN D ON B.id = D.id",
    "SELECT * FROM A JOIN B ON A.id = B.id JOIN E ON B.id = E.id"
  ],
  "minOccurrences": 2,
  "sourceLanguage": "hive"
}
EOF

echo "Request:"
cat /tmp/test-request-1.json | jq .
echo ""

echo "Calling endpoint..."
RESPONSE=$(curl -s -X POST "${ENDPOINT}" \
  -H "Content-Type: application/json" \
  -d @/tmp/test-request-1.json)

echo ""
echo "Response:"
echo "$RESPONSE" | jq .

echo ""
echo "Summary:"
echo "  - Materialized Views Created: $(echo "$RESPONSE" | jq -r '.stats.materializedViewsCreated')"
echo "  - Total Replacements: $(echo "$RESPONSE" | jq -r '.stats.totalReplacements')"
echo "  - Common Patterns Found: $(echo "$RESPONSE" | jq -r '.stats.commonPatternsFound')"

echo ""
echo "======================================================================="
echo "TEST 2: Multiple Patterns"
echo "======================================================================="
echo ""

cat > /tmp/test-request-2.json <<'EOF'
{
  "queries": [
    "SELECT * FROM orders o JOIN users u ON o.user_id = u.id WHERE o.status = 'completed'",
    "SELECT * FROM orders o JOIN users u ON o.user_id = u.id WHERE o.amount > 100",
    "SELECT * FROM products p JOIN categories c ON p.category_id = c.id WHERE c.active = true",
    "SELECT * FROM products p JOIN categories c ON p.category_id = c.id WHERE p.in_stock = 1"
  ],
  "minOccurrences": 2
}
EOF

echo "Request:"
cat /tmp/test-request-2.json | jq .
echo ""

echo "Calling endpoint..."
RESPONSE=$(curl -s -X POST "${ENDPOINT}" \
  -H "Content-Type: application/json" \
  -d @/tmp/test-request-2.json)

echo ""
echo "Response:"
echo "$RESPONSE" | jq .

echo ""
echo "Summary:"
echo "  - Materialized Views Created: $(echo "$RESPONSE" | jq -r '.stats.materializedViewsCreated')"
echo "  - Total Replacements: $(echo "$RESPONSE" | jq -r '.stats.totalReplacements')"
echo "  - Common Patterns Found: $(echo "$RESPONSE" | jq -r '.stats.commonPatternsFound')"

echo ""
echo "======================================================================="
echo "TEST 3: Error Case - Too Few Queries"
echo "======================================================================="
echo ""

cat > /tmp/test-request-3.json <<'EOF'
{
  "queries": [
    "SELECT * FROM A JOIN B"
  ],
  "minOccurrences": 2
}
EOF

echo "Request:"
cat /tmp/test-request-3.json | jq .
echo ""

echo "Calling endpoint..."
RESPONSE=$(curl -s -X POST "${ENDPOINT}" \
  -H "Content-Type: application/json" \
  -d @/tmp/test-request-3.json)

echo ""
echo "Response (Expected Error):"
echo "$RESPONSE" | jq .

echo ""
echo "======================================================================="
echo "All tests completed!"
echo "======================================================================="

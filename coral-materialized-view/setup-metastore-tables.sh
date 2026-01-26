#!/bin/bash

# Setup Script for Metastore Tables via Coral Service API
# Creates database and tables using the Coral service endpoint

CORAL_URL="${CORAL_URL:-http://localhost:8080}"

echo ""
echo "================================================================"
echo "  METASTORE SETUP - Creating Tables via Coral Service"
echo "================================================================"
echo ""

echo "📦 Creating database and tables using Coral service API..."
echo "   Service URL: $CORAL_URL"
echo ""

# Function to execute DDL via Coral service
execute_ddl() {
  local ddl="$1"
  local description="$2"

  echo -n "  $description... "

  response=$(curl -s --header "Content-Type: application/json" \
    --request POST \
    --data "$ddl" \
    "$CORAL_URL/api/catalog-ops/execute" 2>&1)

  if [ $? -eq 0 ]; then
    echo "✓"
    return 0
  else
    echo "✗ (may already exist)"
    return 1
  fi
}

# Create database
execute_ddl "CREATE DATABASE IF NOT EXISTS db1" "Creating database db1"

# Create tables with unified schema
# All tables have: id, country, area_code, code, datepartition
execute_ddl "CREATE TABLE IF NOT EXISTS db1.A(id int, country string, area_code int, code string, datepartition string)" "Creating table db1.A"
execute_ddl "CREATE TABLE IF NOT EXISTS db1.B(id int, country string, area_code int, code string, datepartition string)" "Creating table db1.B"
execute_ddl "CREATE TABLE IF NOT EXISTS db1.C(id int, country string, area_code int, code string, datepartition string)" "Creating table db1.C"
execute_ddl "CREATE TABLE IF NOT EXISTS db1.D(id int, country string, area_code int, code string, datepartition string)" "Creating table db1.D"
execute_ddl "CREATE TABLE IF NOT EXISTS db1.E(id int, country string, area_code int, code string, datepartition string)" "Creating table db1.E"

echo ""
echo "================================================================"
echo "Tables Created in db1:"
echo "================================================================"
echo ""
echo "  • db1.A (id, country, area_code, code, datepartition)"
echo "  • db1.B (id, country, area_code, code, datepartition)"
echo "  • db1.C (id, country, area_code, code, datepartition)"
echo "  • db1.D (id, country, area_code, code, datepartition)"
echo "  • db1.E (id, country, area_code, code, datepartition)"
echo ""
echo "Total: 5 tables created"
echo ""
echo "================================================================"
echo "✅ Metastore setup complete!"
echo "================================================================"
echo ""

# Test Scripts Guide

## Active Test Scripts

After cleanup, we have **3 essential test scripts**:

### 1. `run-all-tests.sh` (Master Test Runner)
**Purpose**: Executes all test suites and provides comprehensive summary

**Usage**:
```bash
./run-all-tests.sh
```

**What it runs**:
- Test Suite 1: All Features (16 tests)
- Test Suite 2: Edge Cases (10 tests)
- Test Suite 3: Quick Validation (4 tests)

**Output**: Comprehensive pass/fail summary with feature checklist

---

### 2. `test-all-features.sh` (Feature Tests)
**Purpose**: Tests all implemented features after refactoring

**Tests Covered** (16 total):
1. JOIN Aggregation - Filter-Agnostic Matching
2. Query Rewrite - Use MV for new filter
3. Column Aliases - Semantic Matching
4. ORDER BY Support
5. LIMIT Support
6. HAVING Support
7. Cross-Database Joins
8. Multiple Aggregation Functions
9. Three-Way Joins
10. Registry Status API
11-12. Clear Registry API
13. Combined Features
14. Rewrite with Combined Features
15. No Match for Different Aggregation
16. No Match for Different GROUP BY

**Usage**:
```bash
./test-all-features.sh
```

**Expected**: All 16 tests pass ✅

---

### 3. `test-edge-cases.sh` (Edge Case Tests)
**Purpose**: Tests boundary conditions and error handling

**Tests Covered** (10 total):
1. Empty query list
2. Single query (below minOccurrences)
3. No common patterns
4. Rewrite without MV
5. Invalid SQL syntax
6. Non-existent table
7. Empty registry status
8. Clear empty registry
9. Large query set (10 queries)
10. minOccurrences=1

**Usage**:
```bash
./test-edge-cases.sh
```

**Expected**: All 10 tests pass ✅

---

## Removed Scripts (Redundant)

The following scripts were removed as they were redundant:

- ❌ `test-refactoring-validation.sh` - Features covered by test-all-features.sh
- ❌ `test-refactoring-validation-fixed.sh` - Simplified version, features covered elsewhere

---

## Quick Testing

For a quick smoke test, run:
```bash
./run-all-tests.sh
```

This will execute all 26 tests and provide a comprehensive summary.

---

## Test Coverage Summary

| Category | Tests | Script |
|----------|-------|--------|
| Features | 16 | test-all-features.sh |
| Edge Cases | 10 | test-edge-cases.sh |
| Quick Validation | 4 | run-all-tests.sh (inline) |
| **TOTAL** | **30** | - |

---

## Prerequisites

1. Service must be running:
   ```bash
   ./gradlew :coral-service:bootRun --args='--spring.profiles.active=localMetastore'
   ```

2. Database and tables should be created (scripts handle this automatically)

3. `jq` must be installed for JSON parsing:
   ```bash
   brew install jq  # macOS
   ```

---

## Troubleshooting

### Service Not Running
```
❌ ERROR: Service is not running on port 8080
```
**Solution**: Start the service first with the bootRun command above

### Test Failures
Check the log files:
- `/tmp/test-all-features.log`
- `/tmp/test-edge-cases.log`

### Database Issues
The test scripts automatically create required databases and tables. If you encounter issues, restart the service.

---

## Continuous Integration

To integrate these tests into CI/CD:

1. Start service in background
2. Wait for service to be ready (health check)
3. Run `./run-all-tests.sh`
4. Check exit code (0 = success, 1 = failure)

Example:
```bash
#!/bin/bash
./gradlew :coral-service:bootRun &
SERVICE_PID=$!
sleep 30  # Wait for service to start
./run-all-tests.sh
TEST_RESULT=$?
kill $SERVICE_PID
exit $TEST_RESULT
```

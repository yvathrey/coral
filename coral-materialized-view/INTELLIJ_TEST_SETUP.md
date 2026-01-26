# Running Tests in IntelliJ IDEA

This guide helps you run `MaterializedViewOptimizerTest` in IntelliJ IDEA.

## Prerequisites

- IntelliJ IDEA with Gradle support
- Java 8 configured in IntelliJ
- Project imported as Gradle project

## Setup Instructions

### Option 1: Run via TestNG Configuration (Recommended)

1. **Install TestNG Plugin** (if not already installed):
   - Go to `File` → `Settings` → `Plugins`
   - Search for "TestNG"
   - Install and restart IntelliJ

2. **Create TestNG Run Configuration**:
   - Right-click on `coral-materialized-view/src/test/resources/testng.xml`
   - Select `Run 'testng.xml'`
   - Or: Go to `Run` → `Edit Configurations` → `+` → `TestNG`
   - Set Suite to: `coral-materialized-view/src/test/resources/testng.xml`
   - Set JRE to Java 8
   - Click Apply and Run

### Option 2: Run Individual Test Methods

1. **Configure Test Runner**:
   - Go to `File` → `Settings` → `Build, Execution, Deployment` → `Build Tools` → `Gradle`
   - Set "Run tests using" to: **IntelliJ IDEA** (not Gradle)
   - Click Apply

2. **Set Java 8**:
   - Go to `File` → `Project Structure` → `Project`
   - Set Project SDK to Java 8
   - Set Project language level to "8 - Lambdas, type annotations, etc."

3. **Run Test**:
   - Right-click on test class `MaterializedViewOptimizerTest`
   - Select `Run 'MaterializedViewOptimizerTest'`
   - Or click the green arrow next to a test method

### Option 3: Run via Gradle (Always Works)

If IntelliJ has issues, you can always run via Gradle:

```bash
./gradlew :coral-materialized-view:test --tests MaterializedViewOptimizerTest \
  -Dorg.gradle.java.home=$(/usr/libexec/java_home -v 1.8)
```

## Troubleshooting

### Error: "Unable to instantiate SessionHiveMetaStoreClient"

This means Derby database can't be created. Try these solutions:

1. **Clean and Rebuild**:
   ```bash
   ./gradlew :coral-materialized-view:clean
   ./gradlew :coral-materialized-view:build -x test
   ```

2. **Delete Temp Files**:
   - Delete `/tmp/coral/mv/` directory
   - On Mac: `rm -rf /tmp/coral/mv/`
   - On Windows: Delete `%TEMP%\coral\mv\`

3. **Check Classpath**:
   - Verify `hive.xml` is in classpath
   - Check that `src/test/resources` is marked as Test Resources Root:
     - Right-click folder → `Mark Directory as` → `Test Resources Root`

4. **Invalidate Caches**:
   - Go to `File` → `Invalidate Caches / Restart`
   - Select "Invalidate and Restart"

5. **Check Dependencies**:
   - Go to `View` → `Tool Windows` → `Gradle`
   - Click refresh icon to reload Gradle dependencies

### Error: "Table not found"

1. Ensure test setup ran successfully - check console for:
   ```
   Setting up test environment...
   Test directory: /tmp/coral/mv/...
   Test tables created successfully
   ```

2. If setup messages are missing, check that `@BeforeClass` annotation is recognized

### Derby Lock File Issues

If you see "Another instance of Derby may have already booted the database":

1. Stop all running tests
2. Delete temp directory: `rm -rf /tmp/coral/mv/*`
3. Restart IntelliJ
4. Run tests again

## Test Output

When tests run successfully, you should see:
```
Setting up test environment...
Test directory: /tmp/coral/mv/[uuid]
Loading hive.xml configuration from resources
HiveConf loaded with test directory: /tmp/coral/mv/[uuid]
Starting Hive session...
Creating test tables...
Test tables created successfully
Metastore setup complete
```

## Debugging Tips

1. **Enable Verbose Output**:
   - The test now prints diagnostic messages
   - Check console for setup progress

2. **Check Java Version**:
   - Tests MUST run with Java 8
   - Verify in Run Configuration → JRE

3. **Run Single Test First**:
   - Start with `testCommonSubexpressionFinder` (simplest)
   - Then try `testBasicOptimization`
   - Finally `testMultiplePatterns`

## Still Having Issues?

Run from command line to verify setup:
```bash
cd coral-materialized-view
../gradlew test --tests MaterializedViewOptimizerTest \
  -Dorg.gradle.java.home=$(/usr/libexec/java_home -v 1.8) \
  --info
```

Check the detailed output for clues about configuration issues.

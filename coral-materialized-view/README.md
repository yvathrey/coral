# Coral Materialized View Optimizer

A POC module for automatic SQL query optimization through common subexpression detection and materialized view generation.

## Overview

This module analyzes multiple SQL queries to identify shared patterns (common subqueries, joins, etc.) and automatically:
1. Creates materialized views for common patterns
2. Rewrites original queries to use these materialized views

This can significantly improve query performance by avoiding redundant computation of shared subexpressions.

## Example

Given these input queries:
```sql
SELECT * FROM A JOIN B JOIN C
SELECT * FROM A JOIN B JOIN D
SELECT * FROM A JOIN B JOIN E
```

The optimizer will:
1. Detect that `SELECT * FROM A JOIN B` is common across all queries
2. Create a materialized view:
```sql
CREATE MATERIALIZED VIEW mv_common_0 AS
SELECT * FROM A JOIN B
```
3. Rewrite the queries:
```sql
SELECT * FROM mv_common_0 JOIN C
SELECT * FROM mv_common_0 JOIN D
SELECT * FROM mv_common_0 JOIN E
```

## Usage

### Basic API

```java
import com.linkedin.coral.materializedview.MaterializedViewOptimizer;
import org.apache.hadoop.hive.conf.HiveConf;
import java.util.*;

// Input: List of SQL queries
List<String> queries = Arrays.asList(
    "SELECT * FROM A JOIN B JOIN C",
    "SELECT * FROM A JOIN B JOIN D",
    "SELECT * FROM A JOIN B JOIN E"
);

// Create optimizer
HiveConf hiveConf = new HiveConf();
MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(hiveConf);

// Run optimization (minOccurrences = 2 means pattern must appear in at least 2 queries)
OptimizationResult result = optimizer.optimize(queries, 2);

// Get results
Map<String, MaterializedViewInfo> materializedViews = result.getMaterializedViews();
List<RewriteResult> rewrittenQueries = result.getRewrittenQueries();

// Or print a formatted report
System.out.println(result.getReport());
```

### Running the Example

```bash
cd coral-materialized-view
../gradlew build
../gradlew run
```

## Architecture

The module consists of four main components:

### 1. CommonSubexpressionFinder
Analyzes multiple query plans (RelNodes) to find common patterns that appear across multiple queries.

**Key features:**
- Uses visitor pattern to traverse RelNode trees
- Currently focuses on Join operations (extensible to filters, aggregations, etc.)
- Tracks occurrence count and query indices for each pattern

### 2. MaterializedViewGenerator
Generates SQL for materialized views based on identified common patterns.

**Key features:**
- Converts RelNode to SQL using Calcite's RelToSqlConverter
- Generates unique view names (mv_common_0, mv_common_1, etc.)
- Produces CREATE MATERIALIZED VIEW statements

### 3. QueryRewriter
Rewrites original queries to use materialized views instead of computing common subexpressions.

**Key features:**
- Uses shuttle pattern to traverse and transform RelNodes
- Replaces common subexpressions with references to materialized views
- Tracks number of replacements per query

### 4. MaterializedViewOptimizer
Main orchestrator that coordinates the entire optimization process.

**Workflow:**
1. Parse SQL queries to RelNodes using HiveToRelConverter
2. Find common subexpressions across queries
3. Generate materialized views for common patterns
4. Rewrite queries to use materialized views
5. Return results with original queries, materialized views, and rewritten queries

## API Reference

### MaterializedViewOptimizer

**Constructor:**
```java
MaterializedViewOptimizer(HiveConf hiveConf)
```

**Methods:**
```java
// Optimize with default threshold (minOccurrences = 2)
OptimizationResult optimize(List<String> sqlQueries)

// Optimize with custom threshold
OptimizationResult optimize(List<String> sqlQueries, int minOccurrences)
```

### OptimizationResult

**Methods:**
```java
List<String> getOriginalQueries()
Map<String, MaterializedViewInfo> getMaterializedViews()
List<RewriteResult> getRewrittenQueries()
Map<String, SubexpressionInfo> getCommonSubexpressions()
String getReport()  // Human-readable report
```

### MaterializedViewInfo

**Methods:**
```java
String getViewName()        // e.g., "mv_common_0"
String getViewSql()         // SQL definition of the view
RelNode getOriginalNode()   // Original RelNode
String toString()           // CREATE MATERIALIZED VIEW statement
```

### RewriteResult

**Methods:**
```java
RelNode getRewrittenRelNode()
String getRewrittenSql()
int getReplacementCount()    // Number of subexpressions replaced
```

## Extensibility

The current POC focuses on Join operations, but the architecture is designed to be extensible:

### Adding Support for Other Operations

To detect additional patterns (filters, aggregations, unions, etc.):

1. Modify `CommonSubexpressionFinder.isInterestingSubexpression()`:
```java
private boolean isInterestingSubexpression(RelNode node) {
    return node instanceof Join
        || node instanceof Filter
        || node instanceof Aggregate;
}
```

2. Extend `QueryRewriter.SubexpressionReplacer` to handle new node types

### Custom Pattern Detection

Implement custom logic by extending `CommonSubexpressionFinder`:
```java
public class CustomSubexpressionFinder extends CommonSubexpressionFinder {
    @Override
    protected boolean isInterestingSubexpression(RelNode node) {
        // Custom logic for what constitutes an "interesting" pattern
        return customLogic(node);
    }
}
```

## Implementation Notes

### Current Limitations (POC)
- Focuses on Join operations; filters, aggregations not yet supported
- Uses RelNode.explain() for digest computation (could be improved with normalized representations)
- TableScan creation for materialized views is simplified
- Requires proper Hive metastore configuration for full functionality

### Future Enhancements
- Support for multi-level aggregations
- Subquery detection and materialization
- Cost-based decision making for which patterns to materialize
- Integration with Coral's existing rewrite infrastructure
- Support for incremental view maintenance

## Testing

Run tests:
```bash
../gradlew test
```

See `MaterializedViewOptimizerTest.java` for example test cases.

## Dependencies

- coral-hive: For SQL parsing (HiveToRelConverter)
- coral-spark: For additional SQL dialect support
- Apache Calcite: For RelNode manipulation and SQL generation

## License

See [LICENSE](../LICENSE) file in the root directory.

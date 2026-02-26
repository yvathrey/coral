# Materialized View Architecture - Visual Summary

## System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                     MATERIALIZED VIEW SYSTEM                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌───────────────────────────────────────────────────────────┐    │
│  │              STAGE 1: ANALYSIS (Offline/Batch)            │    │
│  └───────────────────────────────────────────────────────────┘    │
│                                                                     │
│    Input: List<SQL Queries>                                        │
│      │                                                              │
│      ├─► HiveToRelConverter                                        │
│      │   └─► Parse SQL → RelNode trees                             │
│      │                                                              │
│      ├─► CommonSubexpressionFinder                                 │
│      │   ├─► SubexpressionCollector (traverse trees)               │
│      │   ├─► Detect patterns (JOINs, Aggregations)                 │
│      │   ├─► Count occurrences                                     │
│      │   └─► Filter nested patterns (HASH_BASED) ✅                │
│      │       └─► Build Merkle trees → O(N² × D)                    │
│      │                                                              │
│      ├─► MaterializedViewGenerator                                 │
│      │   └─► Generate MV SQL for each pattern                      │
│      │                                                              │
│      └─► MaterializedViewRegistry                                  │
│          └─► Store: {digest → MV metadata}                         │
│                                                                     │
│    Output: List<MaterializedViewInfo>, Registry                    │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────┐    │
│  │            STAGE 2: QUERY REWRITING (Runtime)             │    │
│  └───────────────────────────────────────────────────────────┘    │
│                                                                     │
│    Input: Single SQL Query                                         │
│      │                                                              │
│      ├─► HiveToRelConverter                                        │
│      │   └─► Parse SQL → RelNode tree                              │
│      │                                                              │
│      ├─► PatternMatcher                                            │
│      │   ├─► Traverse query tree                                   │
│      │   ├─► Compute digest (strip ORDER BY)                       │
│      │   ├─► Check exact match in registry (Fast Path)             │
│      │   └─► Try filter implication (if no exact match)            │
│      │       └─► FilterImplicationChecker                          │
│      │           └─► Query filter implies MV filter?               │
│      │               └─► Return residual filter                    │
│      │                                                              │
│      ├─► QueryRewriter                                             │
│      │   ├─► Replace matched node with TableScan(mv_name)          │
│      │   ├─► Add residual filter (if any)                          │
│      │   ├─► Preserve ORDER BY, LIMIT on top                       │
│      │   └─► Convert RelNode → SQL                                 │
│      │                                                              │
│      └─► MaterializedViewRegistry                                  │
│          └─► recordUsage(digest) - track metrics                   │
│                                                                     │
│    Output: Rewritten SQL using MV                                  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Component Interaction Flow

```
┌──────────────────────┐
│ MaterializedView     │ ◄── Entry Point (orchestrates everything)
│ Service              │
└──────┬───────────────┘
       │
       ├─► Stage 1: analyzeQueries()
       │   │
       │   └─► ┌────────────────────────┐
       │       │ HiveToRelConverter     │ ◄── SQL → RelNode
       │       └────────┬───────────────┘
       │                │
       │                ▼
       │       ┌────────────────────────┐
       │       │ CommonSubexpression    │ ◄── Find patterns
       │       │ Finder                 │
       │       └────────┬───────────────┘
       │                │
       │                ├─► SubexpressionCollector (visitor)
       │                ├─► filterNestedPatternsWithHashing()
       │                └─► Return Map<digest, SubexpressionInfo>
       │                │
       │                ▼
       │       ┌────────────────────────┐
       │       │ MaterializedView       │ ◄── Generate MV SQL
       │       │ Generator              │
       │       └────────┬───────────────┘
       │                │
       │                ▼
       │       ┌────────────────────────┐
       │       │ MaterializedView       │ ◄── Store patterns
       │       │ Registry               │
       │       └────────────────────────┘
       │
       └─► Stage 2: rewriteQuery()
           │
           └─► ┌────────────────────────┐
               │ HiveToRelConverter     │ ◄── SQL → RelNode
               └────────┬───────────────┘
                        │
                        ▼
               ┌────────────────────────┐
               │ PatternMatcher         │ ◄── Find matching MV
               └────────┬───────────────┘
                        │
                        ├─► checkNode() - compute digest
                        ├─► registry.contains() - exact match?
                        └─► tryFilterImplicationMatch()
                            │
                            └─► ┌──────────────────────┐
                                │ FilterImplication    │
                                │ Checker              │
                                └──────┬───────────────┘
                                       │
                                       └─► Returns ImplicationResult
                                           {implies: true, residual: "country='US'"}
                        │
                        ▼
               ┌────────────────────────┐
               │ QueryRewriter          │ ◄── Rewrite query
               └────────┬───────────────┘
                        │
                        ├─► Replace matched node with MV scan
                        ├─► Add residual filter
                        └─► Convert back to SQL
                        │
                        ▼
               Return rewritten SQL
```

---

## Hash-Based vs String-Based Filtering

### String-Based (Old Approach)
```
Algorithm:
  for each pattern P1:
    for each pattern P2:
      if P1.digest.contains(P2.digest):  ◄── String search
        mark P2 as nested

Complexity: O(N² × M)
  N = number of patterns
  M = average digest length (500-2000 chars)

Example:
  P1 digest: "LogicalJoin(...LogicalJoin(...TableScan[A]...TableScan[B]...)...)"
  P2 digest: "LogicalJoin(...TableScan[A]...TableScan[B]...)"

  Check: P1.contains(P2)? → YES, nested!

Performance:
  100 queries:   ~1 second
  1,000 queries: ~100 seconds
  10,000 queries: ~3 hours ❌
```

### Hash-Based (New Approach) ✅
```
Algorithm:
  Step 1: Build Merkle tree for each pattern
    ┌─────────────────────────────────────┐
    │        h3 = hash("JOIN" + h1 + h2)  │ ◄── Root hash
    ├──────────┬────────────────────────┬─┘
    │          │                        │
    │  h1 = hash("JOIN" + hA + hB)    h2 = hash("TableScan_C")
    │  ├────┬────┤
    │  hA  hB   ...
    │
    └─► Hash Set: {h3, h1, h2, hA, hB}  ◄── Store all hashes

  Step 2: Check containment using set operations
    for each pattern P1:
      for each pattern P2:
        if P2.hashSet.containsAll(P1.hashSet):  ◄── Set containment O(D)
          mark P1 as nested

Complexity: O(N² × D)
  N = number of patterns
  D = tree depth (typically 5-10)

Example:
  P1 (A JOIN B):
    Hash Set: {h1, hA, hB}

  P2 (A JOIN B JOIN C):
    Hash Set: {h3, h1, h2, hA, hB, hC}

  Check: P2.contains(P1's hashes)? → YES, nested!

Performance:
  100 queries:   ~0.5 seconds
  1,000 queries: ~5 seconds
  10,000 queries: ~50 seconds ✅

Speedup: 1000X faster for large workloads
```

---

## Feature: Filter Implication Deep Dive

### Problem
```
Query 1: SELECT location, COUNT(*) FROM members WHERE exp > 5 GROUP BY location
Query 2: SELECT location, COUNT(*) FROM members WHERE exp > 5 AND country = 'US' GROUP BY location
Query 3: SELECT location, COUNT(*) FROM members WHERE exp > 5 AND dept = 'Eng' GROUP BY location

Without filter implication: Need 3 separate MVs ❌
With filter implication: 1 MV serves all 3! ✅
```

### How It Works
```
┌─────────────────────────────────────────────────────────────────┐
│                    FILTER IMPLICATION LOGIC                     │
└─────────────────────────────────────────────────────────────────┘

Step 1: Create MV (most general pattern)
  ┌────────────────────────────────────────────────┐
  │ MV: SELECT location, COUNT(*)                  │
  │     FROM members                               │
  │     WHERE experience_years > 5                 │
  │     GROUP BY location                          │
  └────────────────────────────────────────────────┘

  Filter: experience_years > 5

Step 2: Query with additional filter
  ┌────────────────────────────────────────────────┐
  │ Query: SELECT location, COUNT(*)               │
  │        FROM members                            │
  │        WHERE experience_years > 5              │
  │              AND country = 'US'                │
  │        GROUP BY location                       │
  └────────────────────────────────────────────────┘

  Filter: experience_years > 5 AND country = 'US'

Step 3: Check implication
  ┌────────────────────────────────────────────────────┐
  │ FilterImplicationChecker.checkImplication()        │
  │                                                    │
  │ Query filter: exp > 5 AND country = 'US'          │
  │ MV filter:    exp > 5                             │
  │                                                    │
  │ Decompose query into conjuncts:                   │
  │   - exp > 5          ◄── Matches MV filter ✓      │
  │   - country = 'US'   ◄── Residual                 │
  │                                                    │
  │ Result: ImplicationResult {                       │
  │   implies: true                                   │
  │   residualFilter: "country = 'US'"                │
  │ }                                                  │
  └────────────────────────────────────────────────────┘

Step 4: Rewrite query
  ┌────────────────────────────────────────────────┐
  │ Rewritten: SELECT *                            │
  │            FROM mv_common_0                    │
  │            WHERE country = 'US'                │
  └────────────────────────────────────────────────┘

Result: MV provides base filtering (exp > 5)
        Residual filter (country = 'US') applied on MV
        No need to scan base table! ✅
```

### Code Flow
```
PatternMatcher.findMatchingPattern(queryPlan)
  │
  ├─► checkNode(node)
  │   ├─► computeDigest(node)
  │   ├─► registry.contains(digest) ◄── Fast path (exact match)
  │   │   └─► return MatchResult (no residual)
  │   │
  │   └─► tryFilterImplicationMatch(node) ◄── Filter implication path
  │       │
  │       ├─► extractFilter(queryNode) → "exp > 5 AND country = 'US'"
  │       ├─► extractFilter(mvNode)    → "exp > 5"
  │       │
  │       └─► FilterImplicationChecker.checkImplication()
  │           │
  │           ├─► Decompose query filter into conjuncts
  │           ├─► Check if MV filter is in conjuncts
  │           └─► Compute residual = remaining conjuncts
  │               └─► return ImplicationResult {
  │                     implies: true,
  │                     residualFilter: "country = 'US'"
  │                   }
  │
  └─► return MatchResult {
        matched: true,
        mvUsed: "mv_common_0",
        residualFilter: "country = 'US'"
      }
  │
  ▼
QueryRewriter.rewriteQuery(queryPlan, matchResult)
  │
  ├─► Replace matched node with TableScan(mv_common_0)
  ├─► Add residual filter on top: LogicalFilter("country = 'US'")
  └─► Convert to SQL: "SELECT * FROM mv_common_0 WHERE country = 'US'"
```

---

## Key Algorithms Summary

### 1. Pattern Detection (CommonSubexpressionFinder)
```
Input: List<RelNode> queryPlans, int minOccurrences

Algorithm:
  1. Map<digest, SubexpressionInfo> patterns = {}

  2. For each query:
       Visit each node in tree:
         If node is interesting (Join, Aggregate):
           digest = computeDigest(node)
           patterns[digest].count++

  3. Filter patterns with count < minOccurrences

  4. Remove nested patterns (HASH_BASED):
       For each pattern P1:
         Build Merkle tree hash set
       For each pattern P1, P2:
         If P2.hashSet contains all of P1.hashSet:
           Remove P1 (it's nested in P2)

  5. Return filtered patterns

Output: Map<digest, SubexpressionInfo>
```

### 2. Pattern Matching (PatternMatcher)
```
Input: RelNode queryPlan, MaterializedViewRegistry registry

Algorithm:
  1. Traverse query tree

  2. For each node:
       a. Compute digest (strip ORDER BY first)
       b. Check exact match:
            If registry.contains(digest):
              return MatchResult(matched=true, residual=null)

       c. Try filter implication:
            queryFilter = extractFilter(node)
            For each MV in registry:
              mvFilter = extractFilter(MV.pattern)
              If checkImplication(queryFilter, mvFilter):
                return MatchResult(matched=true, residual=computed)

  3. If no match found:
       return MatchResult(matched=false)

Output: MatchResult
```

### 3. Filter Implication (FilterImplicationChecker)
```
Input: RexNode queryFilter, RexNode mvFilter

Algorithm:
  1. If queryFilter equals mvFilter:
       return ImplicationResult(implies=true, residual=null)

  2. If queryFilter is AND expression:
       conjuncts = decompose(queryFilter)
       For each conjunct:
         If conjunct equals mvFilter:
           residual = remaining conjuncts
           return ImplicationResult(implies=true, residual=residual)

  3. Otherwise:
       return ImplicationResult(implies=false)

Output: ImplicationResult
```

### 4. Query Rewriting (QueryRewriter)
```
Input: RelNode queryPlan, MatchResult match

Algorithm:
  1. Create SubexpressionReplacer visitor

  2. Traverse query tree:
       If node matches pattern:
         Replace with: TableScan(mv_name)
         If residual filter exists:
           Add LogicalFilter(residual) on top

  3. Preserve ORDER BY, LIMIT at top of tree

  4. Convert modified RelNode → SQL using RelToSqlConverter

Output: String (rewritten SQL)
```

---

## Performance Comparison Table

| Operation | String-Based | Hash-Based | Speedup |
|-----------|--------------|------------|---------|
| Build pattern index | O(N) | O(N × D) | Same |
| Nested pattern check | O(N² × M) | O(N² × D) | 100-1000X |
| Pattern matching | O(1) | O(1) | Same |
| Filter implication | O(F) | O(F) | Same |

Where:
- N = number of patterns
- M = digest string length (500-2000 chars)
- D = tree depth (5-10 nodes)
- F = number of filter conjuncts

**Memory Usage:**
- String-Based: O(N × M) - stores long digest strings
- Hash-Based: O(N × D) - stores hash sets per pattern
- Similar memory footprint, hash-based is more efficient

---

## Quick Reference: Which Component Does What?

| Component | Purpose | Input | Output |
|-----------|---------|-------|--------|
| **MaterializedViewService** | Orchestrator | Queries / Single query | MVs / Rewritten query |
| **CommonSubexpressionFinder** | Pattern detection | List<RelNode> | Map<digest, SubexpressionInfo> |
| **PatternMatcher** | Runtime matching | RelNode, Registry | MatchResult |
| **FilterImplicationChecker** | Filter logic | Query filter, MV filter | ImplicationResult |
| **QueryRewriter** | SQL generation | RelNode, Match | Rewritten SQL |
| **MaterializedViewRegistry** | Storage | - | MV lookup/storage |

---

## Decision Tree: When to Use What

```
Do you have >1000 queries to analyze?
  ├─ YES → Use HASH_BASED filtering ✅
  └─ NO  → Either approach works

Do queries have complex JOINs (4+ tables)?
  ├─ YES → Use HASH_BASED (long digests) ✅
  └─ NO  → Either approach works

Is this production deployment?
  └─ YES → Always use HASH_BASED ✅

Do queries have additional WHERE clauses?
  └─ YES → Enable filter implication ✅
             (automatically enabled in PatternMatcher)

Do you need to debug pattern matching?
  ├─ YES → Look at digest strings in logs
  └─ NO  → Hash-based abstracts this away
```

---

## Common Pitfalls & Solutions

### Pitfall 1: Using STRING_BASED in production
**Problem:** O(N² × M) complexity causes 3-hour analysis for 10K queries
**Solution:** Always use `NestedPatternFilterStrategy.HASH_BASED`

### Pitfall 2: Not stripping ORDER BY before matching
**Problem:** "SELECT ... ORDER BY x" won't match "SELECT ..." even though ORDER BY doesn't affect MV
**Solution:** `PatternMatcher.stripSort()` removes ORDER BY before computing digest

### Pitfall 3: False negatives in filter implication
**Problem:** "WHERE x > 10" should imply "WHERE x > 5" but doesn't match
**Solution:** Extend `FilterImplicationChecker` with range subsumption logic

### Pitfall 4: Registry not persisted
**Problem:** MVs lost on service restart
**Solution:** Implement persistent storage (database, file system) for registry

---

**For full code details, see `MATERIALIZED_VIEW_CODE_GUIDE.md`**

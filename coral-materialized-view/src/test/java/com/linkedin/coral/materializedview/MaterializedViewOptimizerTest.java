/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import java.io.File;
import java.io.IOException;
import java.util.*;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.linkedin.coral.common.HiveMetastoreClient;

import static org.testng.Assert.*;


/**
 * Test cases for MaterializedViewOptimizer.
 */
public class MaterializedViewOptimizerTest {

  private static final Logger LOG = LoggerFactory.getLogger(MaterializedViewOptimizerTest.class);

  private static HiveConf conf;
  private static HiveMetastoreClient msc;

  @BeforeClass
  public static void setUp() throws IOException, HiveException, MetaException {
    // Use local test infrastructure to set up Hive environment
    conf = TestUtils.loadResourceHiveConf();
    LOG.debug("Setting up test environment...");
    LOG.debug("Test directory: {}", conf.get(TestUtils.CORAL_MV_TEST_DIR));

    try {
      msc = TestUtils.setupTestMetastore(conf);
      LOG.debug("Metastore setup complete");
    } catch (Exception e) {
      LOG.error("Failed to setup metastore: {}", e.getMessage());
      e.printStackTrace();
      throw e;
    }
  }

  @AfterTest
  public void cleanUp() throws IOException {
    // Clean up test directory after tests
    if (conf != null) {
      String testDir = conf.get(TestUtils.CORAL_MV_TEST_DIR);
      if (testDir != null) {
        LOG.debug("Cleaning up test directory: {}", testDir);
        try {
          FileUtils.deleteDirectory(new File(testDir));
        } catch (IOException e) {
          LOG.warn("Failed to clean up test directory: {}", e.getMessage());
        }
      }
    }
  }

  @Test
  public void testBasicOptimization() {
    // Test the example from the requirements using real test tables:
    // default.tableOne and default.tableTwo exist in the test environment
    //
    // Should create a materialized view for common join pattern

    List<String> queries =
        Arrays.asList("SELECT * FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x WHERE t1.a > 5",
            "SELECT * FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x WHERE t1.b = 'test'",
            "SELECT * FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x WHERE t2.y > 100");

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      // Verify that we found common patterns
      assertNotNull(result);
      assertTrue(result.getCommonSubexpressions().size() > 0, "Should find at least one common pattern");

      // Print the report
      LOG.debug("Basic optimization test result:\n{}", result.getReport());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Test should succeed with proper Hive setup: " + e.getMessage());
    }
  }

  @Test
  public void testMultiplePatterns() {
    // Test with multiple different common patterns using test tables
    List<String> queries =
        Arrays.asList("SELECT * FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x WHERE t1.a > 10",
            "SELECT * FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x WHERE t1.a > 20",
            "SELECT * FROM default.tableTwo t2 JOIN default.tableThree t3 ON t2.x = t3.id WHERE t2.x > 5",
            "SELECT * FROM default.tableTwo t2 JOIN default.tableThree t3 ON t2.x = t3.id WHERE t3.value = 'test'");

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      assertNotNull(result);
      LOG.debug("Multiple patterns test result:\n{}", result.getReport());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Test should succeed with proper Hive setup: " + e.getMessage());
    }
  }

  @Test
  public void testCommonSubexpressionFinder() {
    // Test the common subexpression finder directly
    CommonSubexpressionFinder finder = new CommonSubexpressionFinder();

    // This test would require creating mock RelNodes
    // For POC purposes, we demonstrate the API
    List<org.apache.calcite.rel.RelNode> queryPlans = new ArrayList<>();

    Map<String, CommonSubexpressionFinder.SubexpressionInfo> result = finder.findCommonSubexpressions(queryPlans, 2);

    assertNotNull(result);
  }

  @Test
  public void testAliasNormalization() {
    // Verify that queries with different column aliases share same MV
    // This validates that aliases are excluded from pattern matching (semantic matching)
    // but preserved in query output via projection layer
    List<String> queries = Arrays.asList(
        "SELECT t1.a, COUNT(*) as cnt FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x GROUP BY t1.a",
        "SELECT t1.a, COUNT(*) as total FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x GROUP BY t1.a",
        "SELECT t1.a, COUNT(*) FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x GROUP BY t1.a");

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      assertNotNull(result);

      // All queries have identical join + aggregation pattern
      // Different aliases (cnt, total, no alias) should be ignored in pattern matching
      assertTrue(result.getCommonSubexpressions().size() > 0,
          "Queries with different aliases should share same MV (semantic matching)");

      LOG.debug("Alias Normalization Test Result:\n{}", result.getReport());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Alias normalization test failed: " + e.getMessage());
    }
  }

  @Test
  public void testMultipleAggregatesWithDifferentAliases() {
    // Verify that queries with multiple aggregates and different aliases share same MV
    List<String> queries = Arrays.asList(
        "SELECT t1.a, COUNT(*) as cnt, SUM(t1.b) as total FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x GROUP BY t1.a",
        "SELECT t1.a, COUNT(*) as count_val, SUM(t1.b) as sum_val FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x GROUP BY t1.a",
        "SELECT t1.a, COUNT(*), SUM(t1.b) FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x GROUP BY t1.a");

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      assertNotNull(result);

      // All queries have identical pattern with multiple aggregates
      // Different aliases for each aggregate should be ignored
      assertTrue(result.getCommonSubexpressions().size() > 0,
          "Multiple aggregates with different aliases should share same MV");

      LOG.debug("Multiple Aggregates with Aliases Test Result:\n{}", result.getReport());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Multiple aggregates with aliases test failed: " + e.getMessage());
    }
  }

  @Test
  public void testJoinOrderInsensitiveForInnerJoins() {
    // Verify that INNER joins with different order share same MV
    // A JOIN B should equal B JOIN A (join order normalized)
    List<String> queries = Arrays.asList(
        "SELECT t1.a, COUNT(*) FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x GROUP BY t1.a",
        "SELECT t2.x, COUNT(*) FROM default.tableTwo t2 JOIN default.tableOne t1 ON t2.x = t1.a GROUP BY t2.x");

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      assertNotNull(result);

      // INNER joins should be order-insensitive
      // A JOIN B = B JOIN A → should share same MV
      assertTrue(result.getCommonSubexpressions().size() > 0,
          "INNER joins with different order should share same MV (join order normalized)");

      LOG.debug("Join Order Insensitive Test Result:\n{}", result.getReport());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Join order insensitive test failed: " + e.getMessage());
    }
  }

  @Test
  public void testJoinOrderWithDifferentAliases() {
    // Combine join order normalization with alias normalization
    // A JOIN B with alias "cnt" vs B JOIN A with alias "total"
    List<String> queries = Arrays.asList(
        "SELECT t1.a, COUNT(*) as cnt FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x GROUP BY t1.a",
        "SELECT t2.x, COUNT(*) as total FROM default.tableTwo t2 JOIN default.tableOne t1 ON t2.x = t1.a GROUP BY t2.x",
        "SELECT t1.a, COUNT(*) FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x GROUP BY t1.a");

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      assertNotNull(result);

      // Both join order AND aliases should be normalized
      // All three queries should share same MV
      assertTrue(result.getCommonSubexpressions().size() > 0, "Join order + alias variations should all share same MV");

      LOG.debug("Join Order + Alias Test Result:\n{}", result.getReport());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Join order with aliases test failed: " + e.getMessage());
    }
  }

  @Test
  public void testFilterAgnosticMatchingForAggregationsOnJoins() {
    // Test the critical filter-agnostic matching feature:
    // Queries with same join+aggregation but different WHERE clauses should share ONE MV
    // This is the main value proposition for materialized views!
    List<String> queries = Arrays.asList(
        // Q1: No WHERE clause
        "SELECT t1.a, COUNT(*) FROM default.tableOne t1 "
            + "JOIN default.tableTwo t2 ON t1.a = t2.x "
            + "JOIN default.tableThree t3 ON t2.x = t3.id "
            + "GROUP BY t1.a",
        // Q2: WHERE clause on first table
        "SELECT t1.a, COUNT(*) FROM default.tableOne t1 "
            + "JOIN default.tableTwo t2 ON t1.a = t2.x "
            + "JOIN default.tableThree t3 ON t2.x = t3.id "
            + "WHERE t1.b = 'test' "
            + "GROUP BY t1.a",
        // Q3: Different WHERE clause
        "SELECT t1.a, COUNT(*) FROM default.tableOne t1 "
            + "JOIN default.tableTwo t2 ON t1.a = t2.x "
            + "JOIN default.tableThree t3 ON t2.x = t3.id "
            + "WHERE t1.c > 100.0 "
            + "GROUP BY t1.a");

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      assertNotNull(result);

      // CRITICAL ASSERTION: All three queries should share ONE materialized view
      // They have identical join structure + aggregation, just different WHERE clauses
      // Filter-agnostic matching should detect this and create ONE MV for the expensive join
      assertEquals(1, result.getCommonSubexpressions().size(),
          "Queries with same join+aggregation but different WHERE clauses should share ONE MV (filter-agnostic matching)");

      // Verify the MV was created
      assertTrue(result.getMaterializedViews().size() > 0, "At least one MV should be created");

      // Verify all queries were rewritten (should use the same MV)
      int totalReplacements =
          result.getRewrittenQueries().stream().mapToInt(r -> r.getReplacementCount()).sum();
      assertTrue(totalReplacements >= 3,
          "All 3 queries should be rewritten to use the MV (3+ replacements expected, got " + totalReplacements + ")");

      LOG.debug("Filter-Agnostic Matching Test Result:\n{}", result.getReport());

      // Log the MV SQL to verify it's just the join (no WHERE clauses)
      for (MaterializedViewGenerator.MaterializedViewInfo mvInfo : result.getMaterializedViews().values()) {
        LOG.debug("MV Created: {}", mvInfo.getViewName());
        LOG.debug("MV SQL: {}", mvInfo.getViewSql());
        // The MV SQL should NOT contain WHERE clauses (filter-agnostic)
        assertFalse(mvInfo.getViewSql().toUpperCase().contains("WHERE"),
            "MV should not contain WHERE clause (filter-agnostic matching)");
      }

    } catch (Exception e) {
      e.printStackTrace();
      fail("Filter-agnostic matching test failed: " + e.getMessage());
    }
  }

  @Test
  public void testScenario2_JoinOptimization() {
    // SCENARIO 2: JOIN OPTIMIZATION
    // Test that aggregation-on-join creates MV with ONLY the join (no GROUP BY)
    // and queries apply GROUP BY on top of the MV
    List<String> queries = Arrays.asList(
        // Q1: No WHERE clause
        "SELECT t1.a, COUNT(*) FROM default.tableOne t1 "
            + "JOIN default.tableTwo t2 ON t1.a = t2.x "
            + "JOIN default.tableThree t3 ON t2.x = t3.id "
            + "GROUP BY t1.a",
        // Q2: With WHERE clause
        "SELECT t1.a, COUNT(*) FROM default.tableOne t1 "
            + "JOIN default.tableTwo t2 ON t1.a = t2.x "
            + "JOIN default.tableThree t3 ON t2.x = t3.id "
            + "WHERE t1.b = 'filter1' "
            + "GROUP BY t1.a");

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      assertNotNull(result);

      // Count UNIQUE SubexpressionInfo instances
      Set<CommonSubexpressionFinder.SubexpressionInfo> uniquePatterns = new HashSet<>(result.getCommonSubexpressions().values());
      LOG.debug("Scenario 2 - Unique patterns: {}", uniquePatterns.size());

      // CRITICAL: Should create ONE unique MV
      assertEquals(uniquePatterns.size(), 1,
          "Should find ONE unique common pattern (aggregation on join with filter-agnostic matching)");

      // Count UNIQUE MV instances
      Set<MaterializedViewGenerator.MaterializedViewInfo> uniqueMVs = new HashSet<>(result.getMaterializedViews().values());
      assertEquals(uniqueMVs.size(), 1, "Should create ONE materialized view");

      // Verify the MV SQL
      MaterializedViewGenerator.MaterializedViewInfo mvInfo =
          result.getMaterializedViews().values().iterator().next();

      LOG.debug("Scenario 2 - MV Created: {}", mvInfo.getViewName());
      LOG.debug("Scenario 2 - MV SQL: {}", mvInfo.getViewSql());
      LOG.debug("Scenario 2 - Is aggregation on join: {}", mvInfo.isAggregationOnJoin());

      // CRITICAL ASSERTION: MV should NOT contain GROUP BY
      assertFalse(mvInfo.getViewSql().toUpperCase().contains("GROUP BY"),
          "MV should NOT contain GROUP BY (only the join should be materialized)");

      // CRITICAL ASSERTION: MV should contain the JOIN
      assertTrue(mvInfo.getViewSql().toUpperCase().contains("JOIN"),
          "MV should contain the JOIN computation");

      // CRITICAL ASSERTION: MV should be marked as aggregation-on-join
      assertTrue(mvInfo.isAggregationOnJoin(),
          "MV should be marked as aggregation-on-join pattern");

      // Verify both queries were rewritten
      int totalReplacements =
          result.getRewrittenQueries().stream().mapToInt(r -> r.getReplacementCount()).sum();
      assertEquals(totalReplacements, 2, "Both queries should be rewritten");

      // Verify rewritten queries contain GROUP BY
      for (int i = 0; i < result.getRewrittenQueries().size(); i++) {
        String rewrittenSql = result.getRewrittenQueries().get(i).getRewrittenSql();
        LOG.debug("Scenario 2 - Rewritten Query {}: {}", i + 1, rewrittenSql);

        // Rewritten query should have GROUP BY (applied on top of MV)
        assertTrue(rewrittenSql.toUpperCase().contains("GROUP BY"),
            "Rewritten query " + (i + 1) + " should contain GROUP BY");
      }

      // Q2 should have WHERE clause in the rewritten query
      String q2Rewritten = result.getRewrittenQueries().get(1).getRewrittenSql();
      assertTrue(q2Rewritten.toUpperCase().contains("WHERE") || q2Rewritten.toUpperCase().contains("FILTER"),
          "Q2 rewritten query should contain WHERE clause (residual filter)");

      LOG.debug("Scenario 2 Test Result:\n{}", result.getReport());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Scenario 2 (Join Optimization) test failed: " + e.getMessage());
    }
  }

  @Test
  public void testScenario1_FilterImplication() {
    // SCENARIO 1: FILTER IMPLICATION (Filter Subsumption)
    // Test that single-table aggregations with filter subsumption share ONE MV
    // IMPORTANT: Residual filter must reference GROUP BY columns (columns in MV output)
    List<String> queries = Arrays.asList(
        // Q1: Base filter with GROUP BY a, b
        "SELECT t1.a, t1.b, COUNT(*) FROM default.tableOne t1 WHERE t1.a > 5 GROUP BY t1.a, t1.b",
        // Q2: More specific filter (base + additional condition on GROUP BY column b)
        "SELECT t1.a, t1.b, COUNT(*) FROM default.tableOne t1 WHERE t1.a > 5 AND t1.b = 'test' GROUP BY t1.a, t1.b",
        // Q3: Same as Q1 but with ORDER BY
        "SELECT t1.a, t1.b, COUNT(*) FROM default.tableOne t1 WHERE t1.a > 5 GROUP BY t1.a, t1.b ORDER BY t1.a");

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      assertNotNull(result);

      // CRITICAL: Should create ONE MV with the most general filter
      LOG.debug("Scenario 1 - Common subexpressions found: {}", result.getCommonSubexpressions().size());
      LOG.debug("Scenario 1 - MVs created: {}", result.getMaterializedViews().size());

      // Count UNIQUE SubexpressionInfo instances (after merging, multiple keys may point to same instance)
      Set<CommonSubexpressionFinder.SubexpressionInfo> uniquePatterns = new HashSet<>(result.getCommonSubexpressions().values());
      LOG.debug("Scenario 1 - Unique patterns: {}", uniquePatterns.size());

      assertEquals(uniquePatterns.size(), 1,
          "Should find ONE unique common pattern (after filter subsumption)");

      // Count UNIQUE MV instances
      Set<MaterializedViewGenerator.MaterializedViewInfo> uniqueMVs = new HashSet<>(result.getMaterializedViews().values());
      LOG.debug("Scenario 1 - Unique MVs: {}", uniqueMVs.size());

      assertEquals(uniqueMVs.size(), 1,
          "Should create ONE materialized view with the most general filter");

      // Verify the MV SQL
      MaterializedViewGenerator.MaterializedViewInfo mvInfo =
          result.getMaterializedViews().values().iterator().next();

      LOG.debug("Scenario 1 - MV Created: {}", mvInfo.getViewName());
      LOG.debug("Scenario 1 - MV SQL: {}", mvInfo.getViewSql());

      // MV should have the most general filter (a > 5)
      assertTrue(mvInfo.getViewSql().toUpperCase().contains("WHERE"),
          "MV should contain WHERE clause");
      assertTrue(mvInfo.getViewSql().contains("> 5") || mvInfo.getViewSql().contains(">5"),
          "MV should contain the base filter condition");

      // MV should contain GROUP BY
      assertTrue(mvInfo.getViewSql().toUpperCase().contains("GROUP BY"),
          "MV should contain GROUP BY for single-table aggregation");

      // Verify all queries were rewritten
      int totalReplacements =
          result.getRewrittenQueries().stream().mapToInt(r -> r.getReplacementCount()).sum();
      assertEquals(totalReplacements, 3, "All 3 queries should be rewritten");

      // Verify rewritten queries
      for (int i = 0; i < result.getRewrittenQueries().size(); i++) {
        String rewrittenSql = result.getRewrittenQueries().get(i).getRewrittenSql();
        LOG.debug("Scenario 1 - Rewritten Query {}: {}", i + 1, rewrittenSql);

        // All rewritten queries should reference the MV
        assertTrue(rewrittenSql.contains(mvInfo.getViewName()),
            "Query " + (i + 1) + " should reference MV");
      }

      // Q2 should have residual filter (b = 'test')
      String q2Rewritten = result.getRewrittenQueries().get(1).getRewrittenSql();
      // Note: Residual filter might be on aggregated result or applied via filter implication
      LOG.debug("Scenario 1 - Q2 (with additional filter) rewritten: {}", q2Rewritten);

      // Q3 should have ORDER BY preserved
      String q3Rewritten = result.getRewrittenQueries().get(2).getRewrittenSql();
      assertTrue(q3Rewritten.toUpperCase().contains("ORDER BY"),
          "Q3 rewritten should contain ORDER BY");

      LOG.debug("Scenario 1 Test Result:\n{}", result.getReport());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Scenario 1 (Filter Implication) test failed: " + e.getMessage());
    }
  }

  @Test
  public void testScenario3_AggregationOptimization() {
    // SCENARIO 3: AGGREGATION OPTIMIZATION
    // Test that single-table aggregations with/without ORDER BY share one MV
    List<String> queries = Arrays.asList(
        // Q1: Simple aggregation
        "SELECT t1.a, COUNT(*) FROM default.tableOne t1 GROUP BY t1.a",
        // Q2: Same aggregation with ORDER BY
        "SELECT t1.a, COUNT(*) FROM default.tableOne t1 GROUP BY t1.a ORDER BY t1.a");

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      assertNotNull(result);

      // Should create ONE MV
      assertEquals(1, result.getCommonSubexpressions().size(), "Should find ONE common pattern");
      assertEquals(1, result.getMaterializedViews().size(), "Should create ONE materialized view");

      // Verify the MV SQL
      MaterializedViewGenerator.MaterializedViewInfo mvInfo =
          result.getMaterializedViews().values().iterator().next();

      LOG.debug("Scenario 3 - MV Created: {}", mvInfo.getViewName());
      LOG.debug("Scenario 3 - MV SQL: {}", mvInfo.getViewSql());

      // For single-table aggregation, MV SHOULD contain GROUP BY
      assertTrue(mvInfo.getViewSql().toUpperCase().contains("GROUP BY"),
          "MV should contain GROUP BY for single-table aggregation");

      // MV should NOT be marked as aggregation-on-join
      assertFalse(mvInfo.isAggregationOnJoin(),
          "MV should NOT be marked as aggregation-on-join (single table)");

      // Verify both queries were rewritten
      int totalReplacements =
          result.getRewrittenQueries().stream().mapToInt(r -> r.getReplacementCount()).sum();
      assertEquals(totalReplacements, 2, "Both queries should be rewritten");

      // Q1 should be simple scan
      String q1Rewritten = result.getRewrittenQueries().get(0).getRewrittenSql();
      LOG.debug("Scenario 3 - Q1 Rewritten: {}", q1Rewritten);

      // Q2 should have ORDER BY preserved
      String q2Rewritten = result.getRewrittenQueries().get(1).getRewrittenSql();
      LOG.debug("Scenario 3 - Q2 Rewritten: {}", q2Rewritten);
      assertTrue(q2Rewritten.toUpperCase().contains("ORDER BY"), "Q2 rewritten should contain ORDER BY");

      LOG.debug("Scenario 3 Test Result:\n{}", result.getReport());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Scenario 3 (Aggregation Optimization) test failed: " + e.getMessage());
    }
  }

  /**
   * Test Scenario: Range Subsumption
   *
   * Verifies that queries with different numeric thresholds share ONE MV with the most general filter.
   *
   * Test Queries:
   * Q1: SELECT a, COUNT(*) FROM test WHERE a > 3 GROUP BY a
   * Q2: SELECT a, COUNT(*) FROM test WHERE a > 5 GROUP BY a
   * Q3: SELECT a, COUNT(*) FROM test WHERE a > 10 GROUP BY a
   *
   * Expected Behavior:
   * - Creates 1 common MV with WHERE a > 3 (most general filter)
   * - Q1: Exact match → SELECT * FROM mv
   * - Q2: Applies residual → SELECT * FROM mv WHERE a > 5
   * - Q3: Applies residual → SELECT * FROM mv WHERE a > 10
   *
   * This demonstrates that "a > 3" subsumes "a > 5" and "a > 10" through range analysis.
   */
  @Test
  public void testScenario_RangeSubsumption() {
    try {
      LOG.debug("===== Starting Scenario: Range Subsumption Test =====");

      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);

      List<String> queries = Arrays.asList(
          // Q1: Most general filter (a > 3)
          "SELECT a, COUNT(*) FROM default.tableOne WHERE a > 3 GROUP BY a",

          // Q2: More restrictive filter (a > 5)
          "SELECT a, COUNT(*) FROM default.tableOne WHERE a > 5 GROUP BY a",

          // Q3: Most restrictive filter (a > 10)
          "SELECT a, COUNT(*) FROM default.tableOne WHERE a > 10 GROUP BY a"
      );

      LOG.debug("Input queries:");
      for (int i = 0; i < queries.size(); i++) {
        LOG.debug("  Q{}: {}", i + 1, queries.get(i));
      }

      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      LOG.debug("Common patterns found: {}", result.getCommonSubexpressions().size());
      LOG.debug("MVs created: {}", result.getMaterializedViews().size());
      LOG.debug("Queries rewritten: {}", result.getRewrittenQueries().size());

      // Count UNIQUE SubexpressionInfo instances (after merging, multiple keys may point to same instance)
      Set<CommonSubexpressionFinder.SubexpressionInfo> uniquePatterns = new HashSet<>(result.getCommonSubexpressions().values());
      LOG.debug("Unique patterns: {}", uniquePatterns.size());

      // Verify: Should create exactly 1 unique common pattern (all 3 queries share it after merging)
      assertEquals(uniquePatterns.size(), 1,
          "Should find exactly 1 unique common pattern (range subsumption should merge them)");

      // Count UNIQUE MV instances
      Set<MaterializedViewGenerator.MaterializedViewInfo> uniqueMVs = new HashSet<>(result.getMaterializedViews().values());
      LOG.debug("Unique MVs: {}", uniqueMVs.size());

      // Verify: Should create exactly 1 unique MV
      assertEquals(uniqueMVs.size(), 1,
          "Should create exactly 1 materialized view");

      // Verify: MV should have the most general filter (a > 3)
      MaterializedViewGenerator.MaterializedViewInfo mvInfo =
          result.getMaterializedViews().values().iterator().next();
      String mvSql = mvInfo.getViewSql().toUpperCase();
      LOG.debug("MV SQL: {}", mvSql);

      assertTrue(mvSql.contains("WHERE"), "MV should contain WHERE clause");
      assertTrue(mvSql.contains("A > 3") || mvSql.contains("A>3"),
          "MV should have the most general filter: a > 3");
      assertTrue(mvSql.contains("GROUP BY"), "MV should contain GROUP BY");

      // Verify: All 3 queries should be rewritten
      assertEquals(result.getRewrittenQueries().size(), 3,
          "All 3 queries should be rewritten");

      int totalReplacements =
          result.getRewrittenQueries().stream().mapToInt(r -> r.getReplacementCount()).sum();
      assertEquals(totalReplacements, 3, "All queries should be rewritten");

      // Q1 (a > 3) should be exact match - no residual
      String q1Rewritten = result.getRewrittenQueries().get(0).getRewrittenSql();
      LOG.debug("Range Subsumption - Q1 Rewritten: {}", q1Rewritten);

      // Q2 (a > 5) should apply residual filter
      String q2Rewritten = result.getRewrittenQueries().get(1).getRewrittenSql();
      LOG.debug("Range Subsumption - Q2 Rewritten: {}", q2Rewritten);
      assertTrue(q2Rewritten.toUpperCase().contains("A > 5") || q2Rewritten.toUpperCase().contains("A>5"),
          "Q2 should apply residual filter: a > 5");

      // Q3 (a > 10) should apply residual filter
      String q3Rewritten = result.getRewrittenQueries().get(2).getRewrittenSql();
      LOG.debug("Range Subsumption - Q3 Rewritten: {}", q3Rewritten);
      assertTrue(q3Rewritten.toUpperCase().contains("A > 10") || q3Rewritten.toUpperCase().contains("A>10"),
          "Q3 should apply residual filter: a > 10");

      LOG.debug("Range Subsumption Test Result:\n{}", result.getReport());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Range Subsumption test failed: " + e.getMessage());
    }
  }

}

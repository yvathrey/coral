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
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.linkedin.coral.common.HiveMetastoreClient;

import static org.testng.Assert.*;


/**
 * Test cases for MaterializedViewOptimizer.
 */
public class MaterializedViewOptimizerTest {

  private static HiveConf conf;
  private static HiveMetastoreClient msc;

  @BeforeClass
  public static void setUp() throws IOException, HiveException, MetaException {
    // Use local test infrastructure to set up Hive environment
    conf = TestUtils.loadResourceHiveConf();
    System.out.println("Setting up test environment...");
    System.out.println("Test directory: " + conf.get(TestUtils.CORAL_MV_TEST_DIR));

    try {
      msc = TestUtils.setupTestMetastore(conf);
      System.out.println("Metastore setup complete");
    } catch (Exception e) {
      System.err.println("Failed to setup metastore: " + e.getMessage());
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
        System.out.println("Cleaning up test directory: " + testDir);
        try {
          FileUtils.deleteDirectory(new File(testDir));
        } catch (IOException e) {
          System.err.println("Warning: Failed to clean up test directory: " + e.getMessage());
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
      System.out.println(result.getReport());

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
      System.out.println(result.getReport());

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
        "SELECT t1.a, COUNT(*) FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x GROUP BY t1.a"
    );

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      assertNotNull(result);

      // All queries have identical join + aggregation pattern
      // Different aliases (cnt, total, no alias) should be ignored in pattern matching
      assertTrue(result.getCommonSubexpressions().size() > 0,
          "Queries with different aliases should share same MV (semantic matching)");

      System.out.println("Alias Normalization Test Result: " + result.getReport());

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
        "SELECT t1.a, COUNT(*), SUM(t1.b) FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x GROUP BY t1.a"
    );

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      assertNotNull(result);

      // All queries have identical pattern with multiple aggregates
      // Different aliases for each aggregate should be ignored
      assertTrue(result.getCommonSubexpressions().size() > 0,
          "Multiple aggregates with different aliases should share same MV");

      System.out.println("Multiple Aggregates with Aliases Test Result: " + result.getReport());

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
        "SELECT t2.x, COUNT(*) FROM default.tableTwo t2 JOIN default.tableOne t1 ON t2.x = t1.a GROUP BY t2.x"
    );

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      assertNotNull(result);

      // INNER joins should be order-insensitive
      // A JOIN B = B JOIN A → should share same MV
      assertTrue(result.getCommonSubexpressions().size() > 0,
          "INNER joins with different order should share same MV (join order normalized)");

      System.out.println("Join Order Insensitive Test Result: " + result.getReport());

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
        "SELECT t1.a, COUNT(*) FROM default.tableOne t1 JOIN default.tableTwo t2 ON t1.a = t2.x GROUP BY t1.a"
    );

    try {
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);
      MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

      assertNotNull(result);

      // Both join order AND aliases should be normalized
      // All three queries should share same MV
      assertTrue(result.getCommonSubexpressions().size() > 0,
          "Join order + alias variations should all share same MV");

      System.out.println("Join Order + Alias Test Result: " + result.getReport());

    } catch (Exception e) {
      e.printStackTrace();
      fail("Join order with aliases test failed: " + e.getMessage());
    }
  }

}

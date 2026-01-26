/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import java.util.*;

import com.linkedin.coral.common.HiveMetastoreClient;


/**
 * Example demonstrating the usage of MaterializedViewOptimizer.
 *
 * This POC automatically:
 * 1. Analyzes multiple SQL queries
 * 2. Identifies common patterns (shared subqueries/joins)
 * 3. Generates materialized views for common patterns
 * 4. Rewrites queries to use the materialized views
 *
 * Example from requirements:
 *
 * Input queries:
 *   SELECT * FROM A JOIN B JOIN C
 *   SELECT * FROM A JOIN B JOIN D
 *   SELECT * FROM A JOIN B JOIN E
 *
 * Output:
 *   Materialized view: CREATE MATERIALIZED VIEW mv_common_0 AS SELECT * FROM A JOIN B
 *
 *   Rewritten queries:
 *   SELECT * FROM mv_common_0 JOIN C
 *   SELECT * FROM mv_common_0 JOIN D
 *   SELECT * FROM mv_common_0 JOIN E
 *
 * Note: This example shows API usage. To run this, you need to set up a Hive metastore
 * and pass an IMetaStoreClient to the MaterializedViewOptimizer constructor.
 * See the test classes for a working example with test setup.
 */
public class Example {

  public static void main(String[] args) {
    System.out.println("See test classes for working examples with Hive metastore setup.");
    System.out.println("This file demonstrates the API usage pattern.");

  }

  /**
   * Example API usage for basic join optimization.
   * Requires a HiveMetastoreClient to be set up first.
   */
  public static void demonstrateBasicOptimization(HiveMetastoreClient msc) throws Exception {
    System.out.println("=== Example 1: Basic Join Optimization ===\n");

    // Input: List of SQL queries
    List<String> queries = Arrays.asList("SELECT * FROM A JOIN B ON A.id = B.id JOIN C ON B.id = C.id",
        "SELECT * FROM A JOIN B ON A.id = B.id JOIN D ON B.id = D.id",
        "SELECT * FROM A JOIN B ON A.id = B.id JOIN E ON B.id = E.id");

    // Create optimizer with IMetaStoreClient
    MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);

    // Run optimization (minOccurrences = 2 means pattern must appear in at least 2 queries)
    MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

    // Print results
    System.out.println("Input Queries:");
    for (int i = 0; i < queries.size(); i++) {
      System.out.println((i + 1) + ". " + queries.get(i));
    }
    System.out.println("\n" + result.getReport());
  }

  /**
   * Example API usage with multiple common patterns.
   * Requires a HiveMetastoreClient to be set up first.
   */
  public static void demonstrateMultiplePatterns(HiveMetastoreClient msc) throws Exception {
    System.out.println("\n=== Example 2: Multiple Common Patterns ===\n");

    List<String> queries = Arrays.asList(
        // Pattern 1: A JOIN B (appears in queries 1-2)
        "SELECT * FROM A JOIN B ON A.id = B.id WHERE A.status = 'active'",
        "SELECT * FROM A JOIN B ON A.id = B.id WHERE A.date > '2024-01-01'",
        // Pattern 2: C JOIN D (appears in queries 3-4)
        "SELECT * FROM C JOIN D ON C.key = D.key WHERE C.type = 1",
        "SELECT * FROM C JOIN D ON C.key = D.key WHERE C.region = 'US'");

    MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);

    // This should create 2 materialized views: one for A JOIN B, one for C JOIN D
    MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(queries, 2);

    System.out.println("Input Queries:");
    for (int i = 0; i < queries.size(); i++) {
      System.out.println((i + 1) + ". " + queries.get(i));
    }
    System.out.println("\n" + result.getReport());
  }

  /**
   * Example usage programmatically accessing results.
   * Requires a HiveMetastoreClient to be set up first.
   */
  public static void programmaticExample(HiveMetastoreClient msc, List<String> sqlQueries) throws Exception {
    MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(msc);

    // Run optimization
    MaterializedViewOptimizer.OptimizationResult result = optimizer.optimize(sqlQueries);

    // Access materialized views
    Map<String, MaterializedViewGenerator.MaterializedViewInfo> materializedViews = result.getMaterializedViews();
    for (MaterializedViewGenerator.MaterializedViewInfo mvInfo : materializedViews.values()) {
      String mvName = mvInfo.getViewName();
      String mvSql = mvInfo.getViewSql();
      System.out.println("Materialized View: " + mvName);
      System.out.println("SQL: " + mvSql);
    }

    // Access rewritten queries
    List<QueryRewriter.RewriteResult> rewrittenQueries = result.getRewrittenQueries();
    for (int i = 0; i < rewrittenQueries.size(); i++) {
      QueryRewriter.RewriteResult rewriteResult = rewrittenQueries.get(i);
      System.out.println("Rewritten Query " + (i + 1) + ": " + rewriteResult.getRewrittenSql());
      System.out.println("Number of replacements: " + rewriteResult.getReplacementCount());
    }
  }
}

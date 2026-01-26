/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import java.util.*;

import org.apache.calcite.rel.RelNode;

import com.linkedin.coral.common.HiveMetastoreClient;
import com.linkedin.coral.hive.hive2rel.HiveToRelConverter;


/**
 * Main orchestrator for materialized view optimization.
 *
 * This class coordinates the entire process:
 * 1. Parse SQL queries into RelNodes
 * 2. Find common subexpressions across queries
 * 3. Generate materialized views for common patterns
 * 4. Rewrite original queries to use materialized views
 *
 * Example usage:
 * <pre>
 * MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(hiveConf);
 * OptimizationResult result = optimizer.optimize(sqlQueries);
 * </pre>
 */
public class MaterializedViewOptimizer {

  private final HiveToRelConverter hiveToRelConverter;
  private final CommonSubexpressionFinder subexpressionFinder;
  private final MaterializedViewGenerator viewGenerator;
  private final QueryRewriter queryRewriter;

  /**
   * Create a new optimizer with a HiveMetastoreClient for parsing SQL.
   * Uses default string-based nested pattern filtering.
   */
  public MaterializedViewOptimizer(HiveMetastoreClient msc) {
    this(msc, CommonSubexpressionFinder.NestedPatternFilterStrategy.HASH_BASED);
  }

  /**
   * Create a new optimizer with a HiveMetastoreClient and specific filtering strategy.
   *
   * @param msc HiveMetastoreClient for parsing SQL
   * @param filterStrategy Strategy for filtering nested patterns
   */
  public MaterializedViewOptimizer(HiveMetastoreClient msc,
      CommonSubexpressionFinder.NestedPatternFilterStrategy filterStrategy) {
    this.hiveToRelConverter = new HiveToRelConverter(msc);
    this.subexpressionFinder = new CommonSubexpressionFinder(
        CommonSubexpressionFinder.PatternDetectionMode.JOINS_AND_AGGREGATES, filterStrategy);
    this.viewGenerator = new MaterializedViewGenerator();
    this.queryRewriter = new QueryRewriter(hiveToRelConverter);
  }

  /**
   * Optimize a list of SQL queries by finding common patterns and creating materialized views.
   *
   * @param sqlQueries List of SQL query strings to optimize
   * @return OptimizationResult containing materialized views and rewritten queries
   */
  public OptimizationResult optimize(List<String> sqlQueries) {
    return optimize(sqlQueries, 2); // Default: require at least 2 occurrences
  }

  /**
   * Optimize a list of SQL queries with a custom minimum occurrence threshold.
   *
   * @param sqlQueries List of SQL query strings to optimize
   * @param minOccurrences Minimum number of queries that must share a pattern for it to be materialized
   * @return OptimizationResult containing materialized views and rewritten queries
   */
  public OptimizationResult optimize(List<String> sqlQueries, int minOccurrences) {
    try {
      System.out.println("\n********** MATERIALIZED VIEW OPTIMIZER **********");
      System.out.println("Input: " + sqlQueries.size() + " queries, minOccurrences=" + minOccurrences);

      // Step 1: Parse SQL queries to RelNodes
      System.out.println("\n*** STEP 1: Parsing SQL to RelNodes ***");
      List<RelNode> queryPlans = new ArrayList<>();
      for (int i = 0; i < sqlQueries.size(); i++) {
        String sql = sqlQueries.get(i);
        System.out.println("Query " + i + ": " + sql);
        RelNode relNode = hiveToRelConverter.convertSql(sql);
        queryPlans.add(relNode);
        System.out.println("  -> Parsed successfully");
      }

      // Step 2: Find common subexpressions
      System.out.println("\n*** STEP 2: Finding Common Subexpressions ***");
      Map<String, CommonSubexpressionFinder.SubexpressionInfo> commonSubexpressions =
          subexpressionFinder.findCommonSubexpressions(queryPlans, minOccurrences);
      System.out.println("Found " + commonSubexpressions.size() + " common subexpressions");

      // Step 3: Generate materialized views for common subexpressions
      System.out.println("\n*** STEP 3: Generating Materialized Views ***");
      Map<String, MaterializedViewGenerator.MaterializedViewInfo> materializedViews = new HashMap<>();
      int mvIndex = 0;
      for (Map.Entry<String, CommonSubexpressionFinder.SubexpressionInfo> entry : commonSubexpressions.entrySet()) {
        System.out.println("\nGenerating MV " + mvIndex + "...");
        CommonSubexpressionFinder.SubexpressionInfo subexprInfo = entry.getValue();

        // HYBRID STRATEGY:
        // - Aggregations: Exact matching (standard MV captures exact query)
        // - Joins: Filter-agnostic matching (MV captures expensive join computation)
        MaterializedViewGenerator.MaterializedViewInfo mvInfo =
            viewGenerator.generateMaterializedView(subexprInfo.getRepresentativeNode());

        materializedViews.put(entry.getKey(), mvInfo);
        System.out.println("  Created: " + mvInfo.getViewName());
        System.out.println("  SQL: " + mvInfo.getViewSql());
        mvIndex++;
      }

      // Step 4: Rewrite queries to use materialized views
      System.out.println("\n*** STEP 4: Rewriting Queries ***");
      List<QueryRewriter.RewriteResult> rewrittenQueries = new ArrayList<>();
      for (int i = 0; i < queryPlans.size(); i++) {
        System.out.println("\n=== Rewriting Query " + i + " ===");
        RelNode queryPlan = queryPlans.get(i);
        QueryRewriter.RewriteResult rewriteResult =
            queryRewriter.rewriteQuery(queryPlan, commonSubexpressions, materializedViews);
        rewrittenQueries.add(rewriteResult);
        System.out.println("Rewrite complete. Replacements made: " + rewriteResult.getReplacementCount());
      }

      System.out.println("\n*** OPTIMIZATION COMPLETE ***");
      System.out.println("Total queries processed: " + sqlQueries.size());
      System.out.println("Materialized views created: " + materializedViews.size());
      int totalReplacements =
          rewrittenQueries.stream().mapToInt(QueryRewriter.RewriteResult::getReplacementCount).sum();
      System.out.println("Total replacements made: " + totalReplacements);
      System.out.println("**************************************************\n");

      return new OptimizationResult(sqlQueries, materializedViews, rewrittenQueries, commonSubexpressions);

    } catch (Exception e) {
      System.err.println("\n!!! OPTIMIZATION FAILED !!!");
      e.printStackTrace();
      throw new RuntimeException("Failed to optimize queries", e);
    }
  }

  /**
   * Result of the optimization process.
   */
  public static class OptimizationResult {
    private final List<String> originalQueries;
    private final Map<String, MaterializedViewGenerator.MaterializedViewInfo> materializedViews;
    private final List<QueryRewriter.RewriteResult> rewrittenQueries;
    private final Map<String, CommonSubexpressionFinder.SubexpressionInfo> commonSubexpressions;

    public OptimizationResult(List<String> originalQueries,
        Map<String, MaterializedViewGenerator.MaterializedViewInfo> materializedViews,
        List<QueryRewriter.RewriteResult> rewrittenQueries,
        Map<String, CommonSubexpressionFinder.SubexpressionInfo> commonSubexpressions) {
      this.originalQueries = originalQueries;
      this.materializedViews = materializedViews;
      this.rewrittenQueries = rewrittenQueries;
      this.commonSubexpressions = commonSubexpressions;
    }

    public List<String> getOriginalQueries() {
      return originalQueries;
    }

    public Map<String, MaterializedViewGenerator.MaterializedViewInfo> getMaterializedViews() {
      return materializedViews;
    }

    public List<QueryRewriter.RewriteResult> getRewrittenQueries() {
      return rewrittenQueries;
    }

    public Map<String, CommonSubexpressionFinder.SubexpressionInfo> getCommonSubexpressions() {
      return commonSubexpressions;
    }

    /**
     * Get a formatted report of the optimization results.
     */
    public String getReport() {
      StringBuilder report = new StringBuilder();
      report.append("=== Materialized View Optimization Report ===\n\n");

      report.append("Original Queries: ").append(originalQueries.size()).append("\n");
      report.append("Common Patterns Found: ").append(commonSubexpressions.size()).append("\n");
      report.append("Materialized Views Created: ").append(materializedViews.size()).append("\n\n");

      // Report materialized views
      if (!materializedViews.isEmpty()) {
        report.append("--- Materialized Views ---\n\n");
        int mvCount = 1;
        for (MaterializedViewGenerator.MaterializedViewInfo mvInfo : materializedViews.values()) {
          report.append(mvCount++).append(". ").append(mvInfo.toString()).append("\n\n");
        }
      }

      // Report rewritten queries
      report.append("--- Rewritten Queries ---\n\n");
      for (int i = 0; i < rewrittenQueries.size(); i++) {
        QueryRewriter.RewriteResult result = rewrittenQueries.get(i);
        report.append("Query ").append(i + 1).append(":\n");
        report.append("Original: ").append(originalQueries.get(i)).append("\n");
        report.append("Rewritten: ").append(result.getRewrittenSql()).append("\n");
        report.append("Replacements: ").append(result.getReplacementCount()).append("\n\n");
      }

      return report.toString();
    }

    @Override
    public String toString() {
      return getReport();
    }
  }
}

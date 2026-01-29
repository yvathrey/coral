/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import java.util.*;

import org.apache.calcite.rel.RelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger LOG = LoggerFactory.getLogger(MaterializedViewOptimizer.class);

  private final HiveToRelConverter hiveToRelConverter;
  private final CommonSubexpressionFinder subexpressionFinder;
  private final MaterializedViewGenerator viewGenerator;
  private final QueryRewriter queryRewriter;

  /**
   * Create a new optimizer with a HiveMetastoreClient for parsing SQL.
   */
  public MaterializedViewOptimizer(HiveMetastoreClient msc) {
    this.hiveToRelConverter = new HiveToRelConverter(msc);
    this.subexpressionFinder = new CommonSubexpressionFinder();
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
      LOG.debug("\n********** MATERIALIZED VIEW OPTIMIZER **********");
      LOG.debug("Input: {} queries, minOccurrences={}", sqlQueries.size(), minOccurrences);

      // Step 1: Parse SQL queries to RelNodes
      LOG.debug("\n*** STEP 1: Parsing SQL to RelNodes ***");
      List<RelNode> queryPlans = new ArrayList<>();
      for (int i = 0; i < sqlQueries.size(); i++) {
        String sql = sqlQueries.get(i);
        LOG.debug("Query {}: {}", i, sql);
        RelNode relNode = hiveToRelConverter.convertSql(sql);
        queryPlans.add(relNode);
        LOG.debug("  -> Parsed successfully");
      }

      // Step 2: Find common subexpressions
      LOG.debug("\n*** STEP 2: Finding Common Subexpressions ***");
      Map<String, CommonSubexpressionFinder.SubexpressionInfo> commonSubexpressions =
          subexpressionFinder.findCommonSubexpressions(queryPlans, minOccurrences);
      LOG.debug("Found {} common subexpressions", commonSubexpressions.size());

      // Step 3: Generate materialized views for common subexpressions
      LOG.debug("\n*** STEP 3: Generating Materialized Views ***");
      Map<String, MaterializedViewGenerator.MaterializedViewInfo> materializedViews = new HashMap<>();
      Set<CommonSubexpressionFinder.SubexpressionInfo> processedInfos = new HashSet<>();
      int mvIndex = 0;

      for (Map.Entry<String, CommonSubexpressionFinder.SubexpressionInfo> entry : commonSubexpressions.entrySet()) {
        CommonSubexpressionFinder.SubexpressionInfo subexprInfo = entry.getValue();

        // Skip if we've already generated an MV for this SubexpressionInfo
        // (multiple keys may point to the same merged pattern)
        if (processedInfos.contains(subexprInfo)) {
          LOG.debug("Skipping duplicate SubexpressionInfo (already processed)");
          // BUT still add to materializedViews map so lookup by this key works
          // Find the MV that was created for this SubexpressionInfo
          for (Map.Entry<String, MaterializedViewGenerator.MaterializedViewInfo> mvEntry : materializedViews.entrySet()) {
            if (commonSubexpressions.get(mvEntry.getKey()) == subexprInfo) {
              materializedViews.put(entry.getKey(), mvEntry.getValue());
              LOG.debug("Reusing MV: {} for key: {}", mvEntry.getValue().getViewName(),
                  entry.getKey().substring(0, Math.min(50, entry.getKey().length())));
              break;
            }
          }
          continue;
        }

        LOG.debug("\nGenerating MV {}...", mvIndex);

        // HYBRID STRATEGY:
        // - Aggregations: Exact matching (standard MV captures exact query)
        // - Joins: Filter-agnostic matching (MV captures expensive join computation)
        MaterializedViewGenerator.MaterializedViewInfo mvInfo =
            viewGenerator.generateMaterializedView(subexprInfo.getRepresentativeNode());

        materializedViews.put(entry.getKey(), mvInfo);
        processedInfos.add(subexprInfo);
        LOG.debug("  Created: {}", mvInfo.getViewName());
        LOG.debug("  SQL: {}", mvInfo.getViewSql());
        mvIndex++;
      }

      // Step 4: Rewrite queries to use materialized views
      LOG.debug("\n*** STEP 4: Rewriting Queries ***");
      List<QueryRewriter.RewriteResult> rewrittenQueries = new ArrayList<>();
      for (int i = 0; i < queryPlans.size(); i++) {
        LOG.debug("\n=== Rewriting Query {} ===", i);
        RelNode queryPlan = queryPlans.get(i);
        QueryRewriter.RewriteResult rewriteResult =
            queryRewriter.rewriteQuery(queryPlan, commonSubexpressions, materializedViews);
        rewrittenQueries.add(rewriteResult);
        LOG.debug("Rewrite complete. Replacements made: {}", rewriteResult.getReplacementCount());
      }

      LOG.debug("\n*** OPTIMIZATION COMPLETE ***");
      LOG.debug("Total queries processed: {}", sqlQueries.size());
      LOG.debug("Materialized views created: {}", materializedViews.size());
      int totalReplacements =
          rewrittenQueries.stream().mapToInt(QueryRewriter.RewriteResult::getReplacementCount).sum();
      LOG.debug("Total replacements made: {}", totalReplacements);
      LOG.debug("**************************************************\n");

      return new OptimizationResult(sqlQueries, materializedViews, rewrittenQueries, commonSubexpressions);

    } catch (Exception e) {
      LOG.error("!!! OPTIMIZATION FAILED !!!", e);
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

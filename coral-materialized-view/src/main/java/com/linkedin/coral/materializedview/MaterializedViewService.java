/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.calcite.rel.RelNode;

import com.linkedin.coral.common.HiveMetastoreClient;
import com.linkedin.coral.hive.hive2rel.HiveToRelConverter;
import com.linkedin.coral.materializedview.CommonSubexpressionFinder.SubexpressionInfo;
import com.linkedin.coral.materializedview.MaterializedViewGenerator.MaterializedViewInfo;
import com.linkedin.coral.materializedview.MaterializedViewOptimizer.OptimizationResult;
import com.linkedin.coral.materializedview.MaterializedViewRegistry.StoredMaterializedView;
import com.linkedin.coral.materializedview.QueryRewriter.RewriteResult;


/**
 * Main service for materialized view operations.
 *
 * This service provides high-level APIs for:
 * - Analyzing queries and creating materialized views (Stage 1)
 * - Rewriting queries to use materialized views (Stage 2)
 * - Managing the materialized view registry
 *
 * All core business logic is encapsulated here, keeping the REST controller lightweight.
 */
public class MaterializedViewService {

  private final HiveMetastoreClient metastoreClient;

  public MaterializedViewService(HiveMetastoreClient metastoreClient) {
    this.metastoreClient = metastoreClient;
  }

  /**
   * Analyze a list of queries to find common patterns and generate materialized views.
   *
   * This is Stage 1 (offline/batch) of the MV optimization system.
   *
   * @param queries List of SQL queries to analyze
   * @param minOccurrences Minimum number of occurrences for a pattern to be considered
   * @param registry Registry to store generated MVs
   * @return Analysis result with MVs and statistics
   * @throws Exception if analysis fails
   */
  public AnalysisResult analyzeQueries(List<String> queries, int minOccurrences, MaterializedViewRegistry registry)
      throws Exception {

    long startTime = System.currentTimeMillis();

    // Create optimizer and run analysis (using HASH_BASED strategy)
    MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(metastoreClient,
        CommonSubexpressionFinder.NestedPatternFilterStrategy.HASH_BASED);
    OptimizationResult result = optimizer.optimize(queries, minOccurrences);

    // Store MVs in registry and build result
    List<MaterializedViewEntry> mvEntries = new ArrayList<>();
    for (Map.Entry<String, MaterializedViewInfo> entry : result.getMaterializedViews().entrySet()) {
      String patternHash = entry.getKey();
      MaterializedViewInfo mvInfo = entry.getValue();
      SubexpressionInfo subexprInfo = result.getCommonSubexpressions().get(patternHash);

      // Store in registry (with pattern for filter implication)
      registry.register(patternHash, mvInfo.getViewName(), mvInfo.getViewSql(), mvInfo.getOriginalNode());

      // Add to response
      int usedInQueries = subexprInfo != null ? subexprInfo.getOccurrenceCount() : 0;
      mvEntries.add(new MaterializedViewEntry(mvInfo.getViewName(), mvInfo.getViewSql(), patternHash, usedInQueries));
    }

    long endTime = System.currentTimeMillis();
    long analysisTimeMs = endTime - startTime;

    return new AnalysisResult(mvEntries, queries.size(), result.getCommonSubexpressions().size(), analysisTimeMs);
  }

  /**
   * Rewrite a single query to use materialized views if a matching pattern is found.
   *
   * This is Stage 2 (online/runtime) of the MV optimization system.
   *
   * @param query SQL query to rewrite
   * @param registry Registry containing available MVs
   * @return Rewrite result indicating if rewrite occurred and the rewritten query
   * @throws Exception if rewriting fails
   */
  public RewriteQueryResult rewriteQuery(String query, MaterializedViewRegistry registry) throws Exception {

    // Create a fresh converter for each rewrite to see latest metastore state
    HiveToRelConverter converter = new HiveToRelConverter(metastoreClient);

    // Parse query to RelNode
    RelNode queryPlan = converter.convertSql(query);

    // Find matching pattern in registry
    PatternMatcher matcher = new PatternMatcher(registry);
    PatternMatcher.MatchResult matchResult = matcher.findMatchingPattern(queryPlan);

    if (matchResult.hasMatch()) {
      // Record usage
      StoredMaterializedView matchedMV = matchResult.getMatchedMV();
      registry.recordUsage(matchResult.getPatternHash());

      // Build subexpression and MV maps for QueryRewriter
      Map<String, SubexpressionInfo> subexprMap = new HashMap<>();
      subexprMap.put(matchResult.getPatternHash(),
          new SubexpressionInfo(matchResult.getMatchedNode(), matchResult.getPatternHash()));

      Map<String, MaterializedViewInfo> mvMap = new HashMap<>();
      mvMap.put(matchResult.getPatternHash(),
          new MaterializedViewInfo(matchedMV.getViewName(), matchedMV.getViewSql(), matchedMV.getPattern()));

      // Rewrite the query
      QueryRewriter rewriter = new QueryRewriter(converter);
      RewriteResult rewriteResult = rewriter.rewriteQuery(queryPlan, subexprMap, mvMap);

      return new RewriteQueryResult(true, query, rewriteResult.getRewrittenSql(), matchedMV.getViewName(),
          matchResult.getPatternHash(), rewriteResult.getReplacementCount());
    } else {
      // No match found
      return new RewriteQueryResult(false, query, query, null, null, 0, matchResult.getQueryPatternHash());
    }
  }

  /**
   * Get current status of the materialized view registry.
   *
   * @param registry Registry to query
   * @return Registry status with all MVs
   */
  public RegistryStatus getRegistryStatus(MaterializedViewRegistry registry) {
    List<MaterializedViewStatus> mvStatuses = new ArrayList<>();

    for (StoredMaterializedView mv : registry.getAll()) {
      mvStatuses.add(new MaterializedViewStatus(mv.getViewName(), mv.getPatternHash(), mv.getUsageCount(),
          mv.getCreatedAt(), mv.getLastUsedAt()));
    }

    return new RegistryStatus(registry.size(), mvStatuses);
  }

  /**
   * Clear all materialized views from the registry.
   *
   * @param registry Registry to clear
   * @return Number of MVs removed
   */
  public int clearRegistry(MaterializedViewRegistry registry) {
    return registry.clear();
  }

  // ============================================================================
  // Result Classes
  // ============================================================================

  /**
   * Result of analyzing queries (Stage 1).
   */
  public static class AnalysisResult {
    private final List<MaterializedViewEntry> materializedViews;
    private final int queriesAnalyzed;
    private final int patternsFound;
    private final long analysisTimeMs;

    public AnalysisResult(List<MaterializedViewEntry> materializedViews, int queriesAnalyzed, int patternsFound,
        long analysisTimeMs) {
      this.materializedViews = materializedViews;
      this.queriesAnalyzed = queriesAnalyzed;
      this.patternsFound = patternsFound;
      this.analysisTimeMs = analysisTimeMs;
    }

    public List<MaterializedViewEntry> getMaterializedViews() {
      return materializedViews;
    }

    public int getQueriesAnalyzed() {
      return queriesAnalyzed;
    }

    public int getPatternsFound() {
      return patternsFound;
    }

    public long getAnalysisTimeMs() {
      return analysisTimeMs;
    }
  }

  /**
   * Metadata for a single materialized view.
   */
  public static class MaterializedViewEntry {
    private final String viewName;
    private final String viewSql;
    private final String patternHash;
    private final int usedInQueries;

    public MaterializedViewEntry(String viewName, String viewSql, String patternHash, int usedInQueries) {
      this.viewName = viewName;
      this.viewSql = viewSql;
      this.patternHash = patternHash;
      this.usedInQueries = usedInQueries;
    }

    public String getViewName() {
      return viewName;
    }

    public String getViewSql() {
      return viewSql;
    }

    public String getPatternHash() {
      return patternHash;
    }

    public int getUsedInQueries() {
      return usedInQueries;
    }
  }

  /**
   * Result of rewriting a query (Stage 2).
   */
  public static class RewriteQueryResult {
    private final boolean matched;
    private final String originalQuery;
    private final String rewrittenQuery;
    private final String mvUsed;
    private final String patternHash;
    private final int replacementCount;
    private final String queryPatternHash;

    public RewriteQueryResult(boolean matched, String originalQuery, String rewrittenQuery, String mvUsed,
        String patternHash, int replacementCount) {
      this(matched, originalQuery, rewrittenQuery, mvUsed, patternHash, replacementCount, null);
    }

    public RewriteQueryResult(boolean matched, String originalQuery, String rewrittenQuery, String mvUsed,
        String patternHash, int replacementCount, String queryPatternHash) {
      this.matched = matched;
      this.originalQuery = originalQuery;
      this.rewrittenQuery = rewrittenQuery;
      this.mvUsed = mvUsed;
      this.patternHash = patternHash;
      this.replacementCount = replacementCount;
      this.queryPatternHash = queryPatternHash;
    }

    public boolean isMatched() {
      return matched;
    }

    public String getOriginalQuery() {
      return originalQuery;
    }

    public String getRewrittenQuery() {
      return rewrittenQuery;
    }

    public String getMvUsed() {
      return mvUsed;
    }

    public String getPatternHash() {
      return patternHash;
    }

    public int getReplacementCount() {
      return replacementCount;
    }

    public String getQueryPatternHash() {
      return queryPatternHash;
    }
  }

  /**
   * Status of the materialized view registry.
   */
  public static class RegistryStatus {
    private final int totalMVs;
    private final List<MaterializedViewStatus> mvs;

    public RegistryStatus(int totalMVs, List<MaterializedViewStatus> mvs) {
      this.totalMVs = totalMVs;
      this.mvs = mvs;
    }

    public int getTotalMVs() {
      return totalMVs;
    }

    public List<MaterializedViewStatus> getMvs() {
      return mvs;
    }
  }

  /**
   * Status of a single materialized view.
   */
  public static class MaterializedViewStatus {
    private final String viewName;
    private final String patternHash;
    private final int usageCount;
    private final java.time.Instant createdAt;
    private final java.time.Instant lastUsedAt;

    public MaterializedViewStatus(String viewName, String patternHash, int usageCount, java.time.Instant createdAt,
        java.time.Instant lastUsedAt) {
      this.viewName = viewName;
      this.patternHash = patternHash;
      this.usageCount = usageCount;
      this.createdAt = createdAt;
      this.lastUsedAt = lastUsedAt;
    }

    public String getViewName() {
      return viewName;
    }

    public String getPatternHash() {
      return patternHash;
    }

    public int getUsageCount() {
      return usageCount;
    }

    public java.time.Instant getCreatedAt() {
      return createdAt;
    }

    public java.time.Instant getLastUsedAt() {
      return lastUsedAt;
    }
  }
}

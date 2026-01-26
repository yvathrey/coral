/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.calcite.rel.RelNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.linkedin.coral.coralservice.entity.AnalyzeMVRequest;
import com.linkedin.coral.coralservice.entity.AnalyzeMVResponse;
import com.linkedin.coral.coralservice.entity.MaterializedViewOptimizationRequestBody;
import com.linkedin.coral.coralservice.entity.MaterializedViewOptimizationResponseBody;
import com.linkedin.coral.coralservice.entity.MaterializedViewOptimizationResponseBody.MaterializedView;
import com.linkedin.coral.coralservice.entity.MaterializedViewOptimizationResponseBody.OptimizationStats;
import com.linkedin.coral.coralservice.entity.MaterializedViewOptimizationResponseBody.RewrittenQuery;
import com.linkedin.coral.coralservice.entity.RewriteQueryRequest;
import com.linkedin.coral.coralservice.entity.RewriteQueryResponse;
import com.linkedin.coral.coralservice.entity.StoredMaterializedView;
import com.linkedin.coral.hive.hive2rel.HiveToRelConverter;
import com.linkedin.coral.materializedview.CommonSubexpressionFinder;
import com.linkedin.coral.materializedview.MaterializedViewGenerator;
import com.linkedin.coral.materializedview.MaterializedViewOptimizer;
import com.linkedin.coral.materializedview.QueryRewriter;

import static com.linkedin.coral.coralservice.utils.CoralProvider.*;


/**
 * REST controller for materialized view optimization endpoint.
 *
 * Provides API to optimize SQL queries by detecting common patterns
 * and generating materialized views automatically.
 */
@RestController
@Service
@Profile({ "localMetastore", "remoteMetastore", "default" })
@CrossOrigin(origins = CORAL_SERVICE_FRONTEND_URL)
public class MaterializedViewController implements ApplicationListener<ContextRefreshedEvent> {

  @Value("${hivePropsLocation:}")
  private String hivePropsLocation;

  /**
   * In-memory registry of materialized views (for demo purposes).
   * In production, this would be a persistent database.
   *
   * Key: Pattern hash
   * Value: Stored materialized view
   */
  private static final Map<String, StoredMaterializedView> mvRegistry = new ConcurrentHashMap<>();

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    // runs after the Spring context has been initialized
    try {
      initHiveMetastoreClient(hivePropsLocation);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Endpoint to optimize SQL queries using materialized views.
   *
   * POST /api/materialized-views/optimize
   *
   * Request body:
   * {
   *   "queries": ["SELECT * FROM A JOIN B JOIN C", ...],
   *   "minOccurrences": 2,          // Optional, default: 2
   *   "sourceLanguage": "hive"      // Optional, default: "hive"
   * }
   *
   * Response:
   * {
   *   "success": true,
   *   "originalQueries": [...],
   *   "materializedViews": [{viewName, viewSql, usedInQueries}],
   *   "rewrittenQueries": [{queryIndex, originalQuery, rewrittenQuery, replacementCount}],
   *   "stats": {totalQueries, commonPatternsFound, materializedViewsCreated, totalReplacements}
   * }
   */
  @PostMapping("/api/materialized-views/optimize")
  public ResponseEntity<MaterializedViewOptimizationResponseBody> optimize(
      @RequestBody MaterializedViewOptimizationRequestBody request) {

    try {
      // Validate request
      if (request.getQueries() == null || request.getQueries().isEmpty()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(MaterializedViewOptimizationResponseBody.error("Queries list cannot be empty"));
      }

      if (request.getQueries().size() < 2) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(MaterializedViewOptimizationResponseBody
            .error("At least 2 queries required for optimization. Received: " + request.getQueries().size()));
      }

      // Create optimizer (always using HASH_BASED strategy)
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(hiveMetastoreClient,
          CommonSubexpressionFinder.NestedPatternFilterStrategy.HASH_BASED);

      // Run optimization
      MaterializedViewOptimizer.OptimizationResult result =
          optimizer.optimize(request.getQueries(), request.getMinOccurrences());

      // Build response
      MaterializedViewOptimizationResponseBody response = buildResponse(request.getQueries(), result);

      return ResponseEntity.status(HttpStatus.OK).body(response);

    } catch (Exception e) {
      e.printStackTrace();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(MaterializedViewOptimizationResponseBody.error("Optimization failed: " + e.getMessage()));
    }
  }

  /**
   * Build response from optimization result.
   */
  private MaterializedViewOptimizationResponseBody buildResponse(List<String> originalQueries,
      MaterializedViewOptimizer.OptimizationResult result) {

    // Extract materialized views
    List<MaterializedView> materializedViews = new ArrayList<>();
    for (Map.Entry<String, MaterializedViewGenerator.MaterializedViewInfo> entry : result.getMaterializedViews()
        .entrySet()) {
      MaterializedViewGenerator.MaterializedViewInfo mvInfo = entry.getValue();
      CommonSubexpressionFinder.SubexpressionInfo subexprInfo = result.getCommonSubexpressions().get(entry.getKey());

      MaterializedView mv = new MaterializedView(mvInfo.getViewName(), mvInfo.getViewSql(),
          subexprInfo != null ? subexprInfo.getOccurrenceCount() : 0);
      materializedViews.add(mv);
    }

    // Extract rewritten queries
    List<RewrittenQuery> rewrittenQueries = new ArrayList<>();
    List<QueryRewriter.RewriteResult> rewriteResults = result.getRewrittenQueries();
    int totalReplacements = 0;

    for (int i = 0; i < rewriteResults.size(); i++) {
      QueryRewriter.RewriteResult rewriteResult = rewriteResults.get(i);
      RewrittenQuery rq = new RewrittenQuery(i, originalQueries.get(i), rewriteResult.getRewrittenSql(),
          rewriteResult.getReplacementCount());
      rewrittenQueries.add(rq);
      totalReplacements += rewriteResult.getReplacementCount();
    }

    // Build stats
    OptimizationStats stats = new OptimizationStats(originalQueries.size(), result.getCommonSubexpressions().size(),
        materializedViews.size(), totalReplacements);

    return new MaterializedViewOptimizationResponseBody(originalQueries, materializedViews, rewrittenQueries, stats);
  }

  // =========================================================================
  // TWO-STAGE API: Stage 1 (Pattern Analysis) + Stage 2 (Query Rewriting)
  // =========================================================================

  /**
   * POST /api/materialized-views/analyze
   * Simulates the nightly batch job that analyzes query logs,
   * finds common patterns, creates materialized views, and stores
   * them in the registry for Stage 2 to use.
   * Request body:
   * {
   *   "queries": ["SELECT...", "SELECT...", ...],
   *   "minOccurrences": 2
   * }
   * Response:
   * {
   *   "materializedViews": [{viewName, viewSql, patternHash, usedInQueries, estimatedSavings}],
   *   "stats": {queriesAnalyzed, patternsFound, materializedViewsCreated, estimatedDailySavings, analysisTimeMs},
   *   "registry": {totalMVs, storageLocation},
   *   "success": true
   * }
   */
  @PostMapping("/api/materialized-views/analyze")
  public ResponseEntity<AnalyzeMVResponse> analyze(@RequestBody AnalyzeMVRequest request) {

    try {
      long startTime = System.currentTimeMillis();

      // Validate request
      if (request.getQueries() == null || request.getQueries().isEmpty()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(AnalyzeMVResponse.error("Queries list cannot be empty"));
      }

      if (request.getQueries().size() < 2) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(AnalyzeMVResponse.error("At least 2 queries required for pattern analysis"));
      }

      // Create optimizer and run analysis (always using HASH_BASED strategy)
      MaterializedViewOptimizer optimizer = new MaterializedViewOptimizer(hiveMetastoreClient,
          CommonSubexpressionFinder.NestedPatternFilterStrategy.HASH_BASED);
      MaterializedViewOptimizer.OptimizationResult result =
          optimizer.optimize(request.getQueries(), request.getMinOccurrences());

      // Store MVs in registry
      List<AnalyzeMVResponse.MVRegistryEntry> registryEntries = new ArrayList<>();
      for (Map.Entry<String, MaterializedViewGenerator.MaterializedViewInfo> entry : result.getMaterializedViews()
          .entrySet()) {
        String patternHash = entry.getKey();
        MaterializedViewGenerator.MaterializedViewInfo mvInfo = entry.getValue();
        CommonSubexpressionFinder.SubexpressionInfo subexprInfo = result.getCommonSubexpressions().get(patternHash);

        // Store in registry
        StoredMaterializedView storedMV =
            new StoredMaterializedView(mvInfo.getViewName(), mvInfo.getViewSql(), patternHash);
        mvRegistry.put(patternHash, storedMV);

        // Add to response
        int usedInQueries = subexprInfo != null ? subexprInfo.getOccurrenceCount() : 0;
        AnalyzeMVResponse.MVRegistryEntry registryEntry = new AnalyzeMVResponse.MVRegistryEntry(mvInfo.getViewName(),
            mvInfo.getViewSql(), patternHash, usedInQueries);
        registryEntries.add(registryEntry);
      }

      long endTime = System.currentTimeMillis();
      long analysisTimeMs = endTime - startTime;

      // Build stats
      AnalyzeMVResponse.AnalysisStats stats = new AnalyzeMVResponse.AnalysisStats(request.getQueries().size(),
          result.getCommonSubexpressions().size(), registryEntries.size(), analysisTimeMs);

      // Build registry info
      AnalyzeMVResponse.RegistryInfo registryInfo = new AnalyzeMVResponse.RegistryInfo(mvRegistry.size(), "in-memory");

      return ResponseEntity.status(HttpStatus.OK).body(new AnalyzeMVResponse(registryEntries, stats, registryInfo));

    } catch (Exception e) {
      e.printStackTrace();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(AnalyzeMVResponse.error("Analysis failed: " + e.getMessage()));
    }
  }

  /**
   * Stage 2: Query Rewriting (Runtime/Online).
   *
   * POST /api/materialized-views/rewrite
   *
   * Simulates runtime query interception. Takes a single query,
   * checks if it matches any MV pattern in the registry, and
   * rewrites it if a match is found.
   *
   * Request body:
   * {
   *   "query": "SELECT..."
   * }
   *
   * Response:
   * {
   *   "matched": true/false,
   *   "originalQuery": "...",
   *   "rewrittenQuery": "...",
   *   "mvUsed": "mv_common_0",
   *   "patternHash": "...",
   *   "replacementCount": 1,
   *   "success": true
   * }
   */
  @PostMapping("/api/materialized-views/rewrite")
  public ResponseEntity<RewriteQueryResponse> rewrite(@RequestBody RewriteQueryRequest request) {

    try {
      // Validate request
      if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(RewriteQueryResponse.error("Query cannot be empty"));
      }

      String query = request.getQuery();

      // Parse query to RelNode
      HiveToRelConverter converter = new HiveToRelConverter(hiveMetastoreClient);
      RelNode queryPlan = converter.convertSql(query);

      // Traverse query tree to find patterns and check if any match registry
      PatternMatcher matcher = new PatternMatcher(mvRegistry);
      PatternMatcher.MatchResult matchResult = matcher.findMatchingPattern(queryPlan);

      if (matchResult.hasMatch()) {
        StoredMaterializedView matchedMV = matchResult.getMatchedMV();
        matchedMV.recordUsage();

        // Now rewrite the query using the MV from the registry
        // Create a subexpression map with the matched pattern
        Map<String, CommonSubexpressionFinder.SubexpressionInfo> subexprMap = new HashMap<>();
        subexprMap.put(matchResult.getPatternHash(), new CommonSubexpressionFinder.SubexpressionInfo(
            matchResult.getMatchedNode(), matchResult.getPatternHash()));

        // Create MV info map for the QueryRewriter
        Map<String, MaterializedViewGenerator.MaterializedViewInfo> mvMap = new HashMap<>();
        mvMap.put(matchResult.getPatternHash(), new MaterializedViewGenerator.MaterializedViewInfo(
            matchedMV.getViewName(), matchedMV.getViewSql(), matchResult.getMatchedNode()));

        // Rewrite the query
        QueryRewriter rewriter = new QueryRewriter(converter);
        QueryRewriter.RewriteResult rewriteResult = rewriter.rewriteQuery(queryPlan, subexprMap, mvMap);

        return ResponseEntity.status(HttpStatus.OK)
            .body(new RewriteQueryResponse(true, query, rewriteResult.getRewrittenSql(), matchedMV.getViewName(),
                matchResult.getPatternHash(), rewriteResult.getReplacementCount()));
      } else {
        // No match - return original query
        return ResponseEntity.status(HttpStatus.OK)
            .body(RewriteQueryResponse.noMatch(query, matchResult.getQueryPatternHash()));
      }

    } catch (Exception e) {
      e.printStackTrace();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(RewriteQueryResponse.error("Rewrite failed: " + e.getMessage()));
    }
  }

  /**
   * Get MV Registry Status.
   *
   * GET /api/materialized-views/registry
   *
   * Returns current state of the MV registry (for demo purposes).
   *
   * Response:
   * {
   *   "totalMVs": 5,
   *   "mvs": [
   *     {
   *       "viewName": "mv_common_0",
   *       "patternHash": "a7f2e9...",
   *       "usageCount": 127,
   *       "createdAt": "2024-01-24T02:00:00Z",
   *       "lastUsedAt": "2024-01-24T14:23:45Z"
   *     },
   *     ...
   *   ]
   * }
   */
  @GetMapping("/api/materialized-views/registry")
  public ResponseEntity<Map<String, Object>> getRegistryStatus() {
    try {
      List<Map<String, Object>> mvList = new ArrayList<>();

      for (StoredMaterializedView mv : mvRegistry.values()) {
        Map<String, Object> mvData = new HashMap<>();
        mvData.put("viewName", mv.getViewName());
        mvData.put("patternHash", mv.getPatternHash());
        mvData.put("usageCount", mv.getUsageCount());
        mvData.put("createdAt", mv.getCreatedAt());
        mvData.put("lastUsedAt", mv.getLastUsedAt());
        mvList.add(mvData);
      }

      Map<String, Object> response = new HashMap<>();
      response.put("totalMVs", mvRegistry.size());
      response.put("mvs", mvList);
      response.put("storageLocation", "in-memory");
      response.put("success", true);

      return ResponseEntity.status(HttpStatus.OK).body(response);

    } catch (Exception e) {
      Map<String, Object> error = new HashMap<>();
      error.put("success", false);
      error.put("errorMessage", "Failed to get registry status: " + e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
  }

  /**
   * Clear MV Registry.
   *
   * DELETE /api/materialized-views/registry
   *
   * Clears all materialized views from the registry (for demo reset).
   *
   * Response:
   * {
   *   "message": "MV registry cleared",
   *   "mvsRemoved": 5,
   *   "success": true
   * }
   */
  @DeleteMapping("/api/materialized-views/registry")
  public ResponseEntity<Map<String, Object>> clearRegistry() {
    try {
      int mvsRemoved = mvRegistry.size();
      mvRegistry.clear();

      Map<String, Object> response = new HashMap<>();
      response.put("message", "MV registry cleared");
      response.put("mvsRemoved", mvsRemoved);
      response.put("success", true);

      return ResponseEntity.status(HttpStatus.OK).body(response);

    } catch (Exception e) {
      Map<String, Object> error = new HashMap<>();
      error.put("success", false);
      error.put("errorMessage", "Failed to clear registry: " + e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
  }

  /**
   * Helper class to traverse a query tree and find patterns that match registry MVs.
   */
  private static class PatternMatcher extends org.apache.calcite.rel.RelShuttleImpl {
    private final Map<String, StoredMaterializedView> registry;
    private MatchResult result;

    PatternMatcher(Map<String, StoredMaterializedView> registry) {
      this.registry = registry;
      this.result = new MatchResult();
    }

    MatchResult findMatchingPattern(org.apache.calcite.rel.RelNode queryPlan) {
      // Traverse the tree to find matches
      queryPlan.accept(this);
      return result;
    }

    @Override
    public org.apache.calcite.rel.RelNode visit(org.apache.calcite.rel.RelNode other) {
      checkNode(other);
      return super.visit(other);
    }

    @Override
    public org.apache.calcite.rel.RelNode visit(org.apache.calcite.rel.logical.LogicalAggregate aggregate) {
      checkNode(aggregate);
      return super.visit(aggregate);
    }

    @Override
    public org.apache.calcite.rel.RelNode visit(org.apache.calcite.rel.logical.LogicalJoin join) {
      checkNode(join);
      return super.visit(join);
    }

    private void checkNode(org.apache.calcite.rel.RelNode node) {
      // Compute digest for this node using the same logic as CommonSubexpressionFinder
      String digest = computeDigest(node);

      // Check if this digest matches anything in the registry
      if (registry.containsKey(digest) && !result.hasMatch()) {
        // Found a match!
        result.setMatch(digest, registry.get(digest), node);
      }
    }

    private String computeDigest(org.apache.calcite.rel.RelNode node) {
      // Mirror the logic from CommonSubexpressionFinder.computeAggregationCoreDigest()
      if (node instanceof org.apache.calcite.rel.core.Aggregate) {
        org.apache.calcite.rel.core.Aggregate agg = (org.apache.calcite.rel.core.Aggregate) node;

        // Check if this aggregation has joins underneath
        boolean hasJoins = hasJoinBelow(agg);

        if (hasJoins) {
          // CASE 1: Aggregation on JOIN → Filter-agnostic matching
          StringBuilder coreDigest = new StringBuilder();
          coreDigest.append("AggregationCore[");
          coreDigest.append("groupSet=").append(agg.getGroupSet()).append(", ");
          coreDigest.append("aggCalls=").append(agg.getAggCallList()).append(", ");
          coreDigest.append("input=").append(computeInputDigestWithoutFilters(agg.getInput()));
          coreDigest.append("]");
          return coreDigest.toString();
        } else {
          // CASE 2: Single-table aggregation → Exact matching
          return org.apache.calcite.plan.RelOptUtil.toString(node);
        }
      }

      // For non-aggregation nodes, use exact digest
      return org.apache.calcite.plan.RelOptUtil.toString(node);
    }

    private boolean hasJoinBelow(org.apache.calcite.rel.RelNode node) {
      // Check if there's a JOIN anywhere in the subtree
      if (node instanceof org.apache.calcite.rel.core.Join) {
        return true;
      }

      for (org.apache.calcite.rel.RelNode input : node.getInputs()) {
        if (hasJoinBelow(input)) {
          return true;
        }
      }

      return false;
    }

    private String computeInputDigestWithoutFilters(org.apache.calcite.rel.RelNode node) {
      // Strip filters and compute structural digest (must match CommonSubexpressionFinder logic!)
      if (node instanceof org.apache.calcite.rel.logical.LogicalFilter) {
        // Skip the filter node and process its input
        return computeInputDigestWithoutFilters(((org.apache.calcite.rel.logical.LogicalFilter) node).getInput());
      }

      if (node instanceof org.apache.calcite.rel.core.Sort) {
        // Skip ORDER BY (Sort node), recurse on input
        // ORDER BY is presentational and doesn't affect computation
        return computeInputDigestWithoutFilters(node.getInput(0));
      }

      if (node instanceof org.apache.calcite.rel.core.TableScan) {
        // Base case: TableScan
        org.apache.calcite.rel.core.TableScan scan = (org.apache.calcite.rel.core.TableScan) node;
        return "TableScan[" + scan.getTable().getQualifiedName() + "]";
      }

      if (node instanceof org.apache.calcite.rel.core.Join) {
        // Include Join node with both inputs
        org.apache.calcite.rel.core.Join join = (org.apache.calcite.rel.core.Join) node;
        String leftDigest = computeInputDigestWithoutFilters(join.getLeft());
        String rightDigest = computeInputDigestWithoutFilters(join.getRight());
        return "Join[" + join.getJoinType() + ", left=" + leftDigest + ", right=" + rightDigest + "]";
      }

      // For all other node types, use class name and recurse
      StringBuilder sb = new StringBuilder();
      sb.append(node.getClass().getSimpleName()).append("[");
      for (int i = 0; i < node.getInputs().size(); i++) {
        if (i > 0)
          sb.append(", ");
        sb.append(computeInputDigestWithoutFilters(node.getInput(i)));
      }
      sb.append("]");
      return sb.toString();
    }

    static class MatchResult {
      private boolean hasMatch = false;
      private String patternHash;
      private StoredMaterializedView matchedMV;
      private org.apache.calcite.rel.RelNode matchedNode;
      private String queryPatternHash = "no-pattern";

      void setMatch(String patternHash, StoredMaterializedView mv, org.apache.calcite.rel.RelNode node) {
        this.hasMatch = true;
        this.patternHash = patternHash;
        this.matchedMV = mv;
        this.matchedNode = node;
      }

      boolean hasMatch() {
        return hasMatch;
      }

      String getPatternHash() {
        return patternHash;
      }

      StoredMaterializedView getMatchedMV() {
        return matchedMV;
      }

      org.apache.calcite.rel.RelNode getMatchedNode() {
        return matchedNode;
      }

      String getQueryPatternHash() {
        return queryPatternHash;
      }
    }
  }
}

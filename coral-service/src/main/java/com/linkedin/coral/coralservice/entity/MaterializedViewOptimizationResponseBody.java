/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.entity;

import java.util.List;


/**
 * Response body for materialized view optimization endpoint.
 *
 * Contains generated materialized views and rewritten queries.
 */
public class MaterializedViewOptimizationResponseBody {
  private List<String> originalQueries;
  private List<MaterializedView> materializedViews;
  private List<RewrittenQuery> rewrittenQueries;
  private OptimizationStats stats;
  private boolean success;
  private String errorMessage;

  public MaterializedViewOptimizationResponseBody() {
  }

  public MaterializedViewOptimizationResponseBody(List<String> originalQueries,
      List<MaterializedView> materializedViews, List<RewrittenQuery> rewrittenQueries, OptimizationStats stats) {
    this.originalQueries = originalQueries;
    this.materializedViews = materializedViews;
    this.rewrittenQueries = rewrittenQueries;
    this.stats = stats;
    this.success = true;
  }

  public static MaterializedViewOptimizationResponseBody error(String errorMessage) {
    MaterializedViewOptimizationResponseBody response = new MaterializedViewOptimizationResponseBody();
    response.success = false;
    response.errorMessage = errorMessage;
    return response;
  }

  // Getters and setters
  public List<String> getOriginalQueries() {
    return originalQueries;
  }

  public void setOriginalQueries(List<String> originalQueries) {
    this.originalQueries = originalQueries;
  }

  public List<MaterializedView> getMaterializedViews() {
    return materializedViews;
  }

  public void setMaterializedViews(List<MaterializedView> materializedViews) {
    this.materializedViews = materializedViews;
  }

  public List<RewrittenQuery> getRewrittenQueries() {
    return rewrittenQueries;
  }

  public void setRewrittenQueries(List<RewrittenQuery> rewrittenQueries) {
    this.rewrittenQueries = rewrittenQueries;
  }

  public OptimizationStats getStats() {
    return stats;
  }

  public void setStats(OptimizationStats stats) {
    this.stats = stats;
  }

  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  /**
   * Represents a generated materialized view.
   */
  public static class MaterializedView {
    private String viewName;
    private String viewSql;
    private int usedInQueries;

    public MaterializedView() {
    }

    public MaterializedView(String viewName, String viewSql, int usedInQueries) {
      this.viewName = viewName;
      this.viewSql = viewSql;
      this.usedInQueries = usedInQueries;
    }

    public String getViewName() {
      return viewName;
    }

    public void setViewName(String viewName) {
      this.viewName = viewName;
    }

    public String getViewSql() {
      return viewSql;
    }

    public void setViewSql(String viewSql) {
      this.viewSql = viewSql;
    }

    public int getUsedInQueries() {
      return usedInQueries;
    }

    public void setUsedInQueries(int usedInQueries) {
      this.usedInQueries = usedInQueries;
    }
  }

  /**
   * Represents a rewritten query.
   */
  public static class RewrittenQuery {
    private int queryIndex;
    private String originalQuery;
    private String rewrittenQuery;
    private int replacementCount;

    public RewrittenQuery() {
    }

    public RewrittenQuery(int queryIndex, String originalQuery, String rewrittenQuery, int replacementCount) {
      this.queryIndex = queryIndex;
      this.originalQuery = originalQuery;
      this.rewrittenQuery = rewrittenQuery;
      this.replacementCount = replacementCount;
    }

    public int getQueryIndex() {
      return queryIndex;
    }

    public void setQueryIndex(int queryIndex) {
      this.queryIndex = queryIndex;
    }

    public String getOriginalQuery() {
      return originalQuery;
    }

    public void setOriginalQuery(String originalQuery) {
      this.originalQuery = originalQuery;
    }

    public String getRewrittenQuery() {
      return rewrittenQuery;
    }

    public void setRewrittenQuery(String rewrittenQuery) {
      this.rewrittenQuery = rewrittenQuery;
    }

    public int getReplacementCount() {
      return replacementCount;
    }

    public void setReplacementCount(int replacementCount) {
      this.replacementCount = replacementCount;
    }
  }

  /**
   * Optimization statistics.
   */
  public static class OptimizationStats {
    private int totalQueries;
    private int commonPatternsFound;
    private int materializedViewsCreated;
    private int totalReplacements;

    public OptimizationStats() {
    }

    public OptimizationStats(int totalQueries, int commonPatternsFound, int materializedViewsCreated,
        int totalReplacements) {
      this.totalQueries = totalQueries;
      this.commonPatternsFound = commonPatternsFound;
      this.materializedViewsCreated = materializedViewsCreated;
      this.totalReplacements = totalReplacements;
    }

    public int getTotalQueries() {
      return totalQueries;
    }

    public void setTotalQueries(int totalQueries) {
      this.totalQueries = totalQueries;
    }

    public int getCommonPatternsFound() {
      return commonPatternsFound;
    }

    public void setCommonPatternsFound(int commonPatternsFound) {
      this.commonPatternsFound = commonPatternsFound;
    }

    public int getMaterializedViewsCreated() {
      return materializedViewsCreated;
    }

    public void setMaterializedViewsCreated(int materializedViewsCreated) {
      this.materializedViewsCreated = materializedViewsCreated;
    }

    public int getTotalReplacements() {
      return totalReplacements;
    }

    public void setTotalReplacements(int totalReplacements) {
      this.totalReplacements = totalReplacements;
    }
  }
}

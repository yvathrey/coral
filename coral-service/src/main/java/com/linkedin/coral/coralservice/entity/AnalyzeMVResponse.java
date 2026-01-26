/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.entity;

import java.util.List;


public class AnalyzeMVResponse {
  private List<MVRegistryEntry> materializedViews;
  private AnalysisStats stats;
  private RegistryInfo registry;
  private boolean success;
  private String errorMessage;

  public AnalyzeMVResponse() {
  }

  public AnalyzeMVResponse(List<MVRegistryEntry> materializedViews, AnalysisStats stats, RegistryInfo registry) {
    this.materializedViews = materializedViews;
    this.stats = stats;
    this.registry = registry;
    this.success = true;
  }

  public static AnalyzeMVResponse error(String errorMessage) {
    AnalyzeMVResponse response = new AnalyzeMVResponse();
    response.success = false;
    response.errorMessage = errorMessage;
    return response;
  }

  // Getters and setters
  public List<MVRegistryEntry> getMaterializedViews() {
    return materializedViews;
  }

  public void setMaterializedViews(List<MVRegistryEntry> materializedViews) {
    this.materializedViews = materializedViews;
  }

  public AnalysisStats getStats() {
    return stats;
  }

  public void setStats(AnalysisStats stats) {
    this.stats = stats;
  }

  public RegistryInfo getRegistry() {
    return registry;
  }

  public void setRegistry(RegistryInfo registry) {
    this.registry = registry;
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
   * Represents a materialized view entry in the registry.
   */
  public static class MVRegistryEntry {
    private String viewName;
    private String viewSql;
    private String patternHash;
    private int usedInQueries;

    public MVRegistryEntry() {
    }

    public MVRegistryEntry(String viewName, String viewSql, String patternHash, int usedInQueries) {
      this.viewName = viewName;
      this.viewSql = viewSql;
      this.patternHash = patternHash;
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

    public String getPatternHash() {
      return patternHash;
    }

    public void setPatternHash(String patternHash) {
      this.patternHash = patternHash;
    }

    public int getUsedInQueries() {
      return usedInQueries;
    }

    public void setUsedInQueries(int usedInQueries) {
      this.usedInQueries = usedInQueries;
    }
  }

  /**
   * Statistics from pattern analysis.
   */
  public static class AnalysisStats {
    private int queriesAnalyzed;
    private int patternsFound;
    private int materializedViewsCreated;
    private long analysisTimeMs;

    public AnalysisStats() {
    }

    public AnalysisStats(int queriesAnalyzed, int patternsFound, int materializedViewsCreated, long analysisTimeMs) {
      this.queriesAnalyzed = queriesAnalyzed;
      this.patternsFound = patternsFound;
      this.materializedViewsCreated = materializedViewsCreated;
      this.analysisTimeMs = analysisTimeMs;
    }

    public int getQueriesAnalyzed() {
      return queriesAnalyzed;
    }

    public void setQueriesAnalyzed(int queriesAnalyzed) {
      this.queriesAnalyzed = queriesAnalyzed;
    }

    public int getPatternsFound() {
      return patternsFound;
    }

    public void setPatternsFound(int patternsFound) {
      this.patternsFound = patternsFound;
    }

    public int getMaterializedViewsCreated() {
      return materializedViewsCreated;
    }

    public void setMaterializedViewsCreated(int materializedViewsCreated) {
      this.materializedViewsCreated = materializedViewsCreated;
    }

    public long getAnalysisTimeMs() {
      return analysisTimeMs;
    }

    public void setAnalysisTimeMs(long analysisTimeMs) {
      this.analysisTimeMs = analysisTimeMs;
    }
  }

  /**
   * Information about the MV registry.
   */
  public static class RegistryInfo {
    private int totalMVs;
    private String storageLocation;

    public RegistryInfo() {
    }

    public RegistryInfo(int totalMVs, String storageLocation) {
      this.totalMVs = totalMVs;
      this.storageLocation = storageLocation;
    }

    public int getTotalMVs() {
      return totalMVs;
    }

    public void setTotalMVs(int totalMVs) {
      this.totalMVs = totalMVs;
    }

    public String getStorageLocation() {
      return storageLocation;
    }

    public void setStorageLocation(String storageLocation) {
      this.storageLocation = storageLocation;
    }
  }
}

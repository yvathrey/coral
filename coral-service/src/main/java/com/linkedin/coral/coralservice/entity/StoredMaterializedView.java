/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.entity;

import java.time.Instant;


/**
 * Represents a materialized view stored in the in-memory registry.
 *
 * Used by Stage 2 to match queries and perform rewrites.
 */
public class StoredMaterializedView {
  private String viewName;
  private String viewSql;
  private String patternHash;
  private int usageCount;
  private Instant createdAt;
  private Instant lastUsedAt;

  public StoredMaterializedView() {
  }

  public StoredMaterializedView(String viewName, String viewSql, String patternHash) {
    this.viewName = viewName;
    this.viewSql = viewSql;
    this.patternHash = patternHash;
    this.usageCount = 0;
    this.createdAt = Instant.now();
  }

  /**
   * Record that this MV was used for query rewriting.
   */
  public void recordUsage() {
    this.usageCount++;
    this.lastUsedAt = Instant.now();
  }

  // Getters and setters
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

  public int getUsageCount() {
    return usageCount;
  }

  public void setUsageCount(int usageCount) {
    this.usageCount = usageCount;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public void setLastUsedAt(Instant lastUsedAt) {
    this.lastUsedAt = lastUsedAt;
  }
}

/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Thread-safe registry for storing and managing materialized views.
 *
 * This registry maintains an in-memory map of materialized views indexed by their pattern hash.
 * In production, this would be backed by a persistent database.
 */
public class MaterializedViewRegistry {

  /**
   * In-memory storage of materialized views.
   * Key: Pattern hash (digest of the query pattern)
   * Value: Stored materialized view metadata
   */
  private final Map<String, StoredMaterializedView> registry;

  public MaterializedViewRegistry() {
    this.registry = new ConcurrentHashMap<>();
  }

  /**
   * Register a new materialized view in the registry.
   *
   * @param patternHash Unique hash identifying the query pattern
   * @param viewName Name of the materialized view
   * @param viewSql SQL definition of the materialized view
   * @return The stored materialized view
   */
  public StoredMaterializedView register(String patternHash, String viewName, String viewSql) {
    StoredMaterializedView mv = new StoredMaterializedView(viewName, viewSql, patternHash);
    registry.put(patternHash, mv);
    return mv;
  }

  /**
   * Get a materialized view by its pattern hash.
   *
   * @param patternHash Pattern hash to lookup
   * @return Stored materialized view, or null if not found
   */
  public StoredMaterializedView get(String patternHash) {
    return registry.get(patternHash);
  }

  /**
   * Check if a pattern hash exists in the registry.
   *
   * @param patternHash Pattern hash to check
   * @return true if exists, false otherwise
   */
  public boolean contains(String patternHash) {
    return registry.containsKey(patternHash);
  }

  /**
   * Get all materialized views in the registry.
   *
   * @return Collection of all stored materialized views
   */
  public Collection<StoredMaterializedView> getAll() {
    return registry.values();
  }

  /**
   * Get the total number of materialized views in the registry.
   *
   * @return Count of materialized views
   */
  public int size() {
    return registry.size();
  }

  /**
   * Clear all materialized views from the registry.
   *
   * @return Number of materialized views removed
   */
  public int clear() {
    int size = registry.size();
    registry.clear();
    return size;
  }

  /**
   * Record that a materialized view was used.
   *
   * @param patternHash Pattern hash of the MV that was used
   */
  public void recordUsage(String patternHash) {
    StoredMaterializedView mv = registry.get(patternHash);
    if (mv != null) {
      mv.recordUsage();
    }
  }

  /**
   * Metadata for a stored materialized view.
   */
  public static class StoredMaterializedView {
    private final String viewName;
    private final String viewSql;
    private final String patternHash;
    private final Instant createdAt;
    private Instant lastUsedAt;
    private int usageCount;

    public StoredMaterializedView(String viewName, String viewSql, String patternHash) {
      this.viewName = viewName;
      this.viewSql = viewSql;
      this.patternHash = patternHash;
      this.createdAt = Instant.now();
      this.lastUsedAt = null;
      this.usageCount = 0;
    }

    public void recordUsage() {
      this.lastUsedAt = Instant.now();
      this.usageCount++;
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

    public Instant getCreatedAt() {
      return createdAt;
    }

    public Instant getLastUsedAt() {
      return lastUsedAt;
    }

    public int getUsageCount() {
      return usageCount;
    }
  }
}

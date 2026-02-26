/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.entity;

/**
 * Request body for Stage 2: Query Rewriting.
 *
 * Takes a single query and rewrites it using materialized views
 * from the registry (if a match is found).
 */
public class RewriteQueryRequest {
  private String query;

  public RewriteQueryRequest() {
  }

  public RewriteQueryRequest(String query) {
    this.query = query;
  }

  public String getQuery() {
    return query;
  }

  public void setQuery(String query) {
    this.query = query;
  }
}

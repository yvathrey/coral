/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.entity;

import java.util.List;


/**
 * Request body for materialized view optimization endpoint.
 *
 * Takes a list of SQL queries and returns optimized versions with materialized views.
 */
public class MaterializedViewOptimizationRequestBody {
  private List<String> queries;
  private Integer minOccurrences;
  private String sourceLanguage;

  public MaterializedViewOptimizationRequestBody() {
  }

  public MaterializedViewOptimizationRequestBody(List<String> queries, Integer minOccurrences, String sourceLanguage) {
    this.queries = queries;
    this.minOccurrences = minOccurrences;
    this.sourceLanguage = sourceLanguage;
  }

  public List<String> getQueries() {
    return queries;
  }

  public void setQueries(List<String> queries) {
    this.queries = queries;
  }

  public Integer getMinOccurrences() {
    return minOccurrences != null ? minOccurrences : 2;
  }

  public void setMinOccurrences(Integer minOccurrences) {
    this.minOccurrences = minOccurrences;
  }

  public String getSourceLanguage() {
    return sourceLanguage != null ? sourceLanguage : "hive";
  }

  public void setSourceLanguage(String sourceLanguage) {
    this.sourceLanguage = sourceLanguage;
  }
}

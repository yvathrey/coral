/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.entity;

import java.util.List;


public class AnalyzeMVRequest {
  private List<String> queries;
  private Integer minOccurrences;

  public AnalyzeMVRequest() {
  }

  public AnalyzeMVRequest(List<String> queries, Integer minOccurrences) {
    this.queries = queries;
    this.minOccurrences = minOccurrences;
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
}

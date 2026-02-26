/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.coralservice.entity;

/**
 * Response body for Stage 2: Query Rewriting.
 *
 * Contains the rewritten query (if match found) or original query (if no match).
 */
public class RewriteQueryResponse {
  private boolean matched;
  private String originalQuery;
  private String rewrittenQuery;
  private String mvUsed;
  private String patternHash;
  private int replacementCount;
  private boolean success;
  private String message;
  private String errorMessage;

  public RewriteQueryResponse() {
  }

  public RewriteQueryResponse(boolean matched, String originalQuery, String rewrittenQuery, String mvUsed,
      String patternHash, int replacementCount) {
    this.matched = matched;
    this.originalQuery = originalQuery;
    this.rewrittenQuery = rewrittenQuery;
    this.mvUsed = mvUsed;
    this.patternHash = patternHash;
    this.replacementCount = replacementCount;
    this.success = true;
  }

  public static RewriteQueryResponse noMatch(String originalQuery, String patternHash) {
    RewriteQueryResponse response = new RewriteQueryResponse();
    response.matched = false;
    response.originalQuery = originalQuery;
    response.rewrittenQuery = originalQuery;
    response.patternHash = patternHash;
    response.replacementCount = 0;
    response.message = "No matching MV found - executing original query";
    response.success = true;
    return response;
  }

  public static RewriteQueryResponse error(String errorMessage) {
    RewriteQueryResponse response = new RewriteQueryResponse();
    response.success = false;
    response.errorMessage = errorMessage;
    return response;
  }

  // Getters and setters
  public boolean isMatched() {
    return matched;
  }

  public void setMatched(boolean matched) {
    this.matched = matched;
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

  public String getMvUsed() {
    return mvUsed;
  }

  public void setMvUsed(String mvUsed) {
    this.mvUsed = mvUsed;
  }

  public String getPatternHash() {
    return patternHash;
  }

  public void setPatternHash(String patternHash) {
    this.patternHash = patternHash;
  }

  public int getReplacementCount() {
    return replacementCount;
  }

  public void setReplacementCount(int replacementCount) {
    this.replacementCount = replacementCount;
  }

  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}

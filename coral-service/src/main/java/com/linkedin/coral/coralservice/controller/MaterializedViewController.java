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
import com.linkedin.coral.coralservice.entity.RewriteQueryRequest;
import com.linkedin.coral.coralservice.entity.RewriteQueryResponse;
import com.linkedin.coral.materializedview.MaterializedViewRegistry;
import com.linkedin.coral.materializedview.MaterializedViewService;

import static com.linkedin.coral.coralservice.utils.CoralProvider.*;


/**
 * Lightweight REST controller for materialized view operations.
 *
 * This controller handles HTTP requests and delegates all business logic
 * to the MaterializedViewService in the coral-materialized-view module.
 *
 * Responsibilities:
 * - HTTP request/response handling
 * - DTO conversion
 * - Error handling
 * - Service orchestration
 *
 * All core logic is in coral-materialized-view module.
 */
@RestController
@Service
@Profile({ "localMetastore", "remoteMetastore", "default" })
@CrossOrigin(origins = CORAL_SERVICE_FRONTEND_URL)
public class MaterializedViewController implements ApplicationListener<ContextRefreshedEvent> {

  @Value("${hivePropsLocation:}")
  private String hivePropsLocation;

  // Core service (contains all business logic)
  private MaterializedViewService mvService;

  // MV registry (shared across requests)
  private final MaterializedViewRegistry registry = new MaterializedViewRegistry();

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    // Metastore client is initialized by TranslationControllerLocal or TranslationController
    // Service will be lazily initialized on first request
  }

  /**
   * Ensure the service is initialized before use.
   * Initializes lazily to avoid race conditions with metastore client initialization.
   */
  private void ensureServiceInitialized() {
    if (mvService == null && hiveMetastoreClient != null) {
      synchronized (this) {
        if (mvService == null && hiveMetastoreClient != null) {
          this.mvService = new MaterializedViewService(hiveMetastoreClient);
        }
      }
    }
  }

  /**
   * POST /api/materialized-views/analyze
   *
   * Analyze queries and create materialized views (Stage 1 - Offline/Batch).
   */
  @PostMapping("/api/materialized-views/analyze")
  public ResponseEntity<AnalyzeMVResponse> analyze(@RequestBody AnalyzeMVRequest request) {

    try {
      // Ensure service is initialized
      ensureServiceInitialized();
      if (mvService == null) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(AnalyzeMVResponse.error("Service not initialized - metastore client unavailable"));
      }

      // Validate request
      if (request.getQueries() == null || request.getQueries().isEmpty()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(AnalyzeMVResponse.error("Queries list cannot be empty"));
      }

      if (request.getQueries().size() < 2) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(AnalyzeMVResponse.error("At least 2 queries required for pattern analysis"));
      }

      // Delegate to service
      MaterializedViewService.AnalysisResult result =
          mvService.analyzeQueries(request.getQueries(), request.getMinOccurrences(), registry);

      // Convert service result to DTO
      List<AnalyzeMVResponse.MVRegistryEntry> entries = new ArrayList<>();
      for (MaterializedViewService.MaterializedViewEntry entry : result.getMaterializedViews()) {
        entries.add(new AnalyzeMVResponse.MVRegistryEntry(entry.getViewName(), entry.getViewSql(),
            entry.getPatternHash(), entry.getUsedInQueries()));
      }

      AnalyzeMVResponse.AnalysisStats stats = new AnalyzeMVResponse.AnalysisStats(result.getQueriesAnalyzed(),
          result.getPatternsFound(), result.getMaterializedViews().size(), result.getAnalysisTimeMs());

      AnalyzeMVResponse.RegistryInfo registryInfo = new AnalyzeMVResponse.RegistryInfo(registry.size(), "in-memory");

      return ResponseEntity.status(HttpStatus.OK).body(new AnalyzeMVResponse(entries, stats, registryInfo));

    } catch (Exception e) {
      e.printStackTrace();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(AnalyzeMVResponse.error("Analysis failed: " + e.getMessage()));
    }
  }

  /**
   * POST /api/materialized-views/rewrite
   *
   * Rewrite a query to use materialized views (Stage 2 - Online/Runtime).
   */
  @PostMapping("/api/materialized-views/rewrite")
  public ResponseEntity<RewriteQueryResponse> rewrite(@RequestBody RewriteQueryRequest request) {

    try {
      // Ensure service is initialized
      ensureServiceInitialized();
      if (mvService == null) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(RewriteQueryResponse.error("Service not initialized - metastore client unavailable"));
      }

      // Validate request
      if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(RewriteQueryResponse.error("Query cannot be empty"));
      }

      // Delegate to service
      MaterializedViewService.RewriteQueryResult result = mvService.rewriteQuery(request.getQuery(), registry);

      // Convert service result to DTO
      if (result.isMatched()) {
        return ResponseEntity.status(HttpStatus.OK).body(new RewriteQueryResponse(true, result.getOriginalQuery(),
            result.getRewrittenQuery(), result.getMvUsed(), result.getPatternHash(), result.getReplacementCount()));
      } else {
        return ResponseEntity.status(HttpStatus.OK)
            .body(RewriteQueryResponse.noMatch(result.getOriginalQuery(), result.getQueryPatternHash()));
      }

    } catch (Exception e) {
      e.printStackTrace();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(RewriteQueryResponse.error("Rewrite failed: " + e.getMessage()));
    }
  }

  /**
   * GET /api/materialized-views/registry
   *
   * Get status of the materialized view registry.
   */
  @GetMapping("/api/materialized-views/registry")
  public ResponseEntity<Map<String, Object>> getRegistryStatus() {
    try {
      // Ensure service is initialized
      ensureServiceInitialized();
      if (mvService == null) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("errorMessage", "Service not initialized - metastore client unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
      }

      // Delegate to service
      MaterializedViewService.RegistryStatus status = mvService.getRegistryStatus(registry);

      // Convert service result to DTO
      List<Map<String, Object>> mvList = new ArrayList<>();
      for (MaterializedViewService.MaterializedViewStatus mv : status.getMvs()) {
        Map<String, Object> mvData = new HashMap<>();
        mvData.put("viewName", mv.getViewName());
        mvData.put("patternHash", mv.getPatternHash());
        mvData.put("usageCount", mv.getUsageCount());
        mvData.put("createdAt", mv.getCreatedAt());
        mvData.put("lastUsedAt", mv.getLastUsedAt());
        mvList.add(mvData);
      }

      Map<String, Object> response = new HashMap<>();
      response.put("totalMVs", status.getTotalMVs());
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
   * DELETE /api/materialized-views/registry
   *
   * Clear all materialized views from the registry.
   */
  @DeleteMapping("/api/materialized-views/registry")
  public ResponseEntity<Map<String, Object>> clearRegistry() {
    try {
      // Ensure service is initialized
      ensureServiceInitialized();
      if (mvService == null) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("errorMessage", "Service not initialized - metastore client unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
      }

      // Delegate to service
      int mvsRemoved = mvService.clearRegistry(registry);

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
}

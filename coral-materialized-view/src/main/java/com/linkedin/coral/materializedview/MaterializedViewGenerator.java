/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import java.util.Set;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.rel2sql.RelToSqlConverter;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Generates SQL for materialized views based on common subexpressions.
 */
public class MaterializedViewGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(MaterializedViewGenerator.class);
  private int viewCounter = 0;

  /**
   * Generate a materialized view definition for a given RelNode.
   *
   * @param subexpression The RelNode representing the common subexpression
   * @return MaterializedViewInfo containing the view name and SQL definition
   */
  public MaterializedViewInfo generateMaterializedView(RelNode subexpression) {
    return generateMaterializedView(subexpression, null);
  }

  /**
   * Generate a materialized view (standard - no enhancement).
   *
   * HYBRID STRATEGY:
   * - Aggregations on Joins: Materialize ONLY the join (no GROUP BY)
   *   → Allows queries with different WHERE clauses to share the expensive join
   *   → Queries apply their own GROUP BY on top of the MV
   * - Single-table Aggregations: Materialize with GROUP BY (exact matching)
   *   → Safe and correct for single-table queries
   *
   * @param subexpression The pattern to materialize
   * @param filterColumnIndices Ignored (no partial aggregation for now)
   * @return MaterializedViewInfo
   */
  public MaterializedViewInfo generateMaterializedView(RelNode subexpression, Set<Integer> filterColumnIndices) {
    String viewName = generateViewName();

    // Determine what to materialize
    RelNode mvNode = subexpression;
    boolean isAggregationOnJoin = false;

    // Check if this is an aggregation on joins pattern
    if (isAggregationPattern(subexpression)) {
      Aggregate agg = extractAggregate(subexpression);
      if (agg != null && hasJoinBelow(agg)) {
        // CRITICAL FIX: For aggregations on joins, materialize ONLY the join
        // This allows queries with different WHERE clauses to share the MV
        LOG.debug("Detected aggregation on join pattern - extracting join for MV");
        mvNode = extractJoinInput(agg);
        isAggregationOnJoin = true;
      }
    }

    // Generate SQL for the MV
    String viewSql = generateViewSql(mvNode);

    LOG.debug("Generated MV: {}", viewName);
    LOG.debug("  Is aggregation on join: {}", isAggregationOnJoin);
    LOG.debug("  MV SQL: {}", viewSql);

    return new MaterializedViewInfo(viewName, viewSql, subexpression, isAggregationOnJoin);
  }

  /**
   * Check if a RelNode pattern is an aggregation pattern.
   * Aggregation patterns have Aggregate at the top or near the top (with Filter/Project on top).
   */
  private boolean isAggregationPattern(RelNode node) {
    if (node instanceof Aggregate) {
      return true;
    }
    // Check through Filter/Project/Sort wrappers
    if (node instanceof Filter || node instanceof Project || node instanceof Sort) {
      for (RelNode child : node.getInputs()) {
        if (child instanceof Aggregate) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Extract the Aggregate node from a pattern (may be wrapped by Filter/Project/Sort).
   */
  private Aggregate extractAggregate(RelNode node) {
    if (node instanceof Aggregate) {
      return (Aggregate) node;
    }
    // Check through wrappers
    if (node instanceof Filter || node instanceof Project || node instanceof Sort) {
      for (RelNode child : node.getInputs()) {
        if (child instanceof Aggregate) {
          return (Aggregate) child;
        }
        // Recursively check one more level
        Aggregate agg = extractAggregate(child);
        if (agg != null) {
          return agg;
        }
      }
    }
    return null;
  }

  /**
   * Check if a node has joins in its subtree.
   */
  private boolean hasJoinBelow(RelNode node) {
    for (RelNode input : node.getInputs()) {
      if (input instanceof Join) {
        return true;
      }
      // Check recursively (limit depth to avoid excessive checking)
      if (hasJoinBelowRecursive(input, 3)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Recursive helper with depth limit.
   */
  private boolean hasJoinBelowRecursive(RelNode node, int maxDepth) {
    if (maxDepth <= 0) {
      return false;
    }
    if (node instanceof Join) {
      return true;
    }
    for (RelNode input : node.getInputs()) {
      if (hasJoinBelowRecursive(input, maxDepth - 1)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Extract the join input from an Aggregate node.
   * Skips Filter/Project nodes between Aggregate and Join.
   */
  private RelNode extractJoinInput(Aggregate agg) {
    RelNode input = agg.getInput();

    // Skip through Filter and Project nodes to get to the join
    while (input instanceof Filter || input instanceof Project) {
      input = input.getInput(0);
    }

    LOG.debug("Extracted join input from aggregate");
    LOG.debug("  Aggregate node type: {}", agg.getClass().getSimpleName());
    LOG.debug("  Join input type: {}", input.getClass().getSimpleName());

    return input;
  }

  /**
   * Generate a unique view name.
   */
  private String generateViewName() {
    return "mv_common_" + (viewCounter++);
  }

  /**
   * Convert a RelNode to SQL for the materialized view definition.
   */
  private String generateViewSql(RelNode relNode) {
    try {
      // Convert RelNode to SQL using Calcite's RelToSqlConverter
      SqlDialect dialect = SqlDialect.DatabaseProduct.HIVE.getDialect();
      RelToSqlConverter converter = new RelToSqlConverter(dialect);
      SqlNode sqlNode = converter.visitChild(0, relNode).asStatement();
      return sqlNode.toSqlString(dialect).getSql();
    } catch (Exception e) {
      // Fallback to simple string representation
      return "-- Generated from RelNode:\n-- " + RelOptUtil.toString(relNode);
    }
  }

  /**
   * Information about a generated materialized view.
   *
   * HYBRID STRATEGY:
   * - For aggregations on joins: MV contains ONLY the join (no GROUP BY)
   *   → Queries must apply their GROUP BY on top of the MV scan
   * - For single-table aggregations: MV contains the full aggregation (with GROUP BY)
   *   → Queries scan the MV directly
   */
  public static class MaterializedViewInfo {
    private final String viewName;
    private final String viewSql;
    private final RelNode originalNode;
    private final boolean isAggregationOnJoin;

    public MaterializedViewInfo(String viewName, String viewSql, RelNode originalNode, boolean isAggregationOnJoin) {
      this.viewName = viewName;
      this.viewSql = viewSql;
      this.originalNode = originalNode;
      this.isAggregationOnJoin = isAggregationOnJoin;
    }

    // Backward compatibility constructor
    public MaterializedViewInfo(String viewName, String viewSql, RelNode originalNode) {
      this(viewName, viewSql, originalNode, false);
    }

    public String getViewName() {
      return viewName;
    }

    public String getViewSql() {
      return viewSql;
    }

    public RelNode getOriginalNode() {
      return originalNode;
    }

    public boolean isAggregationOnJoin() {
      return isAggregationOnJoin;
    }

    @Override
    public String toString() {
      return "CREATE MATERIALIZED VIEW " + viewName + " AS\n" + viewSql;
    }
  }
}

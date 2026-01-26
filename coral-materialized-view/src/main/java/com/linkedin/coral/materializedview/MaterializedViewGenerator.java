/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import java.util.Set;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.rel2sql.RelToSqlConverter;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlNode;


/**
 * Generates SQL for materialized views based on common subexpressions.
 */
public class MaterializedViewGenerator {

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
   * - Aggregations: Direct materialization (exact matching, no enhancement)
   * - Joins: Filter-agnostic materialization (huge performance win for multi-table joins)
   *
   * @param subexpression The pattern to materialize
   * @param filterColumnIndices Ignored (no partial aggregation for now)
   * @return MaterializedViewInfo
   */
  public MaterializedViewInfo generateMaterializedView(RelNode subexpression, Set<Integer> filterColumnIndices) {
    String viewName = generateViewName();

    // No enhancement - use the pattern as-is
    // For joins: This captures the expensive join computation
    // For aggregations: This captures the exact query (safe and correct)
    String viewSql = generateViewSql(subexpression);

    return new MaterializedViewInfo(viewName, viewSql, subexpression);
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
   * - For joins: MV contains the expensive join computation (filter-agnostic)
   * - For aggregations: MV contains the exact aggregation (safe and correct)
   */
  public static class MaterializedViewInfo {
    private final String viewName;
    private final String viewSql;
    private final RelNode originalNode;

    public MaterializedViewInfo(String viewName, String viewSql, RelNode originalNode) {
      this.viewName = viewName;
      this.viewSql = viewSql;
      this.originalNode = originalNode;
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

    @Override
    public String toString() {
      return "CREATE MATERIALIZED VIEW " + viewName + " AS\n" + viewSql;
    }
  }
}

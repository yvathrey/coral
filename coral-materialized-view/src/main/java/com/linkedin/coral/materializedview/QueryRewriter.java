/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import java.util.*;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelDistributions;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.rel2sql.RelToSqlConverter;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.ColumnStrategy;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlNode;

import com.linkedin.coral.hive.hive2rel.HiveToRelConverter;


/**
 * Rewrites queries to use materialized views instead of common subexpressions.
 *
 * This uses proper RelNode tree manipulation rather than string substitution.
 */
public class QueryRewriter {

  private final HiveToRelConverter hiveToRelConverter;

  public QueryRewriter(HiveToRelConverter hiveToRelConverter) {
    this.hiveToRelConverter = hiveToRelConverter;
  }

  /**
   * Rewrite a query to use materialized views.
   *
   * This performs tree-based replacement at the RelNode level,
   * then generates rewritten SQL with MV substitutions.
   *
   * @param originalQuery The original query RelNode
   * @param subexpressionMap Map of digest to subexpression info
   * @param materializedViews Map of digest to materialized view info
   * @return RewriteResult containing the rewritten RelNode and SQL
   */
  public RewriteResult rewriteQuery(RelNode originalQuery,
      Map<String, CommonSubexpressionFinder.SubexpressionInfo> subexpressionMap,
      Map<String, MaterializedViewGenerator.MaterializedViewInfo> materializedViews) {

    System.out.println("\n@@@ QUERY REWRITER - START @@@");
    System.out.println("DEBUG: Original query type: " + originalQuery.getClass().getSimpleName());
    System.out.println("DEBUG: Number of subexpression patterns: " + subexpressionMap.size());
    System.out.println("DEBUG: Number of materialized views: " + materializedViews.size());

    // Use tree transformer to replace matching subtrees with MV scans
    System.out.println("DEBUG: Creating SubtreeReplacer...");
    SubtreeReplacer replacer = new SubtreeReplacer(subexpressionMap, materializedViews, hiveToRelConverter);

    System.out.println("DEBUG: Starting tree traversal with replacer...");
    RelNode rewrittenQuery = originalQuery.accept(replacer);

    System.out.println("DEBUG: Tree traversal complete");
    System.out.println("DEBUG: Rewritten query type: " + rewrittenQuery.getClass().getSimpleName());
    System.out.println("DEBUG: Total replacements made: " + replacer.getReplacementCount());

    boolean queryChanged = (rewrittenQuery != originalQuery);
    System.out.println("DEBUG: Query structure changed: " + queryChanged);

    // Convert to SQL
    System.out.println("DEBUG: Converting rewritten query to SQL...");
    String rewrittenSql = convertToSql(rewrittenQuery);

    System.out.println("DEBUG: Rewrite complete!");
    System.out.println("@@@ QUERY REWRITER - END @@@\n");

    return new RewriteResult(rewrittenQuery, rewrittenSql, replacer.getReplacementCount());
  }

  /**
   * Tree transformer that replaces matching subtrees with MV table scans.
   *
   * This uses proper RelNode digest comparison to detect matches and replaces them in the tree.
   */
  private static class SubtreeReplacer extends RelShuttleImpl {
    private final Map<String, CommonSubexpressionFinder.SubexpressionInfo> subexpressionMap;
    private final Map<String, MaterializedViewGenerator.MaterializedViewInfo> materializedViews;
    private final HiveToRelConverter hiveToRelConverter;
    private int replacementCount = 0;

    SubtreeReplacer(Map<String, CommonSubexpressionFinder.SubexpressionInfo> subexpressionMap,
        Map<String, MaterializedViewGenerator.MaterializedViewInfo> materializedViews,
        HiveToRelConverter hiveToRelConverter) {
      this.subexpressionMap = subexpressionMap;
      this.materializedViews = materializedViews;
      this.hiveToRelConverter = hiveToRelConverter;
    }

    /**
     * Check if a node matches any common subexpression and replace if found.
     * This method is called by all the specific visit methods.
     */
    private RelNode checkAndReplace(RelNode node) {
      // Get the digest of this node for structural comparison
      // HYBRID STRATEGY: Must match the digest computation used in pattern detection!
      // - Aggregations on joins: filter-agnostic digest
      // - Single-table aggregations: exact digest
      // - Other patterns: exact digest
      String nodeDigest = computeDigestForMatching(node);

      // Debug: Print what we're comparing
      System.out.println("\n========================================");
      System.out.println("DEBUG: Visiting node type: " + node.getClass().getSimpleName());
      System.out.println("DEBUG: Node digest hash: " + nodeDigest.hashCode());
      System.out.println("DEBUG: Node digest length: " + nodeDigest.length());
      System.out.println("DEBUG: Full node digest:");
      System.out.println(nodeDigest);
      System.out.println("========================================");

      // Check if this subtree matches any of our common subexpressions
      System.out.println("\nDEBUG: Checking " + subexpressionMap.size() + " patterns...");

      int patternIndex = 0;
      for (Map.Entry<String, CommonSubexpressionFinder.SubexpressionInfo> entry : subexpressionMap.entrySet()) {
        patternIndex++;
        CommonSubexpressionFinder.SubexpressionInfo subexprInfo = entry.getValue();
        String targetDigest = subexprInfo.getDigest();

        System.out.println("\n--- Pattern " + patternIndex + " ---");
        System.out.println("Pattern digest hash: " + targetDigest.hashCode());
        System.out.println("Pattern digest length: " + targetDigest.length());
        System.out.println("Pattern digest:");
        System.out.println(targetDigest);

        // Compare using digest (proper structural comparison, not string matching)
        boolean matches = targetDigest.equals(nodeDigest);
        System.out.println("Match result: " + matches);

        if (matches) {
          System.out.println("\n*** MATCH FOUND! ***");
          // Found a match! Replace with MV scan (direct TableScan replacement)
          // HYBRID STRATEGY: Exact matching for aggregations, filter-agnostic for joins
          MaterializedViewGenerator.MaterializedViewInfo mvInfo = materializedViews.get(entry.getKey());
          if (mvInfo != null) {
            try {
              System.out.println("DEBUG: Creating standard MV replacement for: " + mvInfo.getViewName());

              // CRITICAL FIX: Use the current node's cluster, not the representative node's cluster!
              // The representative node is from a different query plan (Stage 1), so its cluster
              // is incompatible with the current query being rewritten.
              RelOptCluster cluster = node.getCluster();
              RelNode matchedSubtree = node; // Use current node, not representative

              // Build standard replacement (direct TableScan)
              RelNode replacement = buildStandardReplacement(matchedSubtree, mvInfo, cluster);

              replacementCount++;
              System.out.println("\n*** REPLACEMENT SUCCESSFUL! ***");
              System.out.println("DEBUG: Replaced with MV: " + mvInfo.getViewName());
              System.out.println("DEBUG: Replacement count now: " + replacementCount);
              System.out.println("========================================\n");

              return replacement;

            } catch (Exception e) {
              System.err.println("\n!!! ERROR: Failed to create MV replacement !!!");
              System.err.println("Error message: " + e.getMessage());
              e.printStackTrace();

              // Fall back to original subtree
              System.err.println("DEBUG: Falling back to original subtree");
              return subexprInfo.getRepresentativeNode();
            }
          } else {
            System.err.println("WARNING: Match found but no MV info available!");
          }
        }
      }

      System.out.println("\nDEBUG: No match found for this node");
      System.out.println("========================================\n");

      // No match found - return null to indicate no replacement
      return null;
    }

    @Override
    public RelNode visit(RelNode other) {
      // Check for replacement first
      RelNode replacement = checkAndReplace(other);
      if (replacement != null) {
        return replacement;
      }

      // No match found or replacement failed - continue traversing children
      return super.visit(other);
    }

    @Override
    public RelNode visit(org.apache.calcite.rel.logical.LogicalJoin join) {
      // Check for replacement first - this is critical for Join nodes!
      RelNode replacement = checkAndReplace(join);
      if (replacement != null) {
        return replacement;
      }

      // No match - continue with default behavior
      return super.visit(join);
    }

    @Override
    public RelNode visit(org.apache.calcite.rel.logical.LogicalProject project) {
      // Check for replacement first
      RelNode replacement = checkAndReplace(project);
      if (replacement != null) {
        return replacement;
      }

      // No match - continue with default behavior
      return super.visit(project);
    }

    @Override
    public RelNode visit(org.apache.calcite.rel.logical.LogicalFilter filter) {
      // Check for replacement first
      RelNode replacement = checkAndReplace(filter);
      if (replacement != null) {
        return replacement;
      }

      // No match - continue with default behavior
      return super.visit(filter);
    }

    @Override
    public RelNode visit(org.apache.calcite.rel.logical.LogicalAggregate aggregate) {
      // Check for replacement first
      RelNode replacement = checkAndReplace(aggregate);
      if (replacement != null) {
        return replacement;
      }

      // No match - continue with default behavior
      return super.visit(aggregate);
    }

    public int getReplacementCount() {
      return replacementCount;
    }

    /**
     * Compute digest for matching - must mirror CommonSubexpressionFinder logic!
     *
     * HYBRID STRATEGY:
     * - Aggregations on joins: Filter-agnostic digest (strip filters)
     * - Single-table aggregations: Exact digest (include filters)
     * - Other nodes: Exact digest
     */
    private String computeDigestForMatching(RelNode node) {
      if (node instanceof org.apache.calcite.rel.core.Aggregate) {
        org.apache.calcite.rel.core.Aggregate agg = (org.apache.calcite.rel.core.Aggregate) node;

        // Check if this aggregation has joins underneath
        boolean hasJoins = hasJoinBelow(agg);

        if (hasJoins) {
          // CASE 1: Aggregation on JOIN → Filter-agnostic matching
          // Build core digest: Aggregate structure + input without filters
          StringBuilder coreDigest = new StringBuilder();
          coreDigest.append("AggregationCore[");
          coreDigest.append("groupSet=").append(agg.getGroupSet()).append(", ");

          coreDigest.append("aggCalls=").append(agg.getAggCallList()).append(", ");

          coreDigest.append("input=").append(computeInputDigestWithoutFilters(agg.getInput()));
          coreDigest.append("]");

          System.out.println("      Aggregation on JOIN detected → Using filter-agnostic digest for matching");
          return coreDigest.toString();
        } else {
          // CASE 2: Single-table aggregation → Exact matching
          System.out.println("      Single-table aggregation detected → Using exact digest for matching");
          return RelOptUtil.toString(node);
        }
      }

      // For non-aggregation nodes, use exact digest
      return RelOptUtil.toString(node);
    }

    /**
     * Check if a node has joins in its subtree.
     */
    private boolean hasJoinBelow(RelNode node) {
      for (RelNode input : node.getInputs()) {
        if (input instanceof org.apache.calcite.rel.core.Join) {
          return true;
        }
        if (hasJoinBelow(input)) {
          return true;
        }
      }
      return false;
    }

    /**
     * Compute digest of input, SKIPPING filters (for filter-agnostic matching).
     * Must mirror the logic in CommonSubexpressionFinder!
     */
    private String computeInputDigestWithoutFilters(RelNode node) {
      if (node instanceof org.apache.calcite.rel.core.Filter) {
        // Skip filter, recurse on input
        return computeInputDigestWithoutFilters(node.getInput(0));
      }

      if (node instanceof org.apache.calcite.rel.core.Sort) {
        // Skip ORDER BY (Sort node), recurse on input
        // ORDER BY is presentational and doesn't affect computation
        return computeInputDigestWithoutFilters(node.getInput(0));
      }

      if (node instanceof org.apache.calcite.rel.core.Join) {
        // Use normalized join digest (order-insensitive for INNER joins)
        return normalizeJoinDigest((org.apache.calcite.rel.core.Join) node);
      }

      if (node instanceof org.apache.calcite.rel.core.TableScan) {
        org.apache.calcite.rel.core.TableScan scan = (org.apache.calcite.rel.core.TableScan) node;
        return "TableScan[" + scan.getTable().getQualifiedName() + "]";
      }

      // For all other node types (including Project), use class name and recurse
      StringBuilder sb = new StringBuilder();
      sb.append(node.getClass().getSimpleName()).append("[");
      for (int i = 0; i < node.getInputs().size(); i++) {
        if (i > 0)
          sb.append(", ");
        sb.append(computeInputDigestWithoutFilters(node.getInput(i)));
      }
      sb.append("]");
      return sb.toString();
    }

    /**
     * Normalize join digest for INNER joins to make order-insensitive.
     * For LEFT/RIGHT/FULL joins, preserve order as it's semantically significant.
     *
     * This ensures that A INNER JOIN B and B INNER JOIN A produce the same digest
     * and can share the same materialized view.
     *
     * Examples:
     * - INNER: A JOIN B = B JOIN A (normalized to same digest)
     * - LEFT:  A LEFT JOIN B ≠ B LEFT JOIN A (different digests, order matters)
     */
    private String normalizeJoinDigest(org.apache.calcite.rel.core.Join join) {
      String leftDigest = computeInputDigestWithoutFilters(join.getLeft());
      String rightDigest = computeInputDigestWithoutFilters(join.getRight());

      // For INNER joins: normalize order (lexicographic comparison)
      // For other joins: preserve order (semantically significant)
      if (join.getJoinType() == JoinRelType.INNER) {
        // Normalize: always put lexicographically smaller digest first
        if (leftDigest.compareTo(rightDigest) > 0) {
          // Swap left and right
          String temp = leftDigest;
          leftDigest = rightDigest;
          rightDigest = temp;
        }
      }

      StringBuilder sb = new StringBuilder();
      sb.append("Join[").append(join.getJoinType()).append(", ");
      sb.append("left=").append(leftDigest).append(", ");
      sb.append("right=").append(rightDigest);
      sb.append("]");
      return sb.toString();
    }

    /**
     * Build STANDARD MV replacement: Direct TableScan.
     *
     * Used for non-enhanced MVs or join patterns.
     * Simply replaces the matched subtree with a scan of the MV table.
     */
    private RelNode buildStandardReplacement(RelNode matchedSubtree,
        MaterializedViewGenerator.MaterializedViewInfo mvInfo, RelOptCluster cluster) {
      System.out.println("  Building standard TableScan replacement");

      // Get expected column names from the query
      RelDataType queryRowType = matchedSubtree.getRowType();
      String mvName = mvInfo.getViewName();
      List<String> qualifiedName = Arrays.asList("hive", "default", mvName);

      Table syntheticMvTable = new AbstractTable() {
        @Override
        public RelDataType getRowType(RelDataTypeFactory typeFactory) {
          return queryRowType;
        }
      };

      // Create RelOptTable and TableScan
      RelOptTable mvTable = new SyntheticMvTable(qualifiedName, queryRowType, syntheticMvTable);
      RelNode mvScan = LogicalTableScan.create(cluster, mvTable);

      // Get actual MV column names
      RelDataType mvRowType = mvScan.getRowType();

      // Check if column names differ (different aliases)
      if (!columnNamesMatch(queryRowType, mvRowType)) {
        System.out.println("  Column names differ - adding projection layer for alias remapping");
        return buildProjectionWithAliases(mvScan, queryRowType, cluster);
      }

      System.out.println("  Standard replacement complete: SELECT * FROM " + String.join(".", qualifiedName));
      return mvScan;
    }

    /**
     * Check if two row types have identical column names.
     * Used to determine if alias remapping is needed.
     */
    private boolean columnNamesMatch(RelDataType type1, RelDataType type2) {
      List<String> names1 = type1.getFieldNames();
      List<String> names2 = type2.getFieldNames();

      if (names1.size() != names2.size()) {
        return false;
      }

      for (int i = 0; i < names1.size(); i++) {
        if (!names1.get(i).equals(names2.get(i))) {
          return false;
        }
      }

      return true;
    }

    /**
     * Build a projection that renames MV columns to match target row type.
     * This enables queries with different column aliases to share the same MV.
     *
     * For example:
     * - Query 1: SELECT country, COUNT(*) as cnt FROM ... → expects columns [country, cnt]
     * - Query 2: SELECT country, COUNT(*) as total FROM ... → expects columns [country, total]
     * Both can use same MV, but we add projection to rename columns in output.
     */
    private RelNode buildProjectionWithAliases(RelNode mvScan, RelDataType targetRowType, RelOptCluster cluster) {
      System.out.println("  Adding projection layer to remap column aliases");

      RexBuilder rexBuilder = cluster.getRexBuilder();
      List<RexNode> projects = new ArrayList<>();
      List<String> fieldNames = new ArrayList<>();

      // Create identity projection ($0, $1, $2, ...) with target column names
      for (int i = 0; i < targetRowType.getFieldCount(); i++) {
        projects.add(rexBuilder.makeInputRef(mvScan, i));
        fieldNames.add(targetRowType.getFieldNames().get(i));

        System.out.println("    Remapping column " + i + ": " + mvScan.getRowType().getFieldNames().get(i) + " -> "
            + targetRowType.getFieldNames().get(i));
      }

      return LogicalProject.create(mvScan, projects, fieldNames);
    }

  }

  /**
   * Convert a RelNode to SQL.
   */
  private String convertToSql(RelNode relNode) {
    System.out.println("\n+++ CONVERTING RELNODE TO SQL +++");
    System.out.println("DEBUG: RelNode type: " + relNode.getClass().getSimpleName());
    System.out.println("DEBUG: RelNode digest preview:");
    String digest = RelOptUtil.toString(relNode);
    System.out.println(digest.substring(0, Math.min(500, digest.length())));

    try {
      System.out.println("DEBUG: Creating SQL dialect (HIVE)...");
      SqlDialect dialect = SqlDialect.DatabaseProduct.HIVE.getDialect();
      System.out.println("DEBUG: Dialect: " + dialect.getClass().getSimpleName());

      System.out.println("DEBUG: Creating RelToSqlConverter...");
      RelToSqlConverter converter = new RelToSqlConverter(dialect);

      System.out.println("DEBUG: Converting RelNode to SqlNode...");
      SqlNode sqlNode = converter.visitChild(0, relNode).asStatement();
      System.out.println("DEBUG: SqlNode created: " + sqlNode.getClass().getSimpleName());

      System.out.println("DEBUG: Converting SqlNode to SQL string...");
      String sql = sqlNode.toSqlString(dialect).getSql();

      System.out.println("DEBUG: SQL conversion successful!");
      System.out.println("DEBUG: Generated SQL:");
      System.out.println(sql);
      System.out.println("+++++++++++++++++++++++++++++++++\n");

      return sql;
    } catch (Exception e) {
      System.err.println("\n!!! ERROR in SQL conversion !!!");
      System.err.println("Error message: " + e.getMessage());
      System.err.println("Error class: " + e.getClass().getName());
      System.err.println("Stack trace:");
      e.printStackTrace();

      // Fallback to explain string
      String fallback = "-- Rewritten query (RelNode):\n-- " + RelOptUtil.toString(relNode);
      System.err.println("DEBUG: Using fallback explain string");
      System.err.println(fallback);
      System.err.println("+++++++++++++++++++++++++++++++++\n");
      return fallback;
    }
  }

  /**
   * Result of query rewriting.
   */
  public static class RewriteResult {
    private final RelNode rewrittenRelNode;
    private final String rewrittenSql;
    private final int replacementCount;

    public RewriteResult(RelNode rewrittenRelNode, String rewrittenSql, int replacementCount) {
      this.rewrittenRelNode = rewrittenRelNode;
      this.rewrittenSql = rewrittenSql;
      this.replacementCount = replacementCount;
    }

    public RelNode getRewrittenRelNode() {
      return rewrittenRelNode;
    }

    public String getRewrittenSql() {
      return rewrittenSql;
    }

    public int getReplacementCount() {
      return replacementCount;
    }

    @Override
    public String toString() {
      return "Rewritten SQL (with " + replacementCount + " replacements):\n" + rewrittenSql;
    }
  }

  /**
   * Synthetic RelOptTable for demo mode MV references.
   * This allows us to create TableScan nodes that reference non-existent MVs.
   */
  private static class SyntheticMvTable implements RelOptTable {
    private final List<String> qualifiedName;
    private final RelDataType rowType;
    private final Table table;

    SyntheticMvTable(List<String> qualifiedName, RelDataType rowType, Table table) {
      this.qualifiedName = qualifiedName;
      this.rowType = rowType;
      this.table = table;
      System.out.println("DEBUG: SyntheticMvTable constructor called for: " + qualifiedName);
    }

    @Override
    public List<String> getQualifiedName() {
      System.out.println("DEBUG: SyntheticMvTable.getQualifiedName() -> " + qualifiedName);
      return qualifiedName;
    }

    @Override
    public double getRowCount() {
      System.out.println("DEBUG: SyntheticMvTable.getRowCount() -> 100.0");
      return 100.0; // Synthetic estimate
    }

    @Override
    public RelDataType getRowType() {
      System.out.println("DEBUG: SyntheticMvTable.getRowType() -> " + rowType);
      return rowType;
    }

    @Override
    public RelOptSchema getRelOptSchema() {
      System.out.println("DEBUG: SyntheticMvTable.getRelOptSchema() -> null");
      return null;
    }

    @Override
    public RelNode toRel(ToRelContext context) {
      System.out.println("DEBUG: SyntheticMvTable.toRel() called");
      RelNode result = LogicalTableScan.create(context.getCluster(), this);
      System.out.println("DEBUG: SyntheticMvTable.toRel() -> " + result.getClass().getSimpleName());
      return result;
    }

    @Override
    public List<RelCollation> getCollationList() {
      System.out.println("DEBUG: SyntheticMvTable.getCollationList() -> empty");
      return Collections.emptyList();
    }

    @Override
    public RelDistribution getDistribution() {
      System.out.println("DEBUG: SyntheticMvTable.getDistribution() -> BROADCAST_DISTRIBUTED");
      return RelDistributions.BROADCAST_DISTRIBUTED;
    }

    @Override
    public boolean isKey(org.apache.calcite.util.ImmutableBitSet columns) {
      System.out.println("DEBUG: SyntheticMvTable.isKey() -> false");
      return false;
    }

    @Override
    public List<RelReferentialConstraint> getReferentialConstraints() {
      System.out.println("DEBUG: SyntheticMvTable.getReferentialConstraints() -> empty");
      return Collections.emptyList();
    }

    @Override
    public org.apache.calcite.linq4j.tree.Expression getExpression(Class clazz) {
      System.out.println("DEBUG: SyntheticMvTable.getExpression() called - throwing UnsupportedOperationException");
      throw new UnsupportedOperationException("getExpression not supported for synthetic MV tables");
    }

    @Override
    public RelOptTable extend(List<RelDataTypeField> extendedFields) {
      System.out.println("DEBUG: SyntheticMvTable.extend() called - throwing UnsupportedOperationException");
      throw new UnsupportedOperationException("extend not supported for synthetic MV tables");
    }

    @Override
    public List<ColumnStrategy> getColumnStrategies() {
      return Collections.emptyList();
    }

    @Override
    public <T> T unwrap(Class<T> clazz) {
      System.out.println("DEBUG: SyntheticMvTable.unwrap() called for class: " + clazz.getName());
      if (clazz.isInstance(table)) {
        System.out.println("DEBUG: SyntheticMvTable.unwrap() -> returning wrapped table");
        return clazz.cast(table);
      }
      System.out.println("DEBUG: SyntheticMvTable.unwrap() -> returning null");
      return null;
    }

    @Override
    public String toString() {
      return "SyntheticMvTable[" + String.join(".", qualifiedName) + "]";
    }
  }
}

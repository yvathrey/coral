/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;


/**
 * Traverses a query tree to find patterns that match materialized views in the registry.
 *
 * This class implements the same pattern matching logic as CommonSubexpressionFinder,
 * specifically the filter-agnostic matching strategy for JOIN aggregations.
 */
public class PatternMatcher extends RelShuttleImpl {

  private final MaterializedViewRegistry registry;
  private MatchResult result;

  public PatternMatcher(MaterializedViewRegistry registry) {
    this.registry = registry;
    this.result = new MatchResult();
  }

  /**
   * Find a matching pattern for the given query plan.
   *
   * @param queryPlan The query plan to match
   * @return MatchResult indicating if a match was found and details
   */
  public MatchResult findMatchingPattern(RelNode queryPlan) {
    // Traverse the tree to find matches
    queryPlan.accept(this);
    return result;
  }

  @Override
  public RelNode visit(RelNode other) {
    checkNode(other);
    return super.visit(other);
  }

  @Override
  public RelNode visit(LogicalAggregate aggregate) {
    checkNode(aggregate);
    return super.visit(aggregate);
  }

  @Override
  public RelNode visit(LogicalJoin join) {
    checkNode(join);
    return super.visit(join);
  }

  /**
   * Check if this node matches any pattern in the registry.
   */
  private void checkNode(RelNode node) {
    // Compute digest for this node using the same logic as CommonSubexpressionFinder
    String digest = computeDigest(node);

    // Store the query's pattern hash for debugging
    if (result.getQueryPatternHash() == null || result.getQueryPatternHash().equals("no-pattern")) {
      result.setQueryPatternHash(digest);
    }

    // Try exact match first (fast path)
    if (registry.contains(digest) && !result.hasMatch()) {
      // Found exact match!
      MaterializedViewRegistry.StoredMaterializedView mv = registry.get(digest);
      result.setMatch(digest, mv, node, null); // No residual filter for exact match
      return;
    }

    // Try filter implication matching
    if (!result.hasMatch()) {
      tryFilterImplicationMatch(node);
    }
  }

  /**
   * Try to match using filter implication logic.
   * Checks if the query's filter implies any registered MV's filter.
   */
  private void tryFilterImplicationMatch(RelNode node) {
    // Extract query filter
    RexNode queryFilter = extractFilter(node);
    if (queryFilter == null) {
      return; // No filter to check
    }

    // Try matching against each registered MV
    for (String mvDigest : registry.getAllDigests()) {
      MaterializedViewRegistry.StoredMaterializedView mv = registry.get(mvDigest);

      // Get the MV's pattern (RelNode)
      RelNode mvPattern = mv.getPattern();
      if (mvPattern == null) {
        continue; // No pattern available
      }

      // Extract MV filter
      RexNode mvFilter = extractFilter(mvPattern);
      if (mvFilter == null) {
        continue; // MV has no filter, skip implication check
      }

      // Check if query filter implies MV filter
      RexBuilder rexBuilder = node.getCluster().getRexBuilder();
      FilterImplicationChecker.ImplicationResult implicationResult =
          FilterImplicationChecker.checkImplication(queryFilter, mvFilter, rexBuilder);

      if (implicationResult.implies()) {
        // Match found with possible residual filter
        result.setMatch(mvDigest, mv, node, implicationResult.getResidualFilter());
        return; // Take first match
      }
    }
  }

  /**
   * Extract filter RexNode from a RelNode.
   * Handles LogicalFilter and LogicalAggregate (with filter as input).
   * Traverses through Project nodes to find filters.
   */
  private RexNode extractFilter(RelNode node) {
    if (node instanceof LogicalFilter) {
      return ((LogicalFilter) node).getCondition();
    }

    if (node instanceof LogicalAggregate) {
      LogicalAggregate agg = (LogicalAggregate) node;
      RelNode input = agg.getInput();

      // Check direct input
      if (input instanceof LogicalFilter) {
        return ((LogicalFilter) input).getCondition();
      }

      // Check through Project node
      if (input instanceof org.apache.calcite.rel.logical.LogicalProject) {
        RelNode projectInput = input.getInput(0);
        if (projectInput instanceof LogicalFilter) {
          return ((LogicalFilter) projectInput).getCondition();
        }
      }
    }

    return null;
  }

  /**
   * Compute digest for a node, mirroring CommonSubexpressionFinder logic.
   */
  private String computeDigest(RelNode node) {
    // Strip Sort (ORDER BY, LIMIT) nodes - they don't affect aggregation results
    RelNode coreNode = stripSort(node);

    // Use exact matching for everything else (includes filters, joins, aggregations)
    return RelOptUtil.toString(coreNode);
  }

  /**
   * Strip Sort nodes (ORDER BY, LIMIT) from the top of the tree.
   * These don't affect aggregation results and can be applied after reading MV.
   */
  private RelNode stripSort(RelNode node) {
    if (node instanceof Sort) {
      return stripSort(node.getInput(0));
    }
    return node;
  }

  /**
   * Check if there's a JOIN anywhere in the subtree.
   */
  private boolean hasJoinBelow(RelNode node) {
    if (node instanceof Join) {
      return true;
    }

    for (RelNode input : node.getInputs()) {
      if (hasJoinBelow(input)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Compute structural digest without filters.
   * Must match CommonSubexpressionFinder.computeInputDigestWithoutFilters() logic!
   */
  private String computeInputDigestWithoutFilters(RelNode node) {
    // Skip filter nodes
    if (node instanceof LogicalFilter) {
      return computeInputDigestWithoutFilters(((LogicalFilter) node).getInput());
    }

    // Skip ORDER BY (Sort node)
    if (node instanceof Sort) {
      return computeInputDigestWithoutFilters(node.getInput(0));
    }

    // Base case: TableScan
    if (node instanceof TableScan) {
      TableScan scan = (TableScan) node;
      return "TableScan[" + scan.getTable().getQualifiedName() + "]";
    }

    // Handle Join nodes with normalization
    if (node instanceof Join) {
      Join join = (Join) node;
      String leftDigest = computeInputDigestWithoutFilters(join.getLeft());
      String rightDigest = computeInputDigestWithoutFilters(join.getRight());

      // Apply join order normalization for INNER joins
      if (join.getJoinType() == JoinRelType.INNER) {
        // Normalize: lexicographic order
        if (leftDigest.compareTo(rightDigest) > 0) {
          // Swap left and right
          String temp = leftDigest;
          leftDigest = rightDigest;
          rightDigest = temp;
        }
      }

      return "Join[" + join.getJoinType() + ", left=" + leftDigest + ", right=" + rightDigest + "]";
    }

    // For all other node types, use class name and recurse
    StringBuilder sb = new StringBuilder();
    sb.append(node.getClass().getSimpleName()).append("[");
    for (int i = 0; i < node.getInputs().size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(computeInputDigestWithoutFilters(node.getInput(i)));
    }
    sb.append("]");
    return sb.toString();
  }

  /**
   * Result of pattern matching operation.
   */
  public static class MatchResult {
    private boolean hasMatch = false;
    private String patternHash;
    private MaterializedViewRegistry.StoredMaterializedView matchedMV;
    private RelNode matchedNode;
    private String queryPatternHash = "no-pattern";
    private RexNode residualFilter; // Filter to apply on top of MV (null if exact match)

    public void setMatch(String patternHash, MaterializedViewRegistry.StoredMaterializedView mv, RelNode node,
        RexNode residualFilter) {
      this.hasMatch = true;
      this.patternHash = patternHash;
      this.matchedMV = mv;
      this.matchedNode = node;
      this.residualFilter = residualFilter;
    }

    public void setQueryPatternHash(String hash) {
      this.queryPatternHash = hash;
    }

    public boolean hasMatch() {
      return hasMatch;
    }

    public String getPatternHash() {
      return patternHash;
    }

    public MaterializedViewRegistry.StoredMaterializedView getMatchedMV() {
      return matchedMV;
    }

    public RelNode getMatchedNode() {
      return matchedNode;
    }

    public String getQueryPatternHash() {
      return queryPatternHash;
    }

    public RexNode getResidualFilter() {
      return residualFilter;
    }

    public boolean hasResidualFilter() {
      return residualFilter != null;
    }
  }
}

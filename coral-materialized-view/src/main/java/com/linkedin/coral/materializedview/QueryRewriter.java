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
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalFilter;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Rewrites queries to use materialized views instead of common subexpressions.
 * This uses proper RelNode tree manipulation rather than string substitution.
 */
public class QueryRewriter {

  private static final Logger LOG = LoggerFactory.getLogger(QueryRewriter.class);
  private final HiveToRelConverter hiveToRelConverter;

  public QueryRewriter(HiveToRelConverter hiveToRelConverter) {
    this.hiveToRelConverter = hiveToRelConverter;
  }

  /**
   * Rewrite a query to use materialized views.
   * This performs tree-based replacement at the RelNode level,
   * then generates rewritten SQL with MV substitutions.
   * @param originalQuery The original query RelNode
   * @param subexpressionMap Map of digest to subexpression info
   * @param materializedViews Map of digest to materialized view info
   * @return RewriteResult containing the rewritten RelNode and SQL
   */
  public RewriteResult rewriteQuery(RelNode originalQuery,
      Map<String, CommonSubexpressionFinder.SubexpressionInfo> subexpressionMap,
      Map<String, MaterializedViewGenerator.MaterializedViewInfo> materializedViews) {
    LOG.debug("Original query type: {}", originalQuery.getClass().getSimpleName());
    LOG.debug("Number of subexpression patterns: {}", subexpressionMap.size());
    LOG.debug("Number of materialized views: {}", materializedViews.size());
    // Use tree transformer to replace matching subtrees with MV scans
    LOG.debug("Creating SubtreeReplacer...");
    SubtreeReplacer replacer = new SubtreeReplacer(subexpressionMap, materializedViews, hiveToRelConverter);
    LOG.debug("Starting tree traversal with replacer...");
    RelNode rewrittenQuery = originalQuery.accept(replacer);

    LOG.debug("Tree traversal complete");
    LOG.debug("Rewritten query type: {}", rewrittenQuery.getClass().getSimpleName());
    LOG.debug("Total replacements made: {}", replacer.getReplacementCount());

    boolean queryChanged = (rewrittenQuery != originalQuery);
    LOG.debug("Query structure changed: {}", queryChanged);

    // Convert to SQL
    LOG.debug("Converting rewritten query to SQL...");
    String rewrittenSql = convertToSql(rewrittenQuery);

    LOG.debug("Rewrite complete!");
    LOG.debug("@@@ QUERY REWRITER - END @@@");

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
      LOG.debug("\n========================================");
      LOG.debug("=== CHECKING NODE FOR MATCH ===");
      LOG.debug("========================================");
      LOG.debug("Node type: {}", node.getClass().getSimpleName());
      LOG.debug("Node digest hash: {}", nodeDigest.hashCode());
      LOG.debug("Node digest length: {}", nodeDigest.length());
      LOG.debug("Full node digest:");
      LOG.debug("{}", nodeDigest);
      LOG.debug("========================================");

      // Check if this subtree matches any of our common subexpressions
      LOG.debug("\nChecking against {} pattern keys...", subexpressionMap.size());

      int patternIndex = 0;
      for (Map.Entry<String, CommonSubexpressionFinder.SubexpressionInfo> entry : subexpressionMap.entrySet()) {
        patternIndex++;
        CommonSubexpressionFinder.SubexpressionInfo subexprInfo = entry.getValue();
        String targetDigest = subexprInfo.getDigest();

        LOG.debug("--- Pattern {} ---", patternIndex);
        LOG.debug("Pattern digest hash: {}", targetDigest.hashCode());
        LOG.debug("Pattern digest length: {}", targetDigest.length());
        LOG.debug("Pattern digest:");
        LOG.debug("{}", targetDigest);

        // Compare using digest (proper structural comparison, not string matching)
        boolean matches = targetDigest.equals(nodeDigest);
        LOG.debug("Match result: {}", matches);

        if (matches) {
          LOG.debug("*** MATCH FOUND! ***");
          // Found a match! Replace with MV scan (direct TableScan replacement)
          // HYBRID STRATEGY: Exact matching for aggregations, filter-agnostic for joins
          MaterializedViewGenerator.MaterializedViewInfo mvInfo = materializedViews.get(entry.getKey());
          if (mvInfo != null) {
            try {
              LOG.debug("Creating standard MV replacement for: {}", mvInfo.getViewName());

              // CRITICAL FIX: Use the current node's cluster, not the representative node's cluster!
              // The representative node is from a different query plan (Stage 1), so its cluster
              // is incompatible with the current query being rewritten.
              RelOptCluster cluster = node.getCluster();
              RelNode matchedSubtree = node; // Use current node, not representative

              // CRITICAL: For aggregations-on-join with filter-agnostic matching,
              // extract any filters from the query as residual filters
              RexNode residualFilter = null;
              if (mvInfo.isAggregationOnJoin()) {
                residualFilter = extractFilterFromAggregationSubtree(matchedSubtree);
                if (residualFilter != null) {
                  LOG.debug("Extracted residual filter from aggregation-on-join: {}", residualFilter);

                  // Validate that residual filter can be applied to MV schema
                  // For aggregation-on-join, the MV output is the join (not the aggregation)
                  // So we need to check against the join's schema
                  if (!canApplyResidualFilterForAggOnJoin(residualFilter, mvInfo.getOriginalNode(), node)) {
                    LOG.debug("Cannot apply residual filter - required columns not in MV output");
                    LOG.debug("Skipping this MV match");
                    continue; // Try next pattern
                  }
                }
              }

              // Build replacement with residual filter (if any)
              RelNode replacement = buildStandardReplacement(matchedSubtree, mvInfo, cluster, residualFilter);

              replacementCount++;
              LOG.debug("*** REPLACEMENT SUCCESSFUL! ***");
              LOG.debug("Replaced with MV: {}", mvInfo.getViewName());
              LOG.debug("Replacement count now: {}", replacementCount);
              LOG.debug("========================================");

              return replacement;

            } catch (Exception e) {
              LOG.error("Failed to create MV replacement", e);
              LOG.error("Error message: {}", e.getMessage());

              // Fall back to original subtree
              LOG.debug("Falling back to original subtree");
              return subexprInfo.getRepresentativeNode();
            }
          } else {
            LOG.warn("Match found but no MV info available!");
          }
        }
      }

      // Try filter implication matching
      LOG.debug("No exact match found. Trying filter implication...");
      RelNode filterMatch = tryFilterImplicationRewrite(node);
      if (filterMatch != null) {
        return filterMatch;
      }

      LOG.debug("No match found for this node");
      LOG.debug("========================================");

      // No match found - return null to indicate no replacement
      return null;
    }

    /**
     * Try to rewrite using filter implication logic.
     * Checks if query filter implies any registered MV's filter.
     */
    private RelNode tryFilterImplicationRewrite(RelNode node) {
      // Extract query filter
      RexNode queryFilter = extractFilterFromNode(node);
      if (queryFilter == null) {
        LOG.debug("No filter found in query node, skipping filter implication");
        return null; // No filter to check
      }

      LOG.debug("Query has filter: {}", queryFilter);

      // Try matching against each registered pattern
      for (Map.Entry<String, CommonSubexpressionFinder.SubexpressionInfo> entry : subexpressionMap.entrySet()) {
        CommonSubexpressionFinder.SubexpressionInfo subexprInfo = entry.getValue();
        MaterializedViewGenerator.MaterializedViewInfo mvInfo = materializedViews.get(entry.getKey());

        if (mvInfo == null) {
          continue;
        }

        // Get MV pattern
        RelNode mvPattern = mvInfo.getOriginalNode();
        if (mvPattern == null) {
          continue;
        }

        // Extract MV filter
        RexNode mvFilter = extractFilterFromNode(mvPattern);
        if (mvFilter == null) {
          continue; // MV has no filter
        }

        LOG.debug("Checking implication against MV: {}", mvInfo.getViewName());
        LOG.debug("MV filter: {}", mvFilter);

        // Check if query filter implies MV filter
        RexBuilder rexBuilder = node.getCluster().getRexBuilder();
        FilterImplicationChecker.ImplicationResult result =
            FilterImplicationChecker.checkImplication(queryFilter, mvFilter, rexBuilder);

        if (result.implies()) {
          LOG.debug("\n*** FILTER IMPLICATION MATCH FOUND! ***");
          LOG.debug("Query filter implies MV filter");
          LOG.debug("Residual filter: {}", (result.getResidualFilter() != null ? result.getResidualFilter() : "none"));

          // Validate that residual filter can be applied to MV schema
          if (result.getResidualFilter() != null) {
            if (!canApplyResidualFilter(result.getResidualFilter(), mvPattern, node)) {
              LOG.debug("Cannot apply residual filter - required columns not in MV output");
              LOG.debug("Skipping filter implication for this MV");
              continue; // Try next MV
            }
          }

          try {
            RelOptCluster cluster = node.getCluster();
            RelNode replacement = buildStandardReplacement(node, mvInfo, cluster, result.getResidualFilter());

            replacementCount++;
            LOG.debug("Replaced with MV (filter implication): {}", mvInfo.getViewName());
            LOG.debug("Replacement count now: {}", replacementCount);
            LOG.debug("========================================\n");

            return replacement;

          } catch (Exception e) {
            LOG.error("Failed to create MV replacement with filter implication", e);
          }
        }
      }

      return null; // No filter implication match found
    }

    /**
     * Check if a residual filter can be applied to an aggregation-on-join MV.
     * For aggregation-on-join MVs, the MV materializes the JOIN (not the aggregation),
     * so ALL columns from the join are available for filtering.
     *
     * @param residualFilter The filter to apply
     * @param mvPattern The MV's original RelNode pattern (Aggregate node)
     * @param queryNode The query node (for column name mapping)
     * @return true if the filter can be applied, false otherwise
     */
    private boolean canApplyResidualFilterForAggOnJoin(RexNode residualFilter, RelNode mvPattern, RelNode queryNode) {
      if (residualFilter == null) {
        return true; // No filter to apply
      }

      // Extract the join node from the aggregation pattern
      RelNode joinNode = extractJoinFromAggregation(mvPattern);
      if (joinNode == null) {
        LOG.debug("Cannot extract join from aggregation pattern, allowing filter application");
        return true; // Conservative: allow if we can't determine
      }

      // Get the output schema of the JOIN (this is what the MV actually contains)
      RelDataType mvOutputType = joinNode.getRowType();
      List<String> mvColumns = mvOutputType.getFieldNames();

      LOG.debug("MV output columns (from join): {}", mvColumns);

      // Get the query's input schema (before filtering/aggregation)
      RelDataType queryInputType = getInputType(queryNode);
      if (queryInputType == null) {
        LOG.debug("Cannot determine query input type, allowing filter application");
        return true; // Conservative: allow if we can't determine
      }

      List<String> queryColumns = queryInputType.getFieldNames();

      // Extract field references from the residual filter
      java.util.Set<Integer> referencedFields = new java.util.HashSet<>();
      extractFieldReferences(residualFilter, referencedFields);

      // Check if all referenced fields exist in MV output
      for (Integer fieldIndex : referencedFields) {
        if (fieldIndex >= queryColumns.size()) {
          LOG.debug("Field index {} out of bounds", fieldIndex);
          return false;
        }

        String fieldName = queryColumns.get(fieldIndex);
        if (!mvColumns.contains(fieldName)) {
          LOG.debug("Field '{}' (index {}) not in MV output: {}", fieldName, fieldIndex, mvColumns);
          return false;
        }
      }

      LOG.debug("All residual filter fields available in MV output");
      return true;
    }

    /**
     * Extract the join node from an aggregation pattern.
     * Aggregation-on-join structure: Aggregate -> [Filter ->] [Project ->] Join
     */
    private RelNode extractJoinFromAggregation(RelNode node) {
      if (node instanceof Aggregate) {
        RelNode input = ((Aggregate) node).getInput();
        return extractJoinFromTree(input);
      }
      return null;
    }

    /**
     * Recursively search for join node in tree, skipping Filter and Project nodes.
     */
    private RelNode extractJoinFromTree(RelNode node) {
      if (node instanceof org.apache.calcite.rel.core.Join) {
        return node;
      }
      if (node instanceof org.apache.calcite.rel.core.Filter) {
        return extractJoinFromTree(node.getInput(0));
      }
      if (node instanceof org.apache.calcite.rel.core.Project) {
        return extractJoinFromTree(node.getInput(0));
      }
      // Not found
      return null;
    }

    /**
     * Check if a residual filter can be applied to an MV.
     * The residual filter can only reference columns that exist in the MV output.
     *
     * For aggregated MVs, only GROUP BY columns are available in the output.
     * For non-aggregated MVs, all projected columns are available.
     *
     * @param residualFilter The filter to apply
     * @param mvPattern The MV's original RelNode pattern
     * @param queryNode The query node (for column name mapping)
     * @return true if the filter can be applied, false otherwise
     */
    private boolean canApplyResidualFilter(RexNode residualFilter, RelNode mvPattern, RelNode queryNode) {
      if (residualFilter == null) {
        return true; // No filter to apply
      }

      // Get the output schema of the MV
      RelDataType mvOutputType = mvPattern.getRowType();
      List<String> mvColumns = mvOutputType.getFieldNames();

      // Get the query's input schema (before filtering/aggregation)
      RelDataType queryInputType = getInputType(queryNode);
      if (queryInputType == null) {
        LOG.debug("Cannot determine query input type, allowing filter application");
        return true; // Conservative: allow if we can't determine
      }

      List<String> queryColumns = queryInputType.getFieldNames();

      // Extract field references from the residual filter
      java.util.Set<Integer> referencedFields = new java.util.HashSet<>();
      extractFieldReferences(residualFilter, referencedFields);

      // Check if all referenced fields exist in MV output
      for (Integer fieldIndex : referencedFields) {
        if (fieldIndex >= queryColumns.size()) {
          LOG.debug("Field index {} out of bounds", fieldIndex);
          return false;
        }

        String fieldName = queryColumns.get(fieldIndex);
        if (!mvColumns.contains(fieldName)) {
          LOG.debug("Field '{}' (index {}) not in MV output: {}", fieldName, fieldIndex, mvColumns);
          return false;
        }
      }

      LOG.debug("All residual filter fields available in MV output");
      return true;
    }

    /**
     * Get the input type (schema before filtering/aggregation) of a RelNode.
     */
    private RelDataType getInputType(RelNode node) {
      if (node instanceof LogicalFilter) {
        return getInputType(node.getInput(0));
      }
      if (node instanceof Aggregate) {
        return getInputType(node.getInput(0));
      }
      if (node instanceof org.apache.calcite.rel.logical.LogicalProject) {
        return getInputType(node.getInput(0));
      }
      if (node instanceof org.apache.calcite.rel.core.TableScan) {
        return node.getRowType();
      }
      // For joins or other complex nodes, return their row type
      return node.getRowType();
    }

    /**
     * Extract all field references from a RexNode.
     */
    private void extractFieldReferences(RexNode node, java.util.Set<Integer> fields) {
      if (node instanceof org.apache.calcite.rex.RexInputRef) {
        fields.add(((org.apache.calcite.rex.RexInputRef) node).getIndex());
      } else if (node instanceof org.apache.calcite.rex.RexCall) {
        for (RexNode operand : ((org.apache.calcite.rex.RexCall) node).getOperands()) {
          extractFieldReferences(operand, fields);
        }
      }
    }

    /**
     * Extract filter RexNode from a RelNode.
     * Handles LogicalFilter and LogicalAggregate (with filter as input).
     */
    /**
     * Extract filter from aggregation subtree for aggregation-on-join patterns.
     * This looks for Filter nodes in the aggregation's input tree.
     */
    private RexNode extractFilterFromAggregationSubtree(RelNode node) {
      return extractFilterFromNode(node);
    }

    private RexNode extractFilterFromNode(RelNode node) {
      if (node instanceof LogicalFilter) {
        return ((LogicalFilter) node).getCondition();
      }

      if (node instanceof Aggregate) {
        Aggregate agg = (Aggregate) node;
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
      // Strip Sort (ORDER BY, LIMIT) nodes - they don't affect aggregation results
      RelNode coreNode = stripSort(node);

      // Check if this is an aggregation pattern
      boolean isAggregation = (coreNode instanceof Aggregate) ||
                              (coreNode instanceof org.apache.calcite.rel.core.Filter && hasAggregateBelow(coreNode)) ||
                              (coreNode instanceof org.apache.calcite.rel.core.Project && hasAggregateBelow(coreNode));

      if (isAggregation) {
        // Use filter-agnostic matching for aggregations on joins
        return computeAggregationCoreDigest(coreNode);
      }

      // Use exact matching for everything else (includes filters, joins, non-aggregation patterns)
      return RelOptUtil.toString(coreNode);
    }

    /**
     * Compute digest for aggregation patterns.
     * MUST MATCH the logic in CommonSubexpressionFinder.computeAggregationCoreDigest()!
     *
     * HYBRID STRATEGY:
     * - For aggregations on joins: Use filter-agnostic matching (excludes WHERE clauses)
     * - For single-table aggregations: Use exact matching (includes WHERE clauses)
     */
    private String computeAggregationCoreDigest(RelNode node) {
      if (!(node instanceof Aggregate)) {
        return RelOptUtil.toString(node);
      }

      Aggregate agg = (Aggregate) node;

      // HYBRID STRATEGY: Check if this aggregation has joins underneath
      boolean hasJoins = hasJoinBelow(agg);

      if (hasJoins) {
        // CASE 1: Aggregation on JOIN → Filter-agnostic matching
        // Build core digest: Aggregate structure + input without filters
        // This allows queries with different WHERE clauses to share the same MV!
        LOG.debug("      [QueryRewriter] Aggregation on JOIN detected → Filter-agnostic matching");

        StringBuilder coreDigest = new StringBuilder();
        coreDigest.append("AggregationCore[");
        coreDigest.append("groupSet=").append(agg.getGroupSet()).append(", ");
        coreDigest.append("aggCalls=").append(agg.getAggCallList()).append(", ");
        coreDigest.append("input=").append(computeInputDigestWithoutFilters(agg.getInput()));
        coreDigest.append("]");

        return coreDigest.toString();
      } else {
        // CASE 2: Single-table aggregation → Exact matching
        // Use full digest including filters for safety
        LOG.debug("      [QueryRewriter] Single-table aggregation detected → Exact matching");
        return RelOptUtil.toString(node);
      }
    }

    /**
     * Check if a node has aggregates in its subtree.
     */
    private boolean hasAggregateBelow(RelNode node) {
      for (RelNode input : node.getInputs()) {
        if (input instanceof Aggregate) {
          return true;
        }
        if (hasAggregateBelow(input)) {
          return true;
        }
      }
      return false;
    }

    /**
     * Strip Sort nodes (ORDER BY, LIMIT) from the top of the tree.
     * These don't affect aggregation results and can be applied after reading MV.
     */
    private RelNode stripSort(RelNode node) {
      if (node instanceof org.apache.calcite.rel.core.Sort) {
        return stripSort(node.getInput(0));
      }
      return node;
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
     * Build STANDARD MV replacement: Direct TableScan or Aggregate on TableScan.
     *
     * HYBRID STRATEGY:
     * - Aggregation on Join: MV contains only the join → Preserve Aggregate on top
     * - Single-table Aggregation: MV contains full aggregation → Direct scan
     * - Join patterns: MV contains join → Direct scan
     */
    private RelNode buildStandardReplacement(RelNode matchedSubtree,
        MaterializedViewGenerator.MaterializedViewInfo mvInfo, RelOptCluster cluster, RexNode residualFilter) {
      LOG.debug("  Building standard TableScan replacement");
      if (residualFilter != null) {
        LOG.debug("  With residual filter: {}", residualFilter);
      }

      boolean isAggregationOnJoin = mvInfo.isAggregationOnJoin();
      LOG.debug("  Is aggregation on join pattern: {}", isAggregationOnJoin);

      if (isAggregationOnJoin) {
        // CRITICAL FIX: For aggregation-on-join patterns, MV contains only the join
        // We need to preserve the Aggregate node on top of the MV scan
        return buildAggregationOnJoinReplacement(matchedSubtree, mvInfo, cluster, residualFilter);
      } else {
        // Standard replacement: MV contains the full pattern
        return buildDirectMvScan(matchedSubtree, mvInfo, cluster, residualFilter);
      }
    }

    /**
     * Build replacement for aggregation-on-join patterns.
     * MV contains only the join, so we preserve the Aggregate node on top.
     */
    private RelNode buildAggregationOnJoinReplacement(RelNode matchedSubtree,
        MaterializedViewGenerator.MaterializedViewInfo mvInfo, RelOptCluster cluster, RexNode residualFilter) {
      LOG.debug("  Building aggregation-on-join replacement");

      // Extract the Aggregate node from the matched pattern
      Aggregate agg = extractAggregateNode(matchedSubtree);
      if (agg == null) {
        LOG.warn("  Could not extract Aggregate node, falling back to direct scan");
        return buildDirectMvScan(matchedSubtree, mvInfo, cluster, residualFilter);
      }

      LOG.debug("  Extracted Aggregate: groupSet={}, aggCalls={}", agg.getGroupSet(), agg.getAggCallList());

      // Get the join input (what the MV actually contains)
      RelNode joinInput = extractJoinInput(agg);
      RelDataType joinRowType = joinInput.getRowType();

      // Create MV scan with the join's row type (not the aggregate's row type)
      String mvName = mvInfo.getViewName();
      List<String> qualifiedName = Arrays.asList("hive", "default", mvName);

      Table syntheticMvTable = new AbstractTable() {
        @Override
        public RelDataType getRowType(RelDataTypeFactory typeFactory) {
          return joinRowType;
        }
      };

      RelOptTable mvTable = new SyntheticMvTable(qualifiedName, joinRowType, syntheticMvTable);
      RelNode mvScan = LogicalTableScan.create(cluster, mvTable);

      LOG.debug("  Created MV scan with {} columns", joinRowType.getFieldCount());

      // Apply residual filter on the MV scan (BEFORE aggregation)
      if (residualFilter != null) {
        LOG.debug("  Applying residual filter before aggregation");
        // For aggregation-on-join, residual filter is on raw columns (before GROUP BY)
        // No field remapping needed - filter is already on the right schema
        mvScan = LogicalFilter.create(mvScan, residualFilter);
      }

      // Recreate the Aggregate node on top of the MV scan
      // Note: Using overload without hints for Calcite compatibility
      RelNode result = LogicalAggregate.create(
          mvScan,
          agg.getGroupSet(),
          agg.getGroupSets(),
          agg.getAggCallList()
      );

      LOG.debug("  Recreated Aggregate on top of MV scan");
      LOG.debug("  Final result: Aggregate(GROUP BY {}) → TableScan({})", agg.getGroupSet(), mvName);

      return result;
    }

    /**
     * Build direct MV scan replacement (standard case).
     * MV contains the full pattern, so we just scan it directly.
     */
    private RelNode buildDirectMvScan(RelNode matchedSubtree,
        MaterializedViewGenerator.MaterializedViewInfo mvInfo, RelOptCluster cluster, RexNode residualFilter) {
      LOG.debug("  Building direct MV scan");

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

      // Apply residual filter if present (with field remapping)
      if (residualFilter != null) {
        LOG.debug("  Applying residual filter on top of MV scan");

        // Remap field indices from query schema to MV output schema
        RelNode mvPattern = mvInfo.getOriginalNode();
        RexNode remappedFilter = remapFilterFields(residualFilter, matchedSubtree, mvPattern, cluster);

        if (remappedFilter != null) {
          LOG.debug("  Remapped filter: {}", remappedFilter);
          mvScan = LogicalFilter.create(mvScan, remappedFilter);
        } else {
          LOG.warn("  Could not remap filter, skipping");
        }
      }

      // Get actual MV column names
      RelDataType mvRowType = mvScan.getRowType();

      // Check if column names differ (different aliases)
      if (!columnNamesMatch(queryRowType, mvRowType)) {
        LOG.debug("  Column names differ - adding projection layer for alias remapping");
        return buildProjectionWithAliases(mvScan, queryRowType, cluster);
      }

      LOG.debug("  Standard replacement complete: SELECT * FROM {}", String.join(".", qualifiedName));
      return mvScan;
    }

    /**
     * Extract the Aggregate node from a pattern (may be wrapped by Filter/Project/Sort).
     */
    private Aggregate extractAggregateNode(RelNode node) {
      if (node instanceof Aggregate) {
        return (Aggregate) node;
      }
      // Check through wrappers
      if (node instanceof LogicalFilter || node instanceof LogicalProject || node instanceof org.apache.calcite.rel.core.Sort) {
        for (RelNode child : node.getInputs()) {
          if (child instanceof Aggregate) {
            return (Aggregate) child;
          }
          // Recursively check one more level
          Aggregate agg = extractAggregateNode(child);
          if (agg != null) {
            return agg;
          }
        }
      }
      return null;
    }

    /**
     * Extract the join input from an Aggregate node.
     * Skips Filter/Project nodes between Aggregate and Join.
     */
    private RelNode extractJoinInput(Aggregate agg) {
      RelNode input = agg.getInput();

      // Skip through Filter and Project nodes to get to the join
      while (input instanceof LogicalFilter || input instanceof LogicalProject) {
        input = input.getInput(0);
      }

      return input;
    }

    /**
     * Remap field references in a filter from query input schema to MV output schema.
     *
     * Example:
     * Query input schema: [id, name, location, exp, country, dept] - country at index 4
     * MV output schema: [location, country, COUNT(*)] - country at index 1
     * Filter: =($4, 'US') needs to become =($1, 'US')
     *
     * @param filter The original filter with query input field indices
     * @param queryNode The query node (to get input schema)
     * @param mvPattern The MV pattern (to get output schema)
     * @param cluster The cluster for creating new RexNodes
     * @return Remapped filter, or null if remapping fails
     */
    private RexNode remapFilterFields(RexNode filter, RelNode queryNode, RelNode mvPattern, RelOptCluster cluster) {
      // Get schemas
      RelDataType queryInputType = getInputType(queryNode);
      RelDataType mvOutputType = mvPattern.getRowType();

      if (queryInputType == null || mvOutputType == null) {
        LOG.error("  Cannot determine schemas for field remapping");
        return null;
      }

      List<String> queryInputFields = queryInputType.getFieldNames();
      List<String> mvOutputFields = mvOutputType.getFieldNames();

      LOG.debug("  Query input fields: {}", queryInputFields);
      LOG.debug("  MV output fields: {}", mvOutputFields);

      // Build mapping: query input index -> MV output index
      java.util.Map<Integer, Integer> indexMap = new java.util.HashMap<>();
      for (int queryIdx = 0; queryIdx < queryInputFields.size(); queryIdx++) {
        String fieldName = queryInputFields.get(queryIdx);
        int mvIdx = mvOutputFields.indexOf(fieldName);
        if (mvIdx >= 0) {
          indexMap.put(queryIdx, mvIdx);
          LOG.debug("  Mapping: ${} ({}) -> ${}", queryIdx, fieldName, mvIdx);
        }
      }

      // Transform the filter using the index mapping
      RexBuilder rexBuilder = cluster.getRexBuilder();
      return remapRexNode(filter, indexMap, rexBuilder);
    }

    /**
     * Recursively remap field indices in a RexNode.
     */
    private RexNode remapRexNode(RexNode node, java.util.Map<Integer, Integer> indexMap, RexBuilder rexBuilder) {
      if (node instanceof org.apache.calcite.rex.RexInputRef) {
        org.apache.calcite.rex.RexInputRef inputRef = (org.apache.calcite.rex.RexInputRef) node;
        int oldIndex = inputRef.getIndex();

        if (indexMap.containsKey(oldIndex)) {
          int newIndex = indexMap.get(oldIndex);
          LOG.debug("    Remapping field reference: ${} -> ${}", oldIndex, newIndex);
          return rexBuilder.makeInputRef(inputRef.getType(), newIndex);
        } else {
          LOG.warn("    Field ${} not found in MV output", oldIndex);
          return null;
        }
      } else if (node instanceof org.apache.calcite.rex.RexCall) {
        org.apache.calcite.rex.RexCall call = (org.apache.calcite.rex.RexCall) node;
        List<RexNode> newOperands = new ArrayList<>();

        for (RexNode operand : call.getOperands()) {
          RexNode remapped = remapRexNode(operand, indexMap, rexBuilder);
          if (remapped == null) {
            return null; // Failed to remap
          }
          newOperands.add(remapped);
        }

        return rexBuilder.makeCall(call.getOperator(), newOperands);
      } else if (node instanceof org.apache.calcite.rex.RexLiteral) {
        // Literals don't need remapping
        return node;
      }

      // For other node types, return as-is
      return node;
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
      LOG.debug("  Adding projection layer to remap column aliases");

      RexBuilder rexBuilder = cluster.getRexBuilder();
      List<RexNode> projects = new ArrayList<>();
      List<String> fieldNames = new ArrayList<>();

      // Create identity projection ($0, $1, $2, ...) with target column names
      for (int i = 0; i < targetRowType.getFieldCount(); i++) {
        projects.add(rexBuilder.makeInputRef(mvScan, i));
        fieldNames.add(targetRowType.getFieldNames().get(i));

        LOG.debug("    Remapping column {}: {} -> {}", i, mvScan.getRowType().getFieldNames().get(i),
            targetRowType.getFieldNames().get(i));
      }

      return LogicalProject.create(mvScan, projects, fieldNames);
    }

  }

  /**
   * Convert a RelNode to SQL.
   */
  private String convertToSql(RelNode relNode) {
    LOG.debug("\n+++ CONVERTING RELNODE TO SQL +++");
    LOG.debug("RelNode type: {}", relNode.getClass().getSimpleName());
    LOG.debug("RelNode digest preview:");
    String digest = RelOptUtil.toString(relNode);
    LOG.debug("{}", digest.substring(0, Math.min(500, digest.length())));

    try {
      LOG.debug("Creating SQL dialect (HIVE)...");
      SqlDialect dialect = SqlDialect.DatabaseProduct.HIVE.getDialect();
      LOG.debug("Dialect: {}", dialect.getClass().getSimpleName());

      LOG.debug("Creating RelToSqlConverter...");
      RelToSqlConverter converter = new RelToSqlConverter(dialect);

      LOG.debug("Converting RelNode to SqlNode...");
      SqlNode sqlNode = converter.visitChild(0, relNode).asStatement();
      LOG.debug("SqlNode created: {}", sqlNode.getClass().getSimpleName());

      LOG.debug("Converting SqlNode to SQL string...");
      String sql = sqlNode.toSqlString(dialect).getSql();

      LOG.debug("SQL conversion successful!");
      LOG.debug("Generated SQL:");
      LOG.debug("{}", sql);
      LOG.debug("+++++++++++++++++++++++++++++++++\n");

      return sql;
    } catch (Exception e) {
      LOG.error("\n!!! ERROR in SQL conversion !!!");
      LOG.error("Error message: {}", e.getMessage());
      LOG.error("Error class: {}", e.getClass().getName());
      LOG.error("Stack trace:", e);

      // Fallback to explain string
      String fallback = "-- Rewritten query (RelNode):\n-- " + RelOptUtil.toString(relNode);
      LOG.debug("Using fallback explain string");
      LOG.debug("{}", fallback);
      LOG.debug("+++++++++++++++++++++++++++++++++\n");
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
      LOG.debug("SyntheticMvTable constructor called for: {}", qualifiedName);
    }

    @Override
    public List<String> getQualifiedName() {
      LOG.debug("SyntheticMvTable.getQualifiedName() -> {}", qualifiedName);
      return qualifiedName;
    }

    @Override
    public double getRowCount() {
      LOG.debug("SyntheticMvTable.getRowCount() -> 100.0");
      return 100.0; // Synthetic estimate
    }

    @Override
    public RelDataType getRowType() {
      LOG.debug("SyntheticMvTable.getRowType() -> {}", rowType);
      return rowType;
    }

    @Override
    public RelOptSchema getRelOptSchema() {
      LOG.debug("SyntheticMvTable.getRelOptSchema() -> null");
      return null;
    }

    @Override
    public RelNode toRel(ToRelContext context) {
      LOG.debug("SyntheticMvTable.toRel() called");
      RelNode result = LogicalTableScan.create(context.getCluster(), this);
      LOG.debug("SyntheticMvTable.toRel() -> {}", result.getClass().getSimpleName());
      return result;
    }

    @Override
    public List<RelCollation> getCollationList() {
      LOG.debug("SyntheticMvTable.getCollationList() -> empty");
      return Collections.emptyList();
    }

    @Override
    public RelDistribution getDistribution() {
      LOG.debug("SyntheticMvTable.getDistribution() -> BROADCAST_DISTRIBUTED");
      return RelDistributions.BROADCAST_DISTRIBUTED;
    }

    @Override
    public boolean isKey(org.apache.calcite.util.ImmutableBitSet columns) {
      LOG.debug("SyntheticMvTable.isKey() -> false");
      return false;
    }

    @Override
    public List<RelReferentialConstraint> getReferentialConstraints() {
      LOG.debug("SyntheticMvTable.getReferentialConstraints() -> empty");
      return Collections.emptyList();
    }

    @Override
    public org.apache.calcite.linq4j.tree.Expression getExpression(Class clazz) {
      LOG.debug("SyntheticMvTable.getExpression() called - throwing UnsupportedOperationException");
      throw new UnsupportedOperationException("getExpression not supported for synthetic MV tables");
    }

    @Override
    public RelOptTable extend(List<RelDataTypeField> extendedFields) {
      LOG.debug("SyntheticMvTable.extend() called - throwing UnsupportedOperationException");
      throw new UnsupportedOperationException("extend not supported for synthetic MV tables");
    }

    @Override
    public List<ColumnStrategy> getColumnStrategies() {
      return Collections.emptyList();
    }

    @Override
    public <T> T unwrap(Class<T> clazz) {
      LOG.debug("SyntheticMvTable.unwrap() called for class: {}", clazz.getName());
      if (clazz.isInstance(table)) {
        LOG.debug("SyntheticMvTable.unwrap() -> returning wrapped table");
        return clazz.cast(table);
      }
      LOG.debug("SyntheticMvTable.unwrap() -> returning null");
      return null;
    }

    @Override
    public String toString() {
      return "SyntheticMvTable[" + String.join(".", qualifiedName) + "]";
    }
  }
}

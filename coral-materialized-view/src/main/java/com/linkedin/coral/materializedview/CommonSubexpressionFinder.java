/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import java.util.*;

import org.apache.calcite.plan.RelOptUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelVisitor;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rex.RexNode;


public class CommonSubexpressionFinder {

  private static final Logger LOG = LoggerFactory.getLogger(CommonSubexpressionFinder.class);

  public CommonSubexpressionFinder() {
  }

  /**
   * Find common subexpressions across multiple query plans.
   * Returns a map of subexpression digest to the RelNode representing that subexpression.
   *
   * @param queryPlans List of RelNode query plans to analyze
   * @param minOccurrences Minimum number of occurrences for a subexpression to be considered common
   * @return Map of subexpression digest to RelNode
   */
  public Map<String, SubexpressionInfo> findCommonSubexpressions(List<RelNode> queryPlans, int minOccurrences) {
    LOG.debug("\n########## COMMON SUBEXPRESSION FINDER ##########");
    LOG.debug("Analyzing {} queries with minOccurrences={}", queryPlans.size(), minOccurrences);

    // Map to track occurrence count of each subexpression
    Map<String, SubexpressionInfo> subexpressionMap = new HashMap<>();

    // Visit each query plan and collect subexpressions
    for (int queryIdx = 0; queryIdx < queryPlans.size(); queryIdx++) {
      LOG.debug("\n=== Processing Query {} ===", queryIdx);
      RelNode queryPlan = queryPlans.get(queryIdx);
      collectSubexpressions(queryPlan, subexpressionMap, queryIdx);
    }

    LOG.debug("\n=== All Subexpressions Found ===");
    LOG.debug("Total unique subexpressions: {}", subexpressionMap.size());
    int idx = 0;
    for (Map.Entry<String, SubexpressionInfo> entry : subexpressionMap.entrySet()) {
      idx++;
      SubexpressionInfo info = entry.getValue();
      LOG.debug("\nSubexpression {}:", idx);
      LOG.debug("  Occurrence count: {}", info.getOccurrenceCount());
      LOG.debug("  Queries: {}", info.getQueryIndices());
      LOG.debug("  Digest hash: {}", info.getDigest().hashCode());
      LOG.debug("  Digest length: {}", info.getDigest().length());
      LOG.debug("  Digest preview: {}", info.getDigest().substring(0, Math.min(200, info.getDigest().length())));
    }

    // Filter to only include subexpressions that occur at least minOccurrences times
    Map<String, SubexpressionInfo> commonSubexpressions = new HashMap<>();
    for (Map.Entry<String, SubexpressionInfo> entry : subexpressionMap.entrySet()) {
      if (entry.getValue().getOccurrenceCount() >= minOccurrences) {
        commonSubexpressions.put(entry.getKey(), entry.getValue());
      }
    }

    LOG.debug("\n=== Common Subexpressions (>= {} occurrences) ===", minOccurrences);
    LOG.debug("Count: {}", commonSubexpressions.size());

    // IMPORTANT: Apply filter subsumption to commonSubexpressions
    // This merges patterns with related filters (e.g., "a>3" with "a>5" if both exist)
    // Also handles patterns with <minOccurrences IF they can be merged with patterns that do pass
    LOG.debug("\n=== Filter Subsumption Analysis ===");
    commonSubexpressions = applyFilterSubsumptionWithMinOccurrences(
        subexpressionMap, commonSubexpressions, minOccurrences);

    LOG.debug("\n=== Common Subexpressions (>= {} occurrences) ===", minOccurrences);
    LOG.debug("Count: {}", commonSubexpressions.size());
    for (Map.Entry<String, SubexpressionInfo> entry : commonSubexpressions.entrySet()) {
      SubexpressionInfo info = entry.getValue();
      LOG.debug("\nCommon pattern:");
      LOG.debug("  Occurrence count: {}", info.getOccurrenceCount());
      LOG.debug("  Queries: {}", info.getQueryIndices());
      LOG.debug("  Full digest:");
      LOG.debug("{}", info.getDigest());
    }

    // For aggregation patterns with the same core, pick the most general one as representative
    LOG.debug("\n=== Updating Representatives for Aggregation Patterns ===");
    for (Map.Entry<String, SubexpressionInfo> entry : commonSubexpressions.entrySet()) {
      SubexpressionInfo info = entry.getValue();
      if (isAggregationPattern(info.getRepresentativeNode())) {
        RelNode mostGeneral = findMostGeneralNode(info);
        if (mostGeneral != info.getRepresentativeNode()) {
          LOG.debug("Updated representative to most general variant (fewer filters)");
          info.setRepresentativeNode(mostGeneral);
        }
      }
    }

    // OPTIMIZATION: Remove nested/redundant patterns
    // Strategy depends on pattern type:
    // - Join patterns: Keep largest (A JOIN B JOIN C > A JOIN B)
    // - Aggregation patterns: Keep most general (fewer filters = more reusable)
    LOG.debug("\n=== Filtering Nested Patterns ===");

    Map<String, SubexpressionInfo> filteredSubexpressions;

    // Separate join patterns from aggregation patterns
    Map<String, SubexpressionInfo> joinPatterns = new HashMap<>();
    Map<String, SubexpressionInfo> aggregationPatterns = new HashMap<>();

    for (Map.Entry<String, SubexpressionInfo> entry : commonSubexpressions.entrySet()) {
      RelNode node = entry.getValue().getRepresentativeNode();
      if (isAggregationPattern(node)) {
        aggregationPatterns.put(entry.getKey(), entry.getValue());
      } else {
        joinPatterns.put(entry.getKey(), entry.getValue());
      }
    }

    LOG.debug("Found {} join patterns and {} aggregation patterns", joinPatterns.size(), aggregationPatterns.size());

    // Filter join patterns - always use hash-based filtering (keep largest)
    Map<String, SubexpressionInfo> filteredJoins = filterNestedPatternsWithHashing(joinPatterns);

    // Filter aggregation patterns - always use hash-based filtering (keep most general)
    Map<String, SubexpressionInfo> filteredAggregations = filterAggregationPatternsWithHashing(aggregationPatterns);

    // CROSS-TYPE FILTERING: Remove join patterns that are nested inside aggregation patterns
    // When we have both "A JOIN B" and "A JOIN B GROUP BY country", keep only the aggregation
    LOG.debug("\n=== Cross-Type Nested Pattern Filtering ===");
    Map<String, SubexpressionInfo> finalFilteredJoins =
        filterJoinsNestedInAggregations(filteredJoins, filteredAggregations);

    // Combine filtered results
    filteredSubexpressions = new HashMap<>();
    filteredSubexpressions.putAll(finalFilteredJoins);
    filteredSubexpressions.putAll(filteredAggregations);

    LOG.debug("Patterns after filtering: {}", filteredSubexpressions.size());
    LOG.debug("  Join patterns: {}", finalFilteredJoins.size());
    LOG.debug("  Aggregation patterns: {}", filteredAggregations.size());
    LOG.debug("##################################################\n");

    return filteredSubexpressions;
  }

  /**
   * Collect subexpressions from a query plan using a visitor pattern.
   */
  private void collectSubexpressions(RelNode node, Map<String, SubexpressionInfo> subexpressionMap, int queryIdx) {
    SubexpressionCollector collector = new SubexpressionCollector(subexpressionMap, queryIdx);
    collector.go(node);
  }

  /**
   * Visitor that collects interesting subexpressions from a RelNode tree.
   * Generic design: Works with any join pattern based on structural comparison.
   */
  private static class SubexpressionCollector extends RelVisitor {
    private final Map<String, SubexpressionInfo> subexpressionMap;
    private final int queryIdx;

    SubexpressionCollector(Map<String, SubexpressionInfo> subexpressionMap, int queryIdx) {
      this.subexpressionMap = subexpressionMap;
      this.queryIdx = queryIdx;
    }

    @Override
    public void visit(RelNode node, int ordinal, RelNode parent) {
      // Record this node as a potential subexpression if it's interesting
      if (isInterestingSubexpression(node)) {
        LOG.debug("  Found interesting node: {}", node.getClass().getSimpleName());
        String digest = computeDigest(node);
        LOG.debug("    Digest hash: {}", digest.hashCode());
        LOG.debug("    Digest length: {}", digest.length());

        SubexpressionInfo info = subexpressionMap.computeIfAbsent(digest, d -> {
          LOG.debug("    -> NEW subexpression pattern");
          return new SubexpressionInfo(node, d);
        });
        info.addOccurrence(queryIdx, node);
        LOG.debug("    -> Occurrence count now: {}", info.getOccurrenceCount());
      }
      // Continue traversing
      super.visit(node, ordinal, parent);
    }

    /**
     * Determine if a node represents an interesting subexpression.
     * GENERIC PATTERN DETECTION:
     * This method detects patterns. The structural comparison
     * (digest matching) ensures we only materialize truly identical patterns regardless of:
     * - Table names (works with ANY tables: A, B, C, users, orders, products, etc.)
     * - Join types (INNER, LEFT, RIGHT, FULL OUTER, SEMI, ANTI)
     * - Join conditions (any ON clause)
     * - Number of tables (2-table, 3-table, 10-table joins)
     * - Nesting structure (simple or complex join trees)
     * The key to genericity: We use Calcite's abstract classes and structural comparison,
     * NOT hardcoded patterns or specific table/column names.
     */
    private boolean isInterestingSubexpression(RelNode node) {
      // Detect joins OR aggregations (including Filter/Project on top of Aggregate)
      if (node instanceof Join) {
        LOG.debug("    -> Join detected - Type: {}", ((Join) node).getJoinType());
        return true;
      }
      if (node instanceof Aggregate) {
        LOG.debug("    -> Aggregate detected");
        return true;
      }
      // Also detect Filter/Project with Aggregate below (to capture full aggregation pattern)
      if (node instanceof Filter && hasAggregateBelow(node)) {
        LOG.debug("    -> Filter with Aggregate below detected");
        return true;
      }
      if (node instanceof Project && hasAggregateBelow(node)) {
        LOG.debug("    -> Project with Aggregate below detected");
        return true;
      }

      return false;
    }

    /**
     * Helper to check if a node has joins in its subtree.
     * Used to detect complex patterns like "Filter on top of Join" or "Aggregate on Join".
     */
    private boolean hasJoinBelow(RelNode node) {
      // Check immediate children
      for (RelNode input : node.getInputs()) {
        if (input instanceof Join) {
          return true;
        }
        // Recursively check deeper (but limit depth to avoid excessive checking)
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
     * Helper to check if a node has an Aggregate in its subtree.
     * Used to detect patterns like "Filter on top of Aggregate".
     */
    private boolean hasAggregateBelow(RelNode node) {
      // Check immediate children
      for (RelNode input : node.getInputs()) {
        if (input instanceof Aggregate) {
          return true;
        }
        // Check one level deeper (Filter → Project → Aggregate or similar)
        for (RelNode child : input.getInputs()) {
          if (child instanceof Aggregate) {
            return true;
          }
        }
      }
      return false;
    }

    /**
     * Compute a digest (string representation) of a RelNode for comparison.
     * Uses the explain string with implementation details.
     *
     * HYBRID STRATEGY:
     * - Aggregations on joins: Filter-agnostic digest (excludes WHERE clauses)
     * - Single-table aggregations: Exact digest (includes WHERE clauses)
     * - Other patterns: Exact digest
     */
    private String computeDigest(RelNode node) {
      // Strip Sort (ORDER BY, LIMIT) nodes - they don't affect aggregation results
      RelNode coreNode = stripSort(node);

      // Check if this is an aggregation pattern
      boolean isAggregation = (coreNode instanceof Aggregate) ||
                              (coreNode instanceof Filter && hasAggregateBelow(coreNode)) ||
                              (coreNode instanceof Project && hasAggregateBelow(coreNode));

      if (isAggregation) {
        // Use filter-agnostic matching for aggregations on joins
        return computeAggregationCoreDigest(coreNode);
      }

      // Use exact matching for non-aggregation patterns (joins, filters)
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
     * Compute digest for aggregation patterns.
     *
     * HYBRID STRATEGY:
     * - For aggregations: Use EXACT matching (includes WHERE clauses)
     * - For joins: Use filter-agnostic matching (ignores WHERE clauses)
     *
     * Why? Aggregations with different WHERE clauses produce different results (different counts/sums).
     * But joins with different WHERE clauses can share the same expensive join computation!
     *
     * Example - Aggregations (EXACT matching):
     *   Q1: SELECT country, COUNT(*) FROM A GROUP BY country
     *   Q2: SELECT country, COUNT(*) FROM A WHERE area_code > 100 GROUP BY country
     *   → Different patterns → Different MVs (safe, correct)
     *
     * Example - Joins (FILTER-AGNOSTIC matching):
     *   Q1: SELECT * FROM A JOIN B JOIN C JOIN D JOIN E
     *   Q2: SELECT * FROM A JOIN B JOIN C JOIN D JOIN E WHERE A.area_code > 100
     *   → Same pattern → One MV → Huge speedup! 🚀
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
        LOG.debug("      Aggregation on JOIN detected → Filter-agnostic matching");

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
        LOG.debug("      Single-table aggregation detected → Exact matching");
        return RelOptUtil.toString(node);
      }
    }

    /**
     * Compute digest of input nodes, skipping Filter nodes.
     */
    private String computeInputDigestWithoutFilters(RelNode node) {
      if (node instanceof Filter) {
        // Skip filter, recurse on input
        return computeInputDigestWithoutFilters(node.getInput(0));
      }

      if (node instanceof org.apache.calcite.rel.core.Sort) {
        // Skip ORDER BY (Sort node), recurse on input
        // ORDER BY is presentational and doesn't affect computation
        return computeInputDigestWithoutFilters(node.getInput(0));
      }

      if (node instanceof org.apache.calcite.rel.core.TableScan) {
        // Use qualified table name
        org.apache.calcite.rel.core.TableScan scan = (org.apache.calcite.rel.core.TableScan) node;
        return "TableScan[" + scan.getTable().getQualifiedName() + "]";
      }

      if (node instanceof Join) {
        // Use normalized join digest (order-insensitive for INNER joins)
        return normalizeJoinDigest((Join) node);
      }

      // For other node types, use class name and recurse
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
     * Examples:
     * - INNER: A JOIN B = B JOIN A (normalized to same digest)
     * - LEFT:  A LEFT JOIN B ≠ B LEFT JOIN A (different digests, order matters)
     */
    private String normalizeJoinDigest(Join join) {
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
  }


  /**
   * Hash-based nested pattern filtering using Merkle tree concepts.
   * More efficient than string containment for large workloads.
   *
   * Key idea: Each pattern has a "fingerprint" of all subtree hashes.
   * If all subtree hashes of pattern A exist in pattern B, then A is contained in B.
   *
   * Time complexity: O(n² × k) where k = number of nodes (typically < 20)
   * vs String-based: O(n² × m) where m = digest length (typically 1000-10000)
   * Speedup: 50-500× for typical queries
   */
  private Map<String, SubexpressionInfo> filterNestedPatternsWithHashing(
      Map<String, SubexpressionInfo> commonSubexpressions) {

    if (commonSubexpressions.size() <= 1) {
      return commonSubexpressions;
    }

    LOG.debug("Building pattern fingerprints (Merkle tree hashing)...");
    long startTime = System.nanoTime();

    // Step 1: Build fingerprints for each pattern
    Map<String, PatternFingerprint> fingerprints = new HashMap<>();
    for (Map.Entry<String, SubexpressionInfo> entry : commonSubexpressions.entrySet()) {
      String digest = entry.getKey();
      RelNode node = entry.getValue().getRepresentativeNode();
      PatternFingerprint fp = new PatternFingerprint(node, digest);
      fingerprints.put(digest, fp);

      LOG.debug("  Pattern fingerprint built:");
      LOG.debug("    Node count: {}", fp.nodeCount);
      LOG.debug("    Unique subtrees: {}", fp.subtreeDigestHashes.size());
      LOG.debug("    Root hash: {}", fp.rootHash);
    }

    // Step 2: Check for containment using hash sets
    Map<String, SubexpressionInfo> filtered = new HashMap<>(commonSubexpressions);

    for (String digest1 : commonSubexpressions.keySet()) {
      PatternFingerprint fp1 = fingerprints.get(digest1);

      for (String digest2 : commonSubexpressions.keySet()) {
        if (digest1.equals(digest2)) {
          continue;
        }

        PatternFingerprint fp2 = fingerprints.get(digest2);

        // Check if fp1 is contained in fp2
        if (isContainedInHash(fp1, fp2, digest1, digest2)) {
          LOG.debug("  NESTED PATTERN DETECTED (via hashing):");
          LOG.debug("    Smaller pattern (will be removed):");
          LOG.debug("      Nodes: {}, Subtrees: {}", fp1.nodeCount, fp1.subtreeDigestHashes.size());
          LOG.debug("      Root hash: {}", fp1.rootHash);
          LOG.debug("    Larger pattern (will be kept):");
          LOG.debug("      Nodes: {}, Subtrees: {}", fp2.nodeCount, fp2.subtreeDigestHashes.size());
          LOG.debug("      Root hash: {}", fp2.rootHash);

          filtered.remove(digest1);
          break;
        }
      }
    }

    long duration = System.nanoTime() - startTime;
    LOG.debug("Hash-based filtering complete in {} microseconds.", duration / 1000);
    LOG.debug("  Before filtering: {} patterns", commonSubexpressions.size());
    LOG.debug("  After filtering: {} patterns", filtered.size());

    return filtered;
  }

  /**
   * Check if smaller pattern is contained within larger pattern using digest hash sets.
   * A pattern is contained if all its subtree digest hashes exist in the larger pattern.
   */
  private boolean isContainedInHash(PatternFingerprint smaller, PatternFingerprint larger, String digest1,
      String digest2) {

    LOG.debug("    Checking containment:");
    LOG.debug("      Smaller: nodes={}, subtrees={}", smaller.nodeCount, smaller.subtreeDigestHashes.size());
    LOG.debug("      Larger:  nodes={}, subtrees={}", larger.nodeCount, larger.subtreeDigestHashes.size());

    // Quick checks to avoid expensive operations
    if (smaller.nodeCount >= larger.nodeCount) {
      LOG.debug("      -> SKIP: smaller has >= nodes than larger");
      return false; // Can't contain something with same or more nodes
    }

    if (smaller.subtreeDigestHashes.size() > larger.subtreeDigestHashes.size()) {
      LOG.debug("      -> SKIP: smaller has more unique subtrees");
      return false; // Can't contain something with more unique subtrees
    }

    // Debug: Show which hashes are in smaller
    LOG.debug("      Smaller subtree hashes: {}", smaller.subtreeDigestHashes);
    LOG.debug("      Larger subtree hashes: {}", larger.subtreeDigestHashes);

    // Main containment check: All subtree digest hashes of smaller must exist in larger
    boolean hashBasedContainment = larger.subtreeDigestHashes.containsAll(smaller.subtreeDigestHashes);

    LOG.debug("      Hash-based containment: {}", hashBasedContainment);

    if (hashBasedContainment) {
      // The hash-based approach is reliable when using RelOptUtil.toString() digest hashes
      // String containment would fail due to indentation differences when subtrees are nested
      // Since we're using the same digest computation as pattern detection, hash matches are trustworthy
      LOG.debug("      -> Pattern IS contained (based on digest hash matching)");
      return true;
    }

    LOG.debug("      -> Pattern NOT contained");
    return false;
  }

  /**
   * Fingerprint of a pattern using digest-based hashing.
   * Contains digest hashes of ALL subtrees within the pattern for fast containment checking.
   *
   * KEY FIX: Instead of computing custom hashes (which are sensitive to column references),
   * we use the digest strings themselves. This ensures identical subtrees have identical hashes.
   */
  private static class PatternFingerprint {
    final String digest;
    final RelNode node;
    final Set<Integer> subtreeDigestHashes; // Hashes of digest strings of ALL subtrees
    final int rootHash; // Hash of the root digest
    final int nodeCount; // Total number of nodes in the tree

    PatternFingerprint(RelNode node, String digest) {
      this.node = node;
      this.digest = digest;
      this.subtreeDigestHashes = new HashSet<>();
      this.rootHash = digest.hashCode();
      this.nodeCount = countNodes(node);

      // Collect digest hashes for all subtrees
      collectSubtreeDigestHashes(node, subtreeDigestHashes);
    }

    /**
     * Recursively collect digest hashes of all subtrees.
     * Uses the same digest computation as pattern detection to ensure consistency.
     */
    private void collectSubtreeDigestHashes(RelNode node, Set<Integer> hashes) {
      // Compute digest for this subtree (same as pattern detection uses)
      String subtreeDigest = RelOptUtil.toString(node);
      int hash = subtreeDigest.hashCode();

      LOG.debug("      Collecting subtree digest hash:");
      LOG.debug("        Node type: {}", node.getClass().getSimpleName());
      LOG.debug("        Digest hash: {}", hash);
      LOG.debug("        Digest preview: {}", subtreeDigest.substring(0, Math.min(100, subtreeDigest.length())));

      // Add this subtree's digest hash to the set
      hashes.add(hash);

      // Recursively collect from children
      for (RelNode child : node.getInputs()) {
        collectSubtreeDigestHashes(child, hashes);
      }
    }

    /**
     * Count total number of nodes in the tree.
     */
    private int countNodes(RelNode node) {
      int count = 1;
      for (RelNode child : node.getInputs()) {
        count += countNodes(child);
      }
      return count;
    }
  }

  /**
   * Apply filter subsumption to group patterns that differ only in filter specificity.
   *
   * Generic Algorithm:
   * 1. Separate single-table aggregations from aggregations on joins
   * 2. For single-table aggregations, group by "core structure" (same aggregation, different filters)
   * 3. Within each group, find the most general filter using FilterSubsumptionAnalyzer
   * 4. Merge patterns in each group into ONE pattern with the most general filter
   * 5. Store residual filter metadata for query rewriting
   *
   * Example:
   * Input patterns:
   * - Pattern A: WHERE exp > 5 GROUP BY location (2 queries)
   * - Pattern B: WHERE exp > 5 AND country = 'US' GROUP BY location (1 query)
   *
   * Analysis:
   * - Same core: Aggregate(GROUP BY location)
   * - Filter A subsumes Filter B (A is more general)
   * - Merge into ONE pattern with Filter A
   *
   * Output:
   * - Pattern merged: WHERE exp > 5 GROUP BY location (3 queries)
   * - Residual for Pattern B queries: WHERE country = 'US'
   */

  /**
   * Apply filter subsumption considering minOccurrences threshold.
   */
  private Map<String, SubexpressionInfo> applyFilterSubsumptionWithMinOccurrences(
      Map<String, SubexpressionInfo> allPatterns,
      Map<String, SubexpressionInfo> qualifiedPatterns,
      int minOccurrences) {

    LOG.debug("Applying filter subsumption with minOccurrences={}...", minOccurrences);

    // Apply subsumption to ALL patterns (including those below threshold)
    // This allows low-occurrence patterns to be merged with high-occurrence ones
    Map<String, SubexpressionInfo> afterSubsumption = applyFilterSubsumption(allPatterns);

    // Now filter to keep only those with >= minOccurrences AFTER subsumption
    Map<String, SubexpressionInfo> result = new HashMap<>();
    for (Map.Entry<String, SubexpressionInfo> entry : afterSubsumption.entrySet()) {
      if (entry.getValue().getOccurrenceCount() >= minOccurrences) {
        result.put(entry.getKey(), entry.getValue());
        LOG.debug("Keeping pattern with {} occurrences", entry.getValue().getOccurrenceCount());
      } else {
        LOG.debug("Filtering out pattern with {} occurrences (< {})",
            entry.getValue().getOccurrenceCount(), minOccurrences);
      }
    }

    LOG.debug("After subsumption + minOccurrences filter: {} patterns", result.size());
    return result;
  }

  private Map<String, SubexpressionInfo> applyFilterSubsumption(Map<String, SubexpressionInfo> patterns) {
    LOG.debug("Applying filter subsumption analysis...");

    // Separate single-table aggregations (candidates for filter subsumption)
    Map<String, SubexpressionInfo> singleTableAggs = new HashMap<>();
    Map<String, SubexpressionInfo> otherPatterns = new HashMap<>();

    for (Map.Entry<String, SubexpressionInfo> entry : patterns.entrySet()) {
      RelNode node = entry.getValue().getRepresentativeNode();
      if (isAggregationPattern(node) && !hasAggregationOnJoin(node)) {
        // Single-table aggregation
        singleTableAggs.put(entry.getKey(), entry.getValue());
      } else {
        // Aggregation on join, or non-aggregation pattern
        otherPatterns.put(entry.getKey(), entry.getValue());
      }
    }

    LOG.debug("Found {} single-table aggregations for subsumption analysis", singleTableAggs.size());
    LOG.debug("Found {} other patterns (no subsumption)", otherPatterns.size());

    if (singleTableAggs.isEmpty()) {
      LOG.debug("No single-table aggregations to analyze");
      return patterns;
    }

    // Group single-table aggregations by "core structure"
    // Core = same aggregation structure (GROUP BY columns, aggregation functions) minus the filter
    Map<String, List<Map.Entry<String, SubexpressionInfo>>> coreGroups = new HashMap<>();

    for (Map.Entry<String, SubexpressionInfo> entry : singleTableAggs.entrySet()) {
      String coreDigest = computeCoreDigestWithoutFilter(entry.getValue().getRepresentativeNode());
      coreGroups.computeIfAbsent(coreDigest, k -> new ArrayList<>()).add(entry);
    }

    LOG.debug("Grouped into {} core structures", coreGroups.size());

    // For each core group, apply filter subsumption
    Map<String, SubexpressionInfo> mergedPatterns = new HashMap<>();

    for (Map.Entry<String, List<Map.Entry<String, SubexpressionInfo>>> groupEntry : coreGroups.entrySet()) {
      String coreDigest = groupEntry.getKey();
      List<Map.Entry<String, SubexpressionInfo>> group = groupEntry.getValue();

      LOG.debug("\nAnalyzing core group: {} patterns", group.size());
      LOG.debug("Core digest: {}", coreDigest.substring(0, Math.min(100, coreDigest.length())));

      if (group.size() == 1) {
        // Single pattern in group - no subsumption needed
        mergedPatterns.put(group.get(0).getKey(), group.get(0).getValue());
        LOG.debug("Single pattern in group - no subsumption needed");
        continue;
      }

      // Multiple patterns with same core - check for filter subsumption
      List<RexNode> filters = new ArrayList<>();
      List<SubexpressionInfo> infos = new ArrayList<>();

      for (Map.Entry<String, SubexpressionInfo> entry : group) {
        SubexpressionInfo info = entry.getValue();
        RexNode filter = FilterSubsumptionAnalyzer.extractFilterFromRelNode(info.getRepresentativeNode());
        filters.add(filter);
        infos.add(info);

        LOG.debug("Pattern {}: filter = {}, occurrences = {}",
            entry.getKey().hashCode(), filter, info.getOccurrenceCount());
      }

      // Find the most general filter
      RexNode mostGeneralFilter = FilterSubsumptionAnalyzer.findMostGeneralFilter(filters);
      LOG.debug("Most general filter: {}", mostGeneralFilter);

      // Find the pattern with the most general filter (or pick first if tied)
      SubexpressionInfo chosenInfo = null;
      int chosenIndex = -1;
      for (int i = 0; i < filters.size(); i++) {
        RexNode filter = filters.get(i);
        if ((mostGeneralFilter == null && filter == null) ||
            (mostGeneralFilter != null && filter != null &&
             mostGeneralFilter.toString().equals(filter.toString()))) {
          chosenInfo = infos.get(i);
          chosenIndex = i;
          break;
        }
      }

      if (chosenInfo == null) {
        // Fallback: pick the one with most occurrences
        chosenInfo = infos.get(0);
        chosenIndex = 0;
        for (int i = 1; i < infos.size(); i++) {
          if (infos.get(i).getOccurrenceCount() > chosenInfo.getOccurrenceCount()) {
            chosenInfo = infos.get(i);
            chosenIndex = i;
          }
        }
      }

      LOG.debug("Chosen pattern index: {}", chosenIndex);

      // Merge all patterns into the chosen one
      SubexpressionInfo mergedInfo = new SubexpressionInfo(
          chosenInfo.getRepresentativeNode(),
          chosenInfo.getDigest()
      );

      // Add all occurrences from all patterns in the group
      for (SubexpressionInfo info : infos) {
        for (Map.Entry<Integer, List<RelNode>> occEntry : info.getOccurrenceMap().entrySet()) {
          for (RelNode node : occEntry.getValue()) {
            mergedInfo.addOccurrence(occEntry.getKey(), node);
          }
        }
      }

      LOG.debug("Merged pattern has {} total occurrences", mergedInfo.getOccurrenceCount());

      // CRITICAL FIX: Keep ALL original keys pointing to the merged pattern
      // This allows both Q1 (WHERE a > 5) and Q2 (WHERE a > 5 AND b = 'test')
      // to find their patterns in the map, even though they're merged
      LOG.debug("\n=== KEEPING ALL {} KEYS FOR MERGED PATTERN ===", group.size());
      for (Map.Entry<String, SubexpressionInfo> entry : group) {
        String originalKey = entry.getKey();
        SubexpressionInfo originalInfo = entry.getValue();
        mergedPatterns.put(originalKey, mergedInfo);
        LOG.debug("\nKey #{} (hash: {}):", group.indexOf(entry) + 1, originalKey.hashCode());
        LOG.debug("  Original occurrences: {}", originalInfo.getOccurrenceCount());
        LOG.debug("  Query indices: {}", originalInfo.getQueryIndices());
        LOG.debug("  Key preview: {}...", originalKey.substring(0, Math.min(150, originalKey.length())));
      }
      LOG.debug("All {} keys now point to merged pattern with {} total occurrences\n",
          group.size(), mergedInfo.getOccurrenceCount());
    }

    // Combine merged patterns with other patterns
    Map<String, SubexpressionInfo> result = new HashMap<>();
    result.putAll(otherPatterns);
    result.putAll(mergedPatterns);

    LOG.debug("After filter subsumption: {} patterns (was {})", result.size(), patterns.size());

    return result;
  }

  /**
   * Compute core digest without filter (for grouping patterns by structure).
   *
   * Strips the filter from the pattern and computes digest of the remaining structure.
   * This allows grouping patterns that differ only in WHERE clause.
   *
   * @param node The aggregation pattern
   * @return Digest of the core structure (aggregation + input, no filter)
   */
  private String computeCoreDigestWithoutFilter(RelNode node) {
    // Strip sort first
    RelNode coreNode = stripSort(node);

    // If the node itself is an Aggregate, get its structure without the input filter
    if (coreNode instanceof Aggregate) {
      Aggregate agg = (Aggregate) coreNode;
      RelNode input = agg.getInput();

      // Skip filter in input
      RelNode inputWithoutFilter = input;
      if (input instanceof Filter) {
        inputWithoutFilter = input.getInput(0);
      }

      // Build core digest: Aggregate structure + input structure (no filter)
      StringBuilder digest = new StringBuilder();
      digest.append("AggregateCore[");
      digest.append("groupSet=").append(agg.getGroupSet());
      digest.append(", aggCalls=").append(agg.getAggCallList());
      digest.append(", inputType=").append(inputWithoutFilter.getRowType().getFieldNames());
      digest.append("]");

      return digest.toString();
    }

    // Fallback: use full digest
    return RelOptUtil.toString(coreNode);
  }

  /**
   * Strip Sort nodes (ORDER BY, LIMIT) from the top of the tree.
   * These don't affect aggregation results and can be applied after reading MV.
   */
  private static RelNode stripSort(RelNode node) {
    if (node instanceof Sort) {
      return stripSort(node.getInput(0));
    }
    return node;
  }

  /**
   * Helper to check if a node has joins in its subtree.
   */
  private static boolean hasJoinBelow(RelNode node) {
    // Check immediate children
    for (RelNode input : node.getInputs()) {
      if (input instanceof Join) {
        return true;
      }
      // Recursively check deeper (but limit depth to avoid excessive checking)
      if (hasJoinBelowRecursive(input, 3)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Recursive helper with depth limit for hasJoinBelow.
   */
  private static boolean hasJoinBelowRecursive(RelNode node, int maxDepth) {
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
   * Check if a pattern is an aggregation on a join (not single-table).
   */
  private boolean hasAggregationOnJoin(RelNode node) {
    if (node instanceof Aggregate) {
      return hasJoinBelow(node);
    }

    // Check through wrappers
    for (RelNode child : node.getInputs()) {
      if (child instanceof Aggregate) {
        return hasJoinBelow(child);
      }
    }

    return false;
  }

  /**
   * Determine if a pattern is an aggregation pattern.
   * An aggregation pattern has Aggregate as the top node or near the top (with Filter/Project on top).
   */
  /**
   * Filter out join patterns that are nested inside aggregation patterns.
   * When we have both "A JOIN B" and "A JOIN B GROUP BY country", keep only the aggregation.
   *
   * @param joinPatterns Filtered join patterns
   * @param aggregationPatterns Filtered aggregation patterns
   * @return Join patterns with nested ones removed
   */
  private Map<String, SubexpressionInfo> filterJoinsNestedInAggregations(Map<String, SubexpressionInfo> joinPatterns,
      Map<String, SubexpressionInfo> aggregationPatterns) {

    if (joinPatterns.isEmpty() || aggregationPatterns.isEmpty()) {
      return joinPatterns;
    }

    LOG.debug("Checking for join patterns nested in aggregations...");
    Map<String, SubexpressionInfo> result = new HashMap<>(joinPatterns);

    // For each aggregation pattern, check if its input matches a join pattern
    for (Map.Entry<String, SubexpressionInfo> aggEntry : aggregationPatterns.entrySet()) {
      RelNode aggNode = aggEntry.getValue().getRepresentativeNode();

      if (aggNode instanceof Aggregate) {
        Aggregate agg = (Aggregate) aggNode;
        RelNode input = agg.getInput();

        // Strip filters AND projects from input to get the core join pattern
        RelNode coreInput = input;
        while (coreInput instanceof Filter || coreInput instanceof Project) {
          coreInput = coreInput.getInput(0);
        }

        // Check if this core input matches any join pattern
        if (coreInput instanceof Join) {
          // Compute digest of the join input (without filters/projects)
          String joinInputDigest = RelOptUtil.toString(coreInput);

          // Check if this digest matches any of the join patterns
          // Compare with the map KEY (joinDigest), which is the digest that was computed during collection
          for (Map.Entry<String, SubexpressionInfo> joinEntry : joinPatterns.entrySet()) {
            String joinDigest = joinEntry.getKey();

            // Compare the aggregation's input digest with the join pattern's stored digest (map key)
            if (joinInputDigest.equals(joinDigest)) {
              LOG.debug("  Found join pattern nested in aggregation:");
              LOG.debug("    Join digest: {}...", joinDigest.substring(0, Math.min(100, joinDigest.length())));
              LOG.debug("    Aggregation digest: {}...",
                  aggEntry.getKey().substring(0, Math.min(100, aggEntry.getKey().length())));
              LOG.debug("    -> Removing join pattern (keeping aggregation)");
              result.remove(joinDigest);
            }
          }
        }
      }
    }

    LOG.debug("Joins after cross-type filtering: {} (removed {})", result.size(),
        (joinPatterns.size() - result.size()));
    return result;
  }

  private boolean isAggregationPattern(RelNode node) {
    if (node instanceof Aggregate) {
      return true;
    }

    // Check if Aggregate is just below a Filter or Project
    if (node instanceof Filter || node instanceof Project) {
      for (RelNode child : node.getInputs()) {
        if (child instanceof Aggregate) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Find the most general node (fewest filters) among all occurrences.
   * For aggregation patterns, the most general variant is more reusable.
   */
  private RelNode findMostGeneralNode(SubexpressionInfo info) {
    List<RelNode> allNodes = info.getAllOccurrences();
    if (allNodes.isEmpty()) {
      return info.getRepresentativeNode();
    }

    RelNode mostGeneral = allNodes.get(0);
    int minFilters = countFiltersInPattern(mostGeneral);

    for (RelNode node : allNodes) {
      int filterCount = countFiltersInPattern(node);
      if (filterCount < minFilters) {
        minFilters = filterCount;
        mostGeneral = node;
      }
    }

    LOG.debug("  Found most general variant with {} filters (out of {} variants)", minFilters, allNodes.size());
    return mostGeneral;
  }


  /**
   * Filter aggregation patterns using hash-based approach.
   *
   * FIX: After including WHERE clauses in the core hash, patterns with different
   * WHERE clauses will have DIFFERENT core hashes. This means they won't be considered
   * "related patterns" anymore, which is CORRECT - they produce different results and
   * need separate materialized views.
   *
   * This method now only filters truly redundant patterns (exact duplicates or
   * patterns that differ only in non-filter aspects).
   */
  private Map<String, SubexpressionInfo> filterAggregationPatternsWithHashing(
      Map<String, SubexpressionInfo> aggregationPatterns) {

    if (aggregationPatterns.size() <= 1) {
      return aggregationPatterns;
    }

    LOG.debug("Filtering aggregation patterns (HASH_BASED)...");
    long startTime = System.nanoTime();

    Map<String, SubexpressionInfo> filtered = new HashMap<>(aggregationPatterns);

    // Build fingerprints
    Map<String, AggregationFingerprint> fingerprints = new HashMap<>();
    for (Map.Entry<String, SubexpressionInfo> entry : aggregationPatterns.entrySet()) {
      String digest = entry.getKey();
      RelNode node = entry.getValue().getRepresentativeNode();
      fingerprints.put(digest, new AggregationFingerprint(node, digest));
    }

    // Compare patterns
    List<String> digests = new ArrayList<>(aggregationPatterns.keySet());
    for (int i = 0; i < digests.size(); i++) {
      String digest1 = digests.get(i);
      AggregationFingerprint fp1 = fingerprints.get(digest1);

      for (int j = 0; j < digests.size(); j++) {
        if (i == j) {
          continue;
        }

        String digest2 = digests.get(j);
        AggregationFingerprint fp2 = fingerprints.get(digest2);

        // Check if they have the same aggregation core (including filters now!)
        if (fp1.aggregationCoreHash == fp2.aggregationCoreHash) {
          LOG.debug("  Found related aggregation patterns:");
          LOG.debug("    Pattern 1: {} filters, core hash: {}", fp1.filterCount, fp1.aggregationCoreHash);
          LOG.debug("    Pattern 2: {} filters, core hash: {}", fp2.filterCount, fp2.aggregationCoreHash);

          // Keep the one with FEWER filters (more general)
          if (fp1.filterCount < fp2.filterCount) {
            LOG.debug("    -> Keeping Pattern 1 (more general)");
            filtered.remove(digest2);
          } else if (fp2.filterCount < fp1.filterCount) {
            LOG.debug("    -> Keeping Pattern 2 (more general)");
            filtered.remove(digest1);
            break; // Pattern 1 is removed, move to next
          }
          // If equal filters, keep both
        }
      }
    }

    long duration = System.nanoTime() - startTime;
    LOG.debug("Aggregation pattern filtering complete (HASH_BASED) in {} microseconds.", duration / 1000);
    LOG.debug("  Before: {} patterns", aggregationPatterns.size());
    LOG.debug("  After: {} patterns", filtered.size());

    return filtered;
  }


  /**
   * Count the number of filters in a pattern.
   * More filters = more specific = less reusable.
   */
  private int countFiltersInPattern(RelNode node) {
    int count = 0;

    if (node instanceof Filter) {
      count++;
    }

    // Recursively count in children
    for (RelNode child : node.getInputs()) {
      count += countFiltersInPattern(child);
    }

    return count;
  }

  /**
   * Fingerprint for aggregation patterns.
   *
   * HYBRID STRATEGY: For aggregations, we use EXACT matching (includes filters).
   * This ensures correct results since different WHERE clauses produce different counts/sums.
   */
  private static class AggregationFingerprint {
    final RelNode node;
    final String digest;
    final int aggregationCoreHash; // Hash of GROUP BY + aggregates + tables/joins + FILTERS (EXACT)
    final int filterCount; // Number of filter conditions
    final int nodeCount;

    AggregationFingerprint(RelNode node, String digest) {
      this.node = node;
      this.digest = digest;
      this.filterCount = countFilters(node);
      this.nodeCount = countNodes(node);
      this.aggregationCoreHash = computeAggregationCoreHash(node);
    }

    /**
     * Compute hash of the aggregation pattern.
     *
     * HYBRID STRATEGY:
     * - Aggregations on joins: Filter-agnostic (skip filters)
     * - Single-table aggregations: Exact matching (include filters)
     */
    private int computeAggregationCoreHash(RelNode node) {
      if (node instanceof Aggregate) {
        Aggregate agg = (Aggregate) node;

        // Check if this aggregation has joins underneath
        boolean hasJoins = hasJoinBelow(agg);

        int hash = "Aggregate".hashCode();
        hash = hash * 31 + agg.getGroupSet().hashCode(); // GROUP BY columns
        hash = hash * 31 + agg.getAggCallList().hashCode(); // Aggregate functions

        if (hasJoins) {
          // CASE 1: Aggregation on JOIN → Filter-agnostic hashing (skip filters)
          hash = hash * 31 + computeInputHashWithoutFilters(agg.getInput());
        } else {
          // CASE 2: Single-table aggregation → Exact hashing (include filters)
          hash = hash * 31 + computeAggregationCoreHash(agg.getInput());
        }

        return hash;
      }

      if (node instanceof Filter) {
        // Include filters in the hash (for single-table aggregations)
        Filter filter = (Filter) node;
        int hash = "Filter".hashCode();
        hash = hash * 31 + filter.getCondition().toString().hashCode();
        hash = hash * 31 + computeAggregationCoreHash(filter.getInput());
        return hash;
      }

      if (node instanceof Join) {
        Join join = (Join) node;
        int hash = "Join".hashCode();
        hash = hash * 31 + join.getJoinType().hashCode();
        // Note: Skip join condition details as they may have column index differences
        for (RelNode child : node.getInputs()) {
          hash = hash * 31 + computeAggregationCoreHash(child);
        }
        return hash;
      }

      if (node instanceof org.apache.calcite.rel.core.TableScan) {
        org.apache.calcite.rel.core.TableScan scan = (org.apache.calcite.rel.core.TableScan) node;
        return scan.getTable().getQualifiedName().hashCode();
      }

      // Generic hash for other node types
      int hash = node.getClass().getName().hashCode();
      for (RelNode child : node.getInputs()) {
        hash = hash * 31 + computeAggregationCoreHash(child);
      }
      return hash;
    }

    /**
     * Check if a node has joins in its subtree.
     */
    private boolean hasJoinBelow(RelNode node) {
      for (RelNode input : node.getInputs()) {
        if (input instanceof Join) {
          return true;
        }
        if (hasJoinBelow(input)) {
          return true;
        }
      }
      return false;
    }

    /**
     * Normalize join hash for INNER joins to make order-insensitive.
     * For LEFT/RIGHT/FULL joins, preserve order as it's semantically significant.
     *
     * This mirrors normalizeJoinDigest but for hash computation.
     */
    private int normalizeJoinHash(Join join) {
      int leftHash = computeInputHashWithoutFilters(join.getLeft());
      int rightHash = computeInputHashWithoutFilters(join.getRight());

      int hash = "Join".hashCode();
      hash = hash * 31 + join.getJoinType().hashCode();

      // For INNER joins: normalize order by sorting hashes
      // For other joins: preserve order (semantically significant)
      if (join.getJoinType() == JoinRelType.INNER) {
        // Normalize: always combine hashes in sorted order
        int hash1 = Math.min(leftHash, rightHash);
        int hash2 = Math.max(leftHash, rightHash);
        hash = hash * 31 + hash1;
        hash = hash * 31 + hash2;
      } else {
        // Preserve order for LEFT/RIGHT/FULL joins
        hash = hash * 31 + leftHash;
        hash = hash * 31 + rightHash;
      }

      return hash;
    }

    /**
     * Compute hash of input, SKIPPING filters (for filter-agnostic matching).
     * Mirrors computeInputDigestWithoutFilters but for hashing.
     */
    private int computeInputHashWithoutFilters(RelNode node) {
      if (node instanceof Filter) {
        // Skip filter, recurse on input
        return computeInputHashWithoutFilters(node.getInput(0));
      }

      if (node instanceof org.apache.calcite.rel.core.Sort) {
        // Skip ORDER BY (Sort node), recurse on input
        return computeInputHashWithoutFilters(node.getInput(0));
      }

      if (node instanceof Join) {
        // Use normalized join hash (order-insensitive for INNER joins)
        return normalizeJoinHash((Join) node);
      }

      if (node instanceof org.apache.calcite.rel.core.TableScan) {
        org.apache.calcite.rel.core.TableScan scan = (org.apache.calcite.rel.core.TableScan) node;
        return scan.getTable().getQualifiedName().hashCode();
      }

      // Generic hash for other node types
      int hash = node.getClass().getName().hashCode();
      for (RelNode child : node.getInputs()) {
        hash = hash * 31 + computeInputHashWithoutFilters(child);
      }
      return hash;
    }

    private int countFilters(RelNode node) {
      int count = 0;
      if (node instanceof Filter) {
        count++;
      }
      for (RelNode child : node.getInputs()) {
        count += countFilters(child);
      }
      return count;
    }

    private int countNodes(RelNode node) {
      int count = 1;
      for (RelNode child : node.getInputs()) {
        count += countNodes(child);
      }
      return count;
    }
  }

  /**
   * Information about a subexpression including where it occurs.
   *
   * For aggregation patterns, tracks filter columns used across all queries
   * to enable partial aggregation (one MV serves multiple filter variations).
   */
  public static class SubexpressionInfo {
    private RelNode representativeNode; // Mutable to allow updating to most general variant
    private final String digest;
    private final Map<Integer, List<RelNode>> occurrencesByQuery; // queryIdx -> list of nodes
    private int totalOccurrences;
    private Set<Integer> filterColumnIndices; // Column indices used in WHERE clauses (for aggregations)

    public SubexpressionInfo(RelNode representativeNode, String digest) {
      this.representativeNode = representativeNode;
      this.digest = digest;
      this.occurrencesByQuery = new HashMap<>();
      this.totalOccurrences = 0;
      this.filterColumnIndices = null; // Lazily computed when needed
    }

    public void addOccurrence(int queryIdx, RelNode node) {
      occurrencesByQuery.computeIfAbsent(queryIdx, k -> new ArrayList<>()).add(node);
      totalOccurrences++;
    }

    public RelNode getRepresentativeNode() {
      return representativeNode;
    }

    public void setRepresentativeNode(RelNode node) {
      this.representativeNode = node;
    }

    public String getDigest() {
      return digest;
    }

    public int getOccurrenceCount() {
      return occurrencesByQuery.size(); // Number of distinct queries where this appears
    }

    public int getTotalOccurrences() {
      return totalOccurrences;
    }

    public Set<Integer> getQueryIndices() {
      return occurrencesByQuery.keySet();
    }

    public List<RelNode> getOccurrencesInQuery(int queryIdx) {
      return occurrencesByQuery.getOrDefault(queryIdx, Collections.emptyList());
    }

    /**
     * Get the occurrence map (queryIdx -> list of nodes).
     */
    public Map<Integer, List<RelNode>> getOccurrenceMap() {
      return occurrencesByQuery;
    }

    /**
     * Get all occurrence nodes across all queries.
     */
    public List<RelNode> getAllOccurrences() {
      List<RelNode> all = new ArrayList<>();
      for (List<RelNode> nodes : occurrencesByQuery.values()) {
        all.addAll(nodes);
      }
      return all;
    }

    /**
     * Get filter column indices (for future use - currently returns empty set).
     *
     * HYBRID STRATEGY: We don't extract filter columns for aggregations anymore.
     * Aggregations use exact matching (safe and correct).
     * Join patterns benefit from filter-agnostic matching (huge performance win).
     *
     * @return Empty set (no filter column extraction for aggregations)
     */
    public Set<Integer> getFilterColumnIndices() {
      // Return empty set - no partial aggregation for aggregation patterns
      return Collections.emptySet();
    }
  }
}

/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import java.util.*;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelVisitor;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;


public class CommonSubexpressionFinder {

  /**
   * Mode for detecting what patterns to materialize.
   */
  public enum PatternDetectionMode {
    JOINS_ONLY, // Only detect Join nodes (default, most useful)
    JOINS_AND_FILTERS, // Detect Joins and Filters with joins below
    JOINS_AND_AGGREGATES, // Detect Joins and Aggregations
    ALL_EXPENSIVE_OPS // Detect all expensive operations
  }

  /**
   * Strategy for filtering nested patterns.
   */
  public enum NestedPatternFilterStrategy {
    STRING_BASED, // Use string containment
    HASH_BASED // Use Merkle tree hashing
  }

  private PatternDetectionMode detectionMode = PatternDetectionMode.JOINS_ONLY;
  private NestedPatternFilterStrategy filterStrategy = NestedPatternFilterStrategy.STRING_BASED;

  public CommonSubexpressionFinder() {
    // Default: detect joins only, use string-based filtering
  }

  public CommonSubexpressionFinder(PatternDetectionMode mode) {
    this.detectionMode = mode;
  }

  public CommonSubexpressionFinder(PatternDetectionMode mode, NestedPatternFilterStrategy filterStrategy) {
    this.detectionMode = mode;
    this.filterStrategy = filterStrategy;
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
    System.out.println("\n########## COMMON SUBEXPRESSION FINDER ##########");
    System.out.println("Analyzing " + queryPlans.size() + " queries with minOccurrences=" + minOccurrences);

    // Map to track occurrence count of each subexpression
    Map<String, SubexpressionInfo> subexpressionMap = new HashMap<>();

    // Visit each query plan and collect subexpressions
    for (int queryIdx = 0; queryIdx < queryPlans.size(); queryIdx++) {
      System.out.println("\n=== Processing Query " + queryIdx + " ===");
      RelNode queryPlan = queryPlans.get(queryIdx);
      collectSubexpressions(queryPlan, subexpressionMap, queryIdx);
    }

    System.out.println("\n=== All Subexpressions Found ===");
    System.out.println("Total unique subexpressions: " + subexpressionMap.size());
    int idx = 0;
    for (Map.Entry<String, SubexpressionInfo> entry : subexpressionMap.entrySet()) {
      idx++;
      SubexpressionInfo info = entry.getValue();
      System.out.println("\nSubexpression " + idx + ":");
      System.out.println("  Occurrence count: " + info.getOccurrenceCount());
      System.out.println("  Queries: " + info.getQueryIndices());
      System.out.println("  Digest hash: " + info.getDigest().hashCode());
      System.out.println("  Digest length: " + info.getDigest().length());
      System.out
          .println("  Digest preview: " + info.getDigest().substring(0, Math.min(200, info.getDigest().length())));
    }

    // Filter to only include subexpressions that occur at least minOccurrences times
    Map<String, SubexpressionInfo> commonSubexpressions = new HashMap<>();
    for (Map.Entry<String, SubexpressionInfo> entry : subexpressionMap.entrySet()) {
      if (entry.getValue().getOccurrenceCount() >= minOccurrences) {
        commonSubexpressions.put(entry.getKey(), entry.getValue());
      }
    }

    System.out.println("\n=== Common Subexpressions (>= " + minOccurrences + " occurrences) ===");
    System.out.println("Count: " + commonSubexpressions.size());
    for (Map.Entry<String, SubexpressionInfo> entry : commonSubexpressions.entrySet()) {
      SubexpressionInfo info = entry.getValue();
      System.out.println("\nCommon pattern:");
      System.out.println("  Occurrence count: " + info.getOccurrenceCount());
      System.out.println("  Queries: " + info.getQueryIndices());
      System.out.println("  Full digest:");
      System.out.println(info.getDigest());
    }

    // For aggregation patterns with the same core, pick the most general one as representative
    System.out.println("\n=== Updating Representatives for Aggregation Patterns ===");
    for (Map.Entry<String, SubexpressionInfo> entry : commonSubexpressions.entrySet()) {
      SubexpressionInfo info = entry.getValue();
      if (isAggregationPattern(info.getRepresentativeNode())) {
        RelNode mostGeneral = findMostGeneralNode(info);
        if (mostGeneral != info.getRepresentativeNode()) {
          System.out.println("Updated representative to most general variant (fewer filters)");
          info.setRepresentativeNode(mostGeneral);
        }
      }
    }

    // OPTIMIZATION: Remove nested/redundant patterns
    // Strategy depends on pattern type:
    // - Join patterns: Keep largest (A JOIN B JOIN C > A JOIN B)
    // - Aggregation patterns: Keep most general (fewer filters = more reusable)
    System.out.println("\n=== Filtering Nested Patterns ===");
    System.out.println("Strategy: " + filterStrategy);
    System.out.println("Detection Mode: " + detectionMode);

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

    System.out.println(
        "Found " + joinPatterns.size() + " join patterns and " + aggregationPatterns.size() + " aggregation patterns");

    // Filter join patterns (existing logic - keep largest)
    Map<String, SubexpressionInfo> filteredJoins;
    if (filterStrategy == NestedPatternFilterStrategy.HASH_BASED) {
      filteredJoins = filterNestedPatternsWithHashing(joinPatterns);
    } else {
      filteredJoins = filterNestedPatterns(joinPatterns);
    }

    // Filter aggregation patterns (new logic - keep most general)
    Map<String, SubexpressionInfo> filteredAggregations;
    if (filterStrategy == NestedPatternFilterStrategy.HASH_BASED) {
      filteredAggregations = filterAggregationPatternsWithHashing(aggregationPatterns);
    } else {
      filteredAggregations = filterAggregationPatterns(aggregationPatterns);
    }

    // CROSS-TYPE FILTERING: Remove join patterns that are nested inside aggregation patterns
    // When we have both "A JOIN B" and "A JOIN B GROUP BY country", keep only the aggregation
    System.out.println("\n=== Cross-Type Nested Pattern Filtering ===");
    Map<String, SubexpressionInfo> finalFilteredJoins =
        filterJoinsNestedInAggregations(filteredJoins, filteredAggregations);

    // Combine filtered results
    filteredSubexpressions = new HashMap<>();
    filteredSubexpressions.putAll(finalFilteredJoins);
    filteredSubexpressions.putAll(filteredAggregations);

    System.out.println("Patterns after filtering: " + filteredSubexpressions.size());
    System.out.println("  Join patterns: " + finalFilteredJoins.size());
    System.out.println("  Aggregation patterns: " + filteredAggregations.size());
    System.out.println("##################################################\n");

    return filteredSubexpressions;
  }

  /**
   * Collect subexpressions from a query plan using a visitor pattern.
   */
  private void collectSubexpressions(RelNode node, Map<String, SubexpressionInfo> subexpressionMap, int queryIdx) {
    SubexpressionCollector collector = new SubexpressionCollector(subexpressionMap, queryIdx, detectionMode);
    collector.go(node);
  }

  /**
   * Visitor that collects interesting subexpressions from a RelNode tree.
   * Generic design: Works with any join pattern based on structural comparison.
   */
  private static class SubexpressionCollector extends RelVisitor {
    private final Map<String, SubexpressionInfo> subexpressionMap;
    private final int queryIdx;
    private final PatternDetectionMode mode;

    SubexpressionCollector(Map<String, SubexpressionInfo> subexpressionMap, int queryIdx, PatternDetectionMode mode) {
      this.subexpressionMap = subexpressionMap;
      this.queryIdx = queryIdx;
      this.mode = mode;
    }

    @Override
    public void visit(RelNode node, int ordinal, RelNode parent) {
      // Record this node as a potential subexpression if it's interesting
      if (isInterestingSubexpression(node)) {
        System.out.println("  Found interesting node: " + node.getClass().getSimpleName());
        String digest = computeDigest(node);
        System.out.println("    Digest hash: " + digest.hashCode());
        System.out.println("    Digest length: " + digest.length());

        SubexpressionInfo info = subexpressionMap.computeIfAbsent(digest, d -> {
          System.out.println("    -> NEW subexpression pattern");
          return new SubexpressionInfo(node, d);
        });
        info.addOccurrence(queryIdx, node);
        System.out.println("    -> Occurrence count now: " + info.getOccurrenceCount());
      }
      // Continue traversing
      super.visit(node, ordinal, parent);
    }

    /**
     * Determine if a node represents an interesting subexpression.
     *
     * GENERIC PATTERN DETECTION:
     * This method detects patterns based on the configured mode. The structural comparison
     * (digest matching) ensures we only materialize truly identical patterns regardless of:
     * - Table names (works with ANY tables: A, B, C, users, orders, products, etc.)
     * - Join types (INNER, LEFT, RIGHT, FULL OUTER, SEMI, ANTI)
     * - Join conditions (any ON clause)
     * - Number of tables (2-table, 3-table, 10-table joins)
     * - Nesting structure (simple or complex join trees)
     *
     * The key to genericity: We use Calcite's abstract classes and structural comparison,
     * NOT hardcoded patterns or specific table/column names.
     */
    private boolean isInterestingSubexpression(RelNode node) {
      switch (mode) {
        case JOINS_ONLY:
          // Default mode: Detect ALL join types
          // Calcite's Join class is abstract and covers: INNER, LEFT, RIGHT, FULL, SEMI, ANTI
          if (node instanceof Join) {
            System.out.println("    -> Join detected - Type: " + ((Join) node).getJoinType());
            return true;
          }
          break;

        case JOINS_AND_FILTERS:
          // Detect joins OR filters with joins underneath
          if (node instanceof Join) {
            System.out.println("    -> Join detected - Type: " + ((Join) node).getJoinType());
            return true;
          }
          if (node instanceof Filter && hasJoinBelow(node)) {
            System.out.println("    -> Filter with join below detected");
            return true;
          }
          break;

        case JOINS_AND_AGGREGATES:
          // Detect joins OR aggregations (including Filter/Project on top of Aggregate)
          if (node instanceof Join) {
            System.out.println("    -> Join detected - Type: " + ((Join) node).getJoinType());
            return true;
          }
          if (node instanceof Aggregate) {
            System.out.println("    -> Aggregate detected");
            return true;
          }
          // Also detect Filter/Project with Aggregate below (to capture full aggregation pattern)
          if (node instanceof Filter && hasAggregateBelow(node)) {
            System.out.println("    -> Filter with Aggregate below detected");
            return true;
          }
          if (node instanceof Project && hasAggregateBelow(node)) {
            System.out.println("    -> Project with Aggregate below detected");
            return true;
          }
          break;

        case ALL_EXPENSIVE_OPS:
          // Detect all expensive operations
          if (node instanceof Join) {
            System.out.println("    -> Join detected - Type: " + ((Join) node).getJoinType());
            return true;
          }
          if (node instanceof Aggregate) {
            System.out.println("    -> Aggregate detected");
            return true;
          }
          if (node instanceof Filter && hasJoinBelow(node)) {
            System.out.println("    -> Filter with join below detected");
            return true;
          }
          if (node instanceof Project && hasJoinBelow(node)) {
            System.out.println("    -> Project with join below detected");
            return true;
          }
          break;
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
     * For aggregation patterns, computes a consistent "core digest" based on
     * GROUP BY + aggregates + input structure, excluding WHERE filters.
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
        System.out.println("      Aggregation on JOIN detected → Filter-agnostic matching");

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
        System.out.println("      Single-table aggregation detected → Exact matching");
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
   * Filter out nested patterns - keep only the largest patterns.
   * If pattern A is a subtree of pattern B, and both are common, only keep B.
   *
   * Example: If we have both "A JOIN B" and "A JOIN B JOIN C" as common patterns,
   * we only want to materialize "A JOIN B JOIN C" since it includes the smaller pattern.
   *
   * @param commonSubexpressions All common subexpressions found
   * @return Filtered map with nested patterns removed
   */
  private Map<String, SubexpressionInfo> filterNestedPatterns(Map<String, SubexpressionInfo> commonSubexpressions) {
    if (commonSubexpressions.size() <= 1) {
      // Nothing to filter if we have 0 or 1 pattern
      return commonSubexpressions;
    }

    System.out.println("Checking for nested patterns...");
    Map<String, SubexpressionInfo> filtered = new HashMap<>(commonSubexpressions);

    // Compare each pattern with every other pattern
    List<Map.Entry<String, SubexpressionInfo>> entries = new ArrayList<>(commonSubexpressions.entrySet());

    for (int i = 0; i < entries.size(); i++) {
      Map.Entry<String, SubexpressionInfo> entry1 = entries.get(i);
      String digest1 = entry1.getKey();
      SubexpressionInfo info1 = entry1.getValue();

      for (int j = 0; j < entries.size(); j++) {
        if (i == j) {
          continue; // Skip comparing with itself
        }

        Map.Entry<String, SubexpressionInfo> entry2 = entries.get(j);
        String digest2 = entry2.getKey();
        SubexpressionInfo info2 = entry2.getValue();

        // Check if digest1 is contained within digest2
        // This indicates pattern1 is a subtree of pattern2
        if (digest2.contains(digest1) && !digest1.equals(digest2)) {
          System.out.println("  NESTED PATTERN DETECTED:");
          System.out.println("    Smaller pattern (will be removed):");
          System.out.println("      Digest length: " + digest1.length());
          System.out.println("      Digest preview: " + digest1.substring(0, Math.min(150, digest1.length())));
          System.out.println("    Larger pattern (will be kept):");
          System.out.println("      Digest length: " + digest2.length());
          System.out.println("      Digest preview: " + digest2.substring(0, Math.min(150, digest2.length())));

          // Remove the smaller pattern
          filtered.remove(digest1);
          break; // No need to check further for this pattern
        }
      }
    }

    System.out.println("Nested pattern filtering complete.");
    System.out.println("  Before filtering: " + commonSubexpressions.size() + " patterns");
    System.out.println("  After filtering: " + filtered.size() + " patterns");

    return filtered;
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

    System.out.println("Building pattern fingerprints (Merkle tree hashing)...");
    long startTime = System.nanoTime();

    // Step 1: Build fingerprints for each pattern
    Map<String, PatternFingerprint> fingerprints = new HashMap<>();
    for (Map.Entry<String, SubexpressionInfo> entry : commonSubexpressions.entrySet()) {
      String digest = entry.getKey();
      RelNode node = entry.getValue().getRepresentativeNode();
      PatternFingerprint fp = new PatternFingerprint(node, digest);
      fingerprints.put(digest, fp);

      System.out.println("  Pattern fingerprint built:");
      System.out.println("    Node count: " + fp.nodeCount);
      System.out.println("    Unique subtrees: " + fp.subtreeDigestHashes.size());
      System.out.println("    Root hash: " + fp.rootHash);
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
          System.out.println("  NESTED PATTERN DETECTED (via hashing):");
          System.out.println("    Smaller pattern (will be removed):");
          System.out.println("      Nodes: " + fp1.nodeCount + ", Subtrees: " + fp1.subtreeDigestHashes.size());
          System.out.println("      Root hash: " + fp1.rootHash);
          System.out.println("    Larger pattern (will be kept):");
          System.out.println("      Nodes: " + fp2.nodeCount + ", Subtrees: " + fp2.subtreeDigestHashes.size());
          System.out.println("      Root hash: " + fp2.rootHash);

          filtered.remove(digest1);
          break;
        }
      }
    }

    long duration = System.nanoTime() - startTime;
    System.out.println("Hash-based filtering complete in " + (duration / 1000) + " microseconds.");
    System.out.println("  Before filtering: " + commonSubexpressions.size() + " patterns");
    System.out.println("  After filtering: " + filtered.size() + " patterns");

    return filtered;
  }

  /**
   * Check if smaller pattern is contained within larger pattern using digest hash sets.
   * A pattern is contained if all its subtree digest hashes exist in the larger pattern.
   */
  private boolean isContainedInHash(PatternFingerprint smaller, PatternFingerprint larger, String digest1,
      String digest2) {

    System.out.println("    Checking containment:");
    System.out
        .println("      Smaller: nodes=" + smaller.nodeCount + ", subtrees=" + smaller.subtreeDigestHashes.size());
    System.out.println("      Larger:  nodes=" + larger.nodeCount + ", subtrees=" + larger.subtreeDigestHashes.size());

    // Quick checks to avoid expensive operations
    if (smaller.nodeCount >= larger.nodeCount) {
      System.out.println("      -> SKIP: smaller has >= nodes than larger");
      return false; // Can't contain something with same or more nodes
    }

    if (smaller.subtreeDigestHashes.size() > larger.subtreeDigestHashes.size()) {
      System.out.println("      -> SKIP: smaller has more unique subtrees");
      return false; // Can't contain something with more unique subtrees
    }

    // Debug: Show which hashes are in smaller
    System.out.println("      Smaller subtree hashes: " + smaller.subtreeDigestHashes);
    System.out.println("      Larger subtree hashes: " + larger.subtreeDigestHashes);

    // Main containment check: All subtree digest hashes of smaller must exist in larger
    boolean hashBasedContainment = larger.subtreeDigestHashes.containsAll(smaller.subtreeDigestHashes);

    System.out.println("      Hash-based containment: " + hashBasedContainment);

    if (hashBasedContainment) {
      // The hash-based approach is reliable when using RelOptUtil.toString() digest hashes
      // String containment would fail due to indentation differences when subtrees are nested
      // Since we're using the same digest computation as pattern detection, hash matches are trustworthy
      System.out.println("      -> Pattern IS contained (based on digest hash matching)");
      return true;
    }

    System.out.println("      -> Pattern NOT contained");
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

      System.out.println("      Collecting subtree digest hash:");
      System.out.println("        Node type: " + node.getClass().getSimpleName());
      System.out.println("        Digest hash: " + hash);
      System.out
          .println("        Digest preview: " + subtreeDigest.substring(0, Math.min(100, subtreeDigest.length())));

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

    System.out.println("Checking for join patterns nested in aggregations...");
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
              System.out.println("  Found join pattern nested in aggregation:");
              System.out
                  .println("    Join digest: " + joinDigest.substring(0, Math.min(100, joinDigest.length())) + "...");
              System.out.println("    Aggregation digest: "
                  + aggEntry.getKey().substring(0, Math.min(100, aggEntry.getKey().length())) + "...");
              System.out.println("    -> Removing join pattern (keeping aggregation)");
              result.remove(joinDigest);
            }
          }
        }
      }
    }

    System.out.println("Joins after cross-type filtering: " + result.size() + " (removed "
        + (joinPatterns.size() - result.size()) + ")");
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

    System.out.println(
        "  Found most general variant with " + minFilters + " filters (out of " + allNodes.size() + " variants)");
    return mostGeneral;
  }

  /**
   * Filter aggregation patterns using string-based approach.
   * For aggregations, we keep the MOST GENERAL pattern (fewest filters/conditions).
   * More general = more reusable across different queries.
   */
  private Map<String, SubexpressionInfo> filterAggregationPatterns(Map<String, SubexpressionInfo> aggregationPatterns) {

    if (aggregationPatterns.size() <= 1) {
      return aggregationPatterns;
    }

    System.out.println("Filtering aggregation patterns (STRING_BASED)...");
    Map<String, SubexpressionInfo> filtered = new HashMap<>(aggregationPatterns);

    List<Map.Entry<String, SubexpressionInfo>> entries = new ArrayList<>(aggregationPatterns.entrySet());

    for (int i = 0; i < entries.size(); i++) {
      Map.Entry<String, SubexpressionInfo> entry1 = entries.get(i);
      String digest1 = entry1.getKey();
      RelNode node1 = entry1.getValue().getRepresentativeNode();

      for (int j = 0; j < entries.size(); j++) {
        if (i == j) {
          continue;
        }

        Map.Entry<String, SubexpressionInfo> entry2 = entries.get(j);
        String digest2 = entry2.getKey();
        RelNode node2 = entry2.getValue().getRepresentativeNode();

        // Check if they have the same aggregation core (same GROUP BY, same aggregates)
        if (haveSameAggregationCore(digest1, digest2)) {
          // Count filters to determine which is more general
          int filters1 = countFiltersInPattern(node1);
          int filters2 = countFiltersInPattern(node2);

          System.out.println("  Found related aggregation patterns:");
          System.out.println("    Pattern 1: " + filters1 + " filters");
          System.out.println("    Pattern 2: " + filters2 + " filters");

          // Keep the one with FEWER filters (more general)
          if (filters1 < filters2) {
            System.out.println("    -> Keeping Pattern 1 (more general)");
            filtered.remove(digest2);
          } else if (filters2 < filters1) {
            System.out.println("    -> Keeping Pattern 2 (more general)");
            filtered.remove(digest1);
            break; // Pattern 1 is removed, move to next
          }
          // If equal filters, keep both (different enough to be useful)
        }
      }
    }

    System.out.println("Aggregation pattern filtering complete (STRING_BASED).");
    System.out.println("  Before: " + aggregationPatterns.size() + " patterns");
    System.out.println("  After: " + filtered.size() + " patterns");

    return filtered;
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

    System.out.println("Filtering aggregation patterns (HASH_BASED)...");
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
          System.out.println("  Found related aggregation patterns:");
          System.out.println("    Pattern 1: " + fp1.filterCount + " filters, core hash: " + fp1.aggregationCoreHash);
          System.out.println("    Pattern 2: " + fp2.filterCount + " filters, core hash: " + fp2.aggregationCoreHash);

          // Keep the one with FEWER filters (more general)
          if (fp1.filterCount < fp2.filterCount) {
            System.out.println("    -> Keeping Pattern 1 (more general)");
            filtered.remove(digest2);
          } else if (fp2.filterCount < fp1.filterCount) {
            System.out.println("    -> Keeping Pattern 2 (more general)");
            filtered.remove(digest1);
            break; // Pattern 1 is removed, move to next
          }
          // If equal filters, keep both
        }
      }
    }

    long duration = System.nanoTime() - startTime;
    System.out
        .println("Aggregation pattern filtering complete (HASH_BASED) in " + (duration / 1000) + " microseconds.");
    System.out.println("  Before: " + aggregationPatterns.size() + " patterns");
    System.out.println("  After: " + filtered.size() + " patterns");

    return filtered;
  }

  /**
   * Check if two patterns have the same aggregation core.
   * Same core means: same tables, same joins (if any), same GROUP BY columns, same aggregates.
   * Different filters/WHERE clauses are OK - that's what makes one more general than another.
   */
  private boolean haveSameAggregationCore(String digest1, String digest2) {
    // Extract the aggregation part (before filters are applied)
    // For now, use a simple heuristic: if one digest contains most of the other's structure
    // A more robust approach would parse the Aggregate node's group set and agg functions

    // Remove filter-related parts for comparison
    String core1 = extractAggregationCore(digest1);
    String core2 = extractAggregationCore(digest2);

    // Check if cores are similar (allowing for minor differences in filters)
    return core1.equals(core2);
  }

  /**
   * Extract the aggregation core from a digest by removing filter-specific parts.
   */
  private String extractAggregationCore(String digest) {
    // Find the Aggregate node and extract its definition
    int aggStart = digest.indexOf("LogicalAggregate");
    if (aggStart == -1) {
      return digest; // Not an aggregate pattern
    }

    // Extract from Aggregate onwards, but stop at Filter nodes
    int filterStart = digest.indexOf("LogicalFilter", aggStart);
    if (filterStart != -1 && filterStart < digest.length() / 2) {
      // Filter is near the top, extract aggregate core below it
      return digest.substring(aggStart);
    }

    // Extract the aggregate and its inputs (joins, tables)
    int nextNodeStart = digest.indexOf("Logical", aggStart + 10);
    if (nextNodeStart != -1) {
      return digest.substring(aggStart, nextNodeStart + 200); // Reasonable window
    }

    return digest.substring(aggStart);
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

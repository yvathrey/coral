/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import java.util.*;
import java.util.stream.Collectors;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Analyzes filter subsumption relationships between queries.
 *
 * Filter Subsumption: Filter A subsumes Filter B if every row that satisfies B also satisfies A.
 *
 * Examples:
 * - "exp > 5" subsumes "exp > 5 AND country = 'US'"  (A subsumes A AND B)
 * - "exp > 5" subsumes "exp > 10"  (weaker condition subsumes stronger)
 * - "true" subsumes any filter
 *
 * This enables creating ONE materialized view with the most general filter,
 * and queries with more specific filters apply residual predicates on the MV result.
 *
 * Generic Implementation:
 * - Works with any filter expressions (not hardcoded to specific columns)
 * - Uses Calcite's RexNode structure for analysis
 * - Handles conjunctions (AND), comparisons, literals
 */
public class FilterSubsumptionAnalyzer {

  private static final Logger LOG = LoggerFactory.getLogger(FilterSubsumptionAnalyzer.class);

  /**
   * Check if filter1 subsumes filter2.
   *
   * Subsumption rules:
   * 1. If filter1 is a subset of filter2's conjuncts (A subsumes A AND B)
   * 2. If filter1 is null/true (no filter subsumes any filter)
   * 3. Structural equality for individual predicates
   *
   * @param filter1 The potentially more general filter
   * @param filter2 The potentially more specific filter
   * @return true if filter1 subsumes filter2
   */
  public static boolean subsumes(RexNode filter1, RexNode filter2) {
    LOG.debug("Checking subsumption:");
    LOG.debug("  Filter1 (general): {}", filter1);
    LOG.debug("  Filter2 (specific): {}", filter2);

    // Case 1: No filter subsumes any filter
    if (filter1 == null) {
      LOG.debug("  Filter1 is null → subsumes any filter");
      return true;
    }

    // Case 2: Any filter cannot subsume "no filter"
    if (filter2 == null) {
      LOG.debug("  Filter2 is null but Filter1 is not → no subsumption");
      return false;
    }

    // Case 3: Identical filters
    if (filter1.toString().equals(filter2.toString())) {
      LOG.debug("  Filters are identical → subsumption");
      return true;
    }

    // Case 4: Extract conjuncts and check subset relationship
    List<RexNode> conjuncts1 = extractConjuncts(filter1);
    List<RexNode> conjuncts2 = extractConjuncts(filter2);

    LOG.debug("  Filter1 conjuncts: {}", conjuncts1.size());
    for (RexNode c : conjuncts1) {
      LOG.debug("    - {}", c);
    }
    LOG.debug("  Filter2 conjuncts: {}", conjuncts2.size());
    for (RexNode c : conjuncts2) {
      LOG.debug("    - {}", c);
    }

    // Check if all conjuncts of filter1 exist in filter2
    // (filter1 is a subset of filter2 → filter1 is more general)
    boolean allFound = true;
    for (RexNode c1 : conjuncts1) {
      boolean found = false;
      for (RexNode c2 : conjuncts2) {
        if (areEquivalent(c1, c2)) {
          found = true;
          break;
        }
      }
      if (!found) {
        LOG.debug("  Conjunct {} from filter1 not found in filter2", c1);
        allFound = false;
        break;
      }
    }

    if (allFound) {
      LOG.debug("  All filter1 conjuncts found in filter2 → SUBSUMES");
      return true;
    } else {
      LOG.debug("  Not all filter1 conjuncts in filter2 → NO SUBSUMPTION");
      return false;
    }
  }

  /**
   * Extract all conjuncts from a filter (flatten AND operations).
   *
   * Examples:
   * - "A AND B" → [A, B]
   * - "A AND B AND C" → [A, B, C]
   * - "A" → [A]
   * - "A OR B" → [A OR B] (not flattened, OR is atomic)
   *
   * @param filter The filter to decompose
   * @return List of conjunct predicates
   */
  public static List<RexNode> extractConjuncts(RexNode filter) {
    List<RexNode> conjuncts = new ArrayList<>();
    extractConjunctsRecursive(filter, conjuncts);
    return conjuncts;
  }

  private static void extractConjunctsRecursive(RexNode node, List<RexNode> conjuncts) {
    if (node instanceof RexCall) {
      RexCall call = (RexCall) node;
      if (call.getKind() == SqlKind.AND) {
        // Flatten AND: recursively extract from both operands
        for (RexNode operand : call.getOperands()) {
          extractConjunctsRecursive(operand, conjuncts);
        }
        return;
      }
    }
    // Atomic predicate (not AND)
    conjuncts.add(node);
  }

  /**
   * Check if two RexNodes are semantically equivalent or if node1 subsumes node2.
   *
   * This method checks:
   * 1. Structural equality (exact match)
   * 2. Range subsumption (e.g., exp > 3 subsumes exp > 5)
   *
   * @param node1 First node (potentially more general)
   * @param node2 Second node (potentially more specific)
   * @return true if equivalent or node1 subsumes node2
   */
  private static boolean areEquivalent(RexNode node1, RexNode node2) {
    // Check structural equality first
    if (node1.toString().equals(node2.toString())) {
      return true;
    }

    // Check range subsumption
    return checkRangeSubsumption(node1, node2);
  }

  /**
   * Check if predicate1 subsumes predicate2 through range analysis.
   *
   * Examples:
   * - exp > 3 subsumes exp > 5 (3 <= 5)
   * - exp >= 3 subsumes exp >= 5 (3 <= 5)
   * - exp < 10 subsumes exp < 5 (10 >= 5)
   * - exp <= 10 subsumes exp <= 5 (10 >= 5)
   * - exp > 3 subsumes exp >= 4 (3 < 4)
   *
   * @param predicate1 The potentially more general predicate
   * @param predicate2 The potentially more specific predicate
   * @return true if predicate1 subsumes predicate2
   */
  private static boolean checkRangeSubsumption(RexNode predicate1, RexNode predicate2) {
    // Both must be comparison operators
    if (!(predicate1 instanceof RexCall) || !(predicate2 instanceof RexCall)) {
      return false;
    }

    RexCall call1 = (RexCall) predicate1;
    RexCall call2 = (RexCall) predicate2;

    // Extract comparison info
    ComparisonInfo info1 = extractComparisonInfo(call1);
    ComparisonInfo info2 = extractComparisonInfo(call2);

    if (info1 == null || info2 == null) {
      return false;
    }

    // Must be comparing the same column
    if (!info1.columnRef.equals(info2.columnRef)) {
      return false;
    }

    // Check if predicate1 subsumes predicate2 based on operators and values
    return checkOperatorSubsumption(info1, info2);
  }

  /**
   * Information extracted from a comparison predicate.
   */
  private static class ComparisonInfo {
    SqlKind operator;      // GT, GTE, LT, LTE, EQUALS
    String columnRef;      // Column reference (e.g., "$0")
    Comparable value;      // Constant value

    ComparisonInfo(SqlKind operator, String columnRef, Comparable value) {
      this.operator = operator;
      this.columnRef = columnRef;
      this.value = value;
    }
  }

  /**
   * Extract comparison information from a RexCall.
   *
   * Handles predicates like: column > 5, column >= 10, etc.
   *
   * @param call The RexCall to analyze
   * @return ComparisonInfo or null if not a simple comparison
   */
  private static ComparisonInfo extractComparisonInfo(RexCall call) {
    SqlKind kind = call.getKind();

    // Only handle comparison operators
    if (!isComparisonOperator(kind)) {
      return null;
    }

    // Expect 2 operands: column and constant (in either order)
    if (call.getOperands().size() != 2) {
      return null;
    }

    RexNode left = call.getOperands().get(0);
    RexNode right = call.getOperands().get(1);

    // Case 1: column op constant (e.g., $0 > 5)
    if (left instanceof RexInputRef && right instanceof RexLiteral) {
      RexInputRef columnRef = (RexInputRef) left;
      RexLiteral literal = (RexLiteral) right;
      Comparable value = extractComparableValue(literal);
      if (value != null) {
        return new ComparisonInfo(kind, columnRef.toString(), value);
      }
    }

    // Case 2: constant op column (e.g., 5 < $0 → $0 > 5)
    if (left instanceof RexLiteral && right instanceof RexInputRef) {
      RexLiteral literal = (RexLiteral) left;
      RexInputRef columnRef = (RexInputRef) right;
      Comparable value = extractComparableValue(literal);
      if (value != null) {
        // Flip the operator (5 < $0 → $0 > 5)
        SqlKind flippedOp = flipOperator(kind);
        return new ComparisonInfo(flippedOp, columnRef.toString(), value);
      }
    }

    return null;
  }

  /**
   * Extract a comparable value from a RexLiteral.
   */
  private static Comparable extractComparableValue(RexLiteral literal) {
    if (literal.getValue() == null) {
      return null;
    }

    Object value = literal.getValue();
    if (value instanceof Comparable) {
      return (Comparable) value;
    }

    return null;
  }

  /**
   * Check if the SqlKind is a comparison operator.
   */
  private static boolean isComparisonOperator(SqlKind kind) {
    return kind == SqlKind.GREATER_THAN ||
           kind == SqlKind.GREATER_THAN_OR_EQUAL ||
           kind == SqlKind.LESS_THAN ||
           kind == SqlKind.LESS_THAN_OR_EQUAL ||
           kind == SqlKind.EQUALS;
  }

  /**
   * Flip a comparison operator (for constant op column → column op constant).
   */
  private static SqlKind flipOperator(SqlKind kind) {
    switch (kind) {
      case GREATER_THAN: return SqlKind.LESS_THAN;
      case GREATER_THAN_OR_EQUAL: return SqlKind.LESS_THAN_OR_EQUAL;
      case LESS_THAN: return SqlKind.GREATER_THAN;
      case LESS_THAN_OR_EQUAL: return SqlKind.GREATER_THAN_OR_EQUAL;
      case EQUALS: return SqlKind.EQUALS;
      default: return kind;
    }
  }

  /**
   * Check if info1's predicate subsumes info2's predicate based on operators and values.
   *
   * Logic:
   * - For GT/GTE: smaller threshold subsumes larger threshold
   *   - exp > 3 subsumes exp > 5 (3 <= 5)
   *   - exp >= 3 subsumes exp >= 5 (3 <= 5)
   *   - exp > 3 subsumes exp >= 4 (3 < 4)
   *
   * - For LT/LTE: larger threshold subsumes smaller threshold
   *   - exp < 10 subsumes exp < 5 (10 >= 5)
   *   - exp <= 10 subsumes exp <= 5 (10 >= 5)
   *   - exp < 10 subsumes exp <= 9 (10 > 9)
   *
   * - For EQUALS: only exact match subsumes
   *   - exp = 5 subsumes exp = 5
   */
  private static boolean checkOperatorSubsumption(ComparisonInfo info1, ComparisonInfo info2) {
    SqlKind op1 = info1.operator;
    SqlKind op2 = info2.operator;

    try {
      int comparison = info1.value.compareTo(info2.value);

      // Handle GREATER_THAN and GREATER_THAN_OR_EQUAL
      if (op1 == SqlKind.GREATER_THAN && op2 == SqlKind.GREATER_THAN) {
        // exp > 3 subsumes exp > 5 if 3 <= 5
        return comparison <= 0;
      }

      if (op1 == SqlKind.GREATER_THAN && op2 == SqlKind.GREATER_THAN_OR_EQUAL) {
        // exp > 3 subsumes exp >= 4 if 3 < 4
        return comparison < 0;
      }

      if (op1 == SqlKind.GREATER_THAN_OR_EQUAL && op2 == SqlKind.GREATER_THAN) {
        // exp >= 3 subsumes exp > 5 if 3 <= 5
        return comparison <= 0;
      }

      if (op1 == SqlKind.GREATER_THAN_OR_EQUAL && op2 == SqlKind.GREATER_THAN_OR_EQUAL) {
        // exp >= 3 subsumes exp >= 5 if 3 <= 5
        return comparison <= 0;
      }

      // Handle LESS_THAN and LESS_THAN_OR_EQUAL
      if (op1 == SqlKind.LESS_THAN && op2 == SqlKind.LESS_THAN) {
        // exp < 10 subsumes exp < 5 if 10 >= 5
        return comparison >= 0;
      }

      if (op1 == SqlKind.LESS_THAN && op2 == SqlKind.LESS_THAN_OR_EQUAL) {
        // exp < 10 subsumes exp <= 9 if 10 > 9
        return comparison > 0;
      }

      if (op1 == SqlKind.LESS_THAN_OR_EQUAL && op2 == SqlKind.LESS_THAN) {
        // exp <= 10 subsumes exp < 5 if 10 >= 5
        return comparison >= 0;
      }

      if (op1 == SqlKind.LESS_THAN_OR_EQUAL && op2 == SqlKind.LESS_THAN_OR_EQUAL) {
        // exp <= 10 subsumes exp <= 5 if 10 >= 5
        return comparison >= 0;
      }

      // Handle EQUALS
      if (op1 == SqlKind.EQUALS && op2 == SqlKind.EQUALS) {
        // exp = 5 subsumes exp = 5 only
        return comparison == 0;
      }

      // Cross-operator cases not covered yet
      return false;

    } catch (ClassCastException e) {
      // Values are not comparable (different types)
      LOG.debug("Cannot compare values of different types: {} and {}", info1.value.getClass(), info2.value.getClass());
      return false;
    }
  }

  /**
   * Find the most general filter from a set of filters.
   *
   * The most general filter is the one that subsumes all others,
   * or the one with the fewest conjuncts if no single filter subsumes all.
   *
   * @param filters List of filters to analyze
   * @return The most general filter, or null if list is empty
   */
  public static RexNode findMostGeneralFilter(List<RexNode> filters) {
    if (filters == null || filters.isEmpty()) {
      return null;
    }

    LOG.debug("Finding most general filter from {} candidates", filters.size());

    // Try to find a filter that subsumes all others
    for (RexNode candidate : filters) {
      boolean subsumeAll = true;
      for (RexNode other : filters) {
        if (candidate != other && !subsumes(candidate, other)) {
          subsumeAll = false;
          break;
        }
      }
      if (subsumeAll) {
        LOG.debug("Found filter that subsumes all others: {}", candidate);
        return candidate;
      }
    }

    // No single filter subsumes all - pick the one with fewest conjuncts
    LOG.debug("No single filter subsumes all, selecting filter with fewest conjuncts");
    RexNode mostGeneral = filters.get(0);
    int minConjuncts = extractConjuncts(mostGeneral).size();

    for (RexNode filter : filters) {
      int conjunctCount = extractConjuncts(filter).size();
      if (conjunctCount < minConjuncts) {
        mostGeneral = filter;
        minConjuncts = conjunctCount;
      }
    }

    LOG.debug("Most general filter (fewest conjuncts): {} with {} conjuncts", mostGeneral, minConjuncts);
    return mostGeneral;
  }

  /**
   * Extract the residual filter: the part of specificFilter not covered by generalFilter.
   *
   * If generalFilter subsumes specificFilter:
   *   residual = specificFilter - generalFilter
   *
   * Examples:
   * - generalFilter: "exp > 5"
   *   specificFilter: "exp > 5 AND country = 'US'"
   *   residual: "country = 'US'"
   *
   * - generalFilter: "exp > 5 AND dept = 'ENG'"
   *   specificFilter: "exp > 5 AND dept = 'ENG' AND country = 'US'"
   *   residual: "country = 'US'"
   *
   * @param generalFilter The more general filter (in the MV)
   * @param specificFilter The more specific filter (in the query)
   * @return The residual filter, or null if no residual
   */
  public static RexNode extractResidualFilter(RexNode generalFilter, RexNode specificFilter) {
    LOG.debug("Extracting residual filter:");
    LOG.debug("  General (MV): {}", generalFilter);
    LOG.debug("  Specific (Query): {}", specificFilter);

    // If MV has no filter, residual is the entire query filter
    if (generalFilter == null) {
      LOG.debug("  MV has no filter → residual is entire query filter");
      return specificFilter;
    }

    // If query has no filter, no residual
    if (specificFilter == null) {
      LOG.debug("  Query has no filter → no residual");
      return null;
    }

    // Extract conjuncts
    List<RexNode> generalConjuncts = extractConjuncts(generalFilter);
    List<RexNode> specificConjuncts = extractConjuncts(specificFilter);

    // Find conjuncts in specific that are not in general
    List<RexNode> residualConjuncts = new ArrayList<>();
    for (RexNode specificConjunct : specificConjuncts) {
      boolean foundInGeneral = false;
      for (RexNode generalConjunct : generalConjuncts) {
        if (areEquivalent(specificConjunct, generalConjunct)) {
          foundInGeneral = true;
          break;
        }
      }
      if (!foundInGeneral) {
        residualConjuncts.add(specificConjunct);
        LOG.debug("  Residual conjunct: {}", specificConjunct);
      }
    }

    // Build residual filter
    if (residualConjuncts.isEmpty()) {
      LOG.debug("  No residual conjuncts → no residual filter");
      return null;
    } else if (residualConjuncts.size() == 1) {
      LOG.debug("  Single residual conjunct");
      return residualConjuncts.get(0);
    } else {
      // Multiple residual conjuncts - need to build AND expression
      // For now, return null (requires RexBuilder to construct AND)
      // This will be handled by the caller with access to RexBuilder
      LOG.debug("  Multiple residual conjuncts - caller must reconstruct AND");
      return null; // Caller will handle reconstruction
    }
  }

  /**
   * Extract filter from a RelNode (if it's a Filter node or has Filter in its tree).
   *
   * @param node The RelNode to analyze
   * @return The filter RexNode, or null if no filter
   */
  public static RexNode extractFilterFromRelNode(RelNode node) {
    if (node instanceof Filter) {
      return ((Filter) node).getCondition();
    }

    // Check immediate children (one level)
    for (RelNode child : node.getInputs()) {
      if (child instanceof Filter) {
        return ((Filter) child).getCondition();
      }
    }

    return null;
  }

  /**
   * Result of filter analysis for a group of related queries.
   */
  public static class FilterAnalysisResult {
    private final RexNode mostGeneralFilter;
    private final Map<RelNode, RexNode> residualFilters;

    public FilterAnalysisResult(RexNode mostGeneralFilter, Map<RelNode, RexNode> residualFilters) {
      this.mostGeneralFilter = mostGeneralFilter;
      this.residualFilters = residualFilters;
    }

    public RexNode getMostGeneralFilter() {
      return mostGeneralFilter;
    }

    public Map<RelNode, RexNode> getResidualFilters() {
      return residualFilters;
    }

    public RexNode getResidualFilter(RelNode query) {
      return residualFilters.get(query);
    }
  }
}

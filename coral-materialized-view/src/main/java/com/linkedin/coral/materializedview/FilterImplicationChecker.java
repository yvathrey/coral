package com.linkedin.coral.materializedview;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlKind;

import java.util.ArrayList;
import java.util.List;


/**
 * Utility class for checking filter implication in materialized view matching.
 *
 * <p>Determines if a query's filter condition logically implies a materialized view's
 * filter condition, allowing the use of the MV with a residual filter applied.
 *
 * <p><b>Examples:</b>
 * <ul>
 *   <li>Query: WHERE a &gt; 10 AND b = 5, MV: WHERE a &gt; 10 → Implies with residual (b = 5)</li>
 *   <li>Query: WHERE a &gt; 10, MV: WHERE a &gt; 10 → Exact match (no residual)</li>
 *   <li>Query: WHERE b = 5, MV: WHERE a &gt; 10 → Does NOT imply (no match)</li>
 * </ul>
 *
 * <p>This feature allows queries with additional filter conditions to reuse materialized views
 * by applying residual filters on top of the MV scan, dramatically improving MV reuse rates.
 *
 * @author Coral Team
 */
public class FilterImplicationChecker {

  /**
   * Result of a filter implication check.
   */
  public static class ImplicationResult {
    private final boolean implies;
    private final RexNode residualFilter;

    public ImplicationResult(boolean implies, RexNode residualFilter) {
      this.implies = implies;
      this.residualFilter = residualFilter;
    }

    /**
     * @return true if query filter implies target filter
     */
    public boolean implies() {
      return implies;
    }

    /**
     * @return Residual filter to apply on top of MV (null if exact match)
     */
    public RexNode getResidualFilter() {
      return residualFilter;
    }

    /**
     * @return true if this is an exact match (no residual filter needed)
     */
    public boolean isExactMatch() {
      return implies && residualFilter == null;
    }

    @Override
    public String toString() {
      if (!implies) {
        return "ImplicationResult{implies=false}";
      }
      return "ImplicationResult{implies=true, residual=" + (residualFilter == null ? "none" : residualFilter.toString())
          + "}";
    }
  }

  /**
   * Check if the query filter implies the target filter.
   *
   * <p><b>Implication Logic:</b>
   * <ol>
   *   <li><b>Exact Match:</b> Query filter equals target filter → No residual</li>
   *   <li><b>Conjunction Decomposition:</b> Query = Target AND Residual → Residual filter</li>
   *   <li><b>No Implication:</b> Otherwise → Cannot use MV</li>
   * </ol>
   *
   * @param queryFilter The filter condition from the user query
   * @param targetFilter The filter condition from the MV definition
   * @param rexBuilder RexBuilder for constructing residual filter expressions
   * @return ImplicationResult indicating if implication holds and any residual filter
   */
  public static ImplicationResult checkImplication(RexNode queryFilter, RexNode targetFilter, RexBuilder rexBuilder) {
    if (queryFilter == null || targetFilter == null) {
      // If either filter is null, cannot establish implication
      return new ImplicationResult(false, null);
    }

    // Case 1: Exact match (string comparison for now)
    String queryStr = queryFilter.toString();
    String targetStr = targetFilter.toString();

    if (queryStr.equals(targetStr)) {
      // Filters are identical → exact match, no residual needed
      return new ImplicationResult(true, null);
    }

    // Case 2: Query is a conjunction (AND) that may contain target
    if (queryFilter.getKind() == SqlKind.AND) {
      // Decompose query filter into conjuncts
      List<RexNode> queryConjuncts = RelOptUtil.conjunctions(queryFilter);

      // Check if any conjunct matches the target filter
      for (int i = 0; i < queryConjuncts.size(); i++) {
        RexNode conjunct = queryConjuncts.get(i);
        if (conjunct.toString().equals(targetStr)) {
          // Found a match! Compute residual by removing this conjunct
          List<RexNode> residualConjuncts = new ArrayList<>(queryConjuncts);
          residualConjuncts.remove(i);

          RexNode residualFilter;
          if (residualConjuncts.isEmpty()) {
            // This shouldn't happen (would be exact match), but handle safely
            residualFilter = null;
          } else if (residualConjuncts.size() == 1) {
            // Single residual condition
            residualFilter = residualConjuncts.get(0);
          } else {
            // Multiple residual conditions → recombine with AND
            residualFilter = RexUtil.composeConjunction(rexBuilder, residualConjuncts);
          }

          return new ImplicationResult(true, residualFilter);
        }
      }
    }

    // Case 3: Target is a conjunction, check if query matches one part
    // (Less common: query is subset of target)
    if (targetFilter.getKind() == SqlKind.AND) {
      List<RexNode> targetConjuncts = RelOptUtil.conjunctions(targetFilter);

      for (RexNode targetConjunct : targetConjuncts) {
        if (queryStr.equals(targetConjunct.toString())) {
          // Query matches ONE part of target's AND
          // This means query is MORE restrictive than target (query implies target)
          // No residual needed - the MV already has the stricter filter
          return new ImplicationResult(true, null);
        }
      }
    }

    // Case 4: No implication found
    return new ImplicationResult(false, null);
  }

  /**
   * Check if query filter implies target filter (without residual computation).
   * Useful for quick checks when residual filter is not needed.
   *
   * @param queryFilter The filter from the query
   * @param targetFilter The filter from the MV
   * @return true if query implies target
   */
  public static boolean impliesSimple(RexNode queryFilter, RexNode targetFilter) {
    ImplicationResult result = checkImplication(queryFilter, targetFilter, null);
    return result.implies();
  }
}

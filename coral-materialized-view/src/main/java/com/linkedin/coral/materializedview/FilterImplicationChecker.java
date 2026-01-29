package com.linkedin.coral.materializedview;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>This class now uses FilterSubsumptionAnalyzer for generic filter analysis.
 *
 * @author Coral Team
 */
public class FilterImplicationChecker {

  private static final Logger LOG = LoggerFactory.getLogger(FilterImplicationChecker.class);

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
   * <p><b>Subsumption Logic (using FilterSubsumptionAnalyzer):</b>
   * <ol>
   *   <li><b>Exact Match:</b> Query filter equals MV filter → No residual</li>
   *   <li><b>MV Filter Subsumes Query Filter:</b> MV filter is more general → Use MV with residual</li>
   *   <li><b>No Subsumption:</b> Otherwise → Cannot use MV</li>
   * </ol>
   *
   * <p>Examples:
   * <ul>
   *   <li>Query: WHERE exp > 5 AND country = 'US', MV: WHERE exp > 5 → MV subsumes query, residual = country='US'</li>
   *   <li>Query: WHERE exp > 5, MV: WHERE exp > 5 → Exact match, no residual</li>
   *   <li>Query: WHERE exp > 5, MV: WHERE exp > 5 AND country = 'US' → NO subsumption (MV is more restrictive)</li>
   * </ul>
   *
   * @param queryFilter The filter condition from the user query
   * @param mvFilter The filter condition from the MV definition (called targetFilter for compatibility)
   * @param rexBuilder RexBuilder for constructing residual filter expressions
   * @return ImplicationResult indicating if MV can be used and any residual filter
   */
  public static ImplicationResult checkImplication(RexNode queryFilter, RexNode mvFilter, RexBuilder rexBuilder) {
    LOG.debug("Checking filter implication:");
    LOG.debug("  Query filter: {}", queryFilter);
    LOG.debug("  MV filter: {}", mvFilter);

    // Case 1: Both null → match
    if (queryFilter == null && mvFilter == null) {
      LOG.debug("  Both filters null → exact match");
      return new ImplicationResult(true, null);
    }

    // Case 2: MV has no filter, query has filter → MV subsumes (residual = query filter)
    if (mvFilter == null && queryFilter != null) {
      LOG.debug("  MV has no filter, query has filter → MV subsumes, residual = query filter");
      return new ImplicationResult(true, queryFilter);
    }

    // Case 3: Query has no filter, MV has filter → NO subsumption (MV is more restrictive)
    if (queryFilter == null && mvFilter != null) {
      LOG.debug("  Query has no filter, MV has filter → MV is more restrictive, no match");
      return new ImplicationResult(false, null);
    }

    // Case 4: Both have filters → check subsumption using FilterSubsumptionAnalyzer
    // Check if MV filter subsumes query filter (MV filter is more general)
    boolean mvSubsumesQuery = FilterSubsumptionAnalyzer.subsumes(mvFilter, queryFilter);

    if (!mvSubsumesQuery) {
      LOG.debug("  MV filter does NOT subsume query filter → no match");
      return new ImplicationResult(false, null);
    }

    LOG.debug("  MV filter subsumes query filter → can use MV");

    // Exact match check
    if (queryFilter.toString().equals(mvFilter.toString())) {
      LOG.debug("  Filters are identical → exact match, no residual");
      return new ImplicationResult(true, null);
    }

    // Compute residual filter (query filter - MV filter)
    RexNode residualFilter = computeResidualFilter(queryFilter, mvFilter, rexBuilder);
    LOG.debug("  Residual filter: {}", residualFilter);

    return new ImplicationResult(true, residualFilter);
  }

  /**
   * Compute the residual filter: parts of queryFilter not covered by mvFilter.
   *
   * @param queryFilter The query's filter
   * @param mvFilter The MV's filter
   * @param rexBuilder RexBuilder for constructing AND expressions
   * @return The residual filter, or null if no residual
   */
  private static RexNode computeResidualFilter(RexNode queryFilter, RexNode mvFilter, RexBuilder rexBuilder) {
    // Extract conjuncts
    List<RexNode> queryConjuncts = FilterSubsumptionAnalyzer.extractConjuncts(queryFilter);
    List<RexNode> mvConjuncts = FilterSubsumptionAnalyzer.extractConjuncts(mvFilter);

    LOG.debug("Computing residual filter:");
    LOG.debug("  Query conjuncts: {}", queryConjuncts.size());
    LOG.debug("  MV conjuncts: {}", mvConjuncts.size());

    // Find conjuncts in query that are NOT in MV
    List<RexNode> residualConjuncts = new ArrayList<>();
    for (RexNode queryConjunct : queryConjuncts) {
      boolean foundInMv = false;
      for (RexNode mvConjunct : mvConjuncts) {
        if (queryConjunct.toString().equals(mvConjunct.toString())) {
          foundInMv = true;
          break;
        }
      }
      if (!foundInMv) {
        residualConjuncts.add(queryConjunct);
        LOG.debug("  Residual conjunct: {}", queryConjunct);
      }
    }

    // Build residual filter
    if (residualConjuncts.isEmpty()) {
      LOG.debug("  No residual conjuncts");
      return null;
    } else if (residualConjuncts.size() == 1) {
      LOG.debug("  Single residual conjunct");
      return residualConjuncts.get(0);
    } else {
      LOG.debug("  Multiple residual conjuncts, building AND");
      if (rexBuilder != null) {
        return RexUtil.composeConjunction(rexBuilder, residualConjuncts);
      } else {
        // Cannot construct AND without RexBuilder
        LOG.warn("  RexBuilder is null, cannot construct AND for multiple residuals");
        return null;
      }
    }
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

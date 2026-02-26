package com.linkedin.coral.materializedview;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.*;


/**
 * Unit tests for FilterImplicationChecker.
 *
 * <p>Tests the filter implication logic in isolation before integration.
 */
public class FilterImplicationCheckerTest {

  private RexBuilder rexBuilder;
  private RelDataTypeFactory typeFactory;

  @BeforeClass
  public void setup() {
    typeFactory = new JavaTypeFactoryImpl();
    rexBuilder = new RexBuilder(typeFactory);
  }

  @Test
  public void testExactMatch() {
    // Query: WHERE a > 10
    // MV: WHERE a > 10
    // Result: Exact match, no residual

    RexNode filter1 = createFilter(">($0, 10)");
    RexNode filter2 = createFilter(">($0, 10)");

    FilterImplicationChecker.ImplicationResult result =
        FilterImplicationChecker.checkImplication(filter1, filter2, rexBuilder);

    assertTrue(result.implies(), "Should imply with exact match");
    assertTrue(result.isExactMatch(), "Should be exact match");
    assertNull(result.getResidualFilter(), "Should have no residual filter");
  }

  @Test
  public void testQueryWithAdditionalFilter() {
    // Query: WHERE a > 10 AND b = 5
    // MV: WHERE a > 10
    // Result: Implies with residual (b = 5)

    RexNode inputRef0 = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 0);
    RexNode inputRef1 = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 1);
    RexNode literal10 = rexBuilder.makeLiteral(10, typeFactory.createSqlType(SqlTypeName.INTEGER), false);
    RexNode literal5 = rexBuilder.makeLiteral(5, typeFactory.createSqlType(SqlTypeName.INTEGER), false);

    RexNode condition1 = rexBuilder.makeCall(SqlStdOperatorTable.GREATER_THAN, inputRef0, literal10); // a > 10
    RexNode condition2 = rexBuilder.makeCall(SqlStdOperatorTable.EQUALS, inputRef1, literal5); // b = 5

    RexNode queryFilter = rexBuilder.makeCall(SqlStdOperatorTable.AND, condition1, condition2); // a > 10 AND b = 5
    RexNode mvFilter = condition1; // a > 10

    FilterImplicationChecker.ImplicationResult result =
        FilterImplicationChecker.checkImplication(queryFilter, mvFilter, rexBuilder);

    assertTrue(result.implies(), "Query filter should imply MV filter");
    assertFalse(result.isExactMatch(), "Should not be exact match");
    assertNotNull(result.getResidualFilter(), "Should have residual filter");
    String residualStr = result.getResidualFilter().toString();
    assertTrue(residualStr.contains("=($1, 5)") || (residualStr.contains("5") && residualStr.contains("$1")),
        "Residual should contain b = 5");
  }

  @Test
  public void testNoImplication() {
    // Query: WHERE b = 5
    // MV: WHERE a > 10
    // Result: No implication

    RexNode inputRef0 = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 0);
    RexNode inputRef1 = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 1);
    RexNode literal10 = rexBuilder.makeLiteral(10, typeFactory.createSqlType(SqlTypeName.INTEGER), false);
    RexNode literal5 = rexBuilder.makeLiteral(5, typeFactory.createSqlType(SqlTypeName.INTEGER), false);

    RexNode queryFilter = rexBuilder.makeCall(SqlStdOperatorTable.EQUALS, inputRef1, literal5); // b = 5
    RexNode mvFilter = rexBuilder.makeCall(SqlStdOperatorTable.GREATER_THAN, inputRef0, literal10); // a > 10

    FilterImplicationChecker.ImplicationResult result =
        FilterImplicationChecker.checkImplication(queryFilter, mvFilter, rexBuilder);

    assertFalse(result.implies(), "Query filter should NOT imply MV filter");
    assertNull(result.getResidualFilter(), "Should have no residual filter");
  }

  @Test
  public void testQueryWithMultipleAdditionalFilters() {
    // Query: WHERE a > 10 AND b = 5 AND c < 100
    // MV: WHERE a > 10
    // Result: Implies with residual (b = 5 AND c < 100)

    RexNode inputRef0 = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 0);
    RexNode inputRef1 = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 1);
    RexNode inputRef2 = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 2);
    RexNode literal10 = rexBuilder.makeLiteral(10, typeFactory.createSqlType(SqlTypeName.INTEGER), false);
    RexNode literal5 = rexBuilder.makeLiteral(5, typeFactory.createSqlType(SqlTypeName.INTEGER), false);
    RexNode literal100 = rexBuilder.makeLiteral(100, typeFactory.createSqlType(SqlTypeName.INTEGER), false);

    RexNode condition1 = rexBuilder.makeCall(SqlStdOperatorTable.GREATER_THAN, inputRef0, literal10); // a > 10
    RexNode condition2 = rexBuilder.makeCall(SqlStdOperatorTable.EQUALS, inputRef1, literal5); // b = 5
    RexNode condition3 = rexBuilder.makeCall(SqlStdOperatorTable.LESS_THAN, inputRef2, literal100); // c < 100

    RexNode queryFilter =
        rexBuilder.makeCall(SqlStdOperatorTable.AND, condition1, rexBuilder.makeCall(SqlStdOperatorTable.AND, condition2,
            condition3)); // a > 10 AND (b = 5 AND c < 100)
    RexNode mvFilter = condition1; // a > 10

    FilterImplicationChecker.ImplicationResult result =
        FilterImplicationChecker.checkImplication(queryFilter, mvFilter, rexBuilder);

    assertTrue(result.implies(), "Query filter should imply MV filter");
    assertNotNull(result.getResidualFilter(), "Should have residual filter");
    String residualStr = result.getResidualFilter().toString();
    assertTrue(residualStr.contains("$1") && residualStr.contains("5"), "Residual should contain b = 5");
    assertTrue(residualStr.contains("$2") && residualStr.contains("100"), "Residual should contain c < 100");
  }

  @Test
  public void testNullFilters() {
    RexNode nonNullFilter = createFilter(">($0, 10)");

    FilterImplicationChecker.ImplicationResult result1 =
        FilterImplicationChecker.checkImplication(null, nonNullFilter, rexBuilder);
    assertFalse(result1.implies(), "Null query filter should not imply");

    FilterImplicationChecker.ImplicationResult result2 =
        FilterImplicationChecker.checkImplication(nonNullFilter, null, rexBuilder);
    assertFalse(result2.implies(), "Null MV filter should not imply");

    FilterImplicationChecker.ImplicationResult result3 = FilterImplicationChecker.checkImplication(null, null, rexBuilder);
    assertFalse(result3.implies(), "Both null filters should not imply");
  }

  @Test
  public void testImpliesSimpleMethod() {
    // Test the convenience method
    RexNode filter1 = createFilter(">($0, 10)");
    RexNode filter2 = createFilter(">($0, 10)");

    boolean result = FilterImplicationChecker.impliesSimple(filter1, filter2);
    assertTrue(result, "impliesSimple should return true for exact match");

    RexNode filter3 = createFilter("=($1, 5)");
    boolean result2 = FilterImplicationChecker.impliesSimple(filter3, filter1);
    assertFalse(result2, "impliesSimple should return false for no implication");
  }

  // Helper method to create a simple filter RexNode
  private RexNode createFilter(String condition) {
    // For simple tests, we'll create basic RexNodes
    // In reality, these would be parsed from SQL or constructed properly
    if (condition.startsWith(">($0,")) {
      RexNode inputRef = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 0);
      RexNode literal = rexBuilder.makeLiteral(10, typeFactory.createSqlType(SqlTypeName.INTEGER), false);
      return rexBuilder.makeCall(SqlStdOperatorTable.GREATER_THAN, inputRef, literal);
    }
    // Default: return a simple condition
    RexNode inputRef = rexBuilder.makeInputRef(typeFactory.createSqlType(SqlTypeName.INTEGER), 0);
    RexNode literal = rexBuilder.makeLiteral(0, typeFactory.createSqlType(SqlTypeName.INTEGER), false);
    return rexBuilder.makeCall(SqlStdOperatorTable.GREATER_THAN, inputRef, literal);
  }
}

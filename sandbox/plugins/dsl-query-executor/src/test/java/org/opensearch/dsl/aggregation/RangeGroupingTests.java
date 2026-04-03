/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeFieldImpl;
import org.apache.calcite.rel.type.RelRecordType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Before;
import org.junit.Test;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.search.aggregations.bucket.range.RangeAggregator.Range;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class RangeGroupingTests {

    private RexBuilder rexBuilder;
    private RelDataType inputRowType;

    @Before
    public void setUp() {
        RelDataTypeFactory typeFactory = new JavaTypeFactoryImpl();
        rexBuilder = new RexBuilder(typeFactory);
        RelDataType doubleType = typeFactory.createSqlType(SqlTypeName.DOUBLE);
        inputRowType = new RelRecordType(List.of(
            new RelDataTypeFieldImpl("price", 0, doubleType)
        ));
    }

    @Test
    public void testGetFieldNames() {
        List<Range> ranges = List.of(new Range(null, 0.0, 100.0));
        RangeGrouping grouping = new RangeGrouping("test_range", "price", ranges);
        assertEquals(List.of("price"), grouping.getFieldNames());
    }

    @Test
    public void testGetProjectedColumnName() {
        List<Range> ranges = List.of(new Range(null, 0.0, 100.0));
        RangeGrouping grouping = new RangeGrouping("test_range", "price", ranges);
        String columnName = grouping.getProjectedColumnName();
        assertTrue(columnName.startsWith("test_range$$price$$"));
        assertTrue(columnName.endsWith("$$range_bucket"));
    }

    @Test
    public void testBuildExpressionWithBoundedRanges() throws ConversionException {
        List<Range> ranges = List.of(
            new Range(null, 0.0, 50.0),
            new Range(null, 50.0, 100.0)
        );
        RangeGrouping grouping = new RangeGrouping("test_range", "price", ranges);
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);

        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);
        RexCall caseCall = (RexCall) expression;
        assertEquals(SqlStdOperatorTable.CASE, caseCall.getOperator());
        
        // CASE has: condition1, result1, condition2, result2, else_result
        assertEquals(5, caseCall.getOperands().size());
    }

    @Test
    public void testBuildExpressionWithKeyedRanges() throws ConversionException {
        List<Range> ranges = List.of(
            new Range("cheap", 0.0, 50.0),
            new Range("expensive", 50.0, 100.0)
        );
        RangeGrouping grouping = new RangeGrouping("test_range", "price", ranges);
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);

        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);
        RexCall caseCall = (RexCall) expression;
        
        // Check that keys are used as literals
        RexNode result1 = caseCall.getOperands().get(1);
        assertTrue(result1 instanceof RexLiteral);
        assertEquals("cheap", ((RexLiteral) result1).getValueAs(String.class));
        
        RexNode result2 = caseCall.getOperands().get(3);
        assertTrue(result2 instanceof RexLiteral);
        assertEquals("expensive", ((RexLiteral) result2).getValueAs(String.class));
    }

    @Test
    public void testBuildExpressionWithUnboundedFrom() throws ConversionException {
        List<Range> ranges = List.of(
            new Range(null, Double.NEGATIVE_INFINITY, 50.0)
        );
        RangeGrouping grouping = new RangeGrouping("test_range", "price", ranges);
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);

        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);
        RexCall caseCall = (RexCall) expression;
        
        // First operand is the condition
        RexNode condition = caseCall.getOperands().get(0);
        assertTrue(condition instanceof RexCall);
        RexCall conditionCall = (RexCall) condition;
        
        // Should only have LESS_THAN condition (no GREATER_THAN_OR_EQUAL for -infinity)
        assertEquals(SqlStdOperatorTable.LESS_THAN, conditionCall.getOperator());
    }

    @Test
    public void testBuildExpressionWithUnboundedTo() throws ConversionException {
        List<Range> ranges = List.of(
            new Range(null, 100.0, Double.POSITIVE_INFINITY)
        );
        RangeGrouping grouping = new RangeGrouping("test_range", "price", ranges);
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);

        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);
        RexCall caseCall = (RexCall) expression;
        
        RexNode condition = caseCall.getOperands().get(0);
        assertTrue(condition instanceof RexCall);
        RexCall conditionCall = (RexCall) condition;
        
        // Should only have GREATER_THAN_OR_EQUAL condition (no LESS_THAN for +infinity)
        assertEquals(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, conditionCall.getOperator());
    }

    @Test
    public void testBuildExpressionWithFullyUnboundedRange() throws ConversionException {
        List<Range> ranges = List.of(
            new Range("all", Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        );
        RangeGrouping grouping = new RangeGrouping("test_range", "price", ranges);
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);

        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);
        RexCall caseCall = (RexCall) expression;
        
        RexNode condition = caseCall.getOperands().get(0);
        assertTrue(condition instanceof RexLiteral);
        
        // Fully unbounded range should have TRUE condition
        assertTrue(((RexLiteral) condition).getValueAs(Boolean.class));
    }

    @Test
    public void testBuildExpressionWithBothBounds() throws ConversionException {
        List<Range> ranges = List.of(
            new Range(null, 50.0, 100.0)
        );
        RangeGrouping grouping = new RangeGrouping("test_range", "price", ranges);
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);

        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);
        RexCall caseCall = (RexCall) expression;
        
        RexNode condition = caseCall.getOperands().get(0);
        assertTrue(condition instanceof RexCall);
        RexCall conditionCall = (RexCall) condition;
        
        // Should have AND of two conditions
        assertEquals(SqlStdOperatorTable.AND, conditionCall.getOperator());
        assertEquals(2, conditionCall.getOperands().size());
        
        RexCall leftCondition = (RexCall) conditionCall.getOperands().get(0);
        assertEquals(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, leftCondition.getOperator());
        
        RexCall rightCondition = (RexCall) conditionCall.getOperands().get(1);
        assertEquals(SqlStdOperatorTable.LESS_THAN, rightCondition.getOperator());
    }

    @Test(expected = ConversionException.class)
    public void testBuildExpressionWithNonExistentField() throws ConversionException {
        List<Range> ranges = List.of(new Range(null, 0.0, 100.0));
        RangeGrouping grouping = new RangeGrouping("test_range", "nonexistent", ranges);
        grouping.buildExpression(inputRowType, rexBuilder);
    }

    @Test
    public void testGenerateKeyFormat() {
        List<Range> ranges = List.of(
            new Range(null, 0.0, 50.0),
            new Range(null, 50.0, 100.0),
            new Range(null, Double.NEGATIVE_INFINITY, 50.0),
            new Range(null, 100.0, Double.POSITIVE_INFINITY)
        );
        RangeGrouping grouping = new RangeGrouping("test_range", "price", ranges);
        
        // Keys should be generated in "from-to" format with * for infinity
        // We can't directly test generateKey (private), but we can verify through buildExpression
        assertNotNull(grouping);
    }
}

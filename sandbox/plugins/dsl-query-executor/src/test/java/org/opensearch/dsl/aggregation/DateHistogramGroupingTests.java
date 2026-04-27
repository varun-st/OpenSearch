/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation;

import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeFieldImpl;
import org.apache.calcite.rel.type.RelRecordType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Before;
import org.junit.Test;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramInterval;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class DateHistogramGroupingTests {

    private RexBuilder rexBuilder;
    private RelDataType inputRowType;

    @Before
    public void setUp() {
        RelDataTypeFactory typeFactory = new JavaTypeFactoryImpl();
        rexBuilder = new RexBuilder(typeFactory);
        RelDataType timestampType = typeFactory.createSqlType(SqlTypeName.TIMESTAMP);
        inputRowType = new RelRecordType(List.of(
            new RelDataTypeFieldImpl("timestamp", 0, timestampType)
        ));
    }

    @Test
    public void testGetFieldNames() {
        DateHistogramGrouping grouping = new DateHistogramGrouping("test_date_histogram", "timestamp", DateHistogramInterval.MONTH, null, 0L);
        assertEquals(List.of("timestamp"), grouping.getFieldNames());
    }

    @Test
    public void testGetProjectedColumnName() {
        DateHistogramGrouping grouping = new DateHistogramGrouping("test_date_histogram", "timestamp", DateHistogramInterval.MONTH, null, 0L);
        String projectedName = grouping.getProjectedColumnName();
        assertTrue(projectedName.startsWith("test_date_histogram$$timestamp$$"));
    }

    @Test
    public void testBuildExpression_CalendarInterval() throws ConversionException {
        DateHistogramGrouping grouping = new DateHistogramGrouping("test_date_histogram", "timestamp", DateHistogramInterval.MONTH, null, 0L);
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);

        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);

        RexCall floorCall = (RexCall) expression;
        assertEquals(SqlStdOperatorTable.FLOOR, floorCall.getOperator());
        assertEquals(2, floorCall.getOperands().size());
        assertTrue(floorCall.getOperands().get(0) instanceof RexInputRef);
        assertTrue(floorCall.getOperands().get(1) instanceof RexLiteral);
    }

    @Test
    public void testBuildExpression_CalendarIntervalAndOffset() throws ConversionException {
        DateHistogramInterval[] intervals = {
            DateHistogramInterval.YEAR,
            DateHistogramInterval.QUARTER,
            DateHistogramInterval.MONTH,
            DateHistogramInterval.WEEK,
            DateHistogramInterval.DAY,
            DateHistogramInterval.HOUR,
            DateHistogramInterval.MINUTE,
            DateHistogramInterval.SECOND
        };

        for (DateHistogramInterval interval : intervals) {
            DateHistogramGrouping grouping = new DateHistogramGrouping("test", "timestamp", interval, null, 3600000L);
            RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);
            assertNotNull(expression);
            assertTrue(expression instanceof RexCall);
            
            RexCall plusCall = (RexCall) expression;
            assertEquals(SqlStdOperatorTable.PLUS, plusCall.getOperator());
            assertEquals(2, plusCall.getOperands().size());

            RexNode floorNode = plusCall.getOperands().get(0);
            assertTrue(floorNode instanceof RexCall);
            assertEquals(SqlStdOperatorTable.FLOOR, ((RexCall) floorNode).getOperator());

            RexNode minusNode = ((RexCall) floorNode).getOperands().get(0);
            assertTrue(minusNode instanceof RexCall);
            assertEquals(SqlStdOperatorTable.MINUS, ((RexCall) minusNode).getOperator());

            assertTrue(plusCall.getOperands().get(1) instanceof RexLiteral);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBuildExpression_UnsupportedCalendarInterval() throws ConversionException {
        DateHistogramGrouping grouping = new DateHistogramGrouping("test", "timestamp", new DateHistogramInterval("1x"), null, 0L);
        grouping.buildExpression(inputRowType, rexBuilder);
    }

    @Test
    public void testBuildExpression_FixedInterval() throws ConversionException {
        DateHistogramGrouping grouping = new DateHistogramGrouping("test_date_histogram", "timestamp", null, DateHistogramInterval.hours(2), 0L);
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);

        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);

        RexCall outerCall = (RexCall) expression;
        assertEquals(SqlStdOperatorTable.PLUS, outerCall.getOperator());
        assertEquals(2, outerCall.getOperands().size());

        RexNode multiplyNode = outerCall.getOperands().get(0);
        assertTrue(multiplyNode instanceof RexCall);
        assertEquals(SqlStdOperatorTable.MULTIPLY, ((RexCall) multiplyNode).getOperator());

        RexNode floorNode = ((RexCall) multiplyNode).getOperands().get(0);
        assertTrue(floorNode instanceof RexCall);
        assertEquals(SqlStdOperatorTable.FLOOR, ((RexCall) floorNode).getOperator());

        RexNode divideNode = ((RexCall) floorNode).getOperands().get(0);
        assertTrue(divideNode instanceof RexCall);
        assertEquals(SqlStdOperatorTable.DIVIDE, ((RexCall) divideNode).getOperator());
    }

    @Test(expected = ConversionException.class)
    public void testBuildExpression_InvalidField() throws ConversionException {
        DateHistogramGrouping grouping = new DateHistogramGrouping("test_date_histogram", "invalid_field", DateHistogramInterval.MONTH, null, 0L);
        grouping.buildExpression(inputRowType, rexBuilder);
    }
}

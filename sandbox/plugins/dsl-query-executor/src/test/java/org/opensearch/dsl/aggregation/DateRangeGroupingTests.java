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
import org.opensearch.common.time.DateFormatter;
import org.opensearch.common.time.DateMathParser;
import org.opensearch.OpenSearchParseException;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.search.aggregations.bucket.range.RangeAggregator.Range;

import java.time.ZoneId;
import java.util.List;

import static org.junit.Assert.*;

public class DateRangeGroupingTests {

    private RexBuilder rexBuilder;
    private RelDataType inputRowType;
    private DateMathParser dateMathParser;
    private ZoneId timeZone;

    @Before
    public void setUp() {
        RelDataTypeFactory typeFactory = new JavaTypeFactoryImpl();
        rexBuilder = new RexBuilder(typeFactory);
        RelDataType longType = typeFactory.createSqlType(SqlTypeName.BIGINT);
        inputRowType = new RelRecordType(List.of(
            new RelDataTypeFieldImpl("timestamp", 0, longType)
        ));
        
        DateFormatter formatter = DateFormatter.forPattern("strict_date_optional_time||epoch_millis");
        dateMathParser = formatter.toDateMathParser();
        timeZone = ZoneId.of("UTC");
    }

    @Test
    public void testGetFieldNames() throws ConversionException {
        List<Range> ranges = List.of(new Range(null, 0.0, 100.0));
        DateRangeGrouping grouping = new DateRangeGrouping("test_range", "timestamp", ranges, dateMathParser, System::currentTimeMillis, timeZone);
        assertEquals(List.of("timestamp"), grouping.getFieldNames());
    }

    @Test
    public void testGetProjectedColumnName() throws ConversionException {
        List<Range> ranges = List.of(new Range(null, 0.0, 100.0));
        DateRangeGrouping grouping = new DateRangeGrouping("test_range", "timestamp", ranges, dateMathParser, System::currentTimeMillis, timeZone);
        String columnName = grouping.getProjectedColumnName();
        assertTrue(columnName.startsWith("test_range$$timestamp$$"));
        assertTrue(columnName.endsWith("$$date_range_bucket"));
    }

    @Test
    public void testBuildExpressionWithNumericRanges() throws ConversionException {
        List<Range> ranges = List.of(
            new Range(null, 0.0, 1000000000000.0),
            new Range(null, 1000000000000.0, 2000000000000.0)
        );
        DateRangeGrouping grouping = new DateRangeGrouping("test_range", "timestamp", ranges, dateMathParser, System::currentTimeMillis, timeZone);
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);

        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);
        RexCall caseCall = (RexCall) expression;
        assertEquals(SqlStdOperatorTable.CASE, caseCall.getOperator());
        assertEquals(5, caseCall.getOperands().size());
    }

    @Test
    public void testBuildExpressionWithDateMathNow() throws ConversionException {
        // Create range with "now" - should resolve to current time
        long fixedNow = 1700000000000L;
        List<Range> ranges = List.of(
            new Range(null, null, "now", null, null)
        );
        
        DateRangeGrouping grouping = new DateRangeGrouping(
            "test_range", "timestamp", ranges, 
            dateMathParser, () -> fixedNow, timeZone
        );
        
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);
        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);
    }

    @Test
    public void testBuildExpressionWithDateMathRelative() throws ConversionException {
        // Test "now-7d" expression
        long fixedNow = 1700000000000L; // Fixed timestamp for testing
        List<Range> ranges = List.of(
            new Range("last_week", null, "now-7d", null, "now")
        );
        
        DateRangeGrouping grouping = new DateRangeGrouping(
            "test_range", "timestamp", ranges,
            dateMathParser, () -> fixedNow, timeZone
        );
        
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);
        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);
        
        RexCall caseCall = (RexCall) expression;
        // Check that key is preserved
        RexNode keyLiteral = caseCall.getOperands().get(1);
        assertTrue(keyLiteral instanceof RexLiteral);
        assertEquals("last_week", ((RexLiteral) keyLiteral).getValueAs(String.class));
    }

    @Test
    public void testBuildExpressionWithDateMathRounding() throws ConversionException {
        // Test "now/d" (round to day)
        long fixedNow = 1700000000000L;
        List<Range> ranges = List.of(
            new Range("today", null, "now/d", null, "now+1d/d")
        );
        
        DateRangeGrouping grouping = new DateRangeGrouping(
            "test_range", "timestamp", ranges,
            dateMathParser, () -> fixedNow, timeZone
        );
        
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);
        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);
    }

    @Test
    public void testBuildExpressionWithMixedFormats() throws ConversionException {
        // Mix date math and numeric values
        List<Range> ranges = List.of(
            new Range("old", null, "0", null, "now-30d"),
            new Range("recent", null, "now-30d", null, "now")
        );
        
        long fixedNow = 1700000000000L;
        DateRangeGrouping grouping = new DateRangeGrouping(
            "test_range", "timestamp", ranges,
            dateMathParser, () -> fixedNow, timeZone
        );
        
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);
        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);
        
        RexCall caseCall = (RexCall) expression;
        assertEquals(5, caseCall.getOperands().size()); // 2 ranges + else
    }

    @Test
    public void testBuildExpressionWithAbsoluteDates() throws ConversionException {
        // Test absolute date strings
        List<Range> ranges = List.of(
            new Range("2024", null, "2024-01-01", null, "2025-01-01")
        );
        
        DateRangeGrouping grouping = new DateRangeGrouping(
            "test_range", "timestamp", ranges,
            dateMathParser, System::currentTimeMillis, timeZone
        );
        
        RexNode expression = grouping.buildExpression(inputRowType, rexBuilder);
        assertNotNull(expression);
        assertTrue(expression instanceof RexCall);
    }

    @Test(expected = OpenSearchParseException.class)
    public void testDateMathParsingFallback() throws ConversionException {
        // Test that invalid date math throws exception
        List<Range> ranges = List.of(
            new Range(null, 0.0, "invalid-date", null, null)
        );
        
        new DateRangeGrouping(
            "test_range", "timestamp", ranges,
            dateMathParser, System::currentTimeMillis, timeZone
        );
    }

    @Test(expected = ConversionException.class)
    public void testWithNullParser() throws ConversionException {
        // Test that null parser with date string throws exception
        List<Range> ranges = List.of(
            new Range(null, 1000.0, "now", null, null)
        );
        
        new DateRangeGrouping(
            "test_range", "timestamp", ranges,
            null, System::currentTimeMillis, timeZone
        );
    }

    @Test(expected = ConversionException.class)
    public void testBuildExpressionWithNonExistentField() throws ConversionException {
        List<Range> ranges = List.of(new Range(null, 0.0, 100.0));
        DateRangeGrouping grouping = new DateRangeGrouping("test_range", "nonexistent", ranges, dateMathParser, System::currentTimeMillis, timeZone);
        grouping.buildExpression(inputRowType, rexBuilder);
    }
}

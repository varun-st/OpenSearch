/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation;

import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramInterval;

import java.math.BigDecimal;
import java.util.List;

/**
 * Expression-based grouping for date histogram bucket aggregations.
 * Computes bucket keys using calendar intervals (year, quarter, month, etc.) or fixed intervals.
 * Calendar intervals: FLOOR((timestamp - offset) TO unit) + offset
 * Fixed intervals: FLOOR((timestamp - offset) / interval) * interval + offset
 */
public class DateHistogramGrouping implements ExpressionGrouping {

    private final String aggregationName;
    private final String fieldName;
    private final DateHistogramInterval calendarInterval;
    private final DateHistogramInterval fixedInterval;
    private final long offset;
    private final String uniqueId;

    public DateHistogramGrouping(String aggregationName, String fieldName, DateHistogramInterval calendarInterval,
                                  DateHistogramInterval fixedInterval, long offset) {
        this.aggregationName = aggregationName;
        this.fieldName = fieldName;
        this.calendarInterval = calendarInterval;
        this.fixedInterval = fixedInterval;
        this.offset = offset;

        String intervalStr = calendarInterval != null ? calendarInterval.toString() : fixedInterval.toString();
        String key = aggregationName + ":" + fieldName + ":" + intervalStr + ":" + offset;
        this.uniqueId = Integer.toHexString(key.hashCode());
    }

    @Override
    public List<String> getFieldNames() {
        return List.of(fieldName);
    }

    @Override
    public String getProjectedColumnName() {
        return aggregationName + "$$" + fieldName + "$$" + uniqueId;
    }

    @Override
    public RexNode buildExpression(RelDataType inputRowType, RexBuilder builder) throws ConversionException {
        RelDataTypeField field = inputRowType.getField(fieldName, false, false);
        if (field == null) {
            throw new ConversionException("Field not found: " + fieldName);
        }

        RexNode fieldRef = builder.makeInputRef(field.getType(), field.getIndex());

        if (calendarInterval != null) {
            return buildCalendarExpression(fieldRef, builder);
        } else {
            return buildFixedExpression(fieldRef, builder);
        }
    }

    private RexNode buildCalendarExpression(RexNode fieldRef, RexBuilder builder) {
        TimeUnit unit = mapToTimeUnit(calendarInterval.toString());

        if (offset == 0) {
            // No offset: FLOOR(timestamp TO unit)
            return builder.makeCall(SqlStdOperatorTable.FLOOR, fieldRef, builder.makeFlag(unit));
        }

        // With offset: FLOOR((timestamp - offset) TO unit) + offset
        RexNode offsetLiteral = builder.makeBigintLiteral(BigDecimal.valueOf(offset));
        RexNode adjusted = builder.makeCall(SqlStdOperatorTable.MINUS, fieldRef, offsetLiteral);
        RexNode floored = builder.makeCall(SqlStdOperatorTable.FLOOR, adjusted, builder.makeFlag(unit));
        return builder.makeCall(SqlStdOperatorTable.PLUS, floored, offsetLiteral);
    }

    private RexNode buildFixedExpression(RexNode fieldRef, RexBuilder builder) {
        long intervalMs = parseFixedInterval(fixedInterval.toString());
        RexNode intervalLiteral = builder.makeBigintLiteral(BigDecimal.valueOf(intervalMs));
        RexNode offsetLiteral = builder.makeBigintLiteral(BigDecimal.valueOf(offset));

        // Date histogram bucketing formula: FLOOR((timestamp - offset) / interval) * interval + offset
        RexNode adjusted = builder.makeCall(SqlStdOperatorTable.MINUS, fieldRef, offsetLiteral);
        RexNode divided = builder.makeCall(SqlStdOperatorTable.DIVIDE, adjusted, intervalLiteral);
        RexNode floored = builder.makeCall(SqlStdOperatorTable.FLOOR, divided);
        RexNode multiplied = builder.makeCall(SqlStdOperatorTable.MULTIPLY, floored, intervalLiteral);
        return builder.makeCall(SqlStdOperatorTable.PLUS, multiplied, offsetLiteral);
    }

    private TimeUnit mapToTimeUnit(String interval) {
        return switch (interval) {
            case "1y" -> TimeUnit.YEAR;
            case "1q" -> TimeUnit.QUARTER;
            case "1M" -> TimeUnit.MONTH;
            case "1w" -> TimeUnit.WEEK;
            case "1d" -> TimeUnit.DAY;
            case "1h" -> TimeUnit.HOUR;
            case "1m" -> TimeUnit.MINUTE;
            case "1s" -> TimeUnit.SECOND;
            default -> throw new IllegalArgumentException("Unsupported calendar interval: " + interval);
        };
    }

    private long parseFixedInterval(String interval) {
        return TimeValue.parseTimeValue(interval, null, "DateHistogramGrouping.parseFixedInterval").millis();
    }
}

/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.opensearch.dsl.exception.ConversionException;

import java.math.BigDecimal;
import java.util.List;

/**
 * Expression-based grouping for histogram bucket aggregations.
 * Computes bucket keys using: FLOOR((field - offset) / interval) * interval + offset
 */
public class HistogramGrouping implements ExpressionGrouping {

    private static final String PROJECTED_COLUMN_SUFFIX = "_histogram_bucket";

    private final String fieldName;
    private final double interval;
    private final double offset;

    /**
     * Creates a histogram grouping with specified bucketing parameters.
     * @param fieldName the numeric field to bucket
     * @param interval the bucket width
     * @param offset the bucket alignment offset
     */
    public HistogramGrouping(String fieldName, double interval, double offset) {
        this.fieldName = fieldName;
        this.interval = interval;
        this.offset = offset;
    }

    @Override
    public List<String> getFieldNames() {
        return List.of(fieldName);
    }

    @Override
    public String getProjectedColumnName() {
        return fieldName + PROJECTED_COLUMN_SUFFIX;
    }

    @Override
    public RexNode buildExpression(RelDataType inputRowType, RexBuilder builder) throws ConversionException {
        RelDataTypeField field = inputRowType.getField(fieldName, true, false);
        if (field == null) {
            throw ConversionException.invalidField(fieldName);
        }

        RexNode fieldRef = builder.makeInputRef(field.getType(), field.getIndex());
        RexNode intervalLiteral = builder.makeExactLiteral(BigDecimal.valueOf(interval));
        RexNode offsetLiteral = builder.makeExactLiteral(BigDecimal.valueOf(offset));

        RexNode adjusted = builder.makeCall(SqlStdOperatorTable.MINUS, fieldRef, offsetLiteral);
        RexNode divided = builder.makeCall(SqlStdOperatorTable.DIVIDE, adjusted, intervalLiteral);
        RexNode floored = builder.makeCall(SqlStdOperatorTable.FLOOR, divided);
        RexNode multiplied = builder.makeCall(SqlStdOperatorTable.MULTIPLY, floored, intervalLiteral);

        return builder.makeCall(SqlStdOperatorTable.PLUS, multiplied, offsetLiteral);
    }
}

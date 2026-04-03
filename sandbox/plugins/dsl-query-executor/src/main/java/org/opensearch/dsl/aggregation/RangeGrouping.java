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
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.dsl.aggregation.util.RangeUtils;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.search.aggregations.bucket.range.RangeAggregator.Range;

import java.util.ArrayList;
import java.util.List;

/**
 * Expression grouping for range bucket aggregation using CASE WHEN.
 */
public class RangeGrouping implements ExpressionGrouping {
    private final String aggregationName;
    private final String field;
    private final List<Range> ranges;
    private final String uniqueId;

    public RangeGrouping(String aggregationName, String field, List<Range> ranges) {
        this.aggregationName = aggregationName;
        this.field = field;
        this.ranges = ranges;
        
        String key = aggregationName + ":" + field + ":" + ranges.size();
        this.uniqueId = Integer.toHexString(key.hashCode());
    }

    @Override
    public RexNode buildExpression(RelDataType inputRowType, RexBuilder builder) throws ConversionException {
        RelDataTypeField field = inputRowType.getField(this.field, true, false);
        if (field == null) {
            throw new ConversionException("Field not found: " + this.field);
        }
        
        RexNode fieldRef = builder.makeInputRef(field.getType(), field.getIndex());
        RelDataType stringType = builder.getTypeFactory().createSqlType(SqlTypeName.VARCHAR);
        
        List<RexNode> operands = new ArrayList<>();
        
        for (Range range : ranges) {
            List<RexNode> conditions = new ArrayList<>();
            
            if (!Double.isInfinite(range.getFrom())) {
                RexNode fromLiteral = builder.makeApproxLiteral(java.math.BigDecimal.valueOf(range.getFrom()));
                conditions.add(builder.makeCall(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, fieldRef, fromLiteral));
            }
            
            if (!Double.isInfinite(range.getTo())) {
                RexNode toLiteral = builder.makeApproxLiteral(java.math.BigDecimal.valueOf(range.getTo()));
                conditions.add(builder.makeCall(SqlStdOperatorTable.LESS_THAN, fieldRef, toLiteral));
            }
            
            RexNode condition = conditions.isEmpty() ? builder.makeLiteral(true)
                : conditions.size() == 1 ? conditions.get(0)
                : builder.makeCall(SqlStdOperatorTable.AND, conditions);
            
            String key = range.getKey() != null ? range.getKey() : RangeUtils.generateKey(range);
            operands.add(condition);
            operands.add(builder.makeLiteral(key));
        }
        
        operands.add(builder.makeNullLiteral(stringType));
        return builder.makeCall(SqlStdOperatorTable.CASE, operands);
    }

    @Override
    public String getProjectedColumnName() {
        return aggregationName + "$$" + field + "$$" + uniqueId + "$$" + "range_bucket";
    }

    @Override
    public List<String> getFieldNames() {
        return List.of(field);
    }
}

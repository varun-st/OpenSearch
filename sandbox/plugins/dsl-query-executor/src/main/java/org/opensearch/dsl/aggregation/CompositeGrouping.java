/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.opensearch.dsl.converter.ConversionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents composite grouping combining multiple value sources.
 * Supports cursor-based pagination via after-key filtering.
 */
public class CompositeGrouping implements GroupingInfo {
    private final List<GroupingInfo> sourceGroupings;
    private final Map<String, Object> afterKey;

    public CompositeGrouping(List<GroupingInfo> sourceGroupings, Map<String, Object> afterKey) {
        this.sourceGroupings = sourceGroupings;
        this.afterKey = afterKey;
    }

    public List<GroupingInfo> getSourceGroupings() {
        return sourceGroupings;
    }

    public Map<String, Object> getAfterKey() {
        return afterKey;
    }

    public boolean hasAfterKey() {
        return afterKey != null && !afterKey.isEmpty();
    }

    @Override
    public List<String> getFieldNames() {
        List<String> fields = new ArrayList<>();
        for (GroupingInfo grouping : sourceGroupings) {
            fields.addAll(grouping.getFieldNames());
        }
        return fields;
    }

    /**
     * Builds a filter predicate for cursor-based pagination.
     * Generates: (col1 > v1) OR (col1 = v1 AND col2 > v2) OR ...
     *
     * @param inputRowType the input row type
     * @param rexBuilder the RexBuilder for creating expressions
     * @param sourceNames the names of composite sources (for looking up after values)
     * @return the filter predicate, or null if no after key
     * @throws ConversionException if field resolution fails
     */
    public RexNode buildAfterKeyFilter(RelDataType inputRowType, RexBuilder rexBuilder, List<String> sourceNames)
            throws ConversionException {
        if (!hasAfterKey()) {
            return null;
        }

        List<String> fieldNames = getFieldNames();

        if (fieldNames.size() != sourceNames.size()) {
            throw new ConversionException(
                "Field names and source names size mismatch: " + fieldNames.size() + " vs " + sourceNames.size()
            );
        }

        if (fieldNames.size() == 1) {
            return buildComparison(inputRowType, rexBuilder, fieldNames.get(0), sourceNames.get(0),
                SqlStdOperatorTable.GREATER_THAN);
        }

        // Build: (col1 > v1) OR (col1 = v1 AND col2 > v2) OR ...
        List<RexNode> orConditions = new ArrayList<>();

        for (int i = 0; i < fieldNames.size(); i++) {
            List<RexNode> andConditions = new ArrayList<>();

            // Add equality conditions for all previous columns
            for (int j = 0; j < i; j++) {
                andConditions.add(buildComparison(inputRowType, rexBuilder, fieldNames.get(j), sourceNames.get(j),
                    SqlStdOperatorTable.EQUALS));
            }

            andConditions.add(buildComparison(inputRowType, rexBuilder, fieldNames.get(i), sourceNames.get(i),
                SqlStdOperatorTable.GREATER_THAN));
            orConditions.add(combineConditions(rexBuilder, andConditions, SqlStdOperatorTable.AND));
        }

        return combineConditions(rexBuilder, orConditions, SqlStdOperatorTable.OR);
    }

    private RexNode buildComparison(RelDataType inputRowType, RexBuilder rexBuilder, String fieldName,
            String sourceName, SqlOperator operator) throws ConversionException {
        Object afterValue = afterKey.get(sourceName);
        if (afterValue == null) {
            throw new ConversionException("Missing after value for source: " + sourceName);
        }

        RexNode fieldRef = makeFieldReference(inputRowType, rexBuilder, fieldName);
        RexNode literal = rexBuilder.makeLiteral(afterValue, fieldRef.getType(), true);
        return rexBuilder.makeCall(operator, fieldRef, literal);
    }

    private RexNode makeFieldReference(RelDataType inputRowType, RexBuilder rexBuilder, String fieldName)
            throws ConversionException {
        try {
            int fieldIndex = inputRowType.getField(fieldName, false, false).getIndex();
            return rexBuilder.makeInputRef(
                inputRowType.getFieldList().get(fieldIndex).getType(),
                fieldIndex
            );
        } catch (Exception e) {
            throw new ConversionException("Failed to resolve field: " + fieldName, e);
        }
    }

    private RexNode combineConditions(RexBuilder rexBuilder, List<RexNode> conditions,
            SqlOperator operator) {
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("Cannot combine empty conditions");
        }
        return conditions.size() == 1
            ? conditions.get(0)
            : rexBuilder.makeCall(operator, conditions);
    }
}

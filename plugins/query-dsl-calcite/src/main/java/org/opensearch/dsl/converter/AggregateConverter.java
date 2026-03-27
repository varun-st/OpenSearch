/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.converter;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableBitSet;
import org.opensearch.dsl.aggregation.AggregationMetadata;
import org.opensearch.dsl.aggregation.AggregationTreeWalker;
import org.opensearch.dsl.aggregation.ExpressionGrouping;
import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.ConversionContext;
import org.opensearch.dsl.exception.ConversionException;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates a {@code LogicalAggregate} from pre-computed {@link AggregationMetadata}
 * stored on the {@link ConversionContext}.
 *
 * The metadata is produced by {@link AggregationTreeWalker}
 * and set on the context by the service layer before running this pipeline step.
 */
public class AggregateConverter extends AbstractDslConverter {

    /** Creates a new AggregateConverter. */
    public AggregateConverter() {}

    @Override
    protected boolean isApplicable(ConversionContext ctx) {
        return ctx.getAggregationMetadata() != null;
    }

    @Override
    protected void validate(ConversionContext ctx) throws ConversionException {
        ctx.requireRelNodeSupported(LogicalAggregate.class);
    }

    @Override
    protected RelNode doConvert(RelNode input, ConversionContext ctx) throws ConversionException {
        AggregationMetadata metadata = ctx.getAggregationMetadata();

        if (metadata.hasExpressionGrouping()) {
            input = addProjectForExpressions(input, metadata, ctx);
        }

        return LogicalAggregate.create(input, metadata.getGroupByBitSet(), null, metadata.getAggregateCalls());
    }

    private static RelNode addProjectForExpressions(RelNode input, AggregationMetadata metadata, ConversionContext ctx)
            throws ConversionException {
        RexBuilder rexBuilder = ctx.getRexBuilder();
        RelDataType inputRowType = input.getRowType();
        List<RexNode> projects = new ArrayList<>();
        List<String> fieldNames = new ArrayList<>();

        for (RelDataTypeField field : inputRowType.getFieldList()) {
            projects.add(rexBuilder.makeInputRef(field.getType(), field.getIndex()));
            fieldNames.add(field.getName());
        }

        for (GroupingInfo grouping : metadata.getGroupings()) {
            if (grouping instanceof ExpressionGrouping exprGrouping) {
                RexNode expr = exprGrouping.buildExpression(inputRowType, rexBuilder);
                projects.add(expr);
                fieldNames.add(exprGrouping.getProjectedColumnName());
            }
        }

        return LogicalProject.create(input, List.of(), projects, fieldNames);
    }
}

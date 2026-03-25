/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation.metric;

import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.dsl.aggregation.AggregationConversionContext;
import org.opensearch.dsl.exception.ConversionException;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.metrics.ExtendedStatsAggregationBuilder;
import org.opensearch.search.aggregations.metrics.InternalExtendedStats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Translator for extended_stats aggregation.
 * Extended stats produces: count, min, max, sum, sum_of_squares, variance, std_deviation, std_deviation_bounds.
 * Note: avg, stddev are not computed in Calcite - InternalExtendedStats calculates them dynamically.
 * We compute variance to derive sum_of_squares (Calcite lacks SUM(x²) function).
 */
public class ExtendedStatsMetricTranslator implements CompositeMetricTranslator<ExtendedStatsAggregationBuilder> {

    private static final int METRIC_COUNT = 5;
    private static final double DEFAULT_SIGMA = 2.0;
    private static final String COUNT_SUFFIX = "_count";
    private static final String MIN_SUFFIX = "_min";
    private static final String MAX_SUFFIX = "_max";
    private static final String SUM_SUFFIX = "_sum";
    private static final String VARIANCE_SUFFIX = "_variance";

    @Override
    public Class<ExtendedStatsAggregationBuilder> getAggregationType() {
        return ExtendedStatsAggregationBuilder.class;
    }

    @Override
    public List<AggregateCall> toAggregateCalls(ExtendedStatsAggregationBuilder agg, AggregationConversionContext ctx)
            throws ConversionException {
        String fieldName = agg.field();
        RelDataTypeField field = ctx.getRowType().getField(fieldName, true, false);
        if (field == null) {
            throw ConversionException.invalidField(fieldName);
        }
        int fieldIndex = field.getIndex();
        String baseName = agg.getName();

        List<AggregateCall> calls = new ArrayList<>(METRIC_COUNT);

        calls.add(createAggregateCall(SqlStdOperatorTable.COUNT, Collections.emptyList(),
            field.getType(), baseName + COUNT_SUFFIX));
        calls.add(createAggregateCall(SqlStdOperatorTable.MIN, Collections.singletonList(fieldIndex),
            field.getType(), baseName + MIN_SUFFIX));
        calls.add(createAggregateCall(SqlStdOperatorTable.MAX, Collections.singletonList(fieldIndex),
            field.getType(), baseName + MAX_SUFFIX));
        calls.add(createAggregateCall(SqlStdOperatorTable.SUM, Collections.singletonList(fieldIndex),
            field.getType(), baseName + SUM_SUFFIX));
        calls.add(createAggregateCall(SqlStdOperatorTable.VAR_POP, Collections.singletonList(fieldIndex),
            field.getType(), baseName + VARIANCE_SUFFIX));

        return calls;
    }

    private AggregateCall createAggregateCall(SqlAggFunction function, List<Integer> argList,
            RelDataType type, String name) {
        return AggregateCall.create(
            function,
            false, false, true,
            argList,
            -1,
            RelCollations.EMPTY,
            type,
            name
        );
    }

    @Override
    public List<String> getAggregateFieldNames(ExtendedStatsAggregationBuilder agg) {
        String baseName = agg.getName();
        return List.of(
            baseName + COUNT_SUFFIX,
            baseName + MIN_SUFFIX,
            baseName + MAX_SUFFIX,
            baseName + SUM_SUFFIX,
            baseName + VARIANCE_SUFFIX
        );
    }

    @Override
    public InternalAggregation toInternalAggregation(String name, List<Object> values) {
        if (values == null || values.size() != METRIC_COUNT) {
            return buildEmptyAggregation(name);
        }

        long count = values.get(0) != null ? ((Number) values.get(0)).longValue() : 0;
        double min = values.get(1) != null ? ((Number) values.get(1)).doubleValue() : Double.POSITIVE_INFINITY;
        double max = values.get(2) != null ? ((Number) values.get(2)).doubleValue() : Double.NEGATIVE_INFINITY;
        double sum = values.get(3) != null ? ((Number) values.get(3)).doubleValue() : 0;
        double variance = values.get(4) != null ? ((Number) values.get(4)).doubleValue() : 0;

        // Calculate sum_of_squares from variance: variance = (sum_of_squares / count) - (avg²)
        // Therefore: sum_of_squares = variance * count + (sum/count)²  * count
        double avg = count > 0 ? sum / count : 0;
        double sumOfSquares = count > 0 ? (variance * count) + (avg * avg * count) : 0;

        // TODO: Support custom sigma from agg.sigma(). Requires passing ExtendedStatsAggregationBuilder
        // to this method to access the sigma value instead of hardcoding DEFAULT_SIGMA.
        return new InternalExtendedStats(name, count, sum, min, max, sumOfSquares, DEFAULT_SIGMA, DocValueFormat.RAW, null);
    }

    @Override
    public InternalAggregation buildEmptyAggregation(String name) {
        return new InternalExtendedStats(name, 0, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, DEFAULT_SIGMA, DocValueFormat.RAW, null);
    }
}

/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation.metric;

import org.apache.calcite.rel.core.AggregateCall;
import org.opensearch.dsl.aggregation.AggregationConversionContext;
import org.opensearch.dsl.aggregation.AggregationType;
import org.opensearch.dsl.exception.ConversionException;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.InternalAggregation;

import java.util.List;

/**
 * Translator interface for composite metric aggregations that return multiple values.
 *
 * Examples:
 * - stats: returns 5 values (count, min, max, sum, avg)
 * - extended_stats: returns 13 values (stats + variance, std_deviation, etc.)
 * - percentiles: returns N values (p50, p75, p95, p99, etc.)
 *
 * Unlike single-value metrics (AVG, SUM, MIN, MAX), composite metrics produce
 * multiple AggregateCall objects in Calcite, one for each metric value.
 */
public interface CompositeMetricTranslator<T extends AggregationBuilder> extends AggregationType<T> {

    /**
     * Converts the composite metric aggregation to multiple Calcite AggregateCalls.
     * Each call represents one metric value (e.g., count, min, max for stats).
     *
     * @param agg The metric aggregation builder
     * @param ctx The aggregation conversion context
     * @return List of AggregateCall objects, one per metric value
     * @throws ConversionException if the conversion fails
     */
    List<AggregateCall> toAggregateCalls(T agg, AggregationConversionContext ctx) throws ConversionException;

    /**
     * Returns the field names for all metric values in the composite aggregation.
     * The order must match the order of AggregateCalls returned by toAggregateCalls().
     *
     * @param agg The metric aggregation builder
     * @return List of field names, one per metric value
     */
    List<String> getAggregateFieldNames(T agg);

    /**
     * Converts multiple raw result values from Calcite execution into an OpenSearch InternalAggregation.
     * The values list order must match the order of AggregateCalls.
     *
     * @param name   the aggregation name
     * @param values the raw values from execution (one per metric), may contain nulls
     * @return the corresponding InternalAggregation
     */
    InternalAggregation toInternalAggregation(String name, List<Object> values);

    /**
     * Builds an empty aggregation with no data (e.g., count=0, min=+INF, max=-INF).
     * Used when no matching rows are found in query results.
     *
     * @param name the aggregation name
     * @return an empty InternalAggregation
     */
    InternalAggregation buildEmptyAggregation(String name);
}

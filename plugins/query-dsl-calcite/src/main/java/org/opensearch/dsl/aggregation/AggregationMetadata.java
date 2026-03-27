/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation;

import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.util.ImmutableBitSet;
import org.opensearch.dsl.converter.CollationResolver;
import org.opensearch.search.aggregations.BucketOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable metadata collected from walking the aggregation tree.
 *
 * Contains the resolved GROUP BY bit set, aggregate calls, bucket orders,
 * and field name lists needed by downstream converters to build
 * {@code LogicalAggregate} and {@code LogicalSort} nodes.
 *
 * Does NOT compute collations — that is the responsibility of
 * {@link CollationResolver}, which has access
 * to the actual post-aggregation schema.
 *
 * Constructed exclusively by {@link AggregationMetadataBuilder#build}.
 */
public final class AggregationMetadata {

    private final ImmutableBitSet groupByBitSet;
    private final List<String> groupByFieldNames;
    private final List<String> aggregateFieldNames;
    private final List<AggregateCall> aggregateCalls;
    private final List<BucketOrder> bucketOrders;
    private final List<GroupingInfo> groupings;

    AggregationMetadata(
        ImmutableBitSet groupByBitSet,
        List<String> groupByFieldNames,
        List<String> aggregateFieldNames,
        List<AggregateCall> aggregateCalls,
        List<BucketOrder> bucketOrders,
        List<GroupingInfo> groupings
    ) {
        this.groupByBitSet = groupByBitSet;
        this.groupByFieldNames = groupByFieldNames;
        this.aggregateFieldNames = aggregateFieldNames;
        this.aggregateCalls = aggregateCalls;
        this.bucketOrders = bucketOrders;
        this.groupings = groupings;
    }

    /** Returns the bit set of GROUP BY column indices. */
    public ImmutableBitSet getGroupByBitSet() {
        return groupByBitSet;
    }

    /** Returns the field names used in GROUP BY. */
    public List<String> getGroupByFieldNames() {
        return groupByFieldNames;
    }

    /** Returns the output field names for aggregate calls. */
    public List<String> getAggregateFieldNames() {
        return aggregateFieldNames;
    }

    /** Returns the list of Calcite aggregate calls. */
    public List<AggregateCall> getAggregateCalls() {
        return aggregateCalls;
    }

    /** Returns the bucket orders for post-aggregation sorting. */
    public List<BucketOrder> getBucketOrders() {
        return bucketOrders;
    }

    /** Returns true if bucket orders are present. */
    public boolean hasBucketOrders() {
        return !bucketOrders.isEmpty();
    }

    /** Returns the list of grouping info objects. */
    public List<GroupingInfo> getGroupings() {
        return groupings;
    }

    /** Returns true if any grouping is expression-based. */
    public boolean hasExpressionGrouping() {
        return groupings.stream().anyMatch(g -> g instanceof ExpressionGrouping);
    }

    /**
     * Returns the actual column names used in GROUP BY.
     * For expression groupings (histogram, date_histogram), returns projected column names.
     * For field groupings (terms, multi_terms), returns original field names.
     */
    public List<String> getGroupByColumnNames() {
        List<String> columnNames = new ArrayList<>();
        for (GroupingInfo grouping : groupings) {
            if (grouping instanceof ExpressionGrouping exprGrouping) {
                columnNames.add(exprGrouping.getProjectedColumnName());
            } else {
                columnNames.addAll(grouping.getFieldNames());
            }
        }
        return columnNames;
    }
}

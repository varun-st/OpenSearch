/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.result;

import org.opensearch.dsl.aggregation.AggregationMetadataBuilder;
import org.opensearch.dsl.aggregation.AggregationRegistry;
import org.opensearch.dsl.aggregation.AggregationType;
import org.opensearch.dsl.aggregation.ExpressionGrouping;
import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.aggregation.bucket.BucketShape;
import org.opensearch.dsl.aggregation.metric.CompositeMetricTranslator;
import org.opensearch.dsl.aggregation.metric.MetricTranslator;
import org.opensearch.dsl.exception.ConversionException;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts flat {@code Object[][]} execution results from multiple granularity levels
 * into a single nested {@link InternalAggregations} matching the original DSL aggregation tree.
 *
 * Each granularity level (distinct GROUP BY key set) produces a separate {@link ExecutionResult}.
 * This builder walks the original aggregation tree, correlates each node to the correct
 * granularity's result, and reconstructs the nested bucket structure.
 */
public final class AggregationResponseBuilder {

    private static final String GRANULARITY_KEY_DELIMITER = ",";

    private final AggregationRegistry registry;
    private final Map<String, ExecutionResult> granularityMap;

    /**
     * Creates a new builder.
     *
     * @param registry   the aggregation registry for finding handlers
     * @param aggResults all AGGREGATION execution results (one per granularity)
     */
    public AggregationResponseBuilder(AggregationRegistry registry, List<ExecutionResult> aggResults) {
        this.registry = registry;
        this.granularityMap = new HashMap<>();
        for (ExecutionResult result : aggResults) {
            String key = granularityKey(result);
            granularityMap.put(key, result);
        }
    }

    /**
     * Builds the merged InternalAggregations from the original aggregation tree.
     *
     * @param originalAggs the top-level aggregation builders from SearchSourceBuilder
     * @return the nested InternalAggregations
     */
    public InternalAggregations build(Collection<AggregationBuilder> originalAggs) throws ConversionException {
        List<InternalAggregation> aggs = buildLevel(originalAggs, new ArrayList<>(), Map.of());
        return InternalAggregations.from(aggs);
    }

    /**
     * Recursively builds InternalAggregations for one level of the aggregation tree.
     *
     * @param aggs                the aggregation builders at this level
     * @param accumulatedGroupFields accumulated GROUP BY field names from parent buckets
     * @param parentKeyFilter     filter to match parent bucket keys in deeper granularity results
     * @return list of InternalAggregation for this level
     */
    @SuppressWarnings("unchecked")
    private List<InternalAggregation> buildLevel(
            Collection<AggregationBuilder> aggs,
            List<String> accumulatedGroupFields,
            Map<String, Object> parentKeyFilter) throws ConversionException {

        List<InternalAggregation> result = new ArrayList<>();

        for (AggregationBuilder agg : aggs) {
            AggregationType<AggregationBuilder> type = registry.findHandler(agg);

            if (type instanceof CompositeMetricTranslator) {
                result.add(buildCompositeMetric((CompositeMetricTranslator<AggregationBuilder>) type, agg,
                    accumulatedGroupFields, parentKeyFilter));
            } else if (type instanceof MetricTranslator) {
                result.add(buildSingleValueMetric((MetricTranslator<AggregationBuilder>) type, agg,
                    accumulatedGroupFields, parentKeyFilter));
            } else if (type instanceof BucketShape) {
                result.add(buildBucket((BucketShape<AggregationBuilder>) type, agg,
                    accumulatedGroupFields, parentKeyFilter));
            }
        }
        return result;
    }

    private InternalAggregation buildSingleValueMetric(
            MetricTranslator<AggregationBuilder> translator,
            AggregationBuilder agg,
            List<String> accumulatedGroupFields,
            Map<String, Object> parentKeyFilter) {

        String granularityKey = String.join(",", accumulatedGroupFields);
        ExecutionResult result = granularityMap.get(granularityKey);
        if (result == null || result.getRows().length == 0) {
            return translator.toInternalAggregation(agg.getName(), null);
        }

        Map<String, Integer> colIndex = buildColumnIndex(result);

        String metricFieldName = agg.getName();
        Integer colIdx = colIndex.get(metricFieldName);
        if (colIdx == null) {
            return translator.toInternalAggregation(agg.getName(), null);
        }

        if (accumulatedGroupFields.isEmpty()) {
            // No grouping — single row result
            Object value = result.getRows()[0][colIdx];
            return translator.toInternalAggregation(agg.getName(), value);
        }

        // With grouping — find the row matching parent key filter
        Object[] matchingRow = findMatchingRow(result, colIndex, parentKeyFilter);
        Object value = matchingRow != null ? matchingRow[colIdx] : null;
        return translator.toInternalAggregation(agg.getName(), value);
    }

    private InternalAggregation buildCompositeMetric(
            CompositeMetricTranslator<AggregationBuilder> translator,
            AggregationBuilder agg,
            List<String> accumulatedGroupFields,
            Map<String, Object> parentKeyFilter) {

        String granularityKey = String.join(GRANULARITY_KEY_DELIMITER, accumulatedGroupFields);
        ExecutionResult result = granularityMap.get(granularityKey);
        if (result == null || result.getRows().length == 0) {
            return translator.buildEmptyAggregation(agg.getName());
        }

        Map<String, Integer> columnNameToIndex = buildColumnIndex(result);
        List<String> metricFieldNames = translator.getAggregateFieldNames(agg);

        List<Integer> metricColumnIndices = resolveMetricColumnIndices(metricFieldNames, columnNameToIndex);
        if (metricColumnIndices == null) {
            return translator.buildEmptyAggregation(agg.getName());
        }

        Object[] dataRow;
        if (accumulatedGroupFields.isEmpty()) {
            dataRow = result.getRows()[0];
        } else {
            dataRow = findMatchingRow(result, columnNameToIndex, parentKeyFilter);
        }

        if (dataRow == null) {
            return translator.buildEmptyAggregation(agg.getName());
        }

        List<Object> metricValues = extractMetricValues(dataRow, metricColumnIndices);
        if (metricValues == null) {
            return translator.buildEmptyAggregation(agg.getName());
        }

        return translator.toInternalAggregation(agg.getName(), metricValues);
    }

    private List<Integer> resolveMetricColumnIndices(
            List<String> metricFieldNames,
            Map<String, Integer> columnNameToIndex) {
        List<Integer> indices = new ArrayList<>(metricFieldNames.size());
        for (String fieldName : metricFieldNames) {
            Integer columnIndex = columnNameToIndex.get(fieldName);
            if (columnIndex == null) {
                return null;
            }
            indices.add(columnIndex);
        }
        return indices;
    }

    private List<Object> extractMetricValues(
            Object[] dataRow,
            List<Integer> metricColumnIndices) {
        List<Object> values = new ArrayList<>(metricColumnIndices.size());
        for (Integer columnIndex : metricColumnIndices) {
            if (columnIndex >= dataRow.length) {
                return null;
            }
            values.add(dataRow[columnIndex]);
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private InternalAggregation buildBucket(
            BucketShape<AggregationBuilder> shape,
            AggregationBuilder agg,
            List<String> accumulatedGroupFields,
            Map<String, Object> parentKeyFilter) throws ConversionException {

        GroupingInfo grouping = shape.getGrouping(agg);
        List<String> bucketFieldNames = grouping.getFieldNames();
        List<String> newAccumulatedFields = new ArrayList<>(accumulatedGroupFields);
        newAccumulatedFields.addAll(bucketFieldNames);

        String granularityKey = String.join(",", newAccumulatedFields);
        ExecutionResult result = granularityMap.get(granularityKey);
        if (result == null || result.getRows().length == 0) {
            return shape.toBucketAggregation(agg, List.of());
        }

        Map<String, Integer> colIndex = buildColumnIndex(result);

        List<String> columnNamesForLookup = bucketFieldNames;
        if (grouping instanceof ExpressionGrouping exprGrouping) {
            columnNamesForLookup = List.of(exprGrouping.getProjectedColumnName());
        }

        // Filter rows by parent key
        List<Object[]> filteredRows = filterRows(result.getRows(), colIndex, parentKeyFilter);

        // Group filtered rows by this bucket's key columns
        Map<List<Object>, List<Object[]>> groups = groupByKeys(filteredRows, colIndex, columnNamesForLookup);

        // Build bucket entries
        Integer countCol = colIndex.get(AggregationMetadataBuilder.IMPLICIT_COUNT_NAME);
        Collection<AggregationBuilder> subAggs = shape.getSubAggregations(agg);

        List<BucketEntry> bucketEntries = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Object[]>> group : groups.entrySet()) {
            List<Object> keys = group.getKey();
            List<Object[]> groupRows = group.getValue();

            // Doc count from _count column, or default to row count
            long docCount = 1;
            if (countCol != null && !groupRows.isEmpty()) {
                Object countVal = groupRows.get(0)[countCol];
                if (countVal instanceof Number) {
                    docCount = ((Number) countVal).longValue();
                }
            }

            Map<String, Object> childKeyFilter = new HashMap<>(parentKeyFilter);
            for (int i = 0; i < bucketFieldNames.size(); i++) {
                String filterKey = (grouping instanceof ExpressionGrouping exprGrouping)
                    ? exprGrouping.getProjectedColumnName()
                    : bucketFieldNames.get(i);
                childKeyFilter.put(filterKey, keys.get(i));
            }

            // Build sub-aggregations
            InternalAggregations subAggResults;
            if (subAggs != null && !subAggs.isEmpty()) {
                List<InternalAggregation> subAggList = buildLevel(subAggs, newAccumulatedFields, childKeyFilter);
                subAggResults = InternalAggregations.from(subAggList);
            } else {
                subAggResults = InternalAggregations.EMPTY;
            }

            bucketEntries.add(new BucketEntry(keys, docCount, subAggResults));
        }

        long minDocCount = shape.getMinDocCount(agg);
        if (minDocCount > 0) {
            bucketEntries = bucketEntries.stream()
                .filter(entry -> entry.docCount() >= minDocCount)
                .toList();
        }

        return shape.toBucketAggregation(agg, bucketEntries);
    }

    private static Map<String, Integer> buildColumnIndex(ExecutionResult result) {
        Map<String, Integer> index = new HashMap<>();
        List<String> fieldNames = result.getFieldNames();
        for (int i = 0; i < fieldNames.size(); i++) {
            index.put(fieldNames.get(i), i);
        }
        return index;
    }

    private static Object[] findMatchingRow(ExecutionResult result, Map<String, Integer> colIndex,
            Map<String, Object> keyFilter) {
        for (Object[] row : result.getRows()) {
            if (rowMatchesFilter(row, colIndex, keyFilter)) {
                return row;
            }
        }
        return null;
    }

    private static List<Object[]> filterRows(Object[][] rows, Map<String, Integer> colIndex,
            Map<String, Object> keyFilter) {
        if (keyFilter.isEmpty()) {
            return List.of(rows);
        }
        List<Object[]> filtered = new ArrayList<>();
        for (Object[] row : rows) {
            if (rowMatchesFilter(row, colIndex, keyFilter)) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private static boolean rowMatchesFilter(Object[] row, Map<String, Integer> colIndex,
            Map<String, Object> keyFilter) {
        for (Map.Entry<String, Object> entry : keyFilter.entrySet()) {
            Integer col = colIndex.get(entry.getKey());
            if (col == null) return false;
            Object rowVal = row[col];
            Object filterVal = entry.getValue();
            if (!valuesEqual(rowVal, filterVal)) return false;
        }
        return true;
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        // Handle numeric type mismatches (e.g., Long vs Integer)
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    private static Map<List<Object>, List<Object[]>> groupByKeys(
            List<Object[]> rows, Map<String, Integer> colIndex, List<String> keyFieldNames) {
        Map<List<Object>, List<Object[]>> groups = new LinkedHashMap<>();
        for (Object[] row : rows) {
            List<Object> key = new ArrayList<>(keyFieldNames.size());
            for (String fieldName : keyFieldNames) {
                Integer col = colIndex.get(fieldName);
                key.add(col != null ? row[col] : null);
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    private static String granularityKey(ExecutionResult result) {
        if (result.getAggregationMetadata() == null) return "";
        List<String> groupByFields = result.getAggregationMetadata().getGroupByFieldNames();
        if (groupByFields.isEmpty()) return "";
        return groupByFields.stream().collect(Collectors.joining(","));
    }
}

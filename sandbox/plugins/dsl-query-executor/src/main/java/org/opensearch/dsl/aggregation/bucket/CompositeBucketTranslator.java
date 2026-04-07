/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation.bucket;

import org.opensearch.dsl.aggregation.CompositeGrouping;
import org.opensearch.dsl.aggregation.DateHistogramGrouping;
import org.opensearch.dsl.aggregation.GroupingInfo;
import org.opensearch.dsl.aggregation.HistogramGrouping;
import org.opensearch.dsl.aggregation.SimpleFieldGrouping;
import org.opensearch.dsl.converter.ConversionException;
import org.opensearch.dsl.result.BucketEntry;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.BucketOrder;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.aggregations.bucket.composite.CompositeAggregationBuilder;
import org.opensearch.search.aggregations.bucket.composite.CompositeKey;
import org.opensearch.search.aggregations.bucket.composite.CompositeValuesSourceBuilder;
import org.opensearch.search.aggregations.bucket.composite.DateHistogramValuesSourceBuilder;
import org.opensearch.search.aggregations.bucket.composite.HistogramValuesSourceBuilder;
import org.opensearch.search.aggregations.bucket.composite.InternalComposite;
import org.opensearch.search.aggregations.bucket.composite.TermsValuesSourceBuilder;
import org.opensearch.search.aggregations.bucket.missing.MissingOrder;
import org.opensearch.search.sort.SortOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Bucket translator for {@link CompositeAggregationBuilder}.
 */
public class CompositeBucketTranslator implements BucketTranslator<CompositeAggregationBuilder> {

    @Override
    public Class<CompositeAggregationBuilder> getAggregationType() {
        return CompositeAggregationBuilder.class;
    }

    @Override
    public GroupingInfo getGrouping(CompositeAggregationBuilder agg) throws ConversionException {
        List<GroupingInfo> sourceGroupings = new ArrayList<>();

        for (CompositeValuesSourceBuilder<?> source : agg.sources()) {
            if (source instanceof TermsValuesSourceBuilder terms) {
                sourceGroupings.add(new SimpleFieldGrouping(List.of(terms.field())));
            } else if (source instanceof HistogramValuesSourceBuilder histogram) {
                sourceGroupings.add(new HistogramGrouping(source.name(), histogram.field(), histogram.interval(), 0.0));
            } else if (source instanceof DateHistogramValuesSourceBuilder dateHistogram) {
                sourceGroupings.add(new DateHistogramGrouping(
                    source.name(), dateHistogram.field(), dateHistogram.getIntervalAsCalendar(), dateHistogram.getIntervalAsFixed()
                ));
            } else {
                throw new ConversionException("Unsupported composite source type: " + source.getClass().getSimpleName());
            }
        }

        return new CompositeGrouping(sourceGroupings, agg.getAfter());
    }

    @Override
    public BucketOrder getOrder(CompositeAggregationBuilder agg) {
        return null;
    }

    @Override
    public Collection<AggregationBuilder> getSubAggregations(CompositeAggregationBuilder agg) {
        return agg.getSubAggregations();
    }

    @Override
    public InternalAggregation toBucketAggregation(CompositeAggregationBuilder agg, List<BucketEntry> buckets) {
        List<InternalComposite.InternalBucket> compositeBuckets = new ArrayList<>();
        List<String> sourceNames = new ArrayList<>();
        List<DocValueFormat> formats = new ArrayList<>();

        for (CompositeValuesSourceBuilder<?> source : agg.sources()) {
            sourceNames.add(source.name());
            formats.add(DocValueFormat.RAW);
        }

        int[] sortDirectionMultipliers = buildSortDirectionMultipliers(agg.sources());
        MissingOrder[] missingBucketOrders = buildMissingBucketOrders(agg.sources());

        CompositeKey afterKey = null;
        for (BucketEntry entry : buckets) {
            Comparable<?>[] keyValues = entry.keys().stream()
                .map(k -> (Comparable<?>) k)
                .toArray(Comparable[]::new);
            CompositeKey key = new CompositeKey(keyValues);
            compositeBuckets.add(new InternalComposite.InternalBucket(
                sourceNames, formats, key, sortDirectionMultipliers, missingBucketOrders, entry.docCount(), entry.subAggs()
            ));
            afterKey = key;
        }

        return new InternalComposite(
            agg.getName(), agg.size(), sourceNames, formats, compositeBuckets,
            afterKey, sortDirectionMultipliers, missingBucketOrders, false, Map.of()
        );
    }

    private static int[] buildSortDirectionMultipliers(List<CompositeValuesSourceBuilder<?>> sources) {
        int[] multipliers = new int[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            multipliers[i] = sources.get(i).order() == SortOrder.DESC ? -1 : 1;
        }
        return multipliers;
    }

    private static MissingOrder[] buildMissingBucketOrders(List<CompositeValuesSourceBuilder<?>> sources) {
        MissingOrder[] orders = new MissingOrder[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            orders[i] = sources.get(i).missingOrder();
        }
        return orders;
    }
}

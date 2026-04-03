/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation.util;

import org.opensearch.search.aggregations.bucket.range.RangeAggregator.Range;

/**
 * Utility methods for range aggregations.
 */
public final class RangeUtils {

    private RangeUtils() {
        // Utility class
    }

    /**
     * Generates a default key for a range when no explicit key is provided.
     * Format: "from-to" with "*" representing infinity.
     * 
     * @param range the range
     * @return generated key (e.g., "0.0-100.0", "*-50.0", "100.0-*")
     */
    public static String generateKey(Range range) {
        String from = Double.isInfinite(range.getFrom()) ? "*" : String.valueOf(range.getFrom());
        String to = Double.isInfinite(range.getTo()) ? "*" : String.valueOf(range.getTo());
        return from + "-" + to;
    }

    /**
     * Generates a default key for a date range using epoch milliseconds.
     * Format: "epochMillis-epochMillis" with "*" representing infinity.
     * 
     * @param range the range
     * @return generated key (e.g., "1000000000000-2000000000000", "*-1000000000000")
     */
    public static String generateDateRangeKey(Range range) {
        String from = Double.isInfinite(range.getFrom()) ? "*" : String.valueOf((long) range.getFrom());
        String to = Double.isInfinite(range.getTo()) ? "*" : String.valueOf((long) range.getTo());
        return from + "-" + to;
    }
}

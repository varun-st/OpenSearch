/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods for processing GroupingInfo instances, including composite groupings.
 */
public final class GroupingUtils {

    private GroupingUtils() {}

    /**
     * Flattens a list of groupings, expanding composite groupings into their source groupings.
     * Non-composite groupings are returned as-is.
     *
     * @param groupings the list of groupings to flatten
     * @return flattened list of groupings
     */
    public static List<GroupingInfo> flatten(List<GroupingInfo> groupings) {
        List<GroupingInfo> result = new ArrayList<>();
        for (GroupingInfo grouping : groupings) {
            if (grouping instanceof CompositeGrouping composite) {
                result.addAll(composite.getSourceGroupings());
            } else {
                result.add(grouping);
            }
        }
        return result;
    }

    /**
     * Checks if any grouping (including sources within composite groupings) is an expression grouping.
     *
     * @param groupings the list of groupings to check
     * @return true if any expression grouping is found
     */
    public static boolean hasExpressionGrouping(List<GroupingInfo> groupings) {
        return flatten(groupings).stream()
            .anyMatch(g -> g instanceof ExpressionGrouping);
    }
}

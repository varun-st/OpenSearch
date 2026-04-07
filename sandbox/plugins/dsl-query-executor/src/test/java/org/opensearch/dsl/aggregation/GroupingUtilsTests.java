/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.aggregation;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class GroupingUtilsTests {

    @Test
    public void testFlattenWithComposite() {
        SimpleFieldGrouping terms = new SimpleFieldGrouping(List.of("category"));
        HistogramGrouping histogram = new HistogramGrouping("price_hist", "price", 50.0, 0.0);
        CompositeGrouping composite = new CompositeGrouping(List.of(terms, histogram), null);

        List<GroupingInfo> flattened = GroupingUtils.flatten(List.of(composite));

        assertEquals(2, flattened.size());
        assertSame(terms, flattened.get(0));
        assertSame(histogram, flattened.get(1));
    }

    @Test
    public void testFlattenWithMixedGroupings() {
        SimpleFieldGrouping terms1 = new SimpleFieldGrouping(List.of("category"));
        HistogramGrouping histogram = new HistogramGrouping("price_hist", "price", 50.0, 0.0);
        CompositeGrouping composite = new CompositeGrouping(List.of(terms1, histogram), null);

        SimpleFieldGrouping terms2 = new SimpleFieldGrouping(List.of("status"));

        List<GroupingInfo> flattened = GroupingUtils.flatten(List.of(composite, terms2));

        assertEquals(3, flattened.size());
        assertSame(terms1, flattened.get(0));
        assertSame(histogram, flattened.get(1));
        assertSame(terms2, flattened.get(2));
    }

    @Test
    public void testHasExpressionGroupingInComposite() {
        SimpleFieldGrouping terms = new SimpleFieldGrouping(List.of("category"));
        HistogramGrouping histogram = new HistogramGrouping("price_hist", "price", 50.0, 0.0);
        CompositeGrouping composite = new CompositeGrouping(List.of(terms, histogram), null);

        assertTrue(GroupingUtils.hasExpressionGrouping(List.of(composite)));
    }

    @Test
    public void testHasExpressionGroupingWithOnlyFieldGroupings() {
        SimpleFieldGrouping terms1 = new SimpleFieldGrouping(List.of("category"));
        SimpleFieldGrouping terms2 = new SimpleFieldGrouping(List.of("status"));
        CompositeGrouping composite = new CompositeGrouping(List.of(terms1, terms2), null);

        assertFalse(GroupingUtils.hasExpressionGrouping(List.of(composite)));
    }
}

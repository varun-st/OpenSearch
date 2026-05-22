/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.planner.rules;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.opensearch.analytics.spi.OpenSearchAggregateOperators;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HEP rule that decomposes the {@link OpenSearchAggregateOperators#EXTENDED_STATS EXTENDED_STATS}
 * bundle aggregate into five primitive aggregates ({@code SUM(x)}, {@code MIN(x)},
 * {@code MAX(x)}, {@code SUM(x*x)}, {@code COUNT(x)}) plus a {@link LogicalProject} that
 * derives {@code avg}, the variance/std_deviation triplets, and the nested
 * {@code std_deviation_bounds} struct.
 *
 * <p><b>Why decompose at HEP time?</b> Same as {@code OpenSearchStatsReduceRule}:
 * {@code EXTENDED_STATS} has no executor implementation in this codebase and isn't a
 * Calcite standard {@link org.apache.calcite.sql.SqlKind}. Doing the rewrite during HEP
 * keeps the rest of the planner unaware of the bundle aggregate; everything downstream
 * sees only primitives that already have full support.
 *
 * @opensearch.internal
 */
public final class OpenSearchExtendedStatsReduceRule extends RelOptRule {

    public static final OpenSearchExtendedStatsReduceRule INSTANCE = new OpenSearchExtendedStatsReduceRule();

    /** Synthetic name prefix for the squared-value columns added beneath the rebuilt aggregate. */
    private static final String SQUARED_COLUMN_NAME_PREFIX = "$ext_stats_sq_";

    private OpenSearchExtendedStatsReduceRule() {
        super(operand(LogicalAggregate.class, any()), "OpenSearchExtendedStatsReduceRule");
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
        LogicalAggregate aggregate = call.rel(0);
        for (AggregateCall aggCall : aggregate.getAggCallList()) {
            if (isExtendedStats(aggCall.getAggregation())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        LogicalAggregate aggregate = call.rel(0);
        RexBuilder rexBuilder = aggregate.getCluster().getRexBuilder();
        RelDataTypeFactory typeFactory = aggregate.getCluster().getTypeFactory();

        int groupCount = aggregate.getGroupCount();
        boolean hasEmptyGroup = aggregate.getGroupSet().isEmpty();

        Expansion expansion = expandAggCalls(aggregate, hasEmptyGroup, groupCount, rexBuilder);

        LogicalAggregate newAgg = LogicalAggregate.create(
            expansion.aggInput,
            aggregate.getHints(),
            aggregate.getGroupSet(),
            aggregate.getGroupSets(),
            expansion.newCalls
        );

        RelNode project = buildAssemblyProject(aggregate, newAgg, expansion.originalToNew, groupCount, rexBuilder, typeFactory);
        call.transformTo(project);
    }

    /**
     * Expands each EXTENDED_STATS aggregate call into five primitives ({@code SUM(x)},
     * {@code MIN(x)}, {@code MAX(x)}, {@code SUM(x_squared)}, {@code COUNT(x)}, in that
     * emission order); leaves non-EXTENDED_STATS calls untouched.
     *
     * <p>The {@code x_squared} input is computed by a {@link LogicalProject} inserted
     * <em>beneath</em> the rebuilt aggregate, which appends one squared-value column per
     * unique EXTENDED_STATS source column. The Project is shared by every aggregate that
     * needs that derivation. Putting the multiplication into a Project beneath rather than
     * into the aggregate call's {@code rexList} matters because
     * {@code DistributedAggregateRewriter} preserves {@code rexList} verbatim into the
     * FINAL aggregate after Volcano split — and at FINAL time the rexList's input refs
     * would point at the wrong columns of the gathered partial output, producing
     * semantically incorrect results. With the Project beneath, FINAL's argList rebases
     * naturally to the column where PARTIAL's {@code sum_of_squares} output lands.
     *
     * <p>Same SUM-first emission rationale as {@code OpenSearchStatsReduceRule} — keeps
     * {@code typeMatchesInferred} happy after Volcano split when FINAL re-infers types
     * against PARTIAL's first column.
     */
    private static Expansion expandAggCalls(
        LogicalAggregate aggregate,
        boolean hasEmptyGroup,
        int groupCount,
        RexBuilder rexBuilder
    ) {
        RelNode input = aggregate.getInput();

        Map<Integer, Integer> srcColToSquaredCol = new LinkedHashMap<>();
        int origColCount = input.getRowType().getFieldCount();
        int nextSquaredCol = origColCount;
        for (AggregateCall original : aggregate.getAggCallList()) {
            if (isExtendedStats(original.getAggregation())) {
                int srcCol = original.getArgList().get(0);
                if (!srcColToSquaredCol.containsKey(srcCol)) {
                    srcColToSquaredCol.put(srcCol, nextSquaredCol++);
                }
            }
        }

        RelNode aggInput = input;
        if (!srcColToSquaredCol.isEmpty()) {
            aggInput = buildSquaredProject(input, aggregate.getHints(), srcColToSquaredCol, rexBuilder);
        }

        List<AggregateCall> newCalls = new ArrayList<>();
        List<int[]> originalToNew = new ArrayList<>(aggregate.getAggCallList().size());
        for (AggregateCall original : aggregate.getAggCallList()) {
            if (isExtendedStats(original.getAggregation())) {
                int srcCol = original.getArgList().get(0);
                int squaredCol = srcColToSquaredCol.get(srcCol);

                int sumIdx = newCalls.size();
                newCalls.add(makePrimitive(SqlStdOperatorTable.SUM, original, aggInput, hasEmptyGroup, groupCount, List.of(srcCol)));
                int minIdx = newCalls.size();
                newCalls.add(makePrimitive(SqlStdOperatorTable.MIN, original, aggInput, hasEmptyGroup, groupCount, List.of(srcCol)));
                int maxIdx = newCalls.size();
                newCalls.add(makePrimitive(SqlStdOperatorTable.MAX, original, aggInput, hasEmptyGroup, groupCount, List.of(srcCol)));
                int sumOfSqIdx = newCalls.size();
                newCalls.add(
                    makePrimitive(SqlStdOperatorTable.SUM, original, aggInput, hasEmptyGroup, groupCount, List.of(squaredCol))
                );
                int countIdx = newCalls.size();
                newCalls.add(makePrimitive(SqlStdOperatorTable.COUNT, original, aggInput, hasEmptyGroup, groupCount, List.of(srcCol)));
                originalToNew.add(new int[] { countIdx, minIdx, maxIdx, sumIdx, sumOfSqIdx });
            } else {
                originalToNew.add(new int[] { newCalls.size() });
                newCalls.add(original);
            }
        }
        return new Expansion(aggInput, newCalls, originalToNew);
    }

    /**
     * Builds a {@link LogicalProject} that exposes every original input column at its
     * original position and appends one {@code x*x} column per entry in
     * {@code srcColToSquaredCol}. Identity refs for the original columns mean any
     * non-EXTENDED_STATS aggregate call's existing argList continues to resolve correctly.
     */
    private static RelNode buildSquaredProject(
        RelNode input,
        List<org.apache.calcite.rel.hint.RelHint> hints,
        Map<Integer, Integer> srcColToSquaredCol,
        RexBuilder rexBuilder
    ) {
        int origColCount = input.getRowType().getFieldCount();
        int totalCols = origColCount + srcColToSquaredCol.size();
        List<RexNode> projects = new ArrayList<>(totalCols);
        List<String> fieldNames = new ArrayList<>(totalCols);

        for (int i = 0; i < origColCount; i++) {
            RelDataTypeField f = input.getRowType().getFieldList().get(i);
            projects.add(rexBuilder.makeInputRef(f.getType(), i));
            fieldNames.add(f.getName());
        }
        for (Map.Entry<Integer, Integer> entry : srcColToSquaredCol.entrySet()) {
            int srcCol = entry.getKey();
            RelDataTypeField f = input.getRowType().getFieldList().get(srcCol);
            RexNode argRef = rexBuilder.makeInputRef(f.getType(), srcCol);
            RexNode squared = rexBuilder.makeCall(SqlStdOperatorTable.MULTIPLY, argRef, argRef);
            projects.add(squared);
            fieldNames.add(SQUARED_COLUMN_NAME_PREFIX + srcCol);
        }
        return LogicalProject.create(input, hints, projects, fieldNames);
    }

    /**
     * Result of {@link #expandAggCalls}: the input to attach to the rebuilt aggregate
     * (either the original input or a Project that adds squared-value columns), the new
     * aggCall list, and a per-original-call mapping to indices in the new list
     * ({@code [countIdx, minIdx, maxIdx, sumIdx, sumOfSquaresIdx]} for EXTENDED_STATS,
     * single-element for everything else).
     */
    private record Expansion(RelNode aggInput, List<AggregateCall> newCalls, List<int[]> originalToNew) {
    }

    /**
     * Builds the Project on top of {@code newAgg} that produces the original aggregate's row
     * type: group-by columns pass through unchanged; non-EXTENDED_STATS aggregate columns are
     * identity refs into {@code newAgg}'s output; EXTENDED_STATS aggregate columns are
     * reassembled struct expressions via {@link #buildExtendedStatsStruct}.
     */
    private static RelNode buildAssemblyProject(
        LogicalAggregate originalAgg,
        LogicalAggregate newAgg,
        List<int[]> originalToNew,
        int groupCount,
        RexBuilder rexBuilder,
        RelDataTypeFactory typeFactory
    ) {
        List<RelDataTypeField> originalFields = originalAgg.getRowType().getFieldList();
        List<RelDataTypeField> newAggFields = newAgg.getRowType().getFieldList();
        List<RexNode> projects = new ArrayList<>(originalFields.size());
        List<String> fieldNames = new ArrayList<>(originalFields.size());

        for (int i = 0; i < groupCount; i++) {
            projects.add(rexBuilder.makeInputRef(newAggFields.get(i).getType(), i));
            fieldNames.add(originalFields.get(i).getName());
        }

        for (int origIdx = 0; origIdx < originalAgg.getAggCallList().size(); origIdx++) {
            AggregateCall original = originalAgg.getAggCallList().get(origIdx);
            int[] mapping = originalToNew.get(origIdx);
            fieldNames.add(originalFields.get(groupCount + origIdx).getName());

            if (isExtendedStats(original.getAggregation())) {
                projects.add(buildExtendedStatsStruct(original, mapping, newAggFields, groupCount, rexBuilder, typeFactory));
            } else {
                int newColPos = groupCount + mapping[0];
                projects.add(rexBuilder.makeInputRef(newAggFields.get(newColPos).getType(), newColPos));
            }
        }

        return LogicalProject.create(newAgg, originalAgg.getHints(), projects, fieldNames);
    }

    /**
     * Builds the 13-field struct returned by EXTENDED_STATS, including the nested 6-field
     * std_deviation_bounds struct. Field order matches legacy OpenSearch
     * {@code InternalExtendedStats.toXContent}: count, min, max, avg, sum, sum_of_squares,
     * variance, variance_population, variance_sampling, std_deviation,
     * std_deviation_population, std_deviation_sampling, std_deviation_bounds.
     *
     * <p>Variance and std_deviation triplets duplicate the population variant because legacy
     * defines {@code variance} as an alias for {@code variance_population} (same value), and
     * likewise for std_deviation. The sampling variants use Bessel's correction ({@code n-1}
     * denominator) and return NULL when {@code count <= 1}.
     */
    private static RexNode buildExtendedStatsStruct(
        AggregateCall original,
        int[] mapping,
        List<RelDataTypeField> newAggFields,
        int groupCount,
        RexBuilder rexBuilder,
        RelDataTypeFactory typeFactory
    ) {
        RelDataType extStatsType = original.getType();
        List<RelDataTypeField> fields = extStatsType.getFieldList();
        if (fields.size() != 13) {
            throw new IllegalStateException(
                "EXTENDED_STATS return type must declare 13 fields "
                    + "(count, min, max, avg, sum, sum_of_squares, variance, variance_population, variance_sampling, "
                    + "std_deviation, std_deviation_population, std_deviation_sampling, std_deviation_bounds), got: "
                    + extStatsType
            );
        }

        int newCountPos = groupCount + mapping[0];
        int newMinPos = groupCount + mapping[1];
        int newMaxPos = groupCount + mapping[2];
        int newSumPos = groupCount + mapping[3];
        int newSumOfSqPos = groupCount + mapping[4];

        RexNode countRef = ensureType(rexBuilder, newAggFields.get(newCountPos).getType(), newCountPos, fields.get(0).getType());
        RexNode minRef = ensureType(rexBuilder, newAggFields.get(newMinPos).getType(), newMinPos, fields.get(1).getType());
        RexNode maxRef = ensureType(rexBuilder, newAggFields.get(newMaxPos).getType(), newMaxPos, fields.get(2).getType());
        RexNode sumRef = ensureType(rexBuilder, newAggFields.get(newSumPos).getType(), newSumPos, fields.get(4).getType());
        RexNode sumOfSqRef = ensureType(rexBuilder, newAggFields.get(newSumOfSqPos).getType(), newSumOfSqPos, fields.get(5).getType());

        RelDataType doubleNullable = typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.DOUBLE), true);
        RexNode rawSum = rexBuilder.makeInputRef(newAggFields.get(newSumPos).getType(), newSumPos);
        RexNode rawCount = rexBuilder.makeInputRef(newAggFields.get(newCountPos).getType(), newCountPos);
        RexNode rawSumOfSq = rexBuilder.makeInputRef(newAggFields.get(newSumOfSqPos).getType(), newSumOfSqPos);
        RexNode sumD = rexBuilder.makeCast(doubleNullable, rawSum);
        RexNode countD = rexBuilder.makeCast(doubleNullable, rawCount);
        RexNode sumOfSqD = rexBuilder.makeCast(doubleNullable, rawSumOfSq);

        RexNode avg = rexBuilder.makeCall(SqlStdOperatorTable.DIVIDE, sumD, countD);

        // variance_population = (sum_of_squares - sum*sum/count) / count.
        RexNode sumSq = rexBuilder.makeCall(SqlStdOperatorTable.MULTIPLY, sumD, sumD);
        RexNode sumSqOverCount = rexBuilder.makeCall(SqlStdOperatorTable.DIVIDE, sumSq, countD);
        // Centered sum of squares — shared numerator for both variance variants.
        // variance_population divides this by count; variance_sampling divides by (count - 1).
        RexNode centeredSumOfSquares = rexBuilder.makeCall(SqlStdOperatorTable.MINUS, sumOfSqD, sumSqOverCount);
        RexNode variancePop = rexBuilder.makeCall(SqlStdOperatorTable.DIVIDE, centeredSumOfSquares, countD);

        // variance_sampling = CASE WHEN count > 1 THEN centeredSumOfSquares / (count - 1) ELSE NULL.
        RexNode oneLong = rexBuilder.makeExactLiteral(BigDecimal.ONE, typeFactory.createSqlType(SqlTypeName.BIGINT));
        RexNode oneDouble = rexBuilder.makeApproxLiteral(BigDecimal.ONE, doubleNullable);
        RexNode countGt1 = rexBuilder.makeCall(SqlStdOperatorTable.GREATER_THAN, rawCount, oneLong);
        RexNode countMinus1 = rexBuilder.makeCall(SqlStdOperatorTable.MINUS, countD, oneDouble);
        RexNode varianceSampValue = rexBuilder.makeCall(SqlStdOperatorTable.DIVIDE, centeredSumOfSquares, countMinus1);
        RexNode nullDouble = rexBuilder.makeNullLiteral(doubleNullable);
        RexNode varianceSamp = rexBuilder.makeCall(SqlStdOperatorTable.CASE, countGt1, varianceSampValue, nullDouble);

        // POWER(v, 0.5) avoids a separate SQRT op (mirrors Calcite's stock AggregateReduceFunctionsRule).
        RexNode half = rexBuilder.makeApproxLiteral(BigDecimal.valueOf(0.5), doubleNullable);
        RexNode stdDev = rexBuilder.makeCall(SqlStdOperatorTable.POWER, variancePop, half);
        RexNode stdDevSamp = rexBuilder.makeCall(SqlStdOperatorTable.POWER, varianceSamp, half);

        // Bounds: avg ± SIGMA * std_deviation. SIGMA defaults to 2.0 (legacy InternalExtendedStats).
        RexNode sigma = rexBuilder.makeApproxLiteral(
            BigDecimal.valueOf(OpenSearchAggregateOperators.EXTENDED_STATS_DEFAULT_SIGMA),
            doubleNullable
        );
        RexNode offsetPop = rexBuilder.makeCall(SqlStdOperatorTable.MULTIPLY, sigma, stdDev);
        RexNode offsetSamp = rexBuilder.makeCall(SqlStdOperatorTable.MULTIPLY, sigma, stdDevSamp);
        RexNode boundUpper = rexBuilder.makeCall(SqlStdOperatorTable.PLUS, avg, offsetPop);
        RexNode boundLower = rexBuilder.makeCall(SqlStdOperatorTable.MINUS, avg, offsetPop);
        RexNode boundUpperSamp = rexBuilder.makeCall(SqlStdOperatorTable.PLUS, avg, offsetSamp);
        RexNode boundLowerSamp = rexBuilder.makeCall(SqlStdOperatorTable.MINUS, avg, offsetSamp);

        // Population bounds reuse boundUpper/boundLower since std_deviation_population
        // == std_deviation in InternalExtendedStats.
        RelDataType boundsType = fields.get(12).getType();
        RexNode boundsStruct = rexBuilder.makeCall(
            boundsType,
            SqlStdOperatorTable.ROW,
            List.of(boundUpper, boundLower, boundUpper, boundLower, boundUpperSamp, boundLowerSamp)
        );

        RexNode avgRef = rexBuilder.makeCast(fields.get(3).getType(), avg);
        RexNode varianceRef = rexBuilder.makeCast(fields.get(6).getType(), variancePop);
        RexNode variancePopRef = rexBuilder.makeCast(fields.get(7).getType(), variancePop);
        RexNode varianceSampRef = rexBuilder.makeCast(fields.get(8).getType(), varianceSamp);
        RexNode stdDevRef = rexBuilder.makeCast(fields.get(9).getType(), stdDev);
        RexNode stdDevPopRef = rexBuilder.makeCast(fields.get(10).getType(), stdDev);
        RexNode stdDevSampRef = rexBuilder.makeCast(fields.get(11).getType(), stdDevSamp);

        return rexBuilder.makeCall(
            extStatsType,
            SqlStdOperatorTable.ROW,
            List.of(
                countRef,
                minRef,
                maxRef,
                avgRef,
                sumRef,
                sumOfSqRef,
                varianceRef,
                variancePopRef,
                varianceSampRef,
                stdDevRef,
                stdDevPopRef,
                stdDevSampRef,
                boundsStruct
            )
        );
    }

    /**
     * Builds a primitive aggregate call (COUNT/MIN/MAX/SUM) over {@code aggInput} with the
     * given {@code argList}. Empty {@code rexList} because primitive aggregates have no
     * per-call expressions; the {@code SUM(x_squared)} variant is realised by pointing
     * {@code argList} at a derived column built by an upstream Project, not by stuffing the
     * multiplication into rexList. The original call's distinct/approximate/ignoreNulls/filter
     * are propagated; {@code null} for type and name lets Calcite infer them.
     */
    private static AggregateCall makePrimitive(
        SqlAggFunction op,
        AggregateCall original,
        RelNode input,
        boolean hasEmptyGroup,
        int groupCount,
        List<Integer> argList
    ) {
        return AggregateCall.create(
            op,
            original.isDistinct(),
            original.isApproximate(),
            original.ignoreNulls(),
            List.of(),
            argList,
            original.filterArg,
            null,
            RelCollations.EMPTY,
            groupCount,
            input,
            null,
            null
        );
    }

    /**
     * Returns a {@link RexNode} of {@code targetType}: an input ref at {@code pos}, optionally
     * wrapped in a CAST when source and target differ modulo nullability.
     */
    private static RexNode ensureType(RexBuilder rexBuilder, RelDataType sourceType, int pos, RelDataType targetType) {
        RexNode ref = rexBuilder.makeInputRef(sourceType, pos);
        if (SqlTypeUtil.equalSansNullability(rexBuilder.getTypeFactory(), sourceType, targetType)) {
            return ref;
        }
        return rexBuilder.makeCast(targetType, ref);
    }

    private static boolean isExtendedStats(SqlAggFunction op) {
        return op == OpenSearchAggregateOperators.EXTENDED_STATS;
    }
}

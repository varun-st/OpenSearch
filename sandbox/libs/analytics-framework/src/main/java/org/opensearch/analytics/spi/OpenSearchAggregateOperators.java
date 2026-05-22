/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.analytics.spi;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.Optionality;

/**
 * Custom Calcite {@link SqlAggFunction} singletons defined by the OpenSearch analytics
 * engine but referenced by frontends (the SQL/PPL parser library, planner-side rules) —
 * kept in the SPI module so any party can import the same singleton.
 *
 * <p>Currently defines:
 * <ul>
 *   <li>{@link #STATS} — bundle aggregate returning {@code STRUCT(count, min, max, avg, sum)}.
 *       Decomposed at HEP planning time by
 *       {@code OpenSearchStatsReduceRule} into primitive COUNT/MIN/MAX/SUM aggregate calls
 *       plus a Project that builds the struct, so no executor ever sees STATS.</li>
 *   <li>{@link #EXTENDED_STATS} — superset of {@link #STATS}, returning a 13-field struct
 *       that adds {@code sum_of_squares}, the {@code variance}/{@code std_deviation}
 *       triplets, and the nested 6-field {@code std_deviation_bounds} struct. Decomposed
 *       at HEP planning time by {@code OpenSearchExtendedStatsReduceRule} into the same
 *       four primitives as STATS plus {@code SUM(x*x)} (computed via a Project beneath the
 *       rebuilt aggregate), with the variance/stddev/bounds derived in the assembly Project.</li>
 * </ul>
 *
 * @opensearch.internal
 */
public final class OpenSearchAggregateOperators {

    private OpenSearchAggregateOperators() {}

    /**
     * Field names of the struct returned by {@link #STATS}. Public so the planner-side rule
     * (which builds the Project that re-assembles the struct) and any test fixture can use
     * the same names without re-declaring constants.
     */
    public static final String STATS_FIELD_COUNT = "count";
    public static final String STATS_FIELD_MIN = "min";
    public static final String STATS_FIELD_MAX = "max";
    public static final String STATS_FIELD_SUM = "sum";
    public static final String STATS_FIELD_AVG = "avg";

    /**
     * Field names of the struct returned by {@link #EXTENDED_STATS}. Order matches legacy
     * OpenSearch {@code InternalExtendedStats.toXContent}: count/min/max/avg/sum from
     * {@code InternalStats}, then sum_of_squares, variance triplet, std_deviation triplet,
     * and the nested {@code std_deviation_bounds} struct (with the six bound sub-fields).
     */
    public static final String EXTENDED_STATS_FIELD_SUM_OF_SQUARES = "sum_of_squares";
    public static final String EXTENDED_STATS_FIELD_VARIANCE = "variance";
    public static final String EXTENDED_STATS_FIELD_VARIANCE_POPULATION = "variance_population";
    public static final String EXTENDED_STATS_FIELD_VARIANCE_SAMPLING = "variance_sampling";
    public static final String EXTENDED_STATS_FIELD_STD_DEVIATION = "std_deviation";
    public static final String EXTENDED_STATS_FIELD_STD_DEVIATION_POPULATION = "std_deviation_population";
    public static final String EXTENDED_STATS_FIELD_STD_DEVIATION_SAMPLING = "std_deviation_sampling";
    public static final String EXTENDED_STATS_FIELD_STD_DEVIATION_BOUNDS = "std_deviation_bounds";
    public static final String EXTENDED_STATS_BOUND_UPPER = "upper";
    public static final String EXTENDED_STATS_BOUND_LOWER = "lower";
    public static final String EXTENDED_STATS_BOUND_UPPER_POPULATION = "upper_population";
    public static final String EXTENDED_STATS_BOUND_LOWER_POPULATION = "lower_population";
    public static final String EXTENDED_STATS_BOUND_UPPER_SAMPLING = "upper_sampling";
    public static final String EXTENDED_STATS_BOUND_LOWER_SAMPLING = "lower_sampling";

    /**
     * Sigma multiplier used when computing {@code std_deviation_bounds}. Hardcoded to match
     * the legacy {@code InternalExtendedStats} default; the bounds are
     * {@code avg ± SIGMA * std_deviation}.
     */
    public static final double EXTENDED_STATS_DEFAULT_SIGMA = 2.0;

    /**
     * Return-type inference for {@link #STATS}. Produces a struct whose component types match
     * what Calcite's built-in COUNT/MIN/MAX/SUM aggregates would produce for the same operand,
     * with AVG fixed to DOUBLE. Matching matters because the Project the decomposition rule
     * emits must produce the same row type the original STATS aggregate declared.
     */
    private static final SqlReturnTypeInference STATS_RETURN_TYPE_INFERENCE = OpenSearchAggregateOperators::inferStatsReturnType;

    private static final SqlReturnTypeInference EXTENDED_STATS_RETURN_TYPE_INFERENCE =
        OpenSearchAggregateOperators::inferExtendedStatsReturnType;

    /**
     * STATS bundle aggregate. Decomposed at HEP planning time — never reaches the executor.
     */
    public static final SqlAggFunction STATS = new SqlAggFunction(
        "STATS",
        null,
        SqlKind.OTHER,
        STATS_RETURN_TYPE_INFERENCE,
        null,
        OperandTypes.NUMERIC,
        SqlFunctionCategory.USER_DEFINED_FUNCTION,
        false,
        false,
        Optionality.FORBIDDEN
    ) {
    };

    /**
     * EXTENDED_STATS bundle aggregate. Superset of {@link #STATS} — adds sum_of_squares,
     * variance/std_deviation triplets, and a nested std_deviation_bounds struct. Decomposed
     * at HEP planning time — never reaches the executor.
     */
    public static final SqlAggFunction EXTENDED_STATS = new SqlAggFunction(
        "EXTENDED_STATS",
        null,
        SqlKind.OTHER,
        EXTENDED_STATS_RETURN_TYPE_INFERENCE,
        null,
        OperandTypes.NUMERIC,
        SqlFunctionCategory.USER_DEFINED_FUNCTION,
        false,
        false,
        Optionality.FORBIDDEN
    ) {
    };

    private static RelDataType inferStatsReturnType(SqlOperatorBinding opBinding) {
        RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
        RelDataType inputType = opBinding.getOperandType(0);

        RelDataType countType = typeFactory.createSqlType(SqlTypeName.BIGINT);

        RelDataType nullableInput = typeFactory.createTypeWithNullability(inputType, true);

        // Delegate to Calcite's SUM type inference. AGG_SUM widens INTEGER → BIGINT,
        // BIGINT → DECIMAL, DOUBLE → DOUBLE, etc. Falls back to the input type if the
        // inference returns null (shouldn't happen for numeric-validated operands).
        RelDataType sumType = ReturnTypes.AGG_SUM.inferReturnType(opBinding);
        if (sumType == null) {
            sumType = nullableInput;
        } else {
            sumType = typeFactory.createTypeWithNullability(sumType, true);
        }

        RelDataType avgType = typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.DOUBLE), true);

        return typeFactory.builder()
            .add(STATS_FIELD_COUNT, countType)
            .add(STATS_FIELD_MIN, nullableInput)
            .add(STATS_FIELD_MAX, nullableInput)
            .add(STATS_FIELD_AVG, avgType)
            .add(STATS_FIELD_SUM, sumType)
            .build();
    }

    private static RelDataType inferExtendedStatsReturnType(SqlOperatorBinding opBinding) {
        RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
        RelDataType inputType = opBinding.getOperandType(0);

        RelDataType countType = typeFactory.createSqlType(SqlTypeName.BIGINT);
        RelDataType nullableInput = typeFactory.createTypeWithNullability(inputType, true);

        RelDataType sumType = ReturnTypes.AGG_SUM.inferReturnType(opBinding);
        if (sumType == null) {
            sumType = nullableInput;
        } else {
            sumType = typeFactory.createTypeWithNullability(sumType, true);
        }

        RelDataType doubleNullable = typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.DOUBLE), true);

        // Nested std_deviation_bounds struct.
        RelDataType boundsType = typeFactory.createTypeWithNullability(
            typeFactory.builder()
                .add(EXTENDED_STATS_BOUND_UPPER, doubleNullable)
                .add(EXTENDED_STATS_BOUND_LOWER, doubleNullable)
                .add(EXTENDED_STATS_BOUND_UPPER_POPULATION, doubleNullable)
                .add(EXTENDED_STATS_BOUND_LOWER_POPULATION, doubleNullable)
                .add(EXTENDED_STATS_BOUND_UPPER_SAMPLING, doubleNullable)
                .add(EXTENDED_STATS_BOUND_LOWER_SAMPLING, doubleNullable)
                .build(),
            true
        );

        return typeFactory.builder()
            .add(STATS_FIELD_COUNT, countType)
            .add(STATS_FIELD_MIN, nullableInput)
            .add(STATS_FIELD_MAX, nullableInput)
            .add(STATS_FIELD_AVG, doubleNullable)
            .add(STATS_FIELD_SUM, sumType)
            .add(EXTENDED_STATS_FIELD_SUM_OF_SQUARES, sumType)
            .add(EXTENDED_STATS_FIELD_VARIANCE, doubleNullable)
            .add(EXTENDED_STATS_FIELD_VARIANCE_POPULATION, doubleNullable)
            .add(EXTENDED_STATS_FIELD_VARIANCE_SAMPLING, doubleNullable)
            .add(EXTENDED_STATS_FIELD_STD_DEVIATION, doubleNullable)
            .add(EXTENDED_STATS_FIELD_STD_DEVIATION_POPULATION, doubleNullable)
            .add(EXTENDED_STATS_FIELD_STD_DEVIATION_SAMPLING, doubleNullable)
            .add(EXTENDED_STATS_FIELD_STD_DEVIATION_BOUNDS, boundsType)
            .build();
    }
}

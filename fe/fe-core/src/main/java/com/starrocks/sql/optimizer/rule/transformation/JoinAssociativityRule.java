// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

package com.starrocks.sql.optimizer.rule.transformation;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.analysis.JoinOperator;
import com.starrocks.sql.optimizer.ExpressionContext;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.operator.OperatorBuilderFactory;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.Projection;
import com.starrocks.sql.optimizer.operator.logical.LogicalJoinOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/*
 *        Join          Join
 *       /    \        /    \
 *    Join     C  =>  A     Join
 *   /    \                /    \
 *  A     B               B      C
 * */
public class JoinAssociativityRule extends TransformationRule {
    private JoinAssociativityRule() {
        super(RuleType.TF_JOIN_ASSOCIATIVITY, Pattern.create(OperatorType.LOGICAL_JOIN)
                .addChildren(
                        Pattern.create(OperatorType.LOGICAL_JOIN).addChildren(
                                Pattern.create(OperatorType.PATTERN_LEAF)
                                        .addChildren(Pattern.create(OperatorType.PATTERN_MULTI_LEAF)),
                                Pattern.create(OperatorType.PATTERN_LEAF)),
                        Pattern.create(OperatorType.PATTERN_LEAF)));
    }

    private static final JoinAssociativityRule instance = new JoinAssociativityRule();

    public static JoinAssociativityRule getInstance() {
        return instance;
    }

    public boolean check(final OptExpression input, OptimizerContext context) {
        LogicalJoinOperator joinOperator = (LogicalJoinOperator) input.getOp();
        if (!joinOperator.getJoinHint().isEmpty() || input.inputAt(0).getOp().hasLimit() ||
                !((LogicalJoinOperator) input.inputAt(0).getOp()).getJoinHint().isEmpty()) {
            return false;
        }

        if (!joinOperator.getJoinType().isInnerJoin()) {
            return false;
        }

        LogicalJoinOperator leftChildJoin = (LogicalJoinOperator) input.inputAt(0).getOp();
        if (leftChildJoin.getProjection() != null) {
            Projection projection = leftChildJoin.getProjection();
            // 1. Forbidden expression column on join-reorder
            // 2. Forbidden on-predicate use columns from two children at same time
            for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : projection.getColumnRefMap().entrySet()) {
                if (!entry.getValue().isColumnRef() &&
                        entry.getValue().getUsedColumns().isIntersect(input.inputAt(0).inputAt(0).getOutputColumns()) &&
                        entry.getValue().getUsedColumns().isIntersect(input.inputAt(0).inputAt(1).getOutputColumns())) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        OptExpression leftChild = input.inputAt(0);
        OptExpression rightChild = input.inputAt(1);

        LogicalJoinOperator parentJoin = (LogicalJoinOperator) input.getOp();
        LogicalJoinOperator leftChildJoin = (LogicalJoinOperator) leftChild.getOp();
        // We do this check here not in check method, because check here is very simple
        if (!leftChildJoin.getJoinType().isInnerJoin() && !leftChildJoin.getJoinType().isCrossJoin()) {
            return Collections.emptyList();
        }

        List<ScalarOperator> parentConjuncts = Utils.extractConjuncts(parentJoin.getOnPredicate());
        List<ScalarOperator> childConjuncts = Utils.extractConjuncts(leftChildJoin.getOnPredicate());

        List<ScalarOperator> allConjuncts = Lists.newArrayList();
        allConjuncts.addAll(parentConjuncts);
        allConjuncts.addAll(childConjuncts);

        OptExpression leftChild1 = leftChild.inputAt(0);
        OptExpression leftChild2 = leftChild.inputAt(1);

        ColumnRefSet newRightChildColumns = new ColumnRefSet();
        newRightChildColumns.union(rightChild.getOutputColumns());
        newRightChildColumns.union(leftChild2.getOutputColumns());

        List<ScalarOperator> newChildConjuncts = Lists.newArrayList();
        List<ScalarOperator> newParentConjuncts = Lists.newArrayList();
        for (ScalarOperator conjunct : allConjuncts) {
            if (newRightChildColumns.contains(conjunct.getUsedColumns())) {
                newChildConjuncts.add(conjunct);
            } else {
                newParentConjuncts.add(conjunct);
            }
        }

        // Eliminate cross join
        if (newChildConjuncts.isEmpty() || newParentConjuncts.isEmpty()) {
            return Collections.emptyList();
        }

        LogicalJoinOperator.Builder topJoinBuilder = new LogicalJoinOperator.Builder();
        LogicalJoinOperator topJoinOperator = topJoinBuilder.withOperator(parentJoin)
                .setJoinType(JoinOperator.INNER_JOIN)
                .setOnPredicate(Utils.compoundAnd(newParentConjuncts))
                .build();

        ColumnRefSet parentJoinRequiredColumns = parentJoin.getOutputColumns(new ExpressionContext(input));
        parentJoinRequiredColumns.union(topJoinOperator.getRequiredChildInputColumns());
        List<ColumnRefOperator> newRightOutputColumns = newRightChildColumns.getStream()
                .filter(parentJoinRequiredColumns::contains)
                .mapToObj(id -> context.getColumnRefFactory().getColumnRef(id)).collect(Collectors.toList());

        Projection leftChildJoinProjection = leftChildJoin.getProjection();
        HashMap<ColumnRefOperator, ScalarOperator> rightExpression = new HashMap<>();
        HashMap<ColumnRefOperator, ScalarOperator> leftExpression = new HashMap<>();
        if (leftChildJoinProjection != null) {
            for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : leftChildJoinProjection.getColumnRefMap()
                    .entrySet()) {
                if (!entry.getValue().isColumnRef() &&
                        newRightChildColumns.contains(entry.getValue().getUsedColumns())) {
                    rightExpression.put(entry.getKey(), entry.getValue());
                } else if (!entry.getValue().isColumnRef() &&
                        leftChild1.getOutputColumns().contains(entry.getValue().getUsedColumns())) {
                    leftExpression.put(entry.getKey(), entry.getValue());
                }
            }
        }

        //build new right child join
        OptExpression newRightChildJoin;
        if (rightExpression.isEmpty()) {
            LogicalJoinOperator.Builder rightChildJoinOperatorBuilder = new LogicalJoinOperator.Builder();
            LogicalJoinOperator rightChildJoinOperator = rightChildJoinOperatorBuilder
                    .setJoinType(JoinOperator.INNER_JOIN)
                    .setOnPredicate(Utils.compoundAnd(newChildConjuncts))
                    .setProjection(new Projection(newRightOutputColumns.stream()
                            .collect(Collectors.toMap(Function.identity(), Function.identity())), new HashMap<>()))
                    .build();
            newRightChildJoin = OptExpression.create(rightChildJoinOperator, leftChild2, rightChild);
        } else {
            rightExpression.putAll(newRightOutputColumns.stream()
                    .collect(Collectors.toMap(Function.identity(), Function.identity())));
            LogicalJoinOperator.Builder rightChildJoinOperatorBuilder = new LogicalJoinOperator.Builder();
            LogicalJoinOperator rightChildJoinOperator = rightChildJoinOperatorBuilder
                    .setJoinType(JoinOperator.INNER_JOIN)
                    .setOnPredicate(Utils.compoundAnd(newChildConjuncts))
                    .setProjection(new Projection(rightExpression))
                    .build();
            newRightChildJoin = OptExpression.create(rightChildJoinOperator, leftChild2, rightChild);
        }

        //build left
        if (!leftExpression.isEmpty()) {
            OptExpression left;
            Map<ColumnRefOperator, ScalarOperator> expressionProject;
            if (leftChild1.getOp().getProjection() == null) {
                expressionProject = leftChild1.getOutputColumns().getStream()
                        .mapToObj(id -> context.getColumnRefFactory().getColumnRef(id))
                        .collect(Collectors.toMap(Function.identity(), Function.identity()));
            } else {
                expressionProject = Maps.newHashMap(leftChild1.getOp().getProjection().getColumnRefMap());
            }
            expressionProject.putAll(leftExpression);
            Operator.Builder builder = OperatorBuilderFactory.build(leftChild1.getOp());
            Operator newOp = builder.withOperator(leftChild1.getOp())
                    .setProjection(new Projection(expressionProject)).build();
            left = OptExpression.create(newOp, leftChild1.getInputs());

            OptExpression topJoin = OptExpression.create(topJoinOperator, left, newRightChildJoin);
            return Lists.newArrayList(topJoin);
        } else {
            OptExpression topJoin = OptExpression.create(topJoinOperator, leftChild1, newRightChildJoin);
            return Lists.newArrayList(topJoin);
        }
    }
}

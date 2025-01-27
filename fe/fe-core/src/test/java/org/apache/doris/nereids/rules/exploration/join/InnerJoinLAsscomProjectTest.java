// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.exploration.join;

import org.apache.doris.common.Pair;
import org.apache.doris.nereids.trees.plans.JoinType;
import org.apache.doris.nereids.trees.plans.logical.LogicalOlapScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalPlan;
import org.apache.doris.nereids.util.LogicalPlanBuilder;
import org.apache.doris.nereids.util.MemoTestUtils;
import org.apache.doris.nereids.util.PatternMatchSupported;
import org.apache.doris.nereids.util.PlanChecker;
import org.apache.doris.nereids.util.PlanConstructor;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

class InnerJoinLAsscomProjectTest implements PatternMatchSupported {

    private final LogicalOlapScan scan1 = PlanConstructor.newLogicalOlapScan(0, "t1", 0);
    private final LogicalOlapScan scan2 = PlanConstructor.newLogicalOlapScan(1, "t2", 0);
    private final LogicalOlapScan scan3 = PlanConstructor.newLogicalOlapScan(2, "t3", 0);

    @Test
    void testJoinLAsscomProject() {
        /*
         * Star-Join
         * t1 -- t2
         * |
         * t3
         * <p>
         *     t1.id=t3.id               t1.id=t2.id
         *       topJoin                  newTopJoin
         *       /     \                   /     \
         *    project   t3           project    project
         * t1.id=t2.id             t1.id=t3.id    t2
         * bottomJoin       -->   newBottomJoin
         *   /    \                   /    \
         * t1      t2               t1      t3
         */
        LogicalPlan plan = new LogicalPlanBuilder(scan1)
                .hashJoinUsing(scan2, JoinType.INNER_JOIN, Pair.of(0, 0))
                .project(ImmutableList.of(0, 1, 2))
                .hashJoinUsing(scan3, JoinType.INNER_JOIN, Pair.of(1, 1))
                .build();

        PlanChecker.from(MemoTestUtils.createConnectContext(), plan)
                .applyExploration(InnerJoinLAsscomProject.INSTANCE.build())
                .printlnExploration()
                .matchesExploration(
                        logicalJoin(
                                logicalJoin(
                                        logicalOlapScan().when(scan -> scan.getTable().getName().equals("t1")),
                                        logicalOlapScan().when(scan -> scan.getTable().getName().equals("t3"))
                                ),
                                logicalProject(
                                        logicalOlapScan().when(scan -> scan.getTable().getName().equals("t2"))
                                ).when(project -> project.getProjects().size() == 1)
                        )
                );
    }

    @Test
    void testAlias() {
        LogicalPlan plan = new LogicalPlanBuilder(scan1)
                .hashJoinUsing(scan2, JoinType.INNER_JOIN, Pair.of(0, 0))
                .alias(ImmutableList.of(0, 2), ImmutableList.of("t1.id", "t2.id"))
                .hashJoinUsing(scan3, JoinType.INNER_JOIN, Pair.of(0, 0))
                .build();

        PlanChecker.from(MemoTestUtils.createConnectContext(), plan)
                .applyExploration(InnerJoinLAsscomProject.INSTANCE.build())
                .matchesExploration(
                        logicalJoin(
                                logicalProject(
                                        logicalJoin(
                                                logicalOlapScan().when(scan -> scan.getTable().getName().equals("t1")),
                                                logicalOlapScan().when(scan -> scan.getTable().getName().equals("t3"))
                                        )
                                ).when(project -> project.getProjects().size() == 3), // t1.id Add t3.id, t3.name
                                logicalProject(
                                        logicalOlapScan().when(scan -> scan.getTable().getName().equals("t2"))
                                ).when(project -> project.getProjects().size() == 1)
                        )
                );
    }

    @Test
    void testAliasTopMultiHashJoin() {
        LogicalPlan plan = new LogicalPlanBuilder(scan1)
                .hashJoinUsing(scan2, JoinType.INNER_JOIN, Pair.of(0, 0)) // t1.id=t2.id
                .alias(ImmutableList.of(0, 2), ImmutableList.of("t1.id", "t2.id"))
                // t1.id=t3.id t2.id = t3.id
                .hashJoinUsing(scan3, JoinType.INNER_JOIN, ImmutableList.of(Pair.of(0, 0), Pair.of(1, 0)))
                .build();

        PlanChecker.from(MemoTestUtils.createConnectContext(), plan)
                .applyExploration(InnerJoinLAsscomProject.INSTANCE.build())
                .printlnOrigin()
                .printlnExploration()
                .matchesExploration(
                        logicalJoin(
                                logicalProject(
                                        logicalJoin(
                                                logicalOlapScan().when(scan -> scan.getTable().getName().equals("t1")),
                                                logicalOlapScan().when(scan -> scan.getTable().getName().equals("t3"))
                                        ).when(join -> join.getHashJoinConjuncts().size() == 1)
                                ).when(project -> project.getProjects().size() == 3), // t1.id Add t3.id, t3.name
                                logicalProject(
                                        logicalOlapScan().when(scan -> scan.getTable().getName().equals("t2"))
                                ).when(project -> project.getProjects().size() == 1)
                        ).when(join -> join.getHashJoinConjuncts().size() == 2)
                );
    }
}

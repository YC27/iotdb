/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.queryengine.plan.relational.planner.iterative.rule;

import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.Symbol;
import org.apache.iotdb.db.queryengine.plan.relational.planner.iterative.Rule;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.ProjectNode;
import org.apache.iotdb.db.queryengine.plan.relational.utils.matching.Capture;
import org.apache.iotdb.db.queryengine.plan.relational.utils.matching.Captures;
import org.apache.iotdb.db.queryengine.plan.relational.utils.matching.Pattern;

import com.google.common.collect.ImmutableList;

import java.util.Optional;
import java.util.Set;

import static org.apache.iotdb.db.queryengine.plan.relational.planner.iterative.rule.Util.pruneInputs;
import static org.apache.iotdb.db.queryengine.plan.relational.planner.node.Patterns.project;
import static org.apache.iotdb.db.queryengine.plan.relational.planner.node.Patterns.source;
import static org.apache.iotdb.db.queryengine.plan.relational.utils.matching.Capture.newCapture;

/**
 * @param <N> The node type to look for under the ProjectNode Looks for a Project parent over a N
 *     child, such that the parent doesn't use all the output columns of the child. Given that
 *     situation, invokes the pushDownProjectOff helper to possibly rewrite the child to produce
 *     fewer outputs.
 */
public abstract class ProjectOffPushDownRule<N extends PlanNode> implements Rule<ProjectNode> {
  private final Capture<N> targetCapture = newCapture();

  private final Pattern<N> targetPattern;

  protected ProjectOffPushDownRule(Pattern<N> targetPattern) {
    this.targetPattern = targetPattern;
  }

  @Override
  public Pattern<ProjectNode> getPattern() {
    return project().with(source().matching(targetPattern.capturedAs(targetCapture)));
  }

  @Override
  public Result apply(ProjectNode parent, Captures captures, Context context) {
    N targetNode = captures.get(targetCapture);

    return pruneInputs(targetNode.getOutputSymbols(), parent.getAssignments().getExpressions())
        .flatMap(prunedOutputs -> this.pushDownProjectOff(context, targetNode, prunedOutputs))
        .map(newChild -> parent.replaceChildren(ImmutableList.of(newChild)))
        .map(Result::ofPlanNode)
        .orElse(Result.empty());
  }

  protected abstract Optional<PlanNode> pushDownProjectOff(
      Context context, N targetNode, Set<Symbol> referencedOutputs);
}

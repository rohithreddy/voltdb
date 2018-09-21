/* This file is part of VoltDB.
 * Copyright (C) 2008-2018 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.calciteadapter.rel.physical;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelDistributionTraitDef;
import org.apache.calcite.rel.RelDistributions;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Exchange;
import org.voltdb.calciteadapter.converter.RexConverter;
import org.voltdb.plannodes.AbstractPlanNode;
import org.voltdb.plannodes.NodeSchema;
import org.voltdb.plannodes.SendPlanNode;

public abstract class AbstractVoltDBPExchange extends Exchange implements VoltDBPRel {

    public static final int DISTRIBUTED_SPLIT_COUNT = 30;

    // Exchange's split count equals the count of physical nodes its input runs on
    protected final int m_splitCount;

    // An indicator to be set to TRUE only for a top(coordinator) exchange for a multi-partitioned queries
    // Other relations could take advantage of this flag during Exchange Transpose rules if a relation
    // behavior depends whether it's part of the coordinator or fragment stack
    protected final boolean m_topExchange;

    protected AbstractVoltDBPExchange(RelOptCluster cluster,
            RelTraitSet traitSet,
            RelNode input,
            int splitCount,
            boolean topExchange) {
        super(cluster, traitSet, input, traitSet.getTrait(RelDistributionTraitDef.INSTANCE));
        assert(!RelDistributions.ANY.getType().equals(traitSet.getTrait(RelDistributionTraitDef.INSTANCE).getType()));
        m_splitCount = splitCount;
        m_topExchange = topExchange;
    }

    protected AbstractPlanNode toPlanNode(AbstractPlanNode epn) {
        SendPlanNode spn = new SendPlanNode();
        epn.addAndLinkChild(spn);

        AbstractPlanNode child = inputRelNodeToPlanNode(this, 0);
        spn.addAndLinkChild(child);

        // Generate output schema
        NodeSchema schema = RexConverter.convertToVoltDBNodeSchema(getInput().getRowType());
        epn.setOutputSchema(schema);
        epn.setHaveSignificantOutputSchema(true);
        return epn;
    }

    @Override
    protected String computeDigest() {
        String digest = super.computeDigest();
        return digest;
    }

    @Override
    public int getSplitCount() {
        return m_splitCount;
    }

    @Override
    public AbstractVoltDBPExchange copy(
            RelTraitSet traitSet,
            RelNode newInput,
            RelDistribution newDistribution) {
        return copyInternal(
                traitSet,
                newInput,
                isTopExchange());
    }

    public AbstractVoltDBPExchange copy(
            RelTraitSet traitSet,
            RelNode newInput,
            RelDistribution newDistribution,
            boolean isTopExchange) {
        return copyInternal(
                traitSet,
                newInput,
                isTopExchange);
    }

    protected abstract AbstractVoltDBPExchange copyInternal(
            RelTraitSet traitSet,
            RelNode newInput,
            boolean isTopExchang);

    public boolean isTopExchange() {
        return m_topExchange;
    }
}
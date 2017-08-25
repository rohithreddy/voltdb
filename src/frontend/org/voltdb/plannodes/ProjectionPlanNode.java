/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
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

package org.voltdb.plannodes;

import java.util.Collection;
import java.util.List;

import org.json_voltpatches.JSONException;
import org.json_voltpatches.JSONObject;
import org.json_voltpatches.JSONStringer;
import org.voltdb.catalog.Database;
import org.voltdb.expressions.AbstractExpression;
import org.voltdb.expressions.AbstractSubqueryExpression;
import org.voltdb.expressions.ExpressionUtil;
import org.voltdb.expressions.TupleValueExpression;
import org.voltdb.planner.PlanningErrorException;
import org.voltdb.types.PlanNodeType;

public class ProjectionPlanNode extends AbstractPlanNode {
    public ProjectionPlanNode() {
        super();
    }

    public ProjectionPlanNode(NodeSchema schemaToClone) {
        m_outputSchema = schemaToClone.clone();
        m_hasSignificantOutputSchema = true;
    }

    @Override
    public PlanNodeType getPlanNodeType() {
        return PlanNodeType.PROJECTION;
    }

    @Override
    public void validate() throws Exception {
        super.validate();

        // Validate Expression Trees
        for (int ctr = 0; ctr < getOutputSchema().getColumns().size(); ctr++) {
            SchemaColumn column = getOutputSchema().getColumns().get(ctr);
            AbstractExpression exp = column.getExpression();
            if (exp == null) {
                throw new Exception("ERROR: The Output Column Expression at position '" + ctr + "' is NULL");
            }
            exp.validate();
        }
    }

    /**
     * Set the output schema for this projection.  This schema will be
     * treated as immutable during the planning (aside from resolving
     * column indexes for TVEs within any expressions in these columns)
     * @param schema
     */
    public void setOutputSchemaWithoutClone(NodeSchema schema) {
        m_outputSchema = schema;
        m_hasSignificantOutputSchema = true;
    }

    @Override
    public void generateOutputSchema(Database db) {
        assert(m_children.size() == 1);
        AbstractPlanNode childNode = m_children.get(0);
        childNode.generateOutputSchema(db);
        NodeSchema input_schema = childNode.getOutputSchema();
        // SCARY MAGIC
        // projection's output schema is mostly pre-determined, however,
        // since aggregates are generated by an earlier node, we need
        // to replace any aggregate expressions in the display columns
        // with a tuple value expression that matches what we're going
        // to generate out of the aggregate node
        NodeSchema new_schema = new NodeSchema();
        int colIndex = 0;
        for (SchemaColumn col : m_outputSchema.getColumns()) {
            if (col.getExpression().getExpressionType().isAggregateExpression()) {
                Object agg_col = input_schema.find(col.getTableName(),
                       col.getTableAlias(),
                       col.getColumnName(),
                       col.getColumnAlias());
                if (agg_col == null) {
                    throw new RuntimeException("Unable to find matching " +
                                               "input column for projection: " +
                                               col.toString());
                }
                new_schema.addColumn(col.copyAndReplaceWithTVE(colIndex));
            }
            else {
                new_schema.addColumn(col.clone());
            }
            ++colIndex;
        }
        setOutputSchema(new_schema);
        m_hasSignificantOutputSchema = true;

        // Generate the output schema for subqueries
        Collection<AbstractExpression> subqueryExpressions = findAllSubquerySubexpressions();
        for (AbstractExpression subqueryExpression : subqueryExpressions) {
            assert(subqueryExpression instanceof AbstractSubqueryExpression);
            ((AbstractSubqueryExpression) subqueryExpression).generateOutputSchema(db);
        }
    }

    @Override
    public void resolveColumnIndexes() {
        assert(m_children.size() == 1);
        AbstractPlanNode childNode = m_children.get(0);
        childNode.resolveColumnIndexes();
        NodeSchema input_schema = childNode.getOutputSchema();
        resolveColumnIndexesUsingSchema(input_schema);

        // Resolve subquery expression indexes
        resolveSubqueryColumnIndexes();
    }

    /**
     * Given an input schema, resolve all the TVEs in all the output column
     * expressions.  This method is necessary to be able to do this for
     * inlined projection nodes that don't have a child from which they can get
     * an output schema.
     */
    void resolveColumnIndexesUsingSchema(NodeSchema inputSchema) {
        // get all the TVEs in the output columns
        int difftor = 0;
        for (SchemaColumn col : m_outputSchema.getColumns()) {
            col.setDifferentiator(difftor);
            ++difftor;
            Collection<TupleValueExpression> allTves =
                    ExpressionUtil.getTupleValueExpressions(col.getExpression());
            // and update their indexes against the table schema
            for (TupleValueExpression tve : allTves) {
                tve.setColumnIndexUsingSchema(inputSchema);
            }
        }
        // DON'T RE-SORT HERE
    }

    @Override
    public void toJSONString(JSONStringer stringer) throws JSONException {
        super.toJSONString(stringer);
    }

    @Override
    public void loadFromJSONObject( JSONObject jobj, Database db ) throws JSONException {
        helpLoadFromJSONObject(jobj, db);
    }

    @Override
    protected String explainPlanForNode(String indent) {
        return "PROJECTION";
    }

    @Override
    /**
     * ProjectionPlanNodes don't need projection nodes.
     */
    public boolean planNodeClassNeedsProjectionNode() {
        return false;
    }

    /**
     * Return true if this node unneeded if its
     * input schema is the given one.
     *
     * @param child The Input Schema.
     * @return true iff the node is unnecessary.
     */
    public boolean isIdentity(AbstractPlanNode childNode) throws PlanningErrorException {
        assert(childNode != null);
        // Find the output schema.
        // If the child node has an inline projection node,
        // then the output schema is the inline projection
        // node's output schema.  Otherwise it's the output
        // schema of the childNode itself.
        NodeSchema childSchema = childNode.getTrueOutputSchema();
        assert(childSchema != null);
        NodeSchema outputSchema = getOutputSchema();
        List<SchemaColumn> cols = outputSchema.getColumns();
        List<SchemaColumn> childCols = childSchema.getColumns();
        assert(childCols != null);
        if (cols.size() != childCols.size()) {
            return false;
        }
        for (int idx = 0; idx < cols.size(); idx += 1) {
            SchemaColumn col = cols.get(idx);
            SchemaColumn childCol = childCols.get(idx);
            if (col.getType() != childCol.getType()) {
                return false;
            }
            if ( ! (col.getExpression() instanceof TupleValueExpression)) {
                return false;
            }
            if ( ! (childCol.getExpression() instanceof TupleValueExpression)) {
                return false;
            }
            TupleValueExpression tve = (TupleValueExpression)col.getExpression();
            if (tve.getColumnIndex() != idx) {
                return false;
            }
        }
        return true;
    }

    /**
     * Replace the column names output schema of the child node with the
     * output schema column names of this node.  We use this when we
     * delete an unnecessary projection node.  We only need
     * to make sure the column names are changed, since we
     * will have checked carefully that everything else is the
     * same.
     *
     * @param child
     */
    public void replaceChildOutputSchemaNames(AbstractPlanNode child) {
        NodeSchema childSchema = child.getTrueOutputSchema();
        NodeSchema mySchema = getOutputSchema();
        assert(childSchema.getColumns().size() == mySchema.getColumns().size());
        for (int idx = 0; idx < childSchema.size(); idx += 1) {
            SchemaColumn cCol = childSchema.getColumns().get(idx);
            SchemaColumn myCol = mySchema.getColumns().get(idx);
            assert(cCol.getType() == myCol.getType());
            assert(cCol.getExpression() instanceof TupleValueExpression);
            assert(myCol.getExpression() instanceof TupleValueExpression);
            cCol.reset(myCol.getTableName(),
                       myCol.getTableAlias(),
                       myCol.getColumnName(),
                       myCol.getColumnAlias());
        }
    }
}

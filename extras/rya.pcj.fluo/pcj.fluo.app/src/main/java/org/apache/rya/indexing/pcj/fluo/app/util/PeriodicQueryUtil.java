/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.rya.indexing.pcj.fluo.app.util;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.fluo.api.client.SnapshotBase;
import org.apache.fluo.api.data.Bytes;
import org.apache.rya.indexing.pcj.fluo.app.IncrementalUpdateConstants;
import org.apache.rya.indexing.pcj.fluo.app.NodeType;
import org.apache.rya.indexing.pcj.fluo.app.query.AggregationMetadata;
import org.apache.rya.indexing.pcj.fluo.app.query.FluoQuery;
import org.apache.rya.indexing.pcj.fluo.app.query.FluoQueryColumns;
import org.apache.rya.indexing.pcj.fluo.app.query.PeriodicQueryMetadata;
import org.apache.rya.indexing.pcj.fluo.app.query.PeriodicQueryNode;
import org.apache.rya.indexing.pcj.fluo.app.query.QueryMetadata;
import org.apache.rya.indexing.pcj.storage.accumulo.VariableOrder;
import org.openrdf.model.Literal;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.model.vocabulary.XMLSchema;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.algebra.Filter;
import org.openrdf.query.algebra.FunctionCall;
import org.openrdf.query.algebra.Group;
import org.openrdf.query.algebra.Projection;
import org.openrdf.query.algebra.QueryModelNode;
import org.openrdf.query.algebra.Reduced;
import org.openrdf.query.algebra.TupleExpr;
import org.openrdf.query.algebra.UnaryTupleOperator;
import org.openrdf.query.algebra.ValueConstant;
import org.openrdf.query.algebra.ValueExpr;
import org.openrdf.query.algebra.Var;
import org.openrdf.query.algebra.helpers.QueryModelVisitorBase;
import org.openrdf.query.parser.sparql.SPARQLParser;

import com.google.common.base.Preconditions;

/**
 * Utility class for creating and executing Perioid Queries.
 *
 */
public class PeriodicQueryUtil {

    private static final ValueFactory vf = new ValueFactoryImpl();
    public static final String PeriodicQueryURI = "http://org.apache.rya/function#periodic";
    public static final String temporalNameSpace = "http://www.w3.org/2006/time#";
    public static final URI DAYS = vf.createURI("http://www.w3.org/2006/time#days");
    public static final URI HOURS = vf.createURI("http://www.w3.org/2006/time#hours");
    public static final URI MINUTES = vf.createURI("http://www.w3.org/2006/time#minutes");

    /**
     * Returns a PeriodicQueryNode for all {@link FunctionCall}s that represent PeriodicQueryNodes, otherwise
     * an empty Optional is returned.
     * @param functionCall - FunctionCall taken from a {@lin TupleExpr}
     * @param arg - TupleExpr that will be the argument of the PeriodicQueryNode if it is created
     * @return - Optional containing a PeriodicQueryNode if FunctionCall represents PeriodicQueryNode and empty Optional otherwise
     * @throws Exception
     */
    public static Optional<PeriodicQueryNode> getPeriodicQueryNode(FunctionCall functionCall, TupleExpr arg) throws Exception {

        if (functionCall.getURI().equals(PeriodicQueryURI)) {
            return Optional.of(parseAndSetValues(functionCall.getArgs(), arg));
        }

        return Optional.empty();
    }

    /**
     * Finds and places a PeriodicQueryNode if the TupleExpr contains a FunctionCall
     * that represents a PeriodicQueryNode.
     * @param query - TupleExpr with PeriodicQueryNode placed and positioned at the top of the query
     */
    public static void placePeriodicQueryNode(TupleExpr query) {
        query.visit(new PeriodicQueryNodeVisitor());
        query.visit(new PeriodicQueryNodeRelocator());
    }
    
    public static Optional<PeriodicQueryNode> getPeriodicNode(String sparql) throws MalformedQueryException {
        TupleExpr te = new SPARQLParser().parseQuery(sparql, null).getTupleExpr();
        PeriodicQueryNodeVisitor periodicVisitor = new PeriodicQueryNodeVisitor();
        te.visit(periodicVisitor);
        return periodicVisitor.getPeriodicNode();
    }

    /**
     * Locates Filter containing FunctionCall with PeriodicQuery info and
     * replaces that Filter with a PeriodicQueryNode.
     */
    public static class PeriodicQueryNodeVisitor extends QueryModelVisitorBase<RuntimeException> {

        private int count = 0;
        private PeriodicQueryNode periodicNode;
        
        public Optional<PeriodicQueryNode> getPeriodicNode() {
            return Optional.ofNullable(periodicNode);
        }

        public void meet(Filter node) {
            if (node.getCondition() instanceof FunctionCall) {
                try {
                    Optional<PeriodicQueryNode> optNode = getPeriodicQueryNode((FunctionCall) node.getCondition(), node.getArg());
                    if (optNode.isPresent()) {
                        if (count > 0) {
                            throw new IllegalArgumentException("Query cannot contain more than one PeriodicQueryNode");
                        }
                        periodicNode = optNode.get();
                        node.replaceWith(periodicNode);
                        count++;
                        periodicNode.visit(this);
                    } else {
                        super.meet(node);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage());
                }
            } else {
                super.meet(node);
            }
        }
    }

    /**
     * Relocates PeriodicQueryNode so that it occurs below either the Construct
     * Query Node, the Projection Query Node if no Aggregation exists, or the
     * Group Node if an Aggregation exists. This limits the number of nodes
     * whose variable order needs to be changed when the PeriodicQueryMetadata
     * is added.
     */
    public static class PeriodicQueryNodeRelocator extends QueryModelVisitorBase<RuntimeException> {

        private UnaryTupleOperator relocationParent;

        public void meet(Projection node) {
            relocationParent = node;
            node.getArg().visit(this);
        }

        public void meet(Group node) {
            relocationParent = node;
            super.meet(node);
        }

        public void meet(Reduced node) {
            relocationParent = node;
            super.meet(node);
        }

        public void meet(Filter node) {
            super.meet(node);
        }

        @Override
        public void meetOther(QueryModelNode node) {

            if (node instanceof PeriodicQueryNode) {
                PeriodicQueryNode pNode = (PeriodicQueryNode) node;
                // do nothing if PeriodicQueryNode already positioned correctly
                if (pNode.equals(relocationParent.getArg())) {
                    return;
                }
                // remove node from query
                pNode.replaceWith(pNode.getArg());
                // set node' child to be relocationParent's child
                pNode.setArg(relocationParent.getArg());
                // add node back into query below relocationParent
                relocationParent.replaceChildNode(relocationParent.getArg(), pNode);
            }
        }
    }

    /**
     * Adds the variable "periodicBinId" to the beginning of all {@link VariableOrder}s for the 
     * Metadata nodes that appear above the PeriodicQueryNode.  This ensures that the binId is
     * written first in the Row so that bins can be easily scanned and deleted.
     * @param builder
     * @param nodeId
     */
    public static void updateVarOrdersToIncludeBin(FluoQuery.Builder builder, String nodeId) {
        NodeType type = NodeType.fromNodeId(nodeId).orNull();
        if (type == null) {
            throw new IllegalArgumentException("NodeId must be associated with an existing MetadataBuilder.");
        }
        switch (type) {
        case AGGREGATION:
            AggregationMetadata.Builder aggBuilder = builder.getAggregateBuilder(nodeId).orNull();
            if (aggBuilder != null) {
                VariableOrder varOrder = aggBuilder.getVariableOrder();
                VariableOrder groupOrder = aggBuilder.getGroupByVariableOrder();
                // update varOrder with BIN_ID
                List<String> orderList = new ArrayList<>(varOrder.getVariableOrders());
                orderList.add(0, IncrementalUpdateConstants.PERIODIC_BIN_ID);
                aggBuilder.setVariableOrder(new VariableOrder(orderList));
                // update groupVarOrder with BIN_ID
                List<String> groupOrderList = new ArrayList<>(groupOrder.getVariableOrders());
                groupOrderList.add(0, IncrementalUpdateConstants.PERIODIC_BIN_ID);
                aggBuilder.setGroupByVariableOrder(new VariableOrder(groupOrderList));
                // recursive call to update the VariableOrders of all ancestors
                // of this node
                updateVarOrdersToIncludeBin(builder, aggBuilder.getParentNodeId());
            } else {
                throw new IllegalArgumentException("There is no AggregationMetadata.Builder for the indicated Id.");
            }
            break;
        case PERIODIC_QUERY:
            PeriodicQueryMetadata.Builder periodicBuilder = builder.getPeriodicQueryBuilder().orNull();
            if (periodicBuilder != null && periodicBuilder.getNodeId().equals(nodeId)) {
                VariableOrder varOrder = periodicBuilder.getVarOrder();
                List<String> orderList = new ArrayList<>(varOrder.getVariableOrders());
                orderList.add(0, IncrementalUpdateConstants.PERIODIC_BIN_ID);
                periodicBuilder.setVarOrder(new VariableOrder(orderList));
                // recursive call to update the VariableOrders of all ancestors
                // of this node
                updateVarOrdersToIncludeBin(builder, periodicBuilder.getParentNodeId());
            } else {
                throw new IllegalArgumentException(
                        "PeriodicQueryMetadata.Builder id does not match the indicated id.  A query cannot have more than one PeriodicQueryMetadata Node.");
            }
            break;
        case QUERY:
            QueryMetadata.Builder queryBuilder = builder.getQueryBuilder().orNull();
            if (queryBuilder != null && queryBuilder.getNodeId().equals(nodeId)) {
                VariableOrder varOrder = queryBuilder.getVariableOrder();
                List<String> orderList = new ArrayList<>(varOrder.getVariableOrders());
                orderList.add(0, IncrementalUpdateConstants.PERIODIC_BIN_ID);
                queryBuilder.setVariableOrder(new VariableOrder(orderList));
            } else {
                throw new IllegalArgumentException(
                        "QueryMetadata.Builder id does not match the indicated id.  A query cannot have more than one QueryMetadata Node.");
            }
            break;
        default:
            throw new IllegalArgumentException(
                    "Incorrectly positioned PeriodicQueryNode.  The PeriodicQueryNode can only be positioned below Projections, Extensions, and ConstructQueryNodes.");
        }
    }

    /**
     * Collects all Metadata node Ids that are ancestors of the PeriodicQueryNode and contain the variable 
     * {@link IncrementalUpdateConstants#PERIODIC_BIN_ID}.
     * @param sx - Fluo Snapshot for scanning Fluo
     * @param nodeId - root node of the PeriodicQuery
     * @param ids - query ids of all metadata nodes appearing between root and PeriodicQueryMetadata node
     */
    public static void getPeriodicQueryNodeAncestorIds(SnapshotBase sx, String nodeId, Set<String> ids) {
        NodeType nodeType = NodeType.fromNodeId(nodeId).orNull();
        checkArgument(nodeType != null, "Invalid nodeId: " + nodeId + ". NodeId does not correspond to a valid NodeType.");
        switch (nodeType) {
        case FILTER:
            ids.add(nodeId);
            getPeriodicQueryNodeAncestorIds(sx, sx.get(Bytes.of(nodeId), FluoQueryColumns.FILTER_CHILD_NODE_ID).toString(), ids);
            break;
        case PERIODIC_QUERY:
            ids.add(nodeId);
            break;
        case QUERY:
            ids.add(nodeId);
            getPeriodicQueryNodeAncestorIds(sx, sx.get(Bytes.of(nodeId), FluoQueryColumns.QUERY_CHILD_NODE_ID).toString(), ids);
            break;
        case AGGREGATION: 
            ids.add(nodeId);
            getPeriodicQueryNodeAncestorIds(sx, sx.get(Bytes.of(nodeId), FluoQueryColumns.AGGREGATION_CHILD_NODE_ID).toString(), ids);
            break;
        default:
            throw new RuntimeException("Invalid NodeType.");
        }
    }

    
    
    /**
     * 
     * @param values - Values extracted from FunctionCall representing the PeriodicQuery Filter
     * @param arg - Argument of the PeriodicQueryNode that will be created (PeriodicQueryNode is a UnaryTupleOperator)
     * @return - PeriodicQueryNode to be inserted in place of the original FunctionCall
     * @throws Exception
     */
    private static PeriodicQueryNode parseAndSetValues(List<ValueExpr> values, TupleExpr arg) throws Exception {
        // general validation of input
        Preconditions.checkArgument(values.size() == 4);
        Preconditions.checkArgument(values.get(0) instanceof Var);
        Preconditions.checkArgument(values.get(1) instanceof ValueConstant);
        Preconditions.checkArgument(values.get(2) instanceof ValueConstant);
        Preconditions.checkArgument(values.get(3) instanceof ValueConstant);

        // get temporal variable
        Var var = (Var) values.get(0);
        Preconditions.checkArgument(var.getValue() == null);
        String tempVar = var.getName();

        // get TimeUnit
        TimeUnit unit = getTimeUnit((ValueConstant) values.get(3));

        // get window and period durations
        double windowDuration = parseTemporalDuration((ValueConstant) values.get(1));
        double periodDuration = parseTemporalDuration((ValueConstant) values.get(2));
        long windowMillis = convertToMillis(windowDuration, unit);
        long periodMillis = convertToMillis(periodDuration, unit);
        // period must evenly divide window at least once
        Preconditions.checkArgument(windowMillis > periodMillis);
        Preconditions.checkArgument(windowMillis % periodMillis == 0, "Period duration does not evenly divide window duration.");

        // create PeriodicMetadata.Builder
        return new PeriodicQueryNode(windowMillis, periodMillis, TimeUnit.MILLISECONDS, tempVar, arg);
    }

    private static TimeUnit getTimeUnit(ValueConstant val) {
        Preconditions.checkArgument(val.getValue() instanceof URI);
        URI uri = (URI) val.getValue();
        Preconditions.checkArgument(uri.getNamespace().equals(temporalNameSpace));

        switch (uri.getLocalName()) {
        case "days":
            return TimeUnit.DAYS;
        case "hours":
            return TimeUnit.HOURS;
        case "minutes":
            return TimeUnit.MINUTES;
        default:
            throw new IllegalArgumentException("Invalid time unit for Periodic Function.");
        }
    }

    private static double parseTemporalDuration(ValueConstant valConst) {
        Value val = valConst.getValue();
        Preconditions.checkArgument(val instanceof Literal);
        Literal literal = (Literal) val;
        String stringVal = literal.getLabel();
        URI dataType = literal.getDatatype();
        Preconditions.checkArgument(dataType.equals(XMLSchema.DECIMAL) || dataType.equals(XMLSchema.DOUBLE)
                || dataType.equals(XMLSchema.FLOAT) || dataType.equals(XMLSchema.INTEGER) || dataType.equals(XMLSchema.INT));
        return Double.parseDouble(stringVal);
    }

    private static long convertToMillis(double duration, TimeUnit unit) {
        Preconditions.checkArgument(duration > 0);

        double convertedDuration = 0;
        switch (unit) {
        case DAYS:
            convertedDuration = duration * 24 * 60 * 60 * 1000;
            break;
        case HOURS:
            convertedDuration = duration * 60 * 60 * 1000;
            break;
        case MINUTES:
            convertedDuration = duration * 60 * 1000;
            break;
        default:
            throw new IllegalArgumentException("TimeUnit must be of type DAYS, HOURS, or MINUTES.");
        }
        // check that double representation has exact millis representation
        Preconditions.checkArgument(convertedDuration == (long) convertedDuration);
        return (long) convertedDuration;
    }

}

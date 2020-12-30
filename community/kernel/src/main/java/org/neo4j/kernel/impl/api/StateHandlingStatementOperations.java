/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.act.temporalProperty.TemporalPropertyStore;
import org.act.temporalProperty.exception.TPSRuntimeException;
import org.act.temporalProperty.exception.ValueUnknownException;
import org.act.temporalProperty.impl.InternalKey;
import org.act.temporalProperty.impl.MemTable;
import org.act.temporalProperty.impl.ValueType;
import org.act.temporalProperty.index.IndexValueType;
import org.act.temporalProperty.index.value.IndexQueryRegion;
import org.act.temporalProperty.index.value.PropertyValueInterval;
import org.act.temporalProperty.index.value.rtree.IndexEntry;
import org.act.temporalProperty.meta.ValueContentType;
import org.act.temporalProperty.util.Slice;
import org.act.temporalProperty.util.Slices;
import org.act.temporalProperty.util.TemporalPropertyValueConvertor;
import org.apache.commons.lang3.tuple.Triple;

import org.neo4j.collection.primitive.PrimitiveIntCollection;
import org.neo4j.collection.primitive.PrimitiveIntIterator;
import org.neo4j.collection.primitive.PrimitiveIntStack;
import org.neo4j.collection.primitive.PrimitiveLongCollections;
import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.cursor.Cursor;
import org.neo4j.function.Predicate;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.kernel.api.EntityType;
import org.neo4j.kernel.api.LegacyIndex;
import org.neo4j.kernel.api.LegacyIndexHits;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.constraints.NodePropertyConstraint;
import org.neo4j.kernel.api.constraints.NodePropertyExistenceConstraint;
import org.neo4j.kernel.api.constraints.PropertyConstraint;
import org.neo4j.kernel.api.constraints.RelationshipPropertyConstraint;
import org.neo4j.kernel.api.constraints.RelationshipPropertyExistenceConstraint;
import org.neo4j.kernel.api.constraints.UniquenessConstraint;
import org.neo4j.kernel.api.cursor.LabelItem;
import org.neo4j.kernel.api.cursor.NodeItem;
import org.neo4j.kernel.api.cursor.PropertyItem;
import org.neo4j.kernel.api.cursor.RelationshipItem;
import org.neo4j.kernel.api.exceptions.*;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.legacyindex.LegacyIndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.schema.AlreadyConstrainedException;
import org.neo4j.kernel.api.exceptions.schema.ConstraintVerificationFailedKernelException;
import org.neo4j.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.kernel.api.exceptions.schema.DropConstraintFailureException;
import org.neo4j.kernel.api.exceptions.schema.DropIndexFailureException;
import org.neo4j.kernel.api.exceptions.schema.IllegalTokenNameException;
import org.neo4j.kernel.api.exceptions.schema.IndexBrokenKernelException;
import org.neo4j.kernel.api.exceptions.schema.ProcedureConstraintViolation;
import org.neo4j.kernel.api.exceptions.schema.SchemaRuleNotFoundException;
import org.neo4j.kernel.api.exceptions.schema.TooManyLabelsException;
import org.neo4j.kernel.api.index.IndexDescriptor;
import org.neo4j.kernel.api.index.InternalIndexState;
import org.neo4j.kernel.api.procedures.ProcedureDescriptor;
import org.neo4j.kernel.api.procedures.ProcedureSignature;
import org.neo4j.kernel.api.procedures.ProcedureSignature.ProcedureName;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.api.properties.Property;
import org.neo4j.kernel.api.properties.PropertyKeyIdIterator;
import org.neo4j.kernel.api.txstate.TransactionState;
import org.neo4j.kernel.api.txstate.TxStateHolder;
import org.neo4j.kernel.impl.api.operations.CountsOperations;
import org.neo4j.kernel.impl.api.operations.EntityOperations;
import org.neo4j.kernel.impl.api.operations.KeyReadOperations;
import org.neo4j.kernel.impl.api.operations.KeyWriteOperations;
import org.neo4j.kernel.impl.api.operations.LegacyIndexReadOperations;
import org.neo4j.kernel.impl.api.operations.LegacyIndexWriteOperations;
import org.neo4j.kernel.impl.api.operations.SchemaReadOperations;
import org.neo4j.kernel.impl.api.operations.SchemaWriteOperations;
import org.neo4j.kernel.impl.api.state.ConstraintIndexCreator;
import org.neo4j.kernel.impl.api.store.RelationshipIterator;
import org.neo4j.kernel.impl.api.store.StoreReadLayer;
import org.neo4j.kernel.impl.api.store.StoreStatement;
import org.neo4j.kernel.impl.core.Token;
import org.neo4j.kernel.impl.index.IndexEntityType;
import org.neo4j.kernel.impl.index.LegacyIndexStore;
import org.neo4j.kernel.impl.store.SchemaStorage;
import org.neo4j.kernel.impl.store.TemporalPropertyStoreAdapter;
import org.neo4j.kernel.impl.util.Cursors;
import org.neo4j.kernel.impl.util.PrimitiveLongResourceIterator;
import org.neo4j.kernel.impl.util.diffsets.ReadableDiffSets;
import org.neo4j.temporal.IntervalEntry;
import org.neo4j.temporal.TGraphUserInputException;
import org.neo4j.temporal.TemporalIndexDescriptor;
import org.neo4j.temporal.TemporalIndexManager;
import org.neo4j.temporal.TemporalPropertyReadOperation;
import org.neo4j.temporal.TemporalPropertyWriteOperation;

import static org.act.temporalProperty.util.TemporalPropertyValueConvertor.CLASS_NAME_LENGTH_SEPERATOR;
import static org.act.temporalProperty.util.TemporalPropertyValueConvertor.TemporalPropertyMarker;
import static org.act.temporalProperty.util.TemporalPropertyValueConvertor.fromSlice;
import static org.act.temporalProperty.util.TemporalPropertyValueConvertor.toSlice;
import static org.neo4j.collection.primitive.PrimitiveLongCollections.single;
import static org.neo4j.helpers.collection.Iterables.filter;
import static org.neo4j.helpers.collection.IteratorUtil.iterator;
import static org.neo4j.helpers.collection.IteratorUtil.resourceIterator;
import static org.neo4j.helpers.collection.IteratorUtil.singleOrNull;
import static org.neo4j.kernel.api.StatementConstants.NO_SUCH_NODE;
import static org.neo4j.kernel.impl.api.PropertyValueComparison.COMPARE_NUMBERS;

public class StateHandlingStatementOperations
        implements KeyReadOperations, KeyWriteOperations, EntityOperations, SchemaReadOperations, SchemaWriteOperations, CountsOperations,
        LegacyIndexReadOperations, LegacyIndexWriteOperations
{
    private final StoreReadLayer storeLayer;
    private final LegacyPropertyTrackers legacyPropertyTrackers;
    private final ConstraintIndexCreator constraintIndexCreator;
    private final LegacyIndexStore legacyIndexStore;
    private final TemporalPropertyStoreAdapter temporalPropertyStore;

    public StateHandlingStatementOperations( StoreReadLayer storeLayer, LegacyPropertyTrackers propertyTrackers, ConstraintIndexCreator constraintIndexCreator, LegacyIndexStore legacyIndexStore, TemporalPropertyStoreAdapter temporalPropertyStore )
    {
        this.storeLayer = storeLayer;
        this.legacyPropertyTrackers = propertyTrackers;
        this.constraintIndexCreator = constraintIndexCreator;
        this.legacyIndexStore = legacyIndexStore;
        this.temporalPropertyStore = temporalPropertyStore;
    }

    // <Cursors>

    @Override
    public Cursor<NodeItem> nodeCursorById( KernelStatement statement, long nodeId ) throws EntityNotFoundException
    {
        Cursor<NodeItem> node = nodeCursor( statement, nodeId );
        if ( !node.next() )
        {
            node.close();
            throw new EntityNotFoundException( EntityType.NODE, nodeId );
        }
        else
        {
            return node;
        }
    }

    @Override
    public Cursor<NodeItem> nodeCursor( KernelStatement statement, long nodeId )
    {
        Cursor<NodeItem> cursor = statement.getStoreStatement().acquireSingleNodeCursor( nodeId );
        if ( statement.hasTxStateWithChanges() )
        {
            return statement.txState().augmentSingleNodeCursor( cursor, nodeId );
        }
        return cursor;
    }

    @Override
    public Cursor<NodeItem> nodeCursor( TxStateHolder txStateHolder, StoreStatement statement, long nodeId )
    {
        Cursor<NodeItem> cursor = statement.acquireSingleNodeCursor( nodeId );
        if ( txStateHolder.hasTxStateWithChanges() )
        {
            return txStateHolder.txState().augmentSingleNodeCursor( cursor, nodeId );
        }
        return cursor;
    }

    @Override
    public Cursor<RelationshipItem> relationshipCursorById( KernelStatement statement, long relationshipId ) throws EntityNotFoundException
    {
        Cursor<RelationshipItem> relationship = relationshipCursor( statement, relationshipId );
        if ( !relationship.next() )
        {
            relationship.close();
            throw new EntityNotFoundException( EntityType.RELATIONSHIP, relationshipId );
        }
        else
        {
            return relationship;
        }
    }

    @Override
    public Cursor<RelationshipItem> relationshipCursor( KernelStatement statement, long relationshipId )
    {
        Cursor<RelationshipItem> cursor = statement.getStoreStatement().acquireSingleRelationshipCursor( relationshipId );
        if ( statement.hasTxStateWithChanges() )
        {
            return statement.txState().augmentSingleRelationshipCursor( cursor, relationshipId );
        }
        return cursor;
    }

    @Override
    public Cursor<RelationshipItem> relationshipCursor( TxStateHolder txStateHolder, StoreStatement statement, long relationshipId )
    {
        Cursor<RelationshipItem> cursor = statement.acquireSingleRelationshipCursor( relationshipId );
        if ( txStateHolder.hasTxStateWithChanges() )
        {
            return txStateHolder.txState().augmentSingleRelationshipCursor( cursor, relationshipId );
        }
        else
        {
            return cursor;
        }
    }

    @Override
    public Cursor<NodeItem> nodeCursorGetAll( KernelStatement statement )
    {
        Cursor<NodeItem> cursor = statement.getStoreStatement().nodesGetAllCursor();
        if ( statement.hasTxStateWithChanges() )
        {
            return statement.txState().augmentNodesGetAllCursor( cursor );
        }
        return cursor;
    }

    @Override
    public Cursor<RelationshipItem> relationshipCursorGetAll( KernelStatement statement )
    {
        Cursor<RelationshipItem> cursor = statement.getStoreStatement().relationshipsGetAllCursor();
        if ( statement.hasTxStateWithChanges() )
        {
            return statement.txState().augmentRelationshipsGetAllCursor( cursor );
        }
        return cursor;
    }

    @Override
    public Cursor<NodeItem> nodeCursorGetForLabel( KernelStatement statement, int labelId )
    {
        // TODO Filter this properly
        return statement.getStoreStatement().acquireIteratorNodeCursor( storeLayer.nodesGetForLabel( statement, labelId ) );
    }

    @Override
    public Cursor<NodeItem> nodeCursorGetFromIndexSeek( KernelStatement statement, IndexDescriptor index, Object value ) throws IndexNotFoundKernelException
    {
        // TODO Filter this properly
        return statement.getStoreStatement().acquireIteratorNodeCursor( storeLayer.nodesGetFromIndexSeek( statement, index, value ) );
    }

    @Override
    public Cursor<NodeItem> nodeCursorGetFromIndexScan( KernelStatement statement, IndexDescriptor index ) throws IndexNotFoundKernelException
    {
        // TODO Filter this properly
        return statement.getStoreStatement().acquireIteratorNodeCursor( storeLayer.nodesGetFromIndexScan( statement, index ) );
    }

    @Override
    public Cursor<NodeItem> nodeCursorGetFromIndexSeekByPrefix( KernelStatement statement, IndexDescriptor index, String prefix ) throws IndexNotFoundKernelException
    {
        // TODO Filter this properly
        return statement.getStoreStatement().acquireIteratorNodeCursor( storeLayer.nodesGetFromIndexRangeSeekByPrefix( statement, index, prefix ) );
    }

    @Override
    public Cursor<NodeItem> nodeCursorGetFromIndexRangeSeekByNumber( KernelStatement statement, IndexDescriptor index, Number lower, boolean includeLower, Number upper, boolean includeUpper ) throws IndexNotFoundKernelException

    {
        // TODO Filter this properly
        return COMPARE_NUMBERS.isEmptyRange( lower, includeLower, upper, includeUpper ) ? Cursors.<NodeItem>empty() : statement.getStoreStatement()
                                                                                                                               .acquireIteratorNodeCursor(
                                                                                                                                       storeLayer.nodesGetFromInclusiveNumericIndexRangeSeek(
                                                                                                                                               statement,
                                                                                                                                               index,
                                                                                                                                               lower,
                                                                                                                                               upper ) );
    }

    @Override
    public Cursor<NodeItem> nodeCursorGetFromIndexRangeSeekByString( KernelStatement statement, IndexDescriptor index, String lower, boolean includeLower, String upper, boolean includeUpper ) throws IndexNotFoundKernelException

    {
        // TODO Filter this properly
        return statement.getStoreStatement()
                        .acquireIteratorNodeCursor( storeLayer.nodesGetFromIndexRangeSeekByString( statement,
                                                                                                   index,
                                                                                                   lower,
                                                                                                   includeLower,
                                                                                                   upper,
                                                                                                   includeUpper ) );
    }

    @Override
    public Cursor<NodeItem> nodeCursorGetFromIndexRangeSeekByPrefix( KernelStatement statement, IndexDescriptor index, String prefix ) throws IndexNotFoundKernelException
    {
        // TODO Filter this properly
        return statement.getStoreStatement().acquireIteratorNodeCursor( storeLayer.nodesGetFromIndexRangeSeekByPrefix( statement, index, prefix ) );
    }

    @Override
    public Cursor<NodeItem> nodeCursorGetFromUniqueIndexSeek( KernelStatement statement, IndexDescriptor index, Object value ) throws IndexBrokenKernelException, IndexNotFoundKernelException
    {
        // TODO Filter this properly
        return statement.getStoreStatement().acquireIteratorNodeCursor( storeLayer.nodeGetFromUniqueIndexSeek( statement, index, value ) );
    }

    // </Cursors>

    @Override
    public long nodeCreate( KernelStatement state )
    {
        long nodeId = storeLayer.reserveNode();
        state.txState().nodeDoCreate( nodeId );
        return nodeId;
    }

    @Override
    public void nodeDelete( KernelStatement state, long nodeId ) throws EntityNotFoundException
    {
        legacyPropertyTrackers.nodeDelete( nodeId );
        try ( Cursor<NodeItem> cursor = nodeCursorById( state, nodeId ) )
        {
            state.txState().nodeDoDelete( cursor.get().id() );
        }
    }

    @Override
    public int nodeDetachDelete( KernelStatement state, long nodeId ) throws EntityNotFoundException
    {
        nodeDelete( state, nodeId );
        return 0;
    }

    @Override
    public List<IntervalEntry> getTemporalPropertyByIndex( KernelStatement statement, TemporalIndexManager.PropertyValueIntervalBuilder builder )
    {
        IndexQueryRegion queryRegion = build2query( builder );
        List<IndexEntry> result;
        if ( builder.isNode() )
        {
            result = temporalPropertyStore.nodeStore().getEntries( queryRegion, statement.txState().getNodeTemporalProperties() );
        }
        else
        {
            result = temporalPropertyStore.relStore().getEntries( queryRegion, statement.txState().getRelationshipTemporalProperties() );
        }
        List<IntervalEntry> r = new ArrayList<>();
        for ( IndexEntry i : result )
        {
            int propertyCount = queryRegion.getPropertyValueIntervals().size();
            Object[] val = new Object[propertyCount];
            for ( int j = 0; j < propertyCount; j++ )
            {
                val[j] = i.getValue( j );
            }
            r.add( new IntervalEntry( i.getStart(), i.getEnd(), i.getEntityId(), val ) );
        }
        return r;
    }

    private IndexQueryRegion build2query( TemporalIndexManager.PropertyValueIntervalBuilder builder )
    {
        IndexQueryRegion condition = new IndexQueryRegion( builder.getStart(), builder.getEnd() );
        for ( Map.Entry<Integer,Triple<String,Object,Object>> entry : builder.getPropertyValues().entrySet() )
        {
            int proId = entry.getKey();
            ValueContentType type = tpType( proId, builder.isNode() );
            if ( type == null )
            {
                throw new NotFoundException( "property " + entry.getValue().getLeft() + "(id=" + proId + ") not found!" );
            }
            else
            {
                Slice min = tpValConvert( entry.getValue().getMiddle(), type );
                Slice max = tpValConvert( entry.getValue().getRight(), type );
                condition.add( new PropertyValueInterval( proId, min, max, IndexValueType.convertFrom( type ) ) );
            }
        }
        return condition;
    }

    private Slice tpValConvert( Object val, ValueContentType type )
    {
        if ( val != null )
        {
            String className = val.getClass().getSimpleName();
            if ( type != TemporalPropertyValueConvertor.str2type( className ) )
            {
                throw new TGraphUserInputException( "property type not match!" );
            }
            return TemporalPropertyValueConvertor.toSlice( val );
        }
        else
        {
            throw new TGraphUserInputException( "property value is null!" );
        }
    }

    private ValueContentType tpType( int proId, boolean isNode )
    {
        return isNode ? temporalPropertyStore.nodeStore().getPropertyValueType( proId ) : temporalPropertyStore.relStore().getPropertyValueType( proId );
    }

    @Override
    public Object nodeGetTemporalProperty( KernelStatement statement, TemporalPropertyReadOperation query ) throws EntityNotFoundException
    {
        // query static property first.
        try ( Cursor<NodeItem> cursor = nodeCursorById( statement, query.getEntityId() ) )
        {
            NodeItem node = cursor.get();
            try ( Cursor<PropertyItem> properties = node.property( query.getProId() ) )
            {
                if ( properties.next() )
                {
                    // return static property value if not a temporal property.
                    Object staticPro = properties.get().value();
                    ValueContentType valueType;
                    try
                    {
                        valueType = decodeTemporalPropertyMeta( staticPro );
                    }
                    catch ( TPSRuntimeException ignore )
                    {
                        return staticPro;
                    }
                    TemporalPropertyStore store = temporalPropertyStore.nodeStore();
                    if ( statement.hasTxStateWithChanges() )
                    {
                        MemTable txState = statement.txState().getNodeTemporalProperties();
                        if ( txState != null && !txState.isEmpty() ) // has in txState
                        {
                            if ( query.isPointQuery() )
                            {
                                return tpQueryPointWithCache( query, txState, store, valueType );
                            }
                            else if ( query.isRangeQuery() )
                            {
                                return tpQueryRangeWithCache( query, txState, store, valueType );
                            }
                            else
                            {
                                return tpQueryIndexWithCache( query, txState, store, valueType );
                            }
                        }
                    }
                    // not in TxState, then get from store
                    if ( query.isPointQuery() )
                    {
                        return tpQueryPoint( query, store, valueType );
                    }
                    else if ( query.isRangeQuery() )
                    {
                        return tpQueryRange( query, store, valueType );
                    }
                    else
                    {
                        return tpQueryIndex( query, store, valueType );
                    }
                }
                else
                {
                    return null; //Cannot get value from neo4j store
                }
            }
        }
    }

    @Override
    public Object relationshipGetTemporalProperty( KernelStatement statement, TemporalPropertyReadOperation query ) throws EntityNotFoundException
    {
        try ( Cursor<RelationshipItem> cursor = relationshipCursorById( statement, query.getEntityId() ) )
        {
            RelationshipItem rel = cursor.get();
            try ( Cursor<PropertyItem> properties = rel.property( query.getProId() ) )
            {
                if ( properties.next() )
                {
                    // return static property value if not a temporal property.
                    Object staticPro = properties.get().value();
                    ValueContentType valueType;
                    try
                    {
                        valueType = decodeTemporalPropertyMeta( staticPro );
                    }
                    catch ( TPSRuntimeException ignore )
                    {
                        return staticPro;
                    }
                    TemporalPropertyStore store = temporalPropertyStore.relStore();
                    if ( statement.hasTxStateWithChanges() )
                    {
                        MemTable txState = statement.txState().getRelationshipTemporalProperties();
                        if ( txState != null && !txState.isEmpty() ) // has in txState
                        {
                            if ( query.isPointQuery() )
                            {
                                return tpQueryPointWithCache( query, txState, store, valueType );
                            }
                            else if ( query.isRangeQuery() )
                            {
                                return tpQueryRangeWithCache( query, txState, store, valueType );
                            }
                            else
                            {
                                return tpQueryIndexWithCache( query, txState, store, valueType );
                            }
                        }
                    }
                    // not in TxState, then get from store
                    if ( query.isPointQuery() )
                    {
                        return tpQueryPoint( query, store, valueType );
                    }
                    else if ( query.isRangeQuery() )
                    {
                        return tpQueryRange( query, store, valueType );
                    }
                    else
                    {
                        return tpQueryIndex( query, store, valueType );
                    }
                }
                else
                {
                    return null; //Cannot get value from neo4j store, entity may not set this property.
                }
            }
        }
    }

    private Object tpQueryPointWithCache( TemporalPropertyReadOperation query, MemTable txState, TemporalPropertyStore store, ValueContentType valueType )
    {
        try
        {
            Slice value = txState.get( new InternalKey( query.getProId(), query.getEntityId(), query.getStart(), ValueType.VALUE ) );
            if ( value == null )
            {
                return null;
            }
            else
            {
                return fromSlice( valueType, value );
            }
        }
        catch ( ValueUnknownException e )
        {
            return tpQueryPoint( query, store, valueType );
        }
    }

    private Object tpQueryRangeWithCache( TemporalPropertyReadOperation query, MemTable txState, TemporalPropertyStore store, ValueContentType valueType )
    {
        return temporalPropertyStore.getRange( store, query, txState );
    }

    private Object tpQueryIndexWithCache( TemporalPropertyReadOperation query, MemTable txState, TemporalPropertyStore store, ValueContentType valueType )
    {
        return temporalPropertyStore.getAggrIndex( store, query, txState );
    }

    private Object tpQueryPoint( TemporalPropertyReadOperation query, TemporalPropertyStore store, ValueContentType valueType )
    {
        Slice value = temporalPropertyStore.getPoint( store, query );
        if ( value != null )
        {
            return fromSlice( valueType, value );
        }
        else
        {
            return null;
        }
    }

    private Object tpQueryRange( TemporalPropertyReadOperation query, TemporalPropertyStore store, ValueContentType valueType )
    {
        return temporalPropertyStore.getRange( store, query, null );
    }

    private Object tpQueryIndex( TemporalPropertyReadOperation query, TemporalPropertyStore store, ValueContentType valueType )
    {
        return temporalPropertyStore.getAggrIndex( store, query, null );
    }

    private String buildTemporalPropertyMeta( ValueContentType valueType )
    {
        return valueType.getId() + CLASS_NAME_LENGTH_SEPERATOR + TemporalPropertyMarker;
    }

    private ValueContentType decodeTemporalPropertyMeta( Object meta )
    {
        if ( meta == null || !(meta instanceof String) )
        {
            throw new TPSRuntimeException( "not a temporal property!" );
        }
        String[] arr = ((String) meta).split( CLASS_NAME_LENGTH_SEPERATOR );
        if ( arr.length != 2 || !arr[1].toUpperCase().equals( TemporalPropertyMarker ) )
        {
            throw new TPSRuntimeException( "not a temporal property!" );
        }
        return ValueContentType.decode( Integer.parseInt( arr[0] ) );
    }

    @Override
    public void nodeSetTemporalProperty( KernelStatement statement, TemporalPropertyWriteOperation op ) throws EntityNotFoundException
    {
        try ( Cursor<NodeItem> cursor = nodeCursorById( statement, op.getEntityId() ) )
        {
            NodeItem node = cursor.get();
            try ( Cursor<PropertyItem> properties = node.property( op.getProId() ) )
            {
                if ( !properties.next() ) // create new property
                {
                    String propertyMetaString = buildTemporalPropertyMeta( op.getInternalKey().getValueType().toValueContentType() );
                    DefinedProperty property = Property.property( op.getProId(), propertyMetaString );
                    legacyPropertyTrackers.nodeAddStoreProperty( node.id(), property );
                    Property existingProperty = Property.noProperty( property.propertyKeyId(), EntityType.NODE, node.id() );
                    statement.txState().nodeDoReplaceProperty( op.getEntityId(), existingProperty, property );
                }
                else if ( op.getInternalKey().getValueType().isValue() ) //check exist property value type
                {
                    Object staticPro = properties.get().value();
                    ValueContentType valueType = decodeTemporalPropertyMeta( staticPro );
                    if ( !op.getInternalKey().getValueType().toValueContentType().equals( valueType ) )
                    {
                        throw new TPSRuntimeException( "value type error: property type {} but try to set {} value!",
                                                       valueType,
                                                       op.getInternalKey().getValueType().toValueContentType() );
                    }
                }
                //                else
                //                {
                //                    existingProperty = Property.property( properties.get().propertyKeyId(), properties.get().value() );
                //                    legacyPropertyTrackers.nodeChangeStoreProperty( node.id(), (DefinedProperty) existingProperty, property ); // TGraph: no need, for value not change.
                //                }
                if ( op.getInternalKey().getValueType() != ValueType.INVALID )
                {
                    op.setValueSlice( toSlice( op.getValue() ) );
                }
                else
                {
                    op.setValueSlice( new Slice( 0 ) );
                }
                statement.txState().nodeDoSetTemporalProperty( op );
            }
        }
    }

    @Override
    public void relationshipSetTemporalProperty( KernelStatement statement, TemporalPropertyWriteOperation op ) throws EntityNotFoundException
    {
        try ( Cursor<RelationshipItem> cursor = relationshipCursorById( statement, op.getEntityId() ) )
        {
            RelationshipItem relationship = cursor.get();
            try ( Cursor<PropertyItem> properties = relationship.property( op.getProId() ) )
            {
                if ( !properties.next() )
                {
                    String propertyMetaString = buildTemporalPropertyMeta( op.getInternalKey().getValueType().toValueContentType() );
                    DefinedProperty property = Property.property( op.getProId(), propertyMetaString );
                    legacyPropertyTrackers.relationshipAddStoreProperty( relationship.id(), property );
                    Property existingProperty = Property.noProperty( op.getProId(), EntityType.RELATIONSHIP, relationship.id() );
                    statement.txState().relationshipDoReplaceProperty( op.getEntityId(), existingProperty, property );
                }
                else if ( op.getInternalKey().getValueType().isValue() ) //check exist property value type
                {
                    Object staticPro = properties.get().value();
                    ValueContentType valueType = decodeTemporalPropertyMeta( staticPro );
                    if ( !op.getInternalKey().getValueType().toValueContentType().equals( valueType ) )
                    {
                        throw new TPSRuntimeException( "value type error: property type {} but try to set {} value!",
                                                       valueType,
                                                       op.getInternalKey().getValueType().toValueContentType() );
                    }
                }
                //                else
                //                {
                //                    existingProperty = Property.property( properties.get().propertyKeyId(), properties.get().value() );
                //                    legacyPropertyTrackers.relationshipChangeStoreProperty( relationship.id(), (DefinedProperty) existingProperty, property );
                //                }
                if ( op.getInternalKey().getValueType() != ValueType.INVALID )
                {
                    op.setValueSlice( toSlice( op.getValue() ) );
                }
                else
                {
                    op.setValueSlice( new Slice( 0 ) );
                }
                statement.txState().relationshipDoSetTemporalProperty( op );
            }
        }
    }

    //    @Override
    //    public void relationshipInvalidTemporalProperty(KernelStatement statement, long relId, int propertyKeyId, int time) throws EntityNotFoundException, PropertyNotFoundException
    //    {
    //        try ( Cursor<RelationshipItem> cursor = relationshipCursorById( statement, relId ) )
    //        {
    //            RelationshipItem rel = cursor.get();
    //            try (Cursor<PropertyItem> properties = rel.property(propertyKeyId))
    //            {
    //                if (!properties.next())
    //                {
    //                    throw new PropertyNotFoundException(propertyKeyId, EntityType.RELATIONSHIP, relId);
    //                } else
    //                {
    //                    int maxValueLength;
    //                    try
    //                    {
    //                        String v = (String) properties.get().value();
    //                        String maxValueLengthStr = v.split(CLASS_NAME_LENGTH_SEPERATOR)[1];
    //                        maxValueLength = Integer.parseInt(maxValueLengthStr);
    //                    } catch (NumberFormatException | NullPointerException e)
    //                    {
    //                        throw new PropertyNotFoundException(propertyKeyId, EntityType.RELATIONSHIP, relId);
    //                    }
    //                    statement.txState().relationshipDoCreateTemporalPropertyInvalidRecord(relId, Property.temporalProperty(propertyKeyId, time, 0, new byte[maxValueLength]));
    //                }
    //            }
    //        }
    //    }
    //
    //    @Override
    //    public void relationshipDeleteTemporalProperty(KernelStatement statement, long relId, int propertyKeyId) throws EntityNotFoundException, PropertyNotFoundException
    //    {
    //        try ( Cursor<RelationshipItem> cursor = relationshipCursorById( statement, relId ) )
    //        {
    //            RelationshipItem node = cursor.get();
    //            try (Cursor<PropertyItem> properties = node.property(propertyKeyId))
    //            {
    //                if (!properties.next())
    //                {
    //                    throw new PropertyNotFoundException(propertyKeyId, EntityType.RELATIONSHIP, relId);
    //                } else
    //                {
    //                    statement.txState().nodeDoDeleteTemporalProperty( relId, propertyKeyId );
    //                    relationshipRemoveProperty( statement, relId, propertyKeyId );
    //                }
    //            }
    //        }
    //    }
    //
    //    @Override
    //    public void relationshipDeleteTemporalPropertyRecord(KernelStatement statement, long relId, int propertyKeyId, int time) throws EntityNotFoundException, PropertyNotFoundException
    //    {
    //        try ( Cursor<RelationshipItem> cursor = relationshipCursorById( statement, relId ) )
    //        {
    //            RelationshipItem rel = cursor.get();
    //            try (Cursor<PropertyItem> properties = rel.property(propertyKeyId))
    //            {
    //                if (!properties.next())
    //                {
    //                    throw new PropertyNotFoundException(propertyKeyId, EntityType.RELATIONSHIP, relId);
    //                } else
    //                {
    //                    int maxValueLength;
    //                    try
    //                    {
    //                        String v = (String) properties.get().value();
    //                        String maxValueLengthStr = v.split(CLASS_NAME_LENGTH_SEPERATOR)[1];
    //                        maxValueLength = Integer.parseInt(maxValueLengthStr);
    //                    } catch (NumberFormatException | NullPointerException e)
    //                    {
    //                        throw new PropertyNotFoundException(propertyKeyId, EntityType.RELATIONSHIP, relId);
    //                    }
    //                    statement.txState().relationshipDoDeleteTemporalPropertyRecord(relId, Property.temporalProperty(propertyKeyId, time, 0, new byte[maxValueLength]));
    //                }
    //            }
    //        }
    //    }

    @Override
    public long relationshipCreate( KernelStatement state, int relationshipTypeId, long startNodeId, long endNodeId ) throws EntityNotFoundException
    {
        try ( Cursor<NodeItem> startNode = nodeCursorById( state, startNodeId ) )
        {
            try ( Cursor<NodeItem> endNode = nodeCursorById( state, endNodeId ) )
            {
                long id = storeLayer.reserveRelationship();
                state.txState().relationshipDoCreate( id, relationshipTypeId, startNode.get().id(), endNode.get().id() );
                return id;
            }
        }
    }

    @Override
    public void relationshipDelete( final KernelStatement state, long relationshipId ) throws EntityNotFoundException
    {
        try ( Cursor<RelationshipItem> cursor = relationshipCursorById( state, relationshipId ) )
        {
            RelationshipItem relationship = cursor.get();

            // NOTE: We implicitly delegate to neoStoreTransaction via txState.legacyState here. This is because that
            // call returns modified properties, which node manager uses to update legacy tx state. This will be cleaned up

            // once we've removed legacy tx state.
            legacyPropertyTrackers.relationshipDelete( relationship.id() );
            final TransactionState txState = state.txState();
            if ( txState.relationshipIsAddedInThisTx( relationship.id() ) )
            {
                txState.relationshipDoDeleteAddedInThisTx( relationship.id() );
            }
            else
            {
                txState.relationshipDoDelete( relationship.id(), relationship.type(), relationship.startNode(), relationship.endNode() );
            }
        }
    }

    @Override
    public PrimitiveLongIterator nodesGetAll( KernelStatement state )
    {
        PrimitiveLongIterator iterator = storeLayer.nodesGetAll();
        return state.hasTxStateWithChanges() ? state.txState().augmentNodesGetAll( iterator ) : iterator;
    }

    @Override

    public RelationshipIterator relationshipsGetAll( KernelStatement state )
    {
        RelationshipIterator iterator = storeLayer.relationshipsGetAll();
        return state.hasTxStateWithChanges() ? state.txState().augmentRelationshipsGetAll( iterator ) : iterator;
    }

    @Override
    public boolean nodeAddLabel( KernelStatement state, long nodeId, int labelId ) throws EntityNotFoundException
    {
        try ( Cursor<NodeItem> cursor = nodeCursorById( state, nodeId ) )
        {
            NodeItem node = cursor.get();
            try ( Cursor<LabelItem> labels = node.label( labelId ) )
            {
                if ( labels.next() )
                {
                    // Label is already in state or in store, no-op
                    return false;
                }
            }

            state.txState().nodeDoAddLabel( labelId, node.id() );

            try ( Cursor<PropertyItem> properties = node.properties() )
            {
                while ( properties.next() )
                {
                    PropertyItem propertyItem = properties.get();
                    IndexDescriptor descriptor = indexesGetForLabelAndPropertyKey( state, labelId, propertyItem.propertyKeyId() );
                    if ( descriptor != null )
                    {
                        DefinedProperty after = Property.property( propertyItem.propertyKeyId(), propertyItem.value() );

                        state.txState().indexDoUpdateProperty( descriptor, node.id(), null, after );
                    }
                }

                return true;
            }
        }
    }

    @Override
    public boolean nodeRemoveLabel( KernelStatement state, long nodeId, int labelId ) throws EntityNotFoundException
    {
        try ( Cursor<NodeItem> cursor = nodeCursorById( state, nodeId ) )
        {
            NodeItem node = cursor.get();
            try ( Cursor<LabelItem> labels = node.label( labelId ) )
            {
                if ( !labels.next() )
                {
                    // Label does not exist in state or in store, no-op
                    return false;
                }
            }

            state.txState().nodeDoRemoveLabel( labelId, node.id() );

            try ( Cursor<PropertyItem> properties = node.properties() )
            {
                while ( properties.next() )
                {
                    PropertyItem propItem = properties.get();
                    DefinedProperty property = Property.property( propItem.propertyKeyId(), propItem.value() );
                    indexUpdateProperty( state, node.id(), labelId, property.propertyKeyId(), property, null );
                }
            }

            return true;
        }
    }

    @Override
    public PrimitiveLongIterator nodesGetForLabel( KernelStatement state, int labelId )
    {
        if ( state.hasTxStateWithChanges() )
        {
            PrimitiveLongIterator wLabelChanges = state.txState().nodesWithLabelChanged( labelId ).augment( storeLayer.nodesGetForLabel( state, labelId ) );
            return state.txState().addedAndRemovedNodes().augmentWithRemovals( wLabelChanges );
        }

        return storeLayer.nodesGetForLabel( state, labelId );
    }

    @Override
    public IndexDescriptor temporalIndexCreate( KernelStatement state, int type, int propertyKey, int from, int to )
    {
        TemporalIndexDescriptor rule = new TemporalIndexDescriptor.MinMax( propertyKey, from, to );
        state.txState().nodeTemporalPropertyIndexAdd( rule );
        return rule;
    }

    @Override
    public IndexDescriptor indexCreate( KernelStatement state, int labelId, int propertyKey )
    {
        IndexDescriptor rule = new IndexDescriptor( labelId, propertyKey );
        state.txState().indexRuleDoAdd( rule );
        return rule;
    }

    @Override
    public void indexDrop( KernelStatement state, IndexDescriptor descriptor ) throws DropIndexFailureException
    {
        state.txState().indexDoDrop( descriptor );
    }

    @Override
    public void uniqueIndexDrop( KernelStatement state, IndexDescriptor descriptor ) throws DropIndexFailureException
    {
        state.txState().constraintIndexDoDrop( descriptor );
    }

    @Override
    public UniquenessConstraint uniquePropertyConstraintCreate( KernelStatement state, int labelId, int propertyKeyId ) throws CreateConstraintFailureException
    {
        UniquenessConstraint constraint = new UniquenessConstraint( labelId, propertyKeyId );
        try
        {
            IndexDescriptor index = new IndexDescriptor( labelId, propertyKeyId );
            if ( state.hasTxStateWithChanges() && state.txState().constraintIndexDoUnRemove( index ) ) // ..., DROP, *CREATE*
            { // creation is undoing a drop
                if ( !state.txState().constraintDoUnRemove( constraint ) ) // CREATE, ..., DROP, *CREATE*
                { // ... the drop we are undoing did itself undo a prior create...
                    state.txState().constraintDoAdd( constraint, state.txState().indexCreatedForConstraint( constraint ) );
                }
            }
            else // *CREATE*
            { // create from scratch
                for ( Iterator<NodePropertyConstraint> it = storeLayer.constraintsGetForLabelAndPropertyKey( labelId, propertyKeyId ); it.hasNext(); )
                {
                    if ( it.next().equals( constraint ) )
                    {
                        return constraint;
                    }
                }
                long indexId = constraintIndexCreator.createUniquenessConstraintIndex( state, this, labelId, propertyKeyId );
                state.txState().constraintDoAdd( constraint, indexId );
            }
            return constraint;
        }
        catch ( ConstraintVerificationFailedKernelException | DropIndexFailureException | TransactionFailureException e )
        {
            throw new CreateConstraintFailureException( constraint, e );
        }
    }

    @Override
    public NodePropertyExistenceConstraint nodePropertyExistenceConstraintCreate( KernelStatement state, int labelId, int propertyKeyId ) throws CreateConstraintFailureException
    {
        NodePropertyExistenceConstraint constraint = new NodePropertyExistenceConstraint( labelId, propertyKeyId );
        state.txState().constraintDoAdd( constraint );
        return constraint;
    }

    @Override
    public RelationshipPropertyExistenceConstraint relationshipPropertyExistenceConstraintCreate( KernelStatement state, int relTypeId, int propertyKeyId ) throws AlreadyConstrainedException, CreateConstraintFailureException
    {
        RelationshipPropertyExistenceConstraint constraint = new RelationshipPropertyExistenceConstraint( relTypeId, propertyKeyId );
        state.txState().constraintDoAdd( constraint );
        return constraint;
    }

    @Override
    public Iterator<NodePropertyConstraint> constraintsGetForLabelAndPropertyKey( KernelStatement state, int labelId, int propertyKeyId )
    {
        Iterator<NodePropertyConstraint> constraints = storeLayer.constraintsGetForLabelAndPropertyKey( labelId, propertyKeyId );
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().constraintsChangesForLabelAndProperty( labelId, propertyKeyId ).apply( constraints );
        }
        return constraints;
    }

    @Override
    public Iterator<NodePropertyConstraint> constraintsGetForLabel( KernelStatement state, int labelId )
    {
        Iterator<NodePropertyConstraint> constraints = storeLayer.constraintsGetForLabel( labelId );
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().constraintsChangesForLabel( labelId ).apply( constraints );
        }
        return constraints;
    }

    @Override
    public Iterator<RelationshipPropertyConstraint> constraintsGetForRelationshipTypeAndPropertyKey( KernelStatement state, int relTypeId, int propertyKeyId )
    {
        Iterator<RelationshipPropertyConstraint> constraints = storeLayer.constraintsGetForRelationshipTypeAndPropertyKey( relTypeId, propertyKeyId );
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().constraintsChangesForRelationshipTypeAndProperty( relTypeId, propertyKeyId ).apply( constraints );
        }
        return constraints;
    }

    @Override
    public Iterator<RelationshipPropertyConstraint> constraintsGetForRelationshipType( KernelStatement state, int typeId )
    {
        Iterator<RelationshipPropertyConstraint> constraints = storeLayer.constraintsGetForRelationshipType( typeId );
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().constraintsChangesForRelationshipType( typeId ).apply( constraints );
        }
        return constraints;
    }

    @Override
    public Iterator<PropertyConstraint> constraintsGetAll( KernelStatement state )
    {
        Iterator<PropertyConstraint> constraints = storeLayer.constraintsGetAll();
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().constraintsChanges().apply( constraints );
        }
        return constraints;
    }

    @Override
    public void constraintDrop( KernelStatement state, NodePropertyConstraint constraint )
    {
        state.txState().constraintDoDrop( constraint );
    }

    @Override
    public void constraintDrop( KernelStatement state, RelationshipPropertyConstraint constraint ) throws DropConstraintFailureException
    {
        state.txState().constraintDoDrop( constraint );
    }

    @Override
    public void procedureCreate( KernelStatement state, ProcedureSignature signature, String language, String code )
    {
        state.txState().procedureDoCreate( signature, language, code );
    }

    @Override
    public void procedureDrop( KernelStatement statement, ProcedureName name ) throws ProcedureException, ProcedureConstraintViolation
    {
        statement.txState().procedureDoDrop( procedureGet( statement, name ) );
    }

    @Override
    public Iterator<ProcedureDescriptor> proceduresGetAll( KernelStatement statement )
    {
        Iterator<ProcedureDescriptor> procs = storeLayer.proceduresGetAll();
        return statement.hasTxStateWithChanges() ? statement.txState().augmentProcedures( procs ) : procs;
    }

    @Override
    public ProcedureDescriptor procedureGet( KernelStatement statement, ProcedureName name ) throws ProcedureException
    {
        if ( statement.hasTxStateWithChanges() )
        {
            TransactionState state = statement.txState();
            ProcedureDescriptor procedure = state.getProcedure( name );
            if ( procedure != null )
            {
                return procedure;
            }
        }
        return storeLayer.procedureGet( name );
    }

    @Override
    public IndexDescriptor indexesGetForLabelAndPropertyKey( KernelStatement state, int labelId, int propertyKey )
    {
        IndexDescriptor indexDescriptor = storeLayer.indexesGetForLabelAndPropertyKey( labelId, propertyKey );

        Iterator<IndexDescriptor> rules = iterator( indexDescriptor );
        if ( state.hasTxStateWithChanges() )
        {
            rules = filterByPropertyKeyId( state.txState().indexDiffSetsByLabel( labelId ).apply( rules ), propertyKey );
        }
        return singleOrNull( rules );
    }

    private Iterator<IndexDescriptor> filterByPropertyKeyId( Iterator<IndexDescriptor> descriptorIterator, final int propertyKey )
    {
        Predicate<IndexDescriptor> predicate = new Predicate<IndexDescriptor>()
        {
            @Override
            public boolean test( IndexDescriptor item )
            {
                return item.getPropertyKeyId() == propertyKey;
            }
        };
        return filter( predicate, descriptorIterator );
    }

    @Override
    public InternalIndexState indexGetState( KernelStatement state, IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        // If index is in our state, then return populating
        if ( state.hasTxStateWithChanges() )
        {
            if ( checkIndexState( descriptor, state.txState().indexDiffSetsByLabel( descriptor.getLabelId() ) ) )
            {
                return InternalIndexState.POPULATING;
            }
            ReadableDiffSets<IndexDescriptor> changes = state.txState().constraintIndexDiffSetsByLabel( descriptor.getLabelId() );
            if ( checkIndexState( descriptor, changes ) )
            {
                return InternalIndexState.POPULATING;
            }
        }

        return storeLayer.indexGetState( descriptor );
    }

    private boolean checkIndexState( IndexDescriptor indexRule, ReadableDiffSets<IndexDescriptor> diffSet ) throws IndexNotFoundKernelException
    {
        if ( diffSet.isAdded( indexRule ) )
        {
            return true;
        }
        if ( diffSet.isRemoved( indexRule ) )
        {
            throw new IndexNotFoundKernelException( String.format( "Index for label id %d on property id %d has been " + "dropped in this transaction.",
                                                                   indexRule.getLabelId(),
                                                                   indexRule.getPropertyKeyId() ) );
        }
        return false;
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetForLabel( KernelStatement state, int labelId )
    {
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().indexDiffSetsByLabel( labelId ).apply( storeLayer.indexesGetForLabel( labelId ) );
        }

        return storeLayer.indexesGetForLabel( labelId );
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetAll( KernelStatement state )
    {
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().indexChanges().apply( storeLayer.indexesGetAll() );
        }

        return storeLayer.indexesGetAll();
    }

    @Override
    public Iterator<IndexDescriptor> uniqueIndexesGetForLabel( KernelStatement state, int labelId )
    {
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().constraintIndexDiffSetsByLabel( labelId ).apply( storeLayer.uniqueIndexesGetForLabel( labelId ) );
        }

        return storeLayer.uniqueIndexesGetForLabel( labelId );
    }

    @Override
    public Iterator<IndexDescriptor> uniqueIndexesGetAll( KernelStatement state )
    {
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().constraintIndexChanges().apply( storeLayer.uniqueIndexesGetAll() );
        }

        return storeLayer.uniqueIndexesGetAll();
    }

    @Override
    public long nodeGetFromUniqueIndexSeek( KernelStatement state, IndexDescriptor index, Object value ) throws IndexNotFoundKernelException, IndexBrokenKernelException
    {
        PrimitiveLongResourceIterator committed = storeLayer.nodeGetFromUniqueIndexSeek( state, index, value );
        PrimitiveLongIterator exactMatches = filterExactIndexMatches( state, index, value, committed );
        PrimitiveLongIterator changesFiltered = filterIndexStateChangesForScanOrSeek( state, index, value, exactMatches );
        return single( resourceIterator( changesFiltered, committed ), NO_SUCH_NODE );
    }

    @Override
    public PrimitiveLongIterator nodesGetFromIndexSeek( KernelStatement state, IndexDescriptor index, Object value ) throws IndexNotFoundKernelException
    {
        PrimitiveLongIterator committed = storeLayer.nodesGetFromIndexSeek( state, index, value );
        PrimitiveLongIterator exactMatches = filterExactIndexMatches( state, index, value, committed );
        return filterIndexStateChangesForScanOrSeek( state, index, value, exactMatches );
    }

    @Override
    public PrimitiveLongIterator nodesGetFromIndexRangeSeekByNumber( KernelStatement state, IndexDescriptor index, Number lower, boolean includeLower, Number upper, boolean includeUpper ) throws IndexNotFoundKernelException

    {
        PrimitiveLongIterator committed = COMPARE_NUMBERS.isEmptyRange( lower, includeLower, upper, includeUpper ) ? PrimitiveLongCollections.emptyIterator()
                                                                                                                   : storeLayer.nodesGetFromInclusiveNumericIndexRangeSeek(
                                                                                                                           state,
                                                                                                                           index,
                                                                                                                           lower,
                                                                                                                           upper );
        PrimitiveLongIterator exactMatches = filterExactRangeMatches( state, index, committed, lower, includeLower, upper, includeUpper );
        return filterIndexStateChangesForRangeSeekByNumber( state, index, lower, includeLower, upper, includeUpper, exactMatches );
    }

    @Override
    public PrimitiveLongIterator nodesGetFromIndexRangeSeekByString( KernelStatement state, IndexDescriptor index, String lower, boolean includeLower, String upper, boolean includeUpper ) throws IndexNotFoundKernelException

    {
        PrimitiveLongIterator committed = storeLayer.nodesGetFromIndexRangeSeekByString( state, index, lower, includeLower, upper, includeUpper );
        return filterIndexStateChangesForRangeSeekByString( state, index, lower, includeLower, upper, includeUpper, committed );
    }

    @Override
    public PrimitiveLongIterator nodesGetFromIndexRangeSeekByPrefix( KernelStatement state, IndexDescriptor index, String prefix ) throws IndexNotFoundKernelException
    {
        PrimitiveLongIterator committed = storeLayer.nodesGetFromIndexRangeSeekByPrefix( state, index, prefix );
        return filterIndexStateChangesForRangeSeekByPrefix( state, index, prefix, committed );
    }

    @Override
    public PrimitiveLongIterator nodesGetFromIndexScan( KernelStatement state, IndexDescriptor index ) throws IndexNotFoundKernelException
    {
        PrimitiveLongIterator committed = storeLayer.nodesGetFromIndexScan( state, index );
        return filterIndexStateChangesForScanOrSeek( state, index, null, committed );
    }

    private PrimitiveLongIterator filterExactIndexMatches( final KernelStatement state, IndexDescriptor index, Object value, PrimitiveLongIterator committed )
    {
        return LookupFilter.exactIndexMatches( this, state, committed, index.getPropertyKeyId(), value );
    }

    private PrimitiveLongIterator filterExactRangeMatches( final KernelStatement state, IndexDescriptor index, PrimitiveLongIterator committed, Number lower, boolean includeLower, Number upper, boolean includeUpper )
    {
        return LookupFilter.exactRangeMatches( this, state, committed, index.getPropertyKeyId(), lower, includeLower, upper, includeUpper );
    }

    private PrimitiveLongIterator filterIndexStateChangesForScanOrSeek( KernelStatement state, IndexDescriptor index, Object value, PrimitiveLongIterator nodeIds )
    {
        if ( state.hasTxStateWithChanges() )
        {
            ReadableDiffSets<Long> labelPropertyChanges = state.txState().indexUpdatesForScanOrSeek( index, value );
            ReadableDiffSets<Long> nodes = state.txState().addedAndRemovedNodes();

            // Apply to actual index lookup
            return nodes.augmentWithRemovals( labelPropertyChanges.augment( nodeIds ) );
        }
        return nodeIds;
    }

    private PrimitiveLongIterator filterIndexStateChangesForRangeSeekByNumber( KernelStatement state, IndexDescriptor index, Number lower, boolean includeLower, Number upper, boolean includeUpper, PrimitiveLongIterator nodeIds )
    {
        if ( state.hasTxStateWithChanges() )
        {
            ReadableDiffSets<Long> labelPropertyChangesForNumber =
                    state.txState().indexUpdatesForRangeSeekByNumber( index, lower, includeLower, upper, includeUpper );
            ReadableDiffSets<Long> nodes = state.txState().addedAndRemovedNodes();

            // Apply to actual index lookup
            return nodes.augmentWithRemovals( labelPropertyChangesForNumber.augment( nodeIds ) );
        }
        return nodeIds;
    }

    private PrimitiveLongIterator filterIndexStateChangesForRangeSeekByString( KernelStatement state, IndexDescriptor index, String lower, boolean includeLower, String upper, boolean includeUpper, PrimitiveLongIterator nodeIds )
    {
        if ( state.hasTxStateWithChanges() )
        {
            ReadableDiffSets<Long> labelPropertyChangesForString =
                    state.txState().indexUpdatesForRangeSeekByString( index, lower, includeLower, upper, includeUpper );
            ReadableDiffSets<Long> nodes = state.txState().addedAndRemovedNodes();

            // Apply to actual index lookup
            return nodes.augmentWithRemovals( labelPropertyChangesForString.augment( nodeIds ) );
        }
        return nodeIds;
    }

    private PrimitiveLongIterator filterIndexStateChangesForRangeSeekByPrefix( KernelStatement state, IndexDescriptor index, String prefix, PrimitiveLongIterator nodeIds )
    {
        if ( state.hasTxStateWithChanges() )
        {
            ReadableDiffSets<Long> labelPropertyChangesForPrefix = state.txState().indexUpdatesForRangeSeekByPrefix( index, prefix );
            ReadableDiffSets<Long> nodes = state.txState().addedAndRemovedNodes();

            // Apply to actual index lookup
            return nodes.augmentWithRemovals( labelPropertyChangesForPrefix.augment( nodeIds ) );
        }
        return nodeIds;
    }

    @Override
    public Property nodeSetProperty( KernelStatement state, long nodeId, DefinedProperty property ) throws EntityNotFoundException
    {
        try ( Cursor<NodeItem> cursor = nodeCursorById( state, nodeId ) )
        {
            NodeItem node = cursor.get();
            Property existingProperty;
            try ( Cursor<PropertyItem> properties = node.property( property.propertyKeyId() ) )
            {
                if ( !properties.next() )
                {
                    legacyPropertyTrackers.nodeAddStoreProperty( node.id(), property );
                    existingProperty = Property.noProperty( property.propertyKeyId(), EntityType.NODE, node.id() );
                }
                else
                {
                    existingProperty = Property.property( properties.get().propertyKeyId(), properties.get().value() );
                    legacyPropertyTrackers.nodeChangeStoreProperty( node.id(), (DefinedProperty) existingProperty, property );
                }
            }

            state.txState().nodeDoReplaceProperty( node.id(), existingProperty, property );

            PrimitiveIntCollection labelIds = getLabels( node );

            indexesUpdateProperty( state,
                                   node.id(),
                                   labelIds,
                                   property.propertyKeyId(),
                                   existingProperty instanceof DefinedProperty ? (DefinedProperty) existingProperty : null,
                                   property );

            return existingProperty;
        }
    }

    @Override
    public Property relationshipSetProperty( KernelStatement state, long relationshipId, DefinedProperty property ) throws EntityNotFoundException
    {
        try ( Cursor<RelationshipItem> cursor = relationshipCursorById( state, relationshipId ) )
        {
            RelationshipItem relationship = cursor.get();
            Property existingProperty;
            try ( Cursor<PropertyItem> properties = relationship.property( property.propertyKeyId() ) )
            {
                if ( !properties.next() )
                {
                    legacyPropertyTrackers.relationshipAddStoreProperty( relationship.id(), property );
                    existingProperty = Property.noProperty( property.propertyKeyId(), EntityType.RELATIONSHIP, relationship.id() );
                }
                else
                {
                    existingProperty = Property.property( properties.get().propertyKeyId(), properties.get().value() );
                    legacyPropertyTrackers.relationshipChangeStoreProperty( relationship.id(), (DefinedProperty) existingProperty, property );
                }
            }

            state.txState().relationshipDoReplaceProperty( relationship.id(), existingProperty, property );
            return existingProperty;
        }
    }

    @Override
    public Property graphSetProperty( KernelStatement state, DefinedProperty property )
    {
        Object existingPropertyValue = graphGetProperty( state, property.propertyKeyId() );
        Property existingProperty = existingPropertyValue == null ? Property.noGraphProperty( property.propertyKeyId() )
                                                                  : Property.property( property.propertyKeyId(), existingPropertyValue );
        state.txState().graphDoReplaceProperty( existingProperty, property );
        return existingProperty;
    }

    @Override
    public Property nodeRemoveProperty( KernelStatement state, long nodeId, int propertyKeyId ) throws EntityNotFoundException
    {
        try ( Cursor<NodeItem> cursor = nodeCursorById( state, nodeId ) )
        {
            NodeItem node = cursor.get();
            PrimitiveIntCollection labelIds = getLabels( node );
            Property existingProperty;
            try ( Cursor<PropertyItem> properties = node.property( propertyKeyId ) )
            {
                if ( !properties.next() )
                {
                    existingProperty = Property.noProperty( propertyKeyId, EntityType.NODE, node.id() );
                }
                else
                {
                    existingProperty = Property.property( properties.get().propertyKeyId(), properties.get().value() );

                    legacyPropertyTrackers.nodeRemoveStoreProperty( node.id(), (DefinedProperty) existingProperty );
                    state.txState().nodeDoRemoveProperty( node.id(), (DefinedProperty) existingProperty );

                    indexesUpdateProperty( state, node.id(), labelIds, propertyKeyId, (DefinedProperty) existingProperty, null );
                }
            }
            return existingProperty;
        }
    }

    @Override
    public Property relationshipRemoveProperty( KernelStatement state, long relationshipId, int propertyKeyId ) throws EntityNotFoundException
    {
        try ( Cursor<RelationshipItem> cursor = relationshipCursorById( state, relationshipId ) )
        {
            RelationshipItem relationship = cursor.get();
            Property existingProperty;
            try ( Cursor<PropertyItem> properties = relationship.property( propertyKeyId ) )
            {
                if ( !properties.next() )
                {
                    existingProperty = Property.noProperty( propertyKeyId, EntityType.RELATIONSHIP, relationship.id() );
                }
                else
                {
                    existingProperty = Property.property( properties.get().propertyKeyId(), properties.get().value() );

                    legacyPropertyTrackers.relationshipRemoveStoreProperty( relationship.id(), (DefinedProperty) existingProperty );
                    state.txState().relationshipDoRemoveProperty( relationship.id(), (DefinedProperty) existingProperty );
                }
            }
            return existingProperty;
        }
    }

    @Override
    public Property graphRemoveProperty( KernelStatement state, int propertyKeyId )
    {
        Object existingPropertyValue = graphGetProperty( state, propertyKeyId );
        if ( existingPropertyValue != null )
        {
            DefinedProperty existingProperty = Property.property( propertyKeyId, existingPropertyValue );
            state.txState().graphDoRemoveProperty( existingProperty );
            return existingProperty;
        }
        else
        {
            return Property.noGraphProperty( propertyKeyId );
        }
    }

    private void indexesUpdateProperty( KernelStatement state, long nodeId, PrimitiveIntCollection labels, int propertyKey, DefinedProperty before, DefinedProperty after )
    {
        PrimitiveIntIterator labelIterator = labels.iterator();
        while ( labelIterator.hasNext() )
        {
            indexUpdateProperty( state, nodeId, labelIterator.next(), propertyKey, before, after );
        }
    }

    private void indexUpdateProperty( KernelStatement state, long nodeId, int labelId, int propertyKey, DefinedProperty before, DefinedProperty after )
    {
        IndexDescriptor descriptor = indexesGetForLabelAndPropertyKey( state, labelId, propertyKey );
        if ( descriptor != null )
        {
            state.txState().indexDoUpdateProperty( descriptor, nodeId, before, after );
        }
    }

    @Override
    public PrimitiveIntIterator graphGetPropertyKeys( KernelStatement state )
    {
        if ( state.hasTxStateWithChanges() )
        {
            return new PropertyKeyIdIterator( graphGetAllProperties( state ) );
        }

        return storeLayer.graphGetPropertyKeys( state );
    }

    @Override
    public boolean graphHasProperty( KernelStatement state, int propertyKeyId )
    {
        return graphGetProperty( state, propertyKeyId ) != null;
    }

    @Override
    public Object graphGetProperty( KernelStatement state, int propertyKeyId )
    {
        Iterator<DefinedProperty> properties = graphGetAllProperties( state );
        while ( properties.hasNext() )
        {
            DefinedProperty property = properties.next();
            if ( property.propertyKeyId() == propertyKeyId )
            {
                return property.value();
            }
        }
        return null;
    }

    private Iterator<DefinedProperty> graphGetAllProperties( KernelStatement state )
    {
        if ( state.hasTxStateWithChanges() )
        {
            return state.txState().augmentGraphProperties( storeLayer.graphGetAllProperties() );
        }

        return storeLayer.graphGetAllProperties();
    }

    @Override
    public long countsForNode( KernelStatement statement, int labelId )
    {
        return storeLayer.countsForNode( labelId );
    }

    @Override
    public long countsForRelationship( KernelStatement statement, int startLabelId, int typeId, int endLabelId )
    {
        return storeLayer.countsForRelationship( startLabelId, typeId, endLabelId );
    }

    @Override
    public long indexSize( KernelStatement statement, IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        return storeLayer.indexSize( descriptor );
    }

    @Override
    public double indexUniqueValuesPercentage( KernelStatement statement, IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        return storeLayer.indexUniqueValuesPercentage( descriptor );
    }

    //
    // Methods that delegate directly to storage
    //

    @Override
    public Long indexGetOwningUniquenessConstraintId( KernelStatement state, IndexDescriptor index ) throws SchemaRuleNotFoundException
    {
        return storeLayer.indexGetOwningUniquenessConstraintId( index );
    }

    @Override
    public long indexGetCommittedId( KernelStatement state, IndexDescriptor index, SchemaStorage.IndexRuleKind kind ) throws SchemaRuleNotFoundException
    {
        return storeLayer.indexGetCommittedId( index, kind );
    }

    @Override
    public String indexGetFailure( Statement state, IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        return storeLayer.indexGetFailure( descriptor );
    }

    @Override
    public int labelGetForName( Statement state, String labelName )
    {
        return storeLayer.labelGetForName( labelName );
    }

    @Override
    public String labelGetName( Statement state, int labelId ) throws LabelNotFoundKernelException
    {
        return storeLayer.labelGetName( labelId );
    }

    @Override
    public int propertyKeyGetForName( Statement state, String propertyKeyName )
    {
        return storeLayer.propertyKeyGetForName( propertyKeyName );
    }

    @Override
    public String propertyKeyGetName( Statement state, int propertyKeyId ) throws PropertyKeyIdNotFoundKernelException
    {
        return storeLayer.propertyKeyGetName( propertyKeyId );
    }

    @Override
    public Iterator<Token> propertyKeyGetAllTokens( Statement state )
    {
        return storeLayer.propertyKeyGetAllTokens();
    }

    @Override
    public Iterator<Token> labelsGetAllTokens( Statement state )
    {
        return storeLayer.labelsGetAllTokens();
    }

    @Override
    public int relationshipTypeGetForName( Statement state, String relationshipTypeName )
    {
        return storeLayer.relationshipTypeGetForName( relationshipTypeName );
    }

    @Override
    public String relationshipTypeGetName( Statement state, int relationshipTypeId ) throws RelationshipTypeIdNotFoundKernelException
    {
        return storeLayer.relationshipTypeGetName( relationshipTypeId );
    }

    @Override
    public int labelGetOrCreateForName( Statement state, String labelName ) throws IllegalTokenNameException, TooManyLabelsException
    {
        return storeLayer.labelGetOrCreateForName( labelName );
    }

    @Override
    public int propertyKeyGetOrCreateForName( Statement state, String propertyKeyName ) throws IllegalTokenNameException
    {
        return storeLayer.propertyKeyGetOrCreateForName( propertyKeyName );
    }

    @Override
    public int relationshipTypeGetOrCreateForName( Statement state, String relationshipTypeName ) throws IllegalTokenNameException
    {
        return storeLayer.relationshipTypeGetOrCreateForName( relationshipTypeName );
    }

    @Override
    public void labelCreateForName( KernelStatement state, String labelName, int id ) throws IllegalTokenNameException, TooManyLabelsException
    {
        state.txState().labelDoCreateForName( labelName, id );
    }

    @Override
    public void propertyKeyCreateForName( KernelStatement state, String propertyKeyName, int id ) throws IllegalTokenNameException
    {
        state.txState().propertyKeyDoCreateForName( propertyKeyName, id );
    }

    @Override
    public void relationshipTypeCreateForName( KernelStatement state, String relationshipTypeName, int id ) throws IllegalTokenNameException
    {
        state.txState().relationshipTypeDoCreateForName( relationshipTypeName, id );
    }

    private static int[] deduplicate( int[] types )
    {
        int unique = 0;
        for ( int i = 0; i < types.length; i++ )
        {
            int type = types[i];
            for ( int j = 0; j < unique; j++ )
            {
                if ( type == types[j] )
                {
                    type = -1; // signal that this relationship is not unique
                    break; // we will not find more than one conflict
                }
            }
            if ( type != -1 )
            { // this has to be done outside the inner loop, otherwise we'd never accept a single one...
                types[unique++] = types[i];
            }
        }
        if ( unique < types.length )
        {
            types = Arrays.copyOf( types, unique );
        }
        return types;
    }

    // <Legacy index>
    @Override
    public <EXCEPTION extends Exception> void relationshipVisit( KernelStatement statement, long relId, RelationshipVisitor<EXCEPTION> visitor ) throws EntityNotFoundException, EXCEPTION
    {
        if ( statement.hasTxStateWithChanges() )
        {
            if ( statement.txState().relationshipVisit( relId, visitor ) )
            {
                return;
            }
        }
        storeLayer.relationshipVisit( relId, visitor );
    }

    @Override
    public LegacyIndexHits nodeLegacyIndexGet( KernelStatement statement, String indexName, String key, Object value ) throws LegacyIndexNotFoundKernelException
    {
        return statement.legacyIndexTxState().nodeChanges( indexName ).get( key, value );
    }

    @Override
    public LegacyIndexHits nodeLegacyIndexQuery( KernelStatement statement, String indexName, String key, Object queryOrQueryObject ) throws LegacyIndexNotFoundKernelException
    {
        return statement.legacyIndexTxState().nodeChanges( indexName ).query( key, queryOrQueryObject );
    }

    @Override
    public LegacyIndexHits nodeLegacyIndexQuery( KernelStatement statement, String indexName, Object queryOrQueryObject ) throws LegacyIndexNotFoundKernelException
    {
        return statement.legacyIndexTxState().nodeChanges( indexName ).query( queryOrQueryObject );
    }

    @Override
    public LegacyIndexHits relationshipLegacyIndexGet( KernelStatement statement, String indexName, String key, Object value, long startNode, long endNode ) throws LegacyIndexNotFoundKernelException
    {
        LegacyIndex index = statement.legacyIndexTxState().relationshipChanges( indexName );
        if ( startNode != -1 || endNode != -1 )
        {
            return index.get( key, value, startNode, endNode );
        }
        return index.get( key, value );
    }

    @Override
    public LegacyIndexHits relationshipLegacyIndexQuery( KernelStatement statement, String indexName, String key, Object queryOrQueryObject, long startNode, long endNode ) throws LegacyIndexNotFoundKernelException
    {
        LegacyIndex index = statement.legacyIndexTxState().relationshipChanges( indexName );
        if ( startNode != -1 || endNode != -1 )
        {
            return index.query( key, queryOrQueryObject, startNode, endNode );
        }
        return index.query( key, queryOrQueryObject );
    }

    @Override
    public LegacyIndexHits relationshipLegacyIndexQuery( KernelStatement statement, String indexName, Object queryOrQueryObject, long startNode, long endNode ) throws LegacyIndexNotFoundKernelException
    {
        LegacyIndex index = statement.legacyIndexTxState().relationshipChanges( indexName );
        if ( startNode != -1 || endNode != -1 )
        {
            return index.query( queryOrQueryObject, startNode, endNode );
        }
        return index.query( queryOrQueryObject );
    }

    @Override
    public void nodeLegacyIndexCreateLazily( KernelStatement statement, String indexName, Map<String,String> customConfig )
    {
        legacyIndexStore.getOrCreateNodeIndexConfig( indexName, customConfig );
    }

    @Override
    public void nodeLegacyIndexCreate( KernelStatement statement, String indexName, Map<String,String> customConfig )
    {
        statement.txState().nodeLegacyIndexDoCreate( indexName, customConfig );
    }

    @Override
    public void relationshipLegacyIndexCreateLazily( KernelStatement statement, String indexName, Map<String,String> customConfig )
    {
        legacyIndexStore.getOrCreateRelationshipIndexConfig( indexName, customConfig );
    }

    @Override
    public void relationshipLegacyIndexCreate( KernelStatement statement, String indexName, Map<String,String> customConfig )
    {
        statement.txState().relationshipLegacyIndexDoCreate( indexName, customConfig );
    }

    @Override
    public void nodeAddToLegacyIndex( KernelStatement statement, String indexName, long node, String key, Object value ) throws LegacyIndexNotFoundKernelException
    {
        statement.legacyIndexTxState().nodeChanges( indexName ).addNode( node, key, value );
    }

    @Override
    public void nodeRemoveFromLegacyIndex( KernelStatement statement, String indexName, long node, String key, Object value ) throws LegacyIndexNotFoundKernelException
    {
        statement.legacyIndexTxState().nodeChanges( indexName ).remove( node, key, value );
    }

    @Override
    public void nodeRemoveFromLegacyIndex( KernelStatement statement, String indexName, long node, String key ) throws LegacyIndexNotFoundKernelException
    {
        statement.legacyIndexTxState().nodeChanges( indexName ).remove( node, key );
    }

    @Override
    public void nodeRemoveFromLegacyIndex( KernelStatement statement, String indexName, long node ) throws LegacyIndexNotFoundKernelException
    {
        statement.legacyIndexTxState().nodeChanges( indexName ).remove( node );
    }

    @Override
    public void relationshipAddToLegacyIndex( final KernelStatement statement, final String indexName, final long relationship, final String key, final Object value ) throws EntityNotFoundException, LegacyIndexNotFoundKernelException
    {
        relationshipVisit( statement, relationship, new RelationshipVisitor<LegacyIndexNotFoundKernelException>()
        {
            @Override
            public void visit( long relId, int type, long startNode, long endNode ) throws LegacyIndexNotFoundKernelException
            {
                statement.legacyIndexTxState().relationshipChanges( indexName ).addRelationship( relationship, key, value, startNode, endNode );
            }
        } );
    }

    @Override
    public void relationshipRemoveFromLegacyIndex( final KernelStatement statement, final String indexName, long relationship, final String key, final Object value ) throws LegacyIndexNotFoundKernelException, EntityNotFoundException
    {
        try
        {
            relationshipVisit( statement, relationship, new RelationshipVisitor<LegacyIndexNotFoundKernelException>()
            {
                @Override
                public void visit( long relId, int type, long startNode, long endNode ) throws LegacyIndexNotFoundKernelException
                {
                    statement.legacyIndexTxState().relationshipChanges( indexName ).removeRelationship( relId, key, value, startNode, endNode );
                }
            } );
        }
        catch ( EntityNotFoundException e )
        {   // Apparently this is OK
        }
    }

    @Override
    public void relationshipRemoveFromLegacyIndex( final KernelStatement statement, final String indexName, long relationship, final String key ) throws EntityNotFoundException, LegacyIndexNotFoundKernelException
    {
        try
        {
            relationshipVisit( statement, relationship, new RelationshipVisitor<LegacyIndexNotFoundKernelException>()
            {
                @Override
                public void visit( long relId, int type, long startNode, long endNode ) throws LegacyIndexNotFoundKernelException
                {
                    statement.legacyIndexTxState().relationshipChanges( indexName ).removeRelationship( relId, key, startNode, endNode );
                }
            } );
        }
        catch ( EntityNotFoundException e )
        {   // Apparently this is OK
        }
    }

    @Override
    public void relationshipRemoveFromLegacyIndex( final KernelStatement statement, final String indexName, long relationship ) throws LegacyIndexNotFoundKernelException, EntityNotFoundException
    {
        try
        {
            relationshipVisit( statement, relationship, new RelationshipVisitor<LegacyIndexNotFoundKernelException>()
            {
                @Override
                public void visit( long relId, int type, long startNode, long endNode ) throws LegacyIndexNotFoundKernelException
                {
                    statement.legacyIndexTxState().relationshipChanges( indexName ).removeRelationship( relId, startNode, endNode );
                }
            } );
        }
        catch ( EntityNotFoundException e )
        {
            // This is a special case which is still OK. This method is called lazily where deleted relationships
            // that still are referenced by a legacy index will be added for removal in this transaction.
            // Ideally we'd want to include start/end node too, but we can't since the relationship doesn't exist.
            // So we do the "normal" remove call on the legacy index transaction changes. The downside is that
            // Some queries on this transaction state that include start/end nodes might produce invalid results.
            statement.legacyIndexTxState().relationshipChanges( indexName ).remove( relationship );
        }
    }

    @Override
    public void nodeLegacyIndexDrop( KernelStatement statement, String indexName ) throws LegacyIndexNotFoundKernelException
    {
        statement.legacyIndexTxState().nodeChanges( indexName ).drop();
        statement.legacyIndexTxState().deleteIndex( IndexEntityType.Node, indexName );
    }

    @Override
    public void relationshipLegacyIndexDrop( KernelStatement statement, String indexName ) throws LegacyIndexNotFoundKernelException
    {
        statement.legacyIndexTxState().relationshipChanges( indexName ).drop();
        statement.legacyIndexTxState().deleteIndex( IndexEntityType.Relationship, indexName );
    }

    @Override
    public String nodeLegacyIndexSetConfiguration( KernelStatement statement, String indexName, String key, String value ) throws LegacyIndexNotFoundKernelException
    {
        return legacyIndexStore.setNodeIndexConfiguration( indexName, key, value );
    }

    @Override
    public String relationshipLegacyIndexSetConfiguration( KernelStatement statement, String indexName, String key, String value ) throws LegacyIndexNotFoundKernelException
    {
        return legacyIndexStore.setRelationshipIndexConfiguration( indexName, key, value );
    }

    @Override
    public String nodeLegacyIndexRemoveConfiguration( KernelStatement statement, String indexName, String key ) throws LegacyIndexNotFoundKernelException
    {
        return legacyIndexStore.removeNodeIndexConfiguration( indexName, key );
    }

    @Override
    public String relationshipLegacyIndexRemoveConfiguration( KernelStatement statement, String indexName, String key ) throws LegacyIndexNotFoundKernelException
    {
        return legacyIndexStore.removeRelationshipIndexConfiguration( indexName, key );
    }

    @Override
    public Map<String,String> nodeLegacyIndexGetConfiguration( KernelStatement statement, String indexName ) throws LegacyIndexNotFoundKernelException
    {
        return legacyIndexStore.getNodeIndexConfiguration( indexName );
    }

    @Override
    public Map<String,String> relationshipLegacyIndexGetConfiguration( KernelStatement statement, String indexName ) throws LegacyIndexNotFoundKernelException
    {
        return legacyIndexStore.getRelationshipIndexConfiguration( indexName );
    }

    @Override
    public String[] nodeLegacyIndexesGetAll( KernelStatement statement )
    {
        return legacyIndexStore.getAllNodeIndexNames();
    }

    @Override
    public String[] relationshipLegacyIndexesGetAll( KernelStatement statement )
    {
        return legacyIndexStore.getAllRelationshipIndexNames();
    }
    // </Legacy index>

    private PrimitiveIntCollection getLabels( NodeItem node )
    {
        PrimitiveIntStack labelIds = new PrimitiveIntStack();
        try ( Cursor<LabelItem> labels = node.labels() )
        {
            while ( labels.next() )
            {
                labelIds.push( labels.get().getAsInt() );
            }
        }
        return labelIds;
    }
}

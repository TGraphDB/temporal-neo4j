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

import org.apache.commons.lang3.mutable.MutableInt;

import java.util.Iterator;

import org.neo4j.function.Consumer;
import org.neo4j.function.Function;
import org.neo4j.graphdb.TGraphNoImplementationException;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.constraints.NodePropertyConstraint;
import org.neo4j.kernel.api.constraints.NodePropertyExistenceConstraint;
import org.neo4j.kernel.api.constraints.PropertyConstraint;
import org.neo4j.kernel.api.constraints.RelationshipPropertyConstraint;
import org.neo4j.kernel.api.constraints.RelationshipPropertyExistenceConstraint;
import org.neo4j.kernel.api.constraints.UniquenessConstraint;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.api.exceptions.ProcedureException;
import org.neo4j.kernel.api.exceptions.PropertyNotFoundException;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.schema.AlreadyConstrainedException;
import org.neo4j.kernel.api.exceptions.schema.AlreadyIndexedException;
import org.neo4j.kernel.api.exceptions.schema.ConstraintValidationKernelException;
import org.neo4j.kernel.api.exceptions.schema.CreateConstraintFailureException;
import org.neo4j.kernel.api.exceptions.schema.DropConstraintFailureException;
import org.neo4j.kernel.api.exceptions.schema.DropIndexFailureException;
import org.neo4j.kernel.api.exceptions.schema.ProcedureConstraintViolation;
import org.neo4j.kernel.api.exceptions.schema.SchemaRuleNotFoundException;
import org.neo4j.kernel.api.index.IndexDescriptor;
import org.neo4j.kernel.api.index.InternalIndexState;
import org.neo4j.kernel.api.procedures.ProcedureDescriptor;
import org.neo4j.kernel.api.procedures.ProcedureSignature;
import org.neo4j.kernel.api.procedures.ProcedureSignature.ProcedureName;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.api.properties.Property;
import org.neo4j.kernel.impl.api.operations.EntityReadOperations;
import org.neo4j.kernel.impl.api.operations.EntityWriteOperations;
import org.neo4j.kernel.impl.api.operations.LockOperations;
import org.neo4j.kernel.impl.api.operations.SchemaReadOperations;
import org.neo4j.kernel.impl.api.operations.SchemaStateOperations;
import org.neo4j.kernel.impl.api.operations.SchemaWriteOperations;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.locking.ResourceTypes;
import org.neo4j.kernel.impl.store.SchemaStorage;
import org.neo4j.temporal.TemporalPropertyWriteOperation;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static org.neo4j.kernel.impl.locking.ResourceTypes.*;

public class LockingStatementOperations implements
        EntityWriteOperations,
        SchemaReadOperations,
        SchemaWriteOperations,
        SchemaStateOperations,
        LockOperations
{
    private final EntityReadOperations entityReadDelegate;
    private final EntityWriteOperations entityWriteDelegate;
    private final SchemaReadOperations schemaReadDelegate;
    private final SchemaWriteOperations schemaWriteDelegate;
    private final SchemaStateOperations schemaStateDelegate;

    public LockingStatementOperations(
            EntityReadOperations entityReadDelegate,
            EntityWriteOperations entityWriteDelegate,
            SchemaReadOperations schemaReadDelegate,
            SchemaWriteOperations schemaWriteDelegate,
            SchemaStateOperations schemaStateDelegate )
    {
        this.entityReadDelegate = entityReadDelegate;
        this.entityWriteDelegate = entityWriteDelegate;
        this.schemaReadDelegate = schemaReadDelegate;
        this.schemaWriteDelegate = schemaWriteDelegate;
        this.schemaStateDelegate = schemaStateDelegate;
    }

    @Override
    public boolean nodeAddLabel( KernelStatement state, long nodeId, int labelId )
            throws ConstraintValidationKernelException, EntityNotFoundException
    {
        // TODO (BBC, 22/11/13):
        // In order to enforce constraints we need to check whether this change violates constraints; we therefore need
        // the schema lock to ensure that our view of constraints is consistent.
        //
        // We would like this locking to be done naturally when ConstraintEnforcingEntityOperations calls
        // SchemaReadOperations#constraintsGetForLabel, but the SchemaReadOperations object that
        // ConstraintEnforcingEntityOperations has a reference to does not lock because of the way the cake is
        // constructed.
        //
        // It would be cleaner if the schema and data cakes were separated so that the SchemaReadOperations object used
        // by ConstraintEnforcingEntityOperations included the full cake, with locking included.
        acquireSharedSchemaLock( state );

        acquireExclusiveNodeLock( state, nodeId );
        state.assertOpen();

        return entityWriteDelegate.nodeAddLabel( state, nodeId, labelId );
    }

    @Override
    public boolean nodeRemoveLabel( KernelStatement state, long nodeId, int labelId ) throws EntityNotFoundException
    {
        acquireExclusiveNodeLock( state, nodeId );
        state.assertOpen();
        return entityWriteDelegate.nodeRemoveLabel( state, nodeId, labelId );
    }

    @Override
    public IndexDescriptor temporalIndexCreate( KernelStatement state, int type, int propertyKey, int from, int to )
            throws AlreadyIndexedException, AlreadyConstrainedException
    {
        acquireExclusiveSchemaLock( state );
        state.assertOpen();
        return schemaWriteDelegate.temporalIndexCreate( state, type, propertyKey, from, to );
    }

    @Override
    public IndexDescriptor indexCreate( KernelStatement state, int labelId, int propertyKey )
            throws AlreadyIndexedException, AlreadyConstrainedException
    {
        acquireExclusiveSchemaLock( state );
        state.assertOpen();
        return schemaWriteDelegate.indexCreate( state, labelId, propertyKey );
    }

    @Override
    public void indexDrop( KernelStatement state, IndexDescriptor descriptor ) throws DropIndexFailureException
    {
        acquireExclusiveSchemaLock( state );
        state.assertOpen();
        schemaWriteDelegate.indexDrop( state, descriptor );
    }

    @Override
    public void uniqueIndexDrop( KernelStatement state, IndexDescriptor descriptor ) throws DropIndexFailureException
    {
        acquireExclusiveSchemaLock( state );
        state.assertOpen();
        schemaWriteDelegate.uniqueIndexDrop( state, descriptor );
    }

    @Override
    public <K, V> V schemaStateGetOrCreate( KernelStatement state, K key, Function<K,V> creator )
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaStateDelegate.schemaStateGetOrCreate( state, key, creator );
    }

    @Override
    public <K> boolean schemaStateContains( KernelStatement state, K key )
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaStateDelegate.schemaStateContains( state, key );
    }

    @Override
    public void schemaStateFlush( KernelStatement state )
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        schemaStateDelegate.schemaStateFlush( state );
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetForLabel( KernelStatement state, int labelId )
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.indexesGetForLabel( state, labelId );
    }

    @Override
    public IndexDescriptor indexesGetForLabelAndPropertyKey( KernelStatement state, int labelId, int propertyKey )
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.indexesGetForLabelAndPropertyKey( state, labelId, propertyKey );
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetAll( KernelStatement state )
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.indexesGetAll( state );
    }

    @Override
    public InternalIndexState indexGetState( KernelStatement state, IndexDescriptor descriptor )
            throws IndexNotFoundKernelException
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.indexGetState( state, descriptor );
    }

    @Override
    public long indexSize( KernelStatement state, IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.indexSize( state, descriptor );
    }

    @Override
    public double indexUniqueValuesPercentage( KernelStatement state,
            IndexDescriptor descriptor ) throws IndexNotFoundKernelException
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.indexUniqueValuesPercentage( state, descriptor );
    }

    @Override
    public Long indexGetOwningUniquenessConstraintId( KernelStatement state, IndexDescriptor index ) throws SchemaRuleNotFoundException
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.indexGetOwningUniquenessConstraintId( state, index );
    }

    @Override
    public long indexGetCommittedId( KernelStatement state, IndexDescriptor index, SchemaStorage.IndexRuleKind kind )
            throws SchemaRuleNotFoundException
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.indexGetCommittedId( state, index, kind );
    }

    @Override
    public Iterator<IndexDescriptor> uniqueIndexesGetForLabel( KernelStatement state, int labelId )
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.uniqueIndexesGetForLabel( state, labelId );
    }

    @Override
    public Iterator<IndexDescriptor> uniqueIndexesGetAll( KernelStatement state )
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.uniqueIndexesGetAll( state );
    }

    @Override
    public void nodeDelete( KernelStatement state, long nodeId ) throws EntityNotFoundException
    {
        acquireExclusiveNodeLock( state, nodeId );
        state.assertOpen();
        entityWriteDelegate.nodeDelete( state, nodeId );
    }

    @Override
    public int nodeDetachDelete( final KernelStatement state, final long nodeId ) throws EntityNotFoundException
    {
        final MutableInt count = new MutableInt(  );
        TwoPhaseNodeForRelationshipLocking locking = new TwoPhaseNodeForRelationshipLocking( entityReadDelegate,
                new Consumer<Long>()
                {
                    @Override
                    public void accept( Long relId )
                    {
                        state.assertOpen();
                        try
                        {
                            entityWriteDelegate.relationshipDelete( state, relId );
                            count.increment();
                        }
                        catch ( EntityNotFoundException e )
                        {
                            // it doesn't matter...
                        }
                    }
                } );

        locking.lockAllNodesAndConsumeRelationships( nodeId, state );
        state.assertOpen();
        entityWriteDelegate.nodeDetachDelete( state, nodeId );
        return count.intValue();
    }

    @Override
    public long nodeCreate( KernelStatement statement )
    {
        return entityWriteDelegate.nodeCreate( statement );
    }

    @Override
    public void nodeSetTemporalProperty(KernelStatement statement, TemporalPropertyWriteOperation operation) throws EntityNotFoundException, ConstraintValidationKernelException
    {
        //FIXME: TGraph lock naive version!
//        acquireShared(statement, NODE, operation.getEntityId());
//        statement.locks().optimistic().acquireTemporalPropExclusive(NODE_TEMPORAL_PROP, nodeId, propertyKeyId, time);
        acquireExclusive( statement, NODE, operation.getEntityId() );
        entityWriteDelegate.nodeSetTemporalProperty(statement, operation);
    }

    @Override
    public void relationshipSetTemporalProperty(KernelStatement statement, TemporalPropertyWriteOperation operation) throws EntityNotFoundException, ConstraintValidationKernelException
    {
        //FIXME: TGraph lock naive version!
//        acquireShared(statement, RELATIONSHIP, operation.getEntityId());
//        statement.locks().optimistic().acquireTemporalPropExclusive(REL_TEMPORAL_PROP, );
        acquireExclusive( statement, RELATIONSHIP, operation.getEntityId() );
        entityWriteDelegate.relationshipSetTemporalProperty(statement, operation);
    }

    @Override
    public long relationshipCreate( KernelStatement state,
            int relationshipTypeId,
            long startNodeId,
            long endNodeId )
            throws EntityNotFoundException
    {
        acquireSharedSchemaLock( state );
        lockRelationshipNodes( state, startNodeId, endNodeId );
        return entityWriteDelegate.relationshipCreate( state, relationshipTypeId, startNodeId, endNodeId );
    }

    @Override
    public void relationshipDelete( final KernelStatement state, long relationshipId ) throws EntityNotFoundException
    {
        entityReadDelegate.relationshipVisit(state, relationshipId, new RelationshipVisitor<RuntimeException>() {
            @Override
            public void visit(long relId, int type, long startNode, long endNode) {
                lockRelationshipNodes(state, startNode, endNode);
            }
        });
        acquireExclusiveRelationshipLock( state, relationshipId );
        state.assertOpen();
        entityWriteDelegate.relationshipDelete(state, relationshipId);
    }

    private void lockRelationshipNodes( KernelStatement state, long startNodeId, long endNodeId )
    {
        // Order the locks to lower the risk of deadlocks with other threads creating/deleting rels concurrently
        acquireExclusiveNodeLock( state, min( startNodeId, endNodeId ) );
        if ( startNodeId != endNodeId )
        {
            acquireExclusiveNodeLock( state, max( startNodeId, endNodeId ) );
        }
    }

    @Override
    public UniquenessConstraint uniquePropertyConstraintCreate( KernelStatement state, int labelId, int propertyKeyId )
            throws CreateConstraintFailureException, AlreadyConstrainedException, AlreadyIndexedException
    {
        acquireExclusiveSchemaLock( state );
        state.assertOpen();
        return schemaWriteDelegate.uniquePropertyConstraintCreate( state, labelId, propertyKeyId );
    }

    @Override
    public NodePropertyExistenceConstraint nodePropertyExistenceConstraintCreate( KernelStatement state, int labelId,
            int propertyKeyId ) throws AlreadyConstrainedException, CreateConstraintFailureException
    {
        acquireExclusiveSchemaLock( state );
        state.assertOpen();
        return schemaWriteDelegate.nodePropertyExistenceConstraintCreate( state, labelId, propertyKeyId );
    }

    @Override
    public RelationshipPropertyExistenceConstraint relationshipPropertyExistenceConstraintCreate( KernelStatement state,
            int relTypeId, int propertyKeyId ) throws AlreadyConstrainedException, CreateConstraintFailureException
    {
        acquireExclusiveSchemaLock( state );
        state.assertOpen();
        return schemaWriteDelegate.relationshipPropertyExistenceConstraintCreate( state, relTypeId, propertyKeyId );
    }

    @Override
    public Iterator<NodePropertyConstraint> constraintsGetForLabelAndPropertyKey( KernelStatement state,
            int labelId,
            int propertyKeyId )
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.constraintsGetForLabelAndPropertyKey( state, labelId, propertyKeyId );
    }

    @Override
    public Iterator<NodePropertyConstraint> constraintsGetForLabel( KernelStatement state, int labelId )
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.constraintsGetForLabel( state, labelId );
    }

    @Override
    public Iterator<RelationshipPropertyConstraint> constraintsGetForRelationshipTypeAndPropertyKey(
            KernelStatement state,
            int relTypeId, int propertyKeyId )
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.constraintsGetForRelationshipTypeAndPropertyKey( state, relTypeId, propertyKeyId );
    }

    @Override
    public Iterator<RelationshipPropertyConstraint> constraintsGetForRelationshipType( KernelStatement state,
            int typeId )
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.constraintsGetForRelationshipType( state, typeId );
    }

    @Override
    public Iterator<PropertyConstraint> constraintsGetAll( KernelStatement state )
    {
        acquireSharedSchemaLock( state );
        state.assertOpen();
        return schemaReadDelegate.constraintsGetAll( state );
    }

    @Override
    public void constraintDrop( KernelStatement state, NodePropertyConstraint constraint )
            throws DropConstraintFailureException
    {
        acquireExclusiveSchemaLock( state );
        state.assertOpen();
        schemaWriteDelegate.constraintDrop( state, constraint );
    }

    @Override
    public void constraintDrop( KernelStatement state, RelationshipPropertyConstraint constraint )
            throws DropConstraintFailureException
    {
        acquireExclusiveSchemaLock( state );
        state.assertOpen();
        schemaWriteDelegate.constraintDrop( state, constraint );
    }

    @Override
    public void procedureCreate( KernelStatement state, ProcedureSignature signature, String language, String code )
            throws ProcedureException, ProcedureConstraintViolation
    {
        // TODO: Document locking logic
        // In order to keep other processes from creating procedures with conflicting names, we lock the procedure
        // name. We don't exclusively lock the schema, since creating a new procedure will not influence any running
        // operation.
        state.locks().optimistic().acquireExclusive( PROCEDURE, procedureResourceId( signature.name() ) );
        schemaWriteDelegate.procedureCreate( state, signature, language, code );
    }

    @Override
    public void procedureDrop( KernelStatement state, ProcedureName name ) throws ProcedureConstraintViolation, ProcedureException
    {
        acquireExclusiveSchemaLock( state );
        state.locks().optimistic().acquireExclusive( PROCEDURE, procedureResourceId( name ) );
        schemaWriteDelegate.procedureDrop( state, name );
    }

    @Override
    public Iterator<ProcedureDescriptor> proceduresGetAll( KernelStatement statement )
    {
        return schemaReadDelegate.proceduresGetAll( statement );
    }

    @Override
    public ProcedureDescriptor procedureGet( KernelStatement statement, ProcedureName signature ) throws ProcedureException
    {
        return schemaReadDelegate.procedureGet( statement, signature );
    }

    @Override
    public Property nodeSetProperty( KernelStatement state, long nodeId, DefinedProperty property )
            throws ConstraintValidationKernelException, EntityNotFoundException
    {
        // TODO (BBC, 22/11/13):
        // In order to enforce constraints we need to check whether this change violates constraints; we therefore need
        // the schema lock to ensure that our view of constraints is consistent.
        //
        // We would like this locking to be done naturally when ConstraintEnforcingEntityOperations calls
        // SchemaReadOperations#constraintsGetForLabel, but the SchemaReadOperations object that
        // ConstraintEnforcingEntityOperations has a reference to does not lock because of the way the cake is
        // constructed.
        //
        // It would be cleaner if the schema and data cakes were separated so that the SchemaReadOperations object used
        // by ConstraintEnforcingEntityOperations included the full cake, with locking included.
        acquireSharedSchemaLock( state );

        acquireExclusiveNodeLock( state, nodeId );
        state.assertOpen();
        return entityWriteDelegate.nodeSetProperty( state, nodeId, property );
    }

    @Override
    public Property nodeRemoveProperty( KernelStatement state, long nodeId, int propertyKeyId )
            throws EntityNotFoundException
    {
        acquireExclusiveNodeLock( state, nodeId );
        state.assertOpen();
        return entityWriteDelegate.nodeRemoveProperty( state, nodeId, propertyKeyId );
    }

    @Override
    public Property relationshipSetProperty( KernelStatement state,
            long relationshipId,
            DefinedProperty property ) throws EntityNotFoundException
    {
        acquireExclusiveRelationshipLock( state, relationshipId );
        state.assertOpen();
        return entityWriteDelegate.relationshipSetProperty( state, relationshipId, property );
    }

    @Override
    public Property relationshipRemoveProperty( KernelStatement state,
            long relationshipId,
            int propertyKeyId ) throws EntityNotFoundException
    {
        acquireExclusiveRelationshipLock( state, relationshipId );
        state.assertOpen();
        return entityWriteDelegate.relationshipRemoveProperty( state, relationshipId, propertyKeyId );
    }

    @Override
    public Property graphSetProperty( KernelStatement state, DefinedProperty property )
    {
        state.locks().optimistic().acquireExclusive( ResourceTypes.GRAPH_PROPS, ResourceTypes.graphPropertyResource() );
        state.assertOpen();
        return entityWriteDelegate.graphSetProperty( state, property );
    }

    @Override
    public Property graphRemoveProperty( KernelStatement state, int propertyKeyId )
    {
        state.locks().optimistic().acquireExclusive( ResourceTypes.GRAPH_PROPS, ResourceTypes.graphPropertyResource() );
        state.assertOpen();
        return entityWriteDelegate.graphRemoveProperty( state, propertyKeyId );
    }

    @Override
    public void acquireExclusive( KernelStatement state, Locks.ResourceType resourceType, long resourceId )
    {
        state.locks().pessimistic().acquireExclusive( resourceType, resourceId );
        state.assertOpen();
    }

    @Override
    public void acquireShared(KernelStatement state, Locks.ResourceType resourceType, long resourceId )
    {
        state.locks().pessimistic().acquireShared( resourceType, resourceId );
        state.assertOpen();
    }

    @Override
    public void releaseExclusive( KernelStatement state, Locks.ResourceType type, long resourceId )
    {
        state.locks().pessimistic().releaseExclusive( type, resourceId );
        state.assertOpen();
    }

    @Override
    public void releaseShared( KernelStatement state, Locks.ResourceType type, long resourceId )
    {
        state.locks().pessimistic().releaseShared( type, resourceId );
        state.assertOpen();
    }

    @Override
    public void acquireTemporalExclusive(KernelStatement state, Locks.ResourceType resourceType, long resourceId, int propertyKeyId, int time)
    {
        if(resourceType.equals( ResourceTypes.NODE ) )
        {
            state.locks().optimistic().acquireShared(ResourceTypes.NODE, resourceId);
        }else
        {
            state.locks().optimistic().acquireShared(ResourceTypes.RELATIONSHIP, resourceId);
        }
//        state.locks().optimistic().acquireTemporalPropExclusive( resourceType, resourceId, propertyKeyId, time );

    }

    @Override
    public void acquireTemporalShared(KernelStatement state, Locks.ResourceType resourceType, long resourceId, int propertyKeyId, int start, int end)
    {
        if(resourceType.equals( ResourceTypes.NODE ) )
        {
            state.locks().optimistic().acquireShared(ResourceTypes.NODE, resourceId);
        }else
        {
            state.locks().optimistic().acquireShared(ResourceTypes.RELATIONSHIP, resourceId);
        }
//        state.locks().optimistic().acquireTemporalPropShared( resourceType, resourceId, propertyKeyId, start, end );
    }

    @Override
    public void releaseTemporalExclusive(KernelStatement statement, Locks.ResourceType type, long id, int propertyKeyId, int time)
    {
        statement.locks().pessimistic().releaseTemporalPropExclusive( type, id, propertyKeyId, time );
        if(type.equals( ResourceTypes.NODE_TEMPORAL_PROP ) )
            statement.locks().pessimistic().releaseShared( ResourceTypes.NODE, id );
        else
            statement.locks().pessimistic().releaseShared( ResourceTypes.RELATIONSHIP, id );
    }

    @Override
    public void releaseTemporalShared(KernelStatement statement, Locks.ResourceType type, long id, int propertyKeyId, int start, int end)
    {
        statement.locks().pessimistic().releaseTemporalPropShared( type, id, propertyKeyId, start, end );
        if(type.equals( ResourceTypes.NODE_TEMPORAL_PROP ) )
            statement.locks().pessimistic().releaseShared( ResourceTypes.NODE, id );
        else
            statement.locks().pessimistic().releaseShared( ResourceTypes.RELATIONSHIP, id );
    }

    // === TODO Below is unnecessary delegate methods
    @Override
    public String indexGetFailure( Statement state, IndexDescriptor descriptor )
            throws IndexNotFoundKernelException
    {
        return schemaReadDelegate.indexGetFailure( state, descriptor );
    }

    private void acquireExclusiveNodeLock( KernelStatement state, long nodeId )
    {
        if ( !state.txState().nodeIsAddedInThisTx( nodeId ) )
        {
            state.locks().optimistic().acquireExclusive( ResourceTypes.NODE, nodeId );
        }
    }

    private void acquireExclusiveRelationshipLock( KernelStatement state, long relationshipId )
    {
        if ( !state.txState().relationshipIsAddedInThisTx( relationshipId ) )
        {
            state.locks().optimistic().acquireExclusive( ResourceTypes.RELATIONSHIP, relationshipId );
        }
    }

    private void acquireSharedSchemaLock( KernelStatement state )
    {
        state.locks().optimistic().acquireShared( ResourceTypes.SCHEMA, schemaResource() );
    }

    private void acquireExclusiveSchemaLock( KernelStatement state )
    {
        state.locks().optimistic().acquireExclusive( ResourceTypes.SCHEMA, schemaResource() );
    }
}

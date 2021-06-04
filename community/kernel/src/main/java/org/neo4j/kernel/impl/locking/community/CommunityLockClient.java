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
package org.neo4j.kernel.impl.locking.community;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.neo4j.collection.primitive.Primitive;
import org.neo4j.collection.primitive.PrimitiveIntObjectMap;
import org.neo4j.collection.primitive.PrimitiveIntObjectVisitor;

import org.neo4j.collection.primitive.PrimitiveLongObjectVisitor;
import org.neo4j.helpers.collection.Visitor;
import org.neo4j.kernel.impl.locking.*;

import static java.lang.String.format;


// Please note. Except separate test cases for particular classes related to community locking
// see also org.neo4j.kernel.impl.locking.community.CommunityLocksCompatibility test suite

public class CommunityLockClient implements Locks.Client
{
    private final LockManagerImpl manager;
    private final LockTransaction lockTransaction = new LockTransaction();

    private final PrimitiveIntObjectMap<Map<LockResourceId, LockResource>> sharedLocks = Primitive.intObjectMap();
    private final PrimitiveIntObjectMap<Map<LockResourceId, LockResource>> exclusiveLocks = Primitive.intObjectMap();

    // To be able to close Locks.Client instance properly we should be able to do couple of things:
    //  - have a possibility to prevent new clients to come
    //  - wake up all the waiters and let them go
    //  - have a possibility to see how many clients are still using us and wait for them to finish
    // We need to do all of that to prevent a situation when a closing client will get a lock that will never be
    // closed and eventually will block other clients.
    private final LockClientStateHolder stateHolder = new LockClientStateHolder();

    public CommunityLockClient( LockManagerImpl manager )
    {
        this.manager = manager;
    }

    @Override
    public void acquireTemporalPropShared(Locks.ResourceType resourceType, long entityId, int propertyKeyId, int start, int end) throws AcquireLockTimeoutException {
        stateHolder.incrementActiveClients( this );
        try
        {
            LockResourceId resId =  new LockResourceId.TemporalProp( entityId, propertyKeyId );
            Map<LockResourceId, LockResource> localLocks = localShared( resourceType );
            LockResource resource = localLocks.get( resId );
            if ( resource == null ) {
                resource = new LockResource(resourceType, resId);
            }
            if ( manager.getTemporalPropReadLock(resource, lockTransaction, start, end) ) {
                localLocks.put( resId, resource );
            } else{
                throw new LockClientStoppedException( this );
            }
        }
        finally
        {
            stateHolder.decrementActiveClients();
        }
    }

    @Override
    public void acquireTemporalPropExclusive(Locks.ResourceType resourceType, long entityId, int propertyKeyId, int start, int end) throws AcquireLockTimeoutException {
        stateHolder.incrementActiveClients( this );
        try
        {
            LockResourceId resId =  new LockResourceId.TemporalProp( entityId, propertyKeyId );
            Map<LockResourceId, LockResource> localLocks = localExclusive( resourceType );
            LockResource resource = localLocks.get( resId );
            if ( resource == null ) {
                resource = new LockResource(resourceType, resId);
            }
            if ( manager.getTemporalPropWriteLock(resource, lockTransaction, start, end) ) {
                localLocks.put( resId, resource );
            } else{
                throw new LockClientStoppedException( this );
            }
        }
        finally
        {
            stateHolder.decrementActiveClients();
        }
    }

    @Override
    public void releaseTemporalPropShared(Locks.ResourceType resourceType, long entityId, int propertyKeyId ) throws AcquireLockTimeoutException {
        stateHolder.incrementActiveClients( this );
        try
        {
            LockResourceId.TemporalProp resId = new LockResourceId.TemporalProp(entityId, propertyKeyId);
            Map<LockResourceId, LockResource> localLocks = localShared( resourceType );
            LockResource resource = localLocks.get( resId );
            if ( resource.releaseReference() != 0 )
            {
                return;
            }

            localLocks.remove( resId );

            manager.releaseTemporalPropLocks( new LockResource( resourceType, resId ), lockTransaction );
        }
        finally
        {
            stateHolder.decrementActiveClients();
        }
    }

    @Override
    public void releaseTemporalPropExclusive(Locks.ResourceType resourceType, long entityId, int propertyKeyId) throws AcquireLockTimeoutException {
        stateHolder.incrementActiveClients( this );
        try
        {
            LockResourceId.TemporalProp resId = new LockResourceId.TemporalProp(entityId,propertyKeyId);
            Map<LockResourceId, LockResource> localLocks = localExclusive( resourceType );
            LockResource resource = localLocks.get( resId );
            if ( resource.releaseReference() != 0 )
            {
                return;
            }
            localLocks.remove( resId );

            manager.releaseTemporalPropLocks( new LockResource( resourceType, resId ), lockTransaction);
        }
        finally
        {
            stateHolder.decrementActiveClients();
        }
    }

    @Override
    public void acquireShared( Locks.ResourceType resourceType, long... resourceIds )
    {
        stateHolder.incrementActiveClients( this );
        try
        {
            Map<LockResourceId, LockResource> localLocks = localShared( resourceType );
            for ( long resourceId : resourceIds )
            {
                LockResourceId.Normal resId = new LockResourceId.Normal(resourceId);
                LockResource resource = localLocks.get( resId );
                if ( resource != null )
                {
                    resource.acquireReference();
                }
                else
                {
                    resource = new LockResource( resourceType, resId );
                    if ( manager.getReadLock( resource, lockTransaction ) )
                    {
                        localLocks.put( resId, resource );
                    }
                    else
                    {
                        throw new LockClientStoppedException( this );
                    }
                }
            }
        }
        finally
        {
            stateHolder.decrementActiveClients();
        }
    }



    @Override
    public void acquireExclusive( Locks.ResourceType resourceType, long... resourceIds )
    {
        stateHolder.incrementActiveClients( this );
        try
        {
            Map<LockResourceId, LockResource> localLocks = localExclusive( resourceType );
            for ( long resourceId : resourceIds )
            {
                LockResourceId.Normal resId = new LockResourceId.Normal(resourceId);
                LockResource resource = localLocks.get( resId );
                if ( resource != null )
                {
                    resource.acquireReference();
                }
                else
                {
                    resource = new LockResource( resourceType, resId );
                    if ( manager.getWriteLock( resource, lockTransaction ) )
                    {
                        localLocks.put( resId, resource );
                    }
                    else
                    {
                        throw new LockClientStoppedException( this );
                    }
                }
            }
        }
        finally
        {
            stateHolder.decrementActiveClients();
        }
    }

    @Override
    public boolean tryExclusiveLock( Locks.ResourceType resourceType, long resourceId )
    {
        stateHolder.incrementActiveClients( this );
        try
        {
            LockResourceId.Normal resId = new LockResourceId.Normal(resourceId);
            Map<LockResourceId, LockResource> localLocks = localExclusive( resourceType );
            LockResource resource = localLocks.get( resId );
            if ( resource != null )
            {
                resource.acquireReference();
                return true;
            }
            else
            {
                resource = new LockResource( resourceType, resId );
                if ( manager.tryWriteLock( resource, lockTransaction ) )
                {
                    localLocks.put( resId, resource );
                    return true;
                }
                else
                {
                    return false;
                }
            }
        }
        finally
        {
            stateHolder.decrementActiveClients();
        }
    }

    @Override
    public boolean trySharedLock( Locks.ResourceType resourceType, long resourceId )
    {
        stateHolder.incrementActiveClients( this );
        try
        {
            LockResourceId.Normal resId = new LockResourceId.Normal(resourceId);
            Map<LockResourceId, LockResource> localLocks = localShared( resourceType );
            LockResource resource = localLocks.get( resId );
            if ( resource != null )
            {
                resource.acquireReference();
                return true;
            }
            else
            {
                resource = new LockResource( resourceType, resId );
                if ( manager.tryReadLock( resource, lockTransaction ) )
                {
                    localLocks.put( resId, resource );
                    return true;
                }
                else
                {
                    return false;
                }
            }
        }
        finally
        {
            stateHolder.decrementActiveClients();
        }
    }

    @Override
    public void releaseShared( Locks.ResourceType resourceType, long resourceId )
    {
        stateHolder.incrementActiveClients( this );
        try
        {
            LockResourceId.Normal resId = new LockResourceId.Normal(resourceId);
            Map<LockResourceId, LockResource> localLocks = localShared( resourceType );
            LockResource resource = localLocks.get( resId );
            if ( resource.releaseReference() != 0 )
            {
                return;
            }

            localLocks.remove( resId );

            manager.releaseReadLock( new LockResource( resourceType, resId ), lockTransaction );
        }
        finally
        {
            stateHolder.decrementActiveClients();
        }
    }

    @Override
    public void releaseExclusive( Locks.ResourceType resourceType, long resourceId )
    {
        stateHolder.incrementActiveClients( this );
        try
        {
            LockResourceId.Normal resId = new LockResourceId.Normal(resourceId);
            Map<LockResourceId, LockResource> localLocks = localExclusive( resourceType );
            LockResource resource = localLocks.get( resId );
            if ( resource.releaseReference() != 0 )
            {
                return;
            }
            localLocks.remove( resId );

            manager.releaseWriteLock( new LockResource( resourceType, resId ), lockTransaction );
        }
        finally
        {
            stateHolder.decrementActiveClients();
        }
    }

    @Override
    public void stop()
    {
        // closing client to prevent any new client to come
        stateHolder.stopClient();
        // wake up and terminate waiters
        terminateAllWaiters();
        // wait for all active clients to go and terminate latecomers
        while ( stateHolder.hasActiveClients() )
        {
            terminateAllWaiters();
            LockSupport.parkNanos( TimeUnit.MILLISECONDS.toNanos( 20 ) );
        }
    }

    @Override
    public void close()
    {
        stop();
        // now we are only one who operate on this client
        // safe to release all the locks
        releaseLocks();
    }

    private void releaseLocks()
    {
        exclusiveLocks.visitEntries( typeWriteReleaser );
        sharedLocks.visitEntries( typeReadReleaser );
        exclusiveLocks.clear();
        sharedLocks.clear();
    }

    // waking up and terminate all waiters that were waiting for any lock for current client
    private void terminateAllWaiters()
    {
        manager.accept( new Visitor<RWLock,RuntimeException>()
        {
            @Override
            public boolean visit( RWLock lock ) throws RuntimeException
            {
                lock.terminateLockRequestsForLockTransaction( lockTransaction );
                return false;
            }
        } );
    }

    @Override
    public int getLockSessionId()
    {
        return lockTransaction.getId();
    }

    private Map<LockResourceId, LockResource> localShared( Locks.ResourceType resourceType )
    {
        Map<LockResourceId, LockResource> map = sharedLocks.get( resourceType.typeId() );
        if(map == null)
        {
            map = new HashMap<>();
            sharedLocks.put( resourceType.typeId(), map );
        }
        return map;
    }

    private Map<LockResourceId, LockResource> localExclusive( Locks.ResourceType resourceType )
    {
        Map<LockResourceId, LockResource> map = exclusiveLocks.get( resourceType.typeId() );
        if(map == null)
        {
            map = new HashMap<>();
            exclusiveLocks.put( resourceType.typeId(), map );
        }
        return map;
    }

    private final PrimitiveIntObjectVisitor<Map<LockResourceId, LockResource>, RuntimeException> typeReadReleaser = new
            PrimitiveIntObjectVisitor<Map<LockResourceId, LockResource>, RuntimeException>()
    {
        @Override
        public boolean visited( int key, Map<LockResourceId, LockResource> value ) throws RuntimeException
        {
            if( key == ResourceTypes.NODE_TEMPORAL_PROP.typeId() ||
                    key == ResourceTypes.REL_TEMPORAL_PROP.typeId())
            {
                for(Map.Entry<LockResourceId,LockResource> entry : value.entrySet() )
                {
                    manager.releaseAllTemporalPropReadLock( entry.getValue(), lockTransaction );
                }
                return false;
            }
            else
            {
                for(Map.Entry<LockResourceId,LockResource> entry : value.entrySet() )
                {
                    manager.releaseReadLock( entry.getValue(), lockTransaction );
                }
                return false;
            }
        }
    };

    private final PrimitiveIntObjectVisitor<Map<LockResourceId, LockResource>, RuntimeException> typeWriteReleaser = new
            PrimitiveIntObjectVisitor<Map<LockResourceId, LockResource>, RuntimeException>()
    {
        @Override
        public boolean visited( int key, Map<LockResourceId, LockResource> value ) throws RuntimeException
        {
            if( key == ResourceTypes.NODE_TEMPORAL_PROP.typeId() ||
                    key == ResourceTypes.REL_TEMPORAL_PROP.typeId())
            {
                for(Map.Entry<LockResourceId,LockResource> entry : value.entrySet() )
                {
                    manager.releaseAllTemporalPropWriteLock( entry.getValue(), lockTransaction );
                }
                return false;
            }
            else
            {
                for(Map.Entry<LockResourceId,LockResource> entry : value.entrySet() )
                {
                    manager.releaseWriteLock( entry.getValue(), lockTransaction );
                }
                return false;
            }
        }
    };

    private final PrimitiveLongObjectVisitor<LockResource, RuntimeException> writeReleaser = new PrimitiveLongObjectVisitor<LockResource, RuntimeException>()
    {
        @Override
        public boolean visited( long key, LockResource lockResource ) throws RuntimeException
        {
            manager.releaseWriteLock( lockResource, lockTransaction );
            return false;
        }
    };

    private final PrimitiveLongObjectVisitor<LockResource, RuntimeException> readReleaser = new PrimitiveLongObjectVisitor<LockResource, RuntimeException>()
    {
        @Override
        public boolean visited( long key, LockResource lockResource ) throws RuntimeException
        {
            manager.releaseReadLock( lockResource, lockTransaction );
            return false;
        }
    };

    @Override
    public String toString()
    {
        return format( "%s[%d]", getClass().getSimpleName(), getLockSessionId() );
    }
}

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

import org.neo4j.kernel.DeadlockDetectedException;
import org.neo4j.logging.Logger;

/**
 * A read/write lock is a lock that will allow many transactions to acquire read
 * locks as long as there is no transaction holding the write lock.
 * <p>
 * When a transaction has write lock no other tx is allowed to acquire read or
 * write lock on that resource but the tx holding the write lock. If one tx has
 * acquired write lock and another tx needs a lock on the same resource that tx
 * must wait. When the lock is released the other tx is notified and wakes up so
 * it can acquire the lock.
 * <p>
 * Waiting for locks may lead to a deadlock. Consider the following scenario. Tx
 * T1 acquires write lock on resource R1. T2 acquires write lock on R2. Now T1
 * tries to acquire read lock on R2 but has to wait since R2 is locked by T2. If
 * T2 now tries to acquire a lock on R1 it also has to wait because R1 is locked
 * by T1. T2 cannot wait on R1 because that would lead to a deadlock where T1
 * and T2 waits forever.
 * <p>
 * Avoiding deadlocks can be done by keeping a resource allocation graph. This
 * class works together with the {@link RagManager} to make sure no deadlocks
 * occur.
 * <p>
 * Waiting transactions are put into a queue and when some tx releases the lock
 * the queue is checked for waiting txs. This implementation tries to avoid lock
 * starvation and increase performance since only waiting txs that can acquire
 * the lock are notified.
 */
class RWLock
{
    public void releaseAllTemporalLocks(Object tx){}
    public boolean acquireTemporalExclusiveLock(Object tx, int beginTime, int endTime){return false;}
    public boolean acquireTemporalSharedLock(Object tx, int start, int end){return false;}


    public Object resource()
    {
        return null;
    }

    synchronized void mark()
    {

    }

    synchronized boolean isMarked()
    {
        return false;
    }

    /**
     * Tries to acquire read lock for a given transaction. If
     * <CODE>this.writeCount</CODE> is greater than the currents tx's write
     * count the transaction has to wait and the {@link RagManager#checkWaitOn}
     * method is invoked for deadlock detection.
     * <p>
     * If the lock can be acquired the lock count is updated on <CODE>this</CODE>
     * and the transaction lock element (tle).
     * Waiting for a lock can also be terminated. In that case waiting thread will be interrupted and corresponding
     * {@link org.neo4j.kernel.impl.locking.community.RWLock.TxLockElement} will be marked as terminated.
     * In that case lock will not be acquired and false will be return as result of acquisition
     *
     * @throws DeadlockDetectedException
     *             if a deadlock is detected
     * @return true is lock was acquired, false otherwise
     */
    synchronized boolean acquireReadLock( Object tx ) throws DeadlockDetectedException
    {
        return false;
    }

    synchronized boolean tryAcquireReadLock( Object tx )
    {
        return false;
    }

    /**
	 * Releases the read lock held by the provided transaction. If it is null then
	 * an attempt to acquire the current transaction will be made. This is to
	 * make safe calling the method from the context of an
	 * <code>afterCompletion()</code> hook where the tx is locally stored and
	 * not necessarily available through the tm. If there are waiting
	 * transactions in the queue they will be interrupted if they can acquire
	 * the lock.
	 */
    synchronized void releaseReadLock( Object tx ) throws LockNotFoundException
    {

    }

    /**
     * Tries to acquire write lock for a given transaction. If
     * <CODE>this.writeCount</CODE> is greater than the currents tx's write
     * count or the read count is greater than the currents tx's read count the
     * transaction has to wait and the {@link RagManager#checkWaitOn} method is
     * invoked for deadlock detection.
     * <p>
     * If the lock can be acquires the lock count is updated on <CODE>this</CODE>
     * and the transaction lock element (tle).
     * Waiting for a lock can also be terminated. In that case waiting thread will be interrupted and corresponding
     * {@link org.neo4j.kernel.impl.locking.community.RWLock.TxLockElement} will be marked as terminated.
     * In that case lock will not be acquired and false will be return as result of acquisition
     *
     * @throws DeadlockDetectedException
     *             if a deadlock is detected
     * @return true is lock was acquired, false otherwise
     */
    synchronized boolean acquireWriteLock( Object tx ) throws DeadlockDetectedException
    {
        return false;
    }

    synchronized boolean tryAcquireWriteLock( Object tx )
    {
        return false;
    }

    /**
	 * Releases the write lock held by the provided tx. If it is null then an
	 * attempt to acquire the current transaction from the transaction manager
	 * will be made. This is to make safe calling this method as an
	 * <code>afterCompletion()</code> hook where the transaction context is not
	 * necessarily available. If write count is zero and there are waiting
	 * transactions in the queue they will be interrupted if they can acquire
	 * the lock.
	 */
    synchronized void releaseWriteLock( Object tx ) throws LockNotFoundException
    {
    }

    synchronized int getWriteCount()
    {
        return 0;
    }

    synchronized int getReadCount()
    {
        return 0;
    }

    synchronized int getWaitingThreadsCount()
    {
        return 0;
    }

    public synchronized boolean logTo( Logger logger )
    {
        return false;
    }

    public synchronized String describe()
    {
        return "";
    }

    public synchronized long maxWaitTime()
    {
        return 0L;
    }

    // for specified transaction object mark all lock elements as terminated
    // and interrupt all waiters
    synchronized void terminateLockRequestsForLockTransaction(Object lockTransaction) {

    }

    @Override
    public String toString()
    {
        return "RWLock[]";
    }

    synchronized Object getTxLockElementCount()
    {
        return null;
    }
}

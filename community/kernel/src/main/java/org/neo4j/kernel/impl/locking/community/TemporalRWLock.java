package org.neo4j.kernel.impl.locking.community;

import org.neo4j.kernel.impl.locking.LockType;
import org.neo4j.kernel.impl.util.ArrayMap;

import java.util.*;

import static java.lang.Thread.interrupted;
import static org.neo4j.kernel.impl.locking.LockType.READ;
import static org.neo4j.kernel.impl.locking.LockType.WRITE;

/**
 * Created by song on 2017/7/22 0022.
 * The reason we use two separate
 */
public class TemporalRWLock extends RWLock
{
    private final Object resource; // the resource this RWLock locks
    private final LinkedList<LockRequest> waitingThreadList = new LinkedList<>();
    private final ArrayMap<Object, TxLockTemporalElement> txLockElementMap = new ArrayMap<>( (byte)5, false, true );
    private final RagManager ragManager;
    private final TimeLineManager timeLine = new TimeLineManager();

    // access to these is guarded by synchronized blocks
    private int totalReadCount;
    private int totalWriteCount;
    private int marked; // synch helper in LockManager

    TemporalRWLock(Object resource, RagManager ragManager)
    {
        this.ragManager = ragManager;
        this.resource = resource;
    }

    // 返回在指定时段上


    // 通过这个实现了锁的可重入（一个事务对同一个资源加多个同样的锁）
    // keeps track of a transactions read and write lock count on this RWLock
    private static class TxLockTemporalElement
    {
        private final Object tx;

        // access to these is guarded by synchronized blocks
        private int readCount;
        private int writeCount;
        // represent number of active request that where current TxLockElement participate in
        // as soon as hasNoRequests return true - txLockElement can be cleaned up
        private int requests = 0;
        // flag indicate that current TxLockElement is terminated because owning client closed
        private boolean terminated = false;

        TxLockTemporalElement(Object tx )
        {
            this.tx = tx;
        }

        void incrementRequests()
        {
            requests++;
        }

        void decrementRequests()
        {
            requests--;
        }

        boolean hasNoRequests()
        {
            return requests == 0;
        }

        boolean isFree()
        {
            return readCount == 0 && writeCount == 0;
        }

        public boolean isTerminated()
        {
            return terminated;
        }

        public void setTerminated( boolean terminated )
        {
            this.terminated = terminated;
        }


        // for temporal
        private final Set<TLock> locks = new HashSet<>();
    }

    // keeps track of what type of lock a thread is waiting for
    private static class LockRequest
    {
        private final TxLockTemporalElement element;
        private final LockType lockType;
        private final Thread waitingThread;
        private final long since = System.currentTimeMillis();

        LockRequest(TxLockTemporalElement element, LockType lockType, Thread thread, int start, int end)
        {
            this.element = element;
            this.lockType = lockType;
            this.waitingThread = thread;
        }
    }

    public Object resource()
    {
        return resource;
    }

    public synchronized void mark()
    {
        this.marked++;
    }

    public synchronized boolean isMarked()
    {
        return marked > 0;
    }

    //释放掉某个事务拿着的所有时态锁
    @Override
    public synchronized void releaseAllTemporalLocks( Object tx )
    {
        TxLockTemporalElement tle = getLockElement( tx );

        assert tle.readCount > 0 || tle.writeCount > 0 : new LockNotFoundException( "" + tx + " don't have temporalLock" );

//        totalReadCount--;
//        tle.readCount--;
        if ( tle.isFree() )
        {
            timeLine.removeAll( tle.locks );
            for(TLock lock : tle.locks) {
                ragManager.lockReleased(lock, tx);
            }
            if ( tle.hasNoRequests() )
            {
                txLockElementMap.remove( tx );
            }
        }
        if ( !waitingThreadList.isEmpty() )
        {
            LockRequest lockRequest = waitingThreadList.getLast();

            if ( lockRequest.lockType == LockType.WRITE )
            {
                // this one is tricky...
                // if readCount > 0 lockRequest either have to find a waiting read lock
                // in the queue or a waiting write lock that has all read
                // locks, if none of these are found it means that there
                // is a (are) thread(s) that will release read lock(s) in the
                // near future...
                if ( totalReadCount == lockRequest.element.readCount )
                {
                    // found a write lock with all read locks
                    waitingThreadList.removeLast();
                    lockRequest.waitingThread.interrupt();
                }
                else
                {
                    ListIterator<LockRequest> listItr = waitingThreadList.listIterator(
                            waitingThreadList.lastIndexOf( lockRequest ) );
                    // hm am I doing the first all over again?
                    // think I am if cursor is at lastIndex + 0.5 oh well...
                    while ( listItr.hasPrevious() )
                    {
                        lockRequest = listItr.previous();
                        if ( lockRequest.lockType == LockType.WRITE && totalReadCount == lockRequest.element.readCount )
                        {
                            // found a write lock with all read locks
                            listItr.remove();
                            lockRequest.waitingThread.interrupt();
                            break;
                        }
                        else if ( lockRequest.lockType == LockType.READ )
                        {
                            // found a read lock, let it do the job...
                            listItr.remove();
                            lockRequest.waitingThread.interrupt();
                        }
                    }
                }
            }
            else
            {
                // some thread may have the write lock and released a read lock
                // if writeCount is down to zero lockRequest can interrupt the waiting
                // read lock
                if ( totalWriteCount == 0 )
                {
                    waitingThreadList.removeLast();
                    lockRequest.waitingThread.interrupt();
                }
            }
        }
    }

    @Override
    public synchronized boolean acquireTemporalExclusiveLock(Object tx, int beginTime, int endTime)
    {
        TxLockTemporalElement tle = getOrCreateLockElement( tx );

        LockRequest lockRequest = null;
        // used to track do we need to add lock request to a waiting queue or we still have it there
        boolean addLockRequest = true;
        try
        {
            tle.incrementRequests();
            Thread currentThread = Thread.currentThread();
            TLock tLock = new TLock( beginTime, endTime, tle, true);
            //Conditions to wait: current tx not has write lock, other tx has write lock or OTHER has read lock union the time
            while ( !tle.isTerminated() && timeLine.shouldWait( tLock ))
            {
                ragManager.checkWaitOn( tLock, tx );

                if (addLockRequest)
                {
                    lockRequest = new LockRequest( tle, WRITE, currentThread, beginTime, endTime);
                    waitingThreadList.addFirst( lockRequest );
                }

                try
                {
                    wait();
                    addLockRequest = false;
                }
                catch ( InterruptedException e )
                {
                    interrupted();
                    addLockRequest = true;
                }
                ragManager.stopWaitOn( tLock, tx );
            }

            if ( !tle.isTerminated() )
            {
                registerWriteLockAcquired( tx, tLock );
                return true;
            } else {
                // in case if lock element was interrupted and it was never register before
                // we need to clean it from lock element map
                // if it was register before it will be cleaned up during standard lock release call
                if ( tle.requests == 1 && tle.isFree() )
                {
                    txLockElementMap.remove( tx );
                }
                return false;
            }
        }
        finally
        {
            cleanupWaitingListRequests( lockRequest, tle, addLockRequest );
            // for cases when spurious wake up was the reason why we waked up, but also there
            // was an interruption as described at 17.2 just clearing interruption flag
            interrupted();
            // if deadlocked, remove marking so lock is removed when empty
            tle.decrementRequests();
            marked--;
        }
    }

    /**
     * 获取某个时段的共享锁
     * 如果在要求的时段上有其他事务的X锁（时间上有重叠），则要等待。
     * @param tx
     * @param start
     * @param end
     * @return
     */
    @Override
    public synchronized boolean acquireTemporalSharedLock(Object tx, int start, int end )
    {
        TxLockTemporalElement tle = getOrCreateLockElement( tx );

        LockRequest lockRequest = null;
        // used to track do we need to add lock request to a waiting queue or we still have it there
        boolean addLockRequest = true;
        try
        {
            tle.incrementRequests();
            Thread currentThread = Thread.currentThread();
            TLock tLock = new TLock( start, end, tle, false);
            // 不满足条件则进入等待
            while ( !tle.isTerminated() && timeLine.shouldWait(tLock))
            {
                ragManager.checkWaitOn( tLock, tx );

                if (addLockRequest)
                {
                    lockRequest = new LockRequest( tle, READ, currentThread, start, end);
                    waitingThreadList.addFirst(lockRequest);
                }

                try
                {
                    wait();
                    addLockRequest = false;
                }
                catch ( InterruptedException e )
                {
                    interrupted();
                    addLockRequest = true;
                }
                ragManager.stopWaitOn( tLock, tx );
            }

            if ( !tle.isTerminated() )
            {
                registerReadLockAcquired( tx, tLock );
                return true;
            } else {
                // in case if lock element was interrupted and it was never register before
                // we need to clean it from lock element map
                // if it was register before it will be cleaned up during standard lock release call
                if ( tle.requests == 1 && tle.isFree() )
                {
                    txLockElementMap.remove( tx );
                }
                return false;
            }
        }
        finally
        {
            cleanupWaitingListRequests( lockRequest, tle, addLockRequest );
            // for cases when spurious wake up was the reason why we waked up, but also there
            // was an interruption as described at 17.2 just clearing interruption flag
            interrupted();
            // if deadlocked, remove marking so lock is removed when empty
            tle.decrementRequests();
            marked--;
        }
    }

    public int getWriteCount()
    {
        return totalWriteCount;
    }

    public int getReadCount()
    {
        return totalReadCount;
    }

    public synchronized int getWaitingThreadsCount()
    {
        return waitingThreadList.size();
    }

    public synchronized String describe()
    {
        StringBuilder sb = new StringBuilder( this.toString() );
        sb.append(" Total lock count: readCount=").append(totalReadCount)
                .append(" writeCount=").append(totalWriteCount)
                .append(" for ").append(resource).append("\n")
                .append( "Waiting list:" + "\n" );
        Iterator<LockRequest> wElements = waitingThreadList.iterator();
        while ( wElements.hasNext() )
        {
            LockRequest we = wElements.next();
            sb.append("[").append(we.waitingThread).append("(").append(we.element.readCount).append("r,").append(we.element.writeCount).append("w),").append(we.lockType).append("]\n");
            if ( wElements.hasNext() )
            {
                sb.append( "," );
            }
        }

        sb.append( "Locking transactions:\n" );
        Iterator<TxLockTemporalElement> lElements = txLockElementMap.values().iterator();
        while ( lElements.hasNext() )
        {
            TxLockTemporalElement tle = lElements.next();
            sb.append("").append(tle.tx).append("(").append(tle.readCount).append("r,").append(tle.writeCount).append("w)\n");
        }
        return sb.toString();
    }

    public synchronized long maxWaitTime()
    {
        long max = 0L;
        for ( LockRequest thread : waitingThreadList )
        {
            if ( thread.since < max )
            {
                max = thread.since;
            }
        }
        return System.currentTimeMillis() - max;
    }

    // for specified transaction object mark all lock elements as terminated
    // and interrupt all waiters
    synchronized void terminateLockRequestsForLockTransaction(Object lockTransaction) {
        TxLockTemporalElement lockElement = txLockElementMap.get( lockTransaction );
        if ( lockElement != null && !lockElement.isTerminated())
        {
            lockElement.setTerminated( true );
            for ( LockRequest lockRequest : waitingThreadList )
            {
                if ( lockRequest.element.tx.equals( lockTransaction ) )
                {
                    lockRequest.waitingThread.interrupt();
                }
            }
        }
    }

    private void registerReadLockAcquired(Object tx, TLock tLock )
    {
        registerLockAcquired( tx, tLock );
        totalReadCount++;
        tLock.tle.readCount++;
        tLock.tle.locks.add( tLock );
        timeLine.addLock( tLock );
    }

    private void registerWriteLockAcquired(Object tx, TLock tLock)
    {
        registerLockAcquired( tx, tLock );
        totalWriteCount++;
        tLock.tle.writeCount++;
        tLock.tle.locks.add( tLock );
        timeLine.addLock( tLock );
    }

    private void registerLockAcquired( Object tx, TLock tLock )
    {
        if ( tLock.tle.isFree() )
        {
            ragManager.lockAcquired( tLock, tx );
        }
    }

    private TxLockTemporalElement getLockElement(Object tx )
    {
        TxLockTemporalElement tle = txLockElementMap.get( tx );
        if ( tle == null )
        {
            throw new LockNotFoundException( "No transaction lock element found for " + tx );
        }
        return tle;
    }

    private void assertTransaction( Object tx )
    {
        if ( tx == null )
        {
            throw new IllegalArgumentException();
        }
    }

    private TxLockTemporalElement getOrCreateLockElement(Object tx )
    {
        assertTransaction( tx );
        TxLockTemporalElement tle = txLockElementMap.get( tx );
        if ( tle == null )
        {
            txLockElementMap.put( tx, tle = new TxLockTemporalElement( tx ) );
        }
        return tle;
    }

    public synchronized Object getTxLockElementCount()
    {
        return txLockElementMap.size();
    }

    private void cleanupWaitingListRequests( LockRequest lockRequest, TxLockTemporalElement lockElement,
                                             boolean addLockRequest )
    {
        if ( lockRequest != null && (lockElement.isTerminated() || !addLockRequest) )
        {
            waitingThreadList.remove( lockRequest );
        }
    }

    //FIXME: use a more efficient data structure
    private static class TimeLineManager
    {
        private final List<TLock> list = new ArrayList<>();

        public boolean shouldWait(TLock lock) {
            for(TLock curLock : list){
                if(curLock.conflict(lock)) return true;
            }
            return false;
        }

        public int size()
        {
            return this.list.size();
        }

        public void removeAll( Set<TLock> locks )
        {
            list.removeAll( locks );
        }

        public void addLock( TLock lock )
        {
            list.add( lock );
        }
    }

    static class TLock
    {
        private final TxLockTemporalElement tle;
        private final int start;
        private final int end;
        private final boolean isWrt;
        public TLock(int s, int e, TxLockTemporalElement tle, boolean isWrite)
        {
            this.start = s;
            this.end = e;
            this.tle = tle;
            this.isWrt = isWrite;
        }

        public boolean conflict(TLock other) {
            return (other.tle != this.tle) && //不是同一个事务的锁。同一个事务无论之前有什么锁都不冲突
                    (other.isWrt || this.isWrt) && //至少有一个写。如果都是读就不冲突
                    (other.start <= this.end && this.start <= other.end); //时间段重叠
        }
    }
}

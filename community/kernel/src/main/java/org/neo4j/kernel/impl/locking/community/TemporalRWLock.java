package org.neo4j.kernel.impl.locking.community;

import org.neo4j.kernel.impl.util.ArrayMap;

import java.util.*;

import static java.lang.Thread.currentThread;
import static java.lang.Thread.interrupted;

/**
 * Created by song on 2017/7/22 0022.
 * The reason we use two separate
 */
public class TemporalRWLock extends RWLock
{
    private final Object temporalPropertyResource; // the resource this RWLock locks
    private final ArrayMap<Object, TxLockTemporalElement> txLockElementMap = new ArrayMap<>( (byte)5, false, true );
    private final RagManager ragManager;
    private final TimeLineManager timeLine = new TimeLineManager();

    // access to these is guarded by synchronized blocks
    private int waitingThreadCnt = 0;
    private int totalReadCount;
    private int totalWriteCount;
    private int marked; // synch helper in LockManager

    TemporalRWLock(Object temporalPropertyResource, RagManager ragManager)
    {
        this.ragManager = ragManager;
        this.temporalPropertyResource = temporalPropertyResource;
    }

    // 通过这个实现了锁的可重入（一个事务对同一个资源加多个同样的锁）
    // keeps track of a transactions read and write lock count on this RWLock
    private static class TxLockTemporalElement
    {
        private final Object tx;
        // for temporal
        private final Set<TLock> locks = new HashSet<>();
        private final Set<TResource> lockResources = new HashSet<>();
        // flag indicate that current TxLockElement is terminated because owning client closed
        private boolean terminated = false;

        TxLockTemporalElement(Object tx )
        {
            this.tx = tx;
        }

        public boolean isTerminated() {
            return terminated;
        }

        public void setTerminated( boolean terminated )
        {
            this.terminated = terminated;
        }
    }

    public Object resource()
    {
        return temporalPropertyResource;
    }

    public synchronized void mark()
    {
        this.marked++;
    }

    public synchronized boolean isMarked()
    {
        return marked > 0;
    }

    @Override
    public synchronized boolean acquireTemporalExclusiveLock(Object tx, int beginTime, int endTime)
    {
        TResource res = timeLine.getTResource(beginTime, endTime);
        TLock tLock = new TLock( res, tx, true);
        boolean succ = acquireTLock(tLock);
        if(succ){
            totalWriteCount++;
        }
        return succ;
    }

    @Override
    public synchronized boolean acquireTemporalSharedLock(Object tx, int start, int end )
    {
        TResource res = timeLine.getTResource(start, end);
        TLock tLock = new TLock( res, tx, false);
        boolean succ = acquireTLock(tLock);
        if(succ){
            totalReadCount++;
        }
        return succ;
    }

    private synchronized boolean acquireTLock(TLock tLock){
        TxLockTemporalElement tle = getOrCreateLockElement( tLock.tx );
        try
        {
            TLock conflict;
            waitingThreadCnt++;
            while ( !tle.isTerminated() && (conflict = timeLine.shouldWait( tLock ))!=null)
            {
                ragManager.checkWaitOn( tLock );
                synchronized (conflict) {
                    conflict.wait();
                }
                ragManager.stopWaitOn( tLock );
            }
            if ( !tle.lockResources.contains(tLock.resource) ) { //RagManager对同一资源只能锁一次。
                ragManager.lockAcquired( tLock );
            }
            timeLine.addLock(tLock);
            tle.locks.add(tLock);
            tle.lockResources.add(tLock.resource);
            return true;
        } catch ( InterruptedException ignore ) {
            System.out.println("TemporalRWLock.acquireTLock: interrupted when waiting TLock");
            return false;
        } finally
        {
            interrupted();
            marked--;
            waitingThreadCnt--;
        }
    }

    //释放掉某个事务拿着的所有时态锁
    @Override
    public synchronized void releaseAllTemporalLocks( Object tx )
    {
        TxLockTemporalElement tle = txLockElementMap.get( tx );
        if ( tle == null ) return;
        timeLine.removeAll( tle.locks );
        for(TLock lock : tle.locks) {
            if(lock.isWrt) totalWriteCount--;
            else totalReadCount--;
            if(tle.lockResources.remove(lock.resource)){
                ragManager.lockReleased(lock);
            }
        }
        txLockElementMap.remove( tx );

        for(TLock lock : tle.locks){
            lock.notifyAll();
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
        return waitingThreadCnt;
    }

    public synchronized String describe()
    {
        StringBuilder sb = new StringBuilder( this.toString() );
        sb.append(" Total lock count: readCount=").append(totalReadCount)
                .append(" writeCount=").append(totalWriteCount)
                .append(" for ").append(temporalPropertyResource).append("\n");
        for(TLock lock : timeLine.list){
            sb.append(lock.resource.start).append("~").append(lock.resource.end).append(" ")
                    .append(lock.isWrt).append(" ").append(lock.tx).append('\n');
        }
        sb.append( "Locking transactions:\n" );
        for (TxLockTemporalElement tle : txLockElementMap.values()) {
            sb.append(tle.tx).append("(").append(tle.locks.size()).append(" locks, ").append(tle.lockResources.size()).append(" resources)\n");
        }
        return sb.toString();
    }

    public synchronized long maxWaitTime()
    {
        return 0;
    }

    // for specified transaction object mark all lock elements as terminated
    // and interrupt all waiters
    synchronized void terminateLockRequestsForLockTransaction(Object lockTransaction) {
        TxLockTemporalElement tle = txLockElementMap.get( lockTransaction );
        if ( tle != null && !tle.isTerminated()) {
            tle.setTerminated( true );
            for ( TLock lock : tle.locks) {
                lock.thread.interrupt();
            }
        }
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

    //FIXME: use a more efficient data structure
    private class TimeLineManager
    {
        private final List<TLock> list = new ArrayList<>();

        public TResource getTResource(int beginTime, int endTime) {
            TResource res = new TResource(beginTime, endTime, temporalPropertyResource);
            for(TLock curLock : list){
                if(curLock.resource.equals(res)) return curLock.resource;
            }
            return res;
        }

        public TLock shouldWait(TLock lock) {
            for(TLock curLock : list){
                if(curLock.conflict(lock)) return curLock;
            }
            return null;
        }

        public int size() {
            return this.list.size();
        }

        public void removeAll( Set<TLock> locks ) {
            list.removeAll( locks );
        }

        public void addLock( TLock lock ) {
            list.add( lock );
        }
    }
    //用于时间线上控锁和唤醒。
    static class TLock
    {
        private final Thread thread = currentThread();
        final Object tx;
        final TResource resource;
        private final boolean isWrt;
        public TLock(TResource resource, Object tx, boolean isWrite)
        {
            this.resource = resource;
            this.tx = tx;
            this.isWrt = isWrite;
        }

        public boolean conflict(TLock other) {
            return (other.tx != this.tx) && //不是同一个事务的锁。同一个事务无论之前有什么锁都不冲突
                    (other.isWrt || this.isWrt) && //至少有一个写。如果都是读就不冲突
                    other.resource.conflict(this.resource); //时间段重叠
        }
    }
    // 用于在RAGManager中区分资源
    public static class TResource {
        private final int start;
        private final int end;
        final Object propResource;

        public TResource(int start, int end, Object propResource) {
            this.start = start;
            this.end = end;
            this.propResource = propResource;
        }

        public boolean conflict(TResource other) {
            return other.start <= this.end && this.start <= other.end; //时间段重叠
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TResource tResource = (TResource) o;
            return propResource == tResource.propResource &&
                    start == tResource.start &&
                    end == tResource.end;
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end, propResource);
        }
    }
}

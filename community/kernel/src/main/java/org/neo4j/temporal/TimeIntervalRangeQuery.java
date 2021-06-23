package org.neo4j.temporal;

import org.act.temporalProperty.query.TimePointL;
import org.act.temporalProperty.query.aggr.TimeIntervalEntry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class TimeIntervalRangeQuery extends TemporalRangeQuery{
    protected TimePointL startTime;
    protected TimePointL endTime;

    private TimePointL lastTime = null;
    private Object lastVal = null;

    public TimeIntervalRangeQuery(TimePointL beginTime, TimePointL endTime){
        this.startTime = beginTime;
        this.endTime = endTime;
    }

    abstract public void onEntry(TimePointL beginTime, TimePointL endTime, Object val);
    abstract public Object returnValue();

    @Override
    public boolean onNewEntry(long entityId, int propertyId, TimePointL time, Object val) {
        if ( lastTime == null ) {
            lastTime = time.compareTo(startTime) < 0 ? startTime : time;
        }else{
            onEntry( lastTime, time.pre(), lastVal );
            lastTime = time;
        }
        lastVal = val;
        return true;
    }

    @Override
    public Object onReturn() {
        if(lastTime!=null && lastTime.compareTo(endTime)<=0){
            onEntry(lastTime, endTime, lastVal);
        }
        return returnValue();
    }

    public static class Duration extends TimeIntervalRangeQuery {

        public Duration(TimePointL beginTime, TimePointL endTime){
            super(beginTime, endTime);
        }

        private final Map<Object, Integer> duration = new HashMap<>();

        @Override
        public void onEntry(TimePointL beginTime, TimePointL endTime, Object val) {
            int durationT = (int) (endTime.val() - beginTime.val() + 1);
            Integer sum = duration.get(val);
            if(sum==null){
                duration.put(val, durationT);
            }else{
                duration.put(val, sum + durationT);
            }
        }

        public Object returnValue() {
            return duration;
        }
    }


}

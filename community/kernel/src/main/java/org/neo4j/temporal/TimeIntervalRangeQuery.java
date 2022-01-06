package org.neo4j.temporal;

import com.google.common.base.Converter;
import org.act.temporalProperty.query.TimePointL;
import org.act.temporalProperty.query.aggr.TimeIntervalEntry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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
    public Object returnValue(){return null;}

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

    public static class IntDuration extends TimeIntervalRangeQuery {

        public IntDuration(TimePointL beginTime, TimePointL endTime){
            super(beginTime, endTime);
        }

        @Override
        public void setValueType(String valueType) {
            super.setValueType(valueType);
            assert "INT".equals(valueType);
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

    public static class FloatDuration extends TimeIntervalRangeQuery {
        private final TreeSet<Float> interval;

        public FloatDuration(TimePointL beginTime, TimePointL endTime, TreeSet<Float> intervalStarts){
            super(beginTime, endTime);
            this.interval = intervalStarts;
        }

        @Override
        public void setValueType(String valueType) {
            super.setValueType(valueType);
            assert "FLOAT".equals(valueType);
        }

        private final Map<Object, Integer> duration = new HashMap<>();

        @Override
        public void onEntry(TimePointL beginTime, TimePointL endTime, Object val) {
            int durationT = (int) (endTime.val() - beginTime.val() + 1);
            Float grp = interval.floor((Float) val);
            if(grp!=null) {
                Integer sum = duration.get(grp);
                if (sum == null) {
                    duration.put(grp, durationT);
                } else {
                    duration.put(grp, sum + durationT);
                }
            }
        }

        public Object returnValue() {
            return duration;
        }
    }

    public static class Duration<E> extends TimeIntervalRangeQuery {
        private final TreeSet<E> interval;
        private final Converter<Object, E> conv;

        public Duration(TimePointL beginTime, TimePointL endTime, TreeSet<E> intervalStarts, Converter<Object, E> conv){
            super(beginTime, endTime);
            this.interval = intervalStarts;
            this.conv = conv;
        }

        @Override
        public void setValueType(String valueType) {
            super.setValueType(valueType);
        }

        private final Map<E, Integer> duration = new HashMap<>();

        @Override
        public void onEntry(TimePointL beginTime, TimePointL endTime, Object val) {
            int durationT = (int) (endTime.val() - beginTime.val() + 1);
            E grp = interval.floor(conv.conv(val));
            if(grp!=null) {
                Integer sum = duration.get(grp);
                if (sum == null) {
                    duration.put(grp, durationT);
                } else {
                    duration.put(grp, sum + durationT);
                }
            }
        }

        public Object returnValue() {
            return duration;
        }
    }

    public interface Converter<A,B> {
        B conv(A from);
    }

}

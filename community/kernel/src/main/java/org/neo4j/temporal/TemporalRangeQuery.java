package org.neo4j.temporal;

import org.act.temporalProperty.impl.InternalEntry;
import org.act.temporalProperty.impl.InternalKey;
import org.act.temporalProperty.impl.ValueType;
import org.act.temporalProperty.query.TimePointL;
import org.act.temporalProperty.query.range.TimeRangeQuery;
import org.act.temporalProperty.util.Slice;

import java.util.Comparator;
import java.util.Objects;

/**
 * Created by song on 2018-07-26.
 */

public abstract class TemporalRangeQuery implements TimeRangeQuery
{
    private CallBack callBack = null;
    @Override
    public void setValueType(String valueType) {
        switch (valueType){
            case "INT":
                this.callBack = new CallBack(){ @Override public Object readSlice(Slice slice) { return slice.getInt(0); }};
                return;
            case "LONG": this.callBack = new CallBack(){ @Override public Object readSlice(Slice slice) { return slice.getLong(0); }}; return;
            case "FLOAT": this.callBack = new CallBack(){ @Override public Object readSlice(Slice slice) { return slice.getFloat(0); }}; return;
            case "DOUBLE": this.callBack = new CallBack(){ @Override public Object readSlice(Slice slice) { return slice.getDouble(0); }}; return;
            case "STRING": this.callBack = new CallBack(){ @Override public Object readSlice(Slice slice) { return new String(slice.getBytes()); }}; return;
        }
    }

    @Override
    public boolean onNewEntry(InternalEntry entry) {
        InternalKey k = entry.getKey();
        Slice v = entry.getValue();
        if(k.getValueType()==ValueType.INVALID){
            return onNewEntry(k.getEntityId(), k.getPropertyId(), k.getStartTime(), null);
        }else{
            return onNewEntry(k.getEntityId(), k.getPropertyId(), k.getStartTime(), callBack.readSlice(v));
        }
    }

    private interface CallBack{
        Object readSlice(Slice slice);
    }

    public abstract boolean onNewEntry(long entityId, int propertyId, TimePointL time, Object val);

    public static class ValueChangeCounter extends TemporalRangeQuery {
        private int change = 0;
        private Object lastVal = null;

        @Override
        public boolean onNewEntry(long entityId, int propertyId, TimePointL beginTime, Object val) {
            if(val!=lastVal) {
                change++;
            }
            lastVal = val;
            return true;
        }

        public Object onReturn() {
            return change;
        }
    }

    public static class MaxInt extends TemporalRangeQuery {
        private Integer max = null;

        @Override
        public boolean onNewEntry(long entityId, int propertyId, TimePointL beginTime, Object val) {
            if (val != null) {
                int vInt = (Integer) val;
                if (max==null || vInt > max) max = vInt;
            }
            return true;
        }

        public Object onReturn() {
            return max;
        }
    }

}

package org.neo4j.temporal;

import org.act.temporalProperty.impl.InternalEntry;
import org.act.temporalProperty.impl.InternalKey;
import org.act.temporalProperty.impl.ValueType;
import org.act.temporalProperty.meta.ValueContentType;
import org.act.temporalProperty.query.TimePointL;
import org.act.temporalProperty.query.range.TimeRangeQuery;
import org.act.temporalProperty.util.Slice;
import org.act.temporalProperty.util.TemporalPropertyValueConvertor;

import java.util.Comparator;
import java.util.Objects;

/**
 * Created by song on 2018-07-26.
 */

public abstract class TemporalRangeQuery implements TimeRangeQuery
{
    ValueContentType vType;
    @Override
    public void setValueType(String valueType) {
        this.vType = ValueContentType.valueOf(valueType);
    }

    @Override
    public boolean onNewEntry(InternalEntry entry) {
        InternalKey k = entry.getKey();
        Slice v = entry.getValue();
        if(k.getValueType()==ValueType.INVALID){
            return onNewEntry(k.getEntityId(), k.getPropertyId(), k.getStartTime(), null);
        }else{
            return onNewEntry(k.getEntityId(), k.getPropertyId(), k.getStartTime(), TemporalPropertyValueConvertor.fromSlice(vType, v));
        }
    }

    @Override
    public Object onReturn() {
        return null;
    }

    public abstract boolean onNewEntry(long entityId, int propertyId, TimePointL time, Object val);

    public static class ValueChangeCounter extends TemporalRangeQuery {
        private int change = 0;
        private Object lastVal = null;

        @Override
        public boolean onNewEntry(long entityId, int propertyId, TimePointL beginTime, Object val) {
            if(!Objects.equals(val, lastVal)) {
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

    public static class MaxFloat extends TemporalRangeQuery {
        private Float max = null;

        @Override
        public boolean onNewEntry(long entityId, int propertyId, TimePointL beginTime, Object val) {
            if (val != null) {
                float v = (Float) val;
                if (max==null || v > max) max = v;
            }
            return true;
        }

        public Object onReturn() {
            return max;
        }
    }

    public static class MaxValue extends TemporalRangeQuery {
        private Comparable max = null;

        @Override
        public boolean onNewEntry(long entityId, int propertyId, TimePointL beginTime, Object val) {
            if (val instanceof Comparable) {
                Comparable v = (Comparable) val;
                if (max==null || v.compareTo(max)>0) max = v;
            }
            return true;
        }

        public Object onReturn() {
            return max;
        }
    }

}

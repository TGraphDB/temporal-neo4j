package org.neo4j.temporal;

import org.act.temporalProperty.impl.InternalEntry;
import org.act.temporalProperty.impl.InternalKey;
import org.act.temporalProperty.impl.ValueType;
import org.act.temporalProperty.query.TimePointL;
import org.act.temporalProperty.query.range.TimeRangeQuery;
import org.act.temporalProperty.util.Slice;

/**
 * Created by song on 2018-07-26.
 */

public abstract class TemporalRangeQuery implements TimeRangeQuery
{
    private CallBack callBack = null;
    @Override
    public void setValueType(String valueType) {
        switch (valueType){
            case "INT": this.callBack = new CallBack(){ @Override public Object readSlice(Slice slice) { return slice.getInt(0); }}; return;
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
            onNewEntry(k.getEntityId(), k.getPropertyId(), k.getStartTime(), null);
        }else{
            onNewEntry(k.getEntityId(), k.getPropertyId(), k.getStartTime(), callBack.readSlice(v));
        }
        return true;
    }

    private interface CallBack{
        Object readSlice(Slice slice);
    }

    public abstract void onNewEntry(long entityId, int propertyId, TimePointL time, Object val);
}

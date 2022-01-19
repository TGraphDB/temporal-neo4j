package org.neo4j.temporal;

import org.act.temporalProperty.impl.InternalKey;
import org.act.temporalProperty.impl.ValueType;
import org.act.temporalProperty.util.Slice;
import org.act.temporalProperty.util.TemporalPropertyValueConvertor;

/**
 * supported operations: set( create new temporal property, set value for one entity)
 * Created by song on 2018-04-21.
 */
public class TemporalPropertyWriteOperation
{
    private final TimePoint end;
    private final Object value;
    private final InternalKey startKey;
    private Slice valueSlice;

    public TemporalPropertyWriteOperation(long entityId, int proId, TimePoint start, TimePoint end, Object value )
    {
        ValueType valueType;
        if(value==null)
        {
            valueType = ValueType.INVALID;
        }else{
            valueType = ValueType.fromValue( value );
        }
        this.startKey = new InternalKey( proId, entityId, start, valueType);
        this.end = end;
        this.value = value;
    }

    public long getEntityId()
    {
        return startKey.getEntityId();
    }

    public int getProId()
    {
        return startKey.getPropertyId();
    }

    public Object getValue()
    {
        return value;
    }

    public InternalKey getInternalKey()
    {
        return startKey;
    }

    public TimePoint getEnd(){
        return end;
    }

    public boolean isEndEqNow(){
        return end.isNow();
    }

    public void setValueSlice( Slice valueSlice )
    {
        this.valueSlice = valueSlice;
    }

    public Slice getValueSlice()
    {
        return valueSlice;
    }
}

package org.neo4j.temporal;

import org.act.temporalProperty.query.TimePointL;
import org.apache.commons.lang3.tuple.Triple;

import java.util.*;

/**
 * Created by song on 2018-01-17.
 */
public class TemporalIndexMetaData {
    private final boolean isNode;
    private final long id;
    private final String indexType;
    private final List<Triple<String, Integer, String>> props;
    private final TimePointL timeStart;
    private final TimePointL timeEnd;
    private final int fileCount;
    private final boolean online;

    public TemporalIndexMetaData(boolean isNode, long id, String indexType, List<Triple<String, Integer, String>> props, TimePointL start, TimePointL end, int fileCount) {
        this.isNode = isNode;
        this.id = id;
        this.indexType = indexType;
        this.props = props;
        this.timeStart = start;
        this.timeEnd = end;
        this.fileCount = fileCount;
        this.online = false;
    }

    public long getId() {
        return id;
    }

    public String getIndexType() {
        return indexType;
    }

    public TimePointL getTimeStart() {
        return timeStart;
    }

    public TimePointL getTimeEnd() {
        return timeEnd;
    }

    public boolean isOnline()
    {
        return online;
    }

    public int getFileCount() {
        return fileCount;
    }

    public List<Triple<String, Integer, String>> getProps() {
        return props;
    }

    public boolean isNode() {
        return isNode;
    }

    @Override
    public String toString() {
        return "TPIndexMetaData{" +
                "id=" + id +
                ", type=" + indexType +
                ", propertyList=" + props +
                ", timeStart=" + timeStart +
                ", timeEnd=" + timeEnd +
                ", fileCount=" + fileCount +
                ", online=" + online +
                '}';
    }

}

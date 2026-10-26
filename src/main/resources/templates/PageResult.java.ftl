package ${package}.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 分页输出；total 为符合条件的记录总数，records 为当前页。 */
public class PageResult<T> {

    private final List<T> records;
    private final long total;

    public PageResult(List<T> records, long total) {
        this.records = Collections.unmodifiableList(new ArrayList<T>(records));
        this.total = total;
    }

    public List<T> getRecords() {
        return records;
    }

<#if stringNumbers>
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
</#if>
    public long getTotal() {
        return total;
    }
}

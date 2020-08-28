package shared;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GrafanaUtil {

    public static final String TYPE_TIMESERIES = "timeseries";
    public static final String TYPE_TABLE = "table";

    public static final int TABLE_TIME_COL = 0;
    public static final int TIMESERIES_VALUE_COL = 0;
    public static final int TIMESERIES_TIME_COL = 1;

    public static final Map<String, String> TABLE_COL_TIME = newColumn("Time", "time");

    public static Map<String,String> newColumn(String text, String type) {
        Map<String, String> col = new HashMap<>();
        col.put("text", text);
        col.put("type", type);
        return col;
    }

    /**
     * @param target     the metric to show
     * @param datapoints the points, starting from small/earliest to large/latest
     * @return
     */
    public static Map<String, Object> newTimeseriesResult(String target, List<List<Number>> datapoints) {
        Map<String, Object> resultForTarget = new HashMap<>();
        resultForTarget.put("target", target);
        resultForTarget.put("datapoints", datapoints);
        return resultForTarget;
    }

    /**
     * @param columns   description of each col of the table
     * @param rows      the rows of the table, starting from small/earliest to large/latest
     * @return
     */
    public static Map<String, Object> newTableResult(List<Map<String, String>> columns, List<List<Number>> rows) {
        Map<String, Object> resultForTarget = new HashMap<>();
        resultForTarget.put("type", TYPE_TABLE);
        resultForTarget.put("columns", columns);
        resultForTarget.put("rows", rows);
        return resultForTarget;
    }

    /**
     * request from grafana JSON plugin comes in this format:
     * {
     *  "requestId":"Q107",
     *  "timezone":"",
     *  "panelId":2,
     *  "dashboardId":null,
     *  "range":{"from":"2019-09-09T07:46:58.716Z","to":"2019-09-09T13:46:58.718Z","raw":{"from":"now-6h","to":"now"}},
     *  "interval":"20s",
     *  "intervalMs":20000,
     *  "targets":[{"data":"test name","target":"latency","refId":"A","hide":false,"type":"timeseries"}],
     *  "maxDataPoints":929,
     *  "scopedVars":{"__from":{"text":"1568013989863","value":"1568013989863"},"__to":{"text":"1568035589863","value":"1568035589863"},"__interval":{"text":"20s","value":"20s"},"__interval_ms":{"text":"20000","value":20000}},
     *  "startTime":1568036818720,
     *  "rangeRaw":{"from":"now-6h","to":"now"},
     *  "adhocFilters":[]
     * }
     */
    public static long getStartDate(Map<String, Object> query) {
        Map<String, Object> scopedVars = (Map<String, Object>)query.get("scopedVars");
        if (scopedVars == null) return 0L;
        Map<String, Object> __from = (Map<String, Object>) scopedVars.get("__from");
        return Long.parseLong((String)__from.get("value"));
    }
}

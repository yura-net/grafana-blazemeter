package shared;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import net.yura.io.JSONUtil;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GrafanaTestUtil {

    private static Map<String, List> getFirstResult(String content) throws Exception {
        List<Map<String, List>> results = (List<Map<String, List>>)new JSONUtil().load(new StringReader(content));

        assertTrue(results.size() == 1);

        return results.get(0);
    }

    public static void assertTableCorrect(String content) throws Exception {
        Map<String, List> firstResult = getFirstResult(content);

        List<Map<String,String>> columns = firstResult.get("columns");
        List<List<Number>> rows = (List<List<Number>>)firstResult.get("rows");

        System.out.println("cols size " + columns.size());
        System.out.println("rows size " + rows.size());

        Number prev = 0;
        for(List<Number> row : rows) {

            assertTrue(columns.size() == row.size());

            Number currentName = row.get(GrafanaUtil.TABLE_TIME_COL);
            if(prev.longValue() >= currentName.longValue()) {
                fail("wrong order " + prev.longValue() + " >= " + currentName.longValue());
            }
            prev = currentName;
        }
    }

    public static void assertTimeseriesCorrect(String content) throws Exception {
        Map<String, List> firstResult = getFirstResult(content);

        List<List<Number>> datapoints = (List<List<Number>>)firstResult.get("datapoints");

        Number prev = 0;
        for(List<Number> datapoint : datapoints) {

            assertTrue(datapoint.size() == 2);

            Number currentValue = datapoint.get(GrafanaUtil.TIMESERIES_VALUE_COL);
            assertNotNull(currentValue);

            Number currentName = datapoint.get(GrafanaUtil.TIMESERIES_TIME_COL);
            if(prev.longValue() >= currentName.longValue()) {
                fail("wrong order " + prev +" >= " + currentName);
            }
            prev = currentName;
        }
    }
}

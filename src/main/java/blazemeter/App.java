package blazemeter;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.stream.Collectors;
import net.yura.io.JSONUtil;
import shared.GatewayResponse;
import shared.GrafanaUtil;

/**
 * Handler for requests to Lambda function.
 */
public class App implements RequestHandler<Map<String,Object>, GatewayResponse> {

    private static final int workspaceId;

    static {
        String workspace = System.getenv("BLAZEMETER_WORKSPACE");
        if (workspace == null) {
            throw new IllegalStateException("env BLAZEMETER_WORKSPACE not set");
        }
        workspaceId = Integer.parseInt(workspace);
    }

    /**
     * KPI: "responseTime"
     */
    private static final String METRIC_TIME = "time";
    /**
     * KPI: "errPercent"
     */
    private static final String METRIC_ERRORS = "errors";
    /**
     * KPI: "hitCount"
     */
    private static final String METRIC_HITS = "hits";
    /**
     * KPI: "bandwidth"
     */
    private static final String METRIC_BANDWIDTH = "bandwidth";
    /**
     * KPI: "latency"
     */
    private static final String METRIC_LATENCY = "latency";
    /**
     * KPI: "stdResp"
     */
    private static final String METRIC_SDV_RESPONSE_TIME = "standard deviation of response time";
    /**
     * KPI: "testDuration"
     */
    private static final String METRIC_DURATION = "duration";

    private static final String METRIC_500 = "500";

    private static final String METRIC_NON_HTTP_ERROR = "non-http";

    private JSONUtil util = new JSONUtil();

    /**
     * async {@link java.net.http.HttpClient} is only available in java-11, so for now we use blocking {@link java.net.HttpURLConnection}
     * parallelStream is for CPU-ONLY tasks, as it splits the task into thread-per-cpu-core ForkJoinPool
     * this means parallelStream should NEVER be used for anything doing IO or Network
     */
    private static ExecutorService executorService = Executors.newCachedThreadPool();

    public GatewayResponse handleRequest(final Map<String,Object> input, final Context context) {

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Custom-Header", "application/json");
        try {

            String path = input == null ? null : (String)input.get("path");
            String body = input == null ? null : (String)input.get("body");

            Object bodyJson = util.load(new StringReader(String.valueOf(body)));

            final List results;

            // version 0.5.0 uses '/search' and 0.6.0 and newer use '/metrics'
            if ("/search".equals(path) || "/metrics".equals(path)) {
                // TODO not sure what this is for?
                String target = (String) ((Map)bodyJson).get("target");
                results = Arrays.asList(
                        METRIC_TIME,
                        METRIC_ERRORS,
                        METRIC_HITS,
                        METRIC_BANDWIDTH,
                        METRIC_LATENCY,
                        METRIC_SDV_RESPONSE_TIME,
                        METRIC_DURATION,
                        METRIC_500,
                        METRIC_NON_HTTP_ERROR);
            }
            else if ("/query".equals(path)) {
                results = query((Map)bodyJson);
            }
            else if ("/annotations".equals(path)) {
                results = Collections.emptyList();
            }
            else if ("/tag-keys".equals(path)) {
                // optional
                // TODO this does not seem to ever be called???
                Map<String,String> tag = new HashMap<>();
                tag.put("type", "string");
                tag.put("text", "Test");
                results = Collections.singletonList(tag);
            }
            else if ("/tag-values".equals(path)) {
                // optional
                // TODO this does not seem to ever be called???
                results = getAllTests();
            }
            else {
                // empty for now
                results = new ArrayList<>();
                if (input != null) {
                    results.add(input);
                }
            }

            StringWriter out = new StringWriter();
            util.save(out, results);

            String output = out.toString();
            return new GatewayResponse(output, headers, 200);
        }
        catch (Exception e) {
            System.err.println("ERROR with request " + e + " " + input);
            e.printStackTrace();

            String output = String.format("{ \"error\": \"%s\" }", e.toString());
            return new GatewayResponse(output, headers, 500);
        }
    }

    private List<Map<String,Object>> query(Map<String, Object> query) throws IOException {

        List<Map<String,Object>> targets = query == null ? Collections.emptyList() : (List<Map<String,Object>>)query.get("targets");

        List<Map<String,Object>> results = new ArrayList<>();

        long testId = -1;
        List<Map<String, Object>> historicTestData = null;

        long fromDate = GrafanaUtil.getStartDate(query);

        for (Map<String,Object> target : targets) {

            Object data = target.get("data"); // we can get the Test ID or name from here
            Object test = getTestSearchString(data);
            Object project = getProjectSearchString(data);
            String name = (String)target.get("target");
            String type = (String)target.get("type");

            List<Map<String,Object>> ids = getTestIds(test, project); // always have at least 1 ID

            if (GrafanaUtil.TYPE_TABLE.equals(type) && ids.size() > 1) {
                results.add(newMultiTestTable(name, ids, String.valueOf(test), fromDate));
            }
            else {
                long newId = getSingleTestId(ids);

                if (newId != testId) { // test id is different, reload the data
                    testId = newId;
                    historicTestData = makeTrendingReportsOverTime(testId, fromDate, (Map<String, Object>) getPageContents("tests/" + testId + "/masters-summaries?limit=100"));
                }

                if (GrafanaUtil.TYPE_TIMESERIES.equals(type) || "timeserie".equals(type)) { // support simpleJSON and JSON plugin
                    results.add(newTimeseries(name, historicTestData));
                }
                else { // else must be "table"
                    results.add(newTable(name, historicTestData));
                }
            }
        }

        return results;
    }

    /**
     * to see example of results from blazemeter
         curl --request GET \
         --url 'https://a.blazemeter.com/api/v4/tests/6885287/masters-summaries?limit=10' \
         --header 'accept: application/json' \
         --header 'authorization: Basic NmQ5NTBhYmFjMGFjMTEwZjlmZTVjNWJjOjFhMjExZTQxNzk2N2QyYThjZWEwZWYwMDQxYzkzYjRkNjg5NmExODUxNjEzODJhMWQ1M2QzNzNiZmJhOGE1NjFiNzUzNGRjYQ=='
     */
    private List<Map<String, Object>> makeTrendingReportsOverTime(long testId, long fromDate, Map<String, Object> historicTestData) {
        Object result = historicTestData.get("result");
        if (result instanceof Map) {
            Map<String, Object> resultMap = (Map<String, Object>) result;
            List<Map<String, Object>> labels = (List<Map<String, Object>>) resultMap.get("labels");
            return labels.stream().filter(item -> getSessionTime(item) >= fromDate).collect(Collectors.toList());
        }
        System.out.println("no results for: " + testId);
        return Collections.emptyList();
    }

    /**
     * @return can be string or number
     */
    private Object getTestSearchString(Object data) {
        if (data instanceof Map) {
            return ((Map) data).get("test");
        }
        return data;
    }

    /**
     * @return can be string or number
     */
    private Object getProjectSearchString(Object data) {
        if (data instanceof Map) {
            return ((Map) data).get("project");
        }
        return null;
    }

    /**
     * https://a.blazemeter.com/api/v4/tests?workspaceId=315312&name=-varied-typical-and-anomalous-requests.jmx-mock&limit=50
     * https://a.blazemeter.com/api/v4/tests?projectId=395384&name=-repeated-large-typical-request.jmx-gpu&limit=10
     */
    private List<Map<String,Object>> getTestIds(Object test, Object project) throws IOException {
        if (project != null) {
            long projectId = getProjectId(project);
            Map<String, Object> value = (Map<String, Object>) getPageContents("tests?projectId=" + projectId + "&name=" + encodeValue(String.valueOf(test)) + "&limit=100");
            List<Map<String, Object>> result = (List<Map<String, Object>>) value.get("result");
            if (!result.isEmpty()) {
                return result;
            }
        }

        if (test instanceof String) {
            Map<String, Object> value = (Map<String, Object>) getPageContents("tests?workspaceId=" + workspaceId + "&name=" + encodeValue(String.valueOf(test)) + "&limit=100");
            List<Map<String, Object>> result = (List<Map<String, Object>>) value.get("result");
            if (!result.isEmpty()) {
                return result;
            }
        }

        long id = (test instanceof Number) ? ((Number)test).longValue() : 6867611; // 6885287
        Map<String,Object> result = new HashMap<>();
        result.put("id", id);
        return Collections.singletonList(result);
    }

    private static String encodeValue(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        }
        catch (UnsupportedEncodingException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * https://a.blazemeter.com/api/v4/projects?workspaceId=315312&name=Finch
     */
    private long getProjectId(Object value) throws IOException {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        Map<String,Object> response = (Map<String,Object>)getPageContents("projects?workspaceId=" + workspaceId + "&name=" + value);
        List<Map<String,Object>> results = (List<Map<String,Object>> )response.get("result");
        return results.stream().findFirst().map(result -> (long)result.get("id")).orElseThrow(() -> new IllegalArgumentException("bad project " + value));
    }

    private long getSingleTestId(List<Map<String,Object>> result) {
        return (long)result.get(0).get("id");
    }

    private Map<String,Object> newMultiTestTable(String target, List<Map<String, Object>> tests, String search, long fromDate) {

        class Result implements Comparable<Result> {
            String name;
            List<Map<String, Object>> items;
            int pos;
            Result(String name, List<Map<String, Object>> items) {
                this.name = name;
                this.items = items;
            }
            long getTime() {
                if (items.size() <= pos) return -1;
                return getSessionTime(items.get(pos));
            }
            Number getValueAtTime(long time) {
                if (time == getTime()) {
                    return getTestMetric(items.get(pos++), target);
                }
                return null;
            }
            @Override
            public int compareTo(Result o) {
                return Long.compare(getTime(), o.getTime());
            }
        }

        List<Map<String, String>> columns = new ArrayList<>();
        columns.add(GrafanaUtil.TABLE_COL_TIME);

        /**
         * do NOT use parallelStream here
         * parallelStream is for CPU-ONLY tasks, as it splits the task into thread-per-cpu-core ForkJoinPool
         * this means parallelStream should NEVER be used for anything doing IO or Network
         */
        List<Result> results = tests.stream().map(test -> {
            long testId = (long) test.get("id");
            return getPageContentsAsync("tests/" + testId + "/masters-summaries?limit=100").thenApply(r -> {
                List<Map<String, Object>> testResults = makeTrendingReportsOverTime(testId, fromDate, (Map<String, Object>)r);
                String testName = (String) test.get("name");
                testName = testName.replace(search, "");
                testName = "".equals(testName) ? search : testName;
                // TODO can have same name but different projects
                // long projectId = (long)test.get("projectId");
                // https://a.blazemeter.com/api/v4/projects?workspaceId=315312&limit=10
                return new Result(testName, testResults);
            });
        }).collect(Collectors.toList()).stream().map(CompletableFuture::join).filter(result -> {
            // we only care about tests with results
            if (!result.items.isEmpty()) {
                columns.add(GrafanaUtil.newColumn(result.name, "number"));
                return true;
            }
            return false;
        }).collect(Collectors.toList());

        List<List<Number>> rows = new LinkedList<>();
        while (!results.isEmpty()) {
            // find largest first number
            long time = Collections.max(results).getTime();

            if (time == -1) { // none of the results have any Values left
                break;
            }
            else {
                List<Number> datapoint = new ArrayList<>();
                datapoint.add(time);
                results.forEach(singleTestHistory -> datapoint.add(singleTestHistory.getValueAtTime(time)));
                rows.add(0, datapoint);
            }
        }
        return GrafanaUtil.newTableResult(columns, rows);
    }

    private Map<String,Object> newTimeseries(String target, List<Map<String, Object>> labels) {

        List<List<Number>> datapoints;

        if (METRIC_500.equals(target) || METRIC_NON_HTTP_ERROR.equals(target)) {
            /**
             * do NOT use parallelStream here
             * parallelStream is for CPU-ONLY tasks, as it splits the task into thread-per-cpu-core ForkJoinPool
             * this means parallelStream should NEVER be used for anything doing IO or Network
             */
            datapoints = labels.stream().map(a -> getTimeseries(a, target)).collect(Collectors.toList())
                               .stream().map(CompletableFuture::join).collect(Collectors.toList());
        }
        else {
            datapoints = labels.stream().map(item -> {
                List<Number> datapoint = new ArrayList<>();
                datapoint.add(getTestMetric(item, target));
                datapoint.add(getSessionTime(item));
                return datapoint;
            }).collect(Collectors.toList());
        }

        // grafana expects items to be earliest to latest order, or the tooltip wont work
        Collections.reverse(datapoints);

        return GrafanaUtil.newTimeseriesResult(target, datapoints);
    }

    private CompletableFuture<List<Number>> getTimeseries(Map<String, Object> item, String target) {
        CompletableFuture<Number> answer;
        if (getTestMetric(item, METRIC_ERRORS).doubleValue() == 0.0) {
            answer = CompletableFuture.completedFuture(0L);
        }
        else {
            long master = (long) item.get("id");
            // example result: https://a.blazemeter.com/api/v4/masters/20884977/reports/errorsreport/data
            answer = getPageContentsAsync("masters/" + master + "/reports/errorsreport/data").thenApply(a -> {
                try {
                    List<Map<String, Object>> results = (List<Map<String, Object>>) ((Map<String, Object>) a).get("result");
                    if (results.isEmpty()) {
                        return 0L;
                    }
                    Map<String, Object> result = results.get(0);
                    // "errors": [{ "m": "Internal Server Error", "rc": "500", "count": 26 }]
                    // "errors": [{ "m": "Non HTTP response message: Cannot assign requested address (Address not available)", "rc": "Non HTTP response code: java.net.NoRouteToHostException", "count": 17936 }]
                    List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
                    return errors.stream().filter(
                            METRIC_500.equals(target) ?
                            error -> {
                                // find any HTTP server error 5xx
                                String rc = (String)error.get("rc");
                                return rc.chars().allMatch(Character::isDigit) && rc.startsWith("5");
                            } :
                            error -> {
                                // find any non-http code error
                                String rc = (String)error.get("rc");
                                return rc.chars().anyMatch(ch -> !Character.isDigit(ch));
                            }
                            ).findFirst().map(error -> (Number) error.get("count")).orElse(0L);
                } catch (Exception ex) {
                    throw new RuntimeException("bad master " + master, ex);
                }
            });
        }

        return answer.thenApply(n -> {
            List<Number> datapoint = new ArrayList<>();
            datapoint.add(n);
            datapoint.add(getSessionTime(item));
            return datapoint;
        });
    }

    /**
     * sometimes the responce can come back like this, if the current test is currently running and the results are not in yet
     * {
     *  duration={count=-9223372036854775807},
     *  hits={avg=0},
     *  size=[],
     *  responseTime=[],
     *  session={created=1571856547, name=pets-1000-attack-requests.jmx-gpu, ended=null, updated=1571856547},
     *  latency=[],
     *  id=21103694,
     *  errors={percent=0}
     * }
     */
    private Number getTestMetric(Map<String, Object> item, String target) {
        try {
            if (METRIC_TIME.equals(target)) {
                Object responseTime = item.get("responseTime");
                // can be empty List if results are not in yet
                return responseTime instanceof Map ? (Number) ((Map<String, Object>) responseTime).get("avg") : null;
            }
            if (METRIC_HITS.equals(target)) {
                Map<String, Object> hits = (Map) item.get("hits");
                return (Number) hits.get("avg");
            }
            if (METRIC_BANDWIDTH.equals(target)) {
                Object size = item.get("size");
                // can be empty List if results are not in yet
                return size instanceof Map ? (Number) ((Map<String, Object>)size).get("avg") : null;
            }
            if (METRIC_LATENCY.equals(target)) {
                Object latency = item.get("latency");
                // can be empty List if results are not in yet
                return latency instanceof Map ? (Number) ((Map<String, Object>) latency).get("avg") : null;
            }
            if (METRIC_SDV_RESPONSE_TIME.equals(target)) {
                Object responseTime = item.get("responseTime");
                // can be empty List if results are not in yet
                return responseTime instanceof Map ? (Number) ((Map<String, Object>)responseTime).get("std") : null;
            }
            if (METRIC_DURATION.equals(target)) {
                Map<String, Object> duration = (Map) item.get("duration");
                return (Number) duration.get("count");
            }
            // if METRIC_ERROR
            Map<String, Object> errors = (Map) item.get("errors");
            return (Number) errors.get("percent");
        }
        catch (RuntimeException ex) {
            System.err.println("ERROR could not get metric from object " + target + " " + item);
            throw ex;
        }
    }

    private Map<String,Object> newTable(String target, List<Map<String, Object>> labels) {
        List<Map<String, String>> columns = new ArrayList<>();
        columns.add(GrafanaUtil.TABLE_COL_TIME);
        columns.add(GrafanaUtil.newColumn(METRIC_TIME, "number"));
        columns.add(GrafanaUtil.newColumn(METRIC_ERRORS, "number"));
        columns.add(GrafanaUtil.newColumn(METRIC_HITS, "number"));
        columns.add(GrafanaUtil.newColumn(METRIC_BANDWIDTH, "number"));
        columns.add(GrafanaUtil.newColumn(METRIC_LATENCY, "number"));
        columns.add(GrafanaUtil.newColumn(METRIC_SDV_RESPONSE_TIME, "number"));
        columns.add(GrafanaUtil.newColumn(METRIC_DURATION, "number"));

        List<List<Number>> rows = labels.stream().map(item -> {
            List<Number> datapoint = new ArrayList<>();
            columns.forEach(col -> {
                if (col == GrafanaUtil.TABLE_COL_TIME) {
                    datapoint.add(getSessionTime(item));
                }
                else {
                    String name = col.get("text");
                    datapoint.add(getTestMetric(item, name));
                }
            });
            return datapoint;
        }).collect(Collectors.toList());

        // grafana expects items to be earliest to latest order, or the tooltip wont work
        Collections.reverse(rows);

        return GrafanaUtil.newTableResult(columns, rows);
    }

    public Object getPageContents(String request) throws IOException {

        // parallelStream is for CPU-ONLY tasks, as it splits the task into thread-per-cpu-core ForkJoinPool
        // this means parallelStream should NEVER be used for anything doing IO or Network
        if (Thread.currentThread() instanceof ForkJoinWorkerThread && ((ForkJoinWorkerThread)Thread.currentThread()).getPool() == ForkJoinPool.commonPool()) {
            throw new AssertionError("network io on common pool");
        }

        URL url = new URL("https://a.blazemeter.com/api/v4/" + request); // https://checkip.amazonaws.com

        URLConnection connection = url.openConnection();

        String userCredentials = System.getenv("BLAZEMETER_LOGIN"); // in the format "user:password"
        if (userCredentials == null) {
            throw new IllegalStateException("env BLAZEMETER_LOGIN not set");
        }

        String basicAuth = "Basic " + new String(Base64.getEncoder().encode(userCredentials.getBytes()));

        connection.setRequestProperty("Authorization", basicAuth);

        //try(BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
        //    return br.lines().collect(Collectors.joining(System.lineSeparator()));
        //}
        return util.load(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
    }

    public CompletableFuture<Object> getPageContentsAsync(String request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println(Thread.currentThread() + " SENDING...");
                Object result = getPageContents(request);
                System.out.println(Thread.currentThread() + " DONE...");
                return result;
            }
            catch (IOException ex) {
                // Supplier.get() does not throw exception like Callable.call() does, even though its called inside a try
                throw new RuntimeException(ex);
            }
        }, executorService); // we dont want to use the default pool as that only has 1 thread per cpu core
    }

    public List<Map<String,Object>> getAllTests() throws IOException {

        Map<String, Object> value = (Map<String, Object>)getPageContents("tests?workspaceId=" + workspaceId + "&limit=10000");

        List<Map<String,Object>> result = (List<Map<String,Object>>)value.get("result");

        return result.stream().map(item -> {
            Map<String, Object> newItem = new HashMap<>();
            newItem.put("text", item.get("name"));
            newItem.put("value", item.get("id"));
            return newItem;
        }).collect(Collectors.toList());
    }

    // sadly this does not work
    public void handleRequestXXX(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Custom-Header", "application/json");

        Map<String, Object> response = new HashMap<>();
        response.put("headers", headers);

        try {
            Object value = this.getPageContents("tests/6885287");

            response.put("statusCode", 200);
            response.put("body", value);
        }
        catch (Exception e) {

            Map<String, Object> body = new HashMap<>();
            body.put("error", e.toString());

            response.put("statusCode", 500);
            response.put("body", body);
        }

        util.save(outputStream, response);
    }

    private static long getSessionTime(Map<String, Object> item) {
        Map<String, Object> session = (Map)item.get("session");
        // used to be "created" but that does not always return results in the right order.
        return (long) session.get("updated") * 1000;
    }
}

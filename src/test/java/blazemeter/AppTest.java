package blazemeter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import shared.GatewayResponse;
import shared.GrafanaTestUtil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class AppTest {
  @Test
  public void successfulResponse() {
    App app = new App();
    GatewayResponse result = app.handleRequest(null, null);
    assertEquals(result.getStatusCode(), 200);
    assertEquals(result.getHeaders().get("Content-Type"), "application/json");
    String content = result.getBody();
    assertNotNull(content);

    System.out.println(content);
  }

  @Test
  public void timeseries() throws Exception {
    App app = new App();
    Map<String, Object> input = new HashMap<>();
    input.put("path", "/query");
    input.put("body", "{\"targets\":[{\"data\":\"pets-varied-typical-and-anomalous-requests.jmx-mock\",\"target\":\"latency\",\"type\":\"timeseries\"}]}");
    GatewayResponse result = app.handleRequest(input, null);
    String content = result.getBody();
    assertNotNull(content);

    System.out.println(content);
    GrafanaTestUtil.assertTimeseriesCorrect(content);
  }

  @Test
  public void timeseries_500() throws Exception {
    App app = new App();
    Map<String, Object> input = new HashMap<>();
    input.put("path", "/query");
    input.put("body", "{\"targets\":[{\"data\":\"pets-1000-attack-requests.jmx-gpu\",\"target\":\"500\",\"type\":\"timeseries\"}]}");
    GatewayResponse result = app.handleRequest(input, null);
    String content = result.getBody();
    assertNotNull(content);

    System.out.println(content);
    GrafanaTestUtil.assertTimeseriesCorrect(content);
  }

  @Test
  public void timeseries_other_errors() throws Exception {
    App app = new App();
    Map<String, Object> input = new HashMap<>();
    input.put("path", "/query");
    input.put("body", "{\"targets\":[{\"data\":\"multiendpoint-reset\",\"target\":\"non-http\",\"type\":\"timeseries\"}]}");
    GatewayResponse result = app.handleRequest(input, null);
    String content = result.getBody();
    assertNotNull(content);

    System.out.println(content);
    GrafanaTestUtil.assertTimeseriesCorrect(content);
  }

  @Test
  public void table() throws Exception {
    App app = new App();
    Map<String, Object> input = new HashMap<>();
    input.put("path", "/query");
    input.put("body", "{\"targets\":[{\"data\":\"pets-varied-typical-and-anomalous-requests.jmx-mock\",\"target\":\"latency\",\"type\":\"table\"}]}");
    GatewayResponse result = app.handleRequest(input, null);
    String content = result.getBody();
    assertNotNull(content);

    System.out.println(content);
    GrafanaTestUtil.assertTableCorrect(content);
  }

  /**
   * @see App#newMultiTestTable(String, List, String, long)
   */
  @Test
  public void multi_test_table() throws Exception {
    App app = new App();
    Map<String, Object> input = new HashMap<>();
    input.put("path", "/query");
    input.put("body", "{\"targets\":[{\"data\":\"-varied-typical-and-anomalous-requests.jmx-mock\",\"target\":\"latency\",\"type\":\"table\"}]}");
    GatewayResponse result = app.handleRequest(input, null);
    String content = result.getBody();
    assertNotNull(content);

    System.out.println(content);
    GrafanaTestUtil.assertTableCorrect(content);
  }

  @Test
  public void multi_test_table_by_project() throws Exception {
    App app = new App();
    Map<String, Object> input = new HashMap<>();
    input.put("path", "/query");
    input.put("body", "{\"targets\":[{\"data\":{\"project\":\"Finch\",\"test\":\"-repeated-large-typical-request.jmx-gpu\"},\"target\":\"latency\",\"type\":\"table\"}]}");
    GatewayResponse result = app.handleRequest(input, null);
    String content = result.getBody();
    assertNotNull(content);

    System.out.println(content);
    GrafanaTestUtil.assertTableCorrect(content);
  }

  @Test
  public void multi_test_table_by_project_last_5_min() throws Exception {
    App app = new App();
    Map<String, Object> input = new HashMap<>();
    input.put("path", "/query");
    input.put("body", "{\"targets\":[{\"data\":{\"project\":\"Finch\",\"test\":\"-repeated-large-typical-request.jmx-gpu\"},\"target\":\"latency\",\"type\":\"table\"}]," +
            "\"scopedVars\":{\"__from\":{\"value\":\"" + (System.currentTimeMillis() - 300000) + "\"}}}");
    GatewayResponse result = app.handleRequest(input, null);
    String content = result.getBody();
    assertNotNull(content);

    System.out.println(content);
    GrafanaTestUtil.assertTableCorrect(content);
  }

  @Test
  public void multi_test_table_by_project_pets() throws Exception {
    App app = new App();
    Map<String, Object> input = new HashMap<>();
    input.put("path", "/query");
    input.put("body", "{\"targets\":[{\"data\":\"pets- -mock\",\"target\":\"latency\",\"type\":\"table\"}]}");
    GatewayResponse result = app.handleRequest(input, null);
    String content = result.getBody();
    assertNotNull(content);

    System.out.println(content);
    GrafanaTestUtil.assertTableCorrect(content);
  }

  @Test
  public void get_annotation() throws Exception {

    App app = new App();
    Map<String, Object> input = new HashMap<>();
    input.put("path", "/annotations");
    input.put("body", "{\"annotation\": {\"name\": \"Annotations & Alerts\",\"datasource\": \"Blazemeter\",\"enable\": true,\"iconColor\": \"rgba(0, 211, 255, 1)\"},\"range\": {\"from\": \"2019-09-18T12:05:13.108Z\",\"to\": \"2019-10-18T12:05:13.109Z\",\"raw\": {\"from\": \"now-30d\",\"to\": \"now\"}},\"rangeRaw\": {\"from\": \"now-30d\",\"to\": \"now\"},\"variables\": {\"__from\": {\"text\": \"1568808313084\",\"value\": \"1568808313084\"},\"__to\": {\"text\": \"1571400313087\",\"value\": \"1571400313087\"}}}");
    GatewayResponse result = app.handleRequest(input, null);

    String content = result.getBody();
    assertNotNull(content);

    System.out.println(content);
  }
}

# grafana-blazemeter
A AWS Lambda for Grafana JSON datasource to get data from blazemeter.com

plugin market: https://grafana.com/grafana/plugins/simpod-json-datasource/  
plugin source: https://github.com/simPod/GrafanaJsonDatasource

currently only supports version 0.5.0 of the plugin, url need updating to support latest version

## Setup

to use set environment variable
 - BLAZEMETER_LOGIN to user:password for test
 - BLAZEMETER_WORKSPACE to your workspace id

to run
 - use maven to package the project
 - create AWS Lambda, set env vars and upload Grafana-1.0.jar
 - Add JSON data source to Grafana and point it at the AWS Lambda

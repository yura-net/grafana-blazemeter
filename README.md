# grafana-blazemeter
A AWS Lambda for Grafana JSON datasource to get data from blazemeter.com

to use set environment variable
 - BLAZEMETER_LOGIN to user:password for test
 - BLAZEMETER_WORKSPACE to your workspace id

to run
 - use maven to package the project
 - create AWS Lambda, set env vars and upload Grafana-1.0.jar
 - Add JSON data source to Grafana and point it at the AWS Lambda

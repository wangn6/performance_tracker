This project support to store the JMeter support result to the ElasticSearch and then we can use the Kibana to visualize the result of performance.

To use it:
1. Using IDEA to open the project, by New-> Project From Existing Sourses
2. Then specify the build.gradle to import the project
3. To review the code or build the project
4. Call 'Gradle shadowJar' to generate the fat jar file
5. Copy the fat jar file which can be found at build/libs/jmeter_es_backendlistener-1.0-SNAPSHOT-all.jar to the JMeter folder JMeter_Folder/lib/ext
6. Add the backend listener into the test plan, then specify the host of ES to store the result to
7. Run your test plan and check the test resul

Any comments or suggestions, please contact wangyj2005@gmail.comt

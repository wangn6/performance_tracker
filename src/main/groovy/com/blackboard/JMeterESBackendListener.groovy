package com.blackboard

/**
 * Created by nwang on 20/12/2016.
 */

import java.text.SimpleDateFormat;
import java.util.*;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;

import org.elasticsearch.transport.client.PreBuiltTransportClient;

public class JMeterESBackendListener extends
        AbstractBackendListenerClient {
    private static TransportClient client;
    private static List<Map<String, Object>> jsonObjects = new ArrayList<Map<String, Object>>();
    private final static int MaxiumBulkSize = 50;
    private String indexName;
    private String dateTimeAppendFormat;
    private String sampleType;
    private String runId;
    private String mBaasBuild;
    private String machineName;
    private String testPlanName;
    private HashMap<String, String> environmentVariables;

    private long offset;
    private static final String ENVIRONMENT_VARIABLE_PREFIX = "PT_";
    private static final int DEFAULT_ELASTICSEARCH_PORT = 9300;
    private static final String TIMESTAMP = "timestamp";
    private static final String VAR_DELIMITER = "~";
    private static final String VALUE_DELIMITER = "=";
    private static final Logger LOGGER = LoggingManager.getLoggerForClass();
    private static final String ELASTICSEARTCH_HOST = "10.75.60.76";
    private static final String DEFAULT_INDEX_NAME = "jmeter-elasticsearch";
    private static final String DEFAULT_DOC_TYPE = "SampleResult";
    @Override
    public void setupTest(BackendListenerContext context) throws Exception {

        LOGGER.info("SetupTest");

        Iterator<String> iterator = context.getParameterNamesIterator();

        while (iterator.hasNext()) {
            String paraName = iterator.next();
            Object paraValue = context.getParameter(paraName);
            LOGGER.info(paraName + ":" + paraValue.toString());
        }
        environmentVariables = getEnvironmentVariables(context);

        indexName = DEFAULT_INDEX_NAME;
        sampleType = DEFAULT_DOC_TYPE;
        dateTimeAppendFormat = "-yyyy.MM.dd";

        String elasticsearchCluster = ELASTICSEARTCH_HOST + ":" + DEFAULT_ELASTICSEARCH_PORT;
        String[] servers = elasticsearchCluster.split(",");
        String normalizedTime = "2011-09-02 20:20:20.000-00:00";

        try
        {
            machineName = InetAddress.getLocalHost().getHostName();
        }
        catch (Exception ex)
        {
            machineName = "unknown";
        }


        testPlanName = getEnvironmentVariable("TestPlan", context);
        if(testPlanName.contains("/"))
        {
            testPlanName = testPlanName.substring(testPlanName.lastIndexOf('/') + 1);
        }

        mBaasBuild = getEnvironmentVariable("MBaasBuild", context);
        String b2Build = getEnvironmentVariable("B2Build", context);
        if(b2Build.isEmpty())
        {
            b2Build = context.getParameter("B2Build");
        }

        runId = getEnvironmentVariable("RunId",context);
        if(runId == null || runId.isEmpty())
        {
            runId = UUID.randomUUID().toString();
        }

        if (dateTimeAppendFormat != null && dateTimeAppendFormat.trim().equals("")) {
            dateTimeAppendFormat = null;
        }

        try {
            client = new PreBuiltTransportClient(Settings.EMPTY);
        } catch (Exception ex) {
            LOGGER.info("Exeption occured when initialize the test");
            LOGGER.info(ex.getMessage());
        }

        for (String serverPort : servers) {
            String[] serverAndPort = serverPort.split(":");
            int port = DEFAULT_ELASTICSEARCH_PORT;
            if (serverAndPort.length == 2) {
                port = Integer.parseInt(serverAndPort[1]);
            }
            LOGGER.info("Add the transport Address ${serverAndPort[0]}:${port}");
            client.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(serverAndPort[0], port)));
        }

        if (normalizedTime != null && normalizedTime.trim().length() > 0) {
            SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSX");
            Date d = sdf2.parse(normalizedTime);
            long normalizedDate = d.getTime();
            Date now = new Date();
            offset = now.getTime() - normalizedDate;
        }

        super.setupTest(context);
    }

    @Override
    public void handleSampleResults(List<SampleResult> results,
                                    BackendListenerContext context) {
        String indexNameToUse = indexName;
        for (SampleResult result : results) {
            Map<String, Object> jsonObject = getMap(result);
            if (dateTimeAppendFormat != null) {
                SimpleDateFormat sdf = new SimpleDateFormat(dateTimeAppendFormat);
                indexNameToUse = indexName + sdf.format(jsonObject.get(TIMESTAMP));
            }
            saveSampleResult2ElasticSearch(indexNameToUse, jsonObject, false);
        }
    }

    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        LOGGER.info("teardownTest");

        SimpleDateFormat sdf = new SimpleDateFormat(dateTimeAppendFormat);
        if(jsonObjects.size() > 0) {
            Map<String, Object> jsonObject = jsonObjects.get(0);
            String indexNameToUse = indexName + sdf.format(jsonObject.get(TIMESTAMP));
            saveSampleResult2ElasticSearch(indexNameToUse, null, true);
        }
        client.close();
        super.teardownTest(context);
    }

    @Override
    public Arguments getDefaultParameters() {
        LOGGER.info("getDefaultParameters");
        Arguments arguments = new Arguments();
        arguments.addArgument("ESHost", "localhost");
        arguments.addArgument("TestPlan", '${__TestPlanName}');
        arguments.addArgument("MBaasBuild", "");
        arguments.addArgument("B2Build", "");
        return arguments;

    }


    public static void main(String[] args) {
        client = new PreBuiltTransportClient(Settings.EMPTY);
        client.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress("10.75.60.76",9300)));
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("SampleLabel", "sampleLabel");
        BulkRequestBuilder bulkBuilder = client.prepareBulk();
            bulkBuilder.add(client.prepareIndex("jmeter-neil-test", "SampleType").setSource(map));
            bulkBuilder.get();
            //bulkBuilder.setRefresh(true);
            bulkBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
        Map<String, String> env = System.getenv();
        for (String envName : env.keySet()) {
            System.out.format("%s=%s%n",
                    envName,
                    env.get(envName));
        }
    }

    private String getEnvironmentVariable(String name, BackendListenerContext context) {
        Map<String, String> env = System.getenv();
        String value = "";
        if (name.startsWith(ENVIRONMENT_VARIABLE_PREFIX)) {
            value = env.getOrDefault(name, "");

        }
        else {
            value = env.getOrDefault(ENVIRONMENT_VARIABLE_PREFIX + name, "");
        }
        if(value.isEmpty())
        {
            value = context.getParameter(name);
        }
        return value;
    }

    private HashMap<String, String> getEnvironmentVariables(BackendListenerContext context)
    {
        HashMap<String, String> variables = new HashMap<String, String>();
        Map<String, String> env = System.getenv();
        for (String envName : env.keySet()) {
            if(envName.startsWith(ENVIRONMENT_VARIABLE_PREFIX))
            {
                variables.put(envName.replace(ENVIRONMENT_VARIABLE_PREFIX,""), env.get(envName));
            }
        }

        Iterator<String> iterator = context.getParameterNamesIterator();
        while (iterator.hasNext()) {
            String paraName = iterator.next();
            String paraValue = context.getParameter(paraName);
            if(paraValue.startsWith("${") && paraName.endsWith("}"))//not evaluated variable, skip it
            {}
            else {
                variables.put(paraName, paraValue);
            }
        }
        return variables;
    }

    private Map<String, Object> getMap(SampleResult result) {
        Map<String, Object> map = new HashMap<String, Object>();
        String[] sampleLabels = result.getSampleLabel().split(VAR_DELIMITER);
        map.put("SampleLabel", sampleLabels[0]);
        for (int i = 1; i < sampleLabels.length; i++) {
            String[] varNameAndValue = sampleLabels[i].split(VALUE_DELIMITER);
            map.put(varNameAndValue[0], varNameAndValue[1]);
        }
        String host = "";
        String path = "";
        String query = "";
        URL url = result.getURL();
        if (url != null) {
            host = url.getHost();
            path = url.getPath();
            query = url.getQuery();
        } else {
            //LOGGER.info("The url is null: " + result.getSampleLabel());
        }
        HashMap<String, String> querys = new HashMap<String, String>();
        if (query != null && query.length() > 0) {
            for (String q : query.split("&")) {
                String[] keyValue = q.split("=");
                String key = "";
                String value = "";
                if (keyValue.length == 1) {
                    key = keyValue[0];
                } else if (keyValue.length == 2) {
                    key = keyValue[0];
                    value = keyValue[1];
                }

                if (!key.isEmpty()) {
                    querys.put(key, value);
                }
            }
        }

        String requestHeaders = result.getRequestHeaders();
        map.put("ResponseTime", result.getTime());
        map.put("ElapsedTime", result.getTime());
        map.put("ResponseCode", result.getResponseCode());
        map.put("ResponseMessage", result.getResponseMessage());
        map.put("ThreadName", result.getThreadName());
        map.put("DataType", result.getDataType());
        map.put("Success", String.valueOf(result.isSuccessful()));
        map.put("GrpThreads", result.getGroupThreads());
        map.put("AllThreads", result.getAllThreads());
        map.put("URL", result.getUrlAsString());
        map.put("EnvironmentVariables", environmentVariables);

        map.put("Host", host);
        map.put("Path", path);
        map.put("Queries", querys);

        map.put("RequestHeaders", requestHeaders);
        map.put("Latency", result.getLatency());
        map.put("ConnectTime", result.getConnectTime());
        map.put("SampleCount", result.getSampleCount());
        map.put("ErrorCount", result.getErrorCount());
        map.put("Bytes", result.getBytes());
        map.put("BodySize", result.getBodySize());
        map.put("ContentType", result.getContentType());
        map.put("IdleTime", result.getIdleTime());
        map.put(TIMESTAMP, new Date(result.getTimeStamp()));
        map.put("NormalizedTimestamp", new Date(result.getTimeStamp() - offset));
        map.put("StartTime", new Date(result.getStartTime()));
        map.put("EndTime", new Date(result.getEndTime()));

        map.put("Offset", offset);
        map.put("RunId", runId);
        map.put("MBaasBuild", mBaasBuild);
        map.put("MachineName", machineName);
        map.put("TestPlanName", testPlanName);

        AssertionResult[] assertions = result.getAssertionResults();
        int count = 0;
        if (assertions != null) {
            Map<String, Object>[] assertionArray = new HashMap[assertions.length];
            for (AssertionResult assertionResult : assertions) {
                Map<String, Object> assertionMap = new HashMap<String, Object>();
                assertionMap.put("Failure", assertionResult.isError() || assertionResult.isFailure());
                assertionMap.put("FailureMessage", assertionResult.getFailureMessage());
                assertionMap.put("Name", assertionResult.getName());
                assertionArray[count++] = assertionMap;
            }
            map.put("Assertions", assertionArray);
        }
        return map;
    }


    private synchronized void saveSampleResult2ElasticSearch(String indexName, Map<String, Object> jsonObject, boolean forceToSave) {

        if (!forceToSave )
        {
            jsonObjects.add(jsonObject);
            if( jsonObjects.size() >= MaxiumBulkSize) {
                LOGGER.info("Save sample results to ES : " + jsonObjects.size());
                BulkRequestBuilder bulkBuilder = client.prepareBulk();
                for (Map<String, Object> obj : jsonObjects) {
                    bulkBuilder.add(client.prepareIndex(indexName, sampleType).setSource(obj));
                }
                try {
                    bulkBuilder.get();
                    //bulkBuilder.setRefresh(true);
                    bulkBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
                } catch (Exception ex) {
                    LOGGER.info(ex.getMessage());
                }finally {
                    jsonObjects.clear();
                }
            }
        }
        else
        {
            LOGGER.info("Save sample results to ES : " + jsonObjects.size());
            BulkRequestBuilder bulkBuilder = client.prepareBulk();

            for (Map<String, Object> obj : jsonObjects) {
                bulkBuilder.add(client.prepareIndex(indexName, sampleType).setSource(obj));
            }
            try {
                bulkBuilder.get();
                //bulkBuilder.setRefresh(true);
                bulkBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.NONE);
            }catch (Exception ex)
            {
                LOGGER.info(ex.getMessage());
            }
            finally {
                jsonObjects.clear();
            }

        }
    }
}

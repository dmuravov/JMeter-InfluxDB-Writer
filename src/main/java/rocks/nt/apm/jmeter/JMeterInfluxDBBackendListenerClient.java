package rocks.nt.apm.jmeter;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterContextService.ThreadCounts;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Point.Builder;
import org.influxdb.dto.Pong;
import rocks.nt.apm.jmeter.config.influxdb.InfluxDBConfig;
import rocks.nt.apm.jmeter.config.influxdb.RequestMeasurement;
import rocks.nt.apm.jmeter.config.influxdb.TestStartEndMeasurement;
import rocks.nt.apm.jmeter.config.influxdb.VirtualUsersMeasurement;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Backend listener that writes JMeter metrics to influxDB directly.
 *
 * @author Alexander Wert
 */
public class JMeterInfluxDBBackendListenerClient extends AbstractBackendListenerClient implements Runnable {
    /**
     * Logger.
     */
    private static final Logger LOGGER = LoggingManager.getLoggerForClass();

    /**
     * Parameter Keys.
     */
    private static final String KEY_USE_REGEX_FOR_SAMPLER_LIST = "useRegexForSamplerList";
    private static final String KEY_TEST_NAME = "testName";
    private static final String KEY_RUN_ID = "runId";
    private static final String KEY_NODE_NAME = "nodeName";
    private static final String KEY_SAMPLERS_LIST = "samplersList";
    private static final String KEY_RECORD_SUB_SAMPLES = "recordSubSamples";
    private static final String KEY_IS_BACKEND_LISTENER_DISABLED = "isBackendListenerDisabled";

    /**
     * Constants.
     */
    private static final String SEPARATOR = ";";
    private static final int ONE_MS_IN_NANOSECONDS = 1000000;

    /**
     * Scheduler for periodic metric aggregation.
     */
    private ScheduledExecutorService scheduler;

    /**
     * Name of the test.
     */
    private String testName;

    /**
     * A unique identifier for a single execution (aka 'run') of a load test.
     * In a CI/CD automated performance test, a Jenkins or Bamboo build id would be a good value for this.
     */
    private String runId;

    /**
     * Name of the name
     */
    private String nodeName;

    /**
     * List of samplers to record.
     */
    private String samplersList = "";

    /**
     * Regex if samplers are defined through regular expression.
     */
    private String regexForSamplerList;

    /**
     * Set of samplers to record.
     */
    private Set<String> samplersToFilter;

    /**
     * InfluxDB configuration.
     */
    InfluxDBConfig influxDBConfig;

    /**
     * influxDB client.
     */
    private InfluxDB influxDB;

    /**
     * Random number generator
     */
    private Random randomNumberGenerator;

    /**
     * Indicates whether to record Subsamples
     */
    private boolean recordSubSamples;

    /**
     * Indicates whether to run the test with or without the Backend Listener. The option can be useful
     * if running JMeter tests on a local machine in order to do check hotfixes in the test plan
     * or in the software under test.
     */
    private boolean isBackendListenerDisabled;

    /**
     * Processes sampler results.
     */
    public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {

        if (isBackendListenerDisabled) {
            getLogger().warn("You have set parameter " + KEY_IS_BACKEND_LISTENER_DISABLED + " to true. Therefore no further handling of samplers will be done!");
        } else {
            // Gather all the listeners
            List<SampleResult> allSampleResults = new ArrayList<>();
            for (SampleResult sampleResult : sampleResults) {
                allSampleResults.add(sampleResult);

                if (recordSubSamples) {
                    allSampleResults.addAll(Arrays.asList(sampleResult.getSubResults()));
                }
            }

            for (SampleResult sampleResult : allSampleResults) {
                getUserMetrics().add(sampleResult);

                if ((null != regexForSamplerList && sampleResult.getSampleLabel().matches(regexForSamplerList)) || samplersToFilter.contains(sampleResult.getSampleLabel())) {
                    Point point = Point.measurement(RequestMeasurement.MEASUREMENT_NAME).time(
                            System.currentTimeMillis() * ONE_MS_IN_NANOSECONDS + getUniqueNumberForTheSamplerThread(), TimeUnit.NANOSECONDS)
                            .tag(RequestMeasurement.Tags.REQUEST_NAME, sampleResult.getSampleLabel())
                            .addField(RequestMeasurement.Fields.ERROR_COUNT, sampleResult.getErrorCount())
                            .addField(RequestMeasurement.Fields.THREAD_NAME, sampleResult.getThreadName())
                            .tag(RequestMeasurement.Tags.RUN_ID, runId)
                            .tag(RequestMeasurement.Tags.TEST_NAME, testName)
                            .addField(RequestMeasurement.Fields.NODE_NAME, nodeName)
                            .addField(RequestMeasurement.Fields.RESPONSE_TIME, sampleResult.getTime()).build();
                    influxDB.write(influxDBConfig.getInfluxDatabase(), influxDBConfig.getInfluxRetentionPolicy(), point);
                }
            }
        }
    }

    @Override
    public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
        arguments.addArgument(KEY_TEST_NAME, "Test");
        arguments.addArgument(KEY_NODE_NAME, "Test-Node");
        arguments.addArgument(KEY_RUN_ID, "R001");
        arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_HOST, "localhost");
        arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_PORT, Integer.toString(InfluxDBConfig.DEFAULT_PORT));
        arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_USER, "");
        arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_PASSWORD, "");
        arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_DATABASE, InfluxDBConfig.DEFAULT_DATABASE);
        arguments.addArgument(InfluxDBConfig.KEY_RETENTION_POLICY, InfluxDBConfig.DEFAULT_RETENTION_POLICY);
        arguments.addArgument(KEY_SAMPLERS_LIST, ".*");
        arguments.addArgument(KEY_USE_REGEX_FOR_SAMPLER_LIST, "true");
        arguments.addArgument(KEY_RECORD_SUB_SAMPLES, "true");
        arguments.addArgument(KEY_IS_BACKEND_LISTENER_DISABLED, "false");

        return arguments;
    }

    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        testName = context.getParameter(KEY_TEST_NAME, "Test");
        runId = context.getParameter(KEY_RUN_ID, "R001"); //Will be used to compare performance of R001, R002, etc of 'Test'
        randomNumberGenerator = new Random();
        nodeName = context.getParameter(KEY_NODE_NAME, "Test-Node");
        isBackendListenerDisabled = context.getBooleanParameter(KEY_IS_BACKEND_LISTENER_DISABLED, false);

        if (isBackendListenerDisabled) {
            getLogger().warn("You have set parameter " + KEY_IS_BACKEND_LISTENER_DISABLED + " to true. Therefore no writing to InfluxDB will be done!");
        } else {
            setupInfluxClient(context);
            influxDB.write(
                    influxDBConfig.getInfluxDatabase(),
                    influxDBConfig.getInfluxRetentionPolicy(),
                    Point.measurement(TestStartEndMeasurement.MEASUREMENT_NAME).time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                            .tag(TestStartEndMeasurement.Tags.TYPE, TestStartEndMeasurement.Values.STARTED)
                            .tag(TestStartEndMeasurement.Tags.NODE_NAME, nodeName)
                            .tag(TestStartEndMeasurement.Tags.TEST_NAME, testName)
                            .addField(TestStartEndMeasurement.Fields.PLACEHOLDER, "1")
                            .build());

            parseSamplers(context);
            scheduler = Executors.newScheduledThreadPool(1);

            scheduler.scheduleAtFixedRate(this, 1, 1, TimeUnit.SECONDS);

            // Indicates whether to write sub sample records to the database
            recordSubSamples = Boolean.parseBoolean(context.getParameter(KEY_RECORD_SUB_SAMPLES, "false"));
        }
    }

    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        if (!isBackendListenerDisabled) {
            LOGGER.info("Shutting down influxDB scheduler...");
            scheduler.shutdown();
            addVirtualUsersMetrics(0, 0, 0, 0, JMeterContextService.getThreadCounts().finishedThreads);
            influxDB.write(
                    influxDBConfig.getInfluxDatabase(),
                    influxDBConfig.getInfluxRetentionPolicy(),
                    Point.measurement(TestStartEndMeasurement.MEASUREMENT_NAME).time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                            .tag(TestStartEndMeasurement.Tags.TYPE, TestStartEndMeasurement.Values.FINISHED)
                            .tag(TestStartEndMeasurement.Tags.NODE_NAME, nodeName)
                            .tag(TestStartEndMeasurement.Tags.RUN_ID, runId)
                            .tag(TestStartEndMeasurement.Tags.TEST_NAME, testName)
                            .addField(TestStartEndMeasurement.Fields.PLACEHOLDER, "1")
                            .build());

            influxDB.disableBatch();
            try {
                scheduler.awaitTermination(30, TimeUnit.SECONDS);
                LOGGER.info("influxDB scheduler terminated!");
            } catch (InterruptedException e) {
                LOGGER.error("Error waiting for end of scheduler");
            }
            samplersToFilter.clear();
        }
        super.teardownTest(context);
    }

    /**
     * Periodically writes virtual users metrics to influxDB.
     */
    public void run() {
        try {
            ThreadCounts tc = JMeterContextService.getThreadCounts();
            if (!isBackendListenerDisabled) {
                addVirtualUsersMetrics(getUserMetrics().getMinActiveThreads(), getUserMetrics().getMeanActiveThreads(), getUserMetrics().getMaxActiveThreads(), tc.startedThreads, tc.finishedThreads);
            }
        } catch (Exception e) {
            LOGGER.error("Failed writing to influx", e);
        }
    }

    /**
     * Setup influxDB client.
     *
     * @param context {@link BackendListenerContext}.
     */
    private void setupInfluxClient(BackendListenerContext context) {
        influxDBConfig = new InfluxDBConfig(context);
        influxDB = InfluxDBFactory.connect(influxDBConfig.getInfluxDBURL(), influxDBConfig.getInfluxUser(), influxDBConfig.getInfluxPassword());
        influxDB.enableBatch(100, 5, TimeUnit.SECONDS);

        try {
            Pong response = influxDB.ping();
            if (response.getVersion().equalsIgnoreCase("unknown")) {
                isBackendListenerDisabled = true;
                getLogger().error("Error pinging server. Is it alive? If you wish to run JMeter tests without the backend listener then set property " + KEY_IS_BACKEND_LISTENER_DISABLED + " to true.\"");
            } else {
                createDatabaseIfNotExistent();
            }
        } catch (Exception e) {
            getLogger().error("Deactivating writing to the InfluxDB due to an error: " + e);
            isBackendListenerDisabled = true;
        }
    }

    /**
     * Parses list of samplers.
     *
     * @param context {@link BackendListenerContext}.
     */
    private void parseSamplers(BackendListenerContext context) {
        samplersList = context.getParameter(KEY_SAMPLERS_LIST, "");
        samplersToFilter = new HashSet<>();
        if (context.getBooleanParameter(KEY_USE_REGEX_FOR_SAMPLER_LIST, false)) {
            regexForSamplerList = samplersList;
        } else {
            regexForSamplerList = null;
            String[] samplers = samplersList.split(SEPARATOR);
            samplersToFilter = new HashSet<>();
            samplersToFilter.addAll(Arrays.asList(samplers));
        }
    }

    /**
     * Write thread metrics.
     */
    private void addVirtualUsersMetrics(int minActiveThreads, int meanActiveThreads, int maxActiveThreads, int startedThreads, int finishedThreads) {
        Builder builder = Point.measurement(VirtualUsersMeasurement.MEASUREMENT_NAME).time(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        builder.addField(VirtualUsersMeasurement.Fields.MIN_ACTIVE_THREADS, minActiveThreads);
        builder.addField(VirtualUsersMeasurement.Fields.MAX_ACTIVE_THREADS, maxActiveThreads);
        builder.addField(VirtualUsersMeasurement.Fields.MEAN_ACTIVE_THREADS, meanActiveThreads);
        builder.addField(VirtualUsersMeasurement.Fields.STARTED_THREADS, startedThreads);
        builder.addField(VirtualUsersMeasurement.Fields.FINISHED_THREADS, finishedThreads);
        builder.tag(VirtualUsersMeasurement.Tags.NODE_NAME, nodeName);
        builder.tag(VirtualUsersMeasurement.Tags.TEST_NAME, testName);
        builder.tag(VirtualUsersMeasurement.Tags.RUN_ID, runId);
        influxDB.write(influxDBConfig.getInfluxDatabase(), influxDBConfig.getInfluxRetentionPolicy(), builder.build());
    }

    /**
     * Creates the configured database in influx if it does not exist yet.
     */
    private void createDatabaseIfNotExistent() {
        List<String> dbNames = influxDB.describeDatabases();
        if (!dbNames.contains(influxDBConfig.getInfluxDatabase())) {
            influxDB.createDatabase(influxDBConfig.getInfluxDatabase());
        }
    }

    /**
     * Try to get a unique number for the sampler thread
     */
    private int getUniqueNumberForTheSamplerThread() {
        return randomNumberGenerator.nextInt(ONE_MS_IN_NANOSECONDS);
    }
}

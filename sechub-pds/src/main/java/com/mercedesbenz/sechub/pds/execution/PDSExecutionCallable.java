// SPDX-License-Identifier: MIT
package com.mercedesbenz.sechub.pds.execution;

import static com.mercedesbenz.sechub.pds.util.PDSAssert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import com.mercedesbenz.sechub.pds.PDSJSONConverterException;
import com.mercedesbenz.sechub.pds.job.PDSCheckJobStatusService;
import com.mercedesbenz.sechub.pds.job.PDSJobConfiguration;
import com.mercedesbenz.sechub.pds.job.PDSJobTransactionService;
import com.mercedesbenz.sechub.pds.job.PDSWorkspaceService;
import com.mercedesbenz.sechub.pds.job.WorkspaceLocationData;
import com.mercedesbenz.sechub.pds.usecase.PDSStep;
import com.mercedesbenz.sechub.pds.usecase.UseCaseAdminFetchesJobErrorStream;
import com.mercedesbenz.sechub.pds.usecase.UseCaseAdminFetchesJobOutputStream;
import com.mercedesbenz.sechub.pds.usecase.UseCaseUserCancelsJob;
import com.mercedesbenz.sechub.pds.util.PDSResilientRetryExecutor;
import com.mercedesbenz.sechub.pds.util.PDSResilientRetryExecutor.ExceptionThrower;

/**
 * Represents the callable executed inside {@link PDSExecutionFutureTask}
 *
 * @author Albert Tregnaghi
 *
 */
class PDSExecutionCallable implements Callable<PDSExecutionResult> {

    private static final Logger LOG = LoggerFactory.getLogger(PDSExecutionCallable.class);

    private PDSJobTransactionService jobTransactionService;
    private Process process;

    private PDSWorkspaceService workspaceService;

    private PDSExecutionEnvironmentService environmentService;

    private UUID jobUUID;

    private PDSCheckJobStatusService jobStatusService;

    private ExceptionThrower<IllegalStateException> pdsJobUpdateExceptionThrower;

    public PDSExecutionCallable(UUID jobUUID, PDSJobTransactionService jobTransactionService, PDSWorkspaceService workspaceService,
            PDSExecutionEnvironmentService environmentService, PDSCheckJobStatusService jobStatusService) {
        notNull(jobUUID, "jobUUID may not be null!");
        notNull(jobTransactionService, "jobTransactionService may not be null!");
        notNull(workspaceService, "workspaceService may not be null!");
        notNull(jobStatusService, "jobStatusService may not be null!");

        this.jobUUID = jobUUID;
        this.jobTransactionService = jobTransactionService;
        this.workspaceService = workspaceService;
        this.environmentService = environmentService;
        this.jobStatusService = jobStatusService;

        pdsJobUpdateExceptionThrower = new ExceptionThrower<IllegalStateException>() {

            @Override
            public void throwException(String message, Exception cause) throws IllegalStateException {
                throw new IllegalStateException("Job stream data refresh failed. " + message, cause);
            }
        };
    }

    @Override
    public PDSExecutionResult call() throws Exception {
        LOG.info("Prepare execution of job {}", jobUUID);
        PDSExecutionResult result = new PDSExecutionResult();
        PDSJobConfiguration config = null;
        try {
            jobTransactionService.markJobAsRunningInOwnTransaction(jobUUID);

            String configJSON = jobTransactionService.getJobConfiguration(jobUUID);

            config = PDSJobConfiguration.fromJSON(configJSON);

            long minutesToWaitForResult = workspaceService.getMinutesToWaitForResult(config);
            if (minutesToWaitForResult < 1) {
                throw new IllegalStateException("Minutes to wait for result configured too low:" + minutesToWaitForResult);
            }

            LOG.debug("Handle source upload for job with uuid:{}", jobUUID);
            workspaceService.prepareWorkspace(jobUUID, config);
            workspaceService.unzipUploadsWhenConfigured(jobUUID, config);

            String path = workspaceService.getProductPathFor(config);
            if (path == null) {
                throw new IllegalStateException("Path not defined for product id:" + config.getProductId());
            }

            createProcess(jobUUID, config, path);

            StreamDataRefreshRequestWatcherRunnable watcherRunnable = new StreamDataRefreshRequestWatcherRunnable(jobUUID);
            Thread watcherThread = new Thread(watcherRunnable);
            watcherThread.setName("stream-watcher-" + jobUUID);
            watcherThread.start();

            waitForProcessEndAndGetResultByFiles(result, jobUUID, config, minutesToWaitForResult, watcherRunnable);

        } catch (Exception e) {

            LOG.error("Execution of job uuid:{} failed", jobUUID, e);

            result.failed = true;
            result.result = "Execution of job uuid:" + jobUUID + " failed. Please look into PDS logs for details and search for former string.";

        } finally {
            cleanUpWorkspace(jobUUID, config);
        }

        /*
         * handle always exit code. Everything having an exit code != 0 is handled as an
         * error
         */
        if (result.exitCode != 0) {
            result.failed = true;
        }

        LOG.info("Finished execution of job {} with exitCode={}, failed={}", jobUUID, result.exitCode, result.failed);

        return result;
    }

    private void waitForProcessEndAndGetResultByFiles(PDSExecutionResult result, UUID jobUUID, PDSJobConfiguration config, long minutesToWaitForResult,
            StreamDataRefreshRequestWatcherRunnable watcherRunnable) throws InterruptedException, IOException {
        LOG.debug("Wait for process of job with uuid:{}, will wait {} minutes for result from product with id:{}", jobUUID, minutesToWaitForResult,
                config.getProductId());
        long started = System.currentTimeMillis();

        boolean exitDoneInTime = process.waitFor(minutesToWaitForResult, TimeUnit.MINUTES);
        long timeElapsedInMilliseconds = System.currentTimeMillis() - started;

        if (!exitDoneInTime) {
            LOG.error("Job {} failed - product id:{} time out reached.", jobUUID, config.getProductId());

            result.failed = true;
            result.result = "Product time out.";
            result.exitCode = 1;
            prepareForCancel(true);

            watcherRunnable.stop();

            return;
        }

        result.exitCode = process.exitValue();
        result.result = "";
        LOG.debug("Process of job with uuid:{} ended after {} ms - for product with id:{}", jobUUID, timeElapsedInMilliseconds, config.getProductId());

        File file = workspaceService.getResultFile(jobUUID);
        String encoding = workspaceService.getFileEncoding(jobUUID);

        if (file.exists()) {
            LOG.debug("Result file found - will read data and set as result");
            result.result = FileUtils.readFileToString(file, encoding);
        } else {
            LOG.debug("Result file NOT found - will fetch system out and and err and use this as to set result");
            /*
             * no result file available - so snap error and system out and paste as result
             */
            result.failed = true;
            result.result = "Result file not found at " + file.getAbsolutePath();

            File systemOutFile = workspaceService.getSystemOutFile(jobUUID);
            String shrinkedOutputStream = null;
            String shrinkedErrorStream = null;

            if (systemOutFile.exists()) {
                String output = FileUtils.readFileToString(systemOutFile, encoding);
                result.result += "\nOutput:\n" + output;
                shrinkedOutputStream = maximum1024chars(output);
            }

            File systemErrorFile = workspaceService.getSystemErrorFile(jobUUID);
            if (systemErrorFile.exists()) {
                String error = FileUtils.readFileToString(systemErrorFile, encoding);
                result.result += "\nErrors:\n" + error;
                shrinkedErrorStream = maximum1024chars(error);
            }

            LOG.error("job {} wrote no result file - here part of console log:\noutput stream:\n{}\nerror stream:\n{}", jobUUID, shrinkedOutputStream,
                    shrinkedErrorStream);

        }

        watcherRunnable.stop();

        writeStreamDataToDatabase(jobUUID);

    }

    @UseCaseAdminFetchesJobOutputStream(@PDSStep(name = "Update stream data", description = "Reads all stream data (output and error ) from workspace files and stores content inside database. Will also refresh update time stamp for caching mechanism.", number = 4))
    @UseCaseAdminFetchesJobErrorStream(@PDSStep(name = "Update stream data", description = "Reads all stream data (output and error ) from workspace files and stores content inside database. Will also refresh update time stamp for caching mechanism.", number = 4))
    private void writeStreamDataToDatabase(UUID jobUUID) {
        LOG.debug("Writing stream data to database for job:{}", jobUUID);

        final StreamData streamData = readStreamData(jobUUID);

        PDSResilientRetryExecutor<IllegalStateException> executor = new PDSResilientRetryExecutor<>(3, pdsJobUpdateExceptionThrower,
                OptimisticLockingFailureException.class);
        executor.execute(() -> {
            jobTransactionService.updateJobStreamDataInOwnTransaction(jobUUID, streamData.outputStreamData, streamData.errorStreamData);
            return null;
        }, jobUUID.toString());

    }

    private StreamData readStreamData(UUID jobUUID) {
        String encoding = workspaceService.getFileEncoding(jobUUID);

        StreamData streamData = new StreamData();

        /* handle output stream */
        File systemOutFile = workspaceService.getSystemOutFile(jobUUID);
        if (systemOutFile.exists()) {
            try {
                streamData.outputStreamData = FileUtils.readFileToString(systemOutFile, encoding);
            } catch (IOException e) {
                LOG.error("Was not able to fetch output stream data for PDS job:{} on path:{}!", jobUUID, systemOutFile.getAbsolutePath(), e);
            }
        }
        /* handle error stream */
        File systemErrorFile = workspaceService.getSystemErrorFile(jobUUID);
        if (systemErrorFile.exists()) {
            try {
                streamData.errorStreamData = FileUtils.readFileToString(systemErrorFile, encoding);
            } catch (IOException e) {
                LOG.error("Was not able to fetch error stream data for PDS job:{} on path:{}!", jobUUID, systemOutFile.getAbsolutePath(), e);
            }
        }
        return streamData;
    }

    private String maximum1024chars(String content) {
        if (content == null) {
            return null;
        }
        if (content.length() < 1024) {
            return content;
        }
        return content.substring(0, 1021) + "...";
    }

    private void createProcess(UUID jobUUID, PDSJobConfiguration config, String path) throws IOException {
        File currentDir = Paths.get("./").toRealPath().toFile();
        List<String> commands = new ArrayList<>();
        commands.add(path);

        ProcessBuilder builder = new ProcessBuilder(commands);
        builder.directory(currentDir);
        builder.inheritIO();

        builder.redirectOutput(workspaceService.getSystemOutFile(jobUUID));
        builder.redirectError(workspaceService.getSystemErrorFile(jobUUID));

        /*
         * add parts from PDS job configuration - means data defined by caller before
         * job was marked as ready to start
         */
        Map<String, String> environment = builder.environment();

        Map<String, String> buildEnvironmentMap = environmentService.buildEnvironmentMap(config);
        environment.putAll(buildEnvironmentMap);

        WorkspaceLocationData locationData = workspaceService.createLocationData(jobUUID);

        environment.put("PDS_JOB_WORKSPACE_LOCATION", locationData.getWorkspaceLocation());
        environment.put("PDS_JOB_RESULT_FILE", locationData.getResultFileLocation());
        environment.put("PDS_JOB_SOURCECODE_ZIP_FILE", locationData.getZippedSourceLocation());
        environment.put("PDS_JOB_SOURCECODE_UNZIPPED_FOLDER", locationData.getUnzippedSourceLocation());

        LOG.debug("Prepared launcher script process for job with uuid:{}, path={}, env={}", jobUUID, path, buildEnvironmentMap);

        LOG.info("Start launcher script for job {}", jobUUID);
        try {

            process = builder.start();

        } catch (IOException e) {
            LOG.error("Process start failed for jobUUID:{}. Current directory was:{}", jobUUID, currentDir.getAbsolutePath());
            throw e;
        }
    }

    /**
     * Is called before cancel operation on caller / task side
     *
     * @param mayInterruptIfRunning
     */
    @UseCaseUserCancelsJob(@PDSStep(name = "process cancelation", description = "process created by job will be destroyed", number = 4))
    void prepareForCancel(boolean mayInterruptIfRunning) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            LOG.info("Cancelation of process: {} will destroy underlying process forcibly");
            process.destroyForcibly();
        } finally {

            String configJSON = jobTransactionService.getJobConfiguration(jobUUID);

            try {
                PDSJobConfiguration config = PDSJobConfiguration.fromJSON(configJSON);
                cleanUpWorkspace(jobUUID, config);
            } catch (PDSJSONConverterException e) {
                LOG.error("Was not able fetch job config for {} - workspace clean only workspace files", jobUUID, e);
            }

        }

    }

    private void cleanUpWorkspace(UUID jobUUID, PDSJobConfiguration config) {
        if (workspaceService.isWorkspaceAutoCleanDisabled()) {
            LOG.info("Auto cleanup is disabled, so keep files at {}", workspaceService.getWorkspaceFolder(jobUUID));
            return;
        }
        try {
            workspaceService.cleanup(jobUUID, config);
            LOG.debug("workspace cleanup done for job:{}", jobUUID);
        } catch (IOException e) {
            LOG.error("workspace cleanup failed for job:{}!", jobUUID);
        }
    }

    private class StreamDataRefreshRequestWatcherRunnable implements Runnable {

        private boolean stopped;
        private UUID jobUUID;

        private StreamDataRefreshRequestWatcherRunnable(UUID jobUUID) {
            this.jobUUID = jobUUID;
        }

        @Override
        public void run() {
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                LOG.trace("start checking stream data refresh requests");

                if (jobStatusService.isJobStreamUpdateNecessary(jobUUID)) {
                    writeStreamDataToDatabase(jobUUID);
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        public void stop() {
            stopped = true;
        }

    }

    private class StreamData {
        String outputStreamData = "";
        String errorStreamData = "";
    }

}
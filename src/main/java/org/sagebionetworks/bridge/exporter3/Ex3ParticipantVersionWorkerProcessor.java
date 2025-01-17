package org.sagebionetworks.bridge.exporter3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.table.PartialRow;
import org.sagebionetworks.repo.model.table.PartialRowSet;
import org.sagebionetworks.repo.model.table.RowReferenceSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.exceptions.BridgeSynapseException;
import org.sagebionetworks.bridge.json.DefaultObjectMapper;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.ParticipantVersion;
import org.sagebionetworks.bridge.rest.model.Study;
import org.sagebionetworks.bridge.sqs.PollSqsWorkerBadRequestException;
import org.sagebionetworks.bridge.sqs.PollSqsWorkerRetryableException;
import org.sagebionetworks.bridge.synapse.SynapseHelper;
import org.sagebionetworks.bridge.worker.ThrowingConsumer;
import org.sagebionetworks.bridge.workerPlatform.bridge.BridgeHelper;
import org.sagebionetworks.bridge.workerPlatform.bridge.BridgeUtils;

/** Worker for exporting Participant Versions in Exporter 3.0. */
@Component("Ex3ParticipantVersionWorker")
public class Ex3ParticipantVersionWorkerProcessor implements ThrowingConsumer<JsonNode> {
    private static final Logger LOG = LoggerFactory.getLogger(Ex3ParticipantVersionWorkerProcessor.class);

    private BridgeHelper bridgeHelper;
    private ParticipantVersionHelper participantVersionHelper;
    private SynapseHelper synapseHelper;

    @Autowired
    public final void setBridgeHelper(BridgeHelper bridgeHelper) {
        this.bridgeHelper = bridgeHelper;
    }

    @Autowired
    public final void setParticipantVersionHelper(ParticipantVersionHelper participantVersionHelper) {
        this.participantVersionHelper = participantVersionHelper;
    }

    @Autowired
    public final void setSynapseHelper(SynapseHelper synapseHelper) {
        this.synapseHelper = synapseHelper;
    }

    @Override
    public void accept(JsonNode jsonNode) throws BridgeSynapseException, IOException, PollSqsWorkerBadRequestException,
            PollSqsWorkerRetryableException, SynapseException {
        // Parse request.
        Ex3ParticipantVersionRequest request;
        try {
            request = DefaultObjectMapper.INSTANCE.treeToValue(jsonNode, Ex3ParticipantVersionRequest.class);
        } catch (IOException e) {
            throw new PollSqsWorkerBadRequestException("Error parsing request: " + e.getMessage(), e);
        }

        // Process request.
        Stopwatch requestStopwatch = Stopwatch.createStarted();
        try {
            process(request);
        } finally {
            LOG.info("Participant version export request took " + requestStopwatch.elapsed(TimeUnit.SECONDS) + " seconds for app " +
                    request.getAppId() + " healthcode " + request.getHealthCode() + " version " +
                    request.getParticipantVersion());
        }
    }

    // Package-scoped for unit tests.
    void process(Ex3ParticipantVersionRequest request) throws BridgeSynapseException, IOException,
            PollSqsWorkerRetryableException, SynapseException {
        // Throw if Synapse is not writable, so the PollSqsWorker can re-send the request.
        if (!synapseHelper.isSynapseWritable()) {
            throw new PollSqsWorkerRetryableException("Synapse is not writable");
        }

        String appId = request.getAppId();
        String healthCode = request.getHealthCode();
        int versionNum = request.getParticipantVersion();

        // Check that app is configured for export.
        App app = bridgeHelper.getApp(appId);
        if (app.isExporter3Enabled() == null || !app.isExporter3Enabled()) {
            // Exporter 3.0 is not enabled for the app. Skip. (We don't care if it's configured, since the studies can
            // be individually configured.
            return;
        }
        boolean exportForApp = BridgeUtils.isExporter3Configured(app);

        // Get participant version.
        ParticipantVersion participantVersion = bridgeHelper.getParticipantVersion(appId,
                "healthCode:" + healthCode, versionNum);

        // Which study is this export for?
        List<Study> studiesToExport = new ArrayList<>();
        if (participantVersion.getStudyMemberships() != null) {
            for (String studyId : participantVersion.getStudyMemberships().keySet()) {
                Study study = bridgeHelper.getStudy(appId, studyId);
                if (BridgeUtils.isExporter3Configured(study)) {
                    studiesToExport.add(study);
                }
            }
        }

        if (exportForApp) {
            String appParticipantVersionTableId = app.getExporter3Configuration().getParticipantVersionTableId();
            PartialRow row = participantVersionHelper.makeRowForParticipantVersion(null,
                    appParticipantVersionTableId, participantVersion);
            exportRowToSynapse(appId, healthCode, versionNum, appParticipantVersionTableId, row);
        }
        for (Study study : studiesToExport) {
            String studyParticipantVersionTableId = study.getExporter3Configuration().getParticipantVersionTableId();
            PartialRow row = participantVersionHelper.makeRowForParticipantVersion(study.getIdentifier(),
                    studyParticipantVersionTableId, participantVersion);
            exportRowToSynapse(appId, healthCode, versionNum, studyParticipantVersionTableId, row);
        }
    }

    // Package-scoped for unit tests.
    void exportRowToSynapse(String appId, String healthCode, int versionNum, String participantVersionTableId,
            PartialRow row) throws BridgeSynapseException, SynapseException {
        PartialRowSet rowSet = new PartialRowSet();
        rowSet.setRows(ImmutableList.of(row));
        rowSet.setTableId(participantVersionTableId);

        RowReferenceSet rowReferenceSet = synapseHelper.appendRowsToTable(rowSet, participantVersionTableId);
        if (rowReferenceSet.getRows().size() != 1) {
            LOG.error("Expected to write 1 participant version for app " + appId + " healthCode " + healthCode +
                    " version " + versionNum + ", instead wrote " + rowReferenceSet.getRows().size());
        }
    }
}

// SPDX-License-Identifier: MIT
package com.mercedesbenz.sechub.pds.execution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mercedesbenz.sechub.commons.pds.ExecutionPDSKey;
import com.mercedesbenz.sechub.commons.pds.PDSConfigDataKeyProvider;
import com.mercedesbenz.sechub.pds.config.PDSProductSetup;
import com.mercedesbenz.sechub.pds.config.PDSProdutParameterDefinition;
import com.mercedesbenz.sechub.pds.config.PDSProdutParameterSetup;
import com.mercedesbenz.sechub.pds.config.PDSServerConfigurationService;
import com.mercedesbenz.sechub.pds.job.PDSJobConfiguration;

@Service
public class PDSExecutionEnvironmentService {

    public static final String CONSTANT_SECHUB_JOB_UUID = "SECHUB_JOB_UUID";

    private static final Logger LOG = LoggerFactory.getLogger(PDSExecutionEnvironmentService.class);

    @Autowired
    PDSKeyToEnvConverter converter;

    @Autowired
    PDSServerConfigurationService serverConfigService;

    public Map<String, String> buildEnvironmentMap(PDSJobConfiguration config) {
        Map<String, String> map = new LinkedHashMap<>();

        String productId = config.getProductId();
        PDSProductSetup productSetup = serverConfigService.getProductSetupOrNull(productId);
        if (productSetup != null) {
            List<PDSExecutionParameterEntry> jobParams = config.getParameters();
            for (PDSExecutionParameterEntry jobParam : jobParams) {
                addJobParamDataWhenAccepted(productSetup, jobParam, map);
            }
        } else {
            LOG.error("No product setup found for product id:{}", productId);
        }
        addSecHubJobUUIDAsEnvironmentEntry(config, map);

        return map;
    }

    private void addSecHubJobUUIDAsEnvironmentEntry(PDSJobConfiguration config, Map<String, String> map) {
        map.put(CONSTANT_SECHUB_JOB_UUID, fetchSecHubJobUUIDasString(config));
    }

    private String fetchSecHubJobUUIDasString(PDSJobConfiguration config) {
        UUID sechubJobUUID = config.getSechubJobUUID();
        if (sechubJobUUID == null) {
            LOG.error("No SecHub job UUID found, environment variable: {} will be empty", CONSTANT_SECHUB_JOB_UUID);
            return "";
        }
        return sechubJobUUID.toString();
    }

    private void addJobParamDataWhenAccepted(PDSProductSetup productSetup, PDSExecutionParameterEntry jobParam, Map<String, String> map) {
        PDSProdutParameterSetup params = productSetup.getParameters();

        boolean validParam = false;
        for (PDSConfigDataKeyProvider provider : PDSConfigDataKeyProvider.values()) {
            ExecutionPDSKey key = provider.getKey();
            if (!key.getId().equals(jobParam.getKey())) {
                continue;
            }
            validParam = key.isAvailableInsideScript();
        }
        validParam = validParam || isJobParameterAcceptedByPDSServerConfiguration(jobParam, params.getMandatory());
        validParam = validParam || isJobParameterAcceptedByPDSServerConfiguration(jobParam, params.getOptional());

        if (validParam) {
            map.put(converter.convertKeyToEnv(jobParam.getKey()), jobParam.getValue());
        } else {
            LOG.warn("Ignored invalid job parameter key {} for product id:{} !", jobParam.getKey(), productSetup.getId());
        }
    }

    private boolean isJobParameterAcceptedByPDSServerConfiguration(PDSExecutionParameterEntry jobParam, List<PDSProdutParameterDefinition> definitions) {
        for (PDSProdutParameterDefinition paramDef : definitions) {
            if (paramDef.getKey().equals(jobParam.getKey())) {
                return true;
            }
        }
        return false;
    }
}
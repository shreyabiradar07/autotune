/*******************************************************************************
 * Copyright (c) 2021, 2022 Red Hat, IBM Corporation and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.autotune.common.datasource;

import com.autotune.common.auth.AuthenticationConfig;
import com.autotune.common.data.ValidationOutputData;
import com.autotune.common.exceptions.datasource.*;
import com.autotune.common.utils.CommonUtils;
import com.autotune.database.service.ExperimentDBService;
import com.autotune.utils.KruizeConstants;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;

import static com.autotune.utils.KruizeConstants.DataSourceConstants.DataSourceErrorMsgs.*;
import static com.autotune.utils.KruizeConstants.DataSourceConstants.DataSourceSuccessMsgs.DATASOURCE_ADDED;
import static com.autotune.utils.KruizeConstants.DataSourceConstants.DataSourceSuccessMsgs.DATASOURCE_AUTH_ADDED_DB;

public class DataSourceCollection {
    private static final Logger LOGGER = LoggerFactory.getLogger(DataSourceCollection.class);
    private static DataSourceCollection dataSourceCollectionInstance = new DataSourceCollection();
    private HashMap<String, DataSourceInfo> dataSourceCollection;

    private DataSourceCollection() {
        this.dataSourceCollection = new HashMap<>();
    }

    /**
     * Returns the instance of dataSourceCollection class
     *
     * @return DataSourceCollection instance
     */
    public static DataSourceCollection getInstance() {
        return dataSourceCollectionInstance;
    }

    public void loadDataSourcesFromDB() {
        try {
            LOGGER.info(KruizeConstants.DataSourceConstants.DataSourceInfoMsgs.CHECKING_AVAILABLE_DATASOURCE_FROM_DB);
            List<DataSourceInfo> availableDataSources = new ExperimentDBService().loadAllDataSources();
            if (null == availableDataSources) {
                LOGGER.info(KruizeConstants.DataSourceConstants.DataSourceInfoMsgs.NO_DATASOURCE_FOUND_IN_DB);
            } else {
                for (DataSourceInfo dataSourceInfo : availableDataSources) {
                    LOGGER.info(KruizeConstants.DataSourceConstants.DataSourceSuccessMsgs.DATASOURCE_FOUND + dataSourceInfo.getName());
                    dataSourceCollection.put(dataSourceInfo.getName(), dataSourceInfo);
                }
            }

        } catch (Exception e) {
            LOGGER.error(e.getMessage());
        }

    }

    /**
     * Returns the hashmap of data sources
     *
     * @return HashMap containing dataSourceInfo objects
     */
    public HashMap<String, DataSourceInfo> getDataSourcesCollection() {
        return dataSourceCollection;
    }

    /**
     * Adds datasource to collection
     *
     * @param datasource DataSourceInfo object containing details of datasource
     */
    public void addDataSource(DataSourceInfo datasource) throws DataSourceAlreadyExist, IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException, DataSourceNotServiceable, UnsupportedDataSourceProvider {
        final String name = datasource.getName();
        final String provider = datasource.getProvider();
        ValidationOutputData addedToDB = null;

        LOGGER.info(KruizeConstants.DataSourceConstants.DataSourceInfoMsgs.ADDING_DATASOURCE, name);


        if (dataSourceCollection.containsKey(name)) {
            throw new DataSourceAlreadyExist(DATASOURCE_ALREADY_EXIST);
        }

        if (provider.equalsIgnoreCase(KruizeConstants.SupportedDatasources.PROMETHEUS)) {
            LOGGER.info(KruizeConstants.DataSourceConstants.DataSourceInfoMsgs.VERIFYING_DATASOURCE_REACHABILITY, name);
            DataSourceOperatorImpl op = DataSourceOperatorImpl.getInstance().getOperator(KruizeConstants.SupportedDatasources.PROMETHEUS);
            if (op.isServiceable(datasource) == CommonUtils.DatasourceReachabilityStatus.REACHABLE) {
                LOGGER.info(KruizeConstants.DataSourceConstants.DataSourceSuccessMsgs.DATASOURCE_SERVICEABLE);
                // add the authentication details to the DB
                addedToDB = new ExperimentDBService().addAuthenticationDetailsToDB(datasource.getAuthenticationConfig(), KruizeConstants.JSONKeys.DATASOURCE);
                if (addedToDB.isSuccess()) {
                    LOGGER.info(KruizeConstants.DataSourceConstants.DataSourceSuccessMsgs.DATASOURCE_AUTH_ADDED_DB);
                    // add the data source to DB
                    addedToDB = new ExperimentDBService().addDataSourceToDB(datasource, addedToDB);
                    if (addedToDB.isSuccess()) {
                        LOGGER.info(DATASOURCE_AUTH_ADDED_DB);
                    } else {
                        LOGGER.error("{}: {}", DATASOURCE_NOT_SERVICEABLE, addedToDB.getMessage());
                    }
                } else {
                    LOGGER.error(DATASOURCE_AUTH_DB_INSERTION_FAILED, addedToDB.getMessage());
                }
                dataSourceCollection.put(name, datasource);
                LOGGER.info(DATASOURCE_ADDED);
            } else {
                throw new DataSourceNotServiceable(DATASOURCE_NOT_SERVICEABLE);
            }
        } else {
            throw new UnsupportedDataSourceProvider(KruizeConstants.DataSourceConstants.DataSourceErrorMsgs.UNSUPPORTED_DATASOURCE_PROVIDER);
        }

    }

    /**
     * Loads the data sources available at installation time
     *
     * @param configFileName name of the config file mounted
     */
    public void addDataSourcesFromConfigFile(String configFileName) throws UnsupportedDataSourceProvider, DataSourceNotServiceable, DataSourceAlreadyExist, IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException {

        String configFile = System.getenv(configFileName);
        JSONObject configObject;
        ValidationOutputData addedToDB;

        InputStream is = new FileInputStream(configFile);
        String jsonTxt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        configObject = new JSONObject(jsonTxt);
        JSONArray dataSourceArr = configObject.getJSONArray(KruizeConstants.DataSourceConstants.KRUIZE_DATASOURCE);

        for (Object dataSourceObj : dataSourceArr) {
            JSONObject dataSourceObject = (JSONObject) dataSourceObj;
            String name = dataSourceObject.getString(KruizeConstants.DataSourceConstants.DATASOURCE_NAME);
            // Fetch the existing datasource from the DB (if it exists)
            DataSourceInfo dataSourceInfo = null;
            try {
                dataSourceInfo = new ExperimentDBService().loadDataSourceFromDBByName(name);
            } catch (Exception e) {
                LOGGER.error(DATASOURCE_DB_LOAD_FAILED, name, e.getMessage());
            }
            if (null != dataSourceInfo) {
                LOGGER.debug(KruizeConstants.DataSourceConstants.DataSourceInfoMsgs.CHECK_DATASOURCE_UPDATES, name);
                // Extract and compare the authentication details
                JSONObject authenticationObj = dataSourceObject.optJSONObject(KruizeConstants.AuthenticationConstants.AUTHENTICATION);
                AuthenticationConfig newAuthConfig;
                if (authenticationObj == null) {
                    LOGGER.warn(MISSING_DATASOURCE_AUTH, name);
                    newAuthConfig = AuthenticationConfig.noAuth();
                } else {
                    // Create the new authentication config from the JSON object
                    newAuthConfig = AuthenticationConfig.createAuthenticationConfigObject(authenticationObj);
                }
                // Compare with the existing authentication config
                if (dataSourceInfo.hasAuthChanged(newAuthConfig)) {
                    LOGGER.info(KruizeConstants.DataSourceConstants.DataSourceInfoMsgs.DATASOURCE_AUTH_CHANGED, name);
                    // check the datasource with the new config
                    dataSourceInfo.updateAuthConfig(newAuthConfig);
                    DataSourceOperatorImpl op = DataSourceOperatorImpl.getInstance().getOperator(KruizeConstants.SupportedDatasources.PROMETHEUS);
                    if (op.isServiceable(dataSourceInfo) == CommonUtils.DatasourceReachabilityStatus.REACHABLE) {
                        // update the authentication details in the DB
                        addedToDB = new ExperimentDBService().addAuthenticationDetailsToDB(dataSourceInfo.getAuthenticationConfig(), KruizeConstants.JSONKeys.DATASOURCE);
                        if (addedToDB.isSuccess()) {
                            LOGGER.debug(KruizeConstants.DataSourceConstants.DataSourceSuccessMsgs.DATASOURCE_AUTH_UPDATED_DB);
                        } else {
                            LOGGER.error(DATASOURCE_AUTH_DB_UPDATE_FAILED, addedToDB.getMessage());
                        }
                    } else {
                        LOGGER.error(DATASOURCE_AUTH_UPDATE_INVALID);
                    }
                } else {
                    LOGGER.debug(KruizeConstants.DataSourceConstants.DataSourceInfoMsgs.DATASOURCE_AUTH_UNCHANGED, name);
                    return;
                }
            } else {
                String provider = dataSourceObject.getString(KruizeConstants.DataSourceConstants.DATASOURCE_PROVIDER);
                String serviceName = dataSourceObject.getString(KruizeConstants.DataSourceConstants.DATASOURCE_SERVICE_NAME);
                String namespace = dataSourceObject.getString(KruizeConstants.DataSourceConstants.DATASOURCE_SERVICE_NAMESPACE);
                String dataSourceURL = dataSourceObject.getString(KruizeConstants.DataSourceConstants.DATASOURCE_URL);
                AuthenticationConfig authConfig;
                try {
                    JSONObject authenticationObj = dataSourceObject.optJSONObject(KruizeConstants.AuthenticationConstants.AUTHENTICATION);
                    // create the corresponding authentication object
                    authConfig = AuthenticationConfig.createAuthenticationConfigObject(authenticationObj);
                } catch (Exception e) {
                    LOGGER.warn(DATASOURCE_DB_AUTH_LOAD_FAILED, name, e.getMessage());
                    authConfig = AuthenticationConfig.noAuth();
                }

                // Validate input
                if (!validateInput(name, provider, serviceName, dataSourceURL, namespace)) { //TODO: add validations for auth
                    continue;
                }
                if (dataSourceURL.isEmpty()) {
                    dataSourceInfo = new DataSourceInfo(name, provider, serviceName, namespace, null, authConfig);
                } else {
                    dataSourceInfo = new DataSourceInfo(name, provider, serviceName, namespace, new URL(dataSourceURL), authConfig);
                }
                // add/update the datasource
                addDataSource(dataSourceInfo);
            }
        }
    }

    /**
     * validates the input parameters before creating dataSourceInfo objects
     *
     * @param name        String containing name of the datasource
     * @param provider    String containing provider of the datasource
     * @param servicename String containing service name for the datasource
     * @param url         String containing URL of the data source
     * @param namespace   String containing namespace for the datasource service
     * @return boolean returns true if validation is successful otherwise return false
     */
    public boolean validateInput(String name, String provider, String servicename, String url, String namespace) {
        try {
            if (name.isEmpty()) {
                throw new DataSourceMissingRequiredFiled(KruizeConstants.DataSourceConstants.DataSourceErrorMsgs.MISSING_DATASOURCE_NAME);
            }
            if (provider.isEmpty()) {
                throw new DataSourceMissingRequiredFiled(KruizeConstants.DataSourceConstants.DataSourceErrorMsgs.MISSING_DATASOURCE_PROVIDER);
            }
            if (servicename.isEmpty() && url.isEmpty()) {
                throw new DataSourceMissingRequiredFiled(KruizeConstants.DataSourceConstants.DataSourceErrorMsgs.MISSING_DATASOURCE_SERVICENAME_AND_URL);
            }
            if (!servicename.isEmpty() && !url.isEmpty()) {
                throw new DataSourceMissingRequiredFiled(KruizeConstants.DataSourceConstants.DataSourceErrorMsgs.DATASOURCE_URL_SERVICENAME_BOTH_SET);
            }
            return true;
        } catch (DataSourceMissingRequiredFiled e) {
            LOGGER.error(e.getMessage());
            return false;
        }
    }

    /**
     * deletes the datasource from the Hashmap
     *
     * @param name String containing the name of the datasource to be deleted
     *                                                                                     TODO: add db related operations
     */
    public void deleteDataSource(String name) throws DataSourceMissingRequiredFiled, DataSourceDoesNotExist {

        if (name == null) {
            throw new DataSourceMissingRequiredFiled(KruizeConstants.DataSourceConstants.DataSourceErrorMsgs.MISSING_DATASOURCE_NAME);
        }
        if (dataSourceCollection.containsKey(name)) {
            dataSourceCollection.remove(name);
        } else {
            throw new DataSourceDoesNotExist(KruizeConstants.DataSourceConstants.DataSourceErrorMsgs.DATASOURCE_NOT_EXIST);
        }

    }

    /**
     * updates the existing datasource in the Hashmap
     *
     * @param name          String containing the name of the datasource to be updated
     * @param newDataSource DataSourceInfo object with updated values
     *                                                                                                                                                    TODO: add db related operations
     */
    public void updateDataSource(String name, DataSourceInfo newDataSource) throws UnsupportedDataSourceProvider, DataSourceNotServiceable, DataSourceAlreadyExist, IOException, NoSuchAlgorithmException, KeyStoreException, KeyManagementException, DataSourceDoesNotExist {

        if (dataSourceCollection.containsKey(name)) {
            dataSourceCollection.remove(name);
            addDataSource(newDataSource);
        } else {
            throw new DataSourceDoesNotExist(name + ": " + KruizeConstants.DataSourceConstants.DataSourceErrorMsgs.DATASOURCE_NOT_EXIST);
        }

    }
}

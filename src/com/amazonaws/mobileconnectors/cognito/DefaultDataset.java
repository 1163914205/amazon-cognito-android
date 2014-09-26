/**
 * Copyright 2013-2014 Amazon.com, 
 * Inc. or its affiliates. All Rights Reserved.
 * 
 * Licensed under the Amazon Software License (the "License"). 
 * You may not use this file except in compliance with the 
 * License. A copy of the License is located at
 * 
 *     http://aws.amazon.com/asl/
 * 
 * or in the "license" file accompanying this file. This file is 
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR 
 * CONDITIONS OF ANY KIND, express or implied. See the License 
 * for the specific language governing permissions and 
 * limitations under the License.
 */

package com.amazonaws.mobileconnectors.cognito;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.mobileconnectors.cognito.exceptions.DataConflictException;
import com.amazonaws.mobileconnectors.cognito.exceptions.DataStorageException;
import com.amazonaws.mobileconnectors.cognito.exceptions.NetworkException;
import com.amazonaws.mobileconnectors.cognito.internal.storage.CognitoSyncStorage;
import com.amazonaws.mobileconnectors.cognito.internal.storage.LocalStorage;
import com.amazonaws.mobileconnectors.cognito.internal.storage.RemoteDataStorage;
import com.amazonaws.mobileconnectors.cognito.internal.storage.RemoteDataStorage.DatasetUpdates;
import com.amazonaws.mobileconnectors.cognito.internal.storage.SQLiteLocalStorage;
import com.amazonaws.mobileconnectors.cognito.internal.util.DatasetUtils;
import com.amazonaws.mobileconnectors.cognito.internal.util.StringUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link Dataset}. It uses {@link CognitoSyncStorage}
 * as remote storage and {@link SQLiteLocalStorage} as local storage.
 */
class DefaultDataset implements Dataset {

    private static final String TAG = "DefaultDataset";

    /**
     * Max number of retries during synchronize before it gives up.
     */
    private static final int MAX_RETRY = 3;

    /**
     * Context that the dataset is attached to
     */
    private final Context context;

    /**
     * Non empty dataset name
     */
    private final String datasetName;
    /**
     * Local storage
     */
    private final LocalStorage local;
    /**
     * Remote storage
     */
    private final RemoteDataStorage remote;
    /**
     * Identity id
     */
    private final CognitoCachingCredentialsProvider provider;

    /**
     * Constructs a DefaultDataset object
     * 
     * @param context context of this dataset
     * @param datasetName non empty dataset name
     * @param provider the credentials provider
     * @param local an instance of LocalStorage
     * @param remote an instance of RemoteDataStorage
     */
    public DefaultDataset(Context context, String datasetName,
            CognitoCachingCredentialsProvider provider,
            LocalStorage local, RemoteDataStorage remote) {
        this.context = context;
        this.datasetName = datasetName;
        this.provider = provider;
        this.local = local;
        this.remote = remote;
    }

    @Override
    public void put(String key, String value) {
        local.putValue(getIdentityId(), datasetName,
                DatasetUtils.validateRecordKey(key), value);
    }

    @Override
    public void remove(String key) {
        local.putValue(getIdentityId(), datasetName,
                DatasetUtils.validateRecordKey(key), null);
    }

    @Override
    public String get(String key) {
        return local.getValue(getIdentityId(), datasetName,
                DatasetUtils.validateRecordKey(key));
    }

    @Override
    public void synchronize(final SyncCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback can't ben null");
        }

        if (!isNetworkAvailable()) {
            callback.onFailure(new NetworkException("Network connectivity unavailable."));
            return;
        }

        discardPendingSyncRequest();

        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "start to synchronize " + datasetName);

                boolean result = false;
                try {
                    List<String> mergedDatasets = getLocalMergedDatasets();
                    if (!mergedDatasets.isEmpty()) {
                        Log.i(TAG, "detected merge datasets " + datasetName);
                        callback.onDatasetsMerged(DefaultDataset.this, mergedDatasets);
                    }

                    result = synchronizeInternal(callback, MAX_RETRY);
                } catch (Exception e) {
                    callback.onFailure(new DataStorageException("Unknown exception", e));
                }

                if (result) {
                    Log.d(TAG, "successfully synchronize " + datasetName);
                } else {
                    Log.d(TAG, "failed to synchronize " + datasetName);
                }
            }
        }).start();
    }

    /**
     * Internal method for synchronization.
     * 
     * @param callback callback during synchronization
     * @param retry number of retries before it's considered failure
     * @return true if synchronize successfully, false otherwise
     */
    synchronized boolean synchronizeInternal(final SyncCallback callback, int retry) {
        if (retry < 0) {
            Log.e(TAG, "synchronize failed because it exceeds maximum retry");
            return false;
        }

        long lastSyncCount = local.getLastSyncCount(getIdentityId(), datasetName);

        // if dataset is deleted locally, push it to remote
        if (lastSyncCount == -1) {
            try {
                remote.deleteDataset(datasetName);
                local.purgeDataset(getIdentityId(), datasetName);
                callback.onSuccess(DefaultDataset.this, Collections.<Record> emptyList());
                return true;
            } catch (DataStorageException dse) {
                callback.onFailure(dse);
                return false;
            }
        }

        // get latest modified records from remote
        Log.d(TAG, "get latest modified records since " + lastSyncCount);
        DatasetUpdates datasetUpdates = null;
        try {
            datasetUpdates = remote.listUpdates(datasetName, lastSyncCount);
        } catch (DataStorageException e) {
            callback.onFailure(e);
            return false;
        }

        if (!datasetUpdates.getMergedDatasetNameList().isEmpty()) {
            boolean resume = callback.onDatasetsMerged(DefaultDataset.this,
                    new ArrayList<String>(datasetUpdates.getMergedDatasetNameList()));
            if (resume) {
                return synchronizeInternal(callback, --retry);
            } else {
                callback.onFailure(new DataStorageException("Manual cancel"));
                return false;
            }
        }

        // if the dataset doesn't exist or is deleted, trigger onDelete
        if (lastSyncCount != 0 && !datasetUpdates.isExists()
                || datasetUpdates.isDeleted()) {
            boolean resume = callback
                    .onDatasetDeleted(DefaultDataset.this, datasetUpdates.getDatasetName());
            if (resume) {
                // remove both records and metadata
                local.deleteDataset(getIdentityId(), datasetName);
                local.purgeDataset(getIdentityId(), datasetName);
                callback.onSuccess(DefaultDataset.this, Collections.<Record> emptyList());
                return true;
            } else {
                callback.onFailure(new DataStorageException("Manual cancel"));
                return false;
            }
        }

        List<Record> remoteRecords = datasetUpdates.getRecords();
        if (!remoteRecords.isEmpty()) {
            // if conflict, prompt developer/user with callback
            List<SyncConflict> conflicts = new ArrayList<SyncConflict>();
            for (Record remoteRecord : remoteRecords) {
                Record localRecord = local.getRecord(getIdentityId(),
                        datasetName,
                        remoteRecord.getKey());
                // only when local is changed and its value is different
                if (localRecord != null && localRecord.isModified()
                        && !StringUtils.equals(localRecord.getValue(), remoteRecord.getValue())) {
                    conflicts.add(new SyncConflict(remoteRecord, localRecord));
                }
            }
            if (!conflicts.isEmpty()) {
                Log.i(TAG, String.format("%d records in conflict!", conflicts.size()));
                boolean resume = callback.onConflict(DefaultDataset.this, conflicts);
                return resume ? synchronizeInternal(callback, --retry) : resume;
            }

            // save to local
            Log.i(TAG, String.format("save %d records to local", remoteRecords.size()));
            local.putRecords(getIdentityId(), datasetName, remoteRecords);

            // new last sync count
            Log.i(TAG, String.format("updated sync count %d", datasetUpdates.getSyncCount()));
            local.updateLastSyncCount(getIdentityId(), datasetName,
                    datasetUpdates.getSyncCount());
        }

        // push changes to remote
        List<Record> localChanges = getModifiedRecords();
        if (!localChanges.isEmpty()) {
            Log.i(TAG, String.format("push %d records to remote", localChanges.size()));
            List<Record> result = null;
            try {
                result = remote.putRecords(datasetName, localChanges,
                        datasetUpdates.getSyncSessionToken());
            } catch (DataConflictException dce) {
                Log.i(TAG, "conflicts detected when pushing changes to remote.");
                return synchronizeInternal(callback, --retry);
            } catch (DataStorageException dse) {
                callback.onFailure(dse);
                return false;
            }

            // update local meta data
            local.putRecords(getIdentityId(), datasetName, result);

            // verify the server sync count is increased exactly by one, aka no
            // other updates were made during this update.
            long newSyncCount = 0;
            for (Record record : result) {
                newSyncCount = newSyncCount < record.getSyncCount()
                        ? record.getSyncCount()
                        : newSyncCount;
            }
            if (newSyncCount == lastSyncCount + 1) {
                Log.i(TAG, String.format("updated sync count %d", newSyncCount));
                local.updateLastSyncCount(getIdentityId(), datasetName,
                        newSyncCount);
            }
        }

        // call back
        callback.onSuccess(DefaultDataset.this, remoteRecords);
        return true;
    }

    @Override
    public List<Record> getAllRecords() {
        return local.getRecords(getIdentityId(), datasetName);
    }

    @Override
    public long getTotalSizeInBytes() {
        long size = 0;
        for (Record record : local.getRecords(getIdentityId(), datasetName)) {
            size += DatasetUtils.computeRecordSize(record);
        }
        return size;
    }

    @Override
    public long getSizeInBytes(String key) {
        return DatasetUtils.computeRecordSize(local.getRecord(getIdentityId(),
                datasetName, DatasetUtils.validateRecordKey(key)));
    }

    @Override
    public boolean isChanged(String key) {
        Record record = local.getRecord(getIdentityId(), datasetName,
                DatasetUtils.validateRecordKey(key));
        return (record != null && record.isModified());
    }

    @Override
    public void delete() {
        local.deleteDataset(getIdentityId(), datasetName);
    }

    @Override
    public DatasetMetadata getDatasetMetadata() {
        return local.getDatasetMetadata(getIdentityId(), datasetName);
    }

    @Override
    public void resolve(List<Record> remoteRecords) {
        local.putRecords(getIdentityId(), datasetName, remoteRecords);
    }

    @Override
    public void putAll(Map<String, String> values) {
        for (String key : values.keySet()) {
            DatasetUtils.validateRecordKey(key);
        }
        local.putAllValues(getIdentityId(), datasetName, values);
    }

    @Override
    public Map<String, String> getAll() {
        Map<String, String> map = new HashMap<String, String>();
        for (Record record : local.getRecords(getIdentityId(), datasetName)) {
            if (!record.isDeleted()) {
                map.put(record.getKey(), record.getValue());
            }
        }
        return map;
    }

    String getIdentityId() {
        return DatasetUtils.getIdentityId(provider);
    }

    /**
     * Gets a list of records that have been modified (marking as deleted
     * included).
     * 
     * @return a list of locally modified records
     */
    List<Record> getModifiedRecords() {
        return local.getModifiedRecords(getIdentityId(), datasetName);
    }

    /**
     * Gets a list of merged datasets that are marked as merged but haven't been
     * processed.
     * 
     * @param datasetName dataset name
     * @return a list dataset names that are marked as merged
     */
    List<String> getLocalMergedDatasets() {
        List<String> mergedDatasets = new ArrayList<String>();
        String prefix = datasetName + ".";
        for (DatasetMetadata dataset : local.getDatasets(getIdentityId())) {
            if (dataset.getDatasetName().startsWith(prefix)) {
                mergedDatasets.add(dataset.getDatasetName());
            }
        }
        return mergedDatasets;
    }

    /**
     * Pending sync request, set when connectivity is unavailable
     */
    private SyncOnConnectivity pendingSyncRequest = null;

    /**
     * This customized broadcast receiver will perform a sync once the
     * connectivity is back.
     */
    static class SyncOnConnectivity extends BroadcastReceiver {
        WeakReference<Dataset> datasetRef;
        WeakReference<SyncCallback> callbackRef;

        SyncOnConnectivity(Dataset dataset, SyncCallback callback) {
            datasetRef = new WeakReference<Dataset>(dataset);
            callbackRef = new WeakReference<Dataset.SyncCallback>(callback);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            NetworkInfo info = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            if (info == null || !info.isAvailable()) {
                Log.d(TAG, "Connectivity is unavailable.");
                return;
            }
            Log.d(TAG, "Connectivity is available. Try synchronizing.");
            context.unregisterReceiver(this);

            // dereference dataset and callback
            Dataset dataset = datasetRef.get();
            SyncCallback callback = callbackRef.get();
            // make sure they are valid
            if (dataset == null || callback == null) {
                Log.w(TAG, "Abort syncOnConnectivity because either dataset "
                        + "or callback was garbage collected");
            } else {
                dataset.synchronize(callback);
            }
        }
    }

    @Override
    public void synchronizeOnConnectivity(SyncCallback callback) {
        if (isNetworkAvailable()) {
            synchronize(callback);
        } else {
            discardPendingSyncRequest();
            Log.d(TAG, "Connectivity is unavailable. "
                    + "Scheduling synchronize for when connectivity is resumed.");
            pendingSyncRequest = new SyncOnConnectivity(this, callback);
            // listen to only connectivity change
            context.registerReceiver(pendingSyncRequest,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }
    }

    void discardPendingSyncRequest() {
        if (pendingSyncRequest != null) {
            Log.d(TAG, "Discard previous pending sync request");
            synchronized (this) {
                try {
                    context.unregisterReceiver(pendingSyncRequest);
                } catch (IllegalArgumentException e) {
                    // ignore in case it has been unregistered
                    Log.d(TAG, "SyncOnConnectivity has been unregistered.");
                }
                pendingSyncRequest = null;
            }
        }
    }

    boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }
}

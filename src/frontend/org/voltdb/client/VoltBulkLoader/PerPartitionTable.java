/* This file is part of VoltDB.
 * Copyright (C) 2008-2019 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.client.VoltBulkLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.voltcore.logging.VoltLogger;
import org.voltcore.utils.CoreUtils;
import org.voltdb.ClientResponseImpl;
import org.voltdb.ParameterConverter;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.VoltTypeException;
import org.voltdb.client.ClientImpl;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcedureCallback;

/**
 * Partition specific table potentially shared by multiple VoltBulkLoader instances,
 * provided that they are all inserting to the same table.
 */
public class PerPartitionTable {
    private static final VoltLogger loaderLog = new VoltLogger("LOADER");

    // Client we are tied to
    final ClientImpl m_clientImpl;
    //The index in loader tables and the PartitionProcessor number
    final int m_partitionId;
    final boolean m_isMP;
    //Queue for processing pending rows for this table
    LinkedBlockingQueue<VoltBulkLoaderRow> m_partitionRowQueue;

    final ExecutorService m_es;

    //Zero based index of the partitioned column in the table
    final int m_partitionedColumnIndex;
    //Partitioned column type
    final VoltType m_partitionColumnType;
    //Table used to build up requests to the PartitionProcessor
    VoltTable m_table;
    //Column information
    final VoltTable.ColumnInfo m_columnInfo[];
    //Column types
    final VoltType[] m_columnTypes;
    //Size of the batches this table submits (minimum of all values provided by VoltBulkLoaders)
    volatile int m_minBatchTriggerSize;
    //Insert procedure name
    final String m_procName;
    //Name of table
    final String m_tableName;
    // Upsert Mode Flag
    final byte m_upsert;
    // Callback for per-row success notification
    final BulkLoaderSuccessCallback m_successCallback;
    //Whether to retry insertion when the connection is lost
    final boolean m_autoReconnect;

    // Callback for batch submissions to the Client. A failed request submits the entire
    // batch of rows to m_failedQueue for row by row processing on m_failureProcessor.
    class PartitionProcedureCallback implements ProcedureCallback {
        final List<VoltBulkLoaderRow> m_batchRowList;
        final Map<VoltBulkLoader, Long> m_batchSizes;

        PartitionProcedureCallback(List<VoltBulkLoaderRow> batchRowList, Map<VoltBulkLoader, Long> batchSizes) {
            m_batchRowList = batchRowList;
            m_batchSizes = batchSizes;
        }

        // Called by Client to inform us of the status of the bulk insert.
        @Override
        public void clientCallback(final ClientResponse response) throws InterruptedException {
            if (response.getStatus() != ClientResponse.SUCCESS) {
                // Queue up all rows for individual processing by originating BulkLoader's FailureProcessor.
                m_es.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            reinsertFailed(m_batchRowList);
                        } catch (Exception e) {
                            loaderLog.error("Failed to re-insert failed batch", e);
                        }
                    }
                });
            }
            else {
                // For each row in the batch, notify the caller of success, so it can do any
                // necessary bookkeeping (like managing offsets, for example). Do this in the executor
                // so as not to hold up the callback.
                if (m_successCallback != null) {
                    m_es.execute(new Runnable() {
                        @Override
                        public void run() {
                            for (VoltBulkLoaderRow r : m_batchRowList) {
                                m_successCallback.success(r.m_rowHandle, response);
                            }
                        }
                    });
                }
                for (Map.Entry<VoltBulkLoader, Long> e : m_batchSizes.entrySet()) {
                    e.getKey().m_loaderCompletedCnt.addAndGet(e.getValue());
                    e.getKey().m_outstandingRowCount.addAndGet(-1 * e.getValue());
                }
            }
        }
    }

    PerPartitionTable(ClientImpl clientImpl, String tableName, int partitionId, boolean isMP,
            VoltBulkLoader firstLoader, int minBatchTriggerSize, BulkLoaderSuccessCallback successCallback) {
        m_clientImpl = clientImpl;
        m_partitionId = partitionId;
        m_isMP = isMP;
        m_procName = firstLoader.m_procName;
        m_upsert = (byte) (firstLoader.m_upsert ? 1:0);
        m_partitionRowQueue = new LinkedBlockingQueue<VoltBulkLoaderRow>(minBatchTriggerSize*5);
        m_minBatchTriggerSize = minBatchTriggerSize;
        m_columnInfo = firstLoader.m_colInfo;
        m_partitionedColumnIndex = firstLoader.m_partitionedColumnIndex;
        m_columnTypes = firstLoader.m_columnTypes;
        m_partitionColumnType = firstLoader.m_partitionColumnType;
        m_tableName = tableName;
        m_successCallback = successCallback;
        m_table = new VoltTable(m_columnInfo);
        m_autoReconnect = m_clientImpl.isAutoReconnectEnabled();

        m_es = CoreUtils.getSingleThreadExecutor(tableName + "-" + partitionId);
    }

    boolean updateMinBatchTriggerSize(int minBatchTriggerSize) {
        if (m_minBatchTriggerSize >= minBatchTriggerSize) {
            // This will generate a batch of arbitrary length when the next insert is made
            m_minBatchTriggerSize = minBatchTriggerSize;
            return true;
        }
        else {
            return false;
        }
     }

    /**
     * Synchronized so that when the a single batch is filled up, we only queue one task to
     * drain the queue. The task will drain the queue until it doesn't contain a single batch.
     */
    synchronized void insertRowInTable(final VoltBulkLoaderRow nextRow) throws InterruptedException {
        m_partitionRowQueue.put(nextRow);
        if (m_partitionRowQueue.size() == m_minBatchTriggerSize) {
            m_es.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (m_partitionRowQueue.size() >= m_minBatchTriggerSize) {
                            loadTable(buildTable(), m_table);
                        }
                    } catch (Exception e) {
                        loaderLog.error("Failed to load batch", e);
                    }
                }
            });
        }
    }

    /**
     * Flush all queued rows even if they are smaller than the batch size. This does not
     * guarantee that they will be reinserted if any of them fail. To make sure all rows
     * are either inserted or failed definitively, call shutdown().
     */
    Future<?> flushAllTableQueues() throws InterruptedException {
        return m_es.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                loadTable(buildTable(), m_table);
                return true;
            }
        });
    }

    void shutdown() throws Exception {
        try {
            flushAllTableQueues().get();
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
        m_es.shutdown();
        m_es.awaitTermination(365, TimeUnit.DAYS);
    }

    private void reinsertFailed(List<VoltBulkLoaderRow> rows) throws Exception {
        VoltTable tmpTable = new VoltTable(m_columnInfo);
        for (final VoltBulkLoaderRow row : rows) {
            // No need to check error here if a correctedLine has come here it was
            // previously successful.
            try {
                Object row_args[] = new Object[row.m_rowData.length];
                for (int i = 0; i < row_args.length; i++) {
                    final VoltType type = m_columnTypes[i];
                    row_args[i] = ParameterConverter.tryToMakeCompatible(type.classFromType(),
                            row.m_rowData[i]);
                }
                tmpTable.addRow(row_args);
            } catch (VoltTypeException ex) {
                // Should never happened because the bulk conversion in PerPartitionProcessor
                // should have caught this.
                loaderLog.error("Type conversion exception", ex);
                assert false: "Type conversion exception" + ex.getMessage();
                continue;
            }

            ProcedureCallback callback = new ProcedureCallback() {
                @Override
                public void clientCallback(ClientResponse response) throws Exception {
                    //one insert at a time callback
                    if (response.getStatus() == ClientResponse.CONNECTION_LOST && m_autoReconnect) {
                        m_es.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    reinsertFailed(Arrays.asList(row));
                                } catch (Exception e) {
                                    loaderLog.error("Failed to re-insert failed batch", e);
                                }
                            }
                        });
                        return;
                    }
                    else if (response.getStatus() != ClientResponse.SUCCESS) {
                        row.m_loader.m_notificationCallBack.failureCallback(row.m_rowHandle, row.m_rowData, response);
                    }

                    row.m_loader.m_loaderCompletedCnt.incrementAndGet();
                    row.m_loader.m_outstandingRowCount.decrementAndGet();
                }
            };
            loadTable(callback, tmpTable);
        }
    }

    private PartitionProcedureCallback buildTable() {
        ArrayList<VoltBulkLoaderRow> buf = new ArrayList<VoltBulkLoaderRow>(m_minBatchTriggerSize);
        m_partitionRowQueue.drainTo(buf, m_minBatchTriggerSize);

        Map<VoltBulkLoader, Long> batchSizes = new HashMap<>();
        ListIterator<VoltBulkLoaderRow> it = buf.listIterator();
        while (it.hasNext()) {
            VoltBulkLoaderRow currRow = it.next();
            VoltBulkLoader loader = currRow.m_loader;
            Object row_args[];
            row_args = new Object[currRow.m_rowData.length];
            try {
                for (int i = 0; i < row_args.length; i++) {
                    final VoltType type = m_columnTypes[i];
                    row_args[i] = ParameterConverter.tryToMakeCompatible(type.classFromType(),
                            currRow.m_rowData[i]);
                }
                m_table.addRow(row_args);
            } catch (Exception e) {
                loader.generateError(currRow.m_rowHandle, currRow.m_rowData, e.getMessage());
                loader.m_outstandingRowCount.decrementAndGet();
                it.remove();
                continue;
            }

            Long prevValue;
            if ((prevValue = batchSizes.put(loader, 1L)) != null) {
                batchSizes.put(loader, prevValue + 1);
            }
        }

        return new PartitionProcedureCallback(buf, batchSizes);
    }

    private void loadTable(ProcedureCallback callback, VoltTable toSend) throws Exception {
        if (toSend.getRowCount() <= 0) {
            return;
        }

        if (m_autoReconnect) {
            while (true) {
                try {
                    load(callback, toSend);
                    // Table loaded successfully. So move on
                    break;
                } catch (IOException e) {
                   synchronized (this) {
                       // If the connection is lost, suspend and wait for reconnect listener's notification
                       this.wait();
                   }
                }
            }
        } else {
            try {
                load(callback, toSend);
            } catch (IOException e) {
                final ClientResponse r = new ClientResponseImpl(
                        ClientResponse.CONNECTION_LOST, new VoltTable[0],
                        "Connection to database was lost");
                callback.clientCallback(r);
            }
        }
        toSend.clearRowData();
    }

    private void load(ProcedureCallback callback, VoltTable toSend) throws Exception {
        if (m_isMP) {
            m_clientImpl.callProcedure(callback, m_procName, m_tableName, m_upsert, toSend);
        } else {
            Object rpartitionParam = VoltType.valueToBytes(toSend.fetchRow(0).get(
                    m_partitionedColumnIndex, m_partitionColumnType));
            m_clientImpl.callProcedure(callback, m_procName, rpartitionParam, m_tableName, m_upsert, toSend);
        }
    }
}

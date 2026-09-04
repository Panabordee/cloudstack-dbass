// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.ldap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-account progress registry for LDAP user imports, polled by the UI
 * through the listLdapImportProgress API while an import is running.
 */
public final class LdapImportProgress {

    public static final String STATUS_IDLE = "Idle";
    public static final String STATUS_FETCHING = "Fetching";
    public static final String STATUS_IMPORTING = "Importing";
    public static final String STATUS_FINISHED = "Finished";

    private static final long FINISHED_ENTRY_RETENTION_MS = 10 * 60 * 1000L;
    private static final Map<Long, LdapImportProgress> PROGRESS_BY_ACCOUNT = new ConcurrentHashMap<>();

    private volatile String status = STATUS_IDLE;
    private volatile long total;
    private volatile long processed;
    private volatile long imported;
    private volatile long skipped;
    private volatile long failed;
    private volatile String currentUser;
    private volatile long startTime;
    private volatile long endTime;

    private LdapImportProgress() {
    }

    public static void startFetching(final long accountId) {
        final LdapImportProgress progress = new LdapImportProgress();
        progress.status = STATUS_FETCHING;
        progress.startTime = System.currentTimeMillis();
        PROGRESS_BY_ACCOUNT.put(accountId, progress);
        purgeStaleEntries();
    }

    public static void startImporting(final long accountId, final long total) {
        final LdapImportProgress progress = PROGRESS_BY_ACCOUNT.get(accountId);
        if (progress != null) {
            progress.total = total;
            progress.status = STATUS_IMPORTING;
        }
    }

    public static void setCurrentUser(final long accountId, final String username) {
        final LdapImportProgress progress = PROGRESS_BY_ACCOUNT.get(accountId);
        if (progress != null) {
            progress.currentUser = username;
        }
    }

    public static void recordProcessed(final long accountId, final boolean imported, final boolean skipped, final boolean failed, final String nextUser) {
        final LdapImportProgress progress = PROGRESS_BY_ACCOUNT.get(accountId);
        if (progress != null) {
            synchronized (progress) {
                progress.processed++;
                if (imported) {
                    progress.imported++;
                }
                if (skipped) {
                    progress.skipped++;
                }
                if (failed) {
                    progress.failed++;
                }
                progress.currentUser = nextUser;
            }
        }
    }

    public static void finish(final long accountId) {
        final LdapImportProgress progress = PROGRESS_BY_ACCOUNT.get(accountId);
        if (progress != null) {
            synchronized (progress) {
                progress.status = STATUS_FINISHED;
                progress.endTime = System.currentTimeMillis();
                progress.currentUser = null;
            }
        }
    }

    public static LdapImportProgress get(final long accountId) {
        final LdapImportProgress progress = PROGRESS_BY_ACCOUNT.get(accountId);
        if (progress != null && progress.status.equals(STATUS_FINISHED)
                && System.currentTimeMillis() - progress.endTime > FINISHED_ENTRY_RETENTION_MS) {
            PROGRESS_BY_ACCOUNT.remove(accountId, progress);
            return null;
        }
        return progress;
    }

    private static void purgeStaleEntries() {
        for (final Map.Entry<Long, LdapImportProgress> entry : PROGRESS_BY_ACCOUNT.entrySet()) {
            final LdapImportProgress progress = entry.getValue();
            if (progress.status.equals(STATUS_FINISHED)
                    && System.currentTimeMillis() - progress.endTime > FINISHED_ENTRY_RETENTION_MS) {
                PROGRESS_BY_ACCOUNT.remove(entry.getKey(), progress);
            }
        }
    }

    public String getStatus() {
        return status;
    }

    public long getTotal() {
        return total;
    }

    public long getProcessed() {
        return processed;
    }

    public long getImported() {
        return imported;
    }

    public long getSkipped() {
        return skipped;
    }

    public long getFailed() {
        return failed;
    }

    public String getCurrentUser() {
        return currentUser;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }
}

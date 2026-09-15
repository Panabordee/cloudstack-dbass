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

<template>
  <div class="form-layout">
    <a-spin :spinning="loading">
      <!-- One database's credential at a time, picked from the dropdown
           below, not every database dumped on screen at once. An instance
           can hold several (createDatabase may be called on it again at any
           time); entries still holds all of them so switching the dropdown
           is instant, nothing is re-fetched. -->
      <a-form-item v-if="entries.length > 1" :label="$t('label.database')" class="database-picker">
        <a-select v-model:value="selectedKey" :options="entryOptions" style="width: 100%" />
      </a-form-item>
      <div v-if="selectedEntry" class="credentials-block">
        <div v-if="selectedEntry.database" class="database-heading">{{ selectedEntry.database }}</div>
        <a-descriptions bordered size="small" :column="1" class="credentials">
          <a-descriptions-item :label="$t('label.engine')">{{ selectedEntry.engine }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.username')">{{ selectedEntry.username }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.password')">
            <span v-if="selectedEntry.password">{{ selectedEntry.password }}</span>
            <span v-else class="unavailable">{{ selectedEntry.statusmessage || $t('message.dbaas.status.pending') }}</span>
          </a-descriptions-item>
          <a-descriptions-item v-if="selectedEntry.connectCommand" :label="$t('label.connect.command')">
            <span class="connect-command">{{ selectedEntry.connectCommand }}</span>
          </a-descriptions-item>
        </a-descriptions>
        <div v-if="selectedEntry.connectCommand" class="entry-actions">
          <a-button
            size="small"
            type="primary"
            @click="notifyCopied"
            v-clipboard:copy="selectedEntry.connectCommand">
            {{ $t('label.copy.connect.command') }}
          </a-button>
        </div>
      </div>
      <!-- A credential exists but the instance has not confirmed it applied
           it (config-drive provisioning stores the credential before the
           instance boots). The password below is the one it will use. -->
      <a-alert
        v-if="credentials.found && provisioningPending"
        type="info"
        showIcon
        :message="$t('message.dbaas.status.pending')"
        class="state-alert" />
      <a-alert
        v-else-if="credentials.found && provisioningFailed"
        type="error"
        showIcon
        :message="$t('message.dbaas.status.failed')"
        :description="credentials.statusmessage"
        class="state-alert" />
      <a-alert
        v-else-if="loaded && miss && autoChecking"
        type="info"
        showIcon
        :message="$t('message.dbaas.provisioning.inprogress', { count: autoChecks, total: maxAutoChecks })"
        class="state-alert" />
      <a-alert
        v-else-if="loaded && miss"
        type="warning"
        showIcon
        :message="$t('message.dbaas.no.stored.credential')"
        class="state-alert" />
      <!-- Only when the fetch itself failed. This used to be a bare
           `v-else-if="loaded"`, which meant the *success* case -- credential
           found, status confirmed, password rendered right above -- fell
           through to it and the dialog showed a red "could not load"
           alongside the credential it had just loaded (observed 2026-09-10
           on the Database page). -->
      <a-alert
        v-else-if="loaded && errorMsg"
        type="error"
        showIcon
        :message="$t('message.dbaas.credential.load.failed')"
        :description="errorMsg"
        class="state-alert" />
      <a-alert
        v-else-if="!loaded"
        type="info"
        showIcon
        :message="$t('message.desc.show.database.password')"
        class="state-alert" />
      <p v-if="credentials.found && credentials.password" class="connect-hint">{{ $t('message.dbaas.connect.command') }}</p>
      <div :span="24" class="action-button">
        <a-button
          v-if="credentials.found && connectCommand"
          @click="notifyCopied"
          v-clipboard:copy="connectCommand"
          type="primary">
          {{ $t('label.copy.connect.command') }}
        </a-button>
        <a-button v-if="loaded && miss" @click="retry">{{ $t('label.retry') }}</a-button>
        <a-button @click="closeAction">{{ $t('label.close') }}</a-button>
      </div>
    </a-spin>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import { buildConnectCommand } from '@/utils/dbaas'

export default {
  name: 'ShowDatabasePassword',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      loading: false,
      loaded: false,
      errorMsg: '',
      autoChecks: 0,
      // 30 x 10s = 5 minutes: a config-drive provision includes a full first
      // boot (image boot, cloud-init, engine readiness wait), so 2 minutes
      // regularly gave up while the instance was still working.
      maxAutoChecks: 30,
      retryTimerId: null,
      credentials: {},
      // One resolved credential per database on this instance.
      entries: [],
      // Which entry's password is on screen. Fetching every credential up
      // front (fetchAllDatabases) still happens, so this never triggers a
      // network call -- it only picks which already-loaded entry to render.
      selectedKey: null
    }
  },
  created () {
    this.fetchPassword()
  },
  beforeUnmount () {
    // The auto-check timer keeps firing against a dead dialog otherwise.
    if (this.retryTimerId) {
      clearTimeout(this.retryTimerId)
      this.retryTimerId = null
    }
  },
  computed: {
    connectCommand () {
      return buildConnectCommand(this.credentials)
    },
    // Machine-readable miss (backend responds 200 with found=false while the
    // database is still being provisioned); anything else that failed to load
    // is a hard error.
    miss () {
      return this.loaded && this.credentials.found === false
    },
    autoChecking () {
      return this.miss && this.autoChecks > 0 && this.autoChecks < this.maxAutoChecks
    },
    // Reported by the instance, not inferred here: 'pending' means the
    // credential was generated and handed to the instance but nothing has
    // confirmed the engine came up with it yet.
    provisioningPending () {
      return this.credentials.status === 'pending'
    },
    provisioningFailed () {
      return this.credentials.status === 'failed'
    },
    entryOptions () {
      return this.entries.map(e => ({ value: e.key, label: e.database || e.username || e.key }))
    },
    selectedEntry () {
      return this.entries.find(e => e.key === this.selectedKey) || this.entries[0]
    }
  },
  methods: {
    fetchPassword () {
      if (this.retryTimerId) {
        // A pending auto-check would keep firing alongside this request --
        // two chains interleaving API calls and state writes.
        clearTimeout(this.retryTimerId)
        this.retryTimerId = null
      }
      this.loading = true
      this.loaded = false
      this.errorMsg = ''
      // Drop stale credentials up front: if this fetch fails, showing an old
      // password next to the red alert would be misleading.
      this.credentials = {}
      getAPI('getDatabasePassword', { virtualmachineid: this.resource.id }).then(json => {
        this.credentials = json.getdatabasepasswordresponse?.dbaas || {}
        this.loaded = true
        this.fetchAllDatabases()
        const unsettled = this.credentials.found === false || this.credentials.status === 'pending'
        if (unsettled && this.autoChecks < this.maxAutoChecks) {
          this.autoChecks++
          this.retryTimerId = setTimeout(() => this.fetchPassword(), 10000)
        }
      }).catch(error => {
        const data = error?.response?.data
        const text = data ? (data[Object.keys(data).find(k => data[k] && data[k].errortext)]?.errortext || '') : ''
        this.errorMsg = text || error?.message || String(error)
        this.loaded = true
      }).finally(() => {
        this.loading = false
      })
    },
    // Every database on the instance, each with its own credential.
    // listDbaasDatabases gives the names and usernames; the password comes
    // from getDatabasePassword per username, which is the only call that
    // decrypts one. A database whose credential has not been confirmed yet
    // is still listed, with its status in place of the password, rather than
    // being hidden until it settles.
    fetchAllDatabases () {
      getAPI('listDbaasDatabases', { virtualmachineid: this.resource.id }).then(json => {
        const list = (json.listdbaasdatabasesresponse || {}).dbaasdatabase || []
        if (list.length === 0) {
          this.entries = this.fallbackEntries()
          this.selectDefaultEntry()
          return
        }
        Promise.all(list.map(db => this.fetchOne(db))).then(rows => {
          this.entries = rows.filter(Boolean)
          this.selectDefaultEntry()
        })
      }).catch(() => {
        // An older management server has no listDbaasDatabases: fall back to
        // the single credential this dialog has always shown.
        this.entries = this.fallbackEntries()
        this.selectDefaultEntry()
      })
    },
    // Keep whatever the user already picked if it still exists (a retry or
    // auto-check re-fetches everything); otherwise default to the first
    // entry so something renders without the user having to touch the
    // dropdown.
    selectDefaultEntry () {
      if (!this.entries.some(e => e.key === this.selectedKey)) {
        this.selectedKey = this.entries[0]?.key ?? null
      }
    },
    fetchOne (db) {
      const params = { virtualmachineid: this.resource.id }
      if (db.username) {
        params.dbusername = db.username
      }
      return getAPI('getDatabasePassword', params).then(json => {
        const c = json.getdatabasepasswordresponse?.dbaas || {}
        return {
          key: db.database || db.username || 'default',
          database: db.database,
          engine: c.engine || db.engine,
          username: c.username || db.username,
          password: c.password,
          status: c.status || db.status,
          statusmessage: c.statusmessage,
          connectCommand: buildConnectCommand({ ...c, database: db.database })
        }
      }).catch(() => ({
        key: db.database || db.username || 'default',
        database: db.database,
        engine: db.engine,
        username: db.username,
        password: '',
        status: db.status,
        statusmessage: ''
      }))
    },
    fallbackEntries () {
      const c = this.credentials
      if (!c.found || !c.username) {
        return []
      }
      return [{
        key: c.username,
        database: c.database,
        engine: c.engine,
        username: c.username,
        password: c.password,
        status: c.status,
        statusmessage: c.statusmessage,
        connectCommand: this.connectCommand
      }]
    },
    retry () {
      // A manual retry restarts the auto-check budget as well.
      this.autoChecks = 0
      this.fetchPassword()
    },
    notifyCopied () {
      this.$notification.info({
        message: this.$t('message.success.copy.clipboard')
      })
    },
    closeAction () {
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped lang="less">
  .form-layout {
    width: 80vw;
    // Never wider than whatever is hosting this dialog: the Database page
    // opens these in a fixed-width modal, and a fixed 560px content inside a
    // narrower modal spills over its background instead of being clipped or
    // wrapped (observed 2026-09-10, Show Password, 134px past the panel).
    max-width: 100%;

    @media (min-width: 600px) {
      width: 560px;
    }
  }

  // No word-break here: labels wrap at spaces; only the value spans
  // (.connect-command) break-all, since commands have no spaces to wrap on.
  .database-picker {
    margin-top: 16px;
    margin-bottom: 0;
  }

  .credentials {
    margin-top: 16px;

    // a-descriptions renders a real <table> with automatic layout, so a long
    // unbreakable value -- the connect command, which is one token with no
    // spaces -- widens the table past the dialog instead of wrapping inside
    // it, and the content spills over the modal's edge. Pin the layout, give
    // the label column a fixed share, and let the value column break
    // anywhere.
    :deep(.ant-descriptions-view) {
      width: 100%;
    }

    :deep(table) {
      width: 100%;
      table-layout: fixed;
    }

    :deep(.ant-descriptions-item-label) {
      width: 34%;
      white-space: normal;
      overflow-wrap: anywhere;
    }

    :deep(.ant-descriptions-item-content) {
      overflow-wrap: anywhere;
      word-break: break-word;
    }
  }

  .connect-command {
    font-family: monospace;
    word-break: break-all;
  }

  .connect-hint {
    margin-top: 4px;
    color: rgba(0, 0, 0, 0.45);
    word-break: break-all;
  }

  .state-alert {
    margin-top: 16px;
    word-break: break-all;
  }
</style>

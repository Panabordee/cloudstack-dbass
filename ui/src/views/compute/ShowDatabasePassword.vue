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
      <a-descriptions v-if="credentials.found && credentials.password" bordered size="small" :column="1" class="credentials">
        <a-descriptions-item :label="$t('label.engine')">{{ credentials.engine }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.username')">{{ credentials.username }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.password')">{{ credentials.password }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.connect.command')">
          <span class="connect-command">{{ connectCommand }}</span>
        </a-descriptions-item>
      </a-descriptions>
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
      <a-alert
        v-else-if="loaded"
        type="error"
        showIcon
        :message="$t('message.dbaas.credential.load.failed')"
        :description="errorMsg"
        class="state-alert" />
      <a-alert
        v-else
        type="info"
        showIcon
        :message="$t('message.desc.show.database.password')"
        class="state-alert" />
      <p v-if="credentials.found && credentials.password" class="connect-hint">{{ $t('message.dbaas.connect.command') }}</p>
      <div :span="24" class="action-button">
        <a-button
          v-if="credentials.found && credentials.password"
          @click="notifyCopied"
          v-clipboard:copy="credentials.password"
          type="primary">
          {{ $t('label.copy.password') }}
        </a-button>
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
      maxAutoChecks: 12,
      retryTimerId: null,
      credentials: {}
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

    @media (min-width: 600px) {
      width: 450px;
    }
  }

  // No word-break here: labels wrap at spaces; only the value spans
  // (.connect-command) break-all, since commands have no spaces to wrap on.
  .credentials {
    margin-top: 16px;
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

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
      <a-descriptions v-if="credentials.password" bordered size="small" :column="1" class="credentials">
        <a-descriptions-item :label="$t('label.engine')">{{ credentials.engine }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.username')">{{ credentials.username }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.password')">{{ credentials.password }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.connect.command')">
          <span class="connect-command">{{ connectCommand }}</span>
        </a-descriptions-item>
      </a-descriptions>
      <p class="connect-hint">{{ $t('message.dbaas.connect.command') }}</p>
      <template v-if="credentials.password && credentials.vmusername">
        <a-descriptions bordered size="small" :column="1" class="credentials">
          <a-descriptions-item :label="$t('label.vm.username')">{{ credentials.vmusername }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.vm.password')">{{ credentials.vmpassword }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.ssh.command')">
            <span class="connect-command">{{ sshCommand }}</span>
          </a-descriptions-item>
        </a-descriptions>
      </template>
      <a-alert
        v-else-if="loaded && noCredential"
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
      <div :span="24" class="action-button">
        <a-button
          v-if="credentials.password"
          @click="notifyCopied"
          v-clipboard:copy="credentials.password"
          type="primary">
          {{ $t('label.copy.password') }}
        </a-button>
        <a-button
          v-if="connectCommand"
          @click="notifyCopied"
          v-clipboard:copy="connectCommand"
          type="primary">
          {{ $t('label.copy.connect.command') }}
        </a-button>
        <a-button
          v-if="credentials.vmpassword"
          @click="notifyCopied"
          v-clipboard:copy="credentials.vmpassword">
          {{ $t('label.copy.vm.password') }}
        </a-button>
        <a-button
          v-if="sshCommand"
          @click="notifyCopied"
          v-clipboard:copy="sshCommand">
          {{ $t('label.copy.ssh.command') }}
        </a-button>
        <a-button v-if="loaded && !credentials.password" @click="fetchPassword">{{ $t('label.retry') }}</a-button>
        <a-button @click="closeAction">{{ $t('label.close') }}</a-button>
      </div>
    </a-spin>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import { buildConnectCommand, buildSshCommand } from '@/utils/dbaas'

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
      noCredential: false,
      errorMsg: '',
      credentials: {}
    }
  },
  created () {
    this.fetchPassword()
  },
  computed: {
    sshCommand () {
      return buildSshCommand(this.credentials)
    },
    connectCommand () {
      return buildConnectCommand(this.credentials)
    }
  },
  methods: {
    fetchPassword () {
      this.loading = true
      this.loaded = false
      this.noCredential = false
      this.errorMsg = ''
      getAPI('getDatabasePassword', { virtualmachineid: this.resource.id }).then(json => {
        this.credentials = json.getdatabasepasswordresponse?.dbaas || {}
        this.loaded = true
      }).catch(error => {
        // The backend answers "No stored database credential found for this
        // VM" while a createDatabase is still provisioning (or before the
        // first one ever ran) -- that is a normal, retryable state, not a
        // broken fetch, so give it its own message instead of the spinner
        // hanging on the initial info alert forever.
        const data = error?.response?.data
        const text = data ? (data[Object.keys(data).find(k => data[k] && data[k].errortext)]?.errortext || '') : ''
        this.errorMsg = text || error?.message || String(error)
        this.noCredential = this.errorMsg.includes('No stored database credential')
        this.loaded = true
      }).finally(() => {
        this.loading = false
      })
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

  .credentials {
    margin-top: 16px;
    word-break: break-all;
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

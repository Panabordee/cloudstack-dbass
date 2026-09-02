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
      <a-alert
        v-if="!credentials.password"
        type="info"
        showIcon
        :message="$t('message.desc.show.database.password')" />
      <a-descriptions v-else bordered size="small" :column="1" class="credentials">
        <a-descriptions-item :label="$t('label.engine')">{{ credentials.engine }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.username')">{{ credentials.username }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.password')">{{ credentials.password }}</a-descriptions-item>
      </a-descriptions>
      <div :span="24" class="action-button">
        <a-button
          v-if="credentials.password"
          @click="notifyCopied"
          v-clipboard:copy="credentials.password"
          type="primary">
          {{ $t('label.copy.password') }}
        </a-button>
        <a-button @click="closeAction">{{ $t('label.close') }}</a-button>
      </div>
    </a-spin>
  </div>
</template>

<script>
import { getAPI } from '@/api'

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
      credentials: {}
    }
  },
  created () {
    this.fetchPassword()
  },
  methods: {
    fetchPassword () {
      this.loading = true
      getAPI('getDatabasePassword', { virtualmachineid: this.resource.id }).then(json => {
        this.credentials = json.getdatabasepasswordresponse?.dbaas || {}
      }).catch(error => {
        this.$notifyError(error)
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
</style>

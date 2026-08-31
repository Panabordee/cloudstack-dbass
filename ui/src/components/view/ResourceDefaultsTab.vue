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
  <div>
    <a-alert type="info" show-icon class="defaults-hint">
      <template #message>{{ $t('label.resource.defaults.hint') }}</template>
    </a-alert>
    <a-table
      :columns="columns"
      :data-source="rows"
      :pagination="false"
      :loading="listLoading"
      rowKey="type"
      size="middle"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'resource'">
          <a-tooltip :title="record.description">
            <span>{{ record.label }}</span>
          </a-tooltip>
        </template>
        <template v-else-if="column.key === 'account'">
          <a-input-number
            style="width: 100%"
            :min="-1"
            v-model:value="form['account_' + record.type]"
            :disabled="!('updateConfiguration' in $store.getters.apis)"
          />
        </template>
        <template v-else-if="column.key === 'project'">
          <span v-if="record.type === 'project'" class="not-applicable">{{ $t('label.na') }}</span>
          <a-input-number
            v-else
            style="width: 100%"
            :min="-1"
            v-model:value="form['project_' + record.type]"
            :disabled="!('updateConfiguration' in $store.getters.apis)"
          />
        </template>
      </template>
    </a-table>
    <div class="defaults-actions">
      <a-button
        type="primary"
        :loading="saving"
        :disabled="!('updateConfiguration' in $store.getters.apis)"
        @click="handleSubmit"
      >{{ $t('label.submit') }}</a-button>
    </div>
  </div>
</template>

<script>
import { reactive } from 'vue'
import { getAPI, postAPI } from '@/api'

const ACCOUNT_PREFIX = 'resource.default.account.'
const PROJECT_PREFIX = 'resource.default.project.'

export default {
  name: 'ResourceDefaultsTab',
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      rows: [],
      form: {},
      origValues: {},
      listLoading: false,
      saving: false
    }
  },
  computed: {
    columns () {
      return [
        {
          key: 'resource',
          title: this.$t('label.resource'),
          dataIndex: 'label'
        },
        {
          key: 'account',
          title: this.$t('label.account.limit')
        },
        {
          key: 'project',
          title: this.$t('label.project.limit')
        }
      ]
    }
  },
  created () {
    this.fetchData()
  },
  watch: {
    resource: {
      handler (newItem) {
        if (!newItem || !newItem.id) {
          return
        }
        this.fetchData()
      }
    }
  },
  methods: {
    typeLabel (type) {
      const localeKey = type === 'backup' ? 'label.maxbackups' : 'label.max' + type.replace('_', '')
      return this.$t(localeKey)
    },
    buildRows (configs) {
      const descriptions = {}
      configs.forEach(config => {
        if (config.name.startsWith(ACCOUNT_PREFIX)) {
          descriptions[config.name.substring(ACCOUNT_PREFIX.length)] = config.description
        }
      })
      return descriptions
    },
    normalizeValue (value) {
      if (value === '' || value === null || value === undefined) {
        return null
      }
      const num = Number(value)
      return isNaN(num) ? null : num
    },
    fetchData () {
      this.listLoading = true
      const accountParams = { domainid: this.resource.id, name: ACCOUNT_PREFIX }
      const projectParams = { domainid: this.resource.id, name: PROJECT_PREFIX }
      Promise.all([
        getAPI('listConfigurations', accountParams),
        getAPI('listConfigurations', projectParams)
      ]).then(([accountRes, projectRes]) => {
        const configs = [
          ...(accountRes?.listconfigurationsresponse?.configuration || []),
          ...(projectRes?.listconfigurationsresponse?.configuration || [])
        ]
        const descriptions = this.buildRows(configs)
        const types = ['user_vm', 'public_ip', 'volume', 'snapshot', 'template', 'project', 'network', 'vpc',
          'cpu', 'memory', 'primary_storage', 'secondary_storage', 'backup', 'backup_storage', 'bucket',
          'object_storage', 'gpu']
        this.form = reactive({})
        this.origValues = {}
        this.rows = types.map(type => {
          const accountConfig = configs.find(c => c.name === ACCOUNT_PREFIX + type)
          const projectConfig = configs.find(c => c.name === PROJECT_PREFIX + type)
          const accountValue = this.normalizeValue(accountConfig?.value)
          const projectValue = this.normalizeValue(projectConfig?.value)
          this.form['account_' + type] = accountValue
          this.origValues['account_' + type] = accountValue
          if (type !== 'project') {
            this.form['project_' + type] = projectValue
            this.origValues['project_' + type] = projectValue
          }
          return {
            type: type,
            label: this.typeLabel(type),
            description: descriptions[type] || ''
          }
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.listLoading = false
      })
    },
    handleSubmit () {
      if (this.saving) {
        return
      }
      const arrAsync = []
      Object.keys(this.form).forEach(key => {
        const newValue = this.form[key]
        const origValue = this.origValues[key]
        if (newValue === origValue) {
          return
        }
        const isAccount = key.startsWith('account_')
        const type = key.substring((isAccount ? 'account_' : 'project_').length)
        const params = {
          domainid: this.resource.id,
          name: (isAccount ? ACCOUNT_PREFIX : PROJECT_PREFIX) + type,
          value: newValue === null || newValue === undefined ? '' : String(newValue)
        }
        arrAsync.push(postAPI('updateConfiguration', params))
      })
      if (arrAsync.length === 0) {
        this.$message.info(this.$t('message.no.changes.to.save'))
        return
      }
      this.saving = true
      Promise.all(arrAsync).then(() => {
        this.$message.success(this.$t('message.apply.success'))
        this.fetchData()
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.saving = false
      })
    }
  }
}
</script>

<style lang="less" scoped>
.defaults-hint {
  margin-bottom: 16px;
}

.defaults-actions {
  margin-top: 16px;
  text-align: right;
}

.not-applicable {
  color: rgba(0, 0, 0, 0.45);
}
</style>

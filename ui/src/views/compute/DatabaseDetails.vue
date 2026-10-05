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
  <div class="database-details">
    <a-card class="breadcrumb-card">
      <router-link to="/database"><arrow-left-outlined /> {{ $t('label.database') }}</router-link>
      <a-button style="float: right" :loading="loading" @click="fetchData">{{ $t('label.refresh') }}</a-button>
    </a-card>
    <a-spin :spinning="loading">
      <a-alert v-if="error" type="error" show-icon :message="error" style="margin: 16px 0" />
      <template v-if="resource.id">
        <a-card class="row-element database-summary" size="small">
          <h2><database-outlined /> {{ resource.displayname || resource.name }}</h2>
          <a-tag color="blue">{{ engineLabel }}</a-tag>
          <status :text="resource.state" displayText />
          <a-descriptions :column="{ xs: 1, sm: 2, lg: 3 }" size="small" style="margin-top: 12px">
            <a-descriptions-item label="Endpoint">{{ endpoint }}</a-descriptions-item>
            <a-descriptions-item label="Databases">{{ databases.length }}</a-descriptions-item>
          </a-descriptions>
          <a-space wrap>
            <a-button v-if="can('createDatabase')" type="primary" :disabled="!available" @click="action = 'create'">{{ $t('label.create.database') }}</a-button>
            <a-button v-if="can('getDatabasePassword')" :disabled="!available" @click="action = 'password'">Connection</a-button>
            <a-dropdown v-if="can('resetDatabasePassword')">
              <a-button aria-label="More database actions"><more-outlined /></a-button>
              <template #overlay>
                <a-menu @click="action = 'reset'">
                  <a-menu-item key="reset" :disabled="resource.state !== 'Running'">{{ $t('label.reset.database.password') }}</a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </a-space>
        </a-card>
        <a-card class="row-element" size="small">
          <a-tabs v-model:activeKey="tab">
            <a-tab-pane key="query" tab="Query">
              <dbaas-console v-if="resource.state === 'Running' && can('listDbaasTables')" :key="resource.id + openedDatabase" :resource="resource" :initial-database="openedDatabase" initial-tab="sql" />
              <a-alert v-else type="info" show-icon :message="resource.state !== 'Running' ? 'Start the database to run queries.' : 'Query access is unavailable.'" />
            </a-tab-pane>
            <a-tab-pane key="databases" tab="Databases">
              <a-table :columns="columns" :data-source="databases" row-key="database" :pagination="false">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'status'">
                    {{ ({ confirmed: 'Ready', pending: 'Creating', failed: 'Failed' })[record.status] || record.status }}
                  </template>
                  <template v-if="column.key === 'open'">
                    <a-button type="link" :disabled="resource.state !== 'Running' || !can('listDbaasTables')" @click="openDatabase(record.database)">Open query</a-button>
                  </template>
                </template>
              </a-table>
            </a-tab-pane>
          </a-tabs>
        </a-card>
      </template>
    </a-spin>
    <a-modal :visible="!!action" :footer="null" :width="680" :title="actionTitle" @cancel="closeAction">
      <create-database v-if="action === 'create'" :resource="resource" @close-action="closeAction" @refresh-data="fetchData" />
      <show-database-password v-if="action === 'password'" :resource="resource" @close-action="closeAction" />
      <reset-database-password v-if="action === 'reset'" :resource="resource" @close-action="closeAction" @refresh-data="fetchData" />
    </a-modal>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import Status from '@/components/widgets/Status.vue'
import DbaasConsole from './DbaasConsole.vue'
import CreateDatabase from './CreateDatabase.vue'
import ShowDatabasePassword from './ShowDatabasePassword.vue'
import ResetDatabasePassword from './ResetDatabasePassword.vue'
import { DBAAS_TEMPLATE_PREFIX } from '@/utils/dbaas'

export default {
  name: 'DatabaseDetails',
  components: { Status, DbaasConsole, CreateDatabase, ShowDatabasePassword, ResetDatabasePassword },
  data () {
    return {
      resource: {},
      databases: [],
      engines: [],
      error: '',
      loading: false,
      tab: 'query',
      action: '',
      openedDatabase: '',
      columns: [
        { title: 'Database', dataIndex: 'database', key: 'database' },
        { title: 'Status', dataIndex: 'status', key: 'status' },
        { title: 'Query', key: 'open' }
      ]
    }
  },
  computed: {
    engineType () {
      return (this.resource.templatename || '').replace(/^dbaas-/, '').replace(/-v\d+$/, '')
    },
    engineLabel () {
      const engine = this.engines.find(e => e.template === this.resource.templatename)
      return engine?.name || ({ mysql: 'MySQL', mariadb: 'MariaDB', postgresql: 'PostgreSQL', mongodb: 'MongoDB' })[this.engineType] || this.engineType
    },
    endpoint () {
      const internal = this.resource.ipaddress || this.resource.nic?.find(n => n.isdefault)?.ipaddress
      const host = this.$config?.dbaas?.connectionHosts?.[internal] || internal
      const port = ({ mysql: 3306, mariadb: 3306, postgresql: 5432, mongodb: 27017 })[this.engineType]
      return host ? host + (port ? ':' + port : '') : '—'
    },
    available () { return ['Running', 'Stopped'].includes(this.resource.state) },
    actionTitle () {
      return this.action === 'password' ? 'Connection' : this.$t(({ create: 'label.create.database', reset: 'label.reset.database.password' })[this.action] || 'label.database')
    }
  },
  watch: {
    '$route.params.id': 'fetchData'
  },
  created () { this.fetchData() },
  methods: {
    can (api) { return !!this.$store.getters.apis?.[api] },
    openDatabase (name) { this.openedDatabase = name; this.tab = 'query' },
    closeAction () { this.action = '' },
    async fetchData () {
      const id = this.$route.params.id
      this.loading = true
      this.error = ''
      this.resource = {}
      this.databases = []
      this.openedDatabase = ''
      try {
        const json = await getAPI('listVirtualMachines', { id })
        const vm = (json.listvirtualmachinesresponse || {}).virtualmachine?.[0]
        if (!vm || !(vm.templatename || '').startsWith(DBAAS_TEMPLATE_PREFIX)) throw new Error('Database service not found or access denied.')
        const db = await getAPI('listDbaasDatabases', { virtualmachineid: id })
        if (id !== this.$route.params.id) return
        this.resource = vm
        this.databases = ((db.listdbaasdatabasesresponse || {}).dbaasdatabase || []).filter(d => d.database)
        try {
          const engines = await getAPI('listDbaasEngines')
          this.engines = (engines.listdbaasenginesresponse || {}).dbaasengine || []
        } catch (e) { this.engines = [] }
      } catch (e) {
        this.error = e?.message || String(e)
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style scoped>
:deep(.ant-descriptions-item-label),
:deep(.ant-descriptions-item-content) {
  color: inherit !important;
  background: transparent !important;
}
</style>

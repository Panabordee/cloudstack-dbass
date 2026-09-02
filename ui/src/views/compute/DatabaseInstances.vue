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
  <a-row :gutter="12">
    <a-col :md="24" :lg="24">
      <a-card class="database-instances-card">
        <template #title>
          {{ $t('label.database') }}
          <a-button
            style="margin-left: 12px; margin-top: 4px"
            :loading="loading"
            size="small"
            shape="round"
            @click="fetchData">
            <template #icon><reload-outlined /></template>
          </a-button>
          <router-link
            v-if="canCreateDatabase"
            :to="{ path: '/action/createDatabase' }"
            style="float: right">
            <a-button type="primary" size="small">
              <template #icon><plus-outlined /></template>
              {{ $t('label.create.database.instance') }}
            </a-button>
          </router-link>
        </template>
        <a-table
          :data-source="instances"
          :loading="loading"
          class="database-instances-table"
          size="middle"
          :pagination="false"
          :columns="columns"
          rowKey="id">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'name'">
              <router-link :to="{ path: '/vm/' + record.id }">{{ record.displayname || record.name }}</router-link>
            </template>
            <template v-else-if="column.key === 'state'">
              <status :text="record.state" displayText />
            </template>
            <template v-else-if="column.key === 'engine'">
              {{ engineLabel(record.templatename) }}
            </template>
          </template>
        </a-table>
        <a-empty v-if="!loading && instances.length === 0" :description="$t('label.database.instances.empty')" />
      </a-card>
    </a-col>
  </a-row>
</template>

<script>
import { getAPI } from '@/api'
import Status from '@/components/widgets/Status.vue'

export default {
  name: 'DatabaseInstances',
  components: { Status },
  data () {
    return {
      loading: false,
      instances: [],
      // Rendered from the same template name the extension itself keys off
      // of -- if it doesn't recognize a template name, neither would the
      // backend, so falling back to the raw name here is the honest answer.
      engineLabels: {},
      columns: [
        { key: 'name', title: this.$t('label.name'), dataIndex: 'name' },
        { key: 'engine', title: this.$t('label.engine'), dataIndex: 'templatename' },
        { key: 'state', title: this.$t('label.state'), dataIndex: 'state' },
        { key: 'ipaddress', title: this.$t('label.ipaddress'), dataIndex: 'ipaddress' },
        { key: 'zonename', title: this.$t('label.zonename'), dataIndex: 'zonename' }
      ]
    }
  },
  computed: {
    canCreateDatabase () {
      return 'createDatabase' in this.$store.getters.apis
    }
  },
  created () {
    this.fetchData()
  },
  methods: {
    engineLabel (templatename) {
      return this.engineLabels[templatename] || templatename
    },
    fetchData () {
      this.loading = true
      // Same keyword contract CreateDatabaseInstance.vue's engine picker
      // uses: the dbaas- prefix on the template name is what actually
      // decides membership, keyword is just a server-side head start.
      getAPI('listTemplates', { templatefilter: 'executable', keyword: 'dbaas' }).then(tplResponse => {
        const templates = (tplResponse.listtemplatesresponse.template || [])
          .filter(t => t.name && t.name.startsWith('dbaas-'))
        this.engineLabels = templates.reduce((acc, t) => {
          acc[t.name] = t.displaytext || t.name
          return acc
        }, {})
        const templateIds = new Set(templates.map(t => t.id))
        if (templateIds.size === 0) {
          this.instances = []
          return
        }
        return getAPI('listVirtualMachines', { listall: true, details: 'tmpl,nics' }).then(vmResponse => {
          this.instances = (vmResponse.listvirtualmachinesresponse.virtualmachine || [])
            .filter(vm => templateIds.has(vm.templateid))
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    }
  }
}
</script>

<style scoped lang="less">
  .database-instances-card {
    width: 100%;
  }
</style>

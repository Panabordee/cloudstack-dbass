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
            <template v-else-if="column.key === 'actions'">
              <a-button
                v-if="canDestroy"
                type="text"
                danger
                size="small"
                :title="$t('label.action.destroy.instance')"
                @click="confirmDestroy(record)">
                <template #icon><delete-outlined /></template>
              </a-button>
            </template>
          </template>
        </a-table>
        <a-empty v-if="!loading && instances.length === 0" :description="$t('label.database.instances.empty')" />
      </a-card>
    </a-col>
  </a-row>
</template>

<script>
import { h, ref } from 'vue'
import { Checkbox, Modal } from 'ant-design-vue'
import { getAPI, postAPI } from '@/api'
import Status from '@/components/widgets/Status.vue'
import { DBAAS_TEMPLATE_PREFIX } from '@/utils/dbaas'

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
        { key: 'zonename', title: this.$t('label.zonename'), dataIndex: 'zonename' },
        { key: 'actions', title: '', dataIndex: 'actions', width: 60 }
      ]
    }
  },
  computed: {
    canCreateDatabase () {
      return 'createDatabase' in this.$store.getters.apis
    },
    // These instances are hidden from the generic Instances list, so this
    // page has to carry the destroy action itself -- otherwise the only way
    // to remove one is to open its detail page and find it there.
    canDestroy () {
      return 'destroyVirtualMachine' in this.$store.getters.apis
    },
    canExpunge () {
      return this.$store.getters.userInfo.roletype === 'Admin' ||
        this.$store.getters.features.allowuserexpungerecovervm
    }
  },
  created () {
    this.fetchData()
  },
  methods: {
    engineLabel (templatename) {
      return this.engineLabels[templatename] || templatename
    },
    confirmDestroy (record) {
      const expungeRef = ref(false)
      Modal.confirm({
        title: this.$t('label.action.destroy.instance'),
        okText: this.$t('label.yes'),
        cancelText: this.$t('label.no'),
        okButtonProps: { danger: true },
        content: () => h('div', [
          h('p', `${record.displayname || record.name} (${this.engineLabel(record.templatename)})`),
          h('p', this.$t('message.action.destroy.instance')),
          // Same option the Instances list offers, gated the same way: a
          // destroyed-but-not-expunged instance is recoverable, an expunged
          // one is not.
          this.canExpunge
            ? h(Checkbox, {
              onChange: e => { expungeRef.value = e.target.checked }
            }, { default: () => this.$t('label.expunge') })
            : null
        ]),
        onOk: () => this.destroyInstance(record, expungeRef.value)
      })
    },
    destroyInstance (record, expunge) {
      const params = { id: record.id }
      if (expunge) {
        params.expunge = true
      }
      return postAPI('destroyVirtualMachine', params).then(json => {
        const jobId = json.destroyvirtualmachineresponse?.jobid
        if (!jobId) {
          this.fetchData()
          return
        }
        this.$pollJob({
          jobId,
          title: this.$t('label.action.destroy.instance'),
          description: record.displayname || record.name,
          successMethod: () => this.fetchData(),
          errorMethod: () => this.fetchData(),
          loadingMessage: `${this.$t('label.action.destroy.instance')} ${this.$t('label.in.progress')}`,
          catchMessage: this.$t('error.fetching.async.job.result'),
          action: { isFetchData: false }
        })
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    fetchData () {
      this.loading = true
      // listDbaasEngines is the source of truth for membership; the dbaas-
      // prefix is only the fallback for management servers running an older
      // plugin build without that API.
      const hasEnginesApi = 'listDbaasEngines' in this.$store.getters.apis
      const templateParams = hasEnginesApi
        ? { templatefilter: 'executable' }
        : { templatefilter: 'executable', keyword: DBAAS_TEMPLATE_PREFIX }
      Promise.all([
        getAPI('listTemplates', templateParams),
        hasEnginesApi ? getAPI('listDbaasEngines') : Promise.resolve(null)
      ]).then(([tplResponse, engines]) => {
        const engineNames = engines
          ? new Set((engines.listdbaasenginesresponse?.dbaasengine || []).map(e => e.template))
          : null
        const templates = (tplResponse.listtemplatesresponse.template || [])
          .filter(t => t.name && (engineNames ? engineNames.has(t.name) : t.name.startsWith(DBAAS_TEMPLATE_PREFIX)))
        this.engineLabels = templates.reduce((acc, t) => {
          acc[t.name] = t.displaytext || t.name
          return acc
        }, {})
        const templateIds = new Set(templates.map(t => t.id))
        if (templateIds.size === 0) {
          this.instances = []
          return
        }
        // pagesize: -1 -- without it the response is capped at the default
        // page size and every DBaaS VM beyond it silently vanishes from this
        // list even though the instance exists and is reachable.
        return getAPI('listVirtualMachines', { listall: true, details: 'tmpl,nics', pagesize: -1 }).then(vmResponse => {
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

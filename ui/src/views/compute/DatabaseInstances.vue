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
      <a-card class="database-instances-card" :bordered="false">
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
          :pagination="{ pageSize: 10, showSizeChanger: false }"
          :scroll="{ x: 800 }"
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
              <a-dropdown v-if="rowActions(record).length > 0">
                <a-button type="text" size="small" :title="$t('label.actions')">
                  <template #icon><more-outlined /></template>
                </a-button>
                <template #overlay>
                  <a-menu @click="({ key }) => runRowAction(key, record)">
                    <a-menu-item v-for="action in rowActions(record)" :key="action.key">
                      {{ $t(action.label) }}
                    </a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
            </template>
          </template>
        </a-table>
        <a-empty v-if="!loading && instances.length === 0" :description="$t('label.database.instances.empty')" />
      </a-card>
      <!-- Row actions that open a dialog reuse the exact components the
           /vm/<id> dataView actions render, receiving the same resource
           shape. closeModal refreshes the list so state changes made inside
           them are reflected immediately. -->
      <a-modal
        :visible="activeRowAction !== ''"
        :footer="null"
        :title="$t(activeRowAction === 'createDatabase'
          ? 'label.create.database'
          : 'label.show.database.password')"
        :width="activeRowAction === 'createDatabase' ? '500px' : '450px'"
        :closable="true"
        @cancel="closeModal">
        <create-database
          v-if="activeRowAction === 'createDatabase'"
          :resource="activeRecord"
          @close-action="closeModal"
          @refresh-data="fetchData" />
        <show-database-password
          v-else-if="activeRowAction === 'getDatabasePassword'"
          :resource="activeRecord"
          @close-action="closeModal"
          @refresh-data="fetchData" />
      </a-modal>
    </a-col>
  </a-row>
</template>

<script>
import { h, ref } from 'vue'
import { Checkbox, Modal } from 'ant-design-vue'
import { getAPI, postAPI } from '@/api'
import Status from '@/components/widgets/Status.vue'
import CreateDatabase from '@/views/compute/CreateDatabase.vue'
import ShowDatabasePassword from '@/views/compute/ShowDatabasePassword.vue'
import { DBAAS_TEMPLATE_PREFIX } from '@/utils/dbaas'

export default {
  name: 'DatabaseInstances',
  components: { Status, CreateDatabase, ShowDatabasePassword },
  data () {
    return {
      loading: false,
      instances: [],
      // Rendered from the same template name the extension itself keys off
      // of -- if it doesn't recognize a template name, neither would the
      // backend, so falling back to the raw name here is the honest answer.
      engineLabels: {},
      engineNames: new Set(),
      activeRowAction: '',
      activeRecord: null,
      columns: [
        { key: 'name', title: this.$t('label.name'), dataIndex: 'name' },
        { key: 'engine', title: this.$t('label.engine'), dataIndex: 'templatename' },
        { key: 'state', title: this.$t('label.state'), dataIndex: 'state' },
        { key: 'ipaddress', title: this.$t('label.ipaddress'), dataIndex: 'ipaddress' },
        { key: 'zonename', title: this.$t('label.zonename'), dataIndex: 'zonename' },
        { key: 'actions', title: this.$t('label.actions'), dataIndex: 'actions', width: 100 }
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
    rowActions (record) {
      // Same conditions and permission gates the /vm/<id> dataView actions
      // use (compute.js) -- the Database page just carries them here, since
      // DBaaS instances are hidden from the Instances list.
      const isRunning = record.state === 'Running'
      const isStopped = record.state === 'Stopped'
      const apis = this.$store.getters.apis
      const actions = []
      // Running or Stopped: config-drive provisioning only reads its request
      // at boot, so a running instance is stopped and restarted as part of
      // the call -- CreateDatabase.vue warns about that itself.
      if ((isRunning || isStopped) && 'createDatabase' in apis && this.isEngineMember(record)) {
        actions.push({ key: 'createDatabase', label: 'label.create.database' })
      }
      // resetDatabasePassword is not offered here: it has no working
      // transport until the in-VM agent exists (PLAN.md Phase D).
      if ((isRunning || isStopped) && 'getDatabasePassword' in apis && this.isEngineMember(record)) {
        actions.push({ key: 'getDatabasePassword', label: 'label.show.database.password' })
      }
      if (isStopped && 'startVirtualMachine' in apis) {
        actions.push({ key: 'startVirtualMachine', label: 'label.action.start.instance' })
      }
      if (isRunning && 'stopVirtualMachine' in apis) {
        actions.push({ key: 'stopVirtualMachine', label: 'label.action.stop.instance' })
      }
      if (isRunning && 'rebootVirtualMachine' in apis) {
        actions.push({ key: 'rebootVirtualMachine', label: 'label.action.reboot.instance' })
      }
      return actions
    },
    isEngineMember (record) {
      // Engine membership follows listDbaasEngines when the management server
      // provides it; the dbaas- prefix is the fallback for older builds.
      if (this.engineNames.size > 0) {
        return this.engineNames.has(record.templatename)
      }
      return (record.templatename || '').startsWith(DBAAS_TEMPLATE_PREFIX)
    },
    runRowAction (key, record) {
      if (key === 'createDatabase' || key === 'getDatabasePassword') {
        this.activeRecord = record
        this.activeRowAction = key
        return
      }
      // start / stop / reboot: async jobs, same flow the destroy action uses.
      postAPI(key, { id: record.id }).then(json => {
        const jobId = json[key.toLowerCase() + 'response']?.jobid
        if (!jobId) {
          this.fetchData()
          return
        }
        this.$pollJob({
          jobId,
          title: this.$t(key === 'startVirtualMachine'
            ? 'label.action.start.instance'
            : key === 'stopVirtualMachine' ? 'label.action.stop.instance' : 'label.action.reboot.instance'),
          description: record.displayname || record.name,
          successMethod: () => this.fetchData(),
          errorMethod: () => this.fetchData(),
          loadingMessage: `${this.$t('label.in.progress')} ${record.displayname || record.name}`,
          catchMessage: this.$t('error.fetching.async.job.result'),
          action: { isFetchData: false }
        })
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    closeModal () {
      this.activeRowAction = ''
      this.activeRecord = null
      this.fetchData()
    },
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
            : null,
          // Expunging also drops the instance's data disks, which is the only
          // way they ever get cleaned up -- say so before it happens.
          this.canExpunge
            ? h('p', { style: { marginTop: '8px', color: 'rgba(0, 0, 0, 0.45)' } },
              this.$t('message.dbaas.expunge.datadisks'))
            : null
        ]),
        onOk: () => this.destroyInstance(record, expungeRef.value)
      })
    },
    // Data disks survive their instance: CloudStack detaches them on expunge
    // and leaves them Ready but unattached, where they keep holding their full
    // allocation against the primary storage pool. Nothing in the UI shows
    // them (the Database page lists instances, not volumes), so they pile up
    // silently until the allocator refuses new deploys with "No destination
    // found for a deployment". Collect the ids BEFORE the destroy runs: once
    // the instance is expunged the volume no longer names it.
    fetchDataDiskIds (vmId) {
      return getAPI('listVolumes', { virtualmachineid: vmId, type: 'DATADISK', listall: true })
        .then(json => (json.listvolumesresponse.volume || []).map(v => v.id))
        .catch(e => {
          console.warn('could not list data disks for', vmId, e)
          return []
        })
    },
    // Only ever called for an expunged instance. A destroyed-but-recoverable
    // one keeps its disks: recovering it and finding the data gone would be
    // worse than the leak this cleans up.
    deleteDataDisks (volumeIds) {
      volumeIds.forEach(id => {
        postAPI('deleteVolume', { id })
          .catch(e => console.warn('deleteVolume failed for', id, e))
      })
    },
    destroyInstance (record, expunge) {
      const params = { id: record.id }
      if (expunge) {
        params.expunge = true
      }
      // Resolved before the destroy call so the lookup still sees the
      // attachment; empty for a non-expunging destroy, which keeps its disks.
      const dataDisks = expunge ? this.fetchDataDiskIds(record.id) : Promise.resolve([])
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
          // The stored credentials belong to the destroyed instance: wipe
          // them server-side once the destroy job succeeds, so the rows the
          // schema docs call out for manual cleanup stop accumulating. The
          // call targets the instance UUID directly, so it still works when
          // the destroy included an expunge.
          successMethod: () => {
            postAPI('deleteDbaasCredentials', { virtualmachineid: record.id })
              .catch(e => console.warn('deleteDbaasCredentials failed for', record.id, e))
            dataDisks.then(ids => this.deleteDataDisks(ids))
            this.fetchData()
          },
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
        // Keep the state a Set even on the fallback path (no listDbaasEngines
        // API): isEngineMember() reads .size off this state, and a null here
        // would throw a TypeError that breaks every row action in the table.
        this.engineNames = engineNames || new Set()
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
    max-width: 100%;
    overflow: hidden;
  }

  .database-instances-table :deep(.ant-table) {
    overflow-x: auto;
  }
</style>

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
    <a-affix
      :offsetTop="$store.getters.maintenanceInitiated || $store.getters.shutdownTriggered ? 103 : 78">
      <a-card class="breadcrumb-card" style="z-index: 10">
        <a-row>
          <a-col :xs="24" :lg="12" class="toolbar-left">
            <breadcrumb>
              <template #end>
                <a-button
                  :loading="loading"
                  class="toolbar-control"
                  shape="round"
                  size="small"
                  @click="fetchData">
                  <template #icon><reload-outlined /></template>
                  {{ $t('label.refresh') }}
                </a-button>
                <a-tooltip placement="right">
                  <template #title>{{ $t('label.filterby') }}</template>
                  <a-select
                    v-model:value="stateFilter"
                    class="state-filter toolbar-control"
                    size="small"
                    @change="onStateFilterChange">
                    <template #suffixIcon><filter-outlined /></template>
                    <a-select-option value="all">{{ $t('label.all') }}</a-select-option>
                    <a-select-option value="Running">{{ $t('label.running') }}</a-select-option>
                    <a-select-option value="Stopped">{{ $t('label.stopped') }}</a-select-option>
                    <a-select-option value="Destroyed">{{ $t('label.destroyed') }}</a-select-option>
                  </a-select>
                </a-tooltip>
              </template>
            </breadcrumb>
          </a-col>
          <a-col :xs="24" :lg="12" class="toolbar-right">
            <action-button
              :actions="createActions"
              :loading="loading"
              @exec-action="openCreateDatabase" />
            <a-input-search
              v-model:value="searchQuery"
              allowClear
              class="database-search"
              :placeholder="$t('label.search')"
              @search="onSearch" />
          </a-col>
        </a-row>
      </a-card>
    </a-affix>

    <div class="row-element">
      <a-table
        :data-source="paginatedInstances"
        :loading="loading"
        class="database-instances-table"
        size="middle"
        :pagination="false"
        :scroll="{ x: 900 }"
        :columns="columns"
        rowKey="id">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <database-outlined class="database-resource-icon" />
            <router-link :to="{ path: '/vm/' + record.id }">{{ record.displayname || record.name }}</router-link>
          </template>
          <template v-else-if="column.key === 'state'">
            <status :text="record.state" displayText :styles="{ 'min-width': '80px' }" />
          </template>
          <template v-else-if="column.key === 'engine'">
            {{ engineLabel(record.templatename) }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <!-- The console is the one action a tenant uses all day; keep it
                 visible while the remaining actions stay in the row menu. -->
            <a-button
              v-if="canConsole(record)"
              type="primary"
              size="small"
              class="console-button"
              :title="$t('label.dbaas.console')"
              @click="runRowAction('console', record)">
              <template #icon><console-sql-outlined /></template>
              {{ $t('label.dbaas.console') }}
            </a-button>
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

      <a-pagination
        v-if="filteredInstances.length > 0"
        class="database-pagination"
        size="small"
        :current="page"
        :pageSize="pageSize"
        :total="filteredInstances.length"
        :showTotal="paginationTotal"
        :pageSizeOptions="pageSizeOptions"
        showSizeChanger
        showQuickJumper
        @change="changePage"
        @showSizeChange="changePageSize">
        <template #buildOptionText="props">
          <span>{{ props.value }} / {{ $t('label.page') }}</span>
        </template>
      </a-pagination>
    </div>

      <!-- Row actions that open a dialog reuse the exact components the
           /vm/<id> dataView actions render, receiving the same resource
           shape. closeModal refreshes the list so state changes made inside
           them are reflected immediately. -->
      <a-modal
        :visible="activeRowAction !== ''"
        :footer="null"
        :title="null"
        :width="modalWidthFor(activeRowAction)"
        :closable="true"
        @cancel="closeModal">
        <template #title>
          <span>{{ $t(modalTitleFor(activeRowAction)) }}</span>
          <!-- The console renders tenant tables of unknown width; a fixed
               modal is the wrong shape for that often enough that it needs
               to be the user's call, not ours. -->
          <a-button
            v-if="activeRowAction === 'console'"
            type="link"
            size="small"
            style="float: right; margin-right: 32px"
            @click="consoleMaximized = !consoleMaximized">
            {{ consoleMaximized
              ? $t('label.dbaas.console.restore')
              : $t('label.dbaas.console.maximize') }}
          </a-button>
        </template>
        <create-database
          v-if="activeRowAction === 'createDatabase'"
          :resource="activeRecord"
          @close-action="closeModal"
          @refresh-data="fetchData" />
        <dbaas-console
          v-else-if="activeRowAction === 'console'"
          :resource="activeRecord" />
        <show-database-password
          v-else-if="activeRowAction === 'getDatabasePassword'"
          :resource="activeRecord"
          @close-action="closeModal"
          @refresh-data="fetchData" />
        <reset-database-password
          v-else-if="activeRowAction === 'resetDatabasePassword'"
          :resource="activeRecord"
          @close-action="closeModal"
          @refresh-data="fetchData" />
      </a-modal>
  </div>
</template>

<script>
import { h, ref } from 'vue'
import { Checkbox, Modal } from 'ant-design-vue'
import { getAPI, postAPI } from '@/api'
import ActionButton from '@/components/view/ActionButton.vue'
import Breadcrumb from '@/components/widgets/Breadcrumb.vue'
import Status from '@/components/widgets/Status.vue'
import CreateDatabase from '@/views/compute/CreateDatabase.vue'
import DbaasConsole from '@/views/compute/DbaasConsole.vue'
import ResetDatabasePassword from '@/views/compute/ResetDatabasePassword.vue'
import ShowDatabasePassword from '@/views/compute/ShowDatabasePassword.vue'
import { DBAAS_TEMPLATE_PREFIX } from '@/utils/dbaas'
import { isZoneCreated } from '@/utils/zone'

export default {
  name: 'DatabaseInstances',
  components: { ActionButton, Breadcrumb, Status, CreateDatabase, DbaasConsole, ShowDatabasePassword, ResetDatabasePassword },
  data () {
    return {
      loading: false,
      instances: [],
      searchQuery: '',
      appliedSearch: '',
      stateFilter: 'all',
      page: 1,
      pageSize: this.$store.getters.defaultListViewPageSize,
      // Rendered from the same template name the extension itself keys off
      // of -- if it doesn't recognize a template name, neither would the
      // backend, so falling back to the raw name here is the honest answer.
      engineLabels: {},
      engineNames: new Set(),
      activeRowAction: '',
      consoleMaximized: false,
      activeRecord: null,
      createActions: [{
        api: 'createDatabase',
        icon: 'plus-outlined',
        label: 'label.create.database.instance',
        listView: true,
        show: isZoneCreated
      }],
      columns: [
        { key: 'name', title: this.$t('label.name'), dataIndex: 'name' },
        { key: 'state', title: this.$t('label.state'), dataIndex: 'state' },
        { key: 'ipaddress', title: this.$t('label.ipaddress'), dataIndex: 'ipaddress' },
        { key: 'engine', title: this.$t('label.engine'), dataIndex: 'templatename' },
        { key: 'serviceofferingname', title: this.$t('label.serviceoffering'), dataIndex: 'serviceofferingname' },
        { key: 'zonename', title: this.$t('label.zonename'), dataIndex: 'zonename' },
        { key: 'actions', title: this.$t('label.actions'), dataIndex: 'actions', width: 160 }
      ]
    }
  },
  computed: {
    filteredInstances () {
      const query = this.appliedSearch.trim().toLowerCase()
      return this.instances.filter(record => {
        if (this.stateFilter !== 'all' && record.state !== this.stateFilter) {
          return false
        }
        if (!query) {
          return true
        }
        return [
          record.displayname,
          record.name,
          record.state,
          record.ipaddress,
          record.templatename,
          this.engineLabel(record.templatename),
          record.serviceofferingname,
          record.zonename
        ].some(value => String(value || '').toLowerCase().includes(query))
      })
    },
    paginatedInstances () {
      const start = (this.page - 1) * this.pageSize
      return this.filteredInstances.slice(start, start + this.pageSize)
    },
    pageSizeOptions () {
      return [...new Set([20, 50, 100, 200, this.$store.getters.defaultListViewPageSize])]
        .sort((a, b) => a - b)
        .map(String)
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
    openCreateDatabase () {
      this.$router.push({ name: 'createDatabase' })
    },
    onSearch (value) {
      this.appliedSearch = value || ''
      this.page = 1
    },
    onStateFilterChange () {
      this.page = 1
    },
    changePage (page) {
      this.page = page
    },
    changePageSize (page, pageSize) {
      this.page = 1
      this.pageSize = pageSize
    },
    paginationTotal (total) {
      const start = total === 0 ? 0 : 1 + ((this.page - 1) * this.pageSize)
      const end = Math.min(this.page * this.pageSize, total)
      return `${this.$t('label.showing')} ${start}-${end} ${this.$t('label.of')} ${total} ${this.$t('label.items')}`
    },
    // The dialogs this modal hosts set their own content width
    // (.form-layout is 560px above the 600px breakpoint, matching the core
    // CloudStack dialogs), so a modal narrower than that pushes the content
    // straight through the modal's background -- observed 2026-09-10 on the
    // Database page: Show Password rendered its table, its alert and its
    // buttons 134px past the white panel, over the page behind it. The
    // /vm/<id> route never showed this because AutogenView opens
    // component-backed actions with width="auto", which sizes to content.
    // Each width below is the content's own width plus the modal's padding,
    // and the console gets far more because it renders data tables rather
    // than a form.
    modalTitleFor (action) {
      switch (action) {
        case 'createDatabase': return 'label.create.database'
        case 'console': return 'label.dbaas.console'
        case 'resetDatabasePassword': return 'label.reset.database.password'
        default: return 'label.show.database.password'
      }
    },
    modalWidthFor (action) {
      if (action === 'console') {
        return this.consoleMaximized ? '96vw' : '1000px'
      }
      return '620px'
    },
    canConsole (record) {
      // Same gate rowActions applies to the console entry: running only
      // (the agent is not polling while stopped) and the engine console
      // command must exist for the caller's role.
      return record.state === 'Running' &&
        'listDbaasTables' in this.$store.getters.apis &&
        this.isEngineMember(record)
    },
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
      if ((isRunning || isStopped) && 'getDatabasePassword' in apis && this.isEngineMember(record)) {
        actions.push({ key: 'getDatabasePassword', label: 'label.show.database.password' })
      }
      // Running only: the reset goes through the in-VM agent, which is not
      // polling while the instance is stopped. Offered again as of
      // 2026-09-09, when that transport was proven on all four engines.
      if (isRunning && 'resetDatabasePassword' in apis && this.isEngineMember(record)) {
        actions.push({ key: 'resetDatabasePassword', label: 'label.reset.database.password' })
      }
      if (isRunning && 'listDbaasTables' in apis && this.isEngineMember(record)) {
        actions.push({ key: 'console', label: 'label.dbaas.console' })
      }
      // The instance's own login password, not the database user's. DBaaS
      // instances are hidden from the Instances list, so without this a
      // tenant has nowhere to reach CloudStack's own action -- which is also
      // why it was reported missing. Stopped only: that is CloudStack's own
      // constraint on resetPasswordForVirtualMachine, not ours.
      if (isStopped && 'resetPasswordForVirtualMachine' in apis && record.passwordenabled) {
        actions.push({ key: 'resetPasswordForVirtualMachine', label: 'label.action.reset.password' })
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
      if (key === 'console') {
        this.activeRecord = record
        this.activeRowAction = key
        return
      }
      if (key === 'createDatabase' || key === 'getDatabasePassword' ||
          key === 'resetDatabasePassword') {
        this.activeRecord = record
        this.activeRowAction = key
        return
      }
      // The instance's own login password. Async like start/stop, but the
      // result carries the new password exactly once -- polling and then
      // refetching the list (the generic path below) would throw it away,
      // which is the whole point of the action.
      if (key === 'resetPasswordForVirtualMachine') {
        postAPI(key, { id: record.id }).then(json => {
          const jobId = json.resetpasswordforvirtualmachineresponse?.jobid
          if (!jobId) {
            this.fetchData()
            return
          }
          this.$pollJob({
            jobId,
            title: this.$t('label.action.reset.password'),
            description: record.displayname || record.name,
            successMethod: result => {
              const pw = result?.jobresult?.virtualmachine?.password
              this.$notification.success({
                message: this.$t('label.action.reset.password'),
                description: pw
                  ? `${record.displayname || record.name}: ${pw}`
                  : (record.displayname || record.name),
                duration: 0
              })
              this.fetchData()
            },
            errorMethod: () => this.fetchData(),
            loadingMessage: `${this.$t('label.in.progress')} ${record.displayname || record.name}`,
            catchMessage: this.$t('error.fetching.async.job.result'),
            action: { isFetchData: false }
          })
        }).catch(error => {
          this.$notifyError(error)
        })
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
      this.consoleMaximized = false
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
    // Collect the ids BEFORE the destroy runs: once the instance is expunged
    // the volume no longer names it. Two lookups, merged: the classic
    // virtualmachineid one (works when the disk was attached at least once)
    // and the dbaas.instance tag written at createDatabase (works even for an
    // instance that was destroyed before its first start, where the disk was
    // never linked to the instance and the first lookup returns nothing).
    fetchDataDiskIds (vmUuid, vmId) {
      const byVm = getAPI('listVolumes', { virtualmachineid: vmId, type: 'DATADISK', listall: true })
        .then(json => (json.listvolumesresponse.volume || []).map(v => v.id))
        .catch(e => {
          console.warn('could not list data disks for', vmId, e)
          return []
        })
      const byTag = getAPI('listVolumes', { 'tags[0].key': 'dbaas.instance', 'tags[0].value': vmUuid, type: 'DATADISK', listall: true })
        .then(json => (json.listvolumesresponse.volume || []).map(v => v.id))
        .catch(e => {
          console.warn('could not list tagged data disks for', vmUuid, e)
          return []
        })
      return Promise.all([byVm, byTag]).then(([byVmIds, byTagIds]) => [...new Set([...byVmIds, ...byTagIds])])
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
      const dataDisks = expunge ? this.fetchDataDiskIds(record.uuid, record.id) : Promise.resolve([])
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
        return getAPI('listVirtualMachines', { listall: true, details: 'tmpl,nics,servoff', pagesize: -1 }).then(vmResponse => {
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
  .breadcrumb-card {
    margin-left: -24px;
    margin-right: -24px;
    margin-top: -16px;
    margin-bottom: 12px;
  }

  .toolbar-left {
    padding-left: 12px;
    margin-top: 10px;
  }

  .toolbar-right {
    display: flex;
    justify-content: flex-end;
    align-items: center;
    gap: 10px;
    padding-right: 10px;
    margin-top: 6px;
  }

  .toolbar-control {
    margin-left: 10px;
    margin-bottom: 5px;
  }

  .state-filter {
    min-width: 110px;
  }

  .database-search {
    width: 240px;
  }

  .row-element {
    margin-bottom: 10px;
  }

  .database-instances-table :deep(.ant-table) {
    overflow-x: auto;
  }

  .database-resource-icon {
    margin-right: 10px;
    font-size: 18px;
  }

  .console-button {
    margin-right: 6px;
  }

  .database-pagination {
    margin-top: 10px;
  }

  @media (max-width: 991px) {
    .toolbar-right {
      justify-content: flex-start;
      flex-wrap: wrap;
      padding-left: 12px;
      padding-right: 0;
      margin-top: 8px;
      margin-bottom: 6px;
    }

    .database-search {
      flex: 1;
      min-width: 180px;
    }
  }
</style>

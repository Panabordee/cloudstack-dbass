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

<!-- Database Query: a page of its own in the left navigation, not an action
     buried in a row menu. Opening it asks which database to work on and for
     the credential to open it with -- the same shape Google Cloud's SQL
     studio uses -- and only then shows the tables and the editor. -->
<template>
  <div>
    <a-affix :offsetTop="$store.getters.maintenanceInitiated || $store.getters.shutdownTriggered ? 103 : 78">
      <a-card class="breadcrumb-card" style="z-index: 10">
        <a-row>
          <a-col :xs="24" :lg="12" class="toolbar-left">
            <breadcrumb>
              <template #end>
                <a-button
                  v-if="connected"
                  class="toolbar-control"
                  shape="round"
                  size="small"
                  @click="disconnect">
                  <template #icon><disconnect-outlined /></template>
                  {{ $t('label.dbaas.query.disconnect') }}
                </a-button>
              </template>
            </breadcrumb>
          </a-col>
        </a-row>
      </a-card>
    </a-affix>

    <div class="row-element">
      <!-- Connection form -->
      <a-card v-if="!connected" class="connect-card">
        <a-alert
          type="info"
          show-icon
          class="connect-note"
          :message="$t('message.dbaas.query.intro')"
          :description="$t('message.dbaas.query.access')" />
        <a-spin :spinning="loading">
          <a-form layout="vertical" :model="form">
            <a-form-item :label="$t('label.dbaas.query.instance')" required>
              <a-select
                v-model:value="form.instanceId"
                show-search
                option-filter-prop="label"
                :placeholder="$t('label.dbaas.query.instance')"
                @change="onInstanceChange">
                <a-select-option
                  v-for="vm in instances"
                  :key="vm.id"
                  :value="vm.id"
                  :label="vm.displayname || vm.name">
                  {{ vm.displayname || vm.name }} ({{ vm.ipaddress }})
                </a-select-option>
              </a-select>
            </a-form-item>

            <a-form-item :label="$t('label.dbaas.query.type')" required>
              <a-select v-model:value="form.engineType" :placeholder="$t('label.dbaas.query.type')">
                <a-select-option v-for="t in engineTypes" :key="t" :value="t">{{ t }}</a-select-option>
              </a-select>
              <span class="hint">{{ $t('message.dbaas.query.type.hint') }}</span>
            </a-form-item>

            <a-form-item :label="$t('label.dbaas.console.database')" required>
              <a-select
                v-model:value="form.database"
                :loading="databasesLoading"
                :placeholder="$t('label.dbaas.console.database')">
                <a-select-option v-for="d in databases" :key="d.database" :value="d.database">
                  {{ d.database }}<span v-if="d.status !== 'confirmed'"> ({{ d.status }})</span>
                </a-select-option>
              </a-select>
            </a-form-item>

            <!-- No password field, deliberately. Asking for one here could
                 only be checked in the browser against the stored credential,
                 which means fetching that credential into the browser to
                 compare it -- handing over the very secret the prompt is
                 pretending to guard, while the check itself is a JS variable
                 anyone can set from devtools. It would also control nothing:
                 the agent connects with the credential held inside the
                 instance regardless of what was typed.
                 The real gate is server-side and already enforced on every
                 command: checkCallerOwnsVm refuses anyone who does not own
                 this instance, and reads run as the read-only role with the
                 engine itself enforcing it. -->

            <a-alert v-if="connectError" type="error" show-icon class="connect-note" :message="connectError" />

            <a-button type="primary" :disabled="!canConnect" @click="connect">
              {{ $t('label.dbaas.query.connect') }}
            </a-button>
          </a-form>
        </a-spin>
      </a-card>

      <!-- Connected: the console itself, full page -->
      <a-card v-else class="query-card">
        <div class="connected-bar">
          <a-tag color="green">{{ connectedLabel }}</a-tag>
        </div>
        <dbaas-console :resource="connectedResource" :initial-database="form.database" />
      </a-card>
    </div>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import { defineAsyncComponent, shallowRef } from 'vue'
import Breadcrumb from '@/components/widgets/Breadcrumb'
import { DBAAS_TEMPLATE_PREFIX } from '@/utils/dbaas'

export default {
  name: 'DbaasQuery',
  components: {
    Breadcrumb,
    DbaasConsole: shallowRef(defineAsyncComponent(() => import('@/views/compute/DbaasConsole.vue')))
  },
  data () {
    return {
      loading: false,
      databasesLoading: false,
      connected: false,
      connectError: '',
      instances: [],
      databases: [],
      engines: [],
      form: {
        instanceId: undefined,
        engineType: undefined,
        database: undefined
      }
    }
  },
  computed: {
    selectedInstance () {
      return this.instances.find(vm => vm.id === this.form.instanceId) || {}
    },
    // Offered rather than inferred, because the point of asking is that the
    // person connecting confirms what they are connecting to. Prefilled from
    // the instance's own template so the common case is one glance.
    engineTypes () {
      return [...new Set(this.engines.map(e => this.engineTypeOf(e.template)).filter(Boolean))]
    },
    canConnect () {
      return !!this.form.instanceId && !!this.form.database && !!this.form.engineType
    },
    connectedResource () {
      return this.selectedInstance
    },
    connectedLabel () {
      const vm = this.selectedInstance
      return `${this.form.engineType} · ${this.form.database} · ${vm.displayname || vm.name || ''}`
    }
  },
  created () {
    this.fetchInstances()
    this.fetchEngines()
  },
  methods: {
    engineTypeOf (template) {
      const e = this.engines.find(x => x.template === template)
      if (!e) {
        return ''
      }
      // The engine type is what the console's own allowlist is keyed by;
      // derive it the same way the server does rather than parsing labels.
      return (e.template || '').replace(/^dbaas-/, '').replace(/-v\d+$/, '')
    },
    fetchEngines () {
      return getAPI('listDbaasEngines').then(json => {
        this.engines = (json.listdbaasenginesresponse || {}).dbaasengine || []
      }).catch(() => {
        this.engines = []
      })
    },
    fetchInstances () {
      this.loading = true
      return getAPI('listVirtualMachines', { listall: true, state: 'Running' }).then(json => {
        const vms = (json.listvirtualmachinesresponse || {}).virtualmachine || []
        // Only DBaaS instances: everything else has no database to open.
        this.instances = vms.filter(vm => (vm.templatename || '').startsWith(DBAAS_TEMPLATE_PREFIX))
      }).catch(error => {
        this.connectError = error?.message || String(error)
      }).finally(() => {
        this.loading = false
      })
    },
    onInstanceChange () {
      this.form.database = undefined
      this.databases = []
      const vm = this.selectedInstance
      this.form.engineType = this.engineTypeOf(vm.templatename) || this.form.engineType
      if (!vm.id) {
        return
      }
      this.databasesLoading = true
      getAPI('listDbaasDatabases', { virtualmachineid: vm.id }).then(json => {
        const list = (json.listdbaasdatabasesresponse || {}).dbaasdatabase || []
        this.databases = list.filter(d => !!d.database)
        if (this.databases.length === 1) {
          this.form.database = this.databases[0].database
        }
      }).catch(() => {
        this.databases = []
      }).finally(() => {
        this.databasesLoading = false
      })
    },
    // Opening the editor is not itself a privileged step -- every command it
    // then issues is authorised server-side on its own. So this only records
    // what was chosen; there is nothing here worth checking in the browser.
    connect () {
      if (!this.canConnect) {
        return
      }
      this.connectError = ''
      this.connected = true
    },
    disconnect () {
      this.connected = false
      this.connectError = ''
    }
  }
}
</script>

<style scoped lang="less">
  .connect-card,
  .query-card {
    max-width: 100%;
  }

  .connect-card {
    max-width: 640px;
  }

  .connect-note {
    margin-bottom: 16px;
  }

  .connected-bar {
    margin-bottom: 12px;
  }

  .hint {
    display: block;
    margin-top: 4px;
    font-size: 12px;
    color: rgba(0, 0, 0, 0.45);
    line-height: 1.4;
  }
</style>

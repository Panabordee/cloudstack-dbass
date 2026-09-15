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
    <a-row :gutter="12">
      <a-col :md="24" :lg="step === 'form' ? 17 : 24">
        <a-card :bordered="true" :title="$t('label.create.database.instance')">
          <!-- step 1: the form -->
          <a-spin :spinning="loading" v-if="step === 'form'">
            <p v-html="$t('message.desc.create.database.instance')"></p>
            <a-alert
              type="info"
              show-icon
              banner
              :message="$t('message.dbaas.username.default')"
              class="form-banner" />
            <a-form
              v-ctrl-enter="handleSubmit"
              :ref="formRef"
              :model="form"
              :rules="rules"
              @finish="handleSubmit"
              layout="vertical">
              <a-steps direction="vertical" size="small">
                <a-step
                  :title="$t('label.select.deployment.infrastructure')"
                  status="process">
                  <template #description>
                    <div class="step-content">
                      <span>{{ $t('message.select.a.zone') }}</span><br/>
                      <a-form-item name="zoneid" ref="zoneid" :label="$t('label.zoneid')">
                        <zone-block-radio-group-select
                          :items="zones"
                          :selectedValue="form.zoneid"
                          :loading="optionsLoading"
                          @change="onSelectZone" />
                      </a-form-item>
                    </div>
                  </template>
                </a-step>

                <a-step
                  :title="$t('label.image')"
                  :status="form.zoneid ? 'process' : 'wait'">
                  <template #description>
                    <div v-if="form.zoneid" class="step-content">
                      <a-input-search
                        v-model:value="imageSearch"
                        class="selection-search"
                        :placeholder="$t('label.search')"
                        @search="value => updateSelectionOptions('templates', { page: 1, pageSize: 10, keyword: value })" />
                      <a-spin :spinning="optionsLoading">
                        <template-iso-radio-group
                          input-decorator="templateid"
                          :osList="pagedTemplates"
                          :itemCount="filteredTemplates.length"
                          :selected="form.engine || ''"
                          :preFillContent="{}"
                          @emit-update-template-iso="(name, id) => onSelectEngine(id)"
                          @handle-search-filter="options => updateSelectionOptions('templates', options)" />
                      </a-spin>
                      <a-form-item name="engine" ref="engine" class="form-item-hidden">
                        <a-input v-model:value="form.engine" />
                      </a-form-item>
                    </div>
                  </template>
                </a-step>

                <a-step
                  :title="$t('label.serviceofferingid')"
                  :status="form.engine && form.zoneid ? 'process' : 'wait'">
                  <template #description>
                    <div v-if="form.zoneid" class="step-content">
                      <a-form-item name="serviceofferingid" ref="serviceofferingid">
                        <compute-offering-selection
                          v-if="form.engine"
                          :compute-items="pagedOfferings"
                          :selected-template="selectedTemplate"
                          :row-count="filteredOfferings.length"
                          :zoneId="form.zoneid || ''"
                          :value="form.serviceofferingid || ''"
                          :loading="optionsLoading"
                          :minimum-memory="selectedEngineMinMemory"
                          @select-compute-item="onSelectOffering"
                          @handle-search-filter="options => updateSelectionOptions('offerings', options)" />
                        <a-alert
                          v-else
                          type="info"
                          show-icon
                          :message="$t('message.dbaas.select.engine.first')" />
                        <p v-if="form.engine && availableOfferings.length === 0" class="offering-warning">
                          {{ $t('message.dbaas.no.offering.fits', { mb: selectedEngineMinMemory }) }}
                        </p>
                      </a-form-item>
                      <a-form-item
                        name="rootdisksize"
                        ref="rootdisksize"
                        :label="$t('label.rootdisksize')"
                        v-if="selectedOfferingIsCustomized">
                        <a-input-number
                          v-model:value="form.rootdisksize"
                          :min="1"
                          style="width: 100%"
                          :placeholder="$t('label.rootdisksize')" />
                      </a-form-item>
                    </div>
                  </template>
                </a-step>

                <a-step
                  :title="$t('label.data.disk')"
                  :status="form.engine && form.zoneid ? 'process' : 'wait'">
                  <template #description>
                    <div v-if="form.zoneid" class="step-content">
                      <a-form-item name="diskofferingid" ref="diskofferingid">
                        <disk-offering-selection
                          :items="pagedDiskOfferings"
                          :row-count="filteredDiskOfferings.length"
                          :zoneId="form.zoneid || ''"
                          :value="form.diskofferingid || '0'"
                          :loading="optionsLoading"
                          :preFillContent="{}"
                          @select-disk-offering-item="onSelectDiskOffering"
                          @handle-search-filter="options => updateSelectionOptions('diskOfferings', options)" />
                      </a-form-item>
                    </div>
                  </template>
                </a-step>

                <a-step
                  v-if="needsNetwork"
                  :title="$t('label.networks')"
                  :status="form.zoneid ? 'process' : 'wait'">
                  <template #description>
                    <div class="step-content">
                      <a-form-item name="networkid" ref="networkid">
                        <network-selection
                          autoscale
                          :items="pagedNetworks"
                          :row-count="filteredNetworks.length"
                          :zoneId="form.zoneid || ''"
                          :value="form.networkid ? [form.networkid] : []"
                          :loading="networkLoading"
                          :preFillContent="{}"
                          @select-network-item="onSelectNetwork"
                          @handle-search-filter="options => updateSelectionOptions('networks', options)" />
                      </a-form-item>
                    </div>
                  </template>
                </a-step>

                <a-step
                  v-if="showKeyPairs"
                  :title="$t('label.sshkeypairs')"
                  :status="form.zoneid ? 'process' : 'wait'">
                  <template #description>
                    <div class="step-content">
                      <a-form-item name="keypairs" ref="keypairs">
                        <ssh-key-pair-selection
                          :items="pagedKeyPairs"
                          :row-count="filteredKeyPairs.length"
                          :zoneId="form.zoneid || ''"
                          :value="form.keypairs || []"
                          :loading="keyPairLoading"
                          :preFillContent="{}"
                          @select-ssh-key-pair-item="onSelectKeyPairs"
                          @handle-search-filter="options => updateSelectionOptions('keyPairs', options)" />
                      </a-form-item>
                    </div>
                  </template>
                </a-step>

                <a-step
                  :title="$t('label.details')"
                  :status="form.zoneid ? 'process' : 'wait'">
                  <template #description>
                    <div v-if="form.zoneid" class="step-content">
                      <a-form-item name="name" ref="name" :label="$t('label.name.optional')">
                        <a-input v-model:value="form.name" />
                      </a-form-item>
                      <a-form-item name="setvmpassword" ref="setvmpassword">
                        <a-checkbox v-model:checked="form.setvmpassword" @change="onSetVmPasswordChange">
                          {{ $t('label.dbaas.vm.password.set') }}
                        </a-checkbox>
                        <span class="hint">{{ $t('message.dbaas.vm.password.hint') }}</span>
                      </a-form-item>
                      <a-form-item
                        v-if="form.setvmpassword"
                        name="vmpassword"
                        ref="vmpassword"
                        :label="$t('label.dbaas.vm.password')">
                        <a-input-password
                          v-model:value="form.vmpassword"
                          autocomplete="new-password"
                          :placeholder="$t('label.dbaas.vm.password')" />
                      </a-form-item>
                    </div>
                  </template>
                </a-step>

                <a-step
                  :title="$t('label.database')"
                  :status="form.zoneid ? 'process' : 'wait'">
                  <template #description>
                    <div v-if="form.zoneid" class="step-content">
                      <a-form-item name="dbname" ref="dbname" :label="$t('label.dbname')">
                        <a-input v-model:value="form.dbname" :placeholder="$t('label.dbname')" />
                      </a-form-item>
                      <a-form-item name="dbusername" ref="dbusername" :label="$t('label.dbusername')">
                        <a-input v-model:value="form.dbusername" :placeholder="form.dbname || $t('label.dbusername')" />
                      </a-form-item>
                      <a-form-item name="dbpassword" ref="dbpassword" :label="$t('label.dbpassword')">
                        <a-input-password
                          v-model:value="form.dbpassword"
                          :placeholder="$t('message.dbaas.password.optional')" />
                      </a-form-item>
                    </div>
                  </template>
                </a-step>
              </a-steps>

              <div class="card-footer" v-if="isMobile()">
                <deploy-buttons
                  :loading="loading"
                  :deployButtonText="$t('label.create.database.instance')"
                  @handle-cancel="closeAction"
                  @handle-deploy="handleSubmit" />
              </div>
            </a-form>
          </a-spin>

          <a-steps v-if="step !== 'form'" :current="stepIndex" size="small" class="steps">
            <a-step :title="$t('label.instance')" />
            <a-step :title="$t('label.database')" />
            <a-step :title="$t('label.status')" />
          </a-steps>

          <!-- step 2: deploying, then waiting for the engine -->
          <div v-if="step === 'deploying' || step === 'provisioning'" class="progress-pane">
            <a-spin size="large" />
            <p class="progress-text">
              {{ step === 'deploying' ? $t('message.dbaas.deploying') : $t('message.dbaas.waiting.engine') }}
            </p>
            <!-- The instance already exists once this step is reached; if the
                 createDatabase call outlives this dialog (ignoreCancelToken) it
                 still finishes in the background. -->
            <p v-if="step === 'provisioning'" class="progress-sub">
              {{ $t('message.dbaas.close.early') }}
            </p>
            <div :span="24" class="action-button">
              <a-button v-if="step === 'provisioning'" @click="closeAction">{{ $t('label.close') }}</a-button>
            </div>
          </div>

          <!-- step 3a: success -->
          <div v-if="step === 'done'">
            <a-alert type="warning" showIcon :message="$t('message.desc.created.database')" />
            <a-descriptions bordered size="small" :column="1" class="credentials">
              <a-descriptions-item :label="$t('label.engine')">{{ credentials.engine }}</a-descriptions-item>
              <a-descriptions-item :label="$t('label.host')">{{ credentials.host }}</a-descriptions-item>
              <a-descriptions-item :label="$t('label.port')">{{ credentials.port }}</a-descriptions-item>
              <a-descriptions-item :label="$t('label.database')">{{ credentials.database }}</a-descriptions-item>
              <a-descriptions-item :label="$t('label.username')">{{ credentials.username }}</a-descriptions-item>
              <a-descriptions-item :label="$t('label.password')">{{ credentials.password }}</a-descriptions-item>
              <a-descriptions-item :label="$t('label.connect.command')">
                <span class="connect-command">{{ connectCommand }}</span>
              </a-descriptions-item>
            </a-descriptions>
            <p class="connect-hint">{{ $t('message.dbaas.connect.command') }}</p>
            <div :span="24" class="action-button">
              <a-button @click="markCopied" v-clipboard:copy="connectCommand" type="primary">
                {{ $t('label.copy.connect.command') }}
              </a-button>
              <a-button @click="markCopied" v-clipboard:copy="credentials.password">
                {{ $t('label.copy.password') }}
              </a-button>
              <a-button @click="confirmClose(goToInstance)">{{ $t('label.go.to.instance') }}</a-button>
              <a-button @click="confirmClose(closeAction)">{{ $t('label.close') }}</a-button>
            </div>
          </div>

          <!-- step 3b: the instance exists but the database step failed -->
          <div v-if="step === 'partial'">
            <a-alert type="error" showIcon :message="$t('label.dbaas.database.failed')">
              <template #description>
                <p>{{ $t('message.dbaas.database.failed') }}</p>
                <p class="error-detail">{{ failureMessage }}</p>
              </template>
            </a-alert>
            <div :span="24" class="action-button">
              <a-button type="primary" @click="goToInstance">{{ $t('label.go.to.instance') }}</a-button>
              <a-button @click="notifyCopied" v-clipboard:copy="failureMessage">{{ $t('label.copy.error') }}</a-button>
              <a-button @click="closeAction">{{ $t('label.close') }}</a-button>
            </div>
          </div>

          <!-- step 3c: the browser stopped listening before the answer came back. -->
          <div v-if="step === 'detached'">
            <a-alert type="info" showIcon :message="$t('label.dbaas.database.submitted')">
              <template #description>
                <p>{{ $t('message.dbaas.database.submitted') }}</p>
              </template>
            </a-alert>
            <div :span="24" class="action-button">
              <a-button type="primary" @click="goToInstance">{{ $t('label.go.to.instance') }}</a-button>
              <a-button @click="closeAction">{{ $t('label.close') }}</a-button>
            </div>
          </div>
        </a-card>
      </a-col>

      <a-col :md="24" :lg="7" v-if="step === 'form' && !isMobile()">
        <a-affix :offsetTop="75" class="database-info-card">
          <info-card
            :footerVisible="true"
            :resource="databaseInstance"
            :title="$t('label.yourinstance')">
            <template #footer-content>
              <deploy-buttons
                :loading="loading"
                :deployButtonText="$t('label.create.database.instance')"
                @handle-cancel="closeAction"
                @handle-deploy="handleSubmit" />
            </template>
          </info-card>
        </a-affix>
      </a-col>
    </a-row>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { Modal } from 'ant-design-vue'
import { getAPI, postAPI } from '@/api'
import { mixinDevice, mixinForm } from '@/utils/mixin'
import InfoCard from '@/components/view/InfoCard'
import ComputeOfferingSelection from '@/views/compute/wizard/ComputeOfferingSelection'
import DeployButtons from '@/views/compute/wizard/DeployButtons'
import DiskOfferingSelection from '@/views/compute/wizard/DiskOfferingSelection'
import NetworkSelection from '@/views/compute/wizard/NetworkSelection'
import SshKeyPairSelection from '@/views/compute/wizard/SshKeyPairSelection'
import TemplateIsoRadioGroup from '@/views/compute/wizard/TemplateIsoRadioGroup'
import ZoneBlockRadioGroupSelect from '@/views/compute/wizard/ZoneBlockRadioGroupSelect'
import {
  buildConnectCommand,
  DBAAS_TEMPLATE_PREFIX,
  DBAAS_IDENTIFIER_PATTERN,
  DBAAS_PASSWORD_PATTERN
} from '@/utils/dbaas'

export default {
  name: 'CreateDatabaseInstance',
  components: {
    ComputeOfferingSelection,
    DeployButtons,
    DiskOfferingSelection,
    InfoCard,
    NetworkSelection,
    SshKeyPairSelection,
    TemplateIsoRadioGroup,
    ZoneBlockRadioGroupSelect
  },
  mixins: [mixinDevice, mixinForm],
  props: {},
  provide () {
    return {
      vmFetchNetworks: this.fetchNetworks
    }
  },
  data () {
    return {
      loading: false,
      optionsLoading: false,
      networkLoading: false,
      step: 'form',
      templates: [],
      // Template id -> that engine's minimum offering RAM in MB, from
      // listDbaasEngines' minmemorymb (cross-referenced by template name,
      // since the engines response is keyed by name and templates by id).
      // Falls back to 0 (no filtering) for a template with no matching
      // engine entry or no minmemorymb set.
      engineMinMemoryByTemplate: {},
      zones: [],
      offerings: [],
      diskOfferings: [],
      networks: [],
      keyPairs: [],
      keyPairLoading: false,
      imageSearch: '',
      selectionOptions: {
        templates: { page: 1, pageSize: 10, keyword: '' },
        offerings: { page: 1, pageSize: 10, keyword: '' },
        diskOfferings: { page: 1, pageSize: 10, keyword: '' },
        networks: { page: 1, pageSize: 10, keyword: '' },
        keyPairs: { page: 1, pageSize: 10, keyword: '' }
      },
      credentials: {},
      dbPasswordCopied: false,
      failureMessage: '',
      closed: false,
      deployedVmId: null
    }
  },
  computed: {
    selectedTemplate () {
      return this.templates.find(item => item.id === this.form.engine) || {}
    },
    filteredTemplates () {
      return this.filterSelection(this.templates, this.selectionOptions.templates, ['name', 'displaytext'])
    },
    pagedTemplates () {
      return this.paginateSelection(this.filteredTemplates, this.selectionOptions.templates)
    },
    filteredOfferings () {
      return this.filterSelection(this.availableOfferings, this.selectionOptions.offerings, ['name', 'displaytext'])
    },
    pagedOfferings () {
      return this.paginateSelection(this.filteredOfferings, this.selectionOptions.offerings)
    },
    filteredDiskOfferings () {
      return this.filterSelection(this.diskOfferings, this.selectionOptions.diskOfferings, ['name', 'displaytext'])
    },
    pagedDiskOfferings () {
      return this.paginateSelection(this.filteredDiskOfferings, this.selectionOptions.diskOfferings)
    },
    filteredNetworks () {
      return this.filterSelection(this.networks, this.selectionOptions.networks, ['name', 'displaytext', 'networkofferingdisplaytext'])
    },
    pagedNetworks () {
      return this.paginateSelection(this.filteredNetworks, this.selectionOptions.networks)
    },
    filteredKeyPairs () {
      return this.filterSelection(this.keyPairs, this.selectionOptions.keyPairs, ['name', 'account', 'domain'])
    },
    pagedKeyPairs () {
      return this.paginateSelection(this.filteredKeyPairs, this.selectionOptions.keyPairs)
    },
    stepIndex () {
      if (this.step === 'form') return 0
      if (this.step === 'deploying' || this.step === 'provisioning') return 1
      return 2
    },
    connectCommand () {
      return buildConnectCommand(this.credentials)
    },
    showKeyPairs () {
      return 'listSSHKeyPairs' in this.$store.getters.apis
    },
    needsNetwork () {
      const zone = this.zones.find(z => z.id === this.form.zoneid)
      return !!zone && zone.networktype !== 'Basic'
    },
    // A fixed-size compute offering rejects rootdisksize outright, so the
    // field only appears when the selected offering actually allows a
    // custom root size -- same rule DeployVM.vue applies.
    selectedOfferingIsCustomized () {
      const offering = this.offerings.find(o => o.id === this.form.serviceofferingid)
      return !!offering && !!offering.iscustomized
    },
    selectedEngineMinMemory () {
      return this.engineMinMemoryByTemplate[this.form.engine] || 0
    },
    // The dropdown iterates this, not the raw list: an offering below the
    // selected engine's minimum is not merely discouraged, it is not
    // selectable at all. Nothing is filtered before an engine is chosen --
    // the field is disabled at that point instead (see the template).
    availableOfferings () {
      const minMb = this.selectedEngineMinMemory
      if (!minMb) {
        return this.offerings
      }
      // A customized offering (memory chosen at deploy time, not fixed here)
      // reports no memory value to filter on -- let it through rather than
      // hide a legitimately fine choice because this dropdown cannot know
      // the number yet.
      return this.offerings.filter(o => o.memory == null || o.memory >= minMb)
    },
    databaseInstance () {
      const engine = this.templates.find(item => item.id === this.form.engine)
      const zone = this.zones.find(item => item.id === this.form.zoneid)
      const offering = this.offerings.find(item => item.id === this.form.serviceofferingid)
      const diskOffering = this.diskOfferings.find(item => item.id === this.form.diskofferingid)
      const network = this.networks.find(item => item.id === this.form.networkid)

      return {
        name: this.form.name || this.form.dbname || this.$t('label.database'),
        templateid: engine?.id,
        templatename: engine?.engineLabel,
        zoneid: zone?.id,
        zonename: zone?.name,
        serviceofferingid: offering?.id,
        serviceofferingname: offering?.name || offering?.displaytext,
        datadiskofferingid: diskOffering?.id,
        datadiskofferingdisplaytext: diskOffering?.displaytext || diskOffering?.name,
        networks: network ? [network] : [],
        keypairs: (this.form.keypairs || []).join(',')
      }
    }
  },
  watch: {
    // The clearing itself lives in onEngineChange (bound on the engine
    // select): a deep watch on `form` did not fire reliably on the engine
    // switch in the browser (observed 2026-09-09 -- a Small selection
    // survived a switch to mysql, whose floor excludes it), and an explicit
    // handler on the change event is the same logic without depending on
    // watcher ordering against the computed availableOfferings.
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createDatabase')
  },
  created () {
    this.initForm()
    this.fetchOptions()
    this.fetchKeyPairs()
  },
  // Leaving via the sidebar (or browser back) bypasses closeAction entirely;
  // marking closed here is what arms the background success/failure
  // notifications, whichever way the user exits.
  unmounted () {
    this.closed = true
  },
  methods: {
    updateSelectionOptions (name, options) {
      this.selectionOptions[name] = {
        ...this.selectionOptions[name],
        ...options
      }
    },
    filterSelection (items, options, fields) {
      const keyword = String(options.keyword || '').trim().toLowerCase()
      if (!keyword) {
        return items
      }
      return items.filter(item => fields.some(field =>
        String(item[field] || '').toLowerCase().includes(keyword)))
    },
    paginateSelection (items, options) {
      const start = (options.page - 1) * options.pageSize
      return items.slice(start, start + options.pageSize)
    },
    onSelectEngine (engineId) {
      this.form.engine = engineId
      this.selectionOptions.offerings.page = 1
      this.onEngineChange()
    },
    onSelectOffering (offeringId) {
      this.form.serviceofferingid = offeringId
    },
    onSelectDiskOffering (diskOfferingId) {
      this.form.diskofferingid = diskOfferingId && diskOfferingId !== '0'
        ? diskOfferingId
        : undefined
    },
    onSelectNetwork (networkId) {
      this.form.networkid = Array.isArray(networkId) ? networkId[0] : networkId
    },
    onSelectKeyPairs (keyPairs) {
      this.form.keypairs = (keyPairs || []).map(keyPair =>
        typeof keyPair === 'string' ? keyPair : keyPair.name)
    },
    onSelectZone (zoneId) {
      this.form.zoneid = zoneId
      this.fetchNetworks()
    },
    // Lives here, not in computed: it mutates form state and returns
    // nothing, and a computed is cached -- bound to @change it would run at
    // most once and then never fire again on later engine switches, which is
    // precisely the stranding it exists to prevent (and it also failed the
    // build's own vue/no-side-effects-in-computed-properties rule).
    onSetVmPasswordChange () {
      // Unticking hides the field; drop whatever was typed with it so a
      // password the tenant can no longer see is never submitted.
      if (!this.form.setvmpassword) {
        this.form.vmpassword = undefined
      }
    },
    onEngineChange () {
      // Switching to an engine with a higher floor can strand a previously
      // valid selection below the new minimum -- clear it rather than submit
      // a choice the dropdown itself would no longer offer. A selection that
      // still qualifies under the new engine is deliberately kept.
      if (this.form.serviceofferingid &&
          !this.availableOfferings.some(o => o.id === this.form.serviceofferingid)) {
        this.form.serviceofferingid = undefined
      }
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      const identifier = {
        pattern: DBAAS_IDENTIFIER_PATTERN,
        message: this.$t('message.error.database.identifier')
      }
      const required = { required: true, message: this.$t('message.error.required.input') }
      // Kept on the instance: fetchNetworks() adds or drops the network rule
      // as the selected zone changes (advanced zones need one, basic zones
      // reject the parameter outright).
      this.requiredRule = required
      this.rules = reactive({
        networkid: [],
        engine: [required],
        zoneid: [required],
        serviceofferingid: [required],
        dbname: [required, identifier],
        // Optional: when left empty the backend defaults the user to the
        // database name (see the banner above the form). The identifier
        // pattern still applies to whatever is typed.
        dbusername: [identifier],
        // Optional: an empty field means the backend generates one.
        dbpassword: [{ pattern: DBAAS_PASSWORD_PATTERN, message: this.$t('message.error.database.password') }]
      })
    },
    fetchOptions () {
      this.optionsLoading = true
      // listDbaasEngines (from the plugin) is the source of truth for which
      // templates are engines; the dbaas- keyword/prefix below is only the
      // fallback for management servers running an older plugin build.
      const hasEnginesApi = 'listDbaasEngines' in this.$store.getters.apis
      const templateParams = hasEnginesApi
        ? { templatefilter: 'executable', pagesize: -1, showicon: true }
        : { templatefilter: 'executable', keyword: DBAAS_TEMPLATE_PREFIX, pagesize: -1, showicon: true }
      Promise.all([
        getAPI('listTemplates', templateParams),
        getAPI('listZones', { available: true }),
        getAPI('listServiceOfferings', { pagesize: -1 }),
        // Data disk is entirely optional, so this is never in the required
        // rules -- it only ever adds an extra volume when actually picked.
        getAPI('listDiskOfferings', { pagesize: -1 }),
        hasEnginesApi ? getAPI('listDbaasEngines') : Promise.resolve(null)
      ]).then(([tpl, zone, off, diskOff, engines]) => {
        const engineList = engines ? (engines.listdbaasenginesresponse?.dbaasengine || []) : []
        const engineNames = engines ? new Set(engineList.map(e => e.template)) : null
        // Keyed by template name here (matching the engines response); the
        // per-id map below is what the offering filter actually reads,
        // since the wizard tracks the selected engine by template id.
        const minMemoryByEngineName = {}
        engineList.forEach(e => { minMemoryByEngineName[e.template] = e.minmemorymb || 0 })
        this.templates = (tpl.listtemplatesresponse.template || [])
          .filter(t => t.name && t.isready && (engineNames ? engineNames.has(t.name) : t.name.startsWith(DBAAS_TEMPLATE_PREFIX)))
          // A template only provisions over the config drive when it carries
          // the marker detail; without it the backend refuses createDatabase
          // anyway, so do not offer what would fail. When the list response
          // carries no details at all, let the backend be the judge.
          .filter(t => !t.details || t.details['dbaas.configdrive'] === 'true')
          // Same label source DatabaseInstances uses: the template's own
          // displaytext ("MySQL Community 8.0 on Debian 12 x86_64"), so a new
          // engine added to the backend config shows up without UI changes.
          .map(t => ({ ...t, engineLabel: t.displaytext || t.name }))
        this.engineMinMemoryByTemplate = {}
        this.templates.forEach(t => {
          this.engineMinMemoryByTemplate[t.id] = minMemoryByEngineName[t.name] || 0
        })
        this.zones = zone.listzonesresponse.zone || []
        this.offerings = off.listserviceofferingsresponse.serviceoffering || []
        this.diskOfferings = diskOff.listdiskofferingsresponse.diskoffering || []
        if (this.zones.length === 1) {
          this.form.zoneid = this.zones[0].id
          this.fetchNetworks()
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.optionsLoading = false
      })
    },
    fetchNetworks () {
      this.form.networkid = undefined
      this.selectionOptions.networks = { page: 1, pageSize: 10, keyword: '' }
      if (!this.needsNetwork) {
        this.networks = []
        // Basic zones take no networkids at all, so the field cannot be
        // required there -- a rule left in place would block every submit.
        this.rules.networkid = []
        return
      }
      // Leaving this empty in an advanced zone does NOT mean "no network":
      // deployVirtualMachine then creates the account's default isolated
      // network and puts the instance behind its virtual router, where the
      // management server has no route to it -- provisioning fails with an
      // opaque SSH "timed out" after the instance is already running.
      this.rules.networkid = [this.requiredRule]
      this.networkLoading = true
      getAPI('listNetworks', { zoneid: this.form.zoneid, pagesize: -1 }).then(json => {
        this.networks = json.listnetworksresponse.network || []
        if (this.networks.length === 1) {
          this.form.networkid = this.networks[0].id
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.networkLoading = false
      })
    },
    fetchKeyPairs () {
      if (!this.showKeyPairs) {
        return
      }
      this.keyPairLoading = true
      getAPI('listSSHKeyPairs', { pagesize: -1 }).then(json => {
        this.keyPairs = json.listsshkeypairsresponse.sshkeypair || []
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.keyPairLoading = false
      })
    },
    errorText (error) {
      const data = error?.response?.data
      if (data) {
        const key = Object.keys(data).find(k => data[k] && data[k].errortext)
        if (key) return data[key].errortext
      }
      return error?.message || String(error)
    },
    handleSubmit (e) {
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        this.loading = true
        const params = {
          templateid: values.engine,
          zoneid: values.zoneid,
          serviceofferingid: values.serviceofferingid
        }
        if (values.name) {
          params.name = values.name
        }
        // Only sent when the offering allows it (the field itself is hidden
        // otherwise), so this never reaches the API for a fixed-size offering.
        if (this.selectedOfferingIsCustomized && values.rootdisksize) {
          params.rootdisksize = values.rootdisksize
        }
        // A data disk is optional; omitting diskofferingid deploys with the
        // root volume only, same as before this field existed.
        if (values.diskofferingid) {
          params.diskofferingid = values.diskofferingid
        }
        // deployVirtualMachine takes both `keypair` and `keypairs`; the standard
        // Add Instance wizard sends `keypairs`, so match it.
        if (values.keypairs && values.keypairs.length > 0) {
          params.keypairs = values.keypairs.join(',')
        }
        // Only when the tenant asked to set one; otherwise CloudStack
        // generates it, which is the pre-existing behaviour.
        if (values.setvmpassword && values.vmpassword) {
          params.password = values.vmpassword
        }
        // Basic zones reject networkids outright, so it is only sent when the
        // selected zone actually needs one.
        if (this.needsNetwork && values.networkid) {
          params.networkids = values.networkid
        }
        // Provisioning reads its request from the config drive at boot, so
        // the request has to be attached before the instance ever starts:
        // deploy it stopped and let createDatabase start it once the request
        // is in place.
        params.startvm = false
        this.step = 'deploying'
        // ignoreCancelToken: leaving mid-deploy must not abort the deploy
        // request -- the API call submits the async job server-side even if
        // the client stops listening, and the success handler below then
        // chains into provisioning in the background.
        postAPI('deployVirtualMachine', params, { ignoreCancelToken: true }).then(response => {
          const jobId = response.deployvirtualmachineresponse.jobid
          if (!jobId) {
            this.failStep(this.$t('error.fetching.async.job.result'))
            return
          }
          this.$pollJob({
            jobId,
            ignoreCancelToken: true,
            title: this.$t('label.create.database.instance'),
            description: values.name || values.dbname,
            showSuccessMessage: false,
            successMethod: result => {
              const vm = result.jobresult.virtualmachine
              this.deployedVmId = vm.id
              this.step = 'provisioning'
              this.provision(vm.id, values)
            },
            errorMethod: result => {
              this.step = 'form'
              this.loading = false
              this.$notifyError(new Error(result?.jobresult?.errortext || this.$t('label.error')))
            },
            loadingMessage: `${this.$t('label.create.database.instance')} ${this.$t('label.in.progress')}`,
            catchMessage: this.$t('error.fetching.async.job.result'),
            action: { isFetchData: false }
          })
        }).catch(error => {
          // Nothing was created yet, so the form is still the right place to be.
          this.step = 'form'
          this.loading = false
          this.$notifyError(error)
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    provision (vmId, values) {
      // ignoreCancelToken keeps this call alive after the dialog is closed:
      // the whole point of the non-blocking flow is that provisioning
      // finishes in the background while route changes cancel every other
      // in-flight request. Without it a navigation aborts the call and the
      // database never gets provisioned, with nothing telling the user why.
      // Unlike the old SSH transport, this is one attempt: createDatabase
      // either attaches the request and starts the instance, or it fails
      // outright -- there is no transient "sshd not listening yet" state to
      // retry against.
      postAPI('createDatabase', {
        virtualmachineid: vmId,
        dbname: values.dbname,
        dbusername: values.dbusername,
        dbpassword: values.dbpassword
      }, { ignoreCancelToken: true }).then(json => {
        const dbaas = json.createdatabaseresponse?.dbaas
        if (dbaas) {
          this.credentials = dbaas
          this.step = 'done'
          this.loading = false
          this.$emit('refresh-data')
        } else {
          this.failStep(this.$t('message.error.database.response'))
        }
      }).catch(error => {
        // Navigating away (Database -> Instances, say) cancels in-flight
        // requests. `ignoreCancelToken` is meant to exempt this one, but when
        // the cancellation lands anyway all we lost is the *answer*: the
        // management server already accepted createDatabase and is running it.
        // Reporting that as "Instance created -- database was not" is simply
        // false, and users saw it while the database was being created
        // perfectly well. Say what is actually true instead.
        if (this.isCancellation(error)) {
          this.detachedStep()
          return
        }
        this.failStep(this.errorText(error))
      })
    },
    // axios reports a cancelled request in three shapes depending on version
    // and on whether the adapter or the interceptor did the cancelling.
    isCancellation (error) {
      if (!error) {
        return false
      }
      if (error.code === 'ERR_CANCELED' || error.__CANCEL__ === true) {
        return true
      }
      const text = String(error.message || error).toLowerCase()
      return text === 'canceled' || text === 'cancelled'
    },
    detachedStep () {
      this.step = 'detached'
      this.loading = false
      if (this.closed) {
        this.$notification.info({
          message: this.$t('label.dbaas.database.submitted'),
          description: this.$t('message.dbaas.database.submitted'),
          duration: 0
        })
      }
      this.$emit('refresh-data')
    },
    failStep (message) {
      // The instance is up either way. Say so loudly: deploying a second one
      // only leaves an orphan behind and the retry would hit a duplicate user.
      this.failureMessage = message
      this.step = 'partial'
      this.loading = false
      if (this.closed) {
        this.$notification.error({
          message: this.$t('label.dbaas.database.failed'),
          description: message,
          duration: 0
        })
      }
      this.$emit('refresh-data')
    },
    goToInstance () {
      if (this.deployedVmId) {
        this.$router.push({ path: '/vm/' + this.deployedVmId })
      }
      // The push above already navigates; closeAction() would $router.back()
      // on top of it and land on the wrong page.
      this.closed = true
    },
    markCopied () {
      this.dbPasswordCopied = true
      this.notifyCopied()
    },
    notifyCopied () {
      this.$notification.info({
        message: this.$t('message.success.copy.clipboard'),
        duration: 2
      })
    },
    // Only guards the credentials step: the database password is also stored
    // server-side and recoverable later via Show Password, so this is a
    // courtesy nudge to copy it now, not a last chance.
    confirmClose (proceed) {
      if (this.step !== 'done' || this.dbPasswordCopied) {
        proceed()
        return
      }
      Modal.confirm({
        title: this.$t('label.close'),
        content: this.$t('message.confirm.close.database.dbpassword'),
        okText: this.$t('label.yes'),
        cancelText: this.$t('label.no'),
        onOk: proceed
      })
    },
    closeAction () {
      this.closed = true
      this.$emit('close-action')
      // This view is only ever rendered as the full-page /action/createDatabase
      // route (never inside a modal), so nothing listens for close-action:
      // navigate back ourselves, the same way DeployVM's full page does.
      if (this.$route.path.startsWith('/action/')) {
        this.$router.back()
      }
    }
  }
}
</script>

<style scoped lang="less">
  .step-content {
    margin-top: 15px;
  }

  .selection-search {
    display: block;
    width: 25vw;
    margin: 0 0 10px auto;
    z-index: 8;

    @media (max-width: 600px) {
      width: 100%;
    }
  }

  .card-footer {
    text-align: right;
    margin-top: 2rem;
  }

  .form-item-hidden {
    display: none;
  }

  .form-banner {
    margin-bottom: 16px;
  }

  .offering-warning {
    margin-top: 4px;
    color: #cf1322;
  }

  .steps {
    margin-bottom: 20px;
  }

  .progress-pane {
    text-align: center;
    padding: 32px 0;
  }

  .progress-text {
    margin-top: 16px;
    font-weight: 500;
  }

  .progress-sub {
    color: rgba(0, 0, 0, 0.45);
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

  .hint {
    display: block;
    margin-top: 4px;
    font-size: 12px;
    color: rgba(0, 0, 0, 0.45);
    line-height: 1.4;
  }

  .connect-hint {
    margin-top: 4px;
    color: rgba(0, 0, 0, 0.45);
    word-break: break-all;
  }

  .error-detail {
    margin-top: 8px;
    font-family: monospace;
    word-break: break-all;
  }
</style>

<style lang="less">
  .database-info-card {
    .ant-card-body {
      min-height: 250px;
      max-height: calc(100vh - 140px);
      overflow: hidden;
    }

    .card-content {
      max-height: calc(100vh - 240px);
      overflow-y: auto;
      scroll-behavior: smooth;
    }

    .card-footer {
      border-top: 1px solid #f0f0f0;
      flex-shrink: 0;
    }

    .resource-detail-item__label {
      font-weight: normal;
    }

    .resource-detail-item__details, .resource-detail-item {
      a {
        color: rgba(0, 0, 0, 0.65);
        cursor: default;
        pointer-events: none;
      }
    }
  }
</style>

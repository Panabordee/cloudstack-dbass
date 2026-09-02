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
    <a-steps :current="stepIndex" size="small" class="steps">
      <a-step :title="$t('label.instance')" />
      <a-step :title="$t('label.database')" />
      <a-step :title="$t('label.status')" />
    </a-steps>

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
        <a-form-item name="engine" ref="engine" :label="$t('label.engine')">
          <a-select
            v-model:value="form.engine"
            :loading="optionsLoading"
            :placeholder="$t('label.engine')"
            v-focus="true">
            <a-select-option v-for="t in templates" :key="t.id" :label="t.engineLabel">
              {{ t.engineLabel }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="zoneid" ref="zoneid" :label="$t('label.zoneid')">
          <a-select
            v-model:value="form.zoneid"
            :loading="optionsLoading"
            :placeholder="$t('label.zoneid')"
            @change="fetchNetworks">
            <a-select-option v-for="z in zones" :key="z.id" :label="z.name">{{ z.name }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="serviceofferingid" ref="serviceofferingid" :label="$t('label.serviceofferingid')">
          <!-- Only offerings at or above Medium are listed: on smaller ones the
               engine starves the vCPU and sshd cannot answer in time. -->
          <a-select
            v-model:value="form.serviceofferingid"
            :loading="optionsLoading"
            :placeholder="$t('label.serviceofferingid')">
            <a-select-option v-for="o in offerings" :key="o.id" :label="o.label">{{ o.label }}</a-select-option>
          </a-select>
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
        <a-form-item name="diskofferingid" ref="diskofferingid" :label="$t('label.datadiskoffering')">
          <a-select
            v-model:value="form.diskofferingid"
            allowClear
            :loading="optionsLoading"
            :placeholder="$t('label.datadiskoffering')">
            <a-select-option v-for="d in diskOfferings" :key="d.id" :label="d.label">{{ d.label }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item
          name="networkid"
          ref="networkid"
          :label="$t('label.networkid')"
          v-if="needsNetwork">
          <a-select
            v-model:value="form.networkid"
            :loading="networkLoading"
            :placeholder="$t('label.networkid')">
            <a-select-option v-for="n in networks" :key="n.id" :label="n.name">{{ n.name }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="keypair" ref="keypair" :label="$t('label.keypair')" v-if="showKeyPairs">
          <a-select
            v-model:value="form.keypair"
            allowClear
            :loading="keyPairLoading"
            :placeholder="$t('label.keypair')">
            <a-select-option v-for="k in keyPairs" :key="k" :label="k">{{ k }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="name" ref="name" :label="$t('label.name')">
          <a-input v-model:value="form.name" :placeholder="$t('label.name')" />
        </a-form-item>
        <a-form-item name="dbname" ref="dbname" :label="$t('label.dbname')">
          <a-input v-model:value="form.dbname" :placeholder="$t('label.dbname')" />
        </a-form-item>
        <a-form-item name="dbusername" ref="dbusername" :label="$t('label.dbusername')">
          <a-input v-model:value="form.dbusername" :placeholder="form.dbname || $t('label.dbusername')" />
        </a-form-item>

        <div :span="24" class="action-button">
          <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-spin>

    <!-- step 2: deploying, then waiting for the engine -->
    <div v-if="step === 'deploying' || step === 'provisioning'" class="progress-pane">
      <a-spin size="large" />
      <p class="progress-text">
        {{ step === 'deploying' ? $t('message.dbaas.deploying') : $t('message.dbaas.waiting.engine') }}
      </p>
      <p v-if="step === 'provisioning'" class="progress-sub">
        {{ $t('label.in.progress') }} {{ attempt }}/{{ maxAttempts }}
      </p>
      <!-- The instance already exists once this step is reached; provisioning
           keeps retrying in the background even after this dialog closes, so
           there is nothing left here that requires staying open. -->
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
        <a-button @click="notifyCopied" v-clipboard:copy="connectCommand" type="primary">
          {{ $t('label.copy.connect.command') }}
        </a-button>
        <a-button @click="notifyCopied" v-clipboard:copy="credentials.password">
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
  </div>
</template>

<script>
import { ref, reactive, toRaw, h } from 'vue'
import { Button, Modal } from 'ant-design-vue'
import { getAPI, postAPI } from '@/api'
import { mixinForm } from '@/utils/mixin'
import { buildConnectCommand, copyTextToClipboard } from '@/utils/dbaas'

// The provisioning run is only reachable once sshd inside the fresh instance
// answers. Anything matching these means we were too early and another attempt
// is worth it; a rejection from the engine itself (duplicate user, invalid
// identifier) will never succeed on a retry and must surface immediately.
const TRANSIENT_ERRORS = [
  // paramiko, when sshd accepts the socket but cannot finish the handshake
  'No existing session',
  'Error reading SSH protocol banner',
  // the NIC has no address in the CloudStack API yet
  'could not resolve VM IP',
  // sshd is not listening yet, or the guest has not brought the NIC up
  'Connection refused',
  'No route to host',
  'Unable to connect to port 22',
  'NoValidConnectionsError',
  // SSH is up but the engine behind it is not listening yet. Each engine says
  // this differently and none of them overlaps with the refusals the scripts
  // raise themselves ("user already exists", "invalid identifier"), which must
  // still fail on the first attempt.
  "Can't connect to local MySQL server",
  'connection to server on socket',
  'MongoNetworkError',
  'ECONNREFUSED',
  // 'timed out' covers ssh/paramiko, 'timeout' covers axios' own
  // "timeout of 600000ms exceeded"
  'timed out',
  'timeout',
  // the request never reached the management server
  'Network Error'
]

export default {
  name: 'CreateDatabaseInstance',
  mixins: [mixinForm],
  props: {},
  data () {
    return {
      loading: false,
      optionsLoading: false,
      networkLoading: false,
      step: 'form',
      templates: [],
      zones: [],
      offerings: [],
      diskOfferings: [],
      networks: [],
      keyPairs: [],
      keyPairLoading: false,
      credentials: {},
      passwordCopied: false,
      failureMessage: '',
      closed: false,
      deployedVmId: null,
      attempt: 0,
      // A fresh instance needs about 48s before sshd answers, and each failed
      // attempt burns ~15s of connect timeout on top of the delay, so eight
      // attempts cover roughly three minutes of slow boot.
      maxAttempts: 8,
      retryDelayMs: 10000
    }
  },
  computed: {
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
    }
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
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      // Same shape the provisioning scripts accept, so an identifier the
      // backend would reject is caught here instead of after a round trip.
      const identifier = {
        pattern: /^[A-Za-z][A-Za-z0-9_]{0,31}$/,
        message: this.$t('message.error.database.identifier')
      }
      const required = { required: true, message: this.$t('message.error.required.input') }
      this.rules = reactive({
        engine: [required],
        zoneid: [required],
        serviceofferingid: [required],
        dbname: [required, identifier],
        // Optional: when left empty the backend defaults the user to the
        // database name (see the banner above the form). The identifier
        // pattern still applies to whatever is typed.
        dbusername: [identifier]
      })
    },
    fetchOptions () {
      this.optionsLoading = true
      Promise.all([
        // keyword also matches displaytext, so the dbaas- prefix is what
        // actually decides — the base image mentions DBaaS in its description.
        getAPI('listTemplates', { templatefilter: 'executable', keyword: 'dbaas' }),
        getAPI('listZones', { available: true }),
        // memory and cpuspeed are minimum filters, not exact matches, so this
        // drops every offering below Medium server-side.
        getAPI('listServiceOfferings', { memory: 1024, cpuspeed: 1000 }),
        // Data disk is entirely optional, so this is never in the required
        // rules -- it only ever adds an extra volume when actually picked.
        getAPI('listDiskOfferings')
      ]).then(([tpl, zone, off, diskOff]) => {
        this.templates = (tpl.listtemplatesresponse.template || [])
          .filter(t => t.name && t.name.startsWith('dbaas-') && t.isready)
          // Same label source DatabaseInstances uses: the template's own
          // displaytext ("MySQL Community 8.0 on Debian 12 x86_64"), so a new
          // engine added to the backend config shows up without UI changes.
          .map(t => ({ id: t.id, name: t.name, engineLabel: t.displaytext || t.name }))
        this.zones = zone.listzonesresponse.zone || []
        this.offerings = (off.listserviceofferingsresponse.serviceoffering || [])
          .map(o => ({ id: o.id, label: `${o.name} (${o.cpunumber} vCPU, ${o.memory} MB)`, iscustomized: o.iscustomized }))
        this.diskOfferings = (diskOff.listdiskofferingsresponse.diskoffering || [])
          .map(d => ({ id: d.id, label: d.iscustomized ? `${d.name} (${this.$t('label.iscustomized')})` : `${d.name} (${d.disksize} GB)` }))
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
      if (!this.needsNetwork) {
        this.networks = []
        return
      }
      this.networkLoading = true
      getAPI('listNetworks', { zoneid: this.form.zoneid }).then(json => {
        this.networks = json.listnetworksresponse.network || []
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
      getAPI('listSSHKeyPairs', {}).then(json => {
        this.keyPairs = (json.listsshkeypairsresponse.sshkeypair || []).map(k => k.name)
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
    isTransient (message) {
      return TRANSIENT_ERRORS.some(m => message.includes(m))
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
        if (values.keypair) {
          params.keypairs = values.keypair
        }
        // Basic zones reject networkids outright, so it is only sent when the
        // selected zone actually needs one.
        if (this.needsNetwork && values.networkid) {
          params.networkids = values.networkid
        }
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
              this.provision(vm.id, values, 1)
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
    provision (vmId, values, attempt) {
      this.attempt = attempt
      // ignoreCancelToken keeps this call (and its retries) alive after the
      // dialog is closed: the whole point of the non-blocking flow is that
      // provisioning finishes in the background while route changes cancel
      // every other in-flight request. Without it a navigation aborts the
      // call, the client treats the cancellation as a hard failure, and the
      // retry chain dies silently -- the database then never gets provisioned
      // and Show Password finds no stored credential.
      postAPI('createDatabase', {
        virtualmachineid: vmId,
        dbname: values.dbname,
        dbusername: values.dbusername
      }, { ignoreCancelToken: true }).then(json => {
        const dbaas = json.createdatabaseresponse?.dbaas
        if (dbaas) {
          this.credentials = dbaas
          this.step = 'done'
          this.loading = false
          if (this.closed) {
            // The user already left via the non-blocking close; the
            // notification is their one shot at the credentials, so it
            // carries username/password/command with copy buttons right on
            // it. The password is also stored server-side, retrievable via
            // Show Password.
            const copyButton = (label, text) => h(Button, {
              size: 'small',
              style: { marginRight: '8px' },
              onClick: async () => {
                if (await copyTextToClipboard(text)) {
                  this.notifyCopied()
                }
              }
            }, { default: () => label })
            this.$notification.success({
              message: this.$t('label.create.database.instance'),
              description: h('div', [
                h('div', `username: ${this.credentials.username}`),
                h('div', `password: ${this.credentials.password}`),
                h('div', {
                  style: { fontFamily: 'monospace', wordBreak: 'break-all', marginTop: '4px' }
                }, this.connectCommand)
              ]),
              btn: h('div', { style: { marginTop: '8px' } }, [
                copyButton(this.$t('label.copy.password'), this.credentials.password),
                copyButton(this.$t('label.copy.connect.command'), this.connectCommand)
              ]),
              duration: 0
            })
          }
          this.$emit('refresh-data')
        } else {
          this.failStep(this.$t('message.error.database.response'))
        }
      }).catch(error => {
        const message = this.errorText(error)
        if (attempt < this.maxAttempts && this.isTransient(message)) {
          setTimeout(() => this.provision(vmId, values, attempt + 1), this.retryDelayMs)
          return
        }
        this.failStep(message)
      })
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
    notifyCopied () {
      this.passwordCopied = true
      this.$notification.info({
        message: this.$t('message.success.copy.clipboard')
      })
    },
    // Only guards the credentials step -- by the time this runs the password
    // is already stored server-side and retrievable via Show Password later,
    // so this is a courtesy nudge to copy it now, not a last chance.
    confirmClose (proceed) {
      if (this.step !== 'done' || this.passwordCopied) {
        proceed()
        return
      }
      Modal.confirm({
        title: this.$t('label.close'),
        content: this.$t('message.confirm.close.database.password'),
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
  .form-layout {
    width: 80vw;

    @media (min-width: 600px) {
      width: 500px;
    }
  }

  .form-banner {
    margin-bottom: 16px;
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

  .error-detail {
    margin-top: 8px;
    font-family: monospace;
    word-break: break-all;
  }
</style>

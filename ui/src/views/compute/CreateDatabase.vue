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
    <a-spin :spinning="loading" v-if="!isSubmitted">
      <p v-html="$t('message.desc.create.database')"></p>
      <a-alert
        type="warning"
        show-icon
        banner
        :message="$t('message.dbaas.will.restart')"
        class="form-banner" />
      <a-form
        v-ctrl-enter="handleSubmit"
        :ref="formRef"
        :model="form"
        :rules="rules"
        @finish="handleSubmit"
        layout="vertical">
        <a-form-item name="dbname" ref="dbname">
          <template #label>
            <tooltip-label :title="$t('label.dbname')" :tooltip="apiParams.dbname.description"/>
          </template>
          <a-input
            v-model:value="form.dbname"
            :placeholder="apiParams.dbname.description"
            v-focus="true" />
        </a-form-item>
        <a-form-item name="dbusername" ref="dbusername">
          <template #label>
            <tooltip-label :title="$t('label.dbusername')" :tooltip="apiParams.dbusername.description"/>
          </template>
          <a-input
            v-model:value="form.dbusername"
            :placeholder="apiParams.dbusername.description"/>
        </a-form-item>
        <a-form-item name="dbpassword" ref="dbpassword">
          <template #label>
            <tooltip-label :title="$t('label.dbpassword')" :tooltip="$t('message.dbaas.password.optional')"/>
          </template>
          <a-input-password
            v-model:value="form.dbpassword"
            :placeholder="$t('message.dbaas.password.optional')"/>
        </a-form-item>

        <div :span="24" class="action-button">
          <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-spin>
    <div v-if="isSubmitted">
      <a-alert type="warning" showIcon :message="$t('message.desc.created.database')" />
      <a-descriptions bordered size="small" :column="1" class="credentials">
        <a-descriptions-item :label="$t('label.engine')">{{ credentials.engine }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.host')">{{ credentials.host }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.port')">{{ credentials.port }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.database')">{{ credentials.database }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.username')">{{ credentials.username }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.password')">{{ credentials.password }}</a-descriptions-item>
      </a-descriptions>
      <p class="connect-hint">{{ $t('message.dbaas.connect.command') }}</p>
      <div :span="24" class="action-button">
        <a-button @click="markCopied" v-clipboard:copy="connectCommand" type="primary">
          {{ $t('label.copy.connect.command') }}
        </a-button>
        <a-button @click="markCopied" v-clipboard:copy="credentials.password">
          {{ $t('label.copy.password') }}
        </a-button>
        <a-button @click="confirmClose">{{ $t('label.close') }}</a-button>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { Modal } from 'ant-design-vue'
import { postAPI } from '@/api'
import { mixinForm } from '@/utils/mixin'
import {
  buildConnectCommand,
  DBAAS_IDENTIFIER_PATTERN,
  DBAAS_PASSWORD_PATTERN
} from '@/utils/dbaas'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'CreateDatabase',
  mixins: [mixinForm],
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  components: {
    TooltipLabel
  },
  data () {
    return {
      loading: false,
      isSubmitted: false,
      credentials: {},
      dbPasswordCopied: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createDatabase')
  },
  computed: {
    connectCommand () {
      return buildConnectCommand(this.credentials)
    }
  },
  created () {
    this.initForm()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      // Same shape the provisioning scripts accept, so an identifier the
      // backend would reject is caught here instead of after a round trip.
      const identifier = {
        pattern: DBAAS_IDENTIFIER_PATTERN,
        message: this.$t('message.error.database.identifier')
      }
      this.rules = reactive({
        dbname: [{ required: true, message: this.$t('message.error.required.input') }, identifier],
        dbusername: [{ required: true, message: this.$t('message.error.required.input') }, identifier],
        // Optional: an empty field means the backend generates one.
        dbpassword: [{ pattern: DBAAS_PASSWORD_PATTERN, message: this.$t('message.error.database.password') }]
      })
    },
    handleSubmit (e) {
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        this.loading = true
        // Provisioning reads its request from the config drive at boot: if
        // this instance is running, the backend stops it, attaches the
        // request, and starts it again -- the warning above says so up front
        // rather than the instance just going dark mid-submit.
        postAPI('createDatabase', {
          virtualmachineid: this.resource.id,
          dbname: values.dbname,
          dbusername: values.dbusername,
          dbpassword: values.dbpassword
        }).then(json => {
          const dbaas = json.createdatabaseresponse?.dbaas
          if (dbaas) {
            // Also retrievable afterwards via Show Password -- the backend
            // stores every successful create/reset, encrypted.
            this.credentials = dbaas
            this.isSubmitted = true
          } else {
            // Provisioning may well have succeeded on the instance. Closing
            // silently would leave no way to tell, and the retry would fail
            // on a duplicate user with nothing explaining why.
            this.$notification.error({
              message: this.$t('label.create.database'),
              description: this.$t('message.error.database.response'),
              duration: 0
            })
          }
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.$emit('refresh-data')
          this.loading = false
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    markCopied () {
      this.dbPasswordCopied = true
      this.$notification.info({
        message: this.$t('message.success.copy.clipboard'),
        duration: 2
      })
    },
    // Only guards the credentials step: the database password is also stored
    // server-side and recoverable later via Show Password, so this is a
    // courtesy nudge to copy it now, not a last chance.
    confirmClose () {
      if (this.dbPasswordCopied) {
        this.closeAction()
        return
      }
      Modal.confirm({
        title: this.$t('label.close'),
        content: this.$t('message.confirm.close.database.dbpassword'),
        okText: this.$t('label.yes'),
        cancelText: this.$t('label.no'),
        onOk: this.closeAction
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

  .form-banner {
    margin-bottom: 16px;
  }

  // No word-break here: labels wrap at spaces; only the value spans
  // (.connect-command) break-all.
  .credentials {
    margin-top: 16px;
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
</style>

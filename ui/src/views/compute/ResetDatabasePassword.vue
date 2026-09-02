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
      <p v-html="$t('message.desc.reset.database.password')"></p>
      <a-form
        v-ctrl-enter="handleSubmit"
        :ref="formRef"
        :model="form"
        :rules="rules"
        @finish="handleSubmit"
        layout="vertical">
        <a-form-item name="dbusername" ref="dbusername">
          <template #label>
            <tooltip-label :title="$t('label.dbusername')" :tooltip="apiParams.dbusername.description"/>
          </template>
          <a-input
            v-model:value="form.dbusername"
            :placeholder="apiParams.dbusername.description"
            v-focus="true"/>
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
        <a-descriptions-item :label="$t('label.username')">{{ credentials.username }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.password')">{{ credentials.password }}</a-descriptions-item>
      </a-descriptions>
      <div :span="24" class="action-button">
        <a-button @click="notifyCopied" v-clipboard:copy="credentials.password" type="primary">
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
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'ResetDatabasePassword',
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
      passwordCopied: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('resetDatabasePassword')
  },
  created () {
    this.initForm()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        dbusername: [
          { required: true, message: this.$t('message.error.required.input') },
          {
            pattern: /^[A-Za-z][A-Za-z0-9_]{0,31}$/,
            message: this.$t('message.error.database.identifier')
          }
        ]
      })
    },
    handleSubmit (e) {
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        this.loading = true
        postAPI('resetDatabasePassword', {
          virtualmachineid: this.resource.id,
          dbusername: values.dbusername
        }).then(json => {
          const dbaas = json.resetdatabasepasswordresponse?.dbaas
          if (dbaas) {
            // Also retrievable afterwards via Show Password -- the backend
            // stores every successful create/reset, encrypted.
            this.credentials = dbaas
            this.isSubmitted = true
          } else {
            // The password may already have been rotated on the instance, so
            // closing quietly would leave the user locked out with no clue.
            this.$notification.error({
              message: this.$t('label.reset.database.password'),
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
    notifyCopied () {
      this.passwordCopied = true
      this.$notification.info({
        message: this.$t('message.success.copy.clipboard')
      })
    },
    confirmClose () {
      if (this.passwordCopied) {
        this.closeAction()
        return
      }
      Modal.confirm({
        title: this.$t('label.close'),
        content: this.$t('message.confirm.close.database.password'),
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

  .credentials {
    margin-top: 16px;
    word-break: break-all;
  }
</style>

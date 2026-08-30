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
  <div class="oauth-provider-layout" v-ctrl-enter="handleSubmit">
    <a-card :bordered="false">
      <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        @finish="handleSubmit"
        layout="vertical">
        <a-form-item :label="$t('label.provider')" ref="provider" name="provider">
          <a-select
            v-model:value="form.provider"
            v-focus="true"
            :placeholder="apiParams.provider.description"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }">
            <a-select-option v-for="opt in providerOptions" :key="opt" :label="opt">
              {{ opt }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item :label="$t('label.description')" ref="description" name="description">
          <a-input v-model:value="form.description" :placeholder="apiParams.description.description" />
        </a-form-item>
        <a-form-item :label="$t('label.clientid')" ref="clientid" name="clientid">
          <a-input v-model:value="form.clientid" :placeholder="apiParams.clientid.description" />
        </a-form-item>
        <a-form-item :label="$t('label.secretkey')" ref="secretkey" name="secretkey">
          <a-input-password v-model:value="form.secretkey" :placeholder="apiParams.secretkey.description" autocomplete="new-password" />
        </a-form-item>
        <a-form-item :label="$t('label.redirecturi')" ref="redirecturi" name="redirecturi">
          <a-input v-model:value="form.redirecturi" :placeholder="apiParams.redirecturi.description" />
        </a-form-item>
        <a-form-item v-if="form.provider === 'keycloak'" :label="$t('label.authorizeurl')" ref="authorizeurl" name="authorizeurl">
          <a-input v-model:value="form.authorizeurl" :placeholder="apiParams.authorizeurl.description" />
        </a-form-item>
        <a-form-item v-if="form.provider === 'keycloak'" :label="$t('label.tokenurl')" ref="tokenurl" name="tokenurl">
          <a-input v-model:value="form.tokenurl" :placeholder="apiParams.tokenurl.description" />
        </a-form-item>
        <a-form-item :label="$t('label.logo')" ref="logo" name="logo">
          <a-upload-dragger
            accept="image/*"
            :showUploadList="false"
            :beforeUpload="beforeLogoUpload">
            <img v-if="form.logo" :src="form.logo" class="logo-preview" />
            <div v-else>
              <p class="ant-upload-drag-icon"><upload-outlined /></p>
              <p class="ant-upload-text">{{ $t('label.upload.logo') }}</p>
              <p class="ant-upload-hint">{{ $t('label.logo.hint') }}</p>
            </div>
          </a-upload-dragger>
          <a-button v-if="form.logo" danger size="small" style="margin-top: 8px" @click="form.logo = null">
            {{ $t('label.remove.logo') }}
          </a-button>
        </a-form-item>

        <div :span="24" class="action-button">
          <a-button @click="handleClose">{{ $t('label.cancel') }}</a-button>
          <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-card>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { postAPI } from '@/api'
import { UploadOutlined } from '@ant-design/icons-vue'

export default {
  name: 'RegisterOauthProvider',
  components: {
    UploadOutlined
  },
  data () {
    return {
      providerOptions: ['google', 'github', 'keycloak'],
      loading: false
    }
  },
  beforeCreate () {
    this.apiConfig = this.$store.getters.apis.registerOauthProvider || {}
    this.apiParams = {}
    this.apiConfig.params.forEach(param => {
      this.apiParams[param.name] = param
    })
  },
  created () {
    this.initForm()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        provider: 'keycloak',
        description: null,
        clientid: null,
        secretkey: null,
        redirecturi: this.getDefaultRedirectUri(),
        authorizeurl: null,
        tokenurl: null,
        logo: null
      })
      this.rules = reactive({
        provider: [{ required: true, message: this.$t('message.error.select') }],
        description: [{ required: true, message: this.$t('message.error.input') }],
        clientid: [{ required: true, message: this.$t('message.error.input') }],
        secretkey: [{ required: true, message: this.$t('message.error.input') }],
        redirecturi: [{ required: true, message: this.$t('message.error.input') }],
        authorizeurl: [{ required: true, message: this.$t('message.error.input') }],
        tokenurl: [{ required: true, message: this.$t('message.error.input') }]
      })
    },
    getDefaultRedirectUri () {
      const origin = window.location.origin
      const path = window.location.pathname || '/'
      if (path.includes('/client')) {
        return origin + path.substring(0, path.indexOf('/client') + '/client/'.length)
      }
      return origin + '/client/'
    },
    beforeLogoUpload (file) {
      const isImage = file.type && file.type.startsWith('image/')
      if (!isImage) {
        this.$message.error(this.$t('label.logo.invalid.image'))
        return false
      }
      if (file.size > 200 * 1024) {
        this.$message.error(this.$t('label.logo.too.large'))
        return false
      }
      const reader = new FileReader()
      reader.onload = (e) => {
        this.form.logo = e.target.result
      }
      reader.readAsDataURL(file)
      return false
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        const params = {
          provider: values.provider,
          description: values.description,
          clientid: values.clientid,
          secretkey: values.secretkey,
          redirecturi: values.redirecturi
        }
        if (values.provider === 'keycloak') {
          params.authorizeurl = values.authorizeurl
          params.tokenurl = values.tokenurl
        }
        if (values.logo) {
          params.logo = values.logo
        }
        this.loading = true
        postAPI('registerOauthProvider', params).then(response => {
          this.$notification.success({
            message: this.$t('label.register.oauth'),
            description: values.provider
          })
          this.$emit('refresh-data')
          this.handleClose()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    handleClose () {
      this.$emit('close-action')
    }
  }
}
</script>

<style lang="less" scoped>
.oauth-provider-layout {
  width: 85vw;

  @media (min-width: 1000px) {
    width: 640px;
  }
}

.logo-preview {
  max-width: 240px;
  max-height: 120px;
  display: block;
  margin: 0 auto;
}
</style>

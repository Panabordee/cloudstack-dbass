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
  <div class="form-layout" v-ctrl-enter="handleSubmit">
    <a-spin :spinning="loading">
      <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        layout="vertical"
        @finish="handleSubmit">
        <a-form-item name="type" ref="type" :label="$t('label.type')">
          <a-select
            v-model:value="form.type"
            v-focus="true"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option key="GROUP" label="GROUP">GROUP</a-select-option>
            <a-select-option key="OU" label="OU">OU</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="ldapdomain" ref="ldapdomain" :label="$t('label.ldap.group.name')">
          <a-input v-model:value="form.ldapdomain" />
        </a-form-item>
        <a-form-item name="assignment" ref="assignment" :label="$t('label.role.type')">
          <a-radio-group v-model:value="form.assignment">
            <a-radio value="accounttype">{{ $t('label.account.type') }}</a-radio>
            <a-radio value="role">{{ $t('label.role') }}</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="form.assignment === 'accounttype'" name="accounttype" ref="accounttype" :label="$t('label.account.type')">
          <a-select
            v-model:value="form.accounttype"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option key="0" :label="$t('label.user')">{{ $t('label.user') }} (0)</a-select-option>
            <a-select-option key="2" :label="$t('label.domain.admin')">{{ $t('label.domain.admin') }} (2)</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-else name="roleid" ref="roleid" :label="$t('label.role')">
          <a-select
            v-model:value="form.roleid"
            :loading="roleLoading"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option v-for="role in roles" :key="role.id" :label="role.name">
              {{ role.name }} ({{ role.type }})
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="admin" ref="admin" :label="$t('label.admin')">
          <a-input v-model:value="form.admin" :placeholder="$t('label.admin')" />
        </a-form-item>
      </a-form>
      <div class="action-button">
        <a-button @click="handleClose">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-spin>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import store from '@/store'

export default {
  name: 'LinkDomainToLdap',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      loading: false,
      roleLoading: false,
      roles: []
    }
  },
  created () {
    this.initForm()
    this.fetchRoles()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        type: 'GROUP',
        ldapdomain: null,
        assignment: 'accounttype',
        accounttype: '0',
        roleid: null,
        admin: null
      })
      this.rules = reactive({
        type: [{ required: true, message: this.$t('message.error.select') }],
        ldapdomain: [{ required: true, message: this.$t('message.error.required.input') }],
        accounttype: [{ required: true, message: this.$t('message.error.select') }],
        roleid: [{ required: true, message: this.$t('message.error.select') }]
      })
    },
    fetchRoles () {
      this.roleLoading = true
      const params = { state: 'enabled' }
      getAPI('listRoles', params).then(response => {
        let roles = response.listrolesresponse.role || []
        const showPrivileged = ['Admin'].includes(store.getters.userInfo.roletype)
        if (!showPrivileged) {
          roles = roles.filter(role => role.type === 'User' || role.type === 'DomainAdmin')
        }
        this.roles = roles
        if (roles.length > 0) {
          this.form.roleid = roles[0].id
        }
      }).finally(() => {
        this.roleLoading = false
      })
    },
    handleClose () {
      this.$emit('close-action')
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        const params = {
          domainid: this.resource.id,
          type: values.type,
          ldapdomain: values.ldapdomain
        }
        if (values.assignment === 'role') {
          params.roleid = values.roleid
        } else {
          params.accounttype = values.accounttype
        }
        if (values.admin) {
          params.admin = values.admin
        }
        this.loading = true
        postAPI('linkDomainToLdap', params).then(response => {
          const result = response.linkdomaintoldapresponse || {}
          this.$notification.success({
            message: this.$t('label.link.domain.to.ldap'),
            description: `${this.$t('label.link.domain.to.ldap')} ${this.resource.name}`
          })
          if (result.roleid && result.roleid > 0) {
            console.log('LDAP domain link uses role id ' + result.roleid)
          }
          this.$emit('refresh-data')
          this.handleClose()
        }).catch(error => {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: (error.response && error.response.headers && error.response.headers['x-description']) || error.message,
            duration: 0
          })
        }).finally(() => {
          this.loading = false
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    }
  }
}
</script>
<style scoped lang="less">
.form-layout {
  width: 75vw;

  @media (min-width: 700px) {
    width: 40vw;
  }
}
</style>

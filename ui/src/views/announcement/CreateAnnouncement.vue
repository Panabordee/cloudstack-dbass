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
        @finish="handleSubmit"
        layout="vertical">
        <a-form-item name="title" ref="title">
          <template #label>
            <tooltip-label :title="$t('label.announcement.title')" :tooltip="apiParams.title.description" />
          </template>
          <a-input
            v-model:value="form.title"
            :placeholder="apiParams.title.description"
            v-focus="true" />
        </a-form-item>
        <a-form-item name="type" ref="type">
          <template #label>
            <tooltip-label :title="$t('label.announcement.type')" :tooltip="apiParams.type.description" />
          </template>
          <a-radio-group v-model:value="form.type" buttonStyle="solid">
            <a-radio-button v-for="type in types" :key="type.value" :value="type.value">
              <a-badge :color="type.color" :text="type.label" />
            </a-radio-button>
          </a-radio-group>
        </a-form-item>
        <a-form-item name="message" ref="message">
          <template #label>
            <tooltip-label :title="$t('label.announcement.message')" :tooltip="apiParams.message.description" />
          </template>
          <a-textarea
            v-model:value="form.message"
            :placeholder="apiParams.message.description"
            :rows="4" />
          <div class="banner-preview" v-if="form.message">
            <a-alert :type="form.type" :show-icon="true" banner :style="[{ border: borderColor }]">
              <template #message>
                <div class="banner-content" v-html="sanitizedPreview" />
              </template>
            </a-alert>
          </div>
        </a-form-item>
        <a-form-item name="enabled" ref="enabled">
          <template #label>
            <tooltip-label :title="$t('label.enabled')" :tooltip="apiParams.enabled.description" />
          </template>
          <a-switch v-model:checked="form.enabled" />
        </a-form-item>
        <a-row :gutter="12">
          <a-col :md="12" :lg="12">
            <a-form-item name="closable" ref="closable">
              <template #label>
                <tooltip-label :title="$t('label.closable')" :tooltip="apiParams.closable.description" />
              </template>
              <a-switch v-model:checked="form.closable" />
            </a-form-item>
          </a-col>
          <a-col :md="12" :lg="12">
            <a-form-item name="persistdismissal" ref="persistdismissal">
              <template #label>
                <tooltip-label :title="$t('label.persistdismissal')" :tooltip="apiParams.persistdismissal.description" />
              </template>
              <a-switch v-model:checked="form.persistdismissal" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item name="useschedule" ref="useschedule">
          <template #label>
            <tooltip-label :title="$t('label.schedule')" :tooltip="$t('message.announcement.schedule.description')" />
          </template>
          <a-switch v-model:checked="form.useschedule" @change="handleScheduleChange" />
        </a-form-item>
        <template v-if="form.useschedule">
          <a-form-item name="startdate" ref="startdate">
            <template #label>
              <tooltip-label :title="$t('label.start.date.and.time')" :tooltip="apiParams.startdate.description" />
            </template>
            <a-date-picker
              v-model:value="form.startdate"
              show-time
              style="width: 100%"
              :placeholder="$t('message.select.start.date.and.time')" />
            <a-button class="preset-button" size="small" @click="setStartDate('now')">{{ $t('label.now') }}</a-button>
          </a-form-item>
          <a-form-item name="enddate" ref="enddate">
            <template #label>
              <tooltip-label :title="$t('label.end.date.and.time')" :tooltip="apiParams.enddate.description" />
            </template>
            <a-date-picker
              v-model:value="form.enddate"
              show-time
              style="width: 100%"
              :placeholder="$t('message.select.end.date.and.time')" />
            <a-button class="preset-button" size="small" @click="setEndDate(1)">+1h</a-button>
            <a-button class="preset-button" size="small" @click="setEndDate(24)">+24h</a-button>
            <a-button class="preset-button" size="small" @click="setEndDate(24 * 7)">+1w</a-button>
          </a-form-item>
        </template>
        <a-form-item name="priority" ref="priority">
          <template #label>
            <tooltip-label :title="$t('label.priority')" :tooltip="apiParams.priority.description" />
          </template>
          <a-input-number v-model:value="form.priority" :min="0" style="width: 100%" />
        </a-form-item>
        <div :span="24" class="action-button">
          <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import dayjs from 'dayjs'
import DOMPurify from 'dompurify'
import { postAPI } from '@/api'
import { mixinForm } from '@/utils/mixin'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'CreateAnnouncement',
  mixins: [mixinForm],
  components: {
    TooltipLabel
  },
  data () {
    return {
      loading: false,
      types: [
        { value: 'info', label: 'Info', color: '#1890ff' },
        { value: 'success', label: 'Success', color: '#52c41a' },
        { value: 'warning', label: 'Warning', color: '#faad14' },
        { value: 'error', label: 'Error', color: '#f5222d' }
      ]
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createAnnouncement')
  },
  created () {
    this.initForm()
  },
  computed: {
    sanitizedPreview () {
      return DOMPurify.sanitize(this.form.message || '', { ALLOWED_TAGS: ['b', 'strong', 'em', 'i', 'a', 'br'] })
    },
    borderColor () {
      const colorMap = {
        error: '#ffa39e',
        warning: '#ffe58f',
        success: '#b7eb8f',
        info: '#b3cde3'
      }
      const color = colorMap[this.form.type]
      return color ? `1px solid ${color}` : '0px'
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        title: null,
        type: 'info',
        message: null,
        enabled: true,
        closable: true,
        persistdismissal: true,
        useschedule: false,
        startdate: null,
        enddate: null,
        priority: 0
      })
      this.rules = reactive({
        title: [{ required: true, message: this.$t('message.error.announcement.title') }],
        message: [{ required: true, message: this.$t('message.error.announcement.message') }]
      })
    },
    handleScheduleChange () {
      if (!this.form.useschedule) {
        this.form.startdate = null
        this.form.enddate = null
      }
    },
    setStartDate (when) {
      if (when === 'now') {
        this.form.startdate = dayjs()
      }
    },
    setEndDate (hours) {
      this.form.enddate = dayjs().add(hours, 'hour')
    },
    formatDate (value) {
      return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : null
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const formRaw = toRaw(this.form)
        const values = this.handleRemoveFields(formRaw)
        const params = {
          title: values.title,
          message: values.message,
          type: values.type,
          enabled: values.enabled,
          closable: values.closable,
          persistdismissal: values.persistdismissal,
          priority: values.priority
        }
        if (values.useschedule) {
          if (values.startdate) {
            params.startdate = this.formatDate(values.startdate)
          }
          if (values.enddate) {
            params.enddate = this.formatDate(values.enddate)
          }
        }
        this.loading = true
        postAPI('createAnnouncement', params).then(json => {
          this.$emit('refresh-data')
          this.$notification.success({
            message: this.$t('label.create.announcement'),
            description: `${this.$t('message.success.create.announcement')} ${params.title}`
          })
          this.closeAction()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
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

    @media (min-width: 700px) {
      width: 650px;
    }
  }

  .banner-preview {
    margin-top: 10px;
  }

  .preset-button {
    margin: 8px 8px 0 0;
  }
</style>

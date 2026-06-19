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
  <div class="quota-pie-row">
    <div class="quota-pie-icon" :style="{ color: color }">
      <render-icon :icon="icon" />
    </div>
    <div class="quota-pie-details">
      <strong class="quota-pie-title">{{ title }}</strong>
      <span class="quota-pie-value">{{ value }}</span>
      <small class="quota-pie-summary">{{ summary }}</small>
    </div>
    <a-progress
      class="quota-pie-chart"
      type="circle"
      :percent="safePercent"
      :width="44"
      :stroke-width="12"
      :stroke-color="color"
      :format="formatPercent" />
  </div>
</template>

<script>
export default {
  name: 'QuotaPie',
  props: {
    title: {
      type: String,
      required: true
    },
    value: {
      type: String,
      default: ''
    },
    summary: {
      type: String,
      default: ''
    },
    icon: {
      type: String,
      default: 'pie-chart-outlined'
    },
    percent: {
      type: Number,
      default: 0
    },
    unlimited: {
      type: Boolean,
      default: false
    },
    color: {
      type: String,
      default: '#52c41a'
    }
  },
  computed: {
    safePercent () {
      if (this.unlimited || !Number.isFinite(this.percent)) {
        return 0
      }
      return Math.min(100, Math.max(0, this.percent))
    }
  },
  methods: {
    formatPercent () {
      return this.unlimited ? '∞' : `${this.safePercent.toFixed(0)}%`
    }
  }
}
</script>

<style lang="less" scoped>
.quota-pie-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  padding: 4px 0;
}

.quota-pie-icon {
  display: flex;
  flex: 0 0 22px;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: currentColor;
  font-size: 13px;

  :deep(svg) {
    color: #fff;
  }
}

.quota-pie-details {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-width: 0;
  font-size: 12px;
  line-height: 1.15;
}

.quota-pie-title,
.quota-pie-value,
.quota-pie-summary {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quota-pie-value {
  margin-top: 1px;
}

.quota-pie-summary {
  margin-top: 1px;
  font-size: 10px;
  opacity: 0.6;
}

.quota-pie-chart {
  flex: 0 0 44px;
}

:deep(.ant-progress-circle .ant-progress-text) {
  font-size: 11px;
  font-weight: 600;
}
</style>

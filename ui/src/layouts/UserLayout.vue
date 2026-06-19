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
  <div id="userLayout" :class="['user-layout', device]">
    <a-button
      v-if="showClear"
      type="default"
      size="small"
      class="button-clear-notification"
      @click="onClearNotification">{{ $t('label.clear.notification') }}</a-button>
    <div class="user-layout-container">
      <route-view></route-view>
    </div>
  </div>
</template>

<script>
import RouteView from '@/layouts/RouteView'
import { mixinDevice } from '@/utils/mixin.js'
import notification from 'ant-design-vue/es/notification'

export default {
  name: 'UserLayout',
  components: { RouteView },
  mixins: [mixinDevice],
  data () {
    return {
      showClear: false
    }
  },
  watch: {
    '$store.getters.darkMode' (darkMode) {
      if (darkMode) {
        document.body.classList.add('dark-mode')
      } else {
        document.body.classList.remove('dark-mode')
      }
    },
    '$store.getters.countNotify' (countNotify) {
      this.showClear = false
      if (countNotify && countNotify > 0) {
        this.showClear = true
      }
    }
  },
  mounted () {
    document.body.classList.add('userLayout')
    const layoutMode = this.$config.theme['@layout-mode'] || 'light'
    this.$store.dispatch('SetDarkMode', (layoutMode === 'dark'))
    if (layoutMode === 'dark') {
      document.body.classList.add('dark-mode')
    }
    const countNotify = this.$store.getters.countNotify
    this.showClear = false
    if (countNotify && countNotify > 0) {
      this.showClear = true
    }
  },
  beforeUnmount () {
    document.body.classList.remove('userLayout')
    document.body.classList.remove('dark-mode')
  },
  methods: {
    onClearNotification () {
      notification.destroy()
      this.$store.commit('SET_COUNT_NOTIFY', 0)
    }
  }
}
</script>

<style lang="less" scoped>
.user-layout {
  min-height: 100vh;

  &-container {
    width: 100%;
  }
}
</style>

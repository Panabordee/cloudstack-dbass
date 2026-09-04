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
import { shallowRef, defineAsyncComponent } from 'vue'

export default {
  name: 'announcement',
  title: 'label.announcements',
  icon: 'ReadOutlined',
  permission: ['listAnnouncements', 'createAnnouncement'],
  columns: ['title', 'type', 'enabled', 'priority', 'startdate', 'enddate', 'created'],
  details: ['id', 'title', 'message', 'type', 'enabled', 'closable', 'persistdismissal', 'priority', 'startdate', 'enddate', 'created'],
  searchFilters: ['type'],
  actions: [
    {
      api: 'createAnnouncement',
      icon: 'plus-outlined',
      label: 'label.create.announcement',
      listView: true,
      popup: true,
      component: shallowRef(defineAsyncComponent(() => import('@/views/announcement/CreateAnnouncement.vue')))
    },
    {
      api: 'updateAnnouncement',
      icon: 'edit-outlined',
      label: 'label.edit.announcement',
      dataView: true,
      popup: true,
      component: shallowRef(defineAsyncComponent(() => import('@/views/announcement/EditAnnouncement.vue')))
    },
    {
      api: 'updateAnnouncement',
      icon: 'play-circle-outlined',
      label: 'label.enable.announcement',
      message: 'message.confirm.enable.announcement',
      dataView: true,
      groupAction: true,
      popup: true,
      defaultArgs: { enabled: true },
      groupMap: (selection) => { return selection.map(x => { return { id: x } }) },
      show: (record) => { return !record.enabled }
    },
    {
      api: 'updateAnnouncement',
      icon: 'pause-circle-outlined',
      label: 'label.disable.announcement',
      message: 'message.confirm.disable.announcement',
      dataView: true,
      groupAction: true,
      popup: true,
      defaultArgs: { enabled: false },
      groupMap: (selection) => { return selection.map(x => { return { id: x } }) },
      show: (record) => { return !!record.enabled }
    },
    {
      api: 'deleteAnnouncement',
      icon: 'delete-outlined',
      label: 'label.delete.announcement',
      message: 'message.confirm.delete.announcement',
      dataView: true,
      groupAction: true,
      popup: true,
      groupMap: (selection) => { return selection.map(x => { return { id: x } }) }
    }
  ]
}

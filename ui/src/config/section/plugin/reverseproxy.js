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

export default {
  name: 'reverseproxy',
  title: 'label.reverse.proxy',
  icon: 'global-outlined',
  permission: ['listReverseProxyHosts'],
  columns: [
    'fqdn',
    'virtualmachinename',
    {
      backend: (record) => record.protocol && record.ipaddress ? record.protocol + '://' + record.ipaddress + ':' + record.port : ''
    },
    'account',
    'domain',
    'state',
    'created'
  ],
  details: ['name', 'fqdn', 'url', 'virtualmachinename', 'ipaddress', 'protocol', 'port', 'state', 'account', 'domain', 'created'],
  searchFilters: ['keyword'],
  actions: [
    {
      api: 'deleteInstanceProxy',
      icon: 'delete-outlined',
      label: 'label.remove.instance.proxy',
      message: 'message.remove.instance.proxy',
      dataView: true,
      show: (record, store) => { return 'deleteInstanceProxy' in store.apis }
    }
  ]
}

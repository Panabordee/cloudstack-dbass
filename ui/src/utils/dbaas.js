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

// Turns a DBaaS credential response (engine/host/port/database/username/
// password) into a single copy-paste connect command. Passwords are generated
// alphanumeric-only by the provisioning scripts, so shell quoting is a
// non-issue; keep that constraint in mind if the generator ever changes.
export function buildConnectCommand (credentials) {
  if (!credentials || !credentials.host || !credentials.username || !credentials.password) {
    return ''
  }
  const engine = (credentials.engine || '').toLowerCase()
  const { host, port, username, password, database } = credentials
  if (engine.includes('postgres')) {
    return `psql "postgresql://${username}:${password}@${host}:${port}/${database}"`
  }
  if (engine.includes('mongo')) {
    return `mongosh "mongodb://${username}:${password}@${host}:${port}/${database || 'admin'}"`
  }
  // mysql and mariadb share the same client syntax
  return `mysql -h ${host} -P ${port || 3306} -u ${username} -p'${password}'${database ? ' ' + database : ''}`
}

// navigator.clipboard only exists in secure contexts; the management UI is
// commonly served over plain http, so fall back to the execCommand textarea
// trick. Returns true when the copy most likely succeeded.
export function copyTextToClipboard (text) {
  if (!text) {
    return false
  }
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text)
    return true
  }
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch (e) {
    ok = false
  }
  document.body.removeChild(textarea)
  return ok
}

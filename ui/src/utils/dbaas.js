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

// The engines map in the extension's config.json -- exposed to the UI through
// the listDbaasEngines API -- is the source of truth for which templates are
// DBaaS engines. This prefix remains in only two places: the static section
// config (compute.js) whose show() hooks cannot await an API call, and the
// fallback used when the management server runs an older plugin without
// listDbaasEngines. New engines SHOULD be named with this prefix so those
// static spots stay correct; see BUILD-DBAAS.md.
export const DBAAS_TEMPLATE_PREFIX = 'dbaas-'

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
    // resetDatabasePassword/getDatabasePassword responses carry no database
    // field, so fall back to the server's own default database.
    return `psql "postgresql://${username}:${password}@${host}:${port}/${database || 'postgres'}"`
  }
  if (engine.includes('mongo')) {
    return `mongosh "mongodb://${username}:${password}@${host}:${port}/${database || 'admin'}"`
  }
  // mysql and mariadb share the same client syntax
  return `mysql -h ${host} -P ${port || 3306} -u ${username} -p'${password}'${database ? ' ' + database : ''}`
}

// navigator.clipboard only exists in secure contexts; the management UI is
// commonly served over plain http, so fall back to the execCommand textarea
// trick. Async so the secure-context path can await the write: resolving true
// before the promise settles would toast "copied" even on a rejected write.
export async function copyTextToClipboard (text) {
  if (!text) {
    return false
  }
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch (e) {
      return false
    }
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

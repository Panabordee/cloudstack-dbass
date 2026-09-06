# CONSOLE-REPORT — 2026-09-06 (overnight session)

**สรุปบรรทัดเดียว (§12.5):** ยังไม่สามารถยืนยันได้ว่า tenant จะ browse/query/create
table ได้จริงบน network ที่ VR ล่ม เพราะทุกการทดสอบ live ต้อง rebuild template +
deploy ซึ่งเป็น host work ที่ห้ามทำใน session นี้ — **โค้ดทั้งหมดของ C0–C4
ถูกเขียนและผ่าน build ฝั่ง repo แล้ว แต่ยังไม่มีการพิสูจน์บนระบบจริงแม้แต่ชั้นเดียว**
(drop table ยัง disabled ตามดีไซน์)

---

## 1. สิ่งที่ทำแล้ว (repo — ผ่าน compile/checkstyle/syntax ทั้งหมด)

### Commit range

`90bce19c68` (F2 volume-service) → `4737145e1f` (docs) → `2cc6327944`
(console C1–C4) — ทุก commit push ที่ `v2` และ `ui` แล้ว

### สถานะราย phase

| Phase | สถานะ | หมายเหตุ |
| --- | --- | --- |
| CF-1 | ✅ ทำแล้วรอบก่อน (429 พิสูจน์บนสายแล้ว 60×403→10×429) | HEAD มี sendErrorQuietly ครบ — ยืนยันโดยอ่านไฟล์ |
| CF-2 | ✅ ทำแล้วรอบก่อน (commit `90bce19c68` — ลบผ่าน VolumeApiService) | HEAD ยืนยัน |
| CF-3 | ✅ โค้ดครบ (repo) | engine scripts × 4 + roles.json + firstboot bootstrap + db_role column + read path |
| C1 | ✅ โค้ดครบ (repo) — ยังไม่ยืนยัน live | schema 3 ตาราง, agent long-poll/report cmds, sweeper, agent script + units |
| C2 | ✅ โค้ดครบ (repo) — ยังไม่ยืนยัน live | 3 cmds + Tables tab (DbaasConsole.vue) |
| C3 | ✅ โค้ดครบ (repo) — ยังไม่ยืนยัน live | runDbaasQuery + write gate + SQL tab |
| C4 | ✅ โค้ดครบ (repo) — ยังไม่ยืนยัน live | DDL cmds + type allowlist จาก config.json + drop gate |
| C5 | ❌ ไม่ได้เริ่ม | `JOB_PASSWORD_RESET` constant มีแล้ว; `resetDatabasePassword` ยัง throw เหมือนเดิม |

### command → file:line (validate + execute)

| command | file | execute() | validation |
| --- | --- | --- | --- |
| listDbaasTables | ListDbaasTablesCmd.java | (base execute) | base gate เท่านั้น |
| describeDbaasTable | DescribeDbaasTableCmd.java | (base) | `jobPayload()` — validateIdentifier(table) |
| previewDbaasTable | PreviewDbaasTableCmd.java | (base) | `jobPayload()` — identifier + clamp limit |
| createDbaasTable | CreateDbaasTableCmd.java | (base) | `jobPayload()` — identifier ×N + type allowlist + default charset |
| dropDbaasTable | DropDbaasTableCmd.java | (base) | `jobPayload()` — drop gate + confirm == table |
| addDbaasColumn | AddDbaasColumnCmd.java | (base) | `jobPayload()` — identifier ×2 + type allowlist |
| dropDbaasColumn | DropDbaasColumnCmd.java | (base) | `jobPayload()` — identifier ×2 |
| createDbaasIndex | CreateDbaasIndexCmd.java | (base) | `jobPayload()` — identifier ×(2+N) |
| dropDbaasIndex | DropDbaasIndexCmd.java | (base) | `jobPayload()` — identifier ×2 |
| runDbaasQuery | RunDbaasQueryCmd.java | (base) | `jobPayload()` — write gate + sql non-empty |
| getDbaasJobResult | GetDbaasJobResultCmd.java | :execute | getEntityOwnerId = job account; delete-on-read ใน manager |
| getDbaasAgentJob | GetDbaasAgentJobCmd.java | authenticate (:73) | token hash → 403; long-poll ผ่าน manager |
| reportDbaasJobResult | ReportDbaasJobResultCmd.java | authenticate (:60) | token + dispatched-state → 403 เมื่อไม่ตรง |

จุด dispatch ของ agent commands ทั้งหมดอ่าน manager จาก static holder
(`DbaasManagerImpl.getRunningManager()`) — กลไกที่ FIX-1 พิสูจน์แล้ว — ไม่มี
`@Inject` บน cmd ที่ถูกสร้างด้วย `newInstance()`

### Schema (จาก `db/schema-dbaas-console.sql` — ยังไม่ได้รันบน DB จริง)

- `dbaas_agent_tokens` (vm_id unique, token_hash char(64), rotated_at, last_seen_at)
- `dbaas_jobs` (uuid unique, vm_id, account_id, type, db_role, payload text
  encrypted, state, expires_at, row_count, truncated, error varchar(1024))
- `dbaas_job_results` (job_id pk, result mediumtext encrypted) — แยกตารางเพื่อ
  delete-on-read เป็น statement เดียว

### Config keys (ทั้ง 10 — register ใน getConfigKeys แล้ว)

`dbaas.console.enabled=false`, `dbaas.console.row.limit=1000`,
`dbaas.console.bytes.limit=1048576`, `dbaas.console.statement.timeout=30`,
`dbaas.console.write.enabled=false`, `dbaas.console.drop.enabled=false`,
`dbaas.agent.longpoll.seconds=25`, `dbaas.agent.token.rotate.days=7`,
`dbaas.job.ttl=120`, `dbaas.job.result.ttl=300` — **4 flag ทั้งหมด false**

## 2. Timeline (โดยประมาณ)

- 18:0x — อ่าน spec/roadmap/audit, วางแผนไฟล์
- 18:1x — CF-3: engine scripts ×4 + firstboot roles.json + db_role schema/ALTER
- 18:2x — C1: console schema + ConfigKeys + storeCredential(db_role) + Stage C methods
- 18:3x — C2–C4 cmds × 12 + import fixer + compile/checkstyle ผ่าน
- 19:0x — DbaasConsole.vue + wire DatabaseInstances + en.json ×17
- 19:1x — commit `2cc6327944` + plugin -am install (SUCCESS) + full build (user cancel)
- 19:2x–19:4x — F2 บนสาย + full build default profile (SUCCESS 2:12) + FIXES-REPORT-ROUND2
- 19:5x — เริ่ม overnight: อ่าน spec ซ้ำ, เขียน agent script + units + UI + report

## 3. Decisions log (ตัดสินใจเอง + เหตุผล)

1. **marker ตอน createDatabase แทนตอน deploy** (FIX-2) — ตอน deploy
   startvm=false ยัง bind ไม่ได้ นั่นคือบั๊กต้นฉบับ; หลัง first start attach
   แล้ว lookup ได้แน่นอน
2. **403 ผ่าน `sendError`** — `HttpUtils.writeHttpResponse` เขียน status ทับ
   `resp.setStatus()` เสมอ; `sendError` commit response ก่อน และ core มี
   catch IllegalStateException รออยู่; body เป็น error page ของ Jetty
3. **`_ro` password generate ฝั่ง MS** แล้วส่งเข้า request ให้ engine script
   สร้างตาม — ทำให้ MS เก็บ credential ทั้งสอง role ได้ (Show Password ต่อ role)
4. **agent ใช้ python libs (pymysql/psycopg2/pymongo)** ตามแผน — ระบุเป็น
   package ที่ต้องติดตั้งตอน template rebuild; ถ้า lib หาย agent รายงาน
   job failed ชัดเจน
5. **grace period 24h เป็นค่าคงที่** ไม่ใช่ ConfigKey — ลด config surface;
   เปลี่ยนเป็น ConfigKey ได้ทีหลังถ้าต้องการ
6. **roles.json เขียนโดย firstboot** (ไม่ใช่ engine script) — จุดเดียว
   ทุก engine, จาก request เดียวกับที่ MS เก็บ credential
7. **รับ statement จาก MS** (server-built) — agent ไม่รับ SQL จาก payload
   ของ DDL commands เลย ยกเว้น runDbaasQuery ซึ่งเป็นข้อยกเว้นตามแผน

## 4. Blockers (สิ่งที่หยุดเพราะขาดสิทธิ์/เหตุผลอยู่นอก repo)

| blocker | error/สาเหตุ | ต้องมีอะไรถึงแก้ | บล็อกอะไรต่อ |
| --- | --- | --- | --- |
| template rebuild (Step 3.5 + agent + ro role) | ห้ามแตะ image/storage | รัน RUNBOOK Step 3.5 รอบใหม่ (ไฟล์: firstboot.sh, report-retry.sh+unit, agent/, engine scripts) + ติดตั้ง python libs ใน image | การทดสอบ live ทั้งหมดของ C1–C4 |
| full server build ของโค้ดสุดท้าย | user สั่งหยุด build (ประหยัดดิสก์ / เหลือ 2.2G) | รัน `mvn -T2 -DskipTests install` อีกครั้ง | การยืนยันว่า console code ไม่พัง server build |
| FIX-2 acceptance รอบเต็ม | ต้อง deploy พร้อม data disk + destroy | deploy 1 ตัวพร้อม diskoffering แล้ว destroy ตามแผน | ยืนยัน FIX-2 ขั้น 2–3 บนระบบจริง |
| `-Dnoredist` ใช้ไม่ได้ | VMware SDK jars เป็น operator-provided (404 ทุก repo) | ผู้ดูแลต้องจัดหา jar ตาม deps/install-non-oss.sh | เฉพาะโมดูล vmware (ไม่เกี่ยว DBaaS) |

## 5. Build evidence

- **plugin -am install** (รวมทุก dep ที่แก้): `BUILD SUCCESS`, Total time 47s
  (ก่อนหน้า: compile + checkstyle ผ่านหลังแก้ import ครบ — ตรวจด้วย
  `mvn compile checkstyle:check -o`, empty = pass)
- **full server build ของโค้ดสุดท้าย: ยังไม่รัน** (user สั่งหยุด — เหลือ 2.2G
  บน /) — full build ล่าสุดที่ SUCCESS คือรอบ 18:33 ซึ่ง**ก่อน** console commit
- `bash -n`: ทุก .sh ใน provisioning + agent ผ่าน
- `python3 -m py_compile`: dbaas_agent.py ผ่าน
- `node --check`: DatabaseInstances.vue + DbaasConsole.vue script blocks ผ่าน
- `npm ci && npm run build`: **ไม่ได้รัน** — พื้นที่ / เหลือ 2.2G ไม่พอให้
  node_modules/dist เขียน และเป็น build ที่ user สั่งงด

## 6. สิ่งที่คนต้องทำต่อ (เช้าวันถัดไป)

1. **ตัดสินใจดิสก์**: / เหลือ ~2.2G — ถ้าจะ build ต้องเคลียร์ก่อน (ห้ามลบ
   DATA-73 / tplbackup ถ้ายังต้องการ rollback)
2. **repatch templates ทั้งหมด** (Step 3.5 รอบใหม่): firstboot.sh,
   report-retry unit, agent/ (dbaas_agent.py + service + env script),
   engine scripts ×4 (ro role), fallback.network — แล้วติดตั้ง
   python3-pymysql / python3-psycopg2 / python3-pymongo ลงใน image
3. **deploy VM ทดสอบ 1 ตัว** จาก dbaas-mariadb-v2 พร้อม data disk →
   createDatabase → ตรวจว่า volume โดน tag (`volume_details`) และ
   agent.json/roles.json ถูกสร้างใน guest
4. **รัน test matrix §11** — ทุกข้อมี "not run" อยู่ในรายงานนี้
5. **full server build ของโค้ดสุดท้าย** หลังเคลียร์ดิสก์

## 7. Untested claims (เชื่อว่าใช้ได้ แต่ยังพิสูจน์ไม่ได้)

- long-poll คืน job ภายใน ~1s เมื่อมี job pending (โค้ด poll ทุก 500ms)
- token rotation เขียน hash ใหม่และ agent ใช้ token ใหม่ได้จริง
- delete-on-read ลบ result row ถาวร (SQL ตรงจากโค้ด ยังไม่รันบน DB)
- DBEncryptionUtil.encrypt ทำงานกับ payload/result ของ console เหมือนกับ
  credential เดิม (ใช้ path เดียวกัน)
- 429/503 รอดจาก servlet override เหมือน 403 (sendError เดียวกัน — 403
  พิสูจน์แล้ว, 429 พิสูจน์แล้ว, 503 ยังไม่พิสูจน์)
- engine scripts สร้าง _ro ได้จริงบน image ที่ rebuild แล้ว
- agent เชื่อมต่อด้วย roles.json และรายงานผลได้จริงเมื่อ network ตื่น

## 8. DATA-73 / ความปลอดภัย

- `DATA-73`: Ready, instance_id NULL, **ไม่ถูกแตะ** — ไม่มี marker จึงไม่มี
  ทางเข้าเงื่อนไข sweeper + flag ยัง false
- log hygiene ตามดีไซน์: job log line = uuid/type/account/role/row count
  เท่านั้น (`console job … created` / `result recorded`) — ไม่มี SQL text,
  payload หรือ result ใด ๆ ผ่าน log (ตรวจ grep จริง: job log lines ว่างเปล่า
  เพราะยังไม่มี job ถูกสร้างบนระบบจริง)
- ไม่มีการเขียน DB นอก repo (เฉพาะ SELECT ระหว่าง audit), ไม่มี host state
  เปลี่ยนใน session นี้

## 9. สิ่งที่ commit แล้ว (git)

```
2cc6327944 feat(dbaas): console transport and command surface (C1-C4)
(ตามด้วย FIXES-REPORT-ROUND2 commit ถัดไป — push ที่ v2/ui แล้ว)
```

# รายงาน Audit — DBaaS v2 (2026-09-05)

ตรวจโค้ด branch `panabordee/dbaas-v2` (squash ใหม่ `9e98d0f2c2`) ของ repo
`Panabordee/cloudstack-dbass` เทียบกับ `PLAN.md` และ `AUDIT-v2.md` เดิม
ครอบคลุม backend (`plugins/integrations/dbaas`), provisioning scripts
(`extensions/dbaas`), UI (`ui/src/views/compute` + `utils/dbaas.js`) และ schema

---

## 1. สรุปผล (TL;DR)

- **ดีไซน์และโครงสร้างแข็งแรง** — config-drive path, one-time report token
  (SHA-256 hashed, single-use, TTL), sweeper และ ACL ผ่าน `getEntityOwnerId`
  ทำถูกต้องตามแผน โค้ด compile ผ่าน (ตามบันทึกเดิม)
- **แต่ยังผ่าน acceptance ตัวเองไม่ได้**: ปัญหา P0 และ P1 ทั้ง 4 ข้อจาก
  `AUDIT-v2.md` เดิม **ยังไม่ถูกแก้เลยแม้แต่ข้อเดียว** (ตรวจยืนยันที่โค้ดจริง
  พร้อมไฟล์:บรรทัด ด้านล่าง) และ **ยังไม่เคยมีการ deploy instance จริงจาก
  template ใหม่เลย** — ทุก claim เรื่อง "end-to-end ได้" ยังเป็นระดับดีไซน์
- งานตาม PLAN.md เหลือ Phase B (ส่วน acceptance), Phase D เกือบทั้งหมด
  (in-VM agent, engines อื่นๆ) และ Phase E ทั้งเฟส

## 2. งาน Git ที่ทำใน session นี้

| รายการ | ผล |
| --- | --- |
| branch ใหม่บน GitHub | `backup` = ประวัติเดิมครบ 13 commit (มี claude trailer) ที่ `1a9112e8a1` |
| | `v2` และ `ui` = ประวัติ clean, squash 13 commit → **1 commit** `9e98d0f2c2` (62 ไฟล์, +5,821 บรรทัด) บนฐาน `ab24f2d774` เดิม, author/committer = `SnowFlex <68010697@kmitl.ac.th>` |
| ตรวจสอบหลัง squash | `git diff v2 backup` **ว่าง** (tree ตรงกันเป๊ะ), grep คำว่า claude ใน message ของ v2/ui = **0** |
| branch เก่าที่ลบจาก GitHub | `panabordee/dbaas-v2`, `panabordee/dbaas-full`, `panabordee/dbaas-ui`, `panabordee/dbaas-v1-archive` |
| คงเหลือบน GitHub | `main`, `backup`, `ui`, `v2` |
| local | `panabordee/dbaas-v2` reset ไปที่ commit clean แล้ว; `.git` แก้ ownership กลับเป็น `nacl` เรียบร้อย (ตอนแรก fetch ด้วย root ทำให้ไฟล์เป็นของ root) |

หมายเหตุ:
- 4 commit ในฐาน upstream (nacl/4.23 — โค้ดของคนอื่น มี Claude trailer)
  **ไม่ได้แตะ** ตามที่ตกลง — เป็นประวัติร่วมกับ origin และ myfork/main
- โค้ด v1 ยังกู้ได้จาก `~/cloudstack-ui-src` (local branches `dbaas-full`,
  `dbaas-ui`, `dbaas-v1-archive`) และจาก branch `backup` สำหรับงาน v2

## 3. ผล audit โค้ด

### 3.1 ปัญหาเดิมจาก AUDIT-v2.md — ยืนยันว่ายังไม่ถูกแก้

| # | ระดับ | ปัญหา | หลักฐานในโค้ดปัจจุบัน |
| --- | --- | --- | --- |
| P0-1 | บล็อก end-to-end | Template `dbaas-mysql-v2` ไม่อยู่ใน engines map → `listDbaasEngines` ไม่ยอก → wizard กรองตก → ไม่มี port, ไม่มี row actions | `extensions/dbaas/config.example.json:3-24` มีแค่ `dbaas-mysql/mariadb/postgresql/mongodb`; ตัวกรองที่ `CreateDatabaseInstance.vue:318-321`; `engineConfigForVm()` ที่ `DbaasManagerImpl.java:606-623` |
| P1-1 | P1 | ไม่มีการรอ mysqld พร้อมก่อน provision — `runcmd` ยิง `firstboot.sh` ทันที, `mysql.sh` บรรทัดแรกๆ query socket เลย; ถ้าแพ้ race → ล้มเหลวถาวร (cloud-init runcmd รันครั้งเดียว) | `DbaasManagerImpl.buildUserData():196-204` (runcmd ไม่มี wait); `mysql.sh:22` (query แรก); `firstboot.sh` ทั้งไฟล์ไม่มี readiness loop |
| P1-2 | P1 | `createDatabase` stop Running instance แล้วขั้นถัดไป (state re-check / `updateVirtualMachine` / `startVirtualMachine`) ตัวไหน throw → **ไม่ start คืนเลย** = outage ฟรี; state re-read หลัง stop ยังเสี่ยงอ่านค่า stale | `DbaasManagerImpl.java:408-422` (stop + re-check), `437-442`, `467-472` — ไม่มี try/catch ครอบ restart |
| P1-3 | P1 | `status_message` varchar(1024) แต่ message ที่รายงานไม่มีการ truncate ทั้ง 2 ฝั่ง → พอ engine fail จริง (output ยาว) UPDATE fail → ค้าง `pending` ถาวร | schema `schema-dbaas-credentials.sql:45`; server `DbaasManagerImpl.applyProvisioningReport():722-729` (bind ตรงๆ); client `firstboot.sh:130-132` (โพสต์ stdout+stderr ทั้งยวง) |
| P1-4 | P1 | ไม่มีการเช็คว่า template รองรับ config-drive — wizard กรองด้วย engines map ซึ่ง **v1 template (`dbaas-mysql`) ก็อยู่ใน map นั้น** → deploy ผ่าน, attach user data ที่ไม่มีใครอ่าน, credential ค้าง pending ไม่มี error | `createDatabase()` ไม่ inspect template เลย (`DbaasManagerImpl.java:423-424` ใช้แค่ชื่อ); `firstboot.sh:100-104` จะ fail ที่หน้า instance เท่านั้น |

### 3.2 ปัญหาเล็ก (P2) จาก AUDIT-v2.md — ยืนยันยังอยู่ครบ

1. Javadoc ของ `createDatabase` บอกว่า caller เห็น warning ใน "response's
   message" แต่ `DbaasResponse` **ไม่มี field message** (`DbaasManagerImpl.java:394-396`)
2. `setHost(primaryIpAddress(vm))` ใช้ VM object ก่อน start → IP อาจ null
   (`DbaasManagerImpl.java:476`)
3. Token expiry คำนวณด้วย `System.currentTimeMillis()` แต่เช็คด้วย `NOW()`
   ของ DB → clock skew ขยับ TTL จริง (`:450-451` vs `:724`)
4. Show Password ยอมแพ้หลัง 12 ครั้ง (~2 นาที) สั้นไปสำหรับ boot เต็มรอบ
   (`ShowDatabasePassword.vue:111`)
5. `message.dbaas.waiting.engine` สื่อผิดจังหวะ (ยังไม่ได้สั่ง engine อะไรเลย)
6. **ไม่มี rate limiting** บน `reportDbaasProvisioningResult` (endpoint
   unauthenticated — ต้องทำก่อนเปิด internet-facing; token กัน brute-force
   ได้แต่กัน flood ไม่ได้)
7. Dead code: `DatabaseInstances.vue:97/109/129/215` ยัง import/render/route
   modal `ResetDatabasePassword` ที่ `rowActions()` ไม่มีทาง emit แล้ว

### 3.3 ปัญหาใหม่ที่พบเพิ่ม (ไม่ซ้ำกับ audit เดิม)

| # | ระดับ | ปัญหา | หลักฐาน |
| --- | --- | --- | --- |
| N-1 | Minor | `dbName`/`dbUsername` ไม่ถูก validate ฝั่ง server เลย (regex อยู่ที่ `mysql.sh:11-16` เท่านั้น — ดีที่ injection ถูกบล็อกที่จุด interpolate แต่ค่าผิดถูกปัดตก **หลัง** stop/restart VM เสียแล้ว) | `CreateDatabaseCmd.java:40-52` (ไม่มี validation); `DbaasManagerImpl.createDatabase` เริ่ม stop ก่อนส่งต่อ |
| N-2 | Minor | `listEngines()` — entry ไหนใน engines map ไม่มี `port` จะ throw แล้ว **loop ทั้งอันดับ** (จับ exception รอบนอก) → กลายเป็น "no engines" ทั้งชุด ทั้งที่ entry อื่นปกติ | `DbaasManagerImpl.java:634-644` (`cfg.get("port").getAsInt()` อยู่ใน loop, try ครอบทั้ง method) |
| N-3 | Cosmetic | หัวไฟล์ engine scripts ทั้ง 7 ยังเขียนว่า "via provision.sh" ทั้งที่ `provision.sh` ถูกลบไปแล้ว (ตอนนี้ invoke โดย `firstboot.sh`) | `mysql.sh:2`, `mariadb.sh:2`, `postgresql.sh:2`, `mongodb.sh:2` + reset scripts |
| N-4 | Cosmetic | หัว `schema-dbaas-credentials.sql` ขัดตัวเอง: บอกว่า "Rows are never deleted automatically" แล้วย่อหน้าถัดไปบอกว่า sweeper ลบตาม schedule อัตโนมัติ; และยังอ้าง 4.22 ทั้งที่ฐานเป็น 4.23 | `schema-dbaas-credentials.sql:17-24` |

### 3.4 จุดแข็งที่ยืนยันแล้ว (ทำถูกต้อง)

- **Report token**: 256-bit, เก็บเฉพาะ SHA-256 hash, single-use จริง (UPDATE
  เดียวเคลียร์ hash พร้อมยอมรับ → replay ชน 0 แถว), TTL, ทุก accept/reject
  log และ rejection ตอบ generic หมด (`DbaasManagerImpl.java:713-743`,
  `ReportProvisioningResultCmd.java:71-89`)
- **ไม่มีเศษ SSH เหลือ**: grep `vmaccess/cs_api/paramiko/authorized_keys`
  ใน plugin+extensions+UI ไม่เจอ code path ใดๆ (เหลือแค่คำใน comment — N-3)
- `provision_mode`/`provisionmode` ถูกลบสะอาดจาก `ListDbaasEnginesCmd` /
  `DbaasEngineResponse`
- `requestHasSensitiveInfo = true` ทั้ง `createDatabase` และ
  `reportDbaasProvisioningResult` (ไม่ถูก log ค่า sensitive)
- ACL ครบทุก command ผูกกับ VM owner (`getEntityOwnerId` ทุก Cmd)
- `firstboot.sh`: request.json 0600 + ลบทิ้งเมื่อสำเร็จ, เขียน result.json
  0600 และไม่มี password ใน message, `set -euo pipefail` ทุก script
- i18n ครบ: keys ที่ dbaas UI ใช้ทั้งหมด 70 keys — **มีครบใน en.json ไม่มี missing**
- wizard deploy แบบ `startvm=false` จริง (`CreateDatabaseInstance.vue:428`)
  และมี banner เตือน restart ที่ `CreateDatabase.vue:26`

## 4. สิ่งที่ยังไม่ทำตาม PLAN.md (unchecked ทั้งหมด)

### Phase B — เหลือ 2 ข้อ
- [ ] **Template rebuild: mysql ก่อน, `passwordenabled=true`** — ยังไม่ได้ทำ
      (รวมกับ P0-1: ชื่อ template ต้องลง engines map ใน
      `config.example.json` ด้วย ไม่งั้นหลุดอีกตอน deploy หน้า)
- [ ] **Acceptance: deploy ลง network ที่ management server มองไม่เห็น,
      VR ปิด, แล้วได้ DB ที่ใช้งานได้ + password แสดง** — ยังไม่เคยทดสอบเลย

### Phase D — เหลือ 3 ข้อ
- [ ] **Reset Database Password**: ปัจจุบัน `resetDatabasePassword` throw
      ชี้ช่องทางชัดเจน (`DbaasManagerImpl.java:490-495`) และ UI ซ่อน action —
      รอ in-VM agent; `*_reset.sh` ยังเก็บใน config.json ให้ agent เรียกต่อได้
- [ ] **Agent ใน template** poll หา pending jobs แล้ว reset ผ่าน agent
- [ ] **Engines ที่เหลือ**: mariadb / postgresql / mongodb ยังไม่ผ่านการ
      wire firstboot.sh แบบ end-to-end (จริงๆ mysql เองก็ยังไม่เคยรันจริง — P0-1)

### Phase E — ยังไม่เริ่มทั้ง 7 ข้อ
- [ ] `@ActionEvent` บน create / reset / show (audit trail การเข้าถึง credential)
- [ ] `listDatabaseCredentials` + picker ใน Show Password
- [ ] Delete database / drop user (บน instance + ในตาราง)
- [ ] Engine health ใน Database list (ต้องมาจาก agent เท่านั้น)
- [ ] Connection hint สำหรับ isolated network (guest IP เข้าไม่ถึงจากภายนอก)
- [ ] Idempotency key บน create
- [ ] เคลียร์ user data เมื่อ instance รายงาน `confirmed` (DB password ยังนอน
      เป็น cleartext ในตาราง user_data ของ CloudStack)

### Known issues จาก §4 ของ PLAN.md — ยังเปิดอยู่ทั้งหมด
- "user already exists" fail แข็ง แต่ UI ยังบอกไม่ชัด (พฤติกรรมยืนยันแล้วที่
  `mysql.sh:22-27`)
- `storeCredential` INSERT แถวใหม่ทุกครั้ง อ่านแค่แถวล่าสุด → ตารางโตไม่จำกัด
  (`DbaasManagerImpl.java:578-601`, SELECT `ORDER BY created_at DESC LIMIT 1`)
- Data disk cleanup มีแค่ทาง UI destroy + sweeper แค่ log ไม่ลบ
- **README.md / INSTALL.md / TEMPLATES.md ยังพูดถึงสถาปัตยกรรม SSH v1 ทั้งหมด**
  (เจอ 19 / 7 / 44 จุดอ้างอิง SSH/extension.py/provision.sh) — PLAN.md ยังเป็น
  แหล่งข้อมูลจริงเพียงแหล่ง
- Rate limiting บน report endpoint (ซ้ำกับ P2-6)

## 5. ไฟล์ที่แก้ไข/เพิ่มใน session นี้

**ไฟล์ที่เพิ่ม (2):**
- `AUDIT-REPORT-2026-09-05.md` — รายงานนี้ (ยังไม่ commit)
- `/tmp/dbaas-v2-squash-msg.txt` — ไฟล์ชั่วคราวสำหรับ squash commit message

**ไม่มีไฟล์ใดถูกแก้เนื้อหา** — งาน git ทั้งหมดเป็นการจัดประวัติ/branch
(working tree ก่อนและหลัง tree hash ตรงกัน)

**ไฟล์ที่อยู่ใน squash commit `9e98d0f2c2` (ผลรวมของ 13 commit เดิม) 62 ไฟล์
จัดกลุ่มให้อ่านง่าย:**

| กลุ่ม | ไฟล์ |
| --- | --- |
| Backend Java (10) | `plugins/integrations/dbaas/src/main/java/com/dbaas/`: `CreateDatabaseCmd`, `GetDatabasePasswordCmd`, `ResetDatabasePasswordCmd`, `ListDbaasEnginesCmd`, `DeleteDbaasCredentialsCmd`, `ReportProvisioningResultCmd`, `DbaasManager`, `DbaasManagerImpl`, `DbaasResponse`, `DbaasEngineResponse` |
| Plugin resources (5) | `pom.xml`, `module.properties`, `spring-dbaas-context.xml`, `db/schema-dbaas-credentials.sql`, `plugins/pom.xml` + `client/pom.xml` (แก้ของเดิม) |
| Provisioning (21) | `extensions/dbaas/`: `config.example.json`, `provisioning/firstboot.sh`, `rotate-admin-password.sh`, `dbaas-rotate-admin-password.service`, `banner/` (13 ไฟล์), engine scripts `mysql/mariadb/postgresql/mongodb` + `*_reset` (8 ไฟล์) |
| UI (6) | `ui/src/views/compute/`: `DatabaseInstances.vue`, `CreateDatabaseInstance.vue`, `CreateDatabase.vue`, `ResetDatabasePassword.vue`, `ShowDatabasePassword.vue`; `ui/src/utils/dbaas.js` (+ hooks/router/locale ที่แก้ของเดิม) |
| เอกสาร (4) | `plugins/integrations/dbaas/`: `PLAN.md`, `README.md`, `INSTALL.md`, `TEMPLATES.md` + `host/README.md` |
| ราก (3) | `AUDIT-v2.md`, `BUILD-DEPLOY-SIMPLE.md`, `README-BUILD-DEPLOY.md` |

## 6. ลำดับงานที่แนะนำถัดไป

1. แก้ **P0-1** (เพิ่ม `dbaas-mysql-v2` ใน `config.example.json` + config บน host)
2. แก้ **P1-3** (truncate message ทั้ง 2 ฝั่ง — แก้ 3 บรรทัด) และ **P1-2**
   (try/finally restart) ก่อนทดสอบ เพราะจะทำให้ acceptance test อ่านผลได้จริง
3. แก้ **P1-1** (readiness wait ใน `firstboot.sh`) แล้วค่อย **rebuild template**
   (ข้อ Phase B) แล้วจึงรัน acceptance test ของ Phase B ให้ผ่าน
4. จากนั้นค่อย in-VM agent (Phase D) และ Phase E

---

*ตรวจโดยอ่านโค้ดทุกไฟล์หลักของ plugin (Java ทั้ง 10, firstboot.sh, mysql.sh,
schema, config.example.json) และเจาะจุดสำคัญของ UI; ไม่ได้รัน build/deploy ใน
session นี้ (สถานะ build อ้างอิงจาก AUDIT-v2.md เดิม)*

---

## 7. Addendum (2026-09-05): ผลการแก้ตามรายงานนี้

แก้แล้วใน commit `fix(dbaas): close the audit findings before the first real
deploy` — ตรวจสอบแล้ว: `mvn compile` + checkstyle ผ่าน, `bash -n` ทุก script,
`node --check` ทุกไฟล์ UI ที่แก้, en.json เป็น JSON ที่ถูกต้อง

| ข้อ | สถานะ |
| --- | --- |
| P0-1 template มองไม่เห็น | ✅ แก้แล้ว — เพิ่ม `dbaas-mysql-v2` ใน `config.example.json` (config.json บน host ต้องเพิ่มด้วยตอน deploy) |
| P1-1 ไม่รอ engine | ✅ แก้แล้ว — `firstboot.sh` รอสูงสุด 120s (`mysqladmin ping` / `pg_isready` / `mongosh ping` ตาม `/opt/dbaas/engine`) |
| P1-2 stop แล้วไม่ start คืน | ✅ แก้แล้ว — validate ก่อนแตะ power state, poll Stopped 30s, try/catch ครอบทุกขั้นหลัง stop พร้อม restart เงียบๆ |
| P1-3 status_message ล้น | ✅ แก้แล้ว — truncate 1000 chars ทั้งฝั่ง `firstboot.sh` และ `applyProvisioningReport` |
| P1-4 v1 template เข้า flow ใหม่ | ✅ แก้แล้ว — บังคับ template detail `dbaas.configdrive=true`, wizard กรองด้วย (ระบุใน PLAN.md §8) |
| P2-1 javadoc เท็จ | ✅ แก้แล้ว |
| P2-2 setHost IP เป็น null | ✅ แก้แล้ว — อ่าน IP หลัง start |
| P2-3 clock skew | ✅ แก้แล้ว — เทียบ expiry ใน Java กับเข็มเดียวกับตอนเขียน |
| P2-4 polling สั้น | ✅ แก้แล้ว — 30 ครั้ง × 10s = 5 นาที |
| P2-5 waiting.engine สื่อผิด | ✅ แก้แล้ว — reword ใน en.json |
| P2-6 rate limiting | ✅ แก้แล้ว — per-IP fixed window, `dbaas.report.rate.limit` (60/นาที, 0 = ปิด), ตอบ 429 |
| P2-7 dead reset modal | ✅ แก้แล้ว — ลด import/render/route ออกจาก DatabaseInstances.vue (ตัว component คงไว้ให้ Phase D) |
| N-1 validate identifier | ✅ แก้แล้ว — server-side ก่อน stop VM |
| N-2 listEngines พังทั้งชุด | ✅ แก้แล้ว — skip เฉพาะ entry เน่า |
| N-3 comment provision.sh | ✅ แก้แล้วทั้ง 8 ไฟล์ |
| N-4 schema header ขัดตัวเอง | ✅ แก้แล้ว |
| README/INSTALL/TEMPLATES ยังเป็น v1 | ⏳ ยังไม่แก้ — เป็นงานเขียนเอกสารแยก |
| Phase B acceptance (deploy จริง) | ⏳ ยังต้องทำ — ไม่มีอะไรแทนการ deploy จริงได้ |

# AUDIT-FIXES — 2026-09-06 (BLOCKER-1/2 + MEDIUM-3/4)

ตามลำดับงานจาก `AUDIT-CONSOLE-2026-09-06.md`: แก้ BLOCKER-1/2 → เขียน
round-trip test → MEDIUM-3 → MEDIUM-4 → host work (ดู §5)

---

## 1. BLOCKER-1 — query รันก่อน bind parameter (แก้ 6 จุดครบ)

ทุกจุดเปลี่ยนเป็นรูปแบบเดียวกับโค้ดเดิมที่ถูกต้อง (:1292–1301 เดิม):
**prepare → bind → เปิด ResultSet แยก**

| จุดเดิม | เมธอด | หลังแก้ |
| --- | --- | --- |
| :552 | getJobAccountId | prepare → setString(1, jobUuid) → open ResultSet |
| :722 | agentPollJob (resolve VM) | prepare → setString(1, vmUuid) → open ResultSet |
| :748 | agentPollJob (หา job) | prepare → setLong(1, vmId) → open ResultSet |
| :830 | agentReportResult (validate) | prepare → bind ×3 → open ResultSet |
| :891 | getUserJobResult (หา job) | prepare → bind ×2 → open ResultSet |
| :917 | getUserJobResult (result + delete-on-read) | prepare → setLong(1, jobId) → open ResultSet |

## 2. BLOCKER-2 — isAgentTokenValid ไม่ bind เลย (แก้)

เพิ่ม `pstmt.setString(1, vmUuid)` ที่ :645 — เดิมเมธอดไม่มี bind อยู่ในนั้นเลยจริง ๆ

## 3. Round-trip test กับ DB จริง (ผ่าน)

`plugins/integrations/dbaas/src/test/java/com/dbaas/ConsoleRoundTripTest.java`:

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 - in com.dbaas.ConsoleRoundTripTest
```

- agentTokenValidation: token ถูกต้องผ่าน / token ผิด false / VM ไม่มีจริง false
  (BLOCKER-2 regression net)
- consoleJobRoundTrip: createConsoleJob → agentPollJob (ได้ jobid + type
  table_list) → agentReportResult (accepted) → getUserJobResult (ได้ result)
  → ครั้งที่สอง = collected:true และไม่มี rows
- skip เมื่อไม่มี db.properties+key ที่อ่านได้ (assumeTrue)
- ทิ้งแค่ 1 แถว dbaas_jobs + ลบ token row ของตัวเองตอนจบ
- mysql-connector-j เพิ่มเป็น test dependency ของ plugin pom

## 4. MEDIUM-3 — rate limit + waiter ceiling (แก้ + พิสูจน์บนสาย)

- getDbaasAgentJob และ reportDbaasJobResult: rate limit per source IP ก่อน
  ทุกอย่าง (DbaasReportRateLimit, 60/นาที default) — limiter ก้อนเดียวกันทั้ง
  ระบบ; เกิน → 429
- long-poll: เพดาน waiter พร้อมกัน `dbaas.agent.longpoll.maxwaiters`
  (default 100) — เกิน → 503; token ถูกตรวจก่อนเข้า hold
- หลักฐานบนสาย (70 requests ต่อ endpoint หลัง repatch jar + restart):
  - getDbaasAgentJob: 403 ×59 → 429 ×10 → (window ใหม่) 403 ×2
  - reportDbaasJobResult: 403 ×59 → 429 ×10 → (window ใหม่) 403 ×2
  - log: rate limited for … 20 บรรทัด
  - UnsatisfiedDependencyException: 0 หลังแก้ (ก่อนแก้ = 6)

## 5. MEDIUM-4 — en.json กลับสู่ format เดิม (แก้)

กู้จาก `4737145e1f` แล้วแทรก 17 keys เป็นบรรทัดดิบ:

- **net diff เทียบ original = 19 บรรทัด** (17 keys + comma โครงสร้าง +
  no-newline-at-EOF) — ตรวจ: `git diff 4737145e1f -- ui/public/locales/en.json
  --stat` = 1 file, 19 insertions, 0 deletions (เดิม re-serialise = 4454
  deletions)
- JSON valid, 4568 keys, console keys ครบ 17

## 6. Host work — สถานะ (ข้อ 4 ของลำดับงาน)

| งาน | สถานะ |
| --- | --- |
| hot-patch jar + restart | ✅ ทำแล้ว 3 ครั้ง (jar ปัจจุบัน = โค้ด HEAD, backup: /root/cloudstack-4.23.0.0.jar.bak + .bak2) |
| full server build ของโค้ดสุดท้าย | ⏳ ยังไม่รัน — / เหลือ 2.2G ตอนเริ่ม; ต้องเคลียร์ดิสก์ก่อน |
| rebuild template (Step 3.5 + agent + _ro scripts + python libs) | ⏳ รออนุมัติ — ไฟล์พร้อมใน repo (agent/dbaas_agent.py, service, env script, engine scripts ×4) — image ต้องเพิ่ม python3-pymysql/psycopg2/pymongo |
| deploy ทดสอบ + test matrix §11 | ⏳ รอ 2 ข้อก่อนหน้า |

jar ที่วิ่งอยู่ = โค้ด HEAD (hot-patch) — full build ครั้งถัดไปจะได้ชุดเดียวกัน

## 7. ยืนยันซ้ำ (คงอยู่จากรอบก่อน — ตรวจซ้ำแล้ว)

- flag ทั้ง 4 = false ครบ
- token เก็บ SHA-256 อย่างเดียว; payload/result เข้ารหัส DBEncryptionUtil
- delete-on-read + TTL sweeper
- ACL ครบผ่าน DbaasConsoleJobCmdBase.getEntityOwnerId (10 command)
- agent endpoint ทั้งคู่ static holder + sendErrorQuietly
- agent บังคับ row/byte limit + statement timeout; log เฉพาะ
  job uuid/type/role — ไม่มี SQL ไม่มีแถวข้อมูล
- **DATA-73 ไม่ถูกแตะ** (Ready, unattached, ไม่มี marker, marker ระบบ = 0 แถว)
- host state นอก jar: ไม่มีการเปลี่ยนแปลงใด ๆ (SELECT เท่านั้น)

## 8. ยังไม่เริ่ม C5

ตามสั่ง — transport ต้องผ่าน test matrix §11 บนระบบจริงก่อน

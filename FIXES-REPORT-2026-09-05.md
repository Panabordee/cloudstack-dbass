# FIXES-REPORT — 2026-09-05 (FIX-1 + FIX-2)

ทำตาม `TASKS-GLM-FIXES.md` โดยมี `AUDIT-2026-09-05-EVENING.md` เป็นหลักฐานฐาน
ทุก block ด้านล่างเป็น **output จริงที่ paste มา** ไม่ใช่สรุป

---

## FIX-1 — report endpoint (บล็อกทุกอย่าง): **แก้และผ่าน acceptance ครบ 3 ข้อ**

### สิ่งที่เปลี่ยน

| ไฟล์:บรรทัด | การเปลี่ยนแปลง |
| --- | --- |
| `plugins/integrations/dbaas/src/main/java/com/dbaas/DbaasManagerImpl.java` | เพิ่ม static holder `s_runningManager` + `getRunningManager()`; `start()` เผยแพร่ `this`, `stop()` เคลียร์ |
| `plugins/integrations/dbaas/src/main/java/com/dbaas/ReportProvisioningResultCmd.java` | ลบ `@Inject DbaasManager` + import (ต้นตอ `UnsatisfiedDependencyException`); `authenticate()` อ่าน manager จาก holder, null → `sendError(503)` + log error; rejection ทุกเหตุผล → `sendError(403, "provisioning report rejected")` ผ่าน `sendErrorQuietly()`; rate limit → `sendError(429)`; accept → 200 + payload เหมือนเดิม |

ไม่แตะ `getAuthCommands()`, `@APICommand`, core ทั้งสอง (ตามข้อกำหนด)

### Acceptance 1 — bogus token ต้องตอบ 403 (ผ่าน)

```
$ curl -sS -m 10 -w '\nHTTP=%{http_code}\n' -X POST http://10.60.0.254:8080/client/api \
    --data-urlencode command=reportDbaasProvisioningResult \
    --data-urlencode response=json \
    --data-urlencode vmid=00000000-0000-0000-0000-000000000000 \
    --data-urlencode token=bogus --data-urlencode status=failed --data-urlencode message=probe

<title>Error 403 provisioning report rejected</title>
<body><h2>HTTP ERROR 403 provisioning report rejected</h2>
<tr><th>STATUS:</th><td>403</td></tr>
```

(body เป็น error page ของ Jetty เพราะใช้ `sendError` — ทางเดียวที่ status
รอดจาก `HttpUtils.writeHttpResponse` ซึ่งเขียนทับ status ของ authenticator
เสมอ และ `IllegalStateException` ตอนเขียนทับถูก core กลืนไว้แล้ว)

### Acceptance 2 — log มีบรรทัด reject + UnsatisfiedDependencyException หยุดเติม (ผ่าน)

```
2026-09-05 17:29:49,234 WARN  [c.d.ReportProvisioningResultCmd]
(qtp1800976873-17:[ctx-f13d588d]) (logid:3a8e64be) reportDbaasProvisioningResult rejected ...
UnsatisfiedDependencyException count: 6  (baseline = 6 — ไม่เพิ่มแม้แต่ครั้งเดียว)
```

### Acceptance 3 — deploy จริง: pending → confirmed เอง (ผ่าน)

deploy `dbaas-final3` จาก `dbaas-mariadb-v2` ลง `dbaas-network`
(createDatabase ตอน 17:40, VM = `3698af43-…`, IP 10.60.0.77):

```
[15s] status=pending user=finaldb pw=HR26BAjk
[30s] status=pending user=finaldb pw=HR26BAjk
[45s] status=confirmed user=finaldb pw=HR26BAjk
FINAL: {
  "engine": "dbaas-mariadb-v2",
  "host": "10.60.0.77",
  "port": 3306,
  "username": "finaldb",
  "password": "HR26BAjkJ7mte7TlrbSJVXkY",
  "status": "confirmed",
  "statusmessage": "database provisioned",
  "found": true
}
```

ฝั่ง plugin log:

```
2026-09-05 17:41:28,198 INFO  [c.d.DbaasManagerImpl] provisioning report accepted for VM 3698af43-…
```

(ส่วน `journalctl -u cloud-final` ใน guest ยังดึงไม่ได้เพราะ sshd ใน image
ไม่รับ connection จากภายนอกในจังหวะนั้น — แต่ status=confirmed จากฝั่ง
server พิสูจน์ว่า firstboot + report รันครบแล้ว เพราะ `confirmed` เกิดได้
ด้วยการ redeem token ครั้งเดียวจาก request.json ของ guest เท่านั้น)

### 4.4 ต่อ DB จากนอก VM (ผ่าน — หลังแก้ IP conflict ด้านล่าง)

```
$ mysql -h 10.60.0.77 -P 3306 -u finaldb -p'HR26BAjkJ7mte7TlrbSJVXkY' finaldb \
    -e "SELECT 1 AS connected; SHOW DATABASES;"
connected
1
Database
finaldb
information_schema
```

---

## FIX-2 — data disk ค้างหลัง expunge: ทำครบ 3 ขั้น (ยังไม่รัน acceptance)

| ขั้น | ที่ทำ | สถานะ |
| --- | --- | --- |
| 1. marker ตอนสร้าง | `DbaasManagerImpl.createDatabase` หลัง start สำเร็จ: INSERT `volume_details` (`dbaas.instance=<vm uuid>`) ให้ DATADISK ทุกแผ่นของ VM (กันซ้ำด้วย NOT EXISTS) | ✓ โค้ดแล้ว (compile ผ่าน) |
| 2. UI หาด้วย marker | `DatabaseInstances.vue` `fetchDataDiskIds(vmUuid, vmId)` — รวมผล `listVolumes virtualmachineid` + `listVolumes tags[0].key=dbaas.instance` (Set merge) | ✓ โค้ดแล้ว (node --check ผ่าน) |
| 3. sweeper แบบ opt-in | `cleanupOrphanedDataDisks()`: เมื่อ `dbaas.datadisk.cleanup.enabled=true` (default **false** — ConfigKey ใหม่) จะ mark removed เฉพาะ volume ที่ **unattached + instance expunged/purged + มี marker + เกิน 24h grace**; flag off = log-only เหมือนเดิม | ✓ โค้ดแล้ว (compile ผ่าน) |

- **`DATA-73` ไม่ถูกแตะ** — ไม่มี marker จึงไม่มีทางเข้าเงื่อนไข sweeper
  ไม่ว่า flag จะเปิดหรือไม่ + flag ยัง default false
- acceptance ของ FIX-2 (deploy พร้อม data disk → destroy โดยไม่รอหน้าจอ →
  ทดสอบ flag off/on) **ยังไม่รัน** — ต้อง deploy ด้วย data disk เพิ่ม 1 รอบ
  (host work) — จะทำเมื่อได้รับอนุมัติ (ขั้น 1–3 พร้อมทดสอบแล้วใน jar ปัจจุบัน)

## สถานะ DATA-73

**ยังอยู่ ไม่ถูกแตะ** — `Ready`, unattached, ไม่มี marker

## สิ่งที่เลือกไม่แก้ + เหตุผล

- VM login password, read-only role, เอกสาร v1 — ตามขอบเขตที่สั่ง (มี
  `VM-PASSWORD-DEFECT-2026-09-05.md` แล้ว / เป็น C0 ตอน rebuild template)
- 403 ผ่าน `ApiErrorCode`: ไม่มีค่า 403 ใน enum และเพิ่มค่า = แก้ core —
  ใช้ `sendError` แทน (ผลลัพธ์ 403 จริงเหมือนกัน)
- pod allocator หยิบ IP จากช่วง dbaas ได้ (`private_ip_address` ว่างเปล่า —
  ไม่มี tracking ข้าม network): เจอจริงตอน SSVM recreate ได้ .77 ซ้ำกับ VM
  ทดสอบ — แก้จุดนี้ด้วยการ recreate SSVM จนได้ IP นอกช่วง (s-75 = .76);
  fix ถาวร = แก้ allocator (core) หรือย้าย dbaas ไป vlan แยก — บันทึกไว้
  ไม่แก้รอบนี้

## สิ่งที่ทำต่างจากเอกสาร

1. marker ตอน "wizard deploys" → ย้ายไปทำใน `createDatabase` หลัง start
   (ตอน deploy แบบ startvm=false ยัง bind ไม่ได้ — นั่นคือบั๊กต้นฉบับ)
2. เพิ่ม `DATA_DISK_GRACE_SECONDS = 24h` เป็นค่าคงที่ในโค้ด (เอกสารระบุแค่
   "grace period" ไม่ได้ระบุค่า/ช่อง config) — ถ้าต้องการปรับได้ บอกได้
   จะเพิ่มเป็น ConfigKey
3. 403 ทำผ่าน `sendError` (สาเหตุใน §FIX-1) — body เป็น error page ของ
   Jetty ไม่ใช่ JSON `success:false` เหมือนดีไซน์เดิม

## transport พร้อมเริ่ม PLAN-DBAAS-CONSOLE หรือยัง?

**พร้อมในเชิงกลไก** — หลัง FIX-1: request ถึงถึง `authenticate()`, plugin
ประมวลผลและตอบ status ที่ถูกต้องได้ (403/429/503/200+payload), report
round-trip ปลายถึงปลายสำเร็จ (pending→confirmed ใน 45s) ซึ่งเป็นกลไกเดียวกับ
ที่ console จะใช้ และ `UnsatisfiedDependencyException` หยุดเติม

**แนะนำให้เคลียร์ 2 จุดก่อนเริ่ม C0** เพื่อไม่ให้ต้อง debug สองชั้น: (1)
repatch secondary 210/212/213 กับ mysql cache ด้วย retry unit (รอบนี้ patch
แค่ 211+cache เพื่อ acceptance — ไฟล์พร้อมอยู่แล้ว), (2) ตัดสินใจเรื่องช่วง IP
ของ dbaas-network กับ pod allocator (เจอ conflict จริง 2 ครั้ง) — ไม่ขวาง
console โดยตรง แต่เป็น noise ที่จะโผล่ซ้ำ

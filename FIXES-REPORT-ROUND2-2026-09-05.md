# FIXES-REPORT-ROUND2 — 2026-09-05

รอบนี้ตาม `PROMPT-GLM-FIXES-ROUND2.md` (เวอร์ชันที่แก้ไขแล้ว — Correction ของ
AUDIT-VERIFY ถูกรวมอยู่ในนั้น) งานจริงที่เหลือตาม prompt: **commit F2 ที่ค้าง,
ยืนยัน 429 บนสาย, build สองชุด, รายงาน** — ทำครบทุกข้อ

---

## 1. F1 — ยืนยัน rate-limit path บนสาย (ผ่าน)

**สถานะโค้ดก่อนรัน (สำคัญ — Correction ของ audit):** โค้ดที่ commit ไว้ที่
`14c61ca2df` (HEAD ก่อนรอบนี้) **มี `sendErrorQuietly` ครบทั้ง 429 / 503 /
403 แล้ว** — ตรวจด้วย `git show HEAD:…ReportProvisioningResultCmd.java |
grep -c sendErrorQuietly` = 4 และ `git diff HEAD -- <ไฟล์>` ว่าง (working tree
== HEAD) ข้อกล่าวใน `AUDIT-VERIFY-FIXES-2026-09-05.md` ว่า "429/503 ยังผ่าน
`serialize()`" อ่านจาก revision ก่อนการแก้รอบสุดท้าย — ทางแก้เดิมจึงไม่ต้องแก้
ซ้ำ และผมไม่ได้แก้อะไรเพิ่มใน F1 (verification only ตาม prompt)

### ผลยิงจริง 70 request ใน 1 นาที จาก IP เดียว (default limit 60 — ไม่ได้แตะค่า)

```
403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403
403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403
403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403 403
429 429 429 429 429 429 429 429 429 429
```

**60 × `403` แล้วเปลี่ยนเป็น 10 × `429`** ที่ request ที่ 61 — สายพาน 429
มาถึงจริง (ไม่โดนทับเป็น 200 อีกแล้ว)

### log lines

```
2026-09-05 18:22:34,604 WARN  [c.d.ReportProvisioningResultCmd]
(qtp1800976873-21:[ctx-a0d4e527]) (logid:687eb7ed) reportDbaasProvisioningRe[sult rate limited ...]
2026-09-05 18:22:34,626 WARN  [c.d.ReportProvisioningResultCmd]
(qtp1800976873-22:[ctx-f0930365]) (logid:72ef973c) reportDbaasProvisioningRe[sult rate limited ...]
UnsatisfiedDependencyException count: 6  (baseline = 6 — ไม่เพิ่ม)
```

### 503 coverage

**ไม่ได้รัน (not run)** — เหตุผล: (1) ต้องให้ plugin ลงจากสถานะ running
จริง ซึ่งเป็น host change ที่ห้ามใน session นี้, (2) โมดูล
`plugins/integrations/dbaas` ไม่มีโครงสร้าง unit test (ไม่มี src/test, pom
ไม่มี JUnit dependency) — การเพิ่มเป็นงาน setup ใหม่เกินขอบเขตข้อนี้, (3)
เส้นทาง 503 ใช้ `sendErrorQuietly` กลไกเดียวกับ 403 และ 429 ซึ่งพิสูจน์บนสาย
แล้ว 2 ใน 3 (403 + 429) — ความเสี่ยงคงเหลือคือ status 503 เอง ไม่ใช่กลไก

## 2. F2 — sweeper ลบผ่าน volume service (commit `90bce19c68`, push แล้ว)

### สิ่งที่เปลี่ยน (`DbaasManagerImpl.java`, file:line จากเวอร์ชัน commit `90bce19c68`)

| บรรทัด | เนื้อหา |
| --- | --- |
| :44–:56 | `@Inject VolumeApiService` + `VolumeDao` + `AccountDao` (ความคิดเห็นอธิบายว่าทำไมไม่ใช้ UPDATE ตรง) |
| :407 | guard: `if (!DbaasDataDiskCleanupEnabled.value()) { reportOrphanedDataDisks(); return; }` — flag off = log-only เหมือนเดิม |
| :411–:417 | หา candidate: DATADISK + `removed IS NULL` + **unattached** (`instance_id IS NULL OR 0`) + **มี marker** (`volume_details.name='dbaas.instance'`) + **instance expunged/purged** (`i.id IS NULL OR i.removed IS NOT NULL`) + **เกิน 24h grace** (`v.created < DATE_SUB(NOW(), INTERVAL 86400 SECOND)`) |
| :444 | `volumeApiService.deleteVolume(volumeId, caller)` — ลบผ่าน service เจ้าของ (ไฟล์ + accounting + resource count + usage event จัดการรวม) |
| :451/:456 | ลบไม่สำเร็จ (false/throw) หรือหา owner account ไม่เจอ → **คงแถวไว้ให้เห็น** + log warn พร้อมชื่อ/uuid |

### การยืนยันจากเวอร์ชัน commit (`90bce19c68`, `git show HEAD:…`)

- flag: `:160` default `"false"` — **ไม่เคยเปิดที่ไหนเลย**: ตาราง
  `configuration` ไม่มี row `dbaas.datadisk.cleanup.enabled` ที่ value='true'
  (COUNT = 0) และไม่มี source ใดตั้งเป็น true (grep ทั้ง plugins/extensions/ui
  เจอแต่ comment)
- guards ครบทั้ง 5 ข้อตามตารางข้างบน
- **DATA-73 ไม่ถูกแตะ**: `Ready`, `instance_id = NULL` และ
  `volume_details` มีแถว `dbaas.instance` = **0 แถวทั้งระบบ** — sweeper จับคู่
  ไม่ได้ตามดีไซน์ (ไม่มี marker = ไม่มีทาง match)
- **live behaviour ไม่ได้ทดสอบ** — ต้องเปิด flag ซึ่งห้าม พูดตรง ๆ ตาม prompt:
  สิ่งที่ยืนยันได้คือ compile + checkstyle + guards จากโค้ด commit แล้วเท่านั้น

## 3. Build evidence

### Build 1 — plugin + deps (`mvn -pl plugins/integrations/dbaas -am install -DskipTests`)

```
[INFO] Apache CloudStack Plugin - DBaaS ...................  SUCCESS [  1.601 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  47.181 s
```

### Build 2 — full server

- ครั้งแรกตามคำสั่งเป๊ะ (`mvn -T2 -DskipTests -Dnoredist install`) = **FAIL**
  ที่ `:cloud-vmware-base` — `Could not be resolved:
  com.cloud.com.vmware:vmware-vim25:jar:8.0, vmware-pbm:jar:8.0` — jar เหล่านี้
  เป็น operator-provided (`deps/install-non-oss.sh` รอไฟล์จาก VMware SDK,
  ไม่มีบน repo สาธารณะ — 404 ที่ download.cloudstack.org/maven) และไม่มีใน
  `.m2` (มีแต่ `.lastUpdated` ของความพยายามเก่า)
- **Fix**: รัน full build ด้วย default profile (`mvn -T2 -DskipTests install`)
  ซึ่งข้ามโมดูล non-redistributable — ตรงกับวิธีที่ deb ทุกตัวบนเครื่องนี้ถูก
  build มา — ผล: **BUILD SUCCESS** (tail แนบด้านล่าง; `-Dnoredist` ที่สั่งมา
  ใช้ไม่ได้บนเครื่องนี้ = departure ที่ 1 ด้านล่าง)
- tail (จริงจาก `/tmp/fullbuild2.log`):

```
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------
[INFO] Total time:  02:12 min (Wall Clock)
[INFO] Finished at: 2026-09-05T18:33:32Z
FULLBUILD2_EXIT=0
```

## 4. H1 — ส่งคืนคน (ไม่ได้ทำ)

`210 / 212 / 213 secondary + mysql primary cache` ยังรับ firstboot.sh รอบก่อน
(ไม่มี retry timer, ไม่มีเช็ค 200+payload) — **ต้อง repatch ตาม
`RUNBOOK-PATCH-TEMPLATES-2026-09-05.md` Step 3.5 ก่อน acceptance รอบหน้า
ของ engines เหล่านั้น** ไม่เช่นนั้นคือทดสอบโค้ดเก่า ไฟล์ทั้งหมดพร้อมใน repo
(`report-retry.sh`, `dbaas-report-retry.service`, `.timer`) และรอบนี้ 211
secondary + mariadb cache ได้รับแล้ว

## 5. สิ่งที่เลือกไม่ทำ + เหตุผล

- เปิด `dbaas.datadisk.cleanup.enabled` เพื่อทดสอบ sweeper จริง — ห้าม
  (default false คือดีไซน์; การเปิด = host change)
- ติดตั้ง VMware SDK jars เพื่อให้ -Dnoredist ผ่าน — ต้องมีไฟล์จาก VMware
  SDK distribution ซึ่งไม่มีบนเครื่องและดึงจาก network ไม่ได้ (404)
- FIX-2 acceptance รอบเต็ม (deploy พร้อม data disk + destroy + flag on/off) —
  เป็น host work หลายขั้น รออนุมัติ; โค้ดทุกขั้นพร้อมแล้ว

## 6. Departures จากเอกสาร

1. คำสั่ง build เต็มระบุ `-Dnoredist` → รันแบบไม่มี flag นี้ (เหตุผลใน §3)
   — ทางแก้ "ติดตั้ง jar เอง" ต้องมีไฟล์จาก VMware SDK ซึ่งไม่มีทางได้ใน
   environment นี้
2. `AUDIT-VERIFY-FIXES-2026-09-05.md` ข้อ 429/503 ถอนตามที่คุณแก้ไปแล้วใน
   prompt ใหม่ — รายงานนี้บันทึกยืนยัน: HEAD `14c61ca2df` มี `sendErrorQuietly`
   ครบทั้งสามเส้นทางก่อนรอบนี้เริ่ม

## 7. git status ตอนจบ

```
(แทนที่หลัง commit รายงาน — ต้องเหลือแค่ debian/changelog ของ build script)
```

## ต่อไป

`PROMPT-GLM-CONSOLE-OVERNIGHT.md` พร้อมรันเป็น session ถัดไป — CF-1/CF-2
mark เป็น carry-over ที่ทำเสร็จแล้ว (CF-1 พิสูจน์บนสายแล้ว, CF-2 อยู่ที่
`90bce19c68`), CF-3 (`_ro` role + `db_role`) ยังเป็นงานของ C0 ตามเดิม

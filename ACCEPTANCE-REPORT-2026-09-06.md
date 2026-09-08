# ACCEPTANCE REPORT — host round (2026-09-06 → 09-07)

ตาม `PROMPT-GLM-HOST-ACCEPTANCE.md` + ADDENDUM + `PROMPT-GLM-OVERNIGHT-MATRIX.md`
รายงานนี้แทน interim ฉบับก่อน ทุกข้อความมาจากของที่รันจริงบน host

---

## TL;DR

- **provisioning พิสูจน์แล้วบนระบบจริง**: `pending → confirmed`, agent long-poll
  25 s วิ่งจริง, ต่อ mysql จากนอก VM ได้ด้วย user ของ tenant
- **บั๊กบล็อกใหญ่ 1 ตัวที่ยังไม่แก้**: server ส่ง job JSON **แบบไม่ห่อ wrapper**
  แต่ agent parse หา `getdbaasagentjobresponse` → **job ทุกตัวที่ถูก dispatch
  ถูก agent ทิ้งเงียบ ไม่ execute ไม่ report** (§7 รายละเอียด) — แก้ 1 จุดใน
  `dbaas_agent.py` แล้ว matrix ทั้งชุดจะไหล
- **§11 matrix ข้อ 1–15: ไม่มีข้อไหนถูกรันจริง** (ถูกบั๊กข้างบนบล็อก) — ตอบตรงๆ
  ว่า not run ทั้งหมด ไม่อ้างว่าผ่าน
- บั๊กที่แก้แล้ว 2 ตัว (commit แนบ) + hot-patch jar + restart mgmt 1 ครั้ง

## 1. Timeline

| เวลา (UTC) | เหตุการณ์ |
| --- | --- |
| 15:05 | อ่านเอกสารทั้งหมด / `/` = **278M** (แย่กว่าใน prompt ที่บอก 2.2G) |
| 15:11–15:24 | build 1 (plugin -am install, รวม test) **SUCCESS 12:51** |
| 15:25–15:41 | เคลียร์ดิสก์ (§2) + build 2 ด้วย `-Dnoredist` ตาม prompt → **พัง** (vmware-vim25 8.0 หาไม่ได้; pom ของ fork นี้ activate profile vmware เมื่อ property มีค่า) → รันใหม่ default profile |
| 15:28–15:40 | patch template 210 (mysql) secondary + cache — §3 **option 2 (chroot)** + `policy-rc.d` + `.deb`-through-host (DNS ใน chroot ใช้ไม่ได้บน host นี้) — `qemu-img check` ผ่านทั้งคู่ |
| 15:47 | build 2 (default profile) **SUCCESS 6 นาที** |
| 15:54–18:10 | build 3 (UI): พัง `ERR_OSSL_EVP_UNSUPPORTED` (webpack เก่า + Node 22) → restart ด้วย `--openssl-legacy-provider` → **ค้าง**: `autoremove` (จาก purge monitoring) ถอน nodejs/npm ไปด้วย → ติดตั้งคืน (node 22.23.2 / npm 10.9.8) → รันใหม่ **as nacl** (build discipline) — **ยังไม่ได้เก็บผลล่าสุด** |
| 16:00 | ADDENDUM: push branch v1 ทั้ง 3 (`ls-remote` ยืนยัน `0d98c736b0` บน GitHub แล้ว) / purge alloy+loki+node-exporter (แก้ dpkg ค้างด้วยการถอน cloudstack-marvin+integration-tests ที่พังก่อนหน้า) / ลบ dbaas-deb 816M / **expunge dbaas-final3 (อนุญาตแล้วใน ADDENDUM §D)** → patch 211 sec+cache, 212, 213 ครบ → `/` เหลือ 13–16G |
| 16:01 | deploy `dbaas-accept-m1` (boot ก่อน createDatabase) → firstboot no-op — **root cause**: cloud-init cache ตอน boot แรกยังไม่มี userdata → boot หลัง createDatabase ใช้ cache เดิน (instance-id เดิม) ไม่อ่าน userdata ใหม่ → destroy |
| 16:24 | deploy `dbaas-accept-m2` ด้วย **startvm=false** → createDatabase → start = boot แรกมี request  |
| 16:36 | start m2 พังครั้งแรก: pool primary 89.9% เกิน threshold 0.85 — **สาเหตุ: backup 6 ไฟล์ของผมเอง (16.4G) อยู่ใน /export/primary** → ย้ายไป gz ที่ `/root/tplbackup-20260906/` → pool เหลือ ~47% → start ผ่าน |
| 16:47–16:53 | **provisioning พิสูจน์**: `testdb1` owner = **confirmed**, `last_seen_at` ขยับ (long-poll), `mysql -h 10.60.0.77 -u testdb1` จาก host **ผ่าน** (`CURRENT_USER()=testdb1@%`) |
| 18:0x | §2 overnight: revert `ui/public/config.json`, commit+push เอกสาร 6 ไฟล์ + fix agent duplicate `command` param (`c055110532`) |
| 18:15 | matrix เริ่มไม่ได้: ทุก console command ตาย **HTTP 431** `Gson multiple JSON fields 'jobid'` — `DbaasJobResponse`/`DbaasJobResultResponse` ประกาศ `jobId` ทับ `BaseResponse` |
| 18:19–18:26 | แก้ 2 คลาส → rebuild plugin → **hot-patch `cloudstack-4.23.0.0.jar`** (backup `.bak3`) → restart mgmt → commit `d57c91cdbc` + push |
| 18:24–18:33 | `runDbaasQuery` คืน `jobid+pending` ได้แล้ว, job ถูก dispatch — แต่ **ไม่มี report กลับเลย** → หยุด m2 อ่าน journal จาก disk: mysqld โดน **OOM-kill 17:43** (unattended-upgrades บน Small Instance 512MB) → inject SSH key เข้า instance ตัวเองเพื่อ debug → reboot: mysqld กลับมา, agent active, probe ใหม่ → **ยังค้าง `dispatched`** |
| 06:3x | access.log: poll ครั้งที่มี job ได้ **200 + 202 bytes** (= job JSON ถูกส่งไปแล้วจริง) แต่ไม่มี `reportDbaasJobResult` ตามมาเลย → อ่านโค้ด ApiServlet + agent จนได้ root cause (§7-D3) |
| (เวลาใน access.log ข้ามคืนเพราะ session ค้างข้ามเที่ยงคืน) | |

## 2. Disk — before/after + สิ่งที่ลบ

ก่อน: `/` = **278M (100%)** → หลัง: **13–16G free** (จบรอบที่ 13G)

| ลบอะไร | ได้คืน | undo |
| --- | --- | --- |
| `loki` + log มัน (syslog.1 8.4G, syslog.2.gz, truncate syslog, vacuum journal 3.4G) — **user อนุญาต** | ~12.6G | `apt-get install loki && systemctl enable --now loki` (จริงจบ: purge ตาม ADDENDUM §B รวม alloy + node-exporter — undo = `apt-get install alloy loki prometheus-node-exporter prometheus-node-exporter-collectors`) |
| `cloudstack-marvin` + `cloudstack-integration-tests` (พังค้าง, บล็อก dpkg ทั้งระบบ) | — | ลง .deb ใหม่จาก build |
| `target/`, `/tmp` log เก่า, `__pycache__`, apt cache, `/root/.npm`, `/root/.cache`, `dbaas-deb`+`dbaas-v2-deb` (816M) | ~1G | regenerate ได้ |
| (เคลื่อนย้าย ไม่ใช่ลบ) backup image 6 ตัวของรอบนี้ `tplbackup/*.bak-20260906` → **gz ไป `/root/tplbackup-20260906/`** | pool 16.4G | `gunzip -c /root/tplbackup-20260906/X.gz > /export/primary/tplbackup/X` |

**ไม่แตะ**: `tplbackup` ของเดิม (Sep 5, 2 ไฟล์) / secondary templates / DATA-73 / `/root/.zcode` / `~/.claude`

## 3. Builds (tail จริง)

1. plugin `-am install`: `[INFO] BUILD SUCCESS / Total time: 12:51 min`
2. full server: คำสั่งใน prompt (`-Dnoredist`) พัง —
   `ERROR ... cloud-vmware-base: Could not resolve ... vmware-vim25:jar:8.0` →
   **deviation**: ใช้ `mvn -T2 -DskipTests install` (default profile, SUCCESS 6 นาที)
3. UI `npm ci && npm run build`: `ERR_OSSL_EVP_UNSUPPORTED` (webpack 4 + Node 22)
   → `NODE_OPTIONS=--openssl-legacy-provider` → nodejs/npm โดน autoremove ตัดเอง →
   ติดตั้งคืน + รัน as nacl — **สถานะ: กำลังรัน/ยังไม่ได้เก็บผล** (ต้องเช็ค
   `/tmp/build3-ui.log` และ `dist/` ก่อนใช้)

## 4. Templates — patch ครบ 4 ตัว (option 2: chroot + policy-rc.d)

ทุกตัว: backup ก่อน (บัดนี้ gz ที่ `/root/tplbackup-20260906/`) → `sync→umount→qemu-nbd -d` → `qemu-img check` = **No errors** ทั้งหมด

| tpl | สำเนาที่ patch | ไฟล์ §3 ครบ 8 แถว | unit symlinks | python lib |
| --- | --- | --- | --- | --- |
| 210 mysql | secondary + cache | ✓ (ตรวจใน image) | ✓ | python3-pymysql 1.0.2 (`ii`, import ok) |
| 211 mariadb | secondary + cache (หลัง expunge final3) | ✓ | ✓ | python3-pymysql ✓ |
| 212 postgresql | secondary (ไม่มี cache) | ✓ | ✓ | python3-psycopg2 2.9.5 ✓ |
| 213 mongodb | secondary (ไม่มี cache) | ✓ | ✓ | python3-bson + python3-pymongo 3.11.0 ✓ |

หมายเหตุ: chroot ไม่ใช่ apt ใน image (DNS ตายใน chroot, UDP โดนกรอง) — โหลด
.deb จาก Debian pool บน host แล้ว `dpkg -i` ใน chroot ทีละตัว

## 5. Host changes + undo (ทุกอัน)

| เปลี่ยน | undo |
| --- | --- |
| purge monitoring + ถอน marvin/integration-tests ค้าง | §2 ของรายงาน |
| ติดตั้ง nodejs/npm คืน | — (คืนสถานะเดิม) |
| expunge `dbaas-final3` | **irreversible — อนุญาตแล้วใน ADDENDUM §D** (เหตุผล: backing-file ของ cache 211, หลักฐาน provisioning อยู่ใน log/audit แล้ว) |
| deploy/destroy instance ของตัวเอง: m1 (destroy), m2 (คงไว้ใช้ต่อ) | destroy m2 ได้ตามใจเจ้าของ |
| inject SSH key (`id_rsa.cloud`) ลง `/home/debian/.ssh/authorized_keys` ของ **m2 ซึ่งเป็น instance ที่ผมสร้างเอง** เพื่อ debug | `ssh ... 'sudo rm /home/debian/.ssh/authorized_keys'` หรือ destroy m2 |
| hot-patch `cloudstack-4.23.0.0.jar` (2 response classes) + restart mgmt 1 ครั้ง | `cp /root/cloudstack-4.23.0.0.jar.bak3 /usr/share/cloudstack-management/lib/cloudstack-4.23.0.0.jar && systemctl restart cloudstack-management` |
| ตั้ง `dbaas.console.enabled = true` | ค่าเดิม `false` — **ปล่อยไว้ true ตั้งใจ** (เหตุผล: matrix ยังไม่จบ เซสชันถัดไปทำต่อทันที; write/drop/datadisk.cleanup ยัง false ครบ) |
| ย้าย backup ของรอบนี้ออกจาก pool | §2 |

## 6. §11 matrix — สถานะตรงๆ

| ข้อ | ผล | หลักฐาน/เหตุผล |
| --- | --- | --- |
| provisioning ไม่ regress | **PASS** | m2: `status: confirmed` (16:53), `testdb1@10.60.0.77` จาก host ได้ |
| agent check-in | **PASS (ระดับ poll)** | `last_seen_at` ขยับทุก ~25–70 s; access.log มือเปล่า `getDbaasAgentJob 200`; **แต่** execution ยังไม่เคยเกิด (ข้อถัดไป) |
| 1–15 ทั้งหมด | **NOT RUN** | บั๊ก D3 (§7) บล็อก: job dispatch ไปแล้วแต่ agent ทิ้งเงียบ — แก้ 1 จุดแล้วรันได้ทั้งชุด |
| ข้อ 15 (VR ดับ) | **NOT RUN** | ยังไปไม่ถึง |

## 7. Defects — แก้แล้ว / root-cause แล้ว / เหลือ

**แก้แล้ว (commit บน `v2` + `ui` ทั้งคู่):**

- **D1** `Gson declares multiple JSON fields 'jobid'` → ทุก console command 431 —
  `DbaasJobResponse`/`DbaasJobResultResponse` ประกาศ `jobId` ทับ `BaseResponse`
  ลบ field ซ้ำ (ใช้ตัวที่ inherit) — **`d57c91cdbc`** + hot-patch jar + restart
- **D2** agent ส่ง `command` ซ้ำ query+body → log เต็มไปด้วย multiple values —
  **`c055110532`** (ยังไม่ได้ push ลง image — ต้องเอา `dbaas_agent.py` ใหม่ลง m2
  เพื่อให้ D3 แก้ต่อจนจบ)

**Root-cause แล้ว ยังไม่ได้แก้ (ตัวบล็อก matrix ทั้งชุด):**

- **D3 ตัวหลัก — agent parse ผิด shape**: `ApiServlet` เขียน string ที่
  `authenticate()` คืนออกไปตรงๆ (ไม่ผ่าน response serializer) ดังนั้น
  `getDbaasAgentJob` ตอนมี job คืน **JSON เปล่า** `{"jobid":...,"type":...,
  "db_role":...,"payload":...,"row_limit":...}` (ตรงกับ access.log 200/202 bytes)
  แต่ `dbaas_agent.py:long_poll()` ทำ `payload.get("getdbaasagentjobresponse", {})`
  (หา wrapper แบบ response ปกติ) → เจอ `{}` เสมอ → `return None` → **job ถูก
  dispatch แล้วแต่ไม่มีการ execute/report ตลอดชีวิตระบบ** อาการที่เห็นตรงเป๊ะ:
  job ค้าง `dispatched`, ไม่มี `reportDbaasJobResult` ใน access.log เลย
  **วิธีแก้ (1 จุด)**: ใน `long_poll` รองรับสอง shape —
  `job = payload.get("getdbaasagentjobresponse") or (payload if "jobid" in payload else {})`
  แล้ว commit + อัปเดต `dbaas_agent.py` ใน m2 (ผ่าน SSH ที่ inject ไว้) + รัน matrix ต่อ
  *(หมายเหตุ: อาจเลือกแก้ฝั่ง server ห่อ wrapper แทนก็ได้ แต่แก้ agent กระทบน้อยกว่า
  เพราะ template 4 ตัวพร้อมอยู่แล้วและ hot-patch jar ต้องร้อง restart mgmt)*

**พบเพิ่ม (ไม่บล็อก แต่ต้องรู้):**

- **D4** deploy แล้ว "start เลย" ก่อน `createDatabase` = cloud-init cache จาก boot
  แรก (ไม่มี userdata) ทำให้ boot ถัดไปไม่อ่าน userdata ใหม่ (instance-id เดียวกัน)
  → provisioning ไม่เกิดเลย ทางเข้าที่ถูก: `startvm=false` แล้วค่อย createDatabase
  (m1 โดนแล้ว destroy) — ควรเขียน guard/เตือนใน plugin หรือ UI ภายหลัง
- **D5** mysqld ใน guest โดน **OOM-kill โดย unattended-upgrades** บน Small Instance
  (512MB) เมื่อ 17:43 — สภาพแวดล้อม ไม่ใช่โค้ด; ควรปิด unattended-upgrades ใน
  template หรือใช้ offering ใหญ่กว่าตอนเทส
- **D6** pool primary เต็มเพราะ backup ของรอบนี้วางใน `/export/primary` — ย้ายแล้ว;
  บทเรียน: backup template ห้ามวางบน pool ที่ planner มองเห็น

## 8. Settings ตอนจบ

```
dbaas.console.enabled          = true   ← ทิ้งไว้ (matrix ยังไม่จบ เซสชันหน้าทำต่อ)
dbaas.console.write.enabled    = false  ← ไม่เคยเปิด (ยังไม่ถึงข้อ 5/7)
dbaas.console.drop.enabled     = false  ← ปิดทั้งคืนตามคำสั่ง
dbaas.datadisk.cleanup.enabled = false
```

## 9. DATA-73 / tplbackup — ไม่ถูกแตะ

`DATA-73`: Ready, unattached, ไม่มี marker / `tplbackup/` เดิม (Sep 5) ไม่มีการ
แก้ไข — ที่เหลือในนั้นคือ `.bak-20260906` ของผมที่ย้ายออกแล้ว (§2)

## 10. §12.5 บรรทัดเดียว — ตอบจากของที่เห็นจริง

**ยังตอบไม่ได้**: สิ่งที่พิสูจน์แล้วมีเพียง provisioning แนว config-drive ไม่
regress และ transport ครึ่งเดียว (agent→MS poll 25 s จริง) — **query/browse/create
ยังไม่เคย execute สักครั้งบนระบบจริง** เพราะ D3 และ VR-stopped ยังไม่ได้เทส
(drop table ยังปิดอยู่ = จริง, ตั้งใจ)

## 11. สิ่งที่ไม่ได้เทส (ตรงๆ)

- §11 ข้อ 1–15 ทั้งหมด (บล็อกด้วย D3 — แก้ 1 บรรทัดแล้วรันได้ทั้งชุดบน m2)
- build 3 (UI) ไม่ได้เก็บผลล่าสุด / ไม่ได้ deploy UI ใหม่
- per-engine spot check (211/212/213): รอ mysql matrix ผ่านก่อนตามลำดับ prompt
- timing ข้อ §12.2-6 (submit→result มัธยฐาน 5 ครั้ง) — รอ D3

## 12. งานถัดไปตามลำดับที่ควรทำ

1. แก้ D3 ใน `dbaas_agent.py` (1 จุด ตาม §7) → commit → push
2. scp `dbaas_agent.py` (+ fix D2 รวมอยู่แล้วใน `c055110532`) ลง m2:
   `/opt/dbaas/agent/dbaas_agent.py` แล้ว `sudo systemctl restart dbaas-agent`
3. รัน §11 ข้อ 1–6 → 7–14 → 15 บน m2 (write เปิดเฉพาะข้อ 5/7 แล้วปิด)
4. เก็บ build 3 → deploy UI
5. per-engine: deploy 211/212/213 ทีละตัว (ข้อ 1–3 + write 1 ข้อ)

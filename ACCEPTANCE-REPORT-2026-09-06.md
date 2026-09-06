# ACCEPTANCE REPORT — host round (2026-09-06) — **INTERIM**

งาน host ตาม `PROMPT-GLM-HOST-ACCEPTANCE.md` — **ยังไม่จบ**: build ชั้น 2
(เต็มเครื่อง) กำลังรันอยู่, template 212/213/211-secondary ยังไม่ patch,
acceptance matrix §11 ทั้ง 15 ข้อยังไม่เริ่ม รายงานนี้อัปเดตตามความจริง
ณ 16:00 UTC แล้วจะแทนที่ด้วยฉบับสุดท้ายเมื่อจบ

---

## 1. Disk — ก่อน/หลัง และสิ่งที่ลบ

`df -h /` **ก่อน** (เริ่มงาน):

```
Filesystem                      Size  Used Avail Use% Mounted on
/dev/mapper/ubuntu--vg-root      42G   39G  278M 100% /
```

(แย่กว่าที่ prompt ระบุ 2.2G — เหลือ 278M)

`df -h /` **หลังเคลียร์**:

```
Filesystem                      Size  Used Avail Use% Mounted on
/dev/mapper/ubuntu--vg-root      42G   34G  5.4G  87% /
/dev/mapper/ubuntu--vg-home      35G   20G   14G  59% /export/primary
```

สิ่งที่ลบ (ทั้งหมดอยู่ในขอบเขต "safe" ของ prompt §2 + ที่ user อนุญาตเพิ่ม):

| สิ่งที่ลบ | ขนาดคืน | หมายเหตุ |
| --- | --- | --- |
| `loki` (stop+disable+`apt-get remove`, ลบ `/tmp/loki`) | ~80M RAM + ต้นตอ log | **user อนุญาตผ่าน chat** — config คงไว้ที่ `/etc/loki/` เพื่อ undo |
| `/var/log/syslog.1` | 8.4G | ปลายทางของ loki debug flood (`mock.go Get - deadline exceeded` ต่อเนื่อง) |
| `/var/log/syslog.2.gz` | 122M | rotated |
| truncate `/var/log/syslog` (ไฟล์ปัจจุบัน) | 845M | truncate ไม่ลบ เพราะ rsyslog ถือ fd อยู่ |
| `journalctl --vacuum-size=200M` | 3.4G | archived journals |
| log build เก่าใน `/tmp`: `dbaas-build*.log`, `fullbuild*.log`, `mvn-*.log`, `cloudstack-build.log`, `build-plugin.log`, `hotpatch{,2,3}/` | ~12M | ของ session ก่อน สร้างใหม่ได้ |
| `__pycache__` ใน repo + `/root` | ~10M | |
| `target/` ทุกโมดูลใน repo | ~800M | prompt ระบุ safe; build จะ regenerate — ทำให้ build สะอาด ไม่มี class เก่าจากตอน hot-patch หลงเหลือ |
| `apt-get clean` | 161M | |

**ไม่ลบ** (ตามกฎ หรือไม่อยู่ใน safe list): `/export/primary/tplbackup/**`,
`/export/secondary/template/**`, `*.qcow2` ใน `/export/primary`, `/root/*.jar.bak*`,
`/root/*.sql.gz`, `/root/extensions-dbaas-backup-*.tgz`, `swap.img`,
และไฟล์ evidence ใน `/tmp` (`accept3.*`, `base.json`, `ours.json`, `theirs.json`,
`roundtrip-test.log`)

## 2. Builds (เรียงลำดับ 3 ชั้น)

**ชั้น 1 — plugin** `mvn -pl plugins/integrations/dbaas -am install` (รวม test):

```
[INFO] BUILD SUCCESS
[INFO] Total time:  12:51 min
```

**ชั้น 2 — full server**: คำสั่งตาม prompt (`mvn -T2 -DskipTests -Dnoredist install`)
**พัง**:

```
ERROR] Failed to execute goal on project cloud-vmware-base: Could not resolve
dependencies ... com.cloud.com.vmware:vmware-vim25:jar:8.0, com.cloud.com.vmware:vmware-pbm:jar:8.0:
Could not find artifact com.cloud.com.vmware:vmware-vim25:jar:8.0 in repo.jenkins-ci.org.releases
```

ต้นเหตุ (ไม่ใช่พื้นที่ดิสก์): pom ของ fork นี้ activate profile `vmware` เมื่อ
property `noredist` **มีอยู่** (activation แบบไม่มี `!`) — คือ `-Dnoredist` =
**ดึง** vmware-base เข้ามา และ VMware SDK jar เป็น operator-provided (404 ทุก
repo, ไม่มี jar ใน `deps/`) — เรื่องเดียวกับที่ `CONSOLE-REPORT-2026-09-06.md`
blocker table จดไว้ และ full build ที่เคย SUCCESS (รอบกลางคืน, 2:12 ชม.) ใช้
**default profile ไม่มี -Dnoredist**

**Deviation จาก prompt (บันทึกตามหน้าที่)**: รันแทนด้วย `mvn -T2 -DskipTests
install` (default profile) — log: `/tmp/build2-server.log` — **กำลังรัน**
(เริ่ม 15:41, คาด ~2 ชม. ตามรอบที่เคย SUCCESS) ยังไม่มีข้อสรุป

**ชั้น 3 — UI** `cd ui && npm ci && npm run build`: ยังไม่รัน (รอชั้น 2 จบตาม
ลำดับของ prompt)

## 3. Template rebuild

**วิธีที่เลือก: option 2 — chroot เข้า mounted image** เหตุผล: (1) delta ต่อ
image เล็กและควบคุมได้ = 1 deb (python lib) + 12 ไฟล์ + symlink 2 ตัว, engine
เองอยู่ใน image แล้ว (2) option 1 จบด้วย `createTemplate` ที่ได้ template
artifact ใหม่ หลุดจาก id 210–213 ที่ deployment/config อ้างอยู่ (3) flow
qemu-nbd พิสูจน์กับ image ชุดนี้บน host นี้มาแล้ว (RUNBOOK)

มาตรการกันพลาดตามที่ prompt กำหนด: backup ก่อน patch ทุกไฟล์ลง
`/export/primary/tplbackup/`, ใส่ `usr/sbin/policy-rc.d` (exit 101) กัน
postinst เรียก service, ลบทิ้งก่อนปิด chroot, ตรวจ `dpkg -l` หลังติดตั้ง,
ปิดตามลำดับ `sync → umount → qemu-nbd -d` และ `qemu-img check` ทุกครั้ง

**ปัญหา DNS ใน chroot (บันทึกไว้เพราะเจอซ้ำแน่)**: `/etc/resolv.conf` ใน image
เป็น symlink ห้อย (→ `/run/systemd/resolve/resolv.conf`) ตอน offline — apt ใน
chroot หา DNS ไม่เจอ และเน็ตของ host เองก็ resolve ผ่าน stub 127.0.0.53
ซึ่งถึงกัน UDP จาก chroot — ทางแก้ที่ใช้: โหลด .deb ของ bookworm บน host
(host ใช้เน็ตได้) แล้ว `dpkg -i` ใน chroot:
`python3-pymysql_1.0.2-2+deb12u1_all.deb` (Depends: python3:any อย่างเดียว,
ตรวจ import ผ่าน) สถานะ resolv.conf ใน image จบ = เหมือนเดิม (symlink ห้อย,
`/run` ว่าง) — ไม่มีสิ่งแปลกหลงเหลือ

### สิ่งที่ patch แล้ว — template 210 (dbaas-mysql-v2) ✅

ครบทั้งสองสำเนา ตามตาราง 8 แถวของ prompt §3 — ตรวจแล้วทั้งคู่:

```
agent-env ใน firstboot.sh  = 2        engine marker = mysql.sh
pipefail-fix ใน mysql.sh   = 1        dbaas-report-retry ใน firstboot = 2
/opt/dbaas/agent/ = dbaas_agent.py, dbaas-agent-env.sh (0755)
units: dbaas-agent.service, dbaas-report-retry.{service,timer} (0644)
symlinks: multi-user.target.wants/dbaas-agent.service,
          timers.target.wants/dbaas-report-retry.timer
python3-pymysql 1.0.2-2+deb12u1 = ii, import ok
```

- secondary `/export/secondary/template/tmpl/2/210/97680484-…qcow2`:
  backup → `tplbackup/97680484-….qcow2.bak-20260906` (1.98G)
  ```
  qemu-img check: No errors were found on the image.
  30469/49152 = 61.99% allocated
  ```
- primary cache `/export/primary/1d9e7b9c-…`:
  backup → `tplbackup/1d9e7b9c-….bak-20260906` (1.98G)
  ```
  qemu-img check: No errors were found on the image.
  30158/49152 = 61.36% allocated
  ```

(คำสั่ง patch cache โดน cancel ระหว่าง session — ตรวจซ้ำหลัง cancel: เนื้อหา
เขียนครบก่อน cancel และ `qemu-img check` ผ่าน, nbd ไม่ค้าง)

### เปลี่ยนแผนจาก "mariadb ก่อน" เป็น "mysql ก่อน" — เหตุผลตาม §5

`dbaas-final3` (VM id 74, กำลังรัน, ผมไม่ได้สร้าง → ห้ามแตะ) ใช้ template 211
และ ROOT volume ของมัน (921cfe4c-…) **มี backing file = 8ceff582-… คือ
primary cache ของ template 211**:

```
virsh dumpxml i-2-74-VM:
  <backingStore type='file' index='4'>
    <source file='/mnt/a081c924-…/8ceff582-71ee-41c9-aa8d-82f0a72fc488'/>
```

patch cache ที่มี VM อื่นถือ overlay อยู่ = เสี่ยงพัง image ของ instance ต้องห้าม
(RUNBOOK Step 0 เตือนไว้ชัด) → ย้ายตัวพิสูจน์ end-to-end ไป mysql (210) ซึ่ง
ตรวจแล้วว่า **ไม่มี VM ใด backing ไปที่ cache 1d9e7b9c** (volume ทุกตัวของ
template 210 = Expunged; ตรวจ `virsh dumpxml` ทุก VM ที่รันอยู่) — จุดประสงค์
"เครื่องเดียวที่ใช้ได้จริงสอนทุกอย่าง" คงเดิม

**ตัวเลือกที่ต้อง user ตัดสินใจสำหรับ cache 211**: (a) รอ dbaas-final3 ถูกลบ
แล้วค่อย patch cache, หรือ (b) user อนุญาตหยุด final3 ชั่วคราวเพื่อ patch
(ผมจะไม่ทำเอง)

### ยังไม่ patch

- 211 secondary (+pymysql), 211 primary cache (**บล็อก** ตามข้างบน)
- 212 secondary (+python3-psycopg2), 213 secondary (+python3-pymongo)
  (ไม่มี cache — ยังไม่เคย deploy)

## 4. Host changes ทั้งหมด + undo

| การเปลี่ยนแปลง | undo |
| --- | --- |
| `systemctl stop loki` + `systemctl disable loki` + `apt-get remove -y loki` + ลบ `/tmp/loki` | `apt-get install loki && systemctl enable --now loki` (config เดิมคงอยู่ที่ `/etc/loki/config.yml`) |
| ลบ `/var/log/syslog.1`, `/var/log/syslog.2.gz` | ไม่ undo ได้ (log เก่า — ผ่านการอนุญาต) |
| truncate `/var/log/syslog` | ไม่ undo ได้ (log ต่อเนื่อง — ผ่านการอนุญาต) |
| `journalctl --vacuum-size=200M` | ไม่ undo ได้ (archived journals) |
| ลบ log build เก่า + `__pycache__` + `target/` + `apt-get clean` | ไม่ undo ได้ (สร้างใหม่ได้ทั้งหมด) |
| backup image 210 ×2 ลง `tplbackup/…bak-20260906` | ลบไฟล์ backup ทิ้งเมื่อพอใจผล (ผมไม่ลบเอง) |
| patch image 210 secondary + cache (files + units + pymysql) | กู้ด้วย `cp -a tplbackup/<ชื่อ>.bak-20260906 <IMG>` แล้ว `qemu-img check` |
| mount nbd0/mnt ชั่วคราวระหว่าง patch | ถอดแล้ว (`umount`, `qemu-nbd -d`) ไม่เหลือสถานะ |
| **dbaas.\* global settings** | **ไม่มีการเปลี่ยนใด ๆ จนถึงตอนนี้** |

## 5. §11 matrix — ยังไม่เริ่ม (ทุกข้อ = not run)

ข้อ 1–15 ทั้งหมดยังไม่รัน ตามลำดับที่ prompt วางไว้จะเริ่มหลัง template 212/213
เสร็จ + build ชั้น 2/3 จบ: provisioning ไม่ regress → agent check-in (journalctl
+ last_seen_at) → ข้อ 1–6 → ข้อ 7–14 → ข้อ 15 ยังไม่มีข้อไหนผ่านหรือ fail
เลย — จะไม่ claim ล่วงหน้า

## 6. ข้อ 15 (VR ดับ) — not run

## 7. Defects ที่เจอ

| # | อาการ | การจัดการ |
| --- | --- | --- |
| 1 | `-Dnoredist` พัง build (vmware-vim25 8.0 ไม่มีที่ไหน) — pom activate profile vmware เมื่อ property มีอยู่ | deviation ตามเอกสารเดิมของ repo: ใช้ default profile; ไม่ใช่บั๊กโค้ด DBaaS, ไม่แก้ pom (นอกขอบเขต) |
| 2 | DNS ใน chroot ใช้ไม่ได้ (symlink ห้อย + UDP ถูกกรอง) | workaround ด้วย .deb ผ่าน host; ไม่แตะ image resolver |
| 3 | loki พ่น debug ลง syslog ~8.4G/วัน จน `/` เต็ม 100% | ลบตามอนุญาตของ user (หัวข้อ 1/4) |
| 4 | คำสั่ง patch cache โดน cancel กลางทาง | ตรวจซ้ำ: เขียนครบก่อน cancel, check ผ่าน, ไม่มี mount ค้าง |

ยังไม่พบ defect ใน plugin/script/UI (ที่มาจากการรันจริง — จะบันทึกตอนเริ่ม matrix)

## 8. Settings — สถานะ

เช็คด้วย SQL ตอนเริ่มงาน (ยังไม่เปลี่ยนอะไร):

```
dbaas.console.enabled          = false   ← ค่าเดิม บันทึกไว้ จะเปิดตอนเทสเท่านั้น
dbaas.console.write.enabled    = false
dbaas.console.drop.enabled     = false
dbaas.datadisk.cleanup.enabled = false
```

## 9. DATA-73 / tplbackup — ไม่ถูกแตะ (ยืนยัน ณ 15:5x)

```
volumes: id=90 name=DATA-73 state=Ready removed=NULL instance_id=NULL (unattached)
tplbackup/: 8ceff582….bak, cf6116a9….qcow2.bak  (ของเดิม — mtime Sep 5 ไม่เปลี่ยน)
            + ไฟล์ใหม่ 2 ตัวที่ผมสร้าง (bak-20260906 ×2) — เป็นการเพิ่ม ไม่ใช่แตะของเดิม
```

## 10. บทสรุปบรรทัดเดียว (§12.5)

**ยังตอบไม่ได้** — matrix §11 ยังไม่ถูกรัน ข้อความนี้จะตอบจากของจริงเท่านั้น
(ห้ามตอบจาก design) — เมื่อจบรอบแล้วจะตอบชัด: tenant บน network ที่ mgmt เข้า
ไม่ถึง + VR ดับ browse/query/create table ได้จริงไหม และ drop table ยังปิดอยู่ไหม
(ค่าที่จะไปยืนยัน: `dbaas.console.drop.enabled=false`)

---

## Next steps (ลำดับที่เหลือ)

1. รอ build ชั้น 2 จบ → ชั้น 3 (UI)
2. Patch 211 secondary, 212+213 secondary (+psycopg2/pymongo ด้วยวิธี .deb)
3. Deploy instance จาก template 210 → provisioning → agent check-in
4. รัน matrix §11 ตามลำดับของ prompt → อัปเดตหัวข้อ 5/6/7/8/10 แล้วแทนที่รายงานนี้

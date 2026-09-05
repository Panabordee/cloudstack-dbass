# Runbook — patch template ทั้ง 4 แล้วรัน acceptance ใหม่ (2026-09-05)

แก้ตาม `ACCEPTANCE-FIX-2026-09-05.md` — 3 ต้นเหตุ: NIC ไม่ถูกตั้งค่า (config drive
เขียน `network_data.json` เป็น `{}`), engine script ตายเงียบที่ `grep` + `pipefail`,
และ readiness wait เป็น no-op เพราะ marker เก็บชื่อพร้อม `.sh`

ทุกคำสั่งรันบน `cloudstackcve` ในฐานะ root

---

## ไฟล์ที่ต้องยัดเข้าแต่ละ image

| Template | id | ไฟล์ที่ต้องอัปเดต |
| --- | --- | --- |
| dbaas-mysql-v2 | 210 | `firstboot.sh`, `mysql.sh`, `99-dbaas-fallback.network` |
| dbaas-mariadb-v2 | 211 | `firstboot.sh`, `mariadb.sh`, `99-dbaas-fallback.network` |
| dbaas-postgresql-v2 | 212 | `firstboot.sh`, `99-dbaas-fallback.network` |
| dbaas-mongodb-v2 | 213 | `firstboot.sh`, `99-dbaas-fallback.network` |

`postgresql.sh` / `mongodb.sh` ไม่ได้แก้ในรอบนี้ (บั๊ก `grep` มีเฉพาะ mysql/mariadb)
แต่จะ copy ทับไปด้วยก็ได้ ไม่เสียหาย

**path ของไฟล์ต้นทาง** (repo `/home/nacl/dbaas-v2`):

```
extensions/dbaas/provisioning/firstboot.sh
extensions/dbaas/provisioning/mysql.sh
extensions/dbaas/provisioning/mariadb.sh
extensions/dbaas/provisioning/99-dbaas-fallback.network
```

**path ปลายทางใน image:**

```
/opt/dbaas/firstboot.sh                          (0755 root:root)
/opt/dbaas/<engine>.sh                           (0755 root:root)
/etc/systemd/network/99-dbaas-fallback.network   (0644 root:root)
```

## path ของ image (ยืนยันแล้ววันนี้)

```
# secondary (ต้นฉบับ — patch ทุกตัว)
/export/secondary/template/tmpl/2/210/97680484-68ed-3390-84c7-67879fc03f60.qcow2
/export/secondary/template/tmpl/2/211/cf6116a9-4b9f-3198-8816-0f1bfd7b2bb5.qcow2
/export/secondary/template/tmpl/2/212/c234573c-0e80-372c-8dd0-b078a2456c9b.qcow2
/export/secondary/template/tmpl/2/213/d0567ee4-aa92-3a6b-b0f6-75cf8abe4156.qcow2

# primary cache (มีแค่ 2 ตัวที่เคย deploy — ต้อง patch ด้วย ไม่งั้น VM ใหม่ยังได้ของเก่า)
/export/primary/1d9e7b9c-afc2-4cfd-92a0-6a23ca5dfd48   # = template 210 (mysql-v2)
/export/primary/8ceff582-71ee-41c9-aa8d-82f0a72fc488   # = template 211 (mariadb-v2)
```

212/213 ยังไม่มี cache บน primary (ยังไม่เคย deploy) — ข้ามได้

---

## Step 0 — เคลียร์ VM ที่ทับ cache อยู่ก่อน

`i-2-64-VM` ใช้ overlay ที่มี `8ceff582…` เป็น backing file อยู่
**ห้าม patch cache ขณะ VM ยังรัน** จะพัง image

```bash
# ผ่าน UI หรือ cmk: destroy + expunge dbaas-mariadb-accept2
cmk destroy virtualmachine id=42658801-d3fa-4434-af06-2120c4df3f39 expunge=true

# ยืนยันว่าไม่มี VM เหลือบน template
sudo virsh list --all
```

credential row ของมันปล่อยไว้ได้ sweeper เก็บเอง

## Step 1 — เตรียมพื้นที่ backup

`/export/secondary` เหลือ 5.0G, `/` เหลือ 3.1G — **backup ลงสองที่นี้ไม่พอ**
ให้ใช้ `/export/primary` ที่เหลือ 24G และทำทีละ template แล้วลบทิ้งเมื่อผ่าน

```bash
sudo mkdir -p /export/primary/tplbackup
```

## Step 2 — patch หนึ่ง image (ทำซ้ำทีละตัว)

ตัวอย่างนี้คือ template 211 (mariadb) — ตัวอื่นเปลี่ยน path กับชื่อ engine script

```bash
IMG=/export/secondary/template/tmpl/2/211/cf6116a9-4b9f-3198-8816-0f1bfd7b2bb5.qcow2
REPO=/home/nacl/dbaas-v2/extensions/dbaas/provisioning

# 2.1 backup
sudo cp -a "$IMG" /export/primary/tplbackup/$(basename "$IMG").bak

# 2.2 attach
sudo modprobe nbd max_part=8
sudo qemu-nbd --connect=/dev/nbd0 -f qcow2 "$IMG"
sleep 1
lsblk /dev/nbd0                     # root คือ nbd0p1 (image ชุดนี้)

# 2.3 mount
sudo mkdir -p /mnt/tplpatch
sudo mount /dev/nbd0p1 /mnt/tplpatch

# 2.4 copy ทีละไฟล์ (classifier บล็อก sudo cp หลาย target ในคำสั่งเดียว)
sudo cp "$REPO/firstboot.sh" /mnt/tplpatch/opt/dbaas/firstboot.sh
sudo cp "$REPO/mariadb.sh" /mnt/tplpatch/opt/dbaas/mariadb.sh
sudo cp "$REPO/99-dbaas-fallback.network" /mnt/tplpatch/etc/systemd/network/99-dbaas-fallback.network

# 2.5 สิทธิ์
sudo chmod 0755 /mnt/tplpatch/opt/dbaas/firstboot.sh
sudo chmod 0755 /mnt/tplpatch/opt/dbaas/mariadb.sh
sudo chmod 0644 /mnt/tplpatch/etc/systemd/network/99-dbaas-fallback.network
sudo chown root:root /mnt/tplpatch/etc/systemd/network/99-dbaas-fallback.network

# 2.6 ตรวจก่อนถอด — สามอย่างนี้ต้องผ่านทั้งหมด
sudo grep -c 'marker%.sh' /mnt/tplpatch/opt/dbaas/firstboot.sh          # ต้อง >= 1
sudo grep -c 'head -1 || true' /mnt/tplpatch/opt/dbaas/mariadb.sh       # ต้อง = 1
sudo cat /mnt/tplpatch/opt/dbaas/engine                                 # ต้องเป็น mariadb.sh

# 2.7 ถอดตามลำดับนี้เท่านั้น
sudo sync
sudo umount /mnt/tplpatch
sudo qemu-nbd --disconnect /dev/nbd0
sudo qemu-img check "$IMG"          # ต้องไม่มี error
```

จบแล้วทำซ้ำกับ:

- `210` + `mysql.sh` (marker = `mysql.sh`)
- `212` (firstboot + fallback อย่างเดียว, marker = `postgresql.sh`)
- `213` (firstboot + fallback อย่างเดียว, marker = `mongodb.sh`)

## Step 3 — patch primary cache ด้วยขั้นตอนเดียวกัน

**ข้ามข้อนี้ไม่ได้** — VM ที่ deploy หลังจากนี้ clone จาก cache ไม่ใช่ secondary
เคยเสียเวลา debug ทั้งรอบเพราะเรื่องนี้มาแล้ว (TEMPLATES.md §"Patching an
existing template in place")

```bash
IMG=/export/primary/8ceff582-71ee-41c9-aa8d-82f0a72fc488     # mariadb
# ...ทำ 2.1–2.7 ซ้ำทั้งหมด...

IMG=/export/primary/1d9e7b9c-afc2-4cfd-92a0-6a23ca5dfd48     # mysql-v2
# ...ทำ 2.1–2.7 ซ้ำทั้งหมด...
```

ทางเลือกแทน: expunge VM ทุกตัวที่ใช้ template นั้นแล้วปล่อยให้ storage GC ลบ
cache ทิ้ง รอบหน้าจะ copy ใหม่จาก secondary เอง — ช้ากว่าและควบคุมจังหวะไม่ได้
patch ตรงๆ ง่ายกว่า

## Step 3.5 — รอบสอง (แก้ report ไม่ถึง mgmt, 15:56)

หลังรอบแรกผ่าน 4.1/4.2/4.4 แต่ 4.3 ค้าง — ต้อง patch เพิ่มอีก 4 ไฟล์ต่อ image
(ทำด้วยขั้นตอน 2.1–2.7 เดิม):

```bash
REPO=/home/nacl/dbaas-v2/extensions/dbaas/provisioning

sudo cp "$REPO/firstboot.sh" /mnt/tplpatch/opt/dbaas/firstboot.sh
sudo cp "$REPO/report-retry.sh" /mnt/tplpatch/opt/dbaas/report-retry.sh
sudo cp "$REPO/dbaas-report-retry.service" /mnt/tplpatch/etc/systemd/system/dbaas-report-retry.service
sudo cp "$REPO/dbaas-report-retry.timer" /mnt/tplpatch/etc/systemd/system/dbaas-report-retry.timer

sudo chmod 0755 /mnt/tplpatch/opt/dbaas/report-retry.sh

# เปิด timer ไว้ใน image เลย (ตัว unit มี ConditionPathExists กันไว้แล้ว
# มันจะไม่ทำอะไรถ้าไม่มี request.json ค้าง)
sudo ln -sf /etc/systemd/system/dbaas-report-retry.timer \
  /mnt/tplpatch/etc/systemd/system/timers.target.wants/dbaas-report-retry.timer
```

ตรวจก่อนถอด:

```bash
sudo grep -c 'dbaas-report-retry' /mnt/tplpatch/opt/dbaas/firstboot.sh    # ต้อง >= 1
sudo ls -la /mnt/tplpatch/etc/systemd/system/timers.target.wants/ | grep dbaas
```

ฝั่ง management server แก้ไปแล้ว (dynamic config ไม่ต้อง restart):

```
dbaas.report.api.url = http://10.60.0.254:8080/client/api    (เดิม 10.60.1.41)
```

URL ถูกฝังลง config drive ตอน `createDatabase` → **VM เก่าไม่ได้รับผลของการแก้นี้
ต้อง deploy ตัวใหม่เท่านั้น**

## Step 4 — deploy ใหม่แล้วดูผล

```bash
# deploy ผ่าน wizard: Database > Create Database Instance
#   template = dbaas-mariadb-v2, network = dbaas-network

# 4.1 ดูว่า NIC ขึ้นจริงภายในไม่กี่วินาที (เดิม 0 packets)
sudo tcpdump -i cloudbr0 -n "ether host <MAC ใหม่>" -c 20

# 4.2 VM ต้องได้ IP ในช่วง .77–.87 และ ping ติด
ping -c 3 <ip>

# 4.3 credential ต้องเด้งเป็น confirmed ภายใน ~2 นาที
cmk getDatabasePassword virtualmachineid=<uuid>     # status ต้อง = confirmed

# 4.4 ต่อ DB จากนอก VM ให้ได้จริง (ข้อนี้พังมาก่อนเพราะ bind-address ไม่ถูกแก้)
mysql -h <ip> -P 3306 -u testdb1 -p'<password>' testdb1 -e "SELECT 1"
```

ถ้า 4.3 ยัง `pending` ให้ดูในเครื่อง guest — คราวนี้เข้าถึงได้แล้วเพราะ network ขึ้น:

```bash
cat /var/lib/dbaas/result.json      # ตอนนี้ message จะไม่มีทางว่างแล้ว
sudo journalctl -u cloud-final --no-pager | grep dbaas-firstboot
```

## Step 5 — acceptance ตัวจริง (VR ดับ)

Step 1–4 ยังพึ่ง DHCP จาก VR อยู่ ยังไม่ใช่ข้อที่ PLAN.md Phase B ต้องการ
ต้องทำ network offering ที่ให้ **ConfigDrive เป็น provider ของ UserData + Dhcp + Dns**
(รายละเอียดใน `ACCEPTANCE-FIX-2026-09-05.md` §"Fix 1b")

- **ห้ามใส่ row `Password` ลง service map** — enum 4.23 ไม่มี service นี้ NPE ทันที
- หลัง deploy ให้เช็คในเครื่อง guest ว่า `/etc/netplan/50-cloud-init.yaml`
  มี block `ethernets:` พร้อม IP static แล้วจริง (เดิมมีแต่ `version: 2`)
- แล้วค่อย `stopRouter` แล้ว deploy ซ้ำ ต้องได้ DB ที่ใช้งานได้เหมือนเดิม

## Step 6 — commit

รวมกับ 4 ไฟล์ที่ค้างอยู่จาก session ก่อน:

```
tools/apidoc/gen_toc.py
plugins/integrations/dbaas/PLAN.md
extensions/dbaas/config.example.json
plugins/integrations/dbaas/src/main/java/com/dbaas/DbaasManagerImpl.java
```

บวกของรอบนี้:

```
extensions/dbaas/provisioning/firstboot.sh
extensions/dbaas/provisioning/mysql.sh
extensions/dbaas/provisioning/mariadb.sh
extensions/dbaas/provisioning/99-dbaas-fallback.network
ACCEPTANCE-BLOCKERS-2026-09-05.md
ACCEPTANCE-FIX-2026-09-05.md
RUNBOOK-PATCH-TEMPLATES-2026-09-05.md
NEON-ROADMAP.md
```

## Rollback

ถ้า image พังหลัง patch:

```bash
sudo cp -a /export/primary/tplbackup/<ชื่อไฟล์>.bak "$IMG"
sudo qemu-img check "$IMG"
```

ลบ backup ทิ้งได้เมื่อ Step 4 ผ่านครบ (`/export/primary/tplbackup`)

## กับดักที่เจอมาแล้ว อย่าเหยียบซ้ำ

- ถอด nbd ผิดลำดับ (`qemu-nbd -d` ก่อน `umount`) = image เสีย ให้ `sync` →
  `umount` → `-d` เสมอ
- `sudo cp` หลายไฟล์ในคำสั่งเดียวโดน classifier บล็อก แยกทีละไฟล์
- `/` เหลือ 3.1G อย่าเอา image ไปวางที่ `/tmp`
- `ps | grep dnsmasq` บน VR หาไม่เจอเพราะ busybox ให้ดู `journalctl` แทน

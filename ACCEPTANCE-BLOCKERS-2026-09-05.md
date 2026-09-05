# รายงานปัญหาที่เหลือ — DBaaS v2 acceptance (2026-09-05, รอบหลัง patch)

สรุป: **DB สร้างได้ ใช้ได้จริง** (พิสูจน์แล้วทุกชั้น) — เหลือบั๊กเดียวคือ
**status ค้าง `pending`** เพราะ report จาก guest ไปถึง API server แต่
**API ไม่เคย dispatch เข้า plugin** → `applyProvisioningReport` ไม่เคยรัน

---

## 1. สิ่งที่ผ่านแล้ว (ยืนยันแล้วทั้งหมด)

| ตรวจ | ผล |
| --- | --- |
| Step 0–3 (expunge, backup, patch 4 secondary + 2 primary cache) | ✓ ตรวจ marker/pipefix/engine ทุกไฟล์ + `qemu-img check` สะอาด |
| 4.1 NIC ขึ้นจริง | ✓ tcpdump เห็น DHCP Request/Reply (เดิม 0 packets) — `99-dbaas-fallback.network` ทำงาน |
| 4.2 IP ใน .77–.87 + ping | ✓ (accept3 = .77) |
| **4.4 ต่อ DB จากนอก VM** | ✓ **`mysql -h 10.60.0.77 -u testdb1 -p'…' testdb1` → `SELECT 1` ผ่าน** (bind-address เปิดแล้ว) |
| firstboot ใน guest | ✓ journal: `engine ready (waited 0s)` → provision สำเร็จ → `provisioned successfully` |
| SSVM/CPVM | ✓ destroy แล้วสร้างใหม่: `s-68-VM` / `v-69-VM` = **4.23.0.0** ทั้งคู่ |

## 2. บล็อกเกอร์เดียว: report ถึง API แต่ไม่ถึง plugin

### ห่วงโซ่หลักฐาน

1. firstboot ใน guest: report 5 attempts ล้ม (URL เดิม `10.60.1.41` ไม่ route
   จาก guest) → แก้ global setting เป็น `http://10.60.0.254:8080/client/api`
   (guest ถึงจริง: 401 ใน 7ms ผ่าน L2 ตรง)
2. หลังแก้: report POST **ถึง API server แล้ว** (เห็น 2 request ใน log:
   จาก `.254` 15:56 และ `.77` 16:08)
3. แต่ response = **`HTTP 200`, `Content-Length: 0`, 15 ms** และ plugin
   **ไม่มี log "provisioning report accepted/rejected" เลย** — ทดสอบยิงจาก
   host ตรง ๆ ด้วย dummy token ก็ได้ผลเหมือนกัน (200 ว่าง)
4. ผลคือ `applyProvisioningReport()` **ไม่เคยถูกเรียก** → status ค้าง pending
   และ curl ฝั่ง guest เห็น 200 → firstboot คิดว่ารายงานสำเร็จ → request.json
   ถูกลบ → token raw หาย → **VM เก่า confirm ไม่ได้อีก** (ต้องใช้ VM ใหม่)

### ต้นตอระดับโค้ด (เจอจุดแล้ว)

`APIAuthenticationManagerImpl.getAPIAuthenticator(name)`
(server/src/main/java/com/cloud/api/auth/APIAuthenticationManagerImpl.java:102):

```java
apiAuthenticator = (APIAuthenticator) s_authenticators.get(name).newInstance();
apiAuthenticator = ComponentContext.inject(apiAuthenticator);
```

- `s_authenticators` ผูกชื่อคำสั่ง → **คลาส bean ที่ implement
  `PluggableAPIAuthenticator`** = `DbaasManagerImpl` (เพราะ
  `getAuthCommands()` อยู่บน manager)
- dispatcher (ApiServlet:556) เรียก `apiAuthenticator.authenticate(...)` บน
  **instance ใหม่ของ DbaasManagerImpl** — ซึ่ง**ไม่มี override ของ
  `authenticate()`** (interface ให้ default ว่าง) → ได้ empty response 200
- ส่วน `ReportProvisioningResultCmd.authenticate()` ที่มี logic จริง
  **ไม่เคยถูกเรียก** เพราะ registry ไม่ได้ผูกชื่อคำสั่งเข้ากับคลาส cmd

### ทางแก้ (แนะนำ — แก้ใน source แล้ว hot-patch แบบเดิม)

เพิ่ม override บน `DbaasManagerImpl` ให้ delegate ไปที่ cmd:

```java
@Override
public String authenticate(String command, Map<String, Object[]> params,
        HttpSession session, InetAddress remoteAddress, String responseType,
        StringBuilder auditTrailSb, HttpServletRequest req, HttpServletResponse resp)
        throws ServerApiException {
    ReportProvisioningResultCmd cmd = ComponentContext.inject(ReportProvisioningResultCmd.class);
    return cmd.authenticate(command, params, session, remoteAddress,
            responseType, auditTrailSb, req, resp);
}
// และ getAPIType()/setAuthenticators() ถ้า interface บังคับ
```

(`ComponentContext.inject` จะ inject `@Inject DbaasManager` ให้ cmd เอง —
รูปแบบเดียวกับที่ ApiServer ใช้กับ cmd ปกติ)

หลัง patch: `jar uf` ลง `/usr/share/cloudstack-management/lib/cloudstack-4.23.0.0.jar`
→ restart mgmt → deploy VM ใหม่ → createDatabase → status ควรเด้ง
`pending → confirmed` ภายใน ~2 นาที

## 3. ข้อควรระวังที่ค้นพบระหว่างนี้

- **pod allocator ไม่เช็คข้าม network**: SSVM ใหม่ (s-68-VM) หยิบ mgmt IP
  `10.60.0.77` มาจาก pod CIDR โดยไม่รู้ว่า dbaas-network ใช้ช่วง .77–.87 →
  ชนกับ VM ทดสอบ (แก้แล้วด้วยการ destroy VM ทดสอบ, s-68 ถือ .77 ต่อไป) —
  ระวังตอน recreate system VM รอบหน้า: เช็คว่า mgmt nic ไม่ไปกิน .78–.87
- **ห้ามใส่ row `Password` ลง service map** — `Network.Service` enum ไม่มี →
  NPE ทันที (listNetworks / createVlanIpRange / LB health check)
- client ที่บังคับ TLS ต่อ DB ไม่ได้ (server ไม่มี cert) — ใช้
  `--ssl-mode=DISABLED` ไปก่อน; ใส่ TLS ให้ image = งานอนาคต

## 4. สถานะ VM ทิ้งไว้

| VM | IP | สถานะ | หมายเหตุ |
| --- | --- | --- | --- |
| s-68-VM / v-69-VM | .73 / .71 (+mgmt .77/.74) | Running 4.23.0.0 | อย่าลบ mgmt nic .77 |
| dbaas-debug (`92023dc9-…`) | 10.60.0.80 | Running | keypair `dbaas-debug-key` (nacl id_ed25519), user `debian`, credential debugdb pending-ถาวร |
| dbaas-final2 (`b9f197e7-…`) | 10.60.0.79(เดิม) | Running | credential finaldb pending — ใช้ทดสอบหลัง patch ได้ (ยิง report มือตาม §2 ได้ ถ้ามี token) |

backup: `/export/primary/tplbackup/` (6 ไฟล์ .bak) — ลบได้เมื่อ confirmed ผ่าน

## 5. ยังรอ commit (Step 6)

```
tools/apidoc/gen_toc.py
plugins/integrations/dbaas/PLAN.md
extensions/dbaas/config.example.json
plugins/integrations/dbaas/src/main/java/com/dbaas/DbaasManagerImpl.java
extensions/dbaas/provisioning/firstboot.sh
extensions/dbaas/provisioning/mysql.sh
extensions/dbaas/provisioning/mariadb.sh
extensions/dbaas/provisioning/99-dbaas-fallback.network
ACCEPTANCE-BLOCKERS-2026-09-05.md
ACCEPTANCE-FIX-2026-09-05.md
RUNBOOK-PATCH-TEMPLATES-2026-09-05.md
NEON-ROADMAP.md
```

## 6. ดีไซน์ข้ออื่นที่ควรแก้ตาม (ไม่บล็อก)

- `firstboot.sh`: `rm request.json` ควรเลื่อนไปทำ**เมื่อ report สำเร็จเท่านั้น**
  (ตอนนี้ report พลาด = token หาย = confirm ไม่ได้ตลอดไป)
- `report_result`: เพิ่ม wait-for-network ก่อน curl (เช่น retry
  `ip route get 10.60.1.41` หรือ ping ปลายทาง) — กัน firstboot ยิงก่อน eth0 ตื่น
- ใส่ TLS ให้ engine images (client สมัยใหม่ default บังคับ TLS)

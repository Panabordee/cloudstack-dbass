# Build + Deploy แบบสั้น

## 1. Build

```bash
cd /home/nacl/dbaas-v2
packaging/build-deb.sh -o /home/nacl/dbaas-v2-deb
```

รันนาน (โมดูลเยอะ, ครั้งแรกโหลด dependency ใหม่เพิ่ม) ปล่อยไว้เฉย ๆ

### ถ้าเจอ error พวกนี้

**`Could not resolve dependencies ... cloud-server:jar:4.23.0.0 ... cached failure`**
โมดูลก่อนหน้ายังไม่เคย `install` เข้า local repo ให้ครบ แก้:
```bash
mvn -q -pl <ชื่อโมดูลที่ error> -am install -DskipTests -Dcheckstyle.skip=true -Denforcer.skip=true
```
แล้วรัน build-deb.sh ใหม่

**`createdFiles.lst (Is a directory)`**
build ก่อนหน้าโดน kill กลางคัน ทิ้งขยะไว้ แก้:
```bash
rm -rf <path-ของ-module-ที่ error>/target
```
แล้วรันใหม่

**Test พังยกแผง (เช่น `NetUtilsTest` fail 101/101 พร้อมกัน, `Please refer to dump files`)**
คือ RAM ไม่พอตอนรัน test ไม่ใช่โค้ดพัง ข้าม test ไปเลย:
```bash
ACS_BUILD_OPTS="-DskipTests" packaging/build-deb.sh -o /home/nacl/dbaas-v2-deb
```

## 2. เช็คว่า build เสร็จ ได้ไฟล์อะไร

```bash
ls -la /home/nacl/dbaas-v2-deb/cloudstack-management_4.23.0.0*.deb
ls -la /home/nacl/dbaas-v2-deb/cloudstack-ui_4.23.0.0*.deb
```

เห็น 2 ไฟล์นี้ (ขนาดหลักร้อย MB สำหรับ management, ~10-70MB สำหรับ ui) แปลว่า build ผ่าน
ไม่ต้องสนไฟล์ .deb อื่น ๆ ในโฟลเดอร์เดียวกัน (agent, usage ฯลฯ ไม่เกี่ยวกับงานนี้)

## 3. Backup ก่อนลง (สำคัญ ห้ามข้าม)

```bash
mysqldump -u cloud -p cloud dbaas_credentials > ~/dbaas_credentials.bak.sql
# ถ้าตารางเดิมเป็นของ v1 (ไม่มีคอลัมน์ status/report_token_*) ให้ล้างทิ้งเลย
mysql -u cloud -p cloud -e "DROP TABLE IF EXISTS dbaas_credentials;"
```

## 4. ลงเป็นเวอร์ชันใหม่ (deploy)

```bash
sudo dpkg -i /home/nacl/dbaas-v2-deb/cloudstack-management_4.23.0.0*.deb
sudo dpkg -i /home/nacl/dbaas-v2-deb/cloudstack-ui_4.23.0.0*.deb
sudo systemctl restart cloudstack-management
```

`dpkg -i` เขียนทับไฟล์เก่าเสมอไม่ว่าเลขเวอร์ชันจะซ้ำเดิมหรือไม่ (ไม่เหมือน `apt` ที่จะข้ามถ้าเวอร์ชันเท่ากัน) แค่รัน `dpkg -i` แล้ว restart ก็กลายเป็นเวอร์ชันใหม่ทันที ไม่ต้องทำอะไรเพิ่ม

## 5. เช็คว่าเวอร์ชันใหม่ขึ้นจริง

```bash
sudo tail -f /var/log/cloudstack/management/management-server.log | grep -i dbaas
```
ต้องเห็นบรรทัด `credentials cleanup sweep scheduled every 3600 s` โผล่มา (โค้ด v2 เท่านั้นที่มีบรรทัดนี้) แล้วไม่มี `ERROR` เกี่ยวกับ `dbaas` เลย = ขึ้นเวอร์ชันใหม่แล้วจริง

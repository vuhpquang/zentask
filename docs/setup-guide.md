# ZenTask — Hướng dẫn cài đặt (việc anh cần tự làm)

Các bước dưới đây cần tài khoản hoặc quyền mà chỉ anh có. Làm theo thứ tự. Mỗi phần ghi rõ **khi nào cần** để anh không phải làm sớm hơn mức cần thiết.

| # | Việc | Cần trước phase |
|---|---|---|
| 1 | DNS `zentask-api.vuhpquang.com` qua Cloudflare Tunnel | P0 (deploy server) |
| 2 | Mật khẩu server + file `.env` trên vps1 | P1 (auth + sync) |
| 3 | Google Cloud OAuth client cho Google Calendar | P5 (calendar) |
| 4 | (Tuỳ chọn) Link iCal bí mật của lịch công ty | P5, chỉ khi bước 3 bị công ty chặn |
| 5 | Cookie TickTick | P2 (hướng dẫn chi tiết sẽ thêm khi tới P2) |

---

## 1. DNS: `zentask-api.vuhpquang.com`

Server ZenTask chạy trên `vps1`, cổng `127.0.0.1:3100`. Cách đưa ra Internet giống hệt `wiki-api.vuhpquang.com`: dùng **Cloudflare Tunnel** đang chạy sẵn trên vps1. Không cần mở port, không cần nginx hay certbot, HTTPS do Cloudflare lo.

Tunnel hiện tại: `69bc8241-0486-4282-9c7f-72d2e8e33ebb`, file cấu hình `/etc/cloudflared/config.yml`.

### 1.1 Tạo bản ghi DNS trỏ vào tunnel

SSH vào VPS:

```bash
ssh vps1
```

Chạy lệnh sau. Nó tự tạo bản ghi CNAME `zentask-api` trong zone `vuhpquang.com` của Cloudflare, trỏ vào tunnel:

```bash
cloudflared tunnel route dns 69bc8241-0486-4282-9c7f-72d2e8e33ebb zentask-api.vuhpquang.com
```

Kết quả mong đợi: `Added CNAME zentask-api.vuhpquang.com which will route to this tunnel`.

> Nếu lệnh báo lỗi về chứng chỉ (`cert.pem`), tạo bản ghi tay trên Cloudflare Dashboard: **vuhpquang.com → DNS → Records → Add record**
> - Type: `CNAME`
> - Name: `zentask-api`
> - Target: `69bc8241-0486-4282-9c7f-72d2e8e33ebb.cfargotunnel.com`
> - Proxy status: **Proxied** (đám mây màu cam)
> - Save

### 1.2 Thêm ingress vào cấu hình tunnel

Vẫn trong `ssh vps1`, sao lưu file cấu hình trước:

```bash
cp /etc/cloudflared/config.yml /etc/cloudflared/config.yml.bak-$(date +%F)
nano /etc/cloudflared/config.yml
```

Thêm 2 dòng `zentask-api` vào **trước** dòng `- service: http_status:404`. Dòng 404 phải luôn nằm cuối cùng. File sau khi sửa:

```yaml
tunnel: 69bc8241-0486-4282-9c7f-72d2e8e33ebb
credentials-file: /root/.cloudflared/69bc8241-0486-4282-9c7f-72d2e8e33ebb.json
protocol: http2

ingress:
  - hostname: wiki-api.vuhpquang.com
    service: http://localhost:3000
  - hostname: wiki-preview.vuhpquang.com
    service: http://localhost:8080
  - hostname: zentask-api.vuhpquang.com
    service: http://localhost:3100
  - service: http_status:404
```

Lưu file (nano: `Ctrl+O`, `Enter`, `Ctrl+X`), kiểm tra cấu hình rồi khởi động lại:

```bash
cloudflared tunnel ingress validate
systemctl restart cloudflared
systemctl status cloudflared --no-pager | head -5   # phải thấy "active (running)"
```

### 1.3 Kiểm tra

Khi server ZenTask chưa được deploy (trước P0), Cloudflare sẽ trả lỗi `502 Bad Gateway`. Điều đó là **bình thường**: DNS và tunnel đã thông, chỉ là chưa có gì chạy ở cổng 3100.

```bash
# Chạy trên Mac
curl -i https://zentask-api.vuhpquang.com/health
```

- `502` → DNS + tunnel OK, chờ deploy.
- `Could not resolve host` → bản ghi DNS chưa có hoặc chưa lan truyền (đợi vài phút, kiểm tra lại bước 1.1).
- `404` → ingress chưa đúng (kiểm tra lại bước 1.2, nhất là thứ tự dòng).

Sau khi deploy (P0), lệnh trên trả `200` và `{"status":"ok"}`.

> Nếu wiki-api ngừng chạy sau khi restart: khôi phục `cp /etc/cloudflared/config.yml.bak-<ngày> /etc/cloudflared/config.yml && systemctl restart cloudflared`.

---

## 2. Mật khẩu server và file `.env`

Server ZenTask có xác thực, vì task của anh chứa thông tin nhạy cảm. Có hai giá trị bí mật cần tạo. Cả hai **chỉ nằm trên vps1, không commit lên git**.

### 2.1 Tạo các giá trị bí mật (chạy trên Mac)

```bash
# Khoá mã hoá refresh token Google (32 byte, base64)
openssl rand -base64 32

# Mật khẩu đăng nhập ZenTask: tự đặt, hoặc sinh ngẫu nhiên
openssl rand -base64 24
```

Lưu **mật khẩu đăng nhập** vào trình quản lý mật khẩu của anh. Anh sẽ nhập nó một lần trên mỗi thiết bị khi đăng nhập ZenTask.

### 2.2 Tạo file `.env` trên vps1

Thư mục `/root/zentask` sẽ do `scripts/deploy.sh` tạo ở P0. Anh có thể tạo trước:

```bash
ssh vps1
mkdir -p /root/zentask && nano /root/zentask/.env
```

Nội dung (thay phần `<...>`):

```dotenv
ZENTASK_PASSWORD=<mật khẩu đăng nhập ở bước 2.1>
ZENTASK_ENCRYPTION_KEY=<khoá base64 ở bước 2.1>
ZENTASK_PUBLIC_URL=https://zentask-api.vuhpquang.com

# Điền ở bước 3 (Google Calendar), để trống cho tới lúc đó
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
```

```bash
chmod 600 /root/zentask/.env
```

> Lần đầu khởi động, server băm mật khẩu và chỉ giữ bản băm. Muốn đổi mật khẩu thì sửa `.env` rồi chạy lại `scripts/deploy.sh`. Các thiết bị đã đăng nhập giữ nguyên token cho tới khi anh thu hồi chúng.

---

## 3. Google Cloud OAuth (Google Calendar, chỉ đọc)

ZenTask server xin quyền **chỉ đọc lịch** (`calendar.readonly`) cho tài khoản Google cá nhân và tài khoản công ty. App để ở chế độ **Testing**, chỉ những email anh thêm vào mới dùng được, nên không cần Google xét duyệt.

### 3.1 Tạo project

1. Mở https://console.cloud.google.com/ bằng **tài khoản cá nhân** (để project không thuộc tổ chức công ty).
2. Ở thanh trên cùng, bấm chọn project → **New Project**.
   - Project name: `ZenTask`
   - Location: `No organization`
   - **Create**, rồi chọn project vừa tạo.

### 3.2 Bật Google Calendar API

1. Menu ☰ → **APIs & Services → Library**.
2. Tìm `Google Calendar API` → **Enable**.

### 3.3 Cấu hình màn hình xin quyền (OAuth consent / Google Auth Platform)

1. Menu ☰ → **APIs & Services → OAuth consent screen**. Nếu thấy giao diện mới "Google Auth Platform" thì bấm **Get started**.
2. **App information**: App name `ZenTask`, User support email là email cá nhân của anh.
3. **Audience**: chọn **External**.
4. **Contact information**: email cá nhân.
5. Đồng ý điều khoản → **Create**.
6. Vào mục **Data Access** (hoặc **Scopes**) → **Add or remove scopes**, tìm và tick `.../auth/calendar.readonly` → **Update** → **Save**.
7. Vào mục **Audience** → **Test users** → **Add users**, thêm **cả hai** email:
   - email Google cá nhân
   - email công ty (`...@mservice.com.vn`)
   - **Save**
8. Để **Publishing status = Testing**. Không bấm "Publish app".

> Ở chế độ Testing, Google bắt đăng nhập lại sau mỗi **7 ngày** với scope nhạy cảm (refresh token hết hạn). ZenTask sẽ báo "Kết nối lại Google" khi đến lúc. Muốn bỏ giới hạn này thì phải publish app và qua xét duyệt của Google. Với app cá nhân thì không đáng.

### 3.4 Tạo OAuth client

1. Menu ☰ → **APIs & Services → Credentials** (hoặc **Clients** trong Google Auth Platform) → **Create credentials → OAuth client ID**.
2. Application type: **Web application**. Name: `ZenTask server`.
3. **Authorized redirect URIs** → **Add URI**:
   ```
   https://zentask-api.vuhpquang.com/v1/calendar/google/callback
   ```
4. **Create**. Copy **Client ID** và **Client secret** (bấm tải JSON để lưu dự phòng vào trình quản lý mật khẩu).

### 3.5 Điền vào `.env` trên vps1

```bash
ssh vps1
nano /root/zentask/.env
```

```dotenv
GOOGLE_CLIENT_ID=<client id>.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=<client secret>
```

Khi tới P5, deploy lại server rồi vào **ZenTask → Settings → Calendar → Connect Google** cho từng tài khoản.

### 3.6 Nếu tài khoản công ty bị chặn

Nếu lúc kết nối email công ty, Google báo *"Access blocked: … has not been approved by your organization"* hoặc *"admin_policy_enforced"*, nghĩa là admin Google Workspace của công ty chặn app bên ngoài. Hai cách:
- Nhờ IT thêm OAuth Client ID của ZenTask vào danh sách cho phép (Admin console → Security → API controls → App access control). Thường khó được duyệt.
- Dùng link iCal bí mật (bước 4).

---

## 4. (Tuỳ chọn) Link iCal bí mật của lịch

Chỉ dùng khi bước 3 bị chặn với tài khoản nào đó.

1. Mở https://calendar.google.com bằng tài khoản đó.
2. ⚙️ **Settings** → cột trái, mục **Settings for my calendars** → chọn lịch (thường là tên của anh).
3. Kéo xuống **Integrate calendar** → **Secret address in iCal format** → bấm copy.
   - Nếu mục này bị ẩn hoặc báo đã tắt, nghĩa là admin công ty cũng chặn chia sẻ ra ngoài, nên cách này không dùng được.
4. Khi tới P5: **ZenTask → Settings → Calendar → Add iCal URL** → dán link.

> Link này cho phép bất kỳ ai đọc lịch của anh. Đừng gửi cho ai, đừng commit. ZenTask lưu nó mã hoá trên server.

---

## 5. Cookie TickTick

Dùng cho nút **Sync TickTick** (kéo task, list, tag, habit, countdown về ZenTask). Hướng dẫn từng bước trong Chrome sẽ được thêm vào đây khi làm P2.

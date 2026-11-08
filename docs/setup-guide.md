# ZenTask — Hướng dẫn cài đặt (việc anh cần tự làm)

Các bước dưới đây cần tài khoản hoặc quyền mà chỉ anh có. Làm theo thứ tự. Mỗi phần ghi rõ **khi nào cần** để anh không phải làm sớm hơn mức cần thiết.

| # | Việc | Cần trước phase |
|---|---|---|
| 1 | DNS `zentask-api.vuhpquang.com` qua Cloudflare Tunnel | P0 (deploy server) |
| 2 | Mật khẩu server + file `.env` trên vps1 | P1 (auth + sync) |
| 3 | Google Cloud OAuth client cho Google Calendar, publish app (bước 3.6) | P5 (calendar) |
| 4 | (Tuỳ chọn) Link iCal bí mật của lịch công ty | P5, chỉ khi bước 3 bị công ty chặn |
| 5 | Đăng nhập ZenTask trên Mac + cookie TickTick | P2 |
| 6 | Kết nối MCP cho AI (Claude Code) | P3b |

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

> Mỗi lần khởi động, server đọc mật khẩu từ `.env` rồi chỉ giữ bản băm (PBKDF2) trong bộ nhớ. Muốn đổi mật khẩu thì sửa `.env` rồi chạy lại `scripts/deploy.sh`. Các thiết bị đã đăng nhập vẫn giữ token cũ cho tới khi anh thu hồi (bước 2.3).
>
> Từ P1, server **không khởi động** nếu thiếu `ZENTASK_PASSWORD`. Khi đó `docker compose logs api` báo `ZENTASK_PASSWORD is not set`.

### 2.3 Kiểm tra đăng nhập (sau khi deploy P1)

Phần này chạy trên Mac, sau khi `scripts/deploy.sh` của P1 báo thành công. Lệnh `read -s` hỏi mật khẩu mà không hiện ra màn hình và không lưu vào lịch sử shell:

```bash
read -s "ZT_PW?Mật khẩu ZenTask: "; echo
curl -s https://zentask-api.vuhpquang.com/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"password\":\"$ZT_PW\",\"deviceName\":\"curl-test\"}" \
  -w '\nHTTP %{http_code}\n'
```

Dòng cuối luôn in mã HTTP:

- `HTTP 404` và không có nội dung: server trên vps1 vẫn là bản cũ (P0), chưa có đăng nhập. Cần chạy `scripts/deploy.sh` với code từ P1 trở đi (Claude sẽ hỏi anh trước khi deploy), rồi chạy lại lệnh trên.
- Đúng mật khẩu (`HTTP 200`): trả về `{"token":"…","deviceId":"…"}`. Ghi lại `token` và `deviceId` để dùng ở bước sau.
- `{"error":"unauthorized"}`: sai mật khẩu, so lại với `.env` trên vps1.
- `{"error":"too_many_attempts"}`: đã sai 5 lần trong 15 phút. Đợi 15 phút rồi thử lại.

Xem danh sách thiết bị, rồi thu hồi thiết bị thử nghiệm vừa tạo (thay `<token>` và `<deviceId>`):

```bash
curl -s https://zentask-api.vuhpquang.com/v1/devices -H 'Authorization: Bearer <token>'
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE \
  https://zentask-api.vuhpquang.com/v1/devices/<deviceId> -H 'Authorization: Bearer <token>'
unset ZT_PW
```

Lệnh `DELETE` in ra `204`. Sau đó token kia trả `401`, tức là thu hồi đã có hiệu lực. App ZenTask trên Mac sẽ đăng nhập riêng (P2/P3), anh không cần giữ token này.

---

## 3. Google Cloud OAuth (Google Calendar, chỉ đọc)

ZenTask server xin quyền **chỉ đọc lịch** (`calendar.readonly`) cho tài khoản Google cá nhân và tài khoản công ty. App được **publish ở chế độ In production nhưng không gửi xét duyệt** (bước 3.6): token không hết hạn sau 7 ngày, Google chỉ hiện một màn hình cảnh báo "chưa xác minh" lúc kết nối. Không cần nộp hồ sơ xét duyệt.

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
8. Tạm để **Publishing status = Testing**. Bước 3.6 sẽ chuyển sang In production.

> Vì sao phải publish: ở chế độ Testing, refresh token của scope nhạy cảm hết hạn sau **7 ngày**, nghĩa là cứ mỗi tuần phải kết nối lại Google. Ở chế độ In production thì không có giới hạn này.

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

### 3.6 Publish app (bỏ giới hạn 7 ngày)

Nút **Publish app** chỉ bấm được khi trang **Branding** đã điền đủ. Ba trang trang chủ / chính sách bảo mật / điều khoản do chính server ZenTask phục vụ, nên **server phải được deploy trước** (`scripts/deploy.sh`). Kiểm tra: mở https://zentask-api.vuhpquang.com/privacy thấy trang "Privacy Policy".

**Điền trang Branding** (Google Auth Platform → **Branding**):

| Ô | Giá trị |
|---|---|
| App name | `ZenTask` |
| User support email | email cá nhân của anh (vd `vuhpq.personal@gmail.com`) |
| App logo | **để trống** (upload logo sẽ bắt buộc xét duyệt) |
| Application home page | `https://zentask-api.vuhpquang.com/` |
| Application privacy policy link | `https://zentask-api.vuhpquang.com/privacy` |
| Application terms of service link | `https://zentask-api.vuhpquang.com/terms` |
| Authorized domain 1 | `vuhpquang.com` |
| Developer contact information → Email addresses | email cá nhân của anh |

Bấm **Save**. Sau đó publish:

App cá nhân dưới 100 người dùng được phép chạy ở chế độ **In production mà không qua xét duyệt**. Cái giá duy nhất là màn hình cảnh báo "Google chưa xác minh ứng dụng này" mỗi lần kết nối một tài khoản.

1. Menu ☰ → **APIs & Services → OAuth consent screen** (hoặc **Google Auth Platform → Audience**).
2. Ở mục **Publishing status: Testing**, bấm **Publish app** → **Confirm**.
3. Trạng thái chuyển thành **In production**. Google có thể hiện banner kiểu *"Your app requires verification"* hoặc nút **Prepare for verification**: **bỏ qua, không nộp hồ sơ**. App vẫn chạy bình thường với tối đa 100 người dùng.
4. Danh sách **Test users** không còn tác dụng, để nguyên cũng được.

Khi kết nối Google từ ZenTask (ở P5), Google sẽ hiện **"Google hasn't verified this app"**:
- Bấm **Advanced** (Nâng cao) → **Go to ZenTask (unsafe)** (Chuyển tới ZenTask (không an toàn)).
- Tick quyền **See and download any calendar you can access** → **Continue**.

Đây là app của chính anh, chỉ xin quyền đọc lịch, nên cảnh báo này an toàn để bỏ qua.

> Nếu đã kết nối Google **trong lúc app còn ở Testing** thì token cũ vẫn hết hạn sau 7 ngày. Kết nối lại một lần sau khi publish là xong.

### 3.7 Nếu tài khoản công ty bị chặn

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

## 5. Cookie TickTick và lần sync đầu tiên

Nút **Sync from TickTick** chỉ kéo dữ liệu một chiều, từ TickTick về ZenTask: task, list, thư mục, tag, cột, habit, countdown. ZenTask không bao giờ ghi gì lên TickTick.

Vì anh đăng nhập TickTick bằng Google, ZenTask không dùng mật khẩu được mà dùng **cookie phiên** của trình duyệt (cookie tên `t`).

> Cookie `t` có toàn quyền với tài khoản TickTick của anh, nên đừng gửi nó cho ai. ZenTask chỉ lưu nó trong **Keychain** của Mac: không gửi lên server ZenTask, không ghi log, không nằm trong git.

### 5.1 Đăng nhập ZenTask (làm một lần trên mỗi máy)

1. Mở ZenTask, nhấn `⌘,` (Settings) rồi chọn tab **Account**.
2. Nhập mật khẩu (`ZENTASK_PASSWORD` trong `.env` ở bước 2) và bấm **Sign in**.
3. Dòng **Status** chuyển thành "Synced …". Token đăng nhập được lưu trong Keychain, nên lần sau không phải nhập lại.

### 5.2 Lấy cookie `t`

**Chrome:**
1. Mở <https://ticktick.com/webapp> và đăng nhập nếu chưa.
2. Nhấn `⌥⌘I` để mở DevTools, chọn tab **Application**.
3. Ở cột trái, mở **Storage → Cookies → https://ticktick.com**.
4. Tìm dòng có **Name** là `t`, bấm đúp vào ô **Value** rồi nhấn `⌘C`. Giá trị là một chuỗi dài chữ và số.

**Safari:**
1. Vào **Safari → Settings → Advanced** và bật **Show features for web developers**.
2. Mở <https://ticktick.com/webapp>, rồi chọn **Develop → Show Web Inspector** (`⌥⌘I`).
3. Chọn tab **Storage → Cookies → ticktick.com**, tìm dòng `t` và copy cột **Value**.

> Nếu không thấy dòng `t`, kiểm tra mình đang ở `ticktick.com` (không phải `dida365.com`) và đã đăng nhập.

### 5.3 Sync lần đầu

1. Trong ZenTask, nhấn `⌘,`, chọn tab **TickTick**, dán cookie vào ô **Value of the t cookie** rồi bấm **Save cookie**.
2. Bấm **Check for changes**. ZenTask tải dữ liệu từ TickTick và báo trước sẽ thay đổi gì, ví dụ "830 new". Lúc này **chưa có gì được ghi**.
3. Bấm **Sync from TickTick** để áp dụng. Màn hình chính hiện số list, số task đang mở và đã xong.
4. Những lần sau làm lại bước 2 và 3 bất cứ khi nào muốn kéo thay đổi mới. Bấm nhiều lần không bị nhân đôi.

Cách ZenTask gộp dữ liệu:
- Trường nào anh sửa trong TickTick sẽ ghi đè bản trong ZenTask.
- Trường anh chỉ sửa trong ZenTask được giữ nguyên.
- Task anh tạo trong ZenTask không bao giờ bị động tới.
- Task bị xoá bên TickTick được chuyển vào **Trash** của ZenTask, khôi phục được, không bị xoá hẳn.
- Nếu một lần sync định chuyển hơn 10 mục, và hơn 20% số mục đã nhập, vào Trash, ZenTask hỏi lại trước khi làm. Trường hợp này thường do cookie lỗi hoặc TickTick đổi API, nên nếu thấy con số lạ thì bấm **Cancel**.

### 5.4 Khi cookie hết hạn

Khi ZenTask báo "The TickTick cookie has expired", làm như sau:
1. Mở tab TickTick và bấm **Remove** để xoá cookie cũ.
2. Lấy cookie mới (bước 5.2), dán vào rồi bấm **Save cookie**.

Cookie thường dùng được nhiều tháng. Nó mất hiệu lực ngay nếu anh bấm **Sign out** TickTick trên trình duyệt đó, nên đừng đăng xuất TickTick ở trình duyệt đã lấy cookie.

### 5.5 (Dự phòng) Nhập file CSV backup

Chỉ dùng khi chưa lấy được cookie. Trong TickTick, vào **Settings → Account → Backup → Generate Backup** để tải file `.csv`. Sau đó trong ZenTask, mở tab TickTick, bấm **Import CSV backup…** và chọn file.

> File CSV không chứa ID thật của TickTick. Vì vậy:
> - Sau khi đã sync bằng cookie, nút nhập CSV sẽ ẩn đi.
> - Nếu nhập CSV trước rồi mới sync bằng cookie, các task nhập từ CSV được chuyển vào Trash (ZenTask sẽ hỏi lại) và thay bằng bản có ID thật từ TickTick. Những gì anh đã sửa trên bản CSV sẽ nằm trong Trash.
>
> Nếu đã có cookie thì bỏ qua CSV.

---

## 6. Kết nối Claude Code (MCP) với ZenTask

Server ZenTask có endpoint MCP tại `https://zentask-api.vuhpquang.com/mcp`. Claude dùng nó để tìm, thêm, sửa, hoàn thành và xoá task, tag. Thay đổi do AI làm xuất hiện trên Mac sau lần sync kế tiếp.

> AI dùng một **token riêng** như một thiết bị, thu hồi được bất cứ lúc nào. "Xoá" mặc định là đưa vào Trash; AI chỉ xoá hẳn được task đã nằm trong Trash. Mọi thay đổi qua MCP được ghi nhật ký trên server (bảng `mcp_audit`).

### 6.1 Tạo token cho Claude (chạy trên Mac)

```bash
read -s "ZT_PW?Mật khẩu ZenTask: "; echo
curl -s https://zentask-api.vuhpquang.com/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"password\":\"$ZT_PW\",\"deviceName\":\"Claude Code\"}" \
  -w '\nHTTP %{http_code}\n'
unset ZT_PW
```

Kết quả là `HTTP 200` cùng `{"token":"…","deviceId":"…"}`. Copy giá trị `token`, và ghi lại `deviceId` để sau này có thể thu hồi.

### 6.2 Thêm vào Claude Code

Thay `<token>` bằng token vừa lấy. Tham số `--scope user` để dùng được ở mọi thư mục:

```bash
claude mcp add --scope user --transport http zentask https://zentask-api.vuhpquang.com/mcp \
  --header "Authorization: Bearer <token>"
claude mcp list        # phải thấy: zentask … ✓ Connected
```

### 6.3 Thử

Trong Claude Code, gõ chẳng hạn:
- "Hôm nay tôi có task gì trong ZenTask?"
- "Thêm task gọi ngân hàng ngày mai 9h, priority cao, tag #finance"
- "Đánh dấu xong task gọi ngân hàng"

Sau đó, trên Mac, bấm sync ở góc dưới sidebar (hoặc đợi vài giây) để thấy thay đổi.

### 6.4 Thu hồi quyền của Claude

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE \
  https://zentask-api.vuhpquang.com/v1/devices/<deviceId> -H 'Authorization: Bearer <token>'
claude mcp remove zentask --scope user
```

Lệnh `DELETE` in ra `204`. Từ lúc đó token của Claude trả `401`.


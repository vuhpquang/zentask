# ZenTask — Thiết kế tổng thể

Ngày: 2026-11-08 · Trạng thái: P0, P1, P2, P3, P3b, P4 xong (plan trong `docs/superpowers/plans/`)

## 1. Ý định

ZenTask là app quản lý công việc cá nhân, **thay thế TickTick** cho chính chủ (một người dùng). Học theo tính năng và trải nghiệm của TickTick, nhưng là sản phẩm riêng: tên, icon, màu, hình ảnh của riêng ZenTask, không dùng lại bất kỳ asset/branding nào của TickTick, không decompile app TickTick.

Lý do: muốn sở hữu dữ liệu (server riêng), tự quyết tính năng, và dùng trên mọi thiết bị của mình.

**Thành công** = chuyển toàn bộ dữ liệu TickTick sang, dùng ZenTask hằng ngày trên Mac thay TickTick, dữ liệu luôn đồng bộ, offline vẫn dùng được.

## 2. Nền tảng & thứ tự

1. **macOS** (chính, làm trước — giai đoạn test chỉ macOS)
2. Android
3. Windows
4. Galaxy Watch (Wear OS)

Kiến trúc phải sẵn sàng cho cả 4 ngay từ đầu (Kotlin Multiplatform), nhưng chỉ build macOS cho tới hết P6.

## 3. Ưu tiên tính năng (theo chủ dự án)

1. Tag + Priority
2. List (folder, list, smart list)
3. Calendar view — hiển thị cả Task và Calendar Event (Google Calendar), giống ảnh week view TickTick: hàng all-day ở trên, lưới giờ, vạch giờ hiện tại, panel danh sách task bên phải
4. Habit + Countdown
5. Import toàn bộ dữ liệu TickTick — **được làm sớm** (ngay sau thiết kế DB) để dogfood với dữ liệu thật

## 4. Goals / Non-goals / Anti-goals

**Goals**
- Offline-first: mọi thao tác ghi vào DB cục bộ trước; sync nền khi có mạng ("Sync thông minh" kiểu TickTick).
- Đồng bộ mọi thứ (task, list, tag, habit, countdown, cài đặt) qua server riêng.
- Đọc Google Calendar từ nhiều tài khoản (cá nhân + công ty).
- Nút Sync TickTick kéo một chiều, bấm nhiều lần không nhân đôi, dùng song song 3–6 tháng.
- Release macOS bằng script, cài thẳng vào `/Applications`.
- **MCP server** để AI (Claude Code, Claude Desktop, …) thêm/sửa/xoá/tìm task, list, tag, habit, countdown (mục 10a).

**Non-goals (v1)**
- Cộng tác / chia sẻ list với người khác, multi-user.
- Tạo/sửa Google Calendar event (chỉ ĐỌC).
- Pomodoro, Eisenhower matrix, Kanban view, AI, web app.
- Notarization / Developer ID (dùng self-signed như LaunchApp/Quickcast).

**Anti-goals** (dù "chạy được" vẫn là thất bại)
- Mất hoặc ghi đè sai dữ liệu khi sync / import.
- App Mac giật, không ra cảm giác native (lag khi gõ, scroll).
- Lộ dữ liệu: server không auth (task chứa thông tin nhạy cảm).
- Trông như bản sao branding của TickTick.

## 5. Kiến trúc (đã chốt)

```
zentask/
├── shared/          # Kotlin Multiplatform: model, SQLDelight DB, sync engine,
│                    #   TickTick importer, recurrence, API client (Ktor client)
├── server/          # Ktor server (JVM), dùng chung module protocol với shared
├── apps/macos/      # SwiftUI app, tiêu thụ shared qua Kotlin/Native framework (+ SKIE)
├── apps/android/    # (P7) Jetpack Compose
├── apps/wear/       # (P7) Compose for Wear OS
├── apps/desktop/    # (P8) Compose Multiplatform Desktop cho Windows
├── scripts/         # release.sh, deploy.sh, build-shared.sh
└── docs/
```

| Lớp | Công nghệ |
|---|---|
| Shared core | Kotlin 2.x Multiplatform, SQLDelight 2, Ktor client, kotlinx.serialization, kotlinx.datetime, coroutines/Flow |
| macOS UI | SwiftUI (+ AppKit khi cần), target macOS 26+, arm64; shared nhúng dạng XCFramework, SKIE để Flow → AsyncSequence, suspend → async |
| Server | Ktor (JVM 17+), SQLite (một file, giống wiki), Docker, deploy lên `vps1` bằng `scripts/deploy.sh` (rsync + docker compose), đưa ra ngoài qua **Cloudflare Tunnel** đang chạy trên vps1 (giống `wiki-api`), bind `127.0.0.1:3100`, domain `zentask-api.vuhpquang.com` |
| Build | Gradle (wrapper), xcodegen (`apps/macos/project.yml`) |

Lý do chọn: KMP vì sau này cần đủ 4 nền tảng; SwiftUI cho macOS vì macOS là nền tảng chính và Compose Desktop chạy JVM không ra cảm giác Mac; server Ktor để chia sẻ model + giao thức sync với client, tránh lệch.

Toolchain trên máy: Xcode 27.1, xcodegen có sẵn; JDK arm64 dùng **Corretto 17** (`~/Library/Java/JavaVirtualMachines/corretto-17.0.13`) — JDK 18 Oracle mặc định là x86_64, không chạy được. Gradle qua wrapper.

## 6. Mô hình dữ liệu (theo cấu trúc TickTick)

Nguồn tham khảo cấu trúc: cột của file backup CSV TickTick và tài liệu Open API công khai (developer.ticktick.com). Mọi entity có các trường chung:

`id` (UUID string, client tạo) · `createdAt` · `updatedAt` · `deleted` (tombstone) · `fieldClock` (map trường → HLC timestamp, dùng cho sync) · `ticktickId` (nullable, để import idempotent)

| Entity | Trường chính |
|---|---|
| **ProjectGroup** (folder) | name, sortOrder, collapsed |
| **Project** (list) | name, color, emoji/icon, groupId, kind (TASK/NOTE), viewMode (list/kanban/timeline — v1 chỉ render list), sortOrder, sortType, isInbox, closed (archived) |
| **Column** (section trong list) | projectId, name, sortOrder |
| **Task** | projectId, parentId (subtask), columnId, title, content (markdown), desc, kind (TEXT/CHECKLIST/NOTE), priority (0 none, 1 low, 3 medium, 5 high — đúng giá trị TickTick), status (0 open, 2 completed, -1 won't do), startDate, dueDate, isAllDay, timeZone, isFloating, repeatRule (RRULE RFC 5545), repeatFrom (due/completion), reminders (list trigger, vd `TRIGGER:-PT15M`), tags (list tên tag), sortOrder, completedAt, pinnedAt |
| **ChecklistItem** | taskId, title, status, sortOrder, startDate, isAllDay, completedAt |
| **Tag** | name (khoá logic, lowercase), label (hiển thị), color, parent (tag lồng), sortOrder, sortType |
| **Filter** (smart list tuỳ chỉnh) | name, rule (JSON: lists, tags, priority, date range, keyword), sortOrder |
| **Habit** | name, icon, color, type (BOOLEAN/REAL), goal, step, unit, repeatRule, reminders, sectionId, sortOrder, archivedAt, targetDays, targetStartDate, encouragement |
| **HabitSection** | name, sortOrder |
| **HabitCheckin** | habitId, stamp (yyyyMMdd), value, goal, status (0 none, 1 unfinished, 2 done), checkinAt |
| **Countdown** | name, date, type (COUNTDOWN/ANNIVERSARY/BIRTHDAY/HOLIDAY), repeat (yearly/none), icon, color, note, sortOrder, archivedAt, showInCalendar |
| **Preference** | key, value (JSON) — cài đặt đồng bộ (tuần bắt đầu thứ mấy, smart list nào hiện, …) |
| **CalendarAccount** *(server-owned, read-only với client)* | email, provider (GOOGLE/ICAL), status |
| **CalendarSource** *(server-owned)* | accountId, remoteId, name, color, visible (visible là trường client được sửa) |
| **CalendarEvent** *(server-owned, read-only)* | sourceId, remoteId, title, location, description, start, end, isAllDay, timeZone, recurringEventId, status |

Smart list mặc định (tính, không lưu): All, Today, Tomorrow, Next 7 Days, Inbox, Completed, Won't Do, Trash.

## 7. Sync (đã chốt hướng)

- Mỗi client giữ bản SQLite đầy đủ. Mọi ghi → DB cục bộ + bản ghi vào bảng `pending_change` (outbox) trong cùng transaction.
- **HLC (hybrid logical clock)** cho từng trường; merge **per-field last-writer-wins**. Xoá = tombstone (`deleted=true`), server dọn tombstone > 90 ngày.
- Server giữ `serverVersion` tăng dần (một số toàn cục); mỗi bản ghi lưu version lần ghi cuối.
- Giao thức: `POST /v1/sync { checkpoint, changes[] }` → server áp dụng changes (merge per-field), trả `{ newCheckpoint, changes[] (mọi bản ghi có version > checkpoint, trừ cái client vừa gửi nếu không đổi) }`. Phân trang nếu nhiều (`hasMore`).
- Trigger sync: khi app mở, sau mỗi thay đổi (debounce ~2s), định kỳ 5 phút khi app active, khi mạng trở lại, nút sync thủ công. Trạng thái sync hiển thị trong UI (icon sync ở rail trái như TickTick).
- Mã protocol (DTO + merge logic) nằm trong `shared` và được server tái sử dụng (server dùng target JVM của shared).

### 7.1 Quyết định bổ sung (người viết thiết kế tự quyết, chủ dự án có thể đổi)

- **Task lặp lại** làm giống TickTick: khi hoàn thành, task gốc chuyển sang ngày tiếp theo theo RRULE, đồng thời sinh một bản sao đã hoàn thành (`Task` mới, `status=2`, `repeatTaskId` trỏ về task gốc) làm lịch sử. Bản sao này hiện trong Completed và trên các ngày đã qua của lịch. Import CSV đưa các bản completed vào đúng dạng bản sao này.
- **Trash tách khỏi tombstone**: thêm trường `trashedAt` cho Task/Project (đưa vào thùng rác, khôi phục được, smart list Trash). `deleted=true` chỉ có nghĩa là đã xoá vĩnh viễn (xoá khỏi Trash hoặc dọn Trash). Server chỉ dọn bản ghi `deleted=true` sau 90 ngày, không bao giờ đụng tới `trashedAt`.
- **Lưu trữ dạng bản ghi chung** (P1): cả client lẫn server lưu mọi entity trong một bảng `record` (các trường + clock dạng JSON), model có kiểu nằm ở Kotlin. Nhờ vậy dữ liệu do client mới hơn ghi (entity hoặc trường lạ) không bị mất trên client cũ, và thêm trường không cần migration.
- **Dọn tombstone > 90 ngày: hoãn.** Muốn dọn an toàn cần thêm giao thức "đồng bộ lại toàn bộ", nếu không thì client offline lâu sẽ làm sống lại bản ghi đã xoá. Tombstone rất nhỏ nên chưa cần dọn.
- **`serverId`**: DB server có một id ngẫu nhiên. Client thấy id đổi (server khôi phục từ backup hoặc tạo lại DB) thì đặt checkpoint về 0 và đẩy lại toàn bộ dữ liệu cục bộ, để không có bản ghi nào chỉ còn nằm ở một máy. Server tự đổi `serverId` khi một client gửi checkpoint lớn hơn version hiện tại của nó, vì đó là dấu hiệu DB đã bị khôi phục từ backup cũ. Nếu khôi phục backup bằng tay, nên xoá luôn `serverId`: `sqlite3 zentask.db "DELETE FROM meta WHERE key='serverId'"`.
- **Từ chối từng bản ghi**: một thay đổi sai (entity lạ, id hỏng, clock lệch) chỉ làm bản ghi đó bị từ chối, các thay đổi khác vẫn được áp dụng, nên một bản ghi hỏng không chặn được sync của cả máy. Client giữ bản ghi bị từ chối trong outbox ở trạng thái "parked" (không mất, sửa lại thì gửi lại). Với lỗi `clock_skew` thì client gán clock mới rồi gửi lại.
- **Chặn lệch đồng hồ**: server từ chối thay đổi có clock vượt giờ server hơn 24 giờ (`clock_skew`), để một máy chỉnh sai giờ không khoá cứng trường đó. Clock cục bộ đi trước đồng hồ máy hơn 12 giờ thì được đặt lại theo giờ máy.
- **Mật khẩu server**: lấy từ `.env`, server chỉ giữ bản băm PBKDF2 trong bộ nhớ. Đăng nhập sai 5 lần trong 15 phút thì khoá đăng nhập (toàn cục, vì mọi request đi qua cloudflared).
- **Trường dạng danh sách** (`tags`, `reminders`): v1 merge theo LWW của cả trường. Chấp nhận được khi mới chỉ có một client. Nếu sau này thấy mất tag giữa các thiết bị thì nâng lên set-merge (add/remove theo từng phần tử) ở P7.

## 8. Auth & bảo mật

- Một người dùng. Server có mật khẩu (hash argon2/bcrypt trong env/DB). `POST /v1/auth/login {password, deviceName}` → device token dài, ngẫu nhiên (lưu hash phía server). Client lưu token trong **Keychain**.
- Mọi endpoint (trừ login, OAuth callback, health) cần `Authorization: Bearer`. Có endpoint thu hồi device.
- HTTPS do Cloudflare Tunnel lo (không cần certbot/nginx). CORS tắt (không có web client).
- Google refresh token mã hoá AES-GCM trên server, khoá trong `.env` (không commit).

## 9. Google Calendar (chỉ đọc)

- Server làm OAuth (web flow): client mở trình duyệt tới `GET /v1/calendar/google/connect` → Google consent (scope `calendar.readonly`) → callback về server → lưu refresh token. Hỗ trợ nhiều tài khoản.
- Server sync event định kỳ (10 phút) bằng `syncToken` incremental của Google Calendar API, cửa sổ thời gian: 90 ngày trước → 365 ngày sau. Event về client qua cùng kênh `/v1/sync` như entity read-only.
- Rủi ro: tài khoản công ty (Google Workspace) có thể chặn OAuth app chưa verify. Dự phòng: thêm lịch bằng **link iCal bí mật** (provider ICAL), server poll định kỳ.
- Việc thủ công của chủ dự án (chi tiết trong `docs/setup-guide.md`): tạo Google Cloud project + OAuth client (Web), thêm redirect URI của server, publish app sang **In production không xét duyệt** (để refresh token không hết hạn sau 7 ngày như chế độ Testing; đổi lại có màn hình cảnh báo "unverified app" khi kết nối, giới hạn 100 user).

## 10. Import & Sync TickTick

Chủ dự án sẽ **dùng song song TickTick và ZenTask 3–6 tháng** (TickTick vẫn dùng trên điện thoại cho tới khi có Android ở P7). Vì vậy import không phải một lần mà là **nút "Sync TickTick" kéo một chiều TickTick → ZenTask**, bấm được nhiều lần. Không bao giờ ghi ngược lên TickTick.

Chạy ở client (shared module), ghi vào DB cục bộ rồi sync lên server như thay đổi bình thường.

**Phát hiện từ file backup thật** (`~/Downloads/TickTick-backup-2026-11-08.csv`, version 7.2): 824 task, 8 list, 1 folder, 16 checklist, tag gần như không dùng, **không có habit, countdown, cũng không có task lặp lại**. Cột `taskId`/`parentId` chỉ là số thứ tự 1, 2, 3… của lần xuất đó, không phải ID thật → **CSV không dùng làm khoá idempotent được**. Có status `1` (67 task) không có trong chú thích file — cần tìm hiểu ý nghĩa (khả năng là "đã hoàn thành" kiểu cũ). Checklist được mã hoá trong cột Content: `▫` = mục chưa xong, `▪` = đã xong.

1. **Nguồn chính: API web nội bộ của TickTick** (`api.ticktick.com/api/v2/...`), xác thực bằng **cookie phiên** chủ dự án copy từ trình duyệt (chủ dự án đăng nhập TickTick bằng Google nên không đăng nhập bằng mật khẩu được). API này trả về ID thật, lấy được task, list, folder, tag (kèm màu), column, habit, habit check-in, countdown. Endpoint cụ thể xác định bằng cách quan sát network của web app với tài khoản của chính mình (HAR). Cookie lưu trong Keychain; khi hết hạn thì báo người dùng dán cookie mới. Hướng dẫn lấy cookie viết ở P2.
2. **Dự phòng: import CSV một lần** (khi chưa có cookie, hoặc API đổi). Chỉ dùng cho lần import đầu; khoá khớp tạm = (list, title, createdTime).
3. **Quy tắc merge khi Sync TickTick** (đã chốt): lưu một *baseline* — bản giá trị TickTick ở lần sync trước — cho từng bản ghi đã import.
   - Trường nào TickTick đổi so với baseline → lấy theo TickTick (ghi đè ZenTask).
   - Trường TickTick không đổi → giữ nguyên giá trị ZenTask (kể cả khi anh đã sửa trong ZenTask).
   - Bản ghi mới bên TickTick → tạo mới trong ZenTask.
   - Bản ghi biến mất bên TickTick → chuyển vào **Trash** của ZenTask (`trashedAt`), khôi phục được, không xoá vĩnh viễn.
   - Bản ghi tạo trong ZenTask (không có `ticktickId`) → không bao giờ bị đụng tới.
4. Màn hình Sync TickTick: trạng thái cookie, lần sync cuối, preview số tạo/cập nhật/đưa vào trash trước khi áp dụng (lần đầu), kết quả và lỗi.

## 10a. MCP cho AI (chủ dự án yêu cầu 2026-11-08)

Mục tiêu: AI thao tác được dữ liệu ZenTask, gồm thêm, sửa, xoá và tìm kiếm, như một thiết bị nữa.

Các quyết định dưới đây là của người viết thiết kế; chủ dự án có thể đổi.

- **MCP chạy trên server Ktor**, endpoint `POST /mcp` (transport Streamable HTTP, thư viện `io.modelcontextprotocol:kotlin-sdk`).
  - Vì dữ liệu đã nằm trên server, AI ở máy nào cũng dùng được, không cần app Mac đang mở.
  - Thay đổi do AI ghi đi qua đúng `merge` per-field của sync, với clock HLC của node riêng `mcp`. Server tăng `version` nên các client nhận về ở lần sync kế tiếp như mọi thay đổi khác. Không có đường ghi thứ hai.
- **Xác thực**:
  - Bearer token riêng cho AI, tạo bằng `POST /v1/auth/login` với `deviceName` kiểu "Claude Code". Token hiện trong danh sách thiết bị và thu hồi được như mọi thiết bị.
  - Cấu hình mẫu: `claude mcp add --transport http zentask https://zentask-api.vuhpquang.com/mcp --header "Authorization: Bearer <token>"`.
  - Connector trên claude.ai cần OAuth nên để sau, chỉ làm nếu chủ dự án cần.
- **Tools** tăng dần theo phase:
  - Task (cùng P3): `search_tasks` (list, tag, priority, khoảng ngày, trạng thái, từ khoá), `get_task`, `create_task`, `update_task` (sửa từng trường), `complete_task` (task lặp lại xử lý giống app, mục 7.1), `trash_task` / `restore_task`, `delete_task`.
  - Tag: `list_tags`, `create_tag`, `update_tag`, `delete_tag`.
  - P4: `list_projects`, `create_project`, `update_project`, `delete_project`, column, filter.
  - P6: `list_habits`, `check_in_habit`, countdown CRUD.
  - P5: `list_events` (chỉ đọc).
- **An toàn** (vì "mất dữ liệu" là anti-goal):
  - "Xoá" mặc định là đưa vào Trash. Xoá vĩnh viễn chỉ khi gọi `delete_task` với `permanent: true`.
  - Mỗi lần ghi qua MCP lưu một dòng nhật ký (`mcp_audit`: thời điểm, tool, bản ghi trước và sau) để hoàn tác được.
  - Có giới hạn số thao tác mỗi phút.
  - Dữ liệu đưa vào được kiểm tra như `/v1/sync`: priority, status và ngày phải hợp lệ.
- Logic nghiệp vụ (hoàn thành task lặp lại, chuẩn hoá tag, quick-add) nằm trong `shared` để app và MCP dùng chung.

## 11. UI macOS

Bố cục học theo TickTick (theo ảnh chủ dự án gửi), dark mode là chính, hỗ trợ light:
- **Rail trái** (icon): Tasks, Calendar, Habit, Countdown, Search; dưới cùng Sync, Settings.
- **Tasks**: sidebar (smart lists, folders/lists, tags, filters) | danh sách task (nhóm theo list/ngày/priority, có "Completed" thu gọn, quick-add ở đầu) | panel chi tiết task.
- **Quick add** với cú pháp: `#tag`, `!high/!medium/!low` (hoặc `!1..!3`), `^list`, ngày tự nhiên ("tomorrow 9am", "mai", "thứ 6") — parser tiếng Anh + tiếng Việt cơ bản.
- Màu priority: high đỏ, medium vàng, low xanh dương, none xám (checkbox có viền màu như TickTick).
- **Calendar**: Day / Week / Month; all-day row (task all-day + event all-day + countdown), lưới giờ, vạch "now", task và event phân biệt màu/kiểu; kéo task từ panel phải vào lưới để đặt giờ; event chỉ đọc.
- Phím tắt: ⌘N task mới, ⌘F search, ⌘1..5 chuyển tab rail, Space hoàn thành task đang chọn.
- Nhắc nhở task: local notification (UserNotifications) theo reminders của task.

## 12. Release & deploy

- `scripts/release.sh X.Y.Z [notes.md]` theo mẫu LaunchApp/Quickcast: build shared XCFramework → `xcodegen generate` → xcodebuild Release arm64 → ký self-signed "ZenTask Self-Signed" (backup ở `~/.config/zentask-signing/`) → zip → tag → (push + GitHub release nếu được cho phép) → thay `/Applications/ZenTask.app`. Hỗ trợ `DRY_RUN=1`, `INSTALL=1`.
- Bundle id `com.vuhpquang.zentask` (`.dev` cho Debug).
- Auto-update: hoãn sau v1.
- `scripts/deploy.sh`: rsync server + docker compose lên `vps1` (giống personal-wiki).

## 13. Phases

| Phase | Nội dung | Xong khi |
|---|---|---|
| **P0** Nền móng | Repo layout, Gradle KMP + server Ktor skeleton (health), Xcode app rỗng gọi được shared, release.sh cài vào /Applications, deploy.sh | App rỗng mở từ /Applications hiển thị version từ shared; server `/health` chạy trên vps1 |
| **P1** DB + Sync | Schema SQLDelight đầy đủ (mục 6), repository, HLC, outbox, sync protocol + server endpoints + auth | Hai bản DB client (test) sync qua server, test merge per-field/tombstone pass |
| **P2** Import & Sync TickTick | Client API v2 bằng cookie (task, list, tag, habit, countdown), merge theo baseline, CSV fallback, màn hình Sync TickTick (UI tối thiểu), hướng dẫn lấy cookie | Dữ liệu TickTick thật nằm trong ZenTask, đếm khớp; bấm Sync lần 2 không nhân đôi |
| **P3** Task + Tag + Priority | Inbox, CRUD task, chi tiết task, subtask/checklist, tag (lồng, màu), priority, quick add, nhắc nhở | Dùng hằng ngày được cho task |
| **P3b** MCP | MCP server `/mcp` trên Ktor, các tool cho task và tag, nhật ký `mcp_audit`, hướng dẫn kết nối Claude Code trong `setup-guide.md`. Tool cho list/habit/countdown/event thêm ở P4/P6/P5 | Từ Claude Code thêm, sửa, hoàn thành và xoá được task; app Mac thấy thay đổi sau khi sync |
| **P4** List | Folder, list, column, smart list mặc định + filter tuỳ chỉnh, nhóm/sắp xếp, kéo thả | Sidebar + list view như TickTick |
| **P5** Calendar | Google OAuth + iCal fallback ở server, Day/Week/Month view, task + event, kéo thả đặt giờ | Week view như ảnh mẫu |
| **P6** Habit + Countdown | Habit list, check-in, streak/thống kê, countdown list, countdown trên calendar | Thay được TickTick hoàn toàn trên Mac |
| **P7** Android + Galaxy Watch | Compose app, Wear app (today tasks, check habit) | — |
| **P8** Windows | Compose Desktop | — |

## 14. Để lại cho người build quyết định

Tên package Kotlin, cấu trúc thư mục bên trong module, thư viện argon2/bcrypt, chi tiết UI pixel, thư viện RRULE (hoặc tự viết tập con cần thiết), cách nhúng XCFramework (SPM local package hay embed trực tiếp).

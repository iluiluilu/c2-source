# Architecture: branding

> File: `docs/01-sa/architecture/branding.md` — Owner: SA
> Status: Draft

## 1. Bối cảnh / Vấn đề

- Nhận diện hệ thống (logo, favicon, tên) đang hardcode ở nhiều nơi; muốn đổi phải sửa code và build lại.
- Mục tiêu: admin (R3, quyền `PERM_system_config.manage`) cấu hình tại **Admin > Cấu hình hệ thống**
  (`admin/system-config`); áp dụng cho web Next.js **và** trang login Keycloak, không cần restart.
- **In-scope:** logo, favicon, tên hệ thống (đầy đủ + rút gọn); API admin + API public; fallback; audit.
- **Out-of-scope:** màu/theme, font, nội dung email Keycloak, đa ngôn ngữ cho tên, dòng `v1.0 · on-premise`
  (giữ hardcode), branding theo đơn vị/tenant, giao diện chọn lại file cũ (xem Câu hỏi mở).

### Hiện trạng (đã xác minh)

| Nơi | File | Nội dung hiện tại |
|---|---|---|
| Sidebar web | `web/src/components/AppShell.tsx:54-70` (client) | SVG ngôi sao + "LƯU TRỮ & XỬ LÝ / THÔNG TIN AI" + "v1.0 · on-premise" |
| Trang chào/login web | `web/src/app/page.tsx:34-50` (client) | SVG ngôi sao + "HỆ THỐNG LƯU TRỮ DỮ LIỆU & XỬ LÝ THÔNG TIN AI" |
| Metadata web | `web/src/app/layout.tsx:16-22` | title cố định "Lưu trữ & Xử lý thông tin AI", template "%s · ..." |
| Favicon web | `web/src/app/favicon.ico` | favicon mặc định Next |
| Login Keycloak | `keycloak/themes/aismartstore/login/template.ftl` (dòng 17-18, 40-48) **và bản sao stg** `stg/config/keycloak/themes/aismartstore/login/template.ftl` | `<title>` từ `msg("loginTitle", realm.displayName)`; favicon `${url.resourcesPath}/img/favicon.ico` → **404** (theme không có `img/`); SVG ngôi sao + 2 dòng `h1` "HỆ THỐNG LƯU TRỮ DỮ LIỆU" / "& XỬ LÝ THÔNG TIN AI" |
| Backend | `SecurityConfig.PUBLIC_PATHS = {"/", "/public"}` | **Chưa** match `/public/**`. CORS: `allowedOriginPatterns=*`, `allowCredentials=false` (đủ cho fetch/img không credential) |
| Keycloak | KC 24.0.0, `start-dev` (dev + stg), theme mount `:ro`; realm export **không** cấu hình `browserSecurityHeaders` → CSP mặc định KC chỉ có `frame-src/frame-ancestors/object-src`, **không** giới hạn `script-src`/`img-src`/`connect-src` | template đã dùng inline `<script type="module">` |
| Web CSP | Không có CSP/`headers()` trong `web/` | `<img>` cross-origin tới API domain không bị chặn |

### Kết quả kiểm tra stg/nginx (quyết định #8)

- `stg/nginx/conf.d/api.conf`: `server_name api.green0tech.site`, `location /` → `backend:3001` (catch-all)
  → `/public/**` **đã được proxy** sang backend, **không cần sửa nginx**.
- `web.conf` (`location /` → web) và `auth.conf` (`location /` → keycloak) không liên quan; không có
  `add_header`/CSP nào trong `stg/nginx` → header cache/nosniff do **backend tự set**.
- Lưu ý: phía trước nginx có Cloudflare (Flexible) — edge có thể cache theo `Cache-Control` backend trả
  (max-age=90 / immutable) → chấp nhận được, `?v=` nằm trong cache key mặc định của Cloudflare.

## 2. Ràng buộc (Constraints)

- Tái sử dụng module `systemconfig` (key/value, `SystemConfigDefaults`, audit/history/rollback, Redis cache)
  và `storage/ObjectStorageService` (SeaweedFS S3, `ensureBuckets`) — không tạo cơ chế cấu hình mới.
- Không dùng bucket `documents` (WORM theo NFR-09) → bucket riêng `branding`.
- Keycloak: chỉ sửa theme **một lần**; theme mount read-only; phải sửa **cả 2 bản** theme (gốc + stg).
- Web: `NEXT_PUBLIC_*` là build-time → URL backend cho Keycloak/web không được phụ thuộc giá trị build mới.
- Không nhận SVG upload (XSS qua SVG).

## 3. NFR (Non-Functional Requirements)

| Loại | Yêu cầu |
|---|---|
| Performance | `GET /public/branding` đọc từ Redis (qua SystemConfigService), p95 < 50 ms; ảnh trả 304 khi ETag khớp; web SSR fetch branding timeout ≤ 1 s, không block render quá ngưỡng này |
| Security | Chỉ `PERM_system_config.manage` được sửa; whitelist định dạng + magic bytes; `X-Content-Type-Options: nosniff`; Content-Type do server quyết định (không tin client); escape tên ở mọi nơi render; endpoint public chỉ đọc, không lộ key nội bộ ngoài `version` |
| Scalability | Dữ liệu rất nhỏ (≤ 2 file hiện hành); cache in-memory theo object key là an toàn khi scale ngang (key bất biến) |
| Availability | Backend/SeaweedFS lỗi → web & Keycloak **vẫn hiển thị** logo SVG ngôi sao, favicon mặc định, tên mặc định; không có trang nào lỗi vì branding |
| Freshness | Web: áp dụng ở lần tải trang kế tiếp (≤ 60 s do revalidate SSR). Keycloak: ≤ ~90 s (max-age=90) |
| Auditability | Mọi thay đổi (upload/reset logo, favicon, sửa tên, rollback) ghi audit log hash-chain |

## 4. Phương án & Quyết định (ADR)

### Quyết định 1: Mô hình dữ liệu — metadata trong `system_config`, file trong bucket `branding`

- **Quyết định:** file lưu SeaweedFS bucket `branding` (thêm vào `ensureBuckets`, property riêng), key mới
  mỗi lần upload: `logo/<uuid>.<ext>`, `favicon/<uuid>.<ext>`; **không xoá file cũ** (phục vụ rollback).
  Metadata là key trong `system_config`:

  | Key | Type | Mặc định | Ai ghi | Ghi chú |
  |---|---|---|---|---|
  | `branding.logo_key` | STRING | `""` | chỉ Branding API / rollback | trống = SVG ngôi sao |
  | `branding.logo_version` | NUMBER | `0` | server tự set | epoch millis lúc key đổi |
  | `branding.favicon_key` | STRING | `""` | chỉ Branding API / rollback | trống = favicon mặc định |
  | `branding.favicon_version` | NUMBER | `0` | server tự set | epoch millis lúc key đổi |
  | `branding.system_name` | STRING | `""` | system-config PUT | tên đầy đủ, ≤ 120 ký tự |
  | `branding.system_name_short` | STRING | `""` | system-config PUT | tên rút gọn cho sidebar, ≤ 40 ký tự |

- **Quy tắc:**
  - `*_key`, `*_version` là **managed keys**: generic `PUT /admin/system-config` từ chối (lỗi kiểu
    `READ_ONLY_KEY`); ẩn hoặc hiển thị read-only trên UI danh sách tham số.
  - Rollback qua history của system-config **được phép** với `*_key`: service kiểm tra object tồn tại trong
    bucket `branding` và đúng prefix, rồi **tự bump** `*_version` tương ứng (không rollback version).
  - Không đánh dấu "nhạy cảm/2 bước" (sai thì sửa lại ngay, không mất dữ liệu — cùng tiêu chí `system-config.md` QĐ3).
- **Lý do chọn `system_name_short`:** sidebar hẹp (2 dòng chữ nhỏ) khác trang login/header (tên đầy đủ). Không
  cho nhập xuống dòng; xuống dòng do CSS wrap/line-clamp.
- **Trade-off:** 6 key thay vì 1 JSON blob → hưởng sẵn audit/history/rollback từng key; đổi lại cần rule
  "managed key" trong SystemConfigService.

### Quyết định 2: API

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| POST | `/admin/branding/logo` | `PERM_system_config.manage` | multipart `file`; validate → put object key mới → set `logo_key` + `logo_version` → audit `BRANDING_LOGO_UPDATED` |
| DELETE | `/admin/branding/logo` | idem | set `logo_key=""`, bump version (file giữ nguyên) → audit `BRANDING_LOGO_RESET` |
| POST / DELETE | `/admin/branding/favicon` | idem | tương tự, audit `BRANDING_FAVICON_UPDATED/RESET` |
| PUT | `/admin/system-config/...` (sẵn có) | idem | sửa `branding.system_name(_short)`; audit sẵn có |
| GET | `/public/branding` | public | JSON (xem dưới) |
| GET | `/public/branding/logo` | public | bytes logo hiện hành; 404 nếu chưa cấu hình |
| GET | `/public/branding/favicon` | public | bytes favicon hiện hành; **nếu chưa cấu hình trả favicon mặc định đóng gói trong backend** (classpath, copy từ `web/src/app/favicon.ico`) — sửa luôn lỗi 404 favicon Keycloak |

- `GET /public/branding` trả:
  `{ systemName, systemNameShort, logoUrl, faviconUrl, version }`
  - `systemName`/`systemNameShort`: `null` nếu trống (client tự dùng text mặc định tại chỗ).
  - `logoUrl`: đường dẫn **tương đối** `/public/branding/logo?v=<logo_version>` hoặc `null`; tương tự `faviconUrl`
    (`null` khi chưa cấu hình → web dùng favicon mặc định của Next).
  - `version`: `max(logo_version, favicon_version, updated_at của 2 key tên)` — dùng cho client so sánh/debug.
  - Không trả object key nội bộ.
- **SecurityConfig:** đổi `PUBLIC_PATHS` thành `{"/", "/public/**"}` (chỉ GET; các path `/public/**` khác nếu có cũng
  thành public → DEV rà soát controller hiện có dưới `/public`).

### Quyết định 3: Chiến lược cache

| Kiểu URL | Dùng bởi | Header |
|---|---|---|
| `/public/branding/{logo,favicon}?v=<version>` với `v` == version hiện hành | Web | `Cache-Control: public, max-age=31536000, immutable` + `ETag` |
| `?v=` **khác** version hiện hành (link cũ) | Web (trang cũ) | trả file hiện hành, `Cache-Control: public, max-age=90` (không immutable, tránh "đóng băng" sai nội dung) |
| URL cố định không `?v=` | Keycloak | `Cache-Control: public, max-age=90` + `ETag: "<kind>-<version>"`; `If-None-Match` khớp → **304** (không đọc S3) |
| 404 logo chưa cấu hình | Keycloak/web | `Cache-Control: public, max-age=90` (giảm request lặp) |
| `GET /public/branding` (JSON) | Web SSR, Keycloak JS | `Cache-Control: public, max-age=60` + ETag |

- Backend: metadata đọc từ Redis (cơ chế sẵn có). Bytes ảnh: cache in-memory tuỳ chọn **theo object key**
  (key bất biến ⇒ không cần invalidation; ≤ ~1.3 MB tổng).
- Web SSR: `fetch` JSON với `revalidate: 60`; sau khi admin lưu, trang admin gọi `router.refresh()` để admin thấy ngay.

### Quyết định 4: Hiển thị tên hệ thống trên Keycloak — JS fetch + localStorage

- **Phương án:**
  - A) JS trong theme fetch `GET /public/branding`, set text bằng `textContent`, fallback text mặc định trong FTL.
  - B) Backend đồng bộ `system_name` sang `realm.displayName` qua Keycloak Admin API → FTL render server-side.
  - C) Theme message/`theme.properties` → phải sửa file + restart, trái quyết định #1.
- **Quyết định:** A, kèm **localStorage** (`branding.systemName`) để giảm nhấp nháy.
- **Cách làm:**
  - FTL render sẵn text mặc định hiện tại (fallback khi JS/API lỗi — không đổi so với nay).
  - Script đồng bộ **ngay sau header** (không defer): đọc localStorage → nếu có thì set `textContent` trước khi
    trình duyệt paint phần sau; sau đó fetch JSON (no credentials, timeout ~2 s) → cập nhật DOM + localStorage;
    tên `null` → xoá localStorage, giữ text mặc định. Cũng set `document.title` (vd `Đăng nhập · <tên>`).
  - Logic đặt trong file `resources/js/branding.js` của theme (không inline) để sẵn sàng nếu sau này bật CSP
    `script-src 'self'`; chỉ URL gốc backend được truyền vào qua `data-*` attribute.
  - Base URL: `theme.properties` → `brandingApiBase=${env.BRANDING_PUBLIC_BASE_URL:http://localhost:3001}`,
    template dùng `${properties.brandingApiBase}`. Env set cho service `keycloak` trong
    `docker-compose.yml` (dev: `http://localhost:3001`) và `stg/docker-compose.yml`
    (`https://${API_DOMAIN}`). DEV-spec phải **xác minh** KC 24 hỗ trợ `${env.X:default}` trong theme.properties;
    nếu không → hardcode theo từng bản theme (2 bản đã tách sẵn gốc/stg).
- **Trade-off:**
  - CORS: backend đã `allowedOriginPatterns=*`, không credential → OK; nếu sau này siết CORS phải thêm origin auth domain.
  - CSP Keycloak: hiện không giới hạn script/connect/img; nếu realm bật CSP chặt phải thêm `connect-src`/`img-src`
    API domain và `script-src 'self'` (lý do tách file JS).
  - Nhấp nháy: lần đầu trên một trình duyệt (chưa có localStorage) sẽ thấy text mặc định → tên mới trong ~vài trăm ms;
    các lần sau không nhấp nháy. Khi admin đổi tên, lần đầu vào sẽ thấy tên cũ (từ localStorage) rồi đổi.
  - B bị loại: cần cấp `manage-realm` cho service account backend (mở rộng quyền, hiện chỉ quản lý user) và tạo
    2 nguồn sự thật cho tên.

### Quyết định 5: Web — dữ liệu branding qua SSR + context, `<img>` thường

- `layout.tsx` (server): hàm `getBranding()` fetch `GET /public/branding` từ **URL nội bộ** (env mới server-only
  `BACKEND_INTERNAL_URL`, vd `http://backend:3001`; fallback `NEXT_PUBLIC_API_BASE_URL`), timeout ≤ 1 s, lỗi → `null`.
  - `generateMetadata`: `title.default` = `systemName ?? "Lưu trữ & Xử lý thông tin AI"`, `title.template` =
    `"%s · " + <cùng giá trị>`; `icons` = `NEXT_PUBLIC_API_BASE_URL + faviconUrl` nếu có, ngược lại giữ `/favicon.ico` mặc định.
  - Truyền kết quả vào `BrandingProvider` (client context) → `AppShell.tsx`, `page.tsx` đọc từ context (không fetch
    phía client → không nhấp nháy).
- Component dùng chung `BrandLogo` (client): props `size`, `className`; có `logoUrl` → `<img alt="<tên>">`,
  `onError` → chuyển về SVG ngôi sao (SVG này chuyển vào `BrandLogo`, xoá 2 bản lặp). Không dùng `next/image`
  (tránh cấu hình `remotePatterns`, ảnh đã nhỏ).
- Ảnh logo hiển thị `object-fit: contain` trong khung cố định hiện tại của ngôi sao (không làm vỡ layout).

### Quyết định 6: Validation & bảo mật file

- Kiểm tra theo thứ tự: kích thước (từ chối sớm theo `Content-Length` + đếm byte thực) → magic bytes → đọc kích thước
  ảnh từ header → Content-Type lưu/trả = **loại đã phát hiện** (bỏ qua Content-Type/đuôi file của client).
- Magic bytes: PNG `89 50 4E 47 0D 0A 1A 0A`; JPEG `FF D8 FF`; WebP `RIFF....WEBP`; ICO `00 00 01 00`.
  Mọi thứ khác (gồm SVG, GIF, BMP, HTML) → 400 `UNSUPPORTED_FORMAT`.
- Response ảnh luôn kèm: `X-Content-Type-Options: nosniff`, `Content-Disposition: inline`,
  `Content-Security-Policy: default-src 'none'; sandbox`, `Cross-Origin-Resource-Policy: cross-origin`
  (web & Keycloak khác origin với API).
- Không re-encode ảnh ở MVP (giữ đơn giản); nếu DEV-spec thấy cần loại metadata/polyglot thì re-encode PNG/JPEG là tuỳ chọn.

## 5. Chi tiết từng hạng mục (dùng trực tiếp cho BA/DEV)

### 5.1 Logo

| Mục | Nội dung |
|---|---|
| Hiện trạng | SVG ngôi sao hardcode 3 nơi (AppShell, page.tsx, template.ftl ×2 bản) |
| Thay đổi | Upload ở admin/system-config (tab/section "Nhận diện hệ thống": preview hiện tại, chọn file, Lưu, "Khôi phục mặc định"); `BrandLogo` dùng chung ở web; Keycloak `<img src="{base}/public/branding/logo">` cạnh SVG |
| Nơi hiển thị | Sidebar web, trang chào/login web, header trang login Keycloak |
| Định dạng / giới hạn | PNG, JPEG, WebP; ≤ **1 MB**; mỗi chiều ≤ **2048 px**; khuyến nghị PNG nền trong suốt, tỉ lệ ~1:1 hoặc ngang ≤ 4:1, ≥ 128 px chiều cao |
| Storage | bucket `branding`, key `logo/<uuid>.<ext>`, file cũ giữ lại |
| Cache | Web: `?v=version` immutable 1 năm. Keycloak: URL cố định, max-age=90 + ETag/304 |
| Fallback | Chưa cấu hình / 404 / lỗi mạng / ảnh hỏng → SVG ngôi sao. Web: `logoUrl=null` render SVG trực tiếp, `onError` về SVG. Keycloak: SVG hiển thị mặc định, JS chỉ ẩn SVG khi `<img>` load thành công (kiểm cả trường hợp ảnh đã load/lỗi trước khi script chạy: `img.complete && naturalWidth`) |
| Audit | `BRANDING_LOGO_UPDATED` (key cũ/mới, size, type), `BRANDING_LOGO_RESET`, rollback qua system-config history |

### 5.2 Favicon

| Mục | Nội dung |
|---|---|
| Hiện trạng | Web: `web/src/app/favicon.ico` mặc định Next. Keycloak: trỏ `img/favicon.ico` không tồn tại → 404 |
| Thay đổi | Upload ở admin; web dùng `icons` trong `generateMetadata`; Keycloak đổi `<link rel="icon">` sang `{base}/public/branding/favicon` |
| Nơi hiển thị | Tab trình duyệt/bookmark của web và trang login Keycloak |
| Định dạng / giới hạn | ICO hoặc PNG; ≤ **256 KB**; PNG phải vuông, cạnh ≤ **512 px**; khuyến nghị ICO đa kích thước 16/32/48 hoặc PNG 32×32 / 48×48 (180×180 cho apple-touch-icon — ngoài phạm vi MVP, chỉ khuyến nghị) |
| Storage | bucket `branding`, key `favicon/<uuid>.<ext>` |
| Cache | Như logo. Lưu ý trình duyệt cache favicon rất "lì" (đặc biệt Keycloak URL cố định) → có thể cần hard-reload; web có `?v=` nên cập nhật đúng |
| Fallback | Web: `faviconUrl=null` hoặc backend lỗi → `/favicon.ico` của Next. Keycloak: backend trả favicon mặc định đóng gói khi chưa cấu hình; backend chết → trình duyệt không có icon (chấp nhận, không ảnh hưởng chức năng) |
| Audit | `BRANDING_FAVICON_UPDATED`, `BRANDING_FAVICON_RESET` |

### 5.3 Tên hệ thống

| Mục | Nội dung |
|---|---|
| Hiện trạng | Web title "Lưu trữ & Xử lý thông tin AI"; sidebar "LƯU TRỮ & XỬ LÝ / THÔNG TIN AI"; trang chào web & Keycloak "HỆ THỐNG LƯU TRỮ DỮ LIỆU & XỬ LÝ THÔNG TIN AI"; Keycloak `<title>` từ `realm.displayName` |
| Thay đổi | 2 key `branding.system_name` (≤ 120) và `branding.system_name_short` (≤ 40), sửa qua system-config (hiển thị trong cùng section "Nhận diện hệ thống") |
| Nơi hiển thị | `system_name`: title web (+ template "%s · …"), header trang chào web, header + `<title>` Keycloak. `system_name_short`: sidebar (trống → dùng `system_name`; cả hai trống → text hiện tại). Chữ hoa như hiện nay bằng CSS `text-transform: uppercase` |
| Validation | Trim; không rỗng khi set (muốn về mặc định thì đặt rỗng qua nút "Khôi phục mặc định" = lưu `""`); không cho ký tự điều khiển/xuống dòng; độ dài theo ký tự Unicode |
| Chống XSS | Web: chỉ render qua JSX text (cấm `dangerouslySetInnerHTML`); metadata do Next escape. Keycloak: chỉ `textContent`/`document.title` (cấm `innerHTML`); không đưa tên vào FreeMarker. Backend không strip HTML (lưu nguyên văn), an toàn nhờ escape đầu ra |
| Cache | JSON max-age=60; web SSR revalidate 60 s; Keycloak localStorage + fetch mỗi lần tải trang |
| Fallback | Trống/lỗi API → text mặc định hiện tại tại từng vị trí (giữ nguyên UI như hôm nay) |
| Audit | Audit sẵn có của system-config (key, oldValue, newValue) |

## 6. Tech stack đề xuất

- Backend: Spring Boot hiện có — package mới `branding` (controller admin + public, service validate/lưu),
  mở rộng `SystemConfigDefaults` (6 key) + rule managed key; `ObjectStorageService` thêm bucket `branding`;
  `SecurityConfig.PUBLIC_PATHS` → `/public/**`; favicon mặc định trong `src/main/resources`.
- Frontend: Next.js — `BrandLogo`, `BrandingProvider`, `getBranding()` server; section "Nhận diện hệ thống" trong trang admin/system-config.
- Keycloak: sửa `template.ftl` + `theme.properties` + thêm `resources/js/branding.js` ở **cả 2** bản theme.
- Data store: PostgreSQL `system_config` (+ Flyway seed nếu defaults cần migration), Redis (sẵn có), SeaweedFS bucket `branding`.
- Infra/Deploy: env `BRANDING_PUBLIC_BASE_URL` (keycloak), `BACKEND_INTERNAL_URL` (web, runtime); **không** sửa nginx.
  Stg: nếu dùng identity S3 riêng theo bucket, cấp quyền cho backend trên bucket `branding` (external-ai **không** cần).

## 7. Rủi ro

| Rủi ro | Mức độ | Giảm thiểu |
|---|---|---|
| Hai bản theme Keycloak (gốc / stg) lệch nhau | Trung bình | DEV sửa cả 2 trong cùng thay đổi; checklist deploy stg |
| KC 24 không hỗ trợ `${env.X}` trong theme.properties | Thấp | Xác minh ở DEV-spec; fallback hardcode base URL theo từng bản theme |
| Ảnh độc hại (polyglot, file giả đuôi) | Trung bình | Magic bytes, không SVG, Content-Type do server, nosniff + CSP sandbox, CORP |
| Nhấp nháy tên/logo trên Keycloak lần đầu | Thấp | localStorage cho tên; SVG làm placeholder cho logo |
| Favicon bị trình duyệt cache lâu hơn 90 s | Thấp | Chấp nhận; ghi chú trong hướng dẫn admin (hard-reload) |
| `PUBLIC_PATHS=/public/**` vô tình mở endpoint khác dưới `/public` | Trung bình | DEV rà soát mapping `/public` hiện có; test security cho từng path |
| Logo tỉ lệ lạ làm vỡ layout sidebar/header | Thấp | Khung cố định + `object-fit: contain`; preview ở trang admin trước khi lưu |
| Rollback `*_key` qua system-config trỏ tới object không tồn tại | Thấp | Service kiểm tra tồn tại + prefix trước khi áp dụng |

## 8. Câu hỏi mở

- [ ] Có cần UI chọn lại logo/favicon cũ (danh sách file đã upload) hay chỉ rollback qua lịch sử system-config là đủ cho MVP?
- [ ] Có cần `apple-touch-icon` (180×180) riêng không, hay chỉ favicon?
- [ ] Sau khi đổi tên, `realm.displayName` và `messages_vi.properties` (`loginTitle`) có cần đồng bộ không (hiện chỉ đổi qua JS)?
- [ ] Có cần dọn file branding cũ (retention) không — hiện giữ vô thời hạn, dung lượng không đáng kể.

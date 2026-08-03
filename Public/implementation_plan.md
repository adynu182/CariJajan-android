# CariJajan — Implementation Plan (MVP Fase 1)

Aplikasi Android untuk menghubungkan pembeli dengan pedagang kaki lima secara real-time berbasis lokasi.

## Keputusan Desain yang Telah Disepakati

| Area | Keputusan |
|---|---|
| Platform | Android saja (Kotlin + Jetpack Compose) |
| Backend | Supabase (Auth + PostgreSQL/PostGIS + Storage + REST) |
| Peta | MapLibre GL Native + MapTiler (tile provider) |
| Image Storage | Supabase Storage |
| Auth Penjual | Email + password (Supabase Auth) |
| Auth Pembeli | Opsional — wajib login (Google/email) hanya untuk rating, komentar, favorit |
| Min SDK | API 26 (Android 8.0) |
| Multi-listing | 1 penjual = 1 lapak (bisa ganti kategori) |
| Auto-close | Lapak disembunyikan otomatis setelah 24 jam tanpa update foto |
| Kategori | cilok, batagor, siomay, gorengan, minuman, makanan berat, dessert, lainnya |
| Radius pencarian | Slider 1–5 km, default 1 km |
| Tampilan pembeli | Peta + List View (keduanya sejak MVP) |
| Struktur app | Single app — penjual login, pembeli langsung ke peta |
| Rating | Akun pembeli wajib (Google/email) |
| Monetisasi | 100% gratis |
| Bahasa | Bahasa Indonesia (i18n-ready) |
| Wilayah | Satu kota percontohan dulu |

---

## User Review Required

> [!IMPORTANT]
> **Satu penjual = satu lapak**: ini menyederhanakan data model, tapi berarti tabel `listings` punya relasi 1:1 ke `sellers`. Lapak tidak bisa dihapus lalu dibuat ulang tanpa kehilangan histori foto. Pastikan ini memang yang diinginkan.

> [!IMPORTANT]
> **Rating memerlukan login pembeli**: ini memerlukan Supabase Auth dikonfigurasi untuk dua tipe user (penjual & pembeli). Akan diimplementasikan dengan satu tabel `users` + field `role` (`seller` | `buyer`). Penjual registrasi via email, pembeli bisa registrasi email **atau** Google OAuth.

> [!WARNING]
> **Auto-close 24 jam**: Supabase tidak punya built-in scheduler. Akan menggunakan **Supabase Edge Function + pg_cron** (tersedia di Supabase free tier) yang berjalan setiap jam untuk menandai lapak basi sebagai `is_open = false`.

> [!WARNING]
> **MapTiler API Key**: kamu perlu membuat akun MapTiler (gratis) dan menyimpan API key di `local.properties` (tidak di-commit ke git). Saya akan sediakan template `.env.example`.

---

## Open Questions

> [!NOTE]
> Tidak ada open question yang memblokir implementasi. Semua keputusan sudah disepakati di sesi grilling.

---

## Proposed Changes

Struktur folder project Android yang akan dibuat:

```
CariJajan/
├── app/                          # Android app module
│   ├── src/main/
│   │   ├── java/com/carijajan/
│   │   │   ├── data/
│   │   │   │   ├── local/        # Room database (cache)
│   │   │   │   ├── remote/       # Supabase API client
│   │   │   │   └── repository/   # Repository pattern
│   │   │   ├── domain/
│   │   │   │   ├── model/        # Data classes
│   │   │   │   └── usecase/      # Business logic
│   │   │   ├── ui/
│   │   │   │   ├── buyer/        # Layar pembeli (peta, list, detail)
│   │   │   │   ├── seller/       # Layar penjual (profil, upload, toggle)
│   │   │   │   ├── auth/         # Login & registrasi
│   │   │   │   └── common/       # Komponen UI reusable
│   │   │   └── MainActivity.kt
│   │   └── res/
│   └── build.gradle.kts
├── supabase/
│   ├── migrations/               # SQL schema migrations
│   └── functions/                # Edge Functions (nearby query, auto-close)
├── .env.example                  # Template API keys
└── local.properties              # API keys (gitignored)
```

---

### Component 1: Supabase Backend Setup

#### [NEW] `supabase/migrations/001_initial_schema.sql`
- Ekstensi: `postgis`, `pgcrypto`, `pg_cron`
- Tabel: `users` (dengan field `role: seller | buyer`), `listings` (1:1 dengan sellers), `listing_photos`, `reports`, `reviews`, `favorites`
- Index: GIST spatial index di `current_location`, partial index `is_open = true`
- RLS Policies: penjual hanya bisa edit lapak sendiri; endpoint publik expose kolom terbatas
- Auto-close: pg_cron job setiap jam → set `is_open = false` untuk lapak dengan `last_photo_at < now() - interval '24 hours'`

#### [NEW] `supabase/functions/nearby/index.ts`
- Edge Function untuk endpoint `GET /nearby?lat=&lng=&radius_km=&category=`
- Query PostGIS `ST_DWithin` + `ST_Distance` untuk radius search
- Rate limiting per-IP (max 60 req/menit)
- Response: array lapak dengan `distance_km`, `thumbnail_url`, `is_open`, `last_photo_at`

#### [NEW] `supabase/functions/auto-close/index.ts`
- Dipanggil oleh pg_cron setiap jam
- Update `is_open = false` + catat alasan di log untuk lapak yang foto terakhirnya > 24 jam

---

### Component 2: Android Project Setup

#### [NEW] `app/build.gradle.kts`
- `minSdk = 26`, `targetSdk = 35` (latest)
- Dependencies kunci:
  - `io.github.jan-tennert.supabase:postgrest-kt` — Supabase Kotlin client
  - `io.github.jan-tennert.supabase:auth-kt` — Supabase Auth
  - `io.github.jan-tennert.supabase:storage-kt` — Supabase Storage
  - `org.maplibre.gl:android-sdk` — MapLibre GL Native
  - `androidx.camera:camera-camera2` + `camera-lifecycle` + `camera-view` — CameraX
  - `com.google.android.gms:play-services-location` — FusedLocationProviderClient
  - `androidx.room:room-runtime` + `room-ktx` — Room cache
  - `androidx.work:work-runtime-ktx` — WorkManager retry upload
  - `io.coil-kt:coil-compose` — image loading
  - `androidx.navigation:navigation-compose` — navigasi antar layar
  - Coroutines + Flow

---

### Component 3: Data Layer

#### [NEW] `data/remote/SupabaseClient.kt`
- Singleton Supabase client (URL + anon key dari `BuildConfig`)
- Konfigurasi Auth + Storage + PostgREST modules

#### [NEW] `data/remote/ListingApi.kt`
- `getNearby(lat, lng, radiusKm, category?)` → panggil Edge Function `/nearby`
- `getListing(id)` → `GET /public/listings/:id`
- `createListing(...)` → `POST /listings` (authenticated)
- `updateListing(...)` → `PUT /listings/:id` (authenticated)
- `toggleOpen(id, isOpen)` → `PATCH /listings/:id/status`
- `uploadPhoto(listingId, imageFile, lat, lng, capturedAt)` → upload ke Supabase Storage + insert ke `listing_photos`
- `reportListing(listingId, reason, detail)` → `POST /public/listings/:id/report`

#### [NEW] `data/local/AppDatabase.kt` (Room)
- Entity: `CachedListingEntity` — cache listing terdekat terakhir
- DAO: `ListingDao` dengan query by distance, category filter
- Digunakan sebagai fallback saat offline

#### [NEW] `data/repository/ListingRepository.kt`
- Koordinasi antara remote API dan Room cache
- Emit Flow: load dari cache dulu, lalu refresh dari network, emit lagi

#### [NEW] `data/repository/AuthRepository.kt`
- `registerSeller(email, password, name, phone)` → Supabase Auth signUp + insert ke `users`
- `loginSeller(email, password)` → Supabase Auth signIn
- `loginBuyer(email, password)` atau `loginWithGoogle()` → Supabase Auth OAuth
- `logout()`, `currentUser()`, `isLoggedIn()`

---

### Component 4: Domain Layer

#### [NEW] `domain/model/Listing.kt`
```kotlin
data class Listing(
    val id: String,
    val sellerId: String,
    val name: String,
    val category: Category,
    val description: String?,
    val priceMin: Int?,
    val priceMax: Int?,
    val isOpen: Boolean,
    val lat: Double,
    val lng: Double,
    val distanceKm: Double?,
    val lastPhotoAt: Instant?,
    val thumbnailUrl: String?,
    val photos: List<ListingPhoto>
)

enum class Category {
    CILOK, BATAGOR, SIOMAY, GORENGAN, MINUMAN,
    MAKANAN_BERAT, DESSERT, LAINNYA
}
```

#### [NEW] `domain/usecase/` (use cases utama)
- `GetNearbyListingsUseCase` — filter + sort by distance
- `UploadPhotoWithLocationUseCase` — ambil GPS (dengan validasi akurasi + mock-location check), kompres foto, upload
- `ToggleListingStatusUseCase`
- `SubmitReportUseCase`
- `GetListingDetailUseCase`

---

### Component 5: UI Layer — Auth

#### [NEW] `ui/auth/LoginScreen.kt`
- Tab: Login / Daftar (untuk Penjual)
- Field: Email, Password (+ Nama, Nomor HP untuk registrasi)
- CTA: "Masuk sebagai Pembeli" (langsung ke peta tanpa akun)

#### [NEW] `ui/auth/BuyerLoginScreen.kt`
- Muncul saat pembeli mencoba akses fitur rating/favorit
- Opsi: Login dengan Email atau Google OAuth
- Tombol "Lanjutkan tanpa akun" (kembali ke browsing)

---

### Component 6: UI Layer — Pembeli

#### [NEW] `ui/buyer/BuyerMapScreen.kt`
- MapLibre GL Native map centered on user's location
- Marker per lapak `is_open = true` — warna berbeda per kategori
- Floating panel bawah: slider radius 1–5 km (default 1 km) + filter kategori chips
- FAB "switch ke list view"
- Loading: shimmer skeleton saat fetch data
- Empty state: "Belum ada pedagang di radius ini, coba perbesar jangkauan"

#### [NEW] `ui/buyer/BuyerListScreen.kt`
- LazyColumn diurutkan dari terdekat
- Card: thumbnail, nama, kategori, jarak, status buka/tutup, "update X menit lalu"
- Filter bar: kategori + radius (sama seperti peta)
- Pull to refresh

#### [NEW] `ui/buyer/ListingDetailScreen.kt`
- Galeri foto (HorizontalPager jika > 1 foto)
- Info: nama, kategori, harga, deskripsi, jarak, "buka/tutup", "update X menit lalu"
- Tombol primer: "Arahkan ke sini" → deep link Google Maps/Waze
- Tombol sekunder: "Bagikan via WhatsApp" (intent share)
- Tombol tersier: "Laporkan lapak" (bottom sheet dengan pilihan alasan)
- Bagian rating & ulasan: tampil daftar review; tombol "Tulis ulasan" (perlu login)

---

### Component 7: UI Layer — Penjual

#### [NEW] `ui/seller/SellerDashboardScreen.kt`
- Header: foto profil, nama, status lapak (buka/tutup toggle besar)
- Card lapak: nama, kategori, foto terbaru, "update X menit lalu"
- Tombol: "Update Foto Lapak" (fitur inti), "Edit Info Lapak", "Lihat Histori Foto"
- Info: jumlah view lapak minggu ini

#### [NEW] `ui/seller/UploadPhotoScreen.kt`
- Integrasi CameraX untuk ambil foto
- **Alur GPS capture:**
  1. Saat shutter ditekan → langsung ambil GPS (HIGH_ACCURACY)
  2. Validasi: akurasi < 50m, bukan mock location
  3. Jika GPS tidak valid → peringatan + opsi coba ulang
  4. Preview foto + overlay info lokasi + waktu
  5. Konfirmasi → kompres foto (≤500 KB, max 1280px) → upload via WorkManager
- Progress indicator selama upload
- Setelah sukses: lapak otomatis kembali muncul di peta pembeli

#### [NEW] `ui/seller/EditListingScreen.kt`
- Form: nama lapak, kategori (dropdown expanded), kisaran harga, deskripsi
- Validasi form sebelum submit

#### [NEW] `ui/seller/PhotoHistoryScreen.kt`
- Daftar foto yang pernah diunggah, dengan waktu + lokasi (thumbnail peta kecil)
- Opsi set sebagai foto utama (primary)

---

### Component 8: Background Work

#### [NEW] `data/work/PhotoUploadWorker.kt` (WorkManager)
- Upload foto ke Supabase Storage
- Retry otomatis (exponential backoff) jika gagal karena jaringan
- Setelah berhasil: update `listing_photos` + update `current_location` di `listings`

---

### Component 9: Navigation & App Shell

#### [NEW] `ui/navigation/AppNavGraph.kt`
- Bottom navigation untuk penjual yang sudah login: Dashboard | Peta | Profil
- Pembeli tanpa login: Peta saja (dengan prompt login saat akses fitur terkunci)
- Route: auth → onboarding → main

#### [MODIFY] `AndroidManifest.xml`
- Permission: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `CAMERA`, `INTERNET`, `POST_NOTIFICATIONS`
- Deep link handling untuk share lapak

---

### Component 10: Config & Security

#### [NEW] `local.properties` (gitignored)
```
SUPABASE_URL=https://xxxx.supabase.co
SUPABASE_ANON_KEY=eyJhbGci...
MAPTILER_API_KEY=xxxx
```

#### [NEW] `.env.example`
- Template tanpa value nyata, di-commit ke git sebagai panduan

#### [NEW] `network_security_config.xml`
- Hanya allow HTTPS untuk koneksi production

---

## Verification Plan

### Automated Tests
```bash
# Unit tests
./gradlew test

# Instrumented tests (emulator/device)
./gradlew connectedAndroidTest
```

Key test cases:
- `UploadPhotoWithLocationUseCase`: mock GPS invalid → pastikan error ter-throw, tidak lanjut upload
- `GetNearbyListingsUseCase`: data dari Room cache dulu, baru dari network
- `ListingRepository`: pastikan auto-close logic terpicu setelah 24 jam (dengan mock time)
- Supabase RLS: penjual A tidak bisa edit lapak penjual B

### Manual Verification Steps
1. **Happy path penjual**: Register → buat lapak → ambil foto → lapak muncul di peta pembeli ✓
2. **Happy path pembeli**: Buka app → lihat peta → filter kategori → tap detail → arahkan ke sini ✓
3. **Auto-close**: Set `last_photo_at` = 25 jam lalu via SQL → verifikasi lapak hilang dari peta ✓
4. **Mock GPS detection**: Install app mock GPS → coba upload foto → pastikan ter-blokir ✓
5. **Offline mode**: Nonaktifkan internet → pastikan list terakhir masih tampil dari cache ✓
6. **Upload retry**: Matikan internet di tengah upload → nyalakan lagi → WorkManager retry berhasil ✓

---

## Urutan Implementasi (Sprint Plan)

### Sprint 1 — Backend Foundation (Hari 1–2)
1. Setup project Supabase (buat project, aktifkan PostGIS, pg_cron)
2. Jalankan migration SQL (schema + RLS policies)
3. Deploy Edge Function `/nearby` dan `/auto-close`
4. Test query PostGIS via Supabase dashboard SQL editor

### Sprint 2 — Android Project Setup (Hari 3)
5. Init Android project (Kotlin, Compose, minSdk 26)
6. Setup Supabase Kotlin client
7. Setup MapLibre + MapTiler
8. Setup Navigation graph + bottom navigation shell

### Sprint 3 — Auth Flow (Hari 4)
9. Login/Register screen penjual
10. AuthRepository + ViewModel
11. Token persistence (Supabase session auto-refresh)
12. BuyerLoginScreen (Google OAuth + email)

### Sprint 4 — Fitur Inti Penjual (Hari 5–7)
13. CameraX integration
14. GPS capture + validasi akurasi + mock detection
15. Kompres foto di client
16. WorkManager upload worker
17. SellerDashboard + toggle buka/tutup
18. EditListingScreen

### Sprint 5 — Fitur Pembeli (Hari 8–10)
19. BuyerMapScreen (MapLibre + marker per lapak)
20. Radius slider + filter kategori
21. BuyerListScreen (LazyColumn + sort by distance)
22. ListingDetailScreen (galeri foto + navigasi + share WA)
23. Report flow (bottom sheet)

### Sprint 6 — Polish & Testing (Hari 11–14)
24. Empty states, loading shimmer, error states
25. Offline cache (Room) + retry logic
26. Unit tests & integration tests
27. Privacy Policy page (wajib untuk Play Store)
28. App icon, splash screen, onboarding singkat

ok
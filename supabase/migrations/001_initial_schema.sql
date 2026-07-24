-- ============================================================
-- CariJajan — Initial Schema Migration
-- Run this in Supabase SQL Editor
-- ============================================================

-- 1. Extensions
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_cron;      -- jadwalkan auto-close

-- ============================================================
-- 2. Tabel users (penjual & pembeli — satu tabel, role-based)
-- ============================================================
-- Catatan: kolom `id` harus sama dengan auth.users.id Supabase (1:1)
CREATE TABLE public.users (
    id          UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    role        VARCHAR(10) NOT NULL CHECK (role IN ('seller', 'buyer')),
    full_name   VARCHAR(150) NOT NULL,
    phone_number VARCHAR(20),               -- wajib untuk penjual, opsional pembeli
    avatar_url  TEXT,
    is_verified BOOLEAN DEFAULT FALSE,
    status      VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'banned')),
    created_at  TIMESTAMPTZ DEFAULT now(),
    updated_at  TIMESTAMPTZ DEFAULT now()
);

-- ============================================================
-- 3. Tabel listings (1 penjual = 1 lapak)
-- ============================================================
CREATE TABLE public.listings (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id        UUID NOT NULL UNIQUE REFERENCES public.users(id) ON DELETE CASCADE,
    name             VARCHAR(150) NOT NULL,
    category         VARCHAR(30) NOT NULL CHECK (category IN (
                         'cilok', 'batagor', 'siomay', 'gorengan',
                         'minuman', 'makanan_berat', 'dessert', 'lainnya'
                     )),
    description      TEXT,
    price_min        INTEGER CHECK (price_min >= 0),
    price_max        INTEGER CHECK (price_max >= 0),
    is_open          BOOLEAN DEFAULT FALSE,
    current_location GEOGRAPHY(POINT, 4326),   -- koordinat foto terakhir
    last_photo_at    TIMESTAMPTZ,              -- dipakai untuk auto-close 24 jam
    view_count       INTEGER DEFAULT 0,
    created_at       TIMESTAMPTZ DEFAULT now(),
    updated_at       TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_listings_location ON public.listings USING GIST (current_location);
CREATE INDEX idx_listings_category ON public.listings (category);
CREATE INDEX idx_listings_open     ON public.listings (is_open) WHERE is_open = TRUE;
CREATE INDEX idx_listings_seller   ON public.listings (seller_id);

-- ============================================================
-- 4. Tabel listing_photos (riwayat foto + lokasi per unggahan)
-- ============================================================
CREATE TABLE public.listing_photos (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id      UUID NOT NULL REFERENCES public.listings(id) ON DELETE CASCADE,
    photo_url       TEXT NOT NULL,
    thumbnail_url   TEXT,
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,
    gps_accuracy_m  REAL,                      -- akurasi GPS saat capture (meter)
    captured_at     TIMESTAMPTZ NOT NULL,       -- waktu diambil di device
    is_primary      BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_photos_listing    ON public.listing_photos (listing_id);
CREATE INDEX idx_photos_captured   ON public.listing_photos (captured_at DESC);

-- Pastikan hanya satu foto primary per listing
CREATE UNIQUE INDEX idx_photos_primary_unique
    ON public.listing_photos (listing_id)
    WHERE is_primary = TRUE;

-- ============================================================
-- 5. Tabel reports (laporan dari pembeli anonim)
-- ============================================================
CREATE TABLE public.reports (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id       UUID REFERENCES public.listings(id) ON DELETE CASCADE,
    reason           VARCHAR(100) NOT NULL CHECK (reason IN (
                         'lokasi_salah', 'konten_tidak_pantas', 'lapak_palsu', 'sudah_tutup', 'lainnya'
                     )),
    detail           TEXT,
    reporter_device_id VARCHAR(100),            -- device fingerprint anonim
    status           VARCHAR(20) DEFAULT 'pending' CHECK (status IN ('pending', 'reviewed', 'dismissed')),
    created_at       TIMESTAMPTZ DEFAULT now()
);

-- ============================================================
-- 6. Tabel reviews (Fase 2 — butuh akun pembeli)
-- ============================================================
CREATE TABLE public.reviews (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id      UUID NOT NULL REFERENCES public.listings(id) ON DELETE CASCADE,
    reviewer_id     UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    rating          SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment         TEXT,
    created_at      TIMESTAMPTZ DEFAULT now(),
    UNIQUE (listing_id, reviewer_id)            -- 1 user = 1 review per lapak
);

CREATE INDEX idx_reviews_listing ON public.reviews (listing_id);

-- ============================================================
-- 7. Tabel favorites (Fase 2 — butuh akun pembeli)
-- ============================================================
CREATE TABLE public.favorites (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    listing_id  UUID NOT NULL REFERENCES public.listings(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ DEFAULT now(),
    UNIQUE (user_id, listing_id)
);

-- ============================================================
-- 8. Helper function: update updated_at otomatis
-- ============================================================
CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON public.users
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER trg_listings_updated_at
    BEFORE UPDATE ON public.listings
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- ============================================================
-- 9. Function: auto-update listings.current_location saat foto baru diupload
-- ============================================================
CREATE OR REPLACE FUNCTION public.sync_listing_location()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE public.listings
    SET
        current_location = ST_MakePoint(NEW.longitude, NEW.latitude)::geography,
        last_photo_at    = NEW.captured_at,
        is_open          = TRUE,
        updated_at       = now()
    WHERE id = NEW.listing_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sync_listing_location
    AFTER INSERT ON public.listing_photos
    FOR EACH ROW EXECUTE FUNCTION public.sync_listing_location();

-- ============================================================
-- 10. Auto-close: tandai lapak basi (foto > 24 jam) sebagai is_open = false
-- ============================================================
CREATE OR REPLACE FUNCTION public.auto_close_stale_listings()
RETURNS void AS $$
BEGIN
    UPDATE public.listings
    SET is_open = FALSE, updated_at = now()
    WHERE is_open = TRUE
      AND (last_photo_at IS NULL OR last_photo_at < now() - INTERVAL '24 hours');
END;
$$ LANGUAGE plpgsql;

-- Jadwalkan setiap jam via pg_cron
SELECT cron.schedule(
    'auto-close-stale-listings',     -- nama job
    '0 * * * *',                     -- setiap jam tepat
    'SELECT public.auto_close_stale_listings();'
);

-- ============================================================
-- 11. Row Level Security (RLS)
-- ============================================================

-- --- users ---
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;

-- User bisa baca profil sendiri
CREATE POLICY "users_select_own" ON public.users
    FOR SELECT USING (auth.uid() = id);

-- User bisa update profil sendiri
CREATE POLICY "users_update_own" ON public.users
    FOR UPDATE USING (auth.uid() = id);

-- Endpoint publik bisa baca nama & avatar (untuk tampil di detail lapak)
CREATE POLICY "users_select_public_fields" ON public.users
    FOR SELECT USING (TRUE);  -- dikontrol di Edge Function, bukan di level row

-- --- listings ---
ALTER TABLE public.listings ENABLE ROW LEVEL SECURITY;

-- Siapapun bisa baca listing yang is_open = true (untuk pembeli)
CREATE POLICY "listings_select_open" ON public.listings
    FOR SELECT USING (is_open = TRUE OR auth.uid() = seller_id);

-- Penjual hanya bisa insert/update/delete lapak sendiri
CREATE POLICY "listings_insert_own" ON public.listings
    FOR INSERT WITH CHECK (auth.uid() = seller_id);

CREATE POLICY "listings_update_own" ON public.listings
    FOR UPDATE USING (auth.uid() = seller_id);

CREATE POLICY "listings_delete_own" ON public.listings
    FOR DELETE USING (auth.uid() = seller_id);

-- --- listing_photos ---
ALTER TABLE public.listing_photos ENABLE ROW LEVEL SECURITY;

-- Semua orang bisa baca foto (foto sudah publik)
CREATE POLICY "photos_select_all" ON public.listing_photos
    FOR SELECT USING (TRUE);

-- Hanya penjual pemilik lapak yang bisa upload foto
CREATE POLICY "photos_insert_own" ON public.listing_photos
    FOR INSERT WITH CHECK (
        auth.uid() = (SELECT seller_id FROM public.listings WHERE id = listing_id)
    );

-- --- reports ---
ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;

-- Siapapun bisa buat laporan (tanpa akun)
CREATE POLICY "reports_insert_all" ON public.reports
    FOR INSERT WITH CHECK (TRUE);

-- Hanya admin yang bisa baca laporan (service_role bypass RLS)
CREATE POLICY "reports_select_none" ON public.reports
    FOR SELECT USING (FALSE);  -- blokir read biasa; akses via service_role key saja

-- --- reviews ---
ALTER TABLE public.reviews ENABLE ROW LEVEL SECURITY;

-- Semua orang bisa baca ulasan
CREATE POLICY "reviews_select_all" ON public.reviews
    FOR SELECT USING (TRUE);

-- Hanya pembeli yang sudah login bisa buat ulasan
CREATE POLICY "reviews_insert_own" ON public.reviews
    FOR INSERT WITH CHECK (auth.uid() = reviewer_id);

-- Pembeli bisa hapus ulasan sendiri
CREATE POLICY "reviews_delete_own" ON public.reviews
    FOR DELETE USING (auth.uid() = reviewer_id);

-- --- favorites ---
ALTER TABLE public.favorites ENABLE ROW LEVEL SECURITY;

CREATE POLICY "favorites_select_own" ON public.favorites
    FOR SELECT USING (auth.uid() = user_id);

CREATE POLICY "favorites_insert_own" ON public.favorites
    FOR INSERT WITH CHECK (auth.uid() = user_id);

CREATE POLICY "favorites_delete_own" ON public.favorites
    FOR DELETE USING (auth.uid() = user_id);

-- ============================================================
-- 12. Increment view_count (aman dari race condition)
-- ============================================================
CREATE OR REPLACE FUNCTION public.increment_view_count(listing_uuid UUID)
RETURNS void AS $$
BEGIN
    UPDATE public.listings SET view_count = view_count + 1 WHERE id = listing_uuid;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

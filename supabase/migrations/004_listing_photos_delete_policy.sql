-- ============================================================================
-- Migration 004: policy DELETE untuk listing_photos
-- ============================================================================
-- listing_photos sebelumnya cuma punya policy SELECT (photos_select_all) &
-- INSERT (photos_insert_own) — tidak ada policy DELETE sama sekali. Akibatnya
-- kalau penjual coba hapus foto lama dari app, RLS diam-diam menolak: query
-- DELETE-nya "berhasil" (tidak error) tapi 0 baris yang benar-benar terhapus.
--
-- Migration ini menambah policy DELETE supaya penjual bisa menghapus foto
-- milik lapak mereka sendiri (dipakai fitur "kelola foto lapak" di
-- UploadPhotoScreen).
-- ============================================================================

CREATE POLICY "photos_delete_own" ON public.listing_photos
    FOR DELETE
    USING (
        auth.uid() = (SELECT seller_id FROM public.listings WHERE id = listing_id)
    );

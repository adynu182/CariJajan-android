-- ============================================================
-- Fix: seller photo upload never worked because the Supabase
-- Storage bucket the app uploads to ("listing-photos") was
-- never created, and storage.objects has RLS enabled by
-- default with no policies for it — so every upload from
-- PhotoUploadWorker failed (bucket not found / permission
-- denied), even though the client code was correct.
-- ============================================================

-- 1. Bucket used by PhotoUploadWorker.kt (STORAGE_BUCKET = "listing-photos").
--    Public so listing photos can be read by anyone browsing the map,
--    matching the existing "photos_select_all" policy on listing_photos.
insert into storage.buckets (id, name, public)
values ('listing-photos', 'listing-photos', true)
on conflict (id) do nothing;

-- 2. Public read. Even on a public bucket, storage.objects RLS is still
--    enforced for the client-side download path, so this policy is required.
create policy "listing_photos_public_read"
    on storage.objects for select
    using (bucket_id = 'listing-photos');

-- 3. Sellers may only upload into their OWN listing's folder.
--    App uploads to path: listings/{listingId}/{timestamp}_{filename}
--    -> (storage.foldername(name))[1] = 'listings', [2] = listingId
create policy "listing_photos_insert_own"
    on storage.objects for insert
    to authenticated
    with check (
        bucket_id = 'listing-photos'
        and (storage.foldername(name))[1] = 'listings'
        and (storage.foldername(name))[2] in (
            select id::text from public.listings where seller_id = auth.uid()
        )
    );

-- 4. Upload uses `upsert = true`, which can trigger an UPDATE on retry —
--    needs its own policy, insert policy alone does not cover it.
create policy "listing_photos_update_own"
    on storage.objects for update
    to authenticated
    using (
        bucket_id = 'listing-photos'
        and (storage.foldername(name))[1] = 'listings'
        and (storage.foldername(name))[2] in (
            select id::text from public.listings where seller_id = auth.uid()
        )
    )
    with check (
        bucket_id = 'listing-photos'
        and (storage.foldername(name))[1] = 'listings'
        and (storage.foldername(name))[2] in (
            select id::text from public.listings where seller_id = auth.uid()
        )
    );

-- 5. Let sellers clean up their own old photos.
create policy "listing_photos_delete_own"
    on storage.objects for delete
    to authenticated
    using (
        bucket_id = 'listing-photos'
        and (storage.foldername(name))[1] = 'listings'
        and (storage.foldername(name))[2] in (
            select id::text from public.listings where seller_id = auth.uid()
        )
    );

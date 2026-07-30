-- ============================================================
-- Fix: public.users has RLS enabled (migration 001) but was
-- missing an INSERT policy. With RLS enabled and no INSERT
-- policy, Postgres denies ALL inserts by default — including
-- a user inserting their own row right after sign up.
--
-- This silently broke seller & buyer registration: the
-- `client.postgrest["users"].insert(...)` call in AuthApi.kt
-- would fail with a 42501 (permission denied / RLS) error
-- even when a valid session was present.
-- ============================================================

CREATE POLICY "users_insert_own" ON public.users
    FOR INSERT WITH CHECK (auth.uid() = id);

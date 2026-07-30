import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// Rate limiting: simpel in-memory counter per IP
// Untuk production, ganti dengan Redis/Upstash
const rateLimitMap = new Map<string, { count: number; resetAt: number }>();
const RATE_LIMIT = 60;         // max request per window
const RATE_WINDOW_MS = 60_000; // 1 menit

function checkRateLimit(ip: string): boolean {
  const now = Date.now();
  const entry = rateLimitMap.get(ip);

  if (!entry || now > entry.resetAt) {
    rateLimitMap.set(ip, { count: 1, resetAt: now + RATE_WINDOW_MS });
    return true; // allowed
  }

  if (entry.count >= RATE_LIMIT) return false; // blocked

  entry.count++;
  return true; // allowed
}

serve(async (req: Request) => {
  // --- Rate limiting ---
  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";
  if (!checkRateLimit(ip)) {
    return new Response(
      JSON.stringify({ error: "Terlalu banyak permintaan. Coba lagi dalam 1 menit." }),
      { status: 429, headers: { "Content-Type": "application/json" } }
    );
  }

  // --- CORS preflight ---
  if (req.method === "OPTIONS") {
    return new Response(null, {
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "POST, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type, Authorization",
      },
    });
  }

  // PENTING: client Android (supabase-kt) selalu mengirim POST untuk
  // `client.functions.invoke(...)` — tidak ada opsi bawaan untuk kirim GET.
  // Fungsi ini sebelumnya HANYA menerima GET dan membaca parameter dari query
  // string, jadi setiap panggilan dari app selalu kena 405 "Method tidak
  // diizinkan", exception itu tertelan diam-diam di ListingRepository, dan
  // peta/daftar lapak jadi terlihat kosong / loading selamanya.
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method tidak diizinkan" }), {
      status: 405,
      headers: { "Content-Type": "application/json" },
    });
  }

  // --- Parse body JSON (dikirim oleh client, bukan query string) ---
  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return new Response(
      JSON.stringify({ error: "Body request tidak valid" }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }

  const lat        = parseFloat(String(body.lat ?? ""));
  const lng        = parseFloat(String(body.lng ?? ""));
  const radiusKm   = Math.min(parseFloat(String(body.radius_km ?? "1")), 5); // max 5 km
  const category   = (body.category as string | undefined) ?? null;
  const page       = Math.max(parseInt(String(body.page ?? "1")), 1);
  const pageSize   = Math.min(parseInt(String(body.page_size ?? "50")), 100);
  const offset     = (page - 1) * pageSize;

  // Validasi koordinat
  if (isNaN(lat) || isNaN(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
    return new Response(
      JSON.stringify({ error: "Parameter lat/lng tidak valid" }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }

  if (isNaN(radiusKm) || radiusKm <= 0) {
    return new Response(
      JSON.stringify({ error: "Parameter radius_km tidak valid (1–5 km)" }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }

  // Validasi kategori
  const VALID_CATEGORIES = [
    "cilok", "batagor", "siomay", "gorengan",
    "minuman", "makanan_berat", "dessert", "lainnya",
  ];
  if (category && !VALID_CATEGORIES.includes(category)) {
    return new Response(
      JSON.stringify({ error: "Kategori tidak valid" }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }

  // --- Supabase client (service role untuk bypass RLS di read) ---
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!
  );

  // Supabase JS tidak support raw parameterized SQL langsung — pakai postgrest
  // dengan filter manual, lalu hitung jarak presisi di JS dengan haversine.
  let sbQuery = supabase
    .from("listings")
    .select(`
      id, name, category, description, price_min, price_max,
      is_open, last_photo_at, view_count,
      current_location,
      users!seller_id (full_name, avatar_url),
      listing_photos (photo_url, thumbnail_url, is_primary),
      reviews (rating)
    `)
    .eq("is_open", true)
    .not("current_location", "is", null);
  // ^ NB: listing_photos SENGAJA tidak pakai `!inner` — itu inner join yang
  // menyembunyikan SEMUA lapak yang belum punya foto sama sekali (termasuk
  // lapak baru yang baru saja daftar). Pembeli tetap harus bisa lihat lapak
  // walau penjualnya belum sempat upload foto.

  if (category) {
    sbQuery = sbQuery.eq("category", category);
  }

  const { data: rows, error: sbError } = await sbQuery
    .range(offset, offset + pageSize - 1);

  if (sbError) {
    console.error("DB error:", sbError);
    return new Response(
      JSON.stringify({ error: "Gagal mengambil data lapak" }),
      { status: 500, headers: { "Content-Type": "application/json" } }
    );
  }

  // Hitung jarak di JS (karena postgrest tidak support ST_DWithin langsung)
  const EARTH_RADIUS_KM = 6371;

  function haversineKm(lat1: number, lng1: number, lat2: number, lng2: number): number {
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLng = (lng2 - lng1) * Math.PI / 180;
    const a = Math.sin(dLat / 2) ** 2
      + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * Math.sin(dLng / 2) ** 2;
    return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  // Parse geography point dari PostgREST response
  // Format: "POINT(lng lat)"
  function parsePoint(geog: string | null): { lat: number; lng: number } | null {
    if (!geog) return null;
    const match = geog.match(/POINT\(([^ ]+) ([^ )]+)\)/);
    if (!match) return null;
    return { lng: parseFloat(match[1]), lat: parseFloat(match[2]) };
  }

  const result = (rows ?? [])
    .map((row: Record<string, unknown>) => {
      const point = parsePoint(row.current_location as string | null);
      if (!point) return null;

      const distanceKm = haversineKm(lat, lng, point.lat, point.lng);
      if (distanceKm > radiusKm) return null; // filter strict by haversine

      const primaryPhoto = Array.isArray(row.listing_photos)
        ? (row.listing_photos as Array<Record<string, unknown>>).find((p) => p.is_primary) ??
          (row.listing_photos as Array<Record<string, unknown>>)[0] ?? null
        : null;

      const ratings = Array.isArray(row.reviews)
        ? (row.reviews as Array<Record<string, unknown>>).map((r) => r.rating as number)
        : [];
      const avgRating = ratings.length
        ? Math.round((ratings.reduce((a, b) => a + b, 0) / ratings.length) * 10) / 10
        : null;

      const seller = row.users as Record<string, unknown> | null;

      return {
        id: row.id,
        name: row.name,
        category: row.category,
        description: row.description,
        price_min: row.price_min,
        price_max: row.price_max,
        is_open: row.is_open,
        last_photo_at: row.last_photo_at,
        view_count: row.view_count,
        latitude: point.lat,
        longitude: point.lng,
        distance_km: Math.round(distanceKm * 100) / 100,
        primary_photo_url: primaryPhoto?.photo_url ?? null,
        primary_thumbnail_url: primaryPhoto?.thumbnail_url ?? null,
        seller_name: seller?.full_name ?? null,
        seller_avatar_url: seller?.avatar_url ?? null,
        avg_rating: avgRating,
        review_count: ratings.length,
      };
    })
    .filter(Boolean)
    .sort((a, b) => (a?.distance_km ?? 0) - (b?.distance_km ?? 0));

  return new Response(
    JSON.stringify({
      data: result,
      meta: {
        lat, lng,
        radius_km: radiusKm,
        category: category ?? null,
        page,
        page_size: pageSize,
        count: result.length,
      },
    }),
    {
      status: 200,
      headers: {
        "Content-Type": "application/json",
        "Access-Control-Allow-Origin": "*",
        "Cache-Control": "public, max-age=10", // cache 10 detik di CDN
      },
    }
  );
});

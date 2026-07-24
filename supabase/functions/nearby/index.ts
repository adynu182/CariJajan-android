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
        "Access-Control-Allow-Methods": "GET, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type, Authorization",
      },
    });
  }

  if (req.method !== "GET") {
    return new Response(JSON.stringify({ error: "Method tidak diizinkan" }), {
      status: 405,
      headers: { "Content-Type": "application/json" },
    });
  }

  // --- Parse query params ---
  const url = new URL(req.url);
  const lat        = parseFloat(url.searchParams.get("lat") ?? "");
  const lng        = parseFloat(url.searchParams.get("lng") ?? "");
  const radiusKm   = Math.min(parseFloat(url.searchParams.get("radius_km") ?? "1"), 5); // max 5 km
  const category   = url.searchParams.get("category") ?? null;
  const page       = Math.max(parseInt(url.searchParams.get("page") ?? "1"), 1);
  const pageSize   = Math.min(parseInt(url.searchParams.get("page_size") ?? "50"), 100);
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

  const radiusMeters = radiusKm * 1000;

  // --- Query PostGIS ---
  // Ambil kolom minimal yang perlu dilihat pembeli (jangan expose data sensitif)
  let query = `
    SELECT
      l.id,
      l.name,
      l.category,
      l.description,
      l.price_min,
      l.price_max,
      l.is_open,
      l.last_photo_at,
      l.view_count,
      ST_Y(l.current_location::geometry)  AS latitude,
      ST_X(l.current_location::geometry)  AS longitude,
      ROUND(
        (ST_Distance(l.current_location, ST_MakePoint($1, $2)::geography) / 1000.0)::numeric,
        2
      ) AS distance_km,
      -- foto utama
      p.photo_url     AS primary_photo_url,
      p.thumbnail_url AS primary_thumbnail_url,
      -- nama penjual (tanpa nomor HP)
      u.full_name     AS seller_name,
      u.avatar_url    AS seller_avatar_url,
      -- rata-rata rating
      COALESCE(
        ROUND(AVG(r.rating)::numeric, 1),
        0
      ) AS avg_rating,
      COUNT(r.id)::int AS review_count
    FROM public.listings l
    JOIN public.users u ON u.id = l.seller_id
    LEFT JOIN public.listing_photos p ON p.listing_id = l.id AND p.is_primary = TRUE
    LEFT JOIN public.reviews r ON r.listing_id = l.id
    WHERE l.is_open = TRUE
      AND l.current_location IS NOT NULL
      AND ST_DWithin(
            l.current_location,
            ST_MakePoint($1, $2)::geography,
            $3
          )
  `;

  const params: (number | string)[] = [lng, lat, radiusMeters];

  if (category) {
    params.push(category);
    query += ` AND l.category = $${params.length}`;
  }

  query += `
    GROUP BY l.id, u.full_name, u.avatar_url, p.photo_url, p.thumbnail_url
    ORDER BY distance_km ASC
    LIMIT $${params.length + 1} OFFSET $${params.length + 2}
  `;
  params.push(pageSize, offset);

  const { data, error } = await supabase.rpc("exec_sql", {}) // fallback ke raw query
    .catch(() => ({ data: null, error: { message: "RPC not available" } }));

  // Supabase JS tidak support raw parameterized SQL langsung — pakai rpc wrapper
  // Alternatif: pakai postgrest dengan computed columns, atau Edge Function saja
  // Di sini kita pakai supabase.from() dengan filter manual sebagai fallback bersih:
  const radiusDeg = radiusKm / 111.0; // ~approximation untuk bounding box pre-filter

  let sbQuery = supabase
    .from("listings")
    .select(`
      id, name, category, description, price_min, price_max,
      is_open, last_photo_at, view_count,
      current_location,
      users!seller_id (full_name, avatar_url),
      listing_photos!inner (photo_url, thumbnail_url, is_primary),
      reviews (rating)
    `)
    .eq("is_open", true)
    .not("current_location", "is", null);

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

  // Post-filter + hitung jarak di JS (karena postgrest tidak support ST_DWithin langsung)
  // Untuk scale besar, migrasi ke stored procedure/RPC
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

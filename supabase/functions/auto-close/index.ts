import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

/**
 * Auto-close Edge Function
 * Dipanggil oleh pg_cron setiap jam:
 *   SELECT net.http_post(url := '<function-url>/auto-close', headers := '{"Authorization": "Bearer <service-role-key>"}');
 *
 * Atau bisa dipanggil via Supabase SQL function publik (tanpa HTTP):
 *   SELECT public.auto_close_stale_listings();
 *
 * Edge Function ini berguna jika ingin trigger dari luar Supabase (e.g., GitHub Actions, cron eksternal)
 */
serve(async (req: Request) => {
  // Validasi authorization — hanya boleh dipanggil oleh service role / internal scheduler
  const authHeader = req.headers.get("Authorization");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

  if (!authHeader || authHeader !== `Bearer ${serviceRoleKey}`) {
    return new Response(JSON.stringify({ error: "Unauthorized" }), {
      status: 401,
      headers: { "Content-Type": "application/json" },
    });
  }

  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method tidak diizinkan" }), {
      status: 405,
      headers: { "Content-Type": "application/json" },
    });
  }

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    serviceRoleKey
  );

  // Ambil dulu lapak yang akan di-close untuk logging
  const { data: staleListings, error: fetchError } = await supabase
    .from("listings")
    .select("id, name, seller_id, last_photo_at")
    .eq("is_open", true)
    .or("last_photo_at.is.null,last_photo_at.lt." + new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString());

  if (fetchError) {
    console.error("Gagal fetch stale listings:", fetchError);
    return new Response(
      JSON.stringify({ error: "Gagal mengambil data lapak basi" }),
      { status: 500, headers: { "Content-Type": "application/json" } }
    );
  }

  if (!staleListings || staleListings.length === 0) {
    return new Response(
      JSON.stringify({ message: "Tidak ada lapak basi yang perlu di-close", closed: 0 }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  }

  const staleIds = staleListings.map((l) => l.id);

  // Close semua lapak basi sekaligus
  const { error: updateError } = await supabase
    .from("listings")
    .update({ is_open: false, updated_at: new Date().toISOString() })
    .in("id", staleIds);

  if (updateError) {
    console.error("Gagal update lapak basi:", updateError);
    return new Response(
      JSON.stringify({ error: "Gagal menutup lapak basi" }),
      { status: 500, headers: { "Content-Type": "application/json" } }
    );
  }

  console.log(`Auto-close: menutup ${staleIds.length} lapak basi`, {
    ids: staleIds,
    timestamp: new Date().toISOString(),
  });

  return new Response(
    JSON.stringify({
      message: `Berhasil menutup ${staleIds.length} lapak yang tidak update dalam 24 jam`,
      closed: staleIds.length,
      listing_ids: staleIds,
    }),
    {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }
  );
});

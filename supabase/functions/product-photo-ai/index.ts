import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function reply(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8" },
  });
}

function extractOutputText(data: any): string {
  const parts = Array.isArray(data?.output) ? data.output : [];
  return parts
    .flatMap((item: any) => Array.isArray(item?.content) ? item.content : [])
    .filter((item: any) => item?.type === "output_text" && typeof item.text === "string")
    .map((item: any) => item.text)
    .join("\n")
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/i, "")
    .trim();
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return reply({ error: "Gunakan metode POST." }, 405);
  }

  try {
    const authorization = req.headers.get("Authorization") || "";
    if (!authorization.startsWith("Bearer ")) {
      return reply({ error: "Sesi SeaMart tidak ditemukan. Silakan login ulang." }, 401);
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
    const openAiKey = Deno.env.get("OPENAI_API_KEY");
    if (!supabaseUrl || !anonKey) {
      return reply({ error: "Konfigurasi Supabase belum lengkap." }, 500);
    }
    if (!openAiKey) {
      return reply({ error: "OPENAI_API_KEY belum dipasang di Supabase Secrets." }, 503);
    }

    const supabase = createClient(supabaseUrl, anonKey, {
      global: { headers: { Authorization: authorization } },
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const { data: authData, error: authError } = await supabase.auth.getUser();
    if (authError || !authData.user) {
      return reply({ error: "Sesi login tidak valid. Silakan login ulang." }, 401);
    }

    const body = await req.json();
    const productName = String(body?.productName || "").trim().slice(0, 160);
    const barcode = String(body?.barcode || "").trim().slice(0, 80);
    const imageUrl = String(body?.imageUrl || "").trim();
    const sourceName = String(body?.sourceName || "").trim().slice(0, 200);

    if (!productName || !barcode || !imageUrl || !sourceName) {
      return reply({ error: "Nama produk, barcode, foto, dan nama produk sumber wajib tersedia." }, 400);
    }

    let parsedUrl: URL;
    try {
      parsedUrl = new URL(imageUrl);
    } catch {
      return reply({ error: "Alamat foto tidak valid." }, 400);
    }
    if (parsedUrl.protocol !== "https:" || !/(^|\.)openfoodfacts\.org$/i.test(parsedUrl.hostname)) {
      return reply({ error: "Sumber foto tidak dikenali. Gunakan foto katalog Open Food Facts." }, 400);
    }

    const { data: member, error: memberError } = await supabase
      .from("store_members")
      .select("store_id")
      .eq("user_id", authData.user.id)
      .limit(1)
      .maybeSingle();

    if (memberError || !member) {
      return reply({ error: "Akun ini belum terdaftar sebagai anggota toko SeaMart." }, 403);
    }

    const aiResponse = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        Authorization: "Bearer " + openAiKey,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: "gpt-4.1-mini",
        instructions: "Anda pemeriksa foto produk untuk aplikasi kasir. Utamakan kecocokan produk yang benar, bukan kemiripan merek. Tolak bila jenis barang, varian penting, atau kemasan tidak dapat dipastikan. Jangan menebak.",
        input: [{
          role: "user",
          content: [
            {
              type: "input_text",
              text: [
                "Periksa apakah gambar benar-benar menunjukkan produk yang dimaksud.",
                "Nama produk SeaMart: " + productName,
                "Barcode: " + barcode,
                "Nama produk pada katalog sumber: " + sourceName,
                "Jika nama atau jenis produk berbeda, tolak. Jika ragu, tolak.",
                "Kembalikan JSON saja dengan format: {\"match\":true atau false,\"confidence\":0 sampai 100,\"reason\":\"alasan singkat bahasa Indonesia\"}.",
              ].join("\n"),
            },
            { type: "input_image", image_url: imageUrl, detail: "low" },
          ],
        }],
        max_output_tokens: 180,
      }),
    });

    const aiData = await aiResponse.json();
    if (!aiResponse.ok) {
      const message = aiData?.error?.message || "Layanan pemeriksaan AI sedang bermasalah.";
      return reply({ error: message }, 502);
    }

    const outputText = extractOutputText(aiData);
    if (!outputText) {
      return reply({ error: "AI tidak memberikan hasil pemeriksaan. Coba lagi." }, 502);
    }

    let result: any;
    try {
      result = JSON.parse(outputText);
    } catch {
      return reply({ error: "Jawaban AI tidak terbaca. Coba ulangi pemeriksaan." }, 502);
    }

    const confidence = Math.max(0, Math.min(100, Number(result?.confidence) || 0));
    const match = result?.match === true && confidence >= 80;

    return reply({
      match,
      confidence,
      reason: String(result?.reason || (match ? "Foto dinilai cocok." : "Foto belum dapat dipastikan cocok.")),
      productName,
      sourceName,
      storeId: member.store_id,
    });
  } catch (error) {
    console.error("product-photo-ai error:", error);
    return reply({
      error: error instanceof Error ? error.message : "Terjadi kesalahan saat memeriksa foto produk.",
    }, 500);
  }
});

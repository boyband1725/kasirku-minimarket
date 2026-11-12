import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

  try {
    if (req.method !== "POST") return json({ error: "Method tidak diizinkan." }, 405);
    const authHeader = req.headers.get("Authorization") || "";
    if (!authHeader.startsWith("Bearer ")) return json({ error: "Silakan login kembali ke SeaMart." }, 401);

    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
    const supabase = createClient(supabaseUrl, anonKey, { global: { headers: { Authorization: authHeader } } });
    const { data: userData, error: userError } = await supabase.auth.getUser();
    if (userError || !userData.user) return json({ error: "Sesi login tidak valid. Login ulang." }, 401);

    const { productName, barcode, imageUrl, sourceName } = await req.json();
    if (!productName || !imageUrl || !sourceName) return json({ error: "Nama produk atau sumber foto tidak lengkap." }, 400);
    const { data: member, error: memberError } = await supabase.from("store_members").select("store_id").eq("user_id", userData.user.id).limit(1).maybeSingle();
    if (memberError || !member) return json({ error: "Akun tidak memiliki akses toko." }, 403);

    const apiKey = Deno.env.get("OPENAI_API_KEY");
    if (!apiKey) return json({ error: "OPENAI_API_KEY belum dipasang di Supabase Edge Function Secrets." }, 503);

    const aiResponse = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: { "Authorization": `Bearer ${apiKey}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: "gpt-4.1-mini",
        input: [{
          role: "user",
          content: [
            { type: "input_text", text: `Periksa apakah foto ini benar-benar menampilkan produk bernama "${productName}" (barcode: ${barcode || "tidak ada"}). Nama dari katalog sumber foto: "${sourceName}". Jangan menyetujui hanya karena merek mirip. Jika jenis/kemasan tidak jelas atau berbeda, nyatakan tidak cocok. Balas JSON saja: {"match":true/false,"confidence":0-100,"reason":"alasan singkat bahasa Indonesia"}.` },
            { type: "input_image", image_url: imageUrl, detail: "low" }
          ]
        }],
        max_output_tokens: 180
      })
    });
    const aiData = await aiResponse.json();
    if (!aiResponse.ok) return json({ error: aiData.error?.message || "Pemeriksaan AI gagal." }, 502);
    const output = (aiData.output || []).flatMap((x: any) => x.content || []).filter((x: any) => x.type === "output_text").map((x: any) => x.text).join("\n");
    const cleaned = output.replace(/```json|\`\`\`/g, "").trim();
    const result = JSON.parse(cleaned);
    return json({ match: result.match === true && Number(result.confidence) >= 75, confidence: Number(result.confidence) || 0, reason: String(result.reason || "Tidak ada keterangan"), sourceName, productName });
  } catch (error) {
    return json({ error: error instanceof Error ? error.message : "Terjadi kesalahan pemeriksaan AI." }, 500);
  }
});

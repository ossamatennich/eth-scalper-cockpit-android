package com.ethscalper.cockpit;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class AiAdvisor {
    public static final long TIMEOUT_MS = 6500L;

    private static final String ENDPOINT = "https://api.openai.com/v1/responses";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final Context context;
    private final OkHttpClient client;

    public AiAdvisor(Context context) {
        this.context = context.getApplicationContext();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(1800, TimeUnit.MILLISECONDS)
                .readTimeout(6000, TimeUnit.MILLISECONDS)
                .writeTimeout(1800, TimeUnit.MILLISECONDS)
                .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    public static boolean isEnabled(Context context) {
        return SecureAiStore.isEnabled(context);
    }


    public void testKeyAsync(ResultCallback callback) {
        String key = SecureAiStore.getKey(context);
        if (key == null || key.trim().length() < 12) {
            callback.onResult(AiResult.fallback("AI_KEY_MISSING"));
            return;
        }

        try {
            JSONObject body = new JSONObject();
            body.put("model", SecureAiStore.getModel(context));
            body.put("max_output_tokens", 400);
            body.put("reasoning", new JSONObject().put("effort", "minimal"));
            body.put("input", "Return exactly this JSON only, no markdown, no reasoning: {\"decision\":\"APPROVE\",\"confidence\":90,\"targetMove\":2.8,\"stopDistance\":1.35,\"reason\":\"TEST_OK\"}");

            Request request = new Request.Builder()
                    .url(ENDPOINT)
                    .addHeader("Authorization", "Bearer " + key.trim())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    callback.onResult(AiResult.fallback("AI_TIMEOUT_OR_NETWORK"));
                }

                @Override public void onResponse(Call call, Response response) {
                    try {
                        String raw = response.body() == null ? "" : response.body().string();
                        if (!response.isSuccessful()) {
                            callback.onResult(AiResult.fallback("AI_HTTP_" + response.code()));
                            return;
                        }
                        callback.onResult(parse(raw));
                    } catch (Exception e) {
                        callback.onResult(AiResult.fallback("AI_PARSE_ERROR"));
                    } finally {
                        try { response.close(); } catch (Exception ignored) {}
                    }
                }
            });
        } catch (Exception e) {
            callback.onResult(AiResult.fallback("AI_REQUEST_ERROR"));
        }
    }

    public void confirmAsync(MarketSnapshot s, SignalDecision decision, ResultCallback callback) {
        confirmAsync(s, decision, "[]", callback);
    }

    public void confirmAsync(MarketSnapshot s, SignalDecision decision, String microContextJson, ResultCallback callback) {
        String key = SecureAiStore.getKey(context);
        if (key == null || key.trim().length() < 12) {
            callback.onResult(AiResult.fallback("AI_KEY_MISSING"));
            return;
        }

        try {
            JSONObject body = new JSONObject();
            body.put("model", SecureAiStore.getModel(context));
            body.put("max_output_tokens", 650);
            body.put("reasoning", new JSONObject().put("effort", "minimal"));
            body.put("input", buildPrompt(s, decision, microContextJson));

            Request request = new Request.Builder()
                    .url(ENDPOINT)
                    .addHeader("Authorization", "Bearer " + key.trim())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    callback.onResult(AiResult.fallback("AI_TIMEOUT_OR_NETWORK"));
                }

                @Override public void onResponse(Call call, Response response) {
                    try {
                        String raw = response.body() == null ? "" : response.body().string();
                        if (!response.isSuccessful()) {
                            callback.onResult(AiResult.fallback("AI_HTTP_" + response.code()));
                            return;
                        }
                        callback.onResult(parse(raw));
                    } catch (Exception e) {
                        callback.onResult(AiResult.fallback("AI_PARSE_ERROR"));
                    } finally {
                        try { response.close(); } catch (Exception ignored) {}
                    }
                }
            });
        } catch (Exception e) {
            callback.onResult(AiResult.fallback("AI_REQUEST_ERROR"));
        }
    }

    private String buildPrompt(MarketSnapshot s, SignalDecision d, String microContextJson) {
        JSONObject x = new JSONObject();
        try {
            x.put("role", "ETH_SCALPER_V2305_EXPERT_ARBITER");
            x.put("mission", "Act as a strict professional ETH scalping arbiter. The Java engine proposes a candidate; you approve only if the market context, micro-replay, BTC, flow, timing, room and fees are coherent.");
            x.put("instruction",
                    "Return ONLY one compact JSON object. No markdown. No reasoning text. "
                            + "First character must be { and last character must be }. "
                            + "Prioritize capital protection. If uncertain, REJECT. "
                            + "Never approve RANGE_FADE_LONG if ETH is still falling with negative move1/move3/flow30 or BTC is falling. "
                            + "Never approve RANGE_FADE_SHORT if ETH is still rising with positive move1/move3/flow30 or BTC is rising. "
                            + "Never approve if entry is late, if price is chasing, or if TP is unrealistic after fees. "
                            + "For weak/fast scalp use targetMove 2.8. For clean scalp use 3.5. Use 5.5 only for premium continuation. "
                            + "Valid targetMove values: 2.8, 3.5, 5.5. Valid stopDistance values: 1.35, 1.65, 2.2.");

            x.put("schema", "{\"decision\":\"APPROVE|REJECT\",\"confidence\":0-100,\"targetMove\":2.8|3.5|5.5,\"stopDistance\":1.35|1.65|2.2,\"reason\":\"short text\"}");

            x.put("side", d.side);
            x.put("family", d.family);
            x.put("setupType", setupType(d));
            x.put("score", d.score);
            x.put("entry", round(d.entry));
            x.put("tp", round(d.takeProfit));
            x.put("sl", round(d.stopLoss));
            x.put("targetMove", d.targetMove);
            x.put("stopDistance", d.stopDistance);

            x.put("marketRegime", regime(s));
            x.put("riskFlags", riskFlags(s, d));

            x.put("ethLast", round(s.ethLast));
            x.put("spread", round(s.ethAsk - s.ethBid));
            x.put("rangePosition", round(s.rangePosition));
            x.put("roomLong", round(s.roomLong));
            x.put("roomShort", round(s.roomShort));

            x.put("move1", round(s.move1));
            x.put("move3", round(s.move3));
            x.put("move8", round(s.move8));
            x.put("move1Norm", round(s.move1Norm));
            x.put("move3Norm", round(s.move3Norm));
            x.put("move8Norm", round(s.move8Norm));
            x.put("moveAccel13", round(s.moveAccel13));
            x.put("moveAccel38", round(s.moveAccel38));

            x.put("flow15", round(s.flow15));
            x.put("flow30", round(s.flow30));
            x.put("flow60", round(s.flow60));
            x.put("flow120", round(s.flow120));
            x.put("deltaFlow15_60", round(s.deltaFlow15_60));
            x.put("deltaFlow30_120", round(s.deltaFlow30_120));
            x.put("flowAccel", round(s.flowAccel));

            x.put("btcMove1", round(s.btcMove1));
            x.put("btcMove3", round(s.btcMove3));
            x.put("btcMove8", round(s.btcMove8));
            x.put("btcAccel1_5", round(s.btcAccel1_5));
            x.put("btcAccel3_8", round(s.btcAccel3_8));

            x.put("antiBurstScore", round(s.antiBurstScore));
            x.put("volumeRatio", round(s.volumeRatio));
            x.put("feeRoundTrip", 1.33);
            x.put("slippageResearch", 0.10);
            x.put("netScalp28AfterCost", round(2.80 - 1.43));
            x.put("netScalp35AfterCost", round(3.50 - 1.43));
            x.put("netPremium55AfterCost", round(5.50 - 1.43));

            try {
                JSONArray micro = new JSONArray(microContextJson == null || microContextJson.trim().isEmpty() ? "[]" : microContextJson);
                x.put("microReplay", micro);
            } catch (Exception ignored) {
                x.put("microReplay", new JSONArray());
            }
        } catch (Exception ignored) {}

        return x.toString();
    }

    private static String setupType(SignalDecision d) {
        String family = d == null || d.family == null ? "" : d.family;
        if (family.contains("RANGE_FADE")) return "RANGE_FADE";
        if (family.contains("SCALP_CONTINUATION")) return "SCALP_CONTINUATION";
        if (family.contains("PREMIUM_CONTINUATION")) return "PREMIUM_CONTINUATION";
        return "UNKNOWN";
    }

    private static String regime(MarketSnapshot s) {
        double avg = Math.max(0.35, s.avgRange20);
        boolean ethUp = s.move1 > avg * 0.35 && s.move3 > avg * 0.65;
        boolean ethDown = s.move1 < -avg * 0.35 && s.move3 < -avg * 0.65;
        boolean flowUp = s.flow15 > 0.02 && s.flow30 > 0.02;
        boolean flowDown = s.flow15 < -0.02 && s.flow30 < -0.02;

        if (ethUp && flowUp) return "ETH_UP_CONTINUATION";
        if (ethDown && flowDown) return "ETH_DOWN_CONTINUATION";
        if (s.rangePosition >= 0.80 && s.move1 < 0 && s.flow15 < 0) return "HIGH_REJECTION";
        if (s.rangePosition <= 0.20 && s.move1 > 0 && s.flow15 > 0) return "LOW_REJECTION";
        if (s.rangePosition > 0.78) return "HIGH_ZONE";
        if (s.rangePosition < 0.22) return "LOW_ZONE";
        return "MIDDLE_RANGE";
    }

    private static JSONObject riskFlags(MarketSnapshot s, SignalDecision d) throws Exception {
        JSONObject r = new JSONObject();
        String side = d == null ? "" : d.side;
        String family = d == null || d.family == null ? "" : d.family;
        double avg = Math.max(0.35, s.avgRange20);

        boolean proposedLong = "LONG".equals(side);
        boolean proposedShort = "SHORT".equals(side);

        boolean fallingKnife = proposedLong && family.contains("RANGE_FADE")
                && (s.rangePosition < 0.02
                || (s.move1 < -avg * 0.45 && s.move3 < -avg * 0.85 && s.flow30 < -0.04)
                || (s.flow15 < -0.10 && s.flow30 < -0.08)
                || (s.btcMove1 < -0.00045 && s.btcMove3 < -0.00040));

        boolean risingKnife = proposedShort && family.contains("RANGE_FADE")
                && (s.rangePosition > 0.98
                || (s.move1 > avg * 0.45 && s.move3 > avg * 0.85 && s.flow30 > 0.04)
                || (s.flow15 > 0.10 && s.flow30 > 0.08)
                || (s.btcMove1 > 0.00045 && s.btcMove3 > 0.00040));

        boolean flowAgainst = proposedLong ? s.flow30 < -0.02 : proposedShort && s.flow30 > 0.02;
        boolean btcAgainst = proposedLong ? (s.btcMove1 < -0.00045 && s.btcMove3 < -0.00040)
                : proposedShort && (s.btcMove1 > 0.00045 && s.btcMove3 > 0.00040);

        boolean terminalRange = proposedLong ? s.rangePosition > 0.88 : proposedShort && s.rangePosition < 0.12;
        boolean roomWeak = proposedLong ? s.roomLong < 1.80 : proposedShort && s.roomShort < 1.80;
        boolean moveConsumed = proposedLong ? (s.move1 > avg * 1.35 && s.rangePosition > 0.70)
                : proposedShort && (s.move1 < -avg * 1.35 && s.rangePosition < 0.30);

        r.put("fallingKnife", fallingKnife);
        r.put("risingKnife", risingKnife);
        r.put("flowAgainst", flowAgainst);
        r.put("btcAgainst", btcAgainst);
        r.put("terminalRange", terminalRange);
        r.put("roomWeak", roomWeak);
        r.put("moveConsumedOrLate", moveConsumed);
        r.put("hardReject", fallingKnife || risingKnife || btcAgainst || terminalRange || roomWeak || moveConsumed);
        return r;
    }

    private AiResult parse(String raw) {
        try {
            JSONObject root = new JSONObject(raw == null ? "{}" : raw);
            if ("incomplete".equals(root.optString("status", ""))) {
                String reason = "AI_INCOMPLETE";
                JSONObject details = root.optJSONObject("incomplete_details");
                if (details != null) reason = "AI_INCOMPLETE_" + details.optString("reason", "UNKNOWN");
                return new AiResult(false, false, 0, Double.NaN, Double.NaN, reason, raw == null ? "{}" : raw);
            }
        } catch (Exception ignored) {}

        String text = extractText(raw);
        String jsonText = extractJsonObject(text);

        try {
            JSONObject o = new JSONObject(jsonText);
            String decision = o.optString("decision", "REJECT").trim().toUpperCase(Locale.US);
            boolean approved = "APPROVE".equals(decision);
            int confidence = Math.max(0, Math.min(100, o.optInt("confidence", approved ? 70 : 0)));
            double targetMove = safeChoice(o.optDouble("targetMove", Double.NaN), 2.80, 3.50, 5.50);
            double stopDistance = safeChoice(o.optDouble("stopDistance", Double.NaN), 1.35, 1.65, 2.20);
            String reason = o.optString("reason", approved ? "AI_APPROVED" : "AI_REJECTED");

            return new AiResult(approved, false, confidence, targetMove, stopDistance, reason, o.toString());
        } catch (Exception ignored) {
            return AiResult.fallback("AI_JSON_INVALID");
        }
    }

    private String extractText(String raw) {
        try {
            JSONObject root = new JSONObject(raw);

            String outputText = root.optString("output_text", "");
            if (outputText != null && !outputText.trim().isEmpty()) return outputText;

            JSONArray output = root.optJSONArray("output");
            if (output != null) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < output.length(); i++) {
                    JSONObject item = output.optJSONObject(i);
                    if (item == null) continue;
                    JSONArray content = item.optJSONArray("content");
                    if (content == null) continue;
                    for (int j = 0; j < content.length(); j++) {
                        JSONObject c = content.optJSONObject(j);
                        if (c == null) continue;
                        String t = c.optString("text", "");
                        if (t == null || t.trim().isEmpty()) t = c.optString("output_text", "");
                        if (t != null && !t.trim().isEmpty()) b.append(t);
                    }
                }
                if (b.length() > 0) return b.toString();
            }
        } catch (Exception ignored) {}

        return raw == null ? "" : raw;
    }

    private String extractJsonObject(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return "{}";
    }

    private static double safeChoice(double value, double a, double b, double c) {
        if (!Double.isFinite(value)) return b;
        double da = Math.abs(value - a), db = Math.abs(value - b), dc = Math.abs(value - c);
        if (da <= db && da <= dc) return a;
        if (db <= da && db <= dc) return b;
        return c;
    }

    private static double round(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    public interface ResultCallback {
        void onResult(AiResult result);
    }

    public static final class AiResult {
        public final boolean approved;
        public final boolean fallback;
        public final int confidence;
        public final double targetMove;
        public final double stopDistance;
        public final String reason;
        public final String rawJson;

        AiResult(boolean approved, boolean fallback, int confidence, double targetMove, double stopDistance, String reason, String rawJson) {
            this.approved = approved;
            this.fallback = fallback;
            this.confidence = confidence;
            this.targetMove = targetMove;
            this.stopDistance = stopDistance;
            this.reason = reason;
            this.rawJson = rawJson;
        }

        static AiResult fallback(String reason) {
            String raw = "{\"decision\":\"FALLBACK\",\"confidence\":0,\"reason\":\"" + reason + "\"}";
            return new AiResult(false, true, 0, Double.NaN, Double.NaN, reason, raw);
        }
    }
}

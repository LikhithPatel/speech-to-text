package com.example.stt;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public class GeminiBridge implements WebSocket.Listener {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/"
          + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=";

    private final Consumer<String> toBrowser;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<WebSocket> socket;
    private CompletableFuture<Void> sendChain;

    private final StringBuilder textBuf = new StringBuilder();
    private final ByteArrayOutputStream binBuf = new ByteArrayOutputStream();

    public GeminiBridge(String apiKey, String model, Consumer<String> toBrowser) {
        this.toBrowser = toBrowser;

        String setup = """
            {"setup":{
              "model":"models/%s",
              "generationConfig":{"responseModalities":["AUDIO"]},
              "inputAudioTranscription":{},
              "systemInstruction":{"parts":[{"text":"You are a silent transcription listener. The speaker mixes Hindi and English. Never answer or speak."}]}
            }}
            """.formatted(model);

        socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(ENDPOINT + apiKey), this);

        sendChain = socket
                .thenCompose(ws -> ws.sendText(setup, true))
                .handle((r, e) -> {
                    if (e != null) emit("error", "Could not connect to Gemini: " + e.getMessage());
                    return (Void) null;
                });
    }

    /** Messages are sent one at a time, and only after Gemini confirms setup. */
    private synchronized void enqueue(String json) {
        sendChain = sendChain
                .thenCompose(v -> ready)
                .thenCompose(v -> socket.thenCompose(ws -> ws.sendText(json, true)))
                .handle((r, e) -> {
                    if (e != null) emit("error", "Send failed: " + e.getMessage());
                    return (Void) null;
                });
    }

    public void sendAudio(byte[] pcm16k) {
        String b64 = Base64.getEncoder().encodeToString(pcm16k);
        enqueue("{\"realtimeInput\":{\"audio\":{\"data\":\"" + b64
                + "\",\"mimeType\":\"audio/pcm;rate=16000\"}}}");
    }

    public void endAudio() {
        enqueue("{\"realtimeInput\":{\"audioStreamEnd\":true}}");
    }

    public void close() {
        socket.thenAccept(ws -> ws.abort());
    }

    /* ---------- Gemini -> server ---------- */

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        textBuf.append(data);
        if (last) {
            String msg = textBuf.toString();
            textBuf.setLength(0);
            handle(msg);
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
        byte[] b = new byte[data.remaining()];
        data.get(b);
        binBuf.write(b, 0, b.length);
        if (last) {
            String msg = binBuf.toString(StandardCharsets.UTF_8);
            binBuf.reset();
            handle(msg);
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
        ready.completeExceptionally(new RuntimeException("closed"));
        if (code != 1000) emit("error", "Gemini closed the connection: " + reason);
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        ready.completeExceptionally(error);
        emit("error", "Gemini error: " + error.getMessage());
    }

    private void handle(String msg) {
        try {
            JsonNode n = MAPPER.readTree(msg);

            if (n.has("setupComplete")) {
                ready.complete(null);
                toBrowser.accept("{\"type\":\"ready\"}");
                return;
            }

            if (n.has("error")) {
                String m = n.path("error").path("message").textValue();
                emit("error", m != null ? m : "Unknown Gemini error");
                return;
            }

            // Only the user's transcribed speech is forwarded; model audio is ignored.
            String text = n.path("serverContent").path("inputTranscription").path("text").textValue();
            if (text != null && !text.isEmpty()) emit("transcript", text);
        } catch (Exception ignored) {
        }
    }

    private void emit(String type, String value) {
        try {
            String key = type.equals("transcript") ? "text" : "message";
            toBrowser.accept(MAPPER.writeValueAsString(Map.of("type", type, key, value)));
        } catch (Exception ignored) {
        }
    }
}

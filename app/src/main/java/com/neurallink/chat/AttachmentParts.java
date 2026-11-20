package com.neurallink.chat;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class AttachmentParts {
    static JSONArray encode(JSONArray messages, JSONObject capabilities, String providerType) throws Exception {
        if (messages == null || messages.length() == 0) throw new ProviderBridge.Failure("Enter a message.");
        var encoded = new JSONArray();
        long total = 0;
        for (int index = 0; index < messages.length(); index++) {
            var message = messages.getJSONObject(index);
            String role = message.optString("role");
            Object content = message.opt("content");
            var files = message.optJSONArray("files");
            int number = files == null ? 0 : files.length();
            if (!("user".equals(role) || "assistant".equals(role)) || !(content instanceof String text)
                    || number > 4 || (number > 0 && !"user".equals(role))) {
                throw new ProviderBridge.Failure("Invalid conversation or attachment.");
            }
            if (number == 0) {
                encoded.put(new JSONObject().put("role", role).put("content", text));
                continue;
            }
            var parts = new JSONArray().put(textPart(text));
            for (int fileIndex = 0; fileIndex < number; fileIndex++) {
                var file = files.getJSONObject(fileIndex);
                String name = file.getString("name").replaceAll("[\\r\\n\\x00]", " ");
                if (name.length() > 180) name = name.substring(0, 180);
                String data = file.getString("data");
                String kind = file.getString("kind");
                if ("text".equals(kind)) {
                    int bytes = data.getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > 256_000 || data.indexOf(0) != -1
                            || !name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|csv|tsv|json|jsonl|xml|log)$")) {
                        throw new ProviderBridge.Failure("Use a supported UTF-8 text or data file under 256 KB.");
                    }
                    total += bytes;
                    parts.put(textPart("Attached file: " + name + "\n\n" + data));
                } else {
                    if (data.isEmpty() || data.length() > 7_000_000) {
                        throw new ProviderBridge.Failure("Invalid image or PDF attachment.");
                    }
                    byte[] bytes;
                    try {
                        bytes = Base64.decode(data, Base64.NO_WRAP);
                    } catch (IllegalArgumentException error) {
                        throw new ProviderBridge.Failure("Invalid image or PDF attachment.");
                    }
                    if (bytes.length > 5_000_000) throw new ProviderBridge.Failure("Each image or PDF must be under 5 MB.");
                    total += bytes.length;
                    String mime = file.optString("mime");
                    if ("image".equals(kind) && isImage(mime, bytes)) {
                        requireSupport(capabilities, "image", "This model does not accept images.");
                        parts.put(new JSONObject().put("type", "image_url").put("image_url",
                                new JSONObject().put("url", "data:" + mime + ";base64," + data)));
                    } else if ("pdf".equals(kind) && "application/pdf".equals(mime) && textAt(bytes, 0, "%PDF-")) {
                        if ("gemini".equals(providerType)) {
                            throw new ProviderBridge.Failure("This provider does not accept PDF attachments in this app.");
                        }
                        requireSupport(capabilities, "pdf", "This model does not accept PDF attachments.");
                        parts.put(new JSONObject().put("type", "file").put("file", new JSONObject()
                                .put("filename", name).put("file_data", "data:application/pdf;base64," + data)));
                    } else {
                        throw new ProviderBridge.Failure("Use a PNG, JPEG, GIF, WebP, or PDF file.");
                    }
                }
                if (total > 6_000_000) {
                    throw new ProviderBridge.Failure("Attachments exceed 6 MB for this conversation. Start a new chat.");
                }
            }
            encoded.put(new JSONObject().put("role", role).put("content", parts));
        }
        return encoded;
    }

    private static JSONObject textPart(String text) throws Exception {
        return new JSONObject().put("type", "text").put("text", text);
    }

    private static void requireSupport(JSONObject capabilities, String type, String message) throws Exception {
        if (capabilities != null && Boolean.FALSE.equals(capabilities.opt(type))) {
            throw new ProviderBridge.Failure(message);
        }
    }

    private static boolean isImage(String mime, byte[] bytes) {
        return switch (mime) {
            case "image/png" -> bytes.length >= 8 && (bytes[0] & 255) == 137 && textAt(bytes, 1, "PNG\r\n\u001a\n");
            case "image/jpeg" -> bytes.length >= 3 && (bytes[0] & 255) == 255
                    && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
            case "image/gif" -> textAt(bytes, 0, "GIF87a") || textAt(bytes, 0, "GIF89a");
            case "image/webp" -> textAt(bytes, 0, "RIFF") && textAt(bytes, 8, "WEBP");
            default -> false;
        };
    }

    private static boolean textAt(byte[] bytes, int offset, String expected) {
        if (bytes.length < offset + expected.length()) return false;
        for (int i = 0; i < expected.length(); i++) {
            if ((bytes[offset + i] & 255) != expected.charAt(i)) return false;
        }
        return true;
    }
}

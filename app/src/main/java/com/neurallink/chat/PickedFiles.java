package com.neurallink.chat;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class PickedFiles {
    static JSONArray read(ContentResolver resolver, Uri[] uris) throws Exception {
        var files = new JSONArray();
        int total = 0;
        for (Uri uri : uris) {
            String name = displayName(resolver, uri);
            int dot = name.lastIndexOf('.');
            String extension = dot < 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = supportedMime(extension);
            if (mime == null) {
                String reported = resolver.getType(uri);
                extension = extensionForMime(reported == null ? "" : reported.toLowerCase(Locale.ROOT));
                if (extension == null) {
                    throw new ProviderBridge.Failure("Use an image, PDF, or UTF-8 file such as TXT, CSV, or JSON.");
                }
                mime = supportedMime(extension);
                name += "." + extension;
            }
            if (name.length() > 180) name = name.substring(0, 179 - extension.length()) + "." + extension;
            boolean text = mime.isEmpty();
            int limit = text ? 256_000 : 5_000_000;
            byte[] bytes;
            try (var input = resolver.openInputStream(uri); var output = new ByteArrayOutputStream()) {
                if (input == null) throw new ProviderBridge.Failure("Could not open the selected file.");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > limit) throw new ProviderBridge.Failure(text
                            ? "Text and data files must be under 256 KB." : "Images and PDFs must be under 5 MB.");
                    if (total + count > 6_000_000) throw new ProviderBridge.Failure("Attachments must be under 6 MB in total.");
                    total += count;
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            if (bytes.length == 0) throw new ProviderBridge.Failure("The selected file is empty.");
            String data;
            try {
                data = text ? StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                        : Base64.encodeToString(bytes, Base64.NO_WRAP);
            } catch (CharacterCodingException error) {
                throw new ProviderBridge.Failure("Text and data files must use UTF-8 encoding.");
            }
            files.put(new JSONObject().put("name", name).put("mime", mime).put("size", bytes.length).put("data", data));
        }
        return files;
    }

    private static String supportedMime(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "txt", "md", "csv", "tsv", "json", "jsonl", "xml", "log" -> "";
            default -> null;
        };
    }

    private static String extensionForMime(String mime) {
        return switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "application/pdf" -> "pdf";
            case "text/plain" -> "txt";
            case "text/markdown" -> "md";
            case "text/csv", "application/csv" -> "csv";
            case "text/tab-separated-values" -> "tsv";
            case "application/json" -> "json";
            case "application/x-ndjson" -> "jsonl";
            case "application/xml", "text/xml" -> "xml";
            default -> null;
        };
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (var cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int field = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                String name = field < 0 ? null : cursor.getString(field);
                if (name != null) {
                    name = name.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
                    if (!name.isEmpty()) return name;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Some file providers expose the stream and MIME type without a metadata cursor.
        }
        return "attachment";
    }
}

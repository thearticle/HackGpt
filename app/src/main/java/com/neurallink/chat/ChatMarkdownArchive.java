package com.neurallink.chat;

import org.json.JSONArray;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ChatMarkdownArchive {
    private ChatMarkdownArchive() {}

    static boolean validName(String name, String extension) {
        return name != null && name.length() <= 128 && name.endsWith(extension)
                && name.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}Z - [^\\\\/:*?\"<>|\\p{Cntrl}]+");
    }

    static void write(String json, OutputStream output) throws Exception {
        var entries = new JSONArray(json);
        var names = new HashSet<String>();
        try (var zip = new ZipOutputStream(output)) {
            for (int i = 0; i < entries.length(); i++) {
                var entry = entries.getJSONObject(i);
                String name = entry.getString("name");
                if (!validName(name, ".md")) throw new IOException("Invalid Markdown filename");
                String stem = name.substring(0, name.length() - 3);
                for (int copy = 2; !names.add(name.toLowerCase(Locale.ROOT)); copy++) {
                    name = stem + " (" + copy + ").md";
                }
                zip.putNextEntry(new ZipEntry(name));
                zip.write(entry.getString("markdown").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}

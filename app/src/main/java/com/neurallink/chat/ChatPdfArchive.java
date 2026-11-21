package com.neurallink.chat;

import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.json.JSONArray;

import java.io.OutputStream;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ChatPdfArchive {
    private static final int WIDTH = 595;
    private static final int HEIGHT = 842;
    private static final int MARGIN = 44;

    private ChatPdfArchive() {}

    static void write(String json, OutputStream output) throws Exception {
        var entries = new JSONArray(json);
        var names = new HashSet<String>();
        try (var zip = new ZipOutputStream(output)) {
            for (int i = 0; i < entries.length(); i++) {
                var entry = entries.getJSONObject(i);
                String title = entry.getString("title");
                String date = entry.getString("date");
                if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}Z")) date = "undated";
                String stem = date + " - " + safeTitle(title);
                String name = stem + ".pdf";
                for (int copy = 2; !names.add(name); copy++) name = stem + " (" + copy + ").pdf";
                zip.putNextEntry(new ZipEntry(name));
                writePdf(title + "\n" + date + "\n\n" + entry.getString("transcript"), zip);
                zip.closeEntry();
            }
        }
    }

    private static String safeTitle(String title) {
        String name = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.length() > 80) name = name.substring(0, 80).trim();
        return name.isEmpty() ? "Chat" : name;
    }

    private static void writePdf(String text, OutputStream output) throws Exception {
        var paint = new TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(11);
        var layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, WIDTH - 2 * MARGIN)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(3, 1).build();
        var pdf = new PdfDocument();
        try {
            int line = 0;
            int pageNumber = 1;
            while (line < layout.getLineCount()) {
                int top = layout.getLineTop(line);
                int end = line + 1;
                while (end < layout.getLineCount()
                        && layout.getLineTop(end + 1) - top <= HEIGHT - 2 * MARGIN) end++;
                var page = pdf.startPage(new PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, pageNumber++).create());
                var canvas = page.getCanvas();
                canvas.save();
                canvas.clipRect(MARGIN, MARGIN, WIDTH - MARGIN, MARGIN + layout.getLineTop(end) - top);
                canvas.translate(MARGIN, MARGIN - top);
                layout.draw(canvas);
                canvas.restore();
                pdf.finishPage(page);
                line = end;
            }
            pdf.writeTo(output);
        } finally {
            pdf.close();
        }
    }
}

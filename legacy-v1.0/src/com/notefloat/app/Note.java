package com.notefloat.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Note {

    public long id;
    public String title = "";
    public String body = "";
    public boolean pinned;
    public long created;
    public long updated;

    public Note() {
    }

    public static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() > 0) {
                if (line.length() > 70) {
                    return line.substring(0, 70) + "...";
                }
                return line;
            }
        }
        return "";
    }

    public String displayTitle() {
        if (title != null && title.trim().length() > 0) {
            return title.trim();
        }
        String first = firstLine(body);
        return first.length() > 0 ? first : "Untitled note";
    }

    public String preview() {
        if (body == null) {
            return "";
        }
        String flat = body.replace("\n", " ").replaceAll("\\s+", " ").trim();
        String lead = firstLine(body);
        if (lead.length() > 0 && flat.startsWith(lead)) {
            flat = flat.substring(lead.length()).trim();
        }
        if (flat.length() > 120) {
            return flat.substring(0, 120) + "...";
        }
        return flat;
    }

    public String stamp() {
        SimpleDateFormat f = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());
        return f.format(new Date(updated));
    }
}

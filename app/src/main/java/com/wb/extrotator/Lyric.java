package com.wb.extrotator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「副屏播放器」要用到的歌词：把 {@code .lrc} 那种带时间轴的文本解析成一行一行，
 * 以及去 LRCLIB 免费取一份。
 *
 * <p><b>为什么走在线</b>：歌词文件不在我们手里。音乐 App 的歌词摆在它自己的私有目录，
 * Android 从 11 起分区存储，别的应用连它的目录都进不去（申请「所有文件」权限也不行）。
 * 能从播放器那边拿到的只有歌名、歌手、专辑、封面 —— 歌词得另想办法。
 * 挑 LRCLIB 的理由很实际：公开、免费、不要 key、返回里直接带 {@code syncedLyrics}
 * （就是带时间轴的那份），不像别家还要再拼一次请求。
 *
 * <p>⚠ 取不到就是取不到：没网、歌太冷门、歌名带后缀（"某某 (Live)"）都可能落空。
 * 这时 {@link #fetchBlocking} 返回 {@link #EMPTY}，界面照常显示封面和歌名，
 * <b>不弹错、不转圈</b> —— 歌词本来就是个加分项。
 */
public final class Lyric {

    /** 一行歌词 */
    public static final class Line {
        /** 这一行从第几毫秒开始 */
        public final long at;
        public final String text;

        Line(long at, String text) {
            this.at = at;
            this.text = text;
        }
    }

    /** 按时间升序的歌词行 */
    public final List<Line> lines;

    private Lyric(List<Line> lines) {
        this.lines = lines;
    }

    /** 没歌词时的占位 */
    public static final Lyric EMPTY = new Lyric(new ArrayList<Line>());

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /**
     * 播放到第 {@code ms} 毫秒时该显示第几行；还没到第一行就返回 -1。
     *
     * <p>线性扫一遍就够了：一份 LRC 撑死几百行，而调用方是 0.5 秒一次的轮询。
     */
    public int indexAt(long ms) {
        int hit = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).at <= ms) {
                hit = i;
            } else {
                break;
            }
        }
        return hit;
    }

    // ============================================================ 解析

    /** {@code [mm:ss.xx]} 或 {@code [mm:ss.xxx]}，一行里可以有多个（副歌重复） */
    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    /**
     * 解析 LRC 文本。认不出的行直接跳过（LRC 里常混着 {@code [ar:]} {@code [ti:]} 这类元信息）。
     */
    public static Lyric parse(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null) {
            return EMPTY;
        }
        for (String raw : lrc.split("\n")) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            Matcher m = TAG.matcher(s);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find()) {
                long mm = Long.parseLong(m.group(1));
                long ss = Long.parseLong(m.group(2));
                long frac = 0;
                if (m.group(3) != null) {
                    String f = m.group(3);
                    // 两位是百分秒、三位是毫秒 —— 统一到毫秒
                    frac = f.length() >= 3 ? Long.parseLong(f.substring(0, 3))
                            : Long.parseLong(f) * 10;
                }
                times.add(mm * 60000L + ss * 1000L + frac);
                end = m.end();
            }
            if (times.isEmpty()) {
                continue;
            }
            String text = s.substring(end).trim();
            if (text.isEmpty()) {
                continue;
            }
            for (long t : times) {
                out.add(new Line(t, text));
            }
        }
        Collections.sort(out, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Long.compare(a.at, b.at);
            }
        });
        return out.isEmpty() ? EMPTY : new Lyric(out);
    }

    // ============================================================ 取词

    /**
     * 去 LRCLIB 按歌名 + 歌手取一份歌词。<b>阻塞</b>，必须在后台线程调。
     *
     * @return 取到就是带时间轴的那份；取不到返回 {@link #EMPTY}（调用方不必判 null）
     */
    public static Lyric fetchBlocking(String title, String artist) {
        if (title == null || title.trim().isEmpty()) {
            return EMPTY;
        }
        String t = title.trim();
        String a = artist == null ? "" : artist.trim();
        // 先按"精确对"问一次（LRCLIB 的 get 就是干这个的）
        String lrc = get("https://lrclib.net/api/get?track_name=" + enc(t)
                + (a.isEmpty() ? "" : "&artist_name=" + enc(a)));
        Lyric parsed = parse(lrc);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        // 对不上就退回搜索，取第一条带时间轴的
        String json = get("https://lrclib.net/api/search?track_name=" + enc(t)
                + (a.isEmpty() ? "" : "&artist_name=" + enc(a)));
        return parse(firstSynced(json));
    }

    /** 从 {@code /api/get} 的返回里抠出 syncedLyrics */
    private static String firstSynced(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            if (json.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    String s = o.optString("syncedLyrics", "");
                    if (!s.isEmpty()) {
                        return s;
                    }
                }
                return null;
            }
            return new JSONObject(json).optString("syncedLyrics", null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 一次 GET，返回正文；非 200 或出错都返回 null（歌词取不到不是错误） */
    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "AdaptiveScreen/1.0 (Android)");
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}

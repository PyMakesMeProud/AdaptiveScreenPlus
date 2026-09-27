package com.wb.extrotator;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;

/**
 * 「副屏播放器」（伪应用）—— 外屏上的一个播放器：当前歌曲的封面、歌词、进度、上一曲/下一曲。
 *
 * <p><b>它自己不播放任何东西</b>，只是接管"系统里正在放的那个"。原理是读系统的
 * {@link MediaSessionManager}：任何走 MediaSession 的播放器（网易云、QQ 音乐、B 站、
 * 播客…）都会把自己的会话挂上去，我们从通知使用权那条路就能拿到它的
 * {@link MediaController} —— 元数据（歌名/歌手/封面/时长）和播放状态都在里面，
 * 控制也走它（{@code getTransportControls()}）。
 *
 * <p>⚠ <b>要用通知使用权</b>：{@code getActiveSessions} 要的就是它。这个权限本应用早就
 * 在用了（外屏侧滑栏的通知来源 {@link CoverNotifListener}），所以不用再多要什么；
 * 没授权时这一页会说清"读不到播放器"，而不是空着让人猜。
 *
 * <p>⚠ 歌词是<b>在线取</b>的（见 {@link Lyric} 里那段为什么）。取不到就只是不显示歌词，
 * 封面和歌名照旧 —— 不要为了一个加分项去转圈或弹错。
 */
public class PlayerActivity extends Activity {

    private ImageView cover;
    private TextView titleView, artistView, lyricPrev, lyricNow, lyricNext, timeView, hintView;
    private SeekBar seek;
    private ImageButton btnPlay;

    private MediaController ctrl;
    /** 通知使用权没给（跟"没有音乐在放"不是一回事，提示语不一样） */
    private boolean denied;
    /** 用户正拖着进度条 —— 这期间别拿播放位置去覆盖他的手指 */
    private boolean seeking;
    /** 这一轮已经为哪首歌取过歌词了（"歌名|歌手"）；变了才重新去取 */
    private String lyricKey;
    private Lyric lyric = Lyric.EMPTY;
    /** 歌词请求序号：切歌比网络快时，靠它丢弃过期结果 */
    private int lyricSeq;
    /** 距上次重连过了几个 tick（没有会话时每 3 秒找一次） */
    private int missTicks;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            sync();
            ui.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_ext_player);
        // 播放页常亮（跟「状态展示柜」一个道理）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        cover = findViewById(R.id.playerCover);
        titleView = findViewById(R.id.playerTitle);
        artistView = findViewById(R.id.playerArtist);
        lyricPrev = findViewById(R.id.playerLyricPrev);
        lyricNow = findViewById(R.id.playerLyricNow);
        lyricNext = findViewById(R.id.playerLyricNext);
        timeView = findViewById(R.id.playerTime);
        hintView = findViewById(R.id.playerHint);
        seek = findViewById(R.id.playerSeek);
        btnPlay = findViewById(R.id.playerPlay);

        findViewById(R.id.playerPrev).setOnClickListener(v -> {
            if (ctrl != null) {
                ctrl.getTransportControls().skipToPrevious();
            }
        });
        findViewById(R.id.playerNext).setOnClickListener(v -> {
            if (ctrl != null) {
                ctrl.getTransportControls().skipToNext();
            }
        });
        btnPlay.setOnClickListener(v -> {
            if (ctrl == null) {
                return;
            }
            PlaybackState ps = ctrl.getPlaybackState();
            boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
            if (playing) {
                ctrl.getTransportControls().pause();
            } else {
                ctrl.getTransportControls().play();
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                seeking = false;
                if (ctrl != null) {
                    ctrl.getTransportControls().seekTo(sb.getProgress());
                }
            }
        });

        // 返回箭头就藏在标题栏上（没有额外按钮），碰一下退出
        findViewById(R.id.playerClose).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        missTicks = 0;
        connect();
        sync();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    // ============================================================ 拿会话

    /** 找系统里正在放的那个会话 */
    private void connect() {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) {
                return;
            }
            List<MediaController> list = msm.getActiveSessions(
                    new ComponentName(this, CoverNotifListener.class));
            MediaController best = null;
            if (list != null) {
                for (MediaController mc : list) {
                    // 优先挑"有元数据"的那个：系统里常驻着一些没有曲目信息的空会话
                    if (mc.getMetadata() != null) {
                        best = mc;
                        break;
                    }
                }
                if (best == null && !list.isEmpty()) {
                    best = list.get(0);
                }
            }
            ctrl = best;
            denied = false;
        } catch (SecurityException se) {
            // 通知使用权没给
            ctrl = null;
            denied = true;
        } catch (Throwable t) {
            ctrl = null;
        }
    }

    // ============================================================ 刷新界面

    private void sync() {
        if (ctrl == null) {
            // 没有会话时每 3 秒找一次（用户刚点开音乐，不用退出去重进）
            if (++missTicks % 6 == 0) {
                connect();
            }
            showIdle();
            return;
        }
        try {
            MediaMetadata md = ctrl.getMetadata();
            if (md == null) {
                showIdle();
                return;
            }
            hintView.setVisibility(TextView.GONE);
            String title = text(md, MediaMetadata.METADATA_KEY_TITLE);
            String artist = text(md, MediaMetadata.METADATA_KEY_ARTIST);
            if (artist.isEmpty()) {
                artist = text(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            }
            titleView.setText(title.isEmpty() ? "未知曲目" : title);
            artistView.setText(artist);

            long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            PlaybackState ps = ctrl.getPlaybackState();
            long pos = ps == null ? 0L : Math.max(0L, ps.getPosition());

            // 封面只在换歌时读一次（getBitmap 不便宜，没必要 0.5 秒来一遍）
            String key = title + "|" + artist;
            if (!key.equals(lyricKey)) {
                lyricKey = key;
                Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) {
                    art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                }
                if (art == null) {
                    cover.setImageResource(R.drawable.ic_pseudo_player);
                } else {
                    cover.setImageBitmap(art);
                }
                requestLyric(title, artist);
            }

            if (dur > 0 && seek.getMax() != (int) dur) {
                seek.setMax((int) dur);
            }
            if (!seeking) {
                seek.setProgress((int) Math.min(pos, seek.getMax()));
            }
            timeView.setText(fmt(pos) + " / " + fmt(dur));

            boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
            btnPlay.setImageResource(playing
                    ? R.drawable.ic_player_pause : R.drawable.ic_player_play);

            showLyric(pos);
        } catch (Throwable t) {
            showIdle();
        }
    }

    /** 没会话（或读不到）时把这一页画成"待命" */
    private void showIdle() {
        titleView.setText(R.string.player_idle_title);
        artistView.setText("");
        cover.setImageResource(R.drawable.ic_pseudo_player);
        lyricKey = null;
        lyric = Lyric.EMPTY;
        lyricPrev.setText("");
        lyricNext.setText("");
        lyricNow.setText("");
        // 待命的原因写在歌词位下面那行：没授权 / 没有音乐在放，是两回事
        hintView.setVisibility(TextView.VISIBLE);
        hintView.setText(denied ? R.string.player_need_access : R.string.player_idle_hint);
        seek.setProgress(0);
        timeView.setText("--:-- / --:--");
        btnPlay.setImageResource(R.drawable.ic_player_play);
    }

    // ============================================================ 歌词

    private void requestLyric(final String title, final String artist) {
        lyric = Lyric.EMPTY;
        final int seq = ++lyricSeq;
        if (title.isEmpty()) {
            return;
        }
        lyricNow.setText(R.string.player_lyric_loading);
        new Thread(() -> {
            final Lyric got = Lyric.fetchBlocking(title, artist);
            ui.post(() -> {
                // 用户可能已经切歌了 —— 过期结果直接丢
                if (seq != lyricSeq) {
                    return;
                }
                lyric = got;
                if (got.isEmpty()) {
                    lyricNow.setText(R.string.player_lyric_none);
                }
            });
        }, "extrot-lyric").start();
    }

    private void showLyric(long pos) {
        if (lyric.isEmpty()) {
            return;
        }
        int i = lyric.indexAt(pos);
        if (i < 0) {
            lyricPrev.setText("");
            lyricNow.setText("");
            lyricNext.setText(lyric.lines.get(0).text);
            return;
        }
        lyricPrev.setText(i > 0 ? lyric.lines.get(i - 1).text : "");
        lyricNow.setText(lyric.lines.get(i).text);
        lyricNext.setText(i + 1 < lyric.lines.size() ? lyric.lines.get(i + 1).text : "");
    }

    // ============================================================ 小工具

    private static String text(MediaMetadata md, String key) {
        try {
            String s = md.getString(key);
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String fmt(long ms) {
        if (ms <= 0) {
            return "0:00";
        }
        long s = ms / 1000L;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}

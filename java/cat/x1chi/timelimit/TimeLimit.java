package cat.x1chi.timelimit;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import cat.narezany.margyt.plugin.MargyPlugin;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Плагин для контролирования времени в тик токе
 *
 * Time = время проведенное в тик токе за день
 * Когда лимит истрачен ты не сможешь зайти в тик ток никогда (шучу)
 * Все настраивается в настройках если вдруг форк сделать захотели
 * Сам я плагин писал половину сам половину навайбкодил но ниче страшного, писал чисто для себя не думаю что кто то будет лезть сюда
 * P.S - коментарии для кода я специально заставил написать ии что бы самому не заморачиваться и полезно когда буду делать обновления
 */
public final class TimeLimit extends MargyPlugin {

    /** The one TikTok screen name plugins.md calls stable: feed, inbox and profile live inside it. */
    private static final String MAIN_ACTIVITY = "com.ss.android.ugc.aweme.main.MainActivity";

    private static final String K_LIMIT = "limit_min";
    private static final String K_UNLOCK = "unlock_min";
    private static final String K_PER_DAY = "unlocks_per_day";
    private static final String K_REMIND = "remind_min";
    private static final String K_WAIT = "unlock_wait_s";
    private static final String K_ONLY_MAIN = "only_main";
    private static final String K_BG = "bg_colour";
    private static final String K_ACCENT = "accent_colour";
    private static final String K_DAY = "day";
    private static final String K_USED = "used_ms";
    private static final String K_UNLOCKS_USED = "unlocks_used";
    private static final String K_UNTIL = "unlock_until";
    private static final String K_WARNED = "warned_key";
    private static final String K_HIST = "history";

    private static final int DEF_LIMIT = 60;    // minutes per day
    private static final int DEF_UNLOCK = 10;   // minutes per emergency unlock
    private static final int DEF_PER_DAY = 2;   // emergency unlocks per day
    private static final int DEF_REMIND = 5;    // minutes before the block (0 = off)
    private static final int DEF_WAIT = 5;      // seconds to wait before an emergency unlock
    private static final String DEF_BG = "#121212";
    private static final String DEF_ACCENT = "#FE2C55";

    /** name_ru, name_en, background, accent. */
    private static final String[][] THEMES = {
            { "Тёмная", "Dark", "#121212", "#FE2C55" },
            { "Светлая", "Light", "#FFFFFF", "#FE2C55" },
            { "AMOLED", "AMOLED", "#000000", "#FFFFFF" },
            { "Океан", "Ocean", "#0B1D3A", "#25F4EE" },
            { "Лес", "Forest", "#10251A", "#4ADE80" },
            { "Закат", "Sunset", "#2B1020", "#FF7A59" },
    };

    private interface ColourSet { void set(int colour); }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { onTick(); }
    };
    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int change) { }
            };

    private int resumed = 0;       // TikTok activities in the foreground (for blocking)
    private int counting = 0;      // of those, how many count towards the limit
    private long since = 0;        // elapsedRealtime when counting last flushed, 0 = not counting
    private boolean stopped = false;
    private Dialog blockDialog;
    private Activity blockHost;
    private Runnable countdown;

    // ---------------------------------------------------------------- hooks

    @Override
    public void onStart(Context context) {
        try {
            margyt().addSettingsRow(
                    tr("Лимит времени", "Time limit"),
                    tr("Лимит, тема, история за 7 дней", "Limit, theme, 7-day history"),
                    () -> openSettings(margyt().screen()));
            margyt().log("timelimit: started, limit " + prefs().getInt(K_LIMIT, DEF_LIMIT) + " min");
        } catch (Throwable t) {
            margyt().log("timelimit: onStart failed: " + t);
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        try {
            stopped = false;
            resumed++;
            if (counts(activity)) {
                counting++;
                if (counting == 1) since = SystemClock.elapsedRealtime();
            }
            enforce(activity);
            maybeWarn();
            schedule();
        } catch (Throwable t) {
            margyt().log("timelimit: resume failed: " + t);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        try {
            if (resumed > 0) resumed--;
            if (counts(activity) && counting > 0) {
                counting--;
                if (counting == 0) {
                    flush();
                    since = 0;
                }
            }
            if (resumed == 0) {
                flush();
                since = 0;
                counting = 0;
                handler.removeCallbacks(tick);
            }
            dismissBlock(); // re-shown by the next onActivityResumed if still blocked
        } catch (Throwable t) {
            margyt().log("timelimit: pause failed: " + t);
        }
    }

    @Override
    public void onStop() {
        try {
            stopped = true;
            handler.removeCallbacks(tick);
            flush();
            since = 0;
            resumed = 0;
            counting = 0;
            dismissBlock();
        } catch (Throwable t) {
            margyt().log("timelimit: stop failed: " + t);
        }
    }

    /** API 3: tapping the plugin in the list opens our own settings window. */
    @Override
    public boolean onOpenSettings(Activity activity) {
        try {
            openSettings(activity);
            return true;
        } catch (Throwable t) {
            margyt().log("timelimit: settings failed: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------ counting

    private SharedPreferences prefs() {
        return margyt().prefs();
    }

    /** Whether time on this screen counts towards the limit. */
    private boolean counts(Activity a) {
        if (!prefs().getBoolean(K_ONLY_MAIN, true)) return true;
        return MAIN_ACTIVITY.equals(a.getClass().getName());
    }

    /** On a new day: archives yesterday into the history and resets the counters. */
    private void rollover() {
        SharedPreferences p = prefs();
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        String old = p.getString(K_DAY, "");
        if (!today.equals(old)) {
            long oldUsed = p.getLong(K_USED, 0L);
            if (!old.isEmpty() && oldUsed > 0) {
                TreeMap<String, Long> m = readHist();
                m.put(old, oldUsed);
                writeHist(m);
            }
            p.edit().putString(K_DAY, today)
                    .putLong(K_USED, 0L)
                    .putInt(K_UNLOCKS_USED, 0)
                    .putLong(K_UNTIL, 0L)
                    .apply();
        }
    }

    private TreeMap<String, Long> readHist() {
        TreeMap<String, Long> m = new TreeMap<String, Long>();
        String s = prefs().getString(K_HIST, "");
        for (String part : s.split(",")) {
            int i = part.indexOf('=');
            if (i > 0) {
                try {
                    m.put(part.substring(0, i), Long.parseLong(part.substring(i + 1)));
                } catch (NumberFormatException ignored) { }
            }
        }
        return m;
    }

    private void writeHist(TreeMap<String, Long> m) {
        while (m.size() > 14) m.pollFirstEntry();
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Long> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        prefs().edit().putString(K_HIST, sb.toString()).apply();
    }

    /** Adds the counted foreground time since the last flush to today's total. */
    private void flush() {
        if (since == 0) return;
        long now = SystemClock.elapsedRealtime();
        long add = now - since;
        since = now;
        rollover();
        if (add > 0) {
            SharedPreferences p = prefs();
            p.edit().putLong(K_USED, p.getLong(K_USED, 0L) + add).apply();
        }
    }

    private long usedMs() {
        flush();
        rollover();
        return prefs().getLong(K_USED, 0L);
    }

    private long limitMs() {
        return prefs().getInt(K_LIMIT, DEF_LIMIT) * 60000L;
    }

    private boolean blocked() {
        rollover();
        SharedPreferences p = prefs();
        if (System.currentTimeMillis() < p.getLong(K_UNTIL, 0L)) return false; // emergency unlock active
        return usedMs() >= limitMs();
    }

    private int unlocksLeft() {
        rollover();
        SharedPreferences p = prefs();
        return Math.max(0, p.getInt(K_PER_DAY, DEF_PER_DAY) - p.getInt(K_UNLOCKS_USED, 0));
    }

    /** Spends one emergency unlock. False if none are left. */
    private boolean emergencyUnlock() {
        if (unlocksLeft() <= 0) return false;
        SharedPreferences p = prefs();
        long until = System.currentTimeMillis() + p.getInt(K_UNLOCK, DEF_UNLOCK) * 60000L;
        p.edit().putInt(K_UNLOCKS_USED, p.getInt(K_UNLOCKS_USED, 0) + 1)
                .putLong(K_UNTIL, until)
                .apply();
        margyt().log("timelimit: emergency unlock, " + unlocksLeft() + " left today");
        return true;
    }

    /** One toast per day (per limit/reminder setting) when the reminder time is reached. */
    private void maybeWarn() {
        SharedPreferences p = prefs();
        int rem = p.getInt(K_REMIND, DEF_REMIND);
        if (rem <= 0) return;
        if (System.currentTimeMillis() < p.getLong(K_UNTIL, 0L)) return;
        long left = limitMs() - usedMs();
        if (left <= 0 || left > rem * 60000L) return;
        String key = p.getString(K_DAY, "") + "|" + p.getInt(K_LIMIT, DEF_LIMIT) + "|" + rem;
        if (key.equals(p.getString(K_WARNED, ""))) return;
        p.edit().putString(K_WARNED, key).apply();
        long mins = Math.max(1L, Math.round(left / 60000.0));
        Toast.makeText(margyt().context(),
                tr("До блокировки TikTok осталось ~", "TikTok will be blocked in ~")
                        + mins + tr(" мин.", " min."), Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------- scheduling

    private void schedule() {
        handler.removeCallbacks(tick);
        if (resumed > 0 && !stopped) handler.postDelayed(tick, nextDelay());
    }

    /** Wake up exactly at the reminder, the limit and the unlock end; at worst every 5 s. */
    private long nextDelay() {
        SharedPreferences p = prefs();
        long d = 5000L;
        long left = limitMs() - p.getLong(K_USED, 0L);
        if (left > 0) d = Math.min(d, left);
        int rem = p.getInt(K_REMIND, DEF_REMIND);
        if (rem > 0) {
            long toWarn = left - rem * 60000L;
            if (toWarn > 0) d = Math.min(d, toWarn);
        }
        long toEnd = p.getLong(K_UNTIL, 0L) - System.currentTimeMillis();
        if (toEnd > 0) d = Math.min(d, toEnd);
        return Math.max(250L, d) + 20L;
    }

    private void onTick() {
        try {
            if (stopped || resumed <= 0) return;
            flush();
            Activity a = margyt().screen();
            if (a != null) enforce(a);
            maybeWarn();
            schedule();
        } catch (Throwable t) {
            margyt().log("timelimit: tick failed: " + t);
        }
    }

    // ------------------------------------------------------------ blocking

    private void enforce(Activity a) {
        if (a == null) return;
        if (blocked()) showBlock(a);
        else dismissBlock();
    }

    /** Full-screen block screen drawn in the current activity's window stack. */
    private void showBlock(final Activity a) {
        if (a.isFinishing()) return;
        if (blockDialog != null && blockHost == a && blockDialog.isShowing()) return;
        dismissBlock();

        SharedPreferences p = prefs();
        final int left = unlocksLeft();
        int unlockMin = p.getInt(K_UNLOCK, DEF_UNLOCK);
        int perDay = p.getInt(K_PER_DAY, DEF_PER_DAY);
        final int wait = left > 0 ? p.getInt(K_WAIT, DEF_WAIT) : 0;

        int bg = colour(p.getString(K_BG, DEF_BG), DEF_BG);
        int accent = colour(p.getString(K_ACCENT, DEF_ACCENT), DEF_ACCENT);
        int fg = readable(bg);

        String msg = tr("Дневной лимит TikTok (", "TikTok daily limit (")
                + fmt(limitMs()) + tr(") исчерпан.", ") reached.");
        msg += "\n\n" + tr("Экстренных разблокировок сегодня осталось: ",
                "Emergency unlocks left today: ") + left + " / " + perDay;
        if (left > 0) {
            msg += "\n" + tr("Каждая даёт ", "Each gives ") + unlockMin + tr(" мин.", " min.");
            if (wait > 0) {
                msg += "\n" + tr("Кнопка станет активной через ", "The button unlocks in ")
                        + wait + tr(" сек.", " s.");
            }
        }

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(bg);
        int pad = dp(a, 32);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text(a, tr("TikTok заблокирован", "TikTok is blocked"), 28, fg, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView body = text(a, msg, 16, fg, false);
        body.setAlpha(0.8f);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(a, 16), 0, dp(a, 24));
        root.addView(body);

        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        final String yesLabel = tr("Экстренная разблокировка", "Emergency unlock");
        final Button yes = button(a, yesLabel, accent, readable(accent), 0);
        if (left <= 0) {
            yes.setEnabled(false);
            yes.setAlpha(0.4f);
        } else if (wait > 0) {
            yes.setEnabled(false);
            yes.setAlpha(0.4f);
            yes.setText(yesLabel + " (" + wait + ")");
        }
        yes.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!v.isEnabled()) return;
                if (emergencyUnlock()) {
                    dismissBlock();
                    Toast.makeText(a, tr("Разблокировано на ", "Unlocked for ")
                            + prefs().getInt(K_UNLOCK, DEF_UNLOCK) + tr(" мин.", " min."),
                            Toast.LENGTH_LONG).show();
                    schedule();
                }
            }
        });
        root.addView(yes);

        Button no = button(a, tr("Закрыть TikTok", "Close TikTok"), Color.TRANSPARENT, fg, fg);
        no.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                a.finishAffinity();
            }
        });
        root.addView(no);

        d.setContentView(root);
        d.setCancelable(false);
        d.setCanceledOnTouchOutside(false);
        d.show();

        // Hide status and navigation bars so the screen really is full-screen.
        try {
            d.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignored) { }

        blockDialog = d;
        blockHost = a;
        grabAudio(true);

        // The emergency button wakes up after `wait` seconds.
        if (left > 0 && wait > 0) {
            final int[] secs = { wait };
            countdown = new Runnable() {
                @Override public void run() {
                    try {
                        if (blockDialog != d || !d.isShowing()) return;
                        secs[0]--;
                        if (secs[0] > 0) {
                            yes.setText(yesLabel + " (" + secs[0] + ")");
                            handler.postDelayed(this, 1000L);
                        } else {
                            yes.setText(yesLabel);
                            yes.setEnabled(true);
                            yes.setAlpha(1f);
                        }
                    } catch (Throwable t) {
                        margyt().log("timelimit: countdown failed: " + t);
                    }
                }
            };
            handler.postDelayed(countdown, 1000L);
        }
    }

    private void dismissBlock() {
        if (countdown != null) {
            handler.removeCallbacks(countdown);
            countdown = null;
        }
        if (blockDialog != null) {
            try { blockDialog.dismiss(); } catch (Throwable ignored) { }
            blockDialog = null;
            blockHost = null;
            grabAudio(false);
        }
    }

    /** Best effort: taking audio focus makes TikTok pause the video behind the screen. */
    private void grabAudio(boolean take) {
        try {
            AudioManager am = (AudioManager) margyt().context().getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (take) am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            else am.abandonAudioFocus(focusListener);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ settings

    /** Settings window with three swipeable tabs, drawn in the saved colours. */
    private void openSettings(final Activity a) {
        if (a == null || a.isFinishing()) return;
        final SharedPreferences p = prefs();

        final int[] bgc = { colour(p.getString(K_BG, DEF_BG), DEF_BG) };
        final int[] acc = { colour(p.getString(K_ACCENT, DEF_ACCENT), DEF_ACCENT) };
        final int wbg = bgc[0];
        final int wfg = readable(wbg);
        final int wacc = acc[0];
        final ArrayList<Runnable> refreshers = new ArrayList<Runnable>();
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                for (Runnable r : refreshers) r.run();
            }
        };

        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int pageW = (int) (dm.widthPixels * 0.92f) - dp(a, 32);
        int pagerH = (int) (dm.heightPixels * 0.56f);

        // ---------------- page 1: limit
        LinearLayout p1 = pageBox(a);

        TextView status = text(a, tr("Сегодня в TikTok: ", "Today in TikTok: ") + fmt(usedMs())
                + "\n" + tr("Экстренных разблокировок осталось: ", "Emergency unlocks left: ")
                + unlocksLeft(), 14, wfg, false);
        status.setAlpha(0.8f);
        p1.addView(status);

        final EditText limit = field(a, p1,
                tr("Лимит в день (минут)", "Daily limit (minutes)"),
                String.valueOf(p.getInt(K_LIMIT, DEF_LIMIT)), wfg, wacc);
        final EditText remind = field(a, p1,
                tr("Напомнить за (минут до блокировки, 0 = выкл.)", "Remind before the block (minutes, 0 = off)"),
                String.valueOf(p.getInt(K_REMIND, DEF_REMIND)), wfg, wacc);
        final EditText unlock = field(a, p1,
                tr("Длительность экстренной разблокировки (минут)", "Emergency unlock length (minutes)"),
                String.valueOf(p.getInt(K_UNLOCK, DEF_UNLOCK)), wfg, wacc);
        final EditText perDay = field(a, p1,
                tr("Экстренных разблокировок в день", "Emergency unlocks per day"),
                String.valueOf(p.getInt(K_PER_DAY, DEF_PER_DAY)), wfg, wacc);
        final EditText wait = field(a, p1,
                tr("Ожидание перед экстренной разблокировкой (секунд)", "Wait before an emergency unlock (seconds)"),
                String.valueOf(p.getInt(K_WAIT, DEF_WAIT)), wfg, wacc);

        final CheckBox onlyMain = new CheckBox(a);
        onlyMain.setText(tr("Считать только главный экран TikTok", "Count only TikTok's main screen"));
        onlyMain.setTextColor(wfg);
        onlyMain.setChecked(p.getBoolean(K_ONLY_MAIN, true));
        onlyMain.setButtonTintList(ColorStateList.valueOf(wacc));
        onlyMain.setPadding(0, dp(a, 8), 0, 0);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ol.topMargin = dp(a, 14);
        p1.addView(onlyMain, ol);

        TextView onlyHint = text(a, tr(
                "Главный экран = лента, входящие и профиль. Видео из поиска, лайвы и прочие экраны не считаются.",
                "Main screen = feed, inbox and profile. Videos opened from search, lives and other screens are not counted."),
                12, wfg, false);
        onlyHint.setAlpha(0.6f);
        p1.addView(onlyHint);

        // ---------------- page 2: theme
        LinearLayout p2 = pageBox(a);
        p2.addView(text(a, tr("Готовые темы", "Presets"), 14, wfg, false));

        for (int i = 0; i < THEMES.length; i += 3) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 3 && i + j < THEMES.length; j++) {
                final String[] th = THEMES[i + j];
                int tbg = colour(th[2], DEF_BG);
                int tac = colour(th[3], DEF_ACCENT);
                Button chip = button(a, tr(th[0], th[1]), tbg, readable(tbg), tac);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, dp(a, 44), 1f);
                cl.topMargin = dp(a, 8);
                cl.leftMargin = dp(a, 3);
                cl.rightMargin = dp(a, 3);
                chip.setLayoutParams(cl);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        bgc[0] = colour(th[2], DEF_BG);
                        acc[0] = colour(th[3], DEF_ACCENT);
                        refresh.run();
                    }
                });
                row.addView(chip);
            }
            p2.addView(row);
        }

        Button modBtn = button(a, tr("Взять акцент мода", "Use the mod's accent"),
                Color.TRANSPARENT, wfg, wfg);
        modBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Integer c = modAccent();
                if (c == null) {
                    Toast.makeText(a, tr("Не удалось получить акцент мода", "Could not read the mod's accent"),
                            Toast.LENGTH_SHORT).show();
                } else {
                    acc[0] = c | 0xFF000000;
                    refresh.run();
                }
            }
        });
        p2.addView(modBtn);

        // live preview of the block screen
        final GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(a, 16));
        final GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(a, 20));
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(a, 16), dp(a, 18), dp(a, 16), dp(a, 18));
        card.setBackground(cardBg);
        final TextView cardTitle = text(a, tr("TikTok заблокирован", "TikTok is blocked"), 16, wfg, true);
        card.addView(cardTitle);
        final TextView pill = text(a, tr("Экстренная разблокировка", "Emergency unlock"), 13, wfg, false);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(pillBg);
        pill.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 8));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(a, 10);
        card.addView(pill, pl);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(a, 14);
        p2.addView(card, cardLp);
        final int outline = wfg;
        refreshers.add(new Runnable() {
            @Override public void run() {
                cardBg.setColor(bgc[0]);
                cardBg.setStroke(dp(a, 1), outline);
                cardTitle.setTextColor(readable(bgc[0]));
                pillBg.setColor(acc[0]);
                pill.setTextColor(readable(acc[0]));
            }
        });

        colourRow(a, p2, tr("Цвет фона экрана блокировки", "Block screen background"),
                bgc, wbg, wfg, wacc, refreshers);
        colourRow(a, p2, tr("Цвет кнопки", "Button colour"),
                acc, wbg, wfg, wacc, refreshers);

        TextView themeHint = text(a, tr(
                "Это окно перекрасится после сохранения и повторного открытия.",
                "This window is re-coloured after saving and reopening it."), 12, wfg, false);
        themeHint.setAlpha(0.6f);
        themeHint.setPadding(0, dp(a, 12), 0, 0);
        p2.addView(themeHint);
        refresh.run();

        // ---------------- page 3: history
        LinearLayout p3 = pageBox(a);
        long[] vals = new long[7];
        String[] labs = new String[7];
        historyData(vals, labs);
        long total = 0;
        for (long v : vals) total += v;

        p3.addView(text(a, tr("Последние 7 дней", "Last 7 days"), 16, wfg, true));
        BarChart chart = new BarChart(a, vals, labs, limitMs(), wfg, wacc);
        LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 230));
        chl.topMargin = dp(a, 12);
        p3.addView(chart, chl);
        TextView sum = text(a, tr("Всего: ", "Total: ") + fmt(total) + "\n"
                + tr("В среднем в день: ", "Daily average: ") + fmt(total / 7), 14, wfg, false);
        sum.setPadding(0, dp(a, 12), 0, 0);
        p3.addView(sum);
        TextView hh = text(a, tr(
                "Пунктир на графике = ваш лимит. Сегодняшний день отмечен ярче. История копится с момента установки этой версии.",
                "The dashed line is your limit. Today is drawn brighter. History is collected from the moment this version is installed."),
                12, wfg, false);
        hh.setAlpha(0.6f);
        hh.setPadding(0, dp(a, 8), 0, 0);
        p3.addView(hh);

        // ---------------- pager + tabs
        final Pager pager = new Pager(a, pageW, 3);
        LinearLayout strip = new LinearLayout(a);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout[] pages = { p1, p2, p3 };
        for (LinearLayout pg : pages) {
            ScrollView sv = new ScrollView(a);
            sv.addView(pg, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            strip.addView(sv, new LinearLayout.LayoutParams(pageW, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        pager.addView(strip, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final TextView[] tabText = new TextView[3];
        final View[] tabLine = new View[3];
        String[] names = { tr("Лимит", "Limit"), tr("Тема", "Theme"), tr("История", "History") };
        LinearLayout tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView t = text(a, names[i], 15, wfg, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(a, 10), 0, dp(a, 8));
            View line = new View(a);
            cell.addView(t);
            cell.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 3)));
            final int idx = i;
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pager.goTo(idx); }
            });
            tabs.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tabText[i] = t;
            tabLine[i] = line;
        }
        Pager.OnPage onPage = new Pager.OnPage() {
            @Override public void on(int page) {
                for (int k = 0; k < 3; k++) {
                    tabText[k].setTextColor(k == page ? wacc : wfg);
                    tabText[k].setAlpha(k == page ? 1f : 0.6f);
                    tabLine[k].setBackgroundColor(k == page ? wacc : Color.TRANSPARENT);
                }
            }
        };
        pager.setOnPage(onPage);
        onPage.on(0);

        // ---------------- buttons
        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(a, 8), 0, 0);

        Button cancel = button(a, tr("Отмена", "Cancel"), Color.TRANSPARENT, wfg, wfg);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        halfWidth(a, cancel);
        buttons.addView(cancel);

        Button save = button(a, tr("Сохранить", "Save"), wacc, readable(wacc), 0);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                p.edit()
                        .putInt(K_LIMIT, num(limit, 1, 1440, DEF_LIMIT))
                        .putInt(K_REMIND, num(remind, 0, 1440, DEF_REMIND))
                        .putInt(K_UNLOCK, num(unlock, 1, 1440, DEF_UNLOCK))
                        .putInt(K_PER_DAY, num(perDay, 0, 99, DEF_PER_DAY))
                        .putInt(K_WAIT, num(wait, 0, 60, DEF_WAIT))
                        .putBoolean(K_ONLY_MAIN, onlyMain.isChecked())
                        .putString(K_BG, hex(bgc[0]))
                        .putString(K_ACCENT, hex(acc[0]))
                        .apply();
                margyt().log("timelimit: settings saved");
                schedule();
                d.dismiss();
            }
        });
        halfWidth(a, save);
        buttons.addView(save);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 12));
        root.addView(text(a, tr("Лимит времени TikTok", "TikTok time limit"), 20, wfg, true));
        root.addView(tabs);
        root.addView(pager, new LinearLayout.LayoutParams(pageW, pagerH));
        root.addView(buttons);
        showThemed(a, d, root, wbg);
    }

    /** Fills the 7-day arrays: oldest first, today (live) last. */
    private void historyData(long[] vals, String[] labs) {
        TreeMap<String, Long> m = readHist();
        SimpleDateFormat key = new SimpleDateFormat("yyyyMMdd", Locale.US);
        SimpleDateFormat day = new SimpleDateFormat("EE", Locale.getDefault());
        Calendar now = Calendar.getInstance();
        for (int i = 6; i >= 0; i--) {
            Calendar c = (Calendar) now.clone();
            c.add(Calendar.DAY_OF_YEAR, -i);
            int idx = 6 - i;
            labs[idx] = day.format(c.getTime());
            if (i == 0) {
                vals[idx] = usedMs();
            } else {
                Long v = m.get(key.format(c.getTime()));
                vals[idx] = v == null ? 0L : v;
            }
        }
    }

    /** The mod's current accent (plugin api 4), or null if it could not be read. */
    private Integer modAccent() {
        try {
            Object o = margyt().accentColour();
            if (o instanceof Number) return ((Number) o).intValue();
            if (o instanceof String) return Color.parseColor((String) o);
        } catch (Throwable t) {
            margyt().log("timelimit: accentColour failed: " + t);
        }
        return null;
    }

    /** A row: colour swatch + hex value; tapping it opens the colour wheel. */
    private void colourRow(final Activity a, LinearLayout box, String label, final int[] holder,
                           final int wbg, final int wfg, final int wacc,
                           ArrayList<Runnable> refreshers) {
        TextView l = text(a, label, 14, wfg, false);
        l.setPadding(0, dp(a, 14), 0, dp(a, 6));
        box.addView(l);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final GradientDrawable sw = new GradientDrawable();
        sw.setColor(holder[0]);
        sw.setCornerRadius(dp(a, 12));
        sw.setStroke(dp(a, 2), wfg);
        View swatch = new View(a);
        swatch.setBackground(sw);
        row.addView(swatch, new LinearLayout.LayoutParams(dp(a, 56), dp(a, 40)));

        final String tap = tr("(нажмите, чтобы выбрать)", "(tap to pick)");
        final TextView code = text(a, hex(holder[0]) + "   " + tap, 14, wfg, false);
        code.setPadding(dp(a, 12), 0, 0, 0);
        row.addView(code);

        final Runnable self = new Runnable() {
            @Override public void run() {
                sw.setColor(holder[0]);
                code.setText(hex(holder[0]) + "   " + tap);
            }
        };
        refreshers.add(self);
        final ArrayList<Runnable> all = refreshers;

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickColour(a, holder[0], wbg, wfg, wacc, new ColourSet() {
                    @Override public void set(int colour) {
                        holder[0] = colour;
                        for (Runnable r : all) r.run();
                    }
                });
            }
        });
        box.addView(row);
    }

    /** Colour wheel (hue + saturation), brightness slider and a hex field. */
    private void pickColour(final Activity a, int initial, final int wbg, final int wfg,
                            final int wacc, final ColourSet done) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = column(a);
        root.addView(text(a, tr("Выбор цвета", "Pick a colour"), 20, wfg, true));

        final ColorWheel wheel = new ColorWheel(a);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 260));
        wl.topMargin = dp(a, 12);
        root.addView(wheel, wl);
        wheel.setColour(initial);

        TextView bl = text(a, tr("Яркость", "Brightness"), 14, wfg, false);
        bl.setPadding(0, dp(a, 8), 0, 0);
        root.addView(bl);

        final SeekBar bright = new SeekBar(a);
        bright.setMax(100);
        bright.setProgress(Math.round(wheel.value() * 100));
        bright.setProgressTintList(ColorStateList.valueOf(wacc));
        bright.setThumbTintList(ColorStateList.valueOf(wacc));
        root.addView(bright);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(a, 8), 0, 0);

        final GradientDrawable pg = new GradientDrawable();
        pg.setColor(initial);
        pg.setCornerRadius(dp(a, 12));
        pg.setStroke(dp(a, 2), wfg);
        View preview = new View(a);
        preview.setBackground(pg);
        row.addView(preview, new LinearLayout.LayoutParams(dp(a, 56), dp(a, 40)));

        final EditText hexField = new EditText(a);
        hexField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hexField.setTextColor(wfg);
        hexField.setBackgroundTintList(ColorStateList.valueOf(wacc));
        hexField.setText(hex(initial));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hl.leftMargin = dp(a, 12);
        row.addView(hexField, hl);
        root.addView(row);

        final boolean[] updating = { false };

        wheel.setChanged(new ColorWheel.Changed() {
            @Override public void on(int colour) {
                pg.setColor(colour);
                if (!updating[0]) {
                    updating[0] = true;
                    hexField.setText(hex(colour));
                    updating[0] = false;
                }
            }
        });

        bright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) wheel.setValue(progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        hexField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (updating[0]) return;
                String t = s.toString().trim();
                if (t.length() != 7 || t.charAt(0) != '#') return;
                int col;
                try {
                    col = Color.parseColor(t) | 0xFF000000;
                } catch (IllegalArgumentException x) {
                    return;
                }
                updating[0] = true;
                try {
                    wheel.setColour(col);
                    bright.setProgress(Math.round(wheel.value() * 100));
                } finally {
                    updating[0] = false;
                }
            }
        });

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(a, 16), 0, 0);

        Button cancel = button(a, tr("Отмена", "Cancel"), Color.TRANSPARENT, wfg, wfg);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        halfWidth(a, cancel);
        buttons.addView(cancel);

        Button ok = button(a, "OK", wacc, readable(wacc), 0);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                done.set(wheel.colour());
                d.dismiss();
            }
        });
        halfWidth(a, ok);
        buttons.addView(ok);
        root.addView(buttons);

        ScrollView scroll = new ScrollView(a);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        showThemed(a, d, scroll, wbg);
    }

    /** Rounded dialog window in the given background colour, ~92% of the screen wide. */
    private static void showThemed(Activity a, Dialog d, View content, int bg) {
        d.setContentView(content);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(a, 20));
        d.getWindow().setBackgroundDrawable(g);
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        d.getWindow().setLayout((int) (dm.widthPixels * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ------------------------------------------------------------- helpers

    private static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 20);
        l.setPadding(pad, pad, pad, pad);
        return l;
    }

    private static LinearLayout pageBox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c, 4), dp(c, 12), dp(c, 4), dp(c, 12));
        return l;
    }

    private static TextView text(Context c, String s, int sp, int colour, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private static EditText field(Context c, LinearLayout box, String label, String value,
                                  int fg, int accent) {
        TextView l = text(c, label, 14, fg, false);
        l.setPadding(0, dp(c, 14), 0, 0);
        box.addView(l);
        EditText e = new EditText(c);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextColor(fg);
        e.setBackgroundTintList(ColorStateList.valueOf(accent));
        e.setText(value);
        box.addView(e);
        return e;
    }

    private static Button button(Context c, String label, int fill, int textColour, int stroke) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColour);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, 28));
        if (stroke != 0) g.setStroke(dp(c, 2), stroke);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 52));
        lp.topMargin = dp(c, 12);
        b.setLayoutParams(lp);
        return b;
    }

    /** Makes a button share a horizontal row equally with its neighbour. */
    private static void halfWidth(Context c, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 48), 1f);
        lp.leftMargin = dp(c, 4);
        lp.rightMargin = dp(c, 4);
        b.setLayoutParams(lp);
    }

    private static int num(EditText e, int min, int max, int def) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException x) {
            return def;
        }
    }

    static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /** Opaque colour from "#RRGGBB" (or "RRGGBB"); falls back to the default if invalid. */
    private static int colour(String s, String def) {
        try {
            return Color.parseColor(fixHash(s)) | 0xFF000000;
        } catch (Throwable t) {
            return Color.parseColor(def) | 0xFF000000;
        }
    }

    private static String fixHash(String s) {
        String h = s == null ? "" : s.trim();
        return h.startsWith("#") ? h : "#" + h;
    }

    private static String hex(int colour) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & colour);
    }

    /** Black or white, whichever reads better on the given background. */
    private static int readable(int bg) {
        double y = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg);
        return y > 150 ? Color.BLACK : Color.WHITE;
    }

    private static String fmt(long ms) {
        long m = ms / 60000L;
        if (m < 60) return m + tr(" мин", " min");
        return (m / 60) + tr(" ч ", " h ") + (m % 60) + tr(" мин", " min");
    }

    static String tr(String ru, String en) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? ru : en;
    }
}

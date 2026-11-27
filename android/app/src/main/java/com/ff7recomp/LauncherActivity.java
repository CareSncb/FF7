package com.ff7recomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;

/**
 * The app's entry point (7th Heaven's role): game files, mods (order, on/off, options),
 * settings, Play. Driven by the Thor's pad (D-pad moves focus, A confirms, B goes back,
 * Start plays) or by touch. The game runs in its own process (GameActivity, ":game"), so
 * quitting it lands back here and Play can start a fresh one.
 */
public class LauncherActivity extends Activity {
    private static final int PICK_GAME = 1, PICK_IRO = 2, PICK_FOLDER = 3, PICK_PSX = 4, PICK_STORAGE = 5, PICK_LOSSLESS = 6;
    /** Settings > Storage > Custom folder sent the player to grant All files access: pick the folder when they are back. */
    private boolean wantStorageFolder;

    /** Back with All files access: a custom folder that only lacked it is usable again (restart
     *  into it), else pick the folder. */
    private void storageAccessBack() {
        wantStorageFolder = false;
        if (!Storage.allFilesAccess(this)) return;
        if (Storage.place(this) == Storage.CUSTOM && Storage.customProblem(this) == null) restartLauncher();
        else pick(PICK_STORAGE);
    }
    // FF7-style menus: blue gradient windows with a light double border, white text with a drop
    // shadow, cyan labels, a pointing-glove cursor (HandCursor.java) on the menu.
    private static final int BG = 0xFF03050C, ACCENT = 0xFF7FD8FF, TEXT = 0xFFF4F6FF, DIM = 0xFFB4C0E4;
    private static final int WIN_TOP = 0xFF3B5CB4, WIN_BOTTOM = 0xFF0E1C64;          // windows
    private static final int BTN_TOP = 0xFF2C4594, BTN_BOTTOM = 0xFF101E5E;          // buttons
    private static final int HOT_TOP = 0xFF6286E6, HOT_BOTTOM = 0xFF2A43A0;          // focused / on
    private final java.util.Map<String, Button> nav = new java.util.HashMap<>();

    private GameInstall install;
    private ModLibrary library;
    private SaveStates states;
    private ModCatalog catalog;
    private ModDownloader downloader;
    private DownloadQueue queue;
    // one catalog / downloader / queue per process: downloads outlive a recreated activity
    private static ModCatalog sCatalog;
    private static ModDownloader sDownloader;
    private static DownloadQueue sQueue;
    private Button downloadsButton;
    private Button settingsButton;                 // shows a dot when an FF7Recomp update is out
    private String queueSig = "";                            // ids+states the page was built with
    private final java.util.Map<String, LinearLayout> actionSlots = new java.util.HashMap<>();   // Get mods rows
    private final java.util.Map<DownloadQueue.Item, Object[]> progressViews = new java.util.HashMap<>(); // Downloads page
    private String catQuery = "", catCategory = null;      // the Get mods page's filters
    private boolean catInstalledOnly;
    private final java.util.Map<String, android.graphics.Bitmap> previews = new java.util.HashMap<>();
    private SharedPreferences prefs;
    private FrameLayout content;
    private TextView status;
    private Button playButton;
    private String page = "mods";
    private boolean busy;
    private GameBuild build;

    /** A small screen (under 600dp wide, such as a 640x480 handheld): the launcher is drawn a
     *  little smaller and its button rows wrap, so nothing is cut off on the right (GitHub #47). */
    private boolean smallScreen;
    private static final int SMALL_WIDTH_DP = 600, COMPACT_WIDTH_DP = 560;
    /** TestControl SCREEN: the launcher laid out as on another screen, in a corner of this one. */
    private int testW, testH;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        android.util.DisplayMetrics dm = base.getResources().getDisplayMetrics();
        int wPx = Math.max(dm.widthPixels, dm.heightPixels), dpi = dm.densityDpi;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)x(\\d+)@(\\d+)")
                .matcher(base.getSharedPreferences("launcher", MODE_PRIVATE).getString(TestControl.TEST_SCREEN, ""));
        if (m.matches()) {
            testW = Integer.parseInt(m.group(1));
            testH = Integer.parseInt(m.group(2));
            wPx = Math.max(testW, testH);
            dpi = Integer.parseInt(m.group(3));
        }
        if (wPx * 160f / dpi >= SMALL_WIDTH_DP) {
            if (testW == 0) return;
        } else smallScreen = true;
        // a little more room: the screen as about 560dp wide (text 13sp is then still 15 pixels or more)
        int want = smallScreen ? Math.max(120, Math.round(wPx * 160f / COMPACT_WIDTH_DP)) : dpi;
        if (want < dpi || testW > 0) {
            android.content.res.Configuration o = new android.content.res.Configuration();
            o.densityDpi = Math.min(want, dpi);
            applyOverrideConfiguration(o);
        }
    }

    // ---- FF7's menu sounds (Sfx.java, from the player's game files)
    private Sfx sfx;
    private long lastNavKey;
    /** On every click of a view theme() passed: the game's confirm sound. Android calls a view's
     *  accessibility delegate on each performClick, with accessibility on or off. */
    private final View.AccessibilityDelegate clickSound = new View.AccessibilityDelegate() {
        @Override public void sendAccessibilityEvent(View host, int type) {
            if (type == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED) sound(Sfx.OK);
            super.sendAccessibilityEvent(host, type);
        }
    };

    // ---- updates (Updater.java)
    private TextView updateLine;                   // Settings page: the updater's status
    private String updateShown = "";

    /** Check GitHub for a newer release: when the launcher opens (every 6 hours at most) or on
     *  "Check now". A newer one is offered once per version; Settings keeps the button. */
    private void checkUpdates(boolean asked) {
        if (Updater.busy) return;
        if (!asked && System.currentTimeMillis() - prefs.getLong("update_checked", 0) < 6 * 3600_000L) {
            if (Updater.available != null) offerUpdate(false);
            return;
        }
        Updater.busy = true;
        Updater.status = "Recherche de mises à jour";
        new Thread(() -> {
            String msg;
            try {
                Updater.Release r = Updater.check(this);
                prefs.edit().putLong("update_checked", System.currentTimeMillis()).apply();
                msg = r != null ? "La version " + r.version + " est disponible" : "Vous avez la dernière version";
            } catch (Exception e) {
                msg = "Impossible de vérifier les mises à jour : " + e.getMessage();
            }
            final String m = msg;
            Updater.status = m;
            Updater.busy = false;
            runOnUiThread(() -> {
                updateStatus();
                if (updateLine != null) updateLine.setText(m);
                if (Updater.available != null) offerUpdate(asked);
                else if (asked) refresh();
            });
        }, "update-check").start();
    }

    private void offerUpdate(boolean asked) {
        Updater.Release r = Updater.available;
        if (r == null || isFinishing()) return;
        if (!asked && (r.version.equals(prefs.getString("update_skipped", "")) || r.version.equals(updateShown))) return;
        updateShown = r.version;
        String notes = r.notes.replaceAll("(?m)^#+\\s*", "").replaceAll("\\*\\*", "").trim();
        if (notes.length() > 900) notes = notes.substring(0, 900) + "…";
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Mise à jour disponible : " + r.version)
                .setMessage("Vous avez la " + Updater.installedVersion(this) + ". Vos fichiers du jeu, mods et sauvegardes sont conservés.\n\n" + notes)
                .setPositiveButton("Mettre à jour", (d, w) -> installUpdate())
                .setNegativeButton("Plus tard", null)
                .setNeutralButton("Ignorer cette version", (d, w) -> prefs.edit().putString("update_skipped", r.version).apply())
                .show());
    }

    private void installUpdate() {
        Updater.Release r = Updater.available;
        if (r == null || Updater.busy) return;
        if (GameBuild.running || JobService.running != null) { toastDialog("Pas maintenant : le jeu est en cours de compilation ou de téléchargement. Mettez à jour quand ce sera fini."); return; }
        Updater.busy = true;
        new Thread(() -> {
            try {
                Updater.install(this, r);           // the system then asks to confirm
            } catch (Exception e) {
                Updater.busy = false;
                Updater.percent = -1;
                Updater.status = "Échec de la mise à jour : " + e.getMessage();
                runOnUiThread(() -> toastDialog(Updater.status));
            }
        }, "update").start();
        show("settings");
    }

    private void sound(int id) {
        if (sfx != null) sfx.play(id);
    }

    /** The cursor sound when the D-pad moves focus (not when a page rebuild moves it). */
    private void cursorOnFocus(View decor) {
        decor.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (was != null && now != null && was != now && System.currentTimeMillis() - lastNavKey < 400) sound(Sfx.CURSOR);
        });
    }

    private void loadSounds() {
        if (sfx != null && sfx.isReady() && sfx.profileStamp == library.profile.lastModified()) {
            sfx.enabled = prefs.getBoolean("menu_sounds", true);
            return;
        }
        if (sfx != null) sfx.release();
        sfx = new Sfx(this, install.game, library.profile);   // the mods' sounds win, as in the game
        sfx.enabled = prefs.getBoolean("menu_sounds", true);
    }
    private android.widget.ProgressBar buildBar;   // Game files page, while building
    private TextView buildLine;
    private TextView jobLine;                      // Game files page, while JobService works
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean wasBuilding, wasJob;
    private final Runnable buildTick = new Runnable() {
        @Override public void run() {
            if (updateLine != null && !updateLine.getText().toString().equals(updateText())) updateLine.setText(updateText());
            if (JobService.running != null) {
                if (jobLine != null && JobService.status != null && !jobLine.getText().toString().equals(JobService.status)
                        && !jobLine.getText().toString().equals("Mise en pause…")) jobLine.setText(JobService.status);
                if (!wasJob) { wasJob = true; refresh(); }
            } else if (wasJob) {
                wasJob = false;
                refresh();
                // how it ended, when it did not go on to the build and is not waiting for Continue
                String m = JobService.status;
                if (!GameBuild.running && JobService.pending(LauncherActivity.this) == null && m != null && !m.isEmpty()) {
                    toastDialog(m + ((m.contains("ff7_en n'a été trouvé") || m.contains("n'a pas été trouvé")) ? "\n\n" + GAMENATIVE_HINT : ""));
                    JobService.status = "";
                }
            }
            if (GameBuild.running) {
                if (buildBar != null) { buildBar.setIndeterminate(GameBuild.percent <= 0); buildBar.setProgress(GameBuild.percent); }
                if (buildLine != null) buildLine.setText(GameBuild.stage + " · " + GameBuild.percent + "%");
                updateStatus();
                wasBuilding = true;
            } else if (wasBuilding) {
                wasBuilding = false;
                refresh();
            }
            ticker.postDelayed(this, 500);
        }
    };

    private String updateText() {
        return Updater.percent >= 0 ? Updater.status + " · " + Updater.percent + "%" : Updater.status;
    }

    /** The game can start: files imported and libff7.so built for this device. */
    private boolean ready() {
        return install.hasGame() && build.built() && !GameBuild.running && JobService.running == null;
    }

    /** The process's catalog, downloader and download queue (made once; also by QueueService
     *  when its notification's Continue runs without the launcher). */
    static synchronized DownloadQueue queueFor(android.content.Context c) {
        if (sQueue == null) {
            final File files = new GameInstall(c).files;
            sCatalog = new ModCatalog(files);
            sCatalog.rebuild();
            sDownloader = new ModDownloader(files, new ModLibrary(files).dir);
            sQueue = new DownloadQueue(c, sCatalog, sDownloader, new File(files, "downloads"), (m, file, wasUpdate) -> {
                // a new mod is switched on (the library lists new files last, off); an update
                // keeps its place, state and options
                if (wasUpdate) return;
                ModLibrary l = new ModLibrary(files);
                l.load();
                for (ModLibrary.Mod lm : l.mods) if (lm.file.equalsIgnoreCase(file)) lm.enabled = true;
                try { l.save(); } catch (Exception ignored) { }
            });
            sQueue.restore();
        }
        return sQueue;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Reports.keepJavaCrashes(this);
        install = new GameInstall(this);
        build = new GameBuild(this);
        library = new ModLibrary(install.files);
        queueFor(this);
        catalog = sCatalog;
        downloader = sDownloader;
        queue = sQueue;
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        if (Storage.sdMissing(this))
            new android.os.Handler(getMainLooper()).post(() -> toastDialog("Vos fichiers du jeu sont sur la carte SD, qui n'est "
                    + "pas insérée. Insérez-la et rouvrez FF7Recomp (Paramètres > Stockage les ramène dans le stockage "
                    + "interne)."));
        String customProblem = Storage.customProblem(this);
        if (customProblem != null)
            new android.os.Handler(getMainLooper()).post(() -> toastDialog(customProblem + ". Tant que ce n'est pas réglé, le "
                    + "lanceur utilise le dossier de l'appli dans le stockage interne, donc vos fichiers du jeu, mods et sauvegardes sont "
                    + "introuvables. Paramètres > Stockage > Dossier personnalisé redemande l'accès."));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        int pad = smallScreen ? dp(8) : dp(24);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(0, 0, smallScreen ? dp(8) : dp(14), 0);
        TextView title = text("FINAL FANTASY VII", smallScreen ? 13 : 17, TEXT);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        title.setLetterSpacing(0.04f);
        title.setSingleLine(true);
        side.addView(title);
        side.addView(text("ARM64 natif · FFNx · Vulkan", smallScreen ? 10 : 11, ACCENT));
        side.addView(space(smallScreen ? 4 : 10));
        // the command window, Play first (the cursor starts there)
        LinearLayout menu = column();
        menu.setBackground(window(WIN_TOP, WIN_BOTTOM, 8));
        menu.setPadding(dp(smallScreen ? 4 : 8), dp(6), dp(smallScreen ? 4 : 8), dp(6));
        playButton = menuItem("Jouer", null);
        playButton.setTextSize(smallScreen ? 16 : 19);
        playButton.setTypeface(Typeface.DEFAULT_BOLD);
        menu.addView(playButton);
        menu.addView(menuItem("Mods", "mods"));
        menu.addView(menuItem("Obtenir des mods", "catalog"));
        downloadsButton = menuItem("Téléchargements", "downloads");
        menu.addView(downloadsButton);
        menu.addView(menuItem("États de sauvegarde", "states"));
        menu.addView(menuItem("Succès", "achievements"));
        settingsButton = menuItem("Paramètres", "settings");
        menu.addView(settingsButton);
        menu.addView(menuItem("Fichiers du jeu", "game"));
        menu.addView(menuItem("Signaler un problème", "report"));
        Button quit = menuItem("Quitter", null);   // GitHub #42
        quit.setOnClickListener(v -> quitApp());
        menu.addView(quit);
        side.addView(menu, new LinearLayout.LayoutParams(dp(smallScreen ? 152 : 200), ViewGroup.LayoutParams.WRAP_CONTENT));
        // the menu scrolls when it is taller than the screen
        ScrollView sideScroll = new ScrollView(this);
        sideScroll.setVerticalScrollBarEnabled(false);
        sideScroll.addView(side);
        root.addView(sideScroll, new LinearLayout.LayoutParams(dp(smallScreen ? 160 : 214), ViewGroup.LayoutParams.MATCH_PARENT));

        // right: the info bar (FF7's one-line help window) over the page's window
        LinearLayout right = column();
        status = text("", smallScreen ? 12 : 14, TEXT);
        status.setBackground(window(WIN_TOP, WIN_BOTTOM, 8));
        status.setPadding(dp(smallScreen ? 10 : 16), dp(smallScreen ? 4 : 8), dp(smallScreen ? 10 : 16), dp(smallScreen ? 4 : 8));
        status.setMaxLines(2);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        right.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        right.addView(space(smallScreen ? 6 : 10));
        content = new FrameLayout(this);
        content.setBackground(window(WIN_TOP, WIN_BOTTOM, 10));
        if (smallScreen) content.setPadding(dp(10), dp(8), dp(10), dp(8));
        else content.setPadding(dp(22), dp(18), dp(22), dp(18));
        right.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        if (testW > 0) {   // TestControl SCREEN: that screen's size, in the top-left corner
            FrameLayout outer = new FrameLayout(this);
            outer.setBackgroundColor(0xFF303030);
            outer.addView(root, new FrameLayout.LayoutParams(Math.max(testW, testH), Math.min(testW, testH)));
            setContentView(outer);
        } else setContentView(root);
        theme(root);
        cursorOnFocus(getWindow().getDecorView());
        hideSystemBars();
        show(ready() ? "mods" : "game");
        // catalogs older than a day are fetched quietly in the background (as 7th Heaven does)
        new Thread(() -> { catalog.refresh(false); runOnUiThread(this::refresh); }).start();
        playButton.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (wantStorageFolder) storageAccessBack();   // back from Android's All files access screen
        queue.setListener(this::queueChanged);
        loadSounds();
        refresh();
        checkUpdates(false);
        ticker.removeCallbacks(buildTick);
        ticker.post(buildTick);
        testPlay(getIntent(), 0);
        clearModCacheAfterGame();
        if (cloudAfterGame && !busy && cloudSaves() && TestControl.gamePid(this) <= 0) {
            cloudAfterGame = false;
            new Thread(() -> {
                String m = syncCloudSaves();
                runOnUiThread(() -> { refresh(); if (m != null) toastDialog(m); });
            }, "cloud-saves").start();
        }
        crashTip();
        Intent ti = getIntent();
        if (ti != null && ti.getBooleanExtra(TestControl.EXTRA_STEAM_QR, false)) {
            ti.removeExtra(TestControl.EXTRA_STEAM_QR);
            steamSignIn();   // layout check; Cancel leaves the current sign-in untouched
        }
    }

    /** Once after each crash (or close for memory) of the game in the last day: what helps, and Report a
     *  problem (GitHub #90: Mali's shader compiler failing on a phone low on free memory). */
    private void crashTip() {
        long t = Reports.lastGameCrash(this);
        if (t <= prefs.getLong("crash_tip_seen", 0) || System.currentTimeMillis() - t > 24 * 3600_000L
                || TestControl.gamePid(this) > 0) return;
        prefs.edit().putLong("crash_tip_seen", t).apply();
        String exit = Reports.lastGameExit(this);
        sound(Sfx.ERROR);
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Le jeu s'est fermé de façon inattendue")
                .setMessage((exit != null ? "Il " + exit + ".\n\n" : "") + "Ce genre de plantage vient le plus souvent d'un appareil "
                        + "à court de mémoire, par exemple pendant la première préparation des graphismes d'une scène.\n\n"
                        + Reports.RECOMMENDED + "\n\nSi cela se reproduit, Signaler un problème (avant de rejouer) crée un "
                        + "zip avec le rapport de plantage à joindre à un ticket GitHub.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Signaler un problème", (d, w) -> show("report")).show());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    /** TestControl's PLAY (adb): press Play once the launcher is ready, with its Start override. */
    private void testPlay(Intent i, int tries) {
        if (i == null || !i.getBooleanExtra(TestControl.EXTRA_PLAY, false)) return;
        if (busy || !install.hasGame() || !ready()) {
            if (tries < 40) ticker.postDelayed(() -> testPlay(i, tries + 1), 250);
            return;
        }
        i.removeExtra(TestControl.EXTRA_PLAY);
        String start = i.getStringExtra(TestControl.EXTRA_START);
        play(start == null ? null : start.equals("normal") ? "" : start);
    }

    @Override
    protected void onPause() {
        super.onPause();
        queue.setListener(null);
        ticker.removeCallbacks(buildTick);
    }

    // ------------------------------------------------------------------- pages
    private void show(String which) {
        page = which;
        refresh();
    }

    private void refresh() {
        // only the cached mod info here: opening every archive (a large library on an SD card)
        // took longer than Android lets the UI thread wait (GitHub #57); the rest is read in
        // the background, then the page is rebuilt
        long t0 = android.os.SystemClock.uptimeMillis();
        if (!library.load(true)) warmMods();
        long ms = android.os.SystemClock.uptimeMillis() - t0;
        if (ms > 100) android.util.Log.w("FF7Recomp", "mod list took " + ms + " ms on the UI thread");
        catalog.prune(library.dir);
        updateStatus();
        playButton.setEnabled(ready() && !busy);
        // the same page rebuilt (a mod switched on or off, moved, a setting changed): the focused
        // view goes away with it, and Android put the controller cursor back on Play (GitHub #47)
        final String keepFocus = page.equals(shownPage) ? focusKey() : null;
        // and where it was on the screen: lines above it can change height (a conflict note
        // comes or goes), which moved the list under the cursor (GitHub #47)
        final int focusOffset = keepFocus == null ? Integer.MIN_VALUE : focusOffset();
        content.removeAllViews();
        actionSlots.clear();
        progressViews.clear();
        updateLine = null;
        queueSig = queueSignature();
        // the same page again (a mod switched on or off, a setting changed): keep its scroll
        // position, not back to the top (GitHub #32)
        final int keepY = page.equals(shownPage) ? scrollY : 0;
        shownPage = page;
        switch (page) {
            case "downloads": content.addView(downloadsPage()); break;
            case "settings": content.addView(settingsPage()); break;
            case "states": content.addView(statesPage()); break;
            case "achievements": content.addView(achievementsPage()); break;
            case "game": content.addView(gamePage()); break;
            case "report": content.addView(reportPage()); break;
            case "catalog": content.addView(catalogPage()); break;
            default: content.addView(modsPage()); break;
        }
        for (java.util.Map.Entry<String, Button> e : nav.entrySet()) e.getValue().setSelected(e.getKey().equals(page));
        theme(content);
        final View refocused = keepFocus == null ? null : restoreFocus(keepFocus);
        scrollY = keepY;
        if (content.getChildCount() > 0 && content.getChildAt(0) instanceof ScrollView) {
            ScrollView sv = (ScrollView) content.getChildAt(0);
            sv.setOnScrollChangeListener((v, x, y, ox, oy) -> scrollY = y);
            // before the rebuilt page's first frame: a post() ran after it, so the list showed a
            // frame at the top and jumped back (a mod switched on or off, #32)
            if (keepY > 0 || refocused != null && focusOffset != Integer.MIN_VALUE) sv.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    sv.getViewTreeObserver().removeOnPreDrawListener(this);
                    int y = refocused != null && focusOffset != Integer.MIN_VALUE ? topIn(refocused, sv) : Integer.MIN_VALUE;
                    sv.scrollTo(0, y != Integer.MIN_VALUE ? Math.max(0, y - focusOffset) : keepY);
                    if (refocused != null && refocused.getWidth() > 0)   // a moved mod stays in view
                        refocused.requestRectangleOnScreen(new android.graphics.Rect(0, 0, refocused.getWidth(), refocused.getHeight()), true);
                    return true;
                }
            });
        }
    }

    private String shownPage;
    private int scrollY;

    /** The focused view's top, from the top of the page's visible part (refresh() keeps it). */
    private int focusOffset() {
        View f = getCurrentFocus();
        if (f == null || content.getChildCount() == 0 || !(content.getChildAt(0) instanceof ScrollView)) return Integer.MIN_VALUE;
        ScrollView sv = (ScrollView) content.getChildAt(0);
        int y = topIn(f, sv);
        return y == Integer.MIN_VALUE ? y : y - sv.getScrollY();
    }

    /** v's top inside the scroll view's content, or MIN_VALUE when v is not in it. */
    private static int topIn(View v, ScrollView sv) {
        int y = 0;
        for (View c = v; c != sv; ) {
            y += c.getTop();
            if (!(c.getParent() instanceof View)) return Integer.MIN_VALUE;
            c = (View) c.getParent();
        }
        return y;
    }

    /** Where the focus is inside the page: a view's "focus:" tag, else its child-index path. */
    private String focusKey() {
        View f = getCurrentFocus();
        if (f == null) return null;
        StringBuilder path = new StringBuilder();
        for (View v = f; v != content; ) {
            if (v.getTag() instanceof String && ((String) v.getTag()).startsWith("focus:") && path.length() == 0)
                return (String) v.getTag();
            if (!(v.getParent() instanceof ViewGroup)) return null;   // not in the page
            ViewGroup g = (ViewGroup) v.getParent();
            path.insert(0, "/" + g.indexOfChild(v));
            v = g;
        }
        return path.toString();
    }

    /** Focus the rebuilt page's view for a focusKey(); else its first focusable, not Play. */
    private View restoreFocus(String key) {
        View t = null;
        if (key.startsWith("focus:")) t = content.findViewWithTag(key);
        else {
            t = content;
            for (String part : key.substring(1).split("/")) {
                int i;
                try { i = Integer.parseInt(part); } catch (NumberFormatException e) { t = null; break; }
                if (!(t instanceof ViewGroup) || i >= ((ViewGroup) t).getChildCount()) { t = null; break; }
                t = ((ViewGroup) t).getChildAt(i);
            }
        }
        if (t != null && t.isFocusable() && t.isEnabled() && t.requestFocus()) return t;
        content.requestFocus();
        return null;
    }

    /** Mods, laid out like FF9Recomp's (the user's wish): a note, one row of buttons, then a row
     *  per mod with its on/off box, its place in the load order and small buttons. The mods that
     *  are on come first, in load order, then the ones that are off. */
    private View modsPage() {
        LinearLayout col = column();
        col.addView(heading("Mods"));
        TextView how = text("FF7Recomp charge les mods activés, du haut vers le bas : quand deux modifient le même fichier, celui du haut l'emporte. "
                + "▲▼ changent l'ordre. Obtenir des mods en trouve d'autres ; Ajouter un fichier de mod prend un .iro, .zip ou .7z de Nexus Mods ou d'un autre site.", 13, DIM);
        how.setPadding(0, dp(2), 0, dp(4));
        col.addView(how);
        ViewGroup actions = buttonRow();
        actions.addView(button("Obtenir des mods…", v -> show("catalog")));
        actions.addView(button("Ajouter un fichier de mod", v -> pick(PICK_IRO)));
        actions.addView(button("Ajouter un dossier", v -> pick(PICK_FOLDER)));
        if (!library.mods.isEmpty()) {
            // every mod on or off in one go (GitHub #47)
            actions.addView(button("Tout activer", v -> allMods(true)));
            actions.addView(button("Tout désactiver", v -> allMods(false)));
        }
        // 7th Heaven's Auto-sort: category order, then the mods' own load-order rules (requested in the comments)
        if (library.mods.size() > 1) {
            Button sort = button("Tri auto", v -> {
                boolean changed = library.autoSort(m -> {
                    ModCatalog.Mod c = m.id == null || m.id.isEmpty() ? null : catalog.byId(m.id);
                    return c != null ? c.category : null;
                });
                if (changed) { saveProfile(); ensureConflicts(); refresh(); }
                android.widget.Toast.makeText(this, changed ? "Trié comme 7th Heaven : par catégorie, puis selon l'ordre de chargement propre à chaque mod"
                        : "Déjà dans l'ordre de 7th Heaven", android.widget.Toast.LENGTH_LONG).show();
            });
            sort.setSingleLine(true);   // one line: "Auto-" / "sort" read badly
            actions.addView(sort);
        }
        java.util.List<ModCatalog.Mod> ups = catalog.updates();
        if (!ups.isEmpty()) actions.addView(button("Tout mettre à jour (" + ups.size() + ")", v -> installMods(ups, true)));
        col.addView(actions);
        // what the enabled mods share (computed in the background: archives are read)
        ensureConflicts();
        if (library.enabledCount() > 1) {
            LinearLayout box = column();
            box.setPadding(0, dp(6), 0, dp(4));
            ConflictChecker.Report r = conflictReport;
            // while it is checked again the last answer stays, so the list below does not move
            // up and down (GitHub #47)
            if (r == null || r.lines.isEmpty() && conflictBusy) box.addView(text("Vérification des fichiers partagés par les mods activés…", 14, DIM));
            if (r != null && r.lines.isEmpty() && !conflictBusy) box.addView(text("✓ Aucun conflit : les mods activés remplacent des fichiers du jeu différents.", 14, 0xFF80D090));
            if (r != null && !r.lines.isEmpty()) {
                String head = (r.problems > 0 ? "⚠ " + r.problems + (r.problems == 1 ? " problème" : " problèmes") : "")
                        + (r.problems > 0 && r.overlaps > 0 ? ", " : "")
                        + (r.overlaps > 0 ? r.overlaps + (r.overlaps == 1 ? " chevauchement" : " chevauchements") + " entre mods" : "")
                        + (conflictBusy ? "  (vérification…)" : "");
                box.addView(text(head, 15, r.problems > 0 ? 0xFFFFC060 : 0xFFE0C080));
                for (int i = 0; i < Math.min(2, r.lines.size()); i++) box.addView(text(r.lines.get(i).text, 13, DIM));
                ViewGroup b = buttonRow();
                b.addView(small("Tous les détails", v -> showConflicts()));
                if (r.interfaceBelow) b.addView(small("Mettre les mods d'interface en premier", v -> {
                    if (library.interfaceFirst()) { saveProfile(); ensureConflicts(); refresh(); }
                }));
                box.addView(b);
            }
            col.addView(box);
        }
        if (library.mods.isEmpty()) col.addView(text("Aucun mod installé pour l'instant. Obtenir des mods liste les catalogues de 7th Heaven ; Ajouter un fichier de mod prend un fichier que vous avez téléchargé.", 16, DIM));
        java.util.List<ModLibrary.Mod> on = new java.util.ArrayList<>(), off = new java.util.ArrayList<>();
        for (ModLibrary.Mod m : library.mods) (m.enabled && m.error == null ? on : off).add(m);
        for (int i = 0; i < on.size(); i++) col.addView(modRow(on.get(i), i, on.size()));
        for (ModLibrary.Mod m : off) col.addView(modRow(m, -1, on.size()));
        return scroll(col);
    }

    /** A mod: its on/off box (load-order number, name, a dim note), ▲▼ while it is on, then
     *  Options, Update, Info and Remove; warnings, if any, on a line under it (which files it
     *  shares with other mods is under All details). */
    private View modRow(ModLibrary.Mod m, int pos, int onCount) {
        ModCatalog.Installed fromCat = catalog.installedByFile(m.file);
        ModCatalog.Mod catMod = fromCat == null ? null : catalog.byId(fromCat.id);
        boolean update = catMod != null && catalog.updateAvailable(catMod);
        StringBuilder note = new StringBuilder();
        String ver = fromCat != null && !fromCat.versionText.isEmpty() ? fromCat.versionText : m.version;
        if (ver != null && !ver.isEmpty()) note.append(ver.matches("(?i)v.*") ? ver : "v" + ver);
        if (update) note.append(note.length() > 0 ? ", " : "").append("mise à jour ").append(catMod.versionText);
        if (!m.options.isEmpty()) note.append(note.length() > 0 ? ", " : "").append(m.options.size()).append(m.options.size() == 1 ? " option" : " options");
        if (catMod == null) note.append(note.length() > 0 ? ", " : "").append("ajouté depuis un fichier");

        LinearLayout whole = column();
        whole.setPadding(0, dp(2), 0, dp(2));
        // the buttons wrap onto a second line if even their own line is too narrow
        ViewGroup r = new FlowRow(this);
        CheckBox cb = new CheckBox(this);
        cb.setTextColor(TEXT);
        cb.setTextSize(smallScreen ? 15 : 17);
        cb.setText(withNote((pos >= 0 ? (pos + 1) + ".  " : "") + m.title(), note.toString()));
        cb.setChecked(pos >= 0);
        cb.setEnabled(m.error == null);
        cb.setOnCheckedChangeListener((b, isOn) -> { m.enabled = isOn; saveProfile(); ensureConflicts(); refresh(); });
        cb.setTag("focus:mod:" + m.file);   // the cursor stays on it when the page is rebuilt (#47)
        if (pos >= 0) {
            Button up = rowButton("▲", v -> moveOn(m, -1));
            up.setTag("focus:up:" + m.file);     // follows the mod to its new place (#47)
            up.setEnabled(pos > 0);
            r.addView(up);
            Button down = rowButton("▼", v -> moveOn(m, +1));
            down.setTag("focus:down:" + m.file);
            down.setEnabled(pos < onCount - 1);
            r.addView(down);
        }
        if (!m.options.isEmpty()) r.addView(rowButton("Options", v -> options(m)));
        if (update && queue.pending(catMod) == null) r.addView(rowButton("Mettre à jour", v -> installMods(java.util.Collections.singletonList(catMod), true)));
        if (catMod != null) {
            Button info = rowButton("Infos", v -> modPage(catMod));
            info.setTag("focus:info:" + m.file);
            r.addView(info);
        }
        // any mod can be removed here, also one added from a file or folder (asked on Reddit)
        Button rm = rowButton("Retirer", v -> removeMod(m, fromCat, catMod));
        rm.setTag("focus:remove:" + m.file);
        r.addView(rm);
        // the name, then its buttons beside it; under it when they would leave the name too
        // little width (a 1240x1080 screen gave it a few pixels, GitHub #58)
        View line = new NameAndButtons(this, cb, r, dp(200), dp(8));
        line.setTag("modrow:" + m.file);
        whole.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // only what needs attention goes under the row
        StringBuilder warn = new StringBuilder();
        if (m.error != null) warn.append("Illisible : ").append(m.error);
        else {
            for (String u : m.unsupported) warn.append(warn.length() > 0 ? "\n" : "").append("⚠ ").append(u).append(" (code Windows, ignoré)");
            String[] inside = new File(library.dir, m.file).list();
            if (inside != null && inside.length == 0) warn.append(warn.length() > 0 ? "\n" : "").append("⚠ Ce dossier est vide, il ne change donc rien. Retirez-le.");
            if (m.hext != null && m.hextProblem) warn.append(warn.length() > 0 ? "\n" : "").append("⚠ ").append(m.hext);
        }
        if (warn.length() > 0) {
            TextView w = text(warn.toString(), 13, m.error != null ? 0xFFFF8080 : 0xFFE0C080);
            w.setPadding(dp(smallScreen ? 32 : 40), 0, 0, dp(4));
            whole.addView(w);
        }
        whole.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return whole;
    }

    /** ▲▼ on the Mods page: m trades places with the mod that is on above or below it. */
    private void moveOn(ModLibrary.Mod m, int dir) {
        java.util.List<ModLibrary.Mod> mods = library.mods;
        int i = mods.indexOf(m);
        if (i < 0) return;
        for (int j = i + dir; j >= 0 && j < mods.size(); j += dir) {
            ModLibrary.Mod o = mods.get(j);
            if (!o.enabled || o.error != null) continue;
            mods.set(j, m);
            mods.set(i, o);
            saveProfile();
            ensureConflicts();
            refresh();
            return;
        }
    }

    /** A small button only as wide as its label (FF9Recomp's size): five of them share a mod's
     *  row with its name. */
    private Button rowButton(String label, View.OnClickListener l) {
        Button b = button(label, l);
        b.setTextSize(14);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(34));
        b.setMinimumHeight(dp(34));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.setMarginStart(dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    /** A label with a smaller, dim note after it (as in FF9Recomp). */
    private CharSequence withNote(String label, String note) {
        if (note == null || note.isEmpty()) return label;
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(label).append("   ");
        int at = b.length();
        b.append(note);
        b.setSpan(new android.text.style.ForegroundColorSpan(DIM), at, b.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new android.text.style.RelativeSizeSpan(0.82f), at, b.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    /** Remove a mod from the Mods page: the launcher's copy (its file or folder in the mods
     *  folder) and its cache are deleted; the file or folder it was added from is not touched. */
    private void removeMod(ModLibrary.Mod m, ModCatalog.Installed fromCat, ModCatalog.Mod catMod) {
        if (m.file == null || m.file.isEmpty() || m.file.contains("/") || m.file.equals(".") || m.file.equals("..")) return;
        if (catMod != null && queue.pending(catMod) != null) { toastDialog(m.title() + " est dans Téléchargements ; annulez-le d'abord là-bas."); return; }
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Retirer " + m.title() + " ?\n\nLa copie du lanceur (" + m.file + ") est supprimée de l'appareil. "
                        + "Le fichier ou dossier d'où vous l'avez ajouté n'est pas touché.")
                .setPositiveButton("Retirer", (x, w) -> {
                    ModDownloader.deleteTree(new File(library.dir, m.file));
                    ModDownloader.deleteTree(new File(modCache(), cacheName(m.file)));
                    if (fromCat != null) catalog.forget(fromCat.id);
                    library.load();
                    saveProfile();
                    ensureConflicts();
                    refresh();
                })
                .setNegativeButton("Annuler", null)
                .show());
    }

    /** All on / All off (GitHub #47): every mod that can be read. */
    private void allMods(boolean on) {
        boolean changed = false;
        for (ModLibrary.Mod m : library.mods)
            if (m.error == null && m.enabled != on) { m.enabled = on; changed = true; }
        if (changed) { saveProfile(); ensureConflicts(); refresh(); }
    }

    // ---- conflicts between the enabled mods (ConflictChecker.report, src/mods) ------------------
    private ConflictChecker.Report conflictReport;
    private String conflictKey = "", conflictWanted = "";
    private boolean conflictBusy;

    /** Recompute when the profile or a mod file changed (a background thread; then refresh). */
    private void ensureConflicts() {
        StringBuilder k = new StringBuilder().append(library.profile.lastModified());
        for (ModLibrary.Mod m : library.mods) {
            if (!m.enabled) continue;
            File f = new File(library.dir, m.file);
            k.append('|').append(m.file).append(':').append(f.length()).append(':').append(f.lastModified());
        }
        final String key = k.toString();
        conflictWanted = key;
        if (key.equals(conflictKey) || conflictBusy) return;
        conflictBusy = true;
        new Thread(() -> {
            org.json.JSONObject j = library.conflicts();
            ConflictChecker.Report r = ConflictChecker.report(j, catalog);
            ConflictChecker.optionNeeds(r, library, catalog);
            ConflictChecker.interfaceOrder(r, library);
            runOnUiThread(() -> {
                int before = conflictReport == null ? -1 : conflictReport.overlaps + conflictReport.problems;
                conflictReport = r;
                conflictKey = key;
                conflictBusy = false;
                if (!conflictWanted.equals(key)) { ensureConflicts(); return; }   // changed meanwhile
                if (before >= 0 && r.overlaps + r.problems > before && !"mods".equals(page))
                    android.widget.Toast.makeText(this, "Certains mods partagent maintenant des fichiers : voir Mods", android.widget.Toast.LENGTH_LONG).show();
                if ("mods".equals(page)) refresh();
            });
        }, "mod-conflicts").start();
    }

    private void showConflicts() {
        ConflictChecker.Report r = conflictReport;
        if (r == null) return;
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Conflits entre mods")
                .setView(scroll(warningList(r.lines, "Quand deux mods remplacent le même fichier du jeu, celui placé le plus haut dans la liste des mods est utilisé "
                        + "(utilisez ▲ ▼ pour changer). Les chevauchements sont souvent sans problème, comme un pack de textures sur un pack de modèles ; "
                        + "un ⚠ signifie qu'un mod lui-même signale un problème.")))
                .setPositiveButton("OK", null)
                .show());
    }

    private void options(ModLibrary.Mod m) {
        LinearLayout col = column();
        col.setPadding(dp(24), dp(8), dp(24), dp(8));
        for (ModLibrary.Option o : m.options) {
            col.addView(text(o.name, 17, TEXT));
            if (o.description != null && !o.description.isEmpty()) col.addView(text(o.description, 13, DIM));
            if (o.valueIds.isEmpty() || "Bool".equalsIgnoreCase(o.type)) {
                CheckBox cb = new CheckBox(this);
                cb.setText("Activé");
                cb.setTextColor(TEXT);
                cb.setChecked(m.value(o) != 0);
                cb.setOnCheckedChangeListener((b, on) -> m.chosen.put(o.id.toLowerCase(java.util.Locale.ROOT), on ? 1 : 0));
                col.addView(cb);
            } else {
                RadioGroup g = new RadioGroup(this);
                for (int k = 0; k < o.valueIds.size(); k++) {
                    RadioButton rb = new RadioButton(this);
                    rb.setText(o.valueNames.get(k));
                    rb.setTextColor(TEXT);
                    rb.setId(View.generateViewId());
                    final int val = o.valueIds.get(k);
                    rb.setOnClickListener(v -> m.chosen.put(o.id.toLowerCase(java.util.Locale.ROOT), val));
                    g.addView(rb);
                    if (val == m.value(o)) rb.setChecked(true);
                }
                col.addView(g);
            }
            col.addView(space(12));
        }
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(m.title())
                .setView(scroll(col))
                .setPositiveButton("OK", (d, w) -> { saveProfile(); refresh(); })
                .show());
    }

    // ------------------------------------------------------------------- Get mods (catalogs)
    /** Get mods, laid out like a store: header (count, refresh, update all), search, then the
     *  categories on the left and the matching mods on the right. A row opens the mod's page;
     *  its one button installs or updates it. */
    private View catalogPage() {
        LinearLayout page = column();
        int total = catalog.mods.size();

        LinearLayout head = row();
        TextView h = heading("Obtenir des mods");
        h.setPadding(0, 0, dp(12), 0);
        head.addView(h);
        head.addView(text(total + (total == 1 ? " mod" : " mods"), 15, ACCENT));
        View gap = new View(this);
        head.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        java.util.List<ModCatalog.Mod> ups = catalog.updates();
        if (!ups.isEmpty()) head.addView(compact(small("Tout mettre à jour (" + ups.size() + ")", v -> installMods(ups, true))));
        head.addView(compact(small("⟳ Actualiser", v -> background(() -> {
            progress("Vérification des catalogues de mods");
            String err = catalog.refresh(true);
            return err == null ? null : "Certains catalogues n'ont pas pu être vérifiés :\n" + err;
        }))));
        page.addView(head);

        EditText q = new EditText(this);
        q.setHint("🔍  Rechercher par nom, auteur ou tag");
        q.setText(catQuery);
        q.setSingleLine(true);
        q.setTextSize(16);
        q.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        q.setTag("focus:search");     // a rebuilt page keeps the cursor in the box
        q.setSelection(catQuery.length());
        // the list follows what is typed: some keyboards have no Enter key (GitHub #47)
        q.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int n) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                catQuery = e.toString();
                ticker.removeCallbacks(searchNow);
                ticker.postDelayed(searchNow, 300);
            }
        });
        q.setOnEditorActionListener((v, id, ev) -> {
            catQuery = v.getText().toString();
            ticker.removeCallbacks(searchNow);
            searchNow.run();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
        page.addView(q, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(space(6));

        LinearLayout split = row();
        split.setGravity(Gravity.TOP);
        // categories: All, Installed, then 7th Heaven's that have mods, each with its count
        LinearLayout cats = column();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        int installed = 0;
        for (ModCatalog.Mod m : catalog.mods) {
            counts.merge(m.category.toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
            if (catalog.installedOf(m) != null) installed++;
        }
        cats.addView(category("Tous", total, catCategory == null && !catInstalledOnly, v -> { catCategory = null; catInstalledOnly = false; refresh(); }));
        cats.addView(category("Installé", installed, catInstalledOnly, v -> { catCategory = null; catInstalledOnly = true; refresh(); }));
        View line = new View(this);
        line.setBackgroundColor(0x40FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.setMargins(dp(8), dp(6), dp(8), dp(6));
        cats.addView(line, lp);
        for (String c : ModCatalog.CATEGORIES) {
            Integer n = counts.get(c.toLowerCase(java.util.Locale.ROOT));
            if (n == null) continue;
            cats.addView(category(c, n, c.equals(catCategory) && !catInstalledOnly, v -> { catCategory = c; catInstalledOnly = false; refresh(); }));
        }
        ScrollView catScroll = scroll(cats);
        catScroll.setVerticalScrollBarEnabled(false);
        // picking a category rebuilds the page: keep the category list where it was, not back
        // at the top (GitHub #54)
        final int keepCatY = catScrollY;
        catScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> catScrollY = y);
        if (keepCatY > 0) catScroll.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                catScroll.getViewTreeObserver().removeOnPreDrawListener(this);
                catScroll.scrollTo(0, keepCatY);
                return true;
            }
        });
        split.addView(catScroll, new LinearLayout.LayoutParams(dp(smallScreen ? 104 : 132), ViewGroup.LayoutParams.MATCH_PARENT));
        View vline = new View(this);
        vline.setBackgroundColor(0x40FFFFFF);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), ViewGroup.LayoutParams.MATCH_PARENT);
        vlp.setMargins(dp(smallScreen ? 4 : 8), 0, dp(smallScreen ? 6 : 12), 0);
        split.addView(vline, vlp);

        catList = column();
        fillCatalogList();
        split.addView(scroll(catList), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        page.addView(split, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    /** Get mods: the list the search and the category pick out (filled again as you type). */
    private LinearLayout catList;
    private int catScrollY;   // the Get mods category list's scroll position (GitHub #54)
    private final Runnable searchNow = () -> {
        if (!"catalog".equals(page) || catList == null) return;
        fillCatalogList();
        theme(catList);
    };

    private void fillCatalogList() {
        LinearLayout list = catList;
        list.removeAllViews();
        actionSlots.clear();
        int total = catalog.mods.size();
        if (total == 0) {
            list.addView(text("Aucun catalogue chargé pour l'instant. Vérifiez la connexion Internet et appuyez sur Actualiser.", 16, DIM));
            for (ModCatalog.Subscription sc : catalog.subs) if (sc.error != null) list.addView(text(sc.name + ": " + sc.error, 13, 0xFFFF8080));
        }
        java.util.List<ModCatalog.Mod> found = catalog.search(catQuery, catCategory, catInstalledOnly);
        if (total > 0 && found.isEmpty()) list.addView(text("Aucun résultat.", 16, DIM));
        for (int i = 0; i < found.size(); i++) {
            if (i > 0) {
                View d = new View(this);
                d.setBackgroundColor(0x30FFFFFF);
                list.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
            }
            list.addView(catalogRow(found.get(i), catCategory == null));
        }
        if (list.getParent() instanceof ScrollView) ((ScrollView) list.getParent()).scrollTo(0, 0);
    }

    /** A category in the Get mods list: its name and count; the one shown is lit. */
    /** French names for 7th Heaven's catalog categories (display only; the catalogs stay English). */
    static String catFr(String c) {
        if (c == null) return "";
        switch (c.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "miscellaneous": return "Divers";
            case "animations": return "Animations";
            case "battle models": return "Modèles de combat";
            case "battle textures": return "Textures de combat";
            case "field models": return "Modèles des décors";
            case "field textures": return "Textures des décors";
            case "gameplay": return "Gameplay";
            case "media": return "Médias";
            case "minigames": return "Mini-jeux";
            case "spell textures": return "Textures des sorts";
            case "user interface": return "Interface";
            case "world models": return "Modèles du monde";
            case "world textures": return "Textures du monde";
            case "shaders": return "Shaders";
            case "unknown": return "Inconnue";
            default: return c;
        }
    }

    private Button category(String label, int count, boolean on, View.OnClickListener l) {
        label = catFr(label);
        Button b = new Button(this);
        android.text.SpannableString t = new android.text.SpannableString(label + "  " + count);
        t.setSpan(new android.text.style.ForegroundColorSpan(on ? TEXT : DIM), label.length(), t.length(), 0);
        t.setSpan(new android.text.style.RelativeSizeSpan(0.8f), label.length(), t.length(), 0);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(TEXT);
        shadow(b);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(8), 0, dp(4), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_focused }, window(HOT_TOP, HOT_BOTTOM, 6));
        sl.addState(new int[] { android.R.attr.state_pressed }, window(HOT_TOP, HOT_BOTTOM, 6));
        if (on) sl.addState(new int[] {}, window(BTN_TOP, BTN_BOTTOM, 6));
        b.setBackground(sl);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        lp.setMargins(0, dp(1), 0, dp(1));
        b.setLayoutParams(lp);
        return b;
    }

    private View catalogRow(ModCatalog.Mod m, boolean showCategory) {
        // the name and details: tap (or A) for the mod's page
        LinearLayout info = column();
        info.setPadding(dp(8), dp(6), dp(8), dp(6));
        TextView name = text(m.name, 16, TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);
        StringBuilder b = new StringBuilder();
        if (!m.author.isEmpty()) b.append(m.author);
        if (showCategory && !m.category.isEmpty()) b.append(b.length() > 0 ? "  ·  " : "").append(catFr(m.category));
        if (!m.sizeText().isEmpty()) b.append(b.length() > 0 ? "  ·  " : "").append(m.sizeText());
        TextView meta = text(b.toString(), 13, DIM);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(meta);
        info.setFocusable(true);
        info.setClickable(true);
        info.setOnClickListener(v -> modPage(m));
        GradientDrawable hot = new GradientDrawable();
        hot.setColor(0x33FFFFFF);
        hot.setCornerRadius(dp(6));
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_focused }, hot);
        sl.addState(new int[] { android.R.attr.state_pressed }, hot);
        info.setBackground(sl);
        LinearLayout slot = row();
        slot.setGravity(Gravity.CENTER_VERTICAL | (smallScreen ? Gravity.START : Gravity.END));
        if (smallScreen) slot.setPadding(dp(8), 0, 0, 0);
        fillAction(slot, m);
        actionSlots.put(m.id, slot);
        LinearLayout r = details(info, slot);
        r.setPadding(0, dp(smallScreen ? 4 : 6), 0, dp(smallScreen ? 4 : 6));
        return r;
    }

    /** A Get mods row's action: Install / Update / Installed, or its place in the queue. */
    private void fillAction(LinearLayout slot, ModCatalog.Mod m) {
        DownloadQueue.Item q = queue.pending(m);
        ModCatalog.Installed in = catalog.installedOf(m);
        String label = q != null ? queueLabel(q, false)
                : in == null ? "\u0001install" : catalog.updateAvailable(m) ? "\u0001update" : "\u0001installed";
        if (label.equals(slot.getTag())) return;                     // unchanged: keep the focus
        boolean hadFocus = slot.hasFocus();
        slot.setTag(label);
        slot.removeAllViews();
        if (q != null) {
            Button b = compact(small(label, v -> show("downloads")));
            slot.addView(b);
            if (hadFocus) b.requestFocus();
            return;
        }
        if (in == null) slot.addView(compact(small("Installer", v -> installMods(java.util.Collections.singletonList(m), false))));
        else if (catalog.updateAvailable(m)) slot.addView(compact(small("Mettre à jour", v -> installMods(java.util.Collections.singletonList(m), true))));
        else slot.addView(text("✓ Installé", 14, ACCENT));
    }

    /** "Queued", "Downloading 40%", "Installing"; long: with sizes. */
    private String queueLabel(DownloadQueue.Item q, boolean longForm) {
        switch (q.state) {
            case QUEUED: return "En file d'attente";
            case RUNNING:
                if (!"Téléchargement".equals(q.phase)) return q.phase + "…";
                int pc = q.percent();
                if (!longForm) return pc >= 0 ? "Téléchargement " + pc + "%" : "Téléchargement…";
                return "Téléchargement " + ModDownloader.human(q.got)
                        + (q.total > 0 ? " sur " + ModDownloader.human(q.total) : "") + (pc >= 0 ? "  (" + pc + "%)" : "");
            case DONE: return q.update ? "Mis à jour vers " + q.mod.versionText : "Installé";
            case CANCELLED: return "Annulé";
            default: return "Échec : " + q.error;
        }
    }

    // ------------------------------------------------------------------- Downloads (the queue)
    private View downloadsPage() {
        LinearLayout col = column();
        col.addView(heading("Téléchargements"));
        col.addView(text("Les mods se téléchargent un par un, du haut vers le bas. Le lanceur reste utilisable pendant ce temps, et Jouer aussi : "
                + "un mod qui se termine pendant que le jeu tourne est utilisé au prochain Jouer. Les téléchargements continuent avec le "
                + "lanceur en arrière-plan ; Pause les arrête et garde ce qui est téléchargé.", 14, DIM));
        if (queue.paused() && queue.pendingCount() > 0)
            col.addView(text("En pause. Continuer reprend là où les téléchargements se sont arrêtés.", 15, ACCENT));
        ViewGroup bar = buttonRow();
        bar.addView(small("Obtenir des mods…", v -> show("catalog")));
        // GitHub #53 (and FF9Recomp): stop for now, keeping the partial download
        if (queue.paused() && queue.pendingCount() > 0) bar.addView(small("Continuer", v -> { askNotifications(); queue.resume(); refresh(); }));
        else if (queue.pendingCount() > 0) bar.addView(small("Pause", v -> { queue.pause(); refresh(); }));
        bar.addView(small("Effacer les terminés", v -> { queue.clearFinished(); refresh(); }));
        col.addView(bar);
        java.util.List<DownloadQueue.Item> items = queue.snapshot();
        if (items.isEmpty()) col.addView(text("\nAucun téléchargement. Choisissez des mods dans Obtenir des mods.", 16, DIM));
        for (DownloadQueue.Item q : items) col.addView(downloadRow(q));
        return scroll(col);
    }

    private View downloadRow(DownloadQueue.Item q) {
        LinearLayout whole = column();
        whole.setPadding(0, dp(10), 0, dp(10));
        ViewGroup bs = buttonRow();
        LinearLayout info = column();
        TextView name = text(q.mod.name + (q.update ? "  (mise à jour vers " + q.mod.versionText + ")" : ""), smallScreen ? 16 : 18, TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(name);
        boolean bad = q.state == DownloadQueue.State.FAILED;
        TextView line = text(queueLabel(q, true) + (q.state == DownloadQueue.State.QUEUED && !q.mod.sizeText().isEmpty() ? "  ·  " + q.mod.sizeText() : ""),
                14, bad ? 0xFFFF8080 : q.state == DownloadQueue.State.DONE ? ACCENT : DIM);
        info.addView(line);
        switch (q.state) {
            case QUEUED:
                bs.addView(small("▲", v -> queue.move(q, -1)));
                bs.addView(small("▼", v -> queue.move(q, +1)));
                bs.addView(small("Annuler", v -> queue.cancel(q)));
                break;
            case RUNNING:
                bs.addView(small("Annuler", v -> queue.cancel(q)));
                break;
            case FAILED: case CANCELLED:
                bs.addView(small("Réessayer", v -> queue.retry(q)));
                break;
            default:
                break;
        }
        whole.addView(details(info, bs));
        android.widget.ProgressBar bar = null;
        if (q.state == DownloadQueue.State.RUNNING) {
            bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            int pc = q.percent();
            bar.setIndeterminate(pc < 0 || !"Téléchargement".equals(q.phase));
            if (pc >= 0) bar.setProgress(pc);
            whole.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        }
        progressViews.put(q, new Object[] { line, bar });
        return whole;
    }

    private String queueSignature() {
        StringBuilder b = new StringBuilder();
        for (DownloadQueue.Item q : queue.snapshot()) b.append(q.mod.id).append(':').append(q.state).append(':').append(q.phase).append(';');
        return b.toString();
    }

    /** The queue moved: the status line always; the page in place when only progress changed. */
    private void queueChanged() {
        String sig = queueSignature();
        boolean structural = !sig.equals(queueSig);
        boolean installedOne = structural && countDone(sig) > countDone(queueSig);
        queueSig = sig;
        if (installedOne) { library.load(); catalog.prune(library.dir); ensureConflicts(); }
        updateStatus();
        switch (page) {
            case "downloads":
                if (structural) { refresh(); return; }
                for (java.util.Map.Entry<DownloadQueue.Item, Object[]> e : progressViews.entrySet()) {
                    DownloadQueue.Item q = e.getKey();
                    ((TextView) e.getValue()[0]).setText(queueLabel(q, true));
                    android.widget.ProgressBar bar = (android.widget.ProgressBar) e.getValue()[1];
                    if (bar != null && q.percent() >= 0 && "Téléchargement".equals(q.phase)) { bar.setIndeterminate(false); bar.setProgress(q.percent()); }
                }
                break;
            case "catalog":
                for (ModCatalog.Mod m : catalog.mods) { LinearLayout s = actionSlots.get(m.id); if (s != null) fillAction(s, m); }
                break;
            case "mods":
                if (installedOne) refresh();
                break;
            default:
                break;
        }
    }

    private static int countDone(String sig) {
        int n = 0, i = 0;
        while ((i = sig.indexOf(":DONE:", i)) >= 0) { n++; i++; }
        return n;
    }

    private void updateStatus() {
        StringBuilder s = new StringBuilder();
        s.append(!install.hasGame() ? "Jeu : pas encore importé"
                : GameBuild.running ? "Jeu : compilation pour cet appareil, " + GameBuild.percent + "%"
                : build.built() ? "Jeu : prêt" : "Jeu : pas encore compilé pour cet appareil")
                .append("\nMods : ").append(library.enabledCount()).append(" sur ").append(library.mods.size()).append(" activés");
        if (!catalog.updates().isEmpty()) s.append("\nMises à jour de mods : ").append(catalog.updates().size());
        if (Updater.available != null) s.append("\nFF7Recomp ").append(Updater.available.version).append(" disponible (Paramètres)");
        DownloadQueue.Item run = queue.running();
        int waiting = queue.pendingCount() - (run != null ? 1 : 0);
        if (run != null) s.append("\n").append(run.mod.name).append(": ").append(queueLabel(run, false));
        if (waiting > 0) s.append("\n").append(waiting).append(" de plus en attente");
        s.append("\nImages par seconde : ").append(fpsName(prefs.getInt("fps", 3))).append(boosterSpeed() > 1 ? ", " + boosterSpeed() + "× vitesse" : "");
        if (prefs.getBoolean("no_encounters", false) || prefs.getBoolean("battle_plus", false))
            s.append("\nBoosters : ").append(prefs.getBoolean("no_encounters", false) ? "sans combats aléatoires" : "")
                    .append(prefs.getBoolean("no_encounters", false) && prefs.getBoolean("battle_plus", false) ? ", " : "")
                    .append(prefs.getBoolean("battle_plus", false) ? "combats améliorés" : "");
        status.setText(s.toString().replace("\n", "   ·   "));
        int n = queue.pendingCount();
        downloadsButton.setText(n > 0 ? "Téléchargements (" + n + ")" : "Téléchargements");
        // an FF7Recomp update is out: a gold dot beside Settings, where the update button is
        if (Updater.available != null) {
            android.text.SpannableString t = new android.text.SpannableString("Paramètres  ●");
            t.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFD24A), t.length() - 1, t.length(), 0);
            settingsButton.setText(t);
            settingsButton.setContentDescription("Paramètres, FF7Recomp " + Updater.available.version + " disponible");
        } else {
            settingsButton.setText("Paramètres");
            settingsButton.setContentDescription(null);
        }
    }


    /** One mod's page: preview, description, notes, links, install / update / remove. */
    private void modPage(ModCatalog.Mod m) {
        LinearLayout col = column();
        col.setPadding(dp(24), dp(8), dp(24), dp(8));
        ImageView img = new ImageView(this);
        img.setAdjustViewBounds(true);
        img.setMaxHeight(dp(260));
        col.addView(img);
        loadPreview(m, img);
        StringBuilder head = new StringBuilder();
        if (!m.author.isEmpty()) head.append("par ").append(m.author).append('\n');
        head.append(m.category.isEmpty() ? "" : catFr(m.category) + "  ·  ").append("version ").append(m.versionText.isEmpty() ? "?" : m.versionText);
        if (!m.releaseDate.isEmpty()) head.append("  ·  ").append(m.releaseDate.length() > 10 ? m.releaseDate.substring(0, 10) : m.releaseDate);
        if (!m.sizeText().isEmpty()) head.append("  ·  ").append(m.sizeText());
        head.append("\nde ").append(m.catalog);
        col.addView(text(head.toString(), 14, DIM));
        ModCatalog.Installed in = catalog.installedOf(m);
        if (in != null) col.addView(text("Installé : version " + in.versionText + " (" + in.file + ")", 14, ACCENT));
        col.addView(space(8));
        if (!m.description.isEmpty()) col.addView(text(m.description, 15, TEXT));
        if (!m.releaseNotes.isEmpty()) { col.addView(space(8)); col.addView(text("Notes de version", 16, TEXT)); col.addView(text(m.releaseNotes, 14, DIM)); }
        if (!m.tags.isEmpty()) col.addView(text("\nTags : " + String.join(", ", m.tags), 13, DIM));
        for (String[] req : m.requirements) {
            ModCatalog.Mod rm = catalog.byId(req[0]);
            String what = rm != null ? rm.name : (req[1].isEmpty() ? req[0] : req[1]);
            boolean have = rm != null && catalog.installedOf(rm) != null;
            col.addView(text("Nécessite : " + what + (have ? " (installé)" : rm != null ? " (installé avec lui)" : " (absent de vos catalogues)"), 14, have ? DIM : 0xFFFFC060));
        }
        if (in == null) {
            java.util.List<ModCatalog.Mod> batch = new java.util.ArrayList<>();
            addWithRequirements(m, batch, 0);
            for (ConflictChecker.Warning w : ConflictChecker.beforeInstall(catalog, library, batch))
                col.addView(text((w.serious ? "⚠ " : "Conflit possible : ") + w.text, 14, w.serious ? 0xFFFFC060 : 0xFFE0C080));
        } else {
            java.util.List<String> notes = conflictReport == null ? null : conflictReport.perMod.get(in.file.toLowerCase(java.util.Locale.ROOT));
            if (notes != null) for (String s : notes) col.addView(text("↕ " + s, 14, 0xFFE0C080));
        }
        boolean browserOnly = !m.links.isEmpty();
        for (String l : m.links) if (ModDownloader.resolve(l) != null) browserOnly = false;
        if (browserOnly) col.addView(text("\nCe mod se télécharge depuis une page web : ouvrez-la, téléchargez, puis ajoutez le fichier dans Mods.", 14, 0xFFFFC060));
        AlertDialog.Builder d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(m.name)
                .setView(scroll(col))
                .setNegativeButton("Fermer", null);
        DownloadQueue.Item q = queue.pending(m);
        if (q != null) d.setPositiveButton(queueLabel(q, false) + " (Téléchargements)", (x, w) -> show("downloads"));
        else if (in == null && !browserOnly) d.setPositiveButton("Installer", (x, w) -> installMods(java.util.Collections.singletonList(m), false));
        else if (in != null && catalog.updateAvailable(m)) d.setPositiveButton("Mettre à jour vers " + m.versionText, (x, w) -> installMods(java.util.Collections.singletonList(m), true));
        else if (in != null) d.setPositiveButton("Retirer", (x, w) -> uninstall(m));
        String web = !m.infoLink.isEmpty() ? m.infoLink : null;
        if (browserOnly) for (String l : m.links) { String u = ModDownloader.browserUrl(l); if (u != null) web = u; }
        if (web != null) {
            final String url = web;
            d.setNeutralButton(browserOnly ? "Ouvrir la page de téléchargement" : "Page web du mod", (x, w) -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toastDialog("Aucun navigateur pour ouvrir " + url); }
            });
        }
        ff(d.show());
    }

    private void loadPreview(ModCatalog.Mod m, ImageView img) {
        if (m.preview.isEmpty()) { img.setVisibility(View.GONE); return; }
        android.graphics.Bitmap cached = previews.get(m.preview);
        if (cached != null) { img.setImageBitmap(cached); return; }
        new Thread(() -> {
            try {
                File dir = new File(catalog.dir, "previews");
                dir.mkdirs();
                File f = new File(dir, Integer.toHexString(m.preview.hashCode()) + ".img");
                if (!f.isFile()) {
                    byte[] b = ModDownloader.get(ModDownloader.resolve(m.preview) != null ? ModDownloader.resolve(m.preview) : m.preview);
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(b); }
                }
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 1;
                android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), bounds);
                while (bounds.outWidth / o.inSampleSize > 1600) o.inSampleSize *= 2;
                android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                if (bm == null) return;
                runOnUiThread(() -> { previews.put(m.preview, bm); img.setImageBitmap(bm); });
            } catch (Exception e) {
                runOnUiThread(() -> img.setVisibility(View.GONE));
            }
        }).start();
    }

    /** Install (or update) mods from the catalog, with the catalog mods they need first. */
    /** Queue mods (installs or updates) from the catalog, with the catalog mods they need first. */
    private void installMods(java.util.List<ModCatalog.Mod> wanted, boolean update) {
        java.util.List<ModCatalog.Mod> order = new java.util.ArrayList<>();
        for (ModCatalog.Mod m : wanted) addWithRequirements(m, order, 0);
        java.util.List<ModCatalog.Mod> todo = new java.util.ArrayList<>();
        for (ModCatalog.Mod m : order) {
            ModCatalog.Installed in = catalog.installedOf(m);
            if (in != null && !catalog.updateAvailable(m)) continue;
            if (queue.pending(m) == null) todo.add(m);
        }
        if (todo.isEmpty()) return;
        // conflicts with the mods already enabled (and within this batch): ask first
        java.util.List<ConflictChecker.Warning> warn = ConflictChecker.beforeInstall(catalog, library, todo);
        if (warn.isEmpty()) { enqueue(todo); return; }
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Conflits possibles")
                .setView(scroll(warningList(warn, "Les catalogues ne listent pas les fichiers contenus dans un mod, c'est donc une estimation d'après la nature des mods. "
                        + "Après le téléchargement, la page Mods montre exactement les fichiers qu'ils partagent.")))
                .setPositiveButton(todo.size() == 1 ? "Installer quand même" : "Tout installer quand même", (d, w) -> enqueue(todo))
                .setNegativeButton("Annuler", null)
                .show());
    }

    private LinearLayout warningList(java.util.List<ConflictChecker.Warning> warn, String footer) {
        LinearLayout col = column();
        col.setPadding(dp(24), dp(8), dp(24), dp(8));
        for (ConflictChecker.Warning w : warn) {
            col.addView(text((w.serious ? "⚠ " : "• ") + w.text, 15, w.serious ? 0xFFFFC060 : TEXT));
            col.addView(space(8));
        }
        if (footer != null) col.addView(text(footer, 13, DIM));
        return col;
    }

    private void enqueue(java.util.List<ModCatalog.Mod> todo) {
        int added = 0;
        for (ModCatalog.Mod m : todo) if (queue.add(m, catalog.installedOf(m) != null)) added++;
        if (added > 0) {
            android.widget.Toast.makeText(this, added == 1 ? "Ajouté aux téléchargements" : added + " mods ajoutés aux téléchargements",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
        queueChanged();
    }

    private void addWithRequirements(ModCatalog.Mod m, java.util.List<ModCatalog.Mod> order, int depth) {
        if (order.contains(m) || depth > 8) return;
        for (String[] req : m.requirements) {
            ModCatalog.Mod rm = catalog.byId(req[0]);
            if (rm != null && catalog.installedOf(rm) == null) addWithRequirements(rm, order, depth + 1);
        }
        order.add(m);
    }

    /** A newly installed mod is switched on (the library lists new files last, off). */
    private void enableNew(String file) {
        library.load();
        for (ModLibrary.Mod lm : library.mods) if (lm.file.equalsIgnoreCase(file)) lm.enabled = true;
        try { library.save(); } catch (Exception ignored) { }
    }

    private void uninstall(ModCatalog.Mod m) {
        ModCatalog.Installed in = catalog.installedOf(m);
        if (in == null) return;
        if (queue.pending(m) != null) { toastDialog(m.name + " est dans Téléchargements ; annulez-le d'abord là-bas."); return; }
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Retirer " + m.name + " ? Son fichier est supprimé de l'appareil.")
                .setPositiveButton("Retirer", (x, w) -> {
                    ModDownloader.deleteTree(new File(library.dir, in.file));
                    ModDownloader.deleteTree(new File(modCache(), cacheName(in.file)));
                    catalog.forget(m.id);
                    library.load();
                    saveProfile();
                    refresh();
                })
                .setNegativeButton("Annuler", null)
                .show());
    }

    /** Settings: the catalogs subscribed to. */
    private View catalogSettings() {
        LinearLayout col = column();
        col.addView(heading("Catalogues de mods"));
        col.addView(text("Les catalogues listent les mods à installer et à mettre à jour, comme dans 7th Heaven. Vérifiés une fois par jour à l'ouverture du lanceur.", 14, DIM));
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        for (ModCatalog.Subscription sc : catalog.subs) {
            ViewGroup bs = buttonRow();
            LinearLayout info = column();
            info.addView(text(sc.name, 16, TEXT));
            String st = sc.count + " mods" + (sc.lastCheck > 0 ? ", vérifié le " + df.format(new java.util.Date(sc.lastCheck)) : ", pas encore vérifié");
            info.addView(text(st + (sc.error != null ? "\n⚠ " + sc.error : ""), 13, sc.error != null ? 0xFFFFC060 : DIM));
            if (!catalog.isDefault(sc)) bs.addView(small("Retirer", v -> { catalog.removeSubscription(sc); catalog.rebuild(); refresh(); }));
            col.addView(details(info, bs));
        }
        col.addView(button("Ajouter un catalogue…", v -> {
            LinearLayout f = column();
            f.setPadding(dp(24), dp(8), dp(24), dp(8));
            EditText name = new EditText(this);
            name.setHint("Nom (facultatif)");
            EditText url = new EditText(this);
            url.setHint("iros://Url/https$... ou https://...catalog.xml");
            f.addView(name);
            f.addView(url);
            ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Ajouter un catalogue de mods")
                    .setView(f)
                    .setPositiveButton("Ajouter", (x, w) -> {
                        String err = catalog.addSubscription(name.getText().toString(), url.getText().toString());
                        if (err != null) { toastDialog(err); return; }
                        background(() -> { progress("Vérification du nouveau catalogue"); String e = catalog.refresh(false); return e; });
                    })
                    .setNegativeButton("Annuler", null)
                    .show());
        }));
        return col;
    }

    private View settingsPage() {
        LinearLayout col = column();
        col.addView(heading("Mises à jour"));
        Updater.Release up = Updater.available;
        if (Updater.status.isEmpty()) Updater.status = "FF7Recomp " + Updater.installedVersion(this);
        updateLine = text(updateText(), 15, up != null ? ACCENT : DIM);
        col.addView(updateLine);
        col.addView(space(8));
        LinearLayout ub = new LinearLayout(this);
        ub.addView(button("Vérifier maintenant", v -> checkUpdates(true)));
        if (up != null) {
            Button go = button("Mettre à jour vers " + up.version, v -> installUpdate());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(12);
            ub.addView(go, lp);
        }
        col.addView(ub);
        col.addView(text("Installée : " + Updater.installedVersion(this) + "   ·   depuis github.com/" + Updater.REPO, 13, DIM));
        col.addView(space(18));
        CheckBox ms = new CheckBox(this);
        ms.setText("Sons des menus (ceux de FF7, tirés de vos fichiers du jeu)");
        ms.setTextSize(16);
        ms.setChecked(prefs.getBoolean("menu_sounds", true));
        ms.setOnCheckedChangeListener((b, on) -> { prefs.edit().putBoolean("menu_sounds", on).apply(); loadSounds(); });
        col.addView(ms);
        col.addView(space(10));
        col.addView(heading("Langue"));
        col.addView(text("Les textes et menus du jeu. Les mods qui remplacent du texte (scripts des décors, commandes de combat) gardent leur propre langue.", 14, DIM));
        RadioGroup lg = new RadioGroup(this);
        int curLang = prefs.getInt("game_language", 1);
        for (String[] l : GameInstall.LANGUAGES) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            final int v = Integer.parseInt(l[0]);
            boolean have = !install.hasGame() || install.hasLanguage(v);
            rb.setText(have ? l[1] : l[1] + "\nImportez à nouveau vos fichiers du jeu (Fichiers du jeu) pour l'ajouter");
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            rb.setOnClickListener(x -> prefs.edit().putInt("game_language", v).apply());
            lg.addView(rb);
            if (v == curLang) rb.setChecked(true);
        }
        col.addView(lg);
        File sd = Storage.sdRoot(this);
        {
            col.addView(space(10));
            col.addView(heading("Stockage"));
            col.addView(text("Où sont gardés vos fichiers du jeu, mods, sauvegardes et états de sauvegarde. Les déplacer prend quelques "
                    + "minutes ; le jeu compilé reste toujours dans le stockage interne. Un dossier personnalisé (par exemple "
                    + "/sdcard/FF7Recomp) est facile à sauvegarder ou synchroniser, et nécessite l'\"Accès à tous les fichiers\" d'Android.", 14, DIM));
            RadioGroup sg = new RadioGroup(this);
            int place = Storage.place(this);
            File custom = Storage.customRoot(this);
            java.util.List<String[]> places = new java.util.ArrayList<>();
            places.add(new String[] { "0", "Stockage interne", ModDownloader.human(Storage.internalRoot(this).getUsableSpace()) + " libres" });
            if (sd != null || place == Storage.SD)
                places.add(new String[] { "1", "Carte SD", sd != null ? ModDownloader.human(sd.getUsableSpace()) + " libres" : "non insérée" });
            places.add(new String[] { "2", "Dossier personnalisé", custom != null
                    ? custom.getAbsolutePath() + (custom.isDirectory() ? ", " + ModDownloader.human(custom.getUsableSpace()) + " libres" : ", introuvable")
                    : "Un dossier de votre choix sur l'appareil ou la carte SD" });
            for (String[] pl : places) {
                RadioButton rb = new RadioButton(this);
                rb.setId(View.generateViewId());
                rb.setText(pl[1] + "\n" + pl[2]);
                rb.setTextColor(TEXT);
                rb.setTextSize(16);
                final int to = Integer.parseInt(pl[0]);
                rb.setChecked(to == place);
                rb.setEnabled(to != Storage.SD || sd != null);
                rb.setOnClickListener(x -> {
                    if (to == Storage.CUSTOM) customStorage();
                    else if (to != Storage.place(this)) moveStorage(to, null);
                    refresh();                               // the radio shows the real place until moved
                });
                sg.addView(rb);
            }
            col.addView(sg);
            if (place == Storage.CUSTOM) col.addView(small("Choisir un autre dossier", v -> customStorage()));
        }
        col.addView(space(10));
        col.addView(heading("Images par seconde"));
        RadioGroup g = new RadioGroup(this);
        int cur = prefs.getInt("fps", 3);
        for (int mode : new int[] { 0, 1, 2, 5, 3, 4 }) {   // 5: 60 FPS with battles at 30 (#64)
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            // one line: the name, then a short dim note
            android.text.SpannableString label = new android.text.SpannableString(fpsName(mode) + "   " + fpsDetail(mode));
            int at = fpsName(mode).length();
            label.setSpan(new android.text.style.ForegroundColorSpan(DIM), at, label.length(), 0);
            label.setSpan(new android.text.style.RelativeSizeSpan(0.85f), at, label.length(), 0);
            rb.setText(label);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int m = mode;
            rb.setOnClickListener(v -> { prefs.edit().putInt("fps", m).apply(); refresh(); });
            g.addView(rb);
            if (mode == cur) rb.setChecked(true);
        }
        col.addView(g);
        // Performance: render scale and frame skipping (heat and battery on smaller devices)
        col.addView(space(16));
        col.addView(heading("Performances"));
        col.addView(text("L'intensité du travail du GPU. Équilibré affiche à la résolution de l'écran et, quand le jeu est "
                + "accéléré, ne dessine que les images qu'aurait la vitesse normale. 120 FPS ci-dessus double le travail à vitesse normale.", 14, DIM));
        RadioGroup pg = new RadioGroup(this);
        String[] perfNames = {
            "Plus frais : une résolution plus basse, pour les appareils moins puissants ou les longues sessions",
            "Équilibré : la résolution de l'écran (recommandé)",
            "Meilleure qualité : la résolution demandée par les mods, chaque image dessinée en accéléré",
        };
        int curPerf = prefs.getInt("perf", 1);
        for (int p = 0; p < perfNames.length; p++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(perfNames[p]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int pv = p;
            rb.setOnClickListener(v -> prefs.edit().putInt("perf", pv).apply());
            pg.addView(rb);
            if (p == curPerf) rb.setChecked(true);
        }
        col.addView(pg);
        // Game boosters (docs/ROADMAP.md M8): how each game starts; in game the bottom screen's
        // Speed / Battles / Battle+ buttons and FFNx's L3 shortcuts change them.
        col.addView(space(16));
        col.addView(heading("Boosters de jeu"));
        col.addView(text("Comment le jeu démarre. En jeu : les boutons Vitesse, Lent (ralenti, pour les mini-jeux), Combats et Combat+ de l'écran du bas, ou L3 puis "
                + "R1/L1 (vitesse), A (combats aléatoires), B (combats améliorés). Des icônes en haut à droite montrent ce qui est actif.", 14, DIM));
        RadioGroup sg = new RadioGroup(this);
        sg.setOrientation(smallScreen ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int curSpeed = boosterSpeed();
        for (int sp = 1; sp <= 3; sp++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(sp + "× vitesse   ");
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            rb.setId(View.generateViewId());
            final int v = sp;
            rb.setOnClickListener(x -> { prefs.edit().putInt("speed", v).putBoolean("turbo", v > 1).apply(); refresh(); });
            sg.addView(rb);
            if (sp == curSpeed) rb.setChecked(true);
        }
        col.addView(sg);
        CheckBox noBattles = new CheckBox(this);
        noBattles.setText("Sans combats aléatoires\nLes combats scénarisés et les boss ont toujours lieu.");
        noBattles.setTextColor(TEXT);
        noBattles.setTextSize(16);
        noBattles.setChecked(prefs.getBoolean("no_encounters", false));
        noBattles.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("no_encounters", on).apply());
        col.addView(noBattles);
        CheckBox bplus = new CheckBox(this);
        bplus.setText("Combats améliorés\nLes PV et PM de l'équipe restent pleins en combat, et la jauge de Limite est pleine.");
        bplus.setTextColor(TEXT);
        bplus.setTextSize(16);
        bplus.setChecked(prefs.getBoolean("battle_plus", false));
        bplus.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("battle_plus", on).apply());
        col.addView(bplus);
        CheckBox saveAny = new CheckBox(this);
        saveAny.setText("Sauvegarder partout" + "\n" + "Sauvegardez aussi depuis le menu en dehors des points de sauvegarde. Gardez un second fichier de sauvegarde : une sauvegarde faite au "
                + "milieu d'un événement peut vous bloquer.");
        saveAny.setTextColor(TEXT);
        saveAny.setTextSize(16);
        saveAny.setChecked(prefs.getBoolean("save_anywhere", false));
        saveAny.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("save_anywhere", on).apply());
        col.addView(saveAny);
        multiplierRow(col, "Gils des combats", "gil");
        multiplierRow(col, "EXP des combats", "exp");    // GitHub #32
        multiplierRow(col, "AP des combats (évolution des matérias)", "ap");
        col.addView(space(16));
        col.addView(heading("Affichage"));
        RadioGroup ar = new RadioGroup(this);
        String[][] aspects = {
            { "0", "4:3, original", "Bandes noires sur les côtés d'un écran 16:9" },
            { "2", "16:9 grand écran", "Les scènes 3D et les menus utilisent tout l'écran ; les décors sans fond grand écran gardent des bandes sur les côtés" },
            { "3", "16:9, étiré pour remplir", "Pour les écrans plus larges que le 16:9 (la plupart des téléphones) : l'image 16:9 est étirée en largeur jusqu'aux bords de l'écran, sans bandes noires" },
            { "1", "Tout étiré pour remplir", "Toute l'image 4:3 étirée jusqu'aux bords de l'écran, décors et vidéos compris : sans bandes, mais personnages et objets paraissent plus larges" },
        };
        int curAr = prefs.getInt("aspect", 2);
        for (String[] a : aspects) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(a[1] + "\n" + a[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = Integer.parseInt(a[0]);
            rb.setOnClickListener(x -> prefs.edit().putInt("aspect", v).apply());
            ar.addView(rb);
            if (v == curAr) rb.setChecked(true);
        }
        col.addView(ar);
        CheckBox fps = new CheckBox(this);
        fps.setText("Afficher le compteur d'images par seconde");
        fps.setTextColor(TEXT);
        fps.setTextSize(16);
        fps.setChecked(prefs.getBoolean("show_fps", false));
        fps.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("show_fps", on).apply());
        col.addView(fps);
        col.addView(text("Résolution", 16, TEXT));
        col.addView(text("La résolution à laquelle le jeu est dessiné avant d'être adapté à l'écran. Au-dessus de la résolution de "
                + "l'écran, c'est plus net (suréchantillonnage), mais le GPU travaille plus : 4× dessine environ 3 fois "
                + "les pixels d'un écran 1080p.", 14, DIM));
        RadioGroup resg = new RadioGroup(this);
        String[][] resolutions = {
            { "0", "Automatique (recommandé)", "Selon Performances : la résolution de l'écran en Équilibré" },
            { "1", "Original", "640×480 (854×480 en 16:9)" },
            { "2", "2×", "1280×960 (1708×960 en 16:9), environ 1080p" },
            { "3", "3×", "1920×1440 (2562×1440 en 16:9), 1440p" },
            { "4", "4×", "2560×1920 (3416×1920 en 16:9), proche de la 4K" },
        };
        int curRes = prefs.getInt("resolution", 0);
        for (String[] r : resolutions) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(r[1] + "\n" + r[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = Integer.parseInt(r[0]);
            rb.setOnClickListener(x -> prefs.edit().putInt("resolution", v).apply());
            resg.addView(rb);
            if (v == curRes) rb.setChecked(true);
        }
        col.addView(resg);
        col.addView(text("Moteur de rendu", 16, TEXT));
        RadioGroup rg = new RadioGroup(this);
        String[][] renderers = {
            { "0", "Vulkan (recommandé)", "Le plus rapide sur la plupart des appareils" },
            { "1", "OpenGL ES", "Recommandé sur les GPU Mali (la plupart des téléphones MediaTek et Exynos). Essayez-le si le jeu est lent, plante, montre des erreurs graphiques ou ne démarre pas avec Vulkan" },
        };
        int curR = prefs.getInt("renderer", 0);
        for (String[] a : renderers) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(a[1] + "\n" + a[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = Integer.parseInt(a[0]);
            rb.setOnClickListener(x -> prefs.edit().putInt("renderer", v).apply());
            rg.addView(rb);
            if (v == curR) rb.setChecked(true);
        }
        col.addView(rg);
        CheckBox crt = new CheckBox(this);
        crt.setText("Filtre CRT\nLignes de balayage et masque de phosphore, comme une vieille télé (sur le second écran aussi)");
        crt.setTextColor(TEXT);
        crt.setTextSize(16);
        crt.setChecked(prefs.getBoolean("crt_filter", false));
        crt.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("crt_filter", on).apply());
        col.addView(crt);
        col.addView(space(16));
        frameGenSection(col);
        col.addView(space(16));
        ffnxSections(col);
        col.addView(heading("Écran"));
        CheckBox second = new CheckBox(this);
        second.setText("Utiliser le second écran\nSur les appareils à double écran comme l'AYN Thor : équipe, PV des ennemis et boutons tactiles. Désactivé : le second écran est libre pour d'autres applis, comme un guide (touchez l'écran du jeu pour rendre la manette au jeu)");
        second.setTextColor(TEXT);
        second.setTextSize(16);
        second.setChecked(prefs.getBoolean("bottom_screen", true));
        second.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("bottom_screen", on).apply());
        col.addView(second);
        CheckBox deckBox = new CheckBox(this);
        deckBox.setText("Boutons du second écran\nMenu, Sauver, Charger, Vitesse, Lent, Combats et Combat+ en bas des panneaux, "
                + "aussi là où ils sont sur l'écran unique. "
                + "Désactivé : les panneaux remplissent l'écran et la manette fait tout");
        deckBox.setTextColor(TEXT);
        deckBox.setTextSize(16);
        deckBox.setChecked(prefs.getBoolean("bottom_buttons", true));
        deckBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("bottom_buttons", on).apply());
        col.addView(deckBox);
        CheckBox artBox = new CheckBox(this);   // #79
        artBox.setText("Images du jeu sur les panneaux\nPortraits et carte du monde tels que le jeu les affiche, avec les versions de vos mods "
                + "(avatars de menu, carte annotée...). Désactivé : les portraits d'origine et la carte propre aux panneaux");
        artBox.setTextColor(TEXT);
        artBox.setTextSize(16);
        artBox.setChecked(prefs.getBoolean("game_art", false));
        artBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("game_art", on).apply());
        col.addView(artBox);
        CheckBox swapBox = new CheckBox(this);
        swapBox.setText("Inverser les écrans\nLe jeu sur le second écran et les panneaux sur l'écran principal. "
                + "Pour les appareils dont l'écran principal est le petit, comme l'Ayaneo Pocket DS");
        swapBox.setTextColor(TEXT);
        swapBox.setTextSize(16);
        swapBox.setChecked(prefs.getBoolean("swap_screens", false));
        swapBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("swap_screens", on).apply());
        col.addView(swapBox);
        RadioGroup or = new RadioGroup(this);
        String[][] orientations = {
            { "0", "Paysage", "Le jeu remplit l'écran" },
            { "1", "Portrait (téléphones)", "Le jeu en haut ; en dessous l'équipe, le lieu et les PV des ennemis, des boutons tactiles "
                    + "(menu, sauver/charger l'état, vitesse) et les commandes à l'écran" },
            { "2", "Partagé (pliables, tablettes)", "Dans les deux sens : le jeu en haut et les panneaux du second écran en dessous, "
                    + "avec les commandes à l'écran sur les côtés quand elles s'affichent. Les écrans sans place sous le jeu "
                    + "(l'écran externe d'un pliable) jouent en paysage" },
            { "3", "Côte à côte (écrans larges)", "Le jeu à gauche et les panneaux du second écran (équipe, menu, sauver/charger "
                    + "l'état, vitesse) à sa droite, sur un seul écran. Idéal avec l'image 4:3, qui laisse le plus de place ; "
                    + "tenu à la verticale, c'est comme Partagé" },
        };
        int curLayout = prefs.getBoolean("side", false) ? 3 : prefs.getBoolean("split", false) ? 2 : prefs.getBoolean("portrait", false) ? 1 : 0;
        for (String[] o : orientations) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(o[1] + "\n" + o[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = Integer.parseInt(o[0]);
            rb.setOnClickListener(x -> prefs.edit().putBoolean("portrait", v == 1).putBoolean("split", v == 2).putBoolean("side", v == 3).apply());
            or.addView(rb);
            if (v == curLayout) rb.setChecked(true);
        }
        col.addView(or);
        CheckBox sideLeftBox = new CheckBox(this);
        sideLeftBox.setText("Côte à côte : panneaux à gauche\nLes panneaux à gauche du jeu au lieu de sa droite. "
                + "En jeu, le bouton en haut des panneaux les masque ; l'onglet au bord de l'écran les fait revenir");
        sideLeftBox.setTextColor(TEXT);
        sideLeftBox.setTextSize(16);
        sideLeftBox.setChecked(prefs.getBoolean("side_left", false));
        sideLeftBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("side_left", on).apply());
        col.addView(sideLeftBox);
        CheckBox panelBox = new CheckBox(this);   // #87
        panelBox.setText("Bouton des panneaux (un écran)\nUn petit bouton par-dessus le jeu, en disposition Paysage, ouvre les panneaux "
                + "du second écran (équipe, carte, objets, sauver/charger l'état, vitesse) et les referme (ou touchez le jeu à côté). Faites-le glisser pour le déplacer. "
                + "Un bouton de manette peut aussi le faire : Manette > Ouvrir les panneaux avec");
        panelBox.setTextColor(TEXT);
        panelBox.setTextSize(16);
        panelBox.setChecked(prefs.getBoolean("panel_button", false));
        panelBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("panel_button", on).apply());
        col.addView(panelBox);
        // Lock rotation (#73): a phone held flat can turn the game by itself
        TextView lockLabel = new TextView(this);
        lockLabel.setText("Verrouiller la rotation\nGarde le jeu dans l'orientation réglée ici quand le téléphone est tourné ou posé à plat. "
                + "Portrait s'applique à Portrait, Partagé et Côte à côte");
        lockLabel.setTextColor(TEXT);
        lockLabel.setTextSize(16);
        lockLabel.setPadding(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
        col.addView(lockLabel);
        RadioGroup lockGroup = new RadioGroup(this);
        lockGroup.setOrientation(RadioGroup.HORIZONTAL);
        String[] locks = { "Désactivé", "Paysage", "Portrait" };
        int curLock = prefs.getInt("rotation_lock", 0);
        for (int i = 0; i < locks.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(locks[i]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = i;
            rb.setOnClickListener(x -> prefs.edit().putInt("rotation_lock", v).apply());
            lockGroup.addView(rb);
            if (v == curLock) rb.setChecked(true);
        }
        col.addView(lockGroup);
        col.addView(space(16));
        col.addView(heading("Commandes à l'écran"));
        RadioGroup tc = new RadioGroup(this);
        String[][] touchModes = {
            { "0", "Automatique", "Affichées sur les appareils à un écran sans manette connectée ; masquées dès qu'une manette est utilisée" },
            { "1", "Toujours", "Affichées par-dessus le jeu, même avec une manette" },
            { "2", "Désactivé", "Jamais affichées" },
        };
        int curTc = prefs.getInt("touch_controls", 0);
        for (String[] t : touchModes) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(t[1] + "\n" + t[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = Integer.parseInt(t[0]);
            rb.setOnClickListener(x -> prefs.edit().putInt("touch_controls", v).apply());
            tc.addView(rb);
            if (v == curTc) rb.setChecked(true);
        }
        col.addView(tc);
        col.addView(button("Taille et disposition…", v -> startActivity(new Intent(this, TouchLayoutActivity.class))));
        col.addView(space(8));
        col.addView(button("Boutons affichés…", v -> TouchLayoutActivity.buttonsShown(this, prefs, () -> { })));   // #88
        col.addView(space(16));
        col.addView(heading("Manette"));
        CheckBox analog = new CheckBox(this);
        analog.setText("Stick analogique\nInclinez un peu le stick gauche pour marcher, à fond pour courir. En combat, le stick droit tourne "
                + "la caméra et L2/R2 zooment (commandes analogiques de FFNx)");
        analog.setTextColor(TEXT);
        analog.setTextSize(16);
        analog.setChecked(prefs.getBoolean("analog_controls", false));
        analog.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("analog_controls", on).apply());
        col.addView(analog);
        CheckBox run = new CheckBox(this);   // GitHub #72
        run.setText("Toujours courir\nVous courez tout seul ; maintenez le bouton de course pour marcher. "
                + "Avec le stick analogique, une légère inclinaison marche toujours");
        run.setTextColor(TEXT);
        run.setTextSize(16);
        run.setChecked(prefs.getBoolean("always_run", false));
        run.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("always_run", on).apply());
        col.addView(run);
        CheckBox fire = new CheckBox(this);   // GitHub #33
        fire.setText("Maintenir Confirmer pour répéter en combat\nMaintenir le bouton de confirmation continue de choisir, environ cinq fois par "
                + "seconde, comme un tir automatique. Hors combat, il appuie une fois, comme dans l'original");
        fire.setTextColor(TEXT);
        fire.setTextSize(16);
        fire.setChecked(prefs.getBoolean("autofire", false));
        fire.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("autofire", on).apply());
        col.addView(fire);
        TextView keyLabel = new TextView(this);   // #87
        keyLabel.setText("Ouvrir les panneaux avec\nSur un écran, ce bouton ouvre et ferme les panneaux par-dessus le jeu (disposition Paysage), ou masque et "
                + "affiche ceux du Côte à côte. Le jeu ne le reçoit alors pas ; Start met en pause combats et mini-jeux");
        keyLabel.setTextColor(TEXT);
        keyLabel.setTextSize(16);
        keyLabel.setPadding(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
        col.addView(keyLabel);
        RadioGroup keyGroup = new RadioGroup(this);
        keyGroup.setOrientation(RadioGroup.HORIZONTAL);
        String[] panelKeys = { "Désactivé", "Start", "L3", "R3" };
        for (int i = 0; i < panelKeys.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(panelKeys[i]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = i;
            rb.setOnClickListener(x -> prefs.edit().putInt("panel_key", v).apply());
            keyGroup.addView(rb);
            if (v == prefs.getInt("panel_key", 0)) rb.setChecked(true);
        }
        col.addView(keyGroup);
        col.addView(text("Ce que fait chaque bouton d'une manette ou d'un clavier. En choisir un déjà utilisé échange les deux.", 14, DIM));
        final LinearLayout maps = new LinearLayout(this);
        maps.setOrientation(LinearLayout.VERTICAL);
        fillButtonMap(maps);
        col.addView(maps);
        col.addView(space(16));
        // Rumble (src/ffnx_port/rumble.cpp): FF7 has none of its own, so it follows the game
        RadioGroup rumbleGroup = new RadioGroup(this);
        String[][] rumbles = {
            { "0", "Sans vibration", "Comme l'original : le jeu ne vibre jamais" },
            { "1", "Vibration légère", "Secousses et explosions dans les décors, et en combat chaque coup : ceux que vous portez et ceux que vous recevez" },
            { "2", "Vibration forte", "La même chose, à pleine puissance" },
        };
        int curRumble = prefs.getInt("rumble", 0);
        for (String[] t : rumbles) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(t[1] + "\n" + t[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final int v = Integer.parseInt(t[0]);
            rb.setOnClickListener(x -> prefs.edit().putInt("rumble", v).apply());
            rumbleGroup.addView(rb);
            if (v == curRumble) rb.setChecked(true);
        }
        col.addView(rumbleGroup);
        col.addView(space(16));
        col.addView(heading("Start"));
        RadioGroup st = new RadioGroup(this);
        String[][] starts = {
            { "", "Normal", "Crédits, écran titre et vidéo d'introduction, comme le jeu les joue" },
            { "newgame", "Directement une nouvelle partie", "Passe les crédits, choisit Nouvelle partie et passe la vidéo d'introduction" },
            { "continue", "Continuer directement", "Passe les crédits et charge la première sauvegarde du premier fichier de sauvegarde" },
            { "resume", "Reprendre l'emplacement d'état de sauvegarde actuel", "Démarre le jeu au moment sauvegardé dans l'emplacement actuel (voir États de sauvegarde)" },
        };
        String curStart = prefs.getString("quickboot", "");
        for (String[] s : starts) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(s[1] + "\n" + s[2]);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final String v = s[0];
            rb.setOnClickListener(x -> prefs.edit().putString("quickboot", v).apply());
            st.addView(rb);
            if (v.equals(curStart)) rb.setChecked(true);
        }
        col.addView(st);
        col.addView(text("N'importe quel bouton arrête le démarrage automatique.", 14, DIM));
        col.addView(space(16));
        col.addView(text("En jeu : appuyez sur Start pendant une vidéo pour la passer. Select+L1 / Select+R1 sauvent et chargent l'état complet du jeu, "
                + "Select+Gauche / Droite changent d'emplacement. Maintenez Select+Start pour quitter le jeu.", 14, DIM));
        col.addView(space(16));
        col.addView(catalogSettings());
        col.addView(space(16));
        col.addView(modCacheSettings());
        return scroll(col);
    }

    /** files/modcache: the game extracts the files it uses from .iro mods there (src/mods/mods.cpp). */
    private File modCache() { return new File(install.files, "modcache"); }

    /** A mod's folder in the mod cache, as mods.cpp names it: its file name in ASCII lower case. */
    static String cacheName(String file) {
        StringBuilder b = new StringBuilder(file.length());
        for (char c : file.toCharArray()) {
            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
            else if (c == '/' || c == '\\' || c == ':') c = '_';
            b.append(c);
        }
        String n = b.toString();
        return n.isEmpty() || n.equals(".") || n.equals("..") ? "_" : n;
    }

    /** Settings: the mod cache's size and a button that empties it (issue #51). */
    private View modCacheSettings() {
        LinearLayout col = column();
        col.addView(heading("Cache des mods"));
        col.addView(text("Le jeu décompresse ici les fichiers qu'il utilise depuis les mods .iro, pour qu'ils se chargent vite la fois suivante. "
                + "Les dossiers des mods retirés sont supprimés au démarrage du jeu. Le vider est sans risque : les fichiers sont décompressés à nouveau quand le jeu en a besoin.", 14, DIM));
        TextView size = text("Taille : calcul…", 15, TEXT);
        col.addView(size);
        File dir = modCache();
        Runnable count = () -> new Thread(() -> {
            long n = dir.exists() ? Storage.size(dir) : 0;
            runOnUiThread(() -> size.setText("Taille : " + ModDownloader.human(n)));
        }, "modcache-size").start();
        count.run();
        col.addView(space(8));
        col.addView(button("Vider le cache des mods", v -> {
            if (TestControl.gamePid(this) > 0) { toastDialog("Fermez d'abord le jeu : il utilise le cache des mods."); return; }
            ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Vider le cache des mods ? Vos mods restent installés. La première fois que vous jouerez ensuite, certains lieux peuvent charger un peu plus lentement pendant que leurs fichiers sont décompressés à nouveau.")
                    .setPositiveButton("Vider", (x, w) -> {
                        size.setText("Taille : vidage…");
                        new Thread(() -> {
                            ModDownloader.deleteTree(dir);
                            runOnUiThread(count);
                        }, "modcache-clear").start();
                    })
                    .setNegativeButton("Annuler", null)
                    .show());
        }));
        // Issue #75: keep it small on phones low on storage
        col.addView(space(12));
        col.addView(text("Taille maximale", 16, TEXT));
        col.addView(text("Pendant que vous jouez, les fichiers utilisés le moins récemment sont supprimés pour rester en dessous.", 14, DIM));
        RadioGroup limits = new RadioGroup(this);
        int curLimit = prefs.getInt("modcache_limit_mb", 0);
        for (int mb : new int[] { 0, 500, 1024, 2048, 4096 }) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(mb == 0 ? "Sans limite" : mb < 1024 ? mb + " Mo" : (mb / 1024) + " Go");
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            rb.setOnClickListener(x -> prefs.edit().putInt("modcache_limit_mb", mb).apply());
            limits.addView(rb);
            if (mb == curLimit) rb.setChecked(true);
        }
        col.addView(limits);
        CheckBox onClose = new CheckBox(this);
        onClose.setText("Le vider à la fermeture du jeu\nChaque fois que vous quittez le jeu, le cache des mods est vidé. "
                + "Les lieux chargent un peu plus lentement la première fois à chaque session");
        onClose.setTextColor(TEXT);
        onClose.setTextSize(16);
        onClose.setChecked(prefs.getBoolean("modcache_clear_on_close", false));
        onClose.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("modcache_clear_on_close", on).apply());
        col.addView(onClose);
        return col;
    }

    /** Issue #75: empties the mod cache after a game, when "Clear it when the game closes" is on.
     *  The flag is saved, so a launcher restarted while the game ran still clears it. */
    private void clearModCacheAfterGame() {
        if (!prefs.getBoolean("modcache_used", false) || TestControl.gamePid(this) > 0) return;
        prefs.edit().putBoolean("modcache_used", false).apply();
        if (!prefs.getBoolean("modcache_clear_on_close", false) || install == null) return;
        File dir = modCache();
        new Thread(() -> {
            long n = dir.exists() ? Storage.size(dir) : 0;
            if (TestControl.gamePid(this) > 0) return;     // started again meanwhile
            ModDownloader.deleteTree(dir);
            android.util.Log.i("FF7Launcher", "mod cache cleared after the game (" + ModDownloader.human(n) + ")");
        }, "modcache-clear").start();
    }

    private static String fpsName(int mode) {
        switch (mode) {
            case 0: return "Original";
            case 1: return "Original, corrigé";
            case 2: return "Combats à 30 FPS";
            case 4: return "120 FPS (expérimental)";
            case 5: return "60 FPS, combats à 30";
            default: return "60 FPS";
        }
    }

    private static String fpsDetail(int mode) {
        switch (mode) {
            case 0: return "Comme à l'origine";
            case 1: return "Comme à l'origine, rythme plus régulier";
            case 2: return "Combats à 30, le reste comme à l'origine";
            case 4: return "Décors à 120 ; carte du monde et combats à 60";
            case 5: return "Tout à 60 sauf les combats, pour les appareils qui ralentissent en combat";
            default: return "Tout à 60";
        }
    }

    private static final String GAMENATIVE_HINT = "Vous avez téléchargé FF7 avec GameNative ? Son stockage interne est privé à "
            + "GameNative, les autres applis ne peuvent donc pas le lire. Utilisez l'option de stockage externe (carte SD) de GameNative, puis choisissez "
            + "GameNative/Steam/steamapps/common/FINAL FANTASY VII sur la carte. Ou copiez le dossier depuis un PC.";

    private View gamePage() {
        LinearLayout col = column();
        buildBar = null;
        buildLine = null;
        jobLine = null;
        col.addView(heading("Votre FINAL FANTASY VII"));
        // the Steam download or the import (JobService, GitHub #53): running, or stopped part way
        String pendingJob = JobService.pending(this);
        if (JobService.running != null) {
            col.addView(text(JobService.title(JobService.running) + ". Cela continue avec le lanceur en arrière-plan ; "
                    + "si le jeu doit être compilé pour cet appareil, cela commence juste après.", 15, TEXT));
            col.addView(space(10));
            jobLine = text(JobService.status, 15, ACCENT);
            col.addView(jobLine);
            col.addView(space(14));
            col.addView(button("Pause", v -> { JobService.pause(); if (jobLine != null) jobLine.setText("Mise en pause…"); }));
            col.addView(space(14));
            col.addView(text("Fichiers du jeu : " + install.game.getAbsolutePath(), 12, DIM));
            return scroll(col);
        }
        if (pendingJob != null && !GameBuild.running) {
            String why = JobService.paused ? "En pause." : JobService.status != null && JobService.status.startsWith("Arrêté")
                    ? JobService.status : "Il a été interrompu avant la fin.";
            col.addView(text(JobService.title(pendingJob) + ": " + why
                    + (why.contains("Continuer") ? "" : " Continuer reprend là où ça s'est arrêté ; rien de ce qui a été fait n'est perdu."), 15, TEXT));
            col.addView(space(14));
            ViewGroup b = buttonRow();
            b.addView(button("Continuer", v -> { askNotifications(); if (JobService.resume(this)) refresh(); }));
            b.addView(button("Annuler", v -> { JobService.forget(this); refresh(); }));
            col.addView(b);
            col.addView(space(14));
        }
        if (!install.hasGame()) {
            col.addView(text("FF7Recomp a besoin de votre copie de FINAL FANTASY VII sur Steam. Téléchargez-la ici avec votre "
                    + "compte Steam (environ 3,3 Go), ou choisissez le dossier du jeu si vous l'avez déjà sur cet appareil "
                    + "(le dossier qui contient \"ff7\"). FF7Recomp transforme ensuite le jeu en appli Android native "
                    + "sur cet appareil.", 15, TEXT));
            col.addView(space(14));
            col.addView(button(install.steamDownloading().exists() ? "Reprendre le téléchargement depuis Steam" : "Télécharger depuis Steam…",
                    v -> steamDownload()));
            col.addView(text("Connectez-vous une fois avec l'appli Steam de votre téléphone (un QR code ; aucun mot de passe n'est saisi ici). "
                    + "Le téléchargement continue avec le lanceur en arrière-plan, avec Pause ; s'il s'arrête, il reprend "
                    + "là où il en était.", 13, DIM));
            col.addView(space(14));
            col.addView(button("Trouver mon dossier FF7…", v -> pick(PICK_GAME)));
            col.addView(space(8));
            col.addView(text(GAMENATIVE_HINT, 13, DIM));
            col.addView(space(8));
            col.addView(text(Reports.RECOMMENDED, 13, DIM));
        } else if (GameBuild.running) {
            col.addView(text("Compilation du jeu pour cet appareil. Cela n'arrive qu'une fois : environ 8 minutes sur un AYN "
                    + "Thor, plus sur les téléphones plus lents (la barre peut rester à 4 % plusieurs minutes pendant la "
                    + "lecture du code du jeu). Vous pouvez quitter le lanceur ; la compilation continue en arrière-plan. "
                    + "Si elle est en pause ou arrêtée, Continuer la compilation reprend là où elle en était.", 15, TEXT));
            col.addView(space(14));
            buildLine = text(GameBuild.stage + " · " + GameBuild.percent + "%", 15, ACCENT);
            col.addView(buildLine);
            col.addView(space(6));
            buildBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            buildBar.setMax(100);
            buildBar.setIndeterminate(GameBuild.percent <= 0);
            buildBar.setProgress(GameBuild.percent);
            buildBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            col.addView(buildBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
            col.addView(space(14));
            // GitHub #53: stop for now, keeping what is done
            col.addView(button("Pause", v -> {
                BuildService.pause();
                if (buildLine != null) buildLine.setText("Mise en pause…");
            }));
        } else if (GameBuild.error == null && build.resumable()) {
            // paused, or stopped by the system or a restart (#53): the work done so far is kept
            col.addView(text((GameBuild.paused
                    ? "La compilation est en pause à " + GameBuild.percent + " %. Continuer la compilation reprend là où elle s'est arrêtée."
                    : "La dernière compilation a été interrompue avant la fin. Continuer la compilation reprend là où elle "
                      + "s'est arrêtée ; rien de ce qui a été fait n'est perdu.")
                    + (build.built() ? " En attendant, Jouer lance le jeu compilé précédemment." : ""), 15, TEXT));
            col.addView(space(14));
            col.addView(button("Continuer la compilation", v -> startBuild()));
            col.addView(space(8));
            col.addView(button("Importer à nouveau depuis un autre dossier…", v -> pick(PICK_GAME)));
        } else if (build.built()) {
            col.addView(text("Prêt. Le jeu est compilé pour cet appareil : appuyez sur Jouer.", 15, TEXT));
            col.addView(space(14));
            col.addView(button("Importer à nouveau depuis un autre dossier…", v -> pick(PICK_GAME)));
            col.addView(space(8));
            col.addView(button("Recompiler", v -> { new File(build.work, "stamp").delete(); startBuild(); }));
        } else {
            col.addView(text(GameBuild.error != null
                    ? "La dernière compilation s'est arrêtée : " + GameBuild.error
                    + (build.resumable() ? "\nRéessayer reprend là où elle s'est arrêtée." : "")
                    : build.relinkOnly()
                    ? "L'appli a été mise à jour : liez le jeu au nouveau moteur (quelques secondes)."
                    : "Vos fichiers du jeu sont là. Encore une étape : compiler le jeu pour cet appareil (une fois : environ 8 "
                      + "minutes sur un AYN Thor).", 15, GameBuild.error != null ? DIM : TEXT));
            col.addView(space(14));
            col.addView(button(GameBuild.error != null ? "Réessayer" : build.relinkOnly() ? "Mettre à jour le jeu" : "Compiler pour cet appareil",
                    v -> startBuild()));
            col.addView(space(8));
            col.addView(button("Importer à nouveau depuis un autre dossier…", v -> pick(PICK_GAME)));
        }
        if (install.hasGame() && !GameBuild.running) {
            col.addView(space(8));
            col.addView(button("Réparer depuis Steam", v -> steamDownload()));
            col.addView(text("Compare chaque fichier du jeu à la copie de Steam et ne télécharge que ceux qui manquent ou sont abîmés.", 13, DIM));
        }
        if (install.hasGame() && !GameBuild.running) {
            col.addView(space(14));
            android.widget.CheckBox q = new android.widget.CheckBox(this);
            q.setText("Compilation silencieuse : environ 14 minutes au lieu de 8, environ 12 °C de moins");
            q.setChecked(prefs.getBoolean("quiet_build", false));
            q.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("quiet_build", on).apply());
            col.addView(q);
        }
        col.addView(space(14));
        col.addView(text("Fichiers du jeu : " + install.game.getAbsolutePath() + "\nJournal de compilation : "
                + build.log.getAbsolutePath(), 12, DIM));
        return scroll(col);
    }

    private void startBuild() {
        if (GameBuild.running || !install.hasGame()) return;
        GameBuild.quiet = prefs.getBoolean("quiet_build", false);
        askNotifications();
        BuildService.start(this);
        show("game");
    }

    /** Background work shows its progress, Pause and Continue in a notification (API 33+ asks). */
    private void askNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 9);
    }

    /** The Steam download or the import, in JobService (GitHub #53: goes on in the background). */
    private void startJob(String kind, String arg) {
        if (busy || JobService.running != null || GameBuild.running) return;
        askNotifications();
        if (!JobService.start(this, kind, arg)) return;
        show("game");
    }

    // ------------------------------------------------------------------- actions
    /** Settings > Display: frame generation with the player's own Lossless Scaling (FF8Recomp #8). */
    private void frameGenSection(LinearLayout col) {
        col.addView(heading("Génération d'images"));
        boolean have = FrameGen.dll(install.files).isFile();
        CheckBox on = new CheckBox(this);
        on.setText("Génération d'images (Lossless Scaling)\nAffiche jusqu'à 60 images par seconde en créant des images entre celles du jeu, "
                + "qui garde sa vitesse normale. Nécessite Lossless Scaling (acheté sur Steam) et Vulkan 1.3. Cela ajoute un peu "
                + "de latence et consomme plus de batterie ; les scènes rapides peuvent paraître floues");
        on.setTextColor(have ? TEXT : DIM);
        on.setTextSize(16);
        on.setEnabled(have);
        on.setChecked(have && prefs.getBoolean("framegen", false));
        on.setOnCheckedChangeListener((b, x) -> prefs.edit().putBoolean("framegen", x).apply());
        col.addView(on);
        if (have && prefs.getInt("renderer", 0) == 1) col.addView(text("Ne fonctionne qu'avec le moteur de rendu Vulkan : choisissez Vulkan ci-dessus.", 14, DIM));
        if (have && !FrameGen.useful(prefs.getInt("fps", 3))) col.addView(text("Les images par seconde sont réglées sur " + fpsName(prefs.getInt("fps", 3))
                + " : il n'y a rien à générer. Choisissez une fréquence plus basse ci-dessus pour l'utiliser.", 14, DIM));
        if (!have) col.addView(text("FF7Recomp ne l'inclut pas : il utilise Lossless.dll (7 Mo) de votre propre copie, "
                + "téléchargé depuis votre compte Steam ou choisi ici.", 14, DIM));
        if (steam().signedIn()) col.addView(button(have ? "Récupérer à nouveau Lossless.dll depuis Steam" : "Récupérer Lossless.dll depuis votre Steam", v -> background(() -> {
            File tmp = new File(FrameGen.dir(install.files), "steam");
            GameBuild.deleteTree(tmp);
            try {
                String err = new SteamFiles(steam()).downloadLossless(tmp, this::progress, new java.util.concurrent.atomic.AtomicBoolean());
                if (err == null) try (java.io.InputStream in = new java.io.FileInputStream(new File(tmp, "Lossless.dll"))) { err = FrameGen.install(in, install.files); }
                if (err != null) return "Non ajouté : " + err;
                return frameGenAdded();
            } finally { GameBuild.deleteTree(tmp); }
        })));
        else col.addView(button("Se connecter à Steam pour récupérer Lossless.dll…", v -> steamSignIn()));
        col.addView(small("Choisir un Lossless.dll sur cet appareil…", v -> pick(PICK_LOSSLESS)));
    }

    private String frameGenAdded() {
        prefs.edit().putBoolean("framegen", true).apply();
        runOnUiThread(() -> show("settings"));
        return "Lossless.dll ajouté. La génération d'images est activée.";
    }

    private void pick(int what) {
        if (busy) return;
        Intent i;
        if (what == PICK_LOSSLESS) {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
        } else if (what == PICK_IRO || what == PICK_PSX) {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        }
        startActivityForResult(i, what);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == PICK_STORAGE) { pickedStorageFolder(data.getData()); return; }
        if (req == PICK_GAME) {
            // JobService (#53): the import goes on in the background, and Continue after a stop
            // needs the folder again, so the permission is kept until the import ends
            try { getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception e) { android.util.Log.w("FF7Jobs", "folder permission not kept: " + e); }
            startJob(JobService.IMPORT, data.getData().toString());
            return;
        }
        background(() -> {
            if (req == PICK_LOSSLESS) {
                try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
                    if (in == null) return "Non ajouté : le fichier est illisible";
                    String err = FrameGen.install(in, install.files);
                    return err != null ? "Non ajouté : " + err : frameGenAdded();
                }
            }
            if (req == PICK_PSX) return importPsx(data);
            if (req == PICK_IRO) {
                // .iro, .zip or .7z: Nexus Mods and other sites' downloads (unpacked like Get mods')
                java.util.List<Uri> picked = new java.util.ArrayList<>();
                if (data.getClipData() != null)
                    for (int k = 0; k < data.getClipData().getItemCount(); k++) picked.add(data.getClipData().getItemAt(k).getUri());
                else if (data.getData() != null) picked.add(data.getData());
                File inbox = new File(install.files, "downloads");
                inbox.mkdirs();
                java.util.List<String> added = new java.util.ArrayList<>(), failed = new java.util.ArrayList<>();
                for (int k = 0; k < picked.size(); k++) {
                    String tag = picked.size() > 1 ? " " + (k + 1) + " sur " + picked.size() : "";
                    progress("Copie du mod" + tag);
                    File copy = null;
                    try {
                        copy = install.importFile(picked.get(k), inbox);
                        final String name = copy.getName();
                        progress("Ajout de " + name);
                        added.add(downloader.installLocal(copy, (what, done, total) ->
                                progress("Décompression de " + name + (total > 0 ? " " + (done * 100 / total) + "%" : ""))));
                    } catch (java.io.IOException e) {
                        if (copy != null) copy.delete();
                        failed.add((copy != null ? copy.getName() : "un fichier") + ": " + e.getMessage());
                    }
                }
                StringBuilder msg = new StringBuilder();
                if (!added.isEmpty()) msg.append("Ajoutés : ").append(String.join(", ", added)).append(". Activez-les dans la liste.");
                if (!failed.isEmpty()) msg.append(msg.length() > 0 ? "\n\n" : "").append("Non ajoutés :\n").append(String.join("\n", failed));
                return msg.length() > 0 ? msg.toString() : "Aucun fichier choisi.";
            }
            File f = install.importFolder(data.getData(), library.dir, this::progress);
            return "Dossier de mod " + f.getName() + " ajouté. Activez-le dans la liste.";
        });
    }

    // ------------------------------------------------------------------- Steam achievements
    private SteamLink steam;
    /** Play synced the saves with Steam Cloud: sync again when the launcher is back and the game has closed. */
    private boolean cloudAfterGame;

    private SteamLink steam() {
        if (steam == null) steam = new SteamLink(this);
        return steam;
    }

    private void steamSection(LinearLayout col) {
        SteamLink s = steam();
        if (!s.signedIn()) {
            col.addView(text("Obtenez ici les succès Steam de FINAL FANTASY VII, sur votre compte Steam (la version Steam "
                    + "à partir de laquelle vous avez compilé le jeu). Connectez-vous une fois en scannant un QR code avec l'appli Steam de votre "
                    + "téléphone : vous ne saisissez jamais votre mot de passe Steam dans FF7Recomp. Les déblocages faits hors ligne sont envoyés la prochaine "
                    + "fois que vous êtes en ligne.", 14, DIM));
            col.addView(space(10));
            col.addView(button("Se connecter avec l'appli Steam…", v -> steamSignIn()));
            return;
        }
        int[] c = s.counts();
        col.addView(text("Connecté en tant que " + s.account() + (c != null ? "  ·  " + c[0] + " sur " + c[1] + " débloqués sur Steam" : ""), 16, ACCENT));
        int w = s.waiting();
        if (w > 0) col.addView(text(w + " déblocage(s) en attente d'envoi à Steam", 13, DIM));
        if (c == null) col.addView(text("Vos succès n'ont pas encore été lus depuis Steam : appuyez sur Synchroniser les succès une fois en ligne.", 13, DIM));
        col.addView(space(8));
        ViewGroup r = buttonRow();
        r.addView(small("Synchroniser les succès", v -> background(() -> {
            progress("Communication avec Steam");
            java.util.List<String> got = steam().sync();
            return got.isEmpty() ? "Les succès Steam sont à jour." : "Envoyés à Steam : " + String.join(", ", got);
        })));
        r.addView(small("Se déconnecter", v -> ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Se déconnecter de Steam ? Les succès Steam et les sauvegardes cloud s'arrêtent jusqu'à ce que vous vous reconnectiez.")
                .setPositiveButton("Se déconnecter", (d, x) -> { steam().signOut(); refresh(); })
                .setNegativeButton("Annuler", null)
                .show())));
        col.addView(r);

        col.addView(space(16));
        col.addView(heading("Sauvegardes Steam Cloud"));
        CheckBox cs = new CheckBox(this);
        cs.setText("Synchroniser mes sauvegardes avec Steam Cloud" + "\n" + "Vos fichiers de sauvegarde vont dans le Steam Cloud de FINAL FANTASY VII avant "
                + "et après chaque partie, pour reprendre là où vous en étiez sur un autre appareil connecté à ce compte "
                + "Steam. Une sauvegarde sur le point d'être remplacée est d'abord copiée.");
        cs.setChecked(prefs.getBoolean("cloud_saves", false));
        cs.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("cloud_saves", on).apply();
            if (on) background(() -> {
                progress("Synchronisation des sauvegardes avec Steam Cloud");
                String m = syncCloudSaves();
                return m != null ? m : "Dernière synchro " + prefs.getString("cloud_last", "");
            });
            else refresh();
        });
        col.addView(cs);
        // where: the PC game's own saves (the same files: SteamFiles.PC_FOLDER) or FF7Recomp's folder
        RadioGroup where = new RadioGroup(this);
        String[][] whereOpts = {
            { "Avec le jeu PC aussi", "Sauvegarde 1 à 10 sont les mêmes fichiers que ceux de la version PC (FINAL FANTASY VII sur Steam)" },
            { "Appareils Android uniquement", "Gardées dans leur propre dossier FF7Recomp dans Steam Cloud ; les sauvegardes du jeu PC ne sont pas touchées" } };
        boolean withPc = prefs.getBoolean("cloud_with_pc", true);
        for (int k = 0; k < 2; k++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            android.text.SpannableString label = new android.text.SpannableString(whereOpts[k][0] + "   " + whereOpts[k][1]);
            int at = whereOpts[k][0].length();
            label.setSpan(new android.text.style.ForegroundColorSpan(DIM), at, label.length(), 0);
            label.setSpan(new android.text.style.RelativeSizeSpan(0.85f), at, label.length(), 0);
            rb.setText(label);
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            final boolean pc = k == 0;
            rb.setOnClickListener(v -> {
                if (pc == prefs.getBoolean("cloud_with_pc", true)) return;
                prefs.edit().putBoolean("cloud_with_pc", pc).remove("cloud_last").apply();
                if (prefs.getBoolean("cloud_saves", false)) background(() -> {
                    progress("Synchronisation des sauvegardes avec Steam Cloud");
                    String m = syncCloudSaves();
                    return m != null ? m : "Dernière synchro " + prefs.getString("cloud_last", "");
                });
                else refresh();
            });
            where.addView(rb);
            if (pc == withPc) rb.setChecked(true);
        }
        col.addView(where);
        col.addView(text("Avec le jeu PC : une sauvegarde modifiée d'un seul côté passe de l'autre. La première fois, un fichier de sauvegarde "
                + "différent des deux côtés garde le plus récent, et l'autre copie est sauvegardée dans "
                + new File(install.files, "steam/cloud-backup").getAbsolutePath() + ". Le PC récupère les sauvegardes la prochaine fois que FINAL "
                + "FANTASY VII y démarre (avec Steam Cloud activé).", 13, DIM));
        if (prefs.getBoolean("cloud_saves", false)) {
            String last = prefs.getString("cloud_last", null);
            if (last != null) col.addView(text("Dernière synchro " + last, 13, DIM));
            col.addView(space(6));
            col.addView(small("Synchroniser maintenant", v -> background(() -> {
                progress("Synchronisation des sauvegardes avec Steam Cloud");
                String m = syncCloudSaves();
                return m != null ? m : "Dernière synchro " + prefs.getString("cloud_last", "");
            })));
        }
    }

    /** Download (or repair) the game from Steam: GameInstall.downloadFromSteam. */
    private void steamDownload() {
        if (busy) return;
        if (!steam().signedIn()) { steamSignIn(this::steamDownload); return; }
        startJob(JobService.STEAM, null);
    }

    /** Cloud saves (the Steam page): before Play and after the game. */
    private boolean cloudSaves() { return steam().signedIn() && prefs.getBoolean("cloud_saves", false); }

    /** Syncs the saves with Steam Cloud; returns what changed (for a message) or null when nothing did. */
    private String syncCloudSaves() {
        try {
            SteamFiles.SyncResult r = new SteamFiles(steam()).syncSaves(new File(install.game, "save"), prefs.getBoolean("cloud_with_pc", true));
            prefs.edit().putString("cloud_last", new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date()) + ": " + r).apply();
            return r.up.isEmpty() && r.down.isEmpty() ? null : r.toString();
        } catch (Exception e) {
            android.util.Log.w("FF7SteamFiles", "cloud saves: " + SteamLink.message(e));
            prefs.edit().putString("cloud_last", "non synchronisé : " + SteamLink.message(e)).apply();
            return null;
        }
    }

    private void steamSignIn() { steamSignIn(null); }

    /** then: runs after a successful sign-in (instead of the "signed in" message). */
    private void steamSignIn(Runnable then) {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(12), dp(16), dp(4));
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView how = text("Sur votre téléphone : ouvrez l'appli Steam, touchez le bouclier Steam Guard, puis \"Scanner un QR code\", "
                + "et approuvez la connexion.", 15, TEXT);
        box.addView(how);
        ImageView qr = new ImageView(this);
        qr.setBackgroundColor(Color.WHITE);
        // The dialog's title, text, status line and Cancel button take about 220 dp. On a short
        // landscape screen (a 1080p handheld is 360-450 dp tall) a fixed 260 dp code pushed the
        // bottom of the code off the dialog (#19), so the code shrinks to fit the screen's height.
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = Math.max(dp(150), Math.min(dp(260), Math.min(dm.widthPixels, dm.heightPixels) - dp(220)));
        box.addView(qr, new LinearLayout.LayoutParams(size, size));
        TextView st = text("Connexion à Steam…", 13, DIM);
        box.addView(st);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);   // never cut off
        scroll.addView(box);
        final java.util.concurrent.Future<?>[] cancel = new java.util.concurrent.Future<?>[1];
        final boolean[] open = { true };
        AlertDialog dlg = ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Se connecter à Steam")
                .setView(scroll)
                .setNegativeButton("Annuler", null)
                .show());
        dlg.setOnDismissListener(d -> {
            open[0] = false;
            if (cancel[0] != null) cancel[0].cancel(true);
        });
        new Thread(() -> steam().signInQr(new SteamLink.QrListener() {
            @Override public void qr(String url) {
                android.graphics.Bitmap bm = qrBitmap(url, size);
                runOnUiThread(() -> { qr.setImageBitmap(bm); st.setText("En attente de l'appli Steam…"); });
            }
            @Override public void done(String error) {
                runOnUiThread(() -> {
                    if (open[0]) dlg.dismiss();
                    refresh();
                    if (error == null && then != null) {
                        then.run();
                    } else if (error == null) {
                        int[] c = steam().counts();
                        toastDialog("Connecté à Steam en tant que " + steam().account() + "."
                                + (c != null ? " " + c[0] + " sur " + c[1] + " succès de FINAL FANTASY VII débloqués." : ""));
                    } else if (then != null && error.startsWith("connecté") && steam().signedIn()) {
                        then.run();   // signed in; only the achievements could not be read yet
                    } else if (!error.equals("cancelled")) {
                        toastDialog(error.startsWith("connecté") ? "C" + error.substring(1) : "Non connecté : " + error);
                    }
                });
            }
        }, cancel), "steam-sign-in").start();
    }

    private static android.graphics.Bitmap qrBitmap(String text, int size) {
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            return android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    /** PlayStation memory cards picked on the Save states page, each into its own GAME file. */
    private String importPsx(Intent data) throws java.io.IOException {
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null)
            for (int k = 0; k < data.getClipData().getItemCount(); k++) uris.add(data.getClipData().getItemAt(k).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        StringBuilder msg = new StringBuilder();
        File saveDir = new File(install.game, "save");
        for (Uri u : uris) {
            String label = uris.size() > 1 ? displayName(u) + ": " : "";
            progress("Lecture des sauvegardes");
            byte[] b;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                if (in == null) throw new java.io.IOException("impossible d'ouvrir le fichier");
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) {
                    o.write(buf, 0, n);
                    if (o.size() > PsxSaves.MAX_ZIP) break;
                }
                b = o.toByteArray();
            } catch (java.io.IOException e) {
                msg.append(label).append("Non importé : ").append(e.getMessage()).append("\n");
                continue;
            }
            if (PsxSaves.isZip(b)) {
                // a zip (Export saves', a problem report, a pack from a website): each save in it
                if (b.length > PsxSaves.MAX_ZIP) { msg.append(label).append("Non importé : ce zip est trop gros pour un pack de sauvegardes\n"); continue; }
                java.util.List<PsxSaves.Entry> es;
                try { es = PsxSaves.unzip(b); }
                catch (java.io.IOException e) { msg.append(label).append("Non importé : ").append(e.getMessage()).append("\n"); continue; }
                int found = 0;
                for (PsxSaves.Entry e : es) {
                    boolean likely = PsxSaves.saveName(e.name);
                    if (e.data == null) {
                        if (likely) msg.append(e.name).append(" : Non importé : trop gros pour être une sauvegarde\n");
                        continue;
                    }
                    if (!likely && !PsxSaves.isPcSave(e.data)) continue;   // a readme, a log...
                    found++;
                    msg.append(e.name).append(": ").append(importOne(e.data, saveDir)).append("\n");
                }
                if (found == 0) msg.append(label).append("Non importé : ce zip ne contient aucune sauvegarde de FINAL FANTASY VII\n");
                continue;
            }
            if (b.length > PsxSaves.MAX_FILE) { msg.append(label).append("Non importé : ce fichier est trop gros pour être une sauvegarde ou une carte mémoire\n"); continue; }
            msg.append(label).append(importOne(b, saveDir)).append("\n");
        }
        return msg.toString().trim();
    }

    /** One save file or memory card into the first free save file; a line saying where it went. */
    private static String importOne(byte[] b, File saveDir) {
        try {
            PsxSaves.Result r = PsxSaves.importAny(b, saveDir);
            String game = "Sauvegarde " + r.gameNumber;
            return r.saves + (r.saves == 1 ? " sauvegarde" : " sauvegardes") + " importée(s) dans " + game
                    + ". En jeu : Continuer, puis choisissez " + game + ".";
        } catch (java.io.IOException e) {
            return "Non importé : " + e.getMessage();
        }
    }

    private String displayName(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {}
        String p = u.getLastPathSegment();
        return p == null ? "file" : p.substring(p.lastIndexOf('/') + 1);
    }

    /** Export saves (FF9 parity): game/save/*.ff7 in one zip, saved to Downloads, then the share sheet. */
    private void exportSaves() {
        if (busy) return;
        background(() -> {
            progress("Préparation des sauvegardes");
            File zip = PsxSaves.exportZip(new File(install.game, "save"), new File(getCacheDir(), "export"));
            Uri uri = Reports.toDownloads(this, zip);
            int n = 0;
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip)) { n = z.size(); }
            final String name = zip.getName();
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, "Sauvegardes FF7Recomp")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(android.content.ClipData.newRawUri(name, uri));
                try { startActivity(Intent.createChooser(send, "Envoyer les sauvegardes")); } catch (Exception ignored) {}
            });
            return "Enregistré dans Téléchargements : " + name + " (" + n + (n == 1 ? " fichier de sauvegarde" : " fichiers de sauvegarde")
                    + "). Importer des sauvegardes reprend ce zip, ici ou sur un autre appareil.";
        });
    }

    // ------------------------------------------------------------------- save states
    /** The achievements the game tracks (src/ffnx_port/achievements.cpp), offline for now. */
    private View achievementsPage() {
        Achievements ach = new Achievements(install.files);
        LinearLayout col = column();
        col.addView(heading("Steam"));
        steamSection(col);
        col.addView(space(18));
        col.addView(heading("Succès"));
        col.addView(text("Débloqués pendant que vous jouez ; une notification s'affiche en jeu. C'est une liste locale, gardée sur cet appareil : "
                + "les comptes RetroAchievements et leur liste officielle nécessitent d'abord que RetroAchievements approuve ce portage.", 14, DIM));
        if (!ach.haveSet) {
            col.addView(text("\nLancez le jeu une fois pour les mettre en place.", 16, DIM));
            return scroll(col);
        }
        col.addView(text(ach.unlockedCount() + " sur " + ach.list.size() + " débloqués  ·  " + ach.points(true) + " sur "
                + ach.points(false) + " points", 16, ACCENT));
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
        for (Achievements.Ach a : ach.list) {
            LinearLayout r = column();
            r.setPadding(0, dp(8), 0, dp(8));
            boolean on = a.unlockedAt != 0;
            r.addView(text((on ? "★ " : "☆ ") + a.title + "  (" + a.points + ")", 18, on ? TEXT : DIM));
            r.addView(text(a.description + (on ? "  ·  débloqué le " + df.format(new java.util.Date(a.unlockedAt * 1000)) : ""), 13, DIM));
            col.addView(r);
        }
        return scroll(col);
    }

    // ------------------------------------------------------------------ report a problem
    private boolean reportSaves = true, reportState = true, reportBusy;
    private String reportDone;   // the last zip's name and size, as saved to Downloads

    private View reportPage() {
        LinearLayout col = column();
        col.addView(heading("Signaler un problème"));
        col.addView(text("Un zip avec ce qu'il faut pour examiner un problème : les journaux du jeu, sa dernière fermeture, votre "
                + "appareil, vos paramètres et votre liste de mods. Ni vos fichiers du jeu, ni vos mods, ni votre connexion Steam. Rien n'est envoyé "
                + "nulle part tant que vous ne le partagez pas.", 14, DIM));
        String exit = Reports.lastGameExit(this);
        if (exit != null) { col.addView(space(4)); col.addView(text("Dernière fermeture du jeu : " + exit, 14, TEXT)); }
        col.addView(space(4));
        col.addView(text(Reports.RECOMMENDED, 13, DIM));
        col.addView(space(4));
        java.util.List<File> saves = Reports.saves(this);
        CheckBox s = new CheckBox(this);
        s.setText(saves.isEmpty() ? "Vos sauvegardes (aucune pour l'instant)" : "Vos sauvegardes (" + saves.size() + (saves.size() == 1 ? " fichier)" : " fichiers)"));
        s.setEnabled(!saves.isEmpty());
        s.setChecked(reportSaves && !saves.isEmpty());
        s.setOnCheckedChangeListener((b, on) -> reportSaves = on);
        col.addView(s);
        File st = Reports.latestState(this);
        CheckBox t = new CheckBox(this);
        t.setText(st == null ? "Votre état de sauvegarde le plus récent (aucun pour l'instant)"
                : "Votre état de sauvegarde le plus récent : " + st.getName().replace(".ff7s", "") + ", "
                  + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date(st.lastModified()))
                  + " (" + Math.max(1, st.length() >> 20) + " Mo)");
        t.setEnabled(st != null);
        t.setChecked(reportState && st != null);
        t.setOnCheckedChangeListener((b, on) -> reportState = on);
        col.addView(t);
        col.addView(text("Les sauvegardes permettent de rejouer le problème là où il se produit. Un zip joint sur GitHub est public : "
                + "décochez-les, ou partagez le zip en privé, si vous préférez.", 13, DIM));
        col.addView(space(6));
        ViewGroup row = buttonRow();
        Button save = button(reportBusy ? "Préparation…" : "Enregistrer dans Téléchargements", v -> makeReport(false));
        Button share = button("Partager…", v -> makeReport(true));
        save.setEnabled(!reportBusy);
        share.setEnabled(!reportBusy);
        row.addView(save);
        row.addView(share);
        col.addView(row);
        if (reportDone != null) col.addView(text(reportDone, 15, ACCENT));
        col.addView(space(10));
        col.addView(text("Ouvrez ensuite un ticket sur GitHub (les lignes sur l'appareil sont remplies) et faites glisser le zip depuis Téléchargements "
                + "dans la zone de commentaire. Sur un autre appareil, copiez d'abord le zip.", 14, DIM));
        col.addView(button("Ouvrir un ticket GitHub", v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Reports.issueUrl(this))); }
            catch (Exception e) { toastDialog("Aucun navigateur pour ouvrir " + Reports.ISSUES); }
        }));
        return scroll(col);
    }

    /** Builds the zip off the UI thread, saves it to Downloads, then shares it (share: true). */
    private void makeReport(boolean share) {
        if (reportBusy) return;
        reportBusy = true;
        reportDone = null;
        refresh();
        final boolean saves = reportSaves, state = reportState;
        new Thread(() -> {
            String done;
            Uri uri = null;
            File zip = null;
            try {
                zip = Reports.build(this, saves, state);
                uri = Reports.toDownloads(this, zip);
                done = "Enregistré dans Téléchargements : " + zip.getName() + " (" + String.format(java.util.Locale.US, "%.1f Mo", zip.length() / 1048576.0) + ")";
            } catch (Exception e) {
                done = "Impossible de créer le rapport : " + e.getMessage();
            }
            final String msg = done;
            final Uri u = uri;
            final String name = zip == null ? "" : zip.getName();
            runOnUiThread(() -> {
                reportBusy = false;
                reportDone = msg;
                if (page.equals("report")) {
                    refresh();
                    if (content.getChildAt(0) instanceof ScrollView) {   // the result line in view
                        ScrollView sv = (ScrollView) content.getChildAt(0);
                        sv.post(() -> sv.smoothScrollTo(0, Math.max(0, sv.getChildAt(0).getHeight() - sv.getHeight())));
                    }
                }
                if (share && u != null) {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, u)
                            .putExtra(Intent.EXTRA_SUBJECT, "FF7Recomp problem report")
                            .putExtra(Intent.EXTRA_TEXT, "FF7Recomp " + Reports.version(this) + " on " + Reports.device())
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    send.setClipData(android.content.ClipData.newRawUri(name, u));
                    try { startActivity(Intent.createChooser(send, "Envoyer le rapport")); }
                    catch (Exception e) { toastDialog("Rien sur cet appareil ne peut partager un fichier"); }
                }
            });
        }).start();
    }

    private View statesPage() {
        if (states == null) states = new SaveStates(install.files);
        LinearLayout col = column();
        col.addView(heading("États de sauvegarde"));
        col.addView(text("En jeu : Select+L1 sauvegarde dans l'emplacement actuel, Select+R1 le charge, Select+Gauche / Droite "
                + "choisit un autre emplacement. \"Jouer d'ici\" démarre le jeu à ce moment.", 14, DIM));
        col.addView(space(10));
        ViewGroup saveButtons = buttonRow();
        saveButtons.addView(small("Importer des sauvegardes…", v -> pick(PICK_PSX)));
        saveButtons.addView(small("Exporter les sauvegardes", v -> exportSaves()));
        col.addView(saveButtons);
        col.addView(text("Exporter les sauvegardes met tous vos fichiers de sauvegarde (Sauvegarde 1-10) dans un zip dans Téléchargements, pour les garder ou les "
                + "déplacer vers un autre appareil. Importer des sauvegardes prend plusieurs fichiers à la fois, ou un zip.", 13, DIM));
        col.addView(text("PC : un fichier de sauvegarde (save00.ff7…save09.ff7) de la version Steam actuelle (dans "
                + "%LOCALAPPDATA%\\FINAL FANTASY VII Steam Edition sur le PC ; les sauvegardes Steam Cloud de la page Succès "
                + "peuvent aussi les partager), de la version Steam 2013 (Documents\\Square Enix\\FINAL FANTASY VII Steam\\user_…), "
                + "de la version PC d'origine ou d'une installation 7th Heaven. PlayStation : une carte mémoire de DuckStation (.mcd), ePSXe (.mcr), "
                + "RetroArch (.srm), DexDrive (.gme) ou PSP (.vmp) ; DuckStation garde ses cartes dans "
                + "Android/data/com.github.stenzek.duckstation/files/memcards. Copiez d'abord le fichier là où vous pouvez le choisir "
                + "(Download, par exemple). Les sauvegardes vont dans un nouveau fichier de sauvegarde (Sauvegarde 2, Sauvegarde 3… dans le jeu), donc "
                + "aucune des vôtres n'est écrasée.", 13, DIM));
        col.addView(space(10));
        int cur = states.current();
        for (int n = 1; n <= SaveStates.SLOTS; n++) {
            SaveStates.Slot sl = states.read(n);
            ViewGroup bs = buttonRow();
            LinearLayout info = column();
            info.addView(text("Emplacement " + n + (n == cur ? "  (actuel)" : ""), smallScreen ? 16 : 18, TEXT));
            String sub;
            if (!sl.exists) sub = "Vide";
            else if (!sl.readable) sub = "D'une ancienne version de l'appli (ne peut pas être chargé)";
            else sub = (sl.location.isEmpty() ? "?" : sl.location) + "  ·  " + sl.playTime() + "  ·  sauvegardé " + sl.when;
            info.addView(text(sub, 13, DIM));
            final int k = n;
            if (sl.readable && install.hasGame()) bs.addView(small("Jouer d'ici", v -> playFrom(k)));
            if (n != cur) bs.addView(small("Rendre actuel", v -> {
                try { states.setCurrent(k); } catch (Exception e) { toastDialog("Impossible de définir l'emplacement : " + e); }
                refresh();
            }));
            if (sl.exists) bs.addView(small("Supprimer", v -> ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Supprimer l'emplacement d'état de sauvegarde " + k + " ? C'est irréversible.")
                    .setPositiveButton("Supprimer", (d, w) -> { states.delete(k); refresh(); })
                    .setNegativeButton("Annuler", null)
                    .show())));
            LinearLayout r = details(info, bs);
            r.setPadding(0, dp(6), 0, dp(6));
            col.addView(r);
        }
        return scroll(col);
    }

    /** Play, resuming save-state slot n (this launch only; the Start setting is unchanged). */
    private void playFrom(int n) {
        play("resume " + n);
    }

    private void play() { play(null); }

    private void play(String quickbootOverride) {
        if (busy || !install.hasGame()) return;
        if (!ready()) { show("game"); return; }
        background(() -> {
            progress("Préparation");
            String missing = install.missingMessage();
            if (missing != null) return missing;
            library.load();
            install.gameLanguage = prefs.getInt("game_language", 1);
            library.language = install.hasLanguage(install.gameLanguage) ? GameInstall.languageCode(install.gameLanguage) : "en";
            library.save();
            install.installRuntime();
            install.fixFontWidths();   // the current release's narrow m/w widths (GameInstall)
            boolean steamOn = false;
            if (steam().signedIn()) {
                progress("Vérification des succès Steam");
                try { steam().sync(); } catch (Exception e) { android.util.Log.w("FF7Steam", "offline: " + SteamLink.message(e)); }
                steamOn = steam().active();
            }
            install.steamAchievements = steamOn;
            if (cloudSaves()) {
                progress("Synchronisation des sauvegardes avec Steam Cloud");
                syncCloudSaves();
                cloudAfterGame = true;
            }
            FrameGen.write(install.files, prefs.getBoolean("framegen", false) && FrameGen.useful(prefs.getInt("fps", 3)) && FrameGen.dll(install.files).isFile());
            install.crtFilter = prefs.getBoolean("crt_filter", false);
            install.analogControls = prefs.getBoolean("analog_controls", false);
            install.resolutionScale = prefs.getInt("resolution", 0);
            install.openGl = prefs.getInt("renderer", 0) == 1;
            install.ffnxOptions = FfnxOptions.chosen(prefs);
            int aspect = prefs.getInt("aspect", 2);   // 3: 16:9 stretched by Android (GameActivity), FFNx renders 16:9
            install.writeFfnxToml(prefs.getInt("fps", 3) == 5 ? 3 : prefs.getInt("fps", 3), boosterSpeed() > 1, aspect == 3 ? 2 : aspect, prefs.getBoolean("show_fps", false), library.mods,
                                  renderScaleCap());
            install.writeModCacheLimit(prefs.getInt("modcache_limit_mb", 0));   // #75
            prefs.edit().putBoolean("modcache_used", true).apply();
            install.writeQuickboot(quickbootOverride != null ? quickbootOverride : prefs.getString("quickboot", ""));
            install.writeBoosters(boosterSpeed(), prefs.getBoolean("no_encounters", false), prefs.getBoolean("battle_plus", false),
                                  prefs.getInt("perf", 1) != 2, prefs.getBoolean("save_anywhere", false),
                                  prefs.getInt("gil", 1), prefs.getInt("exp", 1), prefs.getInt("ap", 1),
                                  prefs.getBoolean("autofire", false), prefs.getInt("rumble", 0),
                                  prefs.getBoolean("always_run", false));
            install.writeCompanion("off");   // battle windows stay on the top screen
            runOnUiThread(() -> startActivity(new Intent(this, GameActivity.class)
                    .putExtra("touch_controls", prefs.getInt("touch_controls", 0))
                    .putExtra("touch_layout", prefs.getString("touch_layout", ""))
                    .putExtra("touch_layout_square", TouchControls.savedLayout(prefs, "touch_layout_square"))   // #66
                    .putExtra("touch_hidden", prefs.getString("touch_hidden", ""))   // #88
                    .putExtra("touch_extra", prefs.getBoolean("touch_extra", false))
                    .putExtra("portrait", prefs.getBoolean("portrait", false))
                    .putExtra("split", prefs.getBoolean("split", false))
                    .putExtra("side", prefs.getBoolean("side", false))
                    .putExtra("side_left", prefs.getBoolean("side_left", false))
                    .putExtra("panel_button", prefs.getBoolean("panel_button", false))
                    .putExtra("panel_key", prefs.getInt("panel_key", 0))   // #87
                    .putExtra("rotation_lock", prefs.getInt("rotation_lock", 0))
                    .putExtra("bottom_screen", prefs.getBoolean("bottom_screen", true))
                    .putExtra("bottom_buttons", prefs.getBoolean("bottom_buttons", true))
                    .putExtra("game_art", prefs.getBoolean("game_art", false))
                    .putExtra("crt_filter", prefs.getBoolean("crt_filter", false))
                    .putExtra("aspect", prefs.getInt("aspect", 2))
                    .putExtra("fps", prefs.getInt("fps", 3)), gameDisplayOptions()));
            return null;
        });
    }

    /** Swap screens (Settings > Screen, GitHub #41): the game starts on the second display and
     *  GameActivity puts the panels on the main one. Null (this display) otherwise. */
    private android.os.Bundle gameDisplayOptions() {
        android.view.Display d = swapDisplay();
        if (d == null) return null;
        android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
        o.setLaunchDisplayId(d.getDisplayId());
        return o.toBundle();
    }

    /** The display the game goes to when the screens are swapped, or null when they are not. */
    private android.view.Display swapDisplay() {
        if (!prefs.getBoolean("swap_screens", false) || !prefs.getBoolean("bottom_screen", true)) return null;
        android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        android.view.Display[] ds = dm.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return ds.length > 0 ? ds[0] : null;
    }

    /** The start speed (1-3); older settings had only the 2x "turbo" switch. */
    /** Performance's render scale cap: the 640x480 multiple nearest the screen (Balanced; 2 on
     *  a 1080p Thor, 2 on a 720p device, 3 at 1440p), one less (Cooler), none (Best). Rounding
     *  up drew 2560x1440 for the Thor's 1080p screen: 1.8x the pixels it shows (GPU 71% busy
     *  in the field at 120 FPS with ChaOS). */
    private int renderScaleCap() {
        int perf = prefs.getInt("perf", 1);
        if (perf == 2) return 0;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        android.view.Display gd = swapDisplay();   // the screen the game will be on
        (gd != null ? gd : getWindowManager().getDefaultDisplay()).getRealMetrics(dm);
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        int match = Math.max(1, (shortSide + 240) / 480);
        return perf == 0 ? Math.max(1, match - 1) : match;
    }

    /** Settings > Controller: one row per button (ButtonMap), and a way back to the usual layout. */
    private void fillButtonMap(LinearLayout maps) {
        maps.removeAllViews();
        final int[] map = ButtonMap.read(prefs);
        for (int i = 0; i < ButtonMap.NAMES.length; i++) {
            final int b = i;
            maps.addView(button(ButtonMap.NAMES[b] + (map[b] == b ? "" : "  agit comme  " + ButtonMap.NAMES[map[b]]), v ->
                    new android.app.AlertDialog.Builder(this).setTitle(ButtonMap.NAMES[b] + " agit comme")
                            .setSingleChoiceItems(ButtonMap.NAMES, map[b], (d, which) -> {
                                ButtonMap.choose(prefs, b, which);
                                d.dismiss();
                                fillButtonMap(maps);
                            }).setNegativeButton("Annuler", null).show()));
        }
        maps.addView(button("Réinitialiser les boutons", v -> { ButtonMap.reset(prefs); fillButtonMap(maps); }));
    }

    private int boosterSpeed() {
        return prefs.getInt("speed", prefs.getBoolean("turbo", false) ? 2 : 1);
    }

    private void saveProfile() {
        try { library.save(); } catch (Exception e) { toastDialog("Impossible d'enregistrer la liste des mods : " + e); }
    }

    /** Nothing is using the files now (a build, a download, the game), else says so and returns false. */
    private boolean canMoveStorage() {
        if (busy) return false;
        if (GameBuild.running) { toastDialog("Non déplacé : le jeu est en cours de compilation. Attendez la fin de la compilation."); return false; }
        if (JobService.running != null || JobService.pending(this) != null) { toastDialog("Non déplacé : terminez ou annulez d'abord le téléchargement ou l'importation sur la page Fichiers du jeu."); return false; }
        if (sQueue != null && sQueue.pendingCount() > 0) { toastDialog("Non déplacé : des mods sont en cours de téléchargement. Attendez-les, ou annulez-les, d'abord."); return false; }
        if (TestControl.gamePid(this) > 0) { toastDialog("Non déplacé : fermez d'abord le jeu."); return false; }
        return true;
    }

    /** Every per-process object points at the old folder: start the launcher afresh. */
    private void restartLauncher() {
        startActivity(Intent.makeRestartActivityTask(new android.content.ComponentName(this, LauncherActivity.class)));
        Runtime.getRuntime().exit(0);
    }

    /** Settings > Storage: move everything to internal storage, the SD card or a custom folder, then restart the launcher. */
    private void moveStorage(int place, File custom) {
        if (!canMoveStorage()) return;
        long size = Storage.size(install.files);
        String where = place == Storage.CUSTOM ? custom.getAbsolutePath() : place == Storage.SD ? "la carte SD" : "le stockage interne";
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Déplacer vos fichiers du jeu, mods, sauvegardes et états de sauvegarde (" + ModDownloader.human(size) + ") vers "
                        + where + " ? Le lanceur redémarre une fois terminé.")
                .setPositiveButton("Déplacer", (d, w) -> background(() -> {
                    Storage.move(this, place, custom, this::progress);
                    runOnUiThread(this::restartLauncher);
                    return null;
                }))
                .setNegativeButton("Annuler", null)
                .show());
    }

    /** Settings > Storage > Custom folder (GitHub #61): All files access first, then the folder. */
    private void customStorage() {
        if (!canMoveStorage()) return;
        if (Storage.allFilesAccess(this)) { pick(PICK_STORAGE); return; }
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Pour garder vos fichiers dans un dossier de votre choix, FF7Recomp a besoin de l'\"Accès à tous les fichiers\" d'Android "
                        + "(sans lui, les applis ne peuvent utiliser que leur propre dossier Android/data). Activez-le pour FF7Recomp sur "
                        + "l'écran suivant, puis revenez et choisissez le dossier.")
                .setPositiveButton("Continuer", (d, w) -> {
                    wantStorageFolder = true;
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    } else {
                        requestPermissions(new String[] { android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                android.Manifest.permission.READ_EXTERNAL_STORAGE }, 10);
                    }
                })
                .setNegativeButton("Annuler", null)
                .show());
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 10 && wantStorageFolder) storageAccessBack();
    }

    /** The folder picked for Settings > Storage > Custom folder. */
    private void pickedStorageFolder(Uri tree) {
        File to = Storage.treeToPath(tree);
        String why = Storage.customRefusal(this, to);
        if (why != null) { toastDialog("Non déplacé. " + why); return; }
        if (to.isDirectory() && !Storage.emptyEnough(to)) {
            if (!Storage.looksLikeOurs(to)) {
                toastDialog("Non déplacé : " + to.getAbsolutePath() + " contient déjà d'autres fichiers. Choisissez un dossier vide, "
                        + "ou créez-en un nouveau dans le sélecteur de dossier.");
                return;
            }
            ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(to.getAbsolutePath() + " contient déjà les fichiers de FF7Recomp (une sauvegarde, ou une copie synchronisée). "
                            + "Utiliser ces fichiers ? Vos fichiers actuels restent où ils sont ("
                            + install.files.getAbsolutePath() + ") ; supprimez-les vous-même quand vous n'en aurez plus besoin. "
                            + "Le lanceur redémarre.")
                    .setPositiveButton("Les utiliser", (d, w) -> {
                        try { Storage.useCustom(this, to); restartLauncher(); }
                        catch (java.io.IOException e) { toastDialog("Non modifié : " + e.getMessage()); }
                    })
                    .setNegativeButton("Annuler", null)
                    .show());
            return;
        }
        moveStorage(Storage.CUSTOM, to);
    }

    // ------------------------------------------------------------- Sound, Game options
    /** Settings > Sound and Game options: FFNx.toml keys (FfnxOptions; FF9Recomp's Memoria page). */
    private void ffnxSections(LinearLayout col) {
        java.util.Map<String, String> last = lastToml();
        col.addView(heading("Son"));
        col.addView(text("Les volumes propres du jeu, sous le volume de l'appareil. Par défaut : comme le jeu ou un mod le règle.", 14, DIM));
        for (FfnxOptions.Opt o : FfnxOptions.SOUND) ffnxRow(col, o, last);
        col.addView(space(16));
        col.addView(heading("Options du jeu"));
        col.addView(text("Paramètres FFNx (son FFNx.toml, écrit avant chaque démarrage). Un mod peut aussi les régler ; ce que vous "
                + "changez ici l'emporte. Une case cochée montre comment le jeu a démarré la dernière fois.", 14, DIM));
        for (FfnxOptions.Opt o : FfnxOptions.GAME) ffnxRow(col, o, last);
        col.addView(small("Remettre Son et Options du jeu par défaut", v -> {
            SharedPreferences.Editor e = prefs.edit();
            for (FfnxOptions.Opt o : FfnxOptions.all()) e.remove(FfnxOptions.PREFIX + o.key);
            e.apply();
            refresh();
        }));
        col.addView(space(16));
    }

    private void ffnxRow(LinearLayout col, FfnxOptions.Opt o, java.util.Map<String, String> last) {
        final String key = FfnxOptions.PREFIX + o.key;
        if (o.isSwitch()) {
            CheckBox cb = new CheckBox(this);
            cb.setText(o.label + (o.help == null ? "" : "\n" + o.help));
            cb.setTextColor(TEXT);
            cb.setTextSize(16);
            String v = prefs.getString(key, null);
            if (v == null) v = last.get(o.key);
            cb.setChecked(v == null ? o.defOn : v.equals("true"));
            cb.setOnCheckedChangeListener((b, on) -> prefs.edit().putString(key, on ? "true" : "false").apply());
            col.addView(cb);
            return;
        }
        // a volume: - and + step it, the middle button puts it back to Default
        final int[] at = { FfnxOptions.index(o, prefs.getString(key, null)) };
        final int full = FfnxOptions.index(o, "100");   // where Default stands
        ViewGroup r = buttonRow();
        final Button mid = small(o.label + ": " + o.names[at[0]], null);
        Runnable store = () -> {
            if (at[0] == 0) prefs.edit().remove(key).apply(); else prefs.edit().putString(key, o.values[at[0]]).apply();
            mid.setText(o.label + ": " + o.names[at[0]]);
        };
        r.addView(compact(small("−", v -> { at[0] = Math.min(o.values.length - 1, (at[0] == 0 ? full : at[0]) + 1); store.run(); })));
        mid.setOnClickListener(v -> { at[0] = 0; store.run(); });
        r.addView(mid);
        r.addView(compact(small("+", v -> { at[0] = Math.max(1, (at[0] == 0 ? full : at[0]) - 1); store.run(); })));
        if (o.help != null) r.addView(text(o.help, 13, DIM));
        col.addView(r);
    }

    /** FFNx.toml as the game last started with it: key -> value (quotes off). */
    private java.util.Map<String, String> lastToml() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        File f = new File(install.game, "FFNx.toml");
        if (!f.isFile()) return m;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("^([A-Za-z0-9_]+)\\s*=\\s*(.*?)\\s*$");
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                java.util.regex.Matcher mm = p.matcher(line);
                if (mm.matches()) m.put(mm.group(1), mm.group(2).replace("\"", ""));
            }
        } catch (java.io.IOException ignored) {}
        return m;
    }

    // ------------------------------------------------------------------- plumbing
    private interface Job { String run() throws Exception; }

    private boolean warmingMods;

    /** Read the mods refresh() could not, off the UI thread, then refresh again. */
    private void warmMods() {
        if (warmingMods) return;
        warmingMods = true;
        new Thread(() -> {
            long t0 = android.os.SystemClock.uptimeMillis();
            try { library.warm(); } catch (Throwable t) { android.util.Log.w("FF7Recomp", "mod info: " + t); }
            android.util.Log.i("FF7Recomp", "mod info read in the background: " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
            runOnUiThread(() -> {
                warmingMods = false;
                if (!isFinishing() && !isDestroyed()) refresh();
            });
        }, "mod-info").start();
    }

    private void background(Job job) {
        busy = true;
        playButton.setEnabled(false);
        new Thread(() -> {
            String msg;
            try { msg = job.run(); } catch (Exception e) { msg = "Échec : " + e.getMessage(); }
            final String m = msg;
            runOnUiThread(() -> {
                busy = false;
                refresh();
                if (m != null) toastDialog(m);
            });
        }).start();
    }

    private void progress(String what) {
        runOnUiThread(() -> status.setText(what + "…"));
    }

    /** Quit (GitHub #42): the launcher closes; a game still running is closed too, if you say so. */
    private void quitApp() {
        int pid = TestControl.gamePid(this);
        if (pid <= 0) { finishAndRemoveTask(); return; }
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("Le jeu tourne encore. Le fermer aussi ? Tout ce qui s'est passé depuis votre dernière sauvegarde est perdu.")
                .setPositiveButton("Fermer les deux", (d, w) -> {
                    android.os.Process.killProcess(pid);
                    finishAndRemoveTask();
                })
                .setNegativeButton("Annuler", null).show());
    }

    private void toastDialog(String msg) {
        if (msg.startsWith("Failed") || msg.startsWith("Not ") || msg.startsWith("Échec") || msg.startsWith("Non ") || msg.startsWith("Impossible")) sound(Sfx.ERROR);
        ff(new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(msg).setPositiveButton("OK", null).show());
    }

    /** The pad: A confirms, B goes back, Start plays (D-pad focus navigation is Android's). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (isNav(code)) lastNavKey = System.currentTimeMillis();
            else if ((code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK) && e.getRepeatCount() == 0) sound(Sfx.CANCEL);
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A)
            return super.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), KeyEvent.KEYCODE_DPAD_CENTER, e.getRepeatCount()));
        if (code == KeyEvent.KEYCODE_BUTTON_B)
            return super.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), KeyEvent.KEYCODE_BACK, e.getRepeatCount()));
        if (code == KeyEvent.KEYCODE_BUTTON_START) {
            if (e.getAction() == KeyEvent.ACTION_UP) play();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean isNav(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_TAB;
    }

    private void hideSystemBars() {
        Compat.hideSystemBars(getWindow());
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        shadow(t);
        return t;
    }

    /** FF7's menu text: a hard drop shadow down and to the right. */
    private void shadow(TextView t) {
        float d = getResources().getDisplayMetrics().density;
        t.setShadowLayer(1.2f * d, 1.4f * d, 1.4f * d, 0xE0000000);
    }

    /** A window: diagonal blue gradient, light outer border, darker inner line. */
    private android.graphics.drawable.Drawable window(int top, int bottom, int cornerDp) {
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { top, bottom });
        fill.setCornerRadius(dp(cornerDp));
        fill.setStroke(dp(2), 0xFFE2E6F0);
        GradientDrawable inner = new GradientDrawable();
        inner.setColor(0);
        inner.setCornerRadius(Math.max(0, dp(cornerDp) - dp(2)));
        inner.setStroke(Math.max(1, dp(1)), 0xFF4C5576);
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] { fill, inner });
        l.setLayerInset(1, dp(2), dp(2), dp(2), dp(2));
        return l;
    }

    /** A command in the menu window: plain text, the glove cursor points at it. which: the page
     *  it shows, or null for Play. */
    private Button menuItem(String label, String which) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(smallScreen ? 13 : 16);
        b.setSingleLine(true);
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][] { { -android.R.attr.state_enabled }, {} }, new int[] { 0xFF7F88AE, TEXT }));
        shadow(b);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setBackground(null);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(new HandCursor(getResources().getDisplayMetrics().density), null, null, null);
        b.setCompoundDrawablePadding(dp(6));
        b.setStateListAnimator(null);
        b.setFocusable(true);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setOnClickListener(v -> { if (which == null) play(); else show(which); });
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(which == null ? (smallScreen ? 34 : 42) : (smallScreen ? 28 : 34))));
        if (which != null) nav.put(which, b);
        return b;
    }

    /** Checkboxes, radio buttons and text fields inside v, in the window colours. */
    private void theme(View v) {
        if (v.isClickable() || v instanceof Button) {
            v.setSoundEffectsEnabled(false);
            v.setAccessibilityDelegate(clickSound);
        }
        if (v instanceof android.widget.AbsListView)     // dialog lists make their rows later
            ((ViewGroup) v).setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) { theme(child); }
                @Override public void onChildViewRemoved(View parent, View child) { }
            });
        if (v instanceof CheckBox || v instanceof RadioButton) {
            android.widget.CompoundButton c = (android.widget.CompoundButton) v;
            c.setButtonTintList(new android.content.res.ColorStateList(
                    new int[][] { { android.R.attr.state_checked }, {} }, new int[] { ACCENT, 0xFFE2E6F0 }));
            c.setTextColor(TEXT);
            shadow(c);
            // a clear mark on the one the controller is on (a faint ripple was all there was:
            // mod options could not be told apart with a D-pad, GitHub #47)
            GradientDrawable hot = new GradientDrawable();
            hot.setColor(0x40FFFFFF);
            hot.setStroke(Math.max(1, dp(1)), 0xFFE2E6F0);
            hot.setCornerRadius(dp(6));
            android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
            sl.addState(new int[] { android.R.attr.state_focused }, hot);
            sl.addState(new int[] {}, new android.graphics.drawable.ColorDrawable(0));
            c.setBackground(sl);
            if (c.getPaddingRight() < dp(8)) c.setPadding(c.getPaddingLeft(), c.getPaddingTop(), dp(8), c.getPaddingBottom());
        } else if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setTextColor(TEXT);
            e.setHintTextColor(DIM);
            e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE2E6F0));
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) theme(g.getChildAt(i));
        }
    }

    /** A dialog in a window like the rest of the launcher. */
    private AlertDialog ff(AlertDialog d) {
        if (d.getWindow() != null) {
            android.graphics.drawable.InsetDrawable bg = new android.graphics.drawable.InsetDrawable(window(WIN_TOP, WIN_BOTTOM, 10), dp(16));
            d.getWindow().setBackgroundDrawable(bg);
            theme(d.getWindow().getDecorView());
            cursorOnFocus(d.getWindow().getDecorView());
            if (testW > 0) {   // TestControl SCREEN: the dialog within that screen too
                d.getWindow().setGravity(Gravity.TOP | Gravity.START);
                d.getWindow().setLayout(Math.max(testW, testH), Math.min(testW, testH));
            }
        }
        d.setOnKeyListener((di, code, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) sound(Sfx.CANCEL);
                else if (isNav(code)) lastNavKey = System.currentTimeMillis();
            }
            return false;
        });
        for (int which : new int[] { AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL }) {
            Button b = d.getButton(which);
            if (b != null) { b.setTextColor(ACCENT); shadow(b); }
        }
        return d;
    }

    private TextView heading(String s) {
        TextView t = text(s, smallScreen ? 18 : 22, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(smallScreen ? 15 : 17);
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][] { { -android.R.attr.state_enabled }, {} }, new int[] { 0xFF8088A8, Color.WHITE }));
        shadow(b);
        b.setStateListAnimator(null);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_focused }, window(HOT_TOP, HOT_BOTTOM, 6));
        sl.addState(new int[] { android.R.attr.state_pressed }, window(HOT_TOP, HOT_BOTTOM, 6));
        sl.addState(new int[] {}, window(BTN_TOP, BTN_BOTTOM, 6));
        b.setBackground(sl);
        b.setFocusable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), dp(8), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    /** A row's button, as small as its label. */
    private Button compact(Button b) {
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        return b;
    }

    private Button small(String label, View.OnClickListener l) {
        Button b = button(label, l);
        b.setTextSize(smallScreen ? 14 : 15);
        if (smallScreen) {
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(12), b.getPaddingTop(), dp(12), b.getPaddingBottom());
        }
        return b;
    }

    /** Buttons side by side, going on to the next line when they don't fit rather than being
     *  squeezed or cut off (GitHub #47). */
    private ViewGroup buttonRow() {
        return new FlowRow(this);
    }

    /** A line's details and its buttons: side by side, or on a small screen the buttons under the
     *  details, which then keep the whole width. */
    private LinearLayout details(View info, View buttons) {
        LinearLayout r = smallScreen ? column() : row();
        if (smallScreen) {
            r.addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            r.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            r.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            r.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return r;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    /** A Game boosters row: 1x / 2x / 5x / 10x, kept in the launcher setting `key`. */
    private void multiplierRow(LinearLayout col, String title, String key) {
        col.addView(text(title, 16, TEXT));
        RadioGroup gg = new RadioGroup(this);
        gg.setOrientation(LinearLayout.HORIZONTAL);
        int cur = prefs.getInt(key, 1);
        for (int gm : new int[] { 1, 2, 5, 10 }) {
            RadioButton rb = new RadioButton(this);
            rb.setText(gm + "×   ");
            rb.setTextColor(TEXT);
            rb.setTextSize(16);
            rb.setId(View.generateViewId());
            final int v = gm;
            rb.setOnClickListener(x -> prefs.edit().putInt(key, v).apply());
            gg.addView(rb);
            if (gm == cur) rb.setChecked(true);
        }
        col.addView(gg);
    }

    private ScrollView scroll(View child) {
        ScrollView s = new ScrollView(this);
        s.addView(child);
        return s;
    }

}

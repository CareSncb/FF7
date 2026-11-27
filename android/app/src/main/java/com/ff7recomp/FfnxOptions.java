package com.ff7recomp;

import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings > Sound and Settings > Game options (FF9Recomp parity: its Memoria settings page):
 * FFNx.toml keys a player would otherwise have to edit by hand, which the launcher could not
 * keep (FFNx.toml is written again before every start). Each is kept as "ffnx.KEY" in the
 * launcher's preferences only once it is changed; until then the template's value, or a mod's,
 * stands. A changed one wins over the mods (GameInstall.writeFfnxToml).
 */
final class FfnxOptions {
    static final String PREFIX = "ffnx.";

    static final class Opt {
        final String key, label, help;
        final String[] values, names;   // a choice; null for an on/off switch
        final boolean defOn;            // a switch's value until it is changed (FFNx's template)
        Opt(String key, String label, String help, String[] values, String[] names) {
            this.key = key; this.label = label; this.help = help; this.values = values; this.names = names; this.defOn = false;
        }
        Opt(String key, String label, String help, boolean defOn) {
            this.key = key; this.label = label; this.help = help; this.values = null; this.names = null; this.defOn = defOn;
        }
        boolean isSwitch() { return values == null; }
    }

    private static final String[] VOL = { "-1", "100", "90", "80", "70", "60", "50", "40", "30", "20", "10", "0" };
    private static final String[] VOL_NAMES = { "Par défaut", "100%", "90%", "80%", "70%", "60%", "50%", "40%", "30%", "20%", "10%", "Désactivé" };

    // above 100%: a boost (src/hle/ff7_dsound.c and FFNx's engine; GitHub #80)
    private static final String[] BOOST = { "-1", "400", "300", "250", "200", "175", "150", "125", "100", "90", "80", "70", "60", "50", "40", "30", "20", "10", "0" };
    private static final String[] BOOST_NAMES = { "Par défaut", "400%", "300%", "250%", "200%", "175%", "150%", "125%", "100%", "90%", "80%", "70%", "60%", "50%", "40%", "30%", "20%", "10%", "Désactivé" };

    private static Opt boostable(String key, String label, String help) { return new Opt(key, label, help, BOOST, BOOST_NAMES); }
    private static Opt volume(String key, String label, String help) { return new Opt(key, label, help, VOL, VOL_NAMES); }

    static final Opt[] SOUND = {
        volume("external_music_volume", "Musique", null),
        boostable("external_sfx_volume", "Effets sonores", "Jusqu'à 400 % : montez-le si la musique couvre les effets"),
        boostable("ffmpeg_video_volume", "Vidéos", null),
        volume("external_voice_volume", "Voix", "Avec un mod de doublage"),
        volume("external_ambient_volume", "Sons d'ambiance", "Avec un mod de sons d'ambiance"),
        new Opt("ff7_footsteps", "Bruits de pas", "Un bruit de pas quand vous marchez dans les décors", false),
        new Opt("enable_voice_music_fade", "Musique plus basse pendant une voix", "Avec un mod de doublage", false),
        new Opt("enable_voice_auto_text", "Les boîtes de dialogue doublées se ferment seules", "Avec un mod de doublage : quand la réplique a été dite", true),
    };

    static final Opt[] GAME = {
        new Opt("external_music_resume", "La musique reprend là où elle s'était arrêtée",
                "De retour sur la carte du monde (et d'autres zones connues), la musique reprend au lieu de recommencer", true),
        new Opt("mdef_fix", "L'armure compte pour la défense magique", "Une correction de la formule du jeu original (par défaut dans FFNx)", true),
        new Opt("ff7_field_autosize_text_box", "Boîtes de texte ajustées au texte", "Dans les décors, les boîtes de dialogue sont ajustées au texte", false),
        new Opt("enable_bilinear", "Textures 3D lissées", "Filtrage bilinéaire des textures des modèles et des menus (l'original est en pixels nets)", false),
    };

    private FfnxOptions() {}

    static Opt[] all() {
        Opt[] a = new Opt[SOUND.length + GAME.length];
        System.arraycopy(SOUND, 0, a, 0, SOUND.length);
        System.arraycopy(GAME, 0, a, SOUND.length, GAME.length);
        return a;
    }

    /** The options changed in the launcher: FFNx.toml key -> value. */
    static Map<String, String> chosen(SharedPreferences p) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Opt o : all()) {
            String v = p.getString(PREFIX + o.key, null);
            if (v != null && !v.isEmpty()) m.put(o.key, v);
        }
        return m;
    }

    static boolean known(String key) {
        for (Opt o : all()) if (o.key.equals(key)) return true;
        return false;
    }

    /** A choice's name for its stored value (the first, "Default", when it is not set). */
    static int index(Opt o, String v) {
        if (v != null) for (int i = 0; i < o.values.length; i++) if (o.values[i].equals(v)) return i;
        return 0;
    }
}

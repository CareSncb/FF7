package com.ff7recomp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Do mods get in each other's way?
 *
 * Before a download only the catalog is known (no file lists), so {@link #beforeInstall}
 * guesses from what the mods are: the same kind of replacement (two field model packs, two
 * soundtracks, two gameplay overhauls, ...), and the Forbid entries in the mod.xml of mods
 * already installed. After a download the real answer comes from {@link #report}: the
 * profile loaded exactly as the game loads it (ModLibrary.conflicts, src/mods), listing the
 * game files two enabled mods both replace -- the one higher in the list wins, as in the
 * game and in 7th Heaven -- and the mods' own Compatibility Require/Forbid declarations.
 */
final class ConflictChecker {

    static final class Warning {
        final String text;
        final boolean serious;     // declared incompatible, or two gameplay overhauls
        Warning(String text, boolean serious) { this.text = text; this.serious = serious; }
    }

    // ---- before downloading -----------------------------------------------------------------

    /** One side of a comparison: a catalog mod or an installed one. */
    private static final class Who {
        String id = "", name = "", category = "";
        final List<String> tags = new ArrayList<>();
        final List<String[]> forbids = new ArrayList<>();
        final Set<String> needs = new LinkedHashSet<>();
        boolean installed;
    }

    private static Who of(ModCatalog.Mod m) {
        Who w = new Who();
        w.id = m.id.toLowerCase(Locale.ROOT);
        w.name = m.name;
        w.category = m.category;
        w.tags.addAll(m.tags);
        for (String[] r : m.requirements) w.needs.add(r[0].toLowerCase(Locale.ROOT));
        return w;
    }

    private static Who of(ModLibrary.Mod m, ModCatalog catalog) {
        Who w = new Who();
        w.installed = true;
        w.id = m.id == null ? "" : m.id;
        w.name = m.title();
        w.category = m.category == null ? "" : m.category;
        ModCatalog.Installed in = catalog.installedByFile(m.file);
        ModCatalog.Mod cm = in == null ? null : catalog.byId(in.id);
        if (cm != null) {
            if (w.id.isEmpty()) w.id = cm.id.toLowerCase(Locale.ROOT);
            if (w.category.isEmpty()) w.category = cm.category;
            w.tags.addAll(cm.tags);
            for (String[] r : cm.requirements) w.needs.add(r[0].toLowerCase(Locale.ROOT));
        }
        w.forbids.addAll(m.forbids);
        for (String[] r : m.requires) w.needs.add(r[0].toLowerCase(Locale.ROOT));
        return w;
    }

    /** What a mod replaces, as far as its category, tags and name say. */
    private static Set<String> slots(Who w) {
        Set<String> s = new LinkedHashSet<>();
        String cat = w.category.toLowerCase(Locale.ROOT);
        StringBuilder words = new StringBuilder(w.name.toLowerCase(Locale.ROOT));
        for (String t : w.tags) words.append(' ').append(t.toLowerCase(Locale.ROOT));
        String k = words.toString();
        switch (cat) {
            case "gameplay": s.add("gameplay"); break;
            case "field models": s.add("modèles des décors"); break;
            case "battle models": s.add("modèles de combat"); break;
            case "animations": s.add("animations"); break;
            case "field textures": s.add("textures des décors"); break;
            case "world textures": s.add("textures de la carte du monde"); break;
            case "battle textures": s.add("textures de combat"); break;
            case "spell textures": s.add(k.contains("limit") ? "effets des limites" : "effets des sorts"); break;
            case "minigames": s.add("mini-jeux"); break;
            case "shaders": s.add("shaders"); break;
            default: break;
        }
        if (cat.equals("media") || cat.equals("miscellaneous") || cat.equals("user interface")) {
            if (k.matches(".*\\b(music|soundtrack|ost|arranged|remake|orchestra)\\b.*")) s.add("musique");
            if (k.matches(".*\\b(fmv|movies?|videos?|cutscenes?)\\b.*")) s.add("vidéos");
            if (k.matches(".*\\b(voice|voices|voiced)\\b.*")) s.add("voix");
            if (k.matches(".*\\b(sfx|sounds?)\\b.*")) s.add("effets sonores");
            if (k.matches(".*\\bavatars?\\b.*")) s.add("portraits des personnages");
            if (k.matches(".*\\bfonts?\\b.*")) s.add("la police");
            if (k.matches(".*\\b(backgrounds?|menu)\\b.*") && cat.equals("user interface")) s.add("graphismes des menus");
        }
        return s;
    }

    private static String what(String slot) {
        switch (slot) {
            case "gameplay": return "données du jeu (stats, ennemis, objets)";
            default: return slot;
        }
    }

    /**
     * Warnings for installing `wanted` (in order; requirements included) next to the enabled
     * mods in `lib`. A new mod goes to the bottom of the Mods list, so where files overlap the
     * mods already there win.
     */
    static List<Warning> beforeInstall(ModCatalog catalog, ModLibrary lib, List<ModCatalog.Mod> wanted) {
        List<Who> have = new ArrayList<>();
        for (ModLibrary.Mod m : lib.mods) if (m.enabled && m.error == null) have.add(of(m, catalog));
        List<Warning> out = new ArrayList<>();
        Set<String> said = new LinkedHashSet<>();
        for (ModCatalog.Mod cm : wanted) {
            Who c = of(cm);
            boolean update = false;
            for (Who h : have) if (!h.id.isEmpty() && h.id.equals(c.id)) update = true;
            if (update) continue;                               // an update replaces itself
            for (Who h : have) {
                if (h.needs.contains(c.id) || c.needs.contains(h.id)) continue;   // meant to go together
                for (String[] f : h.forbids)
                    if (f[0].equalsIgnoreCase(c.id) && said.add("F" + h.id + c.id))
                        out.add(new Warning(h.name + " indique ne pas fonctionner avec " + c.name
                                + (f[1].isEmpty() ? "." : ": " + f[1]), true));
                Set<String> both = slots(c);
                both.retainAll(slots(h));
                for (String s : both) {
                    if (!said.add(s + h.id + c.id)) continue;
                    if (s.equals("gameplay"))
                        out.add(new Warning(c.name + " et " + h.name + " modifient tous deux : " + what(s)
                                + ". Deux mods de gameplay remplacent généralement les mêmes fichiers, donc l'un des deux ne s'appliquera en grande partie pas"
                                + " -- n'en utilisez qu'un, sauf si la page de " + c.name + " indique qu'il fonctionne avec " + h.name + ".", true));
                    else
                        out.add(new Warning(c.name + " et " + h.name + " remplacent tous deux : " + what(s) + ". "
                                + c.name + " va en bas de votre liste de mods, donc les fichiers de " + h.name
                                + " l'emportent là où ils se chevauchent (montez " + c.name + " pour le privilégier).", false));
                }
            }
            c.installed = true;
            have.add(c);                                        // the rest of the batch sees it
        }
        return out;
    }

    // ---- after downloading: the real file overlaps -------------------------------------------

    /** Friendly names for the folders the game's files live in (keys' first part). */
    static String area(String a) {
        switch (a.toLowerCase(Locale.ROOT)) {
            case "char": return "modèles des décors";
            case "battle": return "modèles et données de combat";
            case "flevel": return "cartes des décors";
            case "field": return "fichiers des décors";
            case "kernel": return "données du jeu";
            case "vgmstream": case "music": return "musique";
            case "movies": return "vidéos";
            case "magic": return "effets des sorts";
            case "wm": case "world": return "carte du monde";
            case "menu": return "menus";
            case "cr": return "crédits";
            case "disc": return "écrans de changement de disque";
            case "snowboard-us": case "high-us": return "mini-jeux";
            case "sfx": return "effets sonores";
            case "voice": return "doublage";
            case "ambient": return "son d'ambiance";
            case "textures": return "textures";
            case "high": case "chocobo": case "condor": case "coaster": case "snowboard": case "sub": case "minigame":
                return "mini-jeux";
            case "exe": return "données du moteur";
            case "time": return "jour/nuit";
            case "shaders": return "shaders";
            case "(root)": return "fichiers du dossier du jeu";
            default: return a;
        }
    }

    static final class Report {
        final List<Warning> lines = new ArrayList<>();
        int overlaps, problems;
        /** A content mod above a user interface mod hides that mod's menu textures. */
        boolean interfaceBelow;
        /** Overlaps by library file: winner, loser, friendly areas. */
        final List<String[]> pairs = new ArrayList<>();
        final List<List<String>> pairAreas = new ArrayList<>();
        /** Per library file: short notes for its row on the Mods page. */
        final java.util.Map<String, List<String>> perMod = new java.util.HashMap<>();
        void note(String file, String s) { perMod.computeIfAbsent(file.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(s); }
    }

    /**
     * Options that need another mod, as their description says ("Full ESUI Theme. Enhanced
     * Stock UI mod required."): an option that is on while that mod is not enabled. Mods
     * write this only in prose, so the catalog's mod names are looked for in the text.
     * (Finishing Touch's UI Theme without Enhanced Stock UI jumps into code that mod supplies:
     * the game crashed at the New Game screen.)
     */
    static void optionNeeds(Report r, ModLibrary lib, ModCatalog catalog) {
        for (ModLibrary.Mod m : lib.mods) {
            if (!m.enabled || m.error != null) continue;
            for (ModLibrary.Option o : m.options) {
                if (m.value(o) == 0 || o.description == null) continue;
                String d = o.description.toLowerCase(Locale.ROOT);
                if (!d.contains("required") && !d.contains("requires") && !d.contains("needs ")) continue;
                for (ModCatalog.Mod cm : catalog.mods) {
                    String n = cm.name.toLowerCase(Locale.ROOT).replaceAll("\\[[^\\]]*\\]\\s*", "").trim();
                    if (n.length() < 6 || !d.contains(n)) continue;
                    // only a sentence that names it as a need, not one choice of several ("Requires
                    // either ESUI Field Icons enabled, or ECHOS or New Threat 2.0 for ...")
                    boolean needed = false;
                    for (String sentence : d.split("[.\\n*]+(\\s|$)")) {
                        if (!sentence.contains(n)) continue;
                        if (!sentence.contains("requir") && !sentence.contains("needs ")) continue;
                        if (sentence.contains("either") || sentence.contains(" or ")) continue;
                        needed = true;
                    }
                    if (!needed) continue;
                    ModCatalog.Installed in = catalog.installedOf(cm);
                    boolean on = false;
                    if (in != null) for (ModLibrary.Mod x : lib.mods) if (x.enabled && x.file.equalsIgnoreCase(in.file)) on = true;
                    if (on) continue;
                    r.lines.add(0, new Warning(m.title() + " : l'option \"" + o.name + "\" nécessite " + cm.name
                            + (in == null ? ", qui n'est pas installé" : ", qui n'est pas activé")
                            + ". Désactivez l'option (Options) ou " + (in == null ? "installez-le" : "activez-le") + " ; sans lui, le jeu peut planter.", true));
                    r.note(m.file, "L'option \"" + o.name + "\" nécessite " + cm.name);
                    r.problems++;
                }
            }
        }
    }

    /**
     * A user interface mod below a content mod whose menu textures win over its own (Echo-S
     * ships the game's fonts too): the interface mod's hext still sets the letter widths for
     * its own font, so the text is drawn squeezed. Interface mods belong above.
     */
    static void interfaceOrder(Report r, ModLibrary lib) {
        for (int i = 0; i < r.pairs.size(); i++) {
            String[] p = r.pairs.get(i);
            ModLibrary.Mod w = lib.byFile(p[0]), l = lib.byFile(p[1]);
            if (w == null || l == null || w.isUi() || !l.isUi()) continue;
            List<String> a = r.pairAreas.get(i);
            if (!a.contains("menus") && !a.contains("textures")) continue;
            r.lines.add(0, new Warning(p[2] + " est au-dessus de " + p[3] + ", donc sa police remplace celle de " + p[3]
                    + " alors que l'espacement des lettres reste celui de " + p[3] + " : le texte paraît tassé. Mettez les mods d'interface en premier.", true));
            r.note(p[1], "Ses textures de menu sont masquées par " + p[2] + " : montez-le");
            r.problems++;
            r.interfaceBelow = true;
        }
    }

    static Report report(JSONObject j, ModCatalog catalog) {
        Report r = new Report();
        if (j == null) return r;
        JSONArray mods = j.optJSONArray("mods");
        if (mods == null) return r;
        String[] file = new String[mods.length()], name = new String[mods.length()];
        for (int i = 0; i < mods.length(); i++) {
            file[i] = mods.optJSONObject(i).optString("file");
            name[i] = mods.optJSONObject(i).optString("name", file[i]);
        }
        JSONArray fb = j.optJSONArray("forbids");
        for (int i = 0; fb != null && i < fb.length(); i++) {
            JSONObject f = fb.optJSONObject(i);
            int a = f.optInt("mod"), b = f.optInt("other");
            String why = f.optString("why");
            r.lines.add(new Warning(name[a] + " indique ne pas fonctionner avec " + name[b] + (why.isEmpty() ? "." : ": " + why), true));
            r.note(file[a], "Ne fonctionne pas avec " + name[b]);
            r.note(file[b], name[a] + " ne fonctionne pas avec lui");
            r.problems++;
        }
        JSONArray rq = j.optJSONArray("requires");
        for (int i = 0; rq != null && i < rq.length(); i++) {
            JSONObject q = rq.optJSONObject(i);
            int a = q.optInt("mod");
            ModCatalog.Mod need = catalog.byId(q.optString("id"));
            String needName = need != null ? need.name : "un autre mod (" + q.optString("id") + ")";
            String why = q.optString("why");
            r.lines.add(new Warning(name[a] + " nécessite " + needName + ", qui n'est pas activé" + (why.isEmpty() ? "." : ": " + why), true));
            r.note(file[a], "Nécessite " + needName);
            r.problems++;
        }
        // mods whose mod.xml says where they go relative to another (Finishing Touch above
        // Enhanced Stock UI): the game loads them that way whatever the list says, as 7th Heaven's sort does
        JSONArray ro = j.optJSONArray("reordered");
        for (int i = 0; ro != null && i < ro.length(); i++) {
            JSONObject o = ro.optJSONObject(i);
            int a = o.optInt("mod"), b = o.optInt("other");
            String where = o.optBoolean("below") ? "en dessous de " : "au-dessus de ";
            r.lines.add(new Warning(name[a] + " doit être " + where + name[b] + " (son propre ordre de chargement), le jeu le charge donc là."
                    + " Placez-le " + where + name[b] + " dans la liste pour correspondre.", false));
            r.note(file[a], "Chargé " + where + name[b] + " (son ordre de chargement)");
        }
        // options another mod's mod.xml sets for compatibility (as 7th Heaven does at launch)
        JSONArray st = j.optJSONArray("settings");
        for (int i = 0; st != null && i < st.length(); i++) {
            JSONObject o = st.optJSONObject(i);
            int a = o.optInt("mod");
            r.lines.add(new Warning(name[a] + ": \"" + o.optString("option") + "\" est réglé sur \"" + o.optString("value")
                    + "\" en jeu, car un autre mod activé le demande pour la compatibilité.", false));
            r.note(file[a], o.optString("option") + ": " + o.optString("value") + " (réglé par un autre mod)");
        }
        JSONArray ov = j.optJSONArray("overlaps");
        for (int i = 0; ov != null && i < ov.length(); i++) {
            JSONObject o = ov.optJSONObject(i);
            int w = o.optInt("winner"), l = o.optInt("loser"), n = o.optInt("files");
            List<String> areas = new ArrayList<>();
            JSONArray as = o.optJSONArray("areas");
            for (int k = 0; as != null && k < as.length(); k++) {
                String a = area(as.optJSONObject(k).optString("area"));
                if (!areas.contains(a)) areas.add(a);
            }
            String where = areas.isEmpty() ? "" : " (" + String.join(", ", areas) + ")";
            r.lines.add(new Warning(name[w] + " et " + name[l] + " remplacent tous deux : " + n + (n == 1 ? " fichier du jeu" : " fichiers du jeu")
                    + where + ". " + name[w] + " est plus haut dans la liste, sa version est donc utilisée"
                    + (o.optBoolean("conditional") ? " (certains seulement à certains moments)" : "") + ".", false));
            r.pairs.add(new String[] { file[w], file[l], name[w], name[l] });
            r.pairAreas.add(areas);
            r.note(file[w], "Remplace " + name[l] + " dans " + n + (n == 1 ? " fichier" : " fichiers"));
            r.note(file[l], n + (n == 1 ? " fichier" : " fichiers") + " remplacé(s) par " + name[w]);
            r.overlaps++;
        }
        return r;
    }
}

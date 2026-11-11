package dev.mwkov.shieldtest;

/** The same feed for both screens: ordinary posts, with a GTA one every few rows. */
final class Feed {
    static final int ROWS = 160;

    static boolean isSpoiler(int i) {
        return i % 7 == 3;
    }

    static String text(int i) {
        if (isSpoiler(i)) return "GTA 6 story breakdown, part " + i + " - what happens at the end";
        String[] t = {"Weekend hiking photos from the north coast", "New cafe opened downtown",
                "Five easy pasta recipes", "Football results and highlights",
                "Phone battery tips that actually work", "Cheap flights for December",
                "How to repot a cactus"};
        return t[i % t.length] + " (" + i + ")";
    }
}

package net.kopias.tapover.ui;

/** One size/position set (portrait or landscape) as edited in memory. */
final class PosSet {
    float size, x, y;
    boolean isSet = true;

    void from(float s, float px, float py) { size = s; x = px; y = py; }
}

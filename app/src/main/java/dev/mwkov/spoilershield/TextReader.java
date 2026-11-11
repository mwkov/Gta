package dev.mwkov.spoilershield;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.List;

/** Reads the text in a picture of the screen. */
interface TextReader {

    final class Block {
        final String text;
        final Rect box;

        Block(String text, Rect box) {
            this.text = text;
            this.box = box;
        }
    }

    /** Blocking; call off the main thread. */
    List<Block> read(Bitmap image) throws Exception;

    void close();
}

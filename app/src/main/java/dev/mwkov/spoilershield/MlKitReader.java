package dev.mwkov.spoilershield;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;

/** On-device text recognition (Google ML Kit, bundled model: nothing leaves the phone). */
final class MlKitReader implements TextReader {

    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    MlKitReader() {
    }

    @Override
    public List<Block> read(Bitmap image) throws Exception {
        Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)));
        List<Block> out = new ArrayList<>();
        for (Text.TextBlock b : result.getTextBlocks()) {
            Rect box = b.getBoundingBox();
            if (box == null) continue;
            out.add(new Block(b.getText().replace('\n', ' '), box));
        }
        return out;
    }

    @Override
    public void close() {
        recognizer.close();
    }
}

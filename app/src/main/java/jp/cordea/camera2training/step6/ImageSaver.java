package jp.cordea.camera2training.step6;

import android.media.Image;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 画像を保存する Runnable class
 */
public class ImageSaver implements Runnable {

    private Image image;
    private File file;

    public ImageSaver(Image image, File file) {
        this.image = image;
        this.file = file;
    }

    @Override
    public void run() {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        try {
            FileOutputStream stream = new FileOutputStream(file.getPath());
            stream.write(bytes);
            stream.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            image.close();
        }
    }
}

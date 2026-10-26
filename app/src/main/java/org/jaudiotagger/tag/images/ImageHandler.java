package org.jaudiotagger.tag.images;

import java.io.IOException;

/**
 * Image Handler
 */
public interface ImageHandler {
    void reduceQuality(Artwork artwork, int maxSize) throws IOException;

    void makeSmaller(Artwork artwork, int size) throws IOException;

    boolean isMimeTypeWritable(String mimeType);

    void showReadFormats();

    void showWriteFormats();
}

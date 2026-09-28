package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class DesktopFileRepository {
    private static final int THUMBNAIL_SIZE = 192;

    private final Context mContext;

    DesktopFileRepository(final Context context) {
        mContext = context.getApplicationContext();
    }

    List<DesktopFile> load(final int thumbnailLimit) throws IOException {
        final DesktopFileInfo[] records = ShellAccess.listDesktopFiles();
        Arrays.sort(records, new Comparator<DesktopFileInfo>() {
            @Override
            public int compare(
                    final DesktopFileInfo left,
                    final DesktopFileInfo right) {
                if (left.directory != right.directory) {
                    return left.directory ? -1 : 1;
                }
                final int compared = left.name.compareToIgnoreCase(right.name);
                return compared != 0 ? compared : left.name.compareTo(right.name);
            }
        });
        final List<DesktopFile> files = new ArrayList<>(records.length);
        int previewsRemaining = Math.max(0, thumbnailLimit);
        for (final DesktopFileInfo record : records) {
            final DesktopEntry desktopEntry = DesktopEntryFile.read(record);
            Bitmap thumbnail = null;
            if (desktopEntry == null
                    && !record.directory
                    && previewsRemaining > 0
                    && MediaThumbnails.supports(record.mimeType)) {
                thumbnail = loadThumbnail(record);
                previewsRemaining--;
            }
            files.add(new DesktopFile(
                    record.relativePath,
                    DesktopFileUri.create(mContext, record.relativePath),
                    record.name,
                    record.mimeType,
                    record.modified,
                    record.size,
                    record.directory,
                    thumbnail,
                    desktopEntry));
        }
        return files;
    }

    /** Photo or video preview, decoded at most once per file version. */
    private static Bitmap loadThumbnail(final DesktopFileInfo record) {
        return MediaThumbnails.load(
                MediaThumbnails.key(ShellDesktopDirectory.ABSOLUTE_PATH + "/" + record.relativePath,
                        record.size, record.modified, THUMBNAIL_SIZE),
                record.mimeType,
                THUMBNAIL_SIZE,
                () -> ShellAccess.openDesktopFile(record.relativePath));
    }
}

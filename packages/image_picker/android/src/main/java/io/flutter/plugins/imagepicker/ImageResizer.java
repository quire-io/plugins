// Copyright 2019 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.imagepicker;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Log;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

class ImageResizer {
  private final File externalFilesDirectory;
  private final ExifDataCopier exifDataCopier;

  ImageResizer(File externalFilesDirectory, ExifDataCopier exifDataCopier) {
    this.externalFilesDirectory = externalFilesDirectory;
    this.exifDataCopier = exifDataCopier;
  }

  /**
   * If necessary, resizes the image located in imagePath and then returns the path for the scaled
   * image.
   *
   * <p>If no resizing is needed, returns the path for the original image.
   */
  String resizeImageIfNeeded(
      String imagePath,
      @Nullable Double maxWidth,
      @Nullable Double maxHeight,
      @Nullable Integer imageQuality) {
    Bitmap bmp = decodeFile(imagePath);
    if (bmp == null) {
      return null;
    }
    boolean shouldScale =
        maxWidth != null || maxHeight != null || isImageQualityValid(imageQuality);

    try {
      if (!shouldScale) {
        return fixOrientation(imagePath);
      }

      String[] pathParts = imagePath.split("/");
      String imageName = pathParts[pathParts.length - 1];
      File file = resizedImage(imagePath, bmp, maxWidth, maxHeight, imageQuality, imageName);
      copyExif(imagePath, file.getPath());
      return file.getPath();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private File resizedImage(
      String path, Bitmap bmp, Double maxWidth, Double maxHeight, Integer imageQuality, String outputImageName)
      throws IOException {
    double originalWidth = bmp.getWidth() * 1.0;
    double originalHeight = bmp.getHeight() * 1.0;

    if (!isImageQualityValid(imageQuality)) {
      imageQuality = 100;
    }

    boolean hasMaxWidth = maxWidth != null;
    boolean hasMaxHeight = maxHeight != null;

    Double width = hasMaxWidth ? Math.min(originalWidth, maxWidth) : originalWidth;
    Double height = hasMaxHeight ? Math.min(originalHeight, maxHeight) : originalHeight;

    boolean shouldDownscaleWidth = hasMaxWidth && maxWidth < originalWidth;
    boolean shouldDownscaleHeight = hasMaxHeight && maxHeight < originalHeight;
    boolean shouldDownscale = shouldDownscaleWidth || shouldDownscaleHeight;

    if (shouldDownscale) {
      double downscaledWidth = (height / originalHeight) * originalWidth;
      double downscaledHeight = (width / originalWidth) * originalHeight;

      if (width < height) {
        if (!hasMaxWidth) {
          width = downscaledWidth;
        } else {
          height = downscaledHeight;
        }
      } else if (height < width) {
        if (!hasMaxHeight) {
          height = downscaledHeight;
        } else {
          width = downscaledWidth;
        }
      } else {
        if (originalWidth < originalHeight) {
          width = downscaledWidth;
        } else if (originalHeight < originalWidth) {
          height = downscaledHeight;
        }
      }
    }

    Bitmap scaledBmp = createScaledBitmap(bmp, width.intValue(), height.intValue(), false);
    Bitmap fixOrientationScaledBmp = fixOrientation(scaledBmp, path);
    if (fixOrientationScaledBmp != null)
      scaledBmp = fixOrientationScaledBmp;

    File file =
        createImageOnExternalDirectory("/scaled_" + outputImageName, scaledBmp, imageQuality);

    return file;
  }

  private File createFile(File externalFilesDirectory, String child) {
    return new File(externalFilesDirectory, child);
  }

  private FileOutputStream createOutputStream(File imageFile) throws IOException {
    return new FileOutputStream(imageFile);
  }

  private void copyExif(String filePathOri, String filePathDest) {
    exifDataCopier.copyExif(filePathOri, filePathDest);
  }

  private Bitmap decodeFile(String path) {
    return BitmapFactory.decodeFile(path);
  }

  private Bitmap createScaledBitmap(Bitmap bmp, int width, int height, boolean filter) {
    return Bitmap.createScaledBitmap(bmp, width, height, filter);
  }

  private boolean isImageQualityValid(Integer imageQuality) {
    return imageQuality != null && imageQuality > 0 && imageQuality < 100;
  }

  private File createImageOnExternalDirectory(String name, Bitmap bitmap, int imageQuality)
      throws IOException {

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    boolean saveAsPNG = bitmap.hasAlpha();
    if (saveAsPNG) {
      Log.d(
          "ImageResizer",
          "image_picker: compressing is not supported for type PNG. Returning the image with original quality");
    }
    bitmap.compress(
        saveAsPNG ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
        imageQuality,
        outputStream);
    File imageFile = createFile(externalFilesDirectory, name);
    FileOutputStream fileOutput = createOutputStream(imageFile);
    fileOutput.write(outputStream.toByteArray());
    fileOutput.close();
    return imageFile;
  }

  private String fixOrientation(String path) throws IOException {
    Bitmap bitmap = fixOrientation(BitmapFactory.decodeFile(path), path);
    if (bitmap == null)
      return path;

    String[] pathParts = path.split("/");
    String imageName = pathParts[pathParts.length - 1];

    boolean saveAsPNG = bitmap.hasAlpha();
    if (saveAsPNG) {
      Log.d(
              "ImageResizer",
              "image_picker: compressing is not supported for type PNG. Returning the image with original quality");
    }

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    bitmap.compress(
            saveAsPNG ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
            100,
            outputStream);

    File imageFile = new File(externalFilesDirectory, "/rotated_" + imageName);
    FileOutputStream fileOutput = new FileOutputStream(imageFile);
    fileOutput.write(outputStream.toByteArray());
    fileOutput.close();
    return imageFile.getPath();
  }

  private Bitmap fixOrientation(Bitmap bitmap, String path) throws IOException {
    int rotate = 0;
    ExifInterface exif;
    exif = new ExifInterface(path);
    int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL);

    switch (orientation) {
      case ExifInterface.ORIENTATION_ROTATE_270:
        rotate = 270;
        break;
      case ExifInterface.ORIENTATION_ROTATE_180:
        rotate = 180;
        break;
      case ExifInterface.ORIENTATION_ROTATE_90:
        rotate = 90;
        break;
      default:
        return null;
    }
    Matrix matrix = new Matrix();
    matrix.postRotate(rotate);
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
            bitmap.getHeight(), matrix, true);
  }
}

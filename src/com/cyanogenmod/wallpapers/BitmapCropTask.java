package com.cyanogenmod.wallpapers;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Bitmap.CompressFormat;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

class BitmapCropTask extends AsyncTask<Void, Void, Boolean> {

    public interface OnBitmapCroppedHandler {
        public void onBitmapCropped(byte[] imageBytes);
    }

    private static final String LOGTAG = BitmapCropTask.class.getSimpleName();
    protected static final int DEFAULT_COMPRESS_QUALITY = 90;

    Uri mInUri = null;
    Context mContext;
    String mInFilePath;
    byte[] mInImageBytes;
    int mInResId = 0;
    InputStream mInStream;
    RectF mCropBounds = null;
    int mOutWidth, mOutHeight;
    int mRotation;
    String mOutputFormat = "jpg"; // for now
    boolean mSetWallpaper;
    boolean mSaveCroppedBitmap;
    Bitmap mCroppedBitmap;
    Runnable mOnEndRunnable;
    Resources mResources;
    OnBitmapCroppedHandler mOnBitmapCroppedHandler;
    boolean mNoCrop;

    public BitmapCropTask(Context c, String filePath,
            RectF cropBounds, int rotation, int outWidth, int outHeight,
            boolean setWallpaper, boolean saveCroppedBitmap, Runnable onEndRunnable) {
        mContext = c;
        mInFilePath = filePath;
        init(cropBounds, rotation,
                outWidth, outHeight, setWallpaper, saveCroppedBitmap, onEndRunnable);
    }

    public BitmapCropTask(byte[] imageBytes,
            RectF cropBounds, int rotation, int outWidth, int outHeight,
            boolean setWallpaper, boolean saveCroppedBitmap, Runnable onEndRunnable) {
        mInImageBytes = imageBytes;
        init(cropBounds, rotation,
                outWidth, outHeight, setWallpaper, saveCroppedBitmap, onEndRunnable);
    }

    public BitmapCropTask(Context c, Uri inUri,
            RectF cropBounds, int rotation, int outWidth, int outHeight,
            boolean setWallpaper, boolean saveCroppedBitmap, Runnable onEndRunnable) {
        mContext = c;
        mInUri = inUri;
        init(cropBounds, rotation,
                outWidth, outHeight, setWallpaper, saveCroppedBitmap, onEndRunnable);
    }

    public BitmapCropTask(Context c, Resources res, int inResId,
            RectF cropBounds, int rotation, int outWidth, int outHeight,
            boolean setWallpaper, boolean saveCroppedBitmap, Runnable onEndRunnable) {
        mContext = c;
        mInResId = inResId;
        mResources = res;
        init(cropBounds, rotation,
                outWidth, outHeight, setWallpaper, saveCroppedBitmap, onEndRunnable);
    }

    private void init(RectF cropBounds, int rotation, int outWidth, int outHeight,
            boolean setWallpaper, boolean saveCroppedBitmap, Runnable onEndRunnable) {
        mCropBounds = cropBounds;
        mRotation = rotation;
        mOutWidth = outWidth;
        mOutHeight = outHeight;
        mSetWallpaper = setWallpaper;
        mSaveCroppedBitmap = saveCroppedBitmap;
        mOnEndRunnable = onEndRunnable;
    }

    public void setOnBitmapCropped(OnBitmapCroppedHandler handler) {
        mOnBitmapCroppedHandler = handler;
    }

    public void setNoCrop(boolean value) {
        mNoCrop = value;
    }

    public void setOnEndRunnable(Runnable onEndRunnable) {
        mOnEndRunnable = onEndRunnable;
    }

    // Helper to setup input stream
    private void regenerateInputStream() {
        if (mInUri == null && mInResId == 0 && mInFilePath == null && mInImageBytes == null) {
            Log.w(LOGTAG, "cannot read original file, no input URI, resource ID, or " +
                    "image byte array given");
        } else {
            if (mInStream != null) {
                try {
                    mInStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            try {
                if (mInUri != null) {
                    mInStream = new BufferedInputStream(
                            mContext.getContentResolver().openInputStream(mInUri));
                } else if (mInFilePath != null) {
                    mInStream = mContext.openFileInput(mInFilePath);
                } else if (mInImageBytes != null) {
                    mInStream = new BufferedInputStream(
                            new ByteArrayInputStream(mInImageBytes));
                } else {
                    mInStream = new BufferedInputStream(
                            mResources.openRawResource(mInResId));
                }
            } catch (FileNotFoundException e) {
                Log.w(LOGTAG, "cannot read file: " + mInUri.toString(), e);
            }
        }
    }

    public Point getImageBounds() {
        regenerateInputStream();
        if (mInStream != null) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(mInStream, null, options);
            if (options.outWidth != 0 && options.outHeight != 0) {
                return new Point(options.outWidth, options.outHeight);
            }
        }
        return null;
    }

    public void setCropBounds(RectF cropBounds) {
        mCropBounds = cropBounds;
    }

    public Bitmap getCroppedBitmap() {
        return mCroppedBitmap;
    }
    public boolean cropBitmap() {
        boolean failure = false;

        regenerateInputStream();

        WallpaperManager wallpaperManager = null;
        if (mSetWallpaper) {
            wallpaperManager = WallpaperManager.getInstance(mContext.getApplicationContext());
        }
        if (mSetWallpaper && mNoCrop && mInStream != null) {
            try {
                wallpaperManager.setStream(mInStream);
            } catch (IOException e) {
                Log.w(LOGTAG, "cannot write stream to wallpaper", e);
                failure = true;
            }
            return !failure;
        }
        if (mInStream != null) {
            // Find crop bounds (scaled to original image size)
            Rect roundedTrueCrop = new Rect();
            Matrix rotateMatrix = new Matrix();
            Matrix inverseRotateMatrix = new Matrix();
            if (mRotation > 0) {
                rotateMatrix.setRotate(mRotation);
                inverseRotateMatrix.setRotate(-mRotation);

                mCropBounds.roundOut(roundedTrueCrop);
                mCropBounds = new RectF(roundedTrueCrop);

                Point bounds = getImageBounds();

                float[] rotatedBounds = new float[] { bounds.x, bounds.y };
                rotateMatrix.mapPoints(rotatedBounds);
                rotatedBounds[0] = Math.abs(rotatedBounds[0]);
                rotatedBounds[1] = Math.abs(rotatedBounds[1]);

                mCropBounds.offset(-rotatedBounds[0]/2, -rotatedBounds[1]/2);
                inverseRotateMatrix.mapRect(mCropBounds);
                mCropBounds.offset(bounds.x/2, bounds.y/2);

                regenerateInputStream();
            }

            mCropBounds.roundOut(roundedTrueCrop);

            if (roundedTrueCrop.width() <= 0 || roundedTrueCrop.height() <= 0) {
                Log.w(LOGTAG, "crop has bad values for full size image");
                failure = true;
                return false;
            }

            // See how much we're reducing the size of the image
            int scaleDownSampleSize = Math.min(roundedTrueCrop.width() / mOutWidth,
                    roundedTrueCrop.height() / mOutHeight);

            // Attempt to open a region decoder
            BitmapRegionDecoder decoder = null;
            try {
                decoder = BitmapRegionDecoder.newInstance(mInStream, true);
            } catch (IOException e) {
                Log.w(LOGTAG, "cannot open region decoder for file: " + mInUri.toString(), e);
            }

            Bitmap crop = null;
            if (decoder != null) {
                // Do region decoding to get crop bitmap
                BitmapFactory.Options options = new BitmapFactory.Options();
                if (scaleDownSampleSize > 1) {
                    options.inSampleSize = scaleDownSampleSize;
                }
                crop = decoder.decodeRegion(roundedTrueCrop, options);
                decoder.recycle();
            }

            if (crop == null) {
                // BitmapRegionDecoder has failed, try to crop in-memory
                regenerateInputStream();
                Bitmap fullSize = null;
                if (mInStream != null) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    if (scaleDownSampleSize > 1) {
                        options.inSampleSize = scaleDownSampleSize;
                    }
                    fullSize = BitmapFactory.decodeStream(mInStream, null, options);
                }
                if (fullSize != null) {
                    mCropBounds.left /= scaleDownSampleSize;
                    mCropBounds.top /= scaleDownSampleSize;
                    mCropBounds.bottom /= scaleDownSampleSize;
                    mCropBounds.right /= scaleDownSampleSize;
                    mCropBounds.roundOut(roundedTrueCrop);

                    crop = Bitmap.createBitmap(fullSize, roundedTrueCrop.left,
                            roundedTrueCrop.top, roundedTrueCrop.width(),
                            roundedTrueCrop.height());
                }
            }

            if (crop == null) {
                Log.w(LOGTAG, "cannot decode file: " + mInUri.toString());
                failure = true;
                return false;
            }
            if (mOutWidth > 0 && mOutHeight > 0 || mRotation > 0) {
                float[] dimsAfter = new float[] { crop.getWidth(), crop.getHeight() };
                rotateMatrix.mapPoints(dimsAfter);
                dimsAfter[0] = Math.abs(dimsAfter[0]);
                dimsAfter[1] = Math.abs(dimsAfter[1]);

                if (!(mOutWidth > 0 && mOutHeight > 0)) {
                    mOutWidth = Math.round(dimsAfter[0]);
                    mOutHeight = Math.round(dimsAfter[1]);
                }

                RectF cropRect = new RectF(0, 0, dimsAfter[0], dimsAfter[1]);
                RectF returnRect = new RectF(0, 0, mOutWidth, mOutHeight);

                Matrix m = new Matrix();
                if (mRotation == 0) {
                    m.setRectToRect(cropRect, returnRect, Matrix.ScaleToFit.FILL);
                } else {
                    Matrix m1 = new Matrix();
                    m1.setTranslate(-crop.getWidth() / 2f, -crop.getHeight() / 2f);
                    Matrix m2 = new Matrix();
                    m2.setRotate(mRotation);
                    Matrix m3 = new Matrix();
                    m3.setTranslate(dimsAfter[0] / 2f, dimsAfter[1] / 2f);
                    Matrix m4 = new Matrix();
                    m4.setRectToRect(cropRect, returnRect, Matrix.ScaleToFit.FILL);

                    Matrix c1 = new Matrix();
                    c1.setConcat(m2, m1);
                    Matrix c2 = new Matrix();
                    c2.setConcat(m4, m3);
                    m.setConcat(c2, c1);
                }

                Bitmap tmp = Bitmap.createBitmap((int) returnRect.width(),
                        (int) returnRect.height(), Bitmap.Config.ARGB_8888);
                if (tmp != null) {
                    Canvas c = new Canvas(tmp);
                    Paint p = new Paint();
                    p.setFilterBitmap(true);
                    c.drawBitmap(crop, m, p);
                    crop = tmp;
                }
            }

            if (mSaveCroppedBitmap) {
                mCroppedBitmap = crop;
            }

            // Get output compression format
            CompressFormat cf =
                    convertExtensionToCompressFormat(getFileExtension(mOutputFormat));

            // Compress to byte array
            ByteArrayOutputStream tmpOut = new ByteArrayOutputStream(2048);
            if (crop.compress(cf, DEFAULT_COMPRESS_QUALITY, tmpOut)) {
                // If we need to set to the wallpaper, set it
                if (mSetWallpaper && wallpaperManager != null) {
                    try {
                        byte[] outByteArray = tmpOut.toByteArray();
                        wallpaperManager.setStream(new ByteArrayInputStream(outByteArray));
                        if (mOnBitmapCroppedHandler != null) {
                            mOnBitmapCroppedHandler.onBitmapCropped(outByteArray);
                        }
                    } catch (IOException e) {
                        Log.w(LOGTAG, "cannot write stream to wallpaper", e);
                        failure = true;
                    }
                }
            } else {
                Log.w(LOGTAG, "cannot compress bitmap");
                failure = true;
            }
        }
        return !failure; // True if any of the operations failed
    }

    @Override
    protected Boolean doInBackground(Void... params) {
        return cropBitmap();
    }

    @Override
    protected void onPostExecute(Boolean result) {
        if (mOnEndRunnable != null) {
            mOnEndRunnable.run();
        }
    }

    protected static CompressFormat convertExtensionToCompressFormat(String extension) {
        return extension.equals("png") ? CompressFormat.PNG : CompressFormat.JPEG;
    }

    protected static String getFileExtension(String requestFormat) {
        String outputFormat = (requestFormat == null)
                ? "jpg"
                : requestFormat;
        outputFormat = outputFormat.toLowerCase();
        return (outputFormat.equals("png") || outputFormat.equals("gif"))
                ? "png" // We don't support gif compression.
                : "jpg";
    }
}

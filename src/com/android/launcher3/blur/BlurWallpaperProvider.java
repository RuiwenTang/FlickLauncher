package com.android.launcher3.blur;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.BitmapDrawable;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;

import java.util.ArrayList;
import java.util.List;


public class BlurWallpaperProvider {
    private final Context mContext;
    private final WallpaperManager mWallpaperManager;
    private final List<Listener> mListeners = new ArrayList<>();
    private DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    private Bitmap mWallpaper;
    private Bitmap mPlaceholder;
    private float mOffset;
    private int mBlurRadius = 25;
    private Runnable mNotifyRunnable = new Runnable() {
        @Override
        public void run() {
            for (Listener listener : mListeners) {
                listener.onWallpaperChanged();
            }
        }
    };

    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint mColorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path mPath = new Path();

    private int mDownsampleFactor = 8;
    private int mWallpaperWidth;
    private Canvas sCanvas = new Canvas();

    private final Runnable mUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateWallpaper();
        }
    };

    public BlurWallpaperProvider(Context context) {
        mContext = context;

        mWallpaperManager = WallpaperManager.getInstance(context);
        sEnabled = false;//mWallpaperManager.getWallpaperInfo() == null && Utilities.isAllowBlurDrawerPrefEnabled(mContext);

        updateBlurRadius();
    }

    private void updateBlurRadius() {
        mBlurRadius = (int) Utilities.getPrefs(mContext).getFloat("pref_blurRadius", 75f) / mDownsampleFactor;
        mBlurRadius = Math.max(1, Math.min(mBlurRadius, 25));
    }

    private void updateWallpaper() {
        Launcher launcher = LauncherAppState.getInstance().getLauncher();
        boolean enabled = false;//mWallpaperManager.getWallpaperInfo() == null && Utilities.isAllowBlurDrawerPrefEnabled(mContext);
        if (enabled != sEnabled) {
            // launcher.scheduleKill();
        }

        if (!sEnabled) return;

        updateBlurRadius();

        Bitmap wallpaper = upscaleToScreenSize(((BitmapDrawable) mWallpaperManager.getDrawable()).getBitmap());

        mWallpaperWidth = wallpaper.getWidth();

        mWallpaper = null;
        mPlaceholder = createPlaceholder(wallpaper.getWidth(), wallpaper.getHeight());
        launcher.runOnUiThread(mNotifyRunnable);
        if (/*Utilities.isVibrancyEnabled(mContext)*/ false) {
            wallpaper = applyVibrancy(wallpaper, getTintColor());
        }
        mWallpaper = blur(wallpaper);
        launcher.runOnUiThread(mNotifyRunnable);
    }

    private Bitmap upscaleToScreenSize(Bitmap bitmap) {
        WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        display.getRealMetrics(mDisplayMetrics);

        int width = mDisplayMetrics.widthPixels, height = mDisplayMetrics.heightPixels;

        float widthFactor = 0f, heightFactor = 0f;
        if (width > bitmap.getWidth()) {
            widthFactor = ((float) width) / bitmap.getWidth();
        }
        if (height > bitmap.getHeight()) {
            heightFactor = ((float) height) / bitmap.getHeight();
        }

        float upscaleFactor = Math.max(widthFactor, heightFactor);
        if (upscaleFactor <= 0) {
            return bitmap;
        }

        int scaledWidth = (int) (bitmap.getWidth() * upscaleFactor);
        int scaledHeight = (int) (bitmap.getHeight() * upscaleFactor);
        Bitmap scaled = Bitmap.createScaledBitmap(
                bitmap,
                scaledWidth,
                scaledHeight, false);

        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas();
        canvas.setBitmap(result);

        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (widthFactor > heightFactor) {
            canvas.drawBitmap(scaled, 0, (height - scaledHeight) / 2, paint);
        } else {
            canvas.drawBitmap(scaled, (width - scaledWidth) / 2, 0, paint);
        }

        return result;
    }

    public Bitmap blur(Bitmap image) {
        int width = Math.round(image.getWidth() / mDownsampleFactor);
        int height = Math.round(image.getHeight() / mDownsampleFactor);

        Bitmap inputBitmap = Bitmap.createScaledBitmap(image, width, height, false);
        Bitmap outputBitmap = Bitmap.createBitmap(inputBitmap);

        RenderScript rs = RenderScript.create(mContext);
        ScriptIntrinsicBlur theIntrinsic = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
        Allocation tmpIn = Allocation.createFromBitmap(rs, inputBitmap);
        Allocation tmpOut = Allocation.createFromBitmap(rs, outputBitmap);
        theIntrinsic.setRadius(mBlurRadius);
        theIntrinsic.setInput(tmpIn);
        theIntrinsic.forEach(tmpOut);
        tmpOut.copyTo(outputBitmap);

        // Have to scale it back to full resolution because antialiasing is too expensive to be done each frame
        Bitmap bitmap = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(bitmap);
        canvas.save();
        canvas.scale(mDownsampleFactor, mDownsampleFactor);
        canvas.drawBitmap(outputBitmap, 0, 0, mPaint);
        canvas.restore();

        return bitmap;
    }

    private Bitmap createPlaceholder(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        sCanvas.setBitmap(bitmap);

        mPath.moveTo(0, 0);
        mPath.lineTo(0, height);
        mPath.lineTo(width, height);
        mPath.lineTo(width, 0);
        mColorPaint.setXfermode(null);
        mColorPaint.setColor(getTintColor());
        sCanvas.drawPath(mPath, mColorPaint);

        return bitmap;
    }

    public int getTintColor() {
        return 0x45FFFFFF;
    }

    public void updateAsync() {
        Utilities.THREAD_POOL_EXECUTOR.execute(mUpdateRunnable);
    }

    private Bitmap applyVibrancy(Bitmap wallpaper, int color) {
        int width = wallpaper.getWidth(), height = wallpaper.getHeight();

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas();
        canvas.setBitmap(bitmap);
        canvas.drawBitmap(wallpaper, 0, 0, mPaint);

        mPath.moveTo(0, 0);
        mPath.lineTo(0, height);
        mPath.lineTo(width, height);
        mPath.lineTo(width, 0);
        mColorPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.LIGHTEN));
        mColorPaint.setColor(color);
        canvas.drawPath(mPath, mColorPaint);

        return bitmap;
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
        listener.onOffsetChanged(mOffset);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    public BlurDrawable createDrawable() {
        return new BlurDrawable(this, 0, false);
    }

    public BlurDrawable createDrawable(float radius, boolean allowTransparencyMode) {
        return new BlurDrawable(this, radius, allowTransparencyMode);
    }

    public void setWallpaperOffset(float offset) {
        if (!isEnabled()) return;
        if (mWallpaper == null) return;

        final int availw = mDisplayMetrics.widthPixels - mWallpaperWidth;
        int xPixels = availw / 2;

        if (availw < 0)
            xPixels += (int) (availw * (offset - .5f) + .5f);

        mOffset = -xPixels;

        for (Listener listener : mListeners) {
            listener.onOffsetChanged(mOffset);
        }
    }

    public void setUseTransparency(boolean useTransparency) {
        for (Listener listener : mListeners) {
            listener.setUseTransparency(useTransparency);
        }
    }

    public Bitmap getWallpaper() {
        return mWallpaper;
    }

    public Bitmap getPlaceholder() {
        return mPlaceholder;
    }

    public Context getContext() {
        return mContext;
    }

    public int getDownsampleFactor() {
        return mDownsampleFactor;
    }

    public int getBlurRadius() {
        return mBlurRadius;
    }

    interface Listener {

        void onWallpaperChanged();
        void onOffsetChanged(float offset);
        void setUseTransparency(boolean useTransparency);
    }

    private static boolean sEnabled;

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static BlurWallpaperProvider getInstance() {
        // return LauncherAppState.getInstance().getLauncher().getBlurWallpaperProvider();
        return null;
    }
}
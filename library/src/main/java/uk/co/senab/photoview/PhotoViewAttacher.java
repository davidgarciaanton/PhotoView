/*******************************************************************************
 * Copyright 2011, 2012 Chris Banes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package uk.co.senab.photoview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Matrix.ScaleToFit;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

import java.lang.ref.WeakReference;

import uk.co.senab.photoview.gestures.OnGestureListener;
import uk.co.senab.photoview.gestures.VersionedGestureDetector;
import uk.co.senab.photoview.log.LogManager;
import uk.co.senab.photoview.log.Logger;
import uk.co.senab.photoview.scrollerproxy.ScrollerProxy;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_UP;

public class PhotoViewAttacher implements IPhotoView, View.OnTouchListener,
        OnGestureListener,
        ViewTreeObserver.OnGlobalLayoutListener {

    private static final String LOG_TAG = "PhotoViewAttacher";

    // let debug flag be dynamic, but still Proguard can be used to remove from
    // release builds
    private static final boolean DEBUG = Log.isLoggable(LOG_TAG, Log.DEBUG);

    static final Interpolator sInterpolator = new AccelerateDecelerateInterpolator();
    int ZOOM_DURATION = DEFAULT_ZOOM_DURATION;

    static final int EDGE_NONE  = -1;
    static final int EDGE_LEFT  = 0;
    static final int EDGE_RIGHT = 1;
    static final int EDGE_BOTH  = 2;

    private float mMinScale = DEFAULT_MIN_SCALE;
    private float mMidScale = DEFAULT_MID_SCALE;
    private float mMaxScale = DEFAULT_MAX_SCALE;

    private boolean mAllowParentInterceptOnEdge = true;
    private boolean mBlockParentIntercept       = false;

    private boolean mOverlaping;
    private int     overlapSize;
    private boolean shouldComputeMatrix;

    @OverlapPosition
    private int overlapPos;

    private static void checkZoomLevels(
            float minZoom, float midZoom,
            float maxZoom) {
        if (minZoom >= midZoom) {
            throw new IllegalArgumentException(
                    "MinZoom has to be less than MidZoom");
        } else if (midZoom >= maxZoom) {
            throw new IllegalArgumentException(
                    "MidZoom has to be less than MaxZoom");
        }
    }

    /**
     * @return true if the ImageView exists, and it's Drawable existss
     */
    private static boolean hasDrawable(ImageView imageView) {
        return null != imageView && null != imageView.getDrawable();
    }

    /**
     * @return true if the ScaleType is supported.
     */
    private static boolean isSupportedScaleType(final ScaleType scaleType) {
        if (null == scaleType) {
            return false;
        }

        switch (scaleType) {
            case MATRIX:
                throw new IllegalArgumentException(
                        scaleType.name()
                                + " is not supported in PhotoView");

            default:
                return true;
        }
    }

    /**
     * Set's the ImageView's ScaleType to Matrix.
     */
    private static void setImageViewScaleTypeMatrix(ImageView imageView) {
        /**
         * PhotoView sets it's own ScaleType to Matrix, then diverts all calls
         * setScaleType to this.setScaleType automatically.
         */
        if (null != imageView && !(imageView instanceof IPhotoView)) {
            if (!ScaleType.MATRIX.equals(imageView.getScaleType())) {
                imageView.setScaleType(ScaleType.MATRIX);
            }
        }
    }

    private WeakReference<ImageView> mImageView;

    // Gesture Detectors
    private GestureDetector                                mGestureDetector;
    private uk.co.senab.photoview.gestures.GestureDetector mScaleDragDetector;

    // These are set so we don't keep allocating them on the heap
    private final Matrix mBaseMatrix = new Matrix();
    private final Matrix mDrawMatrix = new Matrix();
    private final Matrix mSuppMatrix = new Matrix();
    private final RectF mDisplayRect = new RectF();
    private final float[] mMatrixValues = new float[9];

    // Listeners
    private OnMatrixChangedListener mMatrixChangeListener;
    private OnPhotoTapListener mPhotoTapListener;
    private OnViewTapListener mViewTapListener;
    private OnLongClickListener mLongClickListener;
    private OnScaleChangeListener mScaleChangeListener;

    private int mIvTop, mIvRight, mIvBottom, mIvLeft;
    private FlingRunnable mCurrentFlingRunnable;
    private int mScrollEdge = EDGE_BOTH;

    private boolean mZoomEnabled;
    private ScaleType mScaleType = ScaleType.FIT_CENTER;

    public PhotoViewAttacher(ImageView imageView) {
        this(imageView, true);
    }

    public PhotoViewAttacher(ImageView imageView, boolean zoomable) {
        mImageView = new WeakReference<>(imageView);

//        imageView.setDrawingCacheEnabled(true);
        imageView.setOnTouchListener(this);

        ViewTreeObserver observer = imageView.getViewTreeObserver();
        if (null != observer)
            observer.addOnGlobalLayoutListener(this);

        // Make sure we using MATRIX Scale Type
        setImageViewScaleTypeMatrix(imageView);

        if (imageView.isInEditMode()) {
            return;
        }
        // Create Gesture Detectors...
        mScaleDragDetector = VersionedGestureDetector.newInstance(
                imageView.getContext(), this);

        mGestureDetector = new GestureDetector(imageView.getContext(),
                new GestureDetector.SimpleOnGestureListener() {

                    // forward long click listener
                    @Override
                    public void onLongPress(MotionEvent e) {
                        if (null != mLongClickListener) {
                            mLongClickListener.onLongClick(getImageView());
                        }
                    }
                });

        mGestureDetector.setOnDoubleTapListener(new DefaultOnDoubleTapListener(this));

        // Finally, update the UI so that we're zoomable
        setZoomable(zoomable);
    }

    @Override
    public void setOnDoubleTapListener(GestureDetector.OnDoubleTapListener newOnDoubleTapListener) {
        if (newOnDoubleTapListener != null) {
            this.mGestureDetector.setOnDoubleTapListener(newOnDoubleTapListener);
        } else {
            this.mGestureDetector.setOnDoubleTapListener(new DefaultOnDoubleTapListener(this));
        }
    }

    @Override
    public void setOnScaleChangeListener(OnScaleChangeListener onScaleChangeListener) {
        this.mScaleChangeListener = onScaleChangeListener;
    }

    @Override
    public void setOverlap(int overlapPix, @OverlapPosition int pos) {
        boolean changed = false;
        if (overlapPix <= 0) {
            changed = mOverlaping == true;
            mOverlaping = false;
        } else {
            if (!mOverlaping) {
                changed = true;
            }
            mOverlaping = true;
            changed |= this.overlapSize != overlapPix;
            changed |= this.overlapPos != pos;
            this.overlapSize = overlapPix;
            this.overlapPos = pos;
        }

        if (changed) {
            update();
        }
    }

    @Override
    public boolean hasOverlap() {
        return mOverlaping;
    }

    @Override
    public int getOverlapPixelSize() {
        return mOverlaping ? overlapSize : 0;
    }

    @Override
    public int getOverlapPosition() {
        return overlapPos;
    }

    @Override
    public void setOverlapPosition(@OverlapPosition int pos) {
        overlapPos = pos;
    }

    @Override
    public boolean canZoom() {
        return mZoomEnabled;
    }

    /**
     * Clean-up the resources attached to this object. This needs to be called when the ImageView is
     * no longer used. A good example is from {@link android.view.View#onDetachedFromWindow()} or
     * from {@link android.app.Activity#onDestroy()}. This is automatically called if you are using
     * {@link uk.co.senab.photoview.PhotoView}.
     */
    @SuppressWarnings("deprecation")
    public void cleanup() {
        if (null == mImageView) {
            return; // cleanup already done
        }

        final ImageView imageView = mImageView.get();

        if (null != imageView) {
            // Remove this as a global layout listener
            ViewTreeObserver observer = imageView.getViewTreeObserver();
            if (null != observer && observer.isAlive()) {
                observer.removeGlobalOnLayoutListener(this);
            }

            // Remove the ImageView's reference to this
            imageView.setOnTouchListener(null);

            // make sure a pending fling runnable won't be run
            cancelFling();
        }

        if (null != mGestureDetector) {
            mGestureDetector.setOnDoubleTapListener(null);
        }

        // Clear listeners too
        mMatrixChangeListener = null;
        mPhotoTapListener = null;
        mViewTapListener = null;

        // Finally, clear ImageView
        mImageView = null;
    }

    @Override
    public RectF getDisplayRect() {
        checkMatrixBounds();
        return getDisplayRect(getDrawMatrix());
    }

    private Runnable normalizationTask = null;

    @Override
    public void setNormalizedDisplayRect(final RectF area) {
        ImageView iv = getImageView();
        if (null == iv) {
            return;
        }

        final Drawable d = iv.getDrawable();
        if (null == d) {
            return;
        }

        final int viewWidth = getImageViewWidth(iv);
        final int viewHeight = getImageViewHeight(iv);

        if (viewWidth == 0 || viewHeight == 0) {
            // should defer
            normalizationTask = new Runnable() {
                @Override
                public void run() {
                    setNormalizedDisplayRect(area);
                }
            };
            return;
        }

        int imWidth = d.getIntrinsicWidth();
        int imHeight = d.getIntrinsicHeight();

        RectF src = new RectF(area.left * imWidth, area.top * imHeight, area.right * imWidth, area.bottom * imHeight);
        RectF target = new RectF(0, 0, viewWidth, viewHeight);

        final float viewAr = (float) viewWidth / viewHeight;
        final float inAr = src.width() / src.height();

        // for now ignore regions not matching aspect ratio...
        if ((int)(viewAr * 1000.0f) != (int)(inAr * 1000.0f)) {
            return;
        }


        Matrix m = new Matrix();
        m.setRectToRect(src, target, ScaleToFit.FILL);

        Matrix baseInvert = new Matrix();
        if (!mBaseMatrix.invert(baseInvert)) {
            return;
        }

        baseInvert.postConcat(m);
        mSuppMatrix.set(baseInvert);
        shouldComputeMatrix = true;
        checkAndDisplayMatrix();
    }


    @Override
    public RectF getNormalizedDisplayRect() {
        return getNormalizedDisplayRect(false);
    }

    private RectF getNormalizedDisplayRect(boolean cached) {
        ImageView iv = getImageView();
        if (null == iv) {
            return null;
        }

        Drawable d = iv.getDrawable();
        if (null == d) {
            return null;
        }
        RectF retVal = new RectF();

        retVal.set(0, 0, getImageViewWidth(iv, cached), getImageViewHeight(iv, cached));
        Matrix inverse = new Matrix();
        Matrix drawingMatrix = getDrawMatrix();
        if (!drawingMatrix.invert(inverse)) {
            return null;
        }
        inverse.mapRect(retVal);
        final int dHeight = d.getIntrinsicHeight();
        final int dWidth = d.getIntrinsicWidth();
        retVal.set(
                retVal.left / dWidth,
                retVal.top / dHeight,
                retVal.right / dWidth,
                retVal.bottom / dHeight
        );
        return retVal;
    }

    @Override
    public boolean setDisplayMatrix(Matrix finalMatrix) {
        if (finalMatrix == null)
            throw new IllegalArgumentException("Matrix cannot be null");

        ImageView imageView = getImageView();
        if (null == imageView)
            return false;

        if (null == imageView.getDrawable())
            return false;

        mSuppMatrix.set(finalMatrix);
        shouldComputeMatrix = true;
        checkAndDisplayMatrix();

        return true;
    }

    /**
     * @deprecated use {@link #setRotationTo(float)}
     */
    @Override
    public void setPhotoViewRotation(float degrees) {
        mSuppMatrix.setRotate(degrees % 360);
        shouldComputeMatrix = true;
        checkAndDisplayMatrix();
    }

    @Override
    public void setRotationTo(float degrees) {
        mSuppMatrix.setRotate(degrees % 360);
        shouldComputeMatrix = true;
        checkAndDisplayMatrix();
    }

    @Override
    public void setRotationBy(float degrees) {
        mSuppMatrix.postRotate(degrees % 360);
        shouldComputeMatrix = true;
        checkAndDisplayMatrix();
    }

    public ImageView getImageView() {
        ImageView imageView = null;

        if (null != mImageView) {
            imageView = mImageView.get();
        }

        // If we don't have an ImageView, call cleanup()
        if (null == imageView) {
            cleanup();
            LogManager.getLogger().i(
                    LOG_TAG,
                    "ImageView no longer exists. You should not use this PhotoViewAttacher any more.");
        }

        return imageView;
    }

    @Override
    @Deprecated
    public float getMinScale() {
        return getMinimumScale();
    }

    @Override
    public float getMinimumScale() {
        return mMinScale;
    }

    @Override
    @Deprecated
    public float getMidScale() {
        return getMediumScale();
    }

    @Override
    public float getMediumScale() {
        return mMidScale;
    }

    @Override
    @Deprecated
    public float getMaxScale() {
        return getMaximumScale();
    }

    @Override
    public float getMaximumScale() {
        return mMaxScale;
    }

    @Override
    public float getScale() {
        return getMatrixScale(mSuppMatrix);
    }

    private float getMatrixScale(Matrix matrix) {
        return (float) Math.sqrt(
                (float) Math.pow(getValue(matrix, Matrix.MSCALE_X), 2) + (float) Math.pow(getValue(matrix, Matrix.MSKEW_Y), 2));
    }

    @Override
    public ScaleType getScaleType() {
        return mScaleType;
    }

    @Override
    public void onDrag(float dx, float dy) {
        if (mScaleDragDetector.isScaling()) {
            return; // Do not drag if we are already scaling
        }

        if (DEBUG) {
            LogManager.getLogger().d(
                    LOG_TAG,
                    String.format("onDrag: dx: %.2f. dy: %.2f", dx, dy));
        }

        ImageView imageView = getImageView();
        mSuppMatrix.postTranslate(dx, dy);
        shouldComputeMatrix = true;
        checkAndDisplayMatrix();

        /**
         * Here we decide whether to let the ImageView's parent to start taking
         * over the touch event.
         *
         * First we check whether this function is enabled. We never want the
         * parent to take over if we're scaling. We then check the edge we're
         * on, and the direction of the scroll (i.e. if we're pulling against
         * the edge, aka 'overscrolling', let the parent take over).
         */
        ViewParent parent = imageView.getParent();
        if (mAllowParentInterceptOnEdge && !mScaleDragDetector.isScaling() && !mBlockParentIntercept) {
            if (mScrollEdge == EDGE_BOTH
                    || (mScrollEdge == EDGE_LEFT && dx >= 1f)
                    || (mScrollEdge == EDGE_RIGHT && dx <= -1f)) {
                if (null != parent)
                    parent.requestDisallowInterceptTouchEvent(false);
            }
        } else {
            if (null != parent) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
        }
    }

    @Override
    public void onFling(
            float startX, float startY, float velocityX,
            float velocityY) {
        if (DEBUG) {
            LogManager.getLogger().d(
                    LOG_TAG,
                    "onFling. sX: " + startX + " sY: " + startY + " Vx: "
                            + velocityX + " Vy: " + velocityY);
        }
        ImageView imageView = getImageView();
        mCurrentFlingRunnable = new FlingRunnable(imageView.getContext());
        mCurrentFlingRunnable.fling(
                getImageViewWidth(imageView),
                getImageViewHeight(imageView), (int) velocityX, (int) velocityY);
        imageView.post(mCurrentFlingRunnable);
    }

    @Override
    public void onGlobalLayout() {
        ImageView imageView = getImageView();

        if (null != imageView) {
            final int top = imageView.getTop();
            final int right = imageView.getRight();
            final int bottom = imageView.getBottom();
            final int left = imageView.getLeft();

            /**
             * We need to check whether the ImageView's bounds have changed.
             * This would be easier if we targeted API 11+ as we could just use
             * View.OnLayoutChangeListener. Instead we have to replicate the
             * work, keeping track of the ImageView's bounds and then checking
             * if the values change.
             */
            if (top != mIvTop || bottom != mIvBottom || left != mIvLeft
                    || right != mIvRight) {
                // Update our base matrix, as the bounds have changed
                updateBaseMatrix(imageView.getDrawable(), false);

                // Update values as something has changed
                mIvTop = top;
                mIvRight = right;
                mIvBottom = bottom;
                mIvLeft = left;

                if (normalizationTask != null) {
                    normalizationTask.run();
                    normalizationTask = null;
                }
            }
        }
    }

    @Override
    public void onScale(float scaleFactor, float focusX, float focusY) {
        if (DEBUG) {
            LogManager.getLogger().d(
                    LOG_TAG,
                    String.format(
                            "onScale: scale: %.2f. fX: %.2f. fY: %.2f",
                            scaleFactor, focusX, focusY));
        }

        if (getScale() < mMaxScale || scaleFactor < 1f) {
            if (null != mScaleChangeListener) {
                mScaleChangeListener.onScaleChange(scaleFactor, focusX, focusY);
            }
            mSuppMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY);
            shouldComputeMatrix = true;
            checkAndDisplayMatrix();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent ev) {
        boolean handled = false;

        if (mZoomEnabled && hasDrawable((ImageView) v)) {
            ViewParent parent = v.getParent();
            switch (ev.getAction()) {
                case ACTION_DOWN:
                    // First, disable the Parent from intercepting the touch
                    // event
                    if (null != parent) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    } else {
                        LogManager.getLogger().i(LOG_TAG, "onTouch getParent() returned null");
                    }

                    // If we're flinging, and the user presses down, cancel
                    // fling
                    cancelFling();
                    break;

                case ACTION_CANCEL:
                case ACTION_UP:
                    // If the user has zoomed less than min scale, zoom back
                    // to min scale
                    if (getScale() < mMinScale) {
                        RectF rect = getDisplayRect();
                        if (null != rect) {
                            v.post(new AnimatedZoomRunnable(getScale(), mMinScale,
                                    rect.centerX(), rect.centerY()));
                            handled = true;
                        }
                    }
                    break;
            }

            // Try the Scale/Drag detector
            if (null != mScaleDragDetector) {
                boolean wasScaling = mScaleDragDetector.isScaling();
                boolean wasDragging = mScaleDragDetector.isDragging();

                handled = mScaleDragDetector.onTouchEvent(ev);

                boolean didntScale = !wasScaling && !mScaleDragDetector.isScaling();
                boolean didntDrag = !wasDragging && !mScaleDragDetector.isDragging();

                mBlockParentIntercept = didntScale && didntDrag;
            }

            // Check to see if the user double tapped
            if (null != mGestureDetector && mGestureDetector.onTouchEvent(ev)) {
                handled = true;
            }

        }

        return handled;
    }

    @Override
    public void setAllowParentInterceptOnEdge(boolean allow) {
        mAllowParentInterceptOnEdge = allow;
    }

    @Override
    @Deprecated
    public void setMinScale(float minScale) {
        setMinimumScale(minScale);
    }

    @Override
    public void setMinimumScale(float minimumScale) {
        checkZoomLevels(minimumScale, mMidScale, mMaxScale);
        mMinScale = minimumScale;
    }

    @Override
    @Deprecated
    public void setMidScale(float midScale) {
        setMediumScale(midScale);
    }

    @Override
    public void setMediumScale(float mediumScale) {
        checkZoomLevels(mMinScale, mediumScale, mMaxScale);
        mMidScale = mediumScale;
    }

    @Override
    @Deprecated
    public void setMaxScale(float maxScale) {
        setMaximumScale(maxScale);
    }

    @Override
    public void setMaximumScale(float maximumScale) {
        checkZoomLevels(mMinScale, mMidScale, maximumScale);
        mMaxScale = maximumScale;
    }

    @Override
    public void setScaleLevels(float minimumScale, float mediumScale, float maximumScale) {
        checkZoomLevels(minimumScale, mediumScale, maximumScale);
        mMinScale = minimumScale;
        mMidScale = mediumScale;
        mMaxScale = maximumScale;
    }

    @Override
    public void setOnLongClickListener(OnLongClickListener listener) {
        mLongClickListener = listener;
    }

    @Override
    public void setOnMatrixChangeListener(OnMatrixChangedListener listener) {
        mMatrixChangeListener = listener;
    }

    @Override
    public void setOnPhotoTapListener(OnPhotoTapListener listener) {
        mPhotoTapListener = listener;
    }

    @Override
    public OnPhotoTapListener getOnPhotoTapListener() {
        return mPhotoTapListener;
    }

    @Override
    public void setOnViewTapListener(OnViewTapListener listener) {
        mViewTapListener = listener;
    }

    @Override
    public OnViewTapListener getOnViewTapListener() {
        return mViewTapListener;
    }

    @Override
    public void setScale(float scale) {
        setScale(scale, false);
    }

    @Override
    public void setScale(float scale, boolean animate) {
        ImageView imageView = getImageView();

        if (null != imageView) {
            setScale(scale,
                    (imageView.getRight()) / 2,
                    (imageView.getBottom()) / 2,
                    animate);
        }
    }

    @Override
    public void setScale(float scale, float focalX, float focalY,
                         boolean animate) {
        ImageView imageView = getImageView();

        if (null != imageView) {
            // Check to see if the scale is within bounds
            if (scale < mMinScale || scale > mMaxScale) {
                LogManager
                        .getLogger()
                        .i(LOG_TAG,
                                "Scale must be within the range of minScale and maxScale");
                return;
            }

            if (animate) {
                imageView.post(new AnimatedZoomRunnable(getScale(), scale,
                        focalX, focalY));
            } else {
                mSuppMatrix.setScale(scale, scale, focalX, focalY);
                shouldComputeMatrix = true;
                checkAndDisplayMatrix();
            }
        }
    }

    @Override
    public void setScaleType(ScaleType scaleType) {
        if (isSupportedScaleType(scaleType) && scaleType != mScaleType) {
            mScaleType = scaleType;

            // Finally update
            update();
        }
    }

    @Override
    public void setZoomable(boolean zoomable) {
        mZoomEnabled = zoomable;
        update();
    }

    public void update() {
        ImageView imageView = getImageView();

        if (null != imageView) {
            if (null != normalizationTask) {
                normalizationTask = null; // cancel it...
            }

            if (mZoomEnabled) {
                // Make sure we using MATRIX Scale Type
                setImageViewScaleTypeMatrix(imageView);

                // Update the base matrix using the current drawable
                updateBaseMatrix(imageView.getDrawable(), true);
            } else {
                // Reset the Matrix...
                resetMatrix(true);
            }
        }
    }

    @Override
    public Matrix getDisplayMatrix() {
        return new Matrix(getDrawMatrix());
    }

    public Matrix getDrawMatrix() {
        if (shouldComputeMatrix) {
            mDrawMatrix.set(mBaseMatrix);
            mDrawMatrix.postConcat(mSuppMatrix);
        }
        return mDrawMatrix;
    }

    private void cancelFling() {
        if (null != mCurrentFlingRunnable) {
            mCurrentFlingRunnable.cancelFling();
            mCurrentFlingRunnable = null;
        }
    }

    /**
     * Helper method that simply checks the Matrix, and then displays the result
     */
    private void checkAndDisplayMatrix() {
        if (checkMatrixBounds()) {
            setImageViewMatrix(getDrawMatrix());
        }
    }

    private void checkImageViewScaleType() {
        ImageView imageView = getImageView();

        /**
         * PhotoView's getScaleType() will just divert to this.getScaleType() so
         * only call if we're not attached to a PhotoView.
         */
        if (null != imageView && !(imageView instanceof IPhotoView)) {
            if (!ScaleType.MATRIX.equals(imageView.getScaleType())) {
                throw new IllegalStateException(
                        "The ImageView's ScaleType has been changed since attaching a PhotoViewAttacher");
            }
        }
    }

    private boolean checkMatrixBounds() {
        final ImageView imageView = getImageView();
        if (null == imageView) {
            return false;
        }

        final Matrix drawMatrix = getDrawMatrix();
        final RectF rect = getDisplayRect(drawMatrix);
        if (null == rect) {
            return false;
        }

        final float height = rect.height(), width = rect.width();
        float deltaX = 0, deltaY = 0;

        final int viewHeight = getImageViewHeight(imageView);
        if (height <= viewHeight) {
            // this only happens while zooming out and still holding fingers down
            switch (mScaleType) {
                case FIT_START:
                    deltaY = -rect.top;
                    break;
                case FIT_END:
                    deltaY = viewHeight - height - rect.top;
                    break;
                default:
                    deltaY = (viewHeight - height) / 2 - rect.top;
                    break;
            }
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }

        final int viewWidth = getImageViewWidth(imageView);
        final int scaledOverlapDim = (int) (getMatrixScale(mBaseMatrix) * overlapSize);


        Logger logger = null;
        if (DEBUG) {
            logger = LogManager.getLogger();
            final Drawable drawable = imageView.getDrawable();
            logger.d(LOG_TAG, "Original rect: 0,0," + drawable.getIntrinsicWidth() + "," + drawable.getIntrinsicHeight());
            logger.d(LOG_TAG, "transformed rect: " + rect);
            logger.d(LOG_TAG, "Transformation matrix: " + drawMatrix);
            logger.d(LOG_TAG, "Transformed DIM with scale " + getMatrixScale(mBaseMatrix) + ": " + scaledOverlapDim);
        }


        final int rightOverlapDim = mOverlaping && overlapPos == Gravity.END ? scaledOverlapDim : 0;
        final int leftOverlapDim = mOverlaping && overlapPos == Gravity.START ? scaledOverlapDim : 0;

        if (width <= viewWidth) {
            // this only happens while zooming out and still holding fingers down
            switch (mScaleType) {
                case FIT_START:
                    deltaX = -rect.left;
                    break;
                case FIT_END:
                    deltaX = viewWidth - width - rect.left;
                    break;
                default:
                    deltaX = (viewWidth - width) / 2 - rect.left;
                    break;
            }
            mScrollEdge = EDGE_BOTH;
        } else if (rect.left + leftOverlapDim > 0) {
            deltaX = -leftOverlapDim - rect.left;
            if (rect.left > 0) {
                mScrollEdge = EDGE_LEFT;
            }
        } else if (rect.right - rightOverlapDim < viewWidth) {
            deltaX = viewWidth + rightOverlapDim - rect.right;
            if (null != logger) {
                logger.d(LOG_TAG, String.format("Hitting right side: %f - %d < %d", rect.right, rightOverlapDim, viewHeight));
                logger.d(LOG_TAG, "Displacement = " + deltaX);
            }

            if (rect.right < viewWidth) {
                mScrollEdge = EDGE_RIGHT;
            }
        } else {
            mScrollEdge = EDGE_NONE;
        }

        // Finally actually translate the matrix
        mSuppMatrix.postTranslate(deltaX, deltaY);
        shouldComputeMatrix = true;
        return true;
    }

    /**
     * Helper method that maps the supplied Matrix to the current Drawable
     *
     * @param matrix - Matrix to map Drawable against
     * @return RectF - Displayed Rectangle
     */
    private RectF getDisplayRect(Matrix matrix) {
        ImageView imageView = getImageView();

        if (null != imageView) {
            Drawable d = imageView.getDrawable();
            if (null != d) {
                mDisplayRect.set(0, 0, d.getIntrinsicWidth(),
                        d.getIntrinsicHeight());
                matrix.mapRect(mDisplayRect);
                return mDisplayRect;
            }
        }
        return null;
    }

    public Bitmap getVisibleRectangleBitmap() {
        // TODO: 9/7/15 I've just disabled drawing cache, to see if that solves my problems
        ImageView imageView = getImageView();
        if (null == imageView) {
            return null;
        }

        return !imageView.isDrawingCacheEnabled() ? null : imageView.getDrawingCache();
    }

    @Override
    public void setZoomTransitionDuration(int milliseconds) {
        if (milliseconds < 0)
            milliseconds = DEFAULT_ZOOM_DURATION;
        this.ZOOM_DURATION = milliseconds;
    }

    @Override
    public IPhotoView getIPhotoViewImplementation() {
        return this;
    }

    /**
     * Helper method that 'unpacks' a Matrix and returns the required value
     *
     * @param matrix     - Matrix to unpack
     * @param whichValue - Which value from Matrix.M* to return
     * @return float - returned value
     */
    private float getValue(Matrix matrix, int whichValue) {
        matrix.getValues(mMatrixValues);
        return mMatrixValues[whichValue];
    }

    /**
     * Resets the Matrix back to FIT_CENTER, and then displays it.s
     * @param resetTransforms
     */
    private void resetMatrix(boolean resetTransforms) {
        if (resetTransforms) {
            mSuppMatrix.reset();
        } else {
            // compute any layout displacement...
            float dx = 0.0f;
            float dy = 0.0f;
            float scale = getScale();
            View v = getImageView();
            if (v.getWidth() != mIvRight - mIvLeft && (mIvRight != 0.0f && mIvLeft != 0.0f)) {
                dx = ((float) (mIvRight - mIvLeft) - (float) v.getWidth()) / 2.0f;
            }

            if (v.getHeight() != mIvBottom - mIvTop && (mIvBottom != 0.0f && mIvTop != 0.0f)) {
                dy = ((float) (mIvBottom - mIvTop) - (float) v.getHeight()) / 2.0f;
            }
            mSuppMatrix.postTranslate(dx * scale, dy * scale);
        }
        shouldComputeMatrix = true;
        checkAndDisplayMatrix();
    }

    private void setImageViewMatrix(Matrix matrix) {
        ImageView imageView = getImageView();
        if (null != imageView) {

            checkImageViewScaleType();
            imageView.setImageMatrix(matrix);

            // Call MatrixChangedListener if needed
            if (null != mMatrixChangeListener) {
                RectF displayRect = getDisplayRect(matrix);
                if (null != displayRect) {
                    mMatrixChangeListener.onMatrixChanged(displayRect);
                }
            }
        }
    }

    /**
     * Calculate Matrix for FIT_CENTER
     *
     * @param d - Drawable being displayed
     */
    private void updateBaseMatrix(Drawable d, boolean resetTransforms) {
        ImageView imageView = getImageView();
        if (null == imageView || null == d) {
            return;
        }

        final float viewWidth = getImageViewWidth(imageView);
        final float viewHeight = getImageViewHeight(imageView);
        final int drawableWidth = d.getIntrinsicWidth();
        final int drawableHeight = d.getIntrinsicHeight();

        mBaseMatrix.reset();

        final float widthScale = viewWidth / drawableWidth;
        final float heightScale = viewHeight / drawableHeight;

        if (mScaleType == ScaleType.CENTER) {
            mBaseMatrix.postTranslate(
                    (viewWidth - drawableWidth) / 2F,
                    (viewHeight - drawableHeight) / 2F);

        } else if (mScaleType == ScaleType.CENTER_CROP) {
            float scale = Math.max(widthScale, heightScale);
            mBaseMatrix.postScale(scale, scale);
            mBaseMatrix.postTranslate((viewWidth - drawableWidth * scale) / 2F,
                    (viewHeight - drawableHeight * scale) / 2F);

        } else if (mScaleType == ScaleType.CENTER_INSIDE) {
            float scale = Math.min(1.0f, Math.min(widthScale, heightScale));
            mBaseMatrix.postScale(scale, scale);
            mBaseMatrix.postTranslate((viewWidth - drawableWidth * scale) / 2F,
                    (viewHeight - drawableHeight * scale) / 2F);

        } else {
            RectF mTempSrc = new RectF(0, 0, drawableWidth, drawableHeight);
            RectF mTempDst = new RectF(0, 0, viewWidth, viewHeight);

            switch (mScaleType) {
                case FIT_CENTER:
                    mBaseMatrix
                            .setRectToRect(mTempSrc, mTempDst, ScaleToFit.CENTER);
                    break;

                case FIT_START:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.START);
                    break;

                case FIT_END:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.END);
                    break;

                case FIT_XY:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.FILL);
                    break;

                default:
                    break;
            }
        }


        resetMatrix(resetTransforms);
    }

    private int getImageViewWidth(ImageView imageView) {
        return getImageViewWidth(imageView, false);
    }

    private int getImageViewWidth(ImageView imageView, boolean cached) {
        if (null == imageView)
            return 0;
        if (!cached) {
            return imageView.getWidth() - imageView.getPaddingLeft() - imageView.getPaddingRight();
        } else {
            return mIvRight - mIvLeft - imageView.getPaddingLeft() - imageView.getPaddingRight();
        }
    }

    private int getImageViewHeight(ImageView imageView) {
        return getImageViewHeight(imageView, false);
    }

    private int getImageViewHeight(ImageView imageView, boolean cached) {
        if (null == imageView)
            return 0;
        if (!cached) {
            return imageView.getHeight() - imageView.getPaddingTop() - imageView.getPaddingBottom();
        } else {
            return mIvBottom - mIvTop - imageView.getPaddingTop() - imageView.getPaddingBottom();
        }
    }

    /**
     * Interface definition for a callback to be invoked when the internal Matrix has changed for
     * this View.
     *
     * @author Chris Banes
     */
    public static interface OnMatrixChangedListener {
        /**
         * Callback for when the Matrix displaying the Drawable has changed. This could be because
         * the View's bounds have changed, or the user has zoomed.
         *
         * @param rect - Rectangle displaying the Drawable's new bounds.
         */
        void onMatrixChanged(RectF rect);
    }

    /**
     * Interface definition for callback to be invoked when attached ImageView scale changes
     *
     * @author Marek Sebera
     */
    public static interface OnScaleChangeListener {
        /**
         * Callback for when the scale changes
         *
         * @param scaleFactor the scale factor (<1 for zoom out, >1 for zoom in)
         * @param focusX      focal point X position
         * @param focusY      focal point Y position
         */
        void onScaleChange(float scaleFactor, float focusX, float focusY);
    }

    /**
     * Interface definition for a callback to be invoked when the Photo is tapped with a single
     * tap.
     *
     * @author Chris Banes
     */
    public static interface OnPhotoTapListener {

        /**
         * A callback to receive where the user taps on a photo. You will only receive a callback if
         * the user taps on the actual photo, tapping on 'whitespace' will be ignored.
         *
         * @param view - View the user tapped.
         * @param x    - where the user tapped from the of the Drawable, as percentage of the
         *             Drawable width.
         * @param y    - where the user tapped from the top of the Drawable, as percentage of the
         *             Drawable height.
         */
        void onPhotoTap(View view, float x, float y);
    }

    /**
     * Interface definition for a callback to be invoked when the ImageView is tapped with a single
     * tap.
     *
     * @author Chris Banes
     */
    public static interface OnViewTapListener {

        /**
         * A callback to receive where the user taps on a ImageView. You will receive a callback if
         * the user taps anywhere on the view, tapping on 'whitespace' will not be ignored.
         *
         * @param view - View the user tapped.
         * @param x    - where the user tapped from the left of the View.
         * @param y    - where the user tapped from the top of the View.
         */
        void onViewTap(View view, float x, float y);
    }

    private class AnimatedZoomRunnable implements Runnable {

        private final float mFocalX, mFocalY;
        private final long mStartTime;
        private final float mZoomStart, mZoomEnd;

        public AnimatedZoomRunnable(final float currentZoom, final float targetZoom,
                                    final float focalX, final float focalY) {
            mFocalX = focalX;
            mFocalY = focalY;
            mStartTime = System.currentTimeMillis();
            mZoomStart = currentZoom;
            mZoomEnd = targetZoom;
        }

        @Override
        public void run() {
            ImageView imageView = getImageView();
            if (imageView == null) {
                return;
            }

            float t = interpolate();
            float scale = mZoomStart + t * (mZoomEnd - mZoomStart);
            float deltaScale = scale / getScale();

            onScale(deltaScale, mFocalX, mFocalY);

            // We haven't hit our target scale yet, so post ourselves again
            if (t < 1f) {
                Compat.postOnAnimation(imageView, this);
            }
        }

        private float interpolate() {
            float t = 1f * (System.currentTimeMillis() - mStartTime) / ZOOM_DURATION;
            t = Math.min(1f, t);
            t = sInterpolator.getInterpolation(t);
            return t;
        }
    }

    private class FlingRunnable implements Runnable {

        private final ScrollerProxy mScroller;
        private int mCurrentX, mCurrentY;

        public FlingRunnable(Context context) {
            mScroller = ScrollerProxy.getScroller(context);
        }

        public void cancelFling() {
            if (DEBUG) {
                LogManager.getLogger().d(LOG_TAG, "Cancel Fling");
            }
            mScroller.forceFinished(true);
        }

        public void fling(int viewWidth, int viewHeight, int velocityX,
                          int velocityY) {
            final RectF rect = getDisplayRect();
            if (null == rect) {
                return;
            }

            int scaledOverlapDim = (int)(getMatrixScale(mBaseMatrix) * overlapSize);

            final int leftOverlapDim = mOverlaping && overlapPos == Gravity.START ? scaledOverlapDim : 0;
            final int rightOverlapDim = mOverlaping && overlapPos == Gravity.END ? scaledOverlapDim : 0;

            final int startX = Math.round(-rect.left);
            final int minX, maxX, minY, maxY;


            if (viewWidth + scaledOverlapDim < rect.width()) {
                minX = leftOverlapDim;
                maxX = Math.round(rect.width() - viewWidth - rightOverlapDim);
            } else {
                minX = maxX = startX;
            }

            final int startY = Math.round(-rect.top);
            if (viewHeight < rect.height()) {
                minY = 0;
                maxY = Math.round(rect.height() - viewHeight);
            } else {
                minY = maxY = startY;
            }

            mCurrentX = startX;
            mCurrentY = startY;

            if (DEBUG) {
                LogManager.getLogger().d(
                        LOG_TAG,
                        "fling. StartX:" + startX + " StartY:" + startY
                                + " MaxX:" + maxX + " MaxY:" + maxY);
            }

            // If we actually can move, fling the scroller
            if (startX != maxX || startY != maxY) {
                mScroller.fling(startX, startY, velocityX, velocityY, minX,
                        maxX, minY, maxY, 0, 0);
            }
        }

        @Override
        public void run() {
            if (mScroller.isFinished()) {
                return; // remaining post that should not be handled
            }

            ImageView imageView = getImageView();
            if (null != imageView && mScroller.computeScrollOffset()) {

                final int newX = mScroller.getCurrX();
                final int newY = mScroller.getCurrY();

                if (DEBUG) {
                    LogManager.getLogger().d(
                            LOG_TAG,
                            "fling run(). CurrentX:" + mCurrentX + " CurrentY:"
                                    + mCurrentY + " NewX:" + newX + " NewY:"
                                    + newY);
                }

                mSuppMatrix.postTranslate(mCurrentX - newX, mCurrentY - newY);
                shouldComputeMatrix = true;
                setImageViewMatrix(getDrawMatrix());

                mCurrentX = newX;
                mCurrentY = newY;

                // Post On animation
                Compat.postOnAnimation(imageView, this);
            }
        }
    }
}

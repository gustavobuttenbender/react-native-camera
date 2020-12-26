package org.reactnative.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.CamcorderProfile;
import android.os.Build;
import androidx.core.content.ContextCompat;

import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.os.AsyncTask;
import com.facebook.react.bridge.*;
import com.facebook.react.uimanager.ThemedReactContext;
import com.google.android.cameraview.CameraView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import org.reactnative.barcodedetector.RNBarcodeDetector;
import org.reactnative.camera.tasks.*;
import org.reactnative.camera.utils.RNFileUtils;
import org.reactnative.facedetector.RNFaceDetector;
import org.reactnative.imagelabeler.RNImageLabeler;
import org.reactnative.objectdetector.RNObjectDetector;
import org.reactnative.posedetector.RNPoseDetector;


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RNCameraView extends CameraView implements LifecycleEventListener, BarCodeScannerAsyncTaskDelegate, FaceDetectorAsyncTaskDelegate,
    BarcodeDetectorAsyncTaskDelegate, TextRecognizerAsyncTaskDelegate, ImageLabelerAsyncTaskDelegate, ObjectDetectorAsyncTaskDelegate, PoseDetectorAsyncTaskDelegate, PictureSavedDelegate {
  private ThemedReactContext mThemedReactContext;
  private Queue<Promise> mPictureTakenPromises = new ConcurrentLinkedQueue<>();
  private Map<Promise, ReadableMap> mPictureTakenOptions = new ConcurrentHashMap<>();
  private Map<Promise, File> mPictureTakenDirectories = new ConcurrentHashMap<>();
  private Promise mVideoRecordedPromise;
  private List<String> mBarCodeTypes = null;
  private boolean mDetectedImageInEvent = false;

  private ScaleGestureDetector mScaleGestureDetector;
  private GestureDetector mGestureDetector;


  private boolean mIsPaused = false;
  private boolean mIsNew = true;
  private boolean invertImageData = false;
  private Boolean mIsRecording = false;
  private Boolean mIsRecordingInterrupted = false;
  private boolean mUseNativeZoom=false;

  // Concurrency lock for scanners to avoid flooding the runtime
  public volatile boolean barCodeScannerTaskLock = false;
  public volatile boolean faceDetectorTaskLock = false;
  public volatile boolean googleBarcodeDetectorTaskLock = false;
  public volatile boolean textRecognizerTaskLock = false;
  public volatile boolean imageLabelerTaskLock = false;
  public volatile boolean objectDetectorTaskLock = false;
  public volatile boolean poseDetectorTaskLock = false;

  // Scanning-related properties
  private MultiFormatReader mMultiFormatReader;
  private RNFaceDetector mFaceDetector;
  private RNBarcodeDetector mGoogleBarcodeDetector;
  private RNImageLabeler mImageLabeler;
  private RNPoseDetector mPoseDetector;
  private RNObjectDetector mObjectDetector;
  private boolean mShouldDetectFaces = false;
  private boolean mShouldGoogleDetectBarcodes = false;
  private boolean mShouldScanBarCodes = false;
  private boolean mShouldRecognizeText = false;
  private boolean mShouldDetectTouches = false;
  private boolean mShouldDetectLabels = false;
  private boolean mShouldDetectPose = false;
  private boolean mShouldDetectObjects = false;
  private int mFaceDetectorMode = RNFaceDetector.FAST_MODE;
  private int mFaceDetectionLandmarks = RNFaceDetector.NO_LANDMARKS;
  private int mFaceDetectionClassifications = RNFaceDetector.NO_CLASSIFICATIONS;
  private int mGoogleVisionBarCodeType = RNBarcodeDetector.ALL_FORMATS;
  private int mGoogleVisionBarCodeMode = RNBarcodeDetector.NORMAL_MODE;
  private boolean mTrackingEnabled = true;
  private int mPaddingX;
  private int mPaddingY;

  // Limit Android Scan Area
  private boolean mLimitScanArea = false;
  private float mScanAreaX = 0.0f;
  private float mScanAreaY = 0.0f;
  private float mScanAreaWidth = 0.0f;
  private float mScanAreaHeight = 0.0f;
  private int mCameraViewWidth = 0;
  private int mCameraViewHeight = 0;

  public RNCameraView(ThemedReactContext themedReactContext) {
    super(themedReactContext, true);
    mThemedReactContext = themedReactContext;
    themedReactContext.addLifecycleEventListener(this);

    addCallback(new Callback() {
      @Override
      public void onCameraOpened(CameraView cameraView) {
        RNCameraViewHelper.emitCameraReadyEvent(cameraView);
      }

      @Override
      public void onMountError(CameraView cameraView) {
        RNCameraViewHelper.emitMountErrorEvent(cameraView, "Camera view threw an error - component could not be rendered.");
      }

      @Override
      public void onPictureTaken(CameraView cameraView, final byte[] data, int deviceOrientation) {
        Promise promise = mPictureTakenPromises.poll();
        ReadableMap options = mPictureTakenOptions.remove(promise);
        if (options.hasKey("fastMode") && options.getBoolean("fastMode")) {
            promise.resolve(null);
        }
        final File cacheDirectory = mPictureTakenDirectories.remove(promise);
        if(Build.VERSION.SDK_INT >= 11/*HONEYCOMB*/) {
          new ResolveTakenPictureAsyncTask(data, promise, options, cacheDirectory, deviceOrientation, RNCameraView.this)
                  .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
          new ResolveTakenPictureAsyncTask(data, promise, options, cacheDirectory, deviceOrientation, RNCameraView.this)
                  .execute();
        }
        RNCameraViewHelper.emitPictureTakenEvent(cameraView);
      }

      @Override
      public void onRecordingStart(CameraView cameraView, String path, int videoOrientation, int deviceOrientation) {
        WritableMap result = Arguments.createMap();
        result.putInt("videoOrientation", videoOrientation);
        result.putInt("deviceOrientation", deviceOrientation);
        result.putString("uri", RNFileUtils.uriFromFile(new File(path)).toString());
        RNCameraViewHelper.emitRecordingStartEvent(cameraView, result);
      }

      @Override
      public void onRecordingEnd(CameraView cameraView) {
        RNCameraViewHelper.emitRecordingEndEvent(cameraView);
      }

      @Override
      public void onVideoRecorded(CameraView cameraView, String path, int videoOrientation, int deviceOrientation) {
        if (mVideoRecordedPromise != null) {
          if (path != null) {
            WritableMap result = Arguments.createMap();
            result.putBoolean("isRecordingInterrupted", mIsRecordingInterrupted);
            result.putInt("videoOrientation", videoOrientation);
            result.putInt("deviceOrientation", deviceOrientation);
            result.putString("uri", RNFileUtils.uriFromFile(new File(path)).toString());
            mVideoRecordedPromise.resolve(result);
          } else {
            mVideoRecordedPromise.reject("E_RECORDING", "Couldn't stop recording - there is none in progress");
          }
          mIsRecording = false;
          mIsRecordingInterrupted = false;
          mVideoRecordedPromise = null;
        }
      }

      @Override
      public void onFramePreview(CameraView cameraView, byte[] data, int width, int height, int rotation) {
        int correctRotation = RNCameraViewHelper.getCorrectCameraRotation(rotation, getFacing(), getCameraOrientation());
        boolean willCallBarCodeTask = mShouldScanBarCodes && !barCodeScannerTaskLock && cameraView instanceof BarCodeScannerAsyncTaskDelegate;
        boolean willCallFaceTask = mShouldDetectFaces && !faceDetectorTaskLock && cameraView instanceof FaceDetectorAsyncTaskDelegate;
        boolean willCallGoogleBarcodeTask = mShouldGoogleDetectBarcodes && !googleBarcodeDetectorTaskLock && cameraView instanceof BarcodeDetectorAsyncTaskDelegate;
        boolean willCallTextTask = mShouldRecognizeText && !textRecognizerTaskLock && cameraView instanceof TextRecognizerAsyncTaskDelegate;
        boolean willCallLabelTask = mShouldDetectLabels && !imageLabelerTaskLock && cameraView instanceof ImageLabelerAsyncTaskDelegate;
        boolean willCallObjectTask = mShouldDetectObjects && !objectDetectorTaskLock && cameraView instanceof ObjectDetectorAsyncTaskDelegate;
        boolean willCallPoseTask = mShouldDetectPose && !poseDetectorTaskLock && cameraView instanceof PoseDetectorAsyncTaskDelegate;

        if (!willCallBarCodeTask && !willCallFaceTask && !willCallGoogleBarcodeTask && !willCallTextTask && !willCallLabelTask && !willCallObjectTask && !willCallPoseTask) {
          return;
        }

        if (data.length < (1.5 * width * height)) {
            return;
        }

        if (willCallBarCodeTask) {
          barCodeScannerTaskLock = true;
          BarCodeScannerAsyncTaskDelegate delegate = (BarCodeScannerAsyncTaskDelegate) cameraView;
          new BarCodeScannerAsyncTask(delegate, mMultiFormatReader, data, width, height, mLimitScanArea, mScanAreaX, mScanAreaY, mScanAreaWidth, mScanAreaHeight, mCameraViewWidth, mCameraViewHeight, getAspectRatio().toFloat()).execute();
        }

        if (willCallFaceTask) {
          faceDetectorTaskLock = true;
          FaceDetectorAsyncTaskDelegate delegate = (FaceDetectorAsyncTaskDelegate) cameraView;
          new FaceDetectorAsyncTask(delegate, mFaceDetector, data, width, height, correctRotation, getResources().getDisplayMetrics().density, getFacing(), getWidth(), getHeight(), mPaddingX, mPaddingY).execute();
        }

        if (willCallGoogleBarcodeTask) {
          googleBarcodeDetectorTaskLock = true;
          if (mGoogleVisionBarCodeMode == RNBarcodeDetector.NORMAL_MODE) {
            invertImageData = false;
          } else if (mGoogleVisionBarCodeMode == RNBarcodeDetector.ALTERNATE_MODE) {
            invertImageData = !invertImageData;
          } else if (mGoogleVisionBarCodeMode == RNBarcodeDetector.INVERTED_MODE) {
            invertImageData = true;
          }
          if (invertImageData) {
            for (int y = 0; y < data.length; y++) {
              data[y] = (byte) ~data[y];
            }
          }
          BarcodeDetectorAsyncTaskDelegate delegate = (BarcodeDetectorAsyncTaskDelegate) cameraView;
          new BarcodeDetectorAsyncTask(delegate, mGoogleBarcodeDetector, data, width, height,
                  correctRotation, getResources().getDisplayMetrics().density, getFacing(),
                  getWidth(), getHeight(), mPaddingX, mPaddingY).execute();
        }

        if (willCallTextTask) {
          textRecognizerTaskLock = true;
          TextRecognizerAsyncTaskDelegate delegate = (TextRecognizerAsyncTaskDelegate) cameraView;
          new TextRecognizerAsyncTask(delegate, mThemedReactContext, data, width, height, correctRotation, getResources().getDisplayMetrics().density, getFacing(), getWidth(), getHeight(), mPaddingX, mPaddingY).execute();
        }

        if (willCallLabelTask) {
          imageLabelerTaskLock = true;
          ImageLabelerAsyncTaskDelegate delegate = (ImageLabelerAsyncTaskDelegate) cameraView;
          new ImageLabelerAsyncTask(delegate, mImageLabeler, data, width, height, correctRotation, getResources().getDisplayMetrics().density, getFacing(), getWidth(), getHeight(), mPaddingX, mPaddingY).execute();
        }

        if (willCallObjectTask) {
          objectDetectorTaskLock = true;
          ObjectDetectorAsyncTaskDelegate delegate = (ObjectDetectorAsyncTaskDelegate) cameraView;
          new ObjectDetectorAsyncTask(delegate, mObjectDetector, data, width, height, correctRotation, getResources().getDisplayMetrics().density, getFacing(), getWidth(), getHeight(), mPaddingX, mPaddingY).execute();
        }

        if (willCallPoseTask) {
          poseDetectorTaskLock = true;
          PoseDetectorAsyncTaskDelegate delegate = (PoseDetectorAsyncTaskDelegate) cameraView;
          new PoseDetectorAsyncTask(delegate, mPoseDetector, data, width, height, correctRotation, getResources().getDisplayMetrics().density, getFacing(), getWidth(), getHeight(), mPaddingX, mPaddingY).execute();
        }
      }
    });
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    View preview = getView();
    if (null == preview) {
      return;
    }
    float width = right - left;
    float height = bottom - top;
    float ratio = getAspectRatio().toFloat();
    int orientation = getResources().getConfiguration().orientation;
    int correctHeight;
    int correctWidth;
    this.setBackgroundColor(Color.BLACK);
    if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
      if (ratio * height < width) {
        correctHeight = (int) (width / ratio);
        correctWidth = (int) width;
      } else {
        correctWidth = (int) (height * ratio);
        correctHeight = (int) height;
      }
    } else {
      if (ratio * width > height) {
        correctHeight = (int) (width * ratio);
        correctWidth = (int) width;
      } else {
        correctWidth = (int) (height / ratio);
        correctHeight = (int) height;
      }
    }
    int paddingX = (int) ((width - correctWidth) / 2);
    int paddingY = (int) ((height - correctHeight) / 2);
    mPaddingX = paddingX;
    mPaddingY = paddingY;
    preview.layout(paddingX, paddingY, correctWidth + paddingX, correctHeight + paddingY);
  }

  @SuppressLint("all")
  @Override
  public void requestLayout() {
    // React handles this for us, so we don't need to call super.requestLayout();
  }

  public void setBarCodeTypes(List<String> barCodeTypes) {
    mBarCodeTypes = barCodeTypes;
    initBarcodeReader();
  }

  public void setDetectedImageInEvent(boolean detectedImageInEvent) {
    this.mDetectedImageInEvent = detectedImageInEvent;
  }

  public void takePicture(final ReadableMap options, final Promise promise, final File cacheDirectory) {
    mBgHandler.post(new Runnable() {
      @Override
      public void run() {
        mPictureTakenPromises.add(promise);
        mPictureTakenOptions.put(promise, options);
        mPictureTakenDirectories.put(promise, cacheDirectory);

        try {
          RNCameraView.super.takePicture(options);
        } catch (Exception e) {
          mPictureTakenPromises.remove(promise);
          mPictureTakenOptions.remove(promise);
          mPictureTakenDirectories.remove(promise);

          promise.reject("E_TAKE_PICTURE_FAILED", e.getMessage());
        }
      }
    });
  }

  @Override
  public void onPictureSaved(WritableMap response) {
    RNCameraViewHelper.emitPictureSavedEvent(this, response);
  }

  public void record(final ReadableMap options, final Promise promise, final File cacheDirectory) {
    mBgHandler.post(new Runnable() {
      @Override
      public void run() {
        try {
          String path = options.hasKey("path") ? options.getString("path") : RNFileUtils.getOutputFilePath(cacheDirectory, ".mp4");
          int maxDuration = options.hasKey("maxDuration") ? options.getInt("maxDuration") : -1;
          int maxFileSize = options.hasKey("maxFileSize") ? options.getInt("maxFileSize") : -1;
          int fps = options.hasKey("fps") ? options.getInt("fps") : -1;

          CamcorderProfile profile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
          if (options.hasKey("quality")) {
            profile = RNCameraViewHelper.getCamcorderProfile(options.getInt("quality"));
          }
          if (options.hasKey("videoBitrate")) {
            profile.videoBitRate = options.getInt("videoBitrate");
          }

          boolean recordAudio = true;
          if (options.hasKey("mute")) {
            recordAudio = !options.getBoolean("mute");
          }

          int orientation = Constants.ORIENTATION_AUTO;
          if (options.hasKey("orientation")) {
            orientation = options.getInt("orientation");
          }

          if (RNCameraView.super.record(path, maxDuration * 1000, maxFileSize, recordAudio, profile, orientation, fps)) {
            mIsRecording = true;
            mVideoRecordedPromise = promise;
          } else {
            promise.reject("E_RECORDING_FAILED", "Starting video recording failed. Another recording might be in progress.");
          }
        } catch (IOException e) {
          promise.reject("E_RECORDING_FAILED", "Starting video recording failed - could not create video file.");
        }
      }
    });
  }

  /**
   * Initialize the barcode decoder.
   * Supports all iOS codes except [code138, code39mod43, itf14]
   * Additionally supports [codabar, code128, maxicode, rss14, rssexpanded, upc_a, upc_ean]
   */
  private void initBarcodeReader() {
    mMultiFormatReader = new MultiFormatReader();
    EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    EnumSet<BarcodeFormat> decodeFormats = EnumSet.noneOf(BarcodeFormat.class);

    if (mBarCodeTypes != null) {
      for (String code : mBarCodeTypes) {
        String formatString = (String) CameraModule.VALID_BARCODE_TYPES.get(code);
        if (formatString != null) {
          decodeFormats.add(BarcodeFormat.valueOf(formatString));
        }
      }
    }

    hints.put(DecodeHintType.POSSIBLE_FORMATS, decodeFormats);
    mMultiFormatReader.setHints(hints);
  }

  public void setShouldScanBarCodes(boolean shouldScanBarCodes) {
    if (shouldScanBarCodes && mMultiFormatReader == null) {
      initBarcodeReader();
    }
    this.mShouldScanBarCodes = shouldScanBarCodes;
    setScanning(mShouldDetectFaces || mShouldGoogleDetectBarcodes || mShouldScanBarCodes || mShouldRecognizeText || mShouldDetectLabels || mShouldDetectObjects || mShouldDetectPose);
  }

  public void onBarCodeRead(Result barCode, int width, int height, byte[] imageData) {
    String barCodeType = barCode.getBarcodeFormat().toString();
    if (!mShouldScanBarCodes || !mBarCodeTypes.contains(barCodeType)) {
      return;
    }

    final byte[] compressedImage;
    if (mDetectedImageInEvent) {
      try {
        // https://stackoverflow.com/a/32793908/122441
        final YuvImage yuvImage = new YuvImage(imageData, ImageFormat.NV21, width, height, null);
        final ByteArrayOutputStream imageStream = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, imageStream);
        compressedImage = imageStream.toByteArray();
      } catch (Exception e) {
        throw new RuntimeException(String.format("Error decoding imageData from NV21 format (%d bytes)", imageData.length), e);
      }
    } else {
      compressedImage = null;
    }

    RNCameraViewHelper.emitBarCodeReadEvent(this, barCode, width, height, compressedImage);
  }

  public void onBarCodeScanningTaskCompleted() {
    barCodeScannerTaskLock = false;
    if(mMultiFormatReader != null) {
      mMultiFormatReader.reset();
    }
  }

  // Limit Scan Area
  public void setRectOfInterest(float x, float y, float width, float height) {
    this.mLimitScanArea = true;
    this.mScanAreaX = x;
    this.mScanAreaY = y;
    this.mScanAreaWidth = width;
    this.mScanAreaHeight = height;
  }
  public void setCameraViewDimensions(int width, int height) {
    this.mCameraViewWidth = width;
    this.mCameraViewHeight = height;
  }


  public void setShouldDetectTouches(boolean shouldDetectTouches) {
    if(!mShouldDetectTouches && shouldDetectTouches){
      mGestureDetector=new GestureDetector(mThemedReactContext,onGestureListener);
    }else{
      mGestureDetector=null;
    }
    this.mShouldDetectTouches = shouldDetectTouches;
  }

  public void setUseNativeZoom(boolean useNativeZoom){
    if(!mUseNativeZoom && useNativeZoom){
      mScaleGestureDetector = new ScaleGestureDetector(mThemedReactContext,onScaleGestureListener);
    }else{
      mScaleGestureDetector=null;
    }
    mUseNativeZoom=useNativeZoom;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if(mUseNativeZoom) {
      mScaleGestureDetector.onTouchEvent(event);
    }
    if(mShouldDetectTouches){
      mGestureDetector.onTouchEvent(event);
    }
    return true;
  }

  /**
   * Initial setup of the face detector
   */
  private void setupFaceDetector() {
    mFaceDetector = new RNFaceDetector(mThemedReactContext);
    mFaceDetector.setMode(mFaceDetectorMode);
    mFaceDetector.setLandmarkType(mFaceDetectionLandmarks);
    mFaceDetector.setClassificationType(mFaceDetectionClassifications);
    mFaceDetector.setTracking(mTrackingEnabled);
  }

  public void setFaceDetectionLandmarks(int landmarks) {
    mFaceDetectionLandmarks = landmarks;
    if (mFaceDetector != null) {
      mFaceDetector.setLandmarkType(landmarks);
    }
  }

  public void setFaceDetectionClassifications(int classifications) {
    mFaceDetectionClassifications = classifications;
    if (mFaceDetector != null) {
      mFaceDetector.setClassificationType(classifications);
    }
  }

  public void setFaceDetectionMode(int mode) {
    mFaceDetectorMode = mode;
    if (mFaceDetector != null) {
      mFaceDetector.setMode(mode);
    }
  }

  public void setTracking(boolean trackingEnabled) {
    mTrackingEnabled = trackingEnabled;
    if (mFaceDetector != null) {
      mFaceDetector.setTracking(trackingEnabled);
    }
  }

  public void setShouldDetectFaces(boolean shouldDetectFaces) {
    if (shouldDetectFaces && mFaceDetector == null) {
      setupFaceDetector();
    }
    this.mShouldDetectFaces = shouldDetectFaces;
    setScanning(mShouldDetectFaces || mShouldGoogleDetectBarcodes || mShouldScanBarCodes || mShouldRecognizeText || mShouldDetectLabels || mShouldDetectObjects || mShouldDetectPose);
  }

  public void onFacesDetected(WritableArray data) {
    if (!mShouldDetectFaces) {
      return;
    }

    RNCameraViewHelper.emitFacesDetectedEvent(this, data);
  }

  public void onFaceDetectionError(RNFaceDetector faceDetector) {
    if (!mShouldDetectFaces) {
      return;
    }

    RNCameraViewHelper.emitFaceDetectionErrorEvent(this, faceDetector);
  }

  @Override
  public void onFaceDetectingTaskCompleted() {
    faceDetectorTaskLock = false;
  }

  /**
   * Initial setup of the barcode detector
   */
  private void setupBarcodeDetector() {
    mGoogleBarcodeDetector = new RNBarcodeDetector(mThemedReactContext);
    mGoogleBarcodeDetector.setBarcodeType(mGoogleVisionBarCodeType);
  }

  public void setShouldGoogleDetectBarcodes(boolean shouldDetectBarcodes) {
    if (shouldDetectBarcodes && mGoogleBarcodeDetector == null) {
      setupBarcodeDetector();
    }
    this.mShouldGoogleDetectBarcodes = shouldDetectBarcodes;
    setScanning(mShouldDetectFaces || mShouldGoogleDetectBarcodes || mShouldScanBarCodes || mShouldRecognizeText || mShouldDetectLabels || mShouldDetectObjects || mShouldDetectPose);
  }

  public void setGoogleVisionBarcodeType(int barcodeType) {
    mGoogleVisionBarCodeType = barcodeType;
    if (mGoogleBarcodeDetector != null) {
      mGoogleBarcodeDetector.setBarcodeType(barcodeType);
    }
  }

  public void setGoogleVisionBarcodeMode(int barcodeMode) {
    mGoogleVisionBarCodeMode = barcodeMode;
  }

  public void onBarcodesDetected(WritableArray barcodesDetected, int width, int height, byte[] imageData) {
    if (!mShouldGoogleDetectBarcodes) {
      return;
    }

    // See discussion in https://github.com/react-native-community/react-native-camera/issues/2786
    final byte[] compressedImage;
    if (mDetectedImageInEvent) {
      try {
        // https://stackoverflow.com/a/32793908/122441
        final YuvImage yuvImage = new YuvImage(imageData, ImageFormat.NV21, width, height, null);
        final ByteArrayOutputStream imageStream = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, imageStream);
        compressedImage = imageStream.toByteArray();
      } catch (Exception e) {
        throw new RuntimeException(String.format("Error decoding imageData from NV21 format (%d bytes)", imageData.length), e);
      }
    } else {
      compressedImage = null;
    }

    RNCameraViewHelper.emitBarcodesDetectedEvent(this, barcodesDetected, compressedImage);
  }

  public void onBarcodeDetectionError(RNBarcodeDetector barcodeDetector) {
    if (!mShouldGoogleDetectBarcodes) {
      return;
    }

    RNCameraViewHelper.emitBarcodeDetectionErrorEvent(this, barcodeDetector);
  }

  @Override
  public void onBarcodeDetectingTaskCompleted() {
    googleBarcodeDetectorTaskLock = false;
  }

  /**
   * Initial setup of the image labeler
   */
  private void setupImageLabeler() {
    mImageLabeler = new RNImageLabeler(mThemedReactContext);
  }

  public void setShouldDetectLabels(boolean shouldDetectLabels) {
    if (shouldDetectLabels && mImageLabeler == null) {
      setupImageLabeler();
    }
    this.mShouldDetectLabels = shouldDetectLabels;
    setScanning(mShouldDetectFaces || mShouldGoogleDetectBarcodes || mShouldScanBarCodes || mShouldRecognizeText || mShouldDetectLabels || mShouldDetectObjects || mShouldDetectPose);
  }

  public void onLabelsDetected(WritableArray labelsDetected) {
    if (!mShouldDetectLabels) {
      return;
    }
    RNCameraViewHelper.emitLabelsDetectedEvent(this, labelsDetected);
  }

  public void onImageLabelingError(RNImageLabeler imageLabeler) {
    if (!mShouldDetectLabels) {
      return;
    }

    RNCameraViewHelper.emitImageLabelingErrorEvent(this, imageLabeler);
  }

  @Override
  public void onImageLabelingTaskCompleted() {
    imageLabelerTaskLock = false;
  }

  /**
   * Initial setup of the pose detector
   */
  private void setupPoseDetector() {
    mPoseDetector = new RNPoseDetector(mThemedReactContext);
  }

  public void setShouldDetectPose(boolean shouldDetectPose) {
    if (shouldDetectPose && mPoseDetector == null) {
      setupPoseDetector();
    }
    this.mShouldDetectPose = shouldDetectPose;
    setScanning(mShouldDetectFaces || mShouldGoogleDetectBarcodes || mShouldScanBarCodes || mShouldRecognizeText || mShouldDetectLabels || mShouldDetectPose || mShouldDetectObjects);
  }

  public void onPoseDetected(WritableMap pose) {
    if (!mShouldDetectPose) {
      return;
    }
    RNCameraViewHelper.emitPoseDetectedEvent(this, pose);
  }

  public void onPoseDetectionError(RNPoseDetector poseDetector) {
    if (!mShouldDetectLabels) {
      return;
    }

    RNCameraViewHelper.emitPoseDetectionErrorEvent(this, poseDetector);
  }

  @Override
  public void onPoseDetectionTaskCompleted() {
    poseDetectorTaskLock = false;
  }

  /**
   * Initial setup of the object detector
   */
  private void setupObjectDetector() {
    mObjectDetector = new RNObjectDetector(mThemedReactContext);
  }

  public void setShouldDetectObjects(boolean shouldDetectObjects) {
    if (shouldDetectObjects && mObjectDetector == null) {
      setupObjectDetector();
    }
    this.mShouldDetectObjects = shouldDetectObjects;
    setScanning(mShouldDetectFaces || mShouldGoogleDetectBarcodes || mShouldScanBarCodes || mShouldRecognizeText || mShouldDetectLabels || mShouldDetectObjects || mShouldDetectPose);
  }

  public void onObjectsDetected(WritableArray objectsDetected) {
    if (!mShouldDetectObjects) {
      return;
    }
    RNCameraViewHelper.emitObjectsDetectedEvent(this, objectsDetected);
  }

  public void onObjectDetectionError(RNObjectDetector objectDetector) {
    if (!mShouldDetectObjects) {
      return;
    }

    RNCameraViewHelper.emitObjectDetectionErrorEvent(this, objectDetector);
  }

  @Override
  public void onObjectDetectionTaskCompleted() {
    objectDetectorTaskLock = false;
  }


  /**
   *
   * Text recognition
   */

  public void setShouldRecognizeText(boolean shouldRecognizeText) {
    this.mShouldRecognizeText = shouldRecognizeText;
    setScanning(mShouldDetectFaces || mShouldGoogleDetectBarcodes || mShouldScanBarCodes || mShouldRecognizeText || mShouldDetectLabels || mShouldDetectObjects || mShouldDetectPose);
  }

  public void onTextRecognized(WritableArray serializedData) {
    if (!mShouldRecognizeText) {
      return;
    }

    RNCameraViewHelper.emitTextRecognizedEvent(this, serializedData);
  }

  @Override
  public void onTextRecognizerTaskCompleted() {
    textRecognizerTaskLock = false;
  }

  /**
  *
  * End Text Recognition */

  @Override
  public void onHostResume() {
    if (hasCameraPermissions()) {
      mBgHandler.post(new Runnable() {
        @Override
        public void run() {
          if ((mIsPaused && !isCameraOpened()) || mIsNew) {
            mIsPaused = false;
            mIsNew = false;
            start();
          }
        }
      });
    } else {
      RNCameraViewHelper.emitMountErrorEvent(this, "Camera permissions not granted - component could not be rendered.");
    }
  }

  @Override
  public void onHostPause() {
    if (mIsRecording) {
      mIsRecordingInterrupted = true;
    }
    if (!mIsPaused && isCameraOpened()) {
      mIsPaused = true;
      stop();
    }
  }

  @Override
  public void onHostDestroy() {
    if (mFaceDetector != null) {
      mFaceDetector.release();
    }
    if (mGoogleBarcodeDetector != null) {
      mGoogleBarcodeDetector.release();
    }
    mMultiFormatReader = null;
    mThemedReactContext.removeLifecycleEventListener(this);

    // camera release can be quite expensive. Run in on bg handler
    // and cleanup last once everything has finished
    mBgHandler.post(new Runnable() {
        @Override
        public void run() {
          stop();
          cleanup();
        }
      });
  }
  private void onZoom(float scale){
    float currentZoom=getZoom();
    float nextZoom=currentZoom+(scale-1.0f);

    if(nextZoom > currentZoom){
      setZoom(Math.min(nextZoom,1.0f));
    }else{
      setZoom(Math.max(nextZoom,0.0f));
    }

  }

  private boolean hasCameraPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      int result = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA);
      return result == PackageManager.PERMISSION_GRANTED;
    } else {
      return true;
    }
  }
  private int scalePosition(float raw){
    Resources resources = getResources();
    Configuration config = resources.getConfiguration();
    DisplayMetrics dm = resources.getDisplayMetrics();
    return (int)(raw/ dm.density);
  }
  private GestureDetector.SimpleOnGestureListener onGestureListener = new GestureDetector.SimpleOnGestureListener(){
    @Override
    public boolean onSingleTapUp(MotionEvent e) {
      RNCameraViewHelper.emitTouchEvent(RNCameraView.this,false,scalePosition(e.getX()),scalePosition(e.getY()));
      return true;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
      RNCameraViewHelper.emitTouchEvent(RNCameraView.this,true,scalePosition(e.getX()),scalePosition(e.getY()));
      return true;
    }
  };
  private ScaleGestureDetector.OnScaleGestureListener onScaleGestureListener = new ScaleGestureDetector.OnScaleGestureListener() {

    @Override
    public boolean onScale(ScaleGestureDetector scaleGestureDetector) {
      onZoom(scaleGestureDetector.getScaleFactor());
      return true;
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector scaleGestureDetector) {
      onZoom(scaleGestureDetector.getScaleFactor());
      return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector scaleGestureDetector) {
    }

  };

}

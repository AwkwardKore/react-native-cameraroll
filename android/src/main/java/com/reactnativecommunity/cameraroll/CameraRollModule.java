/**
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.reactnativecommunity.cameraroll;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.text.TextUtils;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.GuardedAsyncTask;
import com.facebook.react.bridge.JSApplicationIllegalArgumentException;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.PermissionAwareActivity;
import com.facebook.react.modules.core.PermissionListener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import javax.annotation.Nullable;

/**
 * {@link NativeModule} that allows JS to interact with the photos and videos on the device (i.e.
 * {@link MediaStore.Images}).
 */
@ReactModule(name = CameraRollModule.NAME)
public class CameraRollModule extends ReactContextBaseJavaModule {

  public static final String NAME = "RNCCameraRoll";

  private static final String ERROR_ACTIVITY_DOES_NOT_EXIST = "E_ACTIVITY_DOES_NOT_EXIST";
  private static final String ERROR_UNABLE_TO_LOAD = "E_UNABLE_TO_LOAD";
  private static final String ERROR_UNABLE_TO_LOAD_PERMISSION = "E_UNABLE_TO_LOAD_PERMISSION";
  private static final String ERROR_UNABLE_TO_SAVE = "E_UNABLE_TO_SAVE";
  private static final String ERROR_UNABLE_TO_DELETE = "E_UNABLE_TO_DELETE";
  private static final String ERROR_UNABLE_TO_FILTER = "E_UNABLE_TO_FILTER";
  private static final String ERROR_PERMISSIONS_MISSING = "E_PERMISSION_MISSING";
  private static final String ERROR_CALLBACK_ERROR = "E_CALLBACK_ERROR";

  private static final String ASSET_TYPE_PHOTOS = "Photos";
  private static final String ASSET_TYPE_VIDEOS = "Videos";
  private static final String ASSET_TYPE_ALL = "All";

  private static final String[] PROJECTION = {
    Images.Media._ID,
    Images.Media.MIME_TYPE,
    Images.Media.BUCKET_DISPLAY_NAME,
    Images.Media.DATE_TAKEN,
    MediaStore.MediaColumns.WIDTH,
    MediaStore.MediaColumns.HEIGHT,
    Images.Media.LONGITUDE,
    Images.Media.LATITUDE,
    MediaStore.MediaColumns.DATA
  };

  private static final String SELECTION_BUCKET = Images.Media.BUCKET_DISPLAY_NAME + " = ?";
  private static final String SELECTION_DATE_TAKEN = Images.Media.DATE_TAKEN + " < ?";

  public CameraRollModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @Override
  public String getName() {
    return NAME;
  }

  /**
   * Save an image to the gallery (i.e. {@link MediaStore.Images}). This copies the original file
   * from wherever it may be to the external storage pictures directory, so that it can be scanned
   * by the MediaScanner.
   *
   * @param uri the file:// URI of the image to save
   * @param promise to be resolved or rejected
   */
  @ReactMethod
  public void saveToCameraRoll(String uri, ReadableMap options, Promise promise) {
    new SaveToCameraRoll(getReactApplicationContext(), Uri.parse(uri), options, promise)
        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private static class SaveToCameraRoll extends GuardedAsyncTask<Void, Void> {

    private final Context mContext;
    private final Uri mUri;
    private final Promise mPromise;
    private final ReadableMap mOptions;

    public SaveToCameraRoll(ReactContext context, Uri uri, ReadableMap options, Promise promise) {
      super(context);
      mContext = context;
      mUri = uri;
      mPromise = promise;
      mOptions = options;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      File source = new File(mUri.getPath());
      FileChannel input = null, output = null;
      try {
        File environment;
        if ("mov".equals(mOptions.getString("type"))) {
          environment = Environment.getExternalStoragePublicDirectory(
                  Environment.DIRECTORY_MOVIES);
        } else {
          environment = Environment.getExternalStoragePublicDirectory(
                  Environment.DIRECTORY_PICTURES);
        }
        File exportDir;
        if (!"".equals(mOptions.getString("album"))) {
          exportDir = new File(environment, mOptions.getString("album"));
          if (!exportDir.exists() && !exportDir.mkdirs()) {
            mPromise.reject(ERROR_UNABLE_TO_LOAD, "Album Directory not created. Did you request WRITE_EXTERNAL_STORAGE?");
            return;
          }
        } else {
          exportDir = environment;
        }

        if (!exportDir.isDirectory()) {
          mPromise.reject(ERROR_UNABLE_TO_LOAD, "External media storage directory not available");
          return;
        }
        File dest = new File(exportDir, source.getName());
        int n = 0;
        String fullSourceName = source.getName();
        String sourceName, sourceExt;
        if (fullSourceName.indexOf('.') >= 0) {
          sourceName = fullSourceName.substring(0, fullSourceName.lastIndexOf('.'));
          sourceExt = fullSourceName.substring(fullSourceName.lastIndexOf('.'));
        } else {
          sourceName = fullSourceName;
          sourceExt = "";
        }
        while (!dest.createNewFile()) {
          dest = new File(exportDir, sourceName + "_" + (n++) + sourceExt);
        }
        input = new FileInputStream(source).getChannel();
        output = new FileOutputStream(dest).getChannel();
        output.transferFrom(input, 0, input.size());
        input.close();
        output.close();

        MediaScannerConnection.scanFile(
            mContext,
            new String[]{dest.getAbsolutePath()},
            null,
            new MediaScannerConnection.OnScanCompletedListener() {
              @Override
              public void onScanCompleted(String path, Uri uri) {
                if (uri != null) {
                  mPromise.resolve(uri.toString());
                } else {
                  mPromise.reject(ERROR_UNABLE_TO_SAVE, "Could not add image to gallery");
                }
              }
            });
      } catch (IOException e) {
        mPromise.reject(e);
      } finally {
        if (input != null && input.isOpen()) {
          try {
            input.close();
          } catch (IOException e) {
            FLog.e(ReactConstants.TAG, "Could not close input channel", e);
          }
        }
        if (output != null && output.isOpen()) {
          try {
            output.close();
          } catch (IOException e) {
            FLog.e(ReactConstants.TAG, "Could not close output channel", e);
          }
        }
      }
    }
  }

  @ReactMethod
  public void getAlbumNames(final ReadableMap options, final Promise promise) {
    if (activity == null) {
      promise.reject(ERROR_ACTIVITY_DOES_NOT_EXIST, "Activity doesnt't exist");
      return;
    }

    premissionsCheck(activity, promise, Collections.singletonList(Manifest.permission.WRITE_EXTERNAL_STORAGE), new Callable<Void>() {
      @Override
      public Void call() {
        String[] PROJECTION_BUCKET = {
          MediaStore.Images.ImageColumns.BUCKET_ID,
          MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
          MediaStore.Images.ImageColumns.DATA_TAKEN,
          MediaStore.Images.ImageColumns.DATA,
          "count(" + MediaStore.Images.ImageColumns.BUCKET_ID + ") as count"
        };

        String BUCKET_GROUP_BY = "1) GROUP BY 1,(2";
        String BUCKET_ORDER_BY = "MAX(" + MediaStore.Images.ImageColumns.DATE_TAKEN + ") DESC";

        Cursor cursor = getReactApplicationContext().getContentResolver().query(
            MediaStore.Image.Media.EXTERNAL_CONTENT_URI,
            PROJECTION_BUCKET,
            BUCKET_GROUP_BY,
            null,
            BUCKET_ORDER_BY
        );

        WritableArray list = Arguments.createArray();

        if (cursor != null && cursor.moveToFirst()) {
          String bucket;
          String date;
          String data;
          String count;
          int bucketColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
          int dateColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN);
          int dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
          int countColumn = cursor.getColumnIndex("count");

          do {
            bucket = cursor.getString(bucketColumn);
            date = cursor.getString(dateColumn);
            data = cursor.getString(dataColumn);
            count = cursor.getString(countColumn);

            WritableMap image = Arguments.createMap();
            setWritableMap(image, "count", count);
            setWritableMap(image, "date", date);
            setWritableMap(image, "cover", "file://" + data);
            setWritableMap(image, "name", bucket);

            list.pushMap(image);
          } while (cursor.moveToNext());

          cursor.close();
        }

        promise.resolve(list);

        return null;
      }
    });
  }

  private void setWritableMap(WritableMap map, String key, String value) {
    if (value == null) {
      map.putNull(key);
    }
    else {
      map.putString(key, value);
    }
  }

  /**
   * Get photos from {@link MediaStore.Images}, most recent first.
   *
   * @param params a map containing the following keys:
   *        <ul>
   *          <li>first (mandatory): a number representing the number of photos to fetch</li>
   *          <li>
   *            after (optional): a cursor that matches page_info[end_cursor] returned by a
   *            previous call to {@link #getPhotos}
   *          </li>
   *          <li>groupName (optional): an album name</li>
   *          <li>
   *            mimeType (optional): restrict returned images to a specific mimetype (e.g.
   *            image/jpeg)
   *          </li>
   *          <li>
   *            assetType (optional): chooses between either photos or videos from the camera roll.
   *            Valid values are "Photos" or "Videos". Defaults to photos.
   *          </li>
   *        </ul>
   * @param promise the Promise to be resolved when the photos are loaded; for a format of the
   *        parameters passed to this callback, see {@code getPhotosReturnChecker} in CameraRoll.js
   */
  @ReactMethod
  public void getPhotos(final ReadableMap params, final Promise promise) {
    int first = params.getInt("first");
    String after = params.hasKey("after") ? params.getString("after") : null;
    String groupName = params.hasKey("groupName") ? params.getString("groupName") : null;
    String assetType = params.hasKey("assetType") ? params.getString("assetType") : ASSET_TYPE_PHOTOS;
    ReadableArray mimeTypes = params.hasKey("mimeTypes")
        ? params.getArray("mimeTypes")
        : null;

    new GetMediaTask(
          getReactApplicationContext(),
          first,
          after,
          groupName,
          mimeTypes,
          assetType,
          promise)
          .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private static class GetMediaTask extends GuardedAsyncTask<Void, Void> {
    private final Context mContext;
    private final int mFirst;
    private final @Nullable String mAfter;
    private final @Nullable String mGroupName;
    private final @Nullable ReadableArray mMimeTypes;
    private final Promise mPromise;
    private final String mAssetType;

    private GetMediaTask(
        ReactContext context,
        int first,
        @Nullable String after,
        @Nullable String groupName,
        @Nullable ReadableArray mimeTypes,
        String assetType,
        Promise promise) {
      super(context);
      mContext = context;
      mFirst = first;
      mAfter = after;
      mGroupName = groupName;
      mMimeTypes = mimeTypes;
      mPromise = promise;
      mAssetType = assetType;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      StringBuilder selection = new StringBuilder("1");
      List<String> selectionArgs = new ArrayList<>();
      if (!TextUtils.isEmpty(mAfter)) {
        selection.append(" AND " + SELECTION_DATE_TAKEN);
        selectionArgs.add(mAfter);
      }
      if (!TextUtils.isEmpty(mGroupName)) {
        selection.append(" AND " + SELECTION_BUCKET);
        selectionArgs.add(mGroupName);
      }

      if (mAssetType.equals(ASSET_TYPE_PHOTOS)) {
        selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " = "
          + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE);
      } else if (mAssetType.equals(ASSET_TYPE_VIDEOS)) {
        selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " = "
          + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO);
      } else if (mAssetType.equals(ASSET_TYPE_ALL)) {
        selection.append(" AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " IN ("
          + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ","
          + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + ")");
      } else {
        mPromise.reject(
          ERROR_UNABLE_TO_FILTER,
          "Invalid filter option: '" + mAssetType + "'. Expected one of '"
            + ASSET_TYPE_PHOTOS + "', '" + ASSET_TYPE_VIDEOS + "' or '" + ASSET_TYPE_ALL + "'."
        );
        return;
      }


      if (mMimeTypes != null && mMimeTypes.size() > 0) {
        selection.append(" AND " + Images.Media.MIME_TYPE + " IN (");
        for (int i = 0; i < mMimeTypes.size(); i++) {
          selection.append("?,");
          selectionArgs.add(mMimeTypes.getString(i));
        }
        selection.replace(selection.length() - 1, selection.length(), ")");
      }
      WritableMap response = new WritableNativeMap();
      ContentResolver resolver = mContext.getContentResolver();
      // using LIMIT in the sortOrder is not explicitly supported by the SDK (which does not support
      // setting a limit at all), but it works because this specific ContentProvider is backed by
      // an SQLite DB and forwards parameters to it without doing any parsing / validation.
      try {
        Cursor media = resolver.query(
            MediaStore.Files.getContentUri("external"),
            PROJECTION,
            selection.toString(),
            selectionArgs.toArray(new String[selectionArgs.size()]),
            Images.Media.DATE_ADDED + " DESC, " + Images.Media.DATE_MODIFIED + " DESC LIMIT " +
                (mFirst + 1)); // set LIMIT to first + 1 so that we know how to populate page_info
        if (media == null) {
          mPromise.reject(ERROR_UNABLE_TO_LOAD, "Could not get media");
        } else {
          try {
            putEdges(resolver, media, response, mFirst);
            putPageInfo(media, response, mFirst);
          } finally {
            media.close();
            mPromise.resolve(response);
          }
        }
      } catch (SecurityException e) {
        mPromise.reject(
            ERROR_UNABLE_TO_LOAD_PERMISSION,
            "Could not get media: need READ_EXTERNAL_STORAGE permission",
            e);
      }
    }
  }

  private static void putPageInfo(Cursor media, WritableMap response, int limit) {
    WritableMap pageInfo = new WritableNativeMap();
    pageInfo.putBoolean("has_next_page", limit < media.getCount());
    if (limit < media.getCount()) {
      media.moveToPosition(limit - 1);
      pageInfo.putString(
          "end_cursor",
          media.getString(media.getColumnIndex(Images.Media.DATE_TAKEN)));
    }
    response.putMap("page_info", pageInfo);
  }

  private static void putEdges(
      ContentResolver resolver,
      Cursor media,
      WritableMap response,
      int limit) {
    WritableArray edges = new WritableNativeArray();
    media.moveToFirst();
    int idIndex = media.getColumnIndex(Images.Media._ID);
    int mimeTypeIndex = media.getColumnIndex(Images.Media.MIME_TYPE);
    int groupNameIndex = media.getColumnIndex(Images.Media.BUCKET_DISPLAY_NAME);
    int dateTakenIndex = media.getColumnIndex(Images.Media.DATE_TAKEN);
    int widthIndex = media.getColumnIndex(MediaStore.MediaColumns.WIDTH);
    int heightIndex = media.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
    int longitudeIndex = media.getColumnIndex(Images.Media.LONGITUDE);
    int latitudeIndex = media.getColumnIndex(Images.Media.LATITUDE);
    int dataIndex = media.getColumnIndex(MediaStore.MediaColumns.DATA);

    for (int i = 0; i < limit && !media.isAfterLast(); i++) {
      WritableMap edge = new WritableNativeMap();
      WritableMap node = new WritableNativeMap();
      boolean imageInfoSuccess =
          putImageInfo(resolver, media, node, idIndex, widthIndex, heightIndex, dataIndex, mimeTypeIndex);
      if (imageInfoSuccess) {
        putBasicNodeInfo(media, node, mimeTypeIndex, groupNameIndex, dateTakenIndex);
        putLocationInfo(media, node, longitudeIndex, latitudeIndex);

        edge.putMap("node", node);
        edges.pushMap(edge);
      } else {
        // we skipped an image because we couldn't get its details (e.g. width/height), so we
        // decrement i in order to correctly reach the limit, if the cursor has enough rows
        i--;
      }
      media.moveToNext();
    }
    response.putArray("edges", edges);
  }

  private static void putBasicNodeInfo(
      Cursor media,
      WritableMap node,
      int mimeTypeIndex,
      int groupNameIndex,
      int dateTakenIndex) {
    node.putString("type", media.getString(mimeTypeIndex));
    node.putString("group_name", media.getString(groupNameIndex));
    node.putDouble("timestamp", media.getLong(dateTakenIndex) / 1000d);
  }

  private static boolean putImageInfo(
      ContentResolver resolver,
      Cursor media,
      WritableMap node,
      int idIndex,
      int widthIndex,
      int heightIndex,
      int dataIndex,
      int mimeTypeIndex) {
    WritableMap image = new WritableNativeMap();
    Uri photoUri = Uri.parse("file://" + media.getString(dataIndex));
    File file = new File(media.getString(dataIndex));
    String strFileName = file.getName();
    image.putString("uri", photoUri.toString());
    image.putString("filename", strFileName);
    float width = media.getInt(widthIndex);
    float height = media.getInt(heightIndex);

    String mimeType = media.getString(mimeTypeIndex);

    if (mimeType != null
        && mimeType.startsWith("video")) {
      try {
        AssetFileDescriptor photoDescriptor = resolver.openAssetFileDescriptor(photoUri, "r");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(photoDescriptor.getFileDescriptor());

        try {
          if (width <= 0 || height <= 0) {
            width =
                Integer.parseInt(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            height =
                Integer.parseInt(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
          }
          int timeInMillisec =
              Integer.parseInt(
                  retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
          int playableDuration = timeInMillisec / 1000;
          image.putInt("playableDuration", playableDuration);
        } catch (NumberFormatException e) {
          FLog.e(
              ReactConstants.TAG,
              "Number format exception occurred while trying to fetch video metadata for "
                  + photoUri.toString(),
              e);
          return false;
        } finally {
          retriever.release();
          photoDescriptor.close();
        }
      } catch (Exception e) {
        FLog.e(ReactConstants.TAG, "Could not get video metadata for " + photoUri.toString(), e);
        return false;
      }
    }

    if (width <= 0 || height <= 0) {
      try {
        AssetFileDescriptor photoDescriptor = resolver.openAssetFileDescriptor(photoUri, "r");
        BitmapFactory.Options options = new BitmapFactory.Options();
        // Set inJustDecodeBounds to true so we don't actually load the Bitmap, but only get its
        // dimensions instead.
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(photoDescriptor.getFileDescriptor(), null, options);
        width = options.outWidth;
        height = options.outHeight;
        photoDescriptor.close();
      } catch (IOException e) {
        FLog.e(ReactConstants.TAG, "Could not get width/height for " + photoUri.toString(), e);
        return false;
      }
    }
    image.putDouble("width", width);
    image.putDouble("height", height);
    node.putMap("image", image);

    return true;
  }

  private static void putLocationInfo(
      Cursor media,
      WritableMap node,
      int longitudeIndex,
      int latitudeIndex) {
    double longitude = media.getDouble(longitudeIndex);
    double latitude = media.getDouble(latitudeIndex);
    if (longitude > 0 || latitude > 0) {
      WritableMap location = new WritableNativeMap();
      location.putDouble("longitude", longitude);
      location.putDouble("latitude", latitude);
      node.putMap("location", location);
    }
  }

  private void permissionsCheck(final Activity activity, final Promise promise, final List<String> requirePermissions, final Callable<Void> callback) {
    List<String> missingPermissions = new ArrayList<>();

    for (String permission : requiredPermissions) {
      int status = ActivityCompat.checkSelfPermission(activity, permission);
      if(status != PackageManager.PERMISSION_GRANTED) {
        missingPermissions.add(permission);
      }
    }

    if (!missingPermissions.isEmpty()) {
      ((PermissionAwareActivity) activity).requestPermissions(missingPermissions.toArray(new String[missingPermissions.size()]), 1, new PermissionListener() {
        @Override
        public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
          if(requestCode == 1) {
            for (int grantResult : grantResults) {
              if(grantResult == PackageManager.PERMISSION_DENIED) {
                promise.reject(ERROR_PERMISSIONS_MISSING, "Required permission missing");
                return true;
              }
            }

            try {
              callback.call();
            } catch (Exception e) {
              promise.reject(ERROR_CALLBACK_EROR, "Unknown error", e);
            }
          }

          return true;
        }
      });

      return;
    }

    try {
      callback.call();
    } catch (Exception e) {
      promise.reject(ERROR_CALLBACK_ERROR, "Unknown error", e);
    }
  }

  /**
   * Delete a set of images.
   *
   * @param uris array of file:// URIs of the images to delete
   * @param promise to be resolved
   */
  @ReactMethod
  public void deletePhotos(ReadableArray uris, Promise promise) {
    if (uris.size() == 0) {
      promise.reject(ERROR_UNABLE_TO_DELETE, "Need at least one URI to delete");
    } else {
      new DeletePhotos(getReactApplicationContext(), uris, promise)
          .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
  }

  private static class DeletePhotos extends GuardedAsyncTask<Void, Void> {

    private final Context mContext;
    private final ReadableArray mUris;
    private final Promise mPromise;

    public DeletePhotos(ReactContext context, ReadableArray uris, Promise promise) {
      super(context);
      mContext = context;
      mUris = uris;
      mPromise = promise;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      ContentResolver resolver = mContext.getContentResolver();

      // Set up the projection (we only need the ID)
      String[] projection = { MediaStore.Images.Media._ID };

      // Match on the file path
      String innerWhere = "?";
      for (int i = 1; i < mUris.size(); i++) {
        innerWhere += ", ?";
      }

      String selection = MediaStore.Images.Media.DATA + " IN (" + innerWhere + ")";
      // Query for the ID of the media matching the file path
      Uri queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

      String[] selectionArgs = new String[mUris.size()];
      for (int i = 0; i < mUris.size(); i++) {
        Uri uri = Uri.parse(mUris.getString(i));
        selectionArgs[i] = uri.getPath();
      }

      Cursor cursor = resolver.query(queryUri, projection, selection, selectionArgs, null);
      int deletedCount = 0;

      while (cursor.moveToNext()) {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
        Uri deleteUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);

        if (resolver.delete(deleteUri, null, null) == 1) {
          deletedCount++;
        }
      }

      cursor.close();

      if (deletedCount == mUris.size()) {
        mPromise.resolve(null);
      } else {
        mPromise.reject(ERROR_UNABLE_TO_DELETE,
            "Could not delete all media, only deleted " + deletedCount + " photos.");
      }
    }
  }
}

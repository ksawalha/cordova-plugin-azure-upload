package com.karamsawalha.azureupload;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AzureUpload extends CordovaPlugin {

    private static final String STORAGE_URL = "https://arabicschool.blob.core.windows.net/";
    private static final String TAG = "AzureUpload";

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("uploadFiles")) {
            String postId = args.getString(0);
            String sasToken = args.getString(1);
            JSONArray files = args.getJSONArray(2);
            uploadFiles(postId, sasToken, files, callbackContext);
            return true;
        }
        return false;
    }

    private void uploadFiles(String postId, String sasToken, JSONArray files, CallbackContext callbackContext) {
        ExecutorService executorService = Executors.newFixedThreadPool(files.length());

        for (int i = 0; i < files.length(); i++) {
            try {
                JSONObject file = files.getJSONObject(i);
                String fileName = file.getString("filename");
                String originalName = file.getString("originalname");
                String mimeType = file.getString("mimetype");
                String binaryData = file.getString("binarydata");
                String thumbnailName = file.has("thumbnail") ? file.getString("thumbnail") : "";

                if (mimeType.startsWith("image")) {
                    executorService.execute(() -> uploadImage(fileName, binaryData, sasToken, originalName, postId));
                } else if (mimeType.startsWith("video")) {
                    executorService.execute(() -> uploadVideo(fileName, binaryData, thumbnailName, sasToken, originalName, postId));
                } else {
                    executorService.execute(() -> uploadFile(fileName, binaryData, sasToken, originalName, postId));
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error processing file upload: " + e.getMessage());
            }
        }

        executorService.shutdown();
        callbackContext.success("File uploads initiated.");
    }

    private void uploadImage(String fileName, String binaryData, String sasToken, String originalName, String postId) {
        try {
            byte[] imageData = Base64.decode(binaryData, Base64.DEFAULT);
            uploadToAzure(fileName, imageData, sasToken, originalName, postId);
        } catch (Exception e) {
            Log.e(TAG, "Error uploading image: " + e.getMessage());
        }
    }

    private void uploadVideo(String fileName, String binaryData, String thumbnailName, String sasToken, String originalName, String postId) {
        try {
            // Decode base64 video data
            byte[] videoData = Base64.decode(binaryData, Base64.DEFAULT);
            
            // Write the video data to a temporary file
            File tempVideoFile = File.createTempFile("video", ".mp4", cordova.getContext().getCacheDir());
            FileOutputStream fos = new FileOutputStream(tempVideoFile);
            fos.write(videoData);
            fos.close();

            // Upload the video to Azure
            uploadToAzure(fileName, videoData, sasToken, originalName, postId);

            // Use MediaMetadataRetriever to generate a thumbnail from the temporary file
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(tempVideoFile.getAbsolutePath());

            Bitmap bitmap = retriever.getFrameAtTime(0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            byte[] thumbnailData = out.toByteArray();

            // Upload the thumbnail to Azure
            uploadToAzure(thumbnailName, thumbnailData, sasToken, originalName, postId);

            // Delete the temporary video file after use
            tempVideoFile.delete();
        } catch (Exception e) {
            Log.e(TAG, "Error uploading video: " + e.getMessage());
        }
    }

    private void uploadFile(String fileName, String binaryData, String sasToken, String originalName, String postId) {
        try {
            byte[] fileData = Base64.decode(binaryData, Base64.DEFAULT);
            uploadToAzure(fileName, fileData, sasToken, originalName, postId);
        } catch (Exception e) {
            Log.e(TAG, "Error uploading file: " + e.getMessage());
        }
    }

    private void uploadToAzure(String fileName, byte[] fileData, String sasToken, String originalName, String postId) {
        try {
            String urlString = STORAGE_URL + fileName + "?" + sasToken;
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("PUT");
            connection.setRequestProperty("Content-Type", "application/octet-stream");

            connection.getOutputStream().write(fileData);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_CREATED || responseCode == HttpURLConnection.HTTP_OK) {
                commitUpload(postId, STORAGE_URL + fileName, originalName, "application/octet-stream");
            } else {
                Log.e(TAG, "Error uploading to Azure, response code: " + responseCode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error uploading to Azure: " + e.getMessage());
        }
    }

    private void commitUpload(String postId, String fileUrl, String originalName, String mimeType) {
        try {
            URL commitUrl = new URL("https://personal-fjlz3d21.outsystemscloud.com/uploads/rest/a/commit?postid=" + postId);
            HttpURLConnection connection = (HttpURLConnection) commitUrl.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");

            JSONObject jsonBody = new JSONObject();
            jsonBody.put("URL", fileUrl);
            jsonBody.put("originalname", originalName);
            jsonBody.put("filemime", mimeType);

            connection.getOutputStream().write(jsonBody.toString().getBytes());

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Error committing upload, response code: " + responseCode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error committing upload: " + e.getMessage());
        }
    }
}

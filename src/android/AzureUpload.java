package com.karamsawalha.azureupload;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AzureUpload extends CordovaPlugin {

    private final String STORAGE_URL = "https://arabicschool.blob.core.windows.net/";
    private NotificationManager notificationManager;
    private String channelId = "azure_upload_channel";

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("uploadFiles")) {
            String postId = args.getString(0);
            String sasToken = args.getString(1);
            JSONArray files = args.getJSONArray(2);
            this.uploadFiles(postId, sasToken, files, callbackContext);
            return true;
        }
        return false;
    }

    private void uploadFiles(String postId, String sasToken, JSONArray files, CallbackContext callbackContext) {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        createNotificationChannel();

        for (int i = 0; i < files.length(); i++) {
            try {
                JSONObject file = files.getJSONObject(i);
                String fileName = file.getString("filename");
                String originalName = file.getString("originalname");
                String mimeType = file.getString("mimetype");
                String binaryData = file.getString("binarydata");
                String thumbnail = file.getString("thumbnail");

                if (mimeType.startsWith("image")) {
                    executor.submit(() -> uploadImage(fileName, binaryData, sasToken, originalName, postId));
                } else if (mimeType.startsWith("video")) {
                    executor.submit(() -> uploadVideo(fileName, binaryData, thumbnail, sasToken, originalName, postId));
                } else {
                    executor.submit(() -> uploadFile(fileName, binaryData, sasToken, originalName, postId));
                }
            } catch (JSONException e) {
                Log.e("AzureUpload", "Error parsing file JSON: " + e.getMessage());
            }
        }

        executor.shutdown();
        callbackContext.success("Upload completed for postId: " + postId);
    }

    private void uploadImage(String fileName, String binaryData, String sasToken, String originalName, String postId) {
        try {
            byte[] imageData = Base64.decode(binaryData, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out);
            byte[] webpData = out.toByteArray();

            uploadToAzure(fileName, webpData, sasToken, originalName, postId);
        } catch (Exception e) {
            Log.e("AzureUpload", "Error uploading image: " + e.getMessage());
        }
    }

    private void uploadVideo(String fileName, String binaryData, String thumbnailName, String sasToken, String originalName, String postId) {
        try {
            byte[] videoData = Base64.decode(binaryData, Base64.DEFAULT);
            uploadToAzure(fileName, videoData, sasToken, originalName, postId);

            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            ByteArrayInputStream videoInputStream = new ByteArrayInputStream(videoData);
            retriever.setDataSource(videoInputStream.getFD());

            Bitmap bitmap = retriever.getFrameAtTime(0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out);
            byte[] thumbnailData = out.toByteArray();

            uploadToAzure(thumbnailName, thumbnailData, sasToken, originalName, postId);
        } catch (Exception e) {
            Log.e("AzureUpload", "Error uploading video: " + e.getMessage());
        }
    }

    private void uploadFile(String fileName, String binaryData, String sasToken, String originalName, String postId) {
        byte[] fileData = Base64.decode(binaryData, Base64.DEFAULT);
        uploadToAzure(fileName, fileData, sasToken, originalName, postId);
    }

    private void uploadToAzure(String fileName, byte[] fileData, String sasToken, String originalName, String postId) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(fileData);
            URL url = new URL(STORAGE_URL + fileName + "?" + sasToken);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setDoOutput(true);
            conn.getOutputStream().write(fileData);
            conn.getInputStream();

            commitUpload(postId, STORAGE_URL + fileName, originalName, "application/octet-stream");
            showNotification("Upload Complete", "File " + originalName + " uploaded successfully.");
        } catch (Exception e) {
            Log.e("AzureUpload", "Error uploading to Azure: " + e.getMessage());
            showNotification("Upload Error", "Failed to upload " + originalName);
        }
    }

    private void commitUpload(String postId, String fileUrl, String originalName, String mimeType) {
        try {
            URL url = new URL("https://personal-fjlz3d21.outsystemscloud.com/uploads/rest/a/commit?postid=" + postId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            JSONObject jsonBody = new JSONObject();
            jsonBody.put("URL", fileUrl);
            jsonBody.put("originalname", originalName);
            jsonBody.put("filemime", mimeType);

            conn.setDoOutput(true);
            conn.getOutputStream().write(jsonBody.toString().getBytes("UTF-8"));
            conn.getInputStream();
        } catch (Exception e) {
            Log.e("AzureUpload", "Error committing upload: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Azure Uploads";
            String description = "Notifications for file uploads to Azure";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(channelId, name, importance);
            channel.setDescription(description);
            notificationManager = cordova.getActivity().getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void showNotification(String title, String content) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(cordova.getContext(), channelId)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }
}

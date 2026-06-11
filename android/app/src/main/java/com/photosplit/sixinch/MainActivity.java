package com.photosplit.sixinch;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends BridgeActivity {

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 注册文件选择器结果回调
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) return;
                    Uri[] results = null;
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        if (result.getData().getClipData() != null) {
                            int count = result.getData().getClipData().getItemCount();
                            results = new Uri[count];
                            for (int i = 0; i < count; i++) {
                                results[i] = result.getData().getClipData().getItemAt(i).getUri();
                            }
                        } else if (result.getData().getData() != null) {
                            results = new Uri[]{result.getData().getData()};
                        }
                    }
                    filePathCallback.onReceiveValue(results);
                    filePathCallback = null;
                }
        );

        // 等待 WebView 初始化完成后设置下载监听
        getWindow().getDecorView().post(() -> {
            WebView webView = bridge.getWebView();
            if (webView == null) return;

            // 添加 JS 接口，用于从 WebView 中保存图片
            webView.addJavascriptInterface(new WebAppInterface(), "AndroidNative");

            // 设置下载监听（处理 blob URL 之外的下载）
            webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
                // 对于普通 URL 的下载，用浏览器打开
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            });
        });
    }

    /**
     * 处理 <input type="file"> 文件选择
     */
    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                      WebChromeClient.FileChooserParams fileChooserParams) {
        if (this.filePathCallback != null) {
            this.filePathCallback.onReceiveValue(null);
        }
        this.filePathCallback = filePathCallback;

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.setType("image/*");

        try {
            fileChooserLauncher.launch(Intent.createChooser(intent, "选择照片"));
        } catch (Exception e) {
            this.filePathCallback = null;
            return false;
        }
        return true;
    }

    /**
     * JS 接口类，提供给 WebView 调用原生功能
     */
    private class WebAppInterface {

        /**
         * 保存 base64 编码的图片到系统相册
         * 从 JS 中调用: AndroidNative.saveImage(base64Data, fileName)
         */
        @JavascriptInterface
        public void saveImage(String base64Data, String fileName) {
            runOnUiThread(() -> {
                try {
                    // 解码 base64
                    byte[] imageBytes = Base64.decode(base64Data, Base64.DEFAULT);
                    Bitmap bitmap = BitmapFactory.decodeStream(new ByteArrayInputStream(imageBytes));
                    if (bitmap == null) {
                        showToast("图片解码失败");
                        return;
                    }

                    // 保存到系统相册
                    OutputStream fos;
                    Uri imageUri;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/照片切分");

                        imageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                        if (imageUri == null) {
                            showToast("创建图片文件失败");
                            return;
                        }
                        fos = getContentResolver().openOutputStream(imageUri);
                    } else {
                        File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                        File saveDir = new File(picturesDir, "照片切分");
                        if (!saveDir.exists()) saveDir.mkdirs();
                        File file = new File(saveDir, fileName);
                        fos = new FileOutputStream(file);
                        imageUri = Uri.fromFile(file);

                        // 通知图库
                        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        mediaScanIntent.setData(imageUri);
                        sendBroadcast(mediaScanIntent);
                    }

                    bitmap.compress(Bitmap.CompressFormat.JPEG, 94, fos);
                    fos.close();
                    showToast("已保存: " + fileName);
                } catch (Exception e) {
                    showToast("保存失败: " + e.getMessage());
                }
            });
        }

        /**
         * 分享 base64 编码的图片
         * 从 JS 中调用: AndroidNative.shareImage(base64Data, fileName)
         */
        @JavascriptInterface
        public void shareImage(String base64Data, String fileName) {
            runOnUiThread(() -> {
                try {
                    byte[] imageBytes = Base64.decode(base64Data, Base64.DEFAULT);
                    Bitmap bitmap = BitmapFactory.decodeStream(new ByteArrayInputStream(imageBytes));
                    if (bitmap == null) {
                        showToast("图片解码失败");
                        return;
                    }

                    // 保存到缓存目录
                    File cacheDir = new File(getCacheDir(), "share");
                    if (!cacheDir.exists()) cacheDir.mkdirs();
                    File file = new File(cacheDir, fileName);
                    FileOutputStream fos = new FileOutputStream(file);
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 94, fos);
                    fos.close();

                    // 通过 FileProvider 分享
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            MainActivity.this,
                            getPackageName() + ".fileprovider",
                            file
                    );

                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("image/jpeg");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent, "分享照片"));
                } catch (Exception e) {
                    showToast("分享失败: " + e.getMessage());
                }
            });
        }

        /**
         * 检测是否运行在 Android 原生环境中
         */
        @JavascriptInterface
        public boolean isNative() {
            return true;
        }
    }

    private void showToast(final String message) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
    }
}

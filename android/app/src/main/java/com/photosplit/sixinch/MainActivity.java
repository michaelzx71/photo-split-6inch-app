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

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

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

            // 设置下载监听（处理 blob URL 之外的下载）
            webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            });
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次 Activity resume 时设置文件选择器回调
        setupFileChooser();
    }

    private void setupFileChooser() {
        runOnUiThread(() -> {
            WebView webView = bridge.getWebView();
            if (webView == null) return;

            final WebChromeClient originalClient = webView.getWebChromeClient();
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                                  FileChooserParams fileChooserParams) {
                    if (MainActivity.this.filePathCallback != null) {
                        MainActivity.this.filePathCallback.onReceiveValue(null);
                    }
                    MainActivity.this.filePathCallback = filePathCallback;

                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    intent.setType("image/*");

                    try {
                        fileChooserLauncher.launch(Intent.createChooser(intent, "选择照片"));
                    } catch (Exception e) {
                        MainActivity.this.filePathCallback = null;
                        return false;
                    }
                    return true;
                }

                @Override
                public void onProgressChanged(WebView view, int newProgress) {
                    if (originalClient != null) {
                        originalClient.onProgressChanged(view, newProgress);
                    }
                }

                @Override
                public void onReceivedTitle(WebView view, String title) {
                    if (originalClient != null) {
                        originalClient.onReceivedTitle(view, title);
                    }
                }

                @Override
                public void onReceivedIcon(WebView view, android.graphics.Bitmap icon) {
                    if (originalClient != null) {
                        originalClient.onReceivedIcon(view, icon);
                    }
                }
            });

            // 添加 JS 接口
            webView.addJavascriptInterface(new WebAppInterface(), "AndroidNative");
        });
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
 * 一次性分享多张 base64 编码的图片（弹一次分享对话框）
 * 从 JS 中调用: AndroidNative.shareMultipleImages(jsonBase64Array, jsonFileNameArray)
 * 例如: shareMultipleImages('["base64_1","base64_2"]', '["照片1.jpg","照片2.jpg"]')
 */
@JavascriptInterface
public void shareMultipleImages(String jsonBase64Array, String jsonFileNames) {
    runOnUiThread(() -> {
        try {
            JSONArray base64Arr = new JSONArray(jsonBase64Array);
            JSONArray namesArr = new JSONArray(jsonFileNames);
            int count = base64Arr.length();
            if (count == 0) {
                showToast("没有图片可分享");
                return;
            }

            // 清理旧缓存
            File cacheDir = new File(getCacheDir(), "share");
            if (cacheDir.exists()) {
                File[] oldFiles = cacheDir.listFiles();
                if (oldFiles != null) for (File f : oldFiles) f.delete();
            } else {
                cacheDir.mkdirs();
            }

            ArrayList<Uri> uris = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String base64 = base64Arr.getString(i);
                String name = namesArr.getString(i);

                byte[] imageBytes = Base64.decode(base64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeStream(new ByteArrayInputStream(imageBytes));
                if (bitmap == null) continue;

                File file = new File(cacheDir, name);
                FileOutputStream fos = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 94, fos);
                fos.close();
                bitmap.recycle();

                Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        MainActivity.this,
                        getPackageName() + ".fileprovider",
                        file
                );
                uris.add(uri);
            }

            if (uris.isEmpty()) {
                showToast("图片解码失败");
                return;
            }

            Intent shareIntent;
            if (uris.size() == 1) {
                // 单张图片用 ACTION_SEND
                shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                // 多张图片用 ACTION_SEND_MULTIPLE
                shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            shareIntent.setType("image/jpeg");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(shareIntent, "分享 " + uris.size() + " 张照片"));
        } catch (Exception e) {
            showToast("分享失败: " + e.getMessage());
        }
    });
}
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

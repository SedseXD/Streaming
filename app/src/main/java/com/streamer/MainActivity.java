package com.streamer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

// Using FFmpeg-Kit 5.1 LTS imports
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

public class MainActivity extends AppCompatActivity {

    private static final int PICK_FILE_REQUEST = 101;
    private static final int PERMISSION_REQUEST = 102;
    
    private EditText streamUrlInput, streamKeyInput;
    private TextView selectedFileText, logText;
    private RadioGroup resolutionGroup;
    private Button startBtn, stopBtn;
    
    private Uri selectedUri;
    private FFmpegSession currentSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        streamUrlInput = findViewById(R.id.streamUrlInput);
        streamKeyInput = findViewById(R.id.streamKeyInput);
        selectedFileText = findViewById(R.id.selectedFileText);
        logText = findViewById(R.id.logText);
        resolutionGroup = findViewById(R.id.resolutionGroup);
        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);
        Button pickFileBtn = findViewById(R.id.pickFileBtn);

        checkPermissions();

        pickFileBtn.setOnClickListener(v -> openFilePicker());
        startBtn.setOnClickListener(v -> startStream());
        stopBtn.setOnClickListener(v -> stopStream());
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.READ_MEDIA_VIDEO, 
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.POST_NOTIFICATIONS
                }, PERMISSION_REQUEST);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
            }
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "image/*"});
        startActivityForResult(intent, PICK_FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK && data != null) {
            selectedUri = data.getData();
            getContentResolver().takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            selectedFileText.setText("File selected successfully.");
        }
    }

    private void startStream() {
        if (selectedUri == null) {
            Toast.makeText(this, "Please select a file first", Toast.LENGTH_SHORT).show();
            return;
        }

        String key = streamKeyInput.getText().toString().trim();
        String baseUrl = streamUrlInput.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "Stream Key is required", Toast.LENGTH_SHORT).show();
            return;
        }

        String fullUrl = baseUrl + key;
        String filePath = FFmpegKitConfig.getSafParameterForRead(this, selectedUri);

        String scale = "1280:720"; // Lowered default for better mobile performance
        if (resolutionGroup.getCheckedRadioButtonId() == R.id.radio916) {
            scale = "720:1280"; 
        }

        String mimeType = getContentResolver().getType(selectedUri);
        boolean isImage = mimeType != null && mimeType.startsWith("image");

        StringBuilder cmd = new StringBuilder();

        if (isImage) {
            // Command for looping an image + silent audio
            cmd.append("-re -loop 1 -i ").append(filePath)
               .append(" -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 ");
        } else {
            // Command for looping a video indefinitely
            cmd.append("-re -stream_loop -1 -i ").append(filePath).append(" ");
        }

        // Encoding settings for RTMP stream
        cmd.append("-c:v libx264 -preset ultrafast -b:v 2500k -maxrate 2500k -bufsize 5000k ")
           .append("-pix_fmt yuv420p -g 60 -vf scale=").append(scale)
           .append(" -c:a aac -b:a 128k -ar 44100 ")
           .append("-f flv ").append(fullUrl);

        logText.setText("Stream initializing...");
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);

        currentSession = FFmpegKit.executeAsync(cmd.toString(), session -> {
            ReturnCode returnCode = session.getReturnCode();
            runOnUiThread(() -> {
                if (returnCode.isSuccess()) {
                    logText.setText("Stream finished.");
                } else if (returnCode.isCancel()) {
                    logText.setText("Stream stopped by user.");
                } else {
                    logText.setText("Error: " + returnCode.toString());
                }
                startBtn.setEnabled(true);
                stopBtn.setEnabled(false);
            });
        }, log -> {
            runOnUiThread(() -> logText.setText(log.getMessage()));
        });
    }

    private void stopStream() {
        if (currentSession != null) {
            FFmpegKit.cancel(currentSession.getSessionId());
            logText.setText("Stopping stream...");
        }
    }
}

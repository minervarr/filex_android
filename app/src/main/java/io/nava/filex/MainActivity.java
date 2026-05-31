package io.nava.filex;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    static {
        System.loadLibrary("filex");
    }

    // JNI declarations — implemented in filex.cpp
    public native String[] listDirectory(String path);
    public native long getFileSize(String path);

    private ListView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.listView);

        if (!Environment.isExternalStorageManager()) {
            requestAllFilesAccess();
        } else {
            loadDirectory(Environment.getExternalStorageDirectory().getAbsolutePath());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Environment.isExternalStorageManager()) {
            loadDirectory(Environment.getExternalStorageDirectory().getAbsolutePath());
        }
    }

    private void requestAllFilesAccess() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
        Toast.makeText(this, "Grant 'All files access' then return to the app", Toast.LENGTH_LONG).show();
    }

    private void loadDirectory(String path) {
        String[] entries = listDirectory(path);
        if (entries == null || entries.length == 0) {
            Toast.makeText(this, "Empty or unreadable: " + path, Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> items = Arrays.asList(entries);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, items);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            // TODO: navigate into subdirectory or open file
        });
    }
}

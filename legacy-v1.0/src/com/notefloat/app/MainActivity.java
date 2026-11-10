package com.notefloat.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private NoteStore store;
    private NoteAdapter adapter;
    private final List<Note> data = new ArrayList<Note>();

    private ListView list;
    private TextView empty;
    private EditText search;
    private Button floatBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = NoteStore.get(this);

        list = (ListView) findViewById(R.id.list);
        empty = (TextView) findViewById(R.id.empty);
        search = (EditText) findViewById(R.id.search);
        floatBtn = (Button) findViewById(R.id.floatBtn);

        adapter = new NoteAdapter(this, data);
        list.setAdapter(adapter);

        findViewById(R.id.newBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor(null);
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openEditor(data.get(position));
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Note note = data.get(position);
                store.setPinned(note.id, !note.pinned);
                Toast.makeText(MainActivity.this,
                        note.pinned ? "Unpinned" : "Pinned to top", Toast.LENGTH_SHORT).show();
                refresh();
                return true;
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refresh();
            }
        });

        floatBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onFloatClicked();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                startFloat();
            } else {
                Toast.makeText(this, "Floating tab needs the display-over-apps permission.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // The floating tab still works without notification permission on Android 13+;
        // only the ongoing notification is hidden from the shade.
    }

    private void refresh() {
        String query = search.getText().toString();
        data.clear();
        data.addAll(store.list(query));
        adapter.notifyDataSetChanged();

        int total = store.count();
        if (total == 0) {
            empty.setText(getString(R.string.empty_state));
            empty.setVisibility(View.VISIBLE);
        } else if (data.isEmpty()) {
            empty.setText(getString(R.string.no_results));
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }

        floatBtn.setText(OverlayService.isRunning
                ? getString(R.string.disable_float)
                : getString(R.string.enable_float));
    }

    private void onFloatClicked() {
        if (OverlayService.isRunning) {
            stopService(new Intent(this, OverlayService.class));
            Toast.makeText(this, "Floating tab off", Toast.LENGTH_SHORT).show();
            list.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            }, 400);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            explainOverlay();
            return;
        }
        startFloat();
    }

    private void explainOverlay() {
        new AlertDialog.Builder(this)
                .setTitle("Allow floating notes")
                .setMessage("NoteFloat needs the \"Display over other apps\" permission so the "
                        + "floating tab can stay visible above Chrome, WhatsApp and everything else. "
                        + "It is used only to draw that tab.")
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQ_OVERLAY);
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void startFloat() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQ_NOTIFICATIONS);
        }
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Floating tab on. Drag it, tap it to write.", Toast.LENGTH_LONG).show();
        list.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 600);
    }

    private void openEditor(final Note note) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_note, null);
        final EditText input = (EditText) view.findViewById(R.id.dialogInput);
        if (note != null) {
            input.setText(note.body);
            input.setSelection(input.getText().length());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(note == null ? "New note" : "Edit note");
        builder.setView(view);
        builder.setPositiveButton(getString(R.string.save), null);
        if (note != null) {
            builder.setNeutralButton(getString(R.string.delete), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    confirmDelete(note);
                }
            });
        }
        builder.setNegativeButton(getString(R.string.cancel), null);

        final AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = input.getText().toString().trim();
                if (text.length() == 0) {
                    Toast.makeText(MainActivity.this, "Nothing to save yet.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (note == null) {
                    store.insert(text);
                } else {
                    store.update(note.id, text);
                }
                dialog.dismiss();
                refresh();
            }
        });
    }

    private void confirmDelete(final Note note) {
        new AlertDialog.Builder(this)
                .setMessage("Delete this note?")
                .setPositiveButton(getString(R.string.delete), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        store.delete(note.id);
                        refresh();
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }
}

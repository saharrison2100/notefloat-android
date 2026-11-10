package com.notefloat.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

public class NoteAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<Note> notes;

    public NoteAdapter(Context context, List<Note> notes) {
        this.inflater = LayoutInflater.from(context);
        this.notes = notes;
    }

    @Override
    public int getCount() {
        return notes.size();
    }

    @Override
    public Object getItem(int position) {
        return notes.get(position);
    }

    @Override
    public long getItemId(int position) {
        return notes.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            row = inflater.inflate(R.layout.item_note, parent, false);
        }
        Note note = notes.get(position);
        TextView title = (TextView) row.findViewById(R.id.rowTitle);
        TextView preview = (TextView) row.findViewById(R.id.rowPreview);
        TextView meta = (TextView) row.findViewById(R.id.rowMeta);

        title.setText(note.displayTitle());
        preview.setText(note.preview());
        preview.setVisibility(note.preview().length() > 0 ? View.VISIBLE : View.GONE);
        meta.setText((note.pinned ? "Pinned  ·  " : "") + note.stamp());
        return row;
    }
}

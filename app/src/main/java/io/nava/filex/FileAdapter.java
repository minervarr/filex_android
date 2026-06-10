package io.nava.filex;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {

    public interface Listener {
        void onTap(FileNode node);
        void onLongPress(FileNode node, View anchor);
        void onSelectionToggle(FileNode node);
    }

    private final List<FileNode> items;
    private final Listener       listener;
    private final Set<String>    selectedPaths = new HashSet<>();
    private boolean              selectionMode = false;

    public FileAdapter(List<FileNode> items, Listener listener) {
        this.items    = items;
        this.listener = listener;
        setHasStableIds(false);
    }

    public void setSelectionMode(boolean active) {
        selectionMode = active;
        selectedPaths.clear();
        notifyDataSetChanged();
    }

    public void toggleSelection(String absolutePath) {
        if (!selectedPaths.remove(absolutePath)) {
            selectedPaths.add(absolutePath);
        }
        notifyDataSetChanged();
    }

    public Set<String> getSelectedPaths() {
        return Collections.unmodifiableSet(selectedPaths);
    }

    public int getSelectedCount() {
        return selectedPaths.size();
    }

    public void selectAll(List<FileNode> nodes) {
        for (FileNode n : nodes) selectedPaths.add(n.absolutePath);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new ViewHolder(v, listener, items, this);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        h.bind(items.get(position), selectionMode, selectedPaths);
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private static final SimpleDateFormat DATE_FMT =
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

        private final TextView     tvName, tvMeta;
        private final CheckBox     checkBox;
        private final List<FileNode> items;
        private final FileAdapter  adapter;

        ViewHolder(@NonNull View v, Listener listener, List<FileNode> items, FileAdapter adapter) {
            super(v);
            this.items   = items;
            this.adapter = adapter;
            tvName   = v.findViewById(R.id.tvName);
            tvMeta   = v.findViewById(R.id.tvMeta);
            checkBox = v.findViewById(R.id.checkBox);

            v.setOnClickListener(view -> {
                int pos = getAbsoluteAdapterPosition();
                if (pos == RecyclerView.NO_ID) return;
                FileNode node = items.get(pos);
                if (adapter.selectionMode) listener.onSelectionToggle(node);
                else                       listener.onTap(node);
            });
            v.setOnLongClickListener(view -> {
                int pos = getAbsoluteAdapterPosition();
                if (pos != RecyclerView.NO_ID) listener.onLongPress(items.get(pos), view);
                return true;
            });
        }

        void bind(FileNode n, boolean selectionMode, Set<String> selectedPaths) {
            tvName.setText(n.isDirectory ? "📁 " + n.name : "📄 " + n.name);
            String meta = n.isDirectory
                    ? DATE_FMT.format(new Date(n.modifiedMs))
                    : n.formattedSize() + "  " + DATE_FMT.format(new Date(n.modifiedMs));
            tvMeta.setText(meta);

            if (selectionMode) {
                checkBox.setVisibility(View.VISIBLE);
                checkBox.setChecked(selectedPaths.contains(n.absolutePath));
            } else {
                checkBox.setVisibility(View.GONE);
            }
        }
    }
}

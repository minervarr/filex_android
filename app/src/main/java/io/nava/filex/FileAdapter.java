package io.nava.filex;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {

    public interface Listener {
        void onTap(FileNode node);
        void onLongPress(FileNode node, View anchor);
    }

    private final List<FileNode> items;
    private final Listener       listener;

    public FileAdapter(List<FileNode> items, Listener listener) {
        this.items    = items;
        this.listener = listener;
        setHasStableIds(false);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new ViewHolder(v, listener, items);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        h.bind(items.get(position));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private static final SimpleDateFormat DATE_FMT =
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

        private final TextView     tvName, tvMeta;
        private final List<FileNode> items;

        ViewHolder(@NonNull View v, Listener listener, List<FileNode> items) {
            super(v);
            this.items  = items;
            tvName = v.findViewById(R.id.tvName);
            tvMeta = v.findViewById(R.id.tvMeta);

            // Listeners set once in constructor; use adapter position at click time
            v.setOnClickListener(view -> {
                int pos = getAbsoluteAdapterPosition();
                if (pos != RecyclerView.NO_ID) listener.onTap(items.get(pos));
            });
            v.setOnLongClickListener(view -> {
                int pos = getAbsoluteAdapterPosition();
                if (pos != RecyclerView.NO_ID) listener.onLongPress(items.get(pos), view);
                return true;
            });
        }

        void bind(FileNode n) {
            tvName.setText(n.isDirectory ? "📁 " + n.name : "📄 " + n.name);
            String meta = n.isDirectory
                    ? DATE_FMT.format(new Date(n.modifiedMs))
                    : n.formattedSize() + "  " + DATE_FMT.format(new Date(n.modifiedMs));
            tvMeta.setText(meta);
        }
    }
}

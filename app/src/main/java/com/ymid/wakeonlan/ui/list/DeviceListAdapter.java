package com.ymid.wakeonlan.ui.list;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.ymid.wakeonlan.R;
import com.ymid.wakeonlan.persistence.models.Device;
import com.ymid.wakeonlan.ui.list.status.pool.StatusTesterPool;
import com.ymid.wakeonlan.ui.list.viewholder.DeviceItemViewHolder;
import com.ymid.wakeonlan.ui.list.viewholder.EmptyViewHolder;
import com.ymid.wakeonlan.ui.list.viewholder.GroupHeaderViewHolder;
import com.ymid.wakeonlan.ui.list.viewholder.ListViewType;

public class DeviceListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface DeviceOrderCallback {
        void onMoveDevice(Device device, int direction);
    }


    private List<Object> items = Collections.emptyList();
    private final DeviceClickedCallback deviceClickedCallback;
    private final StatusTesterPool statusTesterPool;
    private final DeviceOrderCallback deviceOrderCallback;

    public DeviceListAdapter(List<Device> initialDataset, DeviceClickedCallback deviceClickedCallback,
                             StatusTesterPool statusTesterPool, DeviceOrderCallback deviceOrderCallback) {
        this.deviceClickedCallback = deviceClickedCallback;
        this.statusTesterPool = statusTesterPool;
        this.deviceOrderCallback = deviceOrderCallback;
        this.items = buildFlatList(initialDataset);
    }

    public void updateDataset(List<Device> devices) {
        this.items = buildFlatList(devices);
        notifyDataSetChanged();
    }

    /** The device shown first in the list, which is the one the launcher icon mirrors. */
    public static Device firstDisplayedDevice(List<Device> devices) {
        for (Object item : buildFlatList(devices)) {
            if (item instanceof Device) return (Device) item;
        }
        return null;
    }

    private static List<Object> buildFlatList(List<Device> devices) {
        if (devices == null || devices.isEmpty()) return Collections.emptyList();

        boolean anyGrouped = false;
        for (Device d : devices) {
            if (!Strings.isNullOrEmpty(d.groupName)) { anyGrouped = true; break; }
        }
        if (!anyGrouped) return new ArrayList<>(devices);

        Map<String, List<Device>> grouped = new TreeMap<>();
        List<Device> ungrouped = new ArrayList<>();
        for (Device d : devices) {
            if (Strings.isNullOrEmpty(d.groupName)) {
                ungrouped.add(d);
            } else {
                grouped.computeIfAbsent(d.groupName, k -> new ArrayList<>()).add(d);
            }
        }

        List<Object> flat = new ArrayList<>();
        for (Map.Entry<String, List<Device>> entry : grouped.entrySet()) {
            flat.add(entry.getKey());
            flat.addAll(entry.getValue());
        }
        flat.addAll(ungrouped);
        return flat;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(viewGroup.getContext());
        if (ListViewType.EMPTY.ordinal() == viewType) {
            View view = inflater.inflate(R.layout.device_list_empty, viewGroup, false);
            return new EmptyViewHolder(view);
        } else if (ListViewType.GROUP_HEADER.ordinal() == viewType) {
            View view = inflater.inflate(R.layout.device_list_group_header, viewGroup, false);
            return new GroupHeaderViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.device_list_item, viewGroup, false);
            return new DeviceItemViewHolder(view, deviceClickedCallback, statusTesterPool, deviceOrderCallback);
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (items.isEmpty()) return ListViewType.EMPTY.ordinal();
        Object item = items.get(position);
        if (item instanceof String) return ListViewType.GROUP_HEADER.ordinal();
        return ListViewType.DEVICE.ordinal();
    }

    @Override
    public long getItemId(int position) {
        if (items.isEmpty()) return RecyclerView.NO_ID;
        Object item = items.get(position);
        if (item instanceof Device) return ((Device) item).id;
        // Negative hash so it cannot collide with positive device IDs
        return -((long) ((String) item).hashCode() + 1);
    }

    @Override
    public int getItemCount() {
        if (items.isEmpty()) return 1;
        return items.size();
    }

    // The launcher icon mirrors the first device shown, which group headers can push past position 0
    private int firstDevicePosition() {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof Device) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    private int findNeighborDevicePosition(int position, int direction) {
        if (position < 0 || position >= items.size() || !(items.get(position) instanceof Device)) return -1;
        Device current = (Device) items.get(position);
        String group = Strings.nullToEmpty(current.groupName);
        for (int i = position + direction; i >= 0 && i < items.size(); i += direction) {
            Object candidate = items.get(i);
            if (candidate instanceof Device
                    && group.equals(Strings.nullToEmpty(((Device) candidate).groupName))) return i;
        }
        return -1;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        int viewType = getItemViewType(position);
        if (viewType == ListViewType.DEVICE.ordinal()) {
            ((DeviceItemViewHolder) viewHolder).fromDevice((Device) items.get(position),
                    position == firstDevicePosition(), findNeighborDevicePosition(position, -1) >= 0,
                    findNeighborDevicePosition(position, 1) >= 0);
        } else if (viewType == ListViewType.GROUP_HEADER.ordinal()) {
            ((GroupHeaderViewHolder) viewHolder).bind((String) items.get(position));
        }
    }
}

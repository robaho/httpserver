package robaho.net.httpserver;

import java.util.Arrays;
import java.util.function.BiConsumer;

public class OpenAddressMap {

    private static class Entry {

        String key;
        Object value;

        Entry(String key, Object value) {
            this.key = key;
            this.value = value;
        }
    }

    private int capacity;
    private int mask;
    private int size;
    private Entry[] entries;

    public OpenAddressMap(int capacity) {
        // round up to next power of 2
        capacity--;
        capacity |= capacity >> 1;
        capacity |= capacity >> 2;
        capacity |= capacity >> 4;
        capacity |= capacity >> 8;
        capacity |= capacity >> 16;
        capacity++;

        this.capacity = capacity;
        this.mask = capacity - 1;
        this.entries = new Entry[capacity];
    }

    public Object put(String key, Object value) {
        int index = key.hashCode() & mask;
        int start = index;
        int sentinel = -1;
        Entry entry;
        while ((entry = entries[index]) != null) {
            if (entry.key.equals(key)) {
                Object oldValue = entry.value;
                entry.value = value;
                if (value == null) {
                    size--;
                }
                return oldValue;
            } else if (entry.value == null) {
                sentinel = index;
            }
            index = (index + 1) & mask;
            if (index == start) {
                resize();
                index = key.hashCode() & mask;
                start = index;
            }
        }
        entries[sentinel==-1 ? index : sentinel] = new Entry(key, value);
        size++;
        return null;
    }

    private void resize() {
        OpenAddressMap newMap = new OpenAddressMap(capacity << 1);
        for (Entry entry : entries) {
            if (entry != null) {
                newMap.put(entry.key, entry.value);
            }
        }
        this.entries = newMap.entries;
        this.capacity = newMap.capacity;
        this.mask = newMap.mask;
    }

    public Object get(String key) {
        int index = key.hashCode() & mask;
        int start = index;
        Entry entry;
        while ((entry = entries[index]) != null) {
            if (entry.key.equals(key)) {
                return entry.value;
            }
            index = (index + 1) & mask;
            if(index==start) {
                break;
            }
        }
        return null;
    }

    public int size() {
        return size;
    }

    public void clear() {
        Arrays.fill(entries, null);
    }

    public void forEach(BiConsumer<String,Object> action) {
        for (Entry entry : entries) {
            if (entry != null && entry.value != null) {
                action.accept(entry.key,entry.value);
            }
        }
    }
}

package robaho.net.httpserver;

import java.util.Arrays;
import java.util.function.BiConsumer;

public class OpenAddressMap<K,V> {

    private static class Entry<K,V> {
        K key;
        V value;

        Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
    private int capacity;
    private int mask;
    private int size;
    private int used;
    private Entry[] entries;

    private static int hash(int hash) {
        return hash;
        // return hash ^ (hash>>>16);
    }

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

    public V put(K key, V value) {
        if(used>=capacity/2) {
            resize();
        }

        int index = hash(key.hashCode()) & mask;
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
                return (V)oldValue;
            } else if (entry.value == null) {
                sentinel = index;
            }
            index = (index + 1) & mask;
            if (index == start) {
                resize();
                index = hash(key.hashCode()) & mask;
                start = index;
            }
        }
        entries[sentinel==-1 ? index : sentinel] = new Entry(key, value);
        size++;
        if(sentinel!=-1) used++;
        return null;
    }

    private void resize() {
        OpenAddressMap newMap = new OpenAddressMap(capacity << 1);
        for (var entry : entries) {
            if (entry != null) {
                newMap.put(entry.key, entry.value);
            }
        }
        this.entries = newMap.entries;
        this.capacity = newMap.capacity;
        this.mask = newMap.mask;
        this.size = newMap.size;
        this.used = newMap.used;
    }

    public V get(K key) {
        int index = hash(key.hashCode()) & mask;
        int start = index;
        // int count=0;
        Entry entry;
        try {
            while ((entry = entries[index]) != null) {
                // count++;
                if (entry.key.equals(key)) {
                    return (V)entry.value;
                }
                index = (index + 1) & mask;
                if(index==start) {
                    break;
                }
            }
            return null;
        } finally {
            // System.out.println("count="+count);
            // if(count>5) {
            //     for(var e : entries) {
            //         if(e!=null) {
            //             System.out.println(e.key+"="+e.value);
            //         } else {
            //             System.out.println("null");
            //         }
            //     }
            // }
        }
    }

    public int size() {
        return size;
    }

    public void clear() {
        Arrays.fill(entries, null);
        size=0;
        used=0;
    }

    public void forEach(BiConsumer<K,V> action) {
        for (Entry entry : entries) {
            if (entry != null && entry.value != null) {
                action.accept((K)entry.key,(V)entry.value);
            }
        }
    }
    public Iterable<K> keys() {
        return () -> new KeyIterator();
    }

    private class KeyIterator implements java.util.Iterator<K> {
        private int index = 0;

        @Override
        public boolean hasNext() {
            while (index < entries.length) {
                if (entries[index] != null && entries[index].value != null) {
                    return true;
                }
                index++;
            }
            return false;
        }

        @Override
        public K next() {
            return (K) entries[index++].key;
        }
    }
}

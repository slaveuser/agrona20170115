/*
 *  Copyright 2014 - 2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.agrona.collections;

import org.agrona.BitUtil;
import org.agrona.generation.DoNotSub;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

import static org.agrona.collections.CollectionUtil.validateLoadFactor;

/**
 * A open addressing with linear probing hash map specialised for primitive key and value pairs.
 */
public class Int2IntHashMap implements Map<Integer, Integer>
{
    @DoNotSub private final float loadFactor;
    private final int missingValue;
    @DoNotSub private int resizeThreshold;
    @DoNotSub private int size = 0;

    private int[] entries;
    private final KeySet keySet;
    private final Values values;
    private final Set<Entry<Integer, Integer>> entrySet;

    public Int2IntHashMap(final int missingValue)
    {
        this(8, 0.67f, missingValue);
    }

    @SuppressWarnings("unchecked")
    public Int2IntHashMap(
        @DoNotSub final int initialCapacity,
        @DoNotSub final float loadFactor,
        final int missingValue)
    {
        validateLoadFactor(loadFactor);

        this.loadFactor = loadFactor;
        this.missingValue = missingValue;

        capacity(BitUtil.findNextPositivePowerOfTwo(initialCapacity));

        keySet = new KeySet();
        values = new Values();
        entrySet = new EntrySet();
    }

    /**
     * The value to be used as a null marker in the map.
     *
     * @return value to be used as a null marker in the map.
     */
    public int missingValue()
    {
        return missingValue;
    }

    /**
     * Get the load factor applied for resize operations.
     *
     * @return the load factor applied for resize operations.
     */
    public float loadFactor()
    {
        return loadFactor;
    }

    /**
     * {@inheritDoc}
     */
    @DoNotSub public int size()
    {
        return size;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isEmpty()
    {
        return size == 0;
    }

    public int get(final int key)
    {
        final int[] entries = this.entries;
        final int missingValue = this.missingValue;
        @DoNotSub final int mask = entries.length - 1;
        @DoNotSub int index = Hashing.evenHash(key, mask);

        int value = missingValue;
        while (entries[index + 1] != missingValue)
        {
            if (entries[index] == key)
            {
                value = entries[index + 1];
                break;
            }

            index = next(index, mask);
        }

        return value;
    }

    /**
     * @param key   lookup key
     * @param value new value, must not be initialValue
     * @return current counter value associated with key, or initialValue if none found
     * @throws IllegalArgumentException if value is missingValue
     */
    public int put(final int key, final int value)
    {
        if (value == missingValue)
        {
            throw new IllegalArgumentException("Cannot accept missingValue");
        }
        final int[] entries = this.entries;
        final int missingValue = this.missingValue;
        @DoNotSub final int mask = entries.length - 1;
        @DoNotSub int index = Hashing.evenHash(key, mask);
        int oldValue = missingValue;

        while (entries[index + 1] != missingValue)
        {
            if (entries[index] == key)
            {
                oldValue = entries[index + 1];
                break;
            }

            index = next(index, mask);
        }

        if (oldValue == missingValue)
        {
            ++size;
            entries[index] = key;
        }

        entries[index + 1] = value;

        increaseCapacity();

        return oldValue;
    }

    private void increaseCapacity()
    {
        if (size > resizeThreshold)
        {
            // entries.length = 2 * capacity
            @DoNotSub final int newCapacity = entries.length;
            rehash(newCapacity);
        }
    }

    private void rehash(@DoNotSub final int newCapacity)
    {
        final int[] oldEntries = entries;
        final int missingValue = this.missingValue;
        @DoNotSub final int length = entries.length;

        capacity(newCapacity);

        for (@DoNotSub int keyIndex = 0; keyIndex < length; keyIndex += 2)
        {
            if (oldEntries[keyIndex + 1] != missingValue)
            {
                put(oldEntries[keyIndex], oldEntries[keyIndex + 1]);
            }
        }
    }

    /**
     * Primitive specialised forEach implementation.
     *
     * NB: Renamed from forEach to avoid overloading on parameter types of lambda
     * expression, which doesn't interplay well with type inference in lambda expressions.
     *
     * @param consumer a callback called for each key/value pair in the map.
     */
    public void intForEach(final IntIntConsumer consumer)
    {
        final int[] entries = this.entries;
        final int missingValue = this.missingValue;
        @DoNotSub final int length = entries.length;

        for (@DoNotSub int keyIndex = 0; keyIndex < length; keyIndex += 2)
        {
            if (entries[keyIndex + 1] != missingValue)
            {
                consumer.accept(entries[keyIndex], entries[keyIndex + 1]);
            }
        }
    }

    /**
     * Int primitive specialised containsKey.
     *
     * @param key the key to check.
     * @return true if the map contains key as a key, false otherwise.
     */
    public boolean containsKey(final int key)
    {
        return get(key) != missingValue;
    }

    public boolean containsValue(final int value)
    {
        boolean found = false;
        if (value != missingValue)
        {
            final int[] entries = this.entries;
            @DoNotSub final int length = entries.length;

            for (@DoNotSub int valueIndex = 1; valueIndex < length; valueIndex += 2)
            {
                if (value == entries[valueIndex])
                {
                    found = true;
                    break;
                }
            }
        }
        return found;
    }

    /**
     * {@inheritDoc}
     */
    public void clear()
    {
        Arrays.fill(entries, missingValue);
        size = 0;
    }

    /**
     * Compact the backing arrays by rehashing with a capacity just larger than current size
     * and giving consideration to the load factor.
     */
    public void compact()
    {
        @DoNotSub final int idealCapacity = (int)Math.round(size() * (1.0d / loadFactor));
        rehash(BitUtil.findNextPositivePowerOfTwo(idealCapacity));
    }

    /**
     * Primitive specialised version of {@link #computeIfAbsent(Object, Function)}
     *
     * @param key             to search on.
     * @param mappingFunction to provide a value if the get returns null.
     * @return the value if found otherwise the missing value.
     */
    public int computeIfAbsent(final int key, final IntUnaryOperator mappingFunction)
    {
        int value = get(key);
        if (value == missingValue)
        {
            value = mappingFunction.applyAsInt(key);
            if (value != missingValue)
            {
                put(key, value);
            }
        }

        return value;
    }

    // ---------------- Boxed Versions Below ----------------

    /**
     * {@inheritDoc}
     */
    public Integer get(final Object key)
    {
        return get((int)key);
    }

    /**
     * {@inheritDoc}
     */
    public Integer put(final Integer key, final Integer value)
    {
        return put((int)key, (int)value);
    }

    /**
     * {@inheritDoc}
     */
    public void forEach(final BiConsumer<? super Integer, ? super Integer> action)
    {
        intForEach(action::accept);
    }

    /**
     * {@inheritDoc}
     */
    public boolean containsKey(final Object key)
    {
        return containsKey((int)key);
    }

    /**
     * {@inheritDoc}
     */
    public boolean containsValue(final Object value)
    {
        return containsValue((int)value);
    }

    /**
     * {@inheritDoc}
     */
    public void putAll(final Map<? extends Integer, ? extends Integer> map)
    {
        for (final Map.Entry<? extends Integer, ? extends Integer> entry : map.entrySet())
        {
            put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * {@inheritDoc}
     */
    public KeySet keySet()
    {
        return keySet;
    }

    /**
     * {@inheritDoc}
     */
    public Values values()
    {
        return values;
    }

    /**
     * {@inheritDoc}
     */
    public Set<Entry<Integer, Integer>> entrySet()
    {
        return entrySet;
    }

    /**
     * {@inheritDoc}
     */
    public Integer remove(final Object key)
    {
        return remove((int)key);
    }

    public int remove(final int key)
    {
        final int[] entries = this.entries;
        final int missingValue = this.missingValue;
        @DoNotSub final int mask = entries.length - 1;
        @DoNotSub int keyIndex = Hashing.evenHash(key, mask);

        int oldValue = missingValue;
        while (entries[keyIndex + 1] != missingValue)
        {
            if (entries[keyIndex] == key)
            {
                oldValue = entries[keyIndex + 1];
                entries[keyIndex + 1] = missingValue;
                size--;

                compactChain(keyIndex);

                break;
            }

            keyIndex = next(keyIndex, mask);
        }

        return oldValue;
    }

    private void compactChain(@DoNotSub int deleteKeyIndex)
    {
        final int[] entries = this.entries;
        final int missingValue = this.missingValue;
        @DoNotSub final int mask = entries.length - 1;
        @DoNotSub int keyIndex = deleteKeyIndex;

        while (true)
        {
            keyIndex = next(keyIndex, mask);
            if (entries[keyIndex + 1] == missingValue)
            {
                break;
            }

            @DoNotSub final int hash = Hashing.evenHash(entries[keyIndex], mask);

            if ((keyIndex < hash && (hash <= deleteKeyIndex || deleteKeyIndex <= keyIndex)) ||
                (hash <= deleteKeyIndex && deleteKeyIndex <= keyIndex))
            {
                entries[deleteKeyIndex] = entries[keyIndex];
                entries[deleteKeyIndex + 1] = entries[keyIndex + 1];

                entries[keyIndex + 1] = missingValue;
                deleteKeyIndex = keyIndex;
            }
        }
    }

    /**
     * Get the minimum value stored in the map. If the map is empty then it will return {@link #missingValue()}
     *
     * @return the minimum value stored in the map.
     */
    public int minValue()
    {
        final int missingValue = this.missingValue;
        int min = size == 0 ? missingValue : Integer.MAX_VALUE;

        final int[] entries = this.entries;
        @DoNotSub final int length = entries.length;

        for (@DoNotSub int valueIndex = 1; valueIndex < length; valueIndex += 2)
        {
            final int value = entries[valueIndex];
            if (value != missingValue)
            {
                min = Math.min(min, value);
            }
        }

        return min;
    }

    /**
     * Get the maximum value stored in the map. If the map is empty then it will return {@link #missingValue()}
     *
     * @return the maximum value stored in the map.
     */
    public int maxValue()
    {
        final int missingValue = this.missingValue;
        int max = size == 0 ? missingValue : Integer.MIN_VALUE;

        final int[] entries = this.entries;
        @DoNotSub final int length = entries.length;

        for (@DoNotSub int valueIndex = 1; valueIndex < length; valueIndex += 2)
        {
            final int value = entries[valueIndex];
            if (value != missingValue)
            {
                max = Math.max(max, value);
            }
        }

        return max;
    }

    /**
     * {@inheritDoc}
     */
    public String toString()
    {
        final StringBuilder sb = new StringBuilder();
        sb.append('{');

        for (final Entry<Integer, Integer> entry : entrySet())
        {
            sb.append(entry.getKey().intValue());
            sb.append('=');
            sb.append(entry.getValue().intValue());
            sb.append(", ");
        }

        if (sb.length() > 1)
        {
            sb.setLength(sb.length() - 2);
        }

        sb.append('}');

        return sb.toString();
    }

    /**
     * Primitive specialised version of {@link #replace(Object, Object)}
     *
     * @param key key with which the specified value is associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with the specified key, or
     *         {@link #missingValue()} if there was no mapping for the key.
     */
    public int replace(final int key, final int value)
    {
        int curValue = get(key);
        if (curValue != missingValue)
        {
            curValue = put(key, value);
        }

        return curValue;
    }

    /**
     * Primitive specialised version of {@link #replace(Object, Object, Object)}
     *
     * @param key key with which the specified value is associated
     * @param oldValue value expected to be associated with the specified key
     * @param newValue value to be associated with the specified key
     * @return {@code true} if the value was replaced
     */
    public boolean replace(final int key, int oldValue, int newValue)
    {
        final int curValue = get(key);
        if (curValue != oldValue || curValue == missingValue)
        {
            return false;
        }

        put(key, newValue);

        return true;
    }

    @DoNotSub private static int next(final int index, final int mask)
    {
        return (index + 2) & mask;
    }

    private void capacity(@DoNotSub int newCapacity)
    {
        @DoNotSub final int entriesLength = newCapacity * 2;
        if (entriesLength < 0)
        {
            throw new IllegalStateException("Max capacity reached at size=" + size);
        }

        /*@DoNotSub*/ resizeThreshold = (int)(newCapacity * loadFactor);
        entries = new int[entriesLength];
        size = 0;
        Arrays.fill(entries, missingValue);
    }

    // ---------------- Utility Classes ----------------

    abstract class AbstractIterator
    {
        @DoNotSub private int positionCounter;
        @DoNotSub private int stopCounter;

        AbstractIterator()
        {
            reset();
        }

        private void reset()
        {
            final int missingValue = Int2IntHashMap.this.missingValue;
            final int[] entries = Int2IntHashMap.this.entries;
            @DoNotSub final int capacity = entries.length;

            @DoNotSub int keyIndex = capacity;
            if (entries[capacity - 1] != missingValue)
            {
                keyIndex = 0;
                for (@DoNotSub int size = capacity; keyIndex < size; keyIndex += 2)
                {
                    if (entries[keyIndex + 1] == missingValue)
                    {
                        break;
                    }
                }
            }

            stopCounter = keyIndex;
            positionCounter = keyIndex + capacity;
        }

        @DoNotSub protected int keyPosition()
        {
            return positionCounter & entries.length - 1;
        }

        public boolean hasNext()
        {
            final int[] entries = Int2IntHashMap.this.entries;
            final int missingValue = Int2IntHashMap.this.missingValue;
            @DoNotSub final int mask = entries.length - 1;
            boolean hasNext = false;
            for (@DoNotSub int keyIndex = positionCounter - 2; keyIndex >= stopCounter; keyIndex -= 2)
            {
                @DoNotSub final int index = keyIndex & mask;
                if (entries[index + 1] != missingValue)
                {
                    hasNext = true;
                    break;
                }
            }

            return hasNext;
        }

        protected void findNext()
        {
            final int[] entries = Int2IntHashMap.this.entries;
            final int missingValue = Int2IntHashMap.this.missingValue;
            @DoNotSub final int mask = entries.length - 1;

            for (@DoNotSub int keyIndex = positionCounter - 2; keyIndex >= stopCounter; keyIndex -= 2)
            {
                @DoNotSub final int index = keyIndex & mask;
                if (entries[index + 1] != missingValue)
                {
                    positionCounter = keyIndex;
                    return;
                }
            }

            throw new NoSuchElementException();
        }
    }

    public final class IntIterator extends AbstractIterator implements Iterator<Integer>
    {
        @DoNotSub private final int offset;

        private IntIterator(
            @DoNotSub final int offset)
        {
            this.offset = offset;
        }

        public Integer next()
        {
            return nextValue();
        }

        public int nextValue()
        {
            findNext();

            return entries[keyPosition() + offset];
        }

        public IntIterator reset()
        {
            super.reset();

            return this;
        }
    }

    final class EntryIterator
        extends AbstractIterator
        implements Iterator<Entry<Integer, Integer>>, Entry<Integer, Integer>
    {
        private int key;
        private int value;

        private EntryIterator()
        {
            super();
        }

        public Integer getKey()
        {
            return key;
        }

        public Integer getValue()
        {
            return value;
        }

        public Integer setValue(final Integer value)
        {
            throw new UnsupportedOperationException();
        }

        public Entry<Integer, Integer> next()
        {
            findNext();

            @DoNotSub final int keyPosition = keyPosition();
            key = entries[keyPosition];
            value = entries[keyPosition + 1];

            return this;
        }

        public EntryIterator reset()
        {
            super.reset();
            key = missingValue;
            value = missingValue;

            return this;
        }
    }

    public final class KeySet extends MapDelegatingSet<Integer>
    {
        private final IntIterator keyIterator = new IntIterator(0);

        private KeySet()
        {
            super(Int2IntHashMap.this);
        }

        /**
         * {@inheritDoc}
         */
        public IntIterator iterator()
        {
            return keyIterator.reset();
        }

        /**
         * {@inheritDoc}
         */
        public boolean contains(final Object o)
        {
            return contains((int)o);
        }

        public boolean contains(final int key)
        {
            return containsKey(key);
        }
    }

    public final class Values extends AbstractCollection<Integer>
    {
        private final IntIterator valueIterator = new IntIterator(1);

        private Values()
        {
        }

        /**
         * {@inheritDoc}
         */
        public IntIterator iterator()
        {
            return valueIterator.reset();
        }

        /**
         * {@inheritDoc}
         */
        @DoNotSub public int size()
        {
            return Int2IntHashMap.this.size();
        }

        /**
         * {@inheritDoc}
         */
        public boolean contains(final Object o)
        {
            return contains((int)o);
        }

        public boolean contains(final int key)
        {
            return containsValue(key);
        }
    }

    private final class EntrySet extends MapDelegatingSet<Entry<Integer, Integer>>
    {
        private final EntryIterator entryIterator = new EntryIterator();

        private EntrySet()
        {
            super(Int2IntHashMap.this);
        }

        /**
         * {@inheritDoc}
         */
        public Iterator<Entry<Integer, Integer>> iterator()
        {
            return entryIterator.reset();
        }

        /**
         * {@inheritDoc}
         */
        public boolean contains(final Object o)
        {
            return containsKey(((Entry)o).getKey());
        }
    }
}

// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package io.github.ghosthack.seven.internal.xz;

/**
 * Fixed allocation policy for the retained raw decoders.
 *
 * <p>robust-seven deliberately avoids a mutable process-global cache: decoder
 * sessions already have bounded lifetimes, and retaining attacker-sized
 * dictionaries globally would weaken memory-budget predictability.
 */
public final class ArrayCache {
    private static final ArrayCache INSTANCE = new ArrayCache();

    public static ArrayCache getInstance() {
        return INSTANCE;
    }

    public byte[] getByteArray(int size, boolean fillWithZeros) {
        return new byte[size];
    }

    public void putArray(byte[] array) {
    }

    private ArrayCache() {
    }
}

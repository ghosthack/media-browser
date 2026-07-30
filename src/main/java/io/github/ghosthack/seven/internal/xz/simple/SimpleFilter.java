// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package io.github.ghosthack.seven.internal.xz.simple;

public interface SimpleFilter {
    int code(byte[] buf, int off, int len);
}

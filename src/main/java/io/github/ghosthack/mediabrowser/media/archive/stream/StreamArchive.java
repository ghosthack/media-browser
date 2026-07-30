package io.github.ghosthack.mediabrowser.media.archive.stream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalized, read-only directory tree over one streaming archive reader. */
final class StreamArchive implements AutoCloseable {

    @FunctionalInterface
    interface InputOpener {
        InputStream open() throws IOException;
    }

    static final class Node {
        final String path;
        final String name;
        final Map<String, Node> children = new LinkedHashMap<>();
        boolean directory;
        long size;
        FileTime modified = FileTime.fromMillis(0);
        Object key;
        InputOpener opener;

        Node(String path, String name, boolean directory) {
            this.path = path;
            this.name = name;
            this.directory = directory;
        }
    }

    private final AutoCloseable reader;
    private final Node root = new Node("/", "", true);
    private final Map<String, Node> byPath = new LinkedHashMap<>();

    StreamArchive(AutoCloseable reader) {
        this.reader = reader;
        byPath.put("/", root);
    }

    /**
     * Adds an untrusted member name. Absolute names, traversal components and
     * NULs are omitted rather than reinterpreted as safe-looking paths.
     */
    void add(String rawName, boolean directory, long size, FileTime modified,
             Object key, InputOpener opener) {
        List<String> parts = safeParts(rawName);
        if (parts.isEmpty()) return;

        Node parent = root;
        StringBuilder path = new StringBuilder();
        for (int index = 0; index < parts.size(); index++) {
            String part = parts.get(index);
            path.append('/').append(part);
            boolean leaf = index == parts.size() - 1;
            boolean needsDirectory = !leaf || directory;
            Node node = parent.children.get(part);
            if (node == null) {
                node = new Node(path.toString(), part, needsDirectory);
                parent.children.put(part, node);
                byPath.put(node.path, node);
            } else if (needsDirectory && !node.directory) {
                // A later child proves this name is a directory. The conflicting
                // file is hidden; a filesystem cannot expose both at one path.
                node.directory = true;
                node.size = 0;
                node.opener = null;
            }
            parent = node;
        }

        // First file wins for duplicate normalized names, matching archive
        // order and avoiding a mutable "last entry wins" surprise.
        if (!directory && !parent.directory && parent.opener == null) {
            parent.size = Math.max(0, size);
            parent.modified = modified == null ? FileTime.fromMillis(0) : modified;
            parent.key = key;
            parent.opener = opener;
        } else if (directory) {
            parent.modified = modified == null ? parent.modified : modified;
            parent.key = parent.key == null ? key : parent.key;
        }
    }

    Node entry(String rawPath) throws NoSuchFileException {
        String path = normalize(rawPath);
        Node node = byPath.get(path);
        if (node == null) throw new NoSuchFileException(rawPath);
        return node;
    }

    List<Node> children(String rawPath) throws IOException {
        Node node = entry(rawPath);
        if (!node.directory) throw new NotDirectoryException(rawPath);
        return List.copyOf(node.children.values());
    }

    InputStream open(Node node) throws IOException {
        if (node.directory || node.opener == null) {
            throw new IOException("is a directory: " + node.path);
        }
        return node.opener.open();
    }

    private static List<String> safeParts(String rawName) {
        if (rawName == null || rawName.isBlank() || rawName.indexOf('\0') >= 0) return List.of();
        String name = rawName.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:/.*")) return List.of();
        var result = new ArrayList<String>();
        for (String part : name.split("/+")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) return List.of();
            result.add(part);
        }
        return List.copyOf(result);
    }

    private static String normalize(String rawPath) {
        var parts = new ArrayList<String>();
        for (String part : rawPath.replace('\\', '/').split("/+")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        return "/" + String.join("/", parts);
    }

    @Override
    public void close() throws IOException {
        try {
            reader.close();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot close archive reader", e);
        }
    }
}

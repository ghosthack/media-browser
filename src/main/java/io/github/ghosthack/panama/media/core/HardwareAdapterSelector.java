package io.github.ghosthack.panama.media.core;

import java.util.Locale;

/**
 * Explicit, portable adapter selector used by hardware decoder sessions.
 *
 * <p>Accepted forms are {@code default}, vendor shortcuts ({@code amd},
 * {@code nvidia}, {@code intel}), {@code vendor:<name-or-hex>}, and exact PCI
 * selectors emitted by {@link HardwareAdapterInfo#selector()}:
 * {@code device:<vendor>:<device>[:<subsystem>]}.</p>
 */
public record HardwareAdapterSelector(
        Kind kind,
        int vendorId,
        int deviceId,
        int subSystemId,
        String text) {

    public enum Kind { DEFAULT, VENDOR, DEVICE }

    public HardwareAdapterSelector {
        if (kind == null) {
            throw new IllegalArgumentException("adapter selector kind is required");
        }
        text = text == null || text.isBlank() ? "default" : text;
    }

    public static HardwareAdapterSelector defaultAdapter() {
        return new HardwareAdapterSelector(Kind.DEFAULT, 0, 0, 0, "default");
    }

    public static HardwareAdapterSelector vendor(int vendorId) {
        if ((vendorId & 0xFFFF) == 0) {
            throw new IllegalArgumentException("vendor id must be non-zero");
        }
        return new HardwareAdapterSelector(Kind.VENDOR, vendorId & 0xFFFF, 0, 0,
                String.format(Locale.ROOT, "vendor:%04x", vendorId & 0xFFFF));
    }

    public static HardwareAdapterSelector parse(String value) {
        if (value == null || value.isBlank()
                || "default".equalsIgnoreCase(value)
                || "auto".equalsIgnoreCase(value)) {
            return defaultAdapter();
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        if ("amd".equals(text) || "nvidia".equals(text) || "intel".equals(text)) {
            return namedVendor(text, value);
        }
        if (text.startsWith("vendor:")) {
            String part = text.substring("vendor:".length());
            if ("amd".equals(part) || "nvidia".equals(part) || "intel".equals(part)) {
                return namedVendor(part, value);
            }
            return new HardwareAdapterSelector(Kind.VENDOR, parseHex(part, "vendor"), 0, 0, value);
        }
        if (text.startsWith("device:")) {
            String[] parts = text.substring("device:".length()).split(":", -1);
            if (parts.length != 2 && parts.length != 3) {
                throw new IllegalArgumentException(
                        "exact adapter selector must be device:<vendor>:<device>[:<subsystem>]");
            }
            int vendor = parseHex(parts[0], "vendor");
            int device = parseHex(parts[1], "device");
            int subsystem = parts.length == 3 ? parseHex32(parts[2], "subsystem") : 0;
            return new HardwareAdapterSelector(Kind.DEVICE, vendor, device, subsystem, value);
        }
        throw new IllegalArgumentException("unknown adapter selector: " + value);
    }

    public boolean isExplicit() {
        return kind != Kind.DEFAULT;
    }

    public boolean matches(HardwareAdapterInfo info) {
        if (kind == Kind.DEFAULT) {
            return true;
        }
        if ((info.vendorId() & 0xFFFF) != (vendorId & 0xFFFF)) {
            return false;
        }
        if (kind == Kind.VENDOR) {
            return true;
        }
        return (info.deviceId() & 0xFFFF) == (deviceId & 0xFFFF)
                && (subSystemId == 0 || info.subSystemId() == subSystemId);
    }

    @Override
    public String toString() {
        return text;
    }

    private static HardwareAdapterSelector namedVendor(String name, String text) {
        int id = switch (name) {
            case "amd" -> 0x1002;
            case "nvidia" -> 0x10DE;
            default -> 0x8086;
        };
        return new HardwareAdapterSelector(Kind.VENDOR, id, 0, 0, text);
    }

    private static int parseHex(String value, String field) {
        int parsed = parseHex32(value, field);
        if ((parsed & 0xFFFF0000) != 0 || parsed == 0) {
            throw new IllegalArgumentException(field + " id must be 1-4 hexadecimal digits: " + value);
        }
        return parsed;
    }

    private static int parseHex32(String value, String field) {
        String digits = value.startsWith("0x") ? value.substring(2) : value;
        if (digits.isEmpty() || digits.length() > 8 || !digits.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException(field + " id must be hexadecimal: " + value);
        }
        return (int) Long.parseUnsignedLong(digits, 16);
    }
}

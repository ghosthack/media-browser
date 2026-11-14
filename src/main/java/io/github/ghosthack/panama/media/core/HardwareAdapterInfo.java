package io.github.ghosthack.panama.media.core;

import java.util.Locale;

/**
 * Normalized identity of the physical adapter selected by a native media
 * provider. Numeric PCI fields are unsigned values stored in Java ints.
 *
 * @param provider       provider that resolved the identity (for example
 *                       {@code dxgi}, {@code cuda}, or {@code videotoolbox})
 * @param name           human-readable adapter name
 * @param vendorId       PCI vendor id, or {@code 0} when not exposed
 * @param deviceId       PCI device id, or {@code 0} when not exposed
 * @param subSystemId    PCI subsystem id, or {@code 0} when not exposed
 * @param revision       PCI revision, or {@code 0} when not exposed
 * @param driverVersion  provider-reported driver version, or {@code unknown}
 */
public record HardwareAdapterInfo(
        String provider,
        String name,
        int vendorId,
        int deviceId,
        int subSystemId,
        int revision,
        String driverVersion) {

    public HardwareAdapterInfo {
        provider = normalized(provider, "unknown");
        name = normalized(name, "unknown");
        driverVersion = normalized(driverVersion, "unknown");
    }

    /** Stable exact selector for this PCI adapter, including subsystem when available. */
    public String selector() {
        if (vendorId == 0 || deviceId == 0) {
            return "unknown";
        }
        String base = String.format(Locale.ROOT, "device:%04x:%04x",
                vendorId & 0xFFFF, deviceId & 0xFFFF);
        return subSystemId == 0
                ? base
                : base + String.format(Locale.ROOT, ":%08x", subSystemId);
    }

    /** Lowercase four-digit PCI vendor id, or an empty string when unavailable. */
    public String vendorIdHex() {
        return vendorId == 0 ? "" : String.format(Locale.ROOT, "%04x", vendorId & 0xFFFF);
    }

    /** Lowercase four-digit PCI device id, or an empty string when unavailable. */
    public String deviceIdHex() {
        return deviceId == 0 ? "" : String.format(Locale.ROOT, "%04x", deviceId & 0xFFFF);
    }

    /** Identity used when a provider is CPU/software rather than adapter-backed. */
    public static HardwareAdapterInfo software(String provider) {
        return new HardwareAdapterInfo(provider, "CPU/software", 0, 0, 0, 0, "n/a");
    }

    /** Honest placeholder when the provider does not expose its selected adapter. */
    public static HardwareAdapterInfo unknown(String provider) {
        return new HardwareAdapterInfo(provider, "unknown", 0, 0, 0, 0, "unknown");
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

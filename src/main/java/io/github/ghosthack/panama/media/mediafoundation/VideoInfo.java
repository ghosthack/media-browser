package io.github.ghosthack.panama.media.mediafoundation;

/**
 * Video metadata returned by {@link MediaFoundation#getVideoInfo}.
 *
 * @param width          frame width in pixels
 * @param height         frame height in pixels
 * @param durationMillis total duration in milliseconds, or {@code 0} if unknown
 * @param frameRate      frame rate in frames per second, or {@code 0} if unknown
 * @param codec          short codec name (e.g. {@code "H.264"}, {@code "HEVC"},
 *                       {@code "VP9"}, {@code "AV1"}, {@code "MPEG-4"}), or
 *                       {@code null} when the subtype GUID maps to no known name
 *                       (best-effort, mirrors {@link AudioInfo#codec})
 */
public record VideoInfo(int width, int height, long durationMillis, double frameRate,
                        String codec) {}

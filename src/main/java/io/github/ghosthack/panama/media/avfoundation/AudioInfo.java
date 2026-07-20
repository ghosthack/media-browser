package io.github.ghosthack.panama.media.avfoundation;

/**
 * Audio metadata returned by {@link AVFoundation#getAudioInfo}.
 *
 * @param hasAudio       whether the asset has at least one audio track
 * @param durationMillis total duration in milliseconds, or -1 if unknown
 * @param sampleRate     sample rate in Hz of the first audio track (0 if none)
 * @param channels       channel count of the first audio track (0 if none)
 * @param codec          short codec name (e.g. {@code "AAC"}, {@code "ALAC"}),
 *                       or {@code null} when unavailable
 */
public record AudioInfo(boolean hasAudio, long durationMillis, double sampleRate,
                        int channels, String codec) {}

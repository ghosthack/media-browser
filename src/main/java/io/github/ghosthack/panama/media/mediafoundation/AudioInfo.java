package io.github.ghosthack.panama.media.mediafoundation;

/**
 * Audio metadata returned by {@link MediaFoundation#getAudioInfo}.
 *
 * <p>Mirrors the AVFoundation {@code AudioInfo} record, except the sample rate
 * is an {@code int} here: Media Foundation exposes
 * {@code MF_MT_AUDIO_SAMPLES_PER_SECOND} as a {@code UINT32}, so there is no
 * sub-Hz precision to preserve.</p>
 *
 * @param hasAudio       whether the file has at least one audio stream
 * @param durationMillis total duration in milliseconds, or {@code 0} if unknown
 * @param sampleRate     sample rate in Hz of the first audio stream (0 if none)
 * @param channels       channel count of the first audio stream (0 if none)
 * @param codec          short codec name (e.g. {@code "AAC"}, {@code "MP3"},
 *                       {@code "FLAC"}, {@code "PCM"}), or {@code null} when the
 *                       subtype GUID maps to no known name
 */
public record AudioInfo(boolean hasAudio, long durationMillis, int sampleRate,
                        int channels, String codec) {}

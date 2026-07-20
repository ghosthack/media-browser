package io.github.ghosthack.panama.media.avfoundation;

/**
 * Video metadata returned by {@link AVFoundation#getVideoInfo}.
 *
 * @param width          frame width in pixels
 * @param height         frame height in pixels
 * @param durationMillis total duration in milliseconds
 * @param frameRate      nominal frame rate (fps) of the first video track
 */
public record VideoInfo(int width, int height, long durationMillis, double frameRate) {}

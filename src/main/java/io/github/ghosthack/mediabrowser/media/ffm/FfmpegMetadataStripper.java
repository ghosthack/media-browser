package io.github.ghosthack.mediabrowser.media.ffm;

import io.github.ghosthack.ffmpegffm.ffmpeg.AVCodecParameters;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVFormatContext;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVOutputFormat;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVPacket;
import io.github.ghosthack.ffmpegffm.ffmpeg.AVStream;
import io.github.ghosthack.ffmpegffm.ffmpeg.FFmpeg;
import io.github.ghosthack.mediabrowser.media.export.ZeroMetadataStripper;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Packet-preserving zero-metadata remuxer backed by ffmpeg-ffm. */
public final class FfmpegMetadataStripper implements ZeroMetadataStripper {
    private static final Set<String> SUPPORTED_SUFFIXES = Set.of(
            "mp4", "mov", "m4v", "m4s", "m4a", "m4b", "m4r", "3gp", "3g2", "3ga",
            "mkv", "webm", "avi", "ogg", "oga", "ogv", "ogm", "opus", "spx",
            "mp3", "wav");

    /** Whether the ffmpeg-ffm bundle has a same-family muxer for this suffix. */
    public static boolean supports(Path input) {
        Objects.requireNonNull(input, "input");
        return muxerName(input) != null;
    }

    @Override
    public Result stripDetailed(Path input) throws IOException {
        Path source = regularFile(input);
        String muxerName = sniffAudioMuxer(source);
        if (muxerName == null) muxerName = muxerName(source);
        if (muxerName == null) {
            throw new IOException("ffmpeg-ffm has no bundled muxer for: " + source);
        }

        long inputBytes = Files.size(source);
        Path output = outputPath(source);
        if (Files.exists(output)) {
            throw new IOException("Output already exists: " + output);
        }

        Path temporary = Files.createTempFile(output.getParent(),
                "." + output.getFileName() + "-", ".tmp");
        boolean moved = false;
        try {
            remux(source, temporary, muxerName);
            moveIntoPlace(temporary, output);
            moved = true;
            return new Result(source, output, inputBytes, Files.size(output));
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static void remux(Path input, Path output, String muxerName) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inputPointer = addressSlot(arena);
            MemorySegment outputPointer = addressSlot(arena);
            MemorySegment outputContext = MemorySegment.NULL;
            boolean outputIoOpen = false;
            boolean nativeInitialized = false;
            try {
                int openResult = FFmpeg.avformat_open_input(inputPointer,
                        arena.allocateFrom(input.toString()), MemorySegment.NULL, MemorySegment.NULL);
                nativeInitialized = true;
                check(openResult, "avformat_open_input", arena);
                MemorySegment inputContext = pointedStruct(
                        inputPointer, AVFormatContext.layout().byteSize(), "input format context");
                check(FFmpeg.avformat_find_stream_info(inputContext, MemorySegment.NULL),
                        "avformat_find_stream_info", arena);

                MemorySegment outputFormat = muxerName.equals("3gp") || muxerName.equals("3g2")
                        ? FFmpeg.av_guess_format(MemorySegment.NULL,
                                arena.allocateFrom("output." + muxerName), MemorySegment.NULL)
                        : FFmpeg.av_guess_format(arena.allocateFrom(muxerName),
                                MemorySegment.NULL, MemorySegment.NULL);
                if (outputFormat.equals(MemorySegment.NULL)) {
                    throw new IOException("No bundled FFmpeg muxer matches: " + input);
                }
                check(FFmpeg.avformat_alloc_output_context2(outputPointer, outputFormat,
                                MemorySegment.NULL, arena.allocateFrom(output.toString())),
                        "avformat_alloc_output_context2", arena);
                outputContext = pointedStruct(
                        outputPointer, AVFormatContext.layout().byteSize(), "output format context");

                int streamCount = AVFormatContext.nb_streams(inputContext);
                if (streamCount <= 0) throw new IOException("Input contains no media streams: " + input);
                for (int i = 0; i < streamCount; i++) {
                    MemorySegment sourceStream = stream(inputContext, i);
                    MemorySegment targetStreamPointer = FFmpeg.avformat_new_stream(
                            outputContext, MemorySegment.NULL);
                    if (targetStreamPointer.equals(MemorySegment.NULL)) {
                        throw new IOException("Could not create output stream " + i);
                    }
                    MemorySegment targetStream = targetStreamPointer
                            .reinterpret(AVStream.layout().byteSize());

                    MemorySegment sourceParameters = AVStream.codecpar(sourceStream)
                            .reinterpret(AVCodecParameters.layout().byteSize());
                    MemorySegment targetParameters = AVStream.codecpar(targetStream)
                            .reinterpret(AVCodecParameters.layout().byteSize());
                    check(FFmpeg.avcodec_parameters_copy(targetParameters, sourceParameters),
                            "avcodec_parameters_copy", arena);
                    AVCodecParameters.codec_tag(targetParameters, 0);

                    AVStream.id(targetStream, AVStream.id(sourceStream));
                    AVStream.time_base(targetStream, AVStream.time_base(sourceStream));
                    AVStream.sample_aspect_ratio(targetStream,
                            AVStream.sample_aspect_ratio(sourceStream));
                    AVStream.avg_frame_rate(targetStream, AVStream.avg_frame_rate(sourceStream));
                    AVStream.r_frame_rate(targetStream, AVStream.r_frame_rate(sourceStream));
                    AVStream.disposition(targetStream, AVStream.disposition(sourceStream));
                    // Container and stream dictionaries are deliberately not copied.
                    // avcodec_parameters_copy retains codec configuration and coded side data.
                }

                MemorySegment format = AVFormatContext.oformat(outputContext)
                        .reinterpret(AVOutputFormat.layout().byteSize());
                if ((AVOutputFormat.flags(format) & FFmpeg.AVFMT_NOFILE()) == 0) {
                    MemorySegment outputPbPointer = outputContext.asSlice(
                            AVFormatContext.pb$offset(), ValueLayout.ADDRESS.byteSize());
                    check(FFmpeg.avio_open2(outputPbPointer, arena.allocateFrom(output.toString()),
                                    FFmpeg.AVIO_FLAG_WRITE(), MemorySegment.NULL, MemorySegment.NULL),
                            "avio_open2", arena);
                    outputIoOpen = true;
                }

                MemorySegment optionsPointer = addressSlot(arena);
                try {
                    setOption(optionsPointer, "fflags", "+bitexact", arena);
                    if (muxerName.equals("mp3")) {
                        setOption(optionsPointer, "id3v2_version", "0", arena);
                        setOption(optionsPointer, "write_id3v1", "0", arena);
                        setOption(optionsPointer, "write_xing", "0", arena);
                    }
                    check(FFmpeg.avformat_write_header(outputContext, optionsPointer),
                            "avformat_write_header", arena);
                    MemorySegment unusedOptions = optionsPointer.get(ValueLayout.ADDRESS, 0);
                    if (!unusedOptions.equals(MemorySegment.NULL)
                            && FFmpeg.av_dict_count(unusedOptions) > 0) {
                        throw new IOException("FFmpeg muxer rejected one or more output options");
                    }
                } finally {
                    FFmpeg.av_dict_free(optionsPointer);
                }

                copyPackets(arena, inputContext, outputContext, streamCount);
                check(FFmpeg.av_write_trailer(outputContext), "av_write_trailer", arena);
            } catch (LinkageError error) {
                throw new IOException("ffmpeg-ffm native libraries are unavailable", error);
            } finally {
                if (nativeInitialized && !outputContext.equals(MemorySegment.NULL)) {
                    if (outputIoOpen) {
                        FFmpeg.avio_closep(outputContext.asSlice(
                                AVFormatContext.pb$offset(), ValueLayout.ADDRESS.byteSize()));
                    }
                    FFmpeg.avformat_free_context(outputContext);
                }
                if (nativeInitialized) FFmpeg.avformat_close_input(inputPointer);
            }
        }
    }

    private static void copyPackets(Arena arena, MemorySegment inputContext,
                                    MemorySegment outputContext, int streamCount) throws IOException {
        MemorySegment packet = FFmpeg.av_packet_alloc();
        if (packet.equals(MemorySegment.NULL)) throw new IOException("av_packet_alloc failed");
        packet = packet.reinterpret(AVPacket.layout().byteSize());
        MemorySegment packetPointer = addressSlot(arena);
        packetPointer.set(ValueLayout.ADDRESS, 0, packet);
        try {
            int readResult;
            while ((readResult = FFmpeg.av_read_frame(inputContext, packet)) >= 0) {
                try {
                    int index = AVPacket.stream_index(packet);
                    if (index < 0 || index >= streamCount) {
                        throw new IOException("Packet refers to invalid stream " + index);
                    }
                    MemorySegment sourceStream = stream(inputContext, index);
                    MemorySegment targetStream = stream(outputContext, index);
                    FFmpeg.av_packet_rescale_ts(packet, AVStream.time_base(sourceStream),
                            AVStream.time_base(targetStream));
                    AVPacket.stream_index(packet, index);
                    AVPacket.pos(packet, -1);
                    check(FFmpeg.av_interleaved_write_frame(outputContext, packet),
                            "av_interleaved_write_frame", arena);
                } finally {
                    FFmpeg.av_packet_unref(packet);
                }
            }
            if (readResult != FFmpeg.AVERROR_EOF()) {
                check(readResult, "av_read_frame", arena);
            }
        } finally {
            FFmpeg.av_packet_free(packetPointer);
        }
    }

    private static MemorySegment stream(MemorySegment context, int index) {
        MemorySegment streams = AVFormatContext.streams(context)
                .reinterpret((index + 1L) * ValueLayout.ADDRESS.byteSize());
        return streams.getAtIndex(ValueLayout.ADDRESS, index)
                .reinterpret(AVStream.layout().byteSize());
    }

    private static MemorySegment addressSlot(Arena arena) {
        MemorySegment slot = arena.allocate(ValueLayout.ADDRESS);
        slot.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
        return slot;
    }

    private static MemorySegment pointedStruct(MemorySegment slot, long size, String description)
            throws IOException {
        MemorySegment pointer = slot.get(ValueLayout.ADDRESS, 0);
        if (pointer.equals(MemorySegment.NULL)) throw new IOException("Missing " + description);
        return pointer.reinterpret(size);
    }

    private static void check(int result, String operation, Arena arena) throws IOException {
        if (result >= 0) return;
        MemorySegment buffer = arena.allocate(256);
        String detail = FFmpeg.av_strerror(result, buffer, buffer.byteSize()) >= 0
                ? buffer.getString(0) : "FFmpeg error " + result;
        throw new IOException(operation + " failed: " + detail);
    }

    private static void setOption(MemorySegment optionsPointer, String name, String value,
                                  Arena arena) throws IOException {
        check(FFmpeg.av_dict_set(optionsPointer, arena.allocateFrom(name),
                        arena.allocateFrom(value), 0),
                "av_dict_set(" + name + "=" + value + ")", arena);
    }

    private static Path regularFile(Path input) throws IOException {
        Objects.requireNonNull(input, "input");
        Path source = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Input is not a regular file: " + source);
        }
        return source;
    }

    private static Path outputPath(Path input) {
        Path namePath = input.getFileName();
        if (namePath == null) throw new IllegalArgumentException("Input has no file name: " + input);
        String name = namePath.toString();
        int dot = name.lastIndexOf('.');
        String outputName = dot > 0
                ? name.substring(0, dot) + "_0m" + name.substring(dot)
                : name + "_0m";
        return input.resolveSibling(outputName);
    }

    private static String muxerName(Path input) {
        Path namePath = input.getFileName();
        if (namePath == null) return null;
        String name = namePath.toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= name.length()) return null;
        String suffix = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_SUFFIXES.contains(suffix)) return null;
        return switch (suffix) {
            case "mov" -> "mov";
            case "m4a", "m4b", "m4r" -> "ipod";
            case "3gp", "3ga" -> "3gp";
            case "3g2" -> "3g2";
            case "mkv" -> "matroska";
            case "webm" -> "webm";
            case "avi" -> "avi";
            case "ogg", "oga", "ogv", "ogm", "opus", "spx" -> "ogg";
            case "mp3" -> "mp3";
            case "wav" -> "wav";
            default -> "mp4";
        };
    }

    /** Keeps signature-routed MP3/WAV export working for missing or incorrect suffixes. */
    private static String sniffAudioMuxer(Path input) throws IOException {
        byte[] header;
        try (var stream = Files.newInputStream(input)) {
            header = stream.readNBytes(12);
        }
        if (header.length >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'A' && header[10] == 'V' && header[11] == 'E') {
            return "wav";
        }
        if (header.length >= 3 && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
            return "mp3";
        }
        if (header.length >= 2 && (header[0] & 0xff) == 0xff && (header[1] & 0xe0) == 0xe0) {
            return "mp3";
        }
        return null;
    }

    private static void moveIntoPlace(Path temporary, Path output) throws IOException {
        try {
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, output);
        }
    }
}

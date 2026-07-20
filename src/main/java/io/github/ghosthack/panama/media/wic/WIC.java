package io.github.ghosthack.panama.media.wic;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.ghosthack.panama.media.core.DecodeException;
import io.github.ghosthack.panama.media.core.Dimensions;
import io.github.ghosthack.panama.media.core.DecodedImage;
import io.github.ghosthack.panama.media.core.ImageDimensions;
import io.github.ghosthack.panama.media.core.PixelFormat;
import io.github.ghosthack.panama.media.comruntime.Ole32;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Panama FFM bindings to the Windows Imaging Component (WIC) for still image
 * decoding via COM vtable dispatch.
 * <p>
 * WIC supports HEIC (with HEVC Video Extensions), AVIF (with AV1 Video
 * Extensions), WEBP, JPEG-XR, DDS, camera RAW, and all standard image formats
 * on Windows 10+.
 * <p>
 * This class provides low-level decode operations that return raw pixel data
 * in Arena-managed memory.
 * <p>
 * Decoded images are always in BGRA format with 4 bytes per pixel.
 * <p>
 * Thread-safe when each thread uses its own {@link Arena}.
 */
public final class WIC {

    private WIC() {}

    // -- Constants -----------------------------------------------------------

    /** Bytes per pixel for 32-bit output. */
    private static final int RGBA_BPP = 4;

    /** COINIT_MULTITHREADED = 0x0 */
    private static final int COINIT_MULTITHREADED = 0x0;

    /** WICDecodeMetadataCacheOnDemand = 0 */
    private static final int WICDecodeMetadataCacheOnDemand = 0;

    /** WICBitmapDitherTypeNone = 0 */
    private static final int WICBitmapDitherTypeNone = 0;

    /** WICBitmapPaletteTypeCustom = 0 */
    private static final int WICBitmapPaletteTypeCustom = 0;

    /**
     * WICBitmapInterpolationModeFant = 3. Area-averaging interpolation that
     * gives good quality at low cost for downscaling (the thumbnail case);
     * WICBitmapInterpolationModeHighQualityCubic = 4 is sharper but slower.
     */
    private static final int WICBitmapInterpolationModeFant = 3;

    /** GENERIC_READ = 0x80000000 */
    private static final int GENERIC_READ = 0x80000000;

    /** PROPVARIANT total size on 64-bit: vt(2) + reserved(6) + union(16) = 24 bytes */
    private static final int PROPVARIANT_SIZE = 24;

    /** VARTYPE VT_UI1 (unsigned 8-bit integer) */
    private static final short VT_UI1 = 17;

    /** VARTYPE VT_UI2 (unsigned 16-bit integer) */
    private static final short VT_UI2 = 18;

    /** VARTYPE VT_UI1 | VT_VECTOR */
    private static final short VT_VECTOR_UI1 = (short) (0x1000 | VT_UI1);

    // -- Remaining VARTYPEs used by the metadata enumeration -----------------
    private static final short VT_EMPTY = 0;
    private static final short VT_NULL = 1;
    private static final short VT_I2 = 2;
    private static final short VT_I4 = 3;
    private static final short VT_R4 = 4;
    private static final short VT_R8 = 5;
    private static final short VT_BSTR = 8;
    private static final short VT_BOOL = 11;
    private static final short VT_UNKNOWN = 13;
    private static final short VT_I1 = 16;
    private static final short VT_UI4 = 19;
    private static final short VT_I8 = 20;
    private static final short VT_UI8 = 21;
    private static final short VT_INT = 22;
    private static final short VT_UINT = 23;
    private static final short VT_LPSTR = 30;
    private static final short VT_LPWSTR = 31;
    private static final short VT_FILETIME = 64;
    private static final short VT_BLOB = 65;
    private static final short VT_CLSID = 72;
    /** VT_VECTOR flag (a counted {@code {ULONG cElems; T *pElems}} array). */
    private static final short VT_VECTOR = 0x1000;

    // -- Metadata-walk safety caps -------------------------------------------
    /** Hard ceiling on enumerated entries, so a pathological file can't run away. */
    private static final int MAX_METADATA_ENTRIES = 4096;
    /** Hard ceiling on nested-reader recursion depth (EXIF/GPS/XMP sub-IFDs). */
    private static final int MAX_METADATA_DEPTH = 12;
    /** Cap on characters read from a single string value (display is capped again upstream). */
    private static final int MAX_STRING_CHARS = 4096;
    /** Cap on elements stringified from a numeric/string vector. */
    private static final int MAX_VECTOR_ELEMS = 16;

    // -- WICBitmapTransformOptions -------------------------------------------

    private static final int WICBitmapTransformRotate0        = 0x0;
    private static final int WICBitmapTransformRotate90       = 0x1;
    private static final int WICBitmapTransformRotate180      = 0x2;
    private static final int WICBitmapTransformRotate270      = 0x3;
    private static final int WICBitmapTransformFlipHorizontal = 0x8;
    private static final int WICBitmapTransformFlipVertical   = 0x10;

    // -- Well-known GUIDs (allocated once in global arena) -------------------

    /** CLSID_WICImagingFactory {CACAF262-9370-4615-A13B-9F5539DA4C0A} */
    private static final MemorySegment CLSID_WIC_FACTORY = Ole32.guid(
            Arena.global(), 0xCACAF262, (short) 0x9370, (short) 0x4615,
            new byte[]{(byte) 0xA1, 0x3B, (byte) 0x9F, 0x55, 0x39, (byte) 0xDA, 0x4C, 0x0A});

    /** IID_IWICImagingFactory {EC5EC8A9-C395-4314-9C77-54D7A935FF70} */
    private static final MemorySegment IID_WIC_FACTORY = Ole32.guid(
            Arena.global(), 0xEC5EC8A9, (short) 0xC395, (short) 0x4314,
            new byte[]{(byte) 0x9C, 0x77, 0x54, (byte) 0xD7, (byte) 0xA9, 0x35, (byte) 0xFF, 0x70});

    /** GUID_WICPixelFormat32bppPBGRA {6FDDC324-4E03-4BFE-B185-3D77768DC910} */
    private static final MemorySegment GUID_PIXEL_FORMAT_PBGRA = Ole32.guid(
            Arena.global(), 0x6FDDC324, (short) 0x4E03, (short) 0x4BFE,
            new byte[]{(byte) 0xB1, (byte) 0x85, 0x3D, 0x77, 0x76, (byte) 0x8D, (byte) 0xC9, 0x10});

    /** IID_IWICMetadataQueryReader {30989668-E1C9-4597-B395-458EEDB808DF} */
    private static final MemorySegment IID_WIC_METADATA_QUERY_READER = Ole32.guid(
            Arena.global(), 0x30989668, (short) 0xE1C9, (short) 0x4597,
            new byte[]{(byte) 0xB3, (byte) 0x95, 0x45, (byte) 0x8E, (byte) 0xED, (byte) 0xB8, 0x08, (byte) 0xDF});

    /** IID_IWICPixelFormatInfo {E8EDA601-3D48-431A-AB44-69059BE88BBE} */
    private static final MemorySegment IID_WIC_PIXEL_FORMAT_INFO = Ole32.guid(
            Arena.global(), 0xE8EDA601, (short) 0x3D48, (short) 0x431A,
            new byte[]{(byte) 0xAB, 0x44, 0x69, 0x05, (byte) 0x9B, (byte) 0xE8, (byte) 0x8B, (byte) 0xBE});

    // -- COM vtable dispatch handles -----------------------------------------
    // Linker.downcallHandle(FunctionDescriptor) -- no fixed address -- yields a
    // MethodHandle with an extra leading MemorySegment for the function pointer.

    private static final Linker LINKER = Linker.nativeLinker();

    // IWICImagingFactory::CreateStream (vtable[14])
    private static final MethodHandle Factory_CreateStream = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICImagingFactory::CreateDecoderFromFilename (vtable[3])
    private static final MethodHandle Factory_CreateDecoderFromFilename = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    JAVA_INT, JAVA_INT, ValueLayout.ADDRESS));

    // IWICImagingFactory::CreateDecoderFromStream (vtable[4])
    private static final MethodHandle Factory_CreateDecoderFromStream = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    JAVA_INT, ValueLayout.ADDRESS));

    // IWICImagingFactory::CreateComponentInfo (vtable[6])
    //   HRESULT CreateComponentInfo(REFCLSID clsidComponent, IWICComponentInfo **ppIInfo)
    private static final MethodHandle Factory_CreateComponentInfo = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICImagingFactory::CreateFormatConverter (vtable[10])
    private static final MethodHandle Factory_CreateFormatConverter = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICImagingFactory::CreateBitmapScaler (vtable[11])
    private static final MethodHandle Factory_CreateBitmapScaler = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICImagingFactory::CreateBitmapFlipRotator (vtable[13])
    private static final MethodHandle Factory_CreateBitmapFlipRotator = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICStream::InitializeFromMemory (vtable[16])
    private static final MethodHandle Stream_InitializeFromMemory = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, JAVA_INT));

    // IWICBitmapDecoder::GetMetadataQueryReader (vtable[8])
    private static final MethodHandle Decoder_GetMetadataQueryReader = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICBitmapDecoder::GetFrameCount (vtable[12])
    private static final MethodHandle Decoder_GetFrameCount = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICBitmapDecoder::GetFrame (vtable[13])
    private static final MethodHandle Decoder_GetFrame = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT,
                    ValueLayout.ADDRESS, JAVA_INT, ValueLayout.ADDRESS));

    // IWICBitmapSource::GetSize (vtable[3])
    private static final MethodHandle Source_GetSize = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICBitmapSource::GetPixelFormat (vtable[4]) -> WICPixelFormatGUID*
    private static final MethodHandle Source_GetPixelFormat = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICBitmapSource::CopyPixels (vtable[7])
    private static final MethodHandle Source_CopyPixels = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, JAVA_INT,
                    JAVA_INT, ValueLayout.ADDRESS));

    // IWICFormatConverter::Initialize (vtable[8])
    private static final MethodHandle Converter_Initialize = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE,
                    JAVA_INT));

    // IWICBitmapFrameDecode::GetMetadataQueryReader (vtable[8])
    private static final MethodHandle Frame_GetMetadataQueryReader = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICBitmapFrameDecode::GetThumbnail (vtable[10])
    private static final MethodHandle Frame_GetThumbnail = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICMetadataQueryReader::GetMetadataByName (vtable[5])
    private static final MethodHandle MetadataReader_GetMetadataByName = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICMetadataQueryReader::GetEnumerator (vtable[6]) -> IEnumString**
    private static final MethodHandle MetadataReader_GetEnumerator = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IEnumString::Next (vtable[3])
    //   HRESULT Next(ULONG celt, LPOLESTR *rgelt, ULONG *pceltFetched)
    private static final MethodHandle EnumString_Next = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT,
                    ValueLayout.ADDRESS, JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICBitmapFlipRotator::Initialize (vtable[8])
    private static final MethodHandle FlipRotator_Initialize = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, JAVA_INT));

    // IWICBitmapScaler::Initialize (vtable[8])
    //   HRESULT Initialize(IWICBitmapSource *pISource, UINT uiWidth,
    //                      UINT uiHeight, WICBitmapInterpolationMode mode)
    private static final MethodHandle Scaler_Initialize = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));

    // IWICPixelFormatInfo::GetBitsPerPixel (vtable[13])
    //   HRESULT GetBitsPerPixel(UINT *puiBitsPerPixel)
    private static final MethodHandle PixelFormatInfo_GetBitsPerPixel = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // IWICPixelFormatInfo::GetChannelCount (vtable[14])
    //   HRESULT GetChannelCount(UINT *puiChannelCount)
    private static final MethodHandle PixelFormatInfo_GetChannelCount = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // -- DLL loading ---------------------------------------------------------

    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;

    static {
        boolean available = false;
        String loadError = null;
        try {
            System.loadLibrary("windowscodecs");
            available = true;
        } catch (Throwable t) {
            loadError = t.getMessage();
        }
        AVAILABLE = available;
        LOAD_ERROR = loadError;
    }

    // -- Cached WIC factory --------------------------------------------------
    // WICImagingFactory is ThreadingModel=Both (thread-safe). We create it
    // once on first access and reuse it for all subsequent calls.  COM must
    // still be initialised on each calling thread (CoInitializeEx is cheap).

    /**
     * Lazy holder -- the factory is created exactly once, on first access.
     * A permanent CoInitializeEx on the loading thread keeps the COM
     * library loaded for the lifetime of the JVM.
     */
    private static class FactoryHolder {
        static final MemorySegment INSTANCE = createFactory();

        private static MemorySegment createFactory() {
            try {
                // Permanent COM init on this thread (never balanced by CoUninitialize)
                Ole32.coInitializeEx();
                MemorySegment ppFactory = Arena.global().allocate(ValueLayout.ADDRESS);
                int hr = Ole32.coCreateInstance(
                        CLSID_WIC_FACTORY,
                        Ole32.CLSCTX_INPROC_SERVER, IID_WIC_FACTORY, ppFactory);
                if (Ole32.failed(hr)) return MemorySegment.NULL;
                return ppFactory.get(ValueLayout.ADDRESS, 0);
            } catch (Throwable t) {
                return MemorySegment.NULL;
            }
        }
    }

    /** Returns the cached WIC imaging factory (created on first call). */
    private static MemorySegment cachedFactory() {
        return FactoryHolder.INSTANCE;
    }

    // -- Public API ----------------------------------------------------------

    /**
     * Returns {@code true} if the WIC native library was loaded successfully.
     */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Probes a header chunk through WIC to check whether Windows can decode the
     * format.
     *
     * @param header first bytes of the image (4 KB is plenty for identification)
     * @param len    number of valid bytes in {@code header}
     * @return {@code true} if WIC recognised the format and can create a decoder
     */
    public static boolean canDecode(byte[] header, int len) {
        return canDecode(header, len, null);
    }

    /**
     * Probes a header chunk through WIC to check whether Windows can decode the
     * format, including an optional name for diagnostic logging.
     *
     * @param header first bytes of the image (4 KB is plenty for identification)
     * @param len    number of valid bytes in {@code header}
     * @param name   optional filename or label included in error messages (may be {@code null})
     * @return {@code true} if WIC recognised the format and can create a decoder
     */
    public static boolean canDecode(byte[] header, int len, String name) {
        if (!AVAILABLE) return false;

        MemorySegment stream = MemorySegment.NULL;
        MemorySegment decoder = MemorySegment.NULL;

        try (Arena arena = Arena.ofConfined()) {
            Ole32.coInitializeEx();

            MemorySegment factory = cachedFactory();
            if (MemorySegment.NULL.equals(factory)) return false;

            // Create IWICStream
            MemorySegment ppStream = arena.allocate(ValueLayout.ADDRESS);
            int hr = (int) Factory_CreateStream.invokeExact(
                    Ole32.vtable(factory, 14), factory, ppStream);
            if (Ole32.failed(hr)) return false;
            stream = ppStream.get(ValueLayout.ADDRESS, 0);

            // Initialize from memory
            MemorySegment buf = arena.allocate(len);
            MemorySegment.copy(header, 0, buf, JAVA_BYTE, 0, len);
            hr = (int) Stream_InitializeFromMemory.invokeExact(
                    Ole32.vtable(stream, 16), stream, buf, len);
            if (Ole32.failed(hr)) return false;

            // Try to create a decoder from the stream
            MemorySegment ppDecoder = arena.allocate(ValueLayout.ADDRESS);
            hr = (int) Factory_CreateDecoderFromStream.invokeExact(
                    Ole32.vtable(factory, 4), factory, stream,
                    MemorySegment.NULL,
                    WICDecodeMetadataCacheOnDemand, ppDecoder);
            if (Ole32.failed(hr)) {
                System.err.println("[WIC] canDecode failed" +
                        (name != null ? " for " + name : "") +
                        " (" + len + " bytes)" +
                        ", HRESULT=0x" + Integer.toHexString(hr));
                return false;
            }
            decoder = ppDecoder.get(ValueLayout.ADDRESS, 0);

            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            Ole32.release(decoder);
            Ole32.release(stream);
        }
    }

    /**
     * Returns image dimensions without full pixel decode.
     *
     * @param imageData the raw image file bytes
     * @return dimensions as {@code [width, height]} with EXIF orientation applied
     * @throws IllegalStateException    if WIC is not available
     * @throws IllegalArgumentException if the data cannot be decoded
     */
    public static Dimensions getSize(byte[] imageData) {
        ensureAvailable();

        MemorySegment stream = MemorySegment.NULL;
        MemorySegment decoder = MemorySegment.NULL;

        try (Arena arena = Arena.ofConfined()) {
            Ole32.coInitializeEx();

            MemorySegment factory = cachedFactory();
            if (MemorySegment.NULL.equals(factory))
                throw new IllegalStateException("Failed to create WIC factory");

            MemorySegment ppStream = arena.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateStream.invokeExact(
                    Ole32.vtable(factory, 14), factory, ppStream),
                    "IWICImagingFactory::CreateStream failed");
            stream = ppStream.get(ValueLayout.ADDRESS, 0);

            MemorySegment nativeBuf = arena.allocateFrom(JAVA_BYTE, imageData);
            Ole32.check((int) Stream_InitializeFromMemory.invokeExact(
                    Ole32.vtable(stream, 16), stream, nativeBuf, imageData.length),
                    "IWICStream::InitializeFromMemory failed");

            MemorySegment ppDecoder = arena.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateDecoderFromStream.invokeExact(
                    Ole32.vtable(factory, 4), factory, stream,
                    MemorySegment.NULL, WICDecodeMetadataCacheOnDemand, ppDecoder),
                    "Unsupported image format");
            decoder = ppDecoder.get(ValueLayout.ADDRESS, 0);

            return getSizeFromDecoder(arena, decoder);
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("WIC size query failed", t);
        } finally {
            Ole32.release(decoder);
            Ole32.release(stream);
        }
    }

    /**
     * Returns image dimensions from a MemorySegment without full pixel decode.
     * EXIF orientation is applied.
     *
     * @param imageData segment containing raw image file bytes
     * @param size      number of bytes to read from {@code imageData}
     * @return image dimensions
     * @throws IllegalStateException    if WIC is not available
     * @throws IllegalArgumentException if the data cannot be decoded
     */
    public static Dimensions getSize(MemorySegment imageData, long size) {
        ensureAvailable();

        MemorySegment stream = MemorySegment.NULL;
        MemorySegment decoder = MemorySegment.NULL;

        try (Arena arena = Arena.ofConfined()) {
            Ole32.coInitializeEx();

            MemorySegment factory = cachedFactory();
            if (MemorySegment.NULL.equals(factory))
                throw new IllegalStateException("Failed to create WIC factory");

            MemorySegment ppStream = arena.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateStream.invokeExact(
                    Ole32.vtable(factory, 14), factory, ppStream),
                    "IWICImagingFactory::CreateStream failed");
            stream = ppStream.get(ValueLayout.ADDRESS, 0);

            Ole32.check((int) Stream_InitializeFromMemory.invokeExact(
                    Ole32.vtable(stream, 16), stream, imageData, (int) size),
                    "IWICStream::InitializeFromMemory failed");

            MemorySegment ppDecoder = arena.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateDecoderFromStream.invokeExact(
                    Ole32.vtable(factory, 4), factory, stream,
                    MemorySegment.NULL, WICDecodeMetadataCacheOnDemand, ppDecoder),
                    "Unsupported image format");
            decoder = ppDecoder.get(ValueLayout.ADDRESS, 0);

            return getSizeFromDecoder(arena, decoder);
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("WIC size query failed", t);
        } finally {
            Ole32.release(decoder);
            Ole32.release(stream);
        }
    }

    /**
     * Returns image dimensions by reading directly from a file path.
     * Avoids loading the entire file into the Java heap.
     *
     * @param path absolute file path
     * @return image dimensions with EXIF orientation applied
     * @throws IllegalStateException    if WIC is not available
     * @throws IllegalArgumentException if the file cannot be decoded
     */
    public static Dimensions getSize(String path) {
        ensureAvailable();

        MemorySegment decoder = MemorySegment.NULL;

        try (Arena arena = Arena.ofConfined()) {
            Ole32.coInitializeEx();

            MemorySegment factory = cachedFactory();
            if (MemorySegment.NULL.equals(factory))
                throw new IllegalStateException("Failed to create WIC factory");

            MemorySegment wpath = Ole32.wstr(arena, path);
            MemorySegment ppDecoder = arena.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateDecoderFromFilename.invokeExact(
                    Ole32.vtable(factory, 3), factory, wpath,
                    MemorySegment.NULL, GENERIC_READ,
                    WICDecodeMetadataCacheOnDemand, ppDecoder),
                    "Unsupported image format: " + path);
            decoder = ppDecoder.get(ValueLayout.ADDRESS, 0);

            return getSizeFromDecoder(arena, decoder);
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("WIC size query failed for: " + path, t);
        } finally {
            Ole32.release(decoder);
        }
    }

    /**
    * Decodes a WIC image from a byte array into Arena-managed BGRA pixel memory.
     *
     * @param arena     the arena that owns the output pixel memory
     * @param imageData raw image file bytes
    * @return decoded image with BGRA pixels
     * @throws IllegalStateException    if WIC is not available
     * @throws IllegalArgumentException if decoding fails
     */
    public static DecodedImage<PixelFormat> decode(Arena arena, byte[] imageData) {
        ensureAvailable();
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment dataSeg = temp.allocateFrom(JAVA_BYTE, imageData);
            return decodeFromMemory(arena, temp, dataSeg, imageData.length);
        }
    }

    /**
    * Decodes a WIC image from a MemorySegment into Arena-managed BGRA pixel memory.
     *
     * @param arena     the arena that owns the output pixel memory
     * @param imageData segment containing raw image file bytes
     * @param size      number of bytes to read from the segment
    * @return decoded image with BGRA pixels
     * @throws IllegalStateException    if WIC is not available
     * @throws IllegalArgumentException if decoding fails
     */
    public static DecodedImage<PixelFormat> decode(Arena arena, MemorySegment imageData, long size) {
        ensureAvailable();
        try (Arena temp = Arena.ofConfined()) {
            return decodeFromMemory(arena, temp, imageData, size);
        }
    }

    /**
    * Decodes a WIC image directly from a file path into Arena-managed BGRA pixel
     * memory. Avoids loading the entire file into the Java heap.
     *
     * @param arena the arena that owns the output pixel memory
     * @param path  file system path to the image file
    * @return decoded image with BGRA pixels
     * @throws IllegalStateException    if WIC is not available
     * @throws IllegalArgumentException if decoding fails
     */
    public static DecodedImage<PixelFormat> decodeFromPath(Arena arena, String path) {
        ensureAvailable();

        MemorySegment decoder = MemorySegment.NULL;

        try (Arena temp = Arena.ofConfined()) {
            Ole32.coInitializeEx();

            MemorySegment factory = cachedFactory();
            if (MemorySegment.NULL.equals(factory))
                throw new IllegalStateException("Failed to create WIC factory");

            MemorySegment wpath = Ole32.wstr(temp, path);
            MemorySegment ppDecoder = temp.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateDecoderFromFilename.invokeExact(
                    Ole32.vtable(factory, 3), factory, wpath,
                    MemorySegment.NULL, GENERIC_READ,
                    WICDecodeMetadataCacheOnDemand, ppDecoder),
                    "WIC decode failed for: " + path);
            decoder = ppDecoder.get(ValueLayout.ADDRESS, 0);

            return decodeFromDecoder(arena, temp, factory, decoder);
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("WIC decodeFromPath failed: " + path, t);
        } finally {
            Ole32.release(decoder);
        }
    }

    /**
     * Decodes a <em>source-scaled</em> thumbnail directly from a file path into
     * Arena-managed BGRA pixel memory: WIC scales the image down during decode
     * (via {@code IWICBitmapScaler}, with an embedded-thumbnail fast path when
     * available) instead of producing a full-resolution bitmap that the caller
     * then shrinks. EXIF orientation is applied. Avoids loading the entire file
     * into the Java heap.
     * <p>
     * The longest output side is {@code <= maxPixelSize}; the image is never
     * upscaled (a request larger than the source yields a full-resolution
     * decode). Aspect ratio is preserved.
     * <p>
     * This mirrors the CGImageSource {@code decodeThumbnailFromPath} contract so
     * the iris94 reflective bridge can treat both Windows and macOS native
     * backends as source-scaling providers.
     *
     * @param arena        the arena that owns the output pixel memory
     * @param path         file system path to the image file
     * @param maxPixelSize maximum length of the longest output side, in pixels
     * @return decoded thumbnail with BGRA pixels
     * @throws IllegalStateException    if WIC is not available
     * @throws IllegalArgumentException if {@code maxPixelSize <= 0}
     * @throws DecodeException          if decoding fails
     */
    public static DecodedImage<PixelFormat> decodeThumbnailFromPath(
            Arena arena, String path, int maxPixelSize) {
        ensureAvailable();
        if (maxPixelSize <= 0)
            throw new IllegalArgumentException("maxPixelSize must be > 0, got " + maxPixelSize);

        MemorySegment decoder = MemorySegment.NULL;

        try (Arena temp = Arena.ofConfined()) {
            Ole32.coInitializeEx();

            MemorySegment factory = cachedFactory();
            if (MemorySegment.NULL.equals(factory))
                throw new IllegalStateException("Failed to create WIC factory");

            MemorySegment wpath = Ole32.wstr(temp, path);
            MemorySegment ppDecoder = temp.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateDecoderFromFilename.invokeExact(
                    Ole32.vtable(factory, 3), factory, wpath,
                    MemorySegment.NULL, GENERIC_READ,
                    WICDecodeMetadataCacheOnDemand, ppDecoder),
                    "WIC thumbnail decode failed for: " + path);
            decoder = ppDecoder.get(ValueLayout.ADDRESS, 0);

            return decodeThumbnailFromDecoder(arena, temp, factory, decoder, maxPixelSize);
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("WIC decodeThumbnailFromPath failed: " + path, t);
        } finally {
            Ole32.release(decoder);
        }
    }

    /**
     * Enumerates the full metadata of an image's first frame as a flat,
     * insertion-ordered map of {@code WIC query path -> stringified value}
     * (e.g. {@code /app1/ifd/{ushort=274} -> 6},
     * {@code /app1/ifd/exif/{ushort=33437} -> 28/10},
     * {@code /app1/ifd/gps/{ushort=2} -> ...}).
     * <p>
     * <b>Enumeration, not a curated set.</b> Of the two options the Windows-native
     * handoff offered, this implements the more complete one: it walks the frame's
     * {@code IWICMetadataQueryReader} recursively with {@code GetEnumerator}
     * ({@code IEnumString::Next}) and descends every nested metadata block
     * ({@code VT_UNKNOWN} sub-reader — the EXIF/GPS sub-IFDs, XMP, IPTC), prefixing
     * child paths with the parent path. So EXIF, GPS, XMP and IPTC all surface
     * without per-tag wiring.
     * <p>
     * Each leaf PROPVARIANT is stringified by VARTYPE: integers/floats as decimal,
     * {@code VT_LPWSTR}/{@code VT_LPSTR}/{@code VT_BSTR} as text, small numeric or
     * string vectors joined, and blobs / byte-vectors rendered as a
     * {@code "<binary, N bytes>"} placeholder whose payload is <em>never</em>
     * copied onto the heap. The walk is bounded — at most
     * {@value #MAX_METADATA_ENTRIES} entries, {@value #MAX_METADATA_DEPTH} levels
     * deep, strings capped at {@value #MAX_STRING_CHARS} chars and vectors at
     * {@value #MAX_VECTOR_ELEMS} elements — so a multi-MB XMP packet cannot be
     * pulled wholesale onto the heap.
     * <p>
     * <b>Lifetime.</b> Every PROPVARIANT filled here is {@code PropVariantClear}'d
     * and every {@code LPOLESTR} from the enumerator is {@code CoTaskMemFree}'d,
     * and all values are copied into Java strings before the confined arena
     * closes, so nothing escapes native lifetime.
     *
     * @param path absolute file path
     * @return insertion-ordered {@code query-path -> value} map (possibly empty)
     * @throws IllegalStateException if WIC is not available
     * @throws DecodeException       if the file cannot be opened / decoded
     */
    public static Map<String, String> readMetadata(String path) {
        ensureAvailable();

        MemorySegment decoder = MemorySegment.NULL;
        Map<String, String> out = new LinkedHashMap<>();

        try (Arena temp = Arena.ofConfined()) {
            Ole32.coInitializeEx();

            MemorySegment factory = cachedFactory();
            if (MemorySegment.NULL.equals(factory))
                throw new IllegalStateException("Failed to create WIC factory");

            MemorySegment wpath = Ole32.wstr(temp, path);
            MemorySegment ppDecoder = temp.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateDecoderFromFilename.invokeExact(
                    Ole32.vtable(factory, 3), factory, wpath,
                    MemorySegment.NULL, GENERIC_READ,
                    WICDecodeMetadataCacheOnDemand, ppDecoder),
                    "WIC metadata read failed for: " + path);
            decoder = ppDecoder.get(ValueLayout.ADDRESS, 0);

            MemorySegment frame = MemorySegment.NULL;
            try {
                MemorySegment ppFrame = temp.allocate(ValueLayout.ADDRESS);
                Ole32.check((int) Decoder_GetFrame.invokeExact(
                        Ole32.vtable(decoder, 13), decoder, 0, ppFrame),
                        "IWICBitmapDecoder::GetFrame(0) failed");
                frame = ppFrame.get(ValueLayout.ADDRESS, 0);

                MemorySegment ppReader = temp.allocate(ValueLayout.ADDRESS);
                int hr = (int) Frame_GetMetadataQueryReader.invokeExact(
                        Ole32.vtable(frame, 8), frame, ppReader);
                if (!Ole32.failed(hr)) {
                    MemorySegment reader = ppReader.get(ValueLayout.ADDRESS, 0);
                    try {
                        walkMetadata(temp, reader, "", out, 0);
                    } finally {
                        Ole32.release(reader);
                    }
                }
            } finally {
                Ole32.release(frame);
            }
            return out;
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("WIC readMetadata failed: " + path, t);
        } finally {
            Ole32.release(decoder);
        }
    }

    // -- Pixel-format description --------------------------------------------

    /**
     * Base GUID shared by the standard {@code GUID_WICPixelFormat*} formats:
     * {@code {6FDDC324-4E03-4BFE-B185-3D77768DC9<XX>}}, where the trailing byte
     * {@code XX} discriminates the format (e.g. {@code 0x0F} = 32bppBGRA). The
     * keys below are exactly what {@link #readGuid} emits — uppercase, braced.
     */
    private static final String PIXEL_FORMAT_BASE = "{6FDDC324-4E03-4BFE-B185-3D77768DC9";

    /**
     * Curated map of the common {@code GUID_WICPixelFormat*} GUIDs (as formatted
     * by {@link #readGuid}) to a short bit-depth / channel-layout description.
     * Covers the formats real decoders report for JPEG/PNG/GIF/TIFF/BMP stills;
     * anything outside this set falls back to {@link #describeUnknownPixelFormat}.
     */
    private static final Map<String, String> PIXEL_FORMAT_NAMES = buildPixelFormatNames();

    private static Map<String, String> buildPixelFormatNames() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(PIXEL_FORMAT_BASE + "01}", "1-bit Indexed");
        m.put(PIXEL_FORMAT_BASE + "02}", "2-bit Indexed");
        m.put(PIXEL_FORMAT_BASE + "03}", "4-bit Indexed");
        m.put(PIXEL_FORMAT_BASE + "04}", "8-bit Indexed");
        m.put(PIXEL_FORMAT_BASE + "05}", "1-bit Black & White");
        m.put(PIXEL_FORMAT_BASE + "06}", "2-bit Gray");
        m.put(PIXEL_FORMAT_BASE + "07}", "4-bit Gray");
        m.put(PIXEL_FORMAT_BASE + "08}", "8-bit Gray");
        m.put(PIXEL_FORMAT_BASE + "09}", "16-bit BGR 555");
        m.put(PIXEL_FORMAT_BASE + "0A}", "16-bit BGR 565");
        m.put(PIXEL_FORMAT_BASE + "0B}", "16-bit Gray");
        m.put(PIXEL_FORMAT_BASE + "0C}", "24-bit BGR");
        m.put(PIXEL_FORMAT_BASE + "0D}", "24-bit RGB");
        m.put(PIXEL_FORMAT_BASE + "0E}", "32-bit BGR");
        m.put(PIXEL_FORMAT_BASE + "0F}", "32-bit BGRA");
        m.put(PIXEL_FORMAT_BASE + "10}", "32-bit premultiplied BGRA");
        m.put(PIXEL_FORMAT_BASE + "14}", "32-bit BGR 101010");
        m.put(PIXEL_FORMAT_BASE + "15}", "48-bit RGB");
        m.put(PIXEL_FORMAT_BASE + "16}", "64-bit RGBA");
        return m;
    }

    /**
     * Returns a short, human-readable description of a still's native pixel
     * format — bit depth and channel layout, e.g. {@code "24-bit RGB"},
     * {@code "32-bit BGRA"}, {@code "8-bit Gray"} — read from the first frame's
     * {@code IWICBitmapFrameDecode::GetPixelFormat} ({@code IWICBitmapSource}
     * vtable[4]).
     * <p>
     * The returned format is the <em>decoded source</em> format, not the BGRA
     * the {@code decode*} entry points convert to. Common standard
     * {@code GUID_WICPixelFormat*} GUIDs are looked up in a curated map; an
     * unrecognised GUID falls back to {@code IWICPixelFormatInfo} for a generic
     * bits-per-pixel / channel-count description (e.g. {@code "32-bit, 4 channels"}).
     * Returns {@code null} when WIC is unavailable or the format is unknown and
     * its component info cannot be queried — never throws for an unrecognised
     * format (only for a genuinely undecodable file).
     *
     * @param path absolute file path
     * @return a pixel-format description, or {@code null} if indeterminate
     * @throws IllegalStateException if WIC is not available
     * @throws DecodeException       if the file cannot be opened / decoded
     */
    public static String describePixelFormat(String path) {
        ensureAvailable();

        MemorySegment decoder = MemorySegment.NULL;

        try (Arena temp = Arena.ofConfined()) {
            Ole32.coInitializeEx();

            MemorySegment factory = cachedFactory();
            if (MemorySegment.NULL.equals(factory))
                throw new IllegalStateException("Failed to create WIC factory");

            MemorySegment wpath = Ole32.wstr(temp, path);
            MemorySegment ppDecoder = temp.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateDecoderFromFilename.invokeExact(
                    Ole32.vtable(factory, 3), factory, wpath,
                    MemorySegment.NULL, GENERIC_READ,
                    WICDecodeMetadataCacheOnDemand, ppDecoder),
                    "WIC pixel-format query failed for: " + path);
            decoder = ppDecoder.get(ValueLayout.ADDRESS, 0);

            MemorySegment frame = MemorySegment.NULL;
            try {
                MemorySegment ppFrame = temp.allocate(ValueLayout.ADDRESS);
                Ole32.check((int) Decoder_GetFrame.invokeExact(
                        Ole32.vtable(decoder, 13), decoder, 0, ppFrame),
                        "IWICBitmapDecoder::GetFrame(0) failed");
                frame = ppFrame.get(ValueLayout.ADDRESS, 0);

                // IWICBitmapSource::GetPixelFormat (vtable[4]) -> WICPixelFormatGUID
                MemorySegment pGuid = temp.allocate(Ole32.GUID_LAYOUT);
                Ole32.check((int) Source_GetPixelFormat.invokeExact(
                        Ole32.vtable(frame, 4), frame, pGuid),
                        "IWICBitmapSource::GetPixelFormat failed");

                String guid = readGuid(pGuid);
                if (guid == null) return null;
                String mapped = PIXEL_FORMAT_NAMES.get(guid);
                if (mapped != null) return mapped;
                // Unknown GUID: best-effort generic description via component info.
                return describeUnknownPixelFormat(temp, factory, pGuid);
            } finally {
                Ole32.release(frame);
            }
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("WIC describePixelFormat failed: " + path, t);
        } finally {
            Ole32.release(decoder);
        }
    }

    /**
     * Fallback description for a pixel-format GUID outside {@link #PIXEL_FORMAT_NAMES}:
     * opens {@code IWICComponentInfo} ({@code IWICImagingFactory::CreateComponentInfo},
     * vtable[6]) for the GUID, queries {@code IWICPixelFormatInfo}, and reads its
     * bits-per-pixel ({@code GetBitsPerPixel}, vtable[13]) and channel count
     * ({@code GetChannelCount}, vtable[14]) into a generic
     * {@code "<bpp>-bit, <n> channels"} string. Fully defensive: any failure (no
     * component info, QI miss, native error) degrades to {@code null} rather than
     * throwing, so an exotic format never breaks a probe.
     */
    private static String describeUnknownPixelFormat(Arena temp, MemorySegment factory,
                                                     MemorySegment pGuid) {
        MemorySegment info = MemorySegment.NULL;
        MemorySegment pfInfo = MemorySegment.NULL;
        try {
            MemorySegment ppInfo = temp.allocate(ValueLayout.ADDRESS);
            int hr = (int) Factory_CreateComponentInfo.invokeExact(
                    Ole32.vtable(factory, 6), factory, pGuid, ppInfo);
            if (Ole32.failed(hr)) return null;
            info = ppInfo.get(ValueLayout.ADDRESS, 0);
            if (MemorySegment.NULL.equals(info)) return null;

            MemorySegment ppPf = temp.allocate(ValueLayout.ADDRESS);
            hr = Ole32.queryInterface(info, IID_WIC_PIXEL_FORMAT_INFO, ppPf);
            if (Ole32.failed(hr)) return null;
            pfInfo = ppPf.get(ValueLayout.ADDRESS, 0);
            if (MemorySegment.NULL.equals(pfInfo)) return null;

            MemorySegment pBpp = temp.allocate(JAVA_INT);
            if (Ole32.failed((int) PixelFormatInfo_GetBitsPerPixel.invokeExact(
                    Ole32.vtable(pfInfo, 13), pfInfo, pBpp))) return null;
            int bpp = pBpp.get(JAVA_INT, 0);
            if (bpp <= 0) return null;

            MemorySegment pCh = temp.allocate(JAVA_INT);
            int channels = -1;
            if (!Ole32.failed((int) PixelFormatInfo_GetChannelCount.invokeExact(
                    Ole32.vtable(pfInfo, 14), pfInfo, pCh))) {
                channels = pCh.get(JAVA_INT, 0);
            }
            if (channels > 0) {
                return bpp + "-bit, " + channels + (channels == 1 ? " channel" : " channels");
            }
            return bpp + "-bit";
        } catch (Throwable t) {
            return null;
        } finally {
            Ole32.release(pfInfo);
            Ole32.release(info);
        }
    }

    // -- Internal implementation ---------------------------------------------

    /**
     * Memory-based decode: create stream, decoder, then decode.
     */
    private static DecodedImage<PixelFormat> decodeFromMemory(Arena arena, Arena temp,
                                                  MemorySegment dataSeg, long dataSize) {
        MemorySegment stream = MemorySegment.NULL;
        MemorySegment decoder = MemorySegment.NULL;

        try {
            Ole32.coInitializeEx();

            MemorySegment factory = cachedFactory();
            if (MemorySegment.NULL.equals(factory))
                throw new IllegalStateException("Failed to create WIC factory");

            // Create IWICStream
            MemorySegment ppStream = temp.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateStream.invokeExact(
                    Ole32.vtable(factory, 14), factory, ppStream),
                    "IWICImagingFactory::CreateStream failed");
            stream = ppStream.get(ValueLayout.ADDRESS, 0);

            // Initialize from memory
            Ole32.check((int) Stream_InitializeFromMemory.invokeExact(
                    Ole32.vtable(stream, 16), stream, dataSeg, (int) dataSize),
                    "IWICStream::InitializeFromMemory failed");

            // Create decoder from stream
            MemorySegment ppDecoder = temp.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateDecoderFromStream.invokeExact(
                    Ole32.vtable(factory, 4), factory, stream,
                    MemorySegment.NULL, WICDecodeMetadataCacheOnDemand, ppDecoder),
                    "IWICImagingFactory::CreateDecoderFromStream failed");
            decoder = ppDecoder.get(ValueLayout.ADDRESS, 0);

            return decodeFromDecoder(arena, temp, factory, decoder);
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("WIC decode failed", t);
        } finally {
            Ole32.release(decoder);
            Ole32.release(stream);
        }
    }

    /**
     * Shared decode pipeline: get frame, apply EXIF orientation, convert to
     * 32bppPBGRA, copy pixels into caller's Arena.
     */
    private static DecodedImage<PixelFormat> decodeFromDecoder(Arena arena, Arena temp,
                                                  MemorySegment factory,
                                                  MemorySegment decoder) throws Throwable {
        MemorySegment frame = MemorySegment.NULL;
        try {
            // GetFrame(0) -> IWICBitmapFrameDecode
            MemorySegment ppFrame = temp.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Decoder_GetFrame.invokeExact(
                    Ole32.vtable(decoder, 13), decoder, 0, ppFrame),
                    "IWICBitmapDecoder::GetFrame(0) failed");
            frame = ppFrame.get(ValueLayout.ADDRESS, 0);

            int orientation = readExifOrientation(temp, frame);
            return convertSourceToBgra(arena, temp, factory, frame, orientation);
        } finally {
            Ole32.release(frame);
        }
    }

    /**
     * Shared tail of every decode path: apply the EXIF {@code orientation} via an
     * optional flip-rotator, convert to 32bppPBGRA, read the (post-rotation)
     * dimensions, and copy pixels into the caller's {@code arena}. The pixels'
     * orientation is baked in, so the returned image is upright.
     * <p>
     * The input {@code source} (a frame, a scaler, or an embedded thumbnail) is
     * owned by the caller and is <em>not</em> released here; this helper only
     * creates and releases its own flip-rotator and converter.
     */
    private static DecodedImage<PixelFormat> convertSourceToBgra(Arena arena, Arena temp,
                                                  MemorySegment factory,
                                                  MemorySegment source,
                                                  int orientation) throws Throwable {
        MemorySegment flipRotator = MemorySegment.NULL;
        MemorySegment converter = MemorySegment.NULL;
        try {
            // EXIF orientation -> optional flip-rotator
            MemorySegment converterSource = source;
            if (orientation != 1) {
                int transform = exifToWicTransform(orientation);
                if (transform != WICBitmapTransformRotate0) {
                    MemorySegment ppFlipRotator = temp.allocate(ValueLayout.ADDRESS);
                    Ole32.check((int) Factory_CreateBitmapFlipRotator.invokeExact(
                            Ole32.vtable(factory, 13), factory, ppFlipRotator),
                            "IWICImagingFactory::CreateBitmapFlipRotator failed");
                    flipRotator = ppFlipRotator.get(ValueLayout.ADDRESS, 0);

                    Ole32.check((int) FlipRotator_Initialize.invokeExact(
                            Ole32.vtable(flipRotator, 8), flipRotator, source, transform),
                            "IWICBitmapFlipRotator::Initialize failed");
                    converterSource = flipRotator;
                }
            }

            // Format converter -> 32bppPBGRA
            MemorySegment ppConverter = temp.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateFormatConverter.invokeExact(
                    Ole32.vtable(factory, 10), factory, ppConverter),
                    "IWICImagingFactory::CreateFormatConverter failed");
            converter = ppConverter.get(ValueLayout.ADDRESS, 0);

            Ole32.check((int) Converter_Initialize.invokeExact(
                    Ole32.vtable(converter, 8), converter, converterSource,
                    GUID_PIXEL_FORMAT_PBGRA,
                    WICBitmapDitherTypeNone,
                    MemorySegment.NULL, 0.0, WICBitmapPaletteTypeCustom),
                    "IWICFormatConverter::Initialize failed");

            // Get dimensions (post-rotation)
            MemorySegment pWidth = temp.allocate(JAVA_INT);
            MemorySegment pHeight = temp.allocate(JAVA_INT);
            Ole32.check((int) Source_GetSize.invokeExact(
                    Ole32.vtable(converter, 3), converter, pWidth, pHeight),
                    "IWICBitmapSource::GetSize failed");
            int w = pWidth.get(JAVA_INT, 0);
            int h = pHeight.get(JAVA_INT, 0);
            ImageDimensions.validateDimensions(w, h);

            // Copy pixels into caller's Arena
            int stride = w * RGBA_BPP;
            long outputSize = (long) stride * h;
            MemorySegment output = arena.allocate(JAVA_BYTE, outputSize);

            Ole32.check((int) Source_CopyPixels.invokeExact(
                    Ole32.vtable(converter, 7), converter,
                    MemorySegment.NULL, stride, (int) outputSize, output),
                    "IWICBitmapSource::CopyPixels failed");

            return new DecodedImage<>(output, w, h, stride, PixelFormat.BGRA);
        } finally {
            Ole32.release(converter);
            Ole32.release(flipRotator);
        }
    }

    /**
     * Source-scaled thumbnail decode pipeline shared by
     * {@link #decodeThumbnailFromPath(Arena, String, int)}: read the frame's
     * native size, and
     * <ul>
     *   <li>if the request would not shrink the image
     *       ({@code maxPixelSize >= max(nativeW, nativeH)}), fall back to the full
     *       {@link #convertSourceToBgra} decode (WIC never upscales here); else</li>
     *   <li>try the embedded thumbnail fast path ({@code IWICBitmapFrameDecode::GetThumbnail});
     *       else</li>
     *   <li>scale the frame down with {@code IWICBitmapScaler} (the always-correct
     *       baseline).</li>
     * </ul>
     * The scale factor is computed in <em>native</em> (pre-rotation) space, so
     * after the EXIF flip-rotator both output sides are {@code <= maxPixelSize}
     * regardless of orientation.
     */
    private static DecodedImage<PixelFormat> decodeThumbnailFromDecoder(Arena arena, Arena temp,
                                                  MemorySegment factory,
                                                  MemorySegment decoder,
                                                  int maxPixelSize) throws Throwable {
        MemorySegment frame = MemorySegment.NULL;
        try {
            MemorySegment ppFrame = temp.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Decoder_GetFrame.invokeExact(
                    Ole32.vtable(decoder, 13), decoder, 0, ppFrame),
                    "IWICBitmapDecoder::GetFrame(0) failed");
            frame = ppFrame.get(ValueLayout.ADDRESS, 0);

            // Native (pre-orientation) dimensions.
            MemorySegment pW = temp.allocate(JAVA_INT);
            MemorySegment pH = temp.allocate(JAVA_INT);
            Ole32.check((int) Source_GetSize.invokeExact(
                    Ole32.vtable(frame, 3), frame, pW, pH),
                    "IWICBitmapSource::GetSize failed");
            int nativeW = pW.get(JAVA_INT, 0);
            int nativeH = pH.get(JAVA_INT, 0);
            int orientation = readExifOrientation(temp, frame);

            int nativeMax = Math.max(nativeW, nativeH);
            if (nativeMax <= 0 || maxPixelSize >= nativeMax) {
                // Never upscale: a full decode of an already-small image is cheap.
                return convertSourceToBgra(arena, temp, factory, frame, orientation);
            }

            double scale = (double) maxPixelSize / (double) nativeMax;
            int targetW = Math.max(1, (int) Math.round(nativeW * scale));
            int targetH = Math.max(1, (int) Math.round(nativeH * scale));

            // B3 fast path: an embedded thumbnail large enough to satisfy the
            // request skips the main-image decode entirely. Restricted to
            // upright images so we never have to reason about whether a camera
            // pre-rotated its embedded thumbnail.
            if (orientation == 1) {
                DecodedImage<PixelFormat> embedded = tryEmbeddedThumbnailToBgra(
                        arena, temp, factory, frame, nativeW, nativeH, targetW, targetH);
                if (embedded != null) {
                    return embedded;
                }
            }

            // B1 baseline: scale the frame down, then orient + convert. (A further
            // optional fast path — IWICBitmapSourceTransform::GetClosestSize/CopyPixels
            // for true JPEG/RAW DCT-scaled decode — could slot in here ahead of the
            // scaler, always falling back to this baseline when the codec declines the
            // transform or the destination format.)
            return scaleSourceToBgra(arena, temp, factory, frame, targetW, targetH, orientation);
        } finally {
            Ole32.release(frame);
        }
    }

    /**
     * Scales {@code source} to {@code targetW x targetH} with an
     * {@code IWICBitmapScaler} (Fant interpolation), then orients and converts to
     * BGRA via {@link #convertSourceToBgra}. The scaler is applied <em>before</em>
     * the flip-rotator so the (cheaper) rotation runs on the already-small image.
     */
    private static DecodedImage<PixelFormat> scaleSourceToBgra(Arena arena, Arena temp,
                                                  MemorySegment factory,
                                                  MemorySegment source,
                                                  int targetW, int targetH,
                                                  int orientation) throws Throwable {
        MemorySegment scaler = MemorySegment.NULL;
        try {
            MemorySegment ppScaler = temp.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateBitmapScaler.invokeExact(
                    Ole32.vtable(factory, 11), factory, ppScaler),
                    "IWICImagingFactory::CreateBitmapScaler failed");
            scaler = ppScaler.get(ValueLayout.ADDRESS, 0);

            Ole32.check((int) Scaler_Initialize.invokeExact(
                    Ole32.vtable(scaler, 8), scaler, source, targetW, targetH,
                    WICBitmapInterpolationModeFant),
                    "IWICBitmapScaler::Initialize failed");

            return convertSourceToBgra(arena, temp, factory, scaler, orientation);
        } finally {
            Ole32.release(scaler);
        }
    }

    /**
     * Embedded-thumbnail fast path ({@code IWICBitmapFrameDecode::GetThumbnail}).
     * Returns a scaled BGRA image when the frame carries an embedded thumbnail
     * that is (a) large enough to downscale to the target without upscaling and
     * (b) within a small tolerance of the full image's aspect ratio — guarding
     * against the letterboxed / fixed-size thumbnails some cameras embed — and
     * {@code null} otherwise so the caller falls back to the scaler baseline.
     * Only called for upright frames (orientation == 1).
     */
    private static DecodedImage<PixelFormat> tryEmbeddedThumbnailToBgra(Arena arena, Arena temp,
                                                  MemorySegment factory,
                                                  MemorySegment frame,
                                                  int nativeW, int nativeH,
                                                  int targetW, int targetH) {
        MemorySegment thumb = MemorySegment.NULL;
        try {
            MemorySegment ppThumb = temp.allocate(ValueLayout.ADDRESS);
            int hr = (int) Frame_GetThumbnail.invokeExact(
                    Ole32.vtable(frame, 10), frame, ppThumb);
            if (Ole32.failed(hr)) {
                return null; // WINCODEC_ERR_CODECNOTHUMBNAIL or similar.
            }
            thumb = ppThumb.get(ValueLayout.ADDRESS, 0);
            if (MemorySegment.NULL.equals(thumb)) {
                return null;
            }

            MemorySegment pW = temp.allocate(JAVA_INT);
            MemorySegment pH = temp.allocate(JAVA_INT);
            if (Ole32.failed((int) Source_GetSize.invokeExact(
                    Ole32.vtable(thumb, 3), thumb, pW, pH))) {
                return null;
            }
            int tw = pW.get(JAVA_INT, 0);
            int th = pH.get(JAVA_INT, 0);

            // Big enough to downscale (never upscale the embedded thumbnail)...
            if (tw < targetW || th < targetH) {
                return null;
            }
            // ...and matching the full image's aspect within ~2% (cross-multiplied
            // so we avoid floating point and divide-by-zero).
            long a = (long) tw * nativeH;
            long b = (long) th * nativeW;
            long tolerance = (long) (0.02 * (double) tw * (double) nativeH) + 1;
            if (Math.abs(a - b) > tolerance) {
                return null;
            }

            // The thumbnail is already upright (orientation == 1 here).
            return scaleSourceToBgra(arena, temp, factory, thumb, targetW, targetH, 1);
        } catch (Throwable t) {
            return null; // Any trouble: fall back to the scaler baseline.
        } finally {
            Ole32.release(thumb);
        }
    }

    // -- Size query from decoder ---------------------------------------------

    /**
     * Reads image dimensions from a WIC decoder (metadata only, no pixel copy).
     */
    private static Dimensions getSizeFromDecoder(Arena arena, MemorySegment decoder) throws Throwable {
        MemorySegment frame = MemorySegment.NULL;
        try {
            MemorySegment ppFrame = arena.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Decoder_GetFrame.invokeExact(
                    Ole32.vtable(decoder, 13), decoder, 0, ppFrame),
                    "Failed to read image frame");
            frame = ppFrame.get(ValueLayout.ADDRESS, 0);

            MemorySegment pWidth = arena.allocate(JAVA_INT);
            MemorySegment pHeight = arena.allocate(JAVA_INT);
            Ole32.check((int) Source_GetSize.invokeExact(
                    Ole32.vtable(frame, 3), frame, pWidth, pHeight),
                    "IWICBitmapSource::GetSize failed");
            int w = pWidth.get(JAVA_INT, 0);
            int h = pHeight.get(JAVA_INT, 0);

            int orientation = readExifOrientation(arena, frame);
            if (orientation >= 5 && orientation <= 8) {
                return new Dimensions(h, w);
            }
            return new Dimensions(w, h);
        } finally {
            Ole32.release(frame);
        }
    }

    // -- EXIF orientation helpers ---------------------------------------------

    /**
     * Reads the EXIF orientation tag from a WIC frame's metadata.
     * <p>
     * Tries JPEG path first ({@code /app1/ifd/{ushort=274}}), then
     * TIFF/HEIC/AVIF path ({@code /ifd/{ushort=274}}).
     *
     * @param arena arena for temporary allocations
     * @param frame the IWICBitmapFrameDecode COM object
     * @return EXIF orientation value (1-8), or 1 if not found / error
     */
    private static int readExifOrientation(Arena arena, MemorySegment frame) {
        MemorySegment reader = MemorySegment.NULL;
        try {
            MemorySegment ppReader = arena.allocate(ValueLayout.ADDRESS);
            int hr = (int) Frame_GetMetadataQueryReader.invokeExact(
                    Ole32.vtable(frame, 8), frame, ppReader);
            if (Ole32.failed(hr)) return 1;
            reader = ppReader.get(ValueLayout.ADDRESS, 0);

            String[] paths = {"/app1/ifd/{ushort=274}", "/ifd/{ushort=274}"};
            for (String path : paths) {
                MemorySegment propvariant = arena.allocate(PROPVARIANT_SIZE);
                propvariant.fill((byte) 0);
                MemorySegment wsPath = Ole32.wstr(arena, path);

                hr = (int) MetadataReader_GetMetadataByName.invokeExact(
                        Ole32.vtable(reader, 5), reader, wsPath, propvariant);
                if (Ole32.failed(hr)) continue;

                short vt = propvariant.get(ValueLayout.JAVA_SHORT, 0);
                if (vt == VT_UI2) {
                    int orientation = Short.toUnsignedInt(propvariant.get(ValueLayout.JAVA_SHORT, 8));
                    if (orientation >= 1 && orientation <= 8) {
                        return orientation;
                    }
                }
            }
            return 1;
        } catch (Throwable t) {
            return 1;
        } finally {
            Ole32.release(reader);
        }
    }

    /**
     * Maps EXIF orientation (1-8) to WICBitmapTransformOptions flags.
     */
    private static int exifToWicTransform(int orientation) {
        return switch (orientation) {
            case 2 -> WICBitmapTransformFlipHorizontal;
            case 3 -> WICBitmapTransformRotate180;
            case 4 -> WICBitmapTransformFlipVertical;
            case 5 -> WICBitmapTransformRotate90 | WICBitmapTransformFlipHorizontal;
            case 6 -> WICBitmapTransformRotate90;
            case 7 -> WICBitmapTransformRotate270 | WICBitmapTransformFlipHorizontal;
            case 8 -> WICBitmapTransformRotate270;
            default -> WICBitmapTransformRotate0;
        };
    }

    // -- Metadata enumeration ------------------------------------------------

    /**
     * Recursively walks an {@code IWICMetadataQueryReader}, appending each leaf
     * path's stringified value to {@code out}. Nested {@code VT_UNKNOWN} readers
     * (EXIF/GPS sub-IFDs, XMP, IPTC) are descended with their child paths
     * prefixed by {@code prefix}; the walk is bounded by
     * {@link #MAX_METADATA_ENTRIES} and {@link #MAX_METADATA_DEPTH}. Every
     * enumerator string is {@code CoTaskMemFree}'d and every PROPVARIANT
     * {@code PropVariantClear}'d before returning.
     */
    private static void walkMetadata(Arena temp, MemorySegment reader, String prefix,
                                     Map<String, String> out, int depth) throws Throwable {
        if (depth > MAX_METADATA_DEPTH || out.size() >= MAX_METADATA_ENTRIES) return;

        MemorySegment ppEnum = temp.allocate(ValueLayout.ADDRESS);
        int hr = (int) MetadataReader_GetEnumerator.invokeExact(
                Ole32.vtable(reader, 6), reader, ppEnum);
        if (Ole32.failed(hr)) return;
        MemorySegment enumStr = ppEnum.get(ValueLayout.ADDRESS, 0);
        if (MemorySegment.NULL.equals(enumStr)) return;

        MemorySegment pElt = temp.allocate(ValueLayout.ADDRESS);
        MemorySegment pFetched = temp.allocate(JAVA_INT);
        try {
            while (out.size() < MAX_METADATA_ENTRIES) {
                pElt.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                pFetched.set(JAVA_INT, 0, 0);
                hr = (int) EnumString_Next.invokeExact(
                        Ole32.vtable(enumStr, 3), enumStr, 1, pElt, pFetched);
                // S_OK with 1 fetched continues; S_FALSE / 0 fetched / failure stops.
                if (Ole32.failed(hr) || pFetched.get(JAVA_INT, 0) < 1) break;

                MemorySegment eltPtr = pElt.get(ValueLayout.ADDRESS, 0);
                String name = readWString(eltPtr, MAX_STRING_CHARS);
                Ole32.coTaskMemFree(eltPtr);
                if (name == null || name.isEmpty()) continue;

                String full = prefix + name;
                MemorySegment pv = temp.allocate(PROPVARIANT_SIZE);
                pv.fill((byte) 0);
                MemorySegment ws = Ole32.wstr(temp, name);
                int rhr = (int) MetadataReader_GetMetadataByName.invokeExact(
                        Ole32.vtable(reader, 5), reader, ws, pv);
                if (Ole32.failed(rhr)) continue;
                try {
                    short vt = pv.get(ValueLayout.JAVA_SHORT, 0);
                    if (vt == VT_UNKNOWN) {
                        // A nested metadata block: the payload is a bare IUnknown,
                        // so QueryInterface it for IWICMetadataQueryReader (its
                        // vtable has GetEnumerator) before descending. The QI'd
                        // pointer is released here; PropVariantClear releases the
                        // original punkVal.
                        MemorySegment punk = pv.get(ValueLayout.ADDRESS, 8);
                        if (!MemorySegment.NULL.equals(punk)) {
                            MemorySegment ppChild = temp.allocate(ValueLayout.ADDRESS);
                            int qhr = Ole32.queryInterface(
                                    punk, IID_WIC_METADATA_QUERY_READER, ppChild);
                            if (!Ole32.failed(qhr)) {
                                MemorySegment child = ppChild.get(ValueLayout.ADDRESS, 0);
                                try {
                                    walkMetadata(temp, child, full, out, depth + 1);
                                } finally {
                                    Ole32.release(child);
                                }
                            }
                        }
                    } else {
                        String value = stringifyPropVariant(pv, vt);
                        if (value != null) out.put(full, value);
                    }
                } finally {
                    Ole32.propVariantClear(pv);
                }
            }
        } finally {
            Ole32.release(enumStr);
        }
    }

    /**
     * Stringifies a non-{@code VT_UNKNOWN} PROPVARIANT by VARTYPE. Numbers render
     * as decimal, strings ({@code VT_LPWSTR}/{@code VT_LPSTR}/{@code VT_BSTR}) as
     * (capped) text, counted vectors via {@link #stringifyVector}, and
     * {@code VT_BLOB} as a {@code "<binary, N bytes>"} placeholder without copying
     * the payload. Returns {@code null} for empty/unsupported types so the caller
     * skips them.
     */
    private static String stringifyPropVariant(MemorySegment pv, short vt) {
        if ((vt & VT_VECTOR) != 0) {
            return stringifyVector(pv, (short) (vt & 0x0FFF));
        }
        return switch (vt) {
            case VT_LPWSTR, VT_BSTR -> readWString(pv.get(ValueLayout.ADDRESS, 8), MAX_STRING_CHARS);
            case VT_LPSTR -> readAString(pv.get(ValueLayout.ADDRESS, 8), MAX_STRING_CHARS);
            case VT_UI1 -> Integer.toString(Byte.toUnsignedInt(pv.get(JAVA_BYTE, 8)));
            case VT_I1 -> Integer.toString(pv.get(JAVA_BYTE, 8));
            case VT_UI2 -> Integer.toString(Short.toUnsignedInt(pv.get(ValueLayout.JAVA_SHORT, 8)));
            case VT_I2 -> Short.toString(pv.get(ValueLayout.JAVA_SHORT, 8));
            case VT_UI4, VT_UINT -> Integer.toUnsignedString(pv.get(JAVA_INT, 8));
            case VT_I4, VT_INT -> Integer.toString(pv.get(JAVA_INT, 8));
            // WIC packs an EXIF RATIONAL / SRATIONAL (the only producers of 64-bit
            // ints in image metadata) into a VT_UI8 / VT_I8: numerator in the low
            // dword, denominator in the high dword -> render as "num/den".
            case VT_UI8 -> formatRationalU(pv.get(ValueLayout.JAVA_LONG, 8));
            case VT_I8 -> formatRationalS(pv.get(ValueLayout.JAVA_LONG, 8));
            case VT_R4 -> Float.toString(pv.get(ValueLayout.JAVA_FLOAT, 8));
            case VT_R8 -> Double.toString(pv.get(ValueLayout.JAVA_DOUBLE, 8));
            case VT_BOOL -> pv.get(ValueLayout.JAVA_SHORT, 8) != 0 ? "True" : "False";
            case VT_FILETIME -> Long.toString(pv.get(ValueLayout.JAVA_LONG, 8));
            case VT_BLOB -> binaryPlaceholder(Integer.toUnsignedLong(pv.get(JAVA_INT, 8)));
            case VT_CLSID -> readGuid(pv.get(ValueLayout.ADDRESS, 8));
            case VT_EMPTY, VT_NULL -> null;
            default -> null;
        };
    }

    /**
     * Stringifies a counted {@code VT_VECTOR} PROPVARIANT (union layout
     * {@code {ULONG cElems; T *pElems}}: {@code cElems} at offset 8, {@code pElems}
     * at offset 16 on x64). Byte vectors ({@code VT_UI1}) become a
     * {@code "<binary, N bytes>"} placeholder; other element types are joined (up
     * to {@link #MAX_VECTOR_ELEMS}) with a trailing total when truncated. Unknown
     * element types degrade to a size-only placeholder.
     */
    private static String stringifyVector(MemorySegment pv, short base) {
        int n = pv.get(JAVA_INT, 8);
        if (base == VT_UI1) return binaryPlaceholder(n < 0 ? 0 : n);
        if (n <= 0) return null;
        MemorySegment ptr = pv.get(ValueLayout.ADDRESS, 16);
        if (MemorySegment.NULL.equals(ptr)) return null;
        long elem = vectorElemSize(base);
        if (elem <= 0) return binaryPlaceholder(n); // unknown element type -> count only
        int k = Math.min(n, MAX_VECTOR_ELEMS);
        MemorySegment arr = ptr.reinterpret(elem * k);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < k; i++) {
            if (i > 0) sb.append(", ");
            sb.append(readVectorElem(arr, base, i * elem));
        }
        if (n > k) sb.append(", \u2026 (").append(n).append(" total)");
        return sb.toString();
    }

    /** Native size of one vector element for {@code base} VARTYPE, or 0 if unsupported. */
    private static long vectorElemSize(short base) {
        return switch (base) {
            case VT_I1, VT_UI1 -> 1;
            case VT_I2, VT_UI2, VT_BOOL -> 2;
            case VT_I4, VT_UI4, VT_INT, VT_UINT, VT_R4 -> 4;
            case VT_I8, VT_UI8, VT_R8, VT_FILETIME -> 8;
            case VT_LPWSTR, VT_LPSTR, VT_BSTR -> ValueLayout.ADDRESS.byteSize();
            default -> 0;
        };
    }

    /** Stringifies one vector element of {@code base} VARTYPE at byte {@code off}. */
    private static String readVectorElem(MemorySegment arr, short base, long off) {
        return switch (base) {
            case VT_UI1 -> Integer.toString(Byte.toUnsignedInt(arr.get(JAVA_BYTE, off)));
            case VT_I1 -> Integer.toString(arr.get(JAVA_BYTE, off));
            case VT_UI2 -> Integer.toString(Short.toUnsignedInt(arr.get(ValueLayout.JAVA_SHORT, off)));
            case VT_I2, VT_BOOL -> Short.toString(arr.get(ValueLayout.JAVA_SHORT, off));
            case VT_UI4, VT_UINT -> Integer.toUnsignedString(arr.get(JAVA_INT, off));
            case VT_I4, VT_INT -> Integer.toString(arr.get(JAVA_INT, off));
            case VT_UI8 -> formatRationalU(arr.get(ValueLayout.JAVA_LONG, off));
            case VT_I8 -> formatRationalS(arr.get(ValueLayout.JAVA_LONG, off));
            case VT_FILETIME -> Long.toString(arr.get(ValueLayout.JAVA_LONG, off));
            case VT_R4 -> Float.toString(arr.get(ValueLayout.JAVA_FLOAT, off));
            case VT_R8 -> Double.toString(arr.get(ValueLayout.JAVA_DOUBLE, off));
            case VT_LPWSTR, VT_BSTR -> readWString(arr.get(ValueLayout.ADDRESS, off), MAX_STRING_CHARS);
            case VT_LPSTR -> readAString(arr.get(ValueLayout.ADDRESS, off), MAX_STRING_CHARS);
            default -> "?";
        };
    }

    /** The standard {@code "<binary, N bytes>"} placeholder (size only; no payload copy). */
    private static String binaryPlaceholder(long bytes) {
        return "<binary, " + bytes + " bytes>";
    }

    /** Renders a packed EXIF RATIONAL (num=low dword, den=high dword) as {@code "num/den"}. */
    private static String formatRationalU(long packed) {
        return (packed & 0xFFFFFFFFL) + "/" + (packed >>> 32);
    }

    /** Renders a packed EXIF SRATIONAL (signed num=low dword, den=high dword) as {@code "num/den"}. */
    private static String formatRationalS(long packed) {
        return (int) (packed & 0xFFFFFFFFL) + "/" + (int) (packed >>> 32);
    }

    /** Reads a NUL-terminated UTF-16LE string from an unmanaged pointer, capped at {@code maxChars}. */
    private static String readWString(MemorySegment ptr, int maxChars) {
        if (ptr == null || MemorySegment.NULL.equals(ptr)) return null;
        MemorySegment s = ptr.reinterpret(((long) maxChars + 1) * 2);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxChars; i++) {
            short c = s.get(ValueLayout.JAVA_SHORT, (long) i * 2);
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    /** Reads a NUL-terminated 8-bit (ASCII/Latin-1) string from an unmanaged pointer, capped. */
    private static String readAString(MemorySegment ptr, int maxChars) {
        if (ptr == null || MemorySegment.NULL.equals(ptr)) return null;
        MemorySegment s = ptr.reinterpret((long) maxChars + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxChars; i++) {
            byte b = s.get(JAVA_BYTE, i);
            if (b == 0) break;
            sb.append((char) (b & 0xFF));
        }
        return sb.toString();
    }

    /** Formats a 16-byte GUID at an unmanaged pointer as {@code {XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}}. */
    private static String readGuid(MemorySegment ptr) {
        if (ptr == null || MemorySegment.NULL.equals(ptr)) return null;
        MemorySegment g = ptr.reinterpret(16);
        int d1 = g.get(JAVA_INT, 0);
        int d2 = Short.toUnsignedInt(g.get(ValueLayout.JAVA_SHORT, 4));
        int d3 = Short.toUnsignedInt(g.get(ValueLayout.JAVA_SHORT, 6));
        StringBuilder sb = new StringBuilder(38);
        sb.append('{').append(String.format("%08X-%04X-%04X-", d1, d2, d3));
        sb.append(String.format("%02X%02X-",
                g.get(JAVA_BYTE, 8) & 0xFF, g.get(JAVA_BYTE, 9) & 0xFF));
        for (int i = 10; i < 16; i++) sb.append(String.format("%02X", g.get(JAVA_BYTE, i) & 0xFF));
        return sb.append('}').toString();
    }

    // -- Animated GIF decoding -----------------------------------------------

    /**
     * A single frame from an animated GIF, including pixel data and metadata.
     *
     * @param pixels BGRA pixel data (width * height * 4 bytes)
     * @param width  frame width in pixels
     * @param height frame height in pixels
     * @param delay  frame delay in milliseconds
     * @param disposal disposal method (0=undefined, 1=none, 2=background, 3=previous)
     * @param left   X offset of this frame within the canvas
     * @param top    Y offset of this frame within the canvas
     */
    public record GifFrame(byte[] pixels, int width, int height,
                           int delay, int disposal, int left, int top) {}

    /**
     * Metadata for an animated GIF image.
     *
     * @param canvasWidth  logical screen width
     * @param canvasHeight logical screen height
     * @param loopCount    0 = infinite, otherwise number of loops
     * @param frames       decoded frames with per-frame metadata
     */
    public record AnimatedGif(int canvasWidth, int canvasHeight, int loopCount,
                              java.util.List<GifFrame> frames) {}

    /**
     * Decodes all frames from an animated GIF file using WIC.
     * <p>
     * Each frame is format-converted to 32bppPBGRA and returned with its
     * delay, disposal method, and position metadata.
     *
     * @param path absolute path to the GIF file
     * @return the decoded animated GIF with all frames and metadata
     */
    public static AnimatedGif decodeAnimatedGif(String path) {
        ensureAvailable();

        MemorySegment decoder = MemorySegment.NULL;
        try (Arena temp = Arena.ofConfined()) {
            Ole32.coInitializeEx();

            MemorySegment factory = cachedFactory();
            if (MemorySegment.NULL.equals(factory))
                throw new IllegalStateException("Failed to create WIC factory");

            // Open decoder from file
            MemorySegment wpath = Ole32.wstr(temp, path);
            MemorySegment ppDecoder = temp.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateDecoderFromFilename.invokeExact(
                    Ole32.vtable(factory, 3), factory, wpath,
                    MemorySegment.NULL, GENERIC_READ,
                    WICDecodeMetadataCacheOnDemand, ppDecoder),
                    "CreateDecoderFromFilename failed");
            decoder = ppDecoder.get(ValueLayout.ADDRESS, 0);

            // Get frame count
            MemorySegment pCount = temp.allocate(JAVA_INT);
            Ole32.check((int) Decoder_GetFrameCount.invokeExact(
                    Ole32.vtable(decoder, 12), decoder, pCount),
                    "GetFrameCount failed");
            int frameCount = pCount.get(JAVA_INT, 0);

            // Get global metadata (canvas size, loop count)
            int canvasWidth = 0, canvasHeight = 0, loopCount = 0;
            MemorySegment ppGlobalMeta = temp.allocate(ValueLayout.ADDRESS);
            int hr = (int) Decoder_GetMetadataQueryReader.invokeExact(
                    Ole32.vtable(decoder, 8), decoder, ppGlobalMeta);
            if (!Ole32.failed(hr)) {
                MemorySegment globalReader = ppGlobalMeta.get(ValueLayout.ADDRESS, 0);
                try {
                    canvasWidth = readMetadataUI2(temp, globalReader, "/logscrdesc/Width");
                    canvasHeight = readMetadataUI2(temp, globalReader, "/logscrdesc/Height");
                    loopCount = readGifLoopCount(temp, globalReader);
                } finally {
                    Ole32.release(globalReader);
                }
            }

            // Decode each frame
            var frames = new java.util.ArrayList<GifFrame>(frameCount);
            for (int i = 0; i < frameCount; i++) {
                frames.add(decodeGifFrame(temp, factory, decoder, i));
            }

            return new AnimatedGif(canvasWidth, canvasHeight, loopCount, frames);
        } catch (Throwable t) {
            throw new DecodeException("WIC decodeAnimatedGif failed: " + path, t);
        } finally {
            Ole32.release(decoder);
        }
    }

    /**
     * Decodes a single GIF frame with format conversion and metadata.
     */
    private static GifFrame decodeGifFrame(Arena temp, MemorySegment factory,
                                           MemorySegment decoder, int index) throws Throwable {
        MemorySegment frame = MemorySegment.NULL;
        MemorySegment converter = MemorySegment.NULL;
        try {
            // Get frame
            MemorySegment ppFrame = temp.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Decoder_GetFrame.invokeExact(
                    Ole32.vtable(decoder, 13), decoder, index, ppFrame),
                    "GetFrame(" + index + ") failed");
            frame = ppFrame.get(ValueLayout.ADDRESS, 0);

            // Read per-frame metadata
            int delay = 100, disposal = 0, left = 0, top = 0;
            MemorySegment ppMeta = temp.allocate(ValueLayout.ADDRESS);
            int hr = (int) Frame_GetMetadataQueryReader.invokeExact(
                    Ole32.vtable(frame, 8), frame, ppMeta);
            if (!Ole32.failed(hr)) {
                MemorySegment metaReader = ppMeta.get(ValueLayout.ADDRESS, 0);
                try {
                    int d = readMetadataUI2(temp, metaReader, "/grctlext/Delay");
                    delay = (d > 0) ? d * 10 : 100; // delay is in 10ms units, min 100ms
                    disposal = readMetadataUI1(temp, metaReader, "/grctlext/Disposal");
                    left = readMetadataUI2(temp, metaReader, "/imgdesc/Left");
                    top = readMetadataUI2(temp, metaReader, "/imgdesc/Top");
                } finally {
                    Ole32.release(metaReader);
                }
            }

            // Format convert to 32bppPBGRA
            MemorySegment ppConverter = temp.allocate(ValueLayout.ADDRESS);
            Ole32.check((int) Factory_CreateFormatConverter.invokeExact(
                    Ole32.vtable(factory, 10), factory, ppConverter),
                    "CreateFormatConverter failed");
            converter = ppConverter.get(ValueLayout.ADDRESS, 0);
            Ole32.check((int) Converter_Initialize.invokeExact(
                    Ole32.vtable(converter, 8), converter, frame,
                    GUID_PIXEL_FORMAT_PBGRA,
                    WICBitmapDitherTypeNone,
                    MemorySegment.NULL, 0.0, WICBitmapPaletteTypeCustom),
                    "FormatConverter::Initialize failed");

            // Get dimensions and copy pixels
            MemorySegment pW = temp.allocate(JAVA_INT);
            MemorySegment pH = temp.allocate(JAVA_INT);
            Ole32.check((int) Source_GetSize.invokeExact(
                    Ole32.vtable(converter, 3), converter, pW, pH),
                    "GetSize failed");
            int w = pW.get(JAVA_INT, 0);
            int h = pH.get(JAVA_INT, 0);

            int stride = w * RGBA_BPP;
            int size = stride * h;
            MemorySegment pixelBuf = temp.allocate(JAVA_BYTE, size);
            Ole32.check((int) Source_CopyPixels.invokeExact(
                    Ole32.vtable(converter, 7), converter,
                    MemorySegment.NULL, stride, size, pixelBuf),
                    "CopyPixels failed");

            byte[] pixels = pixelBuf.toArray(JAVA_BYTE);
            return new GifFrame(pixels, w, h, delay, disposal, left, top);
        } finally {
            Ole32.release(converter);
            Ole32.release(frame);
        }
    }

    private static int readMetadataUI2(Arena arena, MemorySegment reader, String path) {
        try {
            MemorySegment pv = arena.allocate(PROPVARIANT_SIZE);
            pv.fill((byte) 0);
            MemorySegment ws = Ole32.wstr(arena, path);
            int hr = (int) MetadataReader_GetMetadataByName.invokeExact(
                    Ole32.vtable(reader, 5), reader, ws, pv);
            if (Ole32.failed(hr)) return 0;
            short vt = pv.get(ValueLayout.JAVA_SHORT, 0);
            if (vt == VT_UI2) return Short.toUnsignedInt(pv.get(ValueLayout.JAVA_SHORT, 8));
            return 0;
        } catch (Throwable t) { return 0; }
    }

    private static int readMetadataUI1(Arena arena, MemorySegment reader, String path) {
        try {
            MemorySegment pv = arena.allocate(PROPVARIANT_SIZE);
            pv.fill((byte) 0);
            MemorySegment ws = Ole32.wstr(arena, path);
            int hr = (int) MetadataReader_GetMetadataByName.invokeExact(
                    Ole32.vtable(reader, 5), reader, ws, pv);
            if (Ole32.failed(hr)) return 0;
            short vt = pv.get(ValueLayout.JAVA_SHORT, 0);
            if (vt == VT_UI1) return Byte.toUnsignedInt(pv.get(JAVA_BYTE, 8));
            return 0;
        } catch (Throwable t) { return 0; }
    }

    /**
     * Reads GIF loop count from NETSCAPE2.0 / ANIMEXTS1.0 application extension.
     * Returns 0 for infinite loop.
     */
    private static int readGifLoopCount(Arena arena, MemorySegment reader) {
        try {
            // Check /appext/Application for NETSCAPE2.0 or ANIMEXTS1.0
            MemorySegment pv = arena.allocate(PROPVARIANT_SIZE);
            pv.fill((byte) 0);
            MemorySegment ws = Ole32.wstr(arena, "/appext/Application");
            int hr = (int) MetadataReader_GetMetadataByName.invokeExact(
                    Ole32.vtable(reader, 5), reader, ws, pv);
            if (Ole32.failed(hr)) return 0;

            short vt = pv.get(ValueLayout.JAVA_SHORT, 0);
            if (vt != VT_VECTOR_UI1) return 0;

            // Read /appext/Data for loop count
            pv.fill((byte) 0);
            ws = Ole32.wstr(arena, "/appext/Data");
            hr = (int) MetadataReader_GetMetadataByName.invokeExact(
                    Ole32.vtable(reader, 5), reader, ws, pv);
            if (Ole32.failed(hr)) return 0;

            vt = pv.get(ValueLayout.JAVA_SHORT, 0);
            if (vt != VT_VECTOR_UI1) return 0;

            // CAUB struct: count at offset 8, pointer at offset 16 (x64)
            int count = pv.get(JAVA_INT, 8);
            if (count < 4) return 0;
            MemorySegment dataPtr = pv.get(ValueLayout.ADDRESS, 16)
                    .reinterpret(count);
            // byte 0: extsize, byte 1: loopType (1=animated), byte 2-3: loop count (LE)
            if (dataPtr.get(JAVA_BYTE, 1) != 1) return 0;
            return Short.toUnsignedInt(dataPtr.get(ValueLayout.JAVA_SHORT_UNALIGNED, 2));
        } catch (Throwable t) { return 0; }
    }

    // -- Error handling ------------------------------------------------------

    private static void ensureAvailable() {
        if (!AVAILABLE)
            throw new IllegalStateException(
                    "WIC is not available" + (LOAD_ERROR != null ? ": " + LOAD_ERROR : ""));
    }
}

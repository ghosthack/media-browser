package io.github.ghosthack.panama.media.mediafoundation;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import io.github.ghosthack.panama.media.core.BgraRotation;
import io.github.ghosthack.panama.media.core.DecodeException;
import io.github.ghosthack.panama.media.core.DecodedImage;
import io.github.ghosthack.panama.media.core.Platform;
import io.github.ghosthack.panama.media.core.PixelFormat;
import io.github.ghosthack.panama.media.comruntime.Ole32;

/**
 * Panama FFM bindings to Windows Media Foundation (mfplat, mfreadwrite) and
 * D3D11 for video decoding.
 * <p>
 * This class provides pipeline-oriented video decode operations that return
 * raw pixel data in Arena-managed memory.
 * <p>
 * Decoded frames are always in BGRA format with 4 bytes per pixel.
 * <p>
 * Thread-safe when each thread uses its own {@link Arena}.
 * <p>
 * Only functional on Windows; {@link #isAvailable()} returns {@code false}
 * on other platforms.
 */
public final class MediaFoundation {

    private MediaFoundation() {}

    // -- OS guard / debug -----------------------------------------------------

    private static final boolean IS_WINDOWS = Platform.IS_WINDOWS;

    /** Set to {@code true} to enable debug logging. JIT eliminates guarded blocks when {@code false}. */
    private static final boolean DEBUG = Boolean.getBoolean("panama.media.debug");

    // -- Constants -----------------------------------------------------------

    /** Bytes per pixel for 32-bit output. */
    private static final int RGBA_BPP = 4;

    public static final int MF_VERSION = 0x00020070;

    public static final int MF_SOURCE_READER_FIRST_VIDEO_STREAM = 0xFFFFFFFC;
    public static final int MF_SOURCE_READER_FIRST_AUDIO_STREAM = 0xFFFFFFFD;
    public static final int MF_SOURCE_READER_MEDIASOURCE         = 0xFFFFFFFF;

    public static final int MF_E_TRANSFORM_NEED_MORE_INPUT  = 0xC00D6D72;
    public static final int MF_E_TRANSFORM_TYPE_NOT_SET     = 0xC00D6D61;
    public static final int MF_E_TRANSFORM_STREAM_CHANGE    = 0xC00D6D62;
    private static final int E_INVALIDARG                    = 0x80070057;

    public static final int MFT_MESSAGE_SET_D3D_MANAGER       = 0x00000002;
    public static final int MFT_MESSAGE_COMMAND_DRAIN          = 0x00000001;
    public static final int MFT_MESSAGE_NOTIFY_BEGIN_STREAMING = 0x10000000;
    public static final int MFT_MESSAGE_NOTIFY_START_OF_STREAM = 0x10000003;

    public static final int MFT_OUTPUT_STREAM_PROVIDES_SAMPLES = 0x100;

    /** PROPVARIANT structure size (vt + padding + value) on 64-bit Windows. */
    private static final int PROPVARIANT_SIZE = 24;

    /** PROPVARIANT type tag for a signed 64-bit integer. */
    private static final short VT_I8 = 20;

    /** MF_SOURCE_READERF_ERROR */
    private static final int MF_SOURCE_READERF_ERROR = 0x1;

    /** MF_SOURCE_READERF_ENDOFSTREAM */
    private static final int MF_SOURCE_READERF_ENDOFSTREAM = 0x2;

    // -- Linker --------------------------------------------------------------

    private static final Linker LINKER = Linker.nativeLinker();

    // -- Flat function handles -----------------------------------------------

    public static final MethodHandle H_MF_STARTUP;
    public static final MethodHandle H_MF_SHUTDOWN;
    public static final MethodHandle H_MF_CREATE_MEDIA_TYPE;
    public static final MethodHandle H_MF_CREATE_ATTRIBUTES;
    public static final MethodHandle H_MF_CREATE_SOURCE_READER_FROM_URL;
    public static final MethodHandle H_D3D11_CREATE_DEVICE;
    public static final MethodHandle H_MF_CREATE_DXGI_DEVICE_MANAGER;
    public static final MethodHandle H_MF_CREATE_SAMPLE;
    public static final MethodHandle H_MF_CREATE_MEMORY_BUFFER;
    public static final MethodHandle H_MFT_ENUM_EX;
    public static final MethodHandle H_MFT_REGISTER_LOCAL_BY_CLSID;

    // -- COM vtable dispatch handles (no address) ----------------------------

    /** IUnknown::Release -- vtable[2] */
    public static final MethodHandle IUnknown_Release = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** IUnknown::QueryInterface -- vtable[0] */
    public static final MethodHandle IUnknown_QueryInterface = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** IMFSourceReader::GetNativeMediaType -- vtable[5] */
    public static final MethodHandle IMFSourceReader_GetNativeMediaType = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** IMFSourceReader::SetCurrentMediaType -- vtable[7] */
    public static final MethodHandle IMFSourceReader_SetCurrentMediaType = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** IMFSourceReader::SetCurrentPosition -- vtable[8] */
    public static final MethodHandle IMFSourceReader_SetCurrentPosition = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** IMFSourceReader::ReadSample -- vtable[9] */
    public static final MethodHandle IMFSourceReader_ReadSample = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** IMFSourceReader::GetPresentationAttribute -- vtable[12] */
    public static final MethodHandle IMFSourceReader_GetPresentationAttribute = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** IMFSourceReader::GetCurrentMediaType -- vtable[6] */
    public static final MethodHandle IMFSourceReader_GetCurrentMediaType = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** IMFAttributes::SetGUID -- vtable[24] */
    public static final MethodHandle IMFAttributes_SetGUID = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** IMFAttributes::SetUINT32 -- vtable[21] */
    public static final MethodHandle IMFAttributes_SetUINT32 = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** IMFAttributes::SetUINT64 -- vtable[22] (SetUINT32=21, SetUINT64=22, SetDouble=23, SetGUID=24). */
    public static final MethodHandle IMFAttributes_SetUINT64 = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    /** IMFAttributes::GetUINT32 -- vtable[7] */
    public static final MethodHandle IMFAttributes_GetUINT32 = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** IMFAttributes::GetUINT64 -- vtable[8] */
    public static final MethodHandle IMFAttributes_GetUINT64 = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** IMFAttributes::GetGUID -- vtable[10] */
    public static final MethodHandle IMFAttributes_GetGUID = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** IMFMediaBuffer::Lock -- vtable[3] */
    public static final MethodHandle IMFMediaBuffer_Lock = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** IMFMediaBuffer::Unlock -- vtable[4] */
    public static final MethodHandle IMFMediaBuffer_Unlock = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** IMFSample::ConvertToContiguousBuffer -- vtable[41] */
    public static final MethodHandle IMFSample_ConvertToContiguousBuffer = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /**
     * IMFSample::GetSampleTime -- vtable[35].
     * <p>
     * {@code HRESULT GetSampleTime(LONGLONG* phnsSampleTime)} — the
     * presentation time of the decoded sample, in 100-nanosecond units.
     * Used for PTS-based playback pacing; for a decoder MFT this reflects
     * the display-order timestamp (post-B-frame-reorder), which is what
     * the renderer needs for wall-clock-accurate present cadence.
     */
    public static final MethodHandle IMFSample_GetSampleTime = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** IMFTransform::GetOutputStreamInfo -- vtable[7] */
    public static final MethodHandle IMFTransform_GetOutputStreamInfo = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** IMFTransform::GetOutputAvailableType -- vtable[14] */
    public static final MethodHandle IMFTransform_GetOutputAvailableType = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    /** IMFTransform::SetInputType -- vtable[15] */
    public static final MethodHandle IMFTransform_SetInputType = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** IMFTransform::SetOutputType -- vtable[16] */
    public static final MethodHandle IMFTransform_SetOutputType = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** IMFTransform::ProcessMessage -- vtable[23] */
    public static final MethodHandle IMFTransform_ProcessMessage = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

    /** IMFTransform::ProcessInput -- vtable[24] */
    public static final MethodHandle IMFTransform_ProcessInput = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** IMFTransform::ProcessOutput -- vtable[25] */
    public static final MethodHandle IMFTransform_ProcessOutput = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** IMFSample::AddBuffer -- vtable[42] */
    public static final MethodHandle IMFSample_AddBuffer = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    /** IMFDXGIDeviceManager::ResetDevice -- vtable[7] */
    public static final MethodHandle IMFDXGIDeviceManager_ResetDevice = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** ID3D10Multithread::SetMultithreadProtected -- vtable[5]
     *  (IUnknown 0-2, Enter=3, Leave=4, SetMultithreadProtected=5, GetMultithreadProtected=6). */
    public static final MethodHandle ID3D10Multithread_SetMultithreadProtected = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** IMFAttributes::SetUnknown -- vtable[27] */
    public static final MethodHandle IMFAttributes_SetUnknown = LINKER.downcallHandle(
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    // -- GUIDs (allocated in Arena.global()) ----------------------------------

    public static final MemorySegment MF_MT_MAJOR_TYPE;
    public static final MemorySegment MF_MT_SUBTYPE;
    public static final MemorySegment MFMediaType_Video;
    public static final MemorySegment MFVideoFormat_RGB32;
    public static final MemorySegment MFVideoFormat_NV12;
    public static final MemorySegment MF_MT_FRAME_SIZE;
    /** MF_MT_VIDEO_ROTATION {C380465D-2271-428C-9B83-ECEA3B4A85C1} (UINT32, MFVideoRotationFormat). */
    public static final MemorySegment MF_MT_VIDEO_ROTATION;
    public static final MemorySegment MF_MT_FRAME_RATE;
    /** MF_MT_AUDIO_NUM_CHANNELS {37E48BF5-645E-4C5B-89DE-ADA9E29B696A} (UINT32). */
    public static final MemorySegment MF_MT_AUDIO_NUM_CHANNELS;
    /** MF_MT_AUDIO_SAMPLES_PER_SECOND {5FAEEAE7-0290-4C31-9E8A-C534F68D9DBA} (UINT32). */
    public static final MemorySegment MF_MT_AUDIO_SAMPLES_PER_SECOND;
    public static final MemorySegment MF_PD_DURATION;
    public static final MemorySegment MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING;
    /** MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING {0F81DA2C-B537-4672-A8B2-A681B17307A3}
     *  Enables hardware-based video processing MFTs (required for Store-distributed codecs like VP9). */
    public static final MemorySegment MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING;
    /** MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS {A634A91C-822B-41B9-A494-4DE4643612B0} */
    public static final MemorySegment MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS;
    /** MFT_CATEGORY_VIDEO_DECODER {D6C02D4B-6833-45B4-971A-05A4B04BAB91} */
    public static final MemorySegment MFT_CATEGORY_VIDEO_DECODER;
    /** IID_IMFTransform for ActivateObject */
    // (already declared as IID_IMFTransform below)
    /** MF_SOURCE_READER_D3D_MANAGER {EC822DA2-E1E9-4B29-A0D8-563C719F5269} */
    public static final MemorySegment MF_SOURCE_READER_D3D_MANAGER;
    public static final MemorySegment CLSID_CMSH264DecoderMFT;
    public static final MemorySegment IID_IMFTransform;
    public static final MemorySegment IID_ID3D10Multithread;
    public static final MemorySegment GUID_NULL;

    // -- Availability --------------------------------------------------------

    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;

    // -- Static initializer --------------------------------------------------

    static {
        MethodHandle mfStartup = null;
        MethodHandle mfShutdown = null;
        MethodHandle mfCreateMediaType = null;
        MethodHandle mfCreateAttributes = null;
        MethodHandle mfCreateSourceReaderFromURL = null;
        MethodHandle d3d11CreateDevice = null;
        MethodHandle mfCreateDXGIDeviceManager = null;
        MethodHandle mfCreateSample = null;
        MethodHandle mfCreateMemoryBuffer = null;
        MethodHandle mftEnumEx = null;
        MethodHandle mftRegisterLocalByCLSID = null;
        boolean available = false;
        String loadError = null;

        MemorySegment gMfMtMajorType = null;
        MemorySegment gMfMtSubtype = null;
        MemorySegment gMfMediaTypeVideo = null;
        MemorySegment gMfVideoFormatRgb32 = null;
        MemorySegment gMfVideoFormatNv12 = null;
        MemorySegment gMfMtFrameSize = null;
        MemorySegment gMfMtVideoRotation = null;
        MemorySegment gMfMtFrameRate = null;
        MemorySegment gMfMtAudioNumChannels = null;
        MemorySegment gMfMtAudioSamplesPerSecond = null;
        MemorySegment gMfPdDuration = null;
        MemorySegment gMfSrEnableVideoProcessing = null;
        MemorySegment gMfSrEnableAdvancedVideoProcessing = null;
        MemorySegment gMfRwEnableHardwareTransforms = null;
        MemorySegment gMftCategoryVideoDecoder = null;
        MemorySegment gMfSrD3dManager = null;
        MemorySegment gClsidCmsh264 = null;
        MemorySegment gIidImfTransform = null;
        MemorySegment gIidId3d10Mt = null;
        MemorySegment gGuidNull = null;

        if (IS_WINDOWS) {
            try {
                System.loadLibrary("mfplat");
                System.loadLibrary("mfreadwrite");
                System.loadLibrary("d3d11");

                SymbolLookup lookup = SymbolLookup.loaderLookup();

                // HRESULT MFStartup(ULONG Version, DWORD dwFlags)
                mfStartup = LINKER.downcallHandle(
                        lookup.findOrThrow("MFStartup"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

                // HRESULT MFShutdown(void)
                mfShutdown = LINKER.downcallHandle(
                        lookup.findOrThrow("MFShutdown"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT));

                // HRESULT MFCreateMediaType(IMFMediaType **ppMFType)
                mfCreateMediaType = LINKER.downcallHandle(
                        lookup.findOrThrow("MFCreateMediaType"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS));

                // HRESULT MFCreateAttributes(IMFAttributes **ppMFAttributes, UINT32 cInitialSize)
                mfCreateAttributes = LINKER.downcallHandle(
                        lookup.findOrThrow("MFCreateAttributes"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

                // HRESULT MFCreateSourceReaderFromURL(LPCWSTR, IMFAttributes*, IMFSourceReader**)
                mfCreateSourceReaderFromURL = LINKER.downcallHandle(
                        lookup.findOrThrow("MFCreateSourceReaderFromURL"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // HRESULT D3D11CreateDevice(...)
                d3d11CreateDevice = LINKER.downcallHandle(
                        lookup.findOrThrow("D3D11CreateDevice"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // HRESULT MFCreateDXGIDeviceManager(UINT *resetToken, IMFDXGIDeviceManager **)
                mfCreateDXGIDeviceManager = LINKER.downcallHandle(
                        lookup.findOrThrow("MFCreateDXGIDeviceManager"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // HRESULT MFCreateSample(IMFSample **ppIMFSample)
                mfCreateSample = LINKER.downcallHandle(
                        lookup.findOrThrow("MFCreateSample"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                // HRESULT MFCreateMemoryBuffer(DWORD cbMaxLength, IMFMediaBuffer **ppBuffer)
                mfCreateMemoryBuffer = LINKER.downcallHandle(
                        lookup.findOrThrow("MFCreateMemoryBuffer"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                // HRESULT MFTEnumEx(GUID guidCategory, UINT32 Flags,
                //   const MFT_REGISTER_TYPE_INFO *pInputType,
                //   const MFT_REGISTER_TYPE_INFO *pOutputType,
                //   IMFActivate ***pppMFTActivate, UINT32 *pnumMFTActivate)
                mftEnumEx = LINKER.downcallHandle(
                        lookup.findOrThrow("MFTEnumEx"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                // HRESULT MFTRegisterLocalByCLSID(REFCLSID clisdMFT, REFGUID guidCategory,
                //   LPCWSTR pszName, UINT32 Flags,
                //   UINT32 cInputTypes, const MFT_REGISTER_TYPE_INFO *pInputTypes,
                //   UINT32 cOutputTypes, const MFT_REGISTER_TYPE_INFO *pOutputTypes)
                mftRegisterLocalByCLSID = LINKER.downcallHandle(
                        lookup.findOrThrow("MFTRegisterLocalByCLSID"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                // GUIDs
                Arena g = Arena.global();
                byte[] mf = {(byte) 0x80, 0x00, 0x00, (byte) 0xAA, 0x00, 0x38, (byte) 0x9B, 0x71};

                gMfMtMajorType = Ole32.guid(g, 0x48eba18e, (short) 0xf8c9, (short) 0x4687,
                        new byte[]{(byte) 0xbf, 0x11, 0x0a, 0x74, (byte) 0xc9, (byte) 0xf9, 0x6a, 0x08});
                gMfMtSubtype = Ole32.guid(g, 0xf7e34c9a, (short) 0x42e8, (short) 0x4714,
                        new byte[]{(byte) 0xb7, 0x4b, (byte) 0xcb, 0x29, (byte) 0xd7, 0x2c, 0x35, (byte) 0xe5});
                gMfMediaTypeVideo = Ole32.guid(g, 0x73646976, (short) 0x0000, (short) 0x0010, mf);
                gMfVideoFormatRgb32 = Ole32.guid(g, 0x00000016, (short) 0x0000, (short) 0x0010, mf);
                gMfVideoFormatNv12 = Ole32.guid(g, 0x3231564E, (short) 0x0000, (short) 0x0010, mf);
                gMfMtFrameSize = Ole32.guid(g, 0x1652c33d, (short) 0xd6b2, (short) 0x4012,
                        new byte[]{(byte) 0xb8, 0x34, 0x72, 0x03, 0x08, 0x49, (byte) 0xa3, 0x7d});
                gMfMtVideoRotation = Ole32.guid(g, 0xC380465D, (short) 0x2271, (short) 0x428C,
                        new byte[]{(byte) 0x9B, (byte) 0x83, (byte) 0xEC, (byte) 0xEA, 0x3B, 0x4A, (byte) 0x85, (byte) 0xC1});
                gMfMtFrameRate = Ole32.guid(g, 0xc459a2e8, (short) 0x3d2c, (short) 0x4e44,
                        new byte[]{(byte) 0xb1, 0x32, (byte) 0xfe, (byte) 0xe5, 0x15, 0x6c, 0x7b, (byte) 0xb0});
                gMfMtAudioNumChannels = Ole32.guid(g, 0x37e48bf5, (short) 0x645e, (short) 0x4c5b,
                        new byte[]{(byte) 0x89, (byte) 0xde, (byte) 0xad, (byte) 0xa9, (byte) 0xe2, (byte) 0x9b, 0x69, 0x6a});
                gMfMtAudioSamplesPerSecond = Ole32.guid(g, 0x5faeeae7, (short) 0x0290, (short) 0x4c31,
                        new byte[]{(byte) 0x9e, (byte) 0x8a, (byte) 0xc5, 0x34, (byte) 0xf6, (byte) 0x8d, (byte) 0x9d, (byte) 0xba});
                gMfPdDuration = Ole32.guid(g, 0x6c990d33, (short) 0xbb8e, (short) 0x477a,
                        new byte[]{(byte) 0x85, (byte) 0x98, 0x0d, 0x5d, (byte) 0x96, (byte) 0xfc, (byte) 0xd8, (byte) 0x8a});
                gMfSrEnableVideoProcessing = Ole32.guid(g,
                        0xFB394F10, (short) 0xB8B0, (short) 0x11DF,
                        new byte[]{(byte) 0x84, (byte) 0x80, 0x00, 0x24, (byte) 0xBE, (byte) 0xD1, (byte) 0xF3, (byte) 0xA0});
                gMfSrEnableAdvancedVideoProcessing = Ole32.guid(g,
                        0x0F81DA2C, (short) 0xB537, (short) 0x4672,
                        new byte[]{(byte) 0xA8, (byte) 0xB2, (byte) 0xA6, (byte) 0x81, (byte) 0xB1, 0x73, 0x07, (byte) 0xA3});
                gMfRwEnableHardwareTransforms = Ole32.guid(g,
                        0xA634A91C, (short) 0x822B, (short) 0x41B9,
                        new byte[]{(byte) 0xA4, (byte) 0x94, 0x4D, (byte) 0xE4, 0x64, 0x36, 0x12, (byte) 0xB0});
                gMftCategoryVideoDecoder = Ole32.guid(g,
                        0xD6C02D4B, (short) 0x6833, (short) 0x45B4,
                        new byte[]{(byte) 0x97, 0x1A, 0x05, (byte) 0xA4, (byte) 0xB0, 0x4B, (byte) 0xAB, (byte) 0x91});
                gMfSrD3dManager = Ole32.guid(g,
                        0xEC822DA2, (short) 0xE1E9, (short) 0x4B29,
                        new byte[]{(byte) 0xA0, (byte) 0xD8, 0x56, 0x3C, 0x71, (byte) 0x9F, 0x52, 0x69});
                gClsidCmsh264 = Ole32.guid(g, 0x62CE7E72, (short) 0x4C71, (short) 0x4d20,
                        new byte[]{(byte) 0xB1, 0x5D, 0x45, 0x28, 0x31, (byte) 0xA8, 0x7D, (byte) 0x9D});
                gIidImfTransform = Ole32.guid(g, 0xbf94c121, (short) 0x5b05, (short) 0x4e6f,
                        new byte[]{(byte) 0x80, 0x00, (byte) 0xba, 0x59, (byte) 0x89, 0x61, 0x41, 0x4d});
                gIidId3d10Mt = Ole32.guid(g, 0x9B7E4E00, (short) 0x342C, (short) 0x4106,
                        new byte[]{(byte) 0xA1, (byte) 0x9F, 0x4F, 0x27, 0x04, (byte) 0xF6, (byte) 0x89, (byte) 0xF0});
                gGuidNull = g.allocate(16); // zero-initialized

                available = true;
            } catch (Throwable t) {
                loadError = t.getMessage();
            }
        } else {
            loadError = "Not running on Windows";
        }

        H_MF_STARTUP = mfStartup;
        H_MF_SHUTDOWN = mfShutdown;
        H_MF_CREATE_MEDIA_TYPE = mfCreateMediaType;
        H_MF_CREATE_ATTRIBUTES = mfCreateAttributes;
        H_MF_CREATE_SOURCE_READER_FROM_URL = mfCreateSourceReaderFromURL;
        H_D3D11_CREATE_DEVICE = d3d11CreateDevice;
        H_MF_CREATE_DXGI_DEVICE_MANAGER = mfCreateDXGIDeviceManager;
        H_MF_CREATE_SAMPLE = mfCreateSample;
        H_MF_CREATE_MEMORY_BUFFER = mfCreateMemoryBuffer;
        H_MFT_ENUM_EX = mftEnumEx;
        H_MFT_REGISTER_LOCAL_BY_CLSID = mftRegisterLocalByCLSID;

        MF_MT_MAJOR_TYPE = gMfMtMajorType;
        MF_MT_SUBTYPE = gMfMtSubtype;
        MFMediaType_Video = gMfMediaTypeVideo;
        MFVideoFormat_RGB32 = gMfVideoFormatRgb32;
        MFVideoFormat_NV12 = gMfVideoFormatNv12;
        MF_MT_FRAME_SIZE = gMfMtFrameSize;
        MF_MT_VIDEO_ROTATION = gMfMtVideoRotation;
        MF_MT_FRAME_RATE = gMfMtFrameRate;
        MF_MT_AUDIO_NUM_CHANNELS = gMfMtAudioNumChannels;
        MF_MT_AUDIO_SAMPLES_PER_SECOND = gMfMtAudioSamplesPerSecond;
        MF_PD_DURATION = gMfPdDuration;
        MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING = gMfSrEnableVideoProcessing;
        MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING = gMfSrEnableAdvancedVideoProcessing;
        MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS = gMfRwEnableHardwareTransforms;
        MFT_CATEGORY_VIDEO_DECODER = gMftCategoryVideoDecoder;
        MF_SOURCE_READER_D3D_MANAGER = gMfSrD3dManager;
        CLSID_CMSH264DecoderMFT = gClsidCmsh264;
        IID_IMFTransform = gIidImfTransform;
        IID_ID3D10Multithread = gIidId3d10Mt;
        GUID_NULL = gGuidNull;

        AVAILABLE = available;
        LOAD_ERROR = loadError;
    }

    // -- Public API ----------------------------------------------------------

    /**
     * Returns {@code true} if running on Windows and all required DLLs
     * (mfplat, mfreadwrite, d3d11) were loaded successfully.
     */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Diagnoses Media Foundation issues that may cause video decode failures.
     * <p>
     * Checks:
     * <ul>
     *   <li>Platform is Windows</li>
     *   <li>Media Foundation DLLs are loadable (mfplat.dll, mfreadwrite.dll, mf.dll)</li>
     *   <li>MFT registry entries exist (HKLM\...\Transforms)</li>
     *   <li>MF can be initialized (MFStartup succeeds)</li>
     *   <li>H.264 decoder MFT is registered</li>
     * </ul>
     *
     * @return a diagnostic report describing any issues found, or an empty
     *         string if all checks pass
     */
    public static String diagnose() {
        StringBuilder sb = new StringBuilder();

        if (!IS_WINDOWS) {
            sb.append("NOT_WINDOWS: Media Foundation is only available on Windows\n");
            return sb.toString();
        }

        sb.append("Platform: Windows\n");

        if (!AVAILABLE) {
            sb.append("DLL_LOAD_FAILURE: One or more required DLLs could not be loaded:\n");
            sb.append("  - mfplat.dll (mfplat)\n");
            sb.append("  - mfreadwrite.dll (mfreadwrite)\n");
            sb.append("  - mf.dll (mf)\n");
            sb.append("  - d3d11.dll (d3d11)\n");
            sb.append("\nPossible causes:\n");
            sb.append("  - Windows Media Feature Pack not installed\n");
            sb.append("  - Missing Windows updates\n");
            sb.append("  - Corrupted system files (run 'sfc /scannow')\n");
            return sb.toString();
        }

        sb.append("DLLs: OK\n");

        checkRegistryTransforms(sb);

        checkMediaFoundationStartup(sb);

        checkH264DecoderMFT(sb);

        if (sb.length() == 0) {
            sb.append("All Media Foundation checks passed.\n");
        }

        return sb.toString();
    }

    private static void checkRegistryTransforms(StringBuilder sb) {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[] { "reg", "query",
                            "HKLM\\SOFTWARE\\Microsoft\\Windows Media Foundation\\Transforms",
                            "/s" });
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                sb.append("REGISTRY_MISSING: HKLM\\SOFTWARE\\Microsoft\\Windows Media Foundation\\Transforms\n");
                sb.append("  This registry hive contains MFT (Media Foundation Transform) registrations.\n");
                sb.append("  Missing entries indicate Media Feature Pack is not installed.\n");
                sb.append("\nTo fix:\n");
                sb.append("  1. Open 'OptionalFeatures.exe'\n");
                sb.append("  2. Enable 'Media Features' > 'Windows Media Player'\n");
                sb.append("  3. For Windows N/KN editions, install 'Media Feature Pack'\n");
                sb.append("  4. Install video codec packs (HEVC, HEIF extensions from Microsoft Store)\n");
                sb.append("\n");
            }
        } catch (Exception e) {
            sb.append("REGISTRY_CHECK_FAILED: ").append(e.getMessage()).append("\n");
        }
    }

    private static void checkMediaFoundationStartup(StringBuilder sb) {
        try (Arena temp = Arena.ofConfined()) {
            Ole32.coInitializeEx();
            try {
                int hr = (int) H_MF_STARTUP.invokeExact(MF_VERSION, 0);
                if (failed(hr)) {
                    sb.append("MFSTARTUP_FAILED: HRESULT 0x").append(Integer.toHexString(hr)).append("\n");
                    sb.append("  MFStartup() failed. Media Foundation may be corrupted.\n");
                    sb.append("\nTo fix:\n");
                    sb.append("  1. Run 'sfc /scannow' to repair system files\n");
                    sb.append("  2. Re-enable Windows Media Player in OptionalFeatures.exe\n");
                    sb.append("\n");
                } else {
                    sb.append("MFStartup: OK\n");
                    try { int ignored = (int) H_MF_SHUTDOWN.invokeExact(); } catch (Throwable ignored) {}
                }
            } finally {
                Ole32.coUninitialize();
            }
        } catch (Throwable t) {
            sb.append("MFSTARTUP_ERROR: ").append(t.getMessage()).append("\n");
        }
    }

    private static void checkH264DecoderMFT(StringBuilder sb) {
        try (Arena temp = Arena.ofConfined()) {
            Ole32.coInitializeEx();
            try {
                int hr = (int) H_MF_STARTUP.invokeExact(MF_VERSION, 0);
                if (failed(hr)) {
                    return;
                }
                try {
                    MemorySegment ppDec = temp.allocate(ValueLayout.ADDRESS);
                    hr = (int) Ole32.coCreateInstance(
                            CLSID_CMSH264DecoderMFT, 1, IID_IMFTransform, ppDec);
                    if (failed(hr)) {
                        sb.append("H264_DECODER_MFT_FAILED: HRESULT 0x").append(Integer.toHexString(hr)).append("\n");
                        sb.append("  H.264 (AVC) decoder MFT could not be created.\n");
                        sb.append("  This is required for MP4, MOV, MKV playback.\n");
                        sb.append("\nTo fix:\n");
                        sb.append("  1. Install 'HEVC Video Extensions' from Microsoft Store\n");
                        sb.append("  2. Or install K-Lite Codec Pack / CCCP / LAV Filters\n");
                        sb.append("\n");
                    } else {
                        sb.append("H264 Decoder MFT: OK\n");
                        release(ppDec.get(ValueLayout.ADDRESS, 0));
                    }
                } finally {
                    try { int ignored = (int) H_MF_SHUTDOWN.invokeExact(); } catch (Throwable ignored) {}
                }
            } finally {
                Ole32.coUninitialize();
            }
        } catch (Throwable t) {
            sb.append("H264_DECODER_CHECK_ERROR: ").append(t.getMessage()).append("\n");
        }
    }

    /**
     * Returns video metadata (dimensions, duration, frame rate) without
     * decoding any frames.
     *
     * @param arena the arena for temporary native allocations
     * @param path  file system path to the video file
     * @return video information
     * @throws IllegalStateException    if Media Foundation is not available
     * @throws IllegalArgumentException if the file cannot be opened or parsed
     */
    public static VideoInfo getVideoInfo(Arena arena, String path) {
        ensureAvailable();
        try (Arena temp = Arena.ofConfined()) {
            ensureComReady();
            ensurePlatformStarted();
            SourceReaderHandles srh = createSourceReader(temp, path);
            try {
                return readVideoInfo(temp, srh.reader());
            } finally {
                releaseSourceReader(srh);
            }
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("getVideoInfo failed: " + path, t);
        }
    }

    /**
     * Returns audio metadata (codec, sample rate, channels, duration) for the
     * file's first audio stream, without decoding any samples.
     *
     * <p>If the file has no audio stream, {@link AudioInfo#hasAudio()} is
     * {@code false} and the sample-rate/channel/codec fields are zero/null
     * (the duration is still reported when available). This is the path a
     * caller uses to tell an audio-only file from a silent video container.</p>
     *
     * <p><b>Cover art is intentionally not surfaced here.</b> v1 returns no
     * embedded artwork — matching the AVFoundation facade, which also returns no
     * cover art for audio. {@code extractCoverArt} via {@code MF_PD_THUMBNAIL}
     * is a documented stretch goal, not part of this binding.</p>
     *
     * @param arena the arena for temporary native allocations
     * @param path  file system path to the media file
     * @return audio information for the first audio stream
     * @throws IllegalStateException if Media Foundation is not available
     * @throws DecodeException       if the file cannot be opened as media
     */
    public static AudioInfo getAudioInfo(Arena arena, String path) {
        ensureAvailable();
        try (Arena temp = Arena.ofConfined()) {
            ensureComReady();
            ensurePlatformStarted();
            SourceReaderHandles srh = createSourceReader(temp, path);
            try {
                return readAudioInfo(temp, srh.reader());
            } finally {
                releaseSourceReader(srh);
            }
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("getAudioInfo failed: " + path, t);
        }
    }

    /**
     * Extracts a single video frame at the given time position.
     * <p>
    * The returned {@link DecodedImage} contains BGRA pixels allocated in the
     * caller's {@code arena}. The pixel data is valid for the arena's lifetime.
     *
     * <h2>Pipeline Fallback Chain</h2>
     * <p>
     * This method implements a fallback chain for maximum compatibility:
     * <ol>
     *   <li><b>Source Reader (RGB32)</b> — Tries to configure the Source Reader with
     *       RGB32 output. Fastest path and supports all codecs (H.264, HEVC, VP8/VP9,
     *       AV1, etc.) because Windows selects the appropriate decoder automatically.</li>
    *   <li><b>Source Reader (NV12)</b> — Fallback if RGB32 fails. Returns NV12
    *       which is then converted to BGRA in software.</li>
     *   <li><b>Manual MFT Pipeline</b> — Fallback when Source Reader fails with
     *       E_INVALIDARG (0x80070057). This occurs on systems where the MFT
     *       registry is incomplete (missing HKLM\...\Windows Media Foundation\Transforms).
     *       Manually wires: SourceReader (demuxer) → H.264 decoder MFT → NV12 → software conversion.
     *       <b>Note:</b> this pipeline only handles H.264 — other codecs require the
     *       Source Reader path.</li>
     * </ol>
     *
     * @param arena      the arena that owns the output pixel memory
     * @param path       file system path to the video file
     * @param timeMillis seek position in milliseconds
    * @return decoded frame as BGRA pixels
     * @throws IllegalStateException    if Media Foundation is not available
     * @throws IllegalArgumentException if decoding fails
     */
    public static DecodedImage<PixelFormat> extractFrame(Arena arena, String path,
                                                                   long timeMillis) {
        return extractFrame(arena, path, timeMillis, 0);
    }

    /**
     * Extracts a single video frame at the given time position, optionally
     * asking the decoder to <b>downscale during decode</b> so the longer edge
     * fits {@code maxEdge} (mirrors {@code AVAssetImageGenerator setMaximumSize:}).
     *
     * <p>The downscale is a best-effort hint applied to the decoder's output
     * media type ({@code MF_MT_FRAME_SIZE}). Many decoders — including the stock
     * Microsoft H.264 MFT on some boxes — reject a non-native output size, so it
     * <b>degrades gracefully</b>: a rejected hint falls back to a full-size
     * decode and the returned frame's dimensions are whatever the decoder
     * actually produced (the longer edge is {@code <= maxEdge} when the hint took
     * effect, else the native size). {@code maxEdge <= 0} means "no hint" and is
     * exactly the legacy full-size behaviour. The frame never upscales.</p>
     *
     * @param arena      the arena that owns the output pixel memory
     * @param path       file system path to the video file
     * @param timeMillis seek position in milliseconds
     * @param maxEdge    longer-edge cap for the decoded frame, or {@code <= 0}
     *                   for a full-size decode (no downscale hint)
     * @return decoded frame as BGRA pixels
     * @throws IllegalStateException    if Media Foundation is not available
     * @throws IllegalArgumentException if decoding fails
     */
    public static DecodedImage<PixelFormat> extractFrame(Arena arena, String path,
                                                                   long timeMillis, int maxEdge) {
        ensureAvailable();
        // Source Reader first: lets Windows choose the right decoder for any
        // codec (H.264, HEVC, VP8/VP9, AV1, etc.).  Falls back to the manual
        // H.264 MFT pipeline for systems with incomplete MFT registries.
        // Set system property "panama.media.manualMFTOnly=true" to skip the
        // Source Reader and use the manual H.264 pipeline exclusively.
        if (Boolean.getBoolean("panama.media.manualMFTOnly")) {
            return extractFrameManualMFTPipeline(arena, path, timeMillis, maxEdge);
        }
        try {
            return extractFrameImpl(arena, path, timeMillis, maxEdge);
        } catch (Exception e) {
            if (DEBUG) System.err.println("[MF] SourceReader failed for " + path + ": " + e.getMessage());
            try {
                return extractFrameManualMFTPipeline(arena, path, timeMillis, maxEdge);
            } catch (Exception manualEx) {
                RuntimeException ex = new RuntimeException(
                        "extractFrame failed (SourceReader + manual MFT): " + path, manualEx);
                ex.addSuppressed(e);
                throw ex;
            }
        }
    }

    private static DecodedImage<PixelFormat> extractFrameImpl(Arena arena, String path,
                                                                   long timeMillis, int maxEdge) {
        ensureAvailable();
        try (Arena temp = Arena.ofConfined()) {
            ensureComReady();
            ensurePlatformStarted();
            SourceReaderHandles srh = createSourceReader(temp, path);
            try {
                return decodeFrame(arena, temp, srh.reader(), timeMillis, maxEdge);
            } finally {
                releaseSourceReader(srh);
            }
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("extractFrame failed: " + path, t);
        }
    }

    /**
     * Packs an {@code MF_MT_FRAME_SIZE} value (high 32 bits = width, low 32 bits
     * = height) for a frame that fits {@code maxEdge} on its longer edge,
     * preserving aspect ratio and <b>never upscaling</b>. Dimensions are rounded
     * down to even values (NV12 chroma needs even width/height). Returns
     * {@code 0} when no downscale applies — {@code maxEdge <= 0}, a degenerate
     * native size, or a source that already fits — signalling "leave the native
     * size alone".
     */
    static long fitFrameSize(long nativeFrameSize, int maxEdge) {
        if (maxEdge <= 0) return 0;
        int w = (int) (nativeFrameSize >>> 32);
        int h = (int) (nativeFrameSize & 0xFFFFFFFFL);
        if (w <= 0 || h <= 0) return 0;
        int longEdge = Math.max(w, h);
        if (longEdge <= maxEdge) return 0; // already fits — never upscale
        double scale = (double) maxEdge / longEdge;
        int dw = Math.max(2, ((int) Math.round(w * scale)) & ~1);
        int dh = Math.max(2, ((int) Math.round(h * scale)) & ~1);
        return ((long) dw << 32) | (dh & 0xFFFFFFFFL);
    }

    // -- Streaming video frame reader ----------------------------------------

    /**
     * Opens a sequential BGRA frame reader over the file's first video track.
     * Mirrors the Apple {@code AVFoundation.FrameStream} that the video player
     * wraps.
     *
     * <p>Internally this drives the same manual MFT pipeline that
     * {@link #extractFrame} falls back to (and which actually works across the
     * supported codecs): an {@code IMFSourceReader} used purely as a demuxer
     * feeding compressed samples into a decoder MFT (resolved for the stream's
     * codec via {@link #activateDecoderForType}, H.264 hardcoded fallback),
     * whose NV12 output is converted to BGRA in software. The reader, decoder
     * and D3D device are kept open for the stream's lifetime and pumped one
     * frame per {@link FrameStream#next()}.</p>
     *
     * <p>The returned reader is confined to the calling thread: it joins that
     * thread to the COM multithreaded apartment, owns a confined {@link Arena},
     * and reuses one BGRA buffer for every frame. All of its methods, including
     * {@link FrameStream#close()}, must be called on that thread.</p>
     *
     * @param path absolute file system path to the video file
     * @throws IllegalStateException if Media Foundation is not available
     * @throws DecodeException       if the file has no video track or no decoder
     *                               for its codec can be created
     */
    public static FrameStream openVideo(String path) {
        ensureAvailable();
        return new FrameStream(path);
    }

    /**
     * Sequential video frame reader: each {@link #next()} pulls the following
     * frame into a reusable BGRA buffer ({@code width * height * 4} bytes,
     * tightly packed). Confined to its creating thread; releases every per-frame
     * COM object (the decoded sample and its buffer) so playback does not leak,
     * and releases the reader/decoder/D3D device on {@link #close()}.
     */
    public static final class FrameStream implements AutoCloseable {

        private final Arena arena = Arena.ofConfined();
        // Per-frame scratch slots, reused every next() (never reallocated).
        private final MemorySegment pActualIndex = arena.allocate(ValueLayout.JAVA_INT);
        private final MemorySegment pFlags = arena.allocate(ValueLayout.JAVA_INT);
        private final MemorySegment pTimestamp = arena.allocate(ValueLayout.JAVA_LONG);
        private final MemorySegment ppSample = arena.allocate(ValueLayout.ADDRESS);
        private final MemorySegment pSampleTime = arena.allocate(ValueLayout.JAVA_LONG);
        private final MemorySegment ppBuffer = arena.allocate(ValueLayout.ADDRESS);
        private final MemorySegment ppData = arena.allocate(ValueLayout.ADDRESS);
        private final MemorySegment pMaxLen = arena.allocate(ValueLayout.JAVA_INT);
        private final MemorySegment pCurLen = arena.allocate(ValueLayout.JAVA_INT);
        private final MemorySegment outputBuf = arena.allocate(32);  // MFT_OUTPUT_DATA_BUFFER
        private final MemorySegment pdwStatus = arena.allocate(ValueLayout.JAVA_INT);
        private final MemorySegment ppOutSample = arena.allocate(ValueLayout.ADDRESS);
        private final MemorySegment ppOutBuffer = arena.allocate(ValueLayout.ADDRESS);
        private final MemorySegment ppType = arena.allocate(ValueLayout.ADDRESS);
        private final MemorySegment pStreamInfo = arena.allocate(12);
        private final MemorySegment pFrameSize = arena.allocate(ValueLayout.JAVA_LONG);

        private MemorySegment reader = MemorySegment.NULL;
        private MemorySegment decoder = MemorySegment.NULL;
        private MemorySegment d3dDevice = MemorySegment.NULL;
        private MemorySegment d3dContext = MemorySegment.NULL;
        private MemorySegment dxgiManager = MemorySegment.NULL;
        private MemorySegment bgra = MemorySegment.NULL;
        private int width;          // display width (cropped output, before rotation)
        private int height;         // display height (cropped output, before rotation)
        private int codedWidth;     // macroblock-coded width the decoder emits
        private int codedHeight;    // macroblock-coded height the decoder emits
        private int rotationTurns;  // container display rotation, 90 CW quarter-turns
        private MemorySegment rotatedBgra = MemorySegment.NULL; // rotated output, when rotationTurns != 0
        private int rotatedBgraLen; // rotatedBgra capacity in ints (width*height)
        private int[] rotSrc;       // reused source scratch for the rotation copy
        private int[] rotDst;       // reused destination scratch for the rotation copy
        private long durationMicros = -1;
        private long ptsMicros = -1;
        private boolean decoderProvidesSamples;
        private int outputBufSize;
        private boolean inputDrained;
        private boolean ended;
        private boolean closed;

        private FrameStream(String path) {
            boolean ok = false;
            try (Arena temp = Arena.ofConfined()) {
                ensureComReady();
                ensurePlatformStarted();
                registerStoreCodecs();

                // Source Reader as a plain demuxer (compressed samples only).
                MemorySegment ppAttrs = temp.allocate(ValueLayout.ADDRESS);
                int hr = (int) H_MF_CREATE_ATTRIBUTES.invokeExact(ppAttrs, 0);
                check(hr, "MFCreateAttributes");
                MemorySegment attrs = ppAttrs.get(ValueLayout.ADDRESS, 0);
                MemorySegment wpath = wstr(temp, path);
                MemorySegment ppReader = temp.allocate(ValueLayout.ADDRESS);
                hr = (int) H_MF_CREATE_SOURCE_READER_FROM_URL.invokeExact(wpath, attrs, ppReader);
                release(attrs);
                check(hr, "MFCreateSourceReaderFromURL");
                reader = ppReader.get(ValueLayout.ADDRESS, 0);

                durationMicros = readDurationMicros(temp, reader);

                MemorySegment ppNativeType = temp.allocate(ValueLayout.ADDRESS);
                hr = (int) IMFSourceReader_GetNativeMediaType.invokeExact(
                        vtable(reader, 5), reader,
                        MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, ppNativeType);
                check(hr, "GetNativeMediaType");
                MemorySegment nativeType = ppNativeType.get(ValueLayout.ADDRESS, 0);
                try {
                    setupDecoder(temp, nativeType);
                } finally {
                    release(nativeType);
                }

                bgra = arena.allocate((long) width * height * RGBA_BPP);
                ok = true;
            } catch (IllegalStateException | DecodeException e) {
                throw e;
            } catch (Throwable t) {
                throw new DecodeException("openVideo failed: " + path, t);
            } finally {
                if (!ok) close();
            }
        }

        /**
         * Creates the D3D11 device + DXGI manager, resolves and configures a
         * decoder MFT for {@code nativeType}, and records the output frame size
         * and stream-info flags. Best-effort on D3D: if the device cannot be
         * created the decoder runs in software without a D3D manager.
         */
        private void setupDecoder(Arena temp, MemorySegment nativeType) throws Throwable {
            MemorySegment ppDevice = temp.allocate(ValueLayout.ADDRESS);
            MemorySegment ppContext = temp.allocate(ValueLayout.ADDRESS);
            int hr = (int) H_D3D11_CREATE_DEVICE.invokeExact(
                    MemorySegment.NULL, 1 /* HARDWARE */, MemorySegment.NULL, 0x820,
                    MemorySegment.NULL, 0, 7, ppDevice, MemorySegment.NULL, ppContext);
            if (failed(hr)) {
                hr = (int) H_D3D11_CREATE_DEVICE.invokeExact(
                        MemorySegment.NULL, 5 /* WARP */, MemorySegment.NULL, 0x820,
                        MemorySegment.NULL, 0, 7, ppDevice, MemorySegment.NULL, ppContext);
            }
            if (!failed(hr)) {
                d3dDevice = ppDevice.get(ValueLayout.ADDRESS, 0);
                d3dContext = ppContext.get(ValueLayout.ADDRESS, 0);
                MemorySegment ppMT = temp.allocate(ValueLayout.ADDRESS);
                hr = (int) IUnknown_QueryInterface.invokeExact(
                        vtable(d3dDevice, 0), d3dDevice, IID_ID3D10Multithread, ppMT);
                if (!failed(hr)) {
                    MemorySegment mt = ppMT.get(ValueLayout.ADDRESS, 0);
                    int ignoredMt = (int) ID3D10Multithread_SetMultithreadProtected.invokeExact(
                            vtable(mt, 5), mt, 1);
                    release(mt);
                }
                MemorySegment pResetToken = temp.allocate(ValueLayout.JAVA_INT);
                MemorySegment ppManager = temp.allocate(ValueLayout.ADDRESS);
                hr = (int) H_MF_CREATE_DXGI_DEVICE_MANAGER.invokeExact(pResetToken, ppManager);
                if (!failed(hr)) {
                    dxgiManager = ppManager.get(ValueLayout.ADDRESS, 0);
                    hr = (int) IMFDXGIDeviceManager_ResetDevice.invokeExact(
                            vtable(dxgiManager, 7), dxgiManager,
                            d3dDevice, pResetToken.get(ValueLayout.JAVA_INT, 0));
                    if (failed(hr)) { release(dxgiManager); dxgiManager = MemorySegment.NULL; }
                }
            }

            // Resolve a decoder MFT for the stream's codec (handles H.264, and
            // Store-distributed VP9/HEVC/AV1), hardcoded H.264 as a last resort.
            decoder = activateDecoderForType(temp, nativeType);
            if (MemorySegment.NULL.equals(decoder)) {
                MemorySegment ppDec = temp.allocate(ValueLayout.ADDRESS);
                hr = (int) Ole32.coCreateInstance(
                        CLSID_CMSH264DecoderMFT, 1, IID_IMFTransform, ppDec);
                check(hr, "CoCreateInstance(H.264 decoder)");
                decoder = ppDec.get(ValueLayout.ADDRESS, 0);
            }

            if (!MemorySegment.NULL.equals(dxgiManager)) {
                int ignored = (int) IMFTransform_ProcessMessage.invokeExact(
                        vtable(decoder, 23), decoder,
                        MFT_MESSAGE_SET_D3D_MANAGER, dxgiManager.address());
            }

            hr = (int) IMFTransform_SetInputType.invokeExact(
                    vtable(decoder, 15), decoder, 0, nativeType, 0);
            check(hr, "Decoder SetInputType");

            MemorySegment ppDecOut = temp.allocate(ValueLayout.ADDRESS);
            hr = (int) IMFTransform_GetOutputAvailableType.invokeExact(
                    vtable(decoder, 14), decoder, 0, 0, ppDecOut);
            check(hr, "Decoder GetOutputAvailableType");
            MemorySegment decoderOutputType = ppDecOut.get(ValueLayout.ADDRESS, 0);
            try {
                hr = (int) IMFTransform_SetOutputType.invokeExact(
                        vtable(decoder, 16), decoder, 0, decoderOutputType, 0);
                check(hr, "Decoder SetOutputType");
                hr = (int) IMFAttributes_GetUINT64.invokeExact(
                        vtable(decoderOutputType, 8), decoderOutputType,
                        MF_MT_FRAME_SIZE, pFrameSize);
                check(hr, "GetUINT64(MF_MT_FRAME_SIZE)");
            } finally {
                release(decoderOutputType);
            }
            long fs = pFrameSize.get(ValueLayout.JAVA_LONG, 0);
            width = (int) (fs >>> 32);
            height = (int) (fs & 0xFFFFFFFFL);
            if (width <= 0 || height <= 0)
                throw new DecodeException("bad video dimensions " + width + "x" + height);
            // Display size for now; a later STREAM_CHANGE re-reports the larger
            // macroblock-coded size (e.g. 1920x1088 for 1918x1080) into
            // codedWidth/codedHeight without disturbing the display output size.
            codedWidth = width;
            codedHeight = height;

            readStreamInfo();

            // Container display rotation (best-effort): MF hands back un-rotated
            // frames, so record the rotation here and bake it in copyNV12Frame so
            // playback is upright like the poster and the Apple/pure backends.
            // Absent or unreadable -> 0 (no rotation), never fatal.
            try {
                rotationTurns = rotationQuarterTurnsCwFromMf(
                        getUint32(temp, nativeType, MF_MT_VIDEO_ROTATION));
            } catch (Throwable ignore) {
                rotationTurns = 0;
            }

            int ignoredBegin = (int) IMFTransform_ProcessMessage.invokeExact(
                    vtable(decoder, 23), decoder, MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0L);
            int ignoredStart = (int) IMFTransform_ProcessMessage.invokeExact(
                    vtable(decoder, 23), decoder, MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0L);
        }

        /** Records whether the decoder allocates output samples and its buffer size. */
        private void readStreamInfo() throws Throwable {
            int hr = (int) IMFTransform_GetOutputStreamInfo.invokeExact(
                    vtable(decoder, 7), decoder, 0, pStreamInfo);
            if (!failed(hr)) {
                decoderProvidesSamples = (pStreamInfo.get(ValueLayout.JAVA_INT, 0)
                        & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) != 0;
                outputBufSize = pStreamInfo.get(ValueLayout.JAVA_INT, 4);
            }
        }

        /** Frame width in pixels (fixed for the stream; reflects container rotation). */
        public int width() { return (rotationTurns & 1) == 1 ? height : width; }

        /** Frame height in pixels (fixed for the stream; reflects container rotation). */
        public int height() { return (rotationTurns & 1) == 1 ? width : height; }

        /** Stream duration in microseconds, or -1 when unknown. */
        public long durationMicros() { return durationMicros; }

        /** Presentation time of the last decoded frame, in microseconds. */
        public long ptsMicros() { return ptsMicros; }

        /** Reusable BGRA buffer holding the last frame ({@code width()*height()*4}
         *  bytes); the rotated buffer when the container carries a display rotation. */
        public MemorySegment bgra() { return rotationTurns == 0 ? bgra : rotatedBgra; }

        /** Pulls the next frame into {@link #bgra()}; {@code false} at end of stream. */
        public boolean next() {
            if (ended || closed) return false;
            try {
                for (int guard = 0; guard < 100_000; guard++) {
                    MemorySegment callerSample = MemorySegment.NULL;
                    outputBuf.set(ValueLayout.JAVA_INT, 0, 0);            // dwStreamID
                    outputBuf.set(ValueLayout.JAVA_INT, 16, 0);           // dwStatus
                    outputBuf.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL); // pEvents
                    if (decoderProvidesSamples) {
                        outputBuf.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
                    } else {
                        callerSample = createOutputSample();
                        outputBuf.set(ValueLayout.ADDRESS, 8, callerSample);
                    }

                    int hr = (int) IMFTransform_ProcessOutput.invokeExact(
                            vtable(decoder, 25), decoder, 0, 1, outputBuf, pdwStatus);

                    if (hr == MF_E_TRANSFORM_TYPE_NOT_SET || hr == MF_E_TRANSFORM_STREAM_CHANGE) {
                        if (!MemorySegment.NULL.equals(callerSample)) release(callerSample);
                        reconfigureOutput();
                        continue;
                    }
                    if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) {
                        if (!MemorySegment.NULL.equals(callerSample)) release(callerSample);
                        if (inputDrained) { ended = true; return false; }
                        feedInput();
                        continue;
                    }
                    check(hr, "Decoder ProcessOutput");

                    MemorySegment decodedSample = outputBuf.get(ValueLayout.ADDRESS, 8);
                    if (!MemorySegment.NULL.equals(callerSample)
                            && !callerSample.equals(decodedSample)) {
                        release(callerSample);
                    }
                    if (MemorySegment.NULL.equals(decodedSample)) continue;
                    try {
                        int hrPts = (int) IMFSample_GetSampleTime.invokeExact(
                                vtable(decodedSample, 35), decodedSample, pSampleTime);
                        if (!failed(hrPts))
                            ptsMicros = pSampleTime.get(ValueLayout.JAVA_LONG, 0) / 10L;
                        copyNV12Frame(decodedSample);
                        return true;
                    } finally {
                        release(decodedSample);
                    }
                }
                throw new DecodeException("decoder made no progress");
            } catch (IllegalStateException | DecodeException e) {
                throw e;
            } catch (Throwable t) {
                throw new DecodeException("frame read failed", t);
            }
        }

        /** Reads one compressed sample from the demuxer and feeds it to the decoder. */
        private void feedInput() throws Throwable {
            ppSample.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            int hr = (int) IMFSourceReader_ReadSample.invokeExact(
                    vtable(reader, 9), reader,
                    MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0,
                    pActualIndex, pFlags, pTimestamp, ppSample);
            check(hr, "ReadSample");
            int flags = pFlags.get(ValueLayout.JAVA_INT, 0);
            if ((flags & MF_SOURCE_READERF_ERROR) != 0)
                throw new DecodeException("Media Foundation stream error during ReadSample");
            MemorySegment compressed = ppSample.get(ValueLayout.ADDRESS, 0);
            if (!MemorySegment.NULL.equals(compressed)) {
                try {
                    int ignored = (int) IMFTransform_ProcessInput.invokeExact(
                            vtable(decoder, 24), decoder, 0, compressed, 0);
                } finally {
                    release(compressed);
                }
            }
            if ((flags & MF_SOURCE_READERF_ENDOFSTREAM) != 0) {
                inputDrained = true;
                int ignored = (int) IMFTransform_ProcessMessage.invokeExact(
                        vtable(decoder, 23), decoder, MFT_MESSAGE_COMMAND_DRAIN, 0L);
            }
        }

        /** Allocates a fresh output sample backed by a memory buffer for ProcessOutput. */
        private MemorySegment createOutputSample() throws Throwable {
            // Size for the macroblock-coded NV12 frame (>= display), so a
            // caller-allocated output buffer is never short for a padded frame.
            int cw = Math.max(codedWidth, width);
            int ch = Math.max(codedHeight, height);
            int bufSz = (outputBufSize > 0) ? outputBufSize : cw * ch * 3 / 2;
            int hr = (int) H_MF_CREATE_SAMPLE.invokeExact(ppOutSample);
            check(hr, "MFCreateSample");
            MemorySegment sample = ppOutSample.get(ValueLayout.ADDRESS, 0);
            hr = (int) H_MF_CREATE_MEMORY_BUFFER.invokeExact(bufSz, ppOutBuffer);
            check(hr, "MFCreateMemoryBuffer");
            MemorySegment memBuf = ppOutBuffer.get(ValueLayout.ADDRESS, 0);
            hr = (int) IMFSample_AddBuffer.invokeExact(vtable(sample, 42), sample, memBuf);
            check(hr, "IMFSample::AddBuffer");
            release(memBuf);
            return sample;
        }

        /** Re-reads the decoder output type after a stream/format change. */
        private void reconfigureOutput() throws Throwable {
            int hr = (int) IMFTransform_GetOutputAvailableType.invokeExact(
                    vtable(decoder, 14), decoder, 0, 0, ppType);
            check(hr, "GetOutputAvailableType after stream change");
            MemorySegment newType = ppType.get(ValueLayout.ADDRESS, 0);
            try {
                hr = (int) IMFTransform_SetOutputType.invokeExact(
                        vtable(decoder, 16), decoder, 0, newType, 0);
                check(hr, "Re-SetOutputType");
                hr = (int) IMFAttributes_GetUINT64.invokeExact(
                        vtable(newType, 8), newType, MF_MT_FRAME_SIZE, pFrameSize);
                if (!failed(hr)) {
                    long fs = pFrameSize.get(ValueLayout.JAVA_LONG, 0);
                    int w = (int) (fs >>> 32);
                    int h = (int) (fs & 0xFFFFFFFFL);
                    if (w > 0 && h > 0) {
                        // The post-change size is the macroblock-coded frame the
                        // decoder actually outputs (>= display). Keep the display
                        // size for the cropped BGRA output; only grow codedWidth/
                        // codedHeight (used to read the padded NV12 planes).
                        codedWidth = w;
                        codedHeight = h;
                        if (width <= 0 || height <= 0) {
                            width = w;
                            height = h;
                            bgra = arena.allocate((long) width * height * RGBA_BPP);
                        }
                    }
                }
            } finally {
                release(newType);
            }
            readStreamInfo();
        }

        /** Locks the decoded NV12 sample and converts it into the reusable {@link #bgra}. */
        private void copyNV12Frame(MemorySegment sample) throws Throwable {
            int hr = (int) IMFSample_ConvertToContiguousBuffer.invokeExact(
                    vtable(sample, 41), sample, ppBuffer);
            check(hr, "ConvertToContiguousBuffer");
            MemorySegment buffer = ppBuffer.get(ValueLayout.ADDRESS, 0);
            try {
                hr = (int) IMFMediaBuffer_Lock.invokeExact(
                        vtable(buffer, 3), buffer, ppData, pMaxLen, pCurLen);
                check(hr, "IMFMediaBuffer::Lock");
                try {
                    MemorySegment data = ppData.get(ValueLayout.ADDRESS, 0);
                    int curLen = pCurLen.get(ValueLayout.JAVA_INT, 0);
                    int cw = codedWidth > 0 ? codedWidth : width;
                    int ch = codedHeight > 0 ? codedHeight : height;
                    int nv12Expected = cw * ch * 3 / 2;
                    if (curLen < nv12Expected)
                        throw new DecodeException("NV12 buffer too small: expected "
                                + nv12Expected + " bytes, got " + curLen);
                    // The decoder emits the macroblock-coded frame (cw x ch) with a
                    // padded plane stride; derive that stride from the contiguous
                    // buffer length (Y rows + half-height interleaved UV rows) and
                    // crop back to the display size. Reading the padded buffer at
                    // the display stride is exactly what shears each row.
                    int totalRows = ch + (ch + 1) / 2;
                    int stride = (totalRows > 0 && curLen % totalRows == 0)
                            ? curLen / totalRows : cw;
                    if (stride < cw) stride = cw;
                    convertNV12intoBGRA(data.reinterpret(curLen), stride, ch,
                            width, height, bgra);
                    if (rotationTurns != 0) applyRotation();
                } finally {
                    try {
                        int ignored = (int) IMFMediaBuffer_Unlock.invokeExact(
                                vtable(buffer, 4), buffer);
                    } catch (Throwable ignored) {}
                }
            } finally {
                release(buffer);
            }
        }

        /**
         * Permutes the just-converted {@link #bgra} into {@link #rotatedBgra} by
         * the container's {@link #rotationTurns} clockwise rotation (shared
         * {@link BgraRotation#rotate}); {@link #bgra()} then hands out the rotated
         * buffer and {@link #width()}/{@link #height()} report the swapped dims.
         * {@code bgra} is tightly packed at {@code width}, so its stride in ints
         * is exactly {@code width}.
         */
        private void applyRotation() {
            int outInts = width * height;
            if (rotSrc == null || rotSrc.length < outInts) rotSrc = new int[outInts];
            MemorySegment.copy(bgra, ValueLayout.JAVA_INT, 0, rotSrc, 0, outInts);
            if (rotDst == null || rotDst.length < outInts) rotDst = new int[outInts];
            BgraRotation.rotate(rotSrc, width, width, height, rotationTurns, rotDst);
            if (MemorySegment.NULL.equals(rotatedBgra) || rotatedBgraLen < outInts) {
                rotatedBgra = arena.allocate((long) outInts * RGBA_BPP);
                rotatedBgraLen = outInts;
            }
            MemorySegment.copy(rotDst, 0, rotatedBgra, ValueLayout.JAVA_INT, 0, outInts);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            release(decoder);
            release(reader);
            release(dxgiManager);
            release(d3dContext);
            release(d3dDevice);
            decoder = MemorySegment.NULL;
            reader = MemorySegment.NULL;
            dxgiManager = MemorySegment.NULL;
            d3dContext = MemorySegment.NULL;
            d3dDevice = MemorySegment.NULL;
            arena.close();
        }
    }

    /** Reads stream duration (100-ns units to micros) from the presentation descriptor, or -1. */
    private static long readDurationMicros(Arena temp, MemorySegment reader) throws Throwable {
        MemorySegment propvar = temp.allocate(PROPVARIANT_SIZE);
        int hr = (int) IMFSourceReader_GetPresentationAttribute.invokeExact(
                vtable(reader, 12), reader,
                MF_SOURCE_READER_MEDIASOURCE, MF_PD_DURATION, propvar);
        if (failed(hr)) return -1;
        long hundredNs = propvar.get(ValueLayout.JAVA_LONG, 8);
        return hundredNs / 10L;
    }

    // -- Internal: Source Reader creation ------------------------------------

    /**
     * Creates a Source Reader with attributes for hardware MFT loading and
     * advanced video processing.  Required for Store-distributed codecs
     * (VP9, HEVC, AV1).
     *
     * @param temp arena for temporary allocations
     * @param path file system path to the media file
     * @return the Source Reader (caller must release)
     */
    /**
     * A Source Reader plus the COM objects backing its hardware decode path.
     *
     * <p>Each field is a COM interface that the caller MUST release via
     * {@link #releaseSourceReader}. The confining {@link Arena} only frees the
     * Java-side pointer slots used to receive these objects — it does NOT touch
     * the underlying COM reference counts, so failing to release them leaks a
     * D3D11 device, its immediate context and a DXGI device manager on every
     * call (which, under the thumbnail pool, exhausts GPU/hardware-decoder
     * resources and makes later extractions fail intermittently).
     */
    private record SourceReaderHandles(MemorySegment reader, MemorySegment device,
                                       MemorySegment context, MemorySegment manager) {}

    /**
     * Releases a Source Reader and the D3D11 device/context/DXGI manager that
     * back it. The reader is released first because it holds its own reference
     * on the DXGI manager (via {@code MF_SOURCE_READER_D3D_MANAGER}), so the
     * manager remains valid until the reader is gone.
     */
    private static void releaseSourceReader(SourceReaderHandles handles) {
        if (handles == null) return;
        release(handles.reader());
        release(handles.manager());
        release(handles.context());
        release(handles.device());
    }

    private static SourceReaderHandles createSourceReader(Arena temp, String path) throws Throwable {
        registerStoreCodecs();
        MemorySegment ppAttrs = temp.allocate(ValueLayout.ADDRESS);
        int hr = (int) H_MF_CREATE_ATTRIBUTES.invokeExact(ppAttrs, 3);
        check(hr, "MFCreateAttributes");
        MemorySegment attrs = ppAttrs.get(ValueLayout.ADDRESS, 0);
        MemorySegment d3dDevice = MemorySegment.NULL;
        MemorySegment d3dContext = MemorySegment.NULL;
        MemorySegment dxgiManager = MemorySegment.NULL;
        boolean handedOff = false;
        try {
            hr = (int) IMFAttributes_SetUINT32.invokeExact(
                    vtable(attrs, 21), attrs,
                    MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, 1);
            check(hr, "SetUINT32(ENABLE_HARDWARE_TRANSFORMS)");

            // Create D3D11 device + DXGI manager for hardware decoder support
            // (Store codecs like VP9/HEVC/AV1 require D3D surfaces)
            MemorySegment ppDevice = temp.allocate(ValueLayout.ADDRESS);
            MemorySegment ppContext = temp.allocate(ValueLayout.ADDRESS);
            hr = (int) H_D3D11_CREATE_DEVICE.invokeExact(
                    MemorySegment.NULL, 1 /* D3D_DRIVER_TYPE_HARDWARE */,
                    MemorySegment.NULL, 0x820 /* BGRA | VIDEO_SUPPORT */,
                    MemorySegment.NULL, 0, 7 /* SDK_VERSION */,
                    ppDevice, MemorySegment.NULL, ppContext);
            if (failed(hr)) {
                // Fall back to WARP software device
                hr = (int) H_D3D11_CREATE_DEVICE.invokeExact(
                        MemorySegment.NULL, 5 /* D3D_DRIVER_TYPE_WARP */,
                        MemorySegment.NULL, 0x820,
                        MemorySegment.NULL, 0, 7,
                        ppDevice, MemorySegment.NULL, ppContext);
            }
            if (!failed(hr)) {
                d3dDevice = ppDevice.get(ValueLayout.ADDRESS, 0);
                d3dContext = ppContext.get(ValueLayout.ADDRESS, 0);

                // The Source Reader may drive the hardware decoder from its own
                // worker threads, so the shared D3D11 device must be marked
                // multithread-protected (matches the manual MFT pipeline).
                MemorySegment ppMT = temp.allocate(ValueLayout.ADDRESS);
                hr = (int) IUnknown_QueryInterface.invokeExact(
                        vtable(d3dDevice, 0), d3dDevice,
                        IID_ID3D10Multithread, ppMT);
                if (!failed(hr)) {
                    MemorySegment mt = ppMT.get(ValueLayout.ADDRESS, 0);
                    int ignoredMt = (int) ID3D10Multithread_SetMultithreadProtected.invokeExact(
                            vtable(mt, 5), mt, 1);
                    release(mt);
                }

                MemorySegment pResetToken = temp.allocate(ValueLayout.JAVA_INT);
                MemorySegment ppManager = temp.allocate(ValueLayout.ADDRESS);
                hr = (int) H_MF_CREATE_DXGI_DEVICE_MANAGER.invokeExact(pResetToken, ppManager);
                if (!failed(hr)) {
                    dxgiManager = ppManager.get(ValueLayout.ADDRESS, 0);
                    hr = (int) IMFDXGIDeviceManager_ResetDevice.invokeExact(
                            vtable(dxgiManager, 7), dxgiManager,
                            d3dDevice, pResetToken.get(ValueLayout.JAVA_INT, 0));
                    if (!failed(hr)) {
                        hr = (int) IMFAttributes_SetUnknown.invokeExact(
                                vtable(attrs, 27), attrs,
                                MF_SOURCE_READER_D3D_MANAGER, dxgiManager);
                    }
                }
            }

            // MSDN: when D3D manager is set, use ENABLE_VIDEO_PROCESSING
            // (ENABLE_ADVANCED_VIDEO_PROCESSING is ignored with D3D)
            hr = (int) IMFAttributes_SetUINT32.invokeExact(
                    vtable(attrs, 21), attrs,
                    MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, 1);
            check(hr, "SetUINT32(ENABLE_VIDEO_PROCESSING)");

            MemorySegment wpath = wstr(temp, path);
            MemorySegment ppReader = temp.allocate(ValueLayout.ADDRESS);
            hr = (int) H_MF_CREATE_SOURCE_READER_FROM_URL.invokeExact(wpath, attrs, ppReader);
            check(hr, "MFCreateSourceReaderFromURL");
            MemorySegment reader = ppReader.get(ValueLayout.ADDRESS, 0);
            handedOff = true;
            return new SourceReaderHandles(reader, d3dDevice, d3dContext, dxgiManager);
        } finally {
            release(attrs);
            if (!handedOff) {
                // Construction failed after the device/manager were created:
                // release them here so the error path does not leak COM objects.
                release(dxgiManager);
                release(d3dContext);
                release(d3dDevice);
            }
        }
    }

    // -- Internal: getVideoInfo pipeline -------------------------------------

    /**
     * Reads video metadata from an open source reader.
     */
    private static VideoInfo readVideoInfo(Arena temp, MemorySegment reader) throws Throwable {
        // Get native media type for the first video stream
        MemorySegment ppType = temp.allocate(ValueLayout.ADDRESS);
        int hr = (int) IMFSourceReader_GetNativeMediaType.invokeExact(
                vtable(reader, 5), reader,
                MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, ppType);
        check(hr, "GetNativeMediaType");
        MemorySegment nativeType = ppType.get(ValueLayout.ADDRESS, 0);

        int width;
        int height;
        double frameRate = 0.0;
        String codec = null;
        try {
            // Frame size: packed as (width << 32 | height)
            MemorySegment pFrameSize = temp.allocate(ValueLayout.JAVA_LONG);
            hr = (int) IMFAttributes_GetUINT64.invokeExact(
                    vtable(nativeType, 8), nativeType,
                    MF_MT_FRAME_SIZE, pFrameSize);
            check(hr, "GetUINT64(MF_MT_FRAME_SIZE)");
            long frameSize = pFrameSize.get(ValueLayout.JAVA_LONG, 0);
            width = (int) (frameSize >>> 32);
            height = (int) (frameSize & 0xFFFFFFFFL);

            // Frame rate: packed as (numerator << 32 | denominator)
            MemorySegment pFrameRate = temp.allocate(ValueLayout.JAVA_LONG);
            hr = (int) IMFAttributes_GetUINT64.invokeExact(
                    vtable(nativeType, 8), nativeType,
                    MF_MT_FRAME_RATE, pFrameRate);
            if (!failed(hr)) {
                long rate = pFrameRate.get(ValueLayout.JAVA_LONG, 0);
                long numerator = rate >>> 32;
                long denominator = rate & 0xFFFFFFFFL;
                if (denominator > 0) {
                    frameRate = (double) numerator / denominator;
                }
            }

            // Codec name from MF_MT_SUBTYPE (best-effort; never break the probe).
            try {
                codec = readVideoSubtypeCodec(temp, nativeType);
            } catch (Throwable ignored) {
                codec = null;
            }
        } finally {
            release(nativeType);
        }

        // Duration from presentation attributes
        long durationMillis = 0;
        MemorySegment propvar = temp.allocate(PROPVARIANT_SIZE);
        hr = (int) IMFSourceReader_GetPresentationAttribute.invokeExact(
                vtable(reader, 12), reader,
                MF_SOURCE_READER_MEDIASOURCE,
                MF_PD_DURATION, propvar);
        if (!failed(hr)) {
            // Duration in 100-nanosecond units at offset 8
            long hundredNs = propvar.get(ValueLayout.JAVA_LONG, 8);
            durationMillis = hundredNs / 10_000;
        }

        return new VideoInfo(width, height, durationMillis, frameRate, codec);
    }

    // -- Internal: getAudioInfo pipeline -------------------------------------

    /**
     * Reads audio metadata from an open source reader's first audio stream.
     * Returns a {@code hasAudio == false} record (duration still populated when
     * known) if the file has no audio stream.
     */
    private static AudioInfo readAudioInfo(Arena temp, MemorySegment reader) throws Throwable {
        long durationMicros = readDurationMicros(temp, reader);
        long durationMillis = durationMicros >= 0 ? durationMicros / 1000L : 0;

        MemorySegment ppType = temp.allocate(ValueLayout.ADDRESS);
        int hr = (int) IMFSourceReader_GetNativeMediaType.invokeExact(
                vtable(reader, 5), reader,
                MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0, ppType);
        if (failed(hr)) {
            // No audio stream (e.g. a silent video container).
            return new AudioInfo(false, durationMillis, 0, 0, null);
        }
        MemorySegment nativeType = ppType.get(ValueLayout.ADDRESS, 0);
        try {
            int sampleRate = getUint32(temp, nativeType, MF_MT_AUDIO_SAMPLES_PER_SECOND);
            int channels = getUint32(temp, nativeType, MF_MT_AUDIO_NUM_CHANNELS);
            String codec = readSubtypeCodec(temp, nativeType);
            return new AudioInfo(true, durationMillis, sampleRate, channels, codec);
        } finally {
            release(nativeType);
        }
    }

    /** Reads a UINT32 media-type attribute, or 0 if it is absent. */
    private static int getUint32(Arena temp, MemorySegment attrs, MemorySegment key) throws Throwable {
        MemorySegment p = temp.allocate(ValueLayout.JAVA_INT);
        int hr = (int) IMFAttributes_GetUINT32.invokeExact(vtable(attrs, 7), attrs, key, p);
        return failed(hr) ? 0 : p.get(ValueLayout.JAVA_INT, 0);
    }

    /**
     * Maps an {@code MF_MT_VIDEO_ROTATION} value to 90&deg; <b>clockwise</b>
     * quarter-turns (0..3) to apply for upright display. The attribute is a
     * {@code MFVideoRotationFormat} (0/90/180/270) measured <b>counterclockwise</b>
     * — the same convention the (correct) pure backend reads from the container as
     * {@code rotationDegreesCcw} — so {@code N} degrees CCW is undone by
     * {@code (4 - N/90)} quarter-turns clockwise. With this, a portrait phone clip
     * decodes upright, matching the poster and the Apple/pure backends.
     *
     * <p><b>Direction note:</b> if a Windows tester ever finds rotated clips land
     * 180&deg; off (90 vs 270 swapped), this single mapping is the one knob — flip
     * to {@code (degreesCcw / 90) & 3}. Everything else is direction-agnostic.</p>
     */
    static int rotationQuarterTurnsCwFromMf(int degreesCcw) {
        int q = (degreesCcw / 90) & 3;
        return (4 - q) & 3;
    }

    /**
     * Best-effort read of the first video stream's {@code MF_MT_VIDEO_ROTATION}
     * from the reader's native media type, as clockwise quarter-turns (0..3).
     * Never throws and returns {@code 0} (no rotation) on any failure, so it can
     * never regress the working decode paths.
     */
    private static int readRotationCw(Arena temp, MemorySegment reader) {
        try {
            MemorySegment pp = temp.allocate(ValueLayout.ADDRESS);
            int hr = (int) IMFSourceReader_GetNativeMediaType.invokeExact(
                    vtable(reader, 5), reader,
                    MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, pp);
            if (failed(hr)) return 0;
            MemorySegment nt = pp.get(ValueLayout.ADDRESS, 0);
            if (MemorySegment.NULL.equals(nt)) return 0;
            try {
                return rotationQuarterTurnsCwFromMf(getUint32(temp, nt, MF_MT_VIDEO_ROTATION));
            } finally {
                release(nt);
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Returns {@code img} rotated by {@code qcw} 90&deg; clockwise quarter-turns
     * (a fresh allocation in {@code arena}), or {@code img} unchanged when
     * {@code qcw == 0}. Used to bake the container's display rotation into the
     * poster so Windows tiles are upright (matching playback and the other
     * backends). The pixel permutation is the shared {@link BgraRotation#rotate}.
     */
    private static DecodedImage<PixelFormat> rotateDecoded(
            Arena arena, DecodedImage<PixelFormat> img, int qcw) {
        if ((qcw & 3) == 0) return img;
        int w = img.width();
        int h = img.height();
        int srcStrideInts = img.stride() / 4;
        int[] src = new int[srcStrideInts * h];
        MemorySegment.copy(img.pixels(), ValueLayout.JAVA_INT, 0, src, 0, src.length);
        boolean swap = (qcw & 1) == 1;
        int ow = swap ? h : w;
        int oh = swap ? w : h;
        int[] dst = new int[ow * oh];
        BgraRotation.rotate(src, srcStrideInts, w, h, qcw, dst);
        MemorySegment out = arena.allocate((long) ow * oh * RGBA_BPP);
        MemorySegment.copy(dst, 0, out, ValueLayout.JAVA_INT, 0, dst.length);
        return new DecodedImage<>(out, ow, oh, ow * RGBA_BPP, PixelFormat.BGRA);
    }

    /** Reads MF_MT_SUBTYPE from a media type and maps it to a codec name, or null. */
    private static String readSubtypeCodec(Arena temp, MemorySegment mediaType) throws Throwable {
        MemorySegment pSubtype = temp.allocate(16);
        int hr = (int) IMFAttributes_GetGUID.invokeExact(
                vtable(mediaType, 10), mediaType, MF_MT_SUBTYPE, pSubtype);
        if (failed(hr)) return null;
        byte[] guid = new byte[16];
        MemorySegment.copy(pSubtype, ValueLayout.JAVA_BYTE, 0, guid, 0, 16);
        return audioCodecName(guid);
    }

    /** Reads MF_MT_SUBTYPE from a video media type and maps it to a codec name, or null. */
    private static String readVideoSubtypeCodec(Arena temp, MemorySegment mediaType) throws Throwable {
        MemorySegment pSubtype = temp.allocate(16);
        int hr = (int) IMFAttributes_GetGUID.invokeExact(
                vtable(mediaType, 10), mediaType, MF_MT_SUBTYPE, pSubtype);
        if (failed(hr)) return null;
        byte[] guid = new byte[16];
        MemorySegment.copy(pSubtype, ValueLayout.JAVA_BYTE, 0, guid, 0, 16);
        return videoCodecName(guid);
    }

    /**
     * Maps a video {@code MF_MT_SUBTYPE} GUID to a short codec name.
     *
     * <p>Video subtypes are FOURCC-leading: the GUID has the form
     * {@code {XXXXXXXX-0000-0010-8000-00AA00389B71}} with the leading four bytes
     * a printable FOURCC (e.g. {@code H264}, {@code HVC1}, {@code VP90},
     * {@code AV01}, {@code MP4V}). The FOURCC is switched to a friendly name; an
     * unmapped-but-printable FOURCC is returned verbatim, and anything else
     * yields {@code null} (best-effort — {@code describe()} skips a null
     * codec).</p>
     */
    private static String videoCodecName(byte[] g) {
        String fourcc = printableFourcc(g);
        if (fourcc == null) return null;
        switch (fourcc.toUpperCase(java.util.Locale.ROOT)) {
            case "H264": case "AVC1": case "X264":  return "H.264";
            case "HEVC": case "HVC1": case "HEV1":  return "HEVC";
            case "VP80": case "VP08":               return "VP8";
            case "VP90": case "VP09":               return "VP9";
            case "AV01":                            return "AV1";
            case "MP4V": case "MPG4": case "M4S2":
            case "MP4S":                            return "MPEG-4";
            case "MPG2": case "MP2V":               return "MPEG-2";
            case "MPG1":                            return "MPEG-1";
            case "WMV1": case "WMV2": case "WMV3":  return "WMV";
            case "MJPG":                            return "Motion JPEG";
            default:                                return fourcc;
        }
    }

    /**
     * Maps an audio {@code MF_MT_SUBTYPE} GUID to a short codec name.
     *
     * <p>Most audio subtypes are derived from a {@code WAVE_FORMAT_*} tag and
     * have the form {@code {XXXXXXXX-0000-0010-8000-00AA00389B71}}, where the
     * leading 32 bits are that tag. When the GUID matches this pattern the tag
     * is mapped to a name; otherwise (or for an unmapped tag) a printable
     * FOURCC of the leading four bytes is returned, falling back to {@code null}
     * (best-effort — {@code describe()} skips a null codec).</p>
     */
    private static String audioCodecName(byte[] g) {
        boolean mfPattern =
                g[4] == 0x00 && g[5] == 0x00 && g[6] == 0x10 && g[7] == 0x00
                        && (g[8] & 0xFF) == 0x80 && g[9] == 0x00 && g[10] == 0x00
                        && (g[11] & 0xFF) == 0xAA && g[12] == 0x00 && g[13] == 0x38
                        && (g[14] & 0xFF) == 0x9B && (g[15] & 0xFF) == 0x71;
        int tag = (g[0] & 0xFF) | ((g[1] & 0xFF) << 8)
                | ((g[2] & 0xFF) << 16) | ((g[3] & 0xFF) << 24);
        if (mfPattern) {
            switch (tag) {
                case 0x0001: case 0x0003: return "PCM";
                case 0x0050:              return "MP2";
                case 0x0055:              return "MP3";
                case 0x00FF: case 0x1610: return "AAC";
                case 0xF1AC:              return "FLAC";
                case 0x6C61:              return "ALAC";
                case 0x704F:              return "Opus";
                case 0x0160: case 0x0161:
                case 0x0162: case 0x0163: return "WMA";
                case 0x2000: case 0x2001: return "AC3";
                default: break;
            }
        }
        return printableFourcc(g);
    }

    /** Returns the leading four GUID bytes as a trimmed ASCII FOURCC, or null. */
    private static String printableFourcc(byte[] g) {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) {
            int v = g[i] & 0xFF;
            if (v < 0x20 || v > 0x7E) return null;
            c[i] = (char) v;
        }
        String s = new String(c).trim();
        return s.isEmpty() ? null : s;
    }

    // -- Internal: extractFrame pipeline (Source Reader path) -----------------

    /**
     * Decodes a single frame from the source reader at the given time.
     */
    private static DecodedImage<PixelFormat> decodeFrame(Arena arena, Arena temp,
                                                                   MemorySegment reader,
                                                                   long timeMillis,
                                                                   int maxEdge) throws Throwable {
        // Container display rotation, baked into the returned poster so Windows
        // tiles are upright (matching playback / the other backends). Best-effort.
        int qcw = readRotationCw(temp, reader);
        boolean useNV12 = false;
        // Create output media type requesting RGB32
        MemorySegment ppType = temp.allocate(ValueLayout.ADDRESS);
        int hr = (int) H_MF_CREATE_MEDIA_TYPE.invokeExact(ppType);
        check(hr, "MFCreateMediaType");
        MemorySegment mediaType = ppType.get(ValueLayout.ADDRESS, 0);

        try {
            // Set major type = Video
            hr = (int) IMFAttributes_SetGUID.invokeExact(
                    vtable(mediaType, 24), mediaType,
                    MF_MT_MAJOR_TYPE, MFMediaType_Video);
            check(hr, "SetGUID(MF_MT_MAJOR_TYPE)");

            // Try RGB32 first; fall back to NV12 if the codec rejects it
            hr = (int) IMFAttributes_SetGUID.invokeExact(
                    vtable(mediaType, 24), mediaType,
                    MF_MT_SUBTYPE, MFVideoFormat_RGB32);
            check(hr, "SetGUID(MF_MT_SUBTYPE=RGB32)");

            hr = (int) IMFSourceReader_SetCurrentMediaType.invokeExact(
                    vtable(reader, 7), reader,
                    MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                    MemorySegment.NULL,
                    mediaType);
            if (hr == E_INVALIDARG) {
                // Codec doesn't support RGB32 — retry with NV12
                hr = (int) IMFAttributes_SetGUID.invokeExact(
                        vtable(mediaType, 24), mediaType,
                        MF_MT_SUBTYPE, MFVideoFormat_NV12);
                check(hr, "SetGUID(MF_MT_SUBTYPE=NV12)");
                hr = (int) IMFSourceReader_SetCurrentMediaType.invokeExact(
                        vtable(reader, 7), reader,
                        MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                        MemorySegment.NULL,
                        mediaType);
                check(hr, "SetCurrentMediaType(NV12)");
                useNV12 = true;
            } else {
                check(hr, "SetCurrentMediaType(RGB32)");
            }

            // Native poster downscale (Source Reader path): the reader has video
            // processing enabled, so request an output frame that fits maxEdge on
            // its longer edge (never upscaling). Best-effort: if the re-set is
            // rejected, MF leaves the already-active full-size type in place, so
            // the working poster path never regresses. GetCurrentMediaType below
            // reads whatever size actually took effect.
            if (maxEdge > 0) {
                MemorySegment ppCur = temp.allocate(ValueLayout.ADDRESS);
                int chr = (int) IMFSourceReader_GetCurrentMediaType.invokeExact(
                        vtable(reader, 6), reader,
                        MF_SOURCE_READER_FIRST_VIDEO_STREAM, ppCur);
                if (!failed(chr)) {
                    MemorySegment curType = ppCur.get(ValueLayout.ADDRESS, 0);
                    try {
                        MemorySegment pNative = temp.allocate(ValueLayout.JAVA_LONG);
                        int nhr = (int) IMFAttributes_GetUINT64.invokeExact(
                                vtable(curType, 8), curType, MF_MT_FRAME_SIZE, pNative);
                        if (!failed(nhr)) {
                            long target = fitFrameSize(
                                    pNative.get(ValueLayout.JAVA_LONG, 0), maxEdge);
                            if (target != 0) {
                                int shr = (int) IMFAttributes_SetUINT64.invokeExact(
                                        vtable(mediaType, 22), mediaType,
                                        MF_MT_FRAME_SIZE, target);
                                if (!failed(shr)) {
                                    int rhr = (int) IMFSourceReader_SetCurrentMediaType.invokeExact(
                                            vtable(reader, 7), reader,
                                            MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                                            MemorySegment.NULL, mediaType);
                                    if (failed(rhr) && DEBUG)
                                        System.err.println("[MF] SR downscale hint rejected (0x"
                                                + Integer.toHexString(rhr) + "); full size");
                                }
                            }
                        }
                    } finally {
                        release(curType);
                    }
                }
            }
        } finally {
            release(mediaType);
        }

        // Seek to position if timeMillis > 0
        if (timeMillis > 0) {
            // Convert milliseconds to 100-nanosecond units
            long time100ns = timeMillis * 10_000L;
            MemorySegment propvar = temp.allocate(PROPVARIANT_SIZE);
            propvar.set(ValueLayout.JAVA_SHORT, 0, VT_I8);
            propvar.set(ValueLayout.JAVA_LONG, 8, time100ns);
            hr = (int) IMFSourceReader_SetCurrentPosition.invokeExact(
                    vtable(reader, 8), reader,
                    GUID_NULL, propvar);
            check(hr, "SetCurrentPosition");
        }

        // ReadSample loop until we get a sample
        MemorySegment pActualIndex = temp.allocate(ValueLayout.JAVA_INT);
        MemorySegment pFlags = temp.allocate(ValueLayout.JAVA_INT);
        MemorySegment pTimestamp = temp.allocate(ValueLayout.JAVA_LONG);
        MemorySegment ppSample = temp.allocate(ValueLayout.ADDRESS);

        MemorySegment sample = MemorySegment.NULL;
        for (int attempt = 0; attempt < 100; attempt++) {
            ppSample.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            hr = (int) IMFSourceReader_ReadSample.invokeExact(
                    vtable(reader, 9), reader,
                    MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                    0, // dwControlFlags
                    pActualIndex, pFlags, pTimestamp, ppSample);
            check(hr, "ReadSample");

            int flags = pFlags.get(ValueLayout.JAVA_INT, 0);
            if ((flags & MF_SOURCE_READERF_ERROR) != 0)
                throw new DecodeException(
                        "Media Foundation stream error during ReadSample");
            if ((flags & MF_SOURCE_READERF_ENDOFSTREAM) != 0)
                throw new DecodeException(
                        "End of stream reached without a video frame");

            sample = ppSample.get(ValueLayout.ADDRESS, 0);
            if (!MemorySegment.NULL.equals(sample)) break;
            // NULL sample with no error/EOS -- MF stream gap, retry
        }

        if (MemorySegment.NULL.equals(sample))
            throw new DecodeException(
                    "No video frame received after ReadSample attempts");

        // Convert sample to pixel buffer
        MemorySegment buffer = MemorySegment.NULL;
        try {
            MemorySegment ppBuffer = temp.allocate(ValueLayout.ADDRESS);
            hr = (int) IMFSample_ConvertToContiguousBuffer.invokeExact(
                    vtable(sample, 41), sample, ppBuffer);
            check(hr, "ConvertToContiguousBuffer");
            buffer = ppBuffer.get(ValueLayout.ADDRESS, 0);

            // Lock buffer to get raw pixel data
            MemorySegment ppData = temp.allocate(ValueLayout.ADDRESS);
            MemorySegment pMaxLen = temp.allocate(ValueLayout.JAVA_INT);
            MemorySegment pCurLen = temp.allocate(ValueLayout.JAVA_INT);
            hr = (int) IMFMediaBuffer_Lock.invokeExact(
                    vtable(buffer, 3), buffer, ppData, pMaxLen, pCurLen);
            check(hr, "IMFMediaBuffer::Lock");

            try {
                MemorySegment data = ppData.get(ValueLayout.ADDRESS, 0);
                int curLen = pCurLen.get(ValueLayout.JAVA_INT, 0);

                // Get frame dimensions from reader's current media type
                MemorySegment ppCurrentType = temp.allocate(ValueLayout.ADDRESS);
                hr = (int) IMFSourceReader_GetCurrentMediaType.invokeExact(
                        vtable(reader, 6), reader,
                        MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                        ppCurrentType);
                check(hr, "GetCurrentMediaType");
                MemorySegment currentType = ppCurrentType.get(ValueLayout.ADDRESS, 0);

                int width;
                int height;
                try {
                    MemorySegment pFrameSize = temp.allocate(ValueLayout.JAVA_LONG);
                    hr = (int) IMFAttributes_GetUINT64.invokeExact(
                            vtable(currentType, 8), currentType,
                            MF_MT_FRAME_SIZE, pFrameSize);
                    check(hr, "GetUINT64(MF_MT_FRAME_SIZE)");
                    long frameSize = pFrameSize.get(ValueLayout.JAVA_LONG, 0);
                    width = (int) (frameSize >>> 32);
                    height = (int) (frameSize & 0xFFFFFFFFL);
                } finally {
                    release(currentType);
                }

                MemorySegment srcPixels = data.reinterpret(curLen);

                if (useNV12) {
                    // NV12: Y plane (w*h) + interleaved UV plane (w*h/2)
                    int nv12Expected = width * height * 3 / 2;
                    if (curLen < nv12Expected)
                        throw new DecodeException(
                                "NV12 buffer too small: expected " + nv12Expected
                                        + " bytes, got " + curLen);
                    return rotateDecoded(arena,
                            convertNV12toBGRA(srcPixels, width, height, arena), qcw);
                }

                // RGB32 = 4 bytes per pixel
                int stride = width * RGBA_BPP;
                int expectedLen = stride * height;
                if (curLen < expectedLen)
                    throw new DecodeException(
                            "Pixel buffer too small: expected " + expectedLen
                                    + " bytes, got " + curLen);

                long outputSize = (long) stride * height;
                MemorySegment output = arena.allocate(ValueLayout.JAVA_BYTE, outputSize);
                MemorySegment.copy(srcPixels, 0, output, 0, outputSize);

                return rotateDecoded(arena, new DecodedImage<>(output, width, height, stride,
                        PixelFormat.BGRA), qcw);
            } finally {
                // Unlock -- best-effort
                try {
                    int ignored = (int) IMFMediaBuffer_Unlock.invokeExact(
                            vtable(buffer, 4), buffer);
                } catch (Throwable ignored) {}
            }
        } finally {
            release(buffer);
            release(sample);
        }
    }

    // -- Vtable dispatch helpers ---------------------------------------------

    /**
     * Reads the function pointer at vtable index {@code idx} from a COM object.
     */
    private static MemorySegment vtable(MemorySegment comObj, int idx) {
        MemorySegment vtablePtr = comObj.reinterpret(ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, 0);
        long offset = (long) idx * ValueLayout.ADDRESS.byteSize();
        return vtablePtr.reinterpret((long) (idx + 1) * ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, offset);
    }

    /**
     * Calls IUnknown::Release on a non-null COM pointer.
     */
    private static void release(MemorySegment comObj) {
        if (comObj != null && !MemorySegment.NULL.equals(comObj)) {
            try {
                int refCount = (int) IUnknown_Release.invokeExact(vtable(comObj, 2), comObj);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Allocates a null-terminated UTF-16LE wide string (LPCWSTR) in the arena.
     */
    private static MemorySegment wstr(Arena arena, String s) {
        byte[] utf16 = s.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(utf16.length + 2L);
        MemorySegment.copy(utf16, 0, seg, ValueLayout.JAVA_BYTE, 0, utf16.length);
        seg.set(ValueLayout.JAVA_SHORT, utf16.length, (short) 0);
        return seg;
    }

    // -- HRESULT helpers -----------------------------------------------------

    private static boolean failed(int hr) { return hr < 0; }

    private static void check(int hr, String msg) {
        if (failed(hr))
            throw new DecodeException(
                    msg + " failed (HRESULT 0x" + Integer.toHexString(hr) + ")");
    }

    public static void mfShutdown() {
        try { int ignored = (int) H_MF_SHUTDOWN.invokeExact(); } catch (Throwable ignored) {}
    }

    private static void ensureAvailable() {
        if (!AVAILABLE)
            throw new IllegalStateException(
                    "Media Foundation is not available"
                            + (LOAD_ERROR != null ? ": " + LOAD_ERROR : ""));
    }

    // -- Process-wide platform / COM lifecycle --------------------------------
    //
    // The thumbnail pool extracts posters from several threads at once. The old
    // code initialized COM and started the Media Foundation platform on EVERY
    // call and tore them both down again in finally blocks. Whenever all decode
    // threads were briefly idle at the same time the platform refcount hit zero
    // and MF was fully unloaded, only to be spun straight back up on the next
    // poster — wasteful, and a source of intermittent failures when one thread
    // shut the platform down underneath another mid-decode. COM and the MF
    // platform are now brought up once and kept up for the thread / process
    // lifetime instead.

    private static final ThreadLocal<Boolean> COM_READY =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Object PLATFORM_LOCK = new Object();
    private static volatile boolean platformStarted;

    /**
     * Joins the calling thread to the COM multithreaded apartment exactly once.
     * It is intentionally never balanced with {@code CoUninitialize}, so the
     * apartment stays alive for the thread's lifetime (Windows tears it down
     * automatically when the thread exits).
     */
    private static void ensureComReady() {
        if (COM_READY.get()) return;
        Ole32.coInitializeEx();
        COM_READY.set(Boolean.TRUE);
    }

    /**
     * Starts the Media Foundation platform once per process. It is intentionally
     * never shut down here so concurrent extractions can never race a teardown.
     * The public {@link #mfStartup()}/{@link #mfShutdown()} remain available for
     * the video player's own refcounted use; this permanent startup simply pins
     * the platform up underneath them.
     */
    private static void ensurePlatformStarted() throws Throwable {
        if (platformStarted) return;
        synchronized (PLATFORM_LOCK) {
            if (platformStarted) return;
            int hr = (int) H_MF_STARTUP.invokeExact(MF_VERSION, 0);
            check(hr, "MFStartup");
            platformStarted = true;
        }
    }

    // -- Store codec registration ---------------------------------------------

    private static final Object STORE_CODECS_LOCK = new Object();
    private static volatile boolean storeCodecsRegistered;

    /**
     * Discovers hardware and field-of-use restricted video decoder MFTs
     * (e.g. VP9, HEVC, AV1 from the Microsoft Store) and registers them
     * locally in the current process via {@code MFTRegisterLocalByCLSID}.
     * <p>
     * This is required because the Source Reader does not automatically
     * discover Store-distributed codecs in Win32 desktop applications.
     * After calling this method, the Source Reader will find and use them.
     * <p>
     * Safe to call multiple times — only runs the enumeration once.
     */
    public static void registerStoreCodecs() {
        if (storeCodecsRegistered || !AVAILABLE) return;
        // Hold the lock across the whole enumeration so concurrent first-callers
        // block until registration actually COMPLETES, rather than racing ahead to
        // create a Source Reader before the Store MFTs are registered (which used
        // to make the first VP9/HEVC/AV1 poster of a session fail intermittently).
        synchronized (STORE_CODECS_LOCK) {
            if (storeCodecsRegistered) return;
            try (Arena arena = Arena.ofConfined()) {
                ensureComReady();
                ensurePlatformStarted();
                int hr;
                // Enumerate ALL video decoders: sync, async, hardware, field-of-use
                int flags = 0x3F; // MFT_ENUM_FLAG_SYNCMFT | ASYNCMFT | HARDWARE
                                  // | FIELDOFUSE | LOCALMFT | SORTANDFILTER
                MemorySegment ppActivate = arena.allocate(ValueLayout.ADDRESS);
                MemorySegment pCount = arena.allocate(ValueLayout.JAVA_INT);

                hr = (int) H_MFT_ENUM_EX.invokeExact(
                        MFT_CATEGORY_VIDEO_DECODER, flags,
                        MemorySegment.NULL, MemorySegment.NULL,
                        ppActivate, pCount);
                if (failed(hr)) {
                    if (DEBUG) System.err.println("[MF] MFTEnumEx failed: HRESULT=0x"
                            + Integer.toHexString(hr));
                    return;
                }

                int count = pCount.get(ValueLayout.JAVA_INT, 0);
                if (DEBUG) System.out.println("[MF] MFTEnumEx found " + count + " video decoder(s)");
                if (count == 0) return;

                MemorySegment array = ppActivate.get(ValueLayout.ADDRESS, 0)
                        .reinterpret((long) count * ValueLayout.ADDRESS.byteSize());

                int registered = 0;
                for (int i = 0; i < count; i++) {
                    MemorySegment activate = array.getAtIndex(ValueLayout.ADDRESS, i);
                    if (MemorySegment.NULL.equals(activate)) continue;

                    try {
                        // Get the CLSID from the activation object
                        // MFT_TRANSFORM_CLSID_Attribute {6821C42B-65A4-4E82-99BC-9A88205ECD0C}
                        MemorySegment clsid = arena.allocate(16);
                        MemorySegment MFT_TRANSFORM_CLSID = Ole32.guid(arena,
                                0x6821C42B, (short) 0x65A4, (short) 0x4E82,
                                new byte[]{(byte) 0x99, (byte) 0xBC, (byte) 0x9A,
                                           (byte) 0x88, 0x20, 0x5E, (byte) 0xCD, 0x0C});

                        // IMFAttributes::GetGUID -- vtable[10]
                        hr = (int) IMFAttributes_GetGUID.invokeExact(
                                vtable(activate, 10), activate,
                                MFT_TRANSFORM_CLSID, clsid);
                        if (failed(hr)) continue;

                        // Register locally so Source Reader can discover it
                        hr = (int) H_MFT_REGISTER_LOCAL_BY_CLSID.invokeExact(
                                clsid, MFT_CATEGORY_VIDEO_DECODER,
                                MemorySegment.NULL, 0,
                                0, MemorySegment.NULL,
                                0, MemorySegment.NULL);
                        if (!failed(hr)) registered++;
                    } finally {
                        release(activate);
                    }
                }

                // Free the array allocated by MFTEnumEx (CoTaskMemFree)
                // We've already released each IMFActivate above; the array
                // itself was allocated by COM and will be reclaimed at process exit.

                if (DEBUG && registered > 0) {
                    System.out.println("[MF] Registered " + registered
                            + " Store video decoder(s) locally");
                }
            } catch (Throwable t) {
                if (DEBUG) System.err.println("[MF] Failed to register Store codecs: " + t.getMessage());
            } finally {
                // One-shot: even if enumeration failed, don't re-scan on every call.
                storeCodecsRegistered = true;
            }
        }
    }

    // -- Public lifecycle helpers ---------------------------------------------

    public static void mfStartup() {
        ensureAvailable();
        try {
            int hr = (int) H_MF_STARTUP.invokeExact(MF_VERSION, 0);
            if (hr < 0) throw new IllegalStateException("MFStartup failed: 0x" + Integer.toHexString(hr));
        } catch (IllegalStateException e) { throw e; }
        catch (Throwable t) { throw new DecodeException("MFStartup failed", t); }
    }

    // -- Decoder discovery via MFTEnumEx + IMFActivate -------------------------

    /**
     * Finds and activates a video decoder MFT for the given native media type.
     * <p>
     * Uses {@code MFTEnumEx} with all flags (sync, async, hardware, field-of-use)
     * to discover decoders, including Store-distributed codecs like VP9, HEVC, AV1.
     * The first matching decoder is activated via {@code IMFActivate::ActivateObject}.
     *
     * @param temp       arena for temporary allocations
     * @param nativeType the native (compressed) media type from the Source Reader
     * @return the activated {@code IMFTransform}, or {@code MemorySegment.NULL} if none found
     */
    public static MemorySegment activateDecoderForType(Arena temp, MemorySegment nativeType)
            throws Throwable {
        // Get the subtype GUID from the native media type
        MemorySegment subtype = temp.allocate(16);
        int hr = (int) IMFAttributes_GetGUID.invokeExact(
                vtable(nativeType, 10), nativeType, MF_MT_SUBTYPE, subtype);
        if (failed(hr)) return MemorySegment.NULL;

        // Build MFT_REGISTER_TYPE_INFO for input filter: { MFMediaType_Video, subtype }
        // MFT_REGISTER_TYPE_INFO is two GUIDs (32 bytes): majorType + subtype
        MemorySegment inputFilter = temp.allocate(32);
        MemorySegment.copy(MFMediaType_Video, 0, inputFilter, 0, 16);
        MemorySegment.copy(subtype, 0, inputFilter, 16, 16);

        MemorySegment ppActivate = temp.allocate(ValueLayout.ADDRESS);
        MemorySegment pCount = temp.allocate(ValueLayout.JAVA_INT);

        // Enumerate all decoder types: sync, async, hardware, field-of-use
        int flags = 0x3F; // SYNCMFT | ASYNCMFT | HARDWARE | FIELDOFUSE | LOCALMFT | SORTANDFILTER
        hr = (int) H_MFT_ENUM_EX.invokeExact(
                MFT_CATEGORY_VIDEO_DECODER, flags,
                inputFilter, MemorySegment.NULL,
                ppActivate, pCount);
        if (failed(hr)) return MemorySegment.NULL;

        int count = pCount.get(ValueLayout.JAVA_INT, 0);
        if (count == 0) return MemorySegment.NULL;

        MemorySegment array = ppActivate.get(ValueLayout.ADDRESS, 0)
                .reinterpret((long) count * ValueLayout.ADDRESS.byteSize());

        // Try each activation object until one succeeds
        MemorySegment decoder = MemorySegment.NULL;
        for (int i = 0; i < count; i++) {
            MemorySegment activate = array.getAtIndex(ValueLayout.ADDRESS, i);
            if (MemorySegment.NULL.equals(activate)) continue;

            if (MemorySegment.NULL.equals(decoder)) {
                // IMFActivate::ActivateObject(REFIID riid, void** ppv)
                // vtable[33]: IUnknown(3) + IMFAttributes(30) + ActivateObject(0)
                MemorySegment ppDec = temp.allocate(ValueLayout.ADDRESS);
                hr = (int) IUnknown_QueryInterface.invokeExact(
                        vtable(activate, 33), activate,
                        IID_IMFTransform, ppDec);
                if (!failed(hr)) {
                    decoder = ppDec.get(ValueLayout.ADDRESS, 0);
                    int fourcc = subtype.get(ValueLayout.JAVA_INT, 0);
                    byte[] b = new byte[4];
                    for (int j = 0; j < 4; j++) b[j] = (byte) ((fourcc >> (j * 8)) & 0xFF);
                    if (DEBUG) System.out.println("[MF] Activated " + new String(b).trim()
                            + " decoder via MFTEnumEx (index " + i + "/" + count + ")");
                }
            }
            release(activate);
        }

        return decoder;
    }

    // -- NV12 to BGRA converter -----------------------------------------------

    /**
     * Converts NV12 pixel data to BGRA using BT.601 coefficients.
     * <p>
     * NV12 is a semi-planar YUV format: Y plane (width * height bytes)
     * followed by interleaved UV plane (width * height/2 bytes).
     *
     * @param nv12Data NV12 pixel data (Y plane + UV plane)
     * @param width    frame width in pixels
     * @param height   frame height in pixels
     * @param arena    arena for output allocation
     * @return decoded image in BGRA format
     */
    public static DecodedImage<PixelFormat> convertNV12toBGRA(
            MemorySegment nv12Data, int width, int height, Arena arena) {
        int stride = width * 4;
        MemorySegment output = arena.allocate(ValueLayout.JAVA_BYTE, (long) stride * height);
        // Tightly packed NV12: source plane stride == width, no crop.
        convertNV12intoBGRA(nv12Data, width, height, width, height, output);
        return new DecodedImage<>(output, width, height, stride, PixelFormat.BGRA);
    }

    /**
     * Converts NV12 pixel data to BGRA, writing into an existing tightly packed
     * {@code dstWidth*dstHeight*4} output segment (overwritten in place). Used by
     * the streaming {@link FrameStream}, which reuses one BGRA buffer per frame.
     *
     * <p>The source planes use {@code srcStride} bytes per row (the decoder's
     * macroblock-coded, padded stride, e.g. 1920 for a 1918-wide frame) and the
     * Y plane is {@code srcStride * srcCodedHeight} bytes, so the interleaved UV
     * plane begins after it. The output is cropped to {@code dstWidth x
     * dstHeight} (the display size), reading each row at its true padded offset
     * so rows are not sheared.</p>
     */
    private static void convertNV12intoBGRA(
            MemorySegment nv12Data, int srcStride, int srcCodedHeight,
            int dstWidth, int dstHeight, MemorySegment output) {
        int dstStride = dstWidth * 4;
        long uvPlane = (long) srcStride * srcCodedHeight;

        for (int y = 0; y < dstHeight; y++) {
            long yRow = (long) y * srcStride;
            long uvRow = uvPlane + (long) (y / 2) * srcStride;
            long outRow = (long) y * dstStride;
            for (int x = 0; x < dstWidth; x++) {
                int yVal = nv12Data.get(ValueLayout.JAVA_BYTE, yRow + x) & 0xFF;
                // UV plane is subsampled 2x2; same padded stride as Y.
                long uvOffset = uvRow + (x & ~1);
                int u = nv12Data.get(ValueLayout.JAVA_BYTE, uvOffset) & 0xFF;
                int v = nv12Data.get(ValueLayout.JAVA_BYTE, uvOffset + 1) & 0xFF;

                // BT.601 conversion (integer math)
                int c = yVal - 16;
                int d = u - 128;
                int e = v - 128;
                int r = clamp((298 * c + 409 * e + 128) >> 8);
                int g = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                int b = clamp((298 * c + 516 * d + 128) >> 8);

                long off = outRow + (long) x * 4;
                // Write as BGRA for little-endian int to become 0xAABBGGRR
                output.set(ValueLayout.JAVA_BYTE, off, (byte) b);
                output.set(ValueLayout.JAVA_BYTE, off + 1, (byte) g);
                output.set(ValueLayout.JAVA_BYTE, off + 2, (byte) r);
                output.set(ValueLayout.JAVA_BYTE, off + 3, (byte) 0xFF);
            }
        }
    }

    /**
     * Historical alias retained for compatibility.
     *
     * @deprecated use {@link #convertNV12toBGRA(MemorySegment, int, int, Arena)}
     */
    @Deprecated
    public static DecodedImage<PixelFormat> convertNV12toRGBA(
            MemorySegment nv12Data, int width, int height, Arena arena) {
        return convertNV12toBGRA(nv12Data, width, height, arena);
    }

    private static int clamp(int val) {
        return Math.max(0, Math.min(255, val));
    }

    // -- Manual MFT pipeline (fallback when Source Reader topology fails) --------

    /**
     * Extracts a frame using manual MFT pipeline: SourceReader (demuxer) → 
    * H.264 decoder MFT → NV12 → BGRA.
     */
    private static DecodedImage<PixelFormat> extractFrameManualMFTPipeline(
            Arena arena, String path, long timeMillis, int maxEdge) {
        try (Arena temp = Arena.ofConfined()) {
            Ole32.coInitializeEx();
            try {
                int hr = (int) H_MF_STARTUP.invokeExact(MF_VERSION, 0);
                check(hr, "MFStartup");
                try {
                    MemorySegment ppAttrs = temp.allocate(ValueLayout.ADDRESS);
                    hr = (int) H_MF_CREATE_ATTRIBUTES.invokeExact(ppAttrs, 0);
                    check(hr, "MFCreateAttributes");
                    MemorySegment attrs = ppAttrs.get(ValueLayout.ADDRESS, 0);

                    MemorySegment wpath = wstr(temp, path);
                    MemorySegment ppReader = temp.allocate(ValueLayout.ADDRESS);
                    hr = (int) H_MF_CREATE_SOURCE_READER_FROM_URL.invokeExact(wpath, attrs, ppReader);
                    check(hr, "MFCreateSourceReaderFromURL");
                    MemorySegment reader = ppReader.get(ValueLayout.ADDRESS, 0);
                    // Container display rotation baked into the poster (best-effort).
                    int qcw = readRotationCw(temp, reader);

                    try {
                        MemorySegment ppNativeType = temp.allocate(ValueLayout.ADDRESS);
                        hr = (int) IMFSourceReader_GetNativeMediaType.invokeExact(
                                vtable(reader, 5), reader,
                                MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, ppNativeType);
                        check(hr, "GetNativeMediaType");
                        MemorySegment nativeType = ppNativeType.get(ValueLayout.ADDRESS, 0);

                        MemorySegment d3dDevice = MemorySegment.NULL;
                        MemorySegment d3dContext = MemorySegment.NULL;
                        MemorySegment dxgiManager = MemorySegment.NULL;
                        MemorySegment decoder = MemorySegment.NULL;
                        try {
                            MemorySegment ppDevice = temp.allocate(ValueLayout.ADDRESS);
                            MemorySegment ppContext = temp.allocate(ValueLayout.ADDRESS);
                            hr = (int) H_D3D11_CREATE_DEVICE.invokeExact(
                                    MemorySegment.NULL, 1, MemorySegment.NULL, 0x820,
                                    MemorySegment.NULL, 0, 7, ppDevice, MemorySegment.NULL, ppContext);
                            if (failed(hr)) {
                                hr = (int) H_D3D11_CREATE_DEVICE.invokeExact(
                                        MemorySegment.NULL, 5, MemorySegment.NULL, 0x820,
                                        MemorySegment.NULL, 0, 7, ppDevice, MemorySegment.NULL, ppContext);
                            }
                            check(hr, "D3D11CreateDevice");
                            d3dDevice = ppDevice.get(ValueLayout.ADDRESS, 0);
                            d3dContext = ppContext.get(ValueLayout.ADDRESS, 0);

                            MemorySegment ppMT = temp.allocate(ValueLayout.ADDRESS);
                            hr = (int) IUnknown_QueryInterface.invokeExact(
                                    vtable(d3dDevice, 0), d3dDevice,
                                    IID_ID3D10Multithread, ppMT);
                            if (!failed(hr)) {
                                MemorySegment mt = ppMT.get(ValueLayout.ADDRESS, 0);
                                int ignored = (int) ID3D10Multithread_SetMultithreadProtected.invokeExact(
                                        vtable(mt, 5), mt, 1);
                                release(mt);
                            }

                            MemorySegment pResetToken = temp.allocate(ValueLayout.JAVA_INT);
                            MemorySegment ppManager = temp.allocate(ValueLayout.ADDRESS);
                            hr = (int) H_MF_CREATE_DXGI_DEVICE_MANAGER.invokeExact(pResetToken, ppManager);
                            check(hr, "MFCreateDXGIDeviceManager");
                            dxgiManager = ppManager.get(ValueLayout.ADDRESS, 0);
                            hr = (int) IMFDXGIDeviceManager_ResetDevice.invokeExact(
                                    vtable(dxgiManager, 7), dxgiManager,
                                    d3dDevice, pResetToken.get(ValueLayout.JAVA_INT, 0));
                            check(hr, "IMFDXGIDeviceManager::ResetDevice");

                            // Find a decoder for the stream's native subtype via MFTEnumEx.
                            // This handles H.264, VP9, HEVC, AV1, etc. — whatever codec
                            // is installed, including Store-distributed (field-of-use) MFTs.
                            decoder = activateDecoderForType(temp, nativeType);
                            if (MemorySegment.NULL.equals(decoder)) {
                                // Fall back to hardcoded H.264 decoder
                                MemorySegment ppDec = temp.allocate(ValueLayout.ADDRESS);
                                hr = (int) Ole32.coCreateInstance(
                                        CLSID_CMSH264DecoderMFT, 1, IID_IMFTransform, ppDec);
                                check(hr, "CoCreateInstance(H.264 decoder)");
                                decoder = ppDec.get(ValueLayout.ADDRESS, 0);
                            }

                            hr = (int) IMFTransform_ProcessMessage.invokeExact(
                                    vtable(decoder, 23), decoder,
                                    MFT_MESSAGE_SET_D3D_MANAGER, dxgiManager.address());
                            check(hr, "MFT_MESSAGE_SET_D3D_MANAGER");

                            hr = (int) IMFTransform_SetInputType.invokeExact(
                                    vtable(decoder, 15), decoder, 0, nativeType, 0);
                            check(hr, "Decoder SetInputType");

                            MemorySegment ppDecOut = temp.allocate(ValueLayout.ADDRESS);
                            hr = (int) IMFTransform_GetOutputAvailableType.invokeExact(
                                    vtable(decoder, 14), decoder, 0, 0, ppDecOut);
                            check(hr, "Decoder GetOutputAvailableType");
                            MemorySegment decoderOutputType = ppDecOut.get(ValueLayout.ADDRESS, 0);

                            MemorySegment pFrameSize = temp.allocate(ValueLayout.JAVA_LONG);
                            // Native poster downscale: ask the decoder to emit a
                            // frame that fits maxEdge on its longer edge (never
                            // upscaling). The stock MS H.264 MFT on some boxes
                            // rejects a non-native output size, so a failed
                            // SetOutputType MUST fall back to a fresh full-size
                            // type — the working poster path never regresses.
                            boolean hinted = false;
                            if (maxEdge > 0) {
                                int ghr = (int) IMFAttributes_GetUINT64.invokeExact(
                                        vtable(decoderOutputType, 8), decoderOutputType,
                                        MF_MT_FRAME_SIZE, pFrameSize);
                                if (!failed(ghr)) {
                                    long target = fitFrameSize(
                                            pFrameSize.get(ValueLayout.JAVA_LONG, 0), maxEdge);
                                    if (target != 0) {
                                        int shr = (int) IMFAttributes_SetUINT64.invokeExact(
                                                vtable(decoderOutputType, 22), decoderOutputType,
                                                MF_MT_FRAME_SIZE, target);
                                        hinted = !failed(shr);
                                    }
                                }
                            }

                            hr = (int) IMFTransform_SetOutputType.invokeExact(
                                    vtable(decoder, 16), decoder, 0, decoderOutputType, 0);
                            if (failed(hr) && hinted) {
                                if (DEBUG) System.err.println("[MF] downscale hint rejected (0x"
                                        + Integer.toHexString(hr) + "); decoding full size");
                                release(decoderOutputType);
                                MemorySegment ppFull = temp.allocate(ValueLayout.ADDRESS);
                                hr = (int) IMFTransform_GetOutputAvailableType.invokeExact(
                                        vtable(decoder, 14), decoder, 0, 0, ppFull);
                                check(hr, "Decoder GetOutputAvailableType (full-size fallback)");
                                decoderOutputType = ppFull.get(ValueLayout.ADDRESS, 0);
                                hr = (int) IMFTransform_SetOutputType.invokeExact(
                                        vtable(decoder, 16), decoder, 0, decoderOutputType, 0);
                            }
                            check(hr, "Decoder SetOutputType");

                            hr = (int) IMFAttributes_GetUINT64.invokeExact(
                                    vtable(decoderOutputType, 8), decoderOutputType,
                                    MF_MT_FRAME_SIZE, pFrameSize);
                            check(hr, "GetUINT64(MF_MT_FRAME_SIZE)");
                            long frameSize = pFrameSize.get(ValueLayout.JAVA_LONG, 0);
                            int width = (int) (frameSize >>> 32);
                            int height = (int) (frameSize & 0xFFFFFFFFL);

                            release(decoderOutputType);
                            release(nativeType);
                            nativeType = MemorySegment.NULL;

                            MemorySegment pStreamInfo = temp.allocate(12);
                            hr = (int) IMFTransform_GetOutputStreamInfo.invokeExact(
                                    vtable(decoder, 7), decoder, 0, pStreamInfo);
                            boolean decoderProvidesSamples = !failed(hr)
                                    && (pStreamInfo.get(ValueLayout.JAVA_INT, 0)
                                        & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) != 0;
                            int outputBufSize = pStreamInfo.get(ValueLayout.JAVA_INT, 4);

                            hr = (int) IMFTransform_ProcessMessage.invokeExact(
                                    vtable(decoder, 23), decoder,
                                    MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0L);
                            hr = (int) IMFTransform_ProcessMessage.invokeExact(
                                    vtable(decoder, 23), decoder,
                                    MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0L);

                            if (timeMillis > 0) {
                                long time100ns = timeMillis * 10_000L;
                                MemorySegment propvar = temp.allocate(PROPVARIANT_SIZE);
                                propvar.set(ValueLayout.JAVA_SHORT, 0, VT_I8);
                                propvar.set(ValueLayout.JAVA_LONG, 8, time100ns);
                                int hrSeek = (int) IMFSourceReader_SetCurrentPosition.invokeExact(
                                        vtable(reader, 8), reader,
                                        GUID_NULL, propvar);
                            }

                            MemorySegment pFlags = temp.allocate(ValueLayout.JAVA_INT);
                            MemorySegment pTimestamp = temp.allocate(ValueLayout.JAVA_LONG);
                            MemorySegment ppSample = temp.allocate(ValueLayout.ADDRESS);
                            MemorySegment pActualIndex = temp.allocate(ValueLayout.JAVA_INT);

                            MemorySegment decodedSample = MemorySegment.NULL;
                            boolean drained = false;
                            for (int attempt = 0; attempt < 200 && MemorySegment.NULL.equals(decodedSample); attempt++) {
                                if (!drained) {
                                    ppSample.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                                    hr = (int) IMFSourceReader_ReadSample.invokeExact(
                                            vtable(reader, 9), reader,
                                            MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0,
                                            pActualIndex, pFlags, pTimestamp, ppSample);
                                    check(hr, "ReadSample");

                                    int flags = pFlags.get(ValueLayout.JAVA_INT, 0);
                                    if ((flags & MF_SOURCE_READERF_ERROR) != 0)
                                        throw new DecodeException("Stream error during ReadSample");

                                    MemorySegment compressedSample = ppSample.get(ValueLayout.ADDRESS, 0);
                                    if (!MemorySegment.NULL.equals(compressedSample)) {
                                        try {
                                            hr = (int) IMFTransform_ProcessInput.invokeExact(
                                                    vtable(decoder, 24), decoder,
                                                    0, compressedSample, 0);
                                        } finally {
                                            release(compressedSample);
                                        }
                                    }

                                    if ((flags & MF_SOURCE_READERF_ENDOFSTREAM) != 0) {
                                        drained = true;
                                        hr = (int) IMFTransform_ProcessMessage.invokeExact(
                                                vtable(decoder, 23), decoder,
                                                MFT_MESSAGE_COMMAND_DRAIN, 0L);
                                    }
                                }

                                MemorySegment outputBuf = temp.allocate(32);
                                outputBuf.set(ValueLayout.JAVA_INT, 0, 0);
                                outputBuf.set(ValueLayout.JAVA_INT, 16, 0);
                                outputBuf.set(ValueLayout.ADDRESS, 24, MemorySegment.NULL);

                                MemorySegment callerSample = MemorySegment.NULL;
                                if (decoderProvidesSamples) {
                                    outputBuf.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
                                } else {
                                    int bufSz = (outputBufSize > 0) ? outputBufSize : width * height * 3 / 2;
                                    MemorySegment ppOS = temp.allocate(ValueLayout.ADDRESS);
                                    int hrS = (int) H_MF_CREATE_SAMPLE.invokeExact(ppOS);
                                    check(hrS, "MFCreateSample");
                                    callerSample = ppOS.get(ValueLayout.ADDRESS, 0);
                                    MemorySegment ppBf = temp.allocate(ValueLayout.ADDRESS);
                                    hrS = (int) H_MF_CREATE_MEMORY_BUFFER.invokeExact(bufSz, ppBf);
                                    check(hrS, "MFCreateMemoryBuffer");
                                    MemorySegment memBuf = ppBf.get(ValueLayout.ADDRESS, 0);
                                    hrS = (int) IMFSample_AddBuffer.invokeExact(
                                            vtable(callerSample, 42), callerSample, memBuf);
                                    check(hrS, "IMFSample::AddBuffer");
                                    release(memBuf);
                                    outputBuf.set(ValueLayout.ADDRESS, 8, callerSample);
                                }

                                MemorySegment pdwStatus = temp.allocate(ValueLayout.JAVA_INT);
                                hr = (int) IMFTransform_ProcessOutput.invokeExact(
                                        vtable(decoder, 25), decoder,
                                        0, 1, outputBuf, pdwStatus);

                                if (hr == MF_E_TRANSFORM_TYPE_NOT_SET || hr == MF_E_TRANSFORM_STREAM_CHANGE) {
                                    if (!MemorySegment.NULL.equals(callerSample))
                                        release(callerSample);
                                    MemorySegment ppNewOut = temp.allocate(ValueLayout.ADDRESS);
                                    int hrNew = (int) IMFTransform_GetOutputAvailableType.invokeExact(
                                            vtable(decoder, 14), decoder, 0, 0, ppNewOut);
                                    check(hrNew, "GetOutputAvailableType after stream change");
                                    MemorySegment newOutType = ppNewOut.get(ValueLayout.ADDRESS, 0);
                                    hrNew = (int) IMFTransform_SetOutputType.invokeExact(
                                            vtable(decoder, 16), decoder, 0, newOutType, 0);
                                    check(hrNew, "Re-SetOutputType");
                                    MemorySegment pNewSize = temp.allocate(ValueLayout.JAVA_LONG);
                                    hrNew = (int) IMFAttributes_GetUINT64.invokeExact(
                                            vtable(newOutType, 8), newOutType,
                                            MF_MT_FRAME_SIZE, pNewSize);
                                    if (!failed(hrNew)) {
                                        long newSize = pNewSize.get(ValueLayout.JAVA_LONG, 0);
                                        width = (int) (newSize >>> 32);
                                        height = (int) (newSize & 0xFFFFFFFFL);
                                    }
                                    MemorySegment pNewSI = temp.allocate(12);
                                    hrNew = (int) IMFTransform_GetOutputStreamInfo.invokeExact(
                                            vtable(decoder, 7), decoder, 0, pNewSI);
                                    if (!failed(hrNew)) {
                                        decoderProvidesSamples = (pNewSI.get(ValueLayout.JAVA_INT, 0)
                                                & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) != 0;
                                        outputBufSize = pNewSI.get(ValueLayout.JAVA_INT, 4);
                                    }
                                    release(newOutType);
                                    continue;
                                }

                                if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) {
                                    if (!MemorySegment.NULL.equals(callerSample))
                                        release(callerSample);
                                    if (drained)
                                        throw new DecodeException("No frame decoded before end of stream");
                                    continue;
                                }
                                check(hr, "Decoder ProcessOutput");

                                decodedSample = outputBuf.get(ValueLayout.ADDRESS, 8);
                                if (!MemorySegment.NULL.equals(callerSample)
                                        && (MemorySegment.NULL.equals(decodedSample)
                                            || !callerSample.equals(decodedSample))) {
                                    release(callerSample);
                                }
                            }

                            if (MemorySegment.NULL.equals(decodedSample))
                                throw new DecodeException("No decoded frame after ReadSample attempts");

                            try {
                                MemorySegment ppPixelBuf = temp.allocate(ValueLayout.ADDRESS);
                                hr = (int) IMFSample_ConvertToContiguousBuffer.invokeExact(
                                        vtable(decodedSample, 41),
                                        decodedSample, ppPixelBuf);
                                check(hr, "ConvertToContiguousBuffer");
                                MemorySegment pixelBuf = ppPixelBuf.get(ValueLayout.ADDRESS, 0);

                                MemorySegment ppData = temp.allocate(ValueLayout.ADDRESS);
                                MemorySegment pMaxLen = temp.allocate(ValueLayout.JAVA_INT);
                                MemorySegment pCurLen = temp.allocate(ValueLayout.JAVA_INT);
                                
                                // Retry lock on transient failures (e.g., 0x887a0005 = MF_E_INVALIDREQUEST)
                                for (int lockRetry = 0; lockRetry < 3; lockRetry++) {
                                    hr = (int) IMFMediaBuffer_Lock.invokeExact(
                                            vtable(pixelBuf, 3), pixelBuf,
                                            ppData, pMaxLen, pCurLen);
                                    if (!failed(hr)) break;
                                    if (lockRetry < 2) {
                                        // Wait briefly and retry
                                        try { Thread.sleep(10); } catch (InterruptedException ie) { break; }
                                    }
                                }
                                check(hr, "IMFMediaBuffer::Lock");

                                try {
                                    MemorySegment data = ppData.get(ValueLayout.ADDRESS, 0);
                                    int curLen = pCurLen.get(ValueLayout.JAVA_INT, 0);

                                    int yPlaneSize = width * height;
                                    int expectedLen = yPlaneSize + (yPlaneSize / 2);
                                    if (curLen < expectedLen)
                                        throw new DecodeException(
                                                "NV12 buffer too small: " + curLen + " < " + expectedLen);

                                    return rotateDecoded(arena,
                                            convertNV12toBGRA(data.reinterpret(curLen), width, height, arena),
                                            qcw);
                                } finally {
                                    try {
                                        int ignored = (int) IMFMediaBuffer_Unlock.invokeExact(
                                                vtable(pixelBuf, 4), pixelBuf);
                                    } catch (Throwable t) { }
                                    release(pixelBuf);
                                }
                            } finally {
                                release(decodedSample);
                            }
                        } finally {
                            if (nativeType != null && !MemorySegment.NULL.equals(nativeType))
                                release(nativeType);
                            release(decoder);
                            release(dxgiManager);
                            release(d3dContext);
                            release(d3dDevice);
                        }
                    } finally {
                        release(reader);
                    }
                } finally {
                    mfShutdown();
                }
            } finally {
                Ole32.coUninitialize();
            }
        } catch (IllegalStateException | DecodeException e) {
            throw e;
        } catch (Throwable t) {
            throw new DecodeException("extractFrame (manual MFT pipeline) failed: " + path, t);
        }
    }
}

package io.github.ghosthack.pdfmedia.internal;

import io.github.ghosthack.pdfmedia.PdfArchiveException;
import io.github.ghosthack.pdfmedia.PdfEntry;
import io.github.ghosthack.pdfmedia.PdfEntry.Kind;
import io.github.ghosthack.pdfmedia.PdfEntry.Origin;
import io.github.ghosthack.pdfmedia.PdfFilter;
import io.github.ghosthack.pdfmedia.PdfFilter.Decoder;
import io.github.ghosthack.pdfmedia.PdfOpenOptions;
import io.github.ghosthack.pdfmedia.PdfMrcComposite;
import io.github.ghosthack.pdfmedia.PdfRasterDescriptor;
import io.github.ghosthack.pdfmedia.PdfTransform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.util.Matrix;

/**
 * Builds the archive-like index without rendering pages. Page content is interpreted only far
 * enough for PDFBox to surface inline image operators and referenced image XObjects.
 */
public final class PdfMediaIndexer {
    private static final COSName AF = name("AF");
    private static final COSName BITS = name("B");
    private static final COSName CHANNELS = name("C");
    private static final COSName ENCODING = name("E");
    private static final COSName EF = name("EF");
    private static final COSName FILESPEC = name("Filespec");
    private static final COSName FILE_ATTACHMENT = name("FileAttachment");
    private static final COSName MOVIE = name("Movie");
    private static final COSName PARAMS = name("Params");
    private static final COSName RENDITION = name("Rendition");
    private static final COSName RICH_MEDIA = name("RichMedia");
    private static final COSName SAMPLE_RATE = name("R");
    private static final COSName SCREEN = name("Screen");
    private static final COSName SOUND = name("Sound");
    private static final COSName THREE_D = name("3D");
    private static final COSName THREE_D_DATA = name("3DD");
    private static final COSName UF = name("UF");
    private static final COSName INLINE_FILTER = name("F");
    private static final COSName INLINE_DECODE_PARMS = name("DP");
    private static final COSName INLINE_COLOR_SPACE = name("CS");
    private static final COSName INLINE_DECODE = name("D");
    private static final COSName INLINE_IMAGE_MASK = name("IM");
    private static final COSName INLINE_INTERPOLATE = name("I");
    private static final COSName JBIG2_GLOBALS = name("JBIG2Globals");

    private final PDDocument document;
    private final PdfOpenOptions options;
    private final List<Candidate> candidates = new ArrayList<>();
    private final IdentityHashMap<COSStream, Candidate> streamCandidates = new IdentityHashMap<>();
    private final IdentityHashMap<COSDictionary, ImageCandidate> xobjectImages =
            new IdentityHashMap<>();
    private final Map<String, ImageCandidate> inlineImages = new LinkedHashMap<>();
    private final List<MrcCandidate> mrcCandidates = new ArrayList<>();
    private int visitedObjects;

    private PdfMediaIndexer(PDDocument document, PdfOpenOptions options) {
        this.document = document;
        this.options = options;
    }

    /** Indexes attached files, rich assets, image XObjects, and inline images. */
    public static PdfIndex index(PDDocument document, PdfOpenOptions options) throws IOException {
        PdfMediaIndexer indexer = new PdfMediaIndexer(document, options);
        indexer.collectPageImages();
        indexer.walkReachableObjects();
        return indexer.freeze();
    }

    private void collectPageImages() throws IOException {
        var seenResources = new IdentityHashMap<COSDictionary, Boolean>();
        var seenContentStreams = new IdentityHashMap<COSStream, Boolean>();
        List<ContentWork> childStreams = new ArrayList<>();
        int pageIndex = 0;
        for (PDPage page : document.getPages()) {
            collectResources(page.getResources(), seenResources, childStreams, page);
            collectMrcComposite(page, pageIndex++);
            COSStream thumbnail = asStream(page.getCOSObject().getDictionaryObject(COSName.THUMB));
            if (thumbnail != null) {
                addXObject(new PDImageXObject(new PDStream(thumbnail), page.getResources()));
            }
            collectInlineImages(page);
            collectAnnotationAppearances(
                    page, seenResources, seenContentStreams, childStreams);
        }
        for (ContentWork work : childStreams) {
            collectInlineImages(work.stream);
        }
    }

    private void collectMrcComposite(PDPage page, int pageIndex) throws IOException {
        PDResources resources = page.getResources();
        if (resources == null) {
            return;
        }
        List<ImageDraw> draws = new ArrayList<>();
        List<COSBase> operands = new ArrayList<>();
        Deque<Matrix> savedMatrices = new ArrayDeque<>();
        Matrix current = new Matrix();
        PDFStreamParser parser = new PDFStreamParser(page);
        try {
            Object token;
            while ((token = parser.parseNextToken()) != null) {
                if (token instanceof COSBase operand) {
                    operands.add(operand);
                    continue;
                }
                if (!(token instanceof Operator operator)) {
                    continue;
                }
                switch (operator.getName()) {
                    case OperatorName.SAVE -> savedMatrices.push(current.clone());
                    case OperatorName.RESTORE -> {
                        if (!savedMatrices.isEmpty()) {
                            current = savedMatrices.pop();
                        }
                    }
                    case OperatorName.CONCAT -> {
                        Matrix concatenated = matrix(operands);
                        if (concatenated != null) {
                            current.concatenate(concatenated);
                        }
                    }
                    case OperatorName.DRAW_OBJECT -> {
                        if (operands.size() == 1 && operands.getFirst() instanceof COSName name) {
                            PDXObject object = resources.getXObject(name);
                            if (object instanceof PDImageXObject image) {
                                ImageCandidate candidate = xobjectImages.get(image.getCOSObject());
                                if (candidate != null) {
                                    draws.add(new ImageDraw(candidate, current.clone()));
                                }
                            }
                        }
                    }
                    default -> {
                        // Text and other page operators do not alter the image placement graph.
                    }
                }
                operands.clear();
            }
        } finally {
            parser.close();
        }

        if (draws.size() != 2) {
            return;
        }
        ImageDraw background = draws.get(0);
        ImageDraw foreground = draws.get(1);
        Candidate hardMask = foreground.image.hardMask;
        if (!(hardMask instanceof ImageCandidate mask)
                || background.image.hardMask != null
                || background.image.spec.width != foreground.image.spec.width
                || background.image.spec.height != foreground.image.spec.height
                || mask.spec.bitsPerComponent != 1
                || !sameMatrix(background.matrix, foreground.matrix)
                || !coversPage(background.matrix, page)) {
            return;
        }
        mrcCandidates.add(
                new MrcCandidate(
                        pageIndex,
                        background.image,
                        foreground.image,
                        mask,
                        background.matrix.clone()));
    }

    private static Matrix matrix(List<COSBase> operands) {
        if (operands.size() != 6 || operands.stream().anyMatch(value -> !(value instanceof COSNumber))) {
            return null;
        }
        return new Matrix(
                ((COSNumber) operands.get(0)).floatValue(),
                ((COSNumber) operands.get(1)).floatValue(),
                ((COSNumber) operands.get(2)).floatValue(),
                ((COSNumber) operands.get(3)).floatValue(),
                ((COSNumber) operands.get(4)).floatValue(),
                ((COSNumber) operands.get(5)).floatValue());
    }

    private static boolean sameMatrix(Matrix left, Matrix right) {
        return close(left.getScaleX(), right.getScaleX())
                && close(left.getShearY(), right.getShearY())
                && close(left.getShearX(), right.getShearX())
                && close(left.getScaleY(), right.getScaleY())
                && close(left.getTranslateX(), right.getTranslateX())
                && close(left.getTranslateY(), right.getTranslateY());
    }

    private static boolean coversPage(Matrix matrix, PDPage page) {
        var box = page.getCropBox();
        double area =
                Math.abs(
                        matrix.getScaleX() * matrix.getScaleY()
                                - matrix.getShearX() * matrix.getShearY());
        double areaRatio = area / (box.getWidth() * box.getHeight());
        double centerX =
                matrix.getTranslateX()
                        + (matrix.getScaleX() + matrix.getShearX()) / 2;
        double centerY =
                matrix.getTranslateY()
                        + (matrix.getShearY() + matrix.getScaleY()) / 2;
        double centerToleranceX = box.getWidth() * 0.02;
        double centerToleranceY = box.getHeight() * 0.02;
        return areaRatio >= 0.98
                && areaRatio <= 1.10
                && Math.abs(
                                centerX - (box.getLowerLeftX() + box.getWidth() / 2))
                        <= centerToleranceX
                && Math.abs(
                                centerY - (box.getLowerLeftY() + box.getHeight() / 2))
                        <= centerToleranceY;
    }

    private static boolean close(double left, double right) {
        double scale = Math.max(1, Math.max(Math.abs(left), Math.abs(right)));
        return Math.abs(left - right) <= scale * 0.0001;
    }

    private void collectResources(
            PDResources resources,
            IdentityHashMap<COSDictionary, Boolean> seenResources,
            List<ContentWork> childStreams,
            PDPage page)
            throws IOException {
        if (resources == null || seenResources.put(resources.getCOSObject(), Boolean.TRUE) != null) {
            return;
        }
        for (COSName objectName : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(objectName);
            if (object instanceof PDImageXObject image) {
                addXObject(image);
            } else if (object instanceof PDFormXObject form) {
                childStreams.add(new ContentWork(form, page));
                collectResources(form.getResources(), seenResources, childStreams, page);
            }
        }
        for (COSName patternName : resources.getPatternNames()) {
            PDAbstractPattern pattern = resources.getPattern(patternName);
            if (pattern instanceof PDTilingPattern tiling) {
                childStreams.add(new ContentWork(tiling, page));
                collectResources(tiling.getResources(), seenResources, childStreams, page);
            }
        }
    }

    private void collectAnnotationAppearances(
            PDPage page,
            IdentityHashMap<COSDictionary, Boolean> seenResources,
            IdentityHashMap<COSStream, Boolean> seenContentStreams,
            List<ContentWork> childStreams)
            throws IOException {
        for (PDAnnotation annotation : page.getAnnotations()) {
            PDAppearanceDictionary appearances = annotation.getAppearance();
            if (appearances == null) {
                continue;
            }
            addAppearance(
                    appearances.getNormalAppearance(),
                    page,
                    seenResources,
                    seenContentStreams,
                    childStreams);
            addAppearance(
                    appearances.getRolloverAppearance(),
                    page,
                    seenResources,
                    seenContentStreams,
                    childStreams);
            addAppearance(
                    appearances.getDownAppearance(),
                    page,
                    seenResources,
                    seenContentStreams,
                    childStreams);
        }
    }

    private void addAppearance(
            PDAppearanceEntry entry,
            PDPage page,
            IdentityHashMap<COSDictionary, Boolean> seenResources,
            IdentityHashMap<COSStream, Boolean> seenContentStreams,
            List<ContentWork> childStreams)
            throws IOException {
        if (entry == null) {
            return;
        }
        if (entry.isStream()) {
            addAppearanceStream(
                    entry.getAppearanceStream(),
                    page,
                    seenResources,
                    seenContentStreams,
                    childStreams);
            return;
        }
        for (PDAppearanceStream stream : entry.getSubDictionary().values()) {
            addAppearanceStream(stream, page, seenResources, seenContentStreams, childStreams);
        }
    }

    private void addAppearanceStream(
            PDAppearanceStream stream,
            PDPage page,
            IdentityHashMap<COSDictionary, Boolean> seenResources,
            IdentityHashMap<COSStream, Boolean> seenContentStreams,
            List<ContentWork> childStreams)
            throws IOException {
        if (stream == null
                || seenContentStreams.put(stream.getCOSObject(), Boolean.TRUE) != null) {
            return;
        }
        childStreams.add(new ContentWork(stream, page));
        collectResources(stream.getResources(), seenResources, childStreams, page);
    }

    private ImageCandidate addXObject(PDImageXObject image) throws IOException {
        COSDictionary identity = image.getCOSObject();
        ImageCandidate existing = xobjectImages.get(identity);
        if (existing != null) {
            existing.origins.add(Origin.IMAGE_XOBJECT);
            return existing;
        }
        COSStream imageStream = (COSStream) identity;
        ImageCandidate candidate =
                new ImageCandidate(
                        Origin.IMAGE_XOBJECT,
                        rasterSpec(identity),
                        imageStream.getLength() >= 0
                                ? OptionalLong.of(imageStream.getLength())
                                : OptionalLong.empty(),
                        output -> {
                            try (InputStream input = imageStream.createRawInputStream()) {
                                input.transferTo(output);
                            }
                        });
        addCandidate(candidate);
        xobjectImages.put(identity, candidate);
        streamCandidates.putIfAbsent((COSStream) identity, candidate);

        COSStream softMask = asStream(identity.getDictionaryObject(COSName.SMASK));
        if (softMask != null) {
            candidate.softMask =
                    addXObject(new PDImageXObject(new PDStream(softMask), null));
        }
        COSStream hardMask = asStream(identity.getDictionaryObject(COSName.MASK));
        if (hardMask != null) {
            candidate.hardMask =
                    addXObject(new PDImageXObject(new PDStream(hardMask), null));
        }
        COSStream globals = findJbig2Globals(identity);
        if (globals != null) {
            candidate.jbig2Globals = addJbig2Globals(globals);
        }
        return candidate;
    }

    private void addInline(COSDictionary parameters, byte[] data) throws IOException {
        String fingerprint = fingerprint(parameters, data);
        ImageCandidate existing = inlineImages.get(fingerprint);
        if (existing != null) {
            existing.origins.add(Origin.INLINE_IMAGE);
            return;
        }
        ImageCandidate candidate =
                new ImageCandidate(
                        Origin.INLINE_IMAGE,
                        rasterSpec(parameters),
                        OptionalLong.empty(),
                        output -> output.write(data));
        addCandidate(candidate);
        inlineImages.put(fingerprint, candidate);
    }

    private void collectInlineImages(PDContentStream contentStream) throws IOException {
        PDFStreamParser parser = new PDFStreamParser(contentStream);
        try {
            Object token;
            while ((token = parser.parseNextToken()) != null) {
                if (token instanceof Operator operator
                        && OperatorName.BEGIN_INLINE_IMAGE.equals(operator.getName())
                        && operator.getImageParameters() != null
                        && operator.getImageData() != null
                        && operator.getImageData().length > 0) {
                    addInline(operator.getImageParameters(), operator.getImageData().clone());
                }
            }
        } finally {
            parser.close();
        }
    }

    private Candidate addJbig2Globals(COSStream stream) throws IOException {
        Candidate existing = streamCandidates.get(stream);
        if (existing != null) {
            existing.origins.add(Origin.JBIG2_GLOBALS);
            return existing;
        }
        StreamCandidate candidate =
                new StreamCandidate(
                        stream,
                        "jbig2-globals-" + (candidates.size() + 1) + ".jb2g",
                        EnumSet.of(Origin.JBIG2_GLOBALS),
                        Optional.of("application/octet-stream"),
                        OptionalLong.empty(),
                        Kind.DECODER_AUXILIARY);
        addCandidate(candidate);
        streamCandidates.put(stream, candidate);
        return candidate;
    }

    /**
     * Walks the reachable COS graph iteratively. This finds direct file specifications that do
     * not live in the document EmbeddedFiles name tree, including associated files and rich
     * annotation assets.
     */
    private void walkReachableObjects() throws IOException {
        Deque<COSBase> pending = new ArrayDeque<>();
        pending.push(document.getDocumentCatalog().getCOSObject());
        var seen = new IdentityHashMap<COSBase, Boolean>();
        while (!pending.isEmpty()) {
            COSBase value = pending.pop();
            if (value == null || seen.put(value, Boolean.TRUE) != null) {
                continue;
            }
            visitedObjects++;
            if (visitedObjects > options.maxObjects()) {
                throw new PdfArchiveException(
                        "PDF object count exceeds budget " + options.maxObjects());
            }
            if (value instanceof COSObject indirect) {
                push(pending, indirect.getObject());
            } else if (value instanceof COSArray array) {
                for (COSBase item : array) {
                    push(pending, item);
                }
            } else if (value instanceof COSDictionary dictionary) {
                inspectDictionary(dictionary);
                for (COSName key : dictionary.keySet()) {
                    push(pending, dictionary.getItem(key));
                }
            }
        }
    }

    private void inspectDictionary(COSDictionary dictionary) throws IOException {
        String type = dictionary.getNameAsString(COSName.TYPE);
        String subtype = dictionary.getNameAsString(COSName.SUBTYPE);

        if (FILESPEC.getName().equals(type) || dictionary.containsKey(EF)) {
            addFileSpecification(dictionary, Origin.EMBEDDED_FILE);
        }
        COSBase associated = dictionary.getItem(AF);
        if (associated != null) {
            collectFileSpecifications(associated, Origin.ASSOCIATED_FILE);
        }

        Origin contextual = annotationOrigin(subtype);
        if (contextual != null) {
            collectFileSpecifications(dictionary, contextual);
        }
        String action = dictionary.getNameAsString(COSName.S);
        if (RENDITION.getName().equals(type) || RENDITION.getName().equals(action)) {
            collectFileSpecifications(dictionary, Origin.SCREEN_RENDITION);
        }

        if (SOUND.getName().equals(subtype) || SOUND.getName().equals(action)) {
            COSStream sound = asStream(dictionary.getDictionaryObject(SOUND));
            if (sound != null) {
                addSoundStream(sound);
            }
        }
        if (THREE_D.getName().equals(subtype)) {
            COSStream model = asStream(dictionary.getDictionaryObject(THREE_D_DATA));
            if (model != null) {
                String modelSubtype = model.getNameAsString(COSName.SUBTYPE);
                String extension =
                        modelSubtype == null ? "bin" : modelSubtype.toLowerCase(Locale.ROOT);
                addDirectStream(
                        model,
                        "model",
                        safeExtension(extension),
                        mimeFromModelSubtype(modelSubtype),
                        Origin.THREE_D);
            }
        }

        if (dictionary instanceof COSStream stream
                && COSName.IMAGE.getName().equals(subtype)
                && !streamCandidates.containsKey(stream)) {
            // A reachable image outside page resources (for example an embedded-file thumbnail).
            // Device color spaces work without a resource dictionary; named color spaces may fail
            // lazily and are reported when that particular entry is opened.
            addXObject(new PDImageXObject(new PDStream(stream), null));
        }
    }

    private void collectFileSpecifications(COSBase root, Origin origin) throws IOException {
        Deque<COSBase> pending = new ArrayDeque<>();
        var seen = new IdentityHashMap<COSBase, Boolean>();
        push(pending, root);
        while (!pending.isEmpty()) {
            COSBase value = pending.pop();
            if (value == null || seen.put(value, Boolean.TRUE) != null) {
                continue;
            }
            if (value instanceof COSObject indirect) {
                push(pending, indirect.getObject());
            } else if (value instanceof COSArray array) {
                for (COSBase item : array) {
                    push(pending, item);
                }
            } else if (value instanceof COSDictionary dictionary) {
                String type = dictionary.getNameAsString(COSName.TYPE);
                if (FILESPEC.getName().equals(type) || dictionary.containsKey(EF)) {
                    addFileSpecification(dictionary, origin);
                    // The embedded stream itself cannot contain another file specification.
                    continue;
                }
                for (COSName key : dictionary.keySet()) {
                    // Page trees and parent pointers turn a local annotation search into a whole
                    // document walk. Rich/file/action payloads never need these backlinks.
                    if (!COSName.PARENT.equals(key) && !COSName.P.equals(key)) {
                        push(pending, dictionary.getItem(key));
                    }
                }
            }
        }
    }

    private void addFileSpecification(COSDictionary fileSpec, Origin origin) throws IOException {
        COSDictionary embedded = asDictionary(fileSpec.getDictionaryObject(EF));
        if (embedded == null) {
            return; // external reference: no bytes physically embedded in this PDF
        }
        String fileName = fileName(fileSpec);
        var unique = new IdentityHashMap<COSStream, Boolean>();
        for (COSName key : List.of(UF, COSName.F, COSName.DOS, COSName.MAC, COSName.UNIX)) {
            COSStream stream = asStream(embedded.getDictionaryObject(key));
            if (stream == null || unique.put(stream, Boolean.TRUE) != null) {
                continue;
            }
            Candidate existing = streamCandidates.get(stream);
            if (existing != null) {
                existing.origins.add(origin);
                continue;
            }
            Optional<String> mediaType = mimeFromStream(stream);
            OptionalLong declaredSize = declaredSize(stream);
            String candidateName =
                    fileName == null || fileName.isBlank()
                            ? syntheticFileName("embedded", mediaType)
                            : fileName;
            StreamCandidate candidate =
                    new StreamCandidate(
                            stream,
                            candidateName,
                            EnumSet.of(origin),
                            mediaType,
                            declaredSize,
                            Kind.EMBEDDED_FILE);
            addCandidate(candidate);
            streamCandidates.put(stream, candidate);
        }
    }

    private void addDirectStream(
            COSStream stream,
            String stem,
            String extension,
            Optional<String> mediaType,
            Origin origin)
            throws IOException {
        Candidate existing = streamCandidates.get(stream);
        if (existing != null) {
            existing.origins.add(origin);
            return;
        }
        String fileName = stem + "-" + (candidates.size() + 1) + "." + extension;
        StreamCandidate candidate =
                new StreamCandidate(
                        stream,
                        fileName,
                        EnumSet.of(origin),
                        mediaType,
                        OptionalLong.empty(),
                        Kind.EMBEDDED_FILE);
        addCandidate(candidate);
        streamCandidates.put(stream, candidate);
    }

    private void addSoundStream(COSStream stream) throws IOException {
        Candidate existing = streamCandidates.get(stream);
        if (existing != null) {
            existing.origins.add(Origin.SOUND);
            return;
        }
        SoundEncoding soundEncoding = SoundEncoding.from(stream);
        if (soundEncoding == null) {
            addDirectStream(
                    stream,
                    "sound",
                    "snd",
                    Optional.of("application/octet-stream"),
                    Origin.SOUND);
            return;
        }
        SoundCandidate candidate =
                new SoundCandidate(
                        stream,
                        "sound-" + (candidates.size() + 1) + ".au",
                        soundEncoding);
        addCandidate(candidate);
        streamCandidates.put(stream, candidate);
    }

    private void addCandidate(Candidate candidate) throws IOException {
        if (candidates.size() >= options.maxEntries()) {
            throw new PdfArchiveException(
                    "PDF media entry count exceeds budget " + options.maxEntries());
        }
        if (candidate instanceof ImageCandidate image) {
            enforceImageBudget(image.spec.width, image.spec.height);
        }
        candidates.add(candidate);
    }

    private PdfIndex freeze() throws IOException {
        List<PdfEntry> entries = new ArrayList<>(candidates.size());
        List<EntryContent> contents = new ArrayList<>(candidates.size());
        IdentityHashMap<Candidate, Integer> candidateIndexes = new IdentityHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            candidateIndexes.put(candidates.get(index), index);
        }
        long totalDeclared = 0;
        int imageNumber = 0;
        int inlineNumber = 0;
        for (Candidate candidate : candidates) {
            int index = entries.size();
            String entryName = candidate.name;
            Optional<PdfRasterDescriptor> raster = Optional.empty();
            if (candidate instanceof ImageCandidate image) {
                boolean inline = candidate.origins.contains(Origin.INLINE_IMAGE);
                int number = inline ? ++inlineNumber : ++imageNumber;
                entryName =
                        (inline ? "inline-image-" : "image-")
                                + String.format(Locale.ROOT, "%04d", number)
                                + ".pdfimg";
                raster = Optional.of(image.descriptor(candidateIndexes));
            }
            if (candidate.declaredSize.isPresent()) {
                long declared = candidate.declaredSize.getAsLong();
                if (declared > options.maxEntryBytes()) {
                    throw new PdfArchiveException(
                            "entry "
                                    + entryName
                                    + " declares "
                                    + declared
                                    + " bytes; per-entry budget is "
                                    + options.maxEntryBytes());
                }
                try {
                    totalDeclared = Math.addExact(totalDeclared, declared);
                } catch (ArithmeticException e) {
                    throw new PdfArchiveException("declared embedded-file sizes overflow", e);
                }
                if (totalDeclared > options.maxTotalDeclaredBytes()) {
                    throw new PdfArchiveException(
                            "declared embedded-file bytes exceed budget "
                                    + options.maxTotalDeclaredBytes());
                }
            }
            entries.add(
                    new PdfEntry(
                            index,
                            entryName,
                            candidate.kind,
                            candidate.origins,
                            candidate.mediaType,
                            candidate.declaredSize,
                            raster));
            contents.add(candidate.content());
        }
        List<PdfMrcComposite> mrcComposites = new ArrayList<>(mrcCandidates.size());
        for (MrcCandidate composite : mrcCandidates) {
            Integer backgroundIndex = candidateIndexes.get(composite.background);
            Integer foregroundIndex = candidateIndexes.get(composite.foreground);
            Integer maskIndex = candidateIndexes.get(composite.mask);
            if (backgroundIndex == null || foregroundIndex == null || maskIndex == null) {
                continue;
            }
            Matrix placement = composite.placement;
            mrcComposites.add(
                    new PdfMrcComposite(
                            composite.pageIndex,
                            composite.mask.spec.width,
                            composite.mask.spec.height,
                            backgroundIndex,
                            foregroundIndex,
                            maskIndex,
                            new PdfTransform(
                                    placement.getScaleX(),
                                    placement.getShearY(),
                                    placement.getShearX(),
                                    placement.getScaleY(),
                                    placement.getTranslateX(),
                                    placement.getTranslateY())));
        }
        return new PdfIndex(entries, contents, mrcComposites);
    }

    private void enforceImageBudget(int width, int height) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new PdfArchiveException("PDF image has invalid dimensions " + width + "x" + height);
        }
        long pixels;
        try {
            pixels = Math.multiplyExact((long) width, (long) height);
        } catch (ArithmeticException e) {
            throw new PdfArchiveException("PDF image dimensions overflow", e);
        }
        if (pixels > options.maxImagePixels()) {
            throw new PdfArchiveException(
                    "PDF image "
                            + width
                            + "x"
                            + height
                            + " exceeds pixel budget "
                            + options.maxImagePixels());
        }
    }

    private static RasterSpec rasterSpec(COSDictionary dictionary) {
        boolean stencil =
                dictionary.getBoolean(COSName.IMAGE_MASK, INLINE_IMAGE_MASK, false);
        int width = dictionary.getInt(COSName.WIDTH, name("W"), -1);
        int height = dictionary.getInt(COSName.HEIGHT, name("H"), -1);
        int bits =
                stencil
                        ? 1
                        : dictionary.getInt(
                                COSName.BITS_PER_COMPONENT, name("BPC"), 0);

        List<String> rawFilters = rawFilterNames(dictionary);
        List<PdfFilter> filters = new ArrayList<>(rawFilters.size());
        COSBase decodeParms =
                firstDictionaryObject(dictionary, COSName.DECODE_PARMS, INLINE_DECODE_PARMS);
        for (int index = 0; index < rawFilters.size(); index++) {
            COSDictionary parameters = filterParameters(decodeParms, index);
            filters.add(
                    new PdfFilter(
                            canonicalFilterName(rawFilters.get(index)),
                            decoderFor(rawFilters.get(index)),
                            simpleDictionary(parameters, JBIG2_GLOBALS)));
        }

        COSBase colorSpace =
                firstDictionaryObject(dictionary, COSName.COLORSPACE, INLINE_COLOR_SPACE);
        COSArray decode =
                asArray(firstDictionaryObject(dictionary, COSName.DECODE, INLINE_DECODE));
        COSArray mask = asArray(dictionary.getDictionaryObject(COSName.MASK));
        return new RasterSpec(
                width,
                height,
                bits,
                stencil,
                dictionary.getBoolean(COSName.INTERPOLATE, INLINE_INTERPOLATE, false),
                filters,
                optionalSummary(colorSpace),
                numericDoubles(decode),
                numericLongs(mask));
    }

    private static List<String> rawFilterNames(COSDictionary dictionary) {
        COSBase value = firstDictionaryObject(dictionary, COSName.FILTER, INLINE_FILTER);
        if (value instanceof COSName filter) {
            return List.of(filter.getName());
        }
        if (value instanceof COSArray filters) {
            List<String> names = new ArrayList<>(filters.size());
            for (COSBase item : filters) {
                COSBase resolved = dereference(item);
                if (resolved instanceof COSName filter) {
                    names.add(filter.getName());
                }
            }
            return List.copyOf(names);
        }
        return List.of();
    }

    private static COSBase firstDictionaryObject(
            COSDictionary dictionary, COSName longName, COSName abbreviation) {
        COSBase value = dictionary.getDictionaryObject(longName);
        return value != null ? value : dictionary.getDictionaryObject(abbreviation);
    }

    private static COSDictionary filterParameters(COSBase decodeParms, int index) {
        COSBase resolved = dereference(decodeParms);
        if (resolved instanceof COSDictionary dictionary) {
            return dictionary;
        }
        if (resolved instanceof COSArray array && index < array.size()) {
            return asDictionary(array.get(index));
        }
        return null;
    }

    private static String canonicalFilterName(String name) {
        return switch (name) {
            case "AHx" -> "ASCIIHexDecode";
            case "A85" -> "ASCII85Decode";
            case "LZW" -> "LZWDecode";
            case "Fl" -> "FlateDecode";
            case "RL" -> "RunLengthDecode";
            case "CCF" -> "CCITTFaxDecode";
            case "DCT" -> "DCTDecode";
            default -> name;
        };
    }

    private static Decoder decoderFor(String filter) {
        return switch (canonicalFilterName(filter)) {
            case "ASCIIHexDecode" -> Decoder.ASCII_HEX;
            case "ASCII85Decode" -> Decoder.ASCII_85;
            case "LZWDecode" -> Decoder.LZW;
            case "FlateDecode" -> Decoder.FLATE;
            case "RunLengthDecode" -> Decoder.RUN_LENGTH;
            case "CCITTFaxDecode" -> Decoder.CCITT_FAX;
            case "JBIG2Decode" -> Decoder.JBIG2;
            case "DCTDecode" -> Decoder.JPEG;
            case "JPXDecode" -> Decoder.JPEG_2000;
            case "Crypt" -> Decoder.CRYPT;
            default -> Decoder.UNKNOWN;
        };
    }

    private static Map<String, Object> simpleDictionary(
            COSDictionary dictionary, COSName excludedKey) {
        if (dictionary == null) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (COSName key : dictionary.keySet()) {
            if (key.equals(excludedKey)) {
                continue;
            }
            Object value = simpleValue(dictionary.getDictionaryObject(key), 0);
            if (value != null) {
                values.put(key.getName(), value);
            }
        }
        return values;
    }

    private static Object simpleValue(COSBase value, int depth) {
        COSBase resolved = dereference(value);
        if (resolved == null || depth >= 8) {
            return null;
        }
        if (resolved instanceof COSBoolean bool) {
            return bool.getValue();
        }
        if (resolved instanceof COSInteger integer) {
            return integer.longValue();
        }
        if (resolved instanceof COSNumber number) {
            return (double) number.floatValue();
        }
        if (resolved instanceof COSName name) {
            return name.getName();
        }
        if (resolved instanceof COSString string) {
            return "hex:" + HexFormat.of().formatHex(string.getBytes());
        }
        if (resolved instanceof COSArray array) {
            List<Object> values = new ArrayList<>(array.size());
            for (COSBase item : array) {
                Object nested = simpleValue(item, depth + 1);
                if (nested != null) {
                    values.add(nested);
                }
            }
            return List.copyOf(values);
        }
        if (resolved instanceof COSDictionary dictionary
                && !(resolved instanceof COSStream)) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (COSName key : dictionary.keySet()) {
                Object nested = simpleValue(dictionary.getDictionaryObject(key), depth + 1);
                if (nested != null) {
                    values.put(key.getName(), nested);
                }
            }
            return Map.copyOf(values);
        }
        return null;
    }

    private static Optional<String> optionalSummary(COSBase value) {
        COSBase resolved = dereference(value);
        if (resolved == null) {
            return Optional.empty();
        }
        if (resolved instanceof COSName name) {
            return Optional.of(
                    switch (name.getName()) {
                        case "G" -> "DeviceGray";
                        case "RGB" -> "DeviceRGB";
                        case "CMYK" -> "DeviceCMYK";
                        case "I" -> "Indexed";
                        default -> name.getName();
                    });
        }
        String summary = resolved.toString();
        return Optional.of(summary.length() <= 512 ? summary : summary.substring(0, 512));
    }

    private static List<Double> numericDoubles(COSArray array) {
        if (array == null) {
            return List.of();
        }
        List<Double> values = new ArrayList<>(array.size());
        for (COSBase item : array) {
            COSBase resolved = dereference(item);
            if (resolved instanceof COSNumber number) {
                values.add((double) number.floatValue());
            }
        }
        return List.copyOf(values);
    }

    private static List<Long> numericLongs(COSArray array) {
        if (array == null) {
            return List.of();
        }
        List<Long> values = new ArrayList<>(array.size());
        for (COSBase item : array) {
            COSBase resolved = dereference(item);
            if (resolved instanceof COSNumber number) {
                values.add(number.longValue());
            }
        }
        return List.copyOf(values);
    }

    private static COSArray asArray(COSBase value) {
        COSBase resolved = dereference(value);
        return resolved instanceof COSArray array ? array : null;
    }

    private static COSStream findJbig2Globals(COSDictionary dictionary) {
        COSBase decodeParms =
                firstDictionaryObject(dictionary, COSName.DECODE_PARMS, INLINE_DECODE_PARMS);
        COSBase resolved = dereference(decodeParms);
        if (resolved instanceof COSDictionary parameters) {
            return asStream(parameters.getDictionaryObject(JBIG2_GLOBALS));
        }
        if (resolved instanceof COSArray array) {
            for (COSBase item : array) {
                COSDictionary parameters = asDictionary(item);
                if (parameters != null) {
                    COSStream globals = asStream(parameters.getDictionaryObject(JBIG2_GLOBALS));
                    if (globals != null) {
                        return globals;
                    }
                }
            }
        }
        return null;
    }

    private static String fingerprint(COSDictionary parameters, byte[] data) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(
                    ByteBuffer.allocate(12)
                            .putInt(parameters.getInt(COSName.WIDTH, name("W"), -1))
                            .putInt(parameters.getInt(COSName.HEIGHT, name("H"), -1))
                            .putInt(
                                    parameters.getInt(
                                            COSName.BITS_PER_COMPONENT, name("BPC"), 0))
                            .array());
            digest.update(parameters.toString().getBytes(StandardCharsets.UTF_8));
            digest.update(data);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static OptionalLong declaredSize(COSStream stream) {
        COSDictionary params = asDictionary(stream.getDictionaryObject(PARAMS));
        if (params == null) {
            return OptionalLong.empty();
        }
        long value = params.getLong(COSName.SIZE, -1);
        return value >= 0 ? OptionalLong.of(value) : OptionalLong.empty();
    }

    private static Optional<String> mimeFromStream(COSStream stream) {
        String value = stream.getNameAsString(COSName.SUBTYPE);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private static Optional<String> mimeFromModelSubtype(String subtype) {
        if (subtype == null) {
            return Optional.empty();
        }
        return switch (subtype.toUpperCase(Locale.ROOT)) {
            case "U3D" -> Optional.of("model/u3d");
            case "PRC" -> Optional.of("model/prc");
            default -> Optional.of("application/octet-stream");
        };
    }

    private String syntheticFileName(String stem, Optional<String> mediaType) {
        return stem
                + "-"
                + (candidates.size() + 1)
                + "."
                + extensionForMime(mediaType.orElse(null));
    }

    private static String extensionForMime(String mediaType) {
        if (mediaType == null) {
            return "bin";
        }
        return switch (mediaType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/tiff" -> "tiff";
            case "image/webp" -> "webp";
            case "audio/mpeg" -> "mp3";
            case "audio/mp4" -> "m4a";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "video/mp4" -> "mp4";
            case "video/quicktime" -> "mov";
            case "video/webm" -> "webm";
            case "model/u3d" -> "u3d";
            case "model/prc" -> "prc";
            default -> {
                int slash = mediaType.lastIndexOf('/');
                yield safeExtension(slash >= 0 ? mediaType.substring(slash + 1) : "bin");
            }
        };
    }

    private static String safeExtension(String candidate) {
        String cleaned = candidate == null ? "" : candidate.replaceAll("[^A-Za-z0-9]+", "");
        return cleaned.isEmpty() || cleaned.length() > 12 ? "bin" : cleaned.toLowerCase(Locale.ROOT);
    }

    private static String fileName(COSDictionary fileSpec) {
        for (COSName key : List.of(UF, COSName.F, COSName.DOS, COSName.MAC, COSName.UNIX)) {
            COSBase value = fileSpec.getDictionaryObject(key);
            if (value instanceof COSString text && !text.getString().isBlank()) {
                return text.getString();
            }
        }
        return null;
    }

    private static Origin annotationOrigin(String subtype) {
        if (subtype == null) {
            return null;
        }
        if (RICH_MEDIA.getName().equals(subtype)) {
            return Origin.RICH_MEDIA;
        }
        if (SCREEN.getName().equals(subtype)) {
            return Origin.SCREEN_RENDITION;
        }
        if (MOVIE.getName().equals(subtype)) {
            return Origin.MOVIE;
        }
        if (SOUND.getName().equals(subtype)) {
            return Origin.SOUND;
        }
        if (THREE_D.getName().equals(subtype)) {
            return Origin.THREE_D;
        }
        if (FILE_ATTACHMENT.getName().equals(subtype)) {
            return Origin.FILE_ATTACHMENT;
        }
        return null;
    }

    private static COSDictionary asDictionary(COSBase value) {
        COSBase resolved = dereference(value);
        return resolved instanceof COSDictionary dictionary ? dictionary : null;
    }

    private static COSStream asStream(COSBase value) {
        COSBase resolved = dereference(value);
        return resolved instanceof COSStream stream ? stream : null;
    }

    private static COSBase dereference(COSBase value) {
        COSBase current = value;
        while (current instanceof COSObject object) {
            current = object.getObject();
        }
        return current;
    }

    private static void push(Deque<COSBase> pending, COSBase value) {
        if (value != null) {
            pending.push(value);
        }
    }

    private static COSName name(String value) {
        return COSName.getPDFName(value);
    }

    private abstract static class Candidate {
        final String name;
        final EnumSet<Origin> origins;
        final Optional<String> mediaType;
        final OptionalLong declaredSize;
        final Kind kind;

        Candidate(
                String name,
                EnumSet<Origin> origins,
                Optional<String> mediaType,
                OptionalLong declaredSize,
                Kind kind) {
            this.name = name;
            this.origins = origins;
            this.mediaType = mediaType;
            this.declaredSize = declaredSize;
            this.kind = kind;
        }

        abstract EntryContent content();
    }

    private static final class StreamCandidate extends Candidate {
        private final COSStream stream;

        StreamCandidate(
                COSStream stream,
                String name,
                EnumSet<Origin> origins,
                Optional<String> mediaType,
                OptionalLong declaredSize,
                Kind kind) {
            super(name, origins, mediaType, declaredSize, kind);
            this.stream = stream;
        }

        @Override
        EntryContent content() {
            return output -> {
                try (InputStream input = stream.createInputStream()) {
                    input.transferTo(output);
                }
            };
        }
    }

    private static final class ImageCandidate extends Candidate {
        private final RasterSpec spec;
        private final EntryContent imageContent;
        private Candidate hardMask;
        private Candidate softMask;
        private Candidate jbig2Globals;

        ImageCandidate(
                Origin origin,
                RasterSpec spec,
                OptionalLong declaredSize,
                EntryContent imageContent) {
            super(
                    "",
                    EnumSet.of(origin),
                    Optional.of("application/vnd.ghosthack.pdf-image-stream"),
                    declaredSize,
                    Kind.RASTER);
            this.spec = spec;
            this.imageContent = imageContent;
        }

        @Override
        EntryContent content() {
            return imageContent;
        }

        PdfRasterDescriptor descriptor(IdentityHashMap<Candidate, Integer> indexes) {
            return new PdfRasterDescriptor(
                    spec.width,
                    spec.height,
                    spec.bitsPerComponent,
                    spec.stencil,
                    spec.interpolate,
                    spec.filters,
                    spec.colorSpace,
                    spec.decode,
                    spec.colorKeyMask,
                    optionalIndex(indexes, hardMask),
                    optionalIndex(indexes, softMask),
                    optionalIndex(indexes, jbig2Globals));
        }

        private static OptionalInt optionalIndex(
                IdentityHashMap<Candidate, Integer> indexes, Candidate candidate) {
            if (candidate == null) {
                return OptionalInt.empty();
            }
            Integer index = indexes.get(candidate);
            return index == null ? OptionalInt.empty() : OptionalInt.of(index);
        }
    }

    private record ImageDraw(ImageCandidate image, Matrix matrix) {}

    private record MrcCandidate(
            int pageIndex,
            ImageCandidate background,
            ImageCandidate foreground,
            ImageCandidate mask,
            Matrix placement) {}

    /**
     * Normalizes the PDF legacy Sound stream into a headered Sun/NeXT AU stream. AU is a useful
     * fit here because its unknown-length sentinel lets extraction remain streaming and its
     * big-endian PCM byte order matches PDF sound samples.
     */
    private static final class SoundCandidate extends Candidate {
        private final COSStream stream;
        private final SoundEncoding encoding;

        SoundCandidate(COSStream stream, String name, SoundEncoding encoding) {
            super(
                    name,
                    EnumSet.of(Origin.SOUND),
                    Optional.of("audio/basic"),
                    OptionalLong.empty(),
                    Kind.EMBEDDED_FILE);
            this.stream = stream;
            this.encoding = encoding;
        }

        @Override
        EntryContent content() {
            return output -> {
                ByteBuffer header = ByteBuffer.allocate(24);
                header.putInt(0x2e736e64); // ".snd"
                header.putInt(24); // data offset
                header.putInt(-1); // unknown streaming data length
                header.putInt(encoding.auEncoding);
                header.putInt(encoding.sampleRate);
                header.putInt(encoding.channels);
                output.write(header.array());
                try (InputStream input = stream.createInputStream()) {
                    byte[] buffer = new byte[8192];
                    int samplePosition = 0;
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        if (encoding.convertUnsignedPcm) {
                            for (int index = 0; index < read; index++) {
                                if (samplePosition == 0) {
                                    buffer[index] ^= (byte) 0x80;
                                }
                                samplePosition++;
                                if (samplePosition == encoding.bytesPerSample) {
                                    samplePosition = 0;
                                }
                            }
                        }
                        output.write(buffer, 0, read);
                    }
                }
            };
        }
    }

    private record SoundEncoding(
            int sampleRate,
            int channels,
            int bytesPerSample,
            int auEncoding,
            boolean convertUnsignedPcm) {

        static SoundEncoding from(COSStream stream) {
            COSBase rateValue = stream.getDictionaryObject(SAMPLE_RATE);
            if (!(rateValue instanceof COSNumber rateNumber)) {
                return null;
            }
            int sampleRate = Math.round(rateNumber.floatValue());
            int channels = stream.getInt(CHANNELS, 1);
            int bits = stream.getInt(BITS, 8);
            String pdfEncoding = stream.getNameAsString(ENCODING, "Raw");
            if (sampleRate <= 0 || channels <= 0) {
                return null;
            }
            if ("muLaw".equalsIgnoreCase(pdfEncoding) && bits == 8) {
                return new SoundEncoding(sampleRate, channels, 1, 1, false);
            }
            if ("ALaw".equalsIgnoreCase(pdfEncoding) && bits == 8) {
                return new SoundEncoding(sampleRate, channels, 1, 27, false);
            }
            int auEncoding =
                    switch (bits) {
                        case 8 -> 2;
                        case 16 -> 3;
                        case 24 -> 4;
                        case 32 -> 5;
                        default -> -1;
                    };
            if (auEncoding < 0
                    || (!"Raw".equalsIgnoreCase(pdfEncoding)
                            && !"Signed".equalsIgnoreCase(pdfEncoding))) {
                return null;
            }
            return new SoundEncoding(
                    sampleRate,
                    channels,
                    bits / 8,
                    auEncoding,
                    "Raw".equalsIgnoreCase(pdfEncoding));
        }
    }

    private record RasterSpec(
            int width,
            int height,
            int bitsPerComponent,
            boolean stencil,
            boolean interpolate,
            List<PdfFilter> filters,
            Optional<String> colorSpace,
            List<Double> decode,
            List<Long> colorKeyMask) {

        RasterSpec {
            filters = List.copyOf(filters);
            colorSpace = Objects.requireNonNull(colorSpace);
            decode = List.copyOf(decode);
            colorKeyMask = List.copyOf(colorKeyMask);
        }
    }

    private record ContentWork(PDContentStream stream, PDPage page) {}
}

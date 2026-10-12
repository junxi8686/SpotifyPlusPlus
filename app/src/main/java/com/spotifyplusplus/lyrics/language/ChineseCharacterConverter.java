package com.spotifyplusplus.lyrics.language;

import com.spotifyplusplus.lyrics.LyricsDocument;
import com.spotifyplusplus.lyrics.LyricsLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Display-time conversion of a chosen lyrics document between Chinese scripts, with the code-point
 * projection that keeps per-character timings aligned.
 *
 * <h2>What this is for</h2>
 *
 * <p>Selection stays exactly as it was: the ranking still scores a document on its own merits, the
 * script component still pays for Simplified-ness, and the engine still prefers a Simplified source
 * when one exists. This class only changes what is <em>drawn</em> when the selected document is
 * Traditional, mixed, or written with compatibility forms and the owner has asked to read Simplified
 * text. It is never consulted by scoring, by {@link DocumentLanguage}, or by the script
 * measurement: converting a document would otherwise feed back into the ranking that chose it.
 *
 * <h2>Why a projection and not a string</h2>
 *
 * <p>Lyrics timing is per character. A phrase conversion is not one-to-one - {@code 頭髮} is two
 * source characters and two target characters, but other entries map two to one or one to two - so a
 * replacement string alone loses which output characters came from which input characters, and a
 * word-by-word highlight would drift after the first phrase. {@link Projection} therefore carries the
 * source-to-display boundary map: {@link Projection#sourceCodePointCount()} is the source length in
 * code points, {@link Projection#mapBoundary(int)} maps a source boundary to a display boundary, and
 * {@link Projection#slice(int, int)} returns the display text for a source range. A renderer slices
 * timings through it instead of assuming equal lengths.
 *
 * <h2>The interface contract</h2>
 *
 * <ul>
 *   <li>{@link #project(LyricsDocument, ChineseCharacterForm)} takes the document that is already
 *       going to be displayed and a direction, and returns a {@link ProjectedDocument}: a
 *       <em>copy</em> whose line texts are the display text, plus one {@link Projection} per line.
 *       The input document is never modified - the same stored document is shared with the cache,
 *       the picker and the next track's ranking.</li>
 *   <li>With {@link ChineseCharacterForm#ORIGINAL} it returns <em>the same document instance</em>,
 *       marked {@link ProjectedDocument#converted} false, before touching any dictionary. That is the
 *       "off" state: the renderer can hand the result straight to today's drawing code and the
 *       rendered output is byte-identical to what it draws now, because nothing was copied and
 *       nothing was consulted. A caller that instead checks the form itself gets the same guarantee;
 *       this early return exists so it does not have to.</li>
 *   <li>Timing alignment is guaranteed in one direction and stated exactly: every source code-point
 *       boundary maps to a display code-point boundary, boundaries never move backwards, and
 *       {@code mapBoundary(0) == 0} while {@code mapBoundary(sourceCodePointCount())} is the display
 *       length. A source character whose text changed may own zero, one or several display
 *       characters, which is why a caller slices ranges and never the single character it started
 *       from.</li>
 *   <li>Nothing here reads the setting or the preferences store: the caller decides the direction.
 *       Nothing here logs, writes, or holds a document beyond the call.</li>
 *   <li>A line that needs no change is returned as the identical string, so a document that is
 *       already Simplified converts to itself; the projection is still present and still exact.</li>
 * </ul>
 *
 * <h2>Data and attribution</h2>
 *
 * <p>The dictionaries under {@code app/src/main/resources/chinese-script/} are OpenCC character and
 * phrase data - package {@code opencc-data}, from the OpenCC project - distributed under the Apache
 * License 2.0 and used here unmodified except that the per-file header comment naming the upstream
 * serialization was replaced with an attribution line, which is the change statement Apache-2.0
 * section 4(b) asks for. Copyright (c) 2020-2021 The nk2028 Project. A copy of the licence travels
 * with the data as {@code LICENSE-APACHE-2.0.txt} in the same directory, byte for byte as it was
 * received; its only URL is the licence's own. The matching, segmentation and
 * ideographic-description handling is adapted from opencc-js (MIT), Copyright (c) 2020-2021 The
 * nk2028 Project; the trie and the projection are this module's own. The Simplified direction needs
 * three files, 50.2 KB in total; the Traditional direction is not bundled, for the reason recorded on
 * {@link ChineseCharacterForm}. The same attribution is carried in the project {@code NOTICE} under
 * "OpenCC Chinese character and phrase data", and the two are kept in step.
 *
 * <p>No network access and no upstream metadata file is carried: the data is read from this module's
 * own resources, exactly as the rest of the app reads its bundled resources.
 *
 * <p>Android-free: safe for unit tests.
 */
public final class ChineseCharacterConverter {
    /** Our own resource directory; the data files are flat, with no upstream metadata beside them. */
    private static final String RESOURCE_DIR = "/chinese-script/";

    private ChineseCharacterConverter() {
    }

    /** True when the bundled dictionaries are present and loaded. */
    public static boolean available() {
        try {
            return Simplified.CONVERSION != null && Common.NORMALIZATION != null;
        } catch (Throwable missing) {
            return false;
        }
    }

    /**
     * Converts one text. The shortcut for a caller that has no timing to preserve; a renderer wants
     * {@link #project(String, ChineseCharacterForm)} or {@link #project(LyricsDocument,
     * ChineseCharacterForm)} so it can slice, not {@code convert}.
     */
    public static String convert(String text, ChineseCharacterForm form) {
        return project(text, form).text;
    }

    /**
     * Converts one text and returns the display text with its source-to-display boundary map.
     *
     * <p>Never null. {@link ChineseCharacterForm#ORIGINAL}, a null form, null text or empty text all
     * return an identity projection of the input, and nothing is loaded from disk on that path.
     */
    public static Projection project(String text, ChineseCharacterForm form) {
        Projection source = Projection.identity(text == null ? "" : text);
        if (form == null || form == ChineseCharacterForm.ORIGINAL || source.text.isEmpty()) {
            return source;
        }
        if (!available()) return source;
        // Compatibility ideographs are normalised first, so a document that uses them converts by the
        // same table as the rest, and the second step reuses the first step's boundaries.
        Projection normalized = source.through(Common.NORMALIZATION);
        return normalized.through(Simplified.CONVERSION);
    }

    /**
     * The one call a render boundary needs: display this document in {@code form}.
     *
     * <p>Returns the same instance untouched when the form is {@link ChineseCharacterForm#ORIGINAL},
     * so the off state cannot change a single drawn character and costs nothing; otherwise a copy
     * whose line texts are display text, with one projection per line for the caller to slice
     * timings through. See the class doc for the full contract.
     */
    public static ProjectedDocument project(LyricsDocument document, ChineseCharacterForm form) {
        if (document == null || form == null || form == ChineseCharacterForm.ORIGINAL
                || !available()) {
            return new ProjectedDocument(document, Collections.<LineProjection>emptyList(), false);
        }
        LyricsDocument copy = LyricsDocument.copyOf(document);
        List<LineProjection> lines = new ArrayList<>();
        boolean changed = false;
        for (int index = 0; index < copy.lines.size(); index++) {
            LyricsLine line = copy.lines.get(index);
            if (line == null) continue;
            String source = line.text == null ? "" : line.text;
            Projection projection = project(source, form);
            if (!projection.text.equals(source)) changed = true;
            line.text = projection.text;
            lines.add(new LineProjection(index, projection));
        }
        return new ProjectedDocument(copy, lines, changed);
    }

    /** A document prepared for display: the copy to draw, and how its lines map back to source. */
    public static final class ProjectedDocument {
        /** The document to display. The input instance when {@link #converted} is false. */
        public final LyricsDocument document;
        /** One entry per non-null line of {@link #document}, in line order. */
        public final List<LineProjection> lines;
        /** False when nothing was converted, in which case {@link #document} is the input instance. */
        public final boolean converted;

        ProjectedDocument(LyricsDocument document, List<LineProjection> lines, boolean converted) {
            this.document = document;
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            this.converted = converted;
        }

        /** The projection for one line, or null when that line was not projected. */
        public Projection projectionFor(int lineIndex) {
            for (LineProjection entry : lines) {
                if (entry.lineIndex == lineIndex) return entry.projection;
            }
            return null;
        }
    }

    /** One line's source-to-display map. */
    public static final class LineProjection {
        /** Index of the line in the document. */
        public final int lineIndex;
        public final Projection projection;

        LineProjection(int lineIndex, Projection projection) {
            this.lineIndex = lineIndex;
            this.projection = projection;
        }
    }

    /**
     * Immutable source-to-display map. All arguments and results use Unicode code points.
     *
     * <p>Boundaries are the display code-point index each source code-point boundary lands on, so a
     * caller holding a timing for source characters {@code [start, end)} draws the display text
     * {@code slice(start, end)}.
     */
    public static final class Projection {
        public final String text;
        private final int[] sourceBoundaries;
        private final int[] targetOffsets;

        private Projection(String text, int[] sourceBoundaries) {
            this.text = text == null ? "" : text;
            this.sourceBoundaries = sourceBoundaries;
            this.targetOffsets = utf16Offsets(this.text);
        }

        public int sourceCodePointCount() {
            return sourceBoundaries.length - 1;
        }

        public int displayCodePointCount() {
            return targetOffsets.length - 1;
        }

        public int mapBoundary(int sourceCp) {
            if (sourceCp < 0 || sourceCp >= sourceBoundaries.length) {
                throw new IndexOutOfBoundsException("Invalid source code-point boundary: " + sourceCp);
            }
            return sourceBoundaries[sourceCp];
        }

        /** The display text for source code points {@code [startCp, endCp)}. */
        public String slice(int startCp, int endCp) {
            if (endCp < startCp) throw new IndexOutOfBoundsException("Reversed source range");
            return text.substring(targetOffsets[mapBoundary(startCp)], targetOffsets[mapBoundary(endCp)]);
        }

        static Projection identity(String text) {
            int count = text.codePointCount(0, text.length());
            int[] boundaries = new int[count + 1];
            for (int i = 0; i <= count; i++) boundaries[i] = i;
            return new Projection(text, boundaries);
        }

        Projection then(String target, int[] nextBoundaries) {
            int[] composed = new int[sourceBoundaries.length];
            for (int i = 0; i < composed.length; i++) composed[i] = nextBoundaries[sourceBoundaries[i]];
            return new Projection(target, composed);
        }

        Projection through(Trie dictionary) {
            int[] input = codePoints(text);
            int[] boundaries = new int[input.length + 1];
            StringBuilder output = new StringBuilder(text.length());
            int outputCp = 0;
            for (int start = 0; start < input.length;) {
                Match match = dictionary.match(input, start, input.length, false);
                int end = match == null ? unmatchedEnd(input, start, input.length) : match.end;
                String replacement = match == null
                        ? new String(input, start, end - start) : match.value;
                int replacementCp = replacement.codePointCount(0, replacement.length());
                for (int i = start; i <= end; i++) {
                    // Equal-length substitutions retain each timed character's ownership; a phrase
                    // that maps to a different length spreads its boundaries proportionally so the
                    // map stays monotonic and every source character lands inside the replacement.
                    boundaries[i] = outputCp
                            + (int) ((long) (i - start) * replacementCp / (end - start));
                }
                output.append(replacement);
                outputCp += replacementCp;
                start = end;
            }
            return then(output.toString(), boundaries);
        }
    }

    /** Normalisation of compatibility ideographs, shared by every direction. */
    private static final class Common {
        static final Trie NORMALIZATION = readGroup("CJK_Compatibility_Ideographs");
    }

    /** Traditional to Simplified: the phrase table first, then the character table. */
    private static final class Simplified {
        static final Trie CONVERSION = readGroup("TSPhrases", "TSCharacters");
    }

    /**
     * Loads one group of dictionaries into a single trie.
     *
     * <p>Loaded in reverse so the first listed dictionary wins a duplicate key, which is OpenCC's own
     * precedence rule. A missing dictionary is a build error, not a silent no-op: a half-loaded table
     * would convert some words and not others, which is indistinguishable from a bug in the data.
     */
    private static Trie readGroup(String... dictionaries) {
        TrieBuilder builder = new TrieBuilder();
        for (int i = dictionaries.length - 1; i >= 0; i--) {
            String path = RESOURCE_DIR + dictionaries[i] + ".txt";
            try (InputStream stream = ChineseCharacterConverter.class.getResourceAsStream(path)) {
                if (stream == null) throw new IOException("Missing bundled dictionary " + path);
                builder.read(new InputStreamReader(stream, StandardCharsets.UTF_8), false);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot load Chinese character dictionary", error);
            }
        }
        return builder.build();
    }

    /** Compact immutable trie. Builder maps are discarded after loading a dictionary group. */
    static final class Trie {
        private final int[] firstEdge, edgeCounts, edgeCodePoints, edgeTargets;
        private final String[] values;
        private final boolean[] segmentationTerminals;

        Trie(int[] firstEdge, int[] edgeCounts, int[] edgeCodePoints, int[] edgeTargets,
             String[] values, boolean[] segmentationTerminals) {
            this.firstEdge = firstEdge;
            this.edgeCounts = edgeCounts;
            this.edgeCodePoints = edgeCodePoints;
            this.edgeTargets = edgeTargets;
            this.values = values;
            this.segmentationTerminals = segmentationTerminals;
        }

        Match match(int[] input, int start, int limit, boolean segmentationOnly) {
            int node = 0, matchedEnd = -1;
            String value = null;
            for (int i = start; i < limit; i++) {
                int edge = Arrays.binarySearch(edgeCodePoints,
                        firstEdge[node], firstEdge[node] + edgeCounts[node], input[i]);
                if (edge < 0) break;
                node = edgeTargets[edge];
                if (values[node] != null && (!segmentationOnly || segmentationTerminals[node])) {
                    matchedEnd = i + 1;
                    value = values[node];
                }
            }
            return matchedEnd < 0 ? null : new Match(matchedEnd, value);
        }
    }

    static final class TrieBuilder {
        private final ArrayList<Node> nodes = new ArrayList<>();

        TrieBuilder() {
            nodes.add(new Node(0));
        }

        void read(Reader reader, boolean segmentation) throws IOException {
            BufferedReader lines = new BufferedReader(reader);
            for (String line; (line = lines.readLine()) != null;) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int tab = line.indexOf('\t');
                if (tab <= 0 || line.indexOf('\t', tab + 1) >= 0) {
                    throw new IOException("Invalid Chinese character dictionary entry");
                }
                Node node = nodes.get(0);
                for (int cp : codePoints(line.substring(0, tab))) {
                    if (node.children == null) node.children = new HashMap<>();
                    Node child = node.children.get(cp);
                    if (child == null) {
                        child = new Node(nodes.size());
                        nodes.add(child);
                        node.children.put(cp, child);
                    }
                    node = child;
                }
                node.value = line.substring(tab + 1);
                node.segmentation |= segmentation;
            }
        }

        Trie build() {
            int count = nodes.size();
            int[] first = new int[count], sizes = new int[count];
            int[] points = new int[count - 1], targets = new int[count - 1];
            String[] values = new String[count];
            boolean[] segmentation = new boolean[count];
            int edge = 0;
            for (Node node : nodes) {
                first[node.id] = edge;
                values[node.id] = node.value;
                segmentation[node.id] = node.segmentation;
                if (node.children == null) continue;
                int[] keys = new int[node.children.size()];
                int keyIndex = 0;
                for (int key : node.children.keySet()) keys[keyIndex++] = key;
                Arrays.sort(keys);
                sizes[node.id] = keys.length;
                for (int key : keys) {
                    points[edge] = key;
                    targets[edge++] = node.children.get(key).id;
                }
            }
            return new Trie(first, sizes, points, targets, values, segmentation);
        }
    }

    private static final class Node {
        final int id;
        Map<Integer, Node> children;
        String value;
        boolean segmentation;

        Node(int id) {
            this.id = id;
        }
    }

    private static final class Match {
        final int end;
        final String value;

        Match(int end, String value) {
            this.end = end;
            this.value = value;
        }
    }

    private static int[] codePoints(String text) {
        int[] points = new int[text.codePointCount(0, text.length())];
        for (int cp = 0, offset = 0; cp < points.length; cp++) {
            points[cp] = text.codePointAt(offset);
            offset += Character.charCount(points[cp]);
        }
        return points;
    }

    private static int[] utf16Offsets(String text) {
        int[] offsets = new int[text.codePointCount(0, text.length()) + 1];
        for (int cp = 0; cp < offsets.length - 1; cp++) {
            offsets[cp + 1] = offsets[cp] + Character.charCount(text.codePointAt(offsets[cp]));
        }
        return offsets;
    }

    private static int unmatchedEnd(int[] input, int start, int limit) {
        int idsEnd = ideographicDescriptionEnd(input, start, limit);
        return idsEnd > start ? idsEnd : start + 1;
    }

    /** An ideographic description sequence is consumed as one unit so its parts are never split. */
    private static int ideographicDescriptionEnd(int[] input, int start, int limit) {
        int cp = input[start];
        int arity = cp >= 0x2ff0 && cp <= 0x2ff1 ? 2
                : cp >= 0x2ff2 && cp <= 0x2ff3 ? 3
                : cp >= 0x2ff4 && cp <= 0x2fff ? 2 : 0;
        if (arity == 0) return 0;
        int end = start + 1;
        for (int child = 0; child < arity; child++) {
            if (end >= limit) return 0;
            int childEnd = ideographicDescriptionEnd(input, end, limit);
            end = childEnd > end ? childEnd : end + 1;
        }
        return end;
    }
}

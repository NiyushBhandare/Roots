package com.atlas.roots.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Markdown parser tests for {@link ObsidianBridge}.
 *
 * <p>These exercise the package-private static helpers directly so we
 * don't need a real vault folder on disk &mdash; pure-string in, pure
 * value out.</p>
 */
class ObsidianBridgeTest {

    @Test
    void extractsH1AsName() {
        String md = "# Resonance Score\n\nSome body text.";
        assertEquals("Resonance Score", ObsidianBridge.extractH1(md).orElseThrow());
    }

    @Test
    void missingH1FallsBackToEmpty() {
        String md = "no heading here, just text.";
        assertTrue(ObsidianBridge.extractH1(md).isEmpty());
    }

    @Test
    void firstParagraphSkipsFrontMatter() {
        String md = """
                ---
                tags: [seen, product]
                ---
                # Title
                
                The actual first paragraph of content.
                """;
        String fp = ObsidianBridge.extractFirstParagraph(md);
        assertTrue(fp.startsWith("The actual first paragraph"),
                "first paragraph should skip frontmatter and headings, got: " + fp);
    }

    @Test
    void countsBacklinksCorrectly() {
        String md = "see [[Note A]] and [[Note B]] and again [[Note A]]";
        assertEquals(3, ObsidianBridge.countBacklinks(md));
    }

    @Test
    void countsZeroBacklinksOnPlainText() {
        assertEquals(0, ObsidianBridge.countBacklinks("plain prose with no links"));
    }

    @Test
    void wordCountIgnoresMarkdownSyntax() {
        String md = """
                # Heading
                
                A short paragraph with **bold** and *italic* words.
                """;
        // "A short paragraph with bold and italic words" = 8 words
        // plus "Heading" = 9 total, allow some slack for the parser
        int count = ObsidianBridge.countWords(md);
        assertTrue(count >= 8 && count <= 12,
                "expected ~8-12 words, got " + count);
    }

    @Test
    void wordCountStripsCodeBlocks() {
        String md = """
                Real prose here.
                
                ```java
                public class Foo { void bar() {} }
                ```
                
                More prose.
                """;
        int count = ObsidianBridge.countWords(md);
        // Should count "Real prose here More prose" = 5 words, not the code.
        assertTrue(count <= 8, "code block should not inflate word count, got " + count);
    }

    @Test
    void extractsHashtagTags() {
        String md = "Some text with #seen and #marketing tags inline.";
        String tags = ObsidianBridge.extractTags(md);
        assertTrue(tags.contains("seen"));
        assertTrue(tags.contains("marketing"));
    }

    @Test
    void extractsFrontMatterTags() {
        String md = """
                ---
                tags: [discipline, sleep, gym]
                ---
                # Lockdown
                content
                """;
        String tags = ObsidianBridge.extractTags(md);
        assertTrue(tags.contains("discipline"));
        assertTrue(tags.contains("sleep"));
        assertTrue(tags.contains("gym"));
    }

    @Test
    void hashtagWontMatchInUrls() {
        String md = "see https://example.com/path#section for details";
        String tags = ObsidianBridge.extractTags(md);
        assertFalse(tags.contains("section"),
                "URL fragments should not be parsed as tags, got: " + tags);
    }
}

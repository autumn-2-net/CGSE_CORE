package org.cgse.view;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

/** Headless desktop export, strip boundaries, opaque icon keys and complete SVG metadata. */
public final class DiagramExporterTest {

    private record Icon(String id) {}

    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("cgse-view-test-");
        try {
            var stripPng = directory.resolve("strips.png");
            StripPngWriter.write(stripPng, 41, 777, (graphics, top, rows) -> {
                for (int y = top; y < top + rows; y++) {
                    graphics.setColor(new Color((y * 71) & 0xFFFFFF));
                    graphics.fillRect(0, y, 41, 1);
                }
            });
            var decoded = ImageIO.read(stripPng.toFile());
            check(decoded.getWidth() == 41 && decoded.getHeight() == 777, "Strip dimensions changed");
            for (int y = 0; y < 777; y++) for (int x = 0; x < 41; x++)
                check((decoded.getRGB(x, y) & 0xFFFFFF) == ((y * 71) & 0xFFFFFF), "Pixel changed across strip boundary");

            var failed = directory.resolve("failed.png");
            try {
                StripPngWriter.write(failed, 41, 777, (graphics, top, rows) -> {
                    if (top > 0) throw new IllegalStateException("stop export");
                });
                throw new AssertionError("Renderer failure swallowed");
            } catch (IllegalStateException expected) {
                check(!Files.exists(failed), "Failed export advertised truncated file");
            }
            try (var files = Files.list(directory)) {
                check(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "Temporary PNG leaked");
            }

            var icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            var graphics = icon.createGraphics();
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 32, 32);
            graphics.dispose();
            var key = new Icon("host-specific-id");
            var nodes = List.of(new DiagramExporter.Node<>(0, 0, key, "Item <&>\"'", "9", "9007199254740993",
                            false, true, "", "NORMAL"),
                    new DiagramExporter.Node<>(80, 300, key, "Other", "2", "2", true, false, "1", "REFERENCE"));
            var png = directory.resolve("diagram.png");
            var svg = directory.resolve("diagram.svg");
            DiagramExporter.write("Graph <&>\"'", nodes, List.of(new DiagramExporter.Line(0, 0, 80, 300)),
                    Map.of(key, icon), true, png, svg);
            decoded = ImageIO.read(png.toFile());
            check(decoded.getWidth() == 272 && decoded.getHeight() == 740, "Diagram resolution changed");
            var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.toFile());
            check(xml.getElementsByTagName("image").getLength() == 1, "Shared icon embedded more than once");
            check(xml.getElementsByTagName("use").getLength() == 2, "Missing icon references");
            check(xml.getElementsByTagName("title").item(0).getTextContent().equals("Graph <&>\"'"), "SVG title escaping changed");
            String markup = Files.readString(svg);
            check(markup.contains("9007199254740993") && markup.contains("Retained seed") && markup.contains("Initial input: 1"), "Exact tooltip metadata lost");
            check(markup.contains("data:image/png;base64,"), "SVG depends on external icon file");
            System.out.println("DiagramExporterTest passed: 777 scanlines, failure cleanup, PNG and self-contained SVG");
        } finally {
            try (var files = Files.list(directory)) {
                for (var file : files.toList()) Files.deleteIfExists(file);
            }
            Files.delete(directory);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

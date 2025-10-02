package org.pitest.voices.fetch;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

@Mojo(name = "fetch", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)
public class FetchModelMojo extends AbstractMojo {

  @Parameter(property = "fetch.url", required = true)
  private String url;

  @Parameter(property = "fetch.extract", defaultValue = "true")
  private boolean extract;

  @Parameter(property = "fetch.skip", defaultValue = "false")
  private boolean skip;

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Override
  public void execute() {
    if (skip) {
      getLog().info("fetch: execution skipped");
      return;
    }
    Objects.requireNonNull(url, "fetch.url must be provided");

    try {
      Path outputDirectory = Files.createTempDirectory("fetch-plugin");
      Files.createDirectories(outputDirectory);

      // Determine filename
      String fileName = fileNameFromUrl(url);
      Path downloaded = outputDirectory.resolve(fileName);

      // Download
      download(url, downloaded);

      Path resourcePath;
      if (extract) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.bz2")) {
          resourcePath = extractTarBZ2(downloaded, outputDirectory);
        } else {
          throw new IllegalArgumentException("Unsupported archive format: " + fileName);
        }
      } else {
        resourcePath = downloaded.getParent();
      }

      // Register as resource
      addResource(resourcePath);

    } catch (Exception e) {
      throw new RuntimeException("Failed to fetch resources from URL: " + url, e);
    }
  }

  private void addResource(Path directory) throws IOException {
    Path target = project.getBasedir().toPath()
            .resolve("target")
            .resolve("generated-resources")
            .resolve("models");
    getLog().info("Moving downloaded files to: " + target);
    if (Files.exists(target)) {
      delete(target);
    }

    Files.createDirectories(target);
    Files.move(directory, target, StandardCopyOption.REPLACE_EXISTING);

    Resource r = new Resource();
    r.setDirectory(target.getParent().toAbsolutePath().toString());
    project.addResource(r);
    getLog().info("Added resource directory: " + r.getDirectory());
  }

  private void delete(Path target) throws IOException {
    try (Stream<Path> paths = Files.walk(target)) {
      paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
  }

  private static String fileNameFromUrl(String url) {
    int q = url.indexOf('?');
    String base = q >= 0 ? url.substring(0, q) : url;
    int slash = base.lastIndexOf('/');
    String name = slash >= 0 ? base.substring(slash + 1) : base;
    if (name.isBlank()) {
      return "download";
    }
    return name;
  }

  private void download(String urlStr, Path dest) throws IOException {
    getLog().info("Downloading " + urlStr + " -> " + dest);
    URL u = new URL(urlStr);
    URLConnection conn = u.openConnection();
    conn.setRequestProperty("User-Agent", "fetch-plugin/1.0 (maven)");
    conn.setConnectTimeout(30_000);
    conn.setReadTimeout(60_000);
    try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
      Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
    }
  }


  private Path extractTarBZ2(Path tarGzFile, Path outDir) throws IOException {
    String base = tarGzFile.getFileName().toString();
    base = base.endsWith(".tgz") ? base.substring(0, base.length() - 4) : base.substring(0, base.length() - 7);
    Path extractDir = outDir.resolve(base);
    Files.createDirectories(extractDir);
    getLog().info("Extracting tar.bz2 to " + extractDir);
    try (InputStream fin = Files.newInputStream(tarGzFile);
         BufferedInputStream bin = new BufferedInputStream(fin);
         CompressorInputStream gzIn = new CompressorStreamFactory().createCompressorInputStream(bin);
         TarArchiveInputStream tarIn = new TarArchiveInputStream(gzIn)) {
      ArchiveEntry entry;
      while ((entry = tarIn.getNextEntry()) != null) {
        Path outPath = extractDir.resolve(entry.getName()).normalize();
        ensureInside(extractDir, outPath);
        if (entry.isDirectory()) {
          Files.createDirectories(outPath);
        } else {
          Files.createDirectories(outPath.getParent());
          try (OutputStream os = Files.newOutputStream(outPath)) {
            tarIn.transferTo(os);
          }
        }
      }
    }
    return extractDir;
  }

  private static void ensureInside(Path root, Path child) throws IOException {
    if (!child.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
      throw new IOException("Blocked extracting entry outside target directory: " + child);
    }
  }
}

package org.araymond.joal.qbittorrent;

import org.springframework.http.ResponseEntity;
import org.araymond.joal.core.SeedManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

@RestController
@RequestMapping("/api/client-generator/qbittorrent")
public class QbittorrentClientGeneratorController {
    private final QbittorrentClientGeneratorService service;
    private final SeedManager seedManager;

    @Autowired
    public QbittorrentClientGeneratorController(QbittorrentClientGeneratorService service, SeedManager seedManager) {
        this.service = service;
        this.seedManager = seedManager;
    }

    @GetMapping("/versions")
    public List<String> listAvailableVersions() throws IOException {
        return service.fetchAvailableVersions();
    }

    @PostMapping
    public void generateClient(@RequestParam(required = false) String version) throws Exception {
        service.generateClientFile(version);
        // Rafraîchir la liste des fichiers clients côté front via websocket
        seedManager.getAppEventPublisher().publishEvent(
            new org.araymond.joal.core.events.config.ListOfClientFilesEvent(seedManager.listClientFiles())
        );
    }
}

@Service
class QbittorrentClientGeneratorService {
    private static final String TAGS_URL = "https://api.github.com/repos/qbittorrent/qBittorrent/tags";
    private static final String CLIENTS_DIR = "resources/clients/";

    public List<String> fetchAvailableVersions() throws IOException {
        RestTemplate rest = new RestTemplate();
        String json = rest.getForObject(TAGS_URL, String.class);
        List<String> tags = new ArrayList<>();
    Matcher m = Pattern.compile("\"name\":\\s*\"(.*?)\"").matcher(json);
        while (m.find()) tags.add(m.group(1));
        return tags;
    }

    public File generateClientFile(String version) throws Exception {
        // 1. Get tarball_url for the version
        RestTemplate rest = new RestTemplate();
        String json = rest.getForObject(TAGS_URL, String.class);
        String tarballUrl = null;
        if (version == null || version.isEmpty()) {
            Matcher m = Pattern.compile("\"tarball_url\":\\s*\"(.*?)\"").matcher(json);
            if (m.find()) tarballUrl = m.group(1);
        } else {
            Matcher m = Pattern.compile("\\{[^}]*\"name\":\\s*\"" + Pattern.quote(version) + "\"[^}]*\\}").matcher(json);
            if (m.find()) {
                Matcher t = Pattern.compile("\"tarball_url\":\\s*\"(.*?)\"").matcher(m.group());
                if (t.find()) tarballUrl = t.group(1);
            }
        }
        if (tarballUrl == null) throw new IllegalArgumentException("Version not found");

        // 2. Download and extract
        Path tempDir = Files.createTempDirectory("qbt-src");
    Path tarGz = tempDir.resolve("qbt.tar.gz");
    // Download tarball using HttpClient (Java 11+)
    java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
        .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
        .build();
    httpClient.followRedirects();
    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(tarballUrl))
        .build();
    java.net.http.HttpResponse<Path> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofFile(tarGz));
    if (response.statusCode() != 200) throw new IOException("Failed to download tarball: " + response.statusCode());
    unzipTarGz(tarGz, tempDir);
    Path srcRoot = findSrcRoot(tempDir);

        // 3. Parse version.h.in
        Path versionH = srcRoot.resolve("src/base/version.h.in");
        Map<String, String> versionMap = parseVersionH(versionH);
        String projectVersion = versionMap.get("QBT_VERSION_MAJOR") + "." + versionMap.get("QBT_VERSION_MINOR") + "." + versionMap.get("QBT_VERSION_BUGFIX");
        if (!"0".equals(versionMap.get("QBT_VERSION_BUILD"))) projectVersion += "." + versionMap.get("QBT_VERSION_BUILD");
        projectVersion += versionMap.getOrDefault("QBT_VERSION_STATUS", "");

        // 4. Parse session.cpp
        Path sessionCpp = srcRoot.resolve("src/base/bittorrent/sessionimpl.cpp");
        String userAgent = "qBittorrent/" + projectVersion;
        String peerIdPrefix = extractPeerIdPrefix(sessionCpp, versionMap);
        String keyFormat = "%08X"; // TODO: parse from libtorrent if needed

    // 5. Write .client file (advanced JSON format for JOAL v2)
    String clientFileName = "qbittorrent-" + projectVersion + ".client";
    Path clientFile = Paths.get(CLIENTS_DIR, clientFileName);
    Files.createDirectories(clientFile.getParent());
    String clientJson = "{\n" +
        "    \"keyGenerator\": {\n" +
        "        \"algorithm\": {\n" +
        "            \"type\": \"HASH_NO_LEADING_ZERO\",\n" +
        "            \"length\": 8\n" +
        "        },\n" +
        "        \"refreshOn\": \"TORRENT_PERSISTENT\",\n" +
        "        \"keyCase\": \"upper\"\n" +
        "    },\n" +
        "    \"peerIdGenerator\": {\n" +
        "        \"algorithm\": {\n" +
        "            \"type\": \"REGEX\",\n" +
        "            \"pattern\": \"" + peerIdPrefix + "[A-Za-z0-9_~\\\\(\\\\)\\\\!\\\\.\\\\*-]{12}\"\n" +
        "        },\n" +
        "        \"refreshOn\": \"NEVER\",\n" +
        "        \"shouldUrlEncode\": false\n" +
        "    },\n" +
        "    \"urlEncoder\": {\n" +
        "        \"encodingExclusionPattern\": \"[A-Za-z0-9_~\\\\(\\\\)\\\\!\\\\.\\\\*-]\",\n" +
        "        \"encodedHexCase\": \"lower\"\n" +
        "    },\n" +
        "    \"query\": \"info_hash={infohash}&peer_id={peerid}&port={port}&uploaded={uploaded}&downloaded={downloaded}&left={left}&corrupt=0&key={key}&event={event}&numwant={numwant}&compact=1&no_peer_id=1&supportcrypto=1&redundant=0\",\n" +
        "    \"numwant\": 200,\n" +
        "    \"numwantOnStop\": 0,\n" +
        "    \"requestHeaders\": [\n" +
        "        { \"name\": \"User-Agent\", \"value\": \"" + userAgent + "\" },\n" +
        "        { \"name\": \"Accept-Encoding\", \"value\": \"gzip\" },\n" +
        "        { \"name\": \"Connection\", \"value\": \"close\" }\n" +
        "    ]\n" +
        "}\n";
    Files.writeString(clientFile, clientJson);

        // 6. Cleanup
        deleteDirectory(tempDir);
        return clientFile.toFile();
    }

    private void unzipTarGz(Path tarGz, Path dest) throws IOException {
        // Décompression tar.gz avec Apache Commons Compress
        try (InputStream fi = Files.newInputStream(tarGz);
             java.util.zip.GZIPInputStream gzi = new java.util.zip.GZIPInputStream(fi);
             org.apache.commons.compress.archivers.tar.TarArchiveInputStream tarIn = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzi)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                Path entryPath = dest.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream out = Files.newOutputStream(entryPath)) {
                        tarIn.transferTo(out);
                    }
                }
            }
        }
    }

    private Path findSrcRoot(Path tempDir) throws IOException {
        // Find the root folder after extraction (first subdir)
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(tempDir)) {
            for (Path p : ds) if (Files.isDirectory(p)) return p;
        }
        throw new FileNotFoundException("No source root found");
    }

    private Map<String, String> parseVersionH(Path versionH) throws IOException {
        Map<String, String> map = new HashMap<>();
        List<String> lines = Files.readAllLines(versionH);
        for (String l : lines) {
            Matcher m = Pattern.compile("#define (QBT_VERSION_\\w+) (\\S+)").matcher(l);
            if (m.find()) map.put(m.group(1), m.group(2).replaceAll("\"", ""));
        }
        return map;
    }

    private String extractPeerIdPrefix(Path sessionCpp, Map<String, String> versionMap) throws IOException {
        // Génère le préfixe peer_id selon la logique libtorrent__compute_peer_id_prefix (8 caractères)
        // -qB + major + minor + bugfix + build (chiffre ou lettre) + '-'
        String smallName = "qB"; // qBittorrent small name
        int major = Integer.parseInt(versionMap.get("QBT_VERSION_MAJOR"));
        int minor = Integer.parseInt(versionMap.get("QBT_VERSION_MINOR"));
        int bugfix = Integer.parseInt(versionMap.get("QBT_VERSION_BUGFIX"));
        int build = Integer.parseInt(versionMap.get("QBT_VERSION_BUILD"));

        // version_to_char logic
        java.util.function.IntFunction<String> versionToChar = v -> {
            if (v >= 0 && v < 10) return Integer.toString(v);
            else return String.valueOf((char)('A' + (v - 10)));
        };

        StringBuilder prefix = new StringBuilder("-");
        prefix.append(smallName.substring(0, 2));
        prefix.append(versionToChar.apply(major));
        prefix.append(versionToChar.apply(minor));
        prefix.append(versionToChar.apply(bugfix));
        prefix.append(versionToChar.apply(build));
        prefix.append('-');
        // Résultat : 8 caractères
        return prefix.toString();
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
                for (Path p : ds) deleteDirectory(p);
            }
        }
        Files.delete(path);
    }
}

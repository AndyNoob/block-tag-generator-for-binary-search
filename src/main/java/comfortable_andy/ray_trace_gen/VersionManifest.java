package comfortable_andy.ray_trace_gen;

import java.util.List;

public record VersionManifest(LatestManifest latest, List<Version> versions) {

    public Version getVersion(String str) {
        return versions.stream().filter(v -> v.id.equals(str)).findFirst().orElse(null);
    }

    public record LatestManifest(String release, String snapshot) {}

    public record Version(String id, String type, String url, String time, String releaseTime) {}

}

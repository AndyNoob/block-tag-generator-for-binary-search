package comfortable_andy.ray_trace_gen;

import java.util.ArrayList;
import java.util.List;

public record VersionData(Downloads downloads, List<Library> libraries) {

    public record Downloads(Download server, Download client) {
        public record Download(String sha1, String url, int size) {
        }
    }

    public record Library(Downloads downloads, List<Rule> rules) {

        public boolean shouldDownload() {
            return rules().stream().allMatch(VersionData.Library.Rule::matches);
        }

        @Override
        public List<Rule> rules() {
            return rules == null ? new ArrayList<>() : rules;
        }

        public record Downloads(Artifact artifact) {
            public record Artifact(String path, String sha1, String url, int size) {
            }
        }

        public record Rule(String action, OperatingSystem os) {

            public record OperatingSystem(String name) {
            }

            public boolean matches() {
                return action.equalsIgnoreCase("allow") && System.getProperty("os.name").toLowerCase().replace(" ", "").contains(os.name);
            }

        }

    }

}
